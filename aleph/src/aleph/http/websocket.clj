;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns aleph.http.websocket
  (:use
    [aleph.http core])
  (:import
    [org.jboss.netty.handler.codec.http.websocket
     DefaultWebSocketFrame
     WebSocketFrame]
    [org.jboss.netty.handler.codec.http
     HttpRequest
     HttpResponse
     DefaultHttpResponse
     DefaultHttpRequest
     HttpVersion
     HttpResponseStatus]
    [org.jboss.netty.buffer
     ChannelBuffers]
    [java.nio
     ByteBuffer]
    [java.security
     MessageDigest]))

(defn md5-hash [buf]
  (->> buf
    .array
    (.digest (MessageDigest/getInstance "MD5"))
    ByteBuffer/wrap))

(defn from-websocket-frame [^WebSocketFrame frame]
  (.getTextData frame))

(defn to-websocket-frame [msg]
  (DefaultWebSocketFrame. msg))

(defn websocket-handshake? [^HttpRequest request]
  (and
    (= "upgrade" (.toLowerCase (.getHeader request "connection")))
    (= "websocket" (.toLowerCase (.getHeader request "upgrade")))))

(defn transform-key [k]
  (/
    (-> k (.replaceAll "[^0-9]" "") Long/parseLong)
    (-> k (.replaceAll "[^ ]" "") .length)))

(defn secure-websocket-response [request]
  (let [headers (:headers request)]
    {:status 101
     :headers {:Sec-WebSocket-Origin (headers "origin")
	       :Sec-WebSocket-Location (str "ws://" (headers "host") "/")
	       :Sec-WebSocket-Protocol (headers "sec-websocket-protocol")}
     :body (md5-hash
	     (doto (ByteBuffer/allocate 16)
	       (.putInt (transform-key (headers "sec-websocket-key1")))
	       (.putInt (transform-key (headers "sec-websocket-key2")))
	       (.putLong (-> request :body .getLong))))}))

(defn standard-websocket-response [request]
  (let [headers (:headers request)]
    {:status 101
     :headers {:WebSocket-Origin (headers "origin")
	       :WebSocket-Location (str "ws://" (headers "host") "/")
	       :WebSocket-Protocol (headers "websocket-protocol")}}))

'(defn secure-websocket-response [request headers ^HttpResponse response]
  (.addHeader response "Sec-WebSocket-Origin" (headers "origin"))
  (.addHeader response "Sec-WebSocket-Location" (str "ws://" (headers "host") "/"))
  (when-let [protocol (headers "sec-websocket-protocol")]
    (.addHeader response "Sec-WebSocket-Protocol" protocol))
  (let [buf (ChannelBuffers/buffer 16)]
    (doto buf
      (.writeInt (transform-key (headers "sec-websocket-key1")))
      (.writeInt (transform-key (headers "sec-websocket-key2")))
      (.writeLong (-> request .getContent .getLong)))
    (.setContent response
      (-> (MessageDigest/getInstance "MD5")
	(.digest (.array buf))
	ChannelBuffers/wrappedBuffer))))

'(defn standard-websocket-response [request headers ^HttpResponse response]
  (.addHeader response "WebSocket-Origin" (headers "origin"))
  (.addHeader response "WebSocket-Location" (str "ws://" (headers "host") "/"))
  (when-let [protocol (headers "websocket-protocol")]
    (.addHeader response "WebSocket-Protocol" protocol)))

(defn websocket-response [^HttpRequest request]
  (.setHeader request "content-type" "application/octet-stream")
  (let [request (transform-netty-request request)
	headers (:headers request)
	response (if (and (headers "sec-websocket-key1") (headers "sec-websocket-key2"))
		   (secure-websocket-response request)
		   (standard-websocket-response request))]
    (transform-aleph-response
      (update-in response [:headers]
	#(assoc %
	   :Upgrade "WebSocket"
	   :Connection "Upgrade"))
      nil)))
