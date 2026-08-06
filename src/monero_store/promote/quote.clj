(ns monero-store.promote.quote
  "Turning independent price reports into one locked quote. Pure.

  The stored price is the only price truth; an amount in any other currency
  exists only as a quote, valid for one invoice and one window. Sources that
  are stale, too few, or in disagreement produce no quote at all — a store that
  invents a rate when its sources cannot agree is a store that sells at the
  wrong price."
  (:require [malli.core :as m]
            [monero-store.currency :as currency]
            [monero-store.schema :as schema])
  (:import (java.math BigDecimal RoundingMode)
           (java.util Date)))

(def profile
  "How much agreement a quote demands, and how long it holds.

  `:quote/max-spread-bps` is measured against the median, in basis points."
  {:quote/min-sources 2
   :quote/max-spread-bps 500
   :quote/max-age-ms 300000
   :quote/lock-ms 900000})

(defonce ^:private bounds
  (atom {[:xmr :usd] [20 5000]
         [:btc :usd] [1000 500000]}))

(defn set-bounds!
  "Declare the [low high] band `pair`'s price must fall inside.

  A sanity band, not a pricing opinion: it exists so a ticker that starts
  reporting a price in the wrong unit cannot sell a year of service for a
  fraction of a cent."
  [pair low high]
  (swap! bounds assoc pair [low high])
  [low high])

(defn bounds-for
  "The band declared for `pair`, or nil when none is."
  [pair]
  (get @bounds pair))

(defn for-pair
  "Rates in `rates` that report on `pair`."
  [rates pair]
  (filterv #(= pair (:rate/pair %)) rates))

(defn median
  "Median of `xs`, or nil when empty. Even counts average the middle pair."
  [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)]
    (when (pos? n)
      (if (odd? n)
        (nth sorted (quot n 2))
        (/ (+ (nth sorted (dec (quot n 2))) (nth sorted (quot n 2))) 2)))))

(defn fresh
  "Rates in `rates` no older than `:quote/max-age-ms` at `now`."
  [profile rates ^Date now]
  (let [oldest (- (.getTime now) (long (:quote/max-age-ms profile)))]
    (filterv #(>= (.getTime ^Date (:rate/as-of %)) oldest) rates)))

(defn agreeing
  "Rates within `:quote/max-spread-bps` of the median of `rates`.

  A reading far from the middle is dropped, never averaged in: one broken
  ticker must neither price the sale nor block it."
  [profile rates]
  (if-let [mid (median (map :rate/price rates))]
    (let [tolerance (/ (* mid (long (:quote/max-spread-bps profile))) 10000)]
      (filterv #(<= (abs (- (:rate/price %) mid)) tolerance) rates))
    []))

(defn consensus
  "The price `rates` agree on for `pair` at `now`, or nil when they do not.

  Returns {:rate/price .. :rate/sources ..}. Stale readings are dropped first,
  then readings far from the median; what remains must still be at least
  `:quote/min-sources` independent sources, or there is no agreement to quote
  from."
  [profile rates pair now]
  (let [usable (agreeing profile (fresh profile (for-pair rates pair) now))]
    (when (>= (count usable) (long (:quote/min-sources profile)))
      {:rate/price (median (map :rate/price usable))
       :rate/sources (mapv :rate/source usable)})))

(defn- pow10
  ^BigDecimal [n]
  (.pow BigDecimal/TEN (int n)))

(defn convert
  "`price` at `rate`, in the minor units of `target-currency`.

  `rate` is how many of `price`'s currency one whole `target-currency` unit
  costs. Rounds away from zero: a rounding error must fall on the store, never
  leave the customer a hair short of their own invoice."
  [price rate target-currency]
  (let [target-scale (or (currency/scale target-currency)
                         (throw (ex-info "no declared scale for currency"
                                         {:monero-store/error :unknown-currency
                                          :currency target-currency})))]
    (-> (BigDecimal/valueOf (long (:money/amount price)))
        (.multiply (pow10 target-scale))
        (.divide (.multiply (bigdec rate) (pow10 (:money/scale price)))
                 0
                 RoundingMode/UP)
        (.longValueExact))))

(defn implied-rate
  "The price of one whole `amount` unit, in `price`'s currency, implied by the
  two naming the same value. Nil when either side is zero."
  [price amount]
  (let [p (:money/amount price)
        a (:money/amount amount)]
    (when (and (pos? p) (pos? a))
      (/ (* p (.longValueExact (.toBigInteger (pow10 (:money/scale amount)))))
         (* a (.longValueExact (.toBigInteger (pow10 (:money/scale price)))))))))

(defn plausible?
  "True when `price` and `amount` imply a rate inside the band declared for
  their pair. A pair with no declared band is trusted."
  [price amount]
  (let [pair [(:money/currency amount) (:money/currency price)]]
    (if-let [[lo hi] (bounds-for pair)]
      (let [rate (implied-rate price amount)]
        (boolean (and rate (<= lo rate hi))))
      true)))

(defn quote-for
  "Quote for `price` in `target-currency` from `rates` at `now`, or nil.

  Nil is a refusal, and the refusal is the point: too few sources, sources in
  disagreement, or an implied rate outside the pair's declared band all mean
  the store does not know the price and must not invent one."
  [profile price rates target-currency ^Date now]
  (let [pair [target-currency (:money/currency price)]]
    (when-let [agreed (consensus profile rates pair now)]
      (let [rate (:rate/price agreed)
            amount (schema/money target-currency (convert price rate target-currency))]
        (when (plausible? price amount)
          {:quote/price price
           :quote/amount amount
           :quote/pair pair
           :quote/rate rate
           :quote/sources (:rate/sources agreed)
           :quote/as-of now
           :quote/expires-at (Date. (+ (.getTime now) (long (:quote/lock-ms profile))))})))))

(defn expired?
  "True when `quote` no longer holds at `now`."
  [quote ^Date now]
  (>= (.getTime now) (.getTime ^Date (:quote/expires-at quote))))

(m/=> median [:=> [:cat [:sequential number?]] [:maybe number?]])
(m/=> for-pair [:=> [:cat [:sequential schema/Rate] schema/Pair] [:vector schema/Rate]])
(m/=> fresh [:=> [:cat :map [:sequential schema/Rate] schema/Instant] [:vector schema/Rate]])
(m/=> agreeing [:=> [:cat :map [:sequential schema/Rate]] [:vector schema/Rate]])
(m/=> convert [:=> [:cat schema/Money number? :keyword] [:int {:min 0}]])
(m/=> plausible? [:=> [:cat schema/Money schema/Money] :boolean])
(m/=> quote-for
      [:=> [:cat :map schema/Money [:sequential schema/Rate] :keyword schema/Instant]
       [:maybe schema/Quote]])
(m/=> expired? [:=> [:cat schema/Quote schema/Instant] :boolean])

(m/=> set-bounds! [:=> [:cat schema/Pair number? number?] [:tuple number? number?]])

(m/=> bounds-for [:=> [:cat schema/Pair] [:maybe [:tuple number? number?]]])

(m/=> consensus [:=> [:cat :map [:sequential schema/Rate] schema/Pair schema/Instant]
                 [:maybe [:map [:rate/price number?] [:rate/sources [:vector :keyword]]]]])

(m/=> pow10 [:=> [:cat :int] :any])

(m/=> implied-rate [:=> [:cat schema/Money schema/Money] [:maybe number?]])
