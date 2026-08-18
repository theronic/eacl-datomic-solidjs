(ns eacl-solidjs.config
  "Environment-backed server configuration with fail-fast validation."
  (:require [clojure.string :as str]))

(def default-config
  {:host "0.0.0.0"
   :port 8088
   :datomic-uri "datomic:dev://localhost:4334/eacl-solidjs"
   :request-timeout-ms 30000
   :max-body-bytes 1048576
   :max-seed-servers 100000
   :max-count-limit 1000000
   :max-eacl-concurrency 4
   :cache-max-entries 512
   :cache-projection-max-weight (* 4 1024 1024)
   :cache-denotation-max-weight (* 4 1024 1024)
   :cache-answer-max-weight (* 16 1024 1024)
   :cache-managed-proof-max-atoms 256
   :jetty-min-threads 2
   :jetty-max-threads 16
   :jetty-max-queued-requests 64
   :security-key nil})

(defn- parse-positive-long
  [field value]
  (try
    (let [n (Long/parseLong (str value))]
      (when-not (pos? n)
        (throw (ex-info (str (name field) " must be positive.")
                        {:type :eacl-solidjs.config/invalid
                         :field field})))
      n)
    (catch NumberFormatException _
      (throw (ex-info (str (name field) " must be a whole number.")
                      {:type :eacl-solidjs.config/invalid
                       :field field})))))

(defn memory-uri?
  [uri]
  (str/starts-with? uri "datomic:mem://"))

(defn validate
  [{:keys [host port datomic-uri request-timeout-ms max-body-bytes
           max-seed-servers max-count-limit max-eacl-concurrency
           cache-max-entries cache-projection-max-weight
           cache-denotation-max-weight cache-answer-max-weight
           cache-managed-proof-max-atoms jetty-min-threads
           jetty-max-threads jetty-max-queued-requests security-key]
    :as config}]
  (when (str/blank? host)
    (throw (ex-info "host must not be blank."
                    {:type :eacl-solidjs.config/invalid :field :host})))
  (when-not (and (integer? port) (<= 1 port 65535))
    (throw (ex-info "port must be between 1 and 65535."
                    {:type :eacl-solidjs.config/invalid :field :port})))
  (when (str/blank? datomic-uri)
    (throw (ex-info "datomic-uri must not be blank."
                    {:type :eacl-solidjs.config/invalid :field :datomic-uri})))
  (doseq [[field value] [[:request-timeout-ms request-timeout-ms]
                         [:max-body-bytes max-body-bytes]
                         [:max-seed-servers max-seed-servers]
                         [:max-count-limit max-count-limit]
                         [:max-eacl-concurrency max-eacl-concurrency]
                         [:cache-max-entries cache-max-entries]
                         [:cache-projection-max-weight
                          cache-projection-max-weight]
                         [:cache-denotation-max-weight
                          cache-denotation-max-weight]
                         [:cache-answer-max-weight cache-answer-max-weight]
                         [:cache-managed-proof-max-atoms
                          cache-managed-proof-max-atoms]
                         [:jetty-min-threads jetty-min-threads]
                         [:jetty-max-threads jetty-max-threads]
                         [:jetty-max-queued-requests
                          jetty-max-queued-requests]]]
    (when-not (and (integer? value) (pos? value))
      (throw (ex-info (str (name field) " must be positive.")
                      {:type :eacl-solidjs.config/invalid :field field}))))
  (when (> jetty-min-threads jetty-max-threads)
    (throw (ex-info
            "jetty-min-threads must not exceed jetty-max-threads."
            {:type :eacl-solidjs.config/invalid
             :field :jetty-min-threads})))
  (when (and (not (memory-uri? datomic-uri))
             (str/blank? security-key))
    (throw (ex-info
            "EACL_SOLIDJS_SECURITY_KEY is required for durable Datomic URIs."
            {:type :eacl-solidjs.config/invalid
             :field :security-key})))
  config)

