(ns monero-store.boundary.routes
  "The HTTP surface.

  Thin on purpose: a handler resolves who is asking, calls one pipeline
  operation, and projects the result through `wire`. Every decision worth
  testing lives below this namespace, where it can be tested without a
  request."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [monero-store.boundary.identity :as identity]
            [monero-store.boundary.wire :as wire]
            [monero-store.collect.store :as store]
            [monero-store.payments.provider :as provider]
            [monero-store.pipeline.checkout :as checkout]
            [monero-store.pipeline.notice :as notice]
            [monero-store.promote.catalog :as catalog]
            [monero-store.promote.quote :as quotes]
            [reitit.ring :as ring]
            [monero-store.boundary.shell :as shell]
            [monero-store.promote.experiments :as experiment]
            [malli.core :as m]
            [monero-store.schema :as schema]
            [monero-store.collect.reachability :as reach])
  (:import (java.util Date)
[java.util UUID]))

(def ^:private mapper
  (json/object-mapper {:decode-key-fn true}))

(defn- json-response
  [status body]
  {:status status
   :headers {"content-type" "application/json"}
   :body (json/write-value-as-string body)})

(defn- raw-body
  "The request body verbatim, as a string.

  Consumes the stream exactly once, so a handler that also needs the parsed
  body must parse THIS value — reserializing a parsed body changes bytes, and
  every signature over those bytes."
  [request]
  (let [body (:body request)]
    (cond
      (nil? body) ""
      (string? body) body
      :else (try (slurp body :encoding "UTF-8") (catch Exception _ "")))))

(defn- parse-json
  [body]
  (when-not (str/blank? body)
    (try (json/read-value body mapper) (catch Exception _ nil))))

(defn- read-json
  [request]
  (parse-json (raw-body request)))

