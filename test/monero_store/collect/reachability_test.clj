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

(deftest a-url-names-a-host-and-a-port
  (testing "an explicit port is taken as given"
    (is (= {:endpoint/host "moneropay" :endpoint/port 5000 :endpoint/label "moneropay"}
           (reach/endpoint-of-url "moneropay" "http://moneropay:5000"))))

  (testing "a scheme's default port stands in for an absent one"
    (is (= 443 (:endpoint/port (reach/endpoint-of-url "cards" "https://api.stripe.com"))))
    (is (= 80 (:endpoint/port (reach/endpoint-of-url "analytics" "http://umami.invalid/api")))))

  (testing "a JDBC URL is read through the driver scheme it wraps"
    (is (= {:endpoint/host "postgres" :endpoint/port 5432 :endpoint/label "database"}
           (reach/endpoint-of-url "database" "jdbc:postgresql://postgres:5432/store")))
    (is (= 5432 (:endpoint/port (reach/endpoint-of-url "database" "jdbc:postgresql://pg/store")))))

  (testing "a url that names no reachable host names no endpoint"
    (is (nil? (reach/endpoint-of-url "x" nil)))
    (is (nil? (reach/endpoint-of-url "x" "")))
    (is (nil? (reach/endpoint-of-url "x" "not a url")))
    (is (nil? (reach/endpoint-of-url "x" "ftp://host.invalid/f"))))

  (testing "what it does return is an Endpoint"
    (let [found (reach/endpoint-of-url "moneropay" "http://moneropay:5000")]
      (is (= found (schema/check! schema/Endpoint found))))))

(deftest a-round-of-probes-names-what-could-not-be-reached
  (let [prober (reach/fake-probe {["down.invalid" 5000] (ConnectException. "refused")})
        summary (reach/report prober
                              [(endpoint "up.invalid" 5000 "up")
                               (endpoint "down.invalid" 5000 "down")]
                              50)]
    (is (= summary (schema/check! schema/ReachabilitySummary summary)))
    (is (= 2 (:reach/checked summary)))
    (is (= 1 (:reach/unreachable summary)))
    (is (false? (:reach/ok? summary)))
    (testing "and says which one, because that is the whole answer"
      (is (= ["down"] (mapv :reach/label (remove reach/open? (:reach/endpoints summary))))))))

(deftest a-deployment-that-reaches-nothing-has-nothing-to-fail
  (let [summary (reach/report (reach/fake-probe {}) [] 50)]
    (is (true? (:reach/ok? summary)))
    (testing "and reports the count, so an empty round is not read as a healthy one"
      (is (zero? (:reach/checked summary))))))
