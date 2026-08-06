(ns build
  "Uberjar. `clojure -T:build uber` — pass :aliases to include the rails this
  deployment actually runs, e.g. -T:build uber :aliases '[:stripe :jdbc]'."
  (:require [clojure.tools.build.api :as b]))

(def lib 'monero-store/monero-store)
(def version (or (System/getenv "VERSION") "0.1.0"))
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn- basis
  [aliases]
  (b/create-basis {:project "deps.edn" :aliases (or aliases [])}))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn uber
  [{:keys [aliases]}]
  (clean nil)
  (let [basis (basis aliases)]
    (b/copy-dir {:src-dirs (into ["src" "resources"]
                                 (mapcat #(get {:stripe ["adapters/stripe/src"]
                                                :monero-rpc ["adapters/monero-rpc/src"]
                                                :jdbc ["adapters/jdbc/src"]}
                                               % [])
                                         aliases))
                 :target-dir class-dir})
    (b/compile-clj {:basis basis :src-dirs ["src"] :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'monero-store.system})
    (println "built" uber-file)))
