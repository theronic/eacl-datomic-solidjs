(ns eacl-datahike-demo.api
  "Ring handlers exposing EACL and demo operations as narrow JSON contracts."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike-eacl]
            [eacl-datahike-demo.contracts :as contracts]
            [eacl-datahike-demo.data :as data]
            [eacl-datahike-demo.runtime :as runtime]
            [reitit.ring :as ring]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.mime-type :as mime]
            [ring.util.response :as response])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]
           [java.util.concurrent Callable Semaphore]))

(defn- object->map
  [object]
  (select-keys object [:type :id]))

(defn- relationship->map
  [relationship]
  {:subject (object->map (:subject relationship))
   :relation (:relation relationship)
   :resource (object->map (:resource relationship))})

(defn- page-info
  [result]
  (or (:page-info result)
      {:start-cursor nil
       :end-cursor nil
       :has-next-page? false
       :has-previous-page? false}))

(defn- transient-node-not-found?
  [throwable]
  (loop [current throwable]
    (cond
      (nil? current) false
      (= :node-not-found (:type (ex-data current))) true
      :else (recur (.getCause ^Throwable current)))))

(defn- with-storage-read-retry
  "Retries the narrow transient window in which Datahike has published a new
  persistent index root while an S3 reader still observes a missing child.
  Every attempt reacquires the current immutable database value."
  [read]
  (loop [attempt 0]
    (let [outcome (try
                    {:value (read)}
                    (catch Exception ex
                      {:error ex}))]
      (if-let [error (:error outcome)]
        (if (and (< attempt 2) (transient-node-not-found? error))
          (do
            (Thread/sleep (* 25 (inc attempt)))
            (recur (inc attempt)))
          (throw error))
        (:value outcome)))))

