(ns eacl-datahike-demo.integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike-eacl]
            [eacl-datahike-demo.data :as data]
            [eacl-datahike-demo.system :as system]
            [eacl-datahike-demo.test-support :as support]))

(deftest removed-coherence-authority-is-deliberately-invalid
  (support/with-test-system [system]
    (let [error (try
                  (datahike-eacl/make-client
                   (:conn system)
                   {:coherence-authority :application})
                  nil
                  (catch clojure.lang.ExceptionInfo exception exception))]
      (is (some? error))
      (is (= :eacl/invalid-config (:type (ex-data error)))))))

(deftest real-eacl-query-endpoints
  (support/with-test-system [system]
    (let [handler (:handler system)
          first-page (support/request handler :post "/api/eacl/lookup-resources"
                                      support/lookup-resources-body)
          first-data (support/data first-page)
          cursor (get-in first-data [:pageInfo :endCursor])
          second-page
          (support/request handler :post "/api/eacl/lookup-resources"
                           (assoc support/lookup-resources-body :after cursor))
          count-response
          (support/request handler :post "/api/eacl/count-resources"
                           (assoc (dissoc support/lookup-resources-body :pageSize)
                                  :countLimit 50000))
          subjects
          (support/request handler :post "/api/eacl/lookup-subjects"
                           {:resource support/server-0
                            :permission "view"
                            :subjectType "user"
                            :pageSize 10
                            :cache true})
          relationships
          (support/request handler :post "/api/eacl/read-relationships"
                           {:subject support/account-0
                            :resourceType "server"
                            :relation "account"
                            :authorizationSubject support/super-user
                            :permission "view"
                            :pageSize 10
                            :cache true})
          check
          (support/request handler :post "/api/eacl/check-permission"
                           {:subject support/user-1
                            :resource support/account-0
                            :permission "admin"
                            :cache true})
          platforms
          (support/request handler :post "/api/eacl/lookup-resources"
                           (assoc support/lookup-resources-body
                                  :resourceType "platform"))]
      (is (= 200 (:status first-page)))
      (is (= 10 (count (:items first-data))))
      (is (not (re-find #"displayName" (:body first-page))))
      (is (true? (get-in first-data [:pageInfo :hasNextPage])))
      (is (= 200 (:status second-page)))
      (is (not= (mapv :id (:items first-data))
                (mapv :id (:items (support/data second-page)))))
      (is (= 48 (get-in (support/data count-response) [:count])))
      (is (= 50000 (get-in (support/data count-response) [:limit])))
      (is (false? (get-in (support/data count-response) [:truncated])))
      (is (= 200 (:status subjects)))
      (is (seq (:items (support/data subjects))))
      (is (not (re-find #"displayName" (:body subjects))))
      (is (= 200 (:status relationships)))
      (is (= 10 (count (:items (support/data relationships)))))
      (is (= "disabled"
             (get-in (support/meta-data relationships) [:cacheStatus])))
      (is (not (re-find #"displayName" (:body relationships))))
      (is (= true (get-in (support/data check) [:allowed])))
      (is (= 200 (:status platforms)))
      (is (= [{:type "platform" :id "platform"}]
             (:items (support/data platforms)))))))

(deftest nested-permission-filter-bypasses-complete-answer-cache
  (support/with-test-system [system]
    (let [queries (atom [])
          handler (:handler system)
          response
          (with-redefs [eacl/check-permission
                        (fn [_ query]
                          (swap! queries conj query)
                          {:allowed? true :cached? false})]
            (support/request
             handler :post "/api/eacl/read-relationships"
             {:subject support/account-0
              :resourceType "server"
              :relation "account"
              :authorizationSubject support/super-user
              :permission "view"
              :pageSize 10
              :cache true}))]
      (is (= 200 (:status response)))
      (is (= 10 (count @queries)))
      (is (every? #(false? (:cache? %)) @queries))
      (is (= "disabled"
             (get-in (support/meta-data response) [:cacheStatus]))))))

(deftest canonical-cache-prewarm-is-bounded-and-reusable
  (support/with-test-system [system]
    (let [token (eacl/cancellation-token)
          warmed (system/prewarm-cache! system token)
          reused (eacl/lookup-resources
                  (:acl system)
                  {:subject (data/->object :user "user-1")
                   :permission :view
                   :resource/type :server
                   :first 20
                   :cache? true})
          stats (datahike-eacl/cache-stats (:acl system))]
      (is (= :complete (:status warmed)))
      (is (= 20 (:storage-prime-items warmed)))
      (is (= 20 (:page-items warmed)))
      (is (true? (:cached? reused)))
      (is (= 1 (:exact-entries stats)))
      (is (zero? (get-in stats [:subproblems :oversized-rejections]))))))

(deftest seed-submission-window-is-bounded-and-concurrent
  (let [active (atom 0)
        peak (atom 0)
        completed (atom [])
        submit! (fn [item]
                  (let [now (swap! active inc)]
                    (swap! peak max now)
                    (Thread/sleep 20)
                    (swap! completed conj item)
                    (swap! active dec)))
        submit-windows! @#'data/submit-windows!]
    (submit-windows! (range 10) 4 submit! 0)
    (is (= 4 @peak))
    (is (= (set (range 10)) (set @completed)))
    (is (zero? @active))))

(deftest asynchronous-seed-keeps-eacl-queries-available
  (support/with-test-system [system]
    (let [handler (:handler system)
          before (get-in (support/data
                          (support/request handler :get "/api/bootstrap"))
                         [:totals :servers])
          accepted (support/request handler :post "/api/seed" {:serverCount 2001})
          query-while-seeding
          (support/request handler :post "/api/eacl/lookup-resources"
                           support/lookup-resources-body)
          completed
          (loop [attempt 0]
            (let [response (support/request handler :get "/api/seed")
                  status (:status (support/data response))]
              (if (or (= :ready status) (= "ready" status) (>= attempt 400))
                response
                (do
                  (Thread/sleep 25)
                  (recur (inc attempt))))))]
      (is (= 202 (:status accepted)))
      (is (= "seeding" (:status (support/data accepted))))
      (is (= 200 (:status query-while-seeding)))
      (is (= "ready" (:status (support/data completed))))
      (is (= (+ before 2001) (:totalServers (support/data completed))))
      (is (not= (:revision (support/meta-data accepted))
                (:revision (support/meta-data completed)))))))

(deftest cursor-mismatch-recovers-through-stable-conflict
  (support/with-test-system [system]
    (let [handler (:handler system)
          first-page (support/request handler :post "/api/eacl/lookup-resources"
                                      support/lookup-resources-body)
          cursor (get-in (support/data first-page) [:pageInfo :endCursor])
          mismatch
          (support/request handler :post "/api/eacl/lookup-resources"
                           (assoc support/lookup-resources-body
                                  :subject support/user-1
                                  :after cursor))
          recovery (support/request handler :post "/api/eacl/lookup-resources"
                                    (assoc support/lookup-resources-body
                                           :subject support/user-1))]
      (is (= 409 (:status mismatch)))
      (is (= "invalid-cursor"
             (get-in (support/response-body mismatch) [:error :code])))
      (is (= 200 (:status recovery))))))

(deftest schema-cache-and-seed-mutations
  (support/with-test-system [system]
    (let [handler (:handler system)
          original (support/request handler :get "/api/schema")
          original-data (support/data original)
          invalid (support/request handler :put "/api/schema"
                                   {:source "definition broken {"})
          after-invalid (support/request handler :get "/api/schema")]
      (testing "failed schema writes preserve the committed schema"
        (is (= 422 (:status invalid)))
        (is (= (:source original-data) (:source (support/data after-invalid)))))
      (testing "successful writes advance the data revision"
        (let [written (support/request handler :put "/api/schema"
                                       {:source data/recursive-schema})]
          (is (= 200 (:status written)))
          (is (not= (get-in (support/meta-data original) [:revision])
                    (get-in (support/meta-data written) [:revision])))
          (is (= data/recursive-schema (:source (support/data written))))
          (is (= 200 (:status (support/request handler :put "/api/schema"
                                              {:source data/default-schema}))))))
      (testing "cache queries report provenance and eviction advances generation"
        (let [query-1 (support/request handler :post "/api/eacl/check-permission"
                                       {:subject support/super-user
                                        :resource support/server-0
                                        :permission "view"
                                        :cache true})
              query-2 (support/request handler :post "/api/eacl/check-permission"
                                       {:subject support/super-user
                                        :resource support/server-0
                                        :permission "view"
                                        :cache true})
              cache-read (support/request handler :get "/api/cache")
              before (get-in (support/meta-data query-2) [:revision])
              evicted (support/request handler :post "/api/cache/evict" {})]
          (is (= "miss" (get-in (support/meta-data query-1) [:cacheStatus])))
          (is (= "hit" (get-in (support/meta-data query-2) [:cacheStatus])))
          (is (= 200 (:status cache-read)))
          (is (= {:miss 1 :hit 1}
                 (get-in (support/data cache-read)
                         [:operations :checkPermission :cacheStatus])))
          (is (not= before (get-in (support/meta-data evicted) [:revision])))))
      (testing "overlapping seed work is rejected without leaking executor wrappers"
        (reset! (:!seed-running? system) true)
        (try
          (let [busy (support/request handler :post "/api/seed" {:serverCount 1})]
            (is (= 409 (:status busy)))
            (is (= "seed-busy"
                   (get-in (support/response-body busy) [:error :code]))))
          (finally
            (reset! (:!seed-running? system) false)))))))
