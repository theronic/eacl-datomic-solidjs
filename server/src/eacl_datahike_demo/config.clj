(ns eacl-datahike-demo.config
  "Environment-backed server and Datahike configuration."
  (:require [clojure.string :as str]))

(def default-config
  {:mode :development
   :host "127.0.0.1"
   :port 8088
   :store-backend :memory
   :store-id nil
   :store-path nil
   :s3-bucket nil
   :s3-region "us-east-1"
   :s3-endpoint-override nil
   :s3-path-style-access? false
   :datahike-store-cache-size 8192
   :datahike-search-cache-size 0
   :request-timeout-ms 30000
   :max-body-bytes 65536
   :max-seed-servers 1000000
   :seed-transaction-size 250
   :seed-pause-ms 0
   :seed-in-flight 4
   :legacy-server-count nil
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
   :security-key nil
   :admin-token nil
   :nrepl-host "127.0.0.1"
   :nrepl-port nil})

(defn- invalid!
  [field message]
  (throw (ex-info message
                  {:type :eacl-datahike-demo.config/invalid
                   :field field})))

(defn- parse-positive-long
  [field value]
  (try
    (let [n (Long/parseLong (str value))]
      (when-not (pos? n)
        (invalid! field (str (name field) " must be positive.")))
      n)
    (catch NumberFormatException _
      (invalid! field (str (name field) " must be a whole number.")))))

(defn- parse-bool-env
  [field value]
  (case (str/lower-case (str value))
    "true" true
    "false" false
    (invalid! field (str (name field) " must be true or false."))))

(defn- parse-nonnegative-long
  [field value]
  (try
    (let [n (Long/parseLong (str value))]
      (when (neg? n)
        (invalid! field (str (name field) " must not be negative.")))
      n)
    (catch NumberFormatException _
      (invalid! field (str (name field) " must be a whole number.")))))

