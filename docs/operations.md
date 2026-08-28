# Running it

This page covers the environment the store reads, the surface an operator
works through, why a reconciliation sweep exists alongside webhooks, the shape
of a deployment, and how to tell whether a chain backend is reachable before
blaming the store.

Everything below was read from the code. Every variable in the tables is one
`monero-store.system/config` actually reads; every route is one
`monero-store.boundary.routes/router` actually declares.

## The environment

`config` reads the environment once, at `start!`. There is no reload — a
changed variable takes a restart.

Three readers do all the work, and their failure modes matter:

| Reader | Behaviour |
|---|---|
| `env` | `System/getenv`, falling back to the default when the variable is absent |
| `env-long` | parses a long; **an unparseable value silently becomes the default** |
| `env-flag` | true only for `true`, `1` or `yes`, case-insensitively |

`env` falls back on absence, not on emptiness. `FOO=` is the empty string, and
only a place that calls `str/blank?` treats that as unset. Those places are the
admin token, the Stripe API key, the Stripe API base, the Stripe webhook
secret, the Monero callback secret, the wallet-rpc URI and the Umami website
id. For each of them the "unset" column below reads the same for `FOO=` as for
an absent `FOO`. Everywhere else an empty string is a value, not a default.

### Process

| Variable | Default | Unset |
|---|---|---|
| `PORT` | `8080` | serves on 8080 |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | callbacks and the Stripe return URLs are built against localhost — wrong for anything a browser reaches from elsewhere |

`PUBLIC_BASE_URL` is the base for `:callback-base` and, verbatim, for Stripe's
`:success-url` (`<base>/paid`) and `:cancel-url` (`<base>/`).

### The operator surface and identity

| Variable | Default | Unset |
|---|---|---|
| `ADMIN_TOKEN` | none | there is no operator surface: `/api/admin/*` answers 401 to everyone, and `start!` logs `operator surface disabled: ADMIN_TOKEN is unset` |
| `IDENTITY` | `none` | the anonymous identity resolves nobody, so checkout has no customer to bill |

`IDENTITY=header` selects `header-identity`, which trusts `x-customer-ref`
outright. `identify-fn-of` logs a warning when it does. That is a demo setting,
or a deployment behind a gateway that has already authenticated the caller.

### What is for sale, and how it looks

| Variable | Default | Unset |
|---|---|---|
| `CATALOG_FILE` | none | the sample catalog is registered, with a warning. A named file that does not exist takes the same path |
| `TOKENS_FILE` | `tokens.edn` | experiments are read from `tokens.edn` |

`load-experiments` resolves design-forge lazily and treats a missing or
malformed token file as no experiments rather than a boot failure — refusing to
start a payment system over a design artifact would be the wrong trade.

### Persistence and fulfilment

| Variable | Default | Unset |
|---|---|---|
| `STORE_BACKEND` | `memory` | the in-memory store, which forgets everything on restart — including who paid |
| `DATABASE_URL` | none | passed to the JDBC adapter; ignored under `memory` |
| `DATABASE_USER` | none | as above |
| `DATABASE_PASSWORD` | none | as above |
| `FULFILMENT` | `ledger` | the composite of the ledger and the logger |

`STORE_BACKEND=jdbc` needs the `:jdbc` alias on the classpath. Without it,
`order-store` logs `STORE_BACKEND=jdbc but the :jdbc alias is not on the
classpath; using memory` and falls back. It boots; it does not persist.

`FULFILMENT` takes `none` (noop) and `log` (logging only). Anything else,
including the default, gives the composite.

### The chain rail

| Variable | Default | Unset |
|---|---|---|
| `MONERO_BACKEND` | `none` | no wallet, so no chain rail is registered |
| `MONERO_PROVIDER_ID` | `monero` | the rail is registered under `:monero` |
| `MONERO_CURRENCY` | `xmr` | the rail settles in XMR |
| `MONERO_MIN_CONFIRMATIONS` | `10` | fewer than ten confirmations is `:settle/pending` |
| `MONERO_CALLBACK_SECRET` | none | nothing signs the token in the callback URL |
| `MONEROPAY_URL` | `http://moneropay:5000` | the compose service name |
| `MONERO_WALLET_RPC_URI` | none | under `MONERO_BACKEND=wallet-rpc` the wallet is nil and the rail is not registered, logged |
| `MONERO_WALLET_RPC_USERNAME` | none | no RPC digest credentials |
| `MONERO_WALLET_RPC_PASSWORD` | none | as above |
| `MONERO_ACCOUNT_INDEX` | `0` | the wallet's first account |

