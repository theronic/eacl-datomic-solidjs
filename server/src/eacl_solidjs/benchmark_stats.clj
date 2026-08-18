(ns eacl-solidjs.benchmark-stats
  "Deterministic aggregation for the opt-in authorization benchmark.")

(defn percentile
  "Return the nearest-rank value for `quantile` from a non-empty collection."
  [values quantile]
  {:pre [(seq values) (<= 0.0 quantile 1.0)]}
  (let [sorted-values (vec (sort values))
        index (-> (* quantile (count sorted-values))
                  Math/ceil
                  long
                  dec
                  (max 0))]
    (nth sorted-values index)))

(defn- distribution
  [samples key]
  (let [values (mapv key samples)]
    {:p50 (percentile values 0.50)
     :p95 (percentile values 0.95)
     :max (apply max values)}))

(defn summarize-operation
  [samples]
  {:requests (count samples)
   :payload-bytes
   (let [total (reduce + (map :payload-bytes samples))]
     {:total total
      :average (/ total (double (count samples)))})
   :cache-status-counts (frequencies (map :cache-status samples))
   :server-latency-ms (distribution samples :server-elapsed-ms)
   :ring-boundary-latency-ms (distribution samples :boundary-elapsed-ms)})

(defn build-result
  [fixture operation-samples warmup-requests]
  (let [summaries (update-vals operation-samples summarize-operation)
        lookup-p95 (get-in summaries
                           [:lookup-resources
                            :ring-boundary-latency-ms
                            :p95])]
    {:fixture fixture
     :warmup-requests-per-operation warmup-requests
     :operations
     (assoc-in summaries
               [:lookup-resources :target]
               {:warmed-default-page-p95-ms 250.0
                :met? (<= lookup-p95 250.0)})}))
