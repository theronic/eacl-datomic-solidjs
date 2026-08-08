(ns benchmark
  "Opt-in 10k-server HTTP-boundary benchmark; never part of the regular suite."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [eacl-solidjs.data :as data]
            [eacl-solidjs.test-support :as support])
  (:import [java.nio.charset StandardCharsets]))

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

(defn run!
  "Seed `server-count` additional servers and measure warmed default-page HTTP
  requests. Returns JSON-safe results including request count, bytes, p50/p95,
  and the proposal's 250 ms target. Defaults to 10,000 servers and 50 samples."
  ([] (run! {:server-count 10000 :iterations 50}))
  ([{:keys [server-count iterations]
     :or {server-count 10000 iterations 50}}]
   (support/with-test-system [system]
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
           p95 (percentile latencies 0.95)]
       {:fixture {:servers-added server-count
                  :servers-total (+ 48 server-count)
                  :seed-ms seed-ms}
        :requests iterations
        :payload-bytes {:total bytes
                        :average (/ bytes (double iterations))}
        :latency-ms {:p50 (percentile latencies 0.50)
                     :p95 p95
                     :max (peek latencies)}
        :target {:warmed-default-page-p95-ms 250.0
                 :met? (<= p95 250.0)}}))))

(defn run-json!
  []
  (json/write-str (run!) :escape-slash false))
