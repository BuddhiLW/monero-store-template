(ns monero-store.promote.quote-test
  "What the store will and will not price a sale at."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.promote.quote :as quotes]
            [monero-store.schema :as schema])
  (:import (java.util Date)))

(def now (Date. 1700000000000))

(defn- rate
  ([source price] (rate source price now))
  ([source price at]
   {:rate/source source :rate/pair [:xmr :usd] :rate/price price :rate/as-of at}))

(def price (schema/money :usd 9900))

(deftest a-quote-needs-independent-agreement
  (testing "one source is not agreement"
    (is (nil? (quotes/quote-for quotes/profile price [(rate :alpha 150.0)] :xmr now))))

  (testing "two agreeing sources are"
    (is (some? (quotes/quote-for quotes/profile
                                 price
                                 [(rate :alpha 150.0) (rate :beta 151.0)]
                                 :xmr now)))))

(deftest a-broken-ticker-neither-prices-the-sale-nor-blocks-it
  (let [rates [(rate :alpha 150.0) (rate :beta 151.0) (rate :broken 3.0)]
        quoted (quotes/quote-for quotes/profile price rates :xmr now)]
    (testing "the outlier is dropped, not averaged in"
      (is (= [:alpha :beta] (:quote/sources quoted)))
      (is (= 150.5 (:quote/rate quoted))))))

(deftest disagreement-produces-no-quote
  (testing "two sources far apart leave nothing that survives the spread test"
    (is (nil? (quotes/quote-for quotes/profile
                                price
                                [(rate :alpha 150.0) (rate :beta 400.0)]
                                :xmr now)))))

(deftest stale-readings-are-not-readings
  (let [old (Date. (- (.getTime now) 600000))]
    (is (nil? (quotes/quote-for quotes/profile
                                price
                                [(rate :alpha 150.0 old) (rate :beta 151.0 old)]
                                :xmr now)))))

(deftest conversion-rounds-in-the-stores-favour
  (testing "the customer is never left a hair short of their own invoice"
    ;; 99.00 USD at 150.5 USD/XMR is 0.657807308970... XMR; the exact value has
    ;; more digits than piconero can hold, so it must round UP.
    (is (= 657807308971 (quotes/convert price 150.5 :xmr))))

  (testing "an exact conversion is not inflated"
    (is (= 1000000000000 (quotes/convert (schema/money :usd 15000) 150.0 :xmr)))))

(deftest an-implausible-rate-is-refused
  (testing "a ticker reporting XMR in the wrong unit cannot sell a year for a cent"
    (is (nil? (quotes/quote-for quotes/profile
                                price
                                [(rate :alpha 0.5) (rate :beta 0.5)]
                                :xmr now))))

  (testing "the band is per pair, and declared"
    (is (= [20 5000] (quotes/bounds-for [:xmr :usd])))))

(deftest a-quote-holds-for-a-window
  (let [quoted (quotes/quote-for quotes/profile price [(rate :alpha 150.0) (rate :beta 151.0)] :xmr now)]
    (is (false? (quotes/expired? quoted now)))
    (is (true? (quotes/expired? quoted (Date. (+ (.getTime now) 900001)))))))

(deftest a-quote-is-a-value-object
  (let [quoted (quotes/quote-for quotes/profile price [(rate :alpha 150.0) (rate :beta 151.0)] :xmr now)]
    (is (= quoted (schema/check! schema/Quote quoted)))
    (testing "it records what it was converted from and who agreed"
      (is (= price (:quote/price quoted)))
      (is (= [:xmr :usd] (:quote/pair quoted))))))
