(ns monero-store.promote.experiments
  "Which arm of an experiment a visitor sees. Pure.

  Assignment is a HASH of the visitor and the experiment, not a coin flip and
  not a row in a table. Three consequences, all of them the reason for the
  choice: the same visitor always sees the same arm without anything being
  stored, two processes agree without talking to each other, and a test can
  assert an assignment instead of stubbing a random.

  The experiments themselves come from `tokens.edn` — the same declaration the
  stylesheet is generated from — so an arm that exists at runtime is an arm
  that shipped its CSS and passed the contrast gate."
  (:require [clojure.string :as str]
            [malli.core :as m])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(defn- digest
  "A stable non-negative integer for `value`.

  SHA-1 rather than `hash` because `clojure.core/hash` is not promised to be
  stable across releases, and an assignment that silently reshuffles on a JVM
  upgrade would corrupt every experiment running at the time."
  [value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-1")
                       (.getBytes (str value) StandardCharsets/UTF_8))]
    (reduce (fn [acc b] (+ (* 31 acc) (bit-and (long b) 0xff)))
            0
            (take 8 bytes))))

(defn variant-for
  "The arm of `experiment` that `visitor` sees.

  Falls back to the declared default when there is no visitor to hash — an
  anonymous first request gets the control arm rather than a random one, so a
  crawler cannot skew a test and an uncookied visitor sees the safe design."
  [experiment-name {:keys [variants default]} visitor]
  (let [arms (vec (sort (keys variants)))]
    (cond
      (empty? arms) nil
      (str/blank? (str visitor)) (or (some-> default keyword) (first arms))
      :else (nth arms (mod (digest (str visitor "/" (name experiment-name))) (count arms))))))

(defn assign
  "Every experiment's arm for `visitor`, as {experiment variant}."
  [experiments visitor]
  (into {}
        (keep (fn [[experiment spec]]
                (when-let [variant (variant-for experiment spec visitor)]
                  [experiment variant])))
        experiments))

(defn attributes
  "`assignments` as the HTML attributes that select them, {attribute value}.

  This is the whole runtime cost of an experiment: one attribute on <html>.
  Every arm's CSS is already in the stylesheet, so nothing loads, nothing
  flashes, and nothing has to be re-rendered when the assignment changes."
  [experiments assignments]
  (into (sorted-map)
        (keep (fn [[experiment variant]]
                (when-let [attribute (get-in experiments [experiment :attribute])]
                  [attribute (name variant)])))
        assignments))

(defn attribute-string
  "`attributes` as text for an HTML open tag."
  [attributes]
  (str/join " " (map (fn [[k v]] (str k "=\"" v "\"")) attributes)))

(defn describe
  "What the browser is told about the running experiments: the arm it is in,
  and the arms that exist. Never the assignment rule, and never another
  visitor's assignment."
  [experiments assignments]
  (into {}
        (map (fn [[experiment spec]]
               [(name experiment)
                {:attribute (:attribute spec)
                 :variant (some-> (get assignments experiment) name)
                 :variants (mapv name (sort (keys (:variants spec))))}]))
        experiments))

;; ---------------------------------------------------------------------------
;; contracts

(m/=> digest [:=> [:cat :any] :int])

(m/=> variant-for [:=> [:cat :keyword
                        [:map [:variants {:optional true} [:maybe [:map-of :keyword :any]]]
                              [:default {:optional true} [:maybe [:or :string :keyword]]]]
                        [:maybe :string]]
                   [:maybe :keyword]])

(m/=> assign [:=> [:cat [:maybe [:map-of :keyword :map]] [:maybe :string]] [:map-of :keyword :keyword]])

(m/=> attributes [:=> [:cat [:maybe [:map-of :keyword :map]] [:maybe [:map-of :keyword :keyword]]] [:map-of :string :string]])

(m/=> attribute-string [:=> [:cat [:map-of :string :string]] :string])

(m/=> describe [:=> [:cat [:maybe [:map-of :keyword :map]] [:maybe [:map-of :keyword :keyword]]]
                [:map-of :string [:map [:attribute :any]
                                       [:variant [:maybe :string]]
                                       [:variants [:vector :string]]]]])
