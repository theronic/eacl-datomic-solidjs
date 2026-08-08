(ns eacl-solidjs.runtime
  "Mutable runtime counters kept outside authorization domain data."
  (:require [datomic.api :as d]))

(defn revision
  [{:keys [conn !cache-generation]}]
  (str "d" (d/basis-t (d/db conn))
       ".c" @!cache-generation))

(defn advance-cache-generation!
  [{:keys [!cache-generation]}]
  (swap! !cache-generation inc))

(defn record-operation!
  [{:keys [!metrics]} operation elapsed-ms success? response-bytes]
  (swap! !metrics
         (fn [metrics]
           (-> metrics
               (update-in [operation :count] (fnil inc 0))
               (update-in [operation :total-ms] (fnil + 0.0) elapsed-ms)
               (update-in [operation :max-ms] (fnil max 0.0) elapsed-ms)
               (update-in [operation :response-bytes]
                          (fnil + 0) (long (or response-bytes 0)))
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
