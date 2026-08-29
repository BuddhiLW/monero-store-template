(ns monero-store.system
  "Process composition: read the environment, wire the rails, serve.

  The only namespace that knows what a deployment is. Adapters that need a
  vendor SDK are resolved at runtime — a build without the `:stripe` alias
  simply has no Stripe gateway, and says so in the log instead of failing to
  start.

  A host application embeds this store by calling `start!` with its own
  fulfilment, identity, and catalog; everything else comes from the
  environment."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [aleph.http :as aleph]
            [monero-store.boundary.identity :as identity]
            [monero-store.boundary.routes :as routes]
            [monero-store.collect.cards :as cards]
            [monero-store.collect.fulfilment :as fulfilment]
            [monero-store.collect.http :as http]
            [monero-store.collect.rates :as rates]
            [monero-store.collect.store :as store]
            [monero-store.collect.wallet :as wallet]
            [monero-store.payments.chain :as chain]
            [monero-store.payments.manual :as manual]
            [monero-store.payments.provider :as provider]
            [monero-store.payments.stripe :as stripe]
            [monero-store.pipeline.reconcile :as reconcile]
            [monero-store.promote.catalog :as catalog]
            [taoensso.timbre :as log]
            [monero-store.collect.analytics :as analytics]
            [malli.core :as m]
            [monero-store.schema :as schema]
            [monero-store.collect.reachability :as reach])
  (:gen-class))

(defn- env
  ([k] (System/getenv k))
  ([k default] (or (System/getenv k) default)))