`MONERO_BACKEND` takes `fake` (a wallet with no network, which is how the
template demonstrates the whole money path), `moneropay`, and `wallet-rpc`.
`wallet-rpc` additionally needs the `:monero-rpc` alias; without it,
`chain-wallet` logs and returns nil, and `rails` registers no chain rail — a
rail that cannot settle is never advertised.

### The card rail

| Variable | Default | Unset |
|---|---|---|
| `CARDS_BACKEND` | `none` | no gateway, so no card rail is registered |
| `CARDS_CURRENCY` | `usd` | the card rail settles in USD — and so does the manual rail, see below |
| `STRIPE_API_KEY` | none | under `CARDS_BACKEND=stripe` nothing is registered, logged |
| `STRIPE_API_BASE` | none | the SDK's own base |
| `STRIPE_WEBHOOK_SECRET` | none | the rail still registers and refuses every notice — the signed fast path is off and the sweep carries settlement |

`CARDS_BACKEND` takes `fake` and `stripe`. Anything else, including the
default, builds no gateway and registers no card rail. `fake` builds
`cards/fake-gateway`, an `ICardGateway` over an atom that needs no SDK and no
alias — the card-side counterpart of `MONERO_BACKEND=fake`, and how the card
path is exercised without a processor account.

`CARDS_BACKEND=stripe` needs the `:stripe` alias. Without it, `card-gateway`
logs `CARDS_BACKEND=stripe but the :stripe alias is not on the classpath` and
returns nil.

### The manual rail

| Variable | Default | Unset |
|---|---|---|
| `DISABLE_MANUAL_RAIL` | unset | the manual rail is registered |
| `CARDS_CURRENCY` | `usd` | the manual rail settles in USD |

Set `DISABLE_MANUAL_RAIL` to `true`, `1` or `yes` to drop the rail. Dropping it
disables `POST /api/admin/grants`, which answers 503 when the rail is absent.

`CARDS_CURRENCY` is read once and used twice. `manual/profile` declares
`:provider/currency :usd`, but `rails` builds the entry as `(manual/entry
{:provider/currency (:currency cards)})`, and `entry` is
`{:profile (merge profile (select-keys config (keys profile))) …}` — so the
passed value wins over the profile's own. The manual rail takes the card
currency whether or not a card rail is registered: `CARDS_BACKEND=none` with
`CARDS_CURRENCY=eur` prices operator grants in EUR.

### Rates and reconciliation

| Variable | Default | Unset |
|---|---|---|
| `RATE_CACHE_TTL_MS` | `60000` | a ticker round is cached for a minute |
| `RATE_TIMEOUT_MS` | `10000` | the shared HTTP client times out after ten seconds |
| `RECONCILE_INTERVAL_MS` | `60000` | a sweep every minute |

`RATE_TIMEOUT_MS` is the timeout of the one `hato` client `start!` builds — the
same client the rate sources, MoneroPay and Umami all use.

### Measurement

| Variable | Default | Unset |
|---|---|---|
| `ANALYTICS` | `none` | the noop sink; nothing is measured |
| `UMAMI_URL` | none | no endpoint to post to |
| `UMAMI_WEBSITE_ID` | none | under `ANALYTICS=umami`, `analytics-of` logs `measuring nothing` and installs the noop sink |
| `ANALYTICS_HOSTNAME` | `store` | events are attributed to the hostname `store` |

`ANALYTICS` also takes `log`.

### Drift between `.env.example` and the code

`.env.example` is a starting point, not the full list. Eight variables `config`
reads are absent from it:

`MONERO_PROVIDER_ID` · `MONERO_CURRENCY` · `MONERO_ACCOUNT_INDEX` ·
`CARDS_CURRENCY` · `STRIPE_API_BASE` · `FULFILMENT` · `DISABLE_MANUAL_RAIL` ·
`RATE_TIMEOUT_MS`

Every variable `.env.example` does list is read. `VERSION` is read too, but by
`build.clj` at build time — it names the uberjar and defaults to `0.1.0`. The
running store never reads it.

## The operator surface

Two routes are behind `with-operator`:

