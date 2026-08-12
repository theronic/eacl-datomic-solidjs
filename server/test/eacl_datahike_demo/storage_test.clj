(ns eacl-datahike-demo.storage-test
  "Opt-in end-to-end probe for the Datahike Konserve S3 backend."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl-datahike-demo.config :as config]
            [eacl-datahike-demo.data :as data]
            [eacl-datahike-demo.system :as system]
            [konserve-s3.core :as konserve-s3])
  (:import [software.amazon.awssdk.services.s3.model
            ListObjectVersionsRequest]))

(defn- minio-config
  [endpoint]
  (let [uri (java.net.URI. endpoint)]
    (merge
     config/default-config
     {:store-backend :s3
      :store-id (random-uuid)
      :s3-bucket (or (System/getenv "EACL_DATAHIKE_DEMO_MINIO_BUCKET")
                     "eacl-datahike-probe")
      :s3-region "auto"
      :s3-endpoint-override
      {:protocol (keyword (.getScheme uri))
       :hostname (.getHost uri)
       :port (.getPort uri)}
      :s3-path-style-access? true
      ;; Static keys are accepted only as direct test overrides. Production
      ;; environment parsing intentionally has no AWS credential variables.
      :s3-access-key
      (or (System/getenv "EACL_DATAHIKE_DEMO_MINIO_ACCESS_KEY") "minioadmin")
      :s3-secret
      (or (System/getenv "EACL_DATAHIKE_DEMO_MINIO_SECRET_KEY")
          "minioadmin123")
      :security-key "0123456789abcdef0123456789abcdef"})))

(defn- s3-snapshot
  [runtime-config]
  (let [{:keys [bucket id] :as store}
        (:store (config/datahike-config runtime-config))
        client (konserve-s3/s3-client store)
        prefix (str id "_")
        keys (->> (konserve-s3/list-objects client bucket)
                  (filter #(or (str/starts-with? % prefix)
                               (str/starts-with? % (str id "."))))
                  vec)
        sizes (mapv #(alength ^bytes
                              (konserve-s3/get-object client bucket %))
                    keys)]
    {:objects (count keys)
     :bytes (reduce + 0 sizes)}))

(defn- s3-version-snapshot
  [runtime-config]
  (let [{:keys [bucket id] :as store}
        (:store (config/datahike-config runtime-config))
        client (konserve-s3/s3-client store)
        prefix (str id "_")
        versions
        (loop [key-marker nil
               version-marker nil
               acc []]
          (let [builder (cond-> (ListObjectVersionsRequest/builder)
                          true (.bucket bucket)
                          key-marker (.keyMarker key-marker)
                          version-marker (.versionIdMarker version-marker))
                response (.listObjectVersions client (.build builder))
                acc' (into acc (.versions response))]
            (if (.isTruncated response)
              (recur (.nextKeyMarker response)
                     (.nextVersionIdMarker response)
                     acc')
              acc')))
        versions (filter #(or (str/starts-with? (.key %) prefix)
                              (str/starts-with? (.key %) (str id ".")))
                         versions)
        current (filter #(.isLatest %) versions)
        noncurrent (remove #(.isLatest %) versions)]
    {:current-version-bytes (reduce + 0 (map #(.size %) current))
     :noncurrent-version-bytes (reduce + 0 (map #(.size %) noncurrent))
     :versions (count versions)}))

(deftest ^:integration s3-create-seed-query-reconnect-and-cleanup
  (if-let [endpoint (System/getenv "EACL_DATAHIKE_DEMO_MINIO_ENDPOINT")]
    (let [runtime-config (minio-config endpoint)
          database-config (config/datahike-config runtime-config)
          !first (atom nil)
          !second (atom nil)]
      (try
        (testing "create, schema, fixtures, query, and a bounded 2k seed"
          (let [{running :result create-stats :stats}
                (konserve-s3/with-global-io-stats
                  (system/build-system runtime-config))
                _ (reset! !first running)
                _ (is (= 48 (data/count-servers (d/db (:conn running)))))
                _ (is (:allowed?
                       (eacl/check-permission
                        (:acl running)
                        {:subject (data/->object :user "super-user")
                         :permission :view
                         :resource
                         (data/->object :server "account-0-server-0")
                         :cache? false})))
                fixture-snapshot (merge (s3-snapshot runtime-config)
                                        (s3-version-snapshot runtime-config))
                {seed-result :result seed-stats :stats}
                (konserve-s3/with-global-io-stats
                  (data/seed-more!
                   (:conn running) (:acl running)
                   (:!seed-running? running) (:!seed-progress running)
                   2000 (:max-seed-servers runtime-config)))
                seeded-snapshot (merge (s3-snapshot runtime-config)
                                       (s3-version-snapshot runtime-config))]
            (is (= 2000 (:servers-added seed-result)))
            (is (= 2048 (data/count-servers (d/db (:conn running)))))
            (println "MINIO_CREATE_IO=" (pr-str create-stats))
            (println "MINIO_FIXTURE_STORE="
                     (pr-str (assoc fixture-snapshot :servers 48)))
            (println "MINIO_SEED_IO=" (pr-str seed-stats))
            (println "MINIO_SEED_DELTA="
                     (pr-str
                      {:servers 2000
                       :objects (- (:objects seeded-snapshot)
                                   (:objects fixture-snapshot))
                       :bytes (- (:bytes seeded-snapshot)
                                 (:bytes fixture-snapshot))
                       :versions (- (:versions seeded-snapshot)
                                    (:versions fixture-snapshot))
                       :current-version-bytes
                       (- (:current-version-bytes seeded-snapshot)
                          (:current-version-bytes fixture-snapshot))
                       :noncurrent-version-bytes
                       (- (:noncurrent-version-bytes seeded-snapshot)
                          (:noncurrent-version-bytes fixture-snapshot))}))))
        (system/close-system! @!first)
        (reset! !first nil)
        (testing "release and reconnect preserve data and authorization"
          (let [{:keys [result stats]}
                (konserve-s3/with-global-io-stats
                  (system/build-system runtime-config))]
            (reset! !second result)
            (is (false? (:database-created? result)))
            (is (= 2048 (data/count-servers (d/db (:conn result)))))
            (is (:allowed?
                 (eacl/check-permission
                  (:acl result)
                  {:subject (data/->object :user "user-1")
                   :permission :view
                   :resource (data/->object :server "account-4-server-1999")
                   :cache? false})))
            (println "MINIO_RECONNECT_IO=" (pr-str stats))
            (println "MINIO_CURRENT_STORE="
                     (pr-str (merge {:servers 2048}
                                    (s3-snapshot runtime-config))))))
        (finally
          (when-let [running @!second]
            (system/close-system! running))
          (when-let [running @!first]
            (system/close-system! running))
          (when (d/database-exists? database-config)
            (d/delete-database database-config))
          (konserve-s3/shutdown-clients!))))
    (is true "Set EACL_DATAHIKE_DEMO_MINIO_ENDPOINT to run the S3 probe.")))
