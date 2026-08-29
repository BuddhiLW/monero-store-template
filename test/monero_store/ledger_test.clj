(ns monero-store.ledger-test
  "The double-entry law, stated over generated money rather than examples.

  Every entry the builders emit sums to zero, an invoice paid in full leaves
  nothing standing in receivable, and a balance is always asked in one
  currency."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [hive-schemas.test :as hst]
            [hive-test.mutation :as mut]
            [malli.generator :as mg]
            [monero-store.collect.ledger :as ledger]
            [monero-store.schema :as schema])
  (:import (java.util Date UUID)))

(def fixed-instant (Date. 1700000000000))

(def GeneratedInvoice
  "An invoice, GENERATED.

  `schema/Invoice` is the contract and validates one; it cannot generate one,
  because `Instant` is `[:fn inst?]` and a predicate has no generator. The
  property varies the two things the book records — the amount and the
  currency — fmapped into a real invoice."
  [:tuple {:gen/fmap (fn [[amount currency]]
                       {:invoice/id (random-uuid)
                        :invoice/customer-id (random-uuid)
                        :invoice/item-id :pro
                        :invoice/provider :monero
                        :invoice/status :pending
                        :invoice/amount amount
                        :invoice/currency currency
                        :invoice/external-ref nil
                        :invoice/created-at fixed-instant})}
   [:int {:min 0 :max 1000000000000}]
   [:enum :xmr :btc :usd :eur :brl]])

(def gen-invoice (mg/generator GeneratedInvoice))

(defn- payment-of
  "A payment covering `amount` against `invoice`, with `ref` as its reference."
  [invoice amount ref]
  {:payment/id (UUID/randomUUID)
   :payment/invoice-id (:invoice/id invoice)
   :payment/provider (:invoice/provider invoice)
   :payment/external-ref ref
   :payment/reference ref
   :payment/amount amount
   :payment/confirmations 10
   :payment/resolution :applied
   :payment/seen-at fixed-instant})

(defn- invoice-for
  [currency amount]
  (assoc (mg/generate GeneratedInvoice)
         :invoice/currency currency
         :invoice/amount amount))

;; ---------------------------------------------------------------------------
;; the law

(hst/deftrifecta-from-schema every-sale-entry-balances
  monero-store.collect.ledger/sale-entry
  {:in [:cat GeneratedInvoice [:enum {} fixed-instant]]
   :out ledger/LedgerEntry
   :rel (fn [[invoice _] out]
          (= (:invoice/amount invoice)
             (->> (:entry/legs out)
                  (filter #(= :receivable (:leg/account %)))
                  (map :leg/amount)
                  (reduce + 0))))
   :contract true
   :strict-in true
   :num-tests 200})

(defspec every-settlement-entry-balances 200
  (prop/for-all [invoice gen-invoice]
    (let [amount (:invoice/amount invoice)
          entry (ledger/settlement-entry invoice (payment-of invoice amount "tx-1"))]
      (and (ledger/balanced? (:entry/legs entry))
           (= amount (->> (:entry/legs entry)
                          (filter #(= :wallet (:leg/account %)))
                          (map :leg/amount)
                          (reduce + 0)))))))

(defspec paying-in-full-clears-the-receivable 200
  (prop/for-all [invoice gen-invoice]
    (let [book (ledger/memory-ledger)
          amount (:invoice/amount invoice)
          currency (:invoice/currency invoice)]
      (ledger/post! book (ledger/sale-entry invoice fixed-instant))
      (ledger/post! book (ledger/settlement-entry invoice (payment-of invoice amount "tx-1")))
      (and (zero? (ledger/balance book :receivable currency))
           (= amount (ledger/balance book :wallet currency))
           (= (- amount) (ledger/balance book :revenue currency))))))

;; ---------------------------------------------------------------------------
;; the in-memory book

(deftest a-redelivered-settlement-books-once
  (let [book (ledger/memory-ledger)
        invoice (invoice-for :xmr 500)
        payment (payment-of invoice 500 "tx-abc")]
    (testing "the first post is recorded"
      (is (some? (ledger/post! book (ledger/settlement-entry invoice payment)))))
    (testing "the same reference again is refused, and adds nothing"
      (is (nil? (ledger/post! book (ledger/settlement-entry invoice payment))))
      (is (ledger/posted? book (:entry/reference (ledger/settlement-entry invoice payment))))
      (is (= 500 (ledger/balance book :wallet :xmr))))))

(deftest an-underpayment-leaves-the-remainder-standing
  (let [book (ledger/memory-ledger)
        invoice (invoice-for :xmr 1000)]
    (ledger/post! book (ledger/sale-entry invoice fixed-instant))
    (ledger/post! book (ledger/settlement-entry invoice (payment-of invoice 400 "tx-1")))
    (is (= 600 (ledger/balance book :receivable :xmr)))
    (is (= 400 (ledger/balance book :wallet :xmr)))))

(deftest two-distinct-payments-both-book
  (let [book (ledger/memory-ledger)
        invoice (invoice-for :xmr 1000)]
    (ledger/post! book (ledger/settlement-entry invoice (payment-of invoice 600 "tx-1")))
    (ledger/post! book (ledger/settlement-entry invoice (payment-of invoice 400 "tx-2")))
    (is (= 1000 (ledger/balance book :wallet :xmr)))))

(deftest a-balance-is-asked-in-one-currency
  (testing "two currencies in one book do not add up into a single number"
    (let [book (ledger/memory-ledger)
          xmr (invoice-for :xmr 1000)
          usd (invoice-for :usd 2500)]
      (ledger/post! book (ledger/settlement-entry xmr (payment-of xmr 1000 "tx-x")))
      (ledger/post! book (ledger/settlement-entry usd (payment-of usd 2500 "tx-u")))
      (is (= 1000 (ledger/balance book :wallet :xmr)))
      (is (= 2500 (ledger/balance book :wallet :usd)))
      (is (zero? (ledger/balance book :wallet :eur))))))

(deftest an-invoice-id-need-not-be-a-uuid
  (testing "a host store that mints its own identifiers still books"
    (let [book (ledger/memory-ledger)
          inv {:invoice/id "inv_9f2c" :invoice/amount 1000 :invoice/currency :xmr}
          pay {:payment/invoice-id "inv_9f2c" :payment/reference "tx-1" :payment/amount 1000}]
      (ledger/post! book (ledger/sale-entry inv fixed-instant))
      (ledger/post! book (ledger/settlement-entry inv pay))
      (is (zero? (ledger/balance book :receivable :xmr)))
      (is (= 1000 (ledger/balance book :wallet :xmr))))))

;; ---------------------------------------------------------------------------
;; teeth

(mut/deftest-mutations balanced?-mutations-are-caught
  monero-store.collect.ledger/balanced?
  [["always-agrees" (constantly true)]
   ["ignores-sign"  (fn [legs] (zero? (reduce + 0 (map #(abs (:leg/amount %)) legs))))]
   ["counts-legs"   (fn [legs] (even? (count legs)))]]
  (fn []
    (is (ledger/balanced? [{:leg/account :receivable :leg/amount 100}
                           {:leg/account :revenue :leg/amount -100}]))
    (is (not (ledger/balanced? [{:leg/account :receivable :leg/amount 100}
                                {:leg/account :revenue :leg/amount -99}])))
    (is (not (ledger/balanced? [{:leg/account :receivable :leg/amount 100}
                                {:leg/account :revenue :leg/amount 100}])))))
