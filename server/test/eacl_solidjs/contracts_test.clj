(ns eacl-solidjs.contracts-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl-solidjs.contracts :as contracts]
            [eacl-solidjs.runtime :as runtime]
            [eacl-solidjs.test-support :as support])
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
      (is (re-matches #"d\d+\.c0" (get-in success [:meta :revision])))
      (is (= "request-1" (get-in success [:meta :requestId])))
      (is (= "internal-error" (get-in internal [:error :code])))
      (is (not (re-find #"secret" (pr-str internal))))
      (is (= "d" (subs (runtime/revision system) 0 1))))))
