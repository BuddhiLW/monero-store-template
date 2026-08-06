(ns monero-store.pipeline.notice-test
  "Who may settle an invoice by posting to the store.

  Every test here is the same claim from a different angle: the notice names an
  invoice, and the INVOICE selects the rail that must authenticate it."
  (:require [clojure.test :refer [deftest is testing]]
            [monero-store.collect.wallet :as wallet]
            [monero-store.pipeline.checkout :as checkout]
            [monero-store.pipeline.notice :as notice]
            [monero-store.support :as support])
  (:import (java.nio.charset StandardCharsets)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def callback-secret "callback-secret")

(defn- open!
  [deps provider-id]
  (checkout/open! deps {:customer (support/customer! deps)
                        :item-id :yearly
                        :provider-id provider-id}))

(defn- notice-for
  [invoice-id & {:keys [provider token body headers]
                 :or {provider "monero" body "{}" headers {}}}]
  {:notice/raw-body body
   :notice/payload {}
   :notice/headers headers
   :notice/path-params (cond-> {:provider provider :invoice (str invoice-id)}
                         token (assoc :token token))
   :notice/received-at 1000})

;; ---------------------------------------------------------------------------
;; the chain rail: a notice is a prompt, the wallet is the evidence

(deftest a-notice-only-ever-prompts-a-re-read
  (let [deps (support/deps {:callback-secret callback-secret})
        {:keys [invoice handle]} (open! deps :monero)
        token (wallet/callback-token callback-secret (str (:invoice/id invoice)))]

    (testing "a notice about an unpaid invoice settles nothing: the wallet has seen nothing"
      (let [verdict (notice/apply-notice! deps :monero
                                          (notice-for (:invoice/id invoice) :token token))]
        (is (= :notice/applied (:notice/verdict verdict)))
        (is (= :settle/pending (:notice/outcome verdict)))
        (is (empty? @(:granted deps)))))

    (testing "with money in the wallet, the same notice grants"
      (wallet/credit! (:wallet deps) (:handle/pay-to handle) (:invoice/amount invoice))
      (let [verdict (notice/apply-notice! deps :monero
                                          (notice-for (:invoice/id invoice) :token token))]
        (is (= :settle/grant (:notice/outcome verdict)))
        (is (= 1 (count @(:granted deps))))))))

(deftest a-callback-token-that-does-not-match-is-refused
  (let [deps (support/deps {:callback-secret callback-secret})
        {:keys [invoice]} (open! deps :monero)]
    (is (= :notice/unauthenticated
           (:notice/verdict (notice/apply-notice! deps :monero
                                                  (notice-for (:invoice/id invoice)
                                                              :token "not-the-token")))))
    (testing "and so is one that is simply absent"
      (is (= :notice/unauthenticated
             (:notice/verdict (notice/apply-notice! deps :monero
                                                    (notice-for (:invoice/id invoice)))))))))

;; ---------------------------------------------------------------------------
;; the claim in the URL is only a claim

(deftest the-invoice-selects-the-rail-not-the-caller
  (let [deps (support/deps)
        {:keys [invoice]} (open! deps :monero)]
    (testing "claiming to be a rail the invoice does not belong to gets nowhere"
      (is (= :notice/unknown-invoice
             (:notice/verdict (notice/apply-notice! deps :stripe
                                                    (notice-for (:invoice/id invoice)
                                                                :provider "stripe"))))))))

(deftest an-unknown-invoice-and-a-refused-rail-look-identical
  (let [deps (support/deps)]
    (testing "no such invoice"
      (is (= :notice/unknown-invoice
             (:notice/verdict (notice/apply-notice!
                               deps :monero
                               (notice-for "00000000-0000-0000-0000-000000000009"))))))

    (testing "a real invoice on a rail with no HTTP settlement surface at all"
      (let [{:keys [invoice]} (open! deps :manual)]
        (is (= :notice/unknown-invoice
               (:notice/verdict (notice/apply-notice! deps :manual
                                                      (notice-for (:invoice/id invoice)
                                                                  :provider "manual")))))))))

;; ---------------------------------------------------------------------------
;; a processor that names the invoice in the body instead

(defn- signed-notice
  "A Stripe account-level notice: no invoice in the path, a signature over the
  exact bytes, and the invoice id replayed inside the body."
  [invoice-id]
  (let [body (str "{\"type\":\"checkout.session.completed\","
                  "\"data\":{\"object\":{\"id\":\"cs_x\",\"currency\":\"usd\","
                  "\"amount_total\":9900,"
                  "\"client_reference_id\":\"" invoice-id "\"}}}")
        mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes ^String support/webhook-secret StandardCharsets/UTF_8)
                                     "HmacSHA256")))
        signature (apply str (map #(format "%02x" (bit-and (long %) 0xff))
                                  (.doFinal mac (.getBytes (str "1000." body) StandardCharsets/UTF_8))))]
    {:notice/raw-body body
     :notice/payload {:type "checkout.session.completed"
                      :data {:object {:id "cs_x"
                                      :currency "usd"
                                      :amount_total 9900
                                      :client_reference_id (str invoice-id)}}}
     :notice/headers {"stripe-signature" (str "t=1000,v1=" signature)}
     :notice/path-params {:provider "stripe"}
     :notice/received-at 1000}))

(deftest a-body-may-name-the-invoice-when-the-path-cannot
  (let [deps (support/deps)
        {:keys [invoice]} (open! deps :stripe)
        notice (signed-notice (:invoice/id invoice))]
    (testing "the amount owed is the invoice's, and a signed notice for it grants"
      (is (= 9900 (:invoice/amount invoice)))
      (is (= :settle/grant (:notice/outcome (notice/apply-notice! deps :stripe notice)))))))

(deftest naming-an-invoice-is-not-authenticating-a-notice
  (let [deps (support/deps)
        {:keys [invoice]} (open! deps :stripe)
        forged (assoc-in (signed-notice (:invoice/id invoice))
                         [:notice/headers "stripe-signature"] "t=1000,v1=deadbeef")]
    (is (= :notice/unauthenticated (:notice/verdict (notice/apply-notice! deps :stripe forged))))
    (is (empty? @(:granted deps)))))
