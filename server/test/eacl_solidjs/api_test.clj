(ns eacl-solidjs.api-test
  (:require [clojure.test :refer [deftest is]]
            [eacl-solidjs.test-support :as support]))

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
