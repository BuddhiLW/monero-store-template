(ns monero-store-ui.views
  "The storefront, as functions of the state atom."
  (:require [clojure.string :as str]
            [monero-store-ui.format :as fmt]
            [monero-store-ui.state :as state]))

(defn- copy!
  [value]
  (some-> js/navigator .-clipboard (.writeText (str value))))

(defn identity-bar
  "Who the storefront says it is.

  A demo affordance: the backend's `IDENTITY=header` mode trusts this string
  outright. A deployment replaces both halves at once — its own identity seam
  in `system/start!`, and this input with whatever its login is."
  []
  (let [{:keys [customer-ref]} @state/app]
    [:div.bar
     [:label "Signed in as"]
     [:input {:value customer-ref
              :placeholder "your-name"
              :on-change (fn [e]
                           (state/set-customer! (.. e -target -value)))}]
     [:button {:on-click (fn [_] (state/load-catalog!) (state/load-invoices!))}
      "Reload"]]))

(defn price-line
  [{:keys [price quotes]}]
  [:div.prices
   [:span.price (fmt/money price)]
   (for [[currency quoted] quotes]
     ^{:key (str currency)}
     [:span.quote "≈ " (fmt/money (:amount quoted)) " today"])])

(defn item-card
  [{:keys [id name blurb period] :as item} providers busy?]
  [:article.card
   [:h3 name]
   (when blurb [:p.blurb blurb])
   [price-line item]
   [:p.period (case period
                "once" "One-off"
                "monthly" "Per month"
                "yearly" "Per year"
                period)]
   [:div.rails
    (for [rail providers]
      ^{:key (str id "-" (:id rail))}
      [:button.rail {:disabled busy?
                     :on-click (fn [_] (state/buy! id (:id rail)))}
       "Pay with " (:id rail)
       [:small " (" (str/upper-case (str (:currency rail))) ")"]])]])

(defn catalog-view
  []
  (let [{:keys [catalog busy?]} @state/app]
    [:section.catalog
     (if (seq (:items catalog))
       (for [item (:items catalog)]
         ^{:key (:id item)}
         [item-card item (:providers catalog) busy?])
       [:p.empty "Nothing is for sale yet."])]))

(defn pay-panel
  "How to pay an open invoice, and how much is still owed."
  [{:keys [invoice handle]}]
  (let [uri (fmt/payment-uri invoice (:pay-to handle))]
    [:section.pay
     [:header
      [:h2 "Pay " (fmt/money (:amount invoice))]
      [:span.status {:data-status (:status invoice)} (fmt/status (:status invoice))]]

     (when (:pay-to handle)
       [:div.address
        [:label "Send exactly " [:strong (fmt/money (:amount invoice))] " to"]
        [:code (:pay-to handle)]
        [:div.actions
         [:button {:on-click (fn [_] (copy! (:pay-to handle)))} "Copy address"]
         (when uri [:a.button {:href uri} "Open in wallet"])]])

     (when (:redirect-url handle)
       [:div.address
        [:a.button {:href (:redirect-url handle)} "Continue to checkout"]])

     [:dl.facts
      [:dt "Paid"] [:dd (fmt/money (:paid invoice))]
      [:dt "Remaining"] [:dd (fmt/money (:remaining invoice))]
      (when (:expires-at invoice)
        [:<> [:dt "Rate locked until"] [:dd (str (:expires-at invoice))]])]

     [:p.note
      "This page re-reads the invoice every few seconds. Money that arrives "
      "while it is closed still settles — the store polls its own wallet."]

     [:button.link {:on-click (fn [_] (state/close-invoice!))} "Back to the catalog"]]))

(defn invoice-row
  [{:keys [id item provider status amount]}]
  [:tr
   [:td (fmt/truncate id 8)]
   [:td item]
   [:td provider]
   [:td (fmt/money amount)]
   [:td [:span.status {:data-status status} (fmt/status status)]]])

(defn history
  []
  (let [{:keys [invoices]} @state/app]
    (when (seq invoices)
      [:section.history
       [:h2 "Your invoices"]
       [:table
        [:thead [:tr [:th "id"] [:th "item"] [:th "rail"] [:th "amount"] [:th "status"]]]
        [:tbody (for [inv invoices] ^{:key (:id inv)} [invoice-row inv])]]])))

(defn error-bar
  []
  (when-let [error (:error @state/app)]
    [:div.error
     [:span error]
     [:button.link {:on-click (fn [_] (state/clear-error!))} "dismiss"]]))

(defn app
  []
  (let [{:keys [invoice]} @state/app]
    [:main
     [:h1 "Store"]
     [identity-bar]
     [error-bar]
     (if invoice
       [pay-panel invoice]
       [catalog-view])
     [history]]))
