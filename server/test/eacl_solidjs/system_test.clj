(ns eacl-solidjs.system-test
  (:require [clojure.test :refer [deftest is]]
            [eacl-solidjs.config :as config]
            [eacl-solidjs.system :as system]))

(deftest bounded-jetty-admission-options
  (let [options (system/jetty-options config/default-config)]
    (is (= 2 (:min-threads options)))
    (is (= 16 (:max-threads options)))
    (is (= 64 (:max-queued-requests options)))
    (is (true? (:async? options)))
    (is (= 30000 (:async-timeout options)))
    (is (= 30000 (:max-idle-time options)))
    (is (fn? (:configurator options)))
    (is (false? (:join? options)))))
