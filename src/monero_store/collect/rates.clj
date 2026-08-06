(ns monero-store.collect.rates
  "Public price tickers behind one port, described as data.

  Adding an exchange — or a whole new pair — is a map entry in `sources`;
  nothing here branches on which exchange it is. A source that fails is simply
  absent from the round, and the quote layer above decides whether what came
  back is enough to price a sale."
  (:require [malli.core :as m]
            [monero-store.collect.http :as http]
            [monero-store.schema :as schema]
            [taoensso.timbre :as log])
  (:import (java.util Date)))

(defprotocol IRateSource
  (spot [this]
    "This source's current price for its pair, as a Rate, or nil."))

(def sources
  "Independent public tickers, none of which needs an account or a key.

  `:source/path` is where the price sits in the parsed body, and
  `:source/pair` is [base quote] — the price is how many `quote` units one
  whole `base` unit costs."
  [{:source/id :kraken
    :source/pair [:xmr :usd]
    :source/url "https://api.kraken.com/0/public/Ticker?pair=XMRUSD"
    :source/path [:result :XXMRZUSD :c 0]}
   {:source/id :coingecko
    :source/pair [:xmr :usd]
    :source/url "https://api.coingecko.com/api/v3/simple/price?ids=monero&vs_currencies=usd"
    :source/path [:monero :usd]}
   {:source/id :coinpaprika
    :source/pair [:xmr :usd]
    :source/url "https://api.coinpaprika.com/v1/tickers/xmr-monero"
    :source/path [:quotes :USD :price]}
   {:source/id :bitfinex
    :source/pair [:xmr :usd]
    :source/url "https://api-pub.bitfinex.com/v2/ticker/tXMRUSD"
    :source/path [6]}])

(defn ->number
  "`x` as a positive number, or nil. Tickers quote prices as strings as often
  as they quote them as numbers."
  [x]
  (let [n (cond
            (number? x) x
            (string? x) (try (Double/parseDouble x) (catch Exception _ nil)))]
    (when (and n (pos? n) (not (Double/isNaN (double n)))) n)))

(defn extract
  "The positive number at `path` in `payload`, or nil."
  [payload path]
  (->number (get-in payload path)))

(defn ->source
  "IRateSource over the ticker `profile` describes.

  `now-fn` stamps the reading, because a ticker rarely says when it last
  traded — and a reading with no time cannot be aged out."
  [profile client now-fn]
  (reify IRateSource
    (spot [_]
      (let [response (http/request client {:http/method :get :http/url (:source/url profile)})]
        (if-let [price (and (http/ok? response) (extract (:http/body response) (:source/path profile)))]
          {:rate/source (:source/id profile)
           :rate/pair (:source/pair profile)
           :rate/price price
           :rate/as-of (now-fn)}
          (do (log/warn "rate source unavailable"
                        {:source (:source/id profile) :status (:http/status response)})
              nil))))))

(defn fixed-source
  "IRateSource that always reports `price`. Development and tests."
  [{:keys [id pair price now-fn] :or {now-fn #(Date.)}}]
  (reify IRateSource
    (spot [_]
      {:rate/source id :rate/pair pair :rate/price price :rate/as-of (now-fn)})))

(defn registry
  "An IRateSource per profile in `source-profiles`."
  [{:keys [client now-fn source-profiles]
    :or {now-fn #(Date.) source-profiles sources}}]
  (mapv #(->source % client now-fn) source-profiles))

(defn round
  "Every rate the sources can supply now. Sources that fail are absent."
  [rate-sources]
  (into [] (keep spot) rate-sources))

(defn feed
  "A cached round over `rate-sources`, as a 0-arity function.

  At most one round of network per `:ttl-ms`; a round that yields nothing is
  not cached, so a total outage retries instead of pinning an empty result."
  [{:keys [rate-sources ttl-ms now-fn]
    :or {ttl-ms 60000 now-fn #(Date.)}}]
  (let [cache (atom {:at 0 :rates []})]
    (fn []
      (let [t (.getTime ^Date (now-fn))
            {:keys [at rates]} @cache]
        (if (and (seq rates) (< (- t at) ttl-ms))
          rates
          (let [fresh (round rate-sources)]
            (when (seq fresh)
              (reset! cache {:at t :rates fresh}))
            fresh))))))

(m/=> ->number [:=> [:cat :any] [:maybe number?]])
(m/=> extract [:=> [:cat :any [:sequential :any]] [:maybe number?]])
(m/=> round [:=> [:cat [:sequential :any]] [:vector schema/Rate]])

(m/=> ->source [:=> [:cat :map :any ifn?] :any])

(m/=> fixed-source [:=> [:cat [:map [:id :keyword] [:pair schema/Pair] [:price number?]
                                  [:now-fn {:optional true} ifn?]]] :any])

(m/=> registry [:=> [:cat [:map [:client {:optional true} :any]
                               [:now-fn {:optional true} ifn?]
                               [:source-profiles {:optional true} [:sequential :map]]]]
                [:vector :any]])

(m/=> feed [:=> [:cat [:map [:rate-sources {:optional true} [:sequential :any]]
                           [:ttl-ms {:optional true} :int]
                           [:now-fn {:optional true} ifn?]]] ifn?])
