#!/usr/bin/env bb
;; Run eval suites against every XTDB 2.x tag, and record which examples
;; hold on which tags, so a skill's observed footnotes can say since when.
;;
;;   bb scripts/eval-releases.clj SUITE... [--tags REGEX] [--storage]
;;
;; For each tag of xtdb/xtdb (betas and release candidates included) it
;; runs `examples` (or, with --storage, `storage`) from evals/ with the
;; xtdb-api client of the same version. From v2.0.0-beta8.4 the client
;; reaches ghcr.io/xtdb/xtdb:<version> on port 15432, seeded first by the
;; suites that have a seed.edn; before it the client has no `xt/client`,
;; so the eval starts a node of that version's xtdb-core in its own
;; process, and the storage checks, which inspect a container's files, do
;; not run. A tag with no image or no client on Maven Central, or whose node
;; refuses a suite's config, is recorded as untested, not failed.
;;
;; Writes evals/SUITE/releases.edn: {:tags [[tag status]...] :results
;; {id [[tag status]...]}}, in release order. `skill-citations lock` reads it.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(def evals (str (fs/path root "evals")))
(def tag-re #"^v(2\.\d+\.\d+)(?:-((?:beta|rc)\d+(?:\.\d+)?))?$")
;; XTDB_PORT, which the eval reads too, moves it off a port already in use.
(def port (parse-long (or (System/getenv "XTDB_PORT") "15432")))

(defn sh [& args] (apply p/shell {:out :string :err :string :continue true} args))

(defn tags
  "Every 2.x tag of xtdb/xtdb in release order: by version, then
  prereleases before the release, by commit date."
  []
  (let [git-dir (str (or (System/getenv "XDG_CACHE_HOME") (str (fs/home) "/.cache"))
                     "/skill-citations/github.com/xtdb/xtdb.git")
        _ (sh "git" "--git-dir" git-dir "fetch" "--quiet" "--tags" "--force"
              "https://github.com/xtdb/xtdb.git")
        out (:out (sh "git" "--git-dir" git-dir "for-each-ref" "--sort=creatordate"
                      "--format=%(refname:short)" "refs/tags"))]
    (->> (str/split-lines out)
         (keep-indexed (fn [i t] (when-let [[_ v pre] (re-matches tag-re t)]
                                   {:tag t :version (str v (when pre (str "-" pre)))
                                    :order [(mapv parse-long (str/split v #"\.")) (if pre 0 1) i]})))
         (sort-by :order)
         (mapv #(dissoc % :order)))))

(defn image? [version]
  (zero? (:exit (sh "docker" "manifest" "inspect" (str "ghcr.io/xtdb/xtdb:" version)))))

(defn deps
  "The client at `version`, with xtdb-core for a node in the eval's own
  process."
  [version]
  (pr-str {:deps {'com.xtdb/xtdb-api {:mvn/version version}
                  'com.xtdb/xtdb-core {:mvn/version version}}}))

(defn client
  "How the eval reaches a node at `version`: :remote through `xt/client`,
  :in-process without it, or nil when the client does not resolve from
  Maven Central."
  [version]
  (when (zero? (:exit (p/shell {:dir evals :out :string :err :string :continue true}
                               "clojure" "-Sdeps" (deps version) "-Spath")))
    (let [jar (str (fs/home) "/.m2/repository/com/xtdb/xtdb-api/" version "/xtdb-api-" version ".jar")]
      (with-open [z (java.util.zip.ZipFile. jar)]
        (if (str/includes? (slurp (.getInputStream z (.getEntry z "xtdb/api.clj"))) "(defn client")
          :remote
          :in-process)))))

(defn await-node
  "Wait for the node's \"Node started\" log line: Docker opens the port
  before XTDB listens on it."
  [container]
  (loop [n 180]
    (let [{:keys [out err]} (sh "docker" "logs" container)]
      (cond (str/includes? (str out err) "Node started") nil
            (zero? n) (throw (ex-info "node did not start" {}))
            :else (do (Thread/sleep 1000) (recur (dec n)))))))

(defn run-tag [{:keys [version]} client suites storage?]
  (let [out (str (fs/create-temp-file {:suffix ".edn"}))
        eval! (fn [env & args]
                (apply p/shell {:dir evals :out :string :err :string :continue true
                                :extra-env (merge {"EVAL_RESULTS" out} env)}
                       "clojure" "-Sdeps" (deps version)
                       "-J--add-opens=java.base/java.nio=ALL-UNNAMED" "-M:eval" args))
        report! #(println (str/join " " (re-seq #"\d+/\d+ passed" (:out %))))
        result #(if (fs/exists? out) (edn/read-string (slurp out)) {})]
    (cond
      storage?
      (let [refused (set (for [s suites
                               :let [r (eval! {"XTDB_IMAGE" (str "ghcr.io/xtdb/xtdb:" version)} "storage" s)]
                               :when (do (report! r) (str/includes? (:out r) "config refused"))]
                           s))]
        (merge (result) (zipmap refused (repeat :config-refused))))

      (= :in-process client)
      (do (report! (apply eval! {} "examples" suites))
          (result))

      :else
      (let [container (str "xtdb-eval-releases-" version)]
        (sh "docker" "run" "-d" "--rm" "--name" container "-p" (str port ":5432")
            (str "ghcr.io/xtdb/xtdb:" version))
        (try
          (await-node container)
          (doseq [s suites :when (fs/exists? (fs/path evals s "seed.edn"))]
            (eval! {} "seed" s))
          (report! (apply eval! {} "examples" suites))
          (result)
          (finally (sh "docker" "stop" container)))))))

(defn -main [args]
  (let [storage? (some #{"--storage"} args)
        tag-filter (some-> (second (drop-while #(not= "--tags" %) args)) re-pattern)
        suites (vec (remove #(or (str/starts-with? % "--") (and tag-filter (= (str tag-filter) %))) args))
        all (cond->> (tags) tag-filter (filter #(re-find tag-filter (:tag %))))
        record (atom (into {} (for [s suites] [s {:tags [] :results (sorted-map)}])))]
    (doseq [{:keys [tag version] :as t} all]
      (print (str tag ": ")) (flush)
      (let [image (image? version)
            client (when image (client version))
            status (cond (not image) :no-image
                         (not client) :no-client
                         (and storage? (= :in-process client)) :no-client)
            res (when-not status
                  (try (run-tag t client suites storage?)
                       (catch Exception e (println (ex-message e)) nil)))]
        (when status (println (name status)))
        (doseq [s suites
                :let [r (get res s)
                      st (cond status status
                               (keyword? r) r
                               (seq r) :tested
                               :else :runner-error)]]
          (swap! record update s
                 (fn [rec]
                   (-> rec
                       (update :tags conj [tag st])
                       (update :results (fn [m] (reduce-kv (fn [m id v] (update m id (fnil conj []) [tag v])) m (when (map? r) r))))))))))
    (doseq [[s {:keys [tags results]}] @record]
      (let [f (str (fs/path evals s "releases.edn"))]
        (spit f (str ";; Written by scripts/eval-releases.clj: each tag's status, and each example's\n"
                     ";; result on every tag it ran on. `skill-citations lock` reads it.\n"
                     (pr-str {:tags tags :results results})
                     "\n"))
        (println "wrote" f)))))

(-main *command-line-args*)
