(ns monero-store.boundary.identity
  "Who is asking — as a seam, not a decision.

  This store never authenticates anyone. A deployment already has an identity
  system: OIDC, a session cookie, an API key, a signed header from a gateway.
  It supplies an `identify-fn` of the request that yields
  {:customer/ref :customer/email} or nil, and the boundary upserts a customer
  from that.

  Two implementations ship: one for a demo and one for machine clients. Neither
  is an authentication system, and both say so."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(defn header-identity
  "Identity from a request header. NO AUTHENTICATION WHATSOEVER.

  Whoever sets the header is whoever they say they are. It exists so the
  template runs end to end before a deployment has wired its own identity, and
  behind a gateway that has already authenticated the caller and is asserting
  the result. `system/start!` refuses to use it unless the deployment says so
  out loud."
  ([] (header-identity {}))
  ([{:keys [header] :or {header "x-customer-ref"}}]
   (fn [request]
     (when-let [ref (some-> (get-in request [:headers header]) str not-empty)]
       {:customer/ref ref
        :customer/email (some-> (get-in request [:headers "x-customer-email"]) str not-empty)}))))

(defn- constant-time=
  [^String a ^String b]
  (and a b (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                                  (.getBytes b StandardCharsets/UTF_8))))

(defn bearer-token
  "The bearer credential a request presents, or nil."
  [request]
  (let [header (get-in request [:headers "authorization"])]
    (when (and header (str/starts-with? (str/lower-case header) "bearer "))
      (not-empty (str/trim (subs header 7))))))

(defn token-identity
  "Identity from a static map of bearer token -> {:customer/ref :customer/email}.

  For machine clients and for tests. Tokens are compared in constant time, and
  an empty map identifies nobody."
  [tokens]
  (fn [request]
    (when-let [presented (bearer-token request)]
      (some (fn [[token identity]]
              (when (constant-time= (str token) presented) identity))
            tokens))))

(defn anonymous
  "Identity that never resolves anyone. The default, and the safe one."
  []
  (fn [_request] nil))

(defn operator?
  "True when `request` carries the configured operator token.

  Constant time, and an unconfigured token accepts nothing: an operator
  surface that fails open is worse than no operator surface."
  [admin-token request]
  (boolean
   (and (not (str/blank? (str admin-token)))
        (constant-time= (str admin-token) (str (bearer-token request))))))
