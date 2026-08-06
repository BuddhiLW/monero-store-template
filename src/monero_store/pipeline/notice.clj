(ns monero-store.pipeline.notice
  "Settling from a notice a provider posted.

  The rule the whole namespace exists to enforce: a notice NAMES an invoice,
  and the invoice SELECTS the rail that must authenticate the notice. A caller
  never chooses its own authenticator. The provider in the URL is a claim,
  checked against the invoice; a body that names an invoice is a claim, and
  granting on it directly is the vulnerability this shape removes.

  Returns a verdict value, not a response: the boundary decides what an HTTP
  status is."
  (:require [monero-store.collect.store :as store]
            [monero-store.payments.provider :as provider]
            [monero-store.pipeline.checkout :as checkout]
            [malli.core :as m]))

(defn subject-invoice
  "The invoice `notice` is about, or nil.

  Named in the path when the rail can put it there, and read out of the body by
  the claimed rail when it cannot — a processor that posts every event of an
  account to one endpoint has nowhere else to put it."
  [{:keys [store rails]} claimed notice]
  (let [named (some-> (get-in notice [:notice/path-params :invoice]) str parse-uuid)
        claimed-id (or named (provider/claimed-invoice-id rails claimed notice))]
    (when claimed-id
      (store/invoice-by-id store claimed-id))))

(defn apply-notice!
  "Authenticate `notice` for the invoice it names and settle from it.

  Verdicts:
  `:notice/applied` with the SettlementOutcome that was applied.
  `:notice/unauthenticated` — the invoice's own rail did not accept the notice.
  `:notice/unknown-invoice` — no such invoice, the claimed provider is not the
  invoice's own, or that rail admits no settlement over HTTP at all. All three
  are one verdict on purpose: telling a stranger which of them it was is
  telling them what to send next."
  [{:keys [rails] :as deps} claimed notice]
  (let [invoice (subject-invoice deps claimed notice)]
    (if (and invoice
             (= claimed (:invoice/provider invoice))
             (provider/webhook-settleable? rails (:invoice/provider invoice)))
      (if-let [settlement (provider/settlement-for rails invoice notice)]
        (if-let [outcome (checkout/settle! deps invoice settlement)]
          {:notice/verdict :notice/applied
           :notice/outcome (:adt/variant outcome)
           :notice/invoice-id (:invoice/id invoice)}
          {:notice/verdict :notice/unknown-invoice})
        {:notice/verdict :notice/unauthenticated})
      {:notice/verdict :notice/unknown-invoice})))

;; ---------------------------------------------------------------------------
;; contracts

(m/=> subject-invoice [:=> [:cat :map :keyword :map] [:maybe :map]])

(m/=> apply-notice! [:=> [:cat :map :keyword :map]
                     [:map [:notice/verdict [:enum :notice/applied :notice/unauthenticated :notice/unknown-invoice]]
                           [:notice/outcome {:optional true} :keyword]
                           [:notice/invoice-id {:optional true} :uuid]]])
