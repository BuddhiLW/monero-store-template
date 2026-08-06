(ns monero-store.boundary.routes-test
  "The HTTP surface, driven as a function of a request map."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [monero-store.boundary.identity :as identity]
            [monero-store.boundary.routes :as routes]
            [monero-store.collect.wallet :as wallet]
            [monero-store.support :as support]))

(def ^:private mapper (json/object-mapper {:decode-key-fn true}))

(defn- app
  [deps]
  (routes/handler (assoc deps :identify-fn (identity/header-identity))))

(defn- body-of
  [response]
  (json/read-value (:body response) mapper))

(defn- request
  [method uri & {:keys [customer body operator]}]
  (cond-> {:request-method method :uri uri :headers {} :path-params {}}
    customer (assoc-in [:headers "x-customer-ref"] customer)
    operator (assoc-in [:headers "authorization"] (str "Bearer " operator))
    body (assoc :body body)))

(deftest the-catalog-lists-what-is-for-sale-and-how-to-pay
  (let [deps (support/deps)
        response ((app deps) (request :get "/api/catalog"))
        body (body-of response)]
    (is (= 200 (:status response)))
    (is (= #{"monthly" "yearly"} (set (map :id (:items body)))))
    (testing "an unlisted item is not advertised"
      (is (not (contains? (set (map :id (:items body))) "hidden"))))
    (testing "every registered rail is offered, with the currency it settles in"
      (is (= #{"monero" "stripe" "manual"} (set (map :id (:providers body))))))
    (testing "a price is quoted into every currency a rail settles in"
      (is (some? (get-in (first (filter #(= "yearly" (:id %)) (:items body)))
                         [:quotes :xmr :amount :display]))))))

(deftest an-operator-sees-what-a-customer-does-not
  (let [deps (support/deps)
        body (body-of ((app deps) (request :get "/api/catalog" :operator "operator-token")))]
    (is (contains? (set (map :id (:items body))) "hidden"))))

(deftest checkout-needs-to-know-who-is-asking
  (let [deps (support/deps)
        anonymous ((routes/handler (assoc deps :identify-fn (identity/anonymous)))
                   (request :post "/api/checkout" :body "{\"item\":\"yearly\",\"provider\":\"monero\"}"))]
    (is (= 401 (:status anonymous)))))

(deftest checkout-hands-back-an-address-to-pay
  (let [deps (support/deps)
        response ((app deps) (request :post "/api/checkout"
                                      :customer "alice"
                                      :body "{\"item\":\"yearly\",\"provider\":\"monero\"}"))
        body (body-of response)]
    (is (= 201 (:status response)))
    (is (= "monero" (get-in body [:handle :provider])))
    (is (some? (get-in body [:handle :pay-to])))
    (testing "the amount is rendered for a human as well as an integer"
      (is (= "0.660000000000" (get-in body [:handle :amount :display])))
      (is (= 660000000000 (get-in body [:handle :amount :amount]))))
    (testing "and the invoice says nothing has been paid yet"
      (is (= "pending" (get-in body [:invoice :status])))
      (is (= 0 (get-in body [:invoice :paid :amount]))))))

(deftest an-unknown-item-or-rail-is-refused-before-anything-is-written
  (let [deps (support/deps)]
    (is (= 400 (:status ((app deps) (request :post "/api/checkout"
                                             :customer "alice"
                                             :body "{\"item\":\"nope\",\"provider\":\"monero\"}")))))
    (is (= 400 (:status ((app deps) (request :post "/api/checkout"
                                             :customer "alice"
                                             :body "{\"item\":\"yearly\",\"provider\":\"nope\"}")))))
    (testing "an unlisted item is a 404 to a customer: knowing its id buys nothing"
      (is (= 404 (:status ((app deps) (request :post "/api/checkout"
                                               :customer "alice"
                                               :body "{\"item\":\"hidden\",\"provider\":\"monero\"}"))))))))

(deftest a-rail-that-cannot-be-priced-is-unavailable-not-broken
  (let [deps (support/deps {:rates-fn (constantly [])})
        response ((app deps) (request :post "/api/checkout"
                                      :customer "alice"
                                      :body "{\"item\":\"yearly\",\"provider\":\"monero\"}"))]
    (is (= 503 (:status response)))))

(deftest an-invoice-is-visible-only-to-the-customer-it-belongs-to
  (let [deps (support/deps)
        opened (body-of ((app deps) (request :post "/api/checkout"
                                             :customer "alice"
                                             :body "{\"item\":\"yearly\",\"provider\":\"monero\"}")))
        invoice-id (get-in opened [:invoice :id])
        as-owner ((app deps) (assoc (request :get (str "/api/invoices/" invoice-id) :customer "alice")
                                    :path-params {:id invoice-id}))
        as-stranger ((app deps) (assoc (request :get (str "/api/invoices/" invoice-id) :customer "mallory")
                                       :path-params {:id invoice-id}))]
    (is (= 200 (:status as-owner)))
    (is (= 404 (:status as-stranger)))))

(deftest a-webhook-settles-the-invoice-it-names
  (let [deps (support/deps)
        opened (body-of ((app deps) (request :post "/api/checkout"
                                             :customer "alice"
                                             :body "{\"item\":\"yearly\",\"provider\":\"monero\"}")))
        invoice-id (get-in opened [:invoice :id])
        address (get-in opened [:handle :pay-to])]
    (wallet/credit! (:wallet deps) address (get-in opened [:handle :amount :amount]))
    (let [response ((app deps)
                    (assoc (request :post (str "/webhooks/monero/" invoice-id) :body "{}")
                           :path-params {:provider "monero" :invoice invoice-id}))]
      (is (= 200 (:status response)))
      (is (= "settle/grant" (:outcome (body-of response))))
      (is (= 1 (count @(:granted deps)))))

    (testing "an invoice nobody opened is unknown, and says nothing more"
      (let [response ((app deps)
                      (assoc (request :post "/webhooks/monero/00000000-0000-0000-0000-000000000009"
                                      :body "{}")
                             :path-params {:provider "monero"
                                           :invoice "00000000-0000-0000-0000-000000000009"}))]
        (is (= 404 (:status response)))))))

(deftest the-operator-surface-is-closed-without-the-token
  (let [deps (support/deps)]
    (is (= 401 (:status ((app deps) (request :get "/api/admin/queue")))))
    (is (= 401 (:status ((app deps) (request :get "/api/admin/queue" :operator "guess")))))
    (is (= 200 (:status ((app deps) (request :get "/api/admin/queue" :operator "operator-token")))))

    (testing "a store with no configured token has no operator surface at all"
      (let [closed (support/deps {:admin-token nil})]
        (is (= 401 (:status ((app closed) (request :get "/api/admin/queue" :operator "")))))))))

(deftest an-operator-grants-against-a-reference
  (let [deps (support/deps)
        _ (support/customer! deps "bob")
        response ((app deps) (request :post "/api/admin/grants"
                                      :operator "operator-token"
                                      :body "{\"customer\":\"bob\",\"item\":\"monthly\",\"reference\":\"wire-7\"}"))]
    (is (= 201 (:status response)))
    (is (= "settle/grant" (:outcome (body-of response))))

    (testing "a grant with nothing to attribute it to is refused"
      (is (= 400 (:status ((app deps) (request :post "/api/admin/grants"
                                               :operator "operator-token"
                                               :body "{\"customer\":\"bob\",\"item\":\"monthly\"}"))))))))

(deftest healthz-answers-without-a-customer
  (is (= 200 (:status ((app (support/deps)) (request :get "/healthz"))))))
