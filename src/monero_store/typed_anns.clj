(ns ^:typed.clojure monero-store.typed-anns
  "Typed Clojure annotations for the pure money core.

  Ships: `t/ann` expands to nothing without the checker, so a consumer who
  type-checks their own code sees these vars typed."
  {:clj-kondo/config '{:linters {:unresolved-symbol {:level :off}
                                 :unresolved-var {:level :off}}}}
  (:require [typed.clojure :as t]
            [monero-store.currency]
            [monero-store.promote.invoice]))

;; ---------------------------------------------------------------------------
;; currency

(t/defalias CurrencyId
  "The id of a registered currency. Keyword, not an enum: the registry is open."
  t/Keyword)

(t/defalias MinorUnits
  "An amount in a currency's minor unit. Signed."
  t/Int)

(t/ann monero-store.currency/known? [CurrencyId :-> t/Bool])
(t/ann monero-store.currency/crypto? [CurrencyId :-> t/Bool])
(t/ann monero-store.currency/scale [CurrencyId :-> (t/Nilable t/Int)])
(t/ann monero-store.currency/registered [:-> (t/Set CurrencyId)])
(t/ann monero-store.currency/spec [CurrencyId :-> (t/Nilable (t/Map t/Any t/Any))])

(t/ann monero-store.currency/->display
       [CurrencyId (t/Nilable MinorUnits) :-> t/Str])

(t/ann monero-store.currency/->minor-units
       [CurrencyId (t/U t/Str Number) :-> MinorUnits])

;; ---------------------------------------------------------------------------
;; invoice

(t/ann monero-store.promote.invoice/remaining
       [(t/Map t/Any t/Any) (t/Nilable MinorUnits) :-> MinorUnits])
