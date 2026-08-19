(ns eacl-datahike-demo.build-info-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eacl-datahike-demo.build-info :as build-info]
            [eacl-datahike-demo.test-support :as support])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.jar JarEntry JarOutputStream]))

(def ^:private published-commit
  "f4be377a139f9bc9dfcb9c40f91418bdbf3a4b3d")

(defn- pom-xml
  [tag]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<project>\n"
       "  <modelVersion>4.0.0</modelVersion>\n"
       "  <parent>\n"
       "    <groupId>example</groupId>\n"
       "    <artifactId>parent</artifactId>\n"
       "    <version>99.0.0</version>\n"
       "  </parent>\n"
       "  <groupId>dev.eacl</groupId>\n"
       "  <artifactId>eacl-datahike</artifactId>\n"
       "  <version>8.0.0-SNAPSHOT</version>\n"
       "  <dependencies>\n"
       "    <dependency>\n"
       "      <groupId>org.clojure</groupId>\n"
       "      <artifactId>clojure</artifactId>\n"
       "      <version>1.11.4</version>\n"
       "    </dependency>\n"
       "  </dependencies>\n"
       "  <scm>\n"
       "    <url>https://github.com/theronic/eacl</url>\n"
       "    <tag>" tag "</tag>\n"
       "  </scm>\n"
       "</project>\n"))

(defn- temp-dir
  ^File []
  (.toFile (Files/createTempDirectory "eacl-build-info"
                                      (make-array FileAttribute 0))))

(defn- write-jar!
  "Writes a jar containing the adapter POM at its Maven path plus `filler`, so
  two jars with different filler have different content."
  [^File file pom filler]
  (with-open [out (JarOutputStream. (io/output-stream file))]
    (.putNextEntry out (JarEntry. "META-INF/maven/dev.eacl/eacl-datahike/pom.xml"))
    (.write out (.getBytes ^String pom "UTF-8"))
    (.closeEntry out)
    (.putNextEntry out (JarEntry. "filler.txt"))
    (.write out (.getBytes ^String filler "UTF-8"))
    (.closeEntry out))
  file)

(deftest pom-parsing-ignores-parent-and-dependency-versions
  (is (= {:version "8.0.0-SNAPSHOT" :scm-tag published-commit}
         (build-info/parse-pom (pom-xml published-commit))))
  (is (= {:version "8.0.0-SNAPSHOT" :scm-tag "HEAD"}
         (build-info/parse-pom (pom-xml "HEAD"))))
  (is (= {:version nil :scm-tag nil}
         (build-info/parse-pom "<project/>"))))

(deftest only-full-commit-shas-identify-a-published-build
  (is (= published-commit (build-info/commit-sha published-commit)))
  (is (nil? (build-info/commit-sha "HEAD")))
  (is (nil? (build-info/commit-sha "f4be377")))
  (is (nil? (build-info/commit-sha nil))))

(deftest published-snapshot-is-proved-by-identical-sibling-content
  (let [dir (temp-dir)
        pom (pom-xml published-commit)
        snapshot (write-jar! (io/file dir "eacl-datahike-8.0.0-SNAPSHOT.jar")
                             pom "build seven")]
    (testing "a locally installed snapshot has no timestamped sibling"
      (is (nil? (build-info/published-snapshot-version
                 build-info/adapter-lib snapshot))))
    (testing "the byte-identical timestamped download names the build"
      (write-jar! (io/file dir "eacl-datahike-8.0.0-20260818.222338-6.jar")
                  pom "build six")
      (write-jar! (io/file dir "eacl-datahike-8.0.0-20260818.233134-7.jar")
                  pom "build seven")
      (is (= "8.0.0-20260818.233134-7"
             (build-info/published-snapshot-version
              build-info/adapter-lib snapshot)))
      (let [provenance (build-info/artifact-provenance
                        build-info/adapter-lib snapshot)]
        (is (= {:lib "dev.eacl/eacl-datahike"
                :version "8.0.0-SNAPSHOT"
                :resolved-version "8.0.0-20260818.233134-7"
                :commit published-commit}
               (dissoc provenance :jar-sha256)))
        (is (= (build-info/sha256 snapshot) (:jar-sha256 provenance)))))
    (testing "a local build stamps HEAD, which is not a commit"
      (let [local (write-jar! (io/file dir "eacl-datahike-8.0.0-SNAPSHOT.jar")
                              (pom-xml "HEAD") "local build")
            provenance (build-info/artifact-provenance
                        build-info/adapter-lib local)]
        (is (nil? (:resolved-version provenance)))
        (is (nil? (:commit provenance)))
        (is (= "8.0.0-SNAPSHOT" (:version provenance)))))))

(deftest source-checkout-derives-eacl-identity-from-the-classpath
  (let [info (build-info/read-build-info)
        adapter (get-in info [:eacl :adapter])
        core (get-in info [:eacl :core])]
    (is (true? (:development? info)))
    (is (= "eacl-datahike-demo" (:application info)))
    (is (= build-info/source-repository (get-in info [:source :repository])))
    (is (= build-info/eacl-repository (get-in info [:eacl :repository])))
    (is (= "dev.eacl/eacl-datahike" (:lib adapter)))
    (is (= "dev.eacl/eacl" (:lib core)))
    (is (string? (:version adapter)))
    (is (re-matches #"[0-9a-f]{64}" (:jar-sha256 adapter)))
    (is (or (nil? (:commit adapter))
            (build-info/commit-sha (:commit adapter))))))

(deftest health-and-bootstrap-report-the-build
  (support/with-test-system [system]
    (let [handler (:handler system)
          health (support/request handler :get "/api/health")
          bootstrap (support/request handler :get "/api/bootstrap")]
      (is (= 200 (:status health)))
      (is (= 200 (:status bootstrap)))
      (doseq [response [health bootstrap]
              :let [build (:build (support/data response))]]
        (is (= "eacl-datahike-demo" (:application build)))
        (is (true? (:development build)))
        (is (= "https://github.com/theronic/eacl-datomic-solidjs"
               (get-in build [:source :repository])))
        (is (= "dev.eacl/eacl-datahike" (get-in build [:eacl :adapter :lib])))
        (is (not (str/blank? (get-in build [:eacl :adapter :jarSha256]))))))))
