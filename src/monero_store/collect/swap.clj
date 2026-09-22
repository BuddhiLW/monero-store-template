(ns monero-store.collect.swap
  "Taking a coin the store does not want to keep.

  A store that converts every incoming coin to one treasury currency has no use
  for a wallet per chain. It needs an address that turns into the coin it does
  keep. An exchange order provides exactly that: pay in here, receive XMR
  there, where `there` is the store's own wallet.

  So the order's pay-in address IS the invoice address, and the whole
  arrangement satisfies `IChainWallet` without a new rail. `payments.chain`
  cannot tell an exchange order from a subaddress, which is the property this
  namespace exists to preserve.

  What the buyer owes is denominated in the coin they agreed to send, and their
  debt is discharged when the exchange has that money. Whether the conversion
  then completes is the store's treasury problem and belongs in the operator
  queue, never in a buyer's waiting. `observation-of` is where that rule is
  actually encoded.

  The lookup key is the PAY-IN ADDRESS, not the provider's order id, because
  `IChainWallet/observe` is handed an address and nothing else. A provider that
  keys its own API by order id must therefore keep that mapping itself; the
  fake here keys by address directly."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [monero-store.adt :as adt]
            [monero-store.collect.wallet :as wallet]
            [monero-store.schema :as schema]
            [taoensso.timbre :as log]))

