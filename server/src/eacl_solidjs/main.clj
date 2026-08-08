(ns eacl-solidjs.main
  (:gen-class)
  (:require [eacl-solidjs.system :as system]))

(defn -main
  [& _args]
  (system/start!)
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread.
    ^Runnable
    (reify Runnable
      (run [_]
        (system/stop!))))))
