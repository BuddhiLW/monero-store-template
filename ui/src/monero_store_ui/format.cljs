(ns monero-store-ui.format
  "Rendering money and status for a human.

  The backend already sends every amount as both an integer and a display
  string, so nothing here recomputes an amount — it only chooses words."
  (:require [clojure.string :as str]))

(defn money
  "`{:display :currency}` as one string."
  [{:keys [display currency]}]
  (when display
    (str display " " (str/upper-case (str currency)))))

(def status-labels
  {"pending" "Waiting for payment"
   "underpaid" "Partly paid"
   "paid" "Paid"
   "expired" "Expired"
   "failed" "Failed"})

(defn status
  [value]
  (get status-labels value (str value)))

(defn payment-uri
  "A wallet URI for `invoice`, or nil when the rail is not an address rail.

  `monero:` with an amount is what a wallet reads out of a QR code or a click,
  and it is the difference between a customer pasting two fields correctly and
  a customer pasting one of them wrong."
  [{:keys [provider amount]} pay-to]
  (when (and pay-to (= "monero" provider))
    (str "monero:" pay-to "?tx_amount=" (:display amount))))

(defn truncate
  [value n]
  (let [s (str value)]
    (if (<= (count s) n) s (str (subs s 0 n) "…"))))
