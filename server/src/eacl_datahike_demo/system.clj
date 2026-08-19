(ns eacl-datahike-demo.system
  "REPL-friendly ownership of Datahike, EACL, executors, nREPL, and Ring."
  (:require [clojure.tools.logging :as log]
            [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike-eacl]
            [eacl-datahike-demo.api :as api]
            [eacl-datahike-demo.build-info :as build-info]
            [eacl-datahike-demo.config :as config]
            [eacl-datahike-demo.data :as data]
            [eacl-datahike-demo.eacl-adapter :as eacl-adapter]
            [eacl-datahike-demo.http :as http]
            [eacl-datahike-demo.storage-gc :as storage-gc]
            [konserve.impl.defaults :as konserve-defaults]
            [konserve.tiered :as konserve-tiered]
            [konserve-s3.core :as konserve-s3]
            [nrepl.server :as nrepl]
            [ring.adapter.jetty :as jetty])
  (:import [java.util.concurrent Executors ExecutorService Semaphore TimeUnit]))

(defonce !system (atom nil))

(defn- skip-tiered-full-sync
  "Keep a durable LMDB frontend as a lazy read-through cache.

  Datahike's default tiered-store readiness path enumerates every backend key
  and copies every missing value before connect returns.  Konserve S3 obtains
  those logical keys by reading every object's metadata, so a large database
  otherwise turns every restart into a full-store S3 scan.  This application
  has exactly one writer and uses :write-through, so commits keep both tiers
  coherent; frontend misses safely fall through to the authoritative backend
  and populate LMDB."
  [_store _sync-strategy _opts]
  true)

(defn- durable-source-lifecycle
  [{:keys [store-backend store-id]}]
  (when (not= :memory store-backend)
    {:application :eacl-datahike-demo
     :store-backend (if (= :s3-lmdb store-backend) :s3 store-backend)
     :store-id (str store-id)}))

(defn client-options
  [{:keys [security-key request-timeout-ms cache-max-entries
           cache-projection-max-weight cache-denotation-max-weight
           cache-answer-max-weight cache-managed-proof-max-atoms]
    :as runtime-config}]
  (let [source-lifecycle (durable-source-lifecycle runtime-config)]
    (cond-> {:cache {:max-entries cache-max-entries
                     :admit-on-repeat? false
                     :subproblem-cache
                     {:projection-max-weight cache-projection-max-weight
                      :denotation-max-weight cache-denotation-max-weight
                      :answer-max-weight cache-answer-max-weight
                      :managed-proof-max-atoms cache-managed-proof-max-atoms}}
             ;; The demo's platform-wide subject legitimately exceeds the
             ;; default per-request work ceilings on exhaustive counts.
             :recursive-traversal-limits {:max-derived-grants 5000000
                                          :max-advanced-datoms 5000000
                                          :max-queued-work 1000000}
             :execution-timeout-ms request-timeout-ms}
      security-key (assoc :security-key security-key)
      source-lifecycle (assoc :source-lifecycle source-lifecycle))))

