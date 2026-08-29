(ns monero-store.proven-test
  "Kernel rungs over the money core: the compiled function agrees with the
  kernel evaluator (differential), and the invariants hold for ALL inputs
  (proof).

  `owed` is the arithmetic `promote.invoice/remaining` delegates to, restated
  here as an `a/defn` so the kernel can reason about it. The tie between the
  two is `the-store-agrees-with-the-proven-definition` below."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.clojure-test :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [ansatz.core :as a]
            [ansatz.kernel.env :as env]
            [ansatz.kernel.name :as kname]
            [hive-schemas.proven :as p]
            [monero-store.promote.invoice :as invoice]))

;; def'd by `a/defn` in the :once fixture, not at load time
(declare owed)

;; Unrefined on purpose: a :min/:max refinement elaborates to a subtype and the
;; termination measure then fails to check.
(m/=> owed [:=> [:cat :int :int] :int])

(defonce ^:private booted (delay (binding [a/*verbose* false] (a/load-init!))))

(defn- ensure-subject!
  "Boot the kernel stdlib and elaborate `owed` into the live env. Idempotent;
  `load-init!` re-inits the env per fixture, so this ns owns its subject
  regardless of test order."
  []
  @booted
  (binding [a/*verbose* false]
    (when-not (env/lookup (a/env) (kname/from-string "owed"))
      (binding [*ns* (find-ns 'monero-store.proven-test)]
        (eval '(ansatz.core/defn owed [amount paid]
                 :termination-by amount
                 (match amount Nat Nat
                        (zero 0)
                        (succ [j] (match paid Nat Nat
                                         (zero (+ 1 j))
                                         (succ [k] (owed j k))))))))))
  nil)

(use-fixtures :once (fn [t] (ensure-subject!) (t)))

;; --- proof: the three invariants, ∀ inputs ----------------------------------

(p/deftrifecta-proven nothing-asked-is-nothing-owed
  :name    'ms-owed-nothing-asked
  :params  '[p :- Nat]
  :prop    '(= Nat ((owed (Nat.zero)) p) 0)
  :tactics '[(simp [owed])])

(p/deftrifecta-proven nothing-paid-leaves-the-whole-amount-owed
  :name    'ms-owed-nothing-paid
  :params  '[a :- Nat]
  :prop    '(= Nat ((owed a) (Nat.zero)) a)
  :tactics '[(induction a) (all_goals (simp_all [owed])) (all_goals (try (omega)))])

(p/deftrifecta-proven paying-in-full-owes-nothing
  :name    'ms-owed-paid-in-full
  :params  '[a :- Nat]
  :prop    '(= Nat ((owed a) a) 0)
  :tactics '[(induction a) (all_goals (simp_all [owed])) (all_goals (try (omega)))])

;; --- teeth: kernel soundness rejects a false variant ------------------------

(deftest a-false-invariant-has-no-proof
  (testing "the tactic skeleton that proves nothing-paid, applied to the false
            variant (owed a 0 = succ a), finds no proof term"
    (let [msg (p/proof-failure 'ms-owed-false '[a :- Nat]
                               '(= Nat ((owed a) (Nat.zero)) (Nat.succ a))
                               '[(induction a) (all_goals (simp_all [owed]))
                                 (all_goals (try (omega)))])]
      (is (some? msg))
      (is (re-find #"(?i)incomplete" (str msg))))))

;; --- the tie: the store's function IS the proven one ------------------------
;; Bounded: the kernel definition recurses unarily on `amount`.

(tc/defspec the-store-agrees-with-the-proven-definition 200
  (prop/for-all [a (gen/large-integer* {:min 0 :max 200})
                 p (gen/large-integer* {:min 0 :max 200})]
    (= (invoice/owed a p) (owed a p))))
