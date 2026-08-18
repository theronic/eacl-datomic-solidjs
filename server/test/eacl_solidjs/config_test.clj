(ns eacl-solidjs.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl-solidjs.config :as config]))

(def valid-env {"EACL_SOLIDJS_SECURITY_KEY" "stable-secret"})

(deftest environment-parsing-and-validation
  (testing "development defaults are LAN reachable and durable"
    (is (= "0.0.0.0" (:host config/default-config)))
    (is (= 8088 (:port config/default-config)))
    (is (= "datomic:dev://localhost:4334/eacl-solidjs"
           (:datomic-uri config/default-config)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"required for durable"
         (config/from-env {})))
    (is (= "stable-secret" (:security-key (config/from-env valid-env)))))
  (testing "numeric overrides are parsed"
    (is (= 8099 (:port (config/from-env
                        (assoc valid-env "EACL_SOLIDJS_PORT" "8099")))))
    (is (= 2048 (:max-body-bytes
                 (config/from-env
                  (assoc valid-env "EACL_SOLIDJS_MAX_BODY_BYTES" "2048")))))
    (is (= 250000 (:max-count-limit
                   (config/from-env
                    (assoc valid-env
                           "EACL_SOLIDJS_MAX_COUNT_LIMIT" "250000")))))
    (is (= 8 (:max-eacl-concurrency
              (config/from-env
               (assoc valid-env
                      "EACL_SOLIDJS_MAX_EACL_CONCURRENCY" "8")))))
    (is (= 24 (:jetty-max-threads
               (config/from-env
                (assoc valid-env
                       "EACL_SOLIDJS_JETTY_MAX_THREADS" "24"))))))
  (testing "invalid numbers fail fast with a field"
    (let [error (try
                  (config/from-env
                   (assoc valid-env "EACL_SOLIDJS_PORT" "not-a-port"))
                  nil
                  (catch Exception ex ex))]
      (is (= :port (:field (ex-data error))))))
  (testing "Jetty minimum threads cannot exceed the maximum"
    (let [error (try
                  (config/from-env
                   valid-env
                   {:jetty-min-threads 17 :jetty-max-threads 16})
                  nil
                  (catch Exception ex ex))]
      (is (= :jetty-min-threads (:field (ex-data error))))))
  (testing "durable databases require a stable security key"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"required for durable"
         (config/from-env {"EACL_SOLIDJS_DATOMIC_URI" "datomic:dev://localhost/demo"})))
    (is (= "stable-secret"
           (:security-key
            (config/from-env
             {"EACL_SOLIDJS_DATOMIC_URI" "datomic:dev://localhost/demo"
              "EACL_SOLIDJS_SECURITY_KEY" "stable-secret"}))))))
