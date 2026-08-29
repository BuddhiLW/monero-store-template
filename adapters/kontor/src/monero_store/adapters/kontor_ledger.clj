(ns monero-store.adapters.kontor-ledger
  "ILedger over a kontor book.

  Minor units become the BigDecimal kontor posts through `currency/->display`,
  so the store's scale table is the one definition of a currency's decimal
  places on both sides of the boundary. A reference that was already posted is
  refused rather than re-posted: `:kontor.transaction/external-id` is
  unique-identity, so transacting it twice upserts onto the SAME transaction
  and doubles its postings."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [kontor.book :as book]
            [malli.core :as m]
            [monero-store.collect.ledger :as ledger]
            [monero-store.currency :as currency]
            [monero-store.schema :as schema]
            [kontor.core :as kontor]))

(def default-accounts
  "Ledger account -> the kontor account path it posts to."
  {:receivable "Assets:Receivable"
   :revenue    "Income:Sales"
   :wallet     "Assets:Wallet"})

(defn symbol-of
  "`currency-id` as the commodity symbol kontor knows it by."
  [currency-id]
  (str/upper-case (name currency-id)))

(defn ->amount
  "`minor-units` of `currency-id` as the BigDecimal kontor posts."
  [currency-id minor-units]
  (bigdec (currency/->display currency-id minor-units)))

(defn ->minor-units
  "`amount`, a BigDecimal of `currency-id`, back as minor units."
  [currency-id amount]
  (currency/->minor-units currency-id (str amount)))

(defn chart
  "Tx-data installing the accounts, journals and commodities this ledger posts
  to. Each commodity's precision is `currency/scale`, so the store and the book
  cannot disagree about a currency's decimals."
  [currencies]
  (into [{:kontor.account/path "Assets:Receivable" :kontor.account/name "Trade receivables"
          :kontor.account/type :asset :kontor.account/active true}
         {:kontor.account/path "Income:Sales" :kontor.account/name "Sales revenue"
          :kontor.account/type :income :kontor.account/active true}
         {:kontor.account/path "Assets:Wallet" :kontor.account/name "Payment wallet"
          :kontor.account/type :asset :kontor.account/active true}
         {:kontor.journal/code "SALE" :kontor.journal/name "Sales"
          :kontor.journal/type :sale :kontor.journal/active true}
         {:kontor.journal/code "CASH" :kontor.journal/name "Cash receipts"
          :kontor.journal/type :cash :kontor.journal/active true}]
        (map (fn [c] {:kontor.commodity/symbol (symbol-of c)
                      :kontor.commodity/precision (or (currency/scale c) 0)}))
        currencies))

(defn bootstrap!
  "Install the chart for `currencies` into `conn`. Idempotent. Returns `conn`."
  [conn currencies]
  (d/transact conn (chart currencies))
  conn)

(defn file-config
  "A datahike config keeping the book on disk at `path`.

  The store id is derived from the path, so reopening the same path reaches
  the same book. Datahike demands a UUID here and rejects a string.

  History is kept and every index node content-addressed, which is what makes
  the book auditable; `:schema-flexibility :write` refuses an undeclared
  attribute rather than storing it."
  [path]
  {:store {:backend :file
           :path path
           :id (java.util.UUID/nameUUIDFromBytes (.getBytes ^String path "UTF-8"))}
   :keep-history? true
   :crypto-hash? true
   :schema-flexibility :write})

(defn memory-config
  "A datahike config keeping the book only as long as the process. For dev and
  staging, where losing the book is an inconvenience rather than money."
  []
  {:store {:backend :memory :id (random-uuid)}
   :keep-history? true
   :crypto-hash? true
   :schema-flexibility :write})

(defn open!
  "Open the book at `config`, creating it if absent, and return a connection
  with the kernel schema and this store's chart installed.

  Idempotent: an existing book is connected to and re-bootstrapped, which
  changes nothing because both installs are."
  [config currencies]
  (when-not (d/database-exists? config)
    (d/create-database config))
  (let [conn (d/connect config)]
    (kontor/install-schema! conn)
    (bootstrap! conn currencies)))

(defn- already-posted?
  [conn reference]
  (some? (d/entity (d/db conn) [:kontor.transaction/external-id reference])))

(defn- account-total
  "Sum of every posting's amount on `path` in `sym`, as a BigDecimal.

  Tuples are [posting-eid amount] rather than bare amounts, because a bare
  `[?amt ...]` find is a SET and would collapse two equal postings into one."
  [conn path sym]
  (->> (d/q '[:find ?p ?amt
              :in $ ?path ?sym
              :where
              [?a :kontor.account/path ?path]
              [?p :kontor.posting/account ?a]
              [?p :kontor.posting/amount ?amt]
              [?c :kontor.commodity/symbol ?sym]
              [?p :kontor.posting/commodity ?c]]
            (d/db conn) path sym)
       (map second)
       (reduce + 0M)))

(defn kontor-ledger
  "ILedger writing double-entry transactions into the kontor book at `conn`.

  `opts` may override `:accounts`, a ledger-account -> kontor-account-path map.
  The chart must already be installed; see `bootstrap!`."
  ([conn] (kontor-ledger conn {}))
  ([conn opts]
   (let [accounts (merge default-accounts (:accounts opts))]
     (reify ledger/ILedger
       (post! [_ entry]
         (let [reference (:entry/reference entry)
               currency (:entry/currency entry)]
           (when-not (already-posted? conn reference)
             (book/entry!
              conn
              {:journal-type (if (= :sale (:entry/kind entry)) :sale :cash)
               :journal-code-hint (if (= :sale (:entry/kind entry)) "SALE" "CASH")
               :effective-date (:entry/at entry)
               :external-id reference
               :commodity (symbol-of currency)
               :narration (str (name (:entry/kind entry)) " " (:entry/invoice-id entry))
               :postings (mapv (fn [{:leg/keys [account amount]}]
                                 {:account (get accounts account)
                                  :amount (->amount currency amount)})
                               (:entry/legs entry))})
             entry)))
       (posted? [_ reference]
         (already-posted? conn reference))
       (balance [_ account currency-id]
         (->minor-units currency-id
                        (account-total conn (get accounts account) (symbol-of currency-id))))))))

(m/=> symbol-of [:=> [:cat schema/CurrencyId] schema/NonBlank])
(m/=> ->amount [:=> [:cat schema/CurrencyId [:maybe :int]] decimal?])
(m/=> ->minor-units [:=> [:cat schema/CurrencyId decimal?] :int])
(m/=> chart [:=> [:cat [:sequential schema/CurrencyId]] [:sequential :map]])
(m/=> kontor-ledger [:function
                     [:=> [:cat :any] [:fn #(satisfies? ledger/ILedger %)]]
                     [:=> [:cat :any :map] [:fn #(satisfies? ledger/ILedger %)]]])

(m/=> bootstrap! [:=> [:cat :any [:sequential schema/CurrencyId]] :any])
(m/=> already-posted? [:=> [:cat :any schema/NonBlank] :boolean])
(m/=> account-total [:=> [:cat :any schema/NonBlank schema/NonBlank] decimal?])

(m/=> file-config [:=> [:cat schema/NonBlank] :map])
(m/=> memory-config [:=> [:cat] :map])
(m/=> open! [:=> [:cat :map [:sequential schema/CurrencyId]] :any])
