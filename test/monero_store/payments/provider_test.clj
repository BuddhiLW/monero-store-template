(ns monero-store.payments.provider-test
  "The settlement decision, and the fact that it reads a profile rather than a
  provider name."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.payments.provider :as provider]))

(defn- profile
  [id overrides]
  (merge {:provider/id id
          :provider/currency :xmr
          :provider/min-confirmations 10
          :provider/underpay-tolerance 0
          :provider/settles-async? true
          :provider/settlement-poll? true
          :provider/webhook-auth :server-confirmed}
         overrides))

(defn- rails-with
  [& profiles]
  (provider/registry (mapv (fn [p] {:profile p :rail nil}) profiles)))

(defn- settlement
  [provider-id overrides]
  (merge {:settlement/provider provider-id
          :settlement/external-ref "ref"
          :settlement/status :settled
          :settlement/paid-amount 1000
          :settlement/expected-amount 1000
          :settlement/confirmations 10}
         overrides))

(def rails
  (rails-with (profile :strict {})
              (profile :lenient {:provider/min-confirmations 0
                                 :provider/underpay-tolerance 5})
              (profile :cards {:provider/currency :usd
                               :provider/min-confirmations 0
                               :provider/webhook-auth :signed-payload})
              (profile :offline {:provider/webhook-auth :none
                                 :provider/settlement-poll? false})))

(defn- outcome
  [provider-id overrides]
  (:adt/variant (provider/settle rails (settlement provider-id overrides))))

(deftest the-profile-decides-not-the-provider-name
  (testing "the same settlement means different things under different profiles"
    (is (= :settle/pending (outcome :strict {:settlement/confirmations 3})))
    (is (= :settle/grant (outcome :lenient {:settlement/confirmations 3}))))

  (testing "underpayment tolerance is a number in the profile, not a branch"
    (is (= :settle/underpaid (outcome :strict {:settlement/paid-amount 997})))
    (is (= :settle/grant (outcome :lenient {:settlement/paid-amount 997})))
    (is (= :settle/underpaid (outcome :lenient {:settlement/paid-amount 990})))))

(deftest the-order-of-the-decision-is-the-policy
  (testing "a failed settlement is a rejection whatever arrived"
    (is (= :settle/reject (outcome :strict {:settlement/status :failed
                                            :settlement/paid-amount 1000}))))

  (testing "no money at all is pending, not underpaid: there is nothing to make whole"
    (is (= :settle/pending (outcome :strict {:settlement/paid-amount 0}))))

  (testing "a shortfall outranks a confirmation count: money that never came cannot be waited for"
    (is (= :settle/underpaid (outcome :strict {:settlement/paid-amount 500
                                               :settlement/confirmations 0}))))

  (testing "overpayment grants; the surplus is an operator's problem, not a refusal"
    (is (= :settle/grant (outcome :strict {:settlement/paid-amount 1500})))))

(deftest a-profile-says-what-may-be-asked-of-a-rail
  (is (true? (provider/webhook-settleable? rails :strict)))
  (is (false? (provider/webhook-settleable? rails :offline)))
  (is (true? (provider/pollable? rails :strict)))
  (is (false? (provider/pollable? rails :offline)))
  (is (= #{:strict :lenient :cards} (provider/webhook-rails rails)))
  (is (= #{:xmr :usd} (provider/currencies rails))))

(deftest a-registry-refuses-a-malformed-profile
  (testing "a rail whose declared behaviour is nonsense fails at boot, not at a customer"
    (is (thrown? clojure.lang.ExceptionInfo
                 (rails-with {:provider/id :broken :provider/currency :xmr})))))
