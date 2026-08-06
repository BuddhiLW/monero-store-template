(ns monero-store.support
  "One wired store, with every port faked, for tests to drive.

  Nothing here reaches a network, a database, or a clock it does not own: the
  wallet is an atom, the card gateway is an atom, rates are constants, and
  `now` is whatever the test says it is."
  (:require [monero-store.collect.cards :as cards]
            [monero-store.collect.fulfilment :as fulfilment]
            [monero-store.collect.store :as store]
            [monero-store.collect.wallet :as wallet]
            [monero-store.payments.chain :as chain]
            [monero-store.payments.manual :as manual]
            [monero-store.payments.provider :as provider]
            [monero-store.payments.stripe :as stripe]
            [monero-store.promote.catalog :as catalog])
  (:import (java.util Date)))

(def catalog-items
  [{:item/id :monthly
    :item/name "Monthly"
    :item/blurb "Billed monthly."
    :item/price {:money/amount 900 :money/currency :usd :money/scale 2}
    :item/period :monthly
    :item/listed? true}
   {:item/id :yearly
    :item/name "Yearly"
    :item/price {:money/amount 9900 :money/currency :usd :money/scale 2}
    :item/period :yearly
    :item/listed? true}
   {:item/id :hidden
    :item/name "Hidden"
    :item/price {:money/amount 100 :money/currency :usd :money/scale 2}
    :item/period :once
    :item/listed? false}])

(def webhook-secret "whsec_test_secret")

(defn rates-at
  "Two independent sources agreeing on `price` for [:xmr :usd] at `at`."
  ([price] (rates-at price (Date.)))
  ([price at]
   [{:rate/source :alpha :rate/pair [:xmr :usd] :rate/price price :rate/as-of at}
    {:rate/source :beta :rate/pair [:xmr :usd] :rate/price price :rate/as-of at}]))

(defn deps
  "A wired store. `opts` may override `:now-fn`, `:rates-fn`, `:fulfilment`,
  and anything else the pipeline reads."
  ([] (deps {}))
  ([opts]
   (catalog/clear!)
   (catalog/register-all! (:catalog opts catalog-items))
   (let [order-store (store/memory-store)
         chain-wallet (wallet/fake-wallet)
         gateway (cards/fake-gateway)
         granted (atom [])]
     (merge
      {:store order-store
       :wallet chain-wallet
       :gateway gateway
       :granted granted
       :rails (provider/registry
               [(chain/entry {:wallet chain-wallet
                              :provider-id :monero
                              :callback-secret (:callback-secret opts)})
                (stripe/entry {:gateway gateway
                               :webhook-secret webhook-secret
                               :success-url "https://store.invalid/paid"
                               :cancel-url "https://store.invalid/"})
                (manual/entry)])
       :fulfilment (fulfilment/handler (fn [grant] (swap! granted conj grant) grant))
       :admin-token "operator-token"
       :callback-base "https://store.invalid"
       :rates-fn #(rates-at 150.0)}
      (dissoc opts :catalog :callback-secret)))))

(defn customer!
  ([deps] (customer! deps "customer-1"))
  ([deps ref]
   (store/upsert-customer! (:store deps) {:customer/ref ref :customer/email (str ref "@test.invalid")})))
