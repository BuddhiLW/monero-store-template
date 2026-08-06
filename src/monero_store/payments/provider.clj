(ns monero-store.payments.provider
  "Payment rails as data plus a protocol for their effects. The DIP seam.

  A ProviderProfile carries the measured behaviour of a rail — its currency,
  how many confirmations it needs, how much underpayment it tolerates, whether
  it can be polled, how its notices can be authenticated. `settle` reads that
  profile and never branches on which rail it is, so a new rail is a new entry
  in a registry and no edit anywhere below.

  The registry is a VALUE, built at boot and carried in deps. Nothing here is
  global: two stores in one process, or a test and the system it tests, hold
  their own."
  (:require [malli.core :as m]
            [monero-store.adt :as adt]
            [monero-store.schema :as schema]))

(defprotocol IPaymentRail
  (charge! [this request]
    "Open a payment for a ChargeRequest. Returns a ChargeHandle.")
  (interpret [this payload invoice]
    "Normalize a rail's own report into a Settlement for `invoice`.

    Trusts `payload` outright. Not an authentication boundary.")
  (notice-subject [this notice]
    "The id of the invoice `notice` claims to be about, or nil.

    A CLAIM, not proof: a processor that posts every event of an account to one
    endpoint cannot put the invoice in the path, so the notice has to name it.
    The invoice it names then selects the rail that must authenticate the
    notice. Nil for a rail whose notices name no invoice.")
  (verify-notice [this notice invoice]
    "Authenticate a settlement notice against `invoice`.

    Returns a Settlement, or nil when the notice is not authentic for this
    invoice.")
  (poll [this invoice]
    "Settlement for `invoice` as this rail's own system reports it now.

    Nothing prompts this read, so nothing about it can be forged. Nil when the
    rail cannot be asked, or has nothing to report.")
  (resume [this invoice]
    "The ChargeHandle an already-open `invoice` still implies.

    Nil when this rail cannot resume one — a hosted checkout that is no longer
    open cannot be handed back, and minting a new one would leave two live ways
    to pay a single invoice."))

;; ---------------------------------------------------------------------------
;; registry

(defn registry
  "A rail registry from `entries`, each {:profile .. :rail ..}.

  The profile is validated here: a rail whose declared behaviour is malformed
  must fail at boot, where an operator is watching, and not at the first
  customer."
  [entries]
  (into {}
        (map (fn [{:keys [profile rail]}]
               (schema/check! schema/ProviderProfile profile
                              {:monero-store/producer `registry})
               [(:provider/id profile) {:profile profile :rail rail}]))
        entries))

(defn profile
  "Registered ProviderProfile for `provider-id`, or nil."
  [rails provider-id]
  (get-in rails [provider-id :profile]))

(defn rail
  "Registered IPaymentRail for `provider-id`, or nil."
  [rails provider-id]
  (get-in rails [provider-id :rail]))

(defn ids
  "Ids of every registered rail."
  [rails]
  (set (keys rails)))

(defn currency-of
  "The currency `provider-id` settles in, or nil."
  [rails provider-id]
  (:provider/currency (profile rails provider-id)))

(defn currencies
  "Every currency some registered rail settles in."
  [rails]
  (into #{} (keep #(currency-of rails %)) (keys rails)))

;; ---------------------------------------------------------------------------
;; what a profile permits

(defn webhook-settleable?
  "True when `provider-id`'s profile admits settlement notices over HTTP."
  [rails provider-id]
  (not= :none (get (profile rails provider-id) :provider/webhook-auth :none)))

(defn webhook-rails
  "Ids of registered rails that accept settlement notices over HTTP."
  [rails]
  (into #{} (filter #(webhook-settleable? rails %)) (keys rails)))

(defn pollable?
  "True when `provider-id`'s profile admits being asked what it has seen."
  [rails provider-id]
  (boolean (get (profile rails provider-id) :provider/settlement-poll? false)))

;; ---------------------------------------------------------------------------
;; rail selection
;;
;; In every one of these, the INVOICE selects the rail. A caller naming a rail
;; is naming a claim to be checked, never the authority that checks it.

(defn settlement-for
  "Settlement authenticated for `invoice` out of `notice`, or nil.

  Nil unless the invoice's own provider is registered, its profile admits
  webhook settlement, and its rail authenticates the notice."
  [rails invoice notice]
  (let [provider-id (:invoice/provider invoice)]
    (when (webhook-settleable? rails provider-id)
      (when-let [selected (rail rails provider-id)]
        (verify-notice selected notice invoice)))))

(defn claimed-invoice-id
  "The invoice id `notice` claims, as the rail named by `provider-id` reads it.

  Reading a claim out of a body is not authentication and grants nothing: the
  invoice this yields is what then selects the rail that must verify the
  notice."
  [rails provider-id notice]
  (when-let [selected (rail rails provider-id)]
    (notice-subject selected notice)))

(defn polled-settlement
  "Settlement for `invoice` as its own rail reports it now, or nil.

  A rail whose profile does not admit polling is never asked."
  [rails invoice]
  (let [provider-id (:invoice/provider invoice)]
    (when (pollable? rails provider-id)
      (when-let [selected (rail rails provider-id)]
        (poll selected invoice)))))

(defn resumed-handle
  "The handle `invoice`'s own rail still implies for it, or nil."
  [rails invoice]
  (when-let [selected (rail rails (:invoice/provider invoice))]
    (resume selected invoice)))

;; ---------------------------------------------------------------------------
;; the decision

(defn settle
  "Decide what a Settlement means under its provider's profile.

  Pure, and open for extension: every threshold comes from the profile, so a
  new rail needs no change here. The order of the clauses is the policy —
  a failure is a failure whatever arrived, no money at all is pending rather
  than underpaid, and a shortfall outranks a confirmation count because money
  that never came cannot be waited for."
  [rails {:settlement/keys [status paid-amount expected-amount confirmations] :as settlement}]
  (let [{:provider/keys [min-confirmations underpay-tolerance]
         :or {min-confirmations 0 underpay-tolerance 0}}
        (profile rails (:settlement/provider settlement))
        shortfall (- (long expected-amount) (long paid-amount))]
    (adt/settlement-outcome
     (cond
       (= :failed status) :settle/reject
       (zero? (long paid-amount)) :settle/pending
       (> shortfall (long underpay-tolerance)) :settle/underpaid
       (< (long confirmations) (long min-confirmations)) :settle/pending
       (= :pending status) :settle/pending
       :else :settle/grant))))

(m/=> profile [:=> [:cat :map :keyword] [:maybe schema/ProviderProfile]])
(m/=> settle [:=> [:cat :map schema/Settlement] schema/SettlementOutcome])
(m/=> webhook-settleable? [:=> [:cat :map :keyword] :boolean])
(m/=> pollable? [:=> [:cat :map :keyword] :boolean])
(m/=> settlement-for [:=> [:cat :map schema/Invoice :map] [:maybe schema/Settlement]])
