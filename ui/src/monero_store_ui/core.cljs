(ns monero-store-ui.core
  "Mount the storefront."
  (:require [monero-store-ui.state :as state]
            [monero-store-ui.views :as views]
            [reagent.dom.client :as rdom]))

(defonce ^:private root
  (delay (rdom/create-root (.getElementById js/document "app"))))

(defn render!
  []
  (rdom/render @root [views/app]))

(defn ^:dev/after-load reload!
  []
  (render!))

(defn init
  []
  (state/load-catalog!)
  (state/load-invoices!)
  (state/start-polling! 5000)
  (render!))
