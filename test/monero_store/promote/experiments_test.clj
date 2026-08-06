(ns monero-store.promote.experiments-test
  "Assignment, and what the store is allowed to say about it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [monero-store.boundary.shell :as shell]
            [monero-store.collect.analytics :as analytics]
            [monero-store.promote.experiments :as experiment]))

(def experiments
  {:cta {:attribute "data-store-cta"
         :default "brand"
         :variants {:brand "#a83c0a" :deep "#8a3210" :ink "#262b33"}}})

(deftest assignment-is-sticky-without-storing-anything
  (let [visitor "visitor-1"
        once (experiment/assign experiments visitor)]
    (is (= once (experiment/assign experiments visitor)))
    (testing "and it is a real choice, not a constant"
      (is (< 1 (count (into #{}
                            (map #(get (experiment/assign experiments (str "v-" %)) :cta))
                            (range 60))))))))

(deftest the-arms-are-used-in-roughly-equal-measure
  (let [counts (frequencies (map #(get (experiment/assign experiments (str "visitor-" %)) :cta)
                                 (range 900)))]
    (is (= 3 (count counts)))
    (testing "no arm starves — a 3-way split of 900 lands well inside these bounds"
      (is (every? #(< 200 % 400) (vals counts))))))

(deftest an-anonymous-visitor-gets-the-control-arm
  (testing "so a crawler cannot skew a test, and an uncookied reader sees the safe design"
    (is (= :brand (get (experiment/assign experiments nil) :cta)))
    (is (= :brand (get (experiment/assign experiments "") :cta)))))

(deftest an-assignment-is-one-attribute
  (let [assignments (experiment/assign experiments "visitor-1")]
    (is (= 1 (count (experiment/attributes experiments assignments))))
    (is (str/starts-with? (experiment/attribute-string
                           (experiment/attributes experiments assignments))
                          "data-store-cta="))))

(deftest the-browser-is-told-its-own-arm-and-nothing-else
  (let [described (experiment/describe experiments (experiment/assign experiments "visitor-1"))]
    (is (= #{"cta"} (set (keys described))))
    (is (= ["brand" "deep" "ink"] (get-in described ["cta" :variants])))
    (is (string? (get-in described ["cta" :variant])))))

;; ---------------------------------------------------------------------------
;; the shell

(deftest the-arm-is-merged-into-the-document-not-spliced-into-it
  (let [document (shell/document {:attributes {"data-store-cta" "deep"}})
        [tag attributes] document]
    (testing "the assignment is a key in a map, so there is nowhere else it can land"
      (is (= :html tag))
      (is (= "deep" (get attributes "data-store-cta")))
      (is (= "en" (:lang attributes))))

    (testing "no assignment leaves the attributes as they were"
      (is (= {:lang "en"} (second (shell/document {})))))))

(deftest the-rendered-document-is-a-document
  (let [html (shell/render {:title "Store" :attributes {"data-store-cta" "ink"}})]
    (is (str/starts-with? html "<!DOCTYPE html>"))
    (is (str/includes? html "data-store-cta=\"ink\""))
    (is (str/includes? html "<link href=\"/css/tokens.css\""))
    (is (str/includes? html "<div id=\"app\">"))
    (testing "exactly one assignment reaches the document"
      (is (= 1 (count (re-seq #"data-store-cta" html)))))))

(deftest the-document-escapes-what-it-is-given
  (testing "hiccup does it, so this store never has to remember to"
    (is (str/includes? (shell/render {:title "<script>alert(1)</script>"})
                       "&lt;script&gt;"))))

(deftest a-visitor-id-is-read-from-a-cookie-and-never-invented-twice
  (is (= "abc" (shell/visitor-of {:headers {"cookie" "other=1; store_visitor=abc; x=2"}})))
  (is (= "hdr" (shell/visitor-of {:headers {"x-visitor-id" "hdr"}})))
  (is (nil? (shell/visitor-of {:headers {}})))
  (testing "and it is set only when it was minted"
    (is (contains? (:headers (shell/set-visitor-cookie {:headers {}} "v" true)) "set-cookie"))
    (is (not (contains? (:headers (shell/set-visitor-cookie {:headers {}} "v" false)) "set-cookie")))))

;; ---------------------------------------------------------------------------
;; analytics

(deftest a-funnel-event-carries-counts-not-people
  (let [scrubbed (analytics/scrub {:item "pro"
                                   :amount 9900
                                   :email "someone@example.com"
                                   :address "4Axxxx"
                                   :tx-hash "deadbeef"
                                   :customer-id (random-uuid)})]
    (is (= {:item "pro" :amount 9900} scrubbed))
    (testing "an unrecognised value becomes its shape, never its contents"
      (is (= {:when "Date"} (analytics/scrub {:when (java.util.Date.)}))))))

(deftest a-broken-sink-does-not-take-the-others-down
  (let [good (analytics/memory)
        exploding (reify analytics/IAnalytics
                    (track! [_ _] (throw (ex-info "vendor is down" {}))))
        sink (analytics/composite [exploding good])]
    (is (nil? (analytics/track! sink (analytics/event :checkout/opened {:item "pro"}))))
    (is (= 1 (count (analytics/events good))))))

(deftest analytics-never-fails-a-payment
  (testing "the no-op and the recorder agree about what track! returns"
    (is (nil? (analytics/track! (analytics/noop) (analytics/event :x {}))))
    (is (nil? (analytics/track! (analytics/memory) (analytics/event :x {}))))))
