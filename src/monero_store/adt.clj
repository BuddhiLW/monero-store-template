(ns monero-store.adt
  "The sums this store closes over.

  `defadt` states a sum ONCE and derives the rest: the constructor (which
  refuses a variant the sum does not declare), the predicate, the keyword
  coercion, and the ADT's own malli schema. `adt-case` then checks coverage at
  macro-expansion time, so a new variant breaks compilation at every site that
  must decide about it — which is the entire reason for naming the sum instead
  of passing loose keywords around.

  Values stay plain data — `{:adt/type T :adt/variant v}` — so they survive a
  trip through JSON, a database column or a test fixture unchanged."
  (:require [hive-dsl.adt :as adt]))

(adt/defadt SettlementOutcome
  "What an observed movement of money means for the invoice behind it."
  :settle/grant
  :settle/pending
  :settle/underpaid
  :settle/late
  :settle/reject)

(adt/defadt CheckoutState
  "What a hosted checkout currently is, as its processor reports it."
  :checkout/awaiting
  :checkout/paid
  :checkout/expired)

(defmacro adt-case
  "`hive-dsl.adt/adt-case`, re-exported so a namespace that decides about one of
  these sums requires the sums and nothing else."
  [type-ref expr & clauses]
  `(adt/adt-case ~type-ref ~expr ~@clauses))
