(ns monero-store.collect.swap-test
  "An exchange order standing in for a wallet.

  The two properties worth pinning: the rail above cannot tell the difference,
  and the buyer's debt is settled by the exchange RECEIVING the money, not by
  the store eventually holding the converted coin."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.adt :as adt]
            [monero-store.collect.swap :as swap]
            [monero-store.collect.wallet :as wallet]
            [monero-store.payments.chain :as chain]
            [monero-store.payments.provider :as provider]))

(def ^:private payout "48treasuryAddressOfTheStore")

(defn- wallet-over
  ([provider] (wallet-over provider :btc))
  ([provider from]
   (swap/->wallet {:provider provider
                   :from from
                   :to :xmr
                   :payout-address payout
                   :refund-address "bc1qrefund"})))

(defn- open!
  [w amount]
  (:subaddress/address
   (wallet/open-address! w #:opening{:label "invoice inv_1" :amount amount})))

(deftest the-pay-in-address-is-what-the-buyer-is-given
  (let [p (swap/fake-provider)
        w (wallet-over p)
        address (open! w 250000)]
    (is (string? address))
    (is (seq address))
    (testing "and the order records where the conversion is told to land"
      (is (= payout (:swap/payout-address (swap/swap-status p address)))))))

(deftest two-invoices-never-share-an-address
  (let [p (swap/fake-provider)
        w (wallet-over p)]
    (is (= 5 (count (set (repeatedly 5 #(open! w 250000))))))))

(deftest an-unpaid-order-reports-no-transfer-rather-than-a-transfer-of-zero
  (let [p (swap/fake-provider)
        w (wallet-over p)
        address (open! w 250000)
        observed (wallet/observe w address)]
    (is (= address (:wallet/address observed)))
    (is (empty? (:wallet/transfers observed))
        "no observation of money is not an observation of no money")))

(deftest an-address-the-provider-does-not-know-is-nil-not-empty
  (let [w (wallet-over (swap/fake-provider))]
    (is (nil? (wallet/observe w "an-address-from-nowhere"))
        "a provider that cannot report is no evidence, which the rail must be
         able to tell apart from an unpaid order")))

(deftest money-in-flight-is-locked-and-does-not-settle
  (let [p (swap/fake-provider)
        w (wallet-over p)
        address (open! w 250000)]
    (swap/pay! p address 250000)
    (let [[transfer :as transfers] (:wallet/transfers (wallet/observe w address))]
      (is (= 1 (count transfers)))
      (is (= 250000 (:transfer/amount transfer)))
      (is (true? (:transfer/locked? transfer)))
      (testing "and the chain rail reads that as not yet settled"
        (is (= :pending
               (:settlement/status
                (chain/settlement-of :btc (wallet/observe w address) 250000))))))))

(deftest the-buyer-is-done-when-the-exchange-accepts-the-money
  (let [p (swap/fake-provider)
        w (wallet-over p)
        address (open! w 250000)]
    (swap/pay! p address 250000)
    (swap/advance! p address :swap/received)
    (let [settlement (chain/settlement-of :btc (wallet/observe w address) 250000)]
      (is (= :settled (:settlement/status settlement)))
      (is (= 250000 (:settlement/paid-amount settlement))))))

(deftest acceptance-counts-as-a-confirmation-because-nothing-else-can
  (let [p (swap/fake-provider)
        w (wallet-over p)
        address (open! w 250000)]
    (swap/pay! p address 250000 {:confirmations 0})
    (swap/advance! p address :swap/received)
    (let [[transfer] (:wallet/transfers (wallet/observe w address))]
      (is (= 1 (:transfer/confirmations transfer))
          "the store runs no node on the source chain, so an exchange that has
           accepted the money is the only finality there is to read")))

  (testing "and a provider that can count blocks is believed over that floor"
    (let [p (swap/fake-provider)
          w (wallet-over p)
          address (open! w 250000)]
      (swap/pay! p address 250000 {:confirmations 6})
      (swap/advance! p address :swap/received)
      (is (= 6 (:transfer/confirmations
                (first (:wallet/transfers (wallet/observe w address)))))))))

(deftest the-conversion-is-the-stores-problem-and-never-the-buyers
  (let [p (swap/fake-provider)
        w (wallet-over p)
        address (open! w 250000)]
    (swap/pay! p address 250000)
    (doseq [variant [:swap/received :swap/converting :swap/delivered]]
      (swap/advance! p address variant)
      (testing (str "still settled at " variant)
        (is (= :settled
               (:settlement/status
                (chain/settlement-of :btc (wallet/observe w address) 250000)))
            "once the exchange has the money the buyer owes nothing, whatever
             the conversion is doing")))))

(deftest a-refund-is-not-a-payment
  (let [p (swap/fake-provider)
        w (wallet-over p)
        address (open! w 250000)]
    (swap/pay! p address 250000)
    (swap/advance! p address :swap/refunded)
    (is (empty? (:wallet/transfers (wallet/observe w address)))
        "the money went back, so reporting it as paid would credit an account
         for funds it does not have")))

(deftest a-short-payment-is-an-underpayment-and-not-a-settlement
  (let [p (swap/fake-provider)
        w (wallet-over p)
        address (open! w 250000)]
    (swap/pay! p address 200000)
    (swap/advance! p address :swap/received)
    (let [settlement (chain/settlement-of :btc (wallet/observe w address) 250000)]
      (is (= 200000 (:settlement/paid-amount settlement)))
      (is (= 250000 (:settlement/expected-amount settlement))))))

(deftest an-exchange-that-quotes-a-different-number-is-refused-at-open
  (let [lying (reify swap/ISwapProvider
                (open-swap! [_ order]
                  (assoc (into {} order)
                         :swap/expected-amount (inc (long (:swap/amount order)))
                         :swap/pay-in-address "bc1qsomewhere"))
                (swap-status [_ _] nil))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (open! (wallet-over lying) 250000))
        "showing the buyer one figure while the exchange waits for another is
         an invoice that can never settle")))

(deftest an-exchange-that-opens-without-an-address-is-refused
  (let [empty-handed (reify swap/ISwapProvider
                       (open-swap! [_ order]
                         (assoc (into {} order)
                                :swap/expected-amount (:swap/amount order)
                                :swap/pay-in-address ""))
                       (swap-status [_ _] nil))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (open! (wallet-over empty-handed) 250000)))))

;; ---------------------------------------------------------------------------
;; limits

(deftest a-provider-with-no-limits-fills-anything
  (let [p (swap/fake-provider)]
    (is (true? (swap/fillable? (reify swap/ISwapProvider
                                 (open-swap! [_ _] nil)
                                 (swap-status [_ _] nil))
                               :btc :xmr 1)))
    (is (true? (swap/fillable? p :btc :xmr 1))
        "the fake declares limits only for pairs it was given")))

(deftest a-purchase-under-the-exchange-minimum-is-not-fillable
  (let [p (swap/fake-provider {:limits {[:btc :xmr] #:swap{:min-amount 100000
                                                           :max-amount 100000000}}})]
    (is (false? (swap/fillable? p :btc :xmr 99999))
        "a cheap first job may be payable in XMR and not in BTC, and checkout
         has to be able to say so before opening an order nobody can fill")
    (is (true? (swap/fillable? p :btc :xmr 100000)))
    (is (false? (swap/fillable? p :btc :xmr 100000001)))))

;; ---------------------------------------------------------------------------
;; the rail cannot tell

(deftest a-swap-backed-rail-is-an-ordinary-chain-rail
  (let [p (swap/fake-provider)
        {:keys [profile rail]} (chain/entry {:wallet (wallet-over p)
                                             :provider-id :btc
                                             :provider/currency :btc
                                             :provider/min-confirmations 1})
        rails (provider/registry [{:profile profile :rail rail}])
        handle (provider/charge! rail #:charge{:invoice-id "inv_1"
                                               :amount {:money/amount 250000
                                                        :money/currency :btc
                                                        :money/scale 8}})
        address (:handle/pay-to handle)
        invoice {:invoice/id "inv_1"
                 :invoice/amount 250000
                 :invoice/currency :btc
                 :invoice/external-ref address}]
    (testing "charging opens an exchange order and hands back its address"
      (is (= :btc (:handle/provider handle)))
      (is (seq address)))

    (testing "polling before payment grants nothing"
      (is (= :settle/pending
             (:adt/variant (provider/settle rails (provider/poll rail invoice))))))

    (testing "and once the exchange has the money, the ordinary rail grants"
      (swap/pay! p address 250000)
      (swap/advance! p address :swap/received)
      (is (= :settle/grant
             (:adt/variant (provider/settle rails (provider/poll rail invoice))))))))

(deftest every-swap-state-is-decided-about
  (testing "adt-case checks coverage at expansion, so this only has to prove
            each variant produces a well-formed observation"
    (doseq [variant (:variants adt/SwapState)]
      (let [order #:swap{:id "order-1"
                         :provider :fake
                         :from :btc
                         :to :xmr
                         :state (adt/swap-state variant)
                         :pay-in-address "bc1qpayin"
                         :payout-address payout
                         :expected-amount 250000
                         :received-amount 250000
                         :confirmations 3
                         :tx-hash "deadbeef"}]
        (is (= "bc1qpayin" (:wallet/address (swap/observation-of order))))))))
