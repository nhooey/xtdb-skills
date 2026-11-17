(ns skills-test
  "What holds across the skills without XTDB: each folder is the skill its
  frontmatter names, every skill one names exists, and every eval suite
  points at a skill."
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(def ^:private root (fs/parent (fs/parent (fs/absolutize *file*))))

(defn- skill-dirs []
  (sort (map str (fs/list-dir (fs/path root "skills") fs/directory?))))

(defn- skill-names [] (set (map fs/file-name (skill-dirs))))

(defn- frontmatter-name
  "The `name` in a SKILL.md's frontmatter, read as YAML."
  [text]
  (when-let [[_ fm] (re-find #"(?s)\A---\r?\n(.*?)\r?\n---\r?\n" text)]
    (:name (yaml/parse-string fm))))

(deftest frontmatter-name-reads-yaml
  (is (= "xtdb-a" (frontmatter-name "---\nname: xtdb-a\ndescription: d\n---\nbody\n")))
  (is (= "xtdb-a" (frontmatter-name "---\nname: \"xtdb-a\"\ndescription: d\n---\n")))
  (is (= "xtdb-a" (frontmatter-name "---\nname: 'xtdb-a' # the folder\n---\n")))
  (is (= "xtdb-a" (frontmatter-name "---\r\nname: xtdb-a\r\n---\r\n")))
  (is (nil? (frontmatter-name "# no frontmatter\n"))))

(deftest each-skill-folder-holds-the-skill-it-names
  (is (seq (skill-dirs)))
  (doseq [dir (skill-dirs)
          :let [f (fs/path dir "SKILL.md")]]
    (testing dir
      (is (fs/exists? f))
      (when (fs/exists? f)
        (is (= (fs/file-name dir) (frontmatter-name (slurp (str f)))))))))

(defn named-skills
  "Every backticked `xtdb-<name>` in a skill's text: how one skill sends the
  agent to another."
  [text]
  (set (map second (re-seq #"`(xtdb-[a-z0-9-]+)`" text))))

(deftest named-skills-reads-backticked-names
  (is (= #{"xtdb-write-path" "xtdb-storage-gc"}
         (named-skills "Load `xtdb-write-path`, then `xtdb-storage-gc`; not xtdb-core or `xtdb.api`."))))

(deftest every-skill-a-skill-names-exists
  (doseq [dir (skill-dirs)
          :let [missing (remove (skill-names) (named-skills (slurp (str (fs/path dir "SKILL.md")))))]]
    (testing dir
      (is (empty? missing)))))

(deftest every-eval-suite-points-at-a-skill
  (doseq [suite (fs/list-dir (fs/path root "evals"))
          :let [f (fs/path suite "examples.edn")]
          :when (fs/exists? f)
          :let [{:keys [skill]} (edn/read-string {:default (fn [_ v] v)} (slurp (str f)))
                target (fs/normalize (fs/path suite skill))]]
    (testing (str f)
      (is (fs/exists? target))
      (is (= (str (fs/path root "skills" (fs/file-name suite) "SKILL.md")) (str target))))))
