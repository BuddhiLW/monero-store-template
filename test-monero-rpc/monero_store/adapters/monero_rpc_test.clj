(ns monero-store.adapters.monero-rpc-test
  "The parts of the monero-wallet-rpc adapter that are about a wallet being a
  LIVE DAEMON rather than a library.

  None of these need a daemon. The connection, the account guarantee, the sync
  throttle and the reachability TTL are all expressed over injected functions
  and atoms precisely so the behaviour that only shows up against a real wallet
  — one that is down at boot, one missing the configured account, one asked
  about five hundred invoices in a sweep — is decidable in milliseconds."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.adapters.monero-rpc :as rpc]))

;; ---------------------------------------------------------------------------
;; retrying-connection

(deftest retrying-connection-caches-only-success
  (testing "the value is opened once and reused"
    (let [opens (atom 0)
          conn (rpc/retrying-connection (fn [] (swap! opens inc) :wallet))]
      (is (= :wallet @conn))
      (is (= :wallet @conn))
      (is (= 1 @opens) "a successful connection is opened once, not per deref")))

  (testing "a failure is NOT cached — this is the whole reason it is not a delay"
    (let [attempts (atom 0)
          conn (rpc/retrying-connection
                (fn []
                  (when (< (swap! attempts inc) 3)
                    (throw (ex-info "wallet is down" {})))
                  :wallet))]
      (is (thrown? clojure.lang.ExceptionInfo @conn))
      (is (thrown? clojure.lang.ExceptionInfo @conn))
      (is (= :wallet @conn) "the wallet came back and the connection follows it")
      (is (= 3 @attempts))
      (is (= :wallet @conn) "and once it succeeds it is cached")
      (is (= 3 @attempts)))))

;; ---------------------------------------------------------------------------
;; ensure-account-index!

(deftest ensure-account-index-grows-the-wallet
  (testing "account 0 always exists, so nothing is created for it"
    (let [created (atom 0)]
      (is (= 1 (rpc/ensure-account-index! (constantly 1)
                                          #(swap! created inc)
                                          0)))
      (is (zero? @created))))

  (testing "the configured account is created before a customer needs it"
    (let [accounts (atom 1)]
      (is (= 2 (rpc/ensure-account-index! #(deref accounts)
                                          #(swap! accounts inc)
                                          1)))
      (is (= 2 @accounts))))

  (testing "a wallet that will not grow fails HERE, not at the first invoice"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"would not create the configured account"
         (rpc/ensure-account-index! (constantly 1) (constantly nil) 3)))
    (is (= :monero-store/account-not-creatable
           (try (rpc/ensure-account-index! (constantly 1) (constantly nil) 3)
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))

  (testing "it terminates rather than looping against a refusing wallet"
    (let [calls (atom 0)]
      (try (rpc/ensure-account-index! (constantly 0) #(swap! calls inc) 2)
           (catch clojure.lang.ExceptionInfo _ nil))
      (is (<= @calls 4) "bounded by the target, not unbounded"))))

;; ---------------------------------------------------------------------------
;; sync throttle

(deftest sync-due?-bounds-refreshes-per-interval
  (testing "the first observation always refreshes"
    (is (true? (rpc/sync-due? (atom nil) 5000 1000))))

  (testing "a sweep over many invoices costs ONE refresh, not one per invoice"
    (let [last-sync (atom nil)
          sweep (repeatedly 500 #(rpc/sync-due? last-sync 5000 1000))]
      (is (= 1 (count (filter true? sweep))))))

  (testing "the interval is respected, and elapsing it re-arms"
    (let [last-sync (atom nil)]
      (is (true? (rpc/sync-due? last-sync 5000 1000)))
      (is (false? (rpc/sync-due? last-sync 5000 5999)))
      (is (true? (rpc/sync-due? last-sync 5000 6000)))))

  (testing "concurrent callers make one refresh, not N"
    (let [last-sync (atom nil)
          results (->> (repeatedly 64 #(future (rpc/sync-due? last-sync 5000 1000)))
                       doall
                       (mapv deref))]
      (is (= 1 (count (filter true? results)))))))

;; ---------------------------------------------------------------------------
;; reachability TTL

(deftest probe-due?-bounds-probes-per-ttl
  (testing "nothing has been probed yet, so a probe is owed"
    (is (true? (rpc/probe-due? (atom {:at nil :ok? false}) 30000 1000))))

  (testing "inside the TTL the cached verdict stands"
    (let [probe (atom {:at nil :ok? false})]
      (is (true? (rpc/probe-due? probe 30000 1000)))
      (is (false? (rpc/probe-due? probe 30000 30999)))
      (is (true? (rpc/probe-due? probe 30000 31000)))))

  (testing "the claim preserves the verdict already recorded"
    (let [probe (atom {:at 1000 :ok? true})]
      (rpc/probe-due? probe 30000 40000)
      (is (true? (:ok? @probe)) "claiming the slot must not clear the last answer")))

  (testing "concurrent callers make one probe, not N"
    (let [probe (atom {:at nil :ok? false})
          results (->> (repeatedly 64 #(future (rpc/probe-due? probe 30000 1000)))
                       doall
                       (mapv deref))]
      (is (= 1 (count (filter true? results)))))))

;; ---------------------------------------------------------------------------
;; the value objects

(deftest observation-is-a-wallet-observation
  (testing "an address the wallet has seen nothing for is zero transfers, not nil"
    (is (= {:wallet/address "9addr" :wallet/transfers []}
           (rpc/observation "9addr" [])))))
