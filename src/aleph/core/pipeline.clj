;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns
  ^{:skip-wiki true}
  aleph.core.pipeline
  (:use
    [clojure.contrib.def :only (defmacro- defvar)]
    [aleph.core.channel])
  (:import
    [org.jboss.netty.channel
     ChannelFuture
     ChannelFutureListener]))

;;;

(defvar *context* nil)

(defn- outer-result []
  (:outer-result *context*))

(defn- pipeline-error-handler []
  (:error-handler *context*))

(defn- current-pipeline []
  (:pipeline *context*))

(defn- initial-value []
  (:initial-value *context*))

(defmacro- with-context [context & body]
  `(binding [*context* ~context]
     ~@body))

(defn- tag= [x tag]
  (and
    (instance? clojure.lang.IMeta x)
    (-> x meta :tag (= tag))))

(defn pipeline? [x]
  (tag= x ::pipeline))

(defn redirect? [x]
  (tag= x ::redirect))

(defn pipeline-channel? [x]
  (tag= x ::pipeline-channel))

(defn pipeline-channel
  ([]
     (pipeline-channel
       (constant-channel)
       (constant-channel)))
  ([success-channel error-channel]
     ^{:tag ::pipeline-channel}
     {:success success-channel
      :error error-channel}))

;;;

(declare handle-result)

(defn- poll-pipeline-channel [chs fns context]
  ;;(println "poll-channels" (-> context :outer-result :error .hashCode))
  (receive (poll chs -1)
    (fn [[typ result]]
      (case typ
	:success
	(handle-result result fns context)
	:error
	(let [outer-error (-> context :outer-result :error)]
	  (if-not (pipeline-error-handler)
	    (enqueue outer-error result)
	    (let [possible-redirect (apply (pipeline-error-handler) result)]
	      (if (redirect? possible-redirect)
		(handle-result
		  (:value possible-redirect)
		  (-> possible-redirect :pipeline :stages)
		  (assoc context
		    :error-handler (-> possible-redirect :pipeline :error-handler)
		    :initial-value (:value possible-redirect)
		    :pipeline (-> possible-redirect :pipeline)))
		(enqueue outer-error result)))))))))

(defn- handle-result [result fns context]
  ;;(println "handle-result" result)
  (with-context context
    (cond
      (redirect? result)
      (recur
	(:value result)
	(-> result :pipeline :stages)
	(assoc context
	  :pipeline (:pipeline result)
	  :initial-value (:value result)
	  :error-handler (-> result :pipeline :error-handler)))
      (pipeline-channel? result)
      (poll-pipeline-channel result fns context)
      :else
      (let [{outer-success :success outer-error :error} (outer-result)]
	(if (empty? fns)
	  (enqueue outer-success result)
	  (let [f (first fns)]
	    (if (pipeline? f)
	      (poll-pipeline-channel (f result) (next fns) context)
	      (try
		(recur (f result) (next fns) context)
		(catch Exception e
		  ;;(.printStackTrace e)
		  (let [failure (pipeline-channel)]
		    (enqueue (:error failure) [result e])
		    (poll-pipeline-channel failure fns context)))))))))))

;;;

(defn redirect
  "When returned from a pipeline stage, redirects the execution flow.."
  [pipeline val]
  (when-not (pipeline? pipeline)
    (throw (Exception. "First parameter must be a pipeline.")))
  ^{:tag ::redirect}
  {:pipeline (-> pipeline meta :pipeline)
   :value val})

(defn restart
  "Redirects to the beginning of the current pipeline.  If no value is passed in, defaults
   to the value previously passed into the pipeline."
  ([]
     (restart (initial-value)))
  ([val]
     ^{:tag ::redirect}
     {:pipeline (current-pipeline)
      :value val}))

;;;

(defn- get-opts [opts+rest]
  (if (-> opts+rest first keyword?)
    (concat (take 2 opts+rest) (get-opts (drop 2 opts+rest)))
    nil))

(defn pipeline
  "Returns a function with an arity of one.  Invoking the function will return
   a pipeline channel.

   Stages should either be pipelines, or functions with an arity of one.  These functions
   should either return a pipeline channel, a redirect signal, or a value which will be passed
   into the next stage."
  [& opts+stages]
  (let [opts (apply hash-map (get-opts opts+stages))
	stages (drop (* 2 (count opts)) opts+stages)
	pipeline {:stages stages
		  :error-handler (:error-handler opts)}]
    ^{:tag ::pipeline
      :pipeline pipeline}
    (fn [x]
      (let [ch (pipeline-channel)]
	(handle-result
	  x
	  (:stages pipeline)
	  {:error-handler (:error-handler pipeline)
	   :pipeline pipeline
	   :outer-result ch
	   :initial-value x})
	ch))))

(defn run-pipeline
  "Equivalent to ((pipeline opts+stages) initial-value).

   Returns a pipeline future."
  [initial-value & opts+stages]
  ((apply pipeline opts+stages) initial-value))

(defn blocking
  "Takes a synchronous function, and returns a function which will be executed asynchronously,
   and whose invocation will return a pipeline channel."
  [f]
  (fn [x]
    (let [result (pipeline-channel)
	  {data :success error :error} result
	  context *context*]
      (future
	(with-context context
	  (try
	    (enqueue data (f x))
	    (catch Exception e
	      (enqueue error [x e])))))
      result)))

(defn to-pipeline-channel [ch]
  (pipeline-channel
    ch
    (reify AlephChannel
      (listen [_ _]))))

(defn receive-in-order [ch f]
  (run-pipeline ch
    to-pipeline-channel
    (fn [x]
      (f x)
      (when-not (closed? ch)
	(restart)))))

;;;

(defn wrap-netty-future [^ChannelFuture netty-future]
  (let [ch (pipeline-channel)]
    (.addListener netty-future
      (reify ChannelFutureListener
	(operationComplete [_ netty-future]
	  (println "complete!" (.isSuccess netty-future) (.getChannel netty-future))
	  (if (.isSuccess netty-future)
	    (enqueue (:success ch) (.getChannel netty-future))
	    (enqueue (:error ch) [nil (.getCause netty-future)]))
	  nil)))
    ch))
