(ns monero-store.currency
  "The currencies this store can hold money in, as a registry.

  A currency is its id, the number of decimal places its minor unit represents,
  and whether it is settled on a chain or by a processor. Adding one is a map
  entry — nothing below this namespace branches on which currency it is."
  (:require [clojure.string :as str]
            [malli.core :as m]))

(def base-currencies
  "Currencies every deployment starts with. `:currency/scale` is how many
  decimal places one major unit is divided into: piconero for XMR, cents for
  the fiat pair, satoshi for BTC."
  [{:currency/id :xmr :currency/scale 12 :currency/kind :crypto :currency/symbol "XMR"}
   {:currency/id :btc :currency/scale 8 :currency/kind :crypto :currency/symbol "BTC"}
   {:currency/id :usd :currency/scale 2 :currency/kind :fiat :currency/symbol "$"}
   {:currency/id :eur :currency/scale 2 :currency/kind :fiat :currency/symbol "€"}
   {:currency/id :brl :currency/scale 2 :currency/kind :fiat :currency/symbol "R$"}])

(defonce ^:private registry
  (atom (into {} (map (juxt :currency/id identity)) base-currencies)))

(defn register!
  "Register `spec` under its `:currency/id`. Returns the spec."
  [{:currency/keys [id scale] :as spec}]
  (assert (keyword? id) "a currency needs a keyword id")
  (assert (and (integer? scale) (not (neg? scale))) "a currency needs a non-negative scale")
  (swap! registry assoc id spec)
  spec)

(defn spec
  "Registered spec for `currency-id`, or nil."
  [currency-id]
  (get @registry currency-id))

(defn known?
  [currency-id]
  (some? (spec currency-id)))

(defn scale
  "Decimal places `currency-id`'s minor unit represents, or nil when unknown."
  [currency-id]
  (:currency/scale (spec currency-id)))

(defn registered
  "Ids of every registered currency."
  []
  (set (keys @registry)))

(defn crypto?
  [currency-id]
  (= :crypto (:currency/kind (spec currency-id))))

(defn- pow10
  ^java.math.BigInteger [n]
  (.pow (java.math.BigInteger/valueOf 10) (int n)))

(defn ->display
  "`amount` minor units of `currency-id` as a fixed-point string.

  Display only: every decision in this store is made on the integer."
  [currency-id amount]
  (let [places (or (scale currency-id) 0)
        units (biginteger (or amount 0))
        divisor (pow10 places)
        whole (.divide units divisor)
        frac (.mod units divisor)]
    (if (zero? places)
      (str whole)
      (str whole "." (str/replace (format (str "%0" places "d") frac) #"^-" "")))))

(defn ->minor-units
  "The decimal string or number `x` as minor units of `currency-id`.

  Rounds half up, because this is only ever used on operator input and a
  configured price — never on money that has already moved."
  [currency-id x]
  (let [places (or (scale currency-id)
                   (throw (ex-info "no declared scale for currency"
                                   {:monero-store/error :unknown-currency
                                    :currency currency-id})))]
    (-> (bigdec x)
        (.setScale (int places) java.math.RoundingMode/HALF_UP)
        (.unscaledValue)
        (.longValueExact))))

;; ---------------------------------------------------------------------------
;; contracts

(m/=> register! [:=> [:cat [:map [:currency/id :keyword] [:currency/scale :int]]] :map])

(m/=> spec [:=> [:cat :keyword] [:maybe :map]])

(m/=> known? [:=> [:cat :keyword] :boolean])

(m/=> scale [:=> [:cat :keyword] [:maybe :int]])

(m/=> registered [:=> :cat [:set :keyword]])

(m/=> crypto? [:=> [:cat :keyword] :boolean])

(m/=> pow10 [:=> [:cat :int] :any])

(m/=> ->display [:=> [:cat :keyword [:maybe :int]] :string])

(m/=> ->minor-units [:=> [:cat :keyword [:or :string number?]] :int])