(defn- now-of
  [deps]
  ((get deps :now-fn #(Date.))))

(defn- received-at
  "Wall clock of `deps`, in epoch seconds — what a signed notice is stamped in."
  [deps]
  (quot (.getTime ^Date (now-of deps)) 1000))

;; ---------------------------------------------------------------------------
;; who is asking

(defn- caller
  "The customer behind `request`, created on first sight, or nil."
  [{:keys [store identify-fn]} request]
  (when identify-fn
    (when-let [identity (identify-fn request)]
      (store/upsert-customer! store identity))))

(defn- with-customer
  [deps request handler]
  (if-let [customer (caller deps request)]
    (handler customer)
    (json-response 401 {:error "unauthenticated"})))

(defn- with-operator
  [{:keys [admin-token]} request handler]
  (if (identity/operator? admin-token request)
    (handler)
    (json-response 401 {:error "unauthenticated"})))

;; ---------------------------------------------------------------------------
;; storefront

(defn- item-quotes
  "A locked conversion per currency some registered rail settles in.

  An ESTIMATE for display: the invoice mints and stores its own quote at
  checkout. A currency the tickers cannot agree on is absent, never guessed."
  [{:keys [rails rates-fn] :as deps} item]
  (let [rates (if rates-fn (rates-fn) [])
        stored (:money/currency (:item/price item))]
    (into {}
          (keep (fn [currency]
                  (when-not (= currency stored)
                    (when-let [quoted (quotes/quote-for quotes/profile (:item/price item)
                                                        rates currency (now-of deps))]
                      [(name currency) (wire/quoted quoted)]))))
          (provider/currencies rails))))

(defn- catalog-handler
  [{:keys [rails] :as deps}]
  (fn [request]
    (let [operator? (identity/operator? (:admin-token deps) request)
          visible (if operator? (catalog/items) (catalog/listed))]
      (json-response 200
                     {:items (mapv #(wire/item % (item-quotes deps %)) visible)
                      :providers (mapv #(wire/provider (provider/profile rails %))
                                       (sort (provider/ids rails)))}))))

(defn- checkout-handler
  [{:keys [rails experiments] :as deps}]
  (fn [request]
    (with-customer deps request
      (fn [customer]
        (let [body (read-json request)
              item-id (some-> (:item body) name keyword)
              provider-id (some-> (:provider body) name keyword)
              visitor (shell/visitor-of request)]
          (cond
            (nil? (catalog/item item-id))
            (json-response 400 {:error "unknown item"})

            ;; An unlisted item is not merely unadvertised: knowing its id must
            ;; not be enough to buy at a price meant for an operator.
            (and (not (catalog/listed? item-id))
                 (not (identity/operator? (:admin-token deps) request)))
            (json-response 404 {:error "unknown item"})

            (nil? (provider/rail rails provider-id))
            (json-response 400 {:error "unknown payment provider"})

            :else
            (try
              (let [{:keys [invoice handle]}
                    (checkout/open! deps {:customer customer
                                          :item-id item-id
                                          :provider-id provider-id
                                          :visitor visitor
                                          :variants (experiment/assign experiments visitor)})]
                (json-response 201 {:invoice (wire/invoice-view invoice 0)
                                    :handle (wire/handle handle)}))
              (catch clojure.lang.ExceptionInfo e
                (if (= :quote-required (:monero-store/error (ex-data e)))
                  (json-response 503 {:error "payment provider unavailable"
                                      :provider (name provider-id)})
                  (throw e))))))))))

(defn- config-handler
  "What the browser needs to know about itself: which experiment arms it is in,
  and which arms exist.

  Never how the assignment is made, and never anyone else's — an endpoint that
  answers 'what would visitor X see' is an endpoint that leaks a cohort."
  [{:keys [experiments] :as deps}]
  (fn [request]
    (let [visitor (shell/visitor-of request)]
      (json-response 200
                     {:experiments (experiment/describe experiments
                                                        (experiment/assign experiments visitor))
                      :providers (mapv name (sort (provider/ids (:rails deps))))}))))

(defn- paid-so-far
  [store invoice]
  (transduce (map :payment/amount) + 0 (store/payments-for store (:invoice/id invoice))))

(defn- invoice-handler
  "One invoice, to the customer it belongs to and nobody else."
  [{:keys [store] :as deps}]
  (fn [request]
    (with-customer deps request
      (fn [customer]
        (let [invoice-id (some-> (get-in request [:path-params :id]) str parse-uuid)
              invoice (when invoice-id (store/invoice-by-id store invoice-id))]
          (if (and invoice (= (:customer/id customer) (:invoice/customer-id invoice)))
            (json-response 200 (wire/invoice-view invoice (paid-so-far store invoice)))
            (json-response 404 {:error "no such invoice"})))))))

(defn- my-invoices-handler
  [{:keys [store] :as deps}]
  (fn [request]
    (with-customer deps request
      (fn [customer]
        (json-response 200
                       {:invoices (mapv #(wire/invoice-view % (paid-so-far store %))
                                        (store/invoices-for-customer store (:customer/id customer)))
                        :fulfilments (mapv wire/fulfilment
                                           (store/fulfilments-for store (:customer/id customer)))})))))

;; ---------------------------------------------------------------------------
;; settlement notices

(defn- webhook-notice
  "The notice as it arrived: verbatim body, parsed body, headers, path
  parameters, arrival time in epoch seconds."
  [deps request body]
  {:notice/raw-body body
   :notice/payload (parse-json body)
   :notice/headers (:headers request)
   :notice/path-params (:path-params request)
   :notice/received-at (received-at deps)})

(def ^:private notice-responses
  {:notice/unauthenticated [400 {:error "unauthenticated settlement notice"}]
   :notice/unknown-invoice [404 {:error "unknown invoice"}]})

(defn- webhook-handler
  "Settle from a provider notice.

  The same handler serves every shape of webhook URL this store publishes: the
  invoice may be a path segment, or it may be a claim inside the body that the
  claimed rail knows how to read. Which one it is changes nothing about who
  authenticates the notice — the invoice's own rail always does."
  [deps]
  (fn [request]
    (let [claimed (some-> (get-in request [:path-params :provider]) keyword)
          verdict (notice/apply-notice! deps claimed
                                        (webhook-notice deps request (raw-body request)))]
      (if (= :notice/applied (:notice/verdict verdict))
        (json-response 200 {:outcome (str (symbol (:notice/outcome verdict)))})
        (let [[status body] (notice-responses (:notice/verdict verdict))]
          (json-response status body))))))

;; ---------------------------------------------------------------------------
;; operator

(defn- queue-handler
  "Everything a human has to look at: money seen but not applied, invoices
  still waiting, and how fresh the price feed is."
  [{:keys [store rates-fn] :as deps}]
  (fn [request]
    (with-operator deps request
      (fn []
        (json-response 200
                       {:unapplied (mapv wire/payment (store/unapplied-payments store 100))
                        :open (mapv wire/invoice-summary (store/open-invoices store))
                        :rates (mapv wire/rate (if rates-fn (rates-fn) []))})))))

(defn- grant-handler
  "Grant on the operator's word.

  `reference` is not optional: a grant nobody can attribute later is a hole in
  the books, and this rail exists precisely because no API can attest to the
  payment."
  [{:keys [store rails] :as deps}]
  (fn [request]
    (with-operator deps request
      (fn []
        (let [{:keys [customer item reference]} (read-json request)
              item-id (some-> item name keyword)
              found (when (seq (str customer)) (store/customer-by-ref store (str customer)))]
          (cond
            (nil? found) (json-response 404 {:error "no such customer"})
            (nil? (catalog/item item-id)) (json-response 400 {:error "unknown item"})
            (str/blank? (str reference)) (json-response 400 {:error "a grant needs a reference"})
            (nil? (provider/rail rails :manual)) (json-response 503 {:error "the manual rail is not registered"})
            :else
            (let [outcome (checkout/grant! deps {:customer found
                                                 :item-id item-id
                                                 :reference reference})]
              (json-response 201 {:outcome (str (symbol (:adt/variant outcome)))
                                  :customer (:customer/ref found)
                                  :item (name item-id)
                                  :reference reference}))))))))

(defn- reachability-handler
  "Can this deployment still reach the services it settles through?

  Operator-only: the answer names the hosts and ports a store talks to."
  [{:keys [endpoints probe reach-timeout-ms] :as deps}]
  (fn [request]
    (with-operator deps request
      (fn []
        (let [summary (reach/report (or probe (reach/socket-probe))
                                    (or endpoints [])
                                    (or reach-timeout-ms 2000))]
          (json-response (if (:reach/ok? summary) 200 503)
                         (wire/reachability summary)))))))

;; ---------------------------------------------------------------------------

(defn router
  [deps]
  (ring/router
   [["/healthz" {:get (fn [_] {:status 200 :body "ok"})}]
    ["/api"
     ["/config" {:get (config-handler deps)}]
     ["/catalog" {:get (catalog-handler deps)}]
     ["/checkout" {:post (checkout-handler deps)}]
     ["/invoices/:id" {:get (invoice-handler deps)}]
     ["/me/invoices" {:get (my-invoices-handler deps)}]
     ["/admin/queue" {:get (queue-handler deps)}]
     ["/admin/grants" {:post (grant-handler deps)}]
     ["/admin/reachability" {:get (reachability-handler deps)}]]
    ;; Three shapes, one handler. A rail that can name the invoice in the path
    ;; does; one that also carries a callback token appends it; a processor
    ;; that posts every event of an account to one endpoint names the invoice
    ;; in the body instead.
    ["/webhooks/:provider" {:post (webhook-handler deps)}]
    ["/webhooks/:provider/:invoice" {:post (webhook-handler deps)}]
    ["/webhooks/:provider/:invoice/:token" {:post (webhook-handler deps)}]]))

(def ^:private served-prefixes
  "Paths that own their own 404. The SPA shell must never shadow them."
  ["/api" "/webhooks" "/healthz"])

(defn- spa-fallback
  "Serve the SPA shell for a navigation that matched no route, so a deep link
  survives a reload. Nil for anything else, including every API path.

  This is also where an experiment assignment is made: the visitor id is minted
  here if it does not exist, the arms are written onto `<html>`, and the first
  paint is already the assigned design.

  There is no shell FILE: the document is hiccup, so the assignment is merged
  into an attribute map. Nothing static can be served in its place, which means
  every navigation is assigned — a raw shell fetched by name would show the
  control arm to whoever asked for it, a broken experiment that looks like a
  working one."
  [{:keys [experiments shell-options]}]
  (fn [request]
    (when (and (contains? #{:get :head} (:request-method request))
               (not-any? #(str/starts-with? (str (:uri request)) %) served-prefixes))
      (let [known (shell/visitor-of request)
            visitor (or known (str (UUID/randomUUID)))
            assignments (experiment/assign experiments visitor)]
        (-> {:status 200
             :headers {"content-type" "text/html; charset=utf-8"
                       "cache-control" "no-store"}
             :body (shell/render (assoc shell-options
                                        :attributes (experiment/attributes experiments assignments)))}
            (shell/set-visitor-cookie visitor (nil? known)))))))

(defn handler
  [deps]
  (ring/ring-handler
   (router deps)
   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (spa-fallback deps)
    (ring/create-default-handler))))

;; ---------------------------------------------------------------------------
;; contracts
;;
;; A handler builder takes the dependency map and returns a function of a ring
;; request. Naming the request shape here would be a fiction: what arrives is
;; whatever the adapter and the middleware stack made of the socket.

(def Response
  "What every handler in this namespace returns."
  [:map [:status :int] [:headers {:optional true} :map] [:body {:optional true} :any]])

(m/=> json-response [:=> [:cat :int :any] Response])

(m/=> raw-body [:=> [:cat :map] [:maybe :string]])

(m/=> parse-json [:=> [:cat :any] :any])

(m/=> read-json [:=> [:cat :map] :any])

(m/=> now-of [:=> [:cat :map] schema/Instant])

(m/=> received-at [:=> [:cat :map] :int])

(m/=> caller [:=> [:cat :map :map] [:maybe :map]])

(m/=> with-customer [:=> [:cat :map :map ifn?] Response])

(m/=> with-operator [:=> [:cat :map :map ifn?] Response])

(m/=> item-quotes [:=> [:cat :map schema/Item] [:map-of :string :map]])

(m/=> catalog-handler [:=> [:cat :map] ifn?])

(m/=> checkout-handler [:=> [:cat :map] ifn?])

(m/=> config-handler [:=> [:cat :map] ifn?])

(m/=> paid-so-far [:=> [:cat :any schema/Invoice] :int])

(m/=> invoice-handler [:=> [:cat :map] ifn?])

(m/=> my-invoices-handler [:=> [:cat :map] ifn?])

(m/=> webhook-notice [:=> [:cat :map :map [:maybe :string]]
                      [:map [:notice/raw-body [:maybe :string]]
                            [:notice/payload :any]]])

(m/=> webhook-handler [:=> [:cat :map] ifn?])

(m/=> queue-handler [:=> [:cat :map] ifn?])

(m/=> grant-handler [:=> [:cat :map] ifn?])

(m/=> reachability-handler [:=> [:cat :map] ifn?])

(m/=> router [:=> [:cat :map] :any])

(m/=> spa-fallback [:=> [:cat :map] ifn?])

(m/=> handler [:=> [:cat :map] ifn?])
