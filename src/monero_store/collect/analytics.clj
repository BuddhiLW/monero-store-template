(ns monero-store.collect.analytics
  "Where the store says what happened — as a port.

  A store that cannot see its own funnel cannot be improved, and a store that
  ships its customers' payment details to an analytics vendor should not exist.
  Both facts are handled by the same seam: the pipeline emits DOMAIN events
  with named fields, and an adapter decides what leaves the building.

  Ships with a no-op, a logger, an in-memory recorder for tests, and an Umami
  adapter — Umami because it is self-hostable, so the funnel of a Monero store
  need not be posted to an advertising network to be measured."
  (:require [clojure.string :as str]
            [monero-store.collect.http :as http]
            [taoensso.timbre :as log]))

(defprotocol IAnalytics
  (track! [this event]
    "Record `event`, which is

      {:event/name      keyword — :checkout/opened, :invoice/settled, …
       :event/visitor   an opaque, store-assigned id; never an email
       :event/variants  {experiment variant} in force for this visitor
       :event/props     a map of small, non-identifying values}

    Must not throw: analytics is never a reason to fail a payment. Adapters
    swallow their own failures and say so in the log."))

(def ^:private forbidden-prop-keys
  "Fields that must never reach an analytics vendor, however convenient.

  An address is a pseudonym until it is joined to anything else; an email or a
  transaction hash joined to a funnel event is a deanonymisation waiting to be
  queried. The store measures COUNTS, not people."
  #{:email :customer :customer-id :address :pay-to :external-ref :reference
    :tx-hash :references :secret :token})

(defn scrub
  "`props` with every forbidden key removed, and every value reduced to
  something countable.

  Deliberately strict: an unrecognised value type becomes its class name rather
  than being passed through, because the failure mode of a permissive scrubber
  is silent and permanent."
  [props]
  (into {}
        (keep (fn [[k v]]
                (when-not (contains? forbidden-prop-keys (keyword (name k)))
                  [(keyword (name k))
                   (cond
                     (or (string? v) (keyword? v) (number? v) (boolean? v)) v
                     (nil? v) nil
                     :else (.getSimpleName (class v)))])))
        props))

(defn event
  "A well-formed event."
  ([name] (event name {}))
  ([name props] {:event/name name :event/props (scrub props)}))

(defn noop
  "Analytics that measures nothing. The default."
  []
  (reify IAnalytics
    (track! [_ _event] nil)))

(defn logging
  []
  (reify IAnalytics
    (track! [_ event] (log/info "event" event) nil)))

(defn memory
  "Analytics that remembers, for tests. `(events analytics)` reads it back."
  []
  (let [state (atom [])]
    (with-meta
      (reify IAnalytics
        (track! [_ event] (swap! state conj event) nil))
      {:state state})))

(defn events
  [analytics]
  (some-> (:state (meta analytics)) deref))

(defn composite
  "Every one of `sinks`, in order. One that throws does not stop the others —
  a broken vendor must not take the funnel down with it."
  [sinks]
  (reify IAnalytics
    (track! [_ event]
      (doseq [sink sinks]
        (try (track! sink event)
             (catch Throwable t
               (log/warn t "analytics sink failed" {:event (:event/name event)}))))
      nil)))

;; ---------------------------------------------------------------------------
;; Umami

(defn- umami-payload
  [{:keys [website-id hostname]} {:event/keys [name props visitor variants]}]
  {:type "event"
   :payload (cond-> {:website website-id
                     :name (subs (str name) 1)
                     :hostname hostname
                     :url (str "/" (namespace name))
                     :data (cond-> (or props {})
                             (seq variants) (merge (into {}
                                                         (map (fn [[k v]]
                                                                [(keyword (str "variant-" (clojure.core/name k)))
                                                                 (clojure.core/name v)]))
                                                         variants)))}
              visitor (assoc :tag visitor))})

(defn umami
  "IAnalytics over a self-hosted Umami instance.

  `:base-url` is the Umami origin, `:website-id` the site's uuid. Umami rejects
  a request with no User-Agent, so one is always sent. Failures are logged and
  swallowed: a customer paying an invoice does not care that a dashboard is
  down."
  [{:keys [client base-url website-id hostname user-agent]
    :or {hostname "store" user-agent "monero-store/1.0"}
    :as config}]
  (reify IAnalytics
    (track! [_ event]
      (when-not (str/blank? (str website-id))
        (try
          (let [response (http/request client
                                       {:http/method :post
                                        :http/url (str base-url "/api/send")
                                        :http/headers {"content-type" "application/json"
                                                       "user-agent" user-agent}
                                        :http/body (umami-payload (assoc config :hostname hostname) event)})]
            (when-not (http/ok? response)
              (log/warn "analytics rejected" {:status (:http/status response)
                                              :event (:event/name event)})))
          (catch Throwable t
            (log/warn t "analytics unreachable" {:event (:event/name event)}))))
      nil)))
