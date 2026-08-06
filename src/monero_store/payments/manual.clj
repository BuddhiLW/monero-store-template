(ns monero-store.payments.manual
  "Operator-asserted payment. The rail for cash, bank transfer, a donation
  platform with no API, and comps.

  Its profile declares `:provider/webhook-auth :none`, which is what keeps it
  off the HTTP settlement surface entirely: `interpret` grants on nothing but
  the invoice's own amount, so reaching it from the wire would be a way to be
  paid by asking. It is reachable only from an operator session."
  (:require [monero-store.payments.provider :as provider]
            [monero-store.schema :as schema]))

(def profile
  {:provider/id :manual
   :provider/currency :usd
   :provider/min-confirmations 0
   :provider/underpay-tolerance 0
   :provider/settles-async? false
   :provider/settlement-poll? false
   :provider/webhook-auth :none})

(defn- reference-of
  [invoice]
  (or (:invoice/external-ref invoice) (str "manual:" (:invoice/id invoice))))

(defn ->rail []
  (reify provider/IPaymentRail
    (charge! [_ {:charge/keys [invoice-id amount]}]
      {:handle/provider :manual
       :handle/external-ref (str "manual:" invoice-id)
       :handle/pay-to nil
       :handle/redirect-url nil
       :handle/amount amount})

    (interpret [_ _payload invoice]
      (let [reference (reference-of invoice)]
        {:settlement/provider :manual
         :settlement/external-ref reference
         :settlement/status :settled
         :settlement/paid-amount (:invoice/amount invoice)
         :settlement/expected-amount (:invoice/amount invoice)
         :settlement/confirmations 0
         :settlement/references [reference]}))

    (notice-subject [_ _notice] nil)
    (verify-notice [_ _notice _invoice] nil)
    (poll [_ _invoice] nil)

    (resume [_ invoice]
      {:handle/provider :manual
       :handle/external-ref (reference-of invoice)
       :handle/pay-to nil
       :handle/redirect-url nil
       :handle/amount (schema/money (:invoice/currency invoice) (:invoice/amount invoice))})))

(defn entry
  "A registry entry {:profile :rail} for the manual rail."
  ([] (entry {}))
  ([config]
   {:profile (merge profile (select-keys config (keys profile)))
    :rail (->rail)}))
