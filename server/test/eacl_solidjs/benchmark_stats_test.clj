(ns eacl-solidjs.benchmark-stats-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl-solidjs.benchmark-stats :as stats]))

(def samples
  [{:server-elapsed-ms 1.0
    :boundary-elapsed-ms 2.0
    :payload-bytes 100
    :cache-status "miss"}
   {:server-elapsed-ms 2.0
    :boundary-elapsed-ms 3.0
    :payload-bytes 110
    :cache-status "hit"}
   {:server-elapsed-ms 3.0
    :boundary-elapsed-ms 4.0
    :payload-bytes 120
    :cache-status "hit"}
   {:server-elapsed-ms 4.0
    :boundary-elapsed-ms 5.0
    :payload-bytes 130
    :cache-status "disabled"}])

(deftest percentile-and-operation-summary-are-deterministic
  (testing "nearest-rank percentiles are independent of input order"
    (is (= 2.0 (stats/percentile [4.0 1.0 3.0 2.0] 0.50)))
    (is (= 4.0 (stats/percentile [4.0 1.0 3.0 2.0] 0.95))))
  (is (= {:requests 4
          :payload-bytes {:total 460 :average 115.0}
          :cache-status-counts {"miss" 1 "hit" 2 "disabled" 1}
          :server-latency-ms {:p50 2.0 :p95 4.0 :max 4.0}
          :ring-boundary-latency-ms {:p50 3.0 :p95 5.0 :max 5.0}}
         (stats/summarize-operation samples))))

(deftest nested-result-keeps-operations-and-targets-separate
  (let [result (stats/build-result
                {:servers-added 10 :servers-total 58 :seed-ms 12.0}
                {:check-permission samples
                 :lookup-resources samples
                 :lookup-subjects samples}
                3)]
    (is (= #{:check-permission :lookup-resources :lookup-subjects}
           (set (keys (:operations result)))))
    (is (= 3 (:warmup-requests-per-operation result)))
    (is (= 4 (get-in result [:operations :check-permission :requests])))
    (is (nil? (get-in result [:operations :check-permission :target])))
    (is (nil? (get-in result [:operations :lookup-subjects :target])))
    (is (= {:warmed-default-page-p95-ms 250.0 :met? true}
           (get-in result [:operations :lookup-resources :target])))))
