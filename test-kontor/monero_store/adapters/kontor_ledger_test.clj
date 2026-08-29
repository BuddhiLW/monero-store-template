(ns monero-store.adapters.kontor-ledger-test
  "The store's book, kept in kontor.

  These drive a real in-memory datahike, so they answer the question the
  in-memory ledger cannot: that a balance read back out of a double-entry
  kernel is the one the store put in, in minor units, at XMR's twelve places."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api]
            [kontor.core :as kontor]
            [monero-store.adapters.kontor-ledger :as kl]
            [monero-store.collect.ledger :as ledger]
            [monero-store.currency :as currency])
  (:import (java.util Date UUID)))

(def fixed-instant (Date. 1700000000000))

(defn- book!
  "A kontor connection with the store's chart installed for `currencies`."
  [& currencies]
  (kl/bootstrap! (kontor/create-test-db) (or (seq currencies) [:xmr])))

(defn- invoice
  [currency amount]
  {:invoice/id (UUID/randomUUID)
   :invoice/customer-id (UUID/randomUUID)
   :invoice/item-id :pro
   :invoice/provider :monero
   :invoice/status :pending
   :invoice/amount amount
   :invoice/currency currency
   :invoice/external-ref nil
   :invoice/created-at fixed-instant})

(defn- payment
  [inv amount ref]
  {:payment/id (UUID/randomUUID)
   :payment/invoice-id (:invoice/id inv)
   :payment/provider :monero
   :payment/external-ref ref
   :payment/reference ref
   :payment/amount amount
   :payment/confirmations 10
   :payment/resolution :applied
   :payment/seen-at fixed-instant})

;; ---------------------------------------------------------------------------
;; the boundary between minor units and BigDecimal

(deftest minor-units-survive-the-round-trip
  (testing "XMR's twelve places"
    (is (= 1M (kl/->amount :xmr 1000000000000)))
    (is (= 0.000000000001M (kl/->amount :xmr 1)))
    (is (= 1 (kl/->minor-units :xmr 0.000000000001M)))
    (is (= 1000000000000 (kl/->minor-units :xmr 1M))))
  (testing "a negative amount stays negative"
    (is (= -1M (kl/->amount :usd -100)))
    (is (= -100 (kl/->minor-units :usd -1M))))
  (testing "the commodity precision is the store's own scale, not a second copy"
    (let [by-symbol (into {} (keep (fn [m]
                                     (when-let [s (:kontor.commodity/symbol m)]
                                       [s (:kontor.commodity/precision m)]))
                                   (kl/chart [:xmr :usd :btc])))]
      (is (= (currency/scale :xmr) (get by-symbol "XMR")))
      (is (= (currency/scale :usd) (get by-symbol "USD")))
      (is (= (currency/scale :btc) (get by-symbol "BTC"))))))

;; ---------------------------------------------------------------------------
;; posting

(deftest a-sale-owes-and-a-settlement-clears
  (let [conn (book! :xmr)
        book (kl/kontor-ledger conn)
        inv (invoice :xmr 1500000000000)]
    (testing "the sale stands in receivable"
      (is (some? (ledger/post! book (ledger/sale-entry inv fixed-instant))))
      (is (= 1500000000000 (ledger/balance book :receivable :xmr)))
      (is (= -1500000000000 (ledger/balance book :revenue :xmr))))
    (testing "paying in full clears it and the wallet holds the money"
      (is (some? (ledger/post! book (ledger/settlement-entry
                                     inv (payment inv 1500000000000 "tx-1")))))
      (is (zero? (ledger/balance book :receivable :xmr)))
      (is (= 1500000000000 (ledger/balance book :wallet :xmr))))))

(deftest an-underpayment-leaves-the-remainder-standing
  (let [conn (book! :xmr)
        book (kl/kontor-ledger conn)
        inv (invoice :xmr 1000000000000)]
    (ledger/post! book (ledger/sale-entry inv fixed-instant))
    (ledger/post! book (ledger/settlement-entry inv (payment inv 400000000000 "tx-1")))
    (is (= 600000000000 (ledger/balance book :receivable :xmr)))
    (is (= 400000000000 (ledger/balance book :wallet :xmr)))))

(deftest a-redelivered-settlement-does-not-double-the-balance
  (testing ":kontor.transaction/external-id is unique-identity, so a second
            transact of the same reference upserts onto the SAME transaction
            and would post its legs twice"
    (let [conn (book! :xmr)
          book (kl/kontor-ledger conn)
          inv (invoice :xmr 1000000000000)
          entry (ledger/settlement-entry inv (payment inv 1000000000000 "tx-dup"))]
      (is (some? (ledger/post! book entry)))
      (is (= 1000000000000 (ledger/balance book :wallet :xmr)))
      (is (ledger/posted? book (:entry/reference entry)))
      (testing "the same entry again is refused"
        (is (nil? (ledger/post! book entry))))
      (testing "and the balance is unchanged"
        (is (= 1000000000000 (ledger/balance book :wallet :xmr)))))))

;; ---------------------------------------------------------------------------
;; opening a book a deployment can actually keep

(deftest a-book-opens-in-memory
  (let [conn (kl/open! (kl/memory-config) [:xmr])
        book (kl/kontor-ledger conn)
        inv (invoice :xmr 1000000000000)]
    (ledger/post! book (ledger/settlement-entry inv (payment inv 1000000000000 "tx-1")))
    (is (= 1000000000000 (ledger/balance book :wallet :xmr)))))

(deftest a-book-opens-on-disk-and-survives-a-reconnect
  (testing "the whole point of a file backend: the balance is still there when
            the process that wrote it is gone"
    (let [path (str (System/getProperty "java.io.tmpdir")
                    "/monero-store-book-" (UUID/randomUUID))
          config (kl/file-config path)
          inv (invoice :xmr 1000000000000)]
      (try
        (let [book (kl/kontor-ledger (kl/open! config [:xmr]))]
          (ledger/post! book (ledger/sale-entry inv fixed-instant))
          (ledger/post! book (ledger/settlement-entry inv (payment inv 1000000000000 "tx-1")))
          (is (zero? (ledger/balance book :receivable :xmr))))
        (testing "re-opened, from nothing but the path"
          (let [reopened (kl/kontor-ledger (kl/open! config [:xmr]))]
            (is (= 1000000000000 (ledger/balance reopened :wallet :xmr)))
            (is (zero? (ledger/balance reopened :receivable :xmr)))
            (testing "and the reference is still known, so a redelivery is refused"
              (is (ledger/posted? reopened
                                  (:entry/reference
                                   (ledger/settlement-entry
                                    inv (payment inv 1000000000000 "tx-1"))))))))
        (finally
          (datahike.api/delete-database config))))))

(deftest two-currencies-in-one-book-do-not-add-up
  (let [conn (book! :xmr :usd)
        book (kl/kontor-ledger conn)
        x (invoice :xmr 1000000000000)
        u (invoice :usd 2500)]
    (ledger/post! book (ledger/settlement-entry x (payment x 1000000000000 "tx-x")))
    (ledger/post! book (ledger/settlement-entry u (payment u 2500 "tx-u")))
    (is (= 1000000000000 (ledger/balance book :wallet :xmr)))
    (is (= 2500 (ledger/balance book :wallet :usd)))))
