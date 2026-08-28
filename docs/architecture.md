# Architecture

The store is five strata and one wiring namespace. Each stratum is a directory
under `src/monero_store/`, and the direction of dependency between them is the
architecture — everything else is detail.

```
boundary/    what leaves the process
pipeline/    the operations, and the effects they cause
payments/    the rails, as data plus a protocol
promote/     what the values mean — pure
collect/     the ports, and the adapters that need no SDK
```

`schema.clj`, `adt.clj` and `currency.clj` sit underneath all of them: the
value objects, the closed sums and the currency registry. They require nothing
from the strata above, so a namespace at any level can name a domain value
without dragging a layer in with it.

`design/` is the sixth directory under `src/monero_store/` and is not a
stratum. `design/theme.clj` is the storefront stylesheet as data; `bb tokens`
renders it to `resources/public/css/store.css` through the `:design` alias.
Nothing under `src/monero_store/` requires it, and it requires nothing back.

## The strata

CPPB is Collect, Promote, Pipeline, Boundary. `payments` is the fifth stratum,
between `promote` and `pipeline`; the acronym does not name it. The rule for
each is what it may not contain, not what it may.

| Stratum | Namespaces | Holds | Must not hold |
| --- | --- | --- | --- |
| `collect` | `store`, `wallet`, `cards`, `rates`, `fulfilment`, `http`, `analytics`, `reachability` | The protocols a deployment satisfies, plus adapters that need no vendor SDK, plus a fake for each | Any decision about money. A port describes an effect; it never says what the effect means |
| `promote` | `catalog`, `quote`, `invoice`, `experiments` | What a price, a quote, an invoice and an experiment assignment mean. `invoice` and `experiments` are functions of their arguments; `catalog` and `quote` each keep one process-global registry — see below | I/O, and a clock of its own — `invoice/resolution` is handed the instant it reasons about |
| `payments` | `provider`, `chain`, `hosted`, `stripe`, `manual` | `IPaymentRail`, the registry, the `ProviderProfile` each rail declares, and `settle` | A branch on which rail it is. `settle` reads thresholds off the profile and nothing else |
| `pipeline` | `checkout`, `notice`, `reconcile` | `open!`, `settle!`, `grant!`, `apply-notice!`, `sweep!`. The exactly-once transition and the late-money rule | An HTTP status. `notice` returns a verdict value; what that is on the wire is the boundary's problem |
| `boundary` | `routes`, `wire`, `identity`, `shell` | Routing, the projection of domain values to wire shapes, the identity seam, the authorisation gates `caller`, `with-customer` and `with-operator`, and the SPA shell as hiccup | A decision that could be made below. A handler resolves who is asking, calls one pipeline operation, and projects the result |

The authorisation gates are the decisions `boundary` does keep, and they are
tested where they live: `test/monero_store/boundary/routes_test.clj` holds
eleven tests, among them `the-operator-surface-is-closed-without-the-token`,
`an-invoice-is-visible-only-to-the-customer-it-belongs-to` and
`checkout-needs-to-know-who-is-asking`.

`system.clj` is the only namespace that knows what a deployment is. It reads
the environment, builds every adapter, and hands the result down as one map.

### Two registries in `promote` are global

`catalog/registry` and `quote/bounds` are `defonce` atoms, not values carried
in `deps`:

```clojure
(defonce ^:private registry
  (atom {}))
```

`catalog` is mutated by `register!`, `register-all!` and `clear!`; `quote` by
`set-bounds!`. `system/start!` calls `(catalog/clear!)` and then
`(catalog/register-all! items)`, so the catalog is one registry per JVM: two
embedded stores in the same process cannot sell different things, and the
second `start!` discards the first's items. The rail registry below is a value
for reasons that apply to the catalog too. The catalog does not have them.

## Dependencies point one way

No namespace in `collect` requires `promote`, `payments`, `pipeline` or
`boundary`. No namespace in `promote` requires `payments`, `pipeline` or
`boundary`. No namespace in `payments` requires `pipeline` or `boundary`, and
no namespace in `pipeline` requires `boundary`. There are no exceptions and no
waivers.

The consequence worth having is that the money path is testable without a
request, a socket or a coin: `checkout/settle!` takes a `deps` map and two
values, so every settlement decision in the suite is a function call.

## The ports

