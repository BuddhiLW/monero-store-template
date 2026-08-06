(ns monero-store.adt
  "Closed sums with compile-time exhaustive matching.

  Kept dependency-free on purpose: a template must compile with nothing but
  Clojure on the classpath. Variants are plain data — `{:adt/type T
  :adt/variant v}` — so they survive a trip through JSON, a database column, or
  a test fixture unchanged."
  (:require [clojure.set :as set]))

(defn value
  "An ADT value of `adt-type` for `variant`, with optional `payload` merged in."
  ([adt-type variant] (value adt-type variant nil))
  ([adt-type variant payload]
   (merge {:adt/type adt-type :adt/variant variant} payload)))

(defmacro defadt
  "Define `name` as an ADT descriptor plus a constructor of the same name.

  `variants` are keywords. The descriptor is a map {:name :variants} bound to
  `name`-ADT metadata so `adt-case` can check exhaustiveness at compile time."
  [name docstring & variants]
  (let [variant-set (set variants)]
    `(def ~(with-meta name {:adt/variants variant-set})
       ~docstring
       {:adt/name ~(keyword name)
        :adt/variants ~variant-set})))

(defn- declared-variants
  [env adt-sym]
  (let [resolved (resolve env adt-sym)]
    (:adt/variants (meta resolved))))

(defmacro adt-case
  "Match `expr`'s `:adt/variant` against `clauses`, exhaustively.

  Every variant `adt-sym` declares must appear exactly once; a missing or
  unknown variant is a compile-time error, which is the whole point of naming
  the sum instead of writing `case` over loose keywords."
  [adt-sym expr & clauses]
  (let [pairs (partition 2 clauses)
        covered (set (map first pairs))
        declared (declared-variants &env adt-sym)]
    (when declared
      (when-let [missing (seq (set/difference declared covered))]
        (throw (ex-info "adt-case does not cover every variant"
                        {:adt adt-sym :missing (set missing)})))
      (when-let [unknown (seq (set/difference covered declared))]
        (throw (ex-info "adt-case matches variants the ADT does not declare"
                        {:adt adt-sym :unknown (set unknown)}))))
    `(let [value# ~expr]
       (case (:adt/variant value#)
         ~@(mapcat (fn [[variant body]] [variant body]) pairs)
         (throw (ex-info "value is not a variant of this ADT"
                         {:adt ~(str adt-sym) :value value#}))))))

(defadt SettlementOutcome
  "What an observed movement of money means for the invoice behind it."
  :settle/grant
  :settle/pending
  :settle/underpaid
  :settle/late
  :settle/reject)

(defadt CheckoutState
  "What a hosted checkout currently is, as its processor reports it."
  :checkout/awaiting
  :checkout/paid
  :checkout/expired)

(defn settlement-outcome
  [variant]
  (value :SettlementOutcome variant))

(defn checkout-state
  [variant]
  (value :CheckoutState variant))