(defn- open-connection!
  [runtime-config]
  (when (= :s3-lmdb (:store-backend runtime-config))
    ;; Loading this namespace registers the optional native :lmdb backend.
    (require 'datahike-lmdb.core))
  (let [open!
        (fn []
          (if-let [database-config (config/datahike-config runtime-config)]
            (if (d/database-exists? database-config)
              {:conn (d/connect database-config)
               :database-config database-config
               :database-created? false}
              {:conn (datahike-eacl/create-conn data/demo-attributes database-config)
               :database-config database-config
               :database-created? true})
            {:conn (datahike-eacl/create-conn data/demo-attributes)
             :database-config nil
             :database-created? true}))]
    (if (= :s3-lmdb (:store-backend runtime-config))
      (with-redefs [konserve-tiered/sync-on-connect skip-tiered-full-sync]
        (open!))
      (open!))))

(defn build-system
  [runtime-config]
  (let [runtime-config (config/validate runtime-config)
        executor (Executors/newFixedThreadPool 1)
        !conn (atom nil)]
    (try
      (let [{:keys [conn] :as connection} (open-connection! runtime-config)
            _ (reset! !conn conn)
            acl ((if (= :s3-lmdb (:store-backend runtime-config))
                   eacl-adapter/make-tiered-client
                   datahike-eacl/make-client)
                 conn (client-options runtime-config))
            !seed-progress (atom data/ready-progress)
            system (merge
                    connection
                    {:config runtime-config
                     :build-info (build-info/read-build-info)
                     :acl acl
                     :executor executor
                     :eacl-permits
                     (Semaphore. (:max-eacl-concurrency runtime-config) true)
                     :!cache-generation (atom 0)
                     :!metrics (atom {})
                     :!seed-running? (atom false)
                     :!storage-gc-running? (atom false)
                     :!seed-progress !seed-progress
                     :evict-lock (Object.)})
            ready-progress (data/install-demo!
                            conn acl (:legacy-server-count runtime-config))
            system (assoc system :handler (api/app system))]
        (reset! !seed-progress ready-progress)
        system)
      (catch Throwable throwable
        (.shutdownNow ^ExecutorService executor)
        (when-let [conn @!conn]
          (try
            (d/release conn)
            (catch Throwable cleanup-error
              (log/warn "Failed to release Datahike after startup failure"
                        {:exception-class
                         (.getName (class cleanup-error))}))))
        (when (#{:s3 :s3-lmdb} (:store-backend runtime-config))
          (try
            (konserve-s3/shutdown-clients!)
            (catch Throwable cleanup-error
              (log/warn "Failed to close S3 clients after startup failure"
                        {:exception-class
                         (.getName (class cleanup-error))}))))
        (throw throwable)))))

(defn- stop-http!
  [{:keys [http-server]}]
  (when http-server
    (.stop http-server)))

(defn close-system!
  [{:keys [nrepl-server conn executor config] :as system}]
  (stop-http! system)
  (when nrepl-server
    (nrepl/stop-server nrepl-server))
  (when executor
    (.shutdownNow ^ExecutorService executor)
    (.awaitTermination ^ExecutorService executor 2 TimeUnit/SECONDS))
  (when conn
    (try
      (d/release conn)
      (catch IllegalStateException exception
        (log/debug "Datahike connection was already released"
                   {:exception-class (.getName (class exception))}))))
  (when (#{:s3 :s3-lmdb} (:store-backend config))
    (konserve-s3/shutdown-clients!)))

(defn gc-storage!
  "Run exact Datahike reachability GC in the sole writer JVM.
  Call only during an operator-controlled maintenance window."
  ([] (gc-storage! @!system))
  ([system]
   (when-not system
     (throw (ex-info "The EACL Datahike system is not running."
                     {:type :eacl-datahike-demo.system/not-running})))
   (when @(:!seed-running? system)
     (throw (ex-info "Cannot run storage GC while a seed is active."
                     {:type :eacl-datahike-demo.system/seed-running})))
   (when-not (compare-and-set! (:!storage-gc-running? system) false true)
     (throw (ex-info "Storage GC is already running."
                     {:type :eacl-datahike-demo.system/gc-running})))
   (try
     (let [result (with-redefs [konserve-defaults/list-keys
                                storage-gc/bounded-list-keys]
                    @(d/gc-storage (:conn system)))]
       (if (instance? Throwable result)
         (throw result)
         ;; Datahike returns the complete deleted-key set. Returning that through
         ;; nREPL can serialize hundreds of thousands of UUIDs after a large
         ;; sweep, so expose the operational fact the caller needs instead.
         {:status :complete
          :deleted-key-count (count result)}))
     (finally
       (reset! (:!storage-gc-running? system) false)))))

(defn- start-nrepl
  [base]
  (if (or (:nrepl-server base)
          (nil? (get-in base [:config :nrepl-port])))
    base
    (let [{:keys [nrepl-host nrepl-port]} (:config base)]
      (assoc base :nrepl-server
             (nrepl/start-server :bind nrepl-host :port nrepl-port)))))

;; The production cache prewarm was removed together with the engine
;; behavior it papered over: the retired engine's cold first page visited
;; every branch (minutes against S3), so the demo warmed the canonical
;; page at boot. The stable-discovery engine's cold first page costs a
;; handful of storage reads, and hiding cold behavior distorts exactly
;; the measurements this deployment exists to produce.

(defn jetty-options
  [{:keys [host port request-timeout-ms jetty-min-threads
           jetty-max-threads jetty-max-queued-requests]}]
  {:host host
   :port port
   :join? false
   :async? true
   :async-timeout request-timeout-ms
   :max-idle-time request-timeout-ms
   :configurator http/configure-server!
   :min-threads jetty-min-threads
   :max-threads jetty-max-threads
   :max-queued-requests jetty-max-queued-requests})

(defn- listen!
  [base]
  (let [{:keys [host port]} (:config base)]
    (try
      (let [server
            (jetty/run-jetty
             (http/asynchronous-handler (:handler base))
             (jetty-options (:config base)))
            running (-> base
                        (assoc :http-server server)
                        start-nrepl)]
        (reset! !system running)
        (log/info "EACL Datahike demo ready"
                  {:host host
                   :port port
                   :store-backend (get-in running [:config :store-backend])
                   :nrepl? (boolean (:nrepl-server running))})
        running)
      (catch Throwable throwable
        (close-system! base)
        (throw throwable)))))

(defn start!
  ([] (start! (config/from-env)))
  ([runtime-config]
   (when @!system
     (throw (ex-info "EACL Datahike demo is already running."
                     {:type :eacl-datahike-demo.system/already-running})))
   (listen! (build-system runtime-config))))

(defn stop!
  []
  (when-let [system @!system]
    (reset! !system nil)
    (close-system! system)
    (log/info "EACL Datahike demo stopped"))
  nil)

(def ^:private reusable-config-keys
  [:mode
   :store-backend :store-id :store-path :s3-bucket :s3-region
   :s3-endpoint-override :s3-path-style-access? :s3-access-key :s3-secret
   :lmdb-path :lmdb-map-size
   :datahike-store-cache-size :datahike-search-cache-size
   :security-key :legacy-server-count
   :request-timeout-ms :max-eacl-concurrency
   :cache-max-entries :cache-projection-max-weight
   :cache-denotation-max-weight :cache-answer-max-weight
   :cache-managed-proof-max-atoms
   :nrepl-host :nrepl-port])

(defn restart!
  ([] (restart! (config/from-env)))
  ([runtime-config]
   (let [runtime-config (config/validate runtime-config)
         previous @!system
         reusable?
         (and previous
              (= (select-keys (:config previous) reusable-config-keys)
                 (select-keys runtime-config reusable-config-keys)))]
     (if reusable?
       (let [base (-> previous
                      (dissoc :http-server :handler)
                      (assoc :config runtime-config))
             replacement (assoc base :handler (api/app base))]
         (stop-http! previous)
         (reset! !system nil)
         (listen! replacement))
       (do
         (when previous
           (reset! !system nil)
           (close-system! previous))
         (listen! (build-system runtime-config)))))))
