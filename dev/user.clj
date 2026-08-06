(ns user
  "The development store: every rail faked, nothing on the network.

  `(go)` boots a real HTTP server with a real settlement pipeline over a fake
  wallet and a fake card processor, so the whole money path — quote, charge,
  partial payment, confirmation, fulfilment, reconciliation — can be driven
  from this REPL without a daemon, a processor account, or a coin.

  Pair it with `npx shadow-cljs watch app`: the storefront compiles into
  resources/public/js, which this server serves, so both halves reload live."
  (:require [clojure.tools.namespace.repl :as tn]
            [monero-store.boundary.identity :as identity]
            [monero-store.collect.analytics :as analytics]
            [monero-store.collect.cards :as cards]
            [monero-store.collect.fulfilment :as fulfilment]
            [monero-store.collect.rates :as rates]
            [monero-store.collect.store :as store]
            [monero-store.collect.wallet :as wallet]
            [monero-store.payments.chain :as chain]
            [monero-store.payments.manual :as manual]
            [monero-store.payments.provider :as provider]
            [monero-store.payments.stripe :as stripe]
            [monero-store.pipeline.checkout :as checkout]
            [monero-store.pipeline.reconcile :as reconcile]
            [monero-store.promote.catalog :as catalog]
            [monero-store.system :as system])
  (:import (java.util Date)))

(tn/set-refresh-dirs "src" "dev" "test")

(defonce system-state (atom nil))

(def demo-rates
  "Two sources that agree, and never touch the network."
  (fn []
    (mapv #(rates/spot %)
          [(rates/fixed-source {:id :demo-a :pair [:xmr :usd] :price 150.0})
           (rates/fixed-source {:id :demo-b :pair [:xmr :usd] :price 151.0})])))

(defn demo-deps
  "Everything the store needs, faked."
  []
  (let [order-store (store/memory-store)
        chain-wallet (wallet/fake-wallet)
        gateway (cards/fake-gateway)
        granted (atom [])]
    {:store order-store
     :wallet chain-wallet
     :gateway gateway
     :granted granted
     :rails (provider/registry
             [(chain/entry {:wallet chain-wallet :provider-id :monero})
              (stripe/entry {:gateway gateway
                             :webhook-secret "whsec_dev"
                             :success-url "http://localhost:8080/"
                             :cancel-url "http://localhost:8080/"})
              (manual/entry)])
     :fulfilment (fulfilment/composite
                  [(fulfilment/ledger order-store)
                   (fulfilment/handler (fn [grant] (swap! granted conj grant) grant))])
     :identify-fn (identity/header-identity)
     ;; A recorder rather than a vendor: `(events)` reads the funnel back in
     ;; the REPL, which is the whole point of having the seam.
     :analytics (analytics/memory)
     :rates-fn demo-rates}))

(defn go
  "Boot the development store on `port` (default 8080)."
  ([] (go 8080))
  ([port]
   (let [demo (demo-deps)
         running (system/start!
                  (merge {:port port
                          :admin-token "dev-operator"
                          :callback-base (str "http://localhost:" port)
                          :catalog catalog/sample-catalog}
                         (select-keys demo [:store :rails :fulfilment :identify-fn :rates-fn :analytics])))]
     (reset! system-state (update running :deps merge
                                  (select-keys demo [:wallet :gateway :granted])))
     (println (str "storefront: http://localhost:" port))
     :started)))

(defn halt!
  []
  (when-let [running @system-state]
    (system/stop! running)
    (reset! system-state nil))
  :stopped)

(defn reset
  "Stop, reload every changed namespace, boot again."
  []
  (halt!)
  (tn/refresh :after 'user/go))

(defn deps [] (:deps @system-state))

;; ---------------------------------------------------------------------------
;; driving the money path from here

(defn customer!
  ([] (customer! "dev"))
  ([ref] (store/upsert-customer! (:store (deps)) {:customer/ref ref
                                                  :customer/email (str ref "@dev.invalid")})))

(defn buy!
  "Open an invoice. Returns {:invoice .. :handle ..}."
  ([item-id] (buy! item-id :monero))
  ([item-id provider-id]
   (checkout/open! (deps) {:customer (customer!)
                           :item-id item-id
                           :provider-id provider-id})))

(defn pay!
  "Make money arrive for `opened`: `fraction` of the amount, all of it by
  default.

  Rounds the fraction UP, so paying halves twice covers the invoice rather than
  leaving it one atomic unit short — which is a real underpayment, and the
  store is right to say so."
  ([opened] (pay! opened 1))
  ([opened fraction]
   (let [owed (get-in opened [:invoice :invoice/amount])]
     (wallet/credit! (:wallet (deps))
                     (get-in opened [:handle :handle/pay-to])
                     (long (Math/ceil (* (double fraction) owed)))))))

(defn sweep!
  "Poll every open invoice and apply what the rails report."
  []
  (reconcile/sweep! (deps)))

(defn granted
  "Everything the store has handed over since boot."
  []
  @(:granted (deps)))

(defn events
  "Every funnel event recorded since boot — checkouts opened, invoices settled,
  each carrying the experiment arms in force at the time."
  []
  (analytics/events (:analytics (deps))))

(comment
  (go)
  (def opened (buy! :pro))
  (pay! opened 1/2)
  (sweep!)                              ; => {:settle/underpaid 1}
  (pay! opened 1/2)
  (sweep!)                              ; => {:settle/grant 1}
  (granted)

  ;; the card rail, without a Stripe account
  (def carded (buy! :support :stripe))
  (cards/pay! (:gateway (deps)) (get-in carded [:handle :handle/external-ref]))
  (sweep!)

  (catalog/items)
  (halt!)
  (reset))