Each port has at least one adapter that ships in the core and one fake, so the
whole path runs with no daemon, no processor account and no coin.

| Port | Declared in | Ships in the core | Behind an alias |
| --- | --- | --- | --- |
| `IPaymentRail` | `payments.provider` | `chain/->rail`, `hosted/->rail`, `manual/->rail` | — |
| `IOrderStore` | `collect.store` | `store/memory-store` | `adapters.jdbc-store` (`:jdbc`) |
| `IChainWallet` | `collect.wallet` | `wallet/moneropay-wallet`, `wallet/fake-wallet` | `adapters.monero-rpc` (`:monero-rpc`) |
| `ICardGateway` | `collect.cards` | `cards/fake-gateway` | `adapters.stripe-cards` (`:stripe`) |
| `IRateSource` | `collect.rates` | `rates/registry` over `rates/sources`, `rates/fixed-source` | — |
| `IFulfilment` | `collect.fulfilment` | `fulfilment/noop`, `/logging`, `/ledger`, `/composite` | — |
| `IHttp` | `collect.http` | `http/hato-client`, `http/stub-client` | — |
| `IAnalytics` | `collect.analytics` | `analytics/noop`, `/logging`, `/memory`, `/umami` | — |
| `IEndpointProbe` | `collect.reachability` | `reachability/socket-probe`, `reachability/fake-probe` | — |

`IFulfilment` is the one a host application is expected to implement: the store
decides whether money was good, the host decides what that buys. `IOrderStore`
carries the two invariants — `record-payment!` is idempotent per (invoice,
reference), and `claim-paid!` is a compare-and-set that tells exactly one
caller it won the transition to `:paid`. It transitions from either open
status: `store/open-statuses` is `#{:pending :underpaid}`, so an underpaid
invoice that is later topped up still claims. An implementation over a real
database gates on that set, not on `:pending` alone.

## The rail registry is a value

`provider/registry` returns a map from provider id to `{:profile .. :rail ..}`.
It is built at boot and carried in `deps`; there is no global atom anywhere in
`payments`.

```clojure
(defn registry
  [entries]
  (into {}
        (map (fn [{:keys [profile rail]}]
               (schema/check! schema/ProviderProfile profile
                              {:monero-store/producer `registry})
               [(:provider/id profile) {:profile profile :rail rail}]))
        entries))
```

Three reasons it is a value and not a global.

Two stores can hold different rails in one process. A test and the system under
test can carry different registries at the same time, in the same JVM, without
either seeing the other's rails — which is what lets the suite register a rail
with a deliberately malformed profile without breaking anything else. Their
catalogs are not separated this way; that is the cost recorded above.

A registry that is a value has no load order. Registration is not a side
effect of requiring a namespace, so which rails exist is decided in one place,
`system/rails`, and is visible in the boot log.

Validation has somewhere to happen. `registry` checks every `ProviderProfile`
as it builds the map, so a rail whose declared behaviour is malformed fails at
boot, where an operator is watching, rather than at the first customer.

What a profile declares is the whole swap point:

```clojure
(def ProviderProfile
  [:map {:closed true}
   [:provider/id ProviderId]
   [:provider/currency CurrencyId]
   [:provider/min-confirmations [:int {:min 0}]]
   [:provider/underpay-tolerance [:int {:min 0}]]
   [:provider/settles-async? :boolean]
   [:provider/settlement-poll? :boolean]
   [:provider/webhook-auth [:enum :none :signed-payload :path-token :server-confirmed]]])
```

`provider/settle` reads two of those fields — `:provider/min-confirmations` and
`:provider/underpay-tolerance` — and produces a `SettlementOutcome`. The rest
are read by name elsewhere: `:provider/currency` by `provider/currency-of`,
`:provider/settlement-poll?` by `provider/pollable?`, `:provider/webhook-auth`
by `provider/webhook-settleable?`, and `:provider/settles-async?` by
`wire/provider`, which projects it to the storefront as `:async`. No function
among them branches on which rail it is, so a new rail is a new registry entry
and no edit below it.

The order of the clauses is the policy. `:settle/reject` is first: a settlement
the rail reports as `:failed` is rejected whatever else is true of it.
`:settle/suspect` is second, so a double spend that leaves the invoice short is
suspect before it can be pending, underpaid or granted.

The same reasoning runs one level up. `system/start!` builds

```clojure
{:store .. :rails .. :fulfilment .. :identify-fn .. :analytics ..
 :experiments .. :admin-token .. :callback-base .. :rates-fn ..}
