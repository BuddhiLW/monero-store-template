(ns monero-store.payments.hosted
  "The hosted-checkout rail: the customer pays on the processor's page.

  Correlation is by session id, and the money is read back from the session
  itself. What differs between processors — which event names mean settled,
  where the amount sits in the body, how a notice is authenticated — is a
  READER, supplied as data. This namespace holds the behaviour every hosted
  checkout shares; `payments.stripe` is one reader over it, and a second
  processor is a second reader, not a second rail."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [monero-store.adt :as adt]
            [monero-store.collect.cards :as cards]
            [monero-store.payments.provider :as provider]
            [monero-store.schema :as schema]))

(def default-profile
  "No confirmations to wait for, no underpayment tolerated, and notices that
  carry their own signature."
  {:provider/id :cards
   :provider/currency :usd
   :provider/min-confirmations 0
   :provider/underpay-tolerance 0
   :provider/settles-async? true
   :provider/settlement-poll? true
   :provider/webhook-auth :signed-payload})

(def default-reader
  "The shape of a processor notice, as data.

  `:reader/authentic?` is the whole authentication boundary: a reader that
  cannot check a signature returns false and the rail settles by polling
  alone, which is always safe and merely slower."
  {:reader/settled-events #{}
   :reader/failed-events #{}
   :reader/event-type-path [:type]
   :reader/object-path [:data :object]
   :reader/subject-key :client_reference_id
   :reader/object-id-key :id
   :reader/amount-keys [:amount_total :amount_paid]
   :reader/currency-key :currency
   :reader/paid-statuses #{"paid" "no_payment_required"}
   :reader/open-status "open"
   :reader/expired-status "expired"
   :reader/authentic? (fn [_notice] false)})

;; ---------------------------------------------------------------------------
;; reading a notice

(defn event-type
  [reader payload]
  (get-in payload (:reader/event-type-path reader)))

(defn event-object
  [reader payload]
  (get-in payload (:reader/object-path reader)))

