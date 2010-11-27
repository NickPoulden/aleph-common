;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.redis.protocol
  (:use
    [lamina core]
    [gloss core]))

(defn string-prefix [count-offset]
  (prefix (string-integer :ascii :delimiters ["\r\n"])
    #(if (neg? %) 0 (+ % count-offset))
    #(if-not % -1 (- % count-offset))))

(def format-byte
  (enum :byte
    {:error \-
     :single-line \+
     :integer \:
     :bulk \$
     :multi-bulk \*}))

(defn codec-map [charset]
  (let [str-codec (string charset :delimiters ["\r\n"])
	bulk (compile-frame [:bulk (finite-frame (string-prefix 2) str-codec)])
	m {:error str-codec
	   :single-line str-codec
	   :integer (string-integer :ascii :delimiters ["\r\n"])
	   :multi-bulk (repeated
			 (header format-byte (constantly bulk) (constantly :bulk))
			 :prefix (string-prefix 0))}
	m (into {}
	    (map
	      (fn [[k v]] [k (compile-frame [k v])])
	      m))]
    (assoc m :bulk bulk)))

(defn redis-codec [charset]
  (let [codecs (codec-map charset)]
    (header format-byte codecs first)))

(defn process-response [rsp]
  (case (first rsp)
    :error (str "ERROR: " (second rsp))
    :multi-bulk (map second (second rsp))
    (second rsp)))

(defn process-request [req]
  [:multi-bulk (map #(list :bulk %) req)])
