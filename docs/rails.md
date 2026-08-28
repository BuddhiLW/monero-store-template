# Adding a payment rail

A rail is a registry entry. `provider/registry` takes a sequence of
`{:profile .. :rail ..}` maps and returns a map keyed by `:provider/id`:

```clojure
(defn registry
  "A rail registry from `entries`, each {:profile .. :rail ..}.

  The profile is validated here: a rail whose declared behaviour is malformed
  must fail at boot, where an operator is watching, and not at the first
  customer."
  [entries]
  (into {}
        (map (fn [{:keys [profile rail]}]
               (schema/check! schema/ProviderProfile profile
                              {:monero-store/producer `registry})
               [(:provider/id profile) {:profile profile :rail rail}]))
        entries))
```

Quoting, opening, settling, reconciling and the webhook all reach a rail through
that map, and never by name. The only provider id spelled out anywhere in the
money path is `:manual`, in `checkout/grant!` and in the guard on the grant
route — the operator path, which is about one rail on purpose.

## Which of the two cases you are in

| Situation | What you write |
|---|---|
| The processor hosts the checkout page, correlates by a session id it mints, and posts events about that session | A **reader**, expressed as data, plus an `ICardGateway` adapter |
| Anything else — a chain, a bank file, an operator's assertion, a processor whose model does not fit the one above | An `IPaymentRail` implementation and a `ProviderProfile` |

The first case is not a shortcut past the second. It is the second, already
written: `payments/hosted.clj` implements `IPaymentRail` once, and a reader
supplies the parts that differ between processors.

## Case 1 — a hosted-checkout processor

From the namespace docstring of `payments/hosted.clj`:

> What differs between processors — which event names mean settled, where the
> amount sits in the body, how a notice is authenticated — is a READER, supplied
> as data. This namespace holds the behaviour every hosted checkout shares;
> `payments.stripe` is one reader over it, and a second processor is a second
> reader, not a second rail.

### The reader keys

`hosted/default-reader` is the whole vocabulary. `hosted/->rail` merges your
reader over it, so a reader supplies only what differs.

```clojure
(def default-reader
  "The shape of a processor notice, as data.

  `:reader/authentic?` is the whole authentication boundary: a reader that
  cannot check a signature returns false and the rail settles by polling
  alone, which is always safe and merely slower."
  {:reader/settled-events #{}
   :reader/failed-events #{}
   :reader/event-type-path [:type]
   :reader/object-path [:data :object]
   :reader/subject-key :client_reference_id
   :reader/object-id-key :id
   :reader/amount-keys [:amount_total :amount_paid]
   :reader/currency-key :currency
   :reader/paid-statuses #{"paid" "no_payment_required"}
   :reader/open-status "open"
   :reader/expired-status "expired"
   :reader/authentic? (fn [_notice] false)})
```

| Key | Read by | Says |
|---|---|---|
| `:reader/settled-events` | `hosted/parse-event` | Event names that mean the money arrived. Anything not in here and not in `:reader/failed-events` is `:pending`. |
| `:reader/failed-events` | `hosted/parse-event` | Event names that mean this checkout is dead. Becomes `:settlement/status :failed`, which `settle` turns into `:settle/reject`. |
| `:reader/event-type-path` | `hosted/event-type` | Where in the body the event name sits, as a `get-in` path. |
| `:reader/object-path` | `hosted/event-object` | Where in the body the object sits. |
| `:reader/subject-key` | `hosted/subject-of`, `hosted/event-matches-invoice?` | The key on the object that replays the invoice id the checkout was opened with. |
| `:reader/object-id-key` | `hosted/event-matches-invoice?`, `hosted/settlement-ref` | The key carrying the processor's own session id — the second way an event may name an invoice, and the settlement's reference. |
| `:reader/amount-keys` | `hosted/event-amount` | Keys to try, in order, for the paid amount. Absent from all of them means 0. |
| `:reader/currency-key` | `verify-notice` in `hosted/->rail` | The key on the object carrying the currency code. |
| `:reader/paid-statuses` | `hosted/checkout-state` | The `:checkout/payment-status` values that mean paid. |
| `:reader/open-status` | `resume` in `hosted/->rail` | The `:checkout/status` that means this session is still payable. |
| `:reader/expired-status` | `hosted/checkout-state` | The `:checkout/status` that means the session is dead. |
| `:reader/authentic?` | `verify-notice` in `hosted/->rail` | The authentication boundary. Takes a `WebhookNotice`, returns a boolean. |

### Stripe's reader

```clojure
(defn reader
  "The Stripe notice reader. `:webhook-secret` is the endpoint signing secret;
  without it every notice is refused and the rail settles by polling alone."
  [{:keys [webhook-secret webhook-tolerance-seconds]}]
  {:reader/settled-events settled-events
   :reader/failed-events failed-events
   :reader/subject-key :client_reference_id
   :reader/object-id-key :id
   :reader/amount-keys [:amount_total :amount_paid]
   :reader/currency-key :currency
   :reader/authentic? (fn [notice]
                        (signature-valid? {:secret webhook-secret
                                           :tolerance-seconds webhook-tolerance-seconds}
                                          (get-in notice [:notice/headers "stripe-signature"])
                                          (:notice/raw-body notice)
                                          (:notice/received-at notice)))})
