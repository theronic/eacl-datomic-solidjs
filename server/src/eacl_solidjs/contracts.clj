(ns eacl-solidjs.contracts
  "JSON boundary, validation helpers, and stable HTTP error semantics."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [datomic.api :as d]
            [eacl-solidjs.data :as data]
            [eacl-solidjs.runtime :as runtime])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]
           [java.util UUID]))

(defn api-error
  ([status code message]
   (api-error status code message nil))
  ([status code message details]
   (throw (ex-info message
                   (cond-> {:type :eacl-solidjs/api-error
                            :http/status status
                            :error/code code}
                     details (assoc :error/details details))))))

(defn- kebab->camel
  [value]
  (let [without-predicate (str/replace (str value) #"\?$" "")]
    (str/replace without-predicate #"-([a-zA-Z])"
                 (fn [[_ letter]] (str/upper-case letter)))))

(defn- json-key
  [key]
  (cond
    (keyword? key) (kebab->camel (name key))
    (symbol? key) (str key)
    :else (str key)))

(declare json-safe)

(defn json-safe
  [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    (symbol? value) (str value)
    (uuid? value) (str value)
    (instance? Instant value) (str value)
    (map? value) (into {} (map (fn [[key item]]
                                 [(json-key key) (json-safe item)])) value)
    (set? value) (mapv json-safe (sort-by str value))
    (sequential? value) (mapv json-safe value)
    :else value))

(defn- body-bytes
  [body]
  (alength (.getBytes ^String body StandardCharsets/UTF_8)))

(defn response
  [request status payload]
  (let [body (json/write-str (json-safe payload))]
    {:status status
     :headers {"content-type" "application/json; charset=utf-8"
               "cache-control" "no-store"
               "x-request-id" (:request-id request)}
     :body body}))

(defn success
  ([system request data]
   (success system request data {}))
  ([system request data meta]
   (response request 200
             {:data data
              :meta (merge {:revision (runtime/revision system)
                            :request-id (:request-id request)}
                           meta)})))

(defn accepted
  [system request data]
  (let [result (success system request data)]
    (assoc result :status 202)))

(defn request-id
  [request]
  (or (get-in request [:headers "x-request-id"])
      (str (UUID/randomUUID))))

(defn read-json
  [request max-body-bytes]
  (let [content-type (get-in request [:headers "content-type"] "")
        content-length (some-> (get-in request [:headers "content-length"])
                               Long/parseLong)]
    (when-not (str/starts-with? (str/lower-case content-type)
                                "application/json")
      (api-error 415 "unsupported-media-type"
                 "Content-Type must be application/json."))
    (when (and content-length (> content-length max-body-bytes))
      (api-error 413 "request-too-large" "Request body is too large."))
    (let [body (slurp (:body request))]
      (when (> (body-bytes body) max-body-bytes)
        (api-error 413 "request-too-large" "Request body is too large."))
      (try
        (let [parsed (json/read-str body :key-fn keyword)]
          (when-not (map? parsed)
            (api-error 400 "invalid-json-shape"
                       "JSON request body must be an object."))
          parsed)
        (catch clojure.lang.ExceptionInfo ex
          (throw ex))
        (catch Exception _
          (api-error 400 "invalid-json" "Request body is not valid JSON."))))))

(defn require-string
  ([body key]
   (require-string body key 4096))
  ([body key max-length]
   (let [value (get body key)]
     (when-not (and (string? value)
                    (not (str/blank? value))
                    (<= (count value) max-length))
       (api-error 400 "invalid-field"
                  (str (name key) " must be a non-empty string.")
                  {:field (name key)}))
     value)))

(defn optional-cursor
  [body]
  (when-let [cursor (:after body)]
    (when-not (and (string? cursor) (<= (count cursor) 16384))
      (api-error 400 "invalid-cursor" "after must be an opaque cursor string."))
    cursor))

(defn cache-enabled?
  [body]
  (let [value (get body :cache true)]
    (when-not (boolean? value)
      (api-error 400 "invalid-cache-mode" "cache must be boolean."))
    value))

(defn page-size
  ([body] (page-size body data/default-page-size))
  ([body fallback]
   (let [value (get body :pageSize fallback)]
     (when-not (some #{value} data/page-size-options)
       (api-error 400 "invalid-page-size"
                  "pageSize must be one of the supported values."
                  {:supported data/page-size-options}))
     value)))

(defn count-limit
  [body]
  (let [value (:countLimit body)]
    (when-not (and (integer? value)
                   (pos? value)
                   (<= value Long/MAX_VALUE))
      (api-error 400 "invalid-count-limit"
                 "countLimit must be a positive whole number."
                 {:field "countLimit"}))
    (long value)))

(defn query-long
  [request key fallback]
  (let [raw (get-in request [:query-params (name key)])]
    (if (nil? raw)
      fallback
      (try
        (Long/parseLong raw)
        (catch NumberFormatException _
          (api-error 400 "invalid-query-parameter"
                     (str (name key) " must be a whole number.")))))))

(defn- schema-index
  [system]
  (let [{:keys [relations permissions]}
        (data/schema-model (d/db (:conn system)))]
    {:types (set (concat (map :eacl.relation/resource-type relations)
                         (map :eacl.relation/subject-type relations)
                         (map :eacl.permission/resource-type permissions)))
     :permissions
     (set (map (juxt :eacl.permission/resource-type
                     :eacl.permission/permission-name)
               permissions))
     :relations
     (set (map (juxt :eacl.relation/resource-type
                     :eacl.relation/relation-name
                     :eacl.relation/subject-type)
               relations))}))

(defn schema-type
  [system value field]
  (let [value (if (string? value) value nil)
        type (some-> value keyword)]
    (when-not (contains? (:types (schema-index system)) type)
      (api-error 400 "unknown-schema-type"
                 (str (name field) " is not defined in the active schema.")
                 {:field (name field)}))
    type))

(defn schema-permission
  [system resource-type value]
  (let [value (if (string? value) value nil)
        permission (some-> value keyword)]
    (when-not (contains? (:permissions (schema-index system))
                         [resource-type permission])
      (api-error 400 "unknown-schema-permission"
                 "permission is not defined for the resource type."))
    permission))

(defn schema-relation
  [system resource-type subject-type value]
  (let [value (if (string? value) value nil)
        relation (some-> value keyword)]
    (when-not (contains? (:relations (schema-index system))
                         [resource-type relation subject-type])
      (api-error 400 "unknown-schema-relation"
                 "relation is not defined for the resource and subject types."))
    relation))

(defn object
  [system body key]
  (let [value (get body key)]
    (when-not (map? value)
      (api-error 400 "invalid-object"
                 (str (name key) " must be an object with type and id.")))
    (let [type (schema-type system (:type value) (keyword (str (name key) "Type")))
          id (require-string value :id 4096)]
      (data/->object type id))))

(defn cache-status
  [result cache?]
  (cond
    (false? cache?) :disabled
    (:cached? result) :hit
    :else :miss))

(defn elapsed-ms
  [started-nanos]
  (/ (double (- (System/nanoTime) started-nanos)) 1000000.0))

(defn exception->response
  [system request throwable]
  (let [data (ex-data throwable)
        message (or (ex-message throwable) "Unexpected server error.")
        cursor-error?
        (or (= "invalid-cursor" (:error/code data))
            (str/includes? (str (or (:type data) "")) "cursor")
            (str/includes? (str/lower-case message) "cursor"))
        status (or (:http/status data) (when cursor-error? 409) 500)
        code (or (:error/code data)
                 (when cursor-error? "invalid-cursor")
                 "internal-error")
        safe-message (if (= 500 status)
                       "The server could not complete the request."
                       message)]
    (response request status
              {:error (cond-> {:code code :message safe-message}
                        (:error/details data)
                        (assoc :details (:error/details data)))
               :meta {:revision (runtime/revision system)
                      :request-id (:request-id request)}})))
