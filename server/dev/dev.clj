(ns dev
  "Persistent nREPL workflow for the EACL SolidJS backend."
  (:require [clojure.test :as test]
            [eacl-solidjs.config :as config]
            [eacl-solidjs.system :as system]))

(defn restart-backend!
  ([] (restart-backend! {}))
  ([overrides]
   (require 'eacl-solidjs.api :reload)
   (require 'eacl-solidjs.contracts :reload)
   (require 'eacl-solidjs.data :reload)
   (require 'eacl-solidjs.system :reload)
   (system/restart!
    (config/from-env (System/getenv) overrides))))

(defn run-tests!
  []
  (doseq [namespace '[eacl-solidjs.config
                      eacl-solidjs.data
                      eacl-solidjs.runtime
                      eacl-solidjs.contracts
                      eacl-solidjs.api
                      eacl-solidjs.http
                      eacl-solidjs.system]]
    (require namespace :reload))
  (doseq [namespace '[eacl-solidjs.config-test
                      eacl-solidjs.benchmark-stats-test
                      eacl-solidjs.contracts-test
                      eacl-solidjs.api-test
                      eacl-solidjs.http-test
                      eacl-solidjs.system-test
                      eacl-solidjs.integration-test]]
    (require namespace :reload))
  (apply test/run-tests
         '[eacl-solidjs.config-test
           eacl-solidjs.benchmark-stats-test
           eacl-solidjs.contracts-test
           eacl-solidjs.api-test
           eacl-solidjs.http-test
           eacl-solidjs.system-test
           eacl-solidjs.integration-test]))
