(ns monero-store.collect.rates-test
  "The ticker table as a contract: what it names, and whether it still answers.

  Two suites here. The offline one holds the table to the rest of the store and
  is what a cold clone runs. The ^:integration one calls the real tickers, and
  is the only thing that can catch a vendor moving a key, because a moved key
  answers 200 with a body carrying no price and that is indistinguishable from
  an outage until somebody looks."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.collect.http :as http]
            [monero-store.collect.rates :as rates]
            [monero-store.currency :as currency]
            [monero-store.promote.quote :as quotes])
  (:import (java.util Date)))

(def ^:private by-pair
  (group-by :source/pair rates/sources))

(def ^:private pairs
  "Every pair the table claims to price, read off the TABLE rather than off the
  bounds map or the currency registry. A universe drawn from the property under
  test cannot see the member that is missing it."
  (set (keys by-pair)))

(deftest the-table-is-not-empty
  (testing "every assertion below is a difference against `sources`, so an
            empty table would make all of them vacuously true"
    (is (seq rates/sources))
    (is (seq pairs))))

(deftest every-pair-is-priced-in-currencies-the-store-registers
  (doseq [[base quoted :as pair] pairs]
    (testing (str pair)
      (is (currency/known? base) "the base is a registered currency")
      (is (currency/known? quoted) "the quote is a registered currency")
      (is (currency/crypto? base)
          "a fiat base means the pair was written the wrong way round")
      (is (not (currency/crypto? quoted))
          "the store prices crypto in fiat, never the other way"))))

(deftest every-pair-carries-a-sanity-band
  (doseq [pair pairs]
    (testing (str pair)
      (let [[lo hi] (quotes/bounds-for quotes/profile pair)]
        (is (and lo hi)
            "a pair with no declared band is trusted unconditionally, which is
             exactly what the band exists to prevent")
        (is (< (double lo) (double hi)))))))

(deftest every-pair-can-lose-a-source-and-still-price
  (let [needed (:quote/min-sources quotes/profile)]
    (doseq [[pair profiles] by-pair]
      (testing (str pair)
        (is (> (count profiles) needed)
            "a pair with exactly the minimum stops selling the moment one
             ticker has a bad afternoon")))))

(deftest one-source-id-per-pair
  (doseq [[pair profiles] by-pair]
    (testing (str pair)
      (is (= (count profiles) (count (set (map :source/id profiles))))
          "consensus counts rates, so two rows sharing an id are one source
           counted twice wearing the clothes of agreement")
      (is (= (count profiles) (count (set (map :source/url profiles))))
          "and two rows sharing a url are the same reading twice"))))

(deftest a-stablecoin-is-quoted-and-not-assumed
  (doseq [stable [:usdt :usdc]]
    (testing (str stable)
      (is (contains? pairs [stable :usd])
          "a stablecoin the store accepts must be priced like any other coin")
      (let [[lo hi] (quotes/bounds-for quotes/profile [stable :usd])]
        (is (< (double lo) 1.0 (double hi))
            "the band brackets the peg")
        (is (< (- (double hi) (double lo)) 0.5)
            "and it is tight, because a wide band on a stablecoin would let a
             depeg settle at the broken price")))))

;; ---------------------------------------------------------------------------
;; live

(defn- live-rates
  "One round against the real tickers, then a second round for whatever did
  not answer.

  The retry is what separates the two ways a source can be silent. A vendor
  rate limit is transient and clears in seconds; a moved key is permanent. One
  round cannot tell them apart, and a suite that calls a 429 a moved key is a
  suite nobody trusts by the third red build."
  []
  (let [client (http/hato-client {:timeout-ms 15000})
        round (fn [profiles] (rates/round (rates/registry {:client client
                                                           :source-profiles profiles})))
        answered (fn [rates] (set (map (juxt :rate/source :rate/pair) rates)))
        first-pass (round rates/sources)
        missing (remove (comp (answered first-pass) (juxt :source/id :source/pair))
                        rates/sources)]
    (if (seq missing)
      (do (Thread/sleep 20000)
          (into (vec first-pass) (round missing)))
      first-pass)))

(deftest ^:integration every-ticker-still-answers-with-a-price
  (let [reported (set (map :rate/source (live-rates)))]
    (doseq [{:source/keys [id pair url]} rates/sources]
      (testing (str pair " via " id)
        (is (contains? reported id)
            (str "no price came back from " url
                 " -- either the ticker is down or it moved the key"))))))

(deftest ^:integration every-pair-reaches-consensus-against-the-real-world
  (let [rates (live-rates)
        now (Date.)]
    (doseq [pair pairs]
      (testing (str pair)
        ;; `consensus` answers a Rate, not a number: the agreed PRICE plus the
        ;; sources that agreed it, so a past charge can name its witnesses.
        (let [agreed (quotes/consensus quotes/profile rates pair now)
              price (:rate/price agreed)]
          (is (some? agreed)
              "the live sources neither agreed nor were fresh enough to price
               this pair, which is the store refusing to sell it")
          (when price
            (let [[lo hi] (quotes/bounds-for quotes/profile pair)]
              (is (< (double lo) (double price) (double hi))
                  (str "the agreed price " price " for " pair " sits outside the"
                       " declared band, so either the band is wrong or every"
                       " source is")))))))))
