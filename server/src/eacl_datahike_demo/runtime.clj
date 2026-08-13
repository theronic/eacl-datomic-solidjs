(ns eacl-datahike-demo.runtime
  "Mutable runtime counters kept outside authorization domain data."
  (:require [datahike.api :as d]))

(defn datahike-revision
  [db]
  (or (some-> (get-in db [:meta :datahike/commit-id]) str)
      (:max-tx db)
      "unknown"))

(defn revision
  [{:keys [conn !cache-generation]}]
  (str "h" (datahike-revision (d/db conn))
       ".c" @!cache-generation))

(defn advance-cache-generation!
  [{:keys [!cache-generation]}]
  (swap! !cache-generation inc))

(defn record-operation!
  [{:keys [!metrics]} operation elapsed-ms success? response-bytes cache-status]
  (swap! !metrics
         (fn [metrics]
           (-> metrics
               (update-in [operation :count] (fnil inc 0))
               (update-in [operation :total-ms] (fnil + 0.0) elapsed-ms)
               (update-in [operation :max-ms] (fnil max 0.0) elapsed-ms)
               (update-in [operation :response-bytes]
                          (fnil + 0) (long (or response-bytes 0)))
               (cond-> cache-status
                 (update-in [operation :cache-status cache-status]
                            (fnil inc 0)))
               (cond-> (not success?)
                 (update-in [operation :errors] (fnil inc 0)))))))

(defn metrics-snapshot
  [{:keys [!metrics]}]
  (into
   (sorted-map)
   (map
    (fn [[operation {:keys [count total-ms] :as metric}]]
      [operation
       (assoc metric :average-ms (if (pos? count) (/ total-ms count) 0.0))]))
   @!metrics))
