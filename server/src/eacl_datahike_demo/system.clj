(ns eacl-datahike-demo.system
  "REPL-friendly ownership of Datahike, EACL, executors, nREPL, and Ring."
  (:require [clojure.tools.logging :as log]
            [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike-eacl]
            [eacl-datahike-demo.api :as api]
            [eacl-datahike-demo.config :as config]
            [eacl-datahike-demo.data :as data]
            [eacl-datahike-demo.http :as http]
            [konserve-s3.core :as konserve-s3]
            [nrepl.server :as nrepl]
            [ring.adapter.jetty :as jetty])
  (:import [java.util.concurrent Executors ExecutorService Semaphore TimeUnit]))

(defonce !system (atom nil))

(defn client-options
  [{:keys [security-key request-timeout-ms cache-max-entries
           cache-projection-max-weight cache-denotation-max-weight
           cache-answer-max-weight cache-managed-proof-max-atoms]}]
  (cond-> {:cache {:max-entries cache-max-entries
                   :admit-on-repeat? false
                   :subproblem-cache
                   {:projection-max-weight cache-projection-max-weight
                    :denotation-max-weight cache-denotation-max-weight
                    :answer-max-weight cache-answer-max-weight
                    :managed-proof-max-atoms cache-managed-proof-max-atoms}}
           :execution-timeout-ms request-timeout-ms}
    security-key (assoc :security-key security-key)))

(defn- open-connection!
  [runtime-config]
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
     :database-created? true}))

(defn build-system
  [runtime-config]
  (let [runtime-config (config/validate runtime-config)
        executor (Executors/newFixedThreadPool 1)
        !conn (atom nil)]
    (try
      (let [{:keys [conn] :as connection} (open-connection! runtime-config)
            _ (reset! !conn conn)
            acl (datahike-eacl/make-client
                 conn (client-options runtime-config))
            !seed-progress (atom data/ready-progress)
            system (merge
                    connection
                    {:config runtime-config
                     :acl acl
                     :executor executor
                     :eacl-permits
                     (Semaphore. (:max-eacl-concurrency runtime-config) true)
                     :!cache-generation (atom 0)
                     :!cache-prewarm (atom nil)
                     :!metrics (atom {})
                     :!seed-running? (atom false)
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
        (when (= :s3 (:store-backend runtime-config))
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
  [{:keys [nrepl-server conn executor config cache-prewarm] :as system}]
  (stop-http! system)
  (when nrepl-server
    (nrepl/stop-server nrepl-server))
  (when cache-prewarm
    (eacl/cancel! (:cancellation-token cache-prewarm))
    (.cancel ^java.util.concurrent.Future (:future cache-prewarm) true)
    (some-> system :!cache-prewarm (reset! nil)))
  (when executor
    (.shutdownNow ^ExecutorService executor)
    (.awaitTermination ^ExecutorService executor 2 TimeUnit/SECONDS))
  (when conn
    (try
      (d/release conn)
      (catch IllegalStateException exception
        (log/debug "Datahike connection was already released"
                   {:exception-class (.getName (class exception))}))))
  (when (= :s3 (:store-backend config))
    (konserve-s3/shutdown-clients!)))

(defn- start-nrepl
  [base]
  (if (or (:nrepl-server base)
          (nil? (get-in base [:config :nrepl-port])))
    base
    (let [{:keys [nrepl-host nrepl-port]} (:config base)]
      (assoc base :nrepl-server
             (nrepl/start-server :bind nrepl-host :port nrepl-port)))))

(def ^:private cache-prewarm-timeout-ms 300000)

(defn prewarm-cache!
  "Warms the public demo's canonical first page.
  This is intentionally a demo-specific optimization, not an EACL default."
  [system cancellation-token]
  (let [common {:subject (data/->object :user "super-user")
                :permission :view
                :resource/type :server
                :cache? true
                :timeout-ms cache-prewarm-timeout-ms
                :cancellation-token cancellation-token}
        started (System/nanoTime)
        page (eacl/lookup-resources (:acl system) (assoc common :first 20))]
    {:status :complete
     :elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
     :page-items (count (:data page))}))

(defn- start-cache-prewarm
  [system]
  (if (or (not= :production (get-in system [:config :mode]))
          (:cache-prewarm system))
    system
    (let [cancellation-token (eacl/cancellation-token)
          state (atom {:status :running})
          task
          (.submit
           ^ExecutorService (:executor system)
           ^Callable
           (reify Callable
             (call [_]
               (try
                 (reset! state (prewarm-cache! system cancellation-token))
                 (catch Throwable throwable
                   (let [cancelled?
                         (= :eacl.execution/cancelled (:type (ex-data throwable)))]
                     (reset! state
                             {:status (if cancelled? :cancelled :error)
                              :exception-class (.getName (class throwable))})
                     (when-not cancelled?
                       (log/warn "EACL demo cache prewarm failed"
                                 {:exception-class
                                  (.getName (class throwable))})))))
               nil)))]
      (let [prewarm {:cancellation-token cancellation-token
                     :future task
                     :state state}]
        (some-> system :!cache-prewarm (reset! prewarm))
        (assoc system :cache-prewarm prewarm)))))

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
                        start-nrepl
                        start-cache-prewarm)]
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
  [:store-backend :store-id :store-path :s3-bucket :s3-region
   :s3-endpoint-override :s3-path-style-access? :s3-access-key :s3-secret
   :security-key
   :request-timeout-ms :max-eacl-concurrency :nrepl-host :nrepl-port])

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