```

Seven keys of twelve. Four of them — `:reader/subject-key`,
`:reader/object-id-key`, `:reader/amount-keys`, `:reader/currency-key` — restate
the defaults verbatim, because the defaults were taken from Stripe's own shape.
A processor that names things differently changes those four and inherits the
rest. The three that carry real work are the two event sets:

```clojure
(def settled-events
  #{"checkout.session.completed" "invoice.payment_succeeded"})

(def failed-events
  #{"checkout.session.expired" "invoice.payment_failed"})
```

and `:reader/authentic?`.

`default-reader` supplies `(fn [_notice] false)` for that last one. A reader
that cannot check a signature refuses every notice and the rail settles by
polling instead — slower, and never wrong. The notice a reader is handed is a
`WebhookNotice`:

```clojure
(def WebhookNotice
  "A settlement notice as received at the HTTP boundary."
  [:map {:closed true}
   [:notice/raw-body :string]
   [:notice/payload [:maybe :map]]
   [:notice/headers [:map-of :string :string]]
   [:notice/path-params {:optional true} [:maybe :map]]
   [:notice/received-at [:int {:min 0}]]])
```

`:notice/raw-body` is the untouched bytes. Signing is over those, not over a
reserialization of `:notice/payload`. Stripe's check uses `javax.crypto`
rather than the SDK, so a notice can be authenticated by a build carrying no
vendor jar at all; the SDK is confined to the gateway adapter.

### The gateway

The reader reads notices. Opening and re-reading a checkout is the other half,
and it is a port:

```clojure
(defprotocol ICardGateway
  (open-checkout! [this checkout]
    "Open a hosted checkout. Returns a CheckoutSession.

    `checkout` is {:checkout/reference :checkout/email :checkout/product
    :checkout/currency :checkout/amount :checkout/success-url
    :checkout/cancel-url}. `:checkout/reference` is the invoice id and is
    replayed on every notice about this checkout; opening the same reference
    twice must yield the same session rather than a second way to pay once.")
  (read-checkout [this session-id]
    "The hosted checkout as the processor reports it now.

    Returns a CheckoutSession, or nil when the processor cannot report on
    `session-id`."))
```

`read-checkout` is what makes the rail pollable, and `hosted/checkout-of`
discards an answer that is not about the session it asked for — an answer about
a different session is not evidence about this one. The session it returns:

```clojure
(def CheckoutSession
  "One hosted checkout as its processor reports it. The unit of evidence a
  processor rail settles from.

  `:checkout/status` is the session's own lifecycle (open, complete, expired)
  and `:checkout/payment-status` whether the money arrived; a session can be
  complete without being paid."
  [:map {:closed true}
   [:checkout/id NonBlank]
   [:checkout/url [:maybe NonBlank]]
   [:checkout/status [:maybe NonBlank]]
   [:checkout/payment-status [:maybe NonBlank]]
   [:checkout/currency [:maybe NonBlank]]
   [:checkout/amount-total [:int {:min 0}]]])
```

The SDK belongs on its own source root, behind its own alias in `deps.edn`, the
way `:stripe` is:

```clojure
:stripe {:extra-paths ["adapters/stripe/src"]
         :extra-deps {com.stripe/stripe-java {:mvn/version "33.2.0"}}}
```

`system/card-gateway` resolves the adapter lazily and says so when it cannot:

```clojure
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
```

### The entry

`hosted/entry` builds the registry entry; a processor namespace supplies its
profile and its reader.

```clojure
(defn entry
  "A registry entry {:profile :rail} for a hosted-checkout rail."
  [config]
  (let [overrides (select-keys config (keys default-profile))
        provider-id (or (:provider-id config) (:provider/id default-profile))]
    {:profile (merge default-profile overrides {:provider/id provider-id})
     :rail (->rail config)}))
```

```clojure
(defn entry
  "A registry entry {:profile :rail} for Stripe over `:gateway`.

  Every profile key is overridable by `config`: a deployment selling in euros
  passes `:provider/currency :eur` and nothing else moves."
  [config]
  (hosted/entry (merge profile
                       {:provider-id :stripe}
                       config
                       {:reader (reader config)})))
```

That is the whole processor namespace: a profile map, two event sets, a
signature check, a reader, and an `entry` that hands them to `hosted`.

## Case 2 — everything else

Implement the protocol directly.

```clojure
(defprotocol IPaymentRail
  (charge! [this request]
    "Open a payment for a ChargeRequest. Returns a ChargeHandle.")
  (interpret [this payload invoice]
    "Normalize a rail's own report into a Settlement for `invoice`.

    Trusts `payload` outright. Not an authentication boundary.")
  (notice-subject [this notice]
    "The id of the invoice `notice` claims to be about, or nil.

    A CLAIM, not proof: a processor that posts every event of an account to one
    endpoint cannot put the invoice in the path, so the notice has to name it.
    The invoice it names then selects the rail that must authenticate the
    notice. Nil for a rail whose notices name no invoice.")
  (verify-notice [this notice invoice]
    "Authenticate a settlement notice against `invoice`.

    Returns a Settlement, or nil when the notice is not authentic for this
    invoice.")
  (poll [this invoice]
    "Settlement for `invoice` as this rail's own system reports it now.

    Nothing prompts this read, so nothing about it can be forged. Nil when the
    rail cannot be asked, or has nothing to report.")
  (resume [this invoice]
    "The ChargeHandle an already-open `invoice` still implies.

    Nil when this rail cannot resume one — a hosted checkout that is no longer
    open cannot be handed back, and minting a new one would leave two live ways
    to pay a single invoice."))
```

### Method by method

| Method | Must return | Nil means | Reached from |
|---|---|---|---|
| `charge!` | A `ChargeHandle` | Nothing. Every caller assumes a handle; `open!` stores its `:handle/external-ref` on the invoice | `checkout/open!`, via `provider/charge!` |
| `interpret` | A `Settlement` | Nothing | `checkout/grant!` only, with `{}` as the payload |
| `notice-subject` | The invoice id the notice claims, or nil | This rail's notices name no invoice; the URL must carry it or the notice cannot be attributed | `notice/subject-invoice`, via `provider/claimed-invoice-id`, and only when the URL had no `:invoice` segment |
| `verify-notice` | A `Settlement`, or nil | The notice is not authentic for this invoice — `apply-notice!` answers `:notice/unauthenticated` | `provider/settlement-for`, and only after `webhook-settleable?` |
| `poll` | A `Settlement`, or nil | Nothing to report, or the rail cannot be asked — the sweep applies nothing for this invoice | `provider/polled-settlement`, and only when `pollable?` |
| `resume` | A `ChargeHandle`, or nil | This invoice cannot be handed back — `open!` quotes and opens a fresh one | `provider/resumed-handle`, from `checkout/open!` |

Three details are worth stating outright.

**`charge!` never prices anything.** `open!` quotes the item in the rail's own
currency and passes the amount down. The `ChargeRequest` is closed:

```clojure
(def ChargeRequest
  "What a rail needs to open a payment. `:charge/amount` is the amount locked
  for this invoice, in the rail's own currency."
  [:map {:closed true}
   [:charge/invoice-id :uuid]
   [:charge/item Item]
   [:charge/customer Customer]
   [:charge/amount Money]
   [:charge/callback-url NonBlank]])
```

`:charge/callback-url` is built by `open!` as
`<callback-base>/webhooks/<provider-id>/<invoice-id>`. A rail may hand it to
the processor unchanged, extend it — the chain rail appends an HMAC token — or
ignore it entirely.

**`notice-subject` is not authentication.** It reads a claim out of a body, and
is called on the rail the *URL* names. The invoice that claim yields then
selects the rail that must call `verify-notice`, which may be a different one.
`provider/settlement-for` is where that is enforced:

```clojure
(defn settlement-for
  "Settlement authenticated for `invoice` out of `notice`, or nil.

  Nil unless the invoice's own provider is registered, its profile admits
  webhook settlement, and its rail authenticates the notice."
  [rails invoice notice]
  (let [provider-id (:invoice/provider invoice)]
    (when (webhook-settleable? rails provider-id)
      (when-let [selected (rail rails provider-id)]
        (verify-notice selected notice invoice)))))
```

**A rail may treat a notice as nothing more than a prompt.** The chain rail's
`verify-notice` checks the callback token and then re-reads the wallet, so what
the notice *said* never enters the settlement. That is legitimate and cheap to
implement: authenticate, then poll, then return the polled result.

### The smallest rail that works

`payments/manual.clj` — the operator path, where no API can tell the store that
a bank transfer landed:

```clojure
(defn ->rail []
  (reify provider/IPaymentRail
    (charge! [_ {:charge/keys [invoice-id amount]}]
      {:handle/provider :manual
       :handle/external-ref (str "manual:" invoice-id)
       :handle/pay-to nil
       :handle/redirect-url nil
       :handle/amount amount})

    (interpret [_ _payload invoice]
      (let [reference (reference-of invoice)]
        {:settlement/provider :manual
         :settlement/external-ref reference
         :settlement/status :settled
         :settlement/paid-amount (:invoice/amount invoice)
         :settlement/expected-amount (:invoice/amount invoice)
         :settlement/confirmations 0
         :settlement/references [reference]}))

    (notice-subject [_ _notice] nil)
    (verify-notice [_ _notice _invoice] nil)
    (poll [_ _invoice] nil)

    (resume [_ invoice]
      {:handle/provider :manual
       :handle/external-ref (reference-of invoice)
       :handle/pay-to nil
       :handle/redirect-url nil
       :handle/amount (schema/money (:invoice/currency invoice) (:invoice/amount invoice))})))
```

Three of the six return nil, and the profile accounts for two of them.
`:provider/settlement-poll? false` makes `pollable?` false, so
`polled-settlement` never reaches `poll`; `:provider/webhook-auth :none` makes
`webhook-settleable?` false, so `settlement-for` never reaches `verify-notice`.

`notice-subject` is the exception, and no profile key gates it. `apply-notice!`
calls `subject-invoice` first, before it checks `webhook-settleable?`, and
`subject-invoice` asks `provider/claimed-invoice-id` — which calls
`notice-subject` on the rail the *URL* names — whenever the URL carried no
`:invoice` segment. An unauthenticated POST to `/webhooks/manual` therefore does
reach this method. Returning nil is the answer, not a stub: the notice names no
invoice, `subject-invoice` finds none, and `apply-notice!` ends
`:notice/unknown-invoice` having authenticated nothing. Every rail must
implement `notice-subject` for a body it cannot read, including a rail that
settles over no wire at all.

A rail's profile and its implementation have to agree; the profile is the half
the store reads.

### The Settlement a rail produces

```clojure
(def Settlement
  "Normalized result of interpreting what a rail has seen.

  `:settlement/references` names the individual movements behind the amount.
  `:settlement/suspect?` says a movement was seen and rejected; it contributes
  no funds, and a rail that cannot observe such a thing omits the key."
  [:map {:closed true}
   [:settlement/provider ProviderId]
   [:settlement/external-ref NonBlank]
   [:settlement/status SettlementStatus]
   [:settlement/paid-amount [:int {:min 0}]]
   [:settlement/expected-amount [:int {:min 0}]]
   [:settlement/confirmations [:int {:min 0}]]
   [:settlement/suspect? {:optional true} :boolean]
   [:settlement/references {:optional true} [:sequential NonBlank]]])
```

`:settlement/provider` must be the rail's own id. `checkout/settle!` returns nil
without touching anything when it is not the invoice's provider. A rail with no
notion of confirmations reports `0` and sets `:provider/min-confirmations 0`.

## The profile

```clojure
(def ProviderProfile
  "Measured, swappable behaviour of a payment rail. The DIP swap point.

  `:provider/settlement-poll?` says whether the rail can be asked, unprompted,
  what it has seen — a rail with no way to call the store must be polled or it
  never settles at all. `:provider/webhook-auth` says how, if at all, a notice
  it posts can be authenticated."
  [:map {:closed true}
   [:provider/id ProviderId]
   [:provider/currency CurrencyId]
   [:provider/min-confirmations [:int {:min 0}]]
   [:provider/underpay-tolerance [:int {:min 0}]]
   [:provider/settles-async? :boolean]
   [:provider/settlement-poll? :boolean]
   [:provider/webhook-auth [:enum :none :signed-payload :path-token :server-confirmed]]])
```

Seven keys, all required, no extras — `registry` validates each one at boot, so
a malformed profile fails where an operator is watching rather than at the first
customer.

| Key | Read by | Effect |
|---|---|---|
| `:provider/id` | `registry` (the map key), `provider/ids`, `wire/provider` | Names the rail. It is the value stored on `:invoice/provider`, the `:provider` segment of the webhook URL `open!` builds, and the id the storefront posts back on checkout. |
| `:provider/currency` | `provider/currency-of`, `provider/currencies`, `wire/provider` | The currency `open!` quotes the item in. It must be registered in `monero-store.currency`: `CurrencyId` is `[:and :keyword [:fn {:error/message "must be a registered currency"} currency/known?]]`, so an unregistered currency fails the profile check at boot. |
| `:provider/min-confirmations` | `provider/settle`, `wire/provider` | Below it, money that arrived is still `:settle/pending`. `wire/provider` also puts it in the catalog. |
| `:provider/underpay-tolerance` | `provider/settle` | A shortfall at or below it grants. Above it is `:settle/underpaid`, or `:settle/suspect` when a movement was seen and rejected. |
| `:provider/settles-async?` | `wire/provider` | Projected to the storefront as `:async`. `settle` never reads it. |
| `:provider/settlement-poll?` | `provider/pollable?` | False and `polled-settlement` returns nil without calling `poll`, so `reconcile!` never asks this rail anything. |
| `:provider/webhook-auth` | `provider/webhook-settleable?` | `:none` and every notice for this rail ends `:notice/unknown-invoice` with `verify-notice` never called. Any other value and the rail's own `verify-notice` decides. |

`webhook-settleable?` is the only reader of `:provider/webhook-auth`, and it
compares against one value:

```clojure
(defn webhook-settleable?
  "True when `provider-id`'s profile admits settlement notices over HTTP."
  [rails provider-id]
  (not= :none (get (profile rails provider-id) :provider/webhook-auth :none)))
```

So `:signed-payload`, `:path-token` and `:server-confirmed` are not
distinguished anywhere in the code. The enum records, for an operator reading
the profile, which mechanism the rail actually implements; only `:none` changes
what the store does.

### What settle does with it

```clojure
(defn settle
  "Decide what a Settlement means under its provider's profile.

  Pure, and open for extension: every threshold comes from the profile, so a
  new rail needs no change here. The order of the clauses is the policy."
  [rails {:settlement/keys [status paid-amount expected-amount confirmations suspect?] :as settlement}]
  (let [{:provider/keys [min-confirmations underpay-tolerance]
         :or {min-confirmations 0 underpay-tolerance 0}}
        (profile rails (:settlement/provider settlement))
        shortfall (- (long expected-amount) (long paid-amount))]
    (adt/settlement-outcome
     (cond
       (= :failed status) :settle/reject
       (and suspect? (> shortfall (long underpay-tolerance))) :settle/suspect
       (zero? (long paid-amount)) :settle/pending
       (> shortfall (long underpay-tolerance)) :settle/underpaid
       (< (long confirmations) (long min-confirmations)) :settle/pending
       (= :pending status) :settle/pending
       :else :settle/grant))))
```

| Order | Condition | Outcome |
|---|---|---|
| 1 | `:settlement/status` is `:failed` | `:settle/reject` |
| 2 | `:settlement/suspect?` and shortfall exceeds the tolerance | `:settle/suspect` |
| 3 | Nothing paid | `:settle/pending` |
| 4 | Shortfall exceeds the tolerance | `:settle/underpaid` |
| 5 | Confirmations below the minimum | `:settle/pending` |
| 6 | `:settlement/status` is `:pending` | `:settle/pending` |
| 7 | Otherwise | `:settle/grant` |

Clause 2 above clause 4 is why a double spend that leaves the invoice short is
`:settle/suspect` while one alongside a covering payment still grants. The
`:or` defaults apply only when the profile is missing altogether — the schema is
closed and both keys are required, so a registered rail always supplies them.

`:settle/late` is the one variant of `SettlementOutcome` that `settle` never
produces. `checkout/settle!` decides it on the invoice's resolution alone,
before consulting the rail's profile, which is why a redelivered notice cannot
grant a lapsed quote.

### Thresholds are data, and the tests say so

```clojure
(deftest the-profile-decides-not-the-provider-name
  (testing "the same settlement means different things under different profiles"
    (is (= :settle/pending (outcome :strict {:settlement/confirmations 3})))
    (is (= :settle/grant (outcome :lenient {:settlement/confirmations 3}))))

  (testing "underpayment tolerance is a number in the profile, not a branch"
    (is (= :settle/underpaid (outcome :strict {:settlement/paid-amount 997})))
    (is (= :settle/grant (outcome :lenient {:settlement/paid-amount 997})))
    (is (= :settle/underpaid (outcome :lenient {:settlement/paid-amount 990})))))
```

The two rails in that test differ by two integers. Nothing else about them is
different, and `settle` contains no mention of either.

## Registering it

```clojure
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
```

This is the one place a new rail is named, and it is deliberately not part of
the money path. `provider/registry` is called from four places in this
repository — `system/rails` for a deployment, `support/deps` for the tests,
`user/demo-deps` for the REPL, and `provider-test/rails-with` for the tests that
exercise the seam itself. A project embedding the library calls it a fifth time
with its own list.

Profile keys are overridable at this point, which is how a deployment retunes a
rail without touching its namespace: the chain entry above takes
`:provider/min-confirmations` straight from `MONERO_MIN_CONFIRMATIONS`, and
`stripe/entry` takes `:provider/currency` from `CARDS_CURRENCY`.

## The URL a notice arrives on

Three route shapes, all served by one handler:

```clojure
["/webhooks/:provider" {:post (webhook-handler deps)}]
["/webhooks/:provider/:invoice" {:post (webhook-handler deps)}]
["/webhooks/:provider/:invoice/:token" {:post (webhook-handler deps)}]
```

| Shape | For a rail that | Attribution |
|---|---|---|
| `/webhooks/:provider` | Posts every event of an account to one endpoint | `notice-subject` reads the claim out of the body |
| `/webhooks/:provider/:invoice` | Can be told which URL to call per payment | The path segment names the invoice |
| `/webhooks/:provider/:invoice/:token` | Can carry a secret in the URL | The path segment names the invoice; the token is the rail's to check |

A new rail needs no new route. Which shape it uses changes nothing about who
authenticates the notice — as `webhook-handler` puts it, the invoice's own rail
always does.

## What does not change

That is the point of the seam.

| Stays as it is | Because |
|---|---|
| `provider/settle` | Every threshold it reads comes from the profile. A new rail is a new row in a map, not a new branch in a `cond`. |
| `pipeline/checkout.clj`, `pipeline/notice.clj`, `pipeline/reconcile.clj` | They reach every rail through `provider/*` and dispatch on `SettlementOutcome`, which is a closed sum. The single exception is `grant!`, which names `:manual` because the operator path is about that rail. |
| `boundary/routes.clj` | The webhook routes take the provider as a path parameter; `catalog-handler` projects `provider/ids` and each profile through `wire/provider`. |
| `schema.clj` | `ProviderId` is `:keyword` — "deliberately open: a new rail is a registry entry, not an edit to this enum". `ProviderProfile` is closed and carries no per-provider key. |
| The storefront | `views/item-card` renders one button per entry in `(:providers catalog)`, labelled from `:id` and `:currency`. A registered rail appears in the UI with no ClojureScript change. |

What you write is a namespace under `src/monero_store/payments/`, an `entry`
function in it, and one line in whichever registry the deployment builds.
