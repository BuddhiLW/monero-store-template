(ns monero-store.promote.catalog
  "What the store sells, as a registry of value objects.

  An item is registered, never compiled in: a deployment declares its own
  catalog at boot and nothing below this namespace knows what a `:pro` is. The
  sample catalog exists so a fresh checkout runs before anyone has configured
  anything."
  (:require [malli.core :as m]
            [monero-store.schema :as schema]))

(defonce ^:private registry
  (atom {}))

(defn register!
  "Register `item`, replacing any item with the same id. Returns the item.

  Validated on the way in: an item priced in a currency nobody can settle, or
  with a negative amount, is a checkout that fails at the customer rather than
  at boot."
  [item]
  (schema/check! schema/Item item {:monero-store/producer `register!})
  (swap! registry assoc (:item/id item) item)
  item)

(defn register-all!
  "Register every item in `items`. Returns them."
  [items]
  (mapv register! items))

(defn clear!
  "Forget every registered item. For tests and for a boot that owns the whole
  catalog."
  []
  (reset! registry {})
  nil)

(defn item
  "Registered Item for `item-id`, or nil."
  [item-id]
  (get @registry item-id))

(defn items
  "Every registered item, in id order."
  []
  (->> @registry (sort-by key) (mapv val)))

(defn listed
  "Items a customer may see. An unlisted item is still sellable — an operator
  can open one — it simply is not advertised."
  []
  (filterv #(not (false? (:item/listed? % true))) (items)))

(defn listed?
  "True when `item-id` is registered and advertised."
  [item-id]
  (boolean (when-let [found (item item-id)]
             (not (false? (:item/listed? found true))))))

(defn price
  "The stored price of `item`, as Money."
  [item]
  (:item/price item))

(def sample-catalog
  "A catalog to run against before a deployment declares its own."
  [{:item/id :support
    :item/name "Support"
    :item/blurb "One month of support, billed monthly."
    :item/price {:money/amount 900 :money/currency :usd :money/scale 2}
    :item/period :monthly
    :item/listed? true}
   {:item/id :pro
    :item/name "Pro"
    :item/blurb "Everything, for a year."
    :item/price {:money/amount 9900 :money/currency :usd :money/scale 2}
    :item/period :yearly
    :item/listed? true}
   {:item/id :smoke-test
    :item/name "Smoke test"
    :item/blurb "The smallest real charge, for exercising a live rail."
    :item/price {:money/amount 100 :money/currency :usd :money/scale 2}
    :item/period :once
    :item/listed? false}])

(m/=> item [:=> [:cat :keyword] [:maybe schema/Item]])
(m/=> items [:=> :cat [:vector schema/Item]])
(m/=> listed [:=> :cat [:vector schema/Item]])
(m/=> price [:=> [:cat schema/Item] schema/Money])

(m/=> register! [:=> [:cat schema/Item] schema/Item])

(m/=> register-all! [:=> [:cat [:sequential schema/Item]] [:vector schema/Item]])

(m/=> clear! [:=> :cat :nil])

(m/=> listed? [:=> [:cat :keyword] :boolean])
