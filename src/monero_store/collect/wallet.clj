(ns monero-store.collect.wallet
  "The chain wallet as a port, plus the adapters that need no SDK.

  Callers above this namespace see `schema/Subaddress` and
  `schema/WalletObservation` — never a gateway's JSON, never a wallet library's
  types. Two concrete adapters live here (MoneroPay over HTTP, and a fake); the
  monero-wallet-rpc adapter needs a Java SDK and lives behind its own alias in
  `adapters/monero-rpc`."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [monero-store.collect.http :as http]
            [monero-store.schema :as schema]
            [taoensso.timbre :as log])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(defprotocol IChainWallet
  (open-address! [this opening]
    "Open a fresh receiving address for one invoice. Returns a Subaddress.

    `opening` is {:opening/label :opening/amount :opening/callback-url}. A
    wallet the store runs itself needs only the label; a gateway that watches
    on the store's behalf needs the amount to know what covers the invoice, and
    the callback URL to say so early.")
  (observe [this address]
    "Incoming transfers the wallet has seen for `address`.

    Returns a WalletObservation, or nil when the wallet cannot report on the
    address — no observation is no evidence, which is not the same as zero."))

(defprotocol IWalletProbe
  (reachable? [this]
    "Can this wallet actually be reached AND authenticated right now?

    Separate from `IChainWallet` on purpose. An adapter with nothing meaningful
    to probe simply does not implement it, and a caller decides with
    `satisfies?` rather than every adapter carrying a stub that answers true.

    It exists because a connection opened lazily makes a configured wallet
    indistinguishable from a usable one: without this, the first customer
    invoice is what discovers that the credentials are wrong."))

;; ---------------------------------------------------------------------------
;; callback tokens
;;
;; A gateway that signs nothing still must not let a stranger prompt arbitrary
;; re-reads. The token is derived from the invoice id the store minted, so the
;; callback URL is computable before an address exists.

(defn- hmac-hex
  [secret message]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes (str secret) StandardCharsets/UTF_8) "HmacSHA256"))
    (format "%064x" (BigInteger. 1 (.doFinal mac (.getBytes (str message) StandardCharsets/UTF_8))))))

(defn callback-token
  "The token that belongs in the callback URL for `invoice-id`."
  [secret invoice-id]
  (subs (hmac-hex secret invoice-id) 0 32))

(defn token-valid?
  "True when `presented` is the token for `invoice-id`. Constant time.

  A blank secret accepts nothing: a check that fails open is not a check."
  [secret invoice-id presented]
  (boolean
   (when-not (or (str/blank? (str secret)) (str/blank? (str presented)))
     (MessageDigest/isEqual (.getBytes ^String (callback-token secret invoice-id) StandardCharsets/UTF_8)
                            (.getBytes ^String (str presented) StandardCharsets/UTF_8)))))

;; ---------------------------------------------------------------------------
;; MoneroPay

(defn- transfer->value
  [tx]
  {:transfer/tx-hash (str (:tx_hash tx))
   :transfer/amount (long (or (:amount tx) 0))
   :transfer/confirmations (long (or (:confirmations tx) 0))
   :transfer/locked? (boolean (:locked tx))
   :transfer/double-spend? (boolean (:double_spend_seen tx))})

(defn moneropay-observation
  "Normalize a MoneroPay receive body into a WalletObservation.

  Handles both shapes the gateway emits: `transactions` on a read, and a
  singular `transaction` on a callback."
  [address payload]
  (let [transactions (or (:transactions payload)
                         (some-> (:transaction payload) vector)
                         [])]
    (cond-> {:wallet/address address
             :wallet/transfers (mapv transfer->value transactions)}
      (get-in payload [:amount :covered :unlocked])
      (assoc :wallet/unlocked-amount (long (get-in payload [:amount :covered :unlocked]))))))

(defn moneropay-wallet
  "IChainWallet over a MoneroPay instance.

  MoneroPay assigns one subaddress per expected amount and posts a callback
  when it sees money — with no signature of any kind. So the callback body is
  never evidence here: it is a prompt to re-read `/receive/{address}`, which is
  what `observe` does and what the rail above settles from."
  [{:keys [client base-url] :or {base-url "http://moneropay:5000"}}]
  (reify IChainWallet
    (open-address! [_ {:opening/keys [label amount callback-url]}]
      (let [response (http/request client
                                   {:http/method :post
                                    :http/url (str base-url "/receive")
                                    :http/headers {"content-type" "application/json"}
                                    :http/body (cond-> {:amount (long (or amount 0))
                                                        :description (str label)}
                                                 callback-url (assoc :callback_url callback-url))})
            address (get-in response [:http/body :address])]
        (when-not (and (http/ok? response) (seq (str address)))
          (throw (ex-info "gateway would not open an address"
                          {:monero-store/error :address-unavailable
                           :status (:http/status response)})))
        {:subaddress/address (str address)
         :subaddress/account-index 0
         :subaddress/index 0}))

    (observe [_ address]
      (let [response (http/request client
                                   {:http/method :get
                                    :http/url (str base-url "/receive/" address)})]
        (if (http/ok? response)
          (moneropay-observation address (:http/body response))
          (do (log/warn "gateway cannot report on address"
                        {:address address :status (:http/status response)})
              nil))))))

;; ---------------------------------------------------------------------------
;; fake

(defn fake-wallet
  "IChainWallet over an atom. Development, tests, and demos.

  `credit!` is how money 'arrives': it appends a transfer to whatever the
  wallet reports for that address, so the whole settlement path can be driven
  from a REPL without a daemon, a gateway, or a coin."
  ([] (fake-wallet {}))
  ([{:keys [prefix] :or {prefix "fake"}}]
   (let [state (atom {:next 0 :transfers {}})]
     (with-meta
       (reify IChainWallet
         (open-address! [_ {:opening/keys [label]}]
           (let [index (:next (swap! state update :next inc))
                 address (str prefix "-" index "-" (str/replace (str label) #"\s+" "-"))]
             (swap! state assoc-in [:transfers address] [])
             {:subaddress/address address
              :subaddress/account-index 0
              :subaddress/index index}))

         (observe [_ address]
           (when-let [transfers (get-in @state [:transfers address])]
             {:wallet/address address :wallet/transfers transfers})))
       {:state state}))))

(defn credit!
  "Add an incoming transfer of `amount` to `address` on a fake wallet."
  ([wallet address amount] (credit! wallet address amount {}))
  ([wallet address amount {:keys [confirmations locked? double-spend? tx-hash]
                           :or {confirmations 10 locked? false double-spend? false}}]
   (let [state (:state (meta wallet))
         transfer {:transfer/tx-hash (or tx-hash (str "tx-" (rand-int 1000000)))
                   :transfer/amount (long amount)
                   :transfer/confirmations (long confirmations)
                   :transfer/locked? (boolean locked?)
                   :transfer/double-spend? (boolean double-spend?)}]
     (swap! state update-in [:transfers address] (fnil conj []) transfer)
     transfer)))

(m/=> callback-token [:=> [:cat :any :any] schema/NonBlank])
(m/=> token-valid? [:=> [:cat :any :any :any] :boolean])
(m/=> moneropay-observation [:=> [:cat schema/NonBlank [:maybe :map]] schema/WalletObservation])

(m/=> hmac-hex [:=> [:cat :any :any] schema/NonBlank])

(m/=> transfer->value [:=> [:cat :map] schema/WalletTransfer])

(m/=> moneropay-wallet [:=> [:cat [:map [:client {:optional true} :any]
                                       [:base-url {:optional true} :string]]] :any])

(m/=> fake-wallet [:function [:=> :cat :any] [:=> [:cat [:map [:prefix {:optional true} :string]]] :any]])

(m/=> credit! [:function
               [:=> [:cat :any schema/NonBlank :int] schema/WalletTransfer]
               [:=> [:cat :any schema/NonBlank :int [:maybe :map]] schema/WalletTransfer]])
