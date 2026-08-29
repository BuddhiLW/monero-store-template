(ns monero-store.promote.catalog
  "What the store sells, as a registry of value objects.

  An item is registered, never compiled in: a deployment declares its own
  catalog at boot and nothing below this namespace knows what a `:pro` is. The
  sample catalog exists so a fresh checkout runs before anyone has configured
  anything.

  The registry is a VALUE the caller holds, created by `store`. Two stores
  embedded in one JVM own two catalogs."
  (:require [malli.core :as m]
            [monero-store.schema :as schema]))

(defn store
  "A fresh, empty catalog."
  []
  (atom {}))

(defn register!
  "Register `item` in `catalog`, replacing any item with the same id. Returns
  the item.

  Validated on the way in: an item priced in a currency nobody can settle, or
  with a negative amount, is a checkout that fails at the customer rather than
  at boot."
  [catalog item]
  (schema/check! schema/Item item {:monero-store/producer `register!})
  (swap! catalog assoc (:item/id item) item)
  item)

(defn register-all!
  "Register every item in `items`. Returns them."
  [catalog items]
  (mapv #(register! catalog %) items))

(defn clear!
  "Forget every registered item. For tests and for a boot that owns the whole
  catalog."
  [catalog]
  (reset! catalog {})
  nil)

(defn item
  "Registered Item for `item-id`, or nil."
  [catalog item-id]
  (get @catalog item-id))

(defn items
  "Every registered item, in id order."
  [catalog]
  (->> @catalog (sort-by key) (mapv val)))

(defn listed
  "Items a customer may see. An unlisted item is still sellable — an operator
  can open one — it simply is not advertised."
  [catalog]
  (filterv #(not (false? (:item/listed? % true))) (items catalog)))

(defn listed?
  "True when `item-id` is registered and advertised."
  [catalog item-id]
  (boolean (when-let [found (item catalog item-id)]
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

(m/=> store [:=> :cat :any])

(m/=> item [:=> [:cat :any :keyword] [:maybe schema/Item]])
(m/=> items [:=> [:cat :any] [:vector schema/Item]])
(m/=> listed [:=> [:cat :any] [:vector schema/Item]])
(m/=> price [:=> [:cat schema/Item] schema/Money])

(m/=> register! [:=> [:cat :any schema/Item] schema/Item])

(m/=> register-all! [:=> [:cat :any [:sequential schema/Item]] [:vector schema/Item]])

(m/=> clear! [:=> [:cat :any] :nil])

(m/=> listed? [:=> [:cat :any :keyword] :boolean])
