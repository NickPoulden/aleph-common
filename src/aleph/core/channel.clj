;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.core.channel
  (:use [clojure.pprint])
  (:import
    [java.util.concurrent
     ScheduledThreadPoolExecutor
     TimeUnit
     TimeoutException]))

(defprotocol AlephChannel
  (listen [ch f]
    "Adds a callback which will receive all new messages.  If the callback returns true,
     the message will be consumed.  If not, it will remain in the channel.  A callback
     which returns false may see the same message multiple times.

     This exists to support poll, don't use it directly unless you know what you're doing.")
  (listen-all [ch f]
    "Same as listen, but for all messages that comes through the queue.")
  (receive [ch f]
    "Adds a callback which will receive the next message from the channel.")
  (receive-all [ch f]
    "Adds a callback which will receive all messages from the channel.")
  (cancel-callback [ch f]
    "Removes a permanent or transient callback from the channel.")
  (enqueue [ch msg]
    "Enqueues a message into the channel.")
  (enqueue-and-close [ch sg]
    "Enqueues the final message into the channel.  When this message is received,
     the channel will be closed.")
  (sealed? [ch]
    "Returns true if no further messages can be enqueued.")
  (closed? [ch]
    "Returns true if queue is sealed and there are no pending messages."))

(defn channel? [ch]
  (satisfies? AlephChannel ch))

;;;

(def delayed-executor (ScheduledThreadPoolExecutor. 1))

(defn delay-invoke [f delay]
  (.schedule ^ScheduledThreadPoolExecutor delayed-executor ^Runnable f (long delay) TimeUnit/MILLISECONDS))

;;;

(defn constant-channel
  "A channel which can hold zero or one messages in the queue.  Once it has
   a message, that message cannot be consumed.  Meant to communicate a single,
   constant value via a channel."
  []
  (let [result (ref nil)
	complete (ref false)
	listeners (ref #{})]
    ^{:type ::constant-channel}
    (reify AlephChannel
      (toString [_]
	(if @complete
	  (->> (with-out-str (pprint @result))
	    drop-last
	    (apply str))))
      (listen [this f]
	(let [value (dosync
		      (if @complete
			@result
			(do
			  (alter listeners conj f)
			  ::incomplete)))]
	  (when-not (= ::incomplete value)
	    (f value)))
	nil)
      (listen-all [this f]
	(listen this f))
      (receive-all [this f]
	(listen this f))
      (receive [this f]
	(listen this f))
      (cancel-callback [_ f]
	(dosync
	  (alter listeners disj f)))
      (enqueue [_ msg]
	(doseq [f (dosync
		    (when @complete
		      (throw (Exception. "Constant channel already contains a result.")))
		    (ref-set result msg)
		    (ref-set complete true)
		    (let [coll @listeners]
		      (ref-set listeners nil)
		      coll))]
	  (f msg))
	nil)
      (enqueue-and-close [_ _]
	(throw (Exception. "Cannot close constant-channel.")))
      (sealed? [_]
	@complete)
      (closed? [_]
	false))))

(defn channel
  "A basic implementation of a channel with an unbounded queue."
  []
  (let [messages (ref [])
	transient-receivers (ref #{})
	receivers (ref #{})
	transient-listeners (ref #{})
	listeners (ref #{})
	closed (ref false)
	sealed (ref false)
	test-listeners
	  (fn [messages listeners]
	    (some identity (doall (map #(% (first messages)) listeners))))
	send-to-listeners
	  (fn []
	    (try
	      (if (test-listeners @messages (concat @listeners @transient-listeners))
		(ref-set messages
		  (let [lst @listeners]
		    (loop [msgs (next @messages)]
		      (if (and msgs (test-listeners msgs lst))
			(recur (next msgs))
			(vec msgs)))))
		(ref-set closed false))
	      (finally
		(ref-set transient-listeners #{}))))
	send-to-all
	  (fn []
	    (try
	      (list*
		[(first @messages)
		 (concat
		   @receivers
		   @transient-receivers
		   @listeners
		   @transient-listeners)]
		(partition 2
		  (interleave
		    (rest (if @sealed (drop-last @messages) @messages))
		    (repeat (concat @receivers @listeners)))))
	      (finally
		(if-not (empty? @receivers)
		  (ref-set messages [])
		  (alter messages (comp vec (if @closed nnext next))))
		(ref-set transient-listeners #{})
		(ref-set transient-receivers #{}))))
	callbacks
	  (fn []
	    (dosync
	      (when-not (empty? @messages)
		(let [close (= ::close (last @messages))]
		  (when close
		    (ref-set closed true))
		  (try
		    (if-not (and (empty? @receivers) (empty? @transient-receivers))
		      (send-to-all)
		      (do
			(send-to-listeners)
			nil)))))))
	send-to-callbacks
	  (fn [callbacks]
	    (doseq [[msg fns] callbacks]
	      (doseq [f fns]
		(f msg))))
	assert-can-enqueue
	  (fn []
	    (when @sealed
	      (throw (Exception. "Can't enqueue into a sealed channel."))))
	assert-can-receive
	  (fn []
	    (when @closed
	      (throw (Exception. "Can't receive from a closed channel."))))]
    ^{:type ::channel}
    (reify AlephChannel
      Object
      (toString [_]
	(->> (with-out-str (pprint @messages))
	  drop-last
	  (apply str)))
      (receive-all [_ f]
	(send-to-callbacks
	  (dosync
	    (assert-can-receive)
	    (alter receivers conj f)
	    (callbacks))))
      (receive [this f]
	(send-to-callbacks
	  (dosync
	    (assert-can-receive)
	    (alter transient-receivers conj f)
	    (callbacks))))
      (listen [this f]
	(send-to-callbacks
	  (dosync
	    (assert-can-receive)
	    (alter transient-listeners conj f)
	    (callbacks))))
      (listen-all [this f]
	(send-to-callbacks
	  (dosync
	    (assert-can-receive)
	    (alter listeners conj f)
	    (callbacks))))
      (cancel-callback [_ f]
	(dosync
	  (alter listeners disj f)
	  (alter transient-listeners disj f)
	  (alter receivers disj f)
	  (alter transient-receivers disj f)))
      (enqueue [this msg]
	(send-to-callbacks
	  (dosync
	    (assert-can-enqueue)
	    (alter messages conj msg)
	    (callbacks))))
      (enqueue-and-close [_ msg]
	(send-to-callbacks
	  (dosync
	    (assert-can-enqueue)
	    (ref-set sealed true)
	    (alter messages concat [msg ::close])
	    (callbacks))))
      (sealed? [_]
	@sealed)
      (closed? [_]
	@closed))))

;;;

(defn- splice [a b]
  ^{:type ::channel}
  (reify AlephChannel
    (toString [_]
      (str a))
    (receive [_ f]
      (receive a f))
    (receive-all [_ f]
      (receive-all a f))
    (listen [_ f]
      (listen a f))
    (listen-all [_ f]
      (listen-all a f))
    (cancel-callback [_ f]
      (cancel-callback a f))
    (closed? [_]
      (closed? a))
    (sealed? [_]
      (sealed? b))
    (enqueue [_ msg]
      (enqueue b msg))
    (enqueue-and-close [_ msg]
      (enqueue-and-close b msg))))

(defn channel-pair
  "Creates paired channels, where an enqueued message from one channel
   can be received from the other."
  []
  (let [a (channel)
	b (channel)]
    [(splice a b) (splice b a)]))

(defn poll
  ([channel-map]
     (poll channel-map 0))
  ([channel-map timeout]
     (let [received (atom 0)
	   result-channel (constant-channel)
	   enqueue-fn
	   (fn [k]
	     #(when (compare-and-set! received 0 1) 
		(enqueue result-channel (when k [k %]))
		true))]
       (doseq [[k ch] channel-map]
	 (listen ch (enqueue-fn k)))
       (when (zero? timeout)
	 ((enqueue-fn nil) nil))
       (when (< 0 timeout)
	 (delay-invoke #((enqueue-fn nil) nil) timeout))
       result-channel)))

(defn channel-seq
  ([ch]
     (channel-seq ch 0))
  ([ch timeout]
     (lazy-seq
       (when-not (closed? ch)
	 (let [value (promise)]
	   (receive (poll {:ch ch} timeout)
	     #(deliver value
		(when (first %)
		  [(second %)])))
	   (when @value
	     (concat @value (channel-seq ch timeout))))))))

(defn wait-for-message
  ([ch]
     (wait-for-message ch -1))
  ([ch timeout]
     (let [msg (take 1 (channel-seq ch timeout))]
       (if (empty? msg)
	 (throw (TimeoutException. "Timed out waiting for message from channel."))
	 (first msg)))))

;;;

(defmethod print-method ::channel [ch writer]
  (.write writer (str "<== " (.toString ch))))

(defmethod print-method ::constant-channel [ch writer]
  (let [s (.toString ch)]
    (.write writer (str "<== [" s (when-not (empty? s) " ...") "]"))))
