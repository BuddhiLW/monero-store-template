(ns monero-store.adapters.monero-rpc
  "IChainWallet over a monero-wallet-rpc the store runs itself.

  Only this namespace knows monero-java exists, and it is on its own source
  root behind the `:monero-rpc` alias — a deployment that watches through a
  gateway instead never loads the SDK at all.

  Correlation is by subaddress: one is opened per invoice, labelled with the
  invoice id, and the invoice records the address. Settlement is read back for
  that address."
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

(defn rpc-wallet
  "IChainWallet backed by a monero-wallet-rpc endpoint.

  `:uri` is the endpoint, `:username`/`:password` its digest credentials,
  `:account-index` the account subaddresses are opened under, and `:sync?`
  refreshes the wallet before each observation. The connection is opened on
  first use, not at construction, so an unreachable daemon fails where it is
  used rather than at boot."
  [{:keys [uri username password account-index sync?]
    :or {account-index 0 sync? true}}]
  (let [wallet (delay (MoneroWalletRpc. ^String uri ^String username ^String password))]
    (reify wallet/IChainWallet
      (open-address! [_ {:opening/keys [label]}]
        (subaddress->value (.createSubaddress ^MoneroWalletRpc @wallet
                                              (int account-index)
                                              ^String (str label))))

      (observe [_ address]
        (try
          (let [^MoneroWalletRpc w @wallet]
            (when sync? (.sync w))
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
                            [:sync? {:optional true} :boolean]]]
                   [:fn #(satisfies? wallet/IChainWallet %)]])
