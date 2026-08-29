(ns monero-store.adapters.jdbc-store
  "IOrderStore over PostgreSQL.

  The two invariants the port declares are the database's here, which is the
  reason to run this adapter rather than the in-memory one:

  `record-payment!` is `on conflict do nothing` against a unique key on
  (invoice, reference) — the ledger de-duplicates in one statement, with no
  read-then-write window for two sweeps to race in.

  `claim-paid!` is a conditional UPDATE ... RETURNING. Exactly one transaction
  can move a row out of pending, so exactly one caller is told it may hand
  anything over, however many webhooks and sweeps arrive at once."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [monero-store.collect.store :as store]
            [monero-store.schema :as schema]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.sql Timestamp)
           (java.util Date UUID)))

(def ddl
  "Every statement needed to bring an empty database up.

  Idempotent, so it is safe to run at every boot; a deployment that prefers a
  migration tool can run these once and skip `migrate!`."
  ["create table if not exists customers (
      id uuid primary key,
      ref text unique not null,
      email text,
      created_at timestamptz not null default now())"
   "create table if not exists invoices (
      id uuid primary key,
      customer_id uuid not null references customers (id),
      item_id text not null,
      provider text not null,
      status text not null,
      amount bigint not null,
      currency text not null,
      external_ref text,
      quoted_rate double precision,
      quote_sources text,
      expires_at timestamptz,
      metadata text,
      created_at timestamptz not null default now())"
   "create index if not exists invoices_status_idx on invoices (status)"
   "create index if not exists invoices_live_idx on invoices (customer_id, item_id, provider, status)"
   "create table if not exists payments (
      id uuid primary key,
      invoice_id uuid not null references invoices (id),
      provider text not null,
      external_ref text not null,
      reference text not null,
      amount bigint not null,
      confirmations integer not null default 0,
      resolution text not null,
      seen_at timestamptz not null default now(),
      unique (invoice_id, reference))"
   "create table if not exists fulfilments (
      id bigserial primary key,
      invoice_id uuid not null references invoices (id),
      customer_id uuid not null references customers (id),
      item_id text not null,
      period_end timestamptz,
      granted_at timestamptz not null default now())"])

(defn datasource
  "A pooled DataSource for `:jdbc-url`."
  [{:keys [jdbc-url username password]}]
  (doto (HikariDataSource.)
    (.setJdbcUrl jdbc-url)
    (.setUsername username)
    (.setPassword password)
    (.setMaximumPoolSize 10)))

