(ns monero-store.collect.http
  "Outbound HTTP as a port, so every adapter above it is testable with a map.

  One shape in — {:http/method :http/url :http/headers :http/body} — and one
  shape out: {:http/status :http/body}, the body already parsed when it was
  JSON. Only this namespace knows which client library is on the classpath."
  (:require [hato.client :as hato]
            [jsonista.core :as json]
            [taoensso.timbre :as log]))

(defprotocol IHttp
  (request [this req]
    "Perform `req`. Returns {:http/status :http/body}.

    A transport failure is a status of 0 and a nil body: an adapter deciding
    what an unreachable service means is doing its job, and an exception
    crossing this port would make every caller do it with try/catch."))

(def ^:private mapper
  (json/object-mapper {:decode-key-fn true}))

(defn parse-json
  "`body` as parsed JSON, or nil when it is not JSON at all."
  [body]
  (when (and (string? body) (seq body))
    (try (json/read-value body mapper)
         (catch Exception _ nil))))

(defn ok?
  "True for a 2xx response."
  [{:http/keys [status]}]
  (<= 200 (long (or status 0)) 299))

(defn hato-client
  "IHttp over hato. `:timeout-ms` bounds every call; a store must not block on
  a ticker or a wallet gateway forever."
  [{:keys [timeout-ms] :or {timeout-ms 10000}}]
  (reify IHttp
    (request [_ {:http/keys [method url headers body form]}]
      (try
        (let [response (hato/request
                        (cond-> {:method (or method :get)
                                 :url url
                                 :as :string
                                 :throw-exceptions? false
                                 :timeout timeout-ms
                                 :headers (merge {"accept" "application/json"} headers)}
                          body (assoc :content-type :json
                                      :body (json/write-value-as-string body))
                          form (assoc :form-params form)))]
          {:http/status (:status response)
           :http/body (or (parse-json (:body response)) (:body response))})
        (catch Exception e
          (log/warn "upstream unreachable" {:url url :error (.getMessage e)})
          {:http/status 0 :http/body nil})))))

(defn stub-client
  "IHttp from a function of the request. The whole test seam.

  `respond-fn` returns {:http/status :http/body}, or nil for \"unreachable\"."
  [respond-fn]
  (reify IHttp
    (request [_ req]
      (or (respond-fn req) {:http/status 0 :http/body nil}))))
