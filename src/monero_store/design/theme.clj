(ns monero-store.design.theme
  "The storefront's stylesheet, as data.

  Every value here is a ROLE — `[:token \"color.surface\"]`, `[:token
  \"space.gutter\"]` — never a colour and never a measurement. Restyling the
  store for a brand is editing `tokens.edn`; this file is the layout, and it
  changes when the layout does.

  Nothing in this namespace is effectful, so the whole stylesheet can be
  rendered, diffed, and asserted on in a test."
  (:require [clojure.string :as str]
            [malli.core :as m]))

(def reset
  [[:comment "reset"]
   ["*, *::before, *::after" {:box-sizing "border-box"}]
   ["body" {:margin 0
            :background [:token "color.background"]
            :color [:token "color.text"]
            :font [[:token "type.body"] [:raw "/"] [:token "type.leading"] [:token "type.family"]]}]
   ["main" {:max-width [:token "size.page-width"]
            :margin "0 auto"
            :padding [[:token "space.section"] [:token "space.gutter"] [:token "space.page"]]}]
   ["h1" {:font-size [:token "type.display"]
          :letter-spacing "-0.01em"
          :margin [[:token "space.tight"] 0 [:token "space.gutter"]]}]
   ["h2" {:font-size [:token "type.heading"]}]
   ["h3" {:margin [0 0 [:token "space.tight"]]}]
   ["code" {:font-family [:token "type.family-mono"]}]
   ["a" {:color [:token "color.accent"]}]])

(def controls
  [[:comment "controls"]
   ["input" {:padding [[:token "space.inline"] [:token "space.inline"]]
             :border [[:token "size.hairline"] "solid" [:token "color.border-strong"]]
             :border-radius [:token "size.radius"]
             :background [:token "color.surface"]
             :color "inherit"
             :font "inherit"}]
   ["input:focus-visible, button:focus-visible, a:focus-visible"
    {:outline ["2px" "solid" [:token "color.focus"]]
     :outline-offset "2px"}]
   ["button, .button"
    {:padding [[:token "space.inline"] [:token "space.gutter"]]
     :border [[:token "size.hairline"] "solid" [:token "color.border-strong"]]
     :border-radius [:token "size.radius"]
     :background [:token "color.surface"]
     :color "inherit"
     :font "inherit"
     :cursor "pointer"
     :text-decoration "none"
     :display "inline-block"}]
   ["button:hover, .button:hover" {:border-color [:token "color.accent"]
                                   :color [:token "color.accent"]}]
   ["button:disabled" {:opacity "0.5" :cursor "default" :border-color [:token "color.border"]}]
   ["button.primary, .button.primary"
    {:background [:token "color.accent"]
     :border-color [:token "color.accent"]
     :color [:token "color.accent-foreground"]}]
   ["button.primary:hover, .button.primary:hover"
    {:background [:token "color.accent-hover"]
     :border-color [:token "color.accent-hover"]
     :color [:token "color.accent-foreground"]}]
   ["button.link"
    {:border 0
     :padding [[:token "space.tight"] 0]
     :background "none"
     :color [:token "color.text-muted"]
     :text-decoration "underline"}]])

(def identity-bar
  [[:comment "who the storefront says it is"]
   [".bar" {:display "flex"
            :gap [:token "space.inline"]
            :align-items "center"
            :flex-wrap "wrap"
            :padding [[:token "space.inline"] 0 [:token "space.block"]]
            :border-bottom [[:token "size.hairline"] "solid" [:token "color.border"]]}]
   [".bar label" {:color [:token "color.text-muted"] :font-size [:token "type.small"]}]])

(def catalog
  [[:comment "catalog"]
   [".catalog" {:display "grid"
                :gap [:token "space.gutter"]
                :grid-template-columns [:raw "repeat(auto-fill, minmax(var(--store-size-card-width), 1fr))"]
                :padding [[:token "space.block"] 0]}]
   [".card" {:border [[:token "size.hairline"] "solid" [:token "color.border"]]
             :border-radius [:token "size.radius-large"]
             :background [:token "color.surface"]
             :padding [:token "space.gutter"]}]
   [".blurb" {:color [:token "color.text-muted"]
              :font-size [:token "type.small"]
              :margin [0 0 [:token "space.inline"]]}]
   [".prices" {:display "flex" :flex-direction "column" :gap "0.1rem"}]
   [".price" {:font-size [:token "type.heading"] :font-weight "600"}]
   [".quote, .period" {:color [:token "color.text-muted"] :font-size [:token "type.small"]}]
   [".rails" {:display "flex"
              :gap [:token "space.inline"]
              :flex-wrap "wrap"
              :margin-top [:token "space.gutter"]}]
   [".rail small" {:color [:token "color.text-muted"]}]])

