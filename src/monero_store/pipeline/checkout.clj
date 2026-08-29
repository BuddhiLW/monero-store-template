(ns monero-store.pipeline.checkout
  "Opening a payment, and applying its settlement.

  Two operations and one invariant between them: money is never accepted
  without what it bought being handed over, and what it bought is never handed
  over twice. The store's pending -> paid transition is the gate — it is a
  compare-and-set, so a redelivered notice, a poll that races a webhook, and a
  retry after a crash all converge on exactly one fulfilment."
  (:require [clojure.string :as str]
            [monero-store.adt :as adt]
            [monero-store.collect.fulfilment :as fulfilment]
            [monero-store.collect.store :as store]
            [monero-store.payments.provider :as provider]
            [monero-store.promote.catalog :as catalog]
            [monero-store.promote.invoice :as invoice]
            [monero-store.promote.quote :as quotes]
            [taoensso.timbre :as log]
            [monero-store.collect.analytics :as analytics]
            [malli.core :as m]
            [monero-store.schema :as schema]
            [monero-store.collect.ledger :as book])
  (:import (java.util Calendar Date UUID)))

(defn- period-end
  "When one period of `item` bought at `from` runs out, or nil for a one-off."
  [^Date from period]
  (when-let [field (case period
                     :monthly Calendar/MONTH
                     :yearly Calendar/YEAR
                     nil)]
    (let [cal (doto (Calendar/getInstance) (.setTime from))]
      (.add cal field 1)
      (.getTime cal))))

