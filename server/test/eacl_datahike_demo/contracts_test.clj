(ns eacl-datahike-demo.contracts-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl-datahike-demo.contracts :as contracts]
            [eacl-datahike-demo.runtime :as runtime]
            [eacl-datahike-demo.test-support :as support])
  (:import [java.time Instant]
           [java.util UUID]))

(deftest json-safe-domain-conversion
  (let [uuid (UUID/randomUUID)
        instant (Instant/parse "2026-08-08T10:00:00Z")]
    (is (= {"cacheStatus" "hit"
            "hasNextPage" true
            "values" ["a" "b"]
            "uuid" (str uuid)
            "capturedAt" (str instant)}
           (contracts/json-safe
            {:cache-status :hit
             :has-next-page? true
             :values #{:b :a}
             :uuid uuid
             :captured-at instant})))))

(deftest shared-boundary-validation
  (testing "page sizes and cache mode are bounded"
    (is (= 20 (contracts/page-size {})))
    (is (= 1000 (contracts/page-size {:pageSize 1000})))
    (is (= "invalid-page-size"
           (:error/code
            (ex-data
             (try (contracts/page-size {:pageSize 11})
                  (catch Exception ex ex))))))
    (is (= "invalid-cache-mode"
           (:error/code
            (ex-data
             (try (contracts/cache-enabled? {:cache "yes"})
                  (catch Exception ex ex))))))))

(deftest envelopes-revisions-and-sanitization
  (support/with-test-system [system]
    (let [request {:request-id "request-1"}
          success (support/response-body
                   (contracts/success system request {:value :ok}))
          internal (support/response-body
                    (contracts/exception->response
                     system request (ex-info "secret database detail" {})))]
      (is (= "ok" (get-in success [:data :value])))
      (is (re-matches #"h[^.]+\.c0" (get-in success [:meta :revision])))
      (is (= "request-1" (get-in success [:meta :requestId])))
      (is (= "internal-error" (get-in internal [:error :code])))
      (is (not (re-find #"secret" (pr-str internal))))
      (is (= "h" (subs (runtime/revision system) 0 1))))))

(deftest execution-timeout-is-a-stable-gateway-timeout
  (support/with-test-system [system]
    (let [response
          (contracts/exception->response
           system {:request-id "timeout-request"}
           (ex-info "internal deadline detail"
                    {:type :eacl.execution/deadline-exceeded}))]
      (is (= 504 (:status response)))
      (is (= "execution-timeout"
             (get-in (support/response-body response) [:error :code])))
      (is (not (re-find #"internal" (:body response)))))))

(deftest relay-cursor-failures-have-an-actionable-public-message
  (support/with-test-system [system]
    (let [response
          (contracts/exception->response
           system {:request-id "cursor-request"}
           (ex-info "Invalid Relay cursor."
                    {:type :eacl.pagination/invalid-cursor
                     :reason :authentication-failed}))
          body (support/response-body response)]
      (is (= 409 (:status response)))
      (is (= "invalid-cursor" (get-in body [:error :code])))
      (is (= (str "This page cursor is no longer valid for the current "
                  "query or deployment. Start again from the first page.")
             (get-in body [:error :message])))
      (is (not (re-find #"Relay" (:body response)))))))

(deftest storage-failures-never-cross-the-api-boundary
  (support/with-test-system [system]
    (let [secret "AKIA-DO-NOT-DISCLOSE"
          raw-config {:store {:backend :s3
                              :bucket "private-bucket"
                              :secret-access-key secret}}
          response
          (contracts/exception->response
           system {:request-id "storage-request"}
           (ex-info (str "S3 request failed for " secret)
                    {:type :datahike.store/unavailable
                     :raw-storage-config raw-config}))
          body (support/response-body response)]
      (is (= 500 (:status response)))
      (is (= "internal-error" (get-in body [:error :code])))
      (is (= "The server could not complete the request."
             (get-in body [:error :message])))
      (is (not (re-find #"AKIA|private-bucket|raw-storage" (:body response)))))))
