# Embedding the store

There are two ways to use this project, and they answer different questions.

| You want | Take | Because |
|---|---|---|
| A whole store — storefront, stylesheet pipeline, adapters, Docker compose — as the starting point for something you will own outright | Clone the repository | The parts you will edit are the parts a jar cannot give you: `tokens.edn`, `catalog.example.edn`, the ClojureScript under `ui/src`, the adapters under `adapters/` |
| The money path, inside an application you already have | Depend on the coordinate | You already have a web server, a session, a database and a notion of what a customer is; you need quoting, invoicing, settlement and reconciliation, not another storefront |

The rest of this page is about the second one.

## The coordinate

```clojure
io.github.buddhilw/monero-store {:mvn/version "0.3.0"}
```

`version.edn` declares `:src-dirs ["src" "resources"]`. From `src` that is the
money path and its ports — the HTML shell is in there too, as hiccup data in
`monero-store.boundary.shell`, not as a template file. From `resources` it is
the compiled storefront: `public/css/tokens.css`, `public/css/store.css`,
`public/js/main.js` and the shadow-cljs runtime beside it — 287 files, which
land at the classpath root under `public/`. Treat those as a wart of the
current build rather than an offer: a consumer with its own `resources/public`
collides with them, and the storefront is a reason to clone the repository, not
to depend on the coordinate.

The jar carries `malli`, `hive-dsl`, `reitit`, `ring`, `aleph`, `hato`,
`jsonista`, `hiccup` and `timbre` — and no payment-provider SDK at all. Every SDK-backed
adapter lives on its own source root behind its own alias in this repository
(`:monero-rpc`, `:stripe`, `:jdbc`) and is not published under this coordinate,
so a consumer inherits zero vendor jars and pays only for the rails it runs.

### Why a source jar

`:build` names `hive-build.api` and produces a **source** jar deliberately. The
things a consumer supplies are protocols — `IPaymentRail` in
`monero-store.payments.provider`, and `IOrderStore`, `IFulfilment`,
`IChainWallet`, `ICardGateway`, `IRateSource`, `IAnalytics`, `IHttp`,
`IEndpointProbe` under `monero-store.collect.*`. A protocol compiled AOT is a
Java interface with an identity fixed at compile time. If the jar shipped
compiled classes and the consumer compiled a record against the source it also
had, the record would implement one interface while the store dispatched on
another, and the two would not link at the consumer's class loader. Shipping
source only means the protocol is compiled once, in the consumer's own image,
by the consumer's own loader.

The practical consequence: the consumer compiles this code, so the consumer's
Clojure version and classpath are the ones that matter.

### Resolving it

Both the artifact and its `io.github.hive-agi/*` dependencies come from the
hive-gitea Maven registry, which is not a default repository. `deps.edn` needs
the entry this repository's own `deps.edn` carries:

```clojure
:mvn/repos {"hive-gitea" {:url "https://gitea.hive-mcp.com/api/packages/hive-agi/maven"}}
```

To build it locally instead — from a checkout of this repository:

```
clojure -T:build install     ;; -> ~/.m2, for a consumer to resolve
```

## The seams

`monero-store.system/start!` takes one map. Eleven of its keys are seams; every
other key is configuration and overrides the environment. The list is literal,
read straight out of `start!`:

```clojure
seams [:fulfilment :identify-fn :catalog :rails :store :rates-fn
       :analytics :experiments :endpoints :probe :ledger]
```

Keep that vector and this page identical. A seam missing from `seams` is not
merely undocumented — `(apply dissoc overrides seams)` will not strip it, so a
host that passes it has it absorbed into `cfg` as a config key and it never
reaches `deps`. `:ledger` spent its whole life that way: read by
`checkout/open!` and `settle!`, written by neither `config` nor `start!`, while
five tests covering it stayed green because they built the deps map by hand.

Most are `(or (:seam overrides) (built-from-the-environment))`, so passing
nothing gives you the environment-configured default and passing something
replaces it entirely. Two are exceptions.

`:ledger` is read with `contains?`, not `or`, because nil is a value it can
legitimately take: `:ledger nil` books nothing, while an absent key lets
`LEDGER` decide. `or` cannot tell those apart — the same trap spelled out under
`:identify-fn` below.

`:catalog` is the other. It is not a value in `deps` at all — it is a side
effect on a registry, taken before `deps` is built:

```clojure
(if-let [items (:catalog overrides)]
  (do (catalog/clear!) (catalog/register-all! items))
  (load-catalog! cfg))
```