(defn- priced
  "What to charge for `item` in `currency`, and the quote it came from.

  The stored price is used verbatim in its own currency. Any other currency
  needs a live quote: `:rates-fn` supplies the current independent readings and
  `promote.quote` decides whether they agree. Returns {:money .. :quote ..};
  throws when no quote can be justified, BEFORE any row is written."
  [{:keys [rates-fn now-fn] :or {now-fn #(Date.)}} item currency]
  (let [stored (catalog/price item)]
    (if (= currency (:money/currency stored))
      {:money stored}
      (if-let [quoted (quotes/quote-for quotes/profile
                                        stored
                                        (if rates-fn (rates-fn) [])
                                        currency
                                        (now-fn))]
        {:money (:quote/amount quoted) :quote quoted}
        (throw (ex-info "no agreed rate for that currency"
                        {:monero-store/error :quote-required
                         :item-id (:item/id item)
                         :stored-currency (:money/currency stored)
                         :requested-currency currency}))))))

(defn- observation-reference
  "A stable name for the set of movements behind `settlement`.

  Every movement the rail named, sorted and joined — so re-seeing the same
  money is recognizably the same observation, while one new transaction makes
  a new one."
  [settlement]
  (let [refs (remove str/blank? (:settlement/references settlement))]
    (if (seq refs)
      (str/join "," (sort refs))
      (:settlement/external-ref settlement))))

(defn- record-observation!
  "Write what the rail saw to the payment ledger.

  Returns the stored Payment, or nil when this exact observation was already
  recorded — which is how a rail that reports the same transaction forever
  stops being interesting."
  [store invoice settlement resolution]
  (when (pos? (long (:settlement/paid-amount settlement 0)))
    (store/record-payment! store
                           {:payment/invoice-id (:invoice/id invoice)
                            :payment/provider (:settlement/provider settlement)
                            :payment/external-ref (:settlement/external-ref settlement)
                            :payment/reference (observation-reference settlement)
                            :payment/amount (:settlement/paid-amount settlement)
                            :payment/confirmations (:settlement/confirmations settlement)
                            :payment/resolution resolution})))

(defn open!
  "Open a payment for `item-id` with `provider-id`, or hand back the one the
  customer already has.

  A customer who reloads the checkout page must not accumulate invoices, so a
  still-chargeable one for the same item and rail is resumed when its rail can
  resume it. Otherwise the amount is quoted once, in the rail's own currency,
  and handed down — a rail never prices an item itself.

  `:variants` — the experiment arms this visitor is in — is recorded ON THE
  INVOICE rather than only reported, because the settlement that decides
  whether the experiment worked can arrive days later, from a webhook that
  knows nothing about the browser that started it.

  A new invoice is booked as revenue owed when `:ledger` is wired. A resumed
  one is not: it was booked when it was opened.

  Returns {:invoice .. :handle ..}. Throws ExceptionInfo
  (`:monero-store/error :quote-required`) before writing anything when the
  price cannot be established."
  [{:keys [store rails callback-base analytics ledger now-fn] :or {now-fn #(Date.)} :as deps}
   {:keys [customer item-id provider-id variants visitor]}]
  (let [live (store/live-invoice-for store {:customer-id (:customer/id customer)
                                            :item-id item-id
                                            :provider provider-id})
        resumed (when (and live (invoice/chargeable? live (now-fn)))
                  (provider/resumed-handle rails live))]
    (if resumed
      {:invoice live :handle resumed}
      (let [item (or (catalog/item (:catalog deps) item-id)
                     (throw (ex-info "no such item"
                                     {:monero-store/error :unknown-item :item-id item-id})))
            currency (or (provider/currency-of rails provider-id)
                         (throw (ex-info "no such payment rail"
                                         {:monero-store/error :unknown-provider
                                          :provider provider-id})))
            {:keys [money quote]} (priced deps item currency)
            invoice-id (UUID/randomUUID)
            invoice (cond-> {:invoice/id invoice-id
                             :invoice/customer-id (:customer/id customer)
                             :invoice/item-id item-id
                             :invoice/provider provider-id
                             :invoice/status :pending
                             :invoice/amount (:money/amount money)
                             :invoice/currency currency
                             :invoice/external-ref nil
                             :invoice/created-at (now-fn)}
                      (seq variants) (assoc :invoice/metadata {:variants variants})
                      quote
                      (assoc :invoice/quoted-rate (:quote/rate quote)
                             :invoice/quote-sources (:quote/sources quote)
                             :invoice/expires-at (:quote/expires-at quote)))
            _ (store/insert-invoice! store invoice)
            _ (when ledger
                (book/post! ledger (book/sale-entry invoice (now-fn))))
            handle (provider/charge!
                    (provider/rail rails provider-id)
                    {:charge/invoice-id invoice-id
                     :charge/item item
                     :charge/customer customer
                     :charge/amount money
                     :charge/callback-url (str callback-base "/webhooks/"
                                               (name provider-id) "/" invoice-id)})]
        (when analytics
          (analytics/track! analytics
                            (assoc (analytics/event :checkout/opened
                                                    {:item (name item-id)
                                                     :provider (name provider-id)
                                                     :currency (name currency)
                                                     :amount (:money/amount money)})
                                   :event/visitor visitor
                                   :event/variants variants)))
        {:invoice (store/attach-external-ref! store invoice-id (:handle/external-ref handle))
         :handle handle}))))

(defn- grant-for
  [invoice item now]
  {:fulfilment/invoice-id (:invoice/id invoice)
   :fulfilment/customer-id (:invoice/customer-id invoice)
   :fulfilment/item-id (:invoice/item-id invoice)
   :fulfilment/period-end (period-end now (:item/period item))})

(defn- hand-over!
  "Claim the invoice and fulfil it, exactly once.

  Only the caller that performs pending -> paid fulfils. A fulfilment that
  throws releases the claim, so the invoice is open again and the next sweep
  retries it: at-least-once is what a store and a foreign system can honestly
  agree on, and it is why `IFulfilment` demands idempotence."
  [{:keys [store fulfilment]} invoice item now]
  (if-let [claimed (store/claim-paid! store (:invoice/id invoice))]
    (let [grant (grant-for claimed item now)]
      (try
        (when fulfilment (fulfilment/fulfil! fulfilment grant))
        grant
        (catch Throwable t
          (store/release-claim! store (:invoice/id invoice))
          (log/error t "fulfilment failed; invoice released for retry"
                     {:invoice (:invoice/id invoice)})
          (throw t))))
    ;; Already paid: the notice is a redelivery, which is acknowledged and
    ;; nothing more. Handing over again is the failure this branch exists to
    ;; prevent.
    nil))

(defn settle!
  "Apply `settlement` to the invoice behind it.

  Money against an invoice that was already paid, or whose quote has lapsed, is
  recorded and reported as `:settle/late` rather than applied. That decision is
  made on the resolution alone.

  `:settle/suspect` leaves the invoice open and changes no status.

  A newly observed movement is booked to `:ledger` when one is wired, late
  included: money that arrived is money received whether or not it buys
  anything.

  Returns the SettlementOutcome that was applied, or nil when `settlement` was
  produced by a rail other than the invoice's own."
  [{:keys [store rails analytics ledger now-fn] :or {now-fn #(Date.)} :as deps} invoice settlement]
  (when (= (:invoice/provider invoice) (:settlement/provider settlement))
    (let [now (now-fn)
          resolution (invoice/resolution invoice now)
          report! (fn [outcome]
                    (when analytics
                      (analytics/track! analytics
                                        (assoc (analytics/event :invoice/settled
                                                                {:item (name (:invoice/item-id invoice))
                                                                 :provider (name (:invoice/provider invoice))
                                                                 :outcome (name (:adt/variant outcome))
                                                                 :amount (:settlement/paid-amount settlement)})
                                               :event/variants (get-in invoice [:invoice/metadata :variants]))))
                    outcome)
          payment (record-observation! store invoice settlement resolution)]
      (when (and ledger payment)
        (book/post! ledger (book/settlement-entry invoice payment)))
      (if (= :late resolution)
        (report! (adt/settlement-outcome :settle/late))
        (let [outcome (provider/settle rails settlement)
              item (catalog/item (:catalog deps) (:invoice/item-id invoice))]
          (adt/adt-case adt/SettlementOutcome outcome
            :settle/grant
            (do (hand-over! deps invoice item now) (report! outcome))

            :settle/underpaid
            (do (store/set-invoice-status! store (:invoice/id invoice) :underpaid) (report! outcome))

            :settle/reject
            (do (store/set-invoice-status! store (:invoice/id invoice) :failed) (report! outcome))

            :settle/suspect
            (report! outcome)

            :settle/late
            (report! outcome)

            :settle/pending
            outcome))))))

(defn grant!
  "Open a manual invoice for `customer` and settle it in the same breath.

  The operator path: no API can tell the store that a bank transfer landed, so
  a person asserts it. `reference` is whatever proves it to a human later — a
  transfer id, a bank line, a ticket — and is recorded on the payment so the
  grant is never unattributable."
  [{:keys [rails] :as deps} {:keys [customer item-id reference]}]
  (let [{:keys [invoice]} (open! deps {:customer customer
                                       :item-id item-id
                                       :provider-id :manual})
        rail (provider/rail rails :manual)
        settlement (assoc (provider/interpret rail {} invoice)
                          :settlement/references [(str "operator:" reference)])]
    (settle! deps invoice settlement)))

;; ---------------------------------------------------------------------------
;; contracts
;;
;; `deps` is :map rather than a shape: it is the injection point, and naming
;; every seam here would make adding one a change in two places.

(m/=> period-end [:=> [:cat schema/Instant [:maybe :keyword]] [:maybe schema/Instant]])

(m/=> priced [:=> [:cat :map schema/Item :keyword]
              [:map [:money schema/Money] [:quote {:optional true} [:maybe schema/Quote]]]])

(m/=> observation-reference [:=> [:cat schema/Settlement] [:maybe :string]])

(m/=> record-observation! [:=> [:cat :any schema/Invoice schema/Settlement :keyword] [:maybe :map]])

(m/=> open! [:=> [:cat :map [:map [:customer :map]
                                  [:item-id :keyword]
                                  [:provider-id :keyword]
                                  [:visitor {:optional true} [:maybe :string]]
                                  [:variants {:optional true} [:maybe :map]]]]
             [:map [:invoice schema/Invoice] [:handle :map]]])

(m/=> grant-for [:=> [:cat schema/Invoice [:maybe schema/Item] schema/Instant]
                 [:map [:fulfilment/invoice-id :uuid]
                       [:fulfilment/customer-id :uuid]
                       [:fulfilment/item-id :keyword]
                       [:fulfilment/period-end [:maybe schema/Instant]]]])

(m/=> hand-over! [:=> [:cat :map schema/Invoice [:maybe schema/Item] schema/Instant] [:maybe :map]])

(m/=> settle! [:=> [:cat :map schema/Invoice schema/Settlement] [:maybe schema/SettlementOutcome]])

(m/=> grant! [:=> [:cat :map [:map [:customer :map]
                                   [:item-id :keyword]
                                   [:reference {:optional true} :any]]]
              [:maybe schema/SettlementOutcome]])
