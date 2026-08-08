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
           max-seed-servers security-key]
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
                         [:max-seed-servers max-seed-servers]]]
    (when-not (and (integer? value) (pos? value))
      (throw (ex-info (str (name field) " must be positive.")
                      {:type :eacl-solidjs.config/invalid :field field}))))
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

       (get env "EACL_SOLIDJS_SECURITY_KEY")
       (assoc :security-key (get env "EACL_SOLIDJS_SECURITY_KEY")))
     overrides))))
