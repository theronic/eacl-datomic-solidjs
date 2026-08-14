(ns eacl-datahike-demo.storage-gc
  "Exact Konserve key metadata enumeration with bounded parallel remote reads.

  Konserve's generic list-keys implementation reads every blob serially. That
  is appropriate for local stores but turns an exact S3 sweep into many hours
  of one-request-at-a-time latency. This namespace preserves the same metadata,
  filtering, and error semantics while bounding remote reads to a small pool."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [konserve.impl.defaults :as defaults]
            [konserve.impl.storage-layout :as layout])
  (:import [java.util.concurrent Callable Executors ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicInteger]))

(def default-parallelism 32)
(def ^:private submission-window 128)

(defn- thread-factory
  []
  (let [counter (AtomicInteger.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable
                       (str "eacl-storage-gc-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- read-store-key
  [backing serializers read-handlers write-handlers env store-key]
  (cond
    (or (str/ends-with? store-key ".new")
        (str/ends-with? store-key ".backup"))
    []

    (str/ends-with? store-key ".ksv")
    (try
      (let [blob (layout/-create-blob backing store-key env)
            lock (atom nil)]
        (try
          (when (and (get-in env [:config :in-place?])
                     (get-in env [:config :lock-blob?]))
            (reset! lock (layout/-get-lock blob env)))
          [(defaults/read-blob blob read-handlers serializers
                               (-> env
                                   (assoc :store-key store-key)
                                   (assoc-in [:msg :keys] store-key)))]
          (finally
            (layout/-release @lock env)
            (layout/-close blob env))))
      ;; Match Konserve's generic enumeration: a concurrently removed or
      ;; unreadable object is skipped rather than failing the entire sweep.
      (catch Exception _ []))

    :else
    (let [serializer (get serializers (:default-serializer env))]
      (layout/-handle-foreign-key backing store-key serializer
                                  read-handlers write-handlers env))))

(defn list-key-metadata
  "Return exactly the metadata set produced by Konserve list-keys, with at most
  `parallelism` simultaneous blob reads. Low-level calls use their synchronous
  form inside dedicated daemon threads; callers may wrap this for async use."
  ([store serializers read-handlers write-handlers env]
   (list-key-metadata store serializers read-handlers write-handlers env
                      default-parallelism))
  ([{:keys [backing]} serializers read-handlers write-handlers env parallelism]
   (let [sync-env (assoc env :sync? true)
         store-keys (layout/-keys backing sync-env)
         executor (Executors/newFixedThreadPool parallelism (thread-factory))]
     (try
       (reduce
        (fn [metadata window]
          (let [jobs
                (mapv
                 (fn [store-key]
                   (.submit executor
                            ^Callable
                            (fn []
                              (read-store-key backing serializers read-handlers
                                              write-handlers sync-env store-key))))
                 window)]
            (reduce (fn [result job]
                      (into result (.get job)))
                    metadata jobs)))
        #{}
        (partition-all submission-window store-keys))
       (finally
         (.shutdown executor)
         (when-not (.awaitTermination executor 30 TimeUnit/SECONDS)
           (.shutdownNow executor)))))))

(defn bounded-list-keys
  "Drop-in replacement for `konserve.impl.defaults/list-keys` during exact GC."
  [store serializers read-handlers write-handlers env]
  (if (:sync? env)
    (list-key-metadata store serializers read-handlers write-handlers env)
    (async/thread
      (try
        (list-key-metadata store serializers read-handlers write-handlers env)
        (catch Exception exception exception)))))
