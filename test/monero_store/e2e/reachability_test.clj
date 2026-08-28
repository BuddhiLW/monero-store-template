(ns monero-store.e2e.reachability-test
  "The telnet question, end to end.

  A real HTTP request over a real socket to a real server, which opens a real
  TCP connection to a port something is listening on and to a port nothing is."
  (:require [clojure.test :refer [deftest is testing]]
            [hato.client :as hc]
            [jsonista.core :as json]
            [monero-store.system :as system])
  (:import (java.net ServerSocket)))

(def ^:private mapper (json/object-mapper {:decode-key-fn true}))

(def ^:private admin-token "e2e-operator-token")

(defn- free-port
  "A port nothing is listening on."
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- endpoint
  [port label]
  {:endpoint/host "127.0.0.1" :endpoint/port port :endpoint/label label})

(defn- ask
  [port & {:keys [operator]}]
  (let [response (hc/get (str "http://127.0.0.1:" port "/api/admin/reachability")
                         (cond-> {:throw-exceptions false}
                           operator (assoc :headers {"authorization" (str "Bearer " operator)})))]
    [(:status response) (json/read-value (str (:body response)) mapper)]))

(defn- with-store
  [endpoints f]
  (let [port (free-port)
        system (system/start! {:port port
                               :admin-token admin-token
                               :endpoints endpoints
                               :reachability {:timeout-ms 500}})]
    (try (f port) (finally (system/stop! system)))))

(deftest a-live-listener-and-a-dead-port-answer-differently-over-http
  (with-open [listener (ServerSocket. 0)]
    (let [live (.getLocalPort listener)
          dead (free-port)]
      (with-store [(endpoint live "live") (endpoint dead "dead")]
        (fn [port]
          (let [[status body] (ask port :operator admin-token)]
            (testing "one endpoint could not be reached, so the store is not ready"
              (is (= 503 status))
              (is (false? (:ok body)))
              (is (= 2 (:checked body)))
              (is (= 1 (:unreachable body))))

            (testing "and the report names which, and how it failed"
              (is (= {"live" "open" "dead" "refused"}
                     (into {} (map (juxt :label :outcome)) (:endpoints body)))))

            (testing "each probe reports how long it took"
              (is (every? #(nat-int? (:elapsed-ms %)) (:endpoints body))))))))))

(deftest a-store-that-reaches-everything-it-needs-is-ready
  (with-open [listener (ServerSocket. 0)]
    (with-store [(endpoint (.getLocalPort listener) "live")]
      (fn [port]
        (let [[status body] (ask port :operator admin-token)]
          (is (= 200 status))
          (is (true? (:ok body)))
          (is (= 1 (:checked body)))
          (is (zero? (:unreachable body))))))))

(deftest the-hosts-a-store-talks-to-are-not-public
  (with-open [listener (ServerSocket. 0)]
    (with-store [(endpoint (.getLocalPort listener) "live")]
      (fn [port]
        (testing "an unauthenticated caller learns nothing about the deployment"
          (let [[status body] (ask port)]
            (is (= 401 status))
            (is (nil? (:endpoints body)))))))))

(deftest liveness-still-answers-while-a-dependency-is-down
  (with-store [(endpoint (free-port) "dead")]
    (fn [port]
      (testing "/healthz is the process, /api/admin/reachability is the deployment"
        (is (= 200 (:status (hc/get (str "http://127.0.0.1:" port "/healthz")
                                    {:throw-exceptions false}))))
        (is (= 503 (first (ask port :operator admin-token))))))))
