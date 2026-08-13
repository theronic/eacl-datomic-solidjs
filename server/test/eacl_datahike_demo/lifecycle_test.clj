(ns eacl-datahike-demo.lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl-datahike-demo.config :as config]
            [eacl-datahike-demo.data :as data]
            [eacl-datahike-demo.runtime :as runtime]
            [eacl-datahike-demo.system :as system]
            [eacl-datahike-demo.test-support :as support])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest file-store-reconnect-and-fixture-idempotency
  (let [directory (Files/createTempDirectory
                   "eacl-datahike-demo-"
                   (make-array FileAttribute 0))
        runtime-config
        (merge config/default-config
               {:store-backend :file
                :store-id (random-uuid)
                :store-path (str directory "/db")
                :security-key "0123456789abcdef0123456789abcdef"})
        first-system (system/build-system runtime-config)
        database-config (:config (d/db (:conn first-system)))
        first-revision (runtime/revision first-system)
        first-page (support/request
                    (:handler first-system)
                    :post
                    "/api/eacl/lookup-resources"
                    support/lookup-resources-body)
        first-page-cursor (get-in (support/data first-page)
                                  [:pageInfo :endCursor])]
    (try
      (testing "first boot creates and seeds once"
        (is (true? (:database-created? first-system)))
        (is (= 48 (data/count-servers (d/db (:conn first-system))))))
      (system/close-system! first-system)
      (let [second-system (system/build-system runtime-config)]
        (try
          (testing "reconnect retains committed data and revision"
            (is (false? (:database-created? second-system)))
            (is (= 48 (data/count-servers (d/db (:conn second-system)))))
            (is (= first-revision (runtime/revision second-system)))
            (is (= 200 (:status
                        (support/request
                         (:handler second-system)
                         :post
                         "/api/eacl/check-permission"
                         {:subject support/super-user
                          :resource support/server-0
                          :permission "view"
                          :cache false}))))
            (is (true?
                 (get-in
                  (support/data
                   (support/request
                    (:handler second-system)
                    :post
                    "/api/eacl/check-permission"
                    {:subject support/super-user
                     :resource support/server-0
                     :permission "view"
                     :cache false}))
                  [:allowed]))))
          (testing "a cursor minted before reconnect continues on the same store"
            (let [second-page
                  (support/request
                   (:handler second-system)
                   :post
                   "/api/eacl/lookup-resources"
                   (assoc support/lookup-resources-body
                          :after first-page-cursor))]
              (is (= 200 (:status second-page)))
              (is (seq (:items (support/data second-page))))))
          (testing "re-running fixture installation is idempotent"
            (data/install-demo! (:conn second-system) (:acl second-system))
            (is (= 48 (data/count-servers (d/db (:conn second-system)))))
            (is (= first-revision (runtime/revision second-system))))
          (finally
            (system/close-system! second-system))))
      (finally
        (when (d/database-exists? database-config)
          (d/delete-database database-config))
        (Files/deleteIfExists directory)))))
