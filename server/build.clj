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
            :eacl-datahike/resolved-version "8.0.0-SNAPSHOT"
            :eacl-datahike/source-pr 115
            :eacl-datahike/source-commit
            "142882c56e2e4f0c4e37a5740fd0f0db96d066e9"
            :eacl-datahike/jar-sha256
            "4ca345d6d23fd3e4779e63df791cd529feaf44cf09737f07f1fbc42d1c6be501"
            :eacl-core/jar-sha256
            "6747516f56f6a867b9ac0140d2e0493d0fdedcff201a040c1b870ac3b4a2ab5b"
            :datahike/version "0.8.1759"
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
