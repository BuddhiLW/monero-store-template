(ns monero-store.payments.stripe-test
  "Authenticating a processor notice, and reading one."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.payments.hosted :as hosted]
            [monero-store.payments.stripe :as stripe])
  (:import (java.nio.charset StandardCharsets)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def secret "whsec_test_secret")

(defn- hmac-hex
  [key message]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes ^String key StandardCharsets/UTF_8) "HmacSHA256"))
    (apply str (map #(format "%02x" (bit-and (long %) 0xff))
                    (.doFinal mac (.getBytes ^String message StandardCharsets/UTF_8))))))

(defn- sign
  [timestamp body]
  (str "t=" timestamp ",v1=" (hmac-hex secret (str timestamp "." body))))

(def body "{\"type\":\"checkout.session.completed\"}")

(deftest a-signature-is-over-the-exact-bytes-that-arrived
  (is (true? (stripe/signature-valid? {:secret secret} (sign 1000 body) body 1000)))

  (testing "one changed byte in the body invalidates it"
    (is (false? (stripe/signature-valid? {:secret secret} (sign 1000 body) (str body " ") 1000))))

  (testing "a signature from another secret is not a signature"
    (is (false? (stripe/signature-valid? {:secret "whsec_other"} (sign 1000 body) body 1000)))))

(deftest an-old-or-future-notice-is-not-a-notice
  (is (false? (stripe/signature-valid? {:secret secret} (sign 1000 body) body 2000)))
  (testing "the future is bounded too, which the SDK's own tolerance is not"
    (is (false? (stripe/signature-valid? {:secret secret} (sign 9000 body) body 1000))))
  (testing "inside the tolerance it holds"
    (is (true? (stripe/signature-valid? {:secret secret} (sign 1000 body) body 1200)))))

(deftest an-unconfigured-endpoint-accepts-nothing
  (testing "failing open is worse than being off"
    (is (false? (stripe/signature-valid? {:secret nil} (sign 1000 body) body 1000)))
    (is (false? (stripe/signature-valid? {:secret ""} (sign 1000 body) body 1000)))))

(deftest a-malformed-header-is-refused-rather-than-parsed-loosely
  (is (nil? (stripe/parse-signature-header nil)))
  (is (nil? (stripe/parse-signature-header "")))
  (is (nil? (stripe/parse-signature-header "v1=abc")))
  (is (nil? (stripe/parse-signature-header "t=notanumber,v1=abc")))
  (is (= {:signature/timestamp 5 :signature/values #{"a" "b"}}
         (stripe/parse-signature-header "t=5,v1=a,v1=b"))))

;; ---------------------------------------------------------------------------

(def reader (merge hosted/default-reader (stripe/reader {:webhook-secret secret})))

(def invoice
  {:invoice/id #uuid "00000000-0000-0000-0000-000000000001"
   :invoice/amount 9900
   :invoice/currency :usd
   :invoice/external-ref "cs_test_1"
   :invoice/provider :stripe})

(defn- event
  [type overrides]
  {:type type
   :data {:object (merge {:id "cs_test_1"
                          :client_reference_id "00000000-0000-0000-0000-000000000001"
                          :currency "usd"
                          :amount_total 9900}
                         overrides)}})

(deftest an-event-is-read-as-a-settlement
  (let [settled (hosted/parse-event reader :stripe (event "checkout.session.completed" {}) 9900 "cs_test_1")]
    (is (= :settled (:settlement/status settled)))
    (is (= 9900 (:settlement/paid-amount settled))))

  (testing "an expiry is a rejection, and pays nothing"
    (let [failed (hosted/parse-event reader :stripe (event "checkout.session.expired" {}) 9900 "cs_test_1")]
      (is (= :failed (:settlement/status failed)))
      (is (zero? (:settlement/paid-amount failed)))))

  (testing "an event this reader does not know settles nothing"
    (let [other (hosted/parse-event reader :stripe (event "customer.created" {}) 9900 "cs_test_1")]
      (is (= :pending (:settlement/status other)))
      (is (zero? (:settlement/paid-amount other)))))

  (testing "the amount owed is the invoice's, whatever the event claims"
    (is (= 9900 (:settlement/expected-amount
                 (hosted/parse-event reader :stripe
                                     (event "checkout.session.completed" {:amount_total 1})
                                     9900 "cs_test_1"))))))

(deftest an-event-must-name-the-invoice-it-claims
  (is (true? (hosted/event-matches-invoice? reader (event "checkout.session.completed" {}) invoice)))

  (testing "an event about someone else's session is not about this invoice"
    (is (false? (hosted/event-matches-invoice?
                 reader
                 (event "checkout.session.completed" {:id "cs_other"
                                                      :client_reference_id "nope"})
                 invoice)))))

(deftest a-currency-that-disagrees-is-refused
  (is (true? (hosted/currency-agrees? "usd" invoice)))
  (is (true? (hosted/currency-agrees? nil invoice)))
  (is (false? (hosted/currency-agrees? "eur" invoice))))
