(ns monero-store.pipeline.checkout-test
  "Opening a payment and applying its settlement: the money invariants."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.collect.fulfilment :as fulfilment]
            [monero-store.collect.store :as store]
            [monero-store.collect.wallet :as wallet]
            [monero-store.payments.provider :as provider]
            [monero-store.pipeline.checkout :as checkout]
            [monero-store.pipeline.reconcile :as reconcile]
            [monero-store.schema :as schema]
            [monero-store.support :as support])
  (:import (java.util Date)))

(defn- open-monero!
  ([deps] (open-monero! deps :yearly))
  ([deps item-id]
   (checkout/open! deps {:customer (support/customer! deps) :item-id item-id :provider-id :monero})))

(deftest an-invoice-is-quoted-once-in-the-rails-own-currency
  (let [deps (support/deps)
        {:keys [invoice handle]} (open-monero! deps)]
    (is (= invoice (schema/check! schema/Invoice invoice)))
    (is (= handle (schema/check! schema/ChargeHandle handle)))
    (testing "the item is priced in USD and the rail settles in XMR"
      (is (= :xmr (:invoice/currency invoice)))
      (is (= 660000000000 (:invoice/amount invoice))))
    (testing "the rate that was locked is recorded, with who agreed it"
      (is (= 150.0 (:invoice/quoted-rate invoice)))
      (is (= [:alpha :beta] (:invoice/quote-sources invoice))))
    (testing "the handle carries the address the customer must pay"
      (is (= (:invoice/external-ref invoice) (:handle/pay-to handle))))))

(deftest reloading-checkout-does-not-accumulate-invoices
  (let [deps (support/deps)
        first-open (open-monero! deps)
        second-open (open-monero! deps)]
    (is (= (get-in first-open [:invoice :invoice/id])
           (get-in second-open [:invoice :invoice/id])))
    (testing "and the same address is handed back, not a second one"
      (is (= (get-in first-open [:handle :handle/pay-to])
             (get-in second-open [:handle :handle/pay-to]))))))

(deftest a-price-that-cannot-be-established-writes-nothing
  (let [deps (support/deps {:rates-fn (constantly [])})]
    (is (thrown? clojure.lang.ExceptionInfo (open-monero! deps)))
    (testing "no invoice was opened for a price the store does not know"
      (is (empty? (store/open-invoices (:store deps)))))))

(deftest money-buys-exactly-one-period
  (let [deps (support/deps)
        {:keys [invoice handle]} (open-monero! deps)]
    (wallet/credit! (:wallet deps) (:handle/pay-to handle) (:invoice/amount invoice))

    (testing "the first sweep grants"
      (is (= {:settle/grant 1} (reconcile/sweep! deps)))
      (is (= 1 (count @(:granted deps))))
      (is (= :paid (:invoice/status (store/invoice-by-id (:store deps) (:invoice/id invoice))))))

    (testing "the grant names what was bought, and until when"
      (let [grant (first @(:granted deps))]
        (is (= :yearly (:fulfilment/item-id grant)))
        (is (inst? (:fulfilment/period-end grant)))))

    (testing "a redelivered settlement is acknowledged, and grants nothing"
      (let [again (checkout/settle! deps
                                    (store/invoice-by-id (:store deps) (:invoice/id invoice))
                                    (provider/polled-settlement (:rails deps) invoice))]
        (is (= :settle/late (:adt/variant again)))
        (is (= 1 (count @(:granted deps))))
        (is (= 1 (count (store/payments-for (:store deps) (:invoice/id invoice)))))))))

(deftest a-one-off-item-grants-no-period
  (let [deps (support/deps)
        {:keys [invoice handle]} (open-monero! deps :hidden)]
    (wallet/credit! (:wallet deps) (:handle/pay-to handle) (:invoice/amount invoice))
    (reconcile/sweep! deps)
    (is (nil? (:fulfilment/period-end (first @(:granted deps)))))))

(deftest a-short-payment-is-underpaid-and-still-open
  (let [deps (support/deps)
        {:keys [invoice handle]} (open-monero! deps)
        owed (:invoice/amount invoice)]
    (wallet/credit! (:wallet deps) (:handle/pay-to handle) (- owed 10))
    (is (= {:settle/underpaid 1} (reconcile/sweep! deps)))
    (is (= :underpaid (:invoice/status (store/invoice-by-id (:store deps) (:invoice/id invoice)))))
    (is (empty? @(:granted deps)))

    (testing "the rest of the money completes it"
      (wallet/credit! (:wallet deps) (:handle/pay-to handle) 10)
      (is (= {:settle/grant 1} (reconcile/sweep! deps)))
      (is (= 1 (count @(:granted deps)))))))

(deftest an-unconfirmed-payment-waits
  (let [deps (support/deps)
        {:keys [invoice handle]} (open-monero! deps)]
    (wallet/credit! (:wallet deps) (:handle/pay-to handle) (:invoice/amount invoice)
                    {:confirmations 2})
    (is (= {:settle/pending 1} (reconcile/sweep! deps)))
    (is (empty? @(:granted deps)))

    (testing "the ledger still records that the money was seen"
      (is (= 1 (count (store/payments-for (:store deps) (:invoice/id invoice))))))))

