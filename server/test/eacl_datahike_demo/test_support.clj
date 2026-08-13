(ns eacl-datahike-demo.test-support
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [datahike.api :as d]
            [eacl-datahike-demo.config :as config]
            [eacl-datahike-demo.system :as system])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.util UUID]
           [java.util.concurrent ExecutorService TimeUnit]))

(defn test-config
  []
  (assoc config/default-config :store-id (UUID/randomUUID)))

(defn build-test-system
  ([] (build-test-system {}))
  ([overrides]
   (system/build-system (merge (test-config) overrides))))

(defn close-test-system!
  [{:keys [conn executor]}]
  (let [database-config (when conn (:config (d/db conn)))]
  (when executor
    (.shutdownNow ^ExecutorService executor)
    (.awaitTermination ^ExecutorService executor 2 TimeUnit/SECONDS))
    (when conn (d/release conn))
    (when database-config
      (d/delete-database database-config))))

(defmacro with-test-system
  [[binding] & body]
  `(let [~binding (build-test-system)]
     (try
       ~@body
       (finally
         (close-test-system! ~binding)))))

(defn- input-stream
  [value]
  (ByteArrayInputStream.
   (.getBytes ^String value StandardCharsets/UTF_8)))

(defn request
  ([handler method uri]
   (request handler method uri nil {}))
  ([handler method uri body]
   (request handler method uri body {}))
  ([handler method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)
         encoded (when (some? body) (json/write-str body))]
     (handler
      (cond-> {:request-method method
               :uri path
               :headers (merge {"accept" "application/json"} headers)}
        query (assoc :query-string query)
        encoded (assoc :body (input-stream encoded)
                       :headers (merge {"accept" "application/json"
                                        "content-type" "application/json"
                                        "content-length" (str (count (.getBytes
                                                                      ^String encoded
                                                                      StandardCharsets/UTF_8)))}
                                       headers)))))))

(defn response-body
  [response]
  (json/read-str (:body response) :key-fn keyword))

(defn data
  [response]
  (:data (response-body response)))

(defn meta-data
  [response]
  (:meta (response-body response)))

(def super-user {:type "user" :id "super-user"})
(def user-1 {:type "user" :id "user-1"})
(def server-0 {:type "server" :id "account-0-server-0"})
(def account-0 {:type "account" :id "account-0"})

(def lookup-resources-body
  {:subject super-user
   :resourceType "server"
   :permission "view"
   :pageSize 10
   :cache true})
