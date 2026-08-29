(ns monero-store.schema
  "Malli value objects for the storefront domain.

  Single source for `m/=>` contracts and schema-driven test synthesis. Nothing
  here names a payment provider: a rail is identified by a keyword the registry
  knows, so adding one never edits this namespace."
  (:require [malli.core :as m]
            [malli.error :as me]
            [monero-store.adt :as adt]
            [monero-store.currency :as currency]))

;; ---------------------------------------------------------------------------
;; primitives

(def NonBlank
  [:string {:min 1}])

(def Instant
  [:fn {:error/message "must be an instant"} inst?])

(def HexString
  [:re #"(?i)^[0-9a-f]+$"])

(def PositiveNumber
  [:and number? [:fn {:error/message "must be positive"} pos?]])

;; ---------------------------------------------------------------------------
;; money

(def CurrencyId
  "A currency the registry knows. Unregistered ids are not money."
  [:and :keyword [:fn {:error/message "must be a registered currency"} currency/known?]])

(defn- scale-agrees?
  [{:money/keys [currency scale]}]
  (= scale (currency/scale currency)))

(def Money
  "An exact amount of one currency: `:money/amount` integer minor units at
  `:money/scale` decimal places. Scale is the currency's, never the caller's."
  [:and
   [:map {:closed true}
    [:money/amount [:int {:min 0}]]
    [:money/currency CurrencyId]
    [:money/scale [:int {:min 0}]]]
   [:fn {:error/message "scale must be the currency's declared scale"} scale-agrees?]])

(def Pair
  "[base quote]. A price on this pair is how many `quote` minor-unit-free units
  one whole `base` unit costs."
  [:tuple CurrencyId CurrencyId])

;; ---------------------------------------------------------------------------
;; catalog

(def ItemId
  :keyword)

(def Period
  "How long one purchase of a recurring item lasts. `:once` never renews."
  [:enum :once :monthly :yearly])

(def Item
  "One sellable thing. `:item/metadata` is the host application's — this store
  stores it, correlates on it, and never reads it."
  [:map {:closed true}
   [:item/id ItemId]
   [:item/name NonBlank]
   [:item/blurb {:optional true} [:maybe :string]]
   [:item/price Money]
   [:item/period Period]
   [:item/listed? {:optional true} :boolean]
   [:item/metadata {:optional true} [:maybe :map]]])

;; ---------------------------------------------------------------------------
;; identity

(def Customer
  "Whoever is paying. `:customer/ref` is the id the host application knows them
  by; this store never authenticates anyone itself."
  [:map
   [:customer/id :uuid]
   [:customer/ref NonBlank]
   [:customer/email {:optional true} [:maybe :string]]
   [:customer/created-at Instant]])

;; ---------------------------------------------------------------------------
;; payments

(def ProviderId
  "Id of a registered payment rail. Deliberately open: a new rail is a registry
  entry, not an edit to this enum."
  :keyword)

(def InvoiceStatus
  [:enum :pending :paid :underpaid :expired :failed])

(def Invoice
  "A payment opened for one item.

  The quote keys are present only when the amount was converted from the item's
  stored price: they record the rate that was locked, who agreed it, and when
  the lock lapses."
  [:map
   [:invoice/id :uuid]
   [:invoice/customer-id :uuid]
   [:invoice/item-id ItemId]
   [:invoice/provider ProviderId]
   [:invoice/status InvoiceStatus]
   [:invoice/amount [:int {:min 0}]]
   [:invoice/currency CurrencyId]
   [:invoice/external-ref [:maybe NonBlank]]
   [:invoice/quoted-rate {:optional true} [:maybe number?]]
   [:invoice/quote-sources {:optional true} [:maybe [:sequential :keyword]]]
   [:invoice/expires-at {:optional true} [:maybe Instant]]
   [:invoice/metadata {:optional true} [:maybe :map]]
   [:invoice/created-at Instant]])

(def ChargeRequest
  "What a rail needs to open a payment. `:charge/amount` is the amount locked
  for this invoice, in the rail's own currency."
  [:map {:closed true}
   [:charge/invoice-id :uuid]
   [:charge/item Item]
   [:charge/customer Customer]
   [:charge/amount Money]
   [:charge/callback-url NonBlank]])

(def ChargeHandle
  "What a rail hands back so the customer can complete payment.

  `:handle/pay-to` is an address to send to; `:handle/redirect-url` a hosted
  page to visit. A rail supplies whichever it has, and both may be present."
  [:map {:closed true}
   [:handle/provider ProviderId]
   [:handle/external-ref NonBlank]
   [:handle/pay-to [:maybe NonBlank]]
   [:handle/redirect-url [:maybe NonBlank]]
   [:handle/amount Money]])

(def SettlementStatus
  [:enum :pending :settled :underpaid :failed])

(def Settlement
  "Normalized result of interpreting what a rail has seen.

  `:settlement/references` names the individual movements behind the amount.
  `:settlement/suspect?` says a movement was seen and rejected; it contributes
  no funds, and a rail that cannot observe such a thing omits the key."
  [:map {:closed true}
   [:settlement/provider ProviderId]
   [:settlement/external-ref NonBlank]
   [:settlement/status SettlementStatus]
   [:settlement/paid-amount [:int {:min 0}]]
   [:settlement/expected-amount [:int {:min 0}]]
   [:settlement/confirmations [:int {:min 0}]]
   [:settlement/suspect? {:optional true} :boolean]
   [:settlement/references {:optional true} [:sequential NonBlank]]])

(def PaymentResolution
  "What the store was able to do with an observed movement of money."
  [:enum :applied :late])

(def Payment
  "One observed movement of money against one invoice, recorded once."
  [:map
   [:payment/id :uuid]
   [:payment/invoice-id :uuid]
   [:payment/provider ProviderId]
   [:payment/external-ref NonBlank]
   [:payment/reference NonBlank]
   [:payment/amount [:int {:min 0}]]
   [:payment/confirmations [:int {:min 0}]]
   [:payment/resolution PaymentResolution]
   [:payment/seen-at Instant]])

(def ProviderProfile
  "Measured, swappable behaviour of a payment rail. The DIP swap point.

  `:provider/settlement-poll?` says whether the rail can be asked, unprompted,
  what it has seen — a rail with no way to call the store must be polled or it
  never settles at all. `:provider/webhook-auth` says how, if at all, a notice
  it posts can be authenticated."
  [:map {:closed true}
   [:provider/id ProviderId]
   [:provider/currency CurrencyId]
   [:provider/min-confirmations [:int {:min 0}]]
   [:provider/underpay-tolerance [:int {:min 0}]]
   [:provider/settles-async? :boolean]
   [:provider/settlement-poll? :boolean]
   [:provider/webhook-auth [:enum :none :signed-payload :path-token :server-confirmed]]])

(def WebhookNotice
  "A settlement notice as received at the HTTP boundary."
  [:map {:closed true}
   [:notice/raw-body :string]
   [:notice/payload [:maybe :map]]
   [:notice/headers [:map-of :string :string]]
   [:notice/path-params {:optional true} [:maybe :map]]
   [:notice/received-at [:int {:min 0}]]])

;; ---------------------------------------------------------------------------
;; evidence a rail settles from

(def Subaddress
  "An address opened for one invoice."
  [:map {:closed true}
   [:subaddress/address NonBlank]
   [:subaddress/account-index [:int {:min 0}]]
   [:subaddress/index [:int {:min 0}]]])

(def WalletTransfer
  "One incoming transfer a wallet has seen for an address."
  [:map {:closed true}
   [:transfer/tx-hash NonBlank]
   [:transfer/amount [:int {:min 0}]]
   [:transfer/confirmations [:int {:min 0}]]
   [:transfer/locked? :boolean]
   [:transfer/double-spend? :boolean]])

(def WalletObservation
  "What a wallet reports for one address at a point in time. The unit of
  evidence a chain rail settles from.

  `:wallet/unlocked-amount` is the wallet's own view of what is spendable; a
  wallet more permissive than this store's confirmation policy can never widen
  the gate, so the two are taken together and the smaller wins."
  [:map {:closed true}
   [:wallet/address NonBlank]
   [:wallet/transfers [:sequential WalletTransfer]]
   [:wallet/unlocked-amount {:optional true} [:maybe [:int {:min 0}]]]])

(def CheckoutRequest
  "What `ICardGateway/open-checkout!` is asked to open. `:checkout/reference`
  is the invoice id as a string; opening it twice must yield the same session."
  [:map {:closed true}
   [:checkout/reference NonBlank]
   [:checkout/email [:maybe :string]]
   [:checkout/product NonBlank]
   [:checkout/currency CurrencyId]
   [:checkout/amount [:int {:min 0}]]
   [:checkout/success-url NonBlank]
   [:checkout/cancel-url NonBlank]])

(def CheckoutSession
  "One hosted checkout as its processor reports it. The unit of evidence a
  processor rail settles from.

  `:checkout/status` is the session's own lifecycle (open, complete, expired)
  and `:checkout/payment-status` whether the money arrived; a session can be
  complete without being paid."
  [:map {:closed true}
   [:checkout/id NonBlank]
   [:checkout/url [:maybe NonBlank]]
   [:checkout/status [:maybe NonBlank]]
   [:checkout/payment-status [:maybe NonBlank]]
   [:checkout/currency [:maybe NonBlank]]
   [:checkout/amount-total [:int {:min 0}]]])

;; ---------------------------------------------------------------------------
;; pricing

(def RateSourceId
  "Id of an independent price source."
  :keyword)

(def Rate
  "One source's price for one pair, as that source reported it."
  [:map {:closed true}
   [:rate/source RateSourceId]
   [:rate/pair Pair]
   [:rate/price PositiveNumber]
   [:rate/as-of Instant]])

(def Quote
  "A stored price converted to another currency and locked for one invoice.

  `:quote/sources` names every source that agreed the rate, so a past charge
  stays auditable after the sources have moved on."
  [:map {:closed true}
   [:quote/price Money]
   [:quote/amount Money]
   [:quote/pair Pair]
   [:quote/rate PositiveNumber]
   [:quote/sources [:sequential {:min 1} RateSourceId]]
   [:quote/as-of Instant]
   [:quote/expires-at Instant]])

;; ---------------------------------------------------------------------------
;; decisions

(def SettlementOutcome
  "A SettlementOutcome ADT value. Variants are read off the sum itself."
  [:map {:closed true}
   [:adt/type [:= :SettlementOutcome]]
   [:adt/variant (into [:enum] (sort (:variants adt/SettlementOutcome)))]])

(def CheckoutState
  "A CheckoutState ADT value. Its variants come from the sum, as above."
  [:map {:closed true}
   [:adt/type [:= :CheckoutState]]
   [:adt/variant (into [:enum] (sort (:variants adt/CheckoutState)))]])

(def Endpoint
  "A service the store must be able to reach to settle through it."
  [:map {:closed true}
   [:endpoint/host NonBlank]
   [:endpoint/port [:int {:min 1 :max 65535}]]
   [:endpoint/label {:optional true} [:maybe NonBlank]]])

(def Reachability
  "A Reachability ADT value. Variants are read off the sum itself."
  [:map {:closed true}
   [:adt/type [:= :Reachability]]
   [:adt/variant (into [:enum] (sort (:variants adt/Reachability)))]])

(def ReachabilityReport
  "What one probe of one endpoint found."
  [:map {:closed true}
   [:reach/label NonBlank]
   [:reach/host NonBlank]
   [:reach/port [:int {:min 1 :max 65535}]]
   [:reach/outcome Reachability]
   [:reach/elapsed-ms [:int {:min 0}]]
   [:reach/detail [:maybe :string]]])

(def ReachabilitySummary
  "One round of probes over every endpoint a deployment must reach."
  [:map {:closed true}
   [:reach/checked [:int {:min 0}]]
   [:reach/unreachable [:int {:min 0}]]
   [:reach/ok? :boolean]
   [:reach/endpoints [:vector ReachabilityReport]]])

(def Fulfilment
  "What the host application was told to hand over, and when."
  [:map
   [:fulfilment/invoice-id :uuid]
   [:fulfilment/customer-id :uuid]
   [:fulfilment/item-id ItemId]
   [:fulfilment/period-end [:maybe Instant]]
   [:fulfilment/granted-at Instant]])

;; ---------------------------------------------------------------------------
;; operations

(defn check!
  "`value` when it satisfies `schema`.

  Throws ExceptionInfo (`:monero-store/error :schema-violation`) carrying a
  humanized explanation merged with `ctx` otherwise."
  ([schema value] (check! schema value nil))
  ([schema value ctx]
   (if (m/validate schema value)
     value
     (throw (ex-info "value violates its declared schema"
                     (merge {:monero-store/error :schema-violation
                             :explain (me/humanize (m/explain schema value))}
                            ctx))))))

(defn money
  "Money of `amount` minor units in `currency`, at that currency's scale.

  Throws ExceptionInfo for an unregistered currency, or when the result does
  not satisfy Money."
  [currency amount]
  (let [scale (currency/scale currency)]
    (when (nil? scale)
      (throw (ex-info "no declared scale for currency"
                      {:monero-store/error :unknown-currency
                       :currency currency})))
    (check! Money
            {:money/amount amount
             :money/currency currency
             :money/scale scale}
            {:monero-store/producer `money
             :currency currency})))

(m/=> money [:=> [:cat :keyword [:int {:min 0}]] Money])

(m/=> scale-agrees? [:=> [:cat :map] :boolean])

(m/=> check! [:function [:=> [:cat :any :any] :any] [:=> [:cat :any :any [:maybe :map]] :any]])
