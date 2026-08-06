(ns monero-store.boundary.wire
  "Domain values as wire shapes.

  One namespace decides what leaves the process. Everything here is a
  projection: keys are renamed and amounts are rendered for display, and
  anything the customer has no business seeing simply has no clause."
  (:require [monero-store.currency :as currency]
            [monero-store.promote.invoice :as invoice]))

(defn money
  [{:money/keys [amount currency] :as value}]
  (when value
    {:amount amount
     :currency (name currency)
     :display (currency/->display currency amount)}))

(defn item
  ([value] (item value nil))
  ([{:item/keys [id name blurb price period metadata]} quotes]
   (cond-> {:id (clojure.core/name id)
            :name name
            :blurb blurb
            :price (money price)
            :period (clojure.core/name period)}
     (seq metadata) (assoc :metadata metadata)
     (seq quotes) (assoc :quotes quotes))))

(defn quoted
  "A quote as an estimate for display. The invoice mints and stores its own."
  [{:quote/keys [amount rate sources expires-at]}]
  {:amount (money amount)
   :rate rate
   :sources (mapv name sources)
   :expires-at expires-at})

(defn provider
  [{:provider/keys [id currency min-confirmations settles-async?]}]
  {:id (name id)
   :currency (name currency)
   :min-confirmations min-confirmations
   :async settles-async?})

(defn invoice-view
  "What the customer paying `inv` may see, including how much is still owed."
  [inv paid]
  {:id (str (:invoice/id inv))
   :item (name (:invoice/item-id inv))
   :provider (name (:invoice/provider inv))
   :status (name (:invoice/status inv))
   :amount (money {:money/amount (:invoice/amount inv)
                   :money/currency (:invoice/currency inv)
                   :money/scale (currency/scale (:invoice/currency inv))})
   :paid (money {:money/amount (long (or paid 0))
                 :money/currency (:invoice/currency inv)
                 :money/scale (currency/scale (:invoice/currency inv))})
   :remaining (money {:money/amount (invoice/remaining inv paid)
                      :money/currency (:invoice/currency inv)
                      :money/scale (currency/scale (:invoice/currency inv))})
   :external-ref (:invoice/external-ref inv)
   :expires-at (:invoice/expires-at inv)
   :created-at (:invoice/created-at inv)})

(defn handle
  "The instructions for paying. `:pay-to` is an address, `:redirect-url` a
  hosted page; a rail supplies whichever it has."
  [{:handle/keys [provider external-ref pay-to redirect-url amount]}]
  {:provider (name provider)
   :external-ref external-ref
   :pay-to pay-to
   :redirect-url redirect-url
   :amount (money amount)})

(defn payment
  [{:payment/keys [id invoice-id provider amount reference resolution confirmations seen-at]}]
  {:id (str id)
   :invoice (str invoice-id)
   :provider (name provider)
   :amount amount
   :reference reference
   :resolution (name resolution)
   :confirmations confirmations
   :seen-at seen-at})

(defn invoice-summary
  [{:invoice/keys [id customer-id item-id provider status amount currency expires-at]}]
  {:id (str id)
   :customer (str customer-id)
   :item (name item-id)
   :provider (name provider)
   :status (name status)
   :amount amount
   :currency (name currency)
   :expires-at expires-at})

(defn rate
  [{:rate/keys [source pair price as-of]}]
  {:source (name source)
   :pair (mapv name pair)
   :price price
   :as-of as-of})

(defn fulfilment
  [{:fulfilment/keys [invoice-id customer-id item-id period-end granted-at]}]
  {:invoice (str invoice-id)
   :customer (str customer-id)
   :item (name item-id)
   :period-end period-end
   :granted-at granted-at})
