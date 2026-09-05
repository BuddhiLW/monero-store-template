# Changelog

All notable changes to `io.github.buddhilw/monero-store` are recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

A note on how to read the versions: `VERSION` names the version to PUBLISH,
declared in the commit that changes the library, and CI never invents a number.
So a consumer may pin the coordinate before the release runs, and the top entry
here is the version `VERSION` currently reads.

## [0.7.1]

### Added

- `:settlement/observed-amount` on `Settlement`, and `payments.chain/settlement-of`
  now reports it. It is money that has been SEEN, before the maturity cap that
  `:settlement/paid-amount` applies. A store settles on `paid-amount` and shows
  `observed-amount` to a payer who is waiting, so a payment that has landed but
  not yet unlocked can be acknowledged instead of reading as nothing sent. The
  key is optional: a rail that cannot tell the two apart omits it rather than
  repeating `paid-amount`, because a claim of no maturity gap is a claim.

- `monero-store.promote.progress`, lifted out of hive-store so both it and
  vtranslate read progress from one definition. `progress` answers from recorded
  `Payment` rows (what hive-store keeps) and `settlement-progress` from a single
  `Settlement` (what a store keeping no payment rows has). Both return the new
  `PaymentProgress` schema and take every threshold from the rail's profile.

- Three optional keys on `ProviderProfile`, each of which hive-store had already
  been forced to widen its own copy of the schema to carry:
  `:provider/confirmation-interval-ms` (how long ONE confirmation takes here;
  absent means nothing is estimated), `:provider/settlement-window-ms` (how long
  after an invoice's own expiry the store keeps asking) and
  `:provider/self-serve?` (a buyer may open a charge on this rail unaided).

### Notes for consumers

All three additions are backwards compatible. `Settlement` and `ProviderProfile`
are `{:closed true}` maps, so the new keys had to be declared here before any
consumer could carry them; a consumer that does not set them is unaffected.

hive-store can now drop its widened `ProviderProfile` and its local
`promote/progress`, and read both from this library instead.

## [0.7.0]

- The wallet adapter ships and survives a live daemon (`adapters.monero-rpc`).
- The book a deployment asks for actually reaches the pipeline (`collect.ledger`).
