(ns eacl-datahike-demo.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.core :as eacl]
            [eacl-datahike-demo.data :as data]
            [eacl-datahike-demo.test-support :as support]))

(deftest bootstrap-uses-maintained-server-total
  (support/with-test-system [system]
    (let [handler (:handler system)
          response
          (with-redefs [data/count-servers
                        (fn [_]
                          (throw (ex-info "bootstrap performed a full scan" {})))]
            (support/request handler :get "/api/bootstrap"))]
      (is (= 200 (:status response)))
      (is (= {:servers 48 :accounts 4 :teams 8 :vpcs 4 :users 19}
             (:totals (support/data response)))))))

(deftest transient-datahike-index-read-is-retried
  (support/with-test-system [system]
    (let [attempts (atom 0)
          totals data/totals
          response
          (with-redefs [data/totals
                        (fn [db server-count]
                          (if (= 1 (swap! attempts inc))
                            (throw (ex-info "transient index child"
                                            {:type :node-not-found}))
                            (totals db server-count)))]
            (support/request (:handler system) :get "/api/bootstrap"))]
      (is (= 200 (:status response)))
      (is (= 2 @attempts)))))

(deftest subject-pages-use-the-maintained-deterministic-fixture-plan
  (support/with-test-system [system]
    (let [known-subjects data/known-subjects
          database-arguments (atom [])
          response
          (with-redefs [data/known-subjects
                        (fn [db & args]
                          (swap! database-arguments conj db)
                          (apply known-subjects db args))]
            (support/request (:handler system) :get
                             "/api/subjects?offset=0&limit=10"))]
      (is (= 200 (:status response)))
      (is (= [nil] @database-arguments))
      (is (= 19 (get-in (support/data response) [:pageInfo :total]))))))

(deftest deterministic-seed-totals-and-subject-pages
  (is (= {:servers 550034 :accounts 139 :teams 548 :vpcs 274 :users 964}
         (data/totals nil 550034)))
  (let [page (data/known-subjects 550034 0 10)]
    (is (= 964 (get-in page [:page-info :total])))
    (is (= 10 (count (:data page))))
    (is (true? (get-in page [:page-info :has-next-page?])))))

