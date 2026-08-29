(ns monero-store.promote.invoice
  "What an invoice's own state means. Pure.

  An invoice is a promise about an amount for a window. Once the window has
  passed the promise is void — but money is not, so this namespace answers what
  ARRIVING money means as well as whether the invoice may still be charged."
  (:require [malli.core :as m]
            [monero-store.collect.store :as store]
            [monero-store.schema :as schema])
  (:import (java.util Date)))

(defn open?
  "True when `invoice` can still take money and settle normally."
  [invoice]
  (contains? store/open-statuses (:invoice/status invoice)))

(defn lapsed?
  "True when `invoice` declares an expiry that `now` has passed.

  An invoice with no expiry never lapses: nothing was promised about a window."
  [invoice ^Date now]
  (boolean
   (when-let [^Date expires (:invoice/expires-at invoice)]
     (>= (.getTime now) (.getTime expires)))))

(defn chargeable?
  "True when `invoice` may still be presented to a customer as the thing to pay."
  [invoice now]
  (and (= :pending (:invoice/status invoice))
       (not (lapsed? invoice now))))

(defn resolution
  "What money seen against `invoice` at `now` means.

  `:applied` — the invoice is open and unlapsed, so settlement proceeds.
  `:late` — the invoice was already paid, or its quote lapsed. The money is
  real and belongs to a known customer; it simply cannot be applied by machine.
  It goes to the operator queue, because the alternative is swallowing it, and
  a chain payment has no reversal primitive to undo that with."
  [invoice now]
  (if (and (open? invoice) (not (lapsed? invoice now)))
    :applied
    :late))

(defn stale?
  "True when an open `invoice` has lapsed and should be marked `:expired`."
  [invoice now]
  (and (open? invoice) (lapsed? invoice now)))

(defn owed
  "Minor units still owed when `amount` was asked and `paid` has arrived.

  Truncated subtraction: never negative, never more than `amount`."
  [amount paid]
  (max 0 (- (long amount) (long paid))))

(defn remaining
  "Minor units still owed on `invoice` given `paid`. Zero once covered."
  [invoice paid]
  (owed (:invoice/amount invoice 0) (or paid 0)))

(m/=> open? [:=> [:cat schema/Invoice] :boolean])
(m/=> lapsed? [:=> [:cat schema/Invoice schema/Instant] :boolean])
(m/=> chargeable? [:=> [:cat schema/Invoice schema/Instant] :boolean])
(m/=> resolution [:=> [:cat schema/Invoice schema/Instant] [:enum :applied :late]])
(m/=> stale? [:=> [:cat schema/Invoice schema/Instant] :boolean])
(m/=> owed [:=> [:cat [:int {:min 0}] [:int {:min 0}]] [:int {:min 0}]])

(m/=> remaining [:=> [:cat schema/Invoice [:maybe :int]] [:int {:min 0}]])
