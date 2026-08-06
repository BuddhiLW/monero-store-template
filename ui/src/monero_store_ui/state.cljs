(ns monero-store-ui.state
  "One atom, and the commands that move it.

  Views read; only this namespace writes. The polling loop is here too, because
  an invoice that settles out of band — a wallet notices money nobody clicked
  for — is the normal case on a chain rail, not an exception."
  (:require [monero-store-ui.api :as api]
            [reagent.core :as r]))

(defonce app
  (r/atom {:customer-ref (or (.getItem js/localStorage "customer-ref") "")
           :catalog nil
           :invoice nil
           :invoices []
           :busy? false
           :error nil}))

(defn- customer [] (:customer-ref @app))

(defn set-customer!
  [value]
  (.setItem js/localStorage "customer-ref" (str value))
  (swap! app assoc :customer-ref value))

(defn clear-error! [] (swap! app assoc :error nil))

(defn- failed!
  [{:keys [status body]} fallback]
  (swap! app assoc
         :busy? false
         :error (or (:error body) (str fallback " (" status ")"))))

(defn load-catalog!
  []
  (-> (api/catalog (customer))
      (.then (fn [{:keys [ok? body] :as response}]
               (if ok?
                 (swap! app assoc :catalog body :error nil)
                 (failed! response "the catalog is unavailable"))))))

(defn load-invoices!
  []
  (when-not (empty? (customer))
    (-> (api/my-invoices (customer))
        (.then (fn [{:keys [ok? body]}]
                 (when ok? (swap! app assoc :invoices (:invoices body))))))))

(defn refresh-invoice!
  "Re-read the open invoice. What settles it is money arriving, not this call —
  this is only how the page notices."
  []
  (when-let [invoice-id (get-in @app [:invoice :invoice :id])]
    (-> (api/invoice (customer) invoice-id)
        (.then (fn [{:keys [ok? body]}]
                 (when ok?
                   (swap! app assoc-in [:invoice :invoice] body)))))))

(defn buy!
  [item provider]
  (swap! app assoc :busy? true :error nil)
  (-> (api/checkout! (customer) item provider)
      (.then (fn [{:keys [ok? body] :as response}]
               (if ok?
                 (do (swap! app assoc :invoice body :busy? false)
                     (load-invoices!)
                     (when-let [url (get-in body [:handle :redirect-url])]
                       ;; A hosted checkout is paid on the processor's page, so
                       ;; the only useful thing this page can do is go there.
                       (js/window.location.assign url)))
                 (failed! response "checkout failed"))))))

(defn close-invoice!
  []
  (swap! app assoc :invoice nil))

(defonce ^:private poller
  (atom nil))

(defn start-polling!
  "Re-read the open invoice every `interval-ms`."
  [interval-ms]
  (when-let [existing @poller] (js/clearInterval existing))
  (reset! poller (js/setInterval refresh-invoice! interval-ms)))