(defn from-env
  ([] (from-env (System/getenv) {}))
  ([env] (from-env env {}))
  ([env overrides]
   (validate
    (merge
     (cond-> default-config
       (get env "EACL_SOLIDJS_HOST")
       (assoc :host (get env "EACL_SOLIDJS_HOST"))

       (get env "EACL_SOLIDJS_PORT")
       (assoc :port (parse-positive-long
                     :port (get env "EACL_SOLIDJS_PORT")))

       (get env "EACL_SOLIDJS_DATOMIC_URI")
       (assoc :datomic-uri (get env "EACL_SOLIDJS_DATOMIC_URI"))

       (get env "EACL_SOLIDJS_REQUEST_TIMEOUT_MS")
       (assoc :request-timeout-ms
              (parse-positive-long
               :request-timeout-ms
               (get env "EACL_SOLIDJS_REQUEST_TIMEOUT_MS")))

       (get env "EACL_SOLIDJS_MAX_BODY_BYTES")
       (assoc :max-body-bytes
              (parse-positive-long
               :max-body-bytes
               (get env "EACL_SOLIDJS_MAX_BODY_BYTES")))

       (get env "EACL_SOLIDJS_MAX_SEED_SERVERS")
       (assoc :max-seed-servers
              (parse-positive-long
               :max-seed-servers
               (get env "EACL_SOLIDJS_MAX_SEED_SERVERS")))

       (get env "EACL_SOLIDJS_MAX_COUNT_LIMIT")
       (assoc :max-count-limit
              (parse-positive-long
               :max-count-limit
               (get env "EACL_SOLIDJS_MAX_COUNT_LIMIT")))

       (get env "EACL_SOLIDJS_MAX_EACL_CONCURRENCY")
       (assoc :max-eacl-concurrency
              (parse-positive-long
               :max-eacl-concurrency
               (get env "EACL_SOLIDJS_MAX_EACL_CONCURRENCY")))

       (get env "EACL_SOLIDJS_CACHE_MAX_ENTRIES")
       (assoc :cache-max-entries
              (parse-positive-long
               :cache-max-entries
               (get env "EACL_SOLIDJS_CACHE_MAX_ENTRIES")))

       (get env "EACL_SOLIDJS_CACHE_PROJECTION_MAX_WEIGHT")
       (assoc :cache-projection-max-weight
              (parse-positive-long
               :cache-projection-max-weight
               (get env "EACL_SOLIDJS_CACHE_PROJECTION_MAX_WEIGHT")))

       (get env "EACL_SOLIDJS_CACHE_DENOTATION_MAX_WEIGHT")
       (assoc :cache-denotation-max-weight
              (parse-positive-long
               :cache-denotation-max-weight
               (get env "EACL_SOLIDJS_CACHE_DENOTATION_MAX_WEIGHT")))

       (get env "EACL_SOLIDJS_CACHE_ANSWER_MAX_WEIGHT")
       (assoc :cache-answer-max-weight
              (parse-positive-long
               :cache-answer-max-weight
               (get env "EACL_SOLIDJS_CACHE_ANSWER_MAX_WEIGHT")))

       (get env "EACL_SOLIDJS_CACHE_MANAGED_PROOF_MAX_ATOMS")
       (assoc :cache-managed-proof-max-atoms
              (parse-positive-long
               :cache-managed-proof-max-atoms
               (get env "EACL_SOLIDJS_CACHE_MANAGED_PROOF_MAX_ATOMS")))

       (get env "EACL_SOLIDJS_JETTY_MIN_THREADS")
       (assoc :jetty-min-threads
              (parse-positive-long
               :jetty-min-threads
               (get env "EACL_SOLIDJS_JETTY_MIN_THREADS")))

       (get env "EACL_SOLIDJS_JETTY_MAX_THREADS")
       (assoc :jetty-max-threads
              (parse-positive-long
               :jetty-max-threads
               (get env "EACL_SOLIDJS_JETTY_MAX_THREADS")))

       (get env "EACL_SOLIDJS_JETTY_MAX_QUEUED_REQUESTS")
       (assoc :jetty-max-queued-requests
              (parse-positive-long
               :jetty-max-queued-requests
               (get env "EACL_SOLIDJS_JETTY_MAX_QUEUED_REQUESTS")))

       (get env "EACL_SOLIDJS_SECURITY_KEY")
       (assoc :security-key (get env "EACL_SOLIDJS_SECURITY_KEY")))
     overrides))))
