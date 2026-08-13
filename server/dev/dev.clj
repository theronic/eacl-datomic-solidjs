(ns dev
  "Persistent nREPL workflow for the EACL Datahike Demo backend."
  (:require [clojure.test :as test]
            [eacl-datahike-demo.config :as config]
            [eacl-datahike-demo.system :as system]))

(defn restart-backend!
  ([] (restart-backend! {}))
  ([overrides]
   (require 'eacl-datahike-demo.api :reload)
   (require 'eacl-datahike-demo.contracts :reload)
   (require 'eacl-datahike-demo.data :reload)
   (require 'eacl-datahike-demo.system :reload)
   (let [running
         (system/restart!
          (config/from-env (System/getenv) overrides))]
     {:status :ready
      :host (get-in running [:config :host])
      :port (get-in running [:config :port])
      :store-backend (get-in running [:config :store-backend])})))

(defn run-tests!
  []
  (doseq [namespace '[eacl-datahike-demo.config
                      eacl-datahike-demo.data
                      eacl-datahike-demo.runtime
                      eacl-datahike-demo.contracts
                      eacl-datahike-demo.api
                      eacl-datahike-demo.system]]
    (require namespace :reload))
  (doseq [namespace '[eacl-datahike-demo.config-test
                      eacl-datahike-demo.contracts-test
                      eacl-datahike-demo.api-test
                      eacl-datahike-demo.integration-test
                      eacl-datahike-demo.lifecycle-test
                      eacl-datahike-demo.storage-test]]
    (require namespace :reload))
  (apply test/run-tests
         '[eacl-datahike-demo.config-test
           eacl-datahike-demo.contracts-test
           eacl-datahike-demo.api-test
           eacl-datahike-demo.integration-test
           eacl-datahike-demo.lifecycle-test
           eacl-datahike-demo.storage-test]))
