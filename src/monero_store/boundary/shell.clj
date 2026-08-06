(ns monero-store.boundary.shell
  "The SPA shell — as DATA, not as a file with holes in it.

  The document is hiccup, so writing an experiment assignment into it is
  `merge` on an attribute map. An earlier version of this namespace kept the
  shell as HTML and spliced attributes in with a regex; it rewrote a COMMENT
  that happened to mention `<html>`, and the assignment silently never reached
  the browser. That class of bug does not exist here: a map has no second place
  to put a key.

  Setting the arm server-side is what makes an experiment free — the stylesheet
  already contains every arm, so the first paint is the assigned design, with
  no script, no round trip, and no flash of the losing variant."
  (:require [clojure.string :as str]
            [hiccup2.core :as hiccup]))

(def visitor-cookie "store_visitor")

(def default-assets
  "What the shell loads. Generated CSS first, then the storefront."
  {:stylesheets ["/css/tokens.css" "/css/store.css"]
   :scripts ["/js/main.js"]})

;; ---------------------------------------------------------------------------
;; who is asking

(defn- cookies-of
  [request]
  (->> (str/split (str (get-in request [:headers "cookie"])) #";")
       (keep (fn [pair]
               (let [[k v] (str/split (str/trim pair) #"=" 2)]
                 (when (and (seq k) v) [k v]))))
       (into {})))

(defn visitor-of
  "The visitor id `request` carries, or nil.

  An opaque, store-assigned id — never an email, never a customer id. It exists
  to keep an experiment assignment stable across a session and for nothing
  else, which is why it is safe to put in an analytics event."
  [request]
  (or (get (cookies-of request) visitor-cookie)
      (some-> (get-in request [:headers "x-visitor-id"]) str not-empty)))

(defn set-visitor-cookie
  "`response` carrying `visitor`, if it was minted for this request.

  SameSite=Lax and no domain: the id is for this store, and it is not a
  cross-site anything."
  [response visitor mint?]
  (cond-> response
    mint? (assoc-in [:headers "set-cookie"]
                    (str visitor-cookie "=" visitor
                         "; Path=/; Max-Age=31536000; SameSite=Lax; HttpOnly"))))

;; ---------------------------------------------------------------------------
;; the document

(defn document
  "The shell as hiccup. `:attributes` land on `<html>`, which is where an
  experiment arm and an explicit theme belong."
  [{:keys [title lang attributes stylesheets scripts]
    :or {title "Store" lang "en"}}]
  [:html (merge {:lang lang} attributes)
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    ;; Both schemes are generated, so the browser may pick either.
    [:meta {:name "color-scheme" :content "light dark"}]
    [:title title]
    (for [href (or stylesheets (:stylesheets default-assets))]
      [:link {:rel "stylesheet" :href href}])]
   [:body
    [:div {:id "app"}]
    (for [src (or scripts (:scripts default-assets))]
      [:script {:src src}])]])

(defn render
  "The shell as a document. Escaping is hiccup's, not this namespace's."
  [options]
  (str "<!DOCTYPE html>\n" (hiccup/html (document options))))