| Seam | You pass | Default when you do not |
|---|---|---|
| `:identify-fn` | `(fn [ring-request] {:customer/ref .. :customer/email ..})` — the function may return `nil` for "nobody" | `identity/anonymous` — resolves nobody, unless `IDENTITY=header` |
| `:fulfilment` | An `IFulfilment` — `fulfilment/handler` wraps a plain function | `FULFILMENT` selects it; the fallback is `composite [ledger logging]` |
| `:catalog` | A vector of `Item` maps | The file `CATALOG_FILE` names, else `catalog/sample-catalog` |
| `:store` | An `IOrderStore` | `STORE_BACKEND`, defaulting to `store/memory-store` — an atom |
| `:rails` | A `provider/registry` of `{:profile .. :rail ..}` entries | Whatever `MONERO_BACKEND`, `CARDS_BACKEND` and `DISABLE_MANUAL_RAIL` describe |
| `:rates-fn` | A 0-arity function returning spot readings | `rates/feed` over the public tickers, cached for `RATE_CACHE_TTL_MS` |
| `:analytics` | An `IAnalytics` | `ANALYTICS`, defaulting to `analytics/noop` |
| `:ledger` | An `ILedger`, or `nil` to book nothing | `LEDGER`, defaulting to `ledger/memory-ledger`; `kontor` needs the `:kontor` alias, `none` books nothing |
| `:experiments` | A map of experiment to arms | Read from `TOKENS_FILE` |
| `:endpoints` | Services this store does not configure but the host needs reachable | Whatever `endpoints` reads from the environment |
| `:probe` | An `IEndpointProbe` | `reach/socket-probe` — a TCP connect |

`start!` returns `{:server :deps :stop-reconcile :config}`. Hand that same map
back to `system/stop!` to shut down: it stops the reconciliation loop, closes
the Aleph server, and closes `(:datasource (meta (:store deps)))` when the
store carries one — so a store you pass in whose metadata names a pooled
datasource will have that pool closed for you.

### The three that are usually yours

**`:identify-fn`** is a function of the Ring request. It answers "who is
asking", and it is the only thing the store knows about authentication —
because it is the only thing the store does not do. Return the map, or `nil`
when the request identifies nobody. The `:customer/ref` you return is what
`upsert-customer!` correlates on, so it must be stable for a given person.
Passing `:identify-fn nil` is not a way to say "nobody". `start!` reads the
seam as `(or (:identify-fn overrides) (identify-fn-of (:identity cfg)))`, which
cannot tell a `nil` value from an absent key, so it falls through to the
environment — and under `IDENTITY=header` that is the header-trusting one. Only
the function's return value carries "nobody".

**`:fulfilment`** must be idempotent for one invoice id. The store gates on the
invoice's `pending -> paid` transition, and `claim-paid!` returns the invoice
only for the call that performed it, so under normal operation `fulfil!` is
called once. But a process that dies between the claim and the call is picked
up by the next reconciliation sweep, and at-least-once is the only delivery a
store and a foreign system can honestly agree on. Throwing from `fulfil!` means
you did not deliver: the claim is released and the sweep retries.

The grant you receive is `{:fulfilment/invoice-id :fulfilment/customer-id
:fulfilment/item-id :fulfilment/period-end}`.

**`:catalog`** is a vector of `Item`, validated on registration by
`schema/Item`, which is `{:closed true}`:

```clojure
[:map {:closed true}
 [:item/id ItemId]
 [:item/name NonBlank]
 [:item/blurb {:optional true} [:maybe :string]]
 [:item/price Money]
 [:item/period Period]
 [:item/listed? {:optional true} :boolean]
 [:item/metadata {:optional true} [:maybe :map]]]
```

`:item/metadata` is yours — the store stores it, correlates on it, and never
reads it. Note that the catalog registry is a process-global `defonce` atom and
passing `:catalog` calls `catalog/clear!` before registering, so one JVM serves
one catalog.

### A worked example

The one in the repository is `dev/user.clj`, which boots the real pipeline over
faked rails so the whole money path can be driven from a REPL. It is a genuine
embedding — seven of the eight seams supplied, nothing on the network:

```clojure
(defn demo-deps
  "Everything the store needs, faked."
  []
  (let [order-store (store/memory-store)
        chain-wallet (wallet/fake-wallet)
        gateway (cards/fake-gateway)
        granted (atom [])]
    {:store order-store
     :wallet chain-wallet
     :gateway gateway
     :granted granted
     :rails (provider/registry
             [(chain/entry {:wallet chain-wallet :provider-id :monero})
              (stripe/entry {:gateway gateway
                             :webhook-secret "whsec_dev"
                             :success-url "http://localhost:8080/"
                             :cancel-url "http://localhost:8080/"})
              (manual/entry)])
     :fulfilment (fulfilment/composite
                  [(fulfilment/ledger order-store)
                   (fulfilment/handler (fn [grant] (swap! granted conj grant) grant))])
     :identify-fn (identity/header-identity)
     ;; A recorder rather than a vendor: `(events)` reads the funnel back in
     ;; the REPL, which is the whole point of having the seam.
     :analytics (analytics/memory)
     :rates-fn demo-rates}))
```

