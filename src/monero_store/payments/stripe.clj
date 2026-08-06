(ns monero-store.payments.stripe
  "Stripe as a READER over the hosted-checkout rail.

  Everything Stripe-specific is here and it is all data plus one signature
  check: which event names mean settled, where the invoice id is replayed, and
  how a notice authenticates itself. The rail behaviour lives in
  `payments.hosted`; adding a second processor means writing another reader
  this size, not another rail.

  The signature is verified with `javax.crypto` rather than the Stripe SDK, so
  a notice can be authenticated by a build that carries no vendor jar at all —
  the SDK is confined to the gateway adapter that opens sessions."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [monero-store.payments.hosted :as hosted]
            [monero-store.schema :as schema])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def profile
  "Stripe settles in one movement, needs no confirmations, and signs its
  notices."
  {:provider/id :stripe
   :provider/currency :usd
   :provider/min-confirmations 0
   :provider/underpay-tolerance 0
   :provider/settles-async? true
   :provider/settlement-poll? true
   :provider/webhook-auth :signed-payload})

(def settled-events
  #{"checkout.session.completed" "invoice.payment_succeeded"})

(def failed-events
  #{"checkout.session.expired" "invoice.payment_failed"})

(def default-tolerance-seconds
  "How old a signed notice may be. Stripe's own recommendation."
  300)

;; ---------------------------------------------------------------------------
;; signature

(defn parse-signature-header
  "The `t` timestamp and every `v1` signature in a Stripe-Signature header, as
  {:signature/timestamp long :signature/values #{hex}}.

  Nil when the header is absent, blank, carries no parsable `t`, or carries no
  `v1`."
  [header]
  (when-not (str/blank? header)
    (let [pairs (keep (fn [part]
                        (let [[k v] (str/split (str/trim part) #"=" 2)]
                          (when (and k v) [k v])))
                      (str/split header #","))
          timestamp (some (fn [[k v]] (when (= "t" k) v)) pairs)
          values (into #{} (comp (filter #(= "v1" (first %))) (map second)) pairs)]
      (when (and timestamp (seq values))
        (try {:signature/timestamp (Long/parseLong timestamp)
              :signature/values values}
             (catch NumberFormatException _ nil))))))

(defn signed-payload
  "The exact string Stripe signs: the header timestamp, a dot, the raw body."
  [timestamp raw-body]
  (str timestamp "." raw-body))

(defn- hmac-hex
  [secret message]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes ^String (str secret) StandardCharsets/UTF_8)
                               "HmacSHA256"))
    (->> (.doFinal mac (.getBytes ^String (str message) StandardCharsets/UTF_8))
         (map #(format "%02x" (bit-and (long %) 0xff)))
         (apply str))))

(defn- constant-time=
  [^String a ^String b]
  (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                         (.getBytes b StandardCharsets/UTF_8)))

(defn signature-valid?
  "True when `header` carries a v1 HMAC-SHA256 of the signed payload under
  `secret`, stamped within `tolerance-seconds` either side of `received-at`.

  `raw-body` must be the untouched request body — reserializing a parsed body
  changes bytes and every signature over it. A blank secret accepts nothing:
  an unconfigured endpoint that fails open is worse than one that is off. The
  future bound is this store's own, because Stripe's tolerance is one-sided and
  a notice stamped a year ahead is not a notice."
  [{:keys [secret tolerance-seconds]} header raw-body received-at]
  (boolean
   (when-not (str/blank? (str secret))
     (when-let [{:signature/keys [timestamp values]} (parse-signature-header header)]
       (let [tolerance (long (or tolerance-seconds default-tolerance-seconds))
             now (long (or received-at 0))
             expected (hmac-hex secret (signed-payload timestamp raw-body))]
         (and (<= (abs (- timestamp now)) tolerance)
              (boolean (some #(constant-time= expected (str %)) values))))))))

;; ---------------------------------------------------------------------------
;; reader

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

(defn entry
  "A registry entry {:profile :rail} for Stripe over `:gateway`.

  Every profile key is overridable by `config`: a deployment selling in euros
  passes `:provider/currency :eur` and nothing else moves."
  [config]
  (hosted/entry (merge profile
                       {:provider-id :stripe}
                       config
                       {:reader (reader config)})))

(m/=> parse-signature-header [:=> [:cat [:maybe :string]] [:maybe :map]])
(m/=> signature-valid? [:=> [:cat :map [:maybe :string] [:maybe :string] [:maybe :int]] :boolean])

(m/=> signed-payload [:=> [:cat :any :any] :string])

(m/=> hmac-hex [:=> [:cat :any :any] :string])

(m/=> constant-time= [:=> [:cat [:maybe :string] [:maybe :string]] [:maybe :boolean]])

(m/=> reader [:=> [:cat [:map [:webhook-secret {:optional true} [:maybe :string]]
                              [:webhook-tolerance-seconds {:optional true} [:maybe :int]]]]
              [:map [:reader/subject-key :keyword] [:reader/authentic? ifn?]]])

(m/=> entry [:=> [:cat :map] [:map [:profile schema/ProviderProfile] [:rail :any]]])
