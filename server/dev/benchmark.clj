(ns benchmark
  "Opt-in 10k-server HTTP-boundary benchmark; never part of the regular suite."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [eacl-datahike-demo.config :as config]
            [eacl-datahike-demo.data :as data]
            [eacl-datahike-demo.system :as system]
            [eacl-datahike-demo.test-support :as support])
  (:import [java.lang.management ManagementFactory]
           [java.nio.charset StandardCharsets]))

(defn- percentile
  [sorted-values percentile]
  (let [index (-> (* percentile (dec (count sorted-values)))
                  Math/ceil
                  long)]
    (nth sorted-values index)))

(defn- timed-request
  [handler]
  (let [started (System/nanoTime)
        response (support/request
                  handler :post "/api/eacl/lookup-resources"
                  support/lookup-resources-body)
        elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
        payload-bytes (alength
                       (.getBytes ^String (:body response)
                                  StandardCharsets/UTF_8))]
    (when-not (= 200 (:status response))
      (throw (ex-info "Benchmark request failed."
                      {:status (:status response)
                       :body (:body response)})))
    {:elapsed-ms elapsed-ms :payload-bytes payload-bytes}))

(defn- heap-snapshot
  []
  (let [runtime (Runtime/getRuntime)
        heap (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
    {:used-bytes (.getUsed heap)
     :committed-bytes (.getCommitted heap)
     :max-bytes (.maxMemory runtime)
     :available-processors (.availableProcessors runtime)}))

(defn- resident-bytes
  []
  (try
    (let [pid (str (.pid (java.lang.ProcessHandle/current)))
          process (.start (ProcessBuilder. ["ps" "-o" "rss=" "-p" pid]))
          rss-kib (with-open [reader (io/reader (.getInputStream process))]
                    (some-> (slurp reader) str/trim Long/parseLong))]
      (.waitFor process)
      (when (zero? (.exitValue process))
        (* 1024 rss-kib)))
    (catch Exception _
      nil)))

(defn- process-snapshot
  []
  (assoc (heap-snapshot) :resident-bytes (resident-bytes)))

(defn- file-config
  [{:keys [store-path store-id]}]
  (when-not (and (string? store-path) (not (str/blank? store-path)))
    (throw (ex-info "A nonblank file store path is required."
                    {:field :store-path})))
  (when-not (uuid? store-id)
    (throw (ex-info "A UUID file store id is required."
                    {:field :store-id})))
  (merge config/default-config
         {:store-backend :file
          :store-path store-path
          :store-id store-id}))

(defn- delete-temp-tree!
  [path]
  (when path
    (doseq [file (reverse (file-seq (io/file path)))]
      (io/delete-file file true))))

(defn run!
  "Seed `server-count` additional servers and measure warmed default-page HTTP
  requests. Returns JSON-safe results including request count, bytes, p50/p95,
  and the proposal's 250 ms target. Defaults to 10,000 servers and 50 samples."
  ([] (run! {:server-count 10000 :iterations 50}))
  ([{:keys [server-count iterations store-backend]
     :or {server-count 10000 iterations 50 store-backend :memory}}]
   (let [temp-root (when (= :file store-backend)
                     (str (java.nio.file.Files/createTempDirectory
                           "eacl-datahike-benchmark"
                           (make-array java.nio.file.attribute.FileAttribute 0))))
         store-path (when temp-root (str temp-root "/store"))
         overrides (cond-> {:store-backend store-backend}
                     store-path (assoc :store-path store-path))
         system (support/build-test-system overrides)]
     (try
     (let [seed-started (System/nanoTime)
           _ (data/seed-more! (:conn system)
                              (:acl system)
                              (:!seed-running? system)
                              (:!seed-progress system)
                              server-count
                              server-count)
           seed-ms (/ (double (- (System/nanoTime) seed-started)) 1000000.0)
           handler (:handler system)
           _ (dotimes [_ 3] (timed-request handler))
           samples (vec (repeatedly iterations #(timed-request handler)))
           latencies (vec (sort (map :elapsed-ms samples)))
           bytes (reduce + (map :payload-bytes samples))
           p95 (percentile latencies 0.95)
           _ (System/gc)
           _ (Thread/sleep 2000)
           heap (process-snapshot)]
       {:fixture {:servers-added server-count
                  :servers-total (+ 48 server-count)
                  :seed-ms seed-ms
                  :store-backend store-backend}
        :requests iterations
        :payload-bytes {:total bytes
                        :average (/ bytes (double iterations))}
        :latency-ms {:p50 (percentile latencies 0.50)
                     :p95 p95
                     :max (peek latencies)}
        :post-full-gc-heap heap
        :target {:warmed-default-page-p95-ms 250.0
                 :minimum-free-heap-ratio 0.20
                 :latency-met? (<= p95 250.0)
                 :heap-headroom-met?
                 (<= (:used-bytes heap) (* 0.80 (:max-bytes heap)))}})
       (finally
         (support/close-test-system! system)
         (delete-temp-tree! temp-root))))))

(defn run-json!
  []
  (json/write-str (run!) :escape-slash false))

(defn seed-file!
  "Create or extend a durable file-backed benchmark store. This function is
  intentionally run in a process separate from `read-file!` so seed-time heap
  pressure cannot contaminate steady-state read sizing. The caller owns and
  removes the store directory."
  [{:keys [server-count transaction-size pause-ms]
    :or {server-count 100000 transaction-size 250 pause-ms 50}
    :as options}]
  (let [runtime (system/build-system (file-config options))]
    (try
      (let [started (System/nanoTime)
            progress (data/seed-more! (:conn runtime)
                                      (:acl runtime)
                                      (:!seed-running? runtime)
                                      (:!seed-progress runtime)
                                      server-count
                                      server-count
                                      transaction-size
                                      pause-ms)]
        {:phase :seed
         :seed-options {:server-count server-count
                        :transaction-size transaction-size
                        :pause-ms pause-ms}
         :progress progress
         :elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
         :process (process-snapshot)})
      (finally
        (system/close-system! runtime)))))

(defn read-file!
  "Reconnect to an existing durable benchmark store and measure warmed reads.
  Run this from a fresh JVM with the same store path/id used by `seed-file!`."
  [{:keys [iterations]
    :or {iterations 100}
    :as options}]
  (let [runtime (system/build-system (file-config options))]
    (try
      (let [handler (:handler runtime)
            _ (dotimes [_ 5] (timed-request handler))
            samples (vec (repeatedly iterations #(timed-request handler)))
            latencies (vec (sort (map :elapsed-ms samples)))
            bytes (reduce + (map :payload-bytes samples))
            _ (System/gc)
            _ (Thread/sleep 2000)
            process (process-snapshot)
            p95 (percentile latencies 0.95)]
        {:phase :read
         :servers-total (data/count-servers (d/db (:conn runtime)))
         :requests iterations
         :payload-bytes {:total bytes
                         :average (/ bytes (double iterations))}
         :latency-ms {:p50 (percentile latencies 0.50)
                      :p95 p95
                      :max (peek latencies)}
         :post-full-gc-process process
         :target {:warmed-default-page-p95-ms 250.0
                  :minimum-free-heap-ratio 0.20
                  :latency-met? (<= p95 250.0)
                  :heap-headroom-met?
                  (<= (:used-bytes process)
                      (* 0.80 (:max-bytes process)))}})
      (finally
        (system/close-system! runtime)))))