(deftest million-seed-plan-is-exact-weighted-and-reproducible
  (let [target (- 1000000 48)
        plan (data/requested-account-plan 4 target)
        repeated (data/requested-account-plan 4 target)
        sizes (mapv :server-count plan)
        mean (/ (double (reduce + sizes)) (count sizes))]
    (is (= plan repeated))
    (is (= target (reduce + sizes)))
    (is (every? #(<= 1 % 50000) sizes))
    (is (< 4000 mean 6000))
    (is (> (/ (double (count (filter #(<= % 7500) sizes)))
              (count sizes))
           0.75))
    (is (some #(> % 20000) sizes))))

(deftest eacl-requests-honor-a-cancelled-http-token
  (support/with-test-system [system]
    (let [token (eacl/cancellation-token)
          _ (eacl/cancel! token)
          handler (fn [request]
                    ((:handler system)
                     (assoc request :eacl/cancellation-token token)))
          response
          (support/request handler :post "/api/eacl/lookup-resources"
                           (assoc support/lookup-resources-body :cache false))]
      (is (= 499 (:status response)))
      (is (= "request-cancelled"
             (get-in (support/response-body response) [:error :code])))
      (is (= 4 (.availablePermits (:eacl-permits system)))))))

(deftest interrupted-storage-read-after-http-cancel-is-not-a-500
  (support/with-test-system [system]
    (let [token (eacl/cancellation-token)
          _ (eacl/cancel! token)
          handler (fn [request]
                    ((:handler system)
                     (assoc request :eacl/cancellation-token token)))
          response
          (with-redefs [eacl/lookup-resources
                        (fn [_ _]
                          (throw (ex-info "SDK request aborted" {})))]
            (support/request handler :post "/api/eacl/lookup-resources"
                             (assoc support/lookup-resources-body
                                    :cache false)))]
      (is (= 499 (:status response)))
      (is (= "request-cancelled"
             (get-in (support/response-body response) [:error :code])))
      (is (= 4 (.availablePermits (:eacl-permits system)))))))

(deftest common-routes-and-methods
  (support/with-test-system [system]
    (let [handler (:handler system)]
      (doseq [uri ["/api/health" "/api/bootstrap" "/api/subjects?offset=0&limit=10"
                   "/api/schema" "/api/cache" "/api/seed"]]
        (is (= 200 (:status (support/request handler :get uri))) uri))
      (is (= 404 (:status (support/request handler :get "/api/missing"))))
      (is (= "api-not-found"
             (get-in (support/response-body
                      (support/request handler :get "/api/missing"))
                     [:error :code])))
      (is (= 405 (:status (support/request handler :post "/api/health" {}))))
      (is (= 200 (:status (support/request handler :get "/any/client/route")))))))

(deftest cache-report-has-no-prewarm-section
  (support/with-test-system [system]
    (let [body (support/data
                (support/request (:handler system) :get "/api/cache"))]
      (is (not (contains? body :prewarm))
          "the boot-time prewarm is gone; the report must not advertise it"))))

(deftest content-type-body-and-field-errors
  (support/with-test-system [system]
    (let [handler (:handler system)
          no-type (support/request handler :post "/api/eacl/lookup-resources"
                                   nil {"content-type" "text/plain"})
          bad-page (support/request handler :post "/api/eacl/lookup-resources"
                                    (assoc support/lookup-resources-body :pageSize 11))
          unknown-permission
          (support/request handler :post "/api/eacl/lookup-resources"
                           (assoc support/lookup-resources-body
                                  :permission "delete"))]
      (is (= 415 (:status no-type)))
      (is (= "unsupported-media-type"
             (get-in (support/response-body no-type) [:error :code])))
      (is (= 400 (:status bad-page)))
      (is (= "invalid-page-size"
             (get-in (support/response-body bad-page) [:error :code])))
      (is (= 400 (:status unknown-permission)))
      (is (= "unknown-schema-permission"
             (get-in (support/response-body unknown-permission) [:error :code]))))))

(deftest count-limit-validation-rejects-before-eacl
  (support/with-test-system [system]
    (let [handler (:handler system)
          base-body (assoc (dissoc support/lookup-resources-body :pageSize)
                           :cache false)
          metrics-before @(:!metrics system)]
      (doseq [body [(dissoc base-body :countLimit)
                    (assoc base-body :countLimit 0)
                    (assoc base-body :countLimit -1)
                    (assoc base-body :countLimit 1.5)
                    (assoc base-body :countLimit 1000001)]]
        (let [response (support/request handler :post
                                        "/api/eacl/count-resources"
                                        body)]
          (is (= 400 (:status response)))
          (is (= "invalid-count-limit"
                 (get-in (support/response-body response) [:error :code])))))
      (is (= metrics-before @(:!metrics system))))))

(deftest production-admin-token-and-public-bounds
  (let [token "0123456789abcdef0123456789abcdef"
        system (support/build-test-system {:admin-token token})
        handler (:handler system)]
    (try
      (testing "administrative mutations require the exact bearer token"
        (is (= 401 (:status (support/request handler :post "/api/cache/evict" {}))))
        (is (= 401 (:status
                    (support/request handler :post "/api/cache/evict" {}
                                     {"authorization" "Bearer wrong"}))))
        (is (= 200 (:status
                    (support/request handler :post "/api/cache/evict" {}
                                     {"authorization" (str "Bearer " token)})))))
      (testing "oversized bodies are rejected before JSON allocation"
        (is (= 413 (:status
                    (support/request handler :put "/api/schema"
                                     {:source (apply str (repeat 70000 "x"))}
                                     {"authorization" (str "Bearer " token)})))))
      (testing "authorization saturation returns a bounded response"
        (let [permits (:eacl-permits system)
              acquired (.drainPermits permits)]
          (try
            (let [response (support/request
                            handler :post "/api/eacl/lookup-resources"
                            support/lookup-resources-body)]
              (is (= 503 (:status response)))
              (is (= "server-busy"
                     (get-in (support/response-body response) [:error :code]))))
            (finally
              (.release permits acquired)))))
      (finally
        (support/close-test-system! system)))))
