(ns monero-store.typed-test
  "The Typed Clojure checker accepts the annotations.

  `deftest-typed-check` fails a namespace that was SKIPPED for want of the
  `^:typed.clojure` tag, which a bare `check-ns-info` reports as clean."
  (:require [hive-schemas.typed-check :as tc]))

(tc/deftest-typed-check the-money-core-annotations-type-check
  'monero-store.typed-anns)
