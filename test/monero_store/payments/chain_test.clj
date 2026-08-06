(ns monero-store.payments.chain-test
  "Reading a wallet observation as money."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.collect.wallet :as wallet]
            [monero-store.payments.chain :as chain]
            [monero-store.schema :as schema]))

(defn- transfer
  [overrides]
  (merge {:transfer/tx-hash "tx-1"
          :transfer/amount 1000
          :transfer/confirmations 12
          :transfer/locked? false
          :transfer/double-spend? false}
         overrides))

(defn- observation
  [transfers & {:keys [unlocked]}]
  (cond-> {:wallet/address "addr-1" :wallet/transfers transfers}
    unlocked (assoc :wallet/unlocked-amount unlocked)))

(deftest an-observation-becomes-a-settlement
  (let [settled (chain/settlement-of :monero (observation [(transfer {})]) 1000)]
    (is (= settled (schema/check! schema/Settlement settled)))
    (is (= :settled (:settlement/status settled)))
    (is (= 1000 (:settlement/paid-amount settled)))
    (is (= ["tx-1"] (:settlement/references settled)))
    (testing "the amount owed comes from the invoice, never from the wallet"
      (is (= 5000 (:settlement/expected-amount
                   (chain/settlement-of :monero (observation [(transfer {})]) 5000)))))))

(deftest a-double-spend-contributes-nothing
  (let [settled (chain/settlement-of :monero
                                     (observation [(transfer {})
                                                   (transfer {:tx-hash "tx-2"
                                                              :transfer/tx-hash "tx-2"
                                                              :transfer/double-spend? true})])
                                     2000)]
    (is (= 1000 (:settlement/paid-amount settled)))
    (is (= ["tx-1"] (:settlement/references settled)))))

(deftest a-locked-transfer-is-not-settled-yet
  (let [settled (chain/settlement-of :monero
                                     (observation [(transfer {:transfer/locked? true})])
                                     1000)]
    (is (= :pending (:settlement/status settled)))
    (testing "the funds are still counted; it is the status that waits"
      (is (= 1000 (:settlement/paid-amount settled))))))

(deftest confirmations-are-the-weakest-transfers
  (testing "a fresh transfer drags the whole payment's depth down with it"
    (is (= 1 (:settlement/confirmations
              (chain/settlement-of :monero
                                   (observation [(transfer {})
                                                 (transfer {:transfer/tx-hash "tx-2"
                                                            :transfer/confirmations 1})])
                                   2000))))))

(deftest a-permissive-wallet-cannot-widen-the-gate
  (testing "when the wallet reports less unlocked than we counted, the wallet wins"
    (is (= 400 (:settlement/paid-amount
                (chain/settlement-of :monero
                                     (observation [(transfer {})] :unlocked 400)
                                     1000)))))

  (testing "and when it reports more, our own count still wins"
    (is (= 1000 (:settlement/paid-amount
                 (chain/settlement-of :monero
                                      (observation [(transfer {})] :unlocked 99999)
                                      1000))))))

(deftest nothing-seen-is-not-zero-seen
  (testing "an empty observation settles nothing and confirms nothing"
    (let [settled (chain/settlement-of :monero (observation []) 1000)]
      (is (= :pending (:settlement/status settled)))
      (is (zero? (:settlement/paid-amount settled)))
      (is (zero? (:settlement/confirmations settled))))))

(deftest a-callback-token-is-derived-from-the-invoice
  (let [token (wallet/callback-token "secret" "invoice-1")]
    (is (= 32 (count token)))
    (is (true? (wallet/token-valid? "secret" "invoice-1" token)))
    (testing "another invoice's token does not open this one"
      (is (false? (wallet/token-valid? "secret" "invoice-2" token))))
    (testing "a blank secret accepts nothing at all"
      (is (false? (wallet/token-valid? "" "invoice-1" token)))
      (is (false? (wallet/token-valid? "secret" "invoice-1" nil))))))

(deftest a-moneropay-body-is-normalized-not-trusted
  (let [payload {:amount {:expected 1000 :covered {:total 1000 :unlocked 600}}
                 :complete true
                 :transactions [{:tx_hash "abc" :amount 1000 :confirmations 3
                                 :locked false :double_spend_seen false}]}
        observed (wallet/moneropay-observation "addr-1" payload)]
    (is (= observed (schema/check! schema/WalletObservation observed)))
    (is (= 600 (:wallet/unlocked-amount observed)))
    (testing "the gateway's own idea of `complete` is not carried across"
      (is (= 3 (-> observed :wallet/transfers first :transfer/confirmations))))))