(defn- env-flag
  "True when `k` is set to one of true/1/yes, case-insensitively."
  [k]
  (contains? #{"true" "1" "yes"} (str/lower-case (str (env k "")))))

(defn- env-long
  [k default]
  (try (Long/parseLong (env k (str default))) (catch Exception _ default)))

(defn- optional-fn
  "The var `sym` names, or nil when its namespace is not on this classpath.

  How an SDK-backed adapter stays optional without a compile-time dependency."
  [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn config
  "The deployment, read from the environment."
  []
  (let [base-url (env "PUBLIC_BASE_URL" "http://localhost:8080")]
    {:port (int (env-long "PORT" 8080))
     :callback-base base-url
     :admin-token (env "ADMIN_TOKEN")
     :identity (keyword (env "IDENTITY" "none"))
     :catalog-file (env "CATALOG_FILE")
     ;; The design contract. Experiments are read from the SAME file the
     ;; stylesheet is generated from, so an arm that can be assigned at runtime
     ;; is an arm whose CSS shipped and whose contrast was checked.
     :tokens-file (env "TOKENS_FILE" "tokens.edn")
     :store {:backend (keyword (env "STORE_BACKEND" "memory"))
             :jdbc-url (env "DATABASE_URL")
             :username (env "DATABASE_USER")
             :password (env "DATABASE_PASSWORD")}
     :fulfilment (keyword (env "FULFILMENT" "ledger"))
     :analytics {:backend (keyword (env "ANALYTICS" "none"))
                 :base-url (env "UMAMI_URL")
                 :website-id (env "UMAMI_WEBSITE_ID")
                 :hostname (env "ANALYTICS_HOSTNAME" "store")}
     :chain {:backend (keyword (env "MONERO_BACKEND" "none"))
             :provider-id (keyword (env "MONERO_PROVIDER_ID" "monero"))
             :currency (keyword (env "MONERO_CURRENCY" "xmr"))
             :min-confirmations (env-long "MONERO_MIN_CONFIRMATIONS" 10)
             :callback-secret (env "MONERO_CALLBACK_SECRET")
             :moneropay-url (env "MONEROPAY_URL" "http://moneropay:5000")
             :wallet-rpc {:uri (env "MONERO_WALLET_RPC_URI")
                          :username (env "MONERO_WALLET_RPC_USERNAME")
                          :password (env "MONERO_WALLET_RPC_PASSWORD")
                          :account-index (int (env-long "MONERO_ACCOUNT_INDEX" 0))}}
     :cards {:backend (keyword (env "CARDS_BACKEND" "none"))
             :currency (keyword (env "CARDS_CURRENCY" "usd"))
             :api-base (env "STRIPE_API_BASE")
             :api-key (env "STRIPE_API_KEY")
             :webhook-secret (env "STRIPE_WEBHOOK_SECRET")
             :success-url (str base-url "/paid")
             :cancel-url (str base-url "/")}
     :manual? (not (env-flag "DISABLE_MANUAL_RAIL"))
     :rates {:ttl-ms (env-long "RATE_CACHE_TTL_MS" 60000)
             :timeout-ms (env-long "RATE_TIMEOUT_MS" 10000)}
     :reachability {:timeout-ms (env-long "REACHABILITY_TIMEOUT_MS" 2000)}
     :reconcile {:interval-ms (env-long "RECONCILE_INTERVAL_MS" 60000)}}))

(defn load-experiments
  "The experiments declared in the token file, or none.

  Read through design-forge, resolved LAZILY: the design pipeline is a BUILD
  dependency, and a deployment that ships the generated CSS without it still
  runs — with no experiments, which is what a store with no design pipeline
  should have. A missing or malformed token file is not fatal either: refusing
  to boot a payment system over a design artifact would be the wrong trade."
  [{:keys [tokens-file]}]
  (try
    (let [parse (optional-fn 'design-forge.tokens/parse)
          format-of (optional-fn 'design-forge.tokens/format-of)
          resolve-tokens (optional-fn 'design-forge.tokens/resolve-tokens)]
      (cond
        (not (and parse format-of resolve-tokens))
        (do (log/info "design-forge is not on the classpath; running with no experiments")
            {})

        (not (and tokens-file (.exists (io/file tokens-file))))
        (do (log/warn "no token file; running with no experiments" {:file tokens-file})
            {})

        :else
        (:experiments (resolve-tokens (parse (slurp tokens-file) (format-of tokens-file))))))
    (catch Throwable t
      (log/warn t "token file could not be read; running with no experiments"
                {:file tokens-file})
      {})))

(defn analytics-of
  "The IAnalytics a deployment asked for."
  [{:keys [backend] :as config} client]
  (case backend
    :umami (if (str/blank? (str (:website-id config)))
             (do (log/warn "ANALYTICS=umami but UMAMI_WEBSITE_ID is unset; measuring nothing")
                 (analytics/noop))
             (analytics/umami (assoc config :client client)))
    :log (analytics/logging)
    (analytics/noop)))

;; ---------------------------------------------------------------------------
;; wiring

(defn order-store
  "The IOrderStore a deployment asked for.

  `:jdbc` needs the `:jdbc` alias; without it the store falls back to memory
  and says so, because a template that refuses to boot teaches nothing."
  [{:keys [backend] :as config}]
  (if (= :jdbc backend)
    (if-let [build (optional-fn 'monero-store.adapters.jdbc-store/jdbc-store)]
      (build config)
      (do (log/warn "STORE_BACKEND=jdbc but the :jdbc alias is not on the classpath; using memory")
          (store/memory-store)))
    (store/memory-store)))

(defn chain-wallet
  "The IChainWallet a deployment asked for, or nil when it wants no chain rail."
  [{:keys [backend moneropay-url wallet-rpc]} client]
  (case backend
    :moneropay (wallet/moneropay-wallet {:client client :base-url moneropay-url})
    :fake (wallet/fake-wallet)
    :wallet-rpc (if-let [build (optional-fn 'monero-store.adapters.monero-rpc/rpc-wallet)]
                  (if (str/blank? (str (:uri wallet-rpc)))
                    (do (log/warn "MONERO_BACKEND=wallet-rpc but MONERO_WALLET_RPC_URI is unset") nil)
                    (build wallet-rpc))
                  (do (log/warn "MONERO_BACKEND=wallet-rpc but the :monero-rpc alias is not on the classpath")
                      nil))
    nil))

(defn card-gateway
  "The ICardGateway a deployment asked for, or nil."
  [{:keys [backend api-key] :as config}]
  (case backend
    :fake (cards/fake-gateway)
    :stripe (if-let [build (optional-fn 'monero-store.adapters.stripe-cards/stripe-gateway)]
              (if (str/blank? (str api-key))
                (do (log/warn "CARDS_BACKEND=stripe but STRIPE_API_KEY is unset") nil)
                (build config))
              (do (log/warn "CARDS_BACKEND=stripe but the :stripe alias is not on the classpath")
                  nil))
    nil))

(defn rails
  "The rail registry for `config`.

  A rail that cannot settle is never advertised: a chain rail needs a wallet to
  watch, and a card rail needs the credentials it opens and re-reads sessions
  with. A configured Stripe with no webhook secret still registers and still
  refuses every notice — the signed fast path is off, the polled path carries
  it."
  [{:keys [chain cards manual?]} {:keys [client]}]
  (let [wallet (chain-wallet chain client)
        gateway (card-gateway cards)]
    (provider/registry
     (cond-> []
       wallet (conj (chain/entry {:wallet wallet
                                  :provider-id (:provider-id chain)
                                  :callback-secret (:callback-secret chain)
                                  :provider/currency (:currency chain)
                                  :provider/min-confirmations (:min-confirmations chain)}))
       gateway (conj (stripe/entry (assoc cards
                                          :gateway gateway
                                          :provider/currency (:currency cards))))
       manual? (conj (manual/entry {:provider/currency (:currency cards)}))))))

(defn endpoints
  "The services this deployment must be able to reach, read off the same
  config the builders above are wired from.

  A rail that is not configured is not probed."
  [{:keys [chain cards store analytics]}]
  (into []
        (keep identity)
        [(when (= :moneropay (:backend chain))
           (reach/endpoint-of-url "moneropay" (:moneropay-url chain)))
         (when (= :wallet-rpc (:backend chain))
           (reach/endpoint-of-url "wallet-rpc" (:uri (:wallet-rpc chain))))
         (when (= :jdbc (:backend store))
           (reach/endpoint-of-url "database" (:jdbc-url store)))
         (when (= :stripe (:backend cards))
           (reach/endpoint-of-url "cards" (or (:api-base cards) "https://api.stripe.com")))
         (when (= :umami (:backend analytics))
           (reach/endpoint-of-url "analytics" (:base-url analytics)))]))

(defn report-reachability!
  "Print what a telnet to each configured service would have told you, and
  return the summary. Reads the same environment `start!` does."
  []
  (let [cfg (config)
        summary (reach/report (reach/socket-probe)
                              (endpoints cfg)
                              (get-in cfg [:reachability :timeout-ms]))]
    (doseq [{:reach/keys [label host port outcome elapsed-ms detail]} (:reach/endpoints summary)]
      (println (format "%-12s %s:%-6s %-13s %5dms%s"
                       label host port
                       (name (:adt/variant outcome))
                       elapsed-ms
                       (if detail (str "  " detail) ""))))
    (when (zero? (:reach/checked summary))
      (println "no service is configured: this deployment reaches nothing over the network"))
    summary))

(defn rates-feed
  "A cached round over the public tickers, as a 0-arity function."
  [{:keys [ttl-ms]} client]
  (rates/feed {:ttl-ms ttl-ms
               :rate-sources (rates/registry {:client client})}))

(defn fulfilment-of
  "The IFulfilment a deployment asked for."
  [kind order-store]
  (case kind
    :none (fulfilment/noop)
    :log (fulfilment/logging)
    (fulfilment/composite [(fulfilment/ledger order-store) (fulfilment/logging)])))

(defn identify-fn-of
  "The identity seam a deployment asked for.

  `:header` trusts a request header outright, so it is only ever a demo or a
  deployment sitting behind a gateway that has already authenticated the
  caller. Anything real supplies its own `:identify-fn` to `start!`."
  [kind]
  (case kind
    :header (do (log/warn "IDENTITY=header: the store trusts x-customer-ref outright")
                (identity/header-identity))
    (identity/anonymous)))

(defn load-catalog!
  "Register into `catalog` the items the file `:catalog-file` names, or the
  sample."
  [catalog {:keys [catalog-file]}]
  (catalog/clear! catalog)
  (if (and catalog-file (.exists (io/file catalog-file)))
    (catalog/register-all! catalog (edn/read-string (slurp catalog-file)))
    (do (log/warn "no CATALOG_FILE; registering the sample catalog")
        (catalog/register-all! catalog catalog/sample-catalog))))

;; ---------------------------------------------------------------------------
;; lifecycle

(defn start!
  "Boot the store. Returns {:server :deps :stop-reconcile :config}.

  `overrides` is how a host application embeds this store: `:fulfilment` to
  hand something over, `:identify-fn` to say who is asking, `:catalog` to sell
  its own things, `:rails` to register a rail this template has never heard of,
  `:store` to persist somewhere it already runs, `:rates-fn` to price from a
  feed it already has, `:analytics` to measure with its own sink,
  `:experiments` to run arms declared somewhere other than the token file,
  `:endpoints` to name services this store does not configure but the host
  needs reachable, and `:probe` to reach them some way other than a TCP
  connect. Anything else is a config key and overrides the environment.

  The catalog is created here, per store. Two stores embedded in one JVM sell
  different things."
  ([] (start! {}))
  ([overrides]
   (let [seams [:fulfilment :identify-fn :catalog :rails :store :rates-fn
                :analytics :experiments :endpoints :probe]
         cfg (merge (config) (apply dissoc overrides seams))
         client (http/hato-client {:timeout-ms (get-in cfg [:rates :timeout-ms])})
         order-store (or (:store overrides) (order-store (:store cfg)))
         catalogue (catalog/store)
         _ (if-let [items (:catalog overrides)]
             (catalog/register-all! catalogue items)
             (load-catalog! catalogue cfg))
         deps {:store order-store
               :catalog catalogue
               :rails (or (:rails overrides) (rails cfg {:client client}))
               :fulfilment (or (:fulfilment overrides)
                               (fulfilment-of (:fulfilment cfg) order-store))
               :identify-fn (or (:identify-fn overrides) (identify-fn-of (:identity cfg)))
               :analytics (or (:analytics overrides) (analytics-of (:analytics cfg) client))
               :experiments (or (:experiments overrides) (load-experiments cfg))
               :admin-token (:admin-token cfg)
               :callback-base (:callback-base cfg)
               :endpoints (into (endpoints cfg) (:endpoints overrides))
               :probe (or (:probe overrides) (reach/socket-probe))
               :reach-timeout-ms (get-in cfg [:reachability :timeout-ms])
               :rates-fn (or (:rates-fn overrides) (rates-feed (:rates cfg) client))}
         stop-reconcile (reconcile/start! deps (:reconcile cfg))
         server (aleph/start-server (routes/handler deps) {:port (:port cfg)})]
     (when (str/blank? (str (:admin-token cfg)))
       (log/warn "operator surface disabled: ADMIN_TOKEN is unset"))
     (when (empty? (:rails deps))
       (log/warn "no payment rail is registered: nothing can be sold"))
     (log/info "monero-store up" {:port (:port cfg)
                                  :rails (sort (provider/ids (:rails deps)))
                                  :items (mapv :item/id (catalog/items catalogue))
                                  :endpoints (mapv :endpoint/label (:endpoints deps))
                                  :experiments (sort (keys (:experiments deps)))})
     {:server server :deps deps :stop-reconcile stop-reconcile :config cfg})))

(defn stop!
  [{:keys [server deps stop-reconcile]}]
  (when stop-reconcile (stop-reconcile))
  ;; An Aleph server is a Closeable, not a Jetty Server — .stop does not exist
  ;; on it, and the difference only shows at shutdown.
  (when server (.close ^java.io.Closeable server))
  (when-let [ds (:datasource (meta (:store deps)))]
    (.close ^java.io.Closeable ds)))

(defn -main
  [& _]
  (let [system (start!)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(stop! system)))
    @(promise)))

;; ---------------------------------------------------------------------------
;; contracts
;;
;; Every builder here answers the same question — given configuration, which
;; implementation of a port does this deployment get — so each returns :any:
;; the value is a reify, and a protocol is not a value schema.

(m/=> env [:function [:=> [:cat :string] [:maybe :string]]
                     [:=> [:cat :string [:maybe :string]] [:maybe :string]]])

(m/=> env-flag [:=> [:cat :string] :boolean])

(m/=> env-long [:=> [:cat :string :int] :int])

(m/=> optional-fn [:=> [:cat qualified-symbol?] [:maybe ifn?]])

(m/=> config [:=> :cat :map])

(m/=> load-experiments [:=> [:cat [:map [:tokens-file {:optional true} [:maybe :string]]]]
                        [:map-of :keyword :map]])

(m/=> analytics-of [:=> [:cat :map :any] :any])

(m/=> order-store [:=> [:cat :map] :any])

(m/=> chain-wallet [:=> [:cat :map :any] [:maybe :any]])

(m/=> card-gateway [:=> [:cat :map] [:maybe :any]])

(m/=> rails [:=> [:cat :map :map] [:map-of :keyword :map]])

(m/=> endpoints [:=> [:cat :map] [:vector schema/Endpoint]])

(m/=> report-reachability! [:=> :cat schema/ReachabilitySummary])

(m/=> rates-feed [:=> [:cat :map :any] ifn?])

(m/=> fulfilment-of [:=> [:cat [:maybe :keyword] :any] :any])

(m/=> identify-fn-of [:=> [:cat [:maybe :keyword]] ifn?])

(m/=> load-catalog! [:=> [:cat :any [:map [:catalog-file {:optional true} [:maybe :string]]]]
                     [:vector schema/Item]])

(m/=> start! [:function
              [:=> :cat [:map [:server :any] [:deps :map] [:config :map]]]
              [:=> [:cat [:maybe :map]] [:map [:server :any] [:deps :map] [:config :map]]]])

(m/=> stop! [:=> [:cat [:maybe :map]] :any])

(m/=> -main [:=> [:cat [:* :any]] :any])
