(ns benchmark
  "Opt-in 10k-server Ring-boundary benchmark; never part of the regular suite."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [eacl-solidjs.benchmark-stats :as stats]
            [eacl-solidjs.data :as data]
            [eacl-solidjs.test-support :as support])
  (:import [java.nio.charset StandardCharsets]))

(def operation-descriptors
  [{:key :check-permission
    :path "/api/eacl/check-permission"
    :body support/check-permission-body}
   {:key :lookup-resources
    :path "/api/eacl/lookup-resources"
    :body support/lookup-resources-body}
   {:key :lookup-subjects
    :path "/api/eacl/lookup-subjects"
    :body support/lookup-subjects-body}])

(defn timed-request
  "Measure one in-process Ring handler invocation for an operation descriptor.
  The response's `meta.elapsedMs` remains separate from this outer boundary."
  [handler {:keys [key path body]}]
  (let [started (System/nanoTime)
        response (support/request handler :post path body)
        boundary-elapsed-ms (/ (double (- (System/nanoTime) started))
                               1000000.0)
        payload-bytes (alength
                       (.getBytes ^String (:body response)
                                  StandardCharsets/UTF_8))]
    (when-not (= 200 (:status response))
      (throw (ex-info "Benchmark request failed."
                      {:operation key
                       :status (:status response)
                       :body (:body response)})))
    (let [envelope (support/response-body response)
          server-elapsed-ms (get-in envelope [:meta :elapsedMs])
          cache-status (get-in envelope [:meta :cacheStatus])]
      (when-not (and (number? server-elapsed-ms)
                     (#{"hit" "miss" "disabled"} cache-status))
        (throw (ex-info "Benchmark response omitted timing metadata."
                        {:operation key
                         :meta (:meta envelope)})))
      {:server-elapsed-ms server-elapsed-ms
       :boundary-elapsed-ms boundary-elapsed-ms
       :payload-bytes payload-bytes
       :cache-status cache-status})))

(defn run!
  "Seed `server-count` additional servers, then warm and sample the point check
  and both lookup operations independently. Defaults to 10,000 servers, three
  warmups per operation, and 50 recorded samples per operation."
  ([] (run! {:server-count 10000 :iterations 50 :warmup-requests 3}))
  ([{:keys [server-count iterations warmup-requests]
     :or {server-count 10000 iterations 50 warmup-requests 3}}]
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
           operation-samples
           (into {}
                 (map (fn [descriptor]
                        (dotimes [_ warmup-requests]
                          (timed-request handler descriptor))
                        [(:key descriptor)
                         (vec (repeatedly iterations
                                          #(timed-request handler descriptor)))])
                      operation-descriptors))]
       (stats/build-result
        {:servers-added server-count
         :servers-total (+ 48 server-count)
         :seed-ms seed-ms}
        operation-samples
        warmup-requests)))))

(defn run-json!
  []
  (json/write-str (run!) :escape-slash false))