| Route | Does |
|---|---|
| `GET /api/admin/queue` | money seen but not applied, invoices still waiting, and how fresh the price feed is |
| `POST /api/admin/grants` | opens a manual invoice for a customer and settles it in the same breath |

`GET /healthz` returns `200 ok` and is gated by nothing.

### What ADMIN_TOKEN gates

`with-operator` calls `identity/operator?`, which compares `ADMIN_TOKEN`
against the request's bearer credential in constant time and refuses a blank
configured token outright:

```clojure
(defn operator?
  "True when `request` carries the configured operator token.

  Constant time, and an unconfigured token accepts nothing: an operator
  surface that fails open is worse than no operator surface."
  [admin-token request]
  (boolean
   (and (not (str/blank? (str admin-token)))
        (constant-time= (str admin-token) (str (bearer-token request))))))
```

An unset `ADMIN_TOKEN` therefore does not mean an open admin surface — it means
a closed one. Both routes answer `401 {"error":"unauthenticated"}` to every
caller, including one presenting an empty token.

The credential is read from the `authorization` header and must carry the
`bearer ` prefix, matched case-insensitively.

```
curl -s -H "authorization: Bearer $ADMIN_TOKEN" \
     http://localhost:8080/api/admin/queue
```

Those two routes are not the token's whole reach. Two public routes call
`identity/operator?` themselves, outside `with-operator`, and a bearer widens
them rather than being refused by them:

| Route | Without the token | With it |
|---|---|---|
| `GET /api/catalog` | `catalog/listed` — advertised items only | `catalog/items` — every registered item, unlisted ones included |
| `POST /api/checkout` | an unlisted item is `404 {"error":"unknown item"}` | an unlisted item can be bought |

An item is unlisted when its `:item/listed?` is `false`; anything else,
absence included, is listed. `checkout-handler` carries the reason the second
row is a gate rather than a courtesy:

> An unlisted item is not merely unadvertised: knowing its id must not be
> enough to buy at a price meant for an operator.

So `ADMIN_TOKEN` is the key to the operator surface, to the unlisted catalogue,
and to buying out of it at whatever price it names. One secret, three
consequences.

### The unapplied-payments queue

`queue-handler` builds three lists:

| Key | From | Shape |
|---|---|---|
| `unapplied` | `store/unapplied-payments store 100` | `id`, `invoice`, `provider`, `amount`, `reference`, `resolution`, `confirmations`, `seen-at` |
| `open` | `store/open-invoices store` | `id`, `customer`, `item`, `provider`, `status`, `amount`, `currency`, `expires-at` |
| `rates` | the rates feed | `source`, `pair`, `price`, `as-of` |

The `IOrderStore` contract for the first is one line — *money that was seen but
could not be applied, newest first* — and the memory store implements it as
every payment whose `:payment/resolution` is `:late`, newest first, capped at
the limit. The cap is 100 and is not configurable.

`open` is every invoice whose status is in `#{:pending :underpaid}`. Those are
the statuses that can still take money and settle normally.

Nothing is ever deleted from the queue. A late payment stays in `unapplied`
forever; granting against it does not retire it, because the two are different
records. The queue is a ledger of things a person had to look at, not a task
list that empties.

### What reaches an operator, and what to do about it

Three outcomes need a person. They arrive by different routes and mean
different things.

| Outcome | Where it shows | Invoice status | What it is |
|---|---|---|---|
| `:settle/late` | `unapplied` | unchanged | money against an invoice that was already paid, or whose quote lapsed |
| `:settle/suspect` | `open`, unchanged | unchanged | the rail flagged the payment and it leaves a shortfall past tolerance |
| `:settle/underpaid` | `open`, as `underpaid` | set to `:underpaid` | the payment is short past tolerance and nothing is wrong with it |

**`:settle/late`.** Decided in `checkout/settle!` on `invoice/resolution`
alone, before the rail's own verdict is consulted — which is why a redelivered
notice cannot grant a lapsed quote. The money is real and belongs to a known
customer; it simply cannot be applied by machine. The payment is written to the
ledger with resolution `:late` and the invoice is left exactly as it was.

The alternative would be swallowing it, and a chain payment has no reversal
primitive to undo that with. So it goes to the queue and a person decides: hand
the thing over anyway, or refund out of band. To hand it over, grant.

