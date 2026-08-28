(ns monero-store.system-test
  "What a deployment's own configuration says it must be able to reach."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.schema :as schema]
            [monero-store.system :as system]))

(deftest a-rail-that-is-not-configured-is-not-probed
  (is (= [] (system/endpoints {:chain {:backend :none}
                               :cards {:backend :none}
                               :store {:backend :memory}
                               :analytics {:backend :none}})))
  (testing "nor is a fake one, which reaches nothing over a network"
    (is (= [] (system/endpoints {:chain {:backend :fake
                                         :moneropay-url "http://moneropay:5000"}})))))

(deftest the-endpoints-follow-the-config-the-adapters-are-wired-from
  (let [found (system/endpoints
               {:chain {:backend :moneropay :moneropay-url "http://moneropay:5000"}
                :store {:backend :jdbc :jdbc-url "jdbc:postgresql://postgres:5432/store"}
                :cards {:backend :stripe :api-base "https://api.stripe.test"}
                :analytics {:backend :umami :base-url "http://umami:3000"}})]
    (is (= ["moneropay" "database" "cards" "analytics"] (mapv :endpoint/label found)))
    (is (= [5000 5432 443 3000] (mapv :endpoint/port found)))
    (is (= found (schema/check! [:vector schema/Endpoint] found)))

    (testing "moving the service moves the probe: there is no second list of hosts"
      (is (= 6000 (:endpoint/port
                   (first (system/endpoints {:chain {:backend :moneropay
                                                     :moneropay-url "http://elsewhere:6000"}}))))))))

(deftest the-wallet-rpc-rail-is-probed-at-the-uri-it-was-given
  (is (= [{:endpoint/host "wallet" :endpoint/port 18083 :endpoint/label "wallet-rpc"}]
         (system/endpoints {:chain {:backend :wallet-rpc
                                    :wallet-rpc {:uri "http://wallet:18083/json_rpc"}}}))))

(deftest a-configured-rail-with-no-usable-url-is-dropped-rather-than-guessed
  (is (= [] (system/endpoints {:chain {:backend :wallet-rpc :wallet-rpc {:uri nil}}})))
  (testing "a stripe deployment with no override still probes the public API"
    (is (= [{:endpoint/host "api.stripe.com" :endpoint/port 443 :endpoint/label "cards"}]
           (system/endpoints {:cards {:backend :stripe}})))))
