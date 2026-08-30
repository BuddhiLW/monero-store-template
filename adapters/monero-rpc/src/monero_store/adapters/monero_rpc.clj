(ns monero-store.adapters.monero-rpc
  "IChainWallet over a monero-wallet-rpc the store runs itself.

  Only this namespace knows monero-java exists, and it is on its own source
  root behind the `:monero-rpc` alias — a deployment that watches through a
  gateway instead never loads the SDK at all.

  Correlation is by subaddress: one is opened per invoice, labelled with the
  invoice id, and the invoice records the address. Settlement is read back for
  that address.

  Four things here are about a wallet being a LIVE daemon rather than a
  library: the connection is retried until it succeeds instead of memoized
  once, the configured account is made addressable before a customer needs it,
  a sweep over many invoices costs one refresh instead of one per invoice, and
  `IWalletProbe` answers whether the endpoint can be reached and authenticated
  at all."
  (:require [malli.core :as m]
            [monero-store.collect.wallet :as wallet]
            [monero-store.schema :as schema]
            [taoensso.timbre :as log])
  (:import (monero.common MoneroError)
           (monero.wallet MoneroWalletRpc)
           (monero.wallet.model MoneroIncomingTransfer MoneroSubaddress
                                MoneroTransferQuery MoneroTxWallet)))

(defn- subaddress->value
  [^MoneroSubaddress sub]
  {:subaddress/address (.getAddress sub)
   :subaddress/account-index (long (or (.getAccountIndex sub) 0))
   :subaddress/index (long (or (.getIndex sub) 0))})

(defn- transfer->value
  [^MoneroIncomingTransfer transfer]
  (let [^MoneroTxWallet tx (.getTx transfer)]
    {:transfer/tx-hash (.getHash tx)
     :transfer/amount (.longValueExact (.getAmount transfer))
     :transfer/confirmations (long (or (.getNumConfirmations tx) 0))
     :transfer/locked? (boolean (.isLocked tx))
     :transfer/double-spend? (boolean (.isDoubleSpendSeen tx))}))

(defn observation
  "WalletObservation for `address` out of the wallet's `transfers`."
  [address transfers]
  {:wallet/address address
   :wallet/transfers (mapv transfer->value transfers)})

;; ---------------------------------------------------------------------------
;; a wallet is a live daemon, not a library

(defn retrying-connection
  "An IDeref over `open!` that caches only SUCCESS.

  `delay` memoizes the thrown exception as well as the value, so a wallet that
  happened to be down at the first call would stay dead for the life of the
  process even after it came back. This retries until it gets a connection."
  [open!]
  (let [cached (atom nil)
        lock   (Object.)]
    (reify clojure.lang.IDeref
      (deref [_]
        (or @cached
            (locking lock
              (or @cached (reset! cached (open!)))))))))

(defn ensure-account-index!
  "Grow the wallet until `account-index` is addressable; return the count.

  `count-accounts` reads the current number of accounts, `create-account!` adds
  one. A wallet only ever has account 0 until someone creates another, so
  without this a deployment that pins account 1 discovers the account is
  missing at the FIRST customer invoice, as an `open-address!` failure.

  Throws ex-info `:monero-store/account-not-creatable` rather than looping
  forever against a wallet that refuses to grow. Creating a subaddress account
  moves no funds and needs no spend key."
  [count-accounts create-account! account-index]
  (let [target (long account-index)]
    (loop [n (long (count-accounts))
           attempts 0]
      (cond
        (> n target) n

        (> attempts (inc target))
        (throw (ex-info "monero-wallet-rpc would not create the configured account"
                        {:type :monero-store/account-not-creatable
                         :account-index target
                         :accounts n}))

        :else
        (do (create-account!)
            (recur (long (count-accounts)) (inc attempts)))))))

(defn ensure-wallet-account!
  "Boundary form of `ensure-account-index!` over a live monero-wallet-rpc."
  [^MoneroWalletRpc wallet account-index]
  (ensure-account-index! #(count (.getAccounts wallet))
                         #(.createAccount wallet)
                         account-index))

(defn sync-due?
  "Whether a wallet refresh is owed at `now`, claiming the slot if so.

  A reconciliation sweep asks about every open invoice in one pass, and syncing
  per invoice would refresh the wallet once per invoice. Throttling here rather
  than at the caller keeps the sweep and a browser poll honest about freshness
  without either knowing about the other. Compare-and-set, so concurrent
  callers make one refresh, not N."
  [last-sync interval-ms now]
  (let [previous @last-sync]
    (and (or (nil? previous) (>= (- now previous) (long interval-ms)))
         (compare-and-set! last-sync previous now))))

