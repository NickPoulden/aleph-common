;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.test.redis
  (:use
    [clojure test]
    [lamina core connections]
    [aleph redis]
    [aleph.test utils]))

(defmacro with-redis-client [[r & {:as options}] & body]
  `(let [~r (redis-client (merge {:host "localhost"} ~options))]
     (try
       ~@body
       (finally
         (close-connection ~r)))))

(defmacro with-redis-stream [[r & {:as options}] & body]
  `(let [~r (redis-stream (merge {:host "localhost"} ~options))]
     (try
       ~@body
       (finally
         (close-connection ~r)))))

;;;

(deftest ^:redis test-basic-commands
  (with-redis-client [r]
    (r [:set :a 1])
    (is (= "1" @(r [:get :a])))
    
    (r [:set :a "abc"])
    (is (= "abc" @(r [:get :a])))

    (is (thrown? Exception @(r [:get])))))

(deftest ^:redis test-db-selection
  (with-redis-client [r]
    (r [:select 1])

    (r [:set :a "foo"])

    (r [:select 2])
    @(r [:set :a "bar"])

    (reset-connection r)
    (is (= "bar" @(r [:get :a])))

    (r [:select 1])
    (is (= "foo" @(r [:get :a])))

    (reset-connection r)
    (is (= "foo" @(r [:get :a])))))

(deftest ^:redis test-task-handling

  (with-redis-client [r :heartbeat? false]
    (r [:del :q])
    (enqueue-task r :q [1 2 3])
    (is (= {:queue "q" :task [1 2 3]} @(receive-task r :a :b :c))))

  (with-redis-client [r]
    (is (thrown? AssertionError (receive-task r :q)))))

(deftest ^:redis test-pub-sub
  (with-redis-client [r]
    (with-redis-stream [s]

      (subscribe s :a)
      (Thread/sleep 500)

      (r [:publish :a "foo"])
      (is (= {:channel "a", :message "foo"} @(read-channel s)))

      (pattern-subscribe s "b*")
      (r [:publish :bar "baz"])
      (is (= {:channel "bar", :message "baz"} @(read-channel s)))

      (reset-connection s)
      (Thread/sleep 500)

      (r [:publish :a "foo"])
      (is (= {:channel "a", :message "foo"} @(read-channel s)))

      (r [:publish :bar "baz"])
      (is (= {:channel "bar", :message "baz"} @(read-channel s))))))

(deftest ^:redis test-task-channels
  (with-redis-client [r]
    (let [ch (task-channel {:host "localhost"} :q) 
          task {:foo "bar"}]
      (try
        (r [:del :q])
        (enqueue-task r :q task)
        (is (= {:queue "q" :task task} @(read-channel ch)))
        (finally
          (close ch))))))

;;;

(deftest ^:benchmark test-redis-roundtrip
  (with-redis-client [r]
    (bench "simple redis roundtrip"
      @(r [:ping]))
    (bench "1e3 redis roundtrips"
      @(apply merge-results (repeatedly 1e3 #(r [:ping]))))))
