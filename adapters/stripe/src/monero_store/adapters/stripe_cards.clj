(ns monero-store.adapters.stripe-cards
  "ICardGateway over Stripe Checkout.

  Only this namespace knows stripe-java exists, and it is on its own source
  root behind the `:stripe` alias. Notice signatures are verified WITHOUT the
  SDK in `payments.stripe`, so a deployment that only needs to authenticate
  webhooks never loads this jar."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [monero-store.collect.cards :as cards]
            [monero-store.schema :as schema]
            [taoensso.timbre :as log])
  (:import (com.stripe StripeClient)
           (com.stripe.exception StripeException)
           (com.stripe.model.checkout Session)
           (com.stripe.net RequestOptions)
           (com.stripe.param.checkout SessionCreateParams
                                      SessionCreateParams$LineItem
                                      SessionCreateParams$LineItem$PriceData
                                      SessionCreateParams$LineItem$PriceData$ProductData
                                      SessionCreateParams$Mode)))

(defn- session->value
  [^Session session]
  {:checkout/id (.getId session)
   :checkout/url (.getUrl session)
   :checkout/status (.getStatus session)
   :checkout/payment-status (.getPaymentStatus session)
   :checkout/currency (.getCurrency session)
   :checkout/amount-total (long (or (.getAmountTotal session) 0))})

(defn- create-params
  ^SessionCreateParams
  [{:checkout/keys [reference email product currency amount success-url cancel-url]}]
  (-> (SessionCreateParams/builder)
      ;; One payment, one period. A Stripe subscription would renew against
      ;; invoices this store never opened and cannot match.
      (.setMode SessionCreateParams$Mode/PAYMENT)
      (.setClientReferenceId (str reference))
      (.setCustomerEmail email)
      (.setSuccessUrl success-url)
      (.setCancelUrl cancel-url)
      (.addLineItem
       (-> (SessionCreateParams$LineItem/builder)
           (.setQuantity (long 1))
           (.setPriceData
            (-> (SessionCreateParams$LineItem$PriceData/builder)
                (.setCurrency (name currency))
                (.setUnitAmount (long amount))
                (.setProductData
                 (-> (SessionCreateParams$LineItem$PriceData$ProductData/builder)
                     (.setName product)
                     (.build)))
                (.build)))
           (.build)))
      (.build)))

(defn- idempotent
  "Opening the same invoice twice must yield the same session, not a second
  live way to pay it once."
  ^RequestOptions [reference]
  (-> (RequestOptions/builder)
      (.setIdempotencyKey (str "invoice-" reference))
      (.build)))

(defn stripe-gateway
  "ICardGateway backed by Stripe.

  `:api-key` decides live or test mode, and `:api-base` points the client
  somewhere else — a sandbox, or stripe-mock in a test rig. The client is built
  on first use, so an unusable key fails where it is used rather than at boot."
  [{:keys [api-key api-base]}]
  (let [client (delay (cond-> (StripeClient/builder)
                        true (.setApiKey ^String api-key)
                        (not (str/blank? (str api-base))) (.setApiBase ^String api-base)
                        true (.build)))]
    (reify cards/ICardGateway
      (open-checkout! [_ checkout]
        (session->value
         (.create (.sessions (.checkout (.v1 ^StripeClient @client)))
                  (create-params checkout)
                  (idempotent (:checkout/reference checkout)))))

      (read-checkout [_ session-id]
        (when-not (str/blank? (str session-id))
          (try
            (session->value
             (.retrieve (.sessions (.checkout (.v1 ^StripeClient @client)))
                        ^String session-id))
            (catch StripeException e
              (log/warn "card processor cannot report on session"
                        {:session session-id :error (.getMessage e)})
              nil)))))))

;; ---------------------------------------------------------------------------
;; contracts. SDK builders are :any; the value objects out are the contract.

(m/=> session->value [:=> [:cat :any] schema/CheckoutSession])

(m/=> create-params [:=> [:cat schema/CheckoutRequest] :any])

(m/=> idempotent [:=> [:cat :any] :any])

(m/=> stripe-gateway [:=> [:cat [:map
                                 [:api-key schema/NonBlank]
                                 [:api-base {:optional true} [:maybe :string]]]]
                      [:fn #(satisfies? cards/ICardGateway %)]])
