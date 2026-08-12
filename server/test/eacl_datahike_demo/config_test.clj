(ns eacl-datahike-demo.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl-datahike-demo.config :as config]
            [eacl-datahike-demo.system :as system]))

(deftest environment-parsing-and-validation
  (testing "development defaults are loopback-only and use adapter memory storage"
    (is (= "127.0.0.1" (:host config/default-config)))
    (is (= 8088 (:port config/default-config)))
    (is (= :memory (:store-backend config/default-config)))
    (is (nil? (config/datahike-config config/default-config)))
    (is (= 65536 (:max-body-bytes config/default-config)))
    (is (= 1000000 (:max-seed-servers config/default-config)))
    (is (= 250 (:seed-transaction-size config/default-config)))
    (is (= 50 (:seed-pause-ms config/default-config)))
    (is (= 512 (:cache-max-entries config/default-config)))
    (is (= (* 16 1024 1024)
           (:cache-answer-max-weight config/default-config))))
  (testing "numeric overrides are parsed"
    (is (= 8099 (:port (config/from-env
                        {"EACL_DATAHIKE_DEMO_PORT" "8099"}))))
    (is (= 2048 (:max-body-bytes
                 (config/from-env
                  {"EACL_DATAHIKE_DEMO_MAX_BODY_BYTES" "2048"}))))
    (is (= 0 (:seed-pause-ms
              (config/from-env
               {"EACL_DATAHIKE_DEMO_SEED_PAUSE_MS" "0"}))))
    (is (= 1024 (:cache-max-entries
                 (config/from-env
                  {"EACL_DATAHIKE_DEMO_CACHE_MAX_ENTRIES" "1024"}))))
    (is (= 33554432 (:cache-answer-max-weight
                     (config/from-env
                      {"EACL_DATAHIKE_DEMO_CACHE_ANSWER_MAX_WEIGHT"
                       "33554432"})))))
  (testing "invalid numbers fail fast with a field"
    (let [error (try
                  (config/from-env
                   {"EACL_DATAHIKE_DEMO_PORT" "not-a-port"})
                  nil
                  (catch Exception ex ex))]
      (is (= :port (:field (ex-data error))))))
  (testing "durable S3 configuration is stable and strict"
    (let [store-id (random-uuid)
          env {"EACL_DATAHIKE_DEMO_MODE" "production"
               "EACL_DATAHIKE_DEMO_STORE_BACKEND" "s3"
               "EACL_DATAHIKE_DEMO_STORE_ID" (str store-id)
               "EACL_DATAHIKE_DEMO_S3_BUCKET" "demo-bucket"
               "EACL_DATAHIKE_DEMO_S3_REGION" "us-east-1"
               "EACL_DATAHIKE_DEMO_SECURITY_KEY"
               "0123456789abcdef0123456789abcdef"
               "EACL_DATAHIKE_DEMO_ADMIN_TOKEN"
               "0123456789abcdef0123456789abcdef"}
          parsed (config/from-env env)
          database-config (config/datahike-config parsed)]
      (is (= :production (:mode parsed)))
      (is (= :s3 (get-in database-config [:store :backend])))
      (is (= store-id (get-in database-config [:store :id])))
      (is (= true (:attribute-refs? database-config)))
      (is (= false (:keep-history? database-config)))
      (is (= false (:commit-graph? database-config)))))
  (testing "a local S3-compatible endpoint is parsed without custom credential variables"
    (let [store-id (random-uuid)
          parsed (config/from-env
                  {"EACL_DATAHIKE_DEMO_STORE_BACKEND" "s3"
                   "EACL_DATAHIKE_DEMO_STORE_ID" (str store-id)
                   "EACL_DATAHIKE_DEMO_S3_BUCKET" "local-datahike"
                   "EACL_DATAHIKE_DEMO_S3_REGION" "us-east-1"
                   "EACL_DATAHIKE_DEMO_S3_ENDPOINT" "http://127.0.0.1:19000"
                   "EACL_DATAHIKE_DEMO_S3_PATH_STYLE_ACCESS" "true"})
          store (:store (config/datahike-config parsed))]
      (is (= {:protocol :http :hostname "127.0.0.1" :port 19000}
             (:endpoint-override store)))
      (is (true? (:path-style-access? store)))
      (is (nil? (:access-key store)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"origin without credentials"
           (config/from-env
            {"EACL_DATAHIKE_DEMO_STORE_BACKEND" "s3"
             "EACL_DATAHIKE_DEMO_STORE_ID" (str store-id)
             "EACL_DATAHIKE_DEMO_S3_BUCKET" "local-datahike"
             "EACL_DATAHIKE_DEMO_S3_ENDPOINT" "http://user:pass@127.0.0.1:19000/path"})))))
  (testing "EACL v8 cache settings use the supported weighted cache surface"
    (is (= {:max-entries 512
            :admit-on-repeat? false
            :subproblem-cache
            {:projection-max-weight (* 4 1024 1024)
             :denotation-max-weight (* 4 1024 1024)
             :answer-max-weight (* 16 1024 1024)
             :managed-proof-max-atoms 256}}
           (:cache (system/client-options config/default-config)))))
  (testing "production requires stable application secrets"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"security-key"
         (config/from-env {"EACL_DATAHIKE_DEMO_MODE" "production"})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"security-key"
         (config/from-env
          {"EACL_DATAHIKE_DEMO_MODE" "production"
           "EACL_DATAHIKE_DEMO_SECURITY_KEY" "too-short"})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"admin-token"
         (config/from-env
          {"EACL_DATAHIKE_DEMO_MODE" "production"
           "EACL_DATAHIKE_DEMO_SECURITY_KEY"
           "0123456789abcdef0123456789abcdef"})))))

(deftest bounded-jetty-admission-options
  (let [options (system/jetty-options config/default-config)]
    (is (= 2 (:min-threads options)))
    (is (= 16 (:max-threads options)))
    (is (= 64 (:max-queued-requests options)))
    (is (true? (:async? options)))
    (is (= 30000 (:async-timeout options)))
    (is (= 30000 (:max-idle-time options)))
    (is (fn? (:configurator options)))
    (is (false? (:join? options)))))