(defprotocol ISwapProvider
  (open-swap! [this order]
    "Open an exchange order. Returns a SwapOrder.

    `order` is #:swap{:from :to :amount :payout-address :refund-address :label}
    where `:amount` is minor units of `:from`. `:refund-address` is where the
    source coin goes if the exchange cannot fill the order, so it is required:
    a store that converts everything holds no wallet on that chain and has
    nowhere for a failure to land otherwise.")
  (swap-status [this pay-in-address]
    "The order paying in to `pay-in-address`, as the provider reports it now,
    or nil when it cannot report on that address.

    Nil is no evidence, which is not the same as an unpaid order."))

(defprotocol ISwapLimits
  "What a provider will and will not fill. Separate from `ISwapProvider` on
  purpose, following `IWalletProbe`: a provider with nothing to declare simply
  does not implement it, and a caller decides with `satisfies?` rather than
  every adapter carrying a stub that promises no limits."
  (limits-for [this from to]
    "`{:swap/min-amount :swap/max-amount}` in minor units of `from`, or nil."))

;; ---------------------------------------------------------------------------
;; what an order means, as evidence

(defn- transfer-of
  "One incoming transfer, as the exchange describes it.

  The confirmation count needs care. A store converting everything runs no node
  on the source chain, so it has NO independent view of those confirmations and
  cannot acquire one. The exchange accepting the funds as final is therefore
  the only finality oracle available, and an accepted order counts as at least
  one confirmation however few the provider reports, or a rail asking for one
  confirmation would never settle an order the exchange has already honoured.

  A provider that does report chain confirmations still wins when it reports
  MORE, so a rail configured to want six of them gets what it asked for from a
  provider able to say."
  [{:swap/keys [received-amount confirmations tx-hash]} locked?]
  {:transfer/tx-hash (or tx-hash "pending")
   :transfer/amount (long (or received-amount 0))
   :transfer/confirmations (if locked?
                             (long (or confirmations 0))
                             (max 1 (long (or confirmations 0))))
   :transfer/locked? (boolean locked?)
   :transfer/double-spend? false})

(defn observation-of
  "A SwapOrder as a WalletObservation in the SOURCE coin. Pure.

  The buyer's debt is measured in what they sent, so nothing here reports the
  payout currency. The state decides whether that money counts yet:

  - awaiting payment is no transfer at all, which is not a transfer of zero
  - confirming is a LOCKED transfer, so the rail sees money that is not final
  - received and everything after it is an unlocked transfer, because the
    exchange has accepted the funds and the buyer is done
  - refunded, failed and expired report no transfer, because the money is not
    the store's and asking the buyer again would be charging them twice

  `:swap/received-amount` is deliberately trusted over the amount asked for. An
  exchange that took less than the invoice wanted is an underpayment, and the
  rail above decides that with the profile's tolerance rather than here."
  [{:swap/keys [state pay-in-address] :as order}]
  (let [transfers (adt/adt-case adt/SwapState state
                    :swap/awaiting-payment []
                    :swap/confirming [(transfer-of order true)]
                    :swap/received [(transfer-of order false)]
                    :swap/converting [(transfer-of order false)]
                    :swap/delivered [(transfer-of order false)]
                    :swap/refunded []
                    :swap/failed []
                    :swap/expired [])]
    {:wallet/address pay-in-address
     :wallet/transfers transfers}))

;; ---------------------------------------------------------------------------
;; the wallet

(defn- opened-as-asked
  "`order` when the exchange agreed to the number the buyer will be shown.

  A provider that quotes back a different expected amount has not filled the
  request: the store would show one figure and the exchange would wait for
  another, and the invoice could never settle. Refusing here is the only place
  that mismatch is still cheap."
  [order requested]
  (let [expected (long (or (:swap/expected-amount order) 0))]
    (when-not (= expected (long requested))
      (throw (ex-info "exchange quoted an amount the invoice did not ask for"
                      {:monero-store/error :swap-amount-mismatch
                       :requested (long requested)
                       :expected expected})))
    (when (str/blank? (str (:swap/pay-in-address order)))
      (throw (ex-info "exchange opened an order with no pay-in address"
                      {:monero-store/error :address-unavailable})))
    order))

(defn ->wallet
  "An `IChainWallet` backed by an exchange order per invoice.

  `:provider` is the ISwapProvider, `:from` the coin the buyer sends, `:to` the
  coin the store keeps, `:payout-address` the store's own wallet and
  `:refund-address` where a failed order sends the source coin back."
  [{:keys [provider from to payout-address refund-address]}]
  (reify wallet/IChainWallet
    (open-address! [_ {:opening/keys [label amount]}]
      (let [order (-> (open-swap! provider #:swap{:from from
                                                  :to to
                                                  :amount (long (or amount 0))
                                                  :payout-address payout-address
                                                  :refund-address refund-address
                                                  :label (str label)})
                      (opened-as-asked (or amount 0)))]
        {:subaddress/address (:swap/pay-in-address order)
         :subaddress/account-index 0
         :subaddress/index 0}))

    (observe [_ address]
      (if-let [order (swap-status provider address)]
        (observation-of order)
        (do (log/warn "exchange cannot report on pay-in address" {:address address})
            nil)))))

(m/=> fake-provider
      [:function
       [:=> :cat :any]
       [:=> [:cat [:map [:prefix {:optional true} :string]
                        [:limits {:optional true} [:maybe :map]]]] :any]])

(m/=> pay!
      [:function
       [:=> [:cat :any schema/NonBlank :int] :nil]
       [:=> [:cat :any schema/NonBlank :int [:maybe :map]] :nil]])

(m/=> advance! [:=> [:cat :any schema/NonBlank :keyword] :nil])

(defn fillable?
  "True when `amount` is one `provider` will take for `from` to `to`.

  A provider that declares no limits is taken at its word. The point of asking
  BEFORE opening is that a checkout must be able to say a coin is unavailable
  for a small purchase, rather than open an order that can never be filled."
  [provider from to amount]
  (if-not (satisfies? ISwapLimits provider)
    true
    (let [{:swap/keys [min-amount max-amount]} (limits-for provider from to)]
      (and (or (nil? min-amount) (>= (long amount) (long min-amount)))
           (or (nil? max-amount) (<= (long amount) (long max-amount)))))))

;; ---------------------------------------------------------------------------
;; fake

(defn fake-provider
  "ISwapProvider over an atom. Development, tests, and demos.

  `pay!` is how the buyer's money 'arrives' and `advance!` how the exchange
  moves, so the whole settlement path can be driven from a REPL with no
  exchange, no key and no coin."
  ([] (fake-provider {}))
  ([{:keys [prefix limits] :or {prefix "swap"}}]
   (let [state (atom {:next 0 :orders {}})]
     (with-meta
       (reify
         ISwapProvider
         (open-swap! [_ {:swap/keys [from to amount payout-address label]}]
           (let [n (:next (swap! state update :next inc))
                 address (str prefix "-" (name from) "-" n)
                 order #:swap{:id (str "order-" n)
                              :provider :fake
                              :from from
                              :to to
                              :state (adt/swap-state :swap/awaiting-payment)
                              :pay-in-address address
                              :payout-address (str payout-address)
                              :expected-amount (long amount)}]
             (swap! state assoc-in [:orders address] order)
             (log/debug "fake swap opened" {:address address :label label})
             order))

         (swap-status [_ address]
           (get-in @state [:orders address]))

         ISwapLimits
         (limits-for [_ from to] (get limits [from to])))
       {:state state}))))

(defn pay!
  "The buyer sends `amount` to `address` on a fake provider, and the exchange
  sees it but has not yet accepted it."
  ([provider address amount] (pay! provider address amount {}))
  ([provider address amount {:keys [confirmations tx-hash]
                             :or {confirmations 0}}]
   (swap! (:state (meta provider)) update-in [:orders address]
          merge #:swap{:state (adt/swap-state :swap/confirming)
                       :received-amount (long amount)
                       :confirmations (long confirmations)
                       :tx-hash (or tx-hash (str "tx-" (rand-int 1000000)))})
   nil))

(defn advance!
  "Move the order at `address` to `variant` on a fake provider."
  [provider address variant]
  (swap! (:state (meta provider)) update-in [:orders address]
         assoc :swap/state (adt/swap-state variant))
  nil)

(m/=> observation-of [:=> [:cat schema/SwapOrder] schema/WalletObservation])
(m/=> transfer-of [:=> [:cat :map :boolean] schema/WalletTransfer])
(m/=> opened-as-asked [:=> [:cat :map :int] :map])
(m/=> fillable? [:=> [:cat :any :keyword :keyword :int] :boolean])
(m/=> ->wallet [:=> [:cat [:map [:provider :any]
                                [:from :keyword]
                                [:to :keyword]
                                [:payout-address schema/NonBlank]
                                [:refund-address {:optional true} [:maybe :string]]]] :any])