(defn migrate!
  [ds]
  (run! #(jdbc/execute! ds [%]) ddl)
  ds)

;; ---------------------------------------------------------------------------
;; rows <-> values

(def ^:private opts
  {:builder-fn rs/as-unqualified-lower-maps})

(defn- ->date
  [^Timestamp ts]
  (when ts (Date. (.getTime ts))))

(defn- ->timestamp
  [^Date date]
  (when date (Timestamp. (.getTime date))))

(defn- ->edn
  [value]
  (when value (pr-str value)))

(defn- <-edn
  [value]
  (when (and value (not (str/blank? value))) (edn/read-string value)))

(defn- row->customer
  [row]
  (when row
    {:customer/id (:id row)
     :customer/ref (:ref row)
     :customer/email (:email row)
     :customer/created-at (->date (:created_at row))}))

(defn- row->invoice
  [row]
  (when row
    (cond-> {:invoice/id (:id row)
             :invoice/customer-id (:customer_id row)
             :invoice/item-id (keyword (:item_id row))
             :invoice/provider (keyword (:provider row))
             :invoice/status (keyword (:status row))
             :invoice/amount (:amount row)
             :invoice/currency (keyword (:currency row))
             :invoice/external-ref (:external_ref row)
             :invoice/created-at (->date (:created_at row))}
      (:quoted_rate row) (assoc :invoice/quoted-rate (:quoted_rate row))
      (:quote_sources row) (assoc :invoice/quote-sources (<-edn (:quote_sources row)))
      (:expires_at row) (assoc :invoice/expires-at (->date (:expires_at row)))
      (:metadata row) (assoc :invoice/metadata (<-edn (:metadata row))))))

(defn- row->payment
  [row]
  (when row
    {:payment/id (:id row)
     :payment/invoice-id (:invoice_id row)
     :payment/provider (keyword (:provider row))
     :payment/external-ref (:external_ref row)
     :payment/reference (:reference row)
     :payment/amount (:amount row)
     :payment/confirmations (:confirmations row)
     :payment/resolution (keyword (:resolution row))
     :payment/seen-at (->date (:seen_at row))}))

(defn- row->fulfilment
  [row]
  (when row
    {:fulfilment/invoice-id (:invoice_id row)
     :fulfilment/customer-id (:customer_id row)
     :fulfilment/item-id (keyword (:item_id row))
     :fulfilment/period-end (->date (:period_end row))
     :fulfilment/granted-at (->date (:granted_at row))}))

(def ^:private open-status-names
  (mapv name store/open-statuses))

;; ---------------------------------------------------------------------------

(defn jdbc-store
  "IOrderStore over PostgreSQL. Runs `ddl` at construction."
  [config]
  (let [ds (migrate! (datasource config))
        one (fn [sql] (jdbc/execute-one! ds sql opts))
        many (fn [sql] (jdbc/execute! ds sql opts))]
    (with-meta
      (reify store/IOrderStore
        (upsert-customer! [_ {:customer/keys [ref email]}]
          (row->customer
           (one ["insert into customers (id, ref, email) values (?, ?, ?)
                  on conflict (ref) do update set email = coalesce(excluded.email, customers.email)
                  returning *"
                 (UUID/randomUUID) ref email])))

        (customer-by-id [_ customer-id]
          (row->customer (one ["select * from customers where id = ?" customer-id])))

        (customer-by-ref [_ customer-ref]
          (row->customer (one ["select * from customers where ref = ?" customer-ref])))

        (insert-invoice! [_ invoice]
          (row->invoice
           (one ["insert into invoices (id, customer_id, item_id, provider, status, amount,
                                        currency, external_ref, quoted_rate, quote_sources,
                                        expires_at, metadata, created_at)
                  values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning *"
                 (:invoice/id invoice)
                 (:invoice/customer-id invoice)
                 (name (:invoice/item-id invoice))
                 (name (:invoice/provider invoice))
                 (name (:invoice/status invoice))
                 (:invoice/amount invoice)
                 (name (:invoice/currency invoice))
                 (:invoice/external-ref invoice)
                 (:invoice/quoted-rate invoice)
                 (->edn (:invoice/quote-sources invoice))
                 (->timestamp (:invoice/expires-at invoice))
                 (->edn (:invoice/metadata invoice))
                 (->timestamp (or (:invoice/created-at invoice) (Date.)))])))

        (invoice-by-id [_ invoice-id]
          (row->invoice (one ["select * from invoices where id = ?" invoice-id])))

        (live-invoice-for [_ {:keys [customer-id item-id provider]}]
          (row->invoice
           (one (into ["select * from invoices
                        where customer_id = ? and item_id = ? and provider = ?
                          and status = any (?) order by created_at desc limit 1"
                       customer-id (name item-id) (name provider)]
                      [(into-array String open-status-names)]))))

        (invoices-for-customer [_ customer-id]
          (mapv row->invoice
                (many ["select * from invoices where customer_id = ? order by created_at desc"
                       customer-id])))

        (open-invoices [_]
          (mapv row->invoice
                (many ["select * from invoices where status = any (?) order by created_at desc"
                       (into-array String open-status-names)])))

        (attach-external-ref! [_ invoice-id external-ref]
          (row->invoice
           (one ["update invoices set external_ref = ? where id = ? returning *"
                 external-ref invoice-id])))

        (set-invoice-status! [_ invoice-id status]
          (row->invoice
           (one ["update invoices set status = ? where id = ? returning *"
                 (name status) invoice-id])))

        (claim-paid! [_ invoice-id]
          (row->invoice
           (one ["update invoices set status = 'paid'
                  where id = ? and status = any (?) returning *"
                 invoice-id (into-array String open-status-names)])))

        (release-claim! [_ invoice-id]
          (one ["update invoices set status = 'pending' where id = ? and status = 'paid'"
                invoice-id])
          nil)

        (record-payment! [_ payment]
          (row->payment
           (one ["insert into payments (id, invoice_id, provider, external_ref, reference,
                                        amount, confirmations, resolution)
                  values (?, ?, ?, ?, ?, ?, ?, ?)
                  on conflict (invoice_id, reference) do nothing returning *"
                 (UUID/randomUUID)
                 (:payment/invoice-id payment)
                 (name (:payment/provider payment))
                 (:payment/external-ref payment)
                 (:payment/reference payment)
                 (:payment/amount payment)
                 (:payment/confirmations payment)
                 (name (:payment/resolution payment))])))

        (payments-for [_ invoice-id]
          (mapv row->payment
                (many ["select * from payments where invoice_id = ? order by seen_at" invoice-id])))

        (unapplied-payments [_ limit]
          (mapv row->payment
                (many ["select * from payments where resolution = 'late'
                        order by seen_at desc limit ?" limit])))

        (record-fulfilment! [_ fulfilment]
          (row->fulfilment
           (one ["insert into fulfilments (invoice_id, customer_id, item_id, period_end)
                  values (?, ?, ?, ?) returning *"
                 (:fulfilment/invoice-id fulfilment)
                 (:fulfilment/customer-id fulfilment)
                 (name (:fulfilment/item-id fulfilment))
                 (->timestamp (:fulfilment/period-end fulfilment))])))

        (fulfilments-for [_ customer-id]
          (mapv row->fulfilment
                (many ["select * from fulfilments where customer_id = ? order by granted_at desc"
                       customer-id]))))
      {:datasource ds})))

;; ---------------------------------------------------------------------------
;; contracts

(def DbConfig
  [:map
   [:jdbc-url schema/NonBlank]
   [:username {:optional true} [:maybe :string]]
   [:password {:optional true} [:maybe :string]]])

(m/=> datasource [:=> [:cat DbConfig] [:fn #(instance? HikariDataSource %)]])

(m/=> migrate! [:=> [:cat :any] :any])

(m/=> ->date [:=> [:cat [:maybe schema/Instant]] [:maybe schema/Instant]])

(m/=> ->timestamp [:=> [:cat [:maybe schema/Instant]] [:maybe schema/Instant]])

(m/=> ->edn [:=> [:cat :any] [:maybe :string]])

(m/=> <-edn [:=> [:cat [:maybe :string]] :any])

(m/=> row->customer [:=> [:cat [:maybe :map]] [:maybe schema/Customer]])

(m/=> row->invoice [:=> [:cat [:maybe :map]] [:maybe schema/Invoice]])

(m/=> row->payment [:=> [:cat [:maybe :map]] [:maybe schema/Payment]])

(m/=> row->fulfilment [:=> [:cat [:maybe :map]] [:maybe schema/Fulfilment]])

(m/=> jdbc-store [:=> [:cat DbConfig] [:fn #(satisfies? store/IOrderStore %)]])