**`:settle/suspect`.** Produced by `provider/settle` when the settlement is
flagged *and* the shortfall exceeds the rail's `underpay-tolerance`. A double
spend alongside a covering payment still grants; one that leaves the invoice
short is suspect. `settle!` reports it and changes nothing — the invoice stays
open at its current status.

There is nothing to do immediately, and that is deliberate. The invoice is
still open, so the next sweep polls the rail again; when the shortfall closes
or the flag clears, it settles normally with no operator action. Investigate
the chain if it persists. If the quote lapses first, the invoice expires and any
later money arrives as `:settle/late`.

**`:settle/underpaid`.** The invoice status becomes `:underpaid`, which is
still an open status — it keeps taking money and the sweep keeps polling it.
This is the outcome that usually needs nobody: the customer sends the rest and
it grants. It appears in the queue's `open` list so that an operator can see a
part-paid invoice ageing towards its expiry, not because it demands a decision.

### Granting

```
curl -s -X POST http://localhost:8080/api/admin/grants \
     -H "authorization: Bearer $ADMIN_TOKEN" \
     -H "content-type: application/json" \
     -d '{"customer":"alice","item":"pro","reference":"bank line 2026-08-14 #88421"}'
```

`customer` is the customer *ref*, not the internal id. `reference` is whatever
proves the payment to a human later — a transfer id, a bank line, a ticket. It
is not optional:

> a grant nobody can attribute later is a hole in the books, and this rail
> exists precisely because no API can attest to the payment.

`grant!` opens a manual invoice and settles it in the same breath, recording
the reference on the payment as `operator:<reference>`.

| Status | Body |
|---|---|
| 201 | `{"outcome": …, "customer": …, "item": …, "reference": …}` |
| 400 | `{"error":"unknown item"}` or `{"error":"a grant needs a reference"}` |
| 401 | `{"error":"unauthenticated"}` |
| 404 | `{"error":"no such customer"}` |
| 503 | `{"error":"the manual rail is not registered"}` |

The 201 body's `outcome` is the settlement outcome the grant produced, not a
promise that it granted.

### Reachability

`GET /api/admin/reachability`, behind `ADMIN_TOKEN` like the rest of this
section. The hosts and ports a store talks to are not public.

It opens a TCP connection to every service the deployment is configured to
reach, closes it, and reports what happened. `200` when all of them answered,
`503` when any did not.

| Field | Means |
|---|---|
| `ok` | every endpoint accepted a connection |
| `checked` | how many were probed — `0` means nothing is configured, not that all is well |
| `unreachable` | how many did not answer |
| `endpoints[].outcome` | `open`, `refused`, `timeout`, `unknown-host` or `error` |
| `endpoints[].elapsed-ms` | how long the connect took, which is how a slow path shows |
| `endpoints[].detail` | the exception message, or `null` |

**`refused` and `timeout` are different diagnoses.** A refusal is a service
that answered by saying no: it is running somewhere and the port is closed, or
nothing is bound. A timeout is a path that never answered at all, which is
what a dropped packet looks like — usually a firewall, a NetworkPolicy or a
security group rather than the service. Do not treat them as one alarm.

Which endpoints appear is decided by the configuration, not by a list in this
document: `MONEROPAY_URL` when `MONERO_BACKEND=moneropay`,
`MONERO_WALLET_RPC_URI` when it is `wallet-rpc`, `DATABASE_URL` when
`STORE_BACKEND=jdbc`, Stripe when `CARDS_BACKEND=stripe`, and `UMAMI_URL` when
`ANALYTICS=umami`. A rail you have not configured is not probed, so a store
selling only through the manual rail reports `checked: 0` and `ok: true`.

The timeout per endpoint is `REACHABILITY_TIMEOUT_MS`, default `2000`. It
bounds each probe, so the whole answer is bounded by the number of endpoints
times that value.

#### From a shell

```
bb reach
```

Reads the same environment the server does and prints one line per service,
exiting `1` when anything is unreachable. This is what replaces `telnet
wallet.internal 18083` — same question, but it names every service at once,
distinguishes a refusal from a block, and has an exit code a deploy can gate
on.

#### Do not point a container health check at it

`/healthz` is liveness: it answers while every dependency is down, and that is
deliberate. An orchestrator that restarts a pod because postgres is
unreachable turns one outage into two. Reachability is for a human, an alert,
or a deploy gate — not for a `livenessProbe`.

