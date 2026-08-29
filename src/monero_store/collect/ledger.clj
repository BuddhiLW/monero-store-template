(ns monero-store.collect.ledger
  "The book the store's money is written into.

  An entry is a set of legs whose signed minor units sum to zero; a debit is
  positive and a credit is negative. The builders are pure and the in-memory
  implementation is the default, so the double-entry law holds with no backend
  present. A real book lives in an adapter behind its own alias."
  (:require [monero-store.schema :as schema]
            [malli.core :as m])
  (:import [java.util Date UUID]))

(def accounts
  "The accounts one store's money moves between."
  #{:receivable :revenue :wallet})

(def LedgerAccount
  [:enum :receivable :revenue :wallet])

(def LedgerLeg
  "One side of an entry. `:leg/amount` is signed minor units: debit positive,
  credit negative."
  [:map
   [:leg/account LedgerAccount]
   [:leg/amount :int]])

(defn balanced?
  "True when `legs` sum to zero minor units."
  [legs]
  (zero? (reduce + 0 (map :leg/amount legs))))

(def InvoiceId
  "However the host store names an invoice.

  Deliberately not `:uuid`. This store mints uuids; another mints a string its
  gateway will echo back as a description. The book records the id it is given
  and never parses it."
  [:or :uuid schema/NonBlank])

(def LedgerEntry
  "One balanced movement, identified by the reference that makes it repeatable."
  [:map
   [:entry/id :uuid]
   [:entry/kind [:enum :sale :settlement]]
   [:entry/invoice-id InvoiceId]
   [:entry/currency schema/CurrencyId]
   [:entry/reference schema/NonBlank]
   [:entry/legs [:and [:sequential {:min 2} LedgerLeg] [:fn balanced?]]]
   [:entry/at schema/Instant]])

(def Billable
  "What the book needs to know about an invoice, and nothing more.

  Deliberately not `schema/Invoice`: a host store's invoice carries its own
  keys — a plan rather than an item, periods, entitlements — and the builders
  read none of them. Demanding the whole record would make the book unusable
  by the very stores it exists for."
  [:map
   [:invoice/id InvoiceId]
   [:invoice/amount [:int {:min 0}]]
   [:invoice/currency schema/CurrencyId]])

(def Received
  "What the book needs to know about one observed movement of money.

  `:payment/reference` is the idempotency key: derived from the movements the
  rail named, so re-seeing the same money yields the same reference and books
  once."
  [:map
   [:payment/invoice-id InvoiceId]
   [:payment/reference schema/NonBlank]
   [:payment/amount [:int {:min 0}]]
   [:payment/seen-at {:optional true} [:maybe schema/Instant]]])

(defprotocol ILedger
  (post! [this entry]
    "Record `entry`. Returns it, or nil when its reference was already recorded.")
  (posted? [this reference]
    "True when `reference` has already been recorded.")
  (balance [this account currency-id]
    "Signed minor units of `currency-id` standing in `account`."))

(defn sale-entry
  "The entry booking `invoice` as revenue owed: debit receivable, credit revenue.

  `at` is the entry's timestamp. Pure."
  [invoice at]
  (let [amount (:invoice/amount invoice)]
    {:entry/id (UUID/randomUUID)
     :entry/kind :sale
     :entry/invoice-id (:invoice/id invoice)
     :entry/currency (:invoice/currency invoice)
     :entry/reference (str "sale:" (:invoice/id invoice))
     :entry/legs [{:leg/account :receivable :leg/amount amount}
                  {:leg/account :revenue :leg/amount (- amount)}]
     :entry/at at}))

(defn settlement-entry
  "The entry booking `payment` against `invoice`: debit wallet, credit receivable.

  The reference is the payment's own, so the same observed movement books once
  however often it is seen. Pure."
  [invoice payment]
  (let [amount (:payment/amount payment)]
    {:entry/id (UUID/randomUUID)
     :entry/kind :settlement
     :entry/invoice-id (:invoice/id invoice)
     :entry/currency (:invoice/currency invoice)
     :entry/reference (str "settle:" (:payment/invoice-id payment)
                           ":" (:payment/reference payment))
     :entry/legs [{:leg/account :wallet :leg/amount amount}
                  {:leg/account :receivable :leg/amount (- amount)}]
     :entry/at (or (:payment/seen-at payment) (Date.))}))

(defn memory-ledger
  "ILedger over an atom. The default, and what the tests run on.

  `post!` is a `swap!` that keeps the first entry for a reference, so a
  redelivered settlement adds nothing."
  []
  (let [state (atom {:by-reference {} :order []})]
    (reify ILedger
      (post! [_ entry]
        (let [ref (:entry/reference entry)
              next (swap! state (fn [s]
                                  (if (contains? (:by-reference s) ref)
                                    (assoc s ::posted nil)
                                    (-> s
                                        (assoc-in [:by-reference ref] entry)
                                        (update :order conj entry)
                                        (assoc ::posted entry)))))]
          (::posted next)))
      (posted? [_ reference]
        (contains? (:by-reference @state) reference))
      (balance [_ account currency-id]
        (transduce (comp (filter #(= currency-id (:entry/currency %)))
                         (mapcat :entry/legs)
                         (filter #(= account (:leg/account %)))
                         (map :leg/amount))
                   + 0 (:order @state))))))

(m/=> balanced? [:=> [:cat [:sequential LedgerLeg]] :boolean])
(m/=> sale-entry [:=> [:cat Billable schema/Instant] LedgerEntry])
(m/=> settlement-entry [:=> [:cat Billable Received] LedgerEntry])
(m/=> memory-ledger [:=> [:cat] [:fn #(satisfies? ILedger %)]])
