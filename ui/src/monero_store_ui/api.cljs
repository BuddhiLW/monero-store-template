(ns monero-store-ui.api
  "The storefront's calls to its own backend.

  One place decides how a request carries identity, so swapping the demo header
  for a real bearer token is one edit here and none in any view."
  (:require [clojure.string :as str]))

(defn- headers
  [customer-ref]
  (cond-> {"accept" "application/json"
           "content-type" "application/json"}
    (not (str/blank? (str customer-ref))) (assoc "x-customer-ref" customer-ref)))

(defn- ->clj
  [response]
  (.then (.json response)
         (fn [body] {:status (.-status response)
                     :ok? (.-ok response)
                     :body (js->clj body :keywordize-keys true)})))

(defn fetch!
  "Call `path`. Returns a promise of {:status :ok? :body}.

  A body that is not JSON resolves to a nil body rather than rejecting: an
  error page from a proxy is a status, not an exception."
  ([path] (fetch! path {}))
  ([path {:keys [method body customer-ref]}]
   (-> (js/fetch path (clj->js (cond-> {:method (name (or method :get))
                                        :headers (headers customer-ref)}
                                 body (assoc :body (js/JSON.stringify (clj->js body))))))
       (.then ->clj)
       (.catch (fn [error]
                 {:status 0 :ok? false :body nil :error (str error)})))))

(defn catalog [customer-ref] (fetch! "/api/catalog" {:customer-ref customer-ref}))

(defn checkout!
  [customer-ref item provider]
  (fetch! "/api/checkout" {:method :post
                           :customer-ref customer-ref
                           :body {:item item :provider provider}}))

(defn invoice
  [customer-ref invoice-id]
  (fetch! (str "/api/invoices/" invoice-id) {:customer-ref customer-ref}))

(defn my-invoices
  [customer-ref]
  (fetch! "/api/me/invoices" {:customer-ref customer-ref}))