## Reconciliation

### Why a sweep exists when there are webhooks

A webhook is a message that may not arrive, and in two configurations this
store publishes it is guaranteed not to be honoured:

- Stripe with no `STRIPE_WEBHOOK_SECRET`. The rail registers and refuses every
  notice — an unauthenticated settlement notice is a 400, by design. Without
  the sweep, such a deployment would never settle a card at all.
- Any rail whose notice was lost, delayed past its own retry budget, or sent
  while the store was down.

The sweep asks the rail directly instead of waiting to be told:

```clojure
(defn sweep!
  "Reconcile every open invoice, then retire the ones whose window has closed.

  Returns the applied outcomes by count, plus how many invoices expired."
  [{:keys [store] :as deps}]
  (let [applied (frequencies (keep #(reconcile! deps %) (store/open-invoices store)))
        expired (expire-stale! deps)]
    (cond-> applied
      (seq expired) (assoc :invoice/expired (count expired)))))
```

`reconcile!` reads through `provider/polled-settlement`, and a rail whose
profile does not admit polling is never asked. One invoice's failure is its
own: it is logged and the sweep continues, because a wallet that is briefly
unreachable must not stop every other invoice from settling.

The result is logged at info when non-empty, so a healthy sweep is silent and
`reconciled invoices {:settle/grant 2}` is what a working store looks like.

### RECONCILE_INTERVAL_MS

It is the argument to `scheduleWithFixedDelay`, used as both the initial delay
and the delay between runs, in milliseconds. Fixed delay, not fixed rate: the
gap is measured from the end of one sweep to the start of the next, so a slow
sweep pushes the next one out rather than stacking runs.

The sweep runs on one daemon thread named `monero-store-reconcile`. Each run is
wrapped in a `try`/`catch Throwable` because `scheduleWithFixedDelay` cancels a
task forever on the first escaping throwable, silently — a store whose
reconciliation had quietly stopped would look identical to one with nothing to
reconcile.

Setting the interval very low costs a poll per open invoice per sweep against
every rail that admits polling. Setting it high delays every settlement that
depends on polling rather than a notice — which, for Stripe with no webhook
secret, is all of them.

### What expire-stale! retires

Every open invoice for which `invoice/stale?` holds — open *and* lapsed — has
its status set to `:expired`:

```clojure
(defn expire-stale!
  "Mark every open invoice whose quote has lapsed `:expired`.

  Runs after the read, never before it: money that arrived inside the window
  and is only now being noticed must still settle normally. Returns the ids
  that moved."
  [{:keys [store now-fn] :or {now-fn #(Date.)}}]
  (let [now (now-fn)]
    (into []
          (keep (fn [inv]
                  (when (invoice/stale? inv now)
                    (store/set-invoice-status! store (:invoice/id inv) :expired)
                    (:invoice/id inv))))
          (store/open-invoices store))))
```

The ordering inside `sweep!` is the point. Expiry runs *after* reconciliation,
never before, so money that arrived inside the window and is only now being
noticed still settles normally. Reverse the two and every payment observed in
the last interval before expiry would be retired out from under itself.

An expired invoice is no longer open, so it is no longer polled and no longer
appears in the queue's `open` list. Money arriving against it afterwards is
`:settle/late` and reaches the operator.

## Deployment shape

### The uberjar carries only the rails its aliases name

```
clojure -T:uberjar uber :aliases '[:stripe :jdbc]'
```

The core `:paths` are `src` and `resources` and carry no payment-provider SDK.
Each SDK-backed adapter lives on its own source root behind its own alias, and
`uber` copies in only the roots the `:aliases` argument names:

| Alias | Source root | Brings |
|---|---|---|
| `:stripe` | `adapters/stripe/src` | `com.stripe/stripe-java` |
| `:monero-rpc` | `adapters/monero-rpc/src` | `io.github.woodser/monero-java` |
| `:jdbc` | `adapters/jdbc/src` | `next.jdbc`, PostgreSQL, HikariCP |

The jar is `target/monero-store-$VERSION-standalone.jar` with main class
`monero-store.system`, and `VERSION` comes from the build environment.

An alias you leave out is an SDK that is not in the jar. The store still boots.
`order-store`, `chain-wallet` and `card-gateway` each resolve their adapter
through `optional-fn`, which returns nil when the namespace is not on the
classpath — so a missing adapter is a warning and a fallback, never a
`ClassNotFoundException` at boot. That is how an optional SDK stays optional
without a compile-time dependency.

