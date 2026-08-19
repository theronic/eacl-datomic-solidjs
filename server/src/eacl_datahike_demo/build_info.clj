(ns eacl-datahike-demo.build-info
  "Identifies the running build: the demo source commit and the exact EACL
  snapshot it embeds.

  The uberjar carries `META-INF/eacl-datahike-demo/build.edn`, written by
  `server/build.clj` from the resolved dependency basis and the Git checkout.
  A source checkout has no such file, so a development build derives what it
  can from the classpath: Maven POMs inside the resolved EACL jars record the
  publishing commit in their `<scm><tag>`, and tools.deps keeps the timestamped
  Clojars download next to its `-SNAPSHOT` copy, so a content match proves
  which published snapshot is in use.

  This namespace depends only on Clojure core and the JDK because the build
  tool loads it outside the application classpath."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.math BigInteger]
           [java.net URL]
           [java.security MessageDigest]
           [java.util.jar JarFile]
           [java.util.regex Pattern]))

(def application "eacl-datahike-demo")
(def source-repository "https://github.com/theronic/eacl-datomic-solidjs")
(def eacl-repository "https://github.com/theronic/eacl")
(def adapter-lib 'dev.eacl/eacl-datahike)
(def core-lib 'dev.eacl/eacl)
(def resource-path "META-INF/eacl-datahike-demo/build.edn")

(def ^:private commit-pattern #"^[0-9a-f]{40}$")

(defn sha256
  "Lower-case hex SHA-256 of a file."
  [file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [input (io/input-stream file)]
      (loop []
        (let [read (.read input buffer)]
          (when (pos? read)
            (.update digest buffer 0 read)
            (recur)))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn commit-sha
  "Returns `value` when it is a full Git commit SHA, otherwise nil. CI builds
  stamp `GITHUB_SHA` into the EACL POM; local builds stamp the literal HEAD."
  [value]
  (when (and (string? value) (re-matches commit-pattern value))
    value))

(defn parse-pom
  "Extracts the artifact version and `<scm><tag>` from Maven POM XML."
  [xml]
  (let [xml (str xml)
        ;; The project version precedes <dependencies>; parent and dependency
        ;; versions must not be mistaken for it.
        head (-> (first (str/split xml #"<dependencies>" 2))
                 (str/replace #"(?s)<parent>.*?</parent>" ""))]
    {:version (some-> (re-find #"<version>([^<]+)</version>" head) second str/trim)
     :scm-tag (some-> (re-find #"(?s)<scm>.*?<tag>([^<]+)</tag>" xml)
                      second
                      str/trim)}))

(defn- pom-entry-name
  [lib]
  (str "META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml"))

(defn jar-pom
  "Reads the Maven POM that `lib` was built with from inside its jar."
  [lib ^File jar-file]
  (with-open [jar (JarFile. jar-file)]
    (when-let [entry (.getJarEntry jar (pom-entry-name lib))]
      (with-open [input (.getInputStream jar entry)]
        (slurp input)))))

(defn- timestamped-jar-pattern
  [lib]
  (re-pattern (str "^" (Pattern/quote (name lib))
                   "-(.+-\\d{8}\\.\\d{6}-\\d+)\\.jar$")))

(defn published-snapshot-version
  "Finds the timestamped snapshot whose jar content equals `jar-file`.

  Maven keeps the timestamped download (`lib-8.0.0-20260817.210259-5.jar`)
  beside the `-SNAPSHOT` copy that tools.deps puts on the classpath. A
  byte-identical sibling therefore names the published build; a locally
  installed snapshot has no such sibling and returns nil."
  [lib ^File jar-file]
  (let [pattern (timestamped-jar-pattern lib)
        siblings (some-> jar-file .getAbsoluteFile .getParentFile .listFiles seq)]
    (when (seq siblings)
      (let [content (sha256 jar-file)]
        (->> siblings
             (keep (fn [^File candidate]
                     (when-let [[_ version] (re-matches pattern
                                                        (.getName candidate))]
                       (when (= content (sha256 candidate))
                         version))))
             sort
             last)))))

(defn artifact-provenance
  "Describes the resolved jar for `lib`: the Maven version its POM declares,
  the published snapshot it matches (nil when unpublished), the EACL commit it
  was built from (nil when unknown), and its SHA-256."
  [lib ^File jar-file]
  (let [{:keys [version scm-tag]} (some->> (jar-pom lib jar-file) parse-pom)]
    {:lib (str lib)
     :version version
     :resolved-version (published-snapshot-version lib jar-file)
     :commit (commit-sha scm-tag)
     :jar-sha256 (sha256 jar-file)}))

(defn- resource-jar-file
  "The jar containing a classpath resource, or nil for a directory resource."
  [^URL resource]
  (when (= "jar" (.getProtocol resource))
    (let [path (.getPath resource)
          jar-url (subs path 0 (str/index-of path "!/"))]
      (io/file (.toURI (URL. jar-url))))))

(defn classpath-jar
  "Locates the jar that supplies `lib` on the running classpath."
  [lib]
  (some-> (io/resource (pom-entry-name lib)) resource-jar-file))

(defn development-build-info
  "Build information for a source checkout, derived from the classpath."
  []
  {:application application
   :development? true
   :built-at nil
   :source {:repository source-repository
            :commit nil
            :ref nil
            :dirty? nil
            :committed-at nil}
   :eacl {:repository eacl-repository
          :adapter (some->> (classpath-jar adapter-lib)
                            (artifact-provenance adapter-lib))
          :core (some->> (classpath-jar core-lib)
                         (artifact-provenance core-lib))}})

(defn read-build-info
  "The packaged build record when running from the uberjar, otherwise a
  development record derived from the classpath."
  []
  (if-let [resource (io/resource resource-path)]
    (assoc (edn/read-string (slurp resource)) :development? false)
    (development-build-info)))
