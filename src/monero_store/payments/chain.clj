(ns monero-store.payments.chain
  "The chain rail: money that arrives at an address the store watches.

  Correlation is by address — charging opens one, the invoice records it, and
  settlement is read back from the wallet for that address. A settlement notice
  is only ever a prompt to re-read; its body is never evidence. That is what
  makes this rail safe over a gateway that signs nothing, and it is why the
  profile declares `:server-confirmed`.

  Any `IChainWallet` drives it: a monero-wallet-rpc the store runs, a MoneroPay
  instance watching on its behalf, or the fake. The rail cannot tell, which is
  the point."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [monero-store.collect.wallet :as wallet]
            [monero-store.payments.provider :as provider]
            [monero-store.schema :as schema]))

(def default-profile
  "Ten confirmations, no underpayment tolerated, settlement by polling.

  Ten is the depth at which a Monero reorg stops being a practical concern;
  a deployment that sells something cheap and revocable may lower it, and one
  that ships a licence key should not."
  {:provider/id :monero
   :provider/currency :xmr
   :provider/min-confirmations 10
   :provider/underpay-tolerance 0
   :provider/settles-async? true
   :provider/settlement-poll? true
   :provider/webhook-auth :server-confirmed})

(defn settlement-of
  "Normalize a WalletObservation into a Settlement for `provider-id`. Pure.

  `expected-amount` is the invoice's authoritative amount in atomic units —
  never the wallet's idea of what was expected. Transfers the daemon has
  flagged as double spends contribute neither funds nor confirmations. The
  payment is `:settled` once every contributing transfer is unlocked; how many
  confirmations that requires is the profile's decision, not this function's.

  When the wallet also reports its own unlocked total, the smaller of the two
  wins: a wallet more permissive than this store can never widen the gate."
  [provider-id observation expected-amount]
  (let [transfers (remove :transfer/double-spend? (:wallet/transfers observation))
        counted (reduce + 0 (map :transfer/amount transfers))
        unlocked (:wallet/unlocked-amount observation)
        paid (if (and unlocked (seq transfers)) (min counted (long unlocked)) counted)
        confirmations (if (seq transfers)
                        (apply min (map :transfer/confirmations transfers))
                        0)
        locked? (boolean (some :transfer/locked? transfers))]
    {:settlement/provider provider-id
     :settlement/external-ref (:wallet/address observation)
     :settlement/status (if (and (seq transfers) (not locked?)) :settled :pending)
     :settlement/paid-amount paid
     :settlement/expected-amount (long expected-amount)
     :settlement/confirmations confirmations
     :settlement/references (mapv :transfer/tx-hash transfers)}))

(defn- observe-settlement
  "What `wallet` currently reports for `invoice`'s address, as a Settlement.

  Nil when the invoice has no address yet, or the wallet cannot report on it:
  no observation is no evidence."
  [provider-id wallet invoice]
  (let [address (:invoice/external-ref invoice)]
    (when-not (str/blank? address)
      (when-let [observation (wallet/observe wallet address)]
        (settlement-of provider-id observation (:invoice/amount invoice))))))

(defn- notice-authentic?
  "True when a notice about `invoice` may prompt a re-read.

  With no callback secret configured every notice is allowed to prompt one —
  which is harmless, because the answer comes from the wallet either way. With
  a secret, the path token must match, which keeps a stranger from making the
  store hammer its own wallet."
  [callback-secret invoice notice]
  (if (str/blank? (str callback-secret))
    true
    (wallet/token-valid? callback-secret
                         (str (:invoice/id invoice))
                         (get-in notice [:notice/path-params :token]))))

(defn ->rail
  "Build a chain rail over an IChainWallet.

  `:wallet` is the port. `:provider-id` names this rail in the registry, so a
  store can run a self-hosted wallet and a watching gateway side by side.
  `:callback-secret`, when set, is the HMAC key for the token in the callback
  URL."
  [{:keys [wallet provider-id callback-secret] :or {provider-id :monero}}]
  (reify provider/IPaymentRail
    (charge! [_ {:charge/keys [invoice-id amount callback-url]}]
      (let [callback (when (and callback-url (not (str/blank? (str callback-secret))))
                       (str callback-url "/" (wallet/callback-token callback-secret (str invoice-id))))
            opened (wallet/open-address! wallet
                                         #:opening{:label (str "invoice " invoice-id)
                                                   :amount (:money/amount amount)
                                                   :callback-url (or callback callback-url)})
            address (:subaddress/address opened)]
        {:handle/provider provider-id
         :handle/external-ref address
         :handle/pay-to address
         :handle/redirect-url nil
         :handle/amount amount}))

    (interpret [_ observation invoice]
      (settlement-of provider-id observation (:invoice/amount invoice)))

    (notice-subject [_ notice]
      (some-> (get-in notice [:notice/path-params :invoice]) str parse-uuid))

    (verify-notice [_ notice invoice]
      (when (notice-authentic? callback-secret invoice notice)
        (observe-settlement provider-id wallet invoice)))

    (poll [_ invoice]
      (observe-settlement provider-id wallet invoice))

    (resume [_ invoice]
      (let [address (:invoice/external-ref invoice)]
        (when-not (str/blank? address)
          {:handle/provider provider-id
           :handle/external-ref address
           :handle/pay-to address
           :handle/redirect-url nil
           :handle/amount (schema/money (:invoice/currency invoice)
                                        (:invoice/amount invoice))})))))

(defn entry
  "A registry entry {:profile :rail} for a chain rail.

  `config` is `->rail`'s, plus any profile key to override — a deployment that
  wants six confirmations passes `:provider/min-confirmations 6` and nothing
  else changes."
  [config]
  (let [overrides (select-keys config (keys default-profile))
        provider-id (or (:provider-id config) (:provider/id default-profile))]
    {:profile (merge default-profile overrides {:provider/id provider-id})
     :rail (->rail config)}))

(m/=> settlement-of
      [:=> [:cat :keyword schema/WalletObservation [:int {:min 0}]] schema/Settlement])

(m/=> observe-settlement [:=> [:cat :keyword :any :map] [:maybe schema/Settlement]])

(m/=> notice-authentic? [:=> [:cat [:maybe :string] :map :map] :boolean])

(m/=> ->rail [:=> [:cat [:map [:wallet :any]
                             [:provider-id {:optional true} :keyword]
                             [:callback-secret {:optional true} [:maybe :string]]]] :any])

(m/=> entry [:=> [:cat :map] [:map [:profile schema/ProviderProfile] [:rail :any]]])
