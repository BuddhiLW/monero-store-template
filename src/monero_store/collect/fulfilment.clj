(ns monero-store.collect.fulfilment
  "What the store does once money is confirmed — as a port.

  This is the seam the host application implements: grant a licence, flip a
  feature, ship a key, extend a subscription. The store decides WHETHER; the
  host decides WHAT.

  A fulfilment MUST be idempotent. The store gates on the invoice's
  pending -> paid transition, so it calls `fulfil!` once per invoice under
  normal operation — but a process that dies between the claim and the call is
  retried by the next sweep, and at-least-once is the only delivery a store
  and a foreign system can honestly agree on."
  (:require [taoensso.timbre :as log]
            [monero-store.collect.store :as store]
            [malli.core :as m]))

(defprotocol IFulfilment
  (fulfil! [this grant]
    "Hand over what `grant` bought. Must be idempotent for one invoice id.

    `grant` is {:fulfilment/invoice-id :fulfilment/customer-id
    :fulfilment/item-id :fulfilment/period-end}. Throwing means the store did
    not manage to deliver: the invoice's claim is released and the sweep will
    try again.")
  (revoke! [this grant reason]
    "Take back what `grant` handed over. Called for a chargeback or an
    operator reversal, never by settlement itself."))

(defn noop
  "Fulfilment that does nothing. The default: a store that only takes money."
  []
  (reify IFulfilment
    (fulfil! [_ _grant] nil)
    (revoke! [_ _grant _reason] nil)))

(defn logging
  "Fulfilment that only announces itself. For bringing a deployment up before
  the host side exists."
  []
  (reify IFulfilment
    (fulfil! [_ grant] (log/info "fulfil" grant) grant)
    (revoke! [_ grant reason] (log/info "revoke" {:grant grant :reason reason}) grant)))

(defn ledger
  "Fulfilment that records the grant in the order store.

  Useful on its own — the store becomes the record of who bought what — and as
  one arm of a `composite` alongside the host's real delivery."
  [order-store]
  (reify IFulfilment
    (fulfil! [_ grant] (store/record-fulfilment! order-store grant))
    (revoke! [_ grant reason]
      (store/record-fulfilment! order-store (assoc grant :fulfilment/revoked reason)))))

(defn handler
  "Fulfilment from a plain function of the grant. The host application's seam
  when it has no reason to reify a protocol."
  [fulfil-fn]
  (reify IFulfilment
    (fulfil! [_ grant] (fulfil-fn grant))
    (revoke! [_ _grant _reason] nil)))

(defn composite
  "Fulfilment that runs every one of `fulfilments`, in order.

  The first to throw stops the rest, and the store treats the whole grant as
  undelivered — so the arms must be individually idempotent, which is the same
  requirement each of them already carries."
  [fulfilments]
  (reify IFulfilment
    (fulfil! [_ grant]
      (mapv #(fulfil! % grant) fulfilments))
    (revoke! [_ grant reason]
      (mapv #(revoke! % grant reason) fulfilments))))

;; ---------------------------------------------------------------------------
;; contracts

(m/=> noop [:=> :cat :any])

(m/=> logging [:=> :cat :any])

(m/=> ledger [:=> [:cat :any] :any])

(m/=> handler [:=> [:cat ifn?] :any])

(m/=> composite [:=> [:cat [:sequential :any]] :any])
