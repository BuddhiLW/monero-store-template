(ns monero-store.payments.confirmation-tiers-test
  "Confirmations as a function of how much money is at stake.

  One threshold cannot be right for a five dollar purchase and a five hundred
  dollar one. Making a cheap buyer wait twenty minutes loses the sale; letting
  an expensive one through on nothing loses the money. The tiers put both
  numbers on the profile, where a deployment can argue with them."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.payments.provider :as provider]))

(def ^:private base
  {:provider/id :xmr
   :provider/currency :xmr
   :provider/min-confirmations 10
   :provider/underpay-tolerance 0
   :provider/settles-async? true
   :provider/settlement-poll? true
   :provider/webhook-auth :server-confirmed})

(def ^:private tiered
  "Cheap and revocable clears on nothing; the middle waits two blocks; anything
  large waits the full ten."
  (assoc base :provider/confirmation-tiers [[50000000000 0]
                                            [500000000000 2]
                                            [nil 10]]))

(deftest a-profile-with-no-tiers-is-unchanged
  (testing "the flat minimum still decides, for every amount"
    (doseq [amount [0 1 50000000000 999999999999999]]
      (is (= 10 (provider/confirmations-required base amount))))))

(deftest tiers-select-by-amount
  (testing "at or under a bound is inside that tier"
    (is (= 0 (provider/confirmations-required tiered 1)))
    (is (= 0 (provider/confirmations-required tiered 50000000000))))
  (testing "one unit past a bound falls to the next"
    (is (= 2 (provider/confirmations-required tiered 50000000001)))
    (is (= 2 (provider/confirmations-required tiered 500000000000))))
  (testing "the nil bound catches everything above"
    (is (= 10 (provider/confirmations-required tiered 500000000001)))
    (is (= 10 (provider/confirmations-required tiered Long/MAX_VALUE)))))

(deftest a-missing-amount-is-treated-as-the-cheapest-case-not-skipped
  (testing "nil reads as zero, which lands in the first tier rather than
            falling through every bound to the strict floor"
    (is (= 0 (provider/confirmations-required tiered nil)))))

(deftest tiers-with-no-catch-all-fall-back-to-the-floor
  (let [capped (assoc base :provider/confirmation-tiers [[50000000000 0]])]
    (is (= 0 (provider/confirmations-required capped 1)))
    (is (= 10 (provider/confirmations-required capped 50000000001))
        "an amount past every declared bound is not tierless, it is strict")))

;; ---------------------------------------------------------------------------
;; the decision that reads them

(defn- rails-with [& profiles]
  (provider/registry (mapv (fn [p] {:profile p :rail nil}) profiles)))

(defn- settlement [overrides]
  (merge {:settlement/provider :xmr
          :settlement/external-ref "ref"
          :settlement/status :settled
          :settlement/paid-amount 1000
          :settlement/expected-amount 1000
          :settlement/confirmations 0}
         overrides))

(defn- outcome [rails s]
  (:adt/variant (provider/settle rails s)))

(deftest a-cheap-invoice-grants-on-zero-confirmations
  (let [rails (rails-with tiered)]
    (is (= :settle/grant
           (outcome rails (settlement {:settlement/paid-amount 1000
                                       :settlement/expected-amount 1000
                                       :settlement/confirmations 0})))
        "a buyer of something cheap is not made to wait for a block")))

(deftest an-expensive-invoice-on-the-same-rail-still-waits
  (let [rails (rails-with tiered)
        big 900000000000]
    (testing "the same rail, the same zero confirmations, a larger sum"
      (is (= :settle/pending
             (outcome rails (settlement {:settlement/paid-amount big
                                         :settlement/expected-amount big
                                         :settlement/confirmations 0}))))
      (is (= :settle/grant
             (outcome rails (settlement {:settlement/paid-amount big
                                         :settlement/expected-amount big
                                         :settlement/confirmations 10})))))))

(deftest tiering-never-rescues-a-payment-that-is-short
  (testing "a cheap invoice clears confirmations but not the shortfall: the
            tier decides WHEN money counts, never WHETHER enough arrived"
    (let [rails (rails-with tiered)]
      (is (= :settle/underpaid
             (outcome rails (settlement {:settlement/paid-amount 900
                                         :settlement/expected-amount 1000
                                         :settlement/confirmations 0})))))))

(deftest tiering-never-rescues-a-suspect-payment
  (let [rails (rails-with tiered)]
    (is (= :settle/suspect
           (outcome rails (settlement {:settlement/paid-amount 0
                                       :settlement/expected-amount 1000
                                       :settlement/confirmations 0
                                       :settlement/suspect? true})))
        "a double spend seen on a cheap invoice is still a double spend")))

(deftest an-untiered-rail-decides-exactly-as-it-did-before
  (testing "the regression guard: adding the key must not move an existing
            deployment's behaviour"
    (let [rails (rails-with base)]
      (is (= :settle/pending
             (outcome rails (settlement {:settlement/confirmations 9}))))
      (is (= :settle/grant
             (outcome rails (settlement {:settlement/confirmations 10})))))))