(def payment
  [[:comment "paying an invoice"]
   [".pay" {:border [[:token "size.hairline"] "solid" [:token "color.border"]]
            :border-radius [:token "size.radius-large"]
            :background [:token "color.surface"]
            :padding [:token "space.block"]
            :margin [[:token "space.block"] 0]}]
   [".pay header" {:display "flex"
                   :justify-content "space-between"
                   :align-items "baseline"
                   :gap [:token "space.gutter"]}]
   [".address" {:margin [[:token "space.gutter"] 0]}]
   [".address code" {:display "block"
                     :word-break "break-all"
                     :padding [:token "space.inline"]
                     :margin [[:token "space.tight"] 0]
                     :border [[:token "size.hairline"] "dashed" [:token "color.border-strong"]]
                     :border-radius [:token "size.radius"]
                     :background [:token "color.surface-sunken"]
                     :font-size [:token "type.small"]}]
   [".actions" {:display "flex" :gap [:token "space.inline"]}]
   [".facts" {:display "grid"
              :grid-template-columns "auto 1fr"
              :gap ["0.2rem" [:token "space.gutter"]]
              :margin [[:token "space.gutter"] 0]}]
   [".facts dt" {:color [:token "color.text-muted"]}]
   [".facts dd" {:margin 0}]
   [".note" {:color [:token "color.text-muted"] :font-size [:token "type.small"]}]])

(def status
  [[:comment "invoice status — one role per meaning, so a scheme can restate it"]
   [".status" {:font-size [:token "type.small"]
               :padding ["0.15rem" [:token "space.inline"]]
               :border-radius [:ref "scale.radius.pill"]
               :border [[:token "size.hairline"] "solid" [:token "color.border"]]
               :color [:token "color.text-muted"]}]
   ["[data-status=\"paid\"]" {:color [:token "color.paid"]
                              :border-color [:token "color.paid"]
                              :background [:token "color.paid-surface"]}]
   ["[data-status=\"pending\"], [data-status=\"underpaid\"]"
    {:color [:token "color.awaiting"]
     :border-color [:token "color.awaiting"]
     :background [:token "color.awaiting-surface"]}]
   ["[data-status=\"failed\"], [data-status=\"expired\"]"
    {:color [:token "color.failed"]
     :border-color [:token "color.failed"]
     :background [:token "color.failed-surface"]}]])

(def history
  [[:comment "past invoices"]
   [".history" {:border-top [[:token "size.hairline"] "solid" [:token "color.border"]]
                :padding-top [:token "space.gutter"]}]
   ["table" {:width "100%" :border-collapse "collapse" :font-size [:token "type.small"]}]
   ["th" {:text-align "left" :color [:token "color.text-muted"] :font-weight "500"}]
   ["th, td" {:padding [[:token "space.tight"] [:token "space.inline"] [:token "space.tight"] 0]
              :border-bottom [[:token "size.hairline"] "solid" [:token "color.border"]]}]])

(def messages
  [[:comment "errors and empty states"]
   [".error" {:border [[:token "size.hairline"] "solid" [:token "color.failed"]]
              :background [:token "color.failed-surface"]
              :color [:token "color.failed"]
              :border-radius [:token "size.radius"]
              :padding [[:token "space.inline"] [:token "space.gutter"]]
              :margin [[:token "space.gutter"] 0]
              :display "flex"
              :justify-content "space-between"
              :gap [:token "space.gutter"]}]
   [".empty" {:color [:token "color.text-muted"]}]])

(def responsive
  [[:comment "one breakpoint: below it the rails stack rather than crowd"]
   [:media {:max-width "30rem"}
    [".pay header" {:flex-direction "column" :gap [:token "space.tight"]}]
    [".rails" {:flex-direction "column" :align-items "stretch"}]
    ["main" {:padding ["var(--store-space-block)" "var(--store-space-inline)" "var(--store-space-section)"]}]]])

(def reduced-motion
  [[:comment "respect a reader who has asked for stillness"]
   [:media {:prefers-reduced-motion "reduce"}
    ["*, *::before, *::after" {:animation-duration "0.01ms !important"
                               :transition-duration "0.01ms !important"}]]])

(def stylesheet
  "Every section, in cascade order."
  (vec (concat reset controls identity-bar catalog payment status history messages
               responsive reduced-motion)))

(defn selectors
  "Every selector the stylesheet defines. Used by the tests to assert that the
  markup and the stylesheet still agree about what exists."
  []
  (into #{}
        (comp (remove #(keyword? (first %)))
              (map first)
              (mapcat #(str/split % #",\s*")))
        stylesheet))

;; ---------------------------------------------------------------------------
;; contracts

(m/=> selectors [:=> :cat [:set :string]])
