(ns eacl-solidjs.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.core :as eacl]
            [eacl-solidjs.test-support :as support]))

(deftest eacl-requests-honor-a-cancelled-request-token
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
      (is (= (get-in system [:config :max-eacl-concurrency])
             (.availablePermits (:eacl-permits system)))))))

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
                    (assoc base-body :countLimit
                           (inc (get-in system [:config :max-count-limit])))]]
        (let [response (support/request handler :post
                                        "/api/eacl/count-resources"
                                        body)]
          (is (= 400 (:status response)))
          (is (= "invalid-count-limit"
                 (get-in (support/response-body response) [:error :code])))))
      (is (= metrics-before @(:!metrics system))))))

(deftest authorization-saturation-fails-fast
  (support/with-test-system [system]
    (let [permits (:eacl-permits system)
          acquired (.drainPermits permits)]
      (try
        (let [response
              (support/request (:handler system) :post
                               "/api/eacl/lookup-resources"
                               support/lookup-resources-body)]
          (is (= 503 (:status response)))
          (is (= "server-busy"
                 (get-in (support/response-body response) [:error :code]))))
        (finally
          (.release permits acquired))))))