(defn event-amount
  "The paid amount the event reports, or 0."
  [reader object]
  (long (or (some #(get object %) (:reader/amount-keys reader)) 0)))

(defn subject-of
  "The invoice id `notice` claims, as a uuid, or nil."
  [reader notice]
  (some-> (get (event-object reader (:notice/payload notice)) (:reader/subject-key reader))
          str
          parse-uuid))

(defn event-matches-invoice?
  "True when the event's object names `invoice` — by the reference set when the
  checkout was opened, or by the session id recorded on the invoice."
  [reader payload invoice]
  (let [object (event-object reader payload)
        external-ref (:invoice/external-ref invoice)]
    (boolean (or (and (:invoice/id invoice)
                      (= (str (:invoice/id invoice))
                         (str (get object (:reader/subject-key reader)))))
                 (and external-ref
                      (= external-ref (str (get object (:reader/object-id-key reader)))))))))

(defn currency-agrees?
  "True when `currency` — a processor's code, or nil — is `invoice`'s.

  A missing code agrees, and so does an invoice carrying none: a value only
  reaches here behind a valid signature or an unprompted read of our own
  session, so it is one of ours."
  [currency invoice]
  (let [named (some-> currency str str/lower-case not-empty)
        want (some-> (:invoice/currency invoice) name)]
    (boolean (or (nil? named) (nil? want) (= named want)))))

(defn parse-event
  "Normalize a processor event into a Settlement. Pure.

  `expected-amount` and `external-ref` come from the invoice, not the event.
  `external-ref` is also the settlement's only reference, so the same money
  seen as an event and as a polled session is one movement, not two."
  [reader provider-id payload expected-amount external-ref]
  (let [type (event-type reader payload)
        object (event-object reader payload)
        settled? (contains? (:reader/settled-events reader) type)]
    {:settlement/provider provider-id
     :settlement/external-ref external-ref
     :settlement/status (cond
                          (contains? (:reader/failed-events reader) type) :failed
                          settled? :settled
                          :else :pending)
     :settlement/paid-amount (if settled? (event-amount reader object) 0)
     :settlement/expected-amount (long expected-amount)
     :settlement/confirmations 0
     :settlement/references [external-ref]}))

;; ---------------------------------------------------------------------------
;; reading a session

(defn checkout-state
  "The CheckoutState `session` is in. Pure.

  A session is paid or it is not; expiry only means anything about one that
  never was."
  [reader session]
  (adt/checkout-state
   (cond
     (contains? (:reader/paid-statuses reader) (:checkout/payment-status session)) :checkout/paid
     (= (:reader/expired-status reader) (:checkout/status session)) :checkout/expired
     :else :checkout/awaiting)))

(defn session-settlement
  "Normalize a CheckoutSession into a Settlement for `invoice`.

  Nil when the session is denominated in another currency. The amount owed
  comes from the invoice; only the amount paid comes from the session."
  [reader provider-id session invoice]
  (when (currency-agrees? (:checkout/currency session) invoice)
    (let [session-id (:checkout/id session)
          base {:settlement/provider provider-id
                :settlement/external-ref session-id
                :settlement/expected-amount (long (:invoice/amount invoice))
                :settlement/confirmations 0
                :settlement/references [session-id]}]
      (adt/adt-case adt/CheckoutState (checkout-state reader session)
        :checkout/paid
        (assoc base
               :settlement/status :settled
               :settlement/paid-amount (long (or (:checkout/amount-total session) 0)))

        :checkout/expired
        (assoc base :settlement/status :failed :settlement/paid-amount 0)

        :checkout/awaiting
        (assoc base :settlement/status :pending :settlement/paid-amount 0)))))

(defn- checkout-of
  "The hosted checkout `invoice` was opened with, or nil.

  Nil when the invoice names no session, or the processor answers about a
  different one — an answer that is not about this session is not evidence
  about it."
  [gateway invoice]
  (when-let [session-id (some-> (:invoice/external-ref invoice) str not-empty)]
    (let [session (cards/read-checkout gateway session-id)]
      (when (= session-id (:checkout/id session))
        session))))

(defn- settlement-ref
  "Stable identifier for a settlement: the event object's id, else the
  invoice's own external ref, else the invoice id."
  [reader payload invoice]
  (or (some-> (get (event-object reader payload) (:reader/object-id-key reader)) str not-empty)
      (:invoice/external-ref invoice)
      (str (:invoice/id invoice))))

(defn ->rail
  "Build a hosted-checkout rail over an ICardGateway.

  `:gateway` opens and re-reads hosted checkouts, `:reader` says how to read
  that processor's notices, and `:success-url`/`:cancel-url` are where the
  customer lands afterwards."
  [{:keys [gateway reader provider-id success-url cancel-url]
    :or {provider-id (:provider/id default-profile)}}]
  (let [reader (merge default-reader reader)
        authentic? (:reader/authentic? reader)]
    (reify provider/IPaymentRail
      (charge! [_ {:charge/keys [invoice-id item customer amount]}]
        (let [session (cards/open-checkout!
                       gateway
                       {:checkout/reference (str invoice-id)
                        :checkout/email (:customer/email customer)
                        :checkout/product (:item/name item)
                        :checkout/currency (:money/currency amount)
                        :checkout/amount (:money/amount amount)
                        :checkout/success-url success-url
                        :checkout/cancel-url cancel-url})]
          {:handle/provider provider-id
           :handle/external-ref (:checkout/id session)
           :handle/pay-to nil
           :handle/redirect-url (:checkout/url session)
           :handle/amount amount}))

      (interpret [_ payload invoice]
        (parse-event reader provider-id payload
                     (:invoice/amount invoice)
                     (settlement-ref reader payload invoice)))

      (notice-subject [_ notice]
        (subject-of reader notice))

      (verify-notice [_ notice invoice]
        (let [payload (:notice/payload notice)]
          (when (and (authentic? notice)
                     (event-matches-invoice? reader payload invoice)
                     (currency-agrees? (get (event-object reader payload)
                                            (:reader/currency-key reader))
                                       invoice))
            (parse-event reader provider-id payload
                         (:invoice/amount invoice)
                         (settlement-ref reader payload invoice)))))

      (poll [_ invoice]
        (some-> (checkout-of gateway invoice)
                (as-> session (session-settlement reader provider-id session invoice))))

      (resume [_ invoice]
        (let [session (checkout-of gateway invoice)]
          (when (and (= (:reader/open-status reader) (:checkout/status session))
                     (seq (str (:checkout/url session))))
            {:handle/provider provider-id
             :handle/external-ref (:checkout/id session)
             :handle/pay-to nil
             :handle/redirect-url (:checkout/url session)
             :handle/amount (schema/money (:invoice/currency invoice)
                                          (:invoice/amount invoice))}))))))

(defn entry
  "A registry entry {:profile :rail} for a hosted-checkout rail."
  [config]
  (let [overrides (select-keys config (keys default-profile))
        provider-id (or (:provider-id config) (:provider/id default-profile))]
    {:profile (merge default-profile overrides {:provider/id provider-id})
     :rail (->rail config)}))

(m/=> parse-event
      [:=> [:cat :map :keyword [:maybe :map] [:int {:min 0}] schema/NonBlank] schema/Settlement])
(m/=> checkout-state [:=> [:cat :map :map] schema/CheckoutState])
(m/=> session-settlement
      [:=> [:cat :map :keyword :map :map] [:maybe schema/Settlement]])
(m/=> currency-agrees? [:=> [:cat :any :map] :boolean])
