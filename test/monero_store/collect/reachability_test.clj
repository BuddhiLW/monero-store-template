(ns monero-store.collect.reachability-test
  "Reaching a service, and saying which way it failed."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.collect.reachability :as reach]
            [monero-store.schema :as schema])
  (:import (java.net ConnectException ServerSocket SocketTimeoutException
                     UnknownHostException)))

(defn- endpoint
  ([host port] (endpoint host port nil))
  ([host port label]
   (cond-> {:endpoint/host host :endpoint/port port}
     label (assoc :endpoint/label label))))

(deftest a-failure-names-which-failure-it-was
  (testing "the distinction telnet makes by hand, as a value"
    (is (= :reach/open (:adt/variant (reach/classify nil))))
    (is (= :reach/timeout (:adt/variant (reach/classify (SocketTimeoutException. "timed out")))))
    (is (= :reach/refused (:adt/variant (reach/classify (ConnectException. "refused")))))
    (is (= :reach/unknown-host (:adt/variant (reach/classify (UnknownHostException. "nope")))))
    (is (= :reach/error (:adt/variant (reach/classify (IllegalStateException. "other")))))))

(deftest a-blocked-path-is-not-a-refusing-service
  (testing "a refusal answered; a timeout never did, and that is the operator's clue"
    (is (true? (reach/blocked? (reach/classify (SocketTimeoutException. "timed out")))))
    (is (false? (reach/blocked? (reach/classify (ConnectException. "refused")))))
    (is (false? (reach/blocked? (reach/classify nil))))))

(deftest a-report-is-a-value-not-a-throw
  (let [prober (reach/fake-probe {["blocked.invalid" 9505] (SocketTimeoutException. "timed out")
                                  ["down.invalid" 9505] (ConnectException. "refused")})]
    (testing "an unreachable service is reported, never raised"
      (let [report (reach/probe prober (endpoint "blocked.invalid" 9505 "xmr") 50)]
        (is (= report (schema/check! schema/ReachabilityReport report)))
        (is (= :reach/timeout (:adt/variant (:reach/outcome report))))
        (is (false? (reach/open? report)))
        (is (= "xmr" (:reach/label report)))))

    (testing "and a refusal is a different report, not the same failure"
      (is (= :reach/refused
             (:adt/variant (:reach/outcome (reach/probe prober (endpoint "down.invalid" 9505) 50))))))

    (testing "an endpoint the fake does not name answered"
      (let [report (reach/probe prober (endpoint "fine.invalid" 9505) 50)]
        (is (true? (reach/open? report)))
        (is (= "fine.invalid:9505" (:reach/label report)))))))

(deftest a-real-socket-reaches-a-real-listener
  (with-open [server (ServerSocket. 0)]
    (let [port (.getLocalPort server)
          report (reach/probe (reach/socket-probe) (endpoint "127.0.0.1" port "local") 2000)]
      (testing "a port something is listening on reports open"
        (is (= report (schema/check! schema/ReachabilityReport report)))
        (is (true? (reach/open? report)))
        (is (nat-int? (:reach/elapsed-ms report)))))))

(deftest a-real-socket-refuses-a-dead-port
  (let [port (with-open [server (ServerSocket. 0)] (.getLocalPort server))
        report (reach/probe (reach/socket-probe) (endpoint "127.0.0.1" port) 2000)]
    (testing "a port nothing is listening on reports refused, not open"
      (is (false? (reach/open? report)))
      (is (= :reach/refused (:adt/variant (:reach/outcome report)))))))
