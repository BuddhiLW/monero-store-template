(ns monero-store.collect.reachability
  "Can the store reach the services it settles through?

  A telnet by hand answers this once, for one person, and leaves nothing
  behind. `probe` answers it as a value: which endpoint, which outcome, how
  long it took."
  (:require [malli.core :as m]
            [monero-store.adt :as adt]
            [monero-store.schema :as schema])
  (:import (java.io IOException)
           (java.net ConnectException InetSocketAddress Socket SocketTimeoutException
                     UnknownHostException)))

(defprotocol IEndpointProbe
  (attempt [this host port timeout-ms]
    "Open a TCP connection and close it. Returns nil on success, or the
    Throwable that stopped it."))

;; ---------------------------------------------------------------------------
;; promote — pure

(defn classify
  "The Reachability variant `failure` means. Nil failure is `:reach/open`."
  [failure]
  (adt/reachability
   (if (nil? failure)
     :reach/open
     (condp instance? failure
       SocketTimeoutException :reach/timeout
       ConnectException       :reach/refused
       UnknownHostException   :reach/unknown-host
       :reach/error))))

(defn blocked?
  "True when `outcome` is a path that never answered, as opposed to a service
  that answered by refusing."
  [outcome]
  (= :reach/timeout (:adt/variant outcome)))

;; ---------------------------------------------------------------------------
;; boundary

(defn socket-probe
  "IEndpointProbe over a real TCP connect."
  []
  (reify IEndpointProbe
    (attempt [_ host port timeout-ms]
      (try
        (with-open [socket (Socket.)]
          (.connect socket (InetSocketAddress. ^String host (int port)) (int timeout-ms)))
        nil
        (catch IOException e e)
        (catch Exception e e)))))

(defn fake-probe
  "IEndpointProbe over a map of [host port] -> Throwable-or-nil."
  [outcomes]
  (reify IEndpointProbe
    (attempt [_ host port _timeout-ms]
      (get outcomes [host (long port)]))))

;; ---------------------------------------------------------------------------
;; facade

(defn probe
  "Reach `endpoint` through `prober` and report what happened.

  `endpoint` is {:endpoint/host :endpoint/port :endpoint/label}. Never throws:
  an unreachable service is a value, because the caller's job is to report it."
  [prober {:endpoint/keys [host port label] :as _endpoint} timeout-ms]
  (let [started (System/currentTimeMillis)
        failure (attempt prober host port timeout-ms)]
    {:reach/label (or label (str host ":" port))
     :reach/host host
     :reach/port port
     :reach/outcome (classify failure)
     :reach/elapsed-ms (- (System/currentTimeMillis) started)
     :reach/detail (some-> failure .getMessage)}))

(defn open?
  "True when `report` says the endpoint accepted a connection."
  [report]
  (= :reach/open (:adt/variant (:reach/outcome report))))

(m/=> classify [:=> [:cat [:maybe [:fn #(instance? Throwable %)]]] schema/Reachability])
(m/=> blocked? [:=> [:cat schema/Reachability] :boolean])
(m/=> socket-probe [:=> [:cat] :any])
(m/=> fake-probe [:=> [:cat [:map-of [:tuple :string :int] :any]] :any])
(m/=> probe [:=> [:cat :any schema/Endpoint [:int {:min 1}]] schema/ReachabilityReport])
(m/=> open? [:=> [:cat schema/ReachabilityReport] :boolean])
