(ns monero-store.collect.cards
  "The hosted-checkout processor as a port, plus a fake.

  Callers above this namespace see `schema/CheckoutSession` values. The Stripe
  adapter needs an SDK and lives behind its own alias in `adapters/stripe`; any
  processor with the same two operations drops in beside it."
  (:require [clojure.string :as str]
            [monero-store.schema :as schema]
            [malli.core :as m])
  (:import (java.util UUID)))

(defprotocol ICardGateway
  (open-checkout! [this checkout]
    "Open a hosted checkout. Returns a CheckoutSession.

    `checkout` is {:checkout/reference :checkout/email :checkout/product
    :checkout/currency :checkout/amount :checkout/success-url
    :checkout/cancel-url}. `:checkout/reference` is the invoice id and is
    replayed on every notice about this checkout; opening the same reference
    twice must yield the same session rather than a second way to pay once.")
  (read-checkout [this session-id]
    "The hosted checkout as the processor reports it now.

    Returns a CheckoutSession, or nil when the processor cannot report on
    `session-id`."))

(defn fake-gateway
  "ICardGateway over an atom. Development, tests, and demos.

  `pay!` and `expire!` move a session the way a customer or a clock would, so
  the card path can be exercised without a processor account."
  ([] (fake-gateway {}))
  ([{:keys [base-url] :or {base-url "https://checkout.invalid"}}]
   (let [state (atom {:sessions {} :by-reference {}})]
     (with-meta
       (reify ICardGateway
         (open-checkout! [_ {:checkout/keys [reference email product currency amount]}]
           (let [existing (get-in @state [:by-reference (str reference)])]
             (or (get-in @state [:sessions existing])
                 (let [id (str "cs_fake_" (UUID/randomUUID))
                       session {:checkout/id id
                                :checkout/url (str base-url "/" id)
                                :checkout/status "open"
                                :checkout/payment-status "unpaid"
                                :checkout/currency (some-> currency name)
                                :checkout/amount-total (long (or amount 0))}]
                   (swap! state #(-> %
                                     (assoc-in [:sessions id] session)
                                     (assoc-in [:by-reference (str reference)] id)
                                     (assoc-in [:emails id] email)
                                     (assoc-in [:products id] product)))
                   session))))

         (read-checkout [_ session-id]
           (when-not (str/blank? (str session-id))
             (get-in @state [:sessions (str session-id)]))))
       {:state state}))))

(defn- move
  [gateway session-id f]
  (let [state (:state (meta gateway))]
    (get-in (swap! state update-in [:sessions (str session-id)] f)
            [:sessions (str session-id)])))

(defn pay!
  "Mark a fake session paid, as a customer completing checkout would."
  [gateway session-id]
  (move gateway session-id #(assoc % :checkout/status "complete" :checkout/payment-status "paid")))

(defn expire!
  "Mark a fake session expired, as the processor's clock would."
  [gateway session-id]
  (move gateway session-id #(assoc % :checkout/status "expired")))

(defn session-for-reference
  "The fake session opened for `reference`, or nil."
  [gateway reference]
  (let [state @(:state (meta gateway))]
    (get-in state [:sessions (get-in state [:by-reference (str reference)])])))

(m/=> pay! [:=> [:cat :any :any] schema/CheckoutSession])
(m/=> expire! [:=> [:cat :any :any] schema/CheckoutSession])

(m/=> fake-gateway [:function [:=> :cat :any] [:=> [:cat [:map [:base-url {:optional true} :string]]] :any]])

(m/=> move [:=> [:cat :any :any ifn?] [:maybe :map]])

(m/=> session-for-reference [:=> [:cat :any :any] [:maybe schema/CheckoutSession]])