(defn- run-eacl
  [system request operation cache? execute transform]
  (let [started (System/nanoTime)
        permits ^Semaphore (:eacl-permits system)
        cancellation-token
        (or (:eacl/cancellation-token request)
            (eacl/cancellation-token))]
    (when-not (.tryAcquire permits)
      (contracts/api-error 503 "server-busy"
                           "The authorization worker pool is busy."))
    (try
      (try
        (let [result (with-storage-read-retry
                       #(execute cancellation-token))
              elapsed (contracts/elapsed-ms started)
              cache-status (contracts/cache-status result cache?)
              api-response
              (contracts/success
               system request (transform result)
               {:elapsed-ms elapsed
                :cache-status cache-status})
              bytes (alength (.getBytes ^String (:body api-response)
                                       StandardCharsets/UTF_8))]
          (runtime/record-operation!
           system operation elapsed true bytes cache-status)
          api-response)
        (catch Exception ex
          (runtime/record-operation!
           system operation (contracts/elapsed-ms started) false 0 nil)
          (throw ex)))
      (finally
        (.release permits)))))

(defn- eacl-page-query
  [system body direction]
  (let [subject (contracts/object system body :subject)
        resource-type
        (contracts/schema-type system (:resourceType body) :resourceType)
        permission
        (contracts/schema-permission system resource-type (:permission body))
        cache? (contracts/cache-enabled? body)
        first (contracts/page-size body)
        after (contracts/optional-cursor body)]
    {:cache? cache?
     :query (cond-> {:subject subject
                     :permission permission
                     :resource/type resource-type
                     direction first
                     :cache? cache?}
              after (assoc :after after))}))

(defn- handle-health
  [system request]
  (contracts/success
   system request
   {:status :ready
    :datahike {:revision (runtime/datahike-revision (d/db (:conn system)))
               :store-backend (get-in system [:config :store-backend])}}))

(defn- handle-bootstrap
  [system request]
  (with-storage-read-retry
    (fn []
      (let [db (d/db (:conn system))
            seed @(:!seed-progress system)]
        (contracts/success
         system request
         {:status (if (= :seeding (:status seed)) :seeding :ready)
          :seed seed
          :totals (data/totals db (:total-servers seed))
          :schema (data/schema-info db)
          :quick-subjects data/quick-subjects
          :page-size-options data/page-size-options
          :default-page-size data/default-page-size
          :capabilities
          {:schema-write? (nil? (get-in system [:config :admin-token]))
           :seed-write? (nil? (get-in system [:config :admin-token]))
           :cache-evict? (nil? (get-in system [:config :admin-token]))}})))))

(defn- handle-subjects
  [system request]
  (let [offset (contracts/query-long request :offset 0)
        limit (contracts/query-long request :limit data/default-page-size)]
    (when (neg? offset)
      (contracts/api-error 400 "invalid-query-parameter"
                           "offset must not be negative."))
    (when-not (some #{limit} data/page-size-options)
      (contracts/api-error 400 "invalid-page-size"
                           "limit must be a supported page size."
                           {:supported data/page-size-options}))
    (contracts/success
     system request
     (data/known-subjects (:total-servers @(:!seed-progress system))
                          offset limit))))

(defn- handle-lookup-resources
  [system request]
  (let [body (contracts/read-json request (get-in system [:config :max-body-bytes]))
        {:keys [query cache?]} (eacl-page-query system body :first)]
    (run-eacl
     system request :lookup-resources cache?
     #(eacl/lookup-resources
       (:acl system) (assoc query :cancellation-token %))
     (fn [result]
       {:items (mapv object->map (:data result))
        :page-info (page-info result)}))))

(defn- handle-count-resources
  [system request]
  (let [body (contracts/read-json request (get-in system [:config :max-body-bytes]))
        subject (contracts/object system body :subject)
        resource-type
        (contracts/schema-type system (:resourceType body) :resourceType)
        permission
        (contracts/schema-permission system resource-type (:permission body))
        cache? (contracts/cache-enabled? body)
        count-limit (contracts/count-limit system body)
        query {:subject subject
               :permission permission
               :resource/type resource-type
               :count-limit count-limit
               :cache? cache?}]
    (run-eacl
     system request :count-resources cache?
     #(eacl/count-resources
       (:acl system) (assoc query :cancellation-token %))
     #(select-keys % [:count :limit :truncated?]))))

(defn- handle-lookup-subjects
  [system request]
  (let [body (contracts/read-json request (get-in system [:config :max-body-bytes]))
        resource (contracts/object system body :resource)
        permission
        (contracts/schema-permission system (:type resource) (:permission body))
        subject-type
        (contracts/schema-type system (:subjectType body) :subjectType)
        cache? (contracts/cache-enabled? body)
        first (contracts/page-size body)
        after (contracts/optional-cursor body)
        query (cond-> {:resource resource
                       :permission permission
                       :subject/type subject-type
                       :first first
                       :cache? cache?}
                after (assoc :after after))]
    (run-eacl
     system request :lookup-subjects cache?
     #(eacl/lookup-subjects
       (:acl system) (assoc query :cancellation-token %))
     (fn [result]
       {:items (mapv object->map (:data result))
        :page-info (page-info result)}))))

(defn- handle-read-relationships
  [system request]
  (let [body (contracts/read-json request (get-in system [:config :max-body-bytes]))
        subject (contracts/object system body :subject)
        resource-type
        (contracts/schema-type system (:resourceType body) :resourceType)
        relation
        (contracts/schema-relation
         system resource-type (:type subject) (:relation body))
        authorization-subject
        (when (:authorizationSubject body)
          (contracts/object system body :authorizationSubject))
        authorization-permission
        (when (:permission body)
          (contracts/schema-permission
           system resource-type (:permission body)))
        _
        (when (not= (boolean authorization-subject)
                    (boolean authorization-permission))
          (contracts/api-error
           400 "invalid-authorization-filter"
           "authorizationSubject and permission must be supplied together."))
        cache? (contracts/cache-enabled? body)
        first (contracts/page-size body)
        after (contracts/optional-cursor body)
        query (cond-> {:subject/type (:type subject)
                       :subject/id (:id subject)
                       :resource/type resource-type
                       :resource/relation relation
                       :first first
                       :cache? cache?}
                after (assoc :after after))]
    (run-eacl
     system request :read-relationships
     ;; Permission checks on a nested page are a sweep of distinct resources.
     ;; Bypass completed-answer caching so recursive point checks use EACL's
     ;; target-anchored reverse evaluator instead of materializing the complete
     ;; forward denotation while holding the client's schema read lock.
     (if authorization-subject false cache?)
     #(let [query (assoc query :cancellation-token %)
            result (eacl/read-relationships (:acl system) query)]
        (if authorization-subject
          (let [decisions
                (mapv
                 (fn [relationship]
                   (eacl/check-permission
                    (:acl system)
                    {:subject authorization-subject
                     :permission authorization-permission
                     :resource (:resource relationship)
                     :cache? false
                     :cancellation-token %}))
                 (:data result))
                allowed
                (->> (map vector (:data result) decisions)
                     (keep (fn [[relationship decision]]
                             (when (:allowed? decision) relationship)))
                     vec)]
            (assoc result
                   :data allowed
                   :cached? false))
          result))
     (fn [result]
       {:items (mapv relationship->map (:data result))
        :page-info (page-info result)}))))

