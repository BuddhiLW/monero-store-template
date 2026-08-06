(ns monero-store.money-test
  "Money, currency scale, and the ADT that decisions are expressed in."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [monero-store.adt :as adt]
            [monero-store.currency :as currency]
            [monero-store.schema :as schema]))

(deftest money-carries-the-currency-scale
  (testing "the constructor supplies the scale; a caller never picks it"
    (is (= {:money/amount 9900 :money/currency :usd :money/scale 2}
           (schema/money :usd 9900)))
    (is (= 12 (:money/scale (schema/money :xmr 1)))))

  (testing "an unregistered currency is not money"
    (is (thrown? clojure.lang.ExceptionInfo (schema/money :doubloons 1))))

  (testing "money whose scale disagrees with its currency is rejected"
    (is (not (m/validate schema/Money
                         {:money/amount 1 :money/currency :xmr :money/scale 2})))
    (is (m/validate schema/Money (schema/money :xmr 1)))))

(deftest display-is-fixed-point-and-lossless-in-both-directions
  (is (= "1.500000000000" (currency/->display :xmr 1500000000000)))
  (is (= "0.000000000001" (currency/->display :xmr 1)))
  (is (= "99.00" (currency/->display :usd 9900)))
  (is (= 9900 (currency/->minor-units :usd "99.00")))
  (is (= 1500000000000 (currency/->minor-units :xmr "1.5"))))

(deftest a-currency-is-a-registry-entry
  (testing "registering one needs no edit to any schema"
    (currency/register! {:currency/id :test-coin :currency/scale 4 :currency/kind :crypto})
    (is (currency/known? :test-coin))
    (is (m/validate schema/Money (schema/money :test-coin 12345)))))

(deftest adt-values-are-plain-data
  (is (= {:adt/type :SettlementOutcome :adt/variant :settle/grant}
         (adt/settlement-outcome :settle/grant)))
  (is (m/validate schema/SettlementOutcome (adt/settlement-outcome :settle/late))))

(deftest adt-case-is-exhaustive-at-compile-time
  (testing "a match that forgets a variant does not compile"
    (is (thrown? Exception
                 (eval '(monero-store.adt/adt-case
                         monero-store.adt/SettlementOutcome
                         (monero-store.adt/settlement-outcome :settle/grant)
                         :settle/grant :g)))))

  (testing "a match on a variant the ADT does not declare does not compile"
    (is (thrown? Exception
                 (eval '(monero-store.adt/adt-case
                         monero-store.adt/CheckoutState
                         (monero-store.adt/checkout-state :checkout/paid)
                         :checkout/awaiting :a
                         :checkout/paid :p
                         :checkout/expired :e
                         :checkout/refunded :r))))))
