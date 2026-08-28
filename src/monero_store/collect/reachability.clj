(ns monero-store.collect.reachability
  "Can the store reach the services it settles through?

  A telnet by hand answers this once, for one person, and leaves nothing
  behind. `probe` answers it as a value: which endpoint, which outcome, how
  long it took."
  (:require [malli.core :as m]
            [monero-store.adt :as adt]
            [monero-store.schema :as schema]
            [clojure.string :as str])
  (:import (java.io IOException)
           (java.net ConnectException InetSocketAddress Socket SocketTimeoutException
                     UnknownHostException)
[java.net URI]))

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

(def ^:private default-ports
  {"http" 80 "https" 443 "postgresql" 5432 "postgres" 5432 "mysql" 3306 "redis" 6379})

(defn endpoint-of-url
  "The endpoint `url` names, or nil when it names none.

  A URL with no explicit port takes its scheme's default. A JDBC URL is read
  through the driver scheme it wraps."
  [label url]
  (let [text (str/trim (str url))
        text (if (str/starts-with? text "jdbc:") (subs text 5) text)]
    (when-not (str/blank? text)
      (try
        (let [uri (URI. text)
              host (.getHost uri)
              port (if (pos? (.getPort uri))
                     (.getPort uri)
                     (get default-ports (str/lower-case (str (.getScheme uri)))))]
          (when (and (not (str/blank? (str host))) port)
            (cond-> {:endpoint/host host :endpoint/port (int port)}
              (not (str/blank? (str label))) (assoc :endpoint/label (str label)))))
        (catch Exception _ nil)))))

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

(defn report
  "Probe every endpoint and say whether this deployment can reach what it
  settles through.

  `:reach/ok?` is vacuously true for an empty list — a deployment that reaches
  nothing over the network has nothing to fail. `:reach/checked` is how a
  caller tells that apart from a healthy round."
  [prober endpoints timeout-ms]
  (let [checks (mapv #(probe prober % timeout-ms) endpoints)]
    {:reach/checked (count checks)
     :reach/unreachable (count (remove open? checks))
     :reach/ok? (every? open? checks)
     :reach/endpoints checks}))

(m/=> classify [:=> [:cat [:maybe [:fn #(instance? Throwable %)]]] schema/Reachability])
(m/=> blocked? [:=> [:cat schema/Reachability] :boolean])
(m/=> socket-probe [:=> [:cat] :any])
(m/=> fake-probe [:=> [:cat [:map-of [:tuple :string :int] :any]] :any])
(m/=> probe [:=> [:cat :any schema/Endpoint [:int {:min 1}]] schema/ReachabilityReport])
(m/=> open? [:=> [:cat schema/ReachabilityReport] :boolean])

(m/=> endpoint-of-url [:=> [:cat [:maybe :string] [:maybe :string]] [:maybe schema/Endpoint]])
(m/=> report [:=> [:cat :any [:sequential schema/Endpoint] [:int {:min 1}]] schema/ReachabilitySummary])
