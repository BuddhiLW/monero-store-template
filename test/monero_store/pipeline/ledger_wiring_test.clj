(ns monero-store.pipeline.ledger-wiring-test
  "That the book is actually wired to the pipeline.

  The ledger tests prove the entries balance; these prove the store emits them
  — opening an invoice owes, and a sweep that finds the money settles it — and
  that a store with no ledger still runs."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.collect.ledger :as book]
            [monero-store.collect.wallet :as wallet]
            [monero-store.pipeline.checkout :as checkout]
            [monero-store.pipeline.reconcile :as reconcile]
            [monero-store.support :as support]
            [monero-store.system :as system]))

(defn- open-monero!
  [deps]
  (checkout/open! deps {:customer (support/customer! deps)
                        :item-id :yearly
                        :provider-id :monero}))

(deftest opening-an-invoice-books-the-revenue-owed
  (let [ledger (book/memory-ledger)
        deps (support/deps {:ledger ledger})
        {:keys [invoice]} (open-monero! deps)
        amount (:invoice/amount invoice)]
    (is (pos? amount))
    (is (= amount (book/balance ledger :receivable :xmr)))
    (is (= (- amount) (book/balance ledger :revenue :xmr)))
    (testing "nothing has arrived yet"
      (is (zero? (book/balance ledger :wallet :xmr))))))

(deftest a-sweep-that-finds-the-money-settles-the-receivable
  (let [ledger (book/memory-ledger)
        deps (support/deps {:ledger ledger})
        {:keys [invoice handle]} (open-monero! deps)
        amount (:invoice/amount invoice)]
    (wallet/credit! (:wallet deps) (:handle/pay-to handle) amount)
    (is (= {:settle/grant 1} (reconcile/sweep! deps)))
    (testing "the money is in the wallet account and the customer owes nothing"
      (is (= amount (book/balance ledger :wallet :xmr)))
      (is (zero? (book/balance ledger :receivable :xmr))))))

(deftest a-second-sweep-books-nothing-further
  (testing "a rail that keeps reporting the same transfer must not keep booking it"
    (let [ledger (book/memory-ledger)
          deps (support/deps {:ledger ledger})
          {:keys [invoice handle]} (open-monero! deps)
          amount (:invoice/amount invoice)]
      (wallet/credit! (:wallet deps) (:handle/pay-to handle) amount)
      (reconcile/sweep! deps)
      (reconcile/sweep! deps)
      (is (= amount (book/balance ledger :wallet :xmr)))
      (is (zero? (book/balance ledger :receivable :xmr))))))

(deftest an-underpayment-leaves-the-remainder-owed
  (let [ledger (book/memory-ledger)
        deps (support/deps {:ledger ledger})
        {:keys [invoice handle]} (open-monero! deps)
        amount (:invoice/amount invoice)
        part (quot amount 4)]
    (wallet/credit! (:wallet deps) (:handle/pay-to handle) part)
    (reconcile/sweep! deps)
    (is (= part (book/balance ledger :wallet :xmr)))
    (is (= (- amount part) (book/balance ledger :receivable :xmr)))))

(deftest a-store-with-no-ledger-still-runs
  (testing ":ledger is optional, exactly as :analytics is"
    (let [deps (support/deps)
          {:keys [invoice handle]} (open-monero! deps)]
      (wallet/credit! (:wallet deps) (:handle/pay-to handle) (:invoice/amount invoice))
      (is (= {:settle/grant 1} (reconcile/sweep! deps))))))

;; ---------------------------------------------------------------------------
;; The boot path
;;
;; Everything above drives `support/deps`, which builds the deps map BY HAND.
;; That proves the pipeline books when a ledger is present, and proves nothing
;; about whether a store booted the supported way HAS one. It did not: `:ledger`
;; was read by `checkout/open!` and `settle!` and written by neither `config`
;; nor `start!`, so every deployment ran with `ledger` nil while the five tests
;; above stayed green. These go through `system/start!`.
;; ---------------------------------------------------------------------------

(defn- free-port
  []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn- booted
  "Start a store on an ephemeral port, hand its deps to `f`, always stop it."
  [overrides f]
  (let [system (system/start! (merge {:port (free-port)} overrides))]
    (try (f (:deps system)) (finally (system/stop! system)))))

(deftest a-booted-store-has-a-book
  (testing "start! wires :ledger, which is the key the pipeline reads"
    (booted {}
            (fn [deps]
              (is (some? (:ledger deps))
                  "LEDGER defaults to memory; booking nothing must be asked for")
              (is (satisfies? book/ILedger (:ledger deps)))))))

(deftest a-host-supplied-book-reaches-the-pipeline
  (testing ":ledger is a seam, not a config key"
    (let [ledger (book/memory-ledger)]
      (booted {:ledger ledger}
              (fn [deps]
                (is (identical? ledger (:ledger deps))
                    "an override absent from `seams` is absorbed into cfg and never reaches deps"))))))

(deftest booking-nowhere-is-said-explicitly
  (testing "nil is a value this seam can take, so it is read with contains? not or"
    (booted {:ledger nil}
            (fn [deps] (is (nil? (:ledger deps)))))))
