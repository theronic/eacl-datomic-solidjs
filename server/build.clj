(ns build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [eacl-datahike-demo.build-info :as build-info])
  (:import [java.nio.file Files]
           [java.time Instant]))

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

(defn- git
  "Runs git against the repository that contains server/. Returns trimmed
  stdout, or nil when git is unavailable or the command fails."
  [& args]
  (try
    (let [{:keys [exit out]} (b/process {:command-args (into ["git"] args)
                                         :dir ".."
                                         :out :capture
                                         :err :capture})]
      (when (zero? exit)
        (str/trim (str out))))
    (catch Exception _
      nil)))

(defn- source-provenance
  "Identifies the demo checkout being built. GitHub Actions variables win so a
  CI build records its commit even from a detached checkout."
  []
  (let [env (System/getenv)
        commit (or (build-info/commit-sha (get env "GITHUB_SHA"))
                   (build-info/commit-sha (git "rev-parse" "HEAD")))
        ref (or (not-empty (get env "GITHUB_REF_NAME"))
                (not-empty (git "branch" "--show-current")))
        status (git "status" "--porcelain" "--untracked-files=no")]
    {:repository build-info/source-repository
     :commit commit
     :ref ref
     ;; nil when git could not report the working tree state.
     :dirty? (some-> status str/blank? not)
     :committed-at (when commit
                     (git "show" "-s" "--format=%cI" commit))}))

(defn- resolved-jar
  [lib]
  (let [paths (get-in basis [:libs lib :paths])
        jar (some->> paths
                     (filter #(str/ends-with? % ".jar"))
                     first
                     io/file)]
    (when-not (and jar (.exists ^java.io.File jar))
      (throw (ex-info "The resolved dependency has no jar on disk."
                      {:lib lib :paths paths})))
    jar))

(defn- resolved-version
  [lib]
  (get-in basis [:libs lib :mvn/version]))

(defn- eacl-provenance
  "Describes the EACL adapter and core jars that tools.deps resolved for this
  build: their published Clojars snapshot, publishing commit, and checksums."
  []
  (let [adapter (build-info/artifact-provenance
                 build-info/adapter-lib (resolved-jar build-info/adapter-lib))
        core (build-info/artifact-provenance
              build-info/core-lib (resolved-jar build-info/core-lib))]
    (doseq [{:keys [lib resolved-version]} [adapter core]
            :when (nil? resolved-version)]
      (binding [*out* *err*]
        (println (str "WARNING: " lib " resolved to a snapshot that no Clojars "
                      "download matches (locally installed or unpublished). "
                      "The deployed footer will report it as unpublished."))))
    {:repository build-info/eacl-repository
     :requested-version (resolved-version build-info/adapter-lib)
     :adapter adapter
     :core core}))

(defn build-metadata
  "The provenance record embedded in the uberjar and served by the API."
  []
  {:application build-info/application
   :built-at (str (Instant/now))
   :java {:target 26
          :runtime (str (Runtime/version))}
   :source (source-provenance)
   :eacl (eacl-provenance)
   :dependencies {:datahike (resolved-version 'org.replikativ/datahike)
                  :datahike-lmdb (resolved-version 'org.replikativ/datahike-lmdb)
                  :konserve-s3 (resolved-version 'org.replikativ/konserve-s3)
                  :manifest-sha256 (build-info/sha256 "../docs/dependency-tree.txt")}})

(defn- write-build-metadata!
  []
  (let [path (str class-dir "/" build-info/resource-path)
        metadata (build-metadata)]
    (io/make-parents path)
    (spit path (pr-str metadata))
    metadata))

(defn provenance
  "Prints the provenance record this checkout would embed, without building."
  [_]
  (prn (build-metadata)))

(defn uber
  [_]
  (require-java-26!)
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (let [metadata (write-build-metadata!)]
    (b/compile-clj {:basis basis
                    :src-dirs ["src"]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'eacl-datahike-demo.main})
    (spit (str uber-file ".sha256")
          (str (build-info/sha256 uber-file) "  "
               (.getName (io/file uber-file)) "\n"))
    {:uber-file (.getAbsolutePath (io/file uber-file))
     :sha256 (build-info/sha256 uber-file)
     :bytes (Files/size (.toPath (io/file uber-file)))
     :source (select-keys (:source metadata) [:commit :ref :dirty?])
     :eacl (-> metadata :eacl :adapter
               (select-keys [:resolved-version :commit]))}))