```

and threads it through every pipeline call. A host application embedding the
store overrides any of those keys by passing them to `start!`.

## SDK adapters live on their own source roots

The core carries no payment-provider SDK. `:paths` is `["src" "resources"]`,
and each SDK-backed adapter is an `:extra-paths` entry on its own alias:

| Alias | Source root | Dependency it pulls |
| --- | --- | --- |
| `:monero-rpc` | `adapters/monero-rpc/src` | `io.github.woodser/monero-java` |
| `:stripe` | `adapters/stripe/src` | `com.stripe/stripe-java` |
| `:jdbc` | `adapters/jdbc/src` | `next.jdbc`, `postgresql`, `HikariCP` |

Three things follow.

A deployment pays only for the rails it runs. A store that settles in Monero
through MoneroPay never resolves stripe-java or a JDBC driver, and its uberjar
does not contain them.

The adapters are resolved at runtime, not at compile time. `system/optional-fn`
is `requiring-resolve` in a `try`; a build without `:stripe` has no Stripe
gateway and logs that it has none, instead of failing to start. `STORE_BACKEND=jdbc`
without the `:jdbc` alias falls back to the memory store and says so.

Authentication does not need the vendor jar. Stripe webhook signatures are
verified with `javax.crypto` in `payments.stripe`, which is core source — the
SDK is confined to `adapters.stripe-cards`, the namespace that opens sessions.
A deployment that only needs to authenticate notices carries no Stripe jar at
all.

The published artifact follows the same rule: `io.github.buddhilw/monero-store`
is a source jar of `src` and `resources`, with no payment SDK in its
dependencies. The protocols are what a consumer implements, and an AOT'd record
compiled against a source-shipped protocol fails to link at the consumer's
loader.

## The model is data, the diagrams are generated

The architecture is described once, as EDN, under `models/monero-store/`:

| File | Holds |
| --- | --- |
| `model.edn` | The C4 elements — actors, the system, its containers, the payment components, the ports, the external systems, and every relation between them |
| `views.edn` | Which elements each view contains, and in which direction the relations are laid out |
| `state.edn` | One state machine, `:monero-store/invoice-lifecycle`, and its view. There is no separate settlement machine — the `SettlementOutcome` variants appear as transition labels inside this one |
| `deployment.edn` | The compose stack as nodes — the Docker host, the store, PostgreSQL, MoneroPay, wallet-rpc, monerod and the volumes — and its view |

The PlantUML under `docs/diagrams/plantuml/monero-store/` is rendered from
those files by overarch. Nothing there is hand-drawn, and editing a `.puml` by
hand is a change that the next render discards. Regenerate with:

```
clojure -M:arch --model-dir models --render-format plantuml --render-dir docs/diagrams
```

### The views

| Diagram | Answers |
| --- | --- |
| [`context-view.puml`](diagrams/plantuml/monero-store/context-view.puml) | Who the store serves, and every system it depends on to take money — the payer, the operator, the host application, MoneroPay, monero-wallet-rpc, Stripe, the rate tickers, PostgreSQL, analytics |
| [`container-view.puml`](diagrams/plantuml/monero-store/container-view.puml) | All five strata as deployable parts, and the one-way dependency between them: storefront to boundary, boundary to pipeline, pipeline to payments, promote and collect, and never back |
| [`rails-view.puml`](diagrams/plantuml/monero-store/rails-view.puml) | The DIP seam. How the registry, the profiles, `settle`, the three rails and the Stripe reader relate — and therefore what adding a rail actually touches |
| [`ports-view.puml`](diagrams/plantuml/monero-store/ports-view.puml) | Everything the store refuses to decide for you: `IOrderStore`, `IChainWallet`, `ICardGateway`, `IRateSource`, `IFulfilment`, `IEndpointProbe`, and which external system satisfies each |

Three further views are rendered from the same model:
`integration-view.puml`, one picture of every system the money path touches and
which direction each integration runs; `invoice-lifecycle-view.puml`, the
invoice state machine from `state.edn`; and `deployment-view.puml`, the compose
stack from `deployment.edn`. The command writes seven `.puml` files in all, and
all seven are checked in beside their rendered `.svg`.