and the call:

```clojure
(system/start!
 (merge {:port port
         :admin-token "dev-operator"
         :callback-base (str "http://localhost:" port)
         :catalog catalog/sample-catalog}
        (select-keys demo [:store :rails :fulfilment :identify-fn :rates-fn :analytics])))
```

`:port`, `:admin-token` and `:callback-base` are not seams — they are config
keys, and passing them overrides `PORT`, `ADMIN_TOKEN` and `PUBLIC_BASE_URL`.

`:wallet`, `:gateway` and `:granted` are not seams either. `go` merges them
back into `:deps` after `start!` returns, so `(pay! ..)`, `(granted)` and the
card gateway are reachable from the REPL.

`:experiments` is the eighth seam, and `go` does not pass it: it comes from
`load-experiments` over the file `TOKENS_FILE` names, defaulting to
`tokens.edn`. The `:dev` alias does not put design-forge on the classpath, so
that resolves to no experiments and says so in the log — which is what a store
booted without its design pipeline should have.

A host application substitutes its own three and lets the rest come from the
environment:

```clojure
(require '[monero-store.system :as system]
         '[monero-store.collect.fulfilment :as fulfilment])

(system/start!
 {:identify-fn (fn [request]
                 (when-let [user (my-auth/user-of request)]
                   {:customer/ref (:id user) :customer/email (:email user)}))

  :fulfilment  (fulfilment/handler
                (fn [{:fulfilment/keys [customer-id item-id period-end]}]
                  (my-app/grant! customer-id item-id period-end)))

  :catalog     [{:item/id :pro
                 :item/name "Pro"
                 :item/price {:money/amount 9900 :money/currency :usd :money/scale 2}
                 :item/period :yearly}]})
```

`fulfilment/handler` exists so a host with no reason to reify a protocol does
not have to. It implements `revoke!` as a no-op; reify `IFulfilment` yourself
if a chargeback has to take something back.

## What the store does not do

Three omissions, all deliberate.

**It does not authenticate anyone.** The default `identify-fn` is
`identity/anonymous`, which resolves nobody. `identify-fn-of` offers exactly
one alternative to it: `IDENTITY=header` selects `identity/header-identity`,
which trusts `x-customer-ref` outright — whoever sets the header is whoever
they say they are — and logs a warning saying so at boot. It exists so the
template runs end to end before a deployment has wired anything, and for a
deployment sitting behind a gateway that has already authenticated the caller
and is asserting the result. One more implementation ships without being
env-selectable: `identity/token-identity`, a static map of bearer token to
`{:customer/ref :customer/email}` compared in constant time, for machine
clients and for tests — a host passes it as `:identify-fn` rather than writing
one. Anything else real passes its own.

**It does not decide what a sale entitles someone to.** The store decides
WHETHER money arrived; the host decides WHAT that buys. Nothing below
`IFulfilment` knows what a `:pro` is — the item is a registered value object
with a price and a period, and `:item/metadata` passes through untouched.
Without a `:fulfilment`, `FULFILMENT=ledger` records the grant in the order
store and logs it, which is a record of who bought what and nothing more.

**It does not persist anything by default.** `STORE_BACKEND` defaults to
`memory`, and `store/memory-store` is an `IOrderStore` over an atom: every
write is a `swap!`, which is exactly why the compare-and-set that `claim-paid!`
depends on is the atom's own rather than a lock. It is what the tests run on,
and it loses everything when the process exits. A real deployment either runs
the `:jdbc` alias — which is in this repository, not in the jar — or passes its
own `:store`, which is the point of the seam: persist where you already
persist.

Note the failure mode `order-store` chooses. Asking for `STORE_BACKEND=jdbc`
without the `:jdbc` alias on the classpath does not refuse to boot; it falls
back to memory and says so in the log, because a template that refuses to boot
teaches nothing. In an embedded deployment that is a trap — check the log, or
pass `:store` explicitly and remove the question.

## Adding a rail

A rail is an `IPaymentRail` plus a `ProviderProfile`, registered together:

```clojure
(provider/registry [{:profile .. :rail ..} ..])
```

`registry` validates each profile against `schema/ProviderProfile` as it goes,
so a rail whose declared behaviour is malformed fails at boot, where an
operator is watching, rather than at the first customer. Pass the result as
`:rails` and nothing in `settle`, the pipeline or the boundary changes — the
profile carries the thresholds, the registry carries the rail.

See the README's "Adding a payment provider" for the reader-data shortcut that
covers a processor with a hosted checkout.
