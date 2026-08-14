(ns eacl-datahike-demo.storage-test
  "Opt-in end-to-end probe for the Datahike Konserve S3 backend."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl-datahike-demo.config :as config]
            [eacl-datahike-demo.data :as data]
            [eacl-datahike-demo.storage-gc :as storage-gc]
            [eacl-datahike-demo.system :as system]
            [konserve.core :as k]
            [konserve.impl.defaults :as konserve-defaults]
            [konserve-s3.core :as konserve-s3])
  (:import [software.amazon.awssdk.services.s3.model
            ListObjectVersionsRequest]))

(deftest storage-gc-is-single-flight-and-returns-a-small-summary
  (let [started (promise)
        release (promise)
        running? (atom false)
        demo-system {:conn :test-connection
                     :!seed-running? (atom false)
                     :!storage-gc-running? running?}]
    (with-redefs [d/gc-storage
                  (fn [_]
                    (deliver started true)
                    release)]
      (let [job (future (system/gc-storage! demo-system))]
        @started
        (try
          (let [duplicate
                (try
                  (system/gc-storage! demo-system)
                  nil
                  (catch clojure.lang.ExceptionInfo exception exception))]
            (is (= :eacl-datahike-demo.system/gc-running
                   (:type (ex-data duplicate)))))
          (finally
            (deliver release #{:deleted-1 :deleted-2})))
        (is (= {:status :complete :deleted-key-count 2} @job))
        (is (false? @running?))))))

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
        (let [store (:store (config/datahike-config runtime-config))]
          (if (= :tiered (:backend store)) (:backend-config store) store))
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
          !tiered (atom nil)
          !fallback (atom nil)]
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
                                       (s3-version-snapshot runtime-config))
                store (:store (d/db (:conn running)))
                serial-key-metadata (k/keys store {:sync? true})
                parallel-key-metadata
                (with-redefs [konserve-defaults/list-keys
                              storage-gc/bounded-list-keys]
                  (k/keys store {:sync? true}))
                gc-result (system/gc-storage! running)
                compacted-snapshot (s3-snapshot runtime-config)]
            (is (= 2000 (:servers-added seed-result)))
            (is (= 2048 (data/count-servers (d/db (:conn running)))))
            (is (= :complete (:status gc-result)))
            (is (pos? (:deleted-key-count gc-result)))
            (is (= serial-key-metadata parallel-key-metadata))
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
                          (:noncurrent-version-bytes fixture-snapshot))}))
            (is (<= (:objects compacted-snapshot)
                    (:objects seeded-snapshot)))
            (is (<= (:bytes compacted-snapshot)
                    (:bytes seeded-snapshot)))
            (println "MINIO_OFFLINE_GC="
                     (pr-str {:before (select-keys seeded-snapshot
                                                   [:objects :bytes])
                              :after compacted-snapshot}))))
        (system/close-system! @!first)
        (reset! !first nil)
        (testing "tiered LMDB reconnect reads through and writes through to S3"
          (let [lmdb-path
                (or (System/getenv "EACL_DATAHIKE_DEMO_MINIO_LMDB_PATH")
                    (throw (ex-info "LMDB test path is required." {})))
                tiered-config (assoc runtime-config
                                     :store-backend :s3-lmdb
                                     :lmdb-path lmdb-path
                                     :lmdb-map-size (* 1024 1024 1024))
                {running :result stats :stats}
                (konserve-s3/with-global-io-stats
                  (system/build-system tiered-config))]
            (reset! !tiered running)
            (is (false? (:database-created? running)))
            (is (= 2048 (data/count-servers (d/db (:conn running)))))
            (is (:allowed?
                 (eacl/check-permission
                  (:acl running)
                  {:subject (data/->object :user "user-1")
                   :permission :view
                   :resource (data/->object :server "account-4-server-1999")
                   :cache? false})))
            (let [page
                  (eacl/lookup-resources
                   (:acl running)
                   {:subject (data/->object :user "user-1")
                    :permission :view
                    :resource/type :server
                    :first 20
                    :cache? true})]
              (is (= 20 (count (:data page)))))
            (data/seed-more!
             (:conn running) (:acl running)
             (:!seed-running? running) (:!seed-progress running)
             1 (:max-seed-servers tiered-config))
            (is (= 2049 (data/count-servers (d/db (:conn running)))))
            (println "MINIO_TIERED_RECONNECT_IO=" (pr-str stats))))
        (system/close-system! @!tiered)
        (reset! !tiered nil)
        (testing "direct-S3 fallback observes the tiered writer's durable commit"
          (let [running (system/build-system runtime-config)]
            (reset! !fallback running)
            (is (false? (:database-created? running)))
            (is (= 2049 (data/count-servers (d/db (:conn running)))))
            (is (= 2049 (data/maintained-server-count (d/db (:conn running)))))))
        (finally
          (when-let [running @!fallback]
            (system/close-system! running))
          (when-let [running @!tiered]
            (system/close-system! running))
          (when-let [running @!first]
            (system/close-system! running))
          (when (d/database-exists? database-config)
            (d/delete-database database-config))
          (konserve-s3/shutdown-clients!))))
    (is true "Set EACL_DATAHIKE_DEMO_MINIO_ENDPOINT to run the S3 probe.")))