(defn probe-due?
  "Whether a reachability probe is owed at `now`, claiming the slot if so.

  Pure over the atom, so the TTL is testable without a wallet. Same shape as
  `sync-due?`: compare-and-set means concurrent callers make one call, not N."
  [probe ttl-ms now]
  (let [previous @probe]
    (and (or (nil? (:at previous))
             (>= (- now (:at previous)) (long ttl-ms)))
         (compare-and-set! probe previous (assoc previous :at now)))))

(defn rpc-wallet
  "IChainWallet backed by a monero-wallet-rpc endpoint.

  `:uri` is the endpoint — the server root, monero-java appends `/json_rpc`
  itself — with `:username`/`:password` its digest credentials.
  `:account-index` is the account subaddresses are opened under, and is made
  addressable as part of opening rather than left for a customer to discover.
  `:sync?` refreshes the wallet before an observation, at most once per
  `:sync-interval-ms`.

  The connection is opened on first use, not at construction, so an
  unreachable daemon fails where it is used rather than at boot — and only a
  SUCCESSFUL connection is cached, so a wallet that was down at the first call
  is picked up once it comes back.

  Also an `IWalletProbe`: `reachable?` asks the wallet for its height, at most
  once per `:probe-ttl-ms`. The wallet demands digest auth and answers 401
  without credentials, so nothing short of a real call distinguishes a
  configured wallet from a usable one."
  [{:keys [uri username password account-index sync? sync-interval-ms
           probe-ttl-ms clock open-wallet ensure-account!]
    :or {account-index 0
         sync? true
         sync-interval-ms 5000
         probe-ttl-ms 30000
         clock #(System/currentTimeMillis)}}]
  (let [open      (or open-wallet
                      #(MoneroWalletRpc. ^String uri ^String username ^String password))
        ensure    (or ensure-account! ensure-wallet-account!)
        wallet    (retrying-connection #(doto (open) (ensure account-index)))
        last-sync (atom nil)
        probe     (atom {:at nil :ok? false})]
    (reify
      wallet/IWalletProbe
      (reachable? [_]
        (let [now (clock)]
          (if (probe-due? probe probe-ttl-ms now)
            (let [ok? (try
                        (.getHeight ^MoneroWalletRpc @wallet)
                        true
                        (catch Throwable _ false))]
              (swap! probe assoc :at now :ok? ok?)
              ok?)
            (boolean (:ok? @probe)))))

      wallet/IChainWallet
      (open-address! [_ {:opening/keys [label]}]
        (subaddress->value (.createSubaddress ^MoneroWalletRpc @wallet
                                              (int account-index)
                                              ^String (str label))))

      (observe [_ address]
        (try
          (let [^MoneroWalletRpc w @wallet]
            (when (and sync? (sync-due? last-sync sync-interval-ms (clock)))
              (.sync w))
            (observation address
                         (.getIncomingTransfers w (doto (MoneroTransferQuery.)
                                                    (.setAddress ^String address)))))
          (catch MoneroError e
            (log/warn "wallet cannot observe subaddress"
                      {:address address :error (.getMessage e)})
            nil))))))

;; ---------------------------------------------------------------------------
;; contracts. SDK objects are :any in; the value objects out are the contract.

(m/=> subaddress->value [:=> [:cat :any] schema/Subaddress])

(m/=> transfer->value [:=> [:cat :any] schema/WalletTransfer])

(m/=> observation [:=> [:cat schema/NonBlank :any] schema/WalletObservation])

(m/=> rpc-wallet [:=> [:cat [:map
                            [:uri schema/NonBlank]
                            [:username {:optional true} [:maybe :string]]
                            [:password {:optional true} [:maybe :string]]
                            [:account-index {:optional true} [:int {:min 0}]]
                            [:sync? {:optional true} :boolean]
                            [:sync-interval-ms {:optional true} [:int {:min 0}]]
                            [:probe-ttl-ms {:optional true} [:int {:min 0}]]
                            [:clock {:optional true} [:fn fn?]]
                            [:open-wallet {:optional true} [:maybe [:fn fn?]]]
                            [:ensure-account! {:optional true} [:maybe [:fn fn?]]]]]
                   [:fn #(satisfies? wallet/IChainWallet %)]])

(m/=> retrying-connection [:=> [:cat [:fn fn?]] :any])

(m/=> ensure-account-index! [:=> [:cat [:fn fn?] [:fn fn?] [:int {:min 0}]] [:int {:min 0}]])

(m/=> ensure-wallet-account! [:=> [:cat :any [:int {:min 0}]] [:int {:min 0}]])

(m/=> sync-due? [:=> [:cat :any [:int {:min 0}] :int] :boolean])

(m/=> probe-due? [:=> [:cat :any [:int {:min 0}] :int] :boolean])
