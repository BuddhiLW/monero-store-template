(ns monero-store.promote.progress
  "How far the money for an invoice has actually got. Pure.

  An invoice's own status cannot answer this: it reads pending before anything
  was sent and pending at nine confirmations of ten. A store that records
  payment rows reads progress from `progress`; one that keeps only the current
  settlement reads it from `settlement-progress`. Both answer a PaymentProgress
  and both take every threshold from the rail's profile."
  (:require [malli.core :as m]
            [monero-store.schema :as schema])
  (:import (java.util Date)))

(defn- contributing?
  [payment]
  (not= :rejected (:payment/resolution payment)))

(defn- millis
  [^Date d]
  (if d (.getTime d) 0))

(defn fullest
  "The fullest of `payments`, or nil when there are none.

  A payment row is a SNAPSHOT of the whole set of movements the rail had seen,
  not one addend of it: a second transfer produces a new row naming both, so
  adding the rows counts the first transfer twice. The fullest snapshot is the
  current picture, and its depth is the depth of its shallowest movement."
  [payments]
  (->> payments
       (sort-by (juxt #(or (:payment/amount %) 0) #(millis (:payment/seen-at %))))
       last))

(defn state
  "What is true about `invoice` right now, given the money observed against it.

  `observed-shortfall` is how far the money SEEN falls short, where the rail can
  report a figure before its maturity cap. Enough seen is `:confirming` and not
  `:short`: a payer who sent the full amount and is waiting for it to unlock has
  not underpaid, and telling them they have is both wrong and the way to get
  paid twice. Nil where the rail cannot say, which is the four-argument arity
  and the behaviour every existing caller had."
  ([invoice received shortfall tolerance]
   (state invoice received shortfall tolerance nil))
  ([invoice received shortfall tolerance observed-shortfall]
   (case (:invoice/status invoice)
     :paid :settled
     :failed :refused
     :expired :lapsed
     (cond
       (and observed-shortfall
            (<= (long observed-shortfall) (long (or tolerance 0)))) :confirming
       (not (pos? received)) :awaiting
       (> shortfall (or tolerance 0)) :short
       :else :confirming))))

(defn- assemble
  "A PaymentProgress from an invoice, a rail profile and the figures observed.

  `observed` is money SEEN before any maturity cap, and is omitted rather than
  guessed when the rail cannot report one: repeating `received` there would
  claim there is no maturity gap."
  [invoice profile {:keys [received observed depth first-seen]}]
  (let [expected (or (:invoice/amount invoice) 0)
        required (or (:provider/min-confirmations profile) 0)
        shortfall (max 0 (- expected received))
        observed-shortfall (when (some? observed) (max 0 (- expected (long observed))))
        left (max 0 (- required depth))
        interval (:provider/confirmation-interval-ms profile)
        state (state invoice received shortfall
                     (:provider/underpay-tolerance profile)
                     observed-shortfall)]
    (cond-> {:progress/state state
             :progress/confirmations depth
             :progress/required required
             :progress/received received
             :progress/expected expected
             :progress/shortfall shortfall
             :progress/currency (:invoice/currency invoice)}
      (some? observed) (assoc :progress/observed observed)
      first-seen (assoc :progress/first-seen-at first-seen)

      (and interval (pos? left) (= :confirming state))
      (assoc :progress/eta-ms (* left (long interval))))))

(defn progress
  "How far `invoice` has got, given every payment recorded against it and its
  rail's `profile`.

  `:progress/first-seen-at` is the EARLIEST sighting, not the sighting of the
  fullest snapshot. `:progress/eta-ms` is present only when the rail declares
  how long a confirmation takes and there is confirming left to do."
  [invoice payments profile]
  (let [counted (filter contributing? payments)
        seen (fullest counted)]
    (assemble invoice profile
              {:received (or (:payment/amount seen) 0)
               :observed nil
               :depth (or (:payment/confirmations seen) 0)
               :first-seen (first (sort-by millis (keep :payment/seen-at counted)))})))

(defn settlement-progress
  "How far `invoice` has got, from one Settlement rather than recorded payments.

  For a store that keeps no payment rows: the settlement IS the current
  picture. `:progress/observed` carries the rail's uncapped figure when it
  reports one, which is what lets a screen say money has been seen while it is
  still maturing."
  [invoice settlement profile]
  (assemble invoice profile
            {:received (or (:settlement/paid-amount settlement) 0)
             :observed (:settlement/observed-amount settlement)
             :depth (or (:settlement/confirmations settlement) 0)
             :first-seen nil}))

(m/=> fullest [:=> [:cat [:sequential schema/Payment]] [:maybe schema/Payment]])

(m/=> progress [:=> [:cat schema/Invoice [:sequential schema/Payment]
                     [:maybe schema/ProviderProfile]]
                schema/PaymentProgress])

(m/=> settlement-progress [:=> [:cat schema/Invoice schema/Settlement
                                [:maybe schema/ProviderProfile]]
                           schema/PaymentProgress])

;; The internals ask for what they READ, not for the records their callers
;; happen to hold: `state` needs a status and `assemble` an amount and a
;; currency, and demanding a whole Invoice of either would be a contract about
;; the caller.

(def ^:private ProgressState
  [:enum :awaiting :confirming :short :settled :refused :lapsed])

(m/=> contributing? [:=> [:cat [:map [:payment/resolution {:optional true} :any]]]
                     :boolean])

(m/=> millis [:=> [:cat [:maybe inst?]] :int])

(m/=> state
      [:function
       [:=> [:cat [:map [:invoice/status {:optional true} [:maybe :keyword]]]
             :int :int [:maybe :int]]
        ProgressState]
       [:=> [:cat [:map [:invoice/status {:optional true} [:maybe :keyword]]]
             :int :int [:maybe :int] [:maybe :int]]
        ProgressState]])

(m/=> assemble
      [:=> [:cat [:map
                  [:invoice/status {:optional true} [:maybe :keyword]]
                  [:invoice/amount {:optional true} [:maybe :int]]
                  [:invoice/currency :keyword]]
            [:maybe schema/ProviderProfile]
            [:map
             [:received :int]
             [:observed {:optional true} [:maybe :int]]
             [:depth :int]
             [:first-seen {:optional true} [:maybe inst?]]]]
       schema/PaymentProgress])
