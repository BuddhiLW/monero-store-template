(ns monero-store.e2e.daemon-test
  "The telnet question against REAL daemons.

  A `ServerSocket` accepts and says nothing, so the socket e2e proves the
  probe and not the deployment. monerod, monero-wallet-rpc, MoneroPay and
  postgres each bind their port on their own schedule and each speak a
  protocol. This suite probes those processes, brought up by

    docker compose -f docker-compose.yml -f docker-compose.integration.yml up -d

  and is tagged ^:integration so a cold `clojure -M:test` never depends on a
  container runtime."
  (:require [clojure.test :refer [deftest is testing]]
            [hato.client :as hc]
            [jsonista.core :as json]
            [monero-store.collect.reachability :as reach]
            [monero-store.system :as system])
  (:import (java.net ServerSocket)))

(def ^:private mapper (json/object-mapper {:decode-key-fn true}))

(def ^:private admin-token "integration-operator-token")

(defn- env-port
  [k default]
  (or (some-> (System/getenv k) parse-long) default))

(def ^:private daemons
  "The published loopback ports of docker-compose.integration.yml."
  [{:endpoint/host "127.0.0.1" :endpoint/port (env-port "IT_POSTGRES_PORT" 55432)
    :endpoint/label "postgres"}
   {:endpoint/host "127.0.0.1" :endpoint/port (env-port "IT_MONEROD_PORT" 38081)
    :endpoint/label "monerod"}
   {:endpoint/host "127.0.0.1" :endpoint/port (env-port "IT_WALLET_RPC_PORT" 38083)
    :endpoint/label "wallet-rpc"}
   {:endpoint/host "127.0.0.1" :endpoint/port (env-port "IT_MONEROPAY_PORT" 5000)
    :endpoint/label "moneropay"}])

(defn- free-port
  "A port nothing is listening on."
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- ask
  [port]
  (let [response (hc/get (str "http://127.0.0.1:" port "/api/admin/reachability")
                         {:throw-exceptions false
                          :headers {"authorization" (str "Bearer " admin-token)}})]
    [(:status response) (json/read-value (str (:body response)) mapper)]))

(defn- with-store
  [endpoints f]
  (let [port (free-port)
        system (system/start! {:port port
                               :admin-token admin-token
                               :endpoints endpoints
                               :reachability {:timeout-ms 3000}})]
    (try (f port) (finally (system/stop! system)))))

(deftest ^:integration every-daemon-the-stack-defines-is-reachable
  (let [summary (reach/report (reach/socket-probe) daemons 3000)]
    (testing "the compose stack is up and every port answers"
      (is (true? (:reach/ok? summary))
          (str "unreachable: "
               (pr-str (into [] (comp (remove reach/open?)
                                      (map (juxt :reach/label
                                                 #(:adt/variant (:reach/outcome %)))))
                             (:reach/endpoints summary)))))
      (is (= 4 (:reach/checked summary)))
      (is (zero? (:reach/unreachable summary))))))

(deftest ^:integration the-store-reports-ready-over-http-against-real-daemons
  (with-store daemons
    (fn [port]
      (let [[status body] (ask port)]
        (is (= 200 status))
        (is (true? (:ok body)))
        (is (= 4 (:checked body)))
        (is (= 0 (:unreachable body)))
        (testing "and every daemon is named open, by label"
          (is (= {"postgres" "open" "monerod" "open"
                  "wallet-rpc" "open" "moneropay" "open"}
                 (into {} (map (juxt :label :outcome)) (:endpoints body)))))))))

(deftest ^:integration one-dead-port-among-live-daemons-fails-the-report
  (let [dead {:endpoint/host "127.0.0.1" :endpoint/port (free-port)
              :endpoint/label "dead"}]
    (with-store (conj daemons dead)
      (fn [port]
        (let [[status body] (ask port)]
          (is (= 503 status))
          (is (false? (:ok body)))
          (is (= 1 (:unreachable body)))
          (testing "and the report names WHICH one, so an operator is not left guessing"
            (is (= ["dead"]
                   (into [] (comp (remove #(= "open" (:outcome %))) (map :label))
                         (:endpoints body))))))))))

(deftest ^:integration an-open-port-is-not-proof-the-daemon-speaks-its-protocol
  (testing "reachability answers `the port answers`, never `the right thing answers`"
    (let [health (hc/get (str "http://127.0.0.1:" (env-port "IT_MONEROPAY_PORT" 5000) "/health")
                         {:throw-exceptions false})
          body (json/read-value (str (:body health)) mapper)]
      (is (= 200 (:status health)))
      (testing "MoneroPay's own health check reaches FURTHER than a TCP connect does"
        (is (true? (get-in body [:services :walletrpc])))
        (is (true? (get-in body [:services :postgresql])))))))
