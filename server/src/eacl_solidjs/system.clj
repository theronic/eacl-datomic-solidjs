(ns eacl-solidjs.system
  "REPL-friendly ownership of Datomic, EACL, executor, and Ring lifecycle."
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.datomic.core :as datomic-eacl]
            [eacl.datomic.schema :as schema]
            [eacl-solidjs.api :as api]
            [eacl-solidjs.config :as config]
            [eacl-solidjs.data :as data]
            [eacl-solidjs.http :as http]
            [ring.adapter.jetty :as jetty])
  (:import [java.util.concurrent Executors ExecutorService Semaphore TimeUnit]))

(defonce !system (atom nil))

(defn- ensure-storage-schema!
  [conn]
  (when-not (d/entid (d/db conn) :eacl/id)
    @(d/transact conn schema/v7-schema)))

(defn- client-options
  [{:keys [security-key request-timeout-ms cache-max-entries
           cache-projection-max-weight cache-denotation-max-weight
           cache-answer-max-weight cache-managed-proof-max-atoms]}]
  ;; :coherence-authority was a pre-release experimental option; cache
  ;; coherence is managed by the release client.
  ;; The demo's million-server platform subject legitimately exceeds the
  ;; default per-request work ceilings, so this deployment opts into
  ;; larger ones explicitly.
  (cond-> {:cache {:remember-answers true
                   :max-entries cache-max-entries
                   :subproblem-cache
                   {:projection-max-weight cache-projection-max-weight
                    :denotation-max-weight cache-denotation-max-weight
                    :answer-max-weight cache-answer-max-weight
                    :managed-proof-max-atoms cache-managed-proof-max-atoms}}
           :recursive-traversal-limits {:max-derived-grants 5000000
                                        :max-advanced-datoms 5000000
                                        :max-queued-work 1000000}
           :execution-timeout-ms request-timeout-ms}
    security-key (assoc :security-key security-key)))

(defn build-system
  [runtime-config]
  (let [runtime-config (config/validate runtime-config)
        uri (:datomic-uri runtime-config)
        _ (d/create-database uri)
        conn (d/connect uri)
        executor (Executors/newFixedThreadPool 2)]
    (try
      (ensure-storage-schema! conn)
      (let [acl (datomic-eacl/make-client conn (client-options runtime-config))
            !seed-progress (atom data/ready-progress)
            system {:config runtime-config
                    :conn conn
                    :acl acl
                    :executor executor
                    :eacl-permits
                    (Semaphore. (:max-eacl-concurrency runtime-config) true)
                    :!cache-generation (atom 0)
                    :!metrics (atom {})
                    :!seed-running? (atom false)
                    :!seed-progress !seed-progress
                    :evict-lock (Object.)}
            ready-progress (data/install-demo! conn acl)
            system (assoc system :handler (api/app system))]
        (reset! !seed-progress ready-progress)
        system)
      (catch Throwable throwable
        (.shutdownNow ^ExecutorService executor)
        (.release conn)
        (throw throwable)))))

(defn- close-system!
  [{:keys [http-server conn executor]}]
  (when http-server
    (.stop http-server))
  (when executor
    (.shutdownNow ^ExecutorService executor)
    (.awaitTermination ^ExecutorService executor 2 TimeUnit/SECONDS))
  (when conn
    (try
      (.release conn)
      (catch IllegalStateException exception
        (log/debug exception "Datomic connection was already released")))))

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
            running (assoc base :http-server server)]
        (reset! !system running)
        (log/info "EACL SolidJS server ready" {:host host :port port})
        running)
      (catch Throwable throwable
        (close-system! base)
        (throw throwable)))))

(defn start!
  ([] (start! (config/from-env)))
  ([runtime-config]
   (when @!system
     (throw (ex-info "EACL SolidJS server is already running."
                     {:type :eacl-solidjs.system/already-running})))
   (listen! (build-system runtime-config))))

(defn stop!
  []
  (when-let [system @!system]
    (reset! !system nil)
    (close-system! system)
    (log/info "EACL SolidJS server stopped"))
  nil)

(defn restart!
  ([] (restart! (config/from-env)))
  ([runtime-config]
   (let [runtime-config (config/validate runtime-config)
         previous @!system
         same-client-config?
         (= (select-keys (:config previous) [:datomic-uri :security-key])
            (select-keys runtime-config [:datomic-uri :security-key]))]
     (if (and previous same-client-config?)
       ;; Reuse the live Datomic/EACL/executor state on a code or listener
       ;; reload. Datomic memory connections are single shared handles, so
       ;; releasing an "old" handle also invalidates a newly connected one.
       (let [base (-> previous
                      (dissoc :http-server :handler)
                      (assoc :config runtime-config))
             replacement (assoc base :handler (api/app base))]
         (when-let [http-server (:http-server previous)]
           (.stop http-server))
         (reset! !system nil)
         (log/info "EACL SolidJS HTTP listener stopped")
         (listen! replacement))
       ;; A different database or token domain requires a fully new client.
       ;; Build it before releasing the prior independent connection.
       (let [replacement (build-system runtime-config)]
         (when previous
           (reset! !system nil)
           (close-system! previous)
           (log/info "EACL SolidJS server stopped"))
         (listen! replacement))))))
