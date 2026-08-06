(ns monero-store.collect.store
  "Persistence as a port, plus the in-memory adapter the template runs on.

  Two operations carry the invariants; everything else is storage.

  `record-payment!` is idempotent per (invoice, reference): a rail that reports
  the same transaction forever must move the books exactly once.

  `claim-paid!` is a compare-and-set on the invoice's status. Only the caller
  that performs the pending -> paid transition is told it did, and that caller
  is the one — the only one — that may hand anything over."
  (:require [monero-store.schema :as schema]
            [malli.core :as m])
  (:import (java.util Date UUID)))

(defprotocol IOrderStore
  (upsert-customer! [this identity]
    "Customer for `identity` {:customer/ref :customer/email}, created if new.")
  (customer-by-id [this customer-id]
    "Customer, or nil.")
  (customer-by-ref [this customer-ref]
    "Customer the host application knows by `customer-ref`, or nil.")

  (insert-invoice! [this invoice]
    "Store `invoice` verbatim. Returns it.")
  (invoice-by-id [this invoice-id]
    "Invoice, or nil.")
  (live-invoice-for [this criteria]
    "The newest still-open invoice matching {:customer-id :item-id :provider},
    or nil.")
  (invoices-for-customer [this customer-id]
    "Every invoice of `customer-id`, newest first.")
  (open-invoices [this]
    "Every invoice that can still take money.")
  (attach-external-ref! [this invoice-id external-ref]
    "Record the rail's own identifier on the invoice. Returns the invoice.")
  (set-invoice-status! [this invoice-id status]
    "Move the invoice to `status` unconditionally. Returns the invoice.")
  (claim-paid! [this invoice-id]
    "Transition `invoice-id` from pending/underpaid to paid, once.

    Returns the invoice when THIS call performed the transition, nil when it
    was already paid or does not exist. The gate that makes a redelivered
    settlement notice harmless.")
  (release-claim! [this invoice-id]
    "Undo a claim whose fulfilment failed, so a later sweep retries it.")

  (record-payment! [this payment]
    "Record one observed movement of money. Returns the stored Payment, or nil
    when this exact (invoice, reference) was already recorded.")
  (payments-for [this invoice-id]
    "Payments recorded against `invoice-id`.")
  (unapplied-payments [this limit]
    "Money that was seen but could not be applied, newest first.")

  (record-fulfilment! [this fulfilment]
    "Record that the host application was told to hand something over.")
  (fulfilments-for [this customer-id]
    "Everything `customer-id` has been granted."))

(def open-statuses
  "Invoice statuses that can still take money and settle normally."
  #{:pending :underpaid})

;; ---------------------------------------------------------------------------
;; in-memory adapter

(defn- newest-first
  [values]
  (sort-by #(.getTime ^Date (or (:invoice/created-at %) (:payment/seen-at %) (Date. 0)))
           #(compare %2 %1)
           values))

(defn- claim
  "State after `invoice-id` moves to :paid, marking whether this move did it."
  [state invoice-id]
  (let [invoice (get-in state [:invoices invoice-id])]
    (if (and invoice (contains? open-statuses (:invoice/status invoice)))
      (-> state
          (assoc-in [:invoices invoice-id :invoice/status] :paid)
          (assoc :claimed invoice-id))
      (dissoc state :claimed))))

(defn memory-store
  "IOrderStore over an atom. The template's default, and what the tests run on.

  Every write is a `swap!`, so the compare-and-set `claim-paid!` depends on is
  the atom's own, not a lock this namespace has to keep."
  ([] (memory-store {}))
  ([{:keys [now-fn] :or {now-fn #(Date.)}}]
   (let [state (atom {:customers {} :invoices {} :payments {} :fulfilments []})]
     (reify IOrderStore
       (upsert-customer! [_ {:customer/keys [ref email]}]
         (or (first (filter #(= ref (:customer/ref %)) (vals (:customers @state))))
             (let [customer {:customer/id (UUID/randomUUID)
                             :customer/ref ref
                             :customer/email email
                             :customer/created-at (now-fn)}]
               (swap! state assoc-in [:customers (:customer/id customer)] customer)
               customer)))

       (customer-by-id [_ customer-id]
         (get-in @state [:customers customer-id]))

       (customer-by-ref [_ customer-ref]
         (first (filter #(= customer-ref (:customer/ref %)) (vals (:customers @state)))))

       (insert-invoice! [_ invoice]
         (swap! state assoc-in [:invoices (:invoice/id invoice)] invoice)
         invoice)

       (invoice-by-id [_ invoice-id]
         (get-in @state [:invoices invoice-id]))

       (live-invoice-for [_ {:keys [customer-id item-id provider]}]
         (->> (vals (:invoices @state))
              (filter #(and (= customer-id (:invoice/customer-id %))
                            (= item-id (:invoice/item-id %))
                            (= provider (:invoice/provider %))
                            (contains? open-statuses (:invoice/status %))))
              newest-first
              first))

       (invoices-for-customer [_ customer-id]
         (->> (vals (:invoices @state))
              (filter #(= customer-id (:invoice/customer-id %)))
              newest-first
              vec))

       (open-invoices [_]
         (->> (vals (:invoices @state))
              (filter #(contains? open-statuses (:invoice/status %)))
              newest-first
              vec))

       (attach-external-ref! [_ invoice-id external-ref]
         (get-in (swap! state assoc-in [:invoices invoice-id :invoice/external-ref] external-ref)
                 [:invoices invoice-id]))

       (set-invoice-status! [_ invoice-id status]
         (get-in (swap! state assoc-in [:invoices invoice-id :invoice/status] status)
                 [:invoices invoice-id]))

       (claim-paid! [_ invoice-id]
         (let [after (swap! state claim invoice-id)]
           (when (= invoice-id (:claimed after))
             (get-in after [:invoices invoice-id]))))

       (release-claim! [_ invoice-id]
         (swap! state (fn [s]
                        (cond-> s
                          (= :paid (get-in s [:invoices invoice-id :invoice/status]))
                          (assoc-in [:invoices invoice-id :invoice/status] :pending))))
         nil)

       (record-payment! [_ payment]
         (let [key [(:payment/invoice-id payment) (:payment/reference payment)]]
           (when-not (get-in @state [:payments key])
             (let [stored (assoc payment
                                 :payment/id (UUID/randomUUID)
                                 :payment/seen-at (now-fn))]
               (swap! state assoc-in [:payments key] stored)
               stored))))

       (payments-for [_ invoice-id]
         (->> (vals (:payments @state))
              (filter #(= invoice-id (:payment/invoice-id %)))
              vec))

       (unapplied-payments [_ limit]
         (->> (vals (:payments @state))
              (filter #(= :late (:payment/resolution %)))
              (sort-by #(.getTime ^Date (:payment/seen-at %)) #(compare %2 %1))
              (take limit)
              vec))

       (record-fulfilment! [_ fulfilment]
         (let [stored (assoc fulfilment :fulfilment/granted-at (now-fn))]
           (swap! state update :fulfilments conj stored)
           stored))

       (fulfilments-for [_ customer-id]
         (filterv #(= customer-id (:fulfilment/customer-id %)) (:fulfilments @state)))))))

(m/=> claim [:=> [:cat :map :uuid] :map])
(m/=> memory-store [:function
                    [:=> :cat [:fn some?]]
                    [:=> [:cat :map] [:fn some?]]])

(comment
  ;; Every invariant the port carries, in five lines.
  (let [store (memory-store)
        customer (upsert-customer! store #:customer{:ref "u-1" :email "a@b.c"})
        invoice {:invoice/id (UUID/randomUUID)
                 :invoice/customer-id (:customer/id customer)
                 :invoice/item-id :pro
                 :invoice/provider :monero
                 :invoice/status :pending
                 :invoice/amount 1000
                 :invoice/currency :xmr
                 :invoice/external-ref nil
                 :invoice/created-at (Date.)}]
    (insert-invoice! store invoice)
    [(some? (claim-paid! store (:invoice/id invoice)))   ; => true, this call won
     (nil? (claim-paid! store (:invoice/id invoice)))])) ; => true, the second cannot