(deftest a-lapsed-quote-is-not-granted-by-a-redelivered-notice
  (let [clock (atom (Date.))
        deps (support/deps {:now-fn #(deref clock)})
        {:keys [invoice handle]} (open-monero! deps)]
    (wallet/credit! (:wallet deps) (:handle/pay-to handle) (:invoice/amount invoice))
    (reset! clock (Date. (+ (.getTime ^Date (:invoice/expires-at invoice)) 1000)))

    (let [current (fn [] (store/invoice-by-id (:store deps) (:invoice/id invoice)))
          settlement (provider/polled-settlement (:rails deps) invoice)
          first-outcome (checkout/settle! deps (current) settlement)
          second-outcome (checkout/settle! deps (current) settlement)]
      (is (= :settle/late (:adt/variant first-outcome)))
      (is (= :settle/late (:adt/variant second-outcome)))
      (is (= 0 (count @(:granted deps))))
      (is (not= :paid (:invoice/status (current)))))))

(deftest late-money-is-recorded-not-swallowed
  (let [deps (support/deps)
        {:keys [invoice handle]} (open-monero! deps)]
    (wallet/credit! (:wallet deps) (:handle/pay-to handle) (:invoice/amount invoice))
    (reconcile/sweep! deps)

    (testing "money arriving after the invoice is paid lands in the operator queue"
      (wallet/credit! (:wallet deps) (:handle/pay-to handle) 500 {:tx-hash "tx-late"})
      (let [outcome (checkout/settle! deps
                                      (store/invoice-by-id (:store deps) (:invoice/id invoice))
                                      (provider/polled-settlement (:rails deps) invoice))]
        (is (= :settle/late (:adt/variant outcome)))
        (is (= 1 (count (store/unapplied-payments (:store deps) 10))))
        (is (= 1 (count @(:granted deps))))))))

(deftest a-failed-fulfilment-releases-the-invoice-for-retry
  (let [attempts (atom 0)
        deps (support/deps
              {:fulfilment (fulfilment/handler
                            (fn [_grant]
                              (when (= 1 (swap! attempts inc))
                                (throw (ex-info "the host is down" {})))))})
        {:keys [invoice handle]} (open-monero! deps)]
    (wallet/credit! (:wallet deps) (:handle/pay-to handle) (:invoice/amount invoice))

    (testing "the sweep survives a fulfilment that throws"
      (is (= {} (reconcile/sweep! deps)))
      (is (= :pending (:invoice/status (store/invoice-by-id (:store deps) (:invoice/id invoice))))))

    (testing "and the next one hands over"
      (is (= {:settle/grant 1} (reconcile/sweep! deps)))
      (is (= 2 @attempts))
      (is (= :paid (:invoice/status (store/invoice-by-id (:store deps) (:invoice/id invoice))))))))

(deftest a-settlement-from-another-rail-is-not-applied
  (let [deps (support/deps)
        {:keys [invoice]} (open-monero! deps)
        foreign {:settlement/provider :stripe
                 :settlement/external-ref "cs_test"
                 :settlement/status :settled
                 :settlement/paid-amount (:invoice/amount invoice)
                 :settlement/expected-amount (:invoice/amount invoice)
                 :settlement/confirmations 0
                 :settlement/references ["cs_test"]}]
    (is (nil? (checkout/settle! deps invoice foreign)))
    (is (empty? @(:granted deps)))))

(deftest an-expired-quote-retires-the-invoice
  (let [clock (atom (Date. 1700000000000))
        deps (support/deps {:now-fn #(deref clock)
                            :rates-fn #(support/rates-at 150.0 @clock)})
        {:keys [invoice]} (open-monero! deps)]
    (reset! clock (Date. (+ 1700000000000 (* 20 60 1000))))
    ;; The sweep reads before it retires: money that arrived inside the window
    ;; and is only now being noticed must still settle normally.
    (is (= 1 (:invoice/expired (reconcile/sweep! deps))))
    (is (= :expired (:invoice/status (store/invoice-by-id (:store deps) (:invoice/id invoice)))))))

(deftest an-operator-can-grant-by-hand
  (let [deps (support/deps)
        customer (support/customer! deps)
        outcome (checkout/grant! deps {:customer customer
                                       :item-id :monthly
                                       :reference "bank-line-42"})
        invoice (first (store/invoices-for-customer (:store deps) (:customer/id customer)))
        payments (store/payments-for (:store deps) (:invoice/id invoice))]
    (is (= :settle/grant (:adt/variant outcome)))
    (is (= 1 (count @(:granted deps))))
    (testing "the grant is attributable to whatever proved it to a human"
      (is (= ["operator:bank-line-42"] (mapv :payment/reference payments))))))
