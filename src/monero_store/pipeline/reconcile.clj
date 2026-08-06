(ns monero-store.pipeline.reconcile
  "Settlement by asking. A rail that cannot call the store must be polled, or
  its invoices never settle at all — and a rail that can call is polled anyway,
  because a webhook that was never delivered leaves money sitting in a wallet.

  The poll drives the same `checkout/settle!` path a notice does, so a payment
  seen twice still buys exactly one period."
  (:require [monero-store.collect.store :as store]
            [monero-store.payments.provider :as provider]
            [monero-store.pipeline.checkout :as checkout]
            [monero-store.promote.invoice :as invoice]
            [taoensso.timbre :as log])
  (:import (java.util Date)
           (java.util.concurrent Executors ThreadFactory TimeUnit)))

(defn reconcile!
  "Ask `invoice`'s rail what it has seen and apply it.

  Returns the applied SettlementOutcome variant, or nil. One invoice's failure
  is its own: it is logged and the sweep continues, because a wallet that is
  briefly unreachable must not stop every other invoice from settling."
  [{:keys [rails] :as deps} invoice]
  (try
    (some->> (provider/polled-settlement rails invoice)
             (checkout/settle! deps invoice)
             :adt/variant)
    (catch Exception e
      (log/warn "invoice could not be reconciled"
                {:invoice (:invoice/id invoice) :error (.getMessage e)})
      nil)))

(defn expire-stale!
  "Mark every open invoice whose quote has lapsed `:expired`.

  Runs after the read, never before it: money that arrived inside the window
  and is only now being noticed must still settle normally. Returns the ids
  that moved."
  [{:keys [store now-fn] :or {now-fn #(Date.)}}]
  (let [now (now-fn)]
    (into []
          (keep (fn [inv]
                  (when (invoice/stale? inv now)
                    (store/set-invoice-status! store (:invoice/id inv) :expired)
                    (:invoice/id inv))))
          (store/open-invoices store))))

(defn sweep!
  "Reconcile every open invoice, then retire the ones whose window has closed.

  Returns the applied outcomes by count, plus how many invoices expired."
  [{:keys [store] :as deps}]
  (let [applied (frequencies (keep #(reconcile! deps %) (store/open-invoices store)))
        expired (expire-stale! deps)]
    (cond-> applied
      (seq expired) (assoc :invoice/expired (count expired)))))

(defn start!
  "Sweep every `:interval-ms` on a daemon thread. Returns a stop function.

  A sweep that throws must not kill the schedule, so the top of each run is
  guarded: `scheduleWithFixedDelay` cancels the task forever on the first
  escaping throwable, silently."
  [deps {:keys [interval-ms] :or {interval-ms 60000}}]
  (let [executor (Executors/newSingleThreadScheduledExecutor
                  ;; A Clojure fn is not a ThreadFactory, and the cast fails at
                  ;; boot rather than at compile time.
                  (reify ThreadFactory
                    (newThread [_ runnable]
                      (doto (Thread. ^Runnable runnable "monero-store-reconcile")
                        (.setDaemon true)))))]
    (.scheduleWithFixedDelay executor
                             (fn []
                               (try
                                 (let [applied (sweep! deps)]
                                   (when (seq applied)
                                     (log/info "reconciled invoices" applied)))
                                 (catch Throwable t
                                   (log/error t "reconcile sweep failed"))))
                             (long interval-ms)
                             (long interval-ms)
                             TimeUnit/MILLISECONDS)
    (fn [] (.shutdownNow executor))))