(defn- parse-mode
  [value]
  (let [mode (keyword (str/lower-case (str value)))]
    (if (#{:development :production} mode)
      mode
      (invalid! :mode "mode must be development or production."))))

(defn- parse-backend
  [value]
  (let [backend (keyword (str/lower-case (str value)))]
    (if (#{:memory :file :s3} backend)
      backend
      (invalid! :store-backend "store-backend must be memory, file, or s3."))))

(defn- parse-uuid-env
  [field value]
  (try
    (java.util.UUID/fromString (str value))
    (catch IllegalArgumentException _
      (invalid! field (str (name field) " must be a UUID.")))))

(defn- parse-s3-endpoint
  [value]
  (try
    (let [uri (java.net.URI. (str value))
          scheme (some-> (.getScheme uri) str/lower-case)
          host (.getHost uri)
          raw-port (.getPort uri)
          port (if (neg? raw-port)
                 (if (= "https" scheme) 443 80)
                 raw-port)]
      (when-not (#{"http" "https"} scheme)
        (invalid! :s3-endpoint
                  "s3-endpoint must use http or https."))
      (when (str/blank? host)
        (invalid! :s3-endpoint "s3-endpoint must include a hostname."))
      (when-not (<= 1 port 65535)
        (invalid! :s3-endpoint
                  "s3-endpoint port must be between 1 and 65535."))
      (when (or (not (str/blank? (.getUserInfo uri)))
                (not (str/blank? (.getPath uri)))
                (not (str/blank? (.getQuery uri)))
                (not (str/blank? (.getFragment uri))))
        (invalid! :s3-endpoint
                  "s3-endpoint must be an origin without credentials, path, query, or fragment."))
      {:protocol (keyword scheme)
       :hostname host
       :port port})
    (catch java.net.URISyntaxException _
      (invalid! :s3-endpoint "s3-endpoint must be an absolute HTTP(S) URI."))))

(defn- validate-port
  [field value]
  (when-not (and (integer? value) (<= 1 value 65535))
    (invalid! field (str (name field) " must be between 1 and 65535."))))

(defn validate
  [{:keys [mode host port store-backend store-id store-path s3-bucket
           s3-region request-timeout-ms max-body-bytes max-seed-servers
           seed-transaction-size seed-pause-ms seed-in-flight
           legacy-server-count max-count-limit
           datahike-store-cache-size datahike-search-cache-size
           max-eacl-concurrency cache-max-entries
           cache-projection-max-weight cache-denotation-max-weight
           cache-answer-max-weight cache-managed-proof-max-atoms
           jetty-min-threads
           jetty-max-threads jetty-max-queued-requests security-key
           admin-token nrepl-host nrepl-port]
    :as config}]
  (when-not (#{:development :production} mode)
    (invalid! :mode "mode must be development or production."))
  (when (str/blank? host)
    (invalid! :host "host must not be blank."))
  (validate-port :port port)
  (when-not (#{:memory :file :s3} store-backend)
    (invalid! :store-backend "store-backend must be memory, file, or s3."))
  (when (and (not= :memory store-backend) (nil? store-id))
    (invalid! :store-id "store-id is required for durable storage."))
  (when (and (= :file store-backend) (str/blank? store-path))
    (invalid! :store-path "store-path is required for file storage."))
  (when (= :s3 store-backend)
    (when (str/blank? s3-bucket)
      (invalid! :s3-bucket "s3-bucket is required for S3 storage."))
    (when (str/blank? s3-region)
      (invalid! :s3-region "s3-region is required for S3 storage.")))
  (doseq [[field value] [[:request-timeout-ms request-timeout-ms]
                         [:max-body-bytes max-body-bytes]
                         [:max-seed-servers max-seed-servers]
                         [:seed-transaction-size seed-transaction-size]
                         [:seed-in-flight seed-in-flight]
                         [:datahike-store-cache-size datahike-store-cache-size]
                         [:max-count-limit max-count-limit]
                         [:max-eacl-concurrency max-eacl-concurrency]
                         [:cache-max-entries cache-max-entries]
                         [:cache-projection-max-weight cache-projection-max-weight]
                         [:cache-denotation-max-weight cache-denotation-max-weight]
                         [:cache-answer-max-weight cache-answer-max-weight]
                         [:cache-managed-proof-max-atoms
                          cache-managed-proof-max-atoms]
                         [:jetty-min-threads jetty-min-threads]
                         [:jetty-max-threads jetty-max-threads]
                         [:jetty-max-queued-requests jetty-max-queued-requests]]]
    (when-not (and (integer? value) (pos? value))
      (invalid! field (str (name field) " must be positive."))))
  (when (> seed-transaction-size 2000)
    (invalid! :seed-transaction-size
              "seed-transaction-size must not exceed 2000."))
  (when-not (and (integer? seed-pause-ms) (not (neg? seed-pause-ms)))
    (invalid! :seed-pause-ms "seed-pause-ms must not be negative."))
  (when-not (and (integer? datahike-search-cache-size)
                 (not (neg? datahike-search-cache-size)))
    (invalid! :datahike-search-cache-size
              "datahike-search-cache-size must not be negative."))
  (when (and legacy-server-count
             (not (and (integer? legacy-server-count)
                       (not (neg? legacy-server-count)))))
    (invalid! :legacy-server-count
              "legacy-server-count must not be negative."))
  (when (> jetty-min-threads jetty-max-threads)
    (invalid! :jetty-min-threads
              "jetty-min-threads must not exceed jetty-max-threads."))
  (when (= :production mode)
    (when (or (str/blank? security-key) (< (count security-key) 32))
      (invalid! :security-key
                "security-key must contain at least 32 characters in production."))
    (when (or (str/blank? admin-token) (< (count admin-token) 32))
      (invalid! :admin-token
                "admin-token must contain at least 32 characters in production.")))
  (when nrepl-port
    (when (str/blank? nrepl-host)
      (invalid! :nrepl-host "nrepl-host must not be blank."))
    (validate-port :nrepl-port nrepl-port)
    (when-not (= "127.0.0.1" nrepl-host)
      (invalid! :nrepl-host "nREPL must bind to 127.0.0.1.")))
  config)

(defn datahike-config
  [{:keys [store-backend store-id store-path s3-bucket s3-region
           s3-endpoint-override s3-path-style-access? s3-access-key
           s3-secret datahike-store-cache-size datahike-search-cache-size]}]
  (case store-backend
    :memory nil
    :file {:store {:backend :file
                   :path store-path
                   :id store-id}
           :schema-flexibility :write
           :attribute-refs? true
           :keep-history? false
           :max-string-length 0
           :store-cache-size datahike-store-cache-size
           :search-cache-size datahike-search-cache-size
           :commit-graph? false}
    :s3 {:store (cond-> {:backend :s3
                         :bucket s3-bucket
                         :region s3-region
                         :id store-id}
                  s3-endpoint-override
                  (assoc :endpoint-override s3-endpoint-override)

                  s3-path-style-access?
                  (assoc :path-style-access? true)

                  s3-access-key
                  (assoc :access-key s3-access-key :secret s3-secret))
         :schema-flexibility :write
         :attribute-refs? true
         :keep-history? false
         :max-string-length 0
         :store-cache-size datahike-store-cache-size
         :search-cache-size datahike-search-cache-size
         :index-config {:diff-buf-size 256}
         :fuse-index-roots? true
         :commit-graph? false}))

(defn from-env
  ([] (from-env (System/getenv) {}))
  ([env] (from-env env {}))
  ([env overrides]
   (let [value #(get env (str "EACL_DATAHIKE_DEMO_" %))]
     (validate
      (merge
       (cond-> default-config
         (value "MODE")
         (assoc :mode (parse-mode (value "MODE")))

         (value "HOST")
         (assoc :host (value "HOST"))

         (value "PORT")
         (assoc :port (parse-positive-long :port (value "PORT")))

         (value "STORE_BACKEND")
         (assoc :store-backend (parse-backend (value "STORE_BACKEND")))

         (value "STORE_ID")
         (assoc :store-id (parse-uuid-env :store-id (value "STORE_ID")))

         (value "STORE_PATH")
         (assoc :store-path (value "STORE_PATH"))

         (value "S3_BUCKET")
         (assoc :s3-bucket (value "S3_BUCKET"))

         (value "S3_REGION")
         (assoc :s3-region (value "S3_REGION"))

         (value "S3_ENDPOINT")
         (assoc :s3-endpoint-override
                (parse-s3-endpoint (value "S3_ENDPOINT")))

         (value "S3_PATH_STYLE_ACCESS")
         (assoc :s3-path-style-access?
                (parse-bool-env :s3-path-style-access?
                                (value "S3_PATH_STYLE_ACCESS")))

         (value "DATAHIKE_STORE_CACHE_SIZE")
         (assoc :datahike-store-cache-size
                (parse-positive-long :datahike-store-cache-size
                                     (value "DATAHIKE_STORE_CACHE_SIZE")))

         (value "DATAHIKE_SEARCH_CACHE_SIZE")
         (assoc :datahike-search-cache-size
                (parse-nonnegative-long :datahike-search-cache-size
                                        (value "DATAHIKE_SEARCH_CACHE_SIZE")))

         (value "REQUEST_TIMEOUT_MS")
         (assoc :request-timeout-ms
                (parse-positive-long :request-timeout-ms
                                     (value "REQUEST_TIMEOUT_MS")))

         (value "MAX_BODY_BYTES")
         (assoc :max-body-bytes
                (parse-positive-long :max-body-bytes
                                     (value "MAX_BODY_BYTES")))

         (value "MAX_SEED_SERVERS")
         (assoc :max-seed-servers
                (parse-positive-long :max-seed-servers
                                     (value "MAX_SEED_SERVERS")))

         (value "SEED_TRANSACTION_SIZE")
         (assoc :seed-transaction-size
                (parse-positive-long :seed-transaction-size
                                     (value "SEED_TRANSACTION_SIZE")))

         (value "SEED_PAUSE_MS")
         (assoc :seed-pause-ms
                (parse-nonnegative-long :seed-pause-ms
                                        (value "SEED_PAUSE_MS")))

         (value "SEED_IN_FLIGHT")
         (assoc :seed-in-flight
                (parse-positive-long :seed-in-flight
                                     (value "SEED_IN_FLIGHT")))

         (value "LEGACY_SERVER_COUNT")
         (assoc :legacy-server-count
                (parse-nonnegative-long :legacy-server-count
                                        (value "LEGACY_SERVER_COUNT")))

         (value "MAX_COUNT_LIMIT")
         (assoc :max-count-limit
                (parse-positive-long :max-count-limit
                                     (value "MAX_COUNT_LIMIT")))

         (value "MAX_EACL_CONCURRENCY")
         (assoc :max-eacl-concurrency
                (parse-positive-long :max-eacl-concurrency
                                     (value "MAX_EACL_CONCURRENCY")))

         (value "CACHE_MAX_ENTRIES")
         (assoc :cache-max-entries
                (parse-positive-long :cache-max-entries
                                     (value "CACHE_MAX_ENTRIES")))

         (value "CACHE_PROJECTION_MAX_WEIGHT")
         (assoc :cache-projection-max-weight
                (parse-positive-long :cache-projection-max-weight
                                     (value "CACHE_PROJECTION_MAX_WEIGHT")))

         (value "CACHE_DENOTATION_MAX_WEIGHT")
         (assoc :cache-denotation-max-weight
                (parse-positive-long :cache-denotation-max-weight
                                     (value "CACHE_DENOTATION_MAX_WEIGHT")))

         (value "CACHE_ANSWER_MAX_WEIGHT")
         (assoc :cache-answer-max-weight
                (parse-positive-long :cache-answer-max-weight
                                     (value "CACHE_ANSWER_MAX_WEIGHT")))

         (value "CACHE_MANAGED_PROOF_MAX_ATOMS")
         (assoc :cache-managed-proof-max-atoms
                (parse-positive-long :cache-managed-proof-max-atoms
                                     (value "CACHE_MANAGED_PROOF_MAX_ATOMS")))

         (value "SECURITY_KEY")
         (assoc :security-key (value "SECURITY_KEY"))

         (value "ADMIN_TOKEN")
         (assoc :admin-token (value "ADMIN_TOKEN"))

         (value "NREPL_PORT")
         (assoc :nrepl-port
                (parse-positive-long :nrepl-port (value "NREPL_PORT"))))
       overrides)))))
