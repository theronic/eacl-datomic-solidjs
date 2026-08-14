(ns build
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b])
  (:import [java.math BigInteger]
           [java.nio.file Files]
           [java.security MessageDigest]))

(def class-dir "target/classes")
(def uber-file "target/eacl-datahike-demo.jar")
(def basis (b/create-basis {:project "deps.edn"}))

(defn- require-java-26!
  []
  (let [feature (.feature (Runtime/version))]
    (when (< feature 26)
      (throw (ex-info "The production artifact requires Java 26 or newer."
                      {:java/feature feature})))))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn- sha256
  [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream path)]
      (let [buffer (byte-array 65536)]
        (loop []
          (let [read (.read input buffer)]
            (when (pos? read)
              (.update digest buffer 0 read)
              (recur))))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- write-build-metadata!
  []
  (let [path (str class-dir "/META-INF/eacl-datahike-demo/build.edn")]
    (io/make-parents path)
    (spit path
          (pr-str
           {:application "eacl-datahike-demo"
            :eacl-datahike/requested-version "8.0.0-SNAPSHOT"
            :eacl-datahike/resolved-version "8.0.0-20260814.204412-4"
            :eacl-datahike/source-pr 116
            :eacl-datahike/source-commit
            "6cce96f15164fe42d1e2b55e58e32c307d5d0942"
            :eacl-datahike/jar-sha256
            "e7ce549d764f872e42efc3ea201bb0e18a0ad0b7e9a3998c496e4acd5aba0c79"
            :eacl-core/jar-sha256
            "d9793db2644ea123a6e28f335ec66003f5fd72494794ca8b23479dc4d7e5a1e7"
            :datahike/version "0.8.1759"
            :datahike-lmdb/version "0.1.8"
            :konserve-s3/version "0.1.37"
            :java/target 26
            :dependency-manifest-sha256
            (sha256 "../docs/dependency-tree.txt")}))))

(defn uber
  [_]
  (require-java-26!)
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (write-build-metadata!)
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'eacl-datahike-demo.main})
  (spit (str uber-file ".sha256")
        (str (sha256 uber-file) "  " (.getName (io/file uber-file)) "\n"))
  {:uber-file (.getAbsolutePath (io/file uber-file))
   :sha256 (sha256 uber-file)
   :bytes (Files/size (.toPath (io/file uber-file)))})