The consequence to remember: **a deployment configured for a rail whose alias
was not built in runs without that rail and says so only in the log.** Read the
boot line. `start!` logs the port, the registered rail ids, the item ids and
the experiment keys, and warns separately when no rail is registered at all.

> One caveat on the commands. `deps.edn` defines `:uberjar` with
> `:ns-default build` — that is the alias above. The separate `:build` alias is
> the library artifact and defaults to `hive-build.api`. The `Dockerfile` and
> `build.clj`'s own docstring still say `-T:build uber`, which no longer names
> this build script. Use `-T:uberjar uber`.

### One origin

`routes/handler` is a Ring handler whose default chain is a resource handler,
then the SPA fallback, then the default handler:

```clojure
(defn handler
  [deps]
  (ring/ring-handler
   (router deps)
   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (spa-fallback deps)
    (ring/create-default-handler))))
```

`shadow-cljs.edn` compiles the storefront into `resources/public/js`, which is
on the classpath, so the compiled ClojureScript is served by the same Aleph
server that serves the API. There is no second web server, no proxy and no
cross-origin configuration to get wrong, in development or in production.

A navigation that matched no route gets the SPA shell, so a deep link survives
a reload. API paths are excluded from that fallback by name — they own their
own 404s and must never be shadowed by the shell. The shell is hiccup rather
than a file, which is what lets the experiment assignment be merged into the
`<html>` attribute map on the way out; there is no static document that could
be fetched by name and served without an assignment.

## Reachability

When settlement stops, the first question is whether the store can reach the
backend at all. `monero-store.collect.reachability` answers exactly that and
nothing more.

It is a seam, not a wired endpoint: nothing in the running store calls it. Call
it from a REPL against the store's own network view, or from a host application
that wants it on a health page.

```clojure
(require '[monero-store.collect.reachability :as reach])

(reach/probe (reach/socket-probe)
             {:endpoint/host "moneropay" :endpoint/port 5000 :endpoint/label "moneropay"}
             2000)
```

`probe` never throws — an unreachable service is a value, because the caller's
job is to report it. It returns `:reach/label`, `:reach/host`, `:reach/port`,
`:reach/outcome`, `:reach/elapsed-ms` and `:reach/detail` (the throwable's
message, or nil).

The outcome is a `Reachability` variant, and the distinction is the whole
point:

| Variant | From | Means |
|---|---|---|
| `:reach/open` | no throwable | the connection was accepted and closed |
| `:reach/refused` | `ConnectException` | something answered and refused — the host is up, the service is not |
| `:reach/timeout` | `SocketTimeoutException` | nothing answered inside the timeout — a blocked path, not a down service |
| `:reach/unknown-host` | `UnknownHostException` | the name does not resolve |
| `:reach/error` | anything else | read `:reach/detail` |

The two predicates do not read the same value. `reach/open?` takes the whole
report — it is `(= :reach/open (:adt/variant (:reach/outcome report)))`.
`reach/blocked?` takes the outcome, and is true for `:reach/timeout` alone.
Handing a report to `blocked?` returns `false` rather than complaining, so
unwrap it:

```clojure
(let [report (reach/probe (reach/socket-probe)
                          {:endpoint/host "moneropay" :endpoint/port 5000 :endpoint/label "moneropay"}
                          2000)]
  {:open? (reach/open? report)
   :blocked? (reach/blocked? (:reach/outcome report))})
```

That care is worth taking: a refusal is a service that is down, a timeout is a
path that is blocked, and those want different people.

The probe opens a TCP connection and closes it. It proves the socket, not the
service: a MoneroPay that accepts connections and returns errors to every
request is `:reach/open`. Read it as evidence that the store's problem is or is
not the network, and no further.

The ports worth probing, from the defaults and `docker-compose.yml`:

| Service | Host:port |
|---|---|
| MoneroPay | `moneropay:5000`, per `MONEROPAY_URL` |
| monero-wallet-rpc | `wallet-rpc:38083` |
| monerod | `monerod:38081` |
| PostgreSQL | `postgres:5432` |

Probe from inside the store's container, not from your laptop. A name that
resolves on the compose network resolves nowhere else, and
`:reach/unknown-host` from the wrong host tells you nothing about the store.