(defn- handle-check-permission
  [system request]
  (let [body (contracts/read-json request (get-in system [:config :max-body-bytes]))
        subject (contracts/object system body :subject)
        resource (contracts/object system body :resource)
        permission
        (contracts/schema-permission system (:type resource) (:permission body))
        cache? (contracts/cache-enabled? body)
        query {:subject subject
               :permission permission
               :resource resource
               :cache? cache?}]
    (run-eacl
     system request :check-permission cache?
     #(eacl/check-permission
       (:acl system) (assoc query :cancellation-token %))
     #(select-keys % [:allowed?]))))

(defn- handle-schema-read
  [system request]
  (with-storage-read-retry
    #(contracts/success system request
                        (data/schema-info (d/db (:conn system))))))

(defn- handle-schema-write
  [system request]
  (contracts/require-admin! system request)
  (let [body (contracts/read-json request (get-in system [:config :max-body-bytes]))
        source (contracts/require-string
                body :source (get-in system [:config :max-body-bytes]))]
    (try
      (eacl/write-schema! (:acl system) source)
      (contracts/success
       system request (data/schema-info (d/db (:conn system))))
      (catch Exception ex
        (contracts/api-error
         422 "invalid-schema" (or (ex-message ex) "Schema is invalid.")
         (when-let [errors (:errors (ex-data ex))]
           {:errors errors}))))))

(defn- handle-cache-read
  [system request]
  (contracts/success
   system request
   {:provider (datahike-eacl/cache-stats (:acl system))
    :operations (runtime/metrics-snapshot system)
    :prewarm (some-> (or (some-> system :!cache-prewarm deref)
                         (:cache-prewarm system))
                     :state deref)
    :captured-at (Instant/now)}))

(defn- handle-cache-evict
  [system request]
  (contracts/require-admin! system request)
  (locking (:evict-lock system)
    (datahike-eacl/expire-cache! (:acl system))
    (runtime/advance-cache-generation! system))
  (contracts/success system request {:status :evicted}))

(defn- handle-seed-read
  [system request]
  (contracts/success system request @(:!seed-progress system)))

(defn- handle-seed-write
  [system request]
  (contracts/require-admin! system request)
  (let [body (contracts/read-json request (get-in system [:config :max-body-bytes]))
        server-count (:serverCount body)
        !running? (:!seed-running? system)
        !progress (:!seed-progress system)
        max-servers (get-in system [:config :max-seed-servers])]
    (data/reserve-seed! !running? server-count max-servers)
    (let [servers-before (:total-servers @!progress)
          queued {:status :seeding
                  :servers-added 0
                  :servers-completed 0
                  :servers-target server-count
                  :total-servers servers-before
                  :label "Queued Datahike seed job"
                  :error nil}]
      (reset! !progress queued)
      (try
        (.submit
         ^java.util.concurrent.ExecutorService (:executor system)
         ^Callable
         (reify Callable
           (call [_]
             (try
               (data/seed-reserved!
                (:conn system) (:acl system) !running? !progress server-count
                (get-in system [:config :seed-transaction-size])
                (get-in system [:config :seed-pause-ms])
                (get-in system [:config :seed-in-flight]))
               (catch Throwable throwable
                 (log/error "Background seed job failed"
                            {:exception-class (.getName (class throwable))})
                 nil)))))
        (contracts/accepted system request queued)
        (catch Throwable throwable
          (reset! !running? false)
          (reset! !progress
                  (assoc queued :status :error :error "Seed job could not start."))
          (throw throwable))))))

(def api-methods
  {"/api/health" #{:get}
   "/api/bootstrap" #{:get}
   "/api/subjects" #{:get}
   "/api/eacl/lookup-resources" #{:post}
   "/api/eacl/count-resources" #{:post}
   "/api/eacl/lookup-subjects" #{:post}
   "/api/eacl/read-relationships" #{:post}
   "/api/eacl/check-permission" #{:post}
   "/api/schema" #{:get :put}
   "/api/cache" #{:get}
   "/api/cache/evict" #{:post}
   "/api/seed" #{:get :post}})

(defn- api-path?
  [uri]
  (str/starts-with? uri "/api/"))

(defn- known-api-path?
  [uri]
  (contains? api-methods uri))

(defn- static-response
  [request]
  (let [uri (:uri request)]
    (cond
      (api-path? uri)
      (if (and (known-api-path? uri)
               (not (contains? (get api-methods uri #{:get})
                               (:request-method request))))
        (contracts/response request 405
                            {:error {:code "method-not-allowed"
                                     :message "HTTP method is not allowed."}})
        (contracts/response request 404
                            {:error {:code "api-not-found"
                                     :message "API route was not found."}}))

      (not= :get (:request-method request))
      (contracts/response request 405
                          {:error {:code "method-not-allowed"
                                   :message "HTTP method is not allowed."}})

      :else
      (let [path (if (= "/" uri) "index.html" (subs uri 1))
            asset? (str/starts-with? path "assets/")
            accepts-gzip? (str/includes?
                           (str/lower-case
                            (get-in request [:headers "accept-encoding"] ""))
                           "gzip")
            compressed (when (and asset? accepts-gzip?)
                         (response/resource-response (str path ".gz")
                                                     {:root "public"}))
            found (or compressed
                      (response/resource-response path {:root "public"}))
            result (or found
                       (when-not asset?
                         (response/resource-response "index.html"
                                                     {:root "public"})))
            served-path (if found path "index.html")]
        (if result
          (-> result
              (response/content-type (mime/ext-mime-type served-path))
              (cond-> compressed
                (assoc-in [:headers "content-encoding"] "gzip")
                compressed (assoc-in [:headers "vary"] "Accept-Encoding"))
              (assoc-in [:headers "cache-control"]
                        (if asset?
                          "public, max-age=31536000, immutable"
                          "no-cache")))
          (contracts/response request 404
                              {:error {:code "not-found"
                                       :message "Resource was not found."}}))))))

(defn- wrap-request-id
  [handler]
  (fn [request]
    (handler (assoc request :request-id (contracts/request-id request)))))

(defn- wrap-exceptions
  [handler system]
  (fn [request]
    (try
      (handler request)
      (catch Throwable throwable
        (let [result (contracts/exception->response system request throwable)]
        (when (= (:status result) 500)
          (log/error "Unhandled API failure"
                     {:exception-class (.getName (class throwable))
                      :request-id (:request-id request)
                      :uri (:uri request)}))
          result)))))

(defn app
  [system]
  (let [router
        (ring/router
          [["/api/health" {:get #(handle-health system %)}]
          ["/api/bootstrap" {:get #(handle-bootstrap system %)}]
          ["/api/subjects" {:get #(handle-subjects system %)}]
          ["/api/eacl/lookup-resources"
           {:post #(handle-lookup-resources system %)}]
          ["/api/eacl/count-resources"
           {:post #(handle-count-resources system %)}]
          ["/api/eacl/lookup-subjects"
           {:post #(handle-lookup-subjects system %)}]
          ["/api/eacl/read-relationships"
           {:post #(handle-read-relationships system %)}]
          ["/api/eacl/check-permission"
           {:post #(handle-check-permission system %)}]
          ["/api/schema"
           {:get #(handle-schema-read system %)
            :put #(handle-schema-write system %)}]
          ["/api/cache" {:get #(handle-cache-read system %)}]
          ["/api/cache/evict" {:post #(handle-cache-evict system %)}]
          ["/api/seed"
           {:get #(handle-seed-read system %)
            :post #(handle-seed-write system %)}]])
        handler (ring/ring-handler router static-response)]
    (-> handler
        wrap-params
        wrap-content-type
        (wrap-exceptions system)
        wrap-request-id)))
