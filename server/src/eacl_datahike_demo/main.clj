(ns eacl-datahike-demo.main
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [eacl-datahike-demo.system :as system]))

(defn -main
  [& _args]
  (try
    (system/start!)
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread.
      ^Runnable
      (reify Runnable
        (run [_]
          (system/stop!)))))
    (catch Throwable throwable
      ;; Startup exceptions can carry storage client configuration. Production
      ;; logs retain the failure class while deliberately omitting the message,
      ;; ex-data, and stack trace.
      (log/error "EACL Datahike demo failed to start"
                 {:exception-class (.getName (class throwable))})
      (throw (ex-info "EACL Datahike demo failed to start."
                      {:type :eacl-datahike-demo/startup-failed}
                      nil)))))
