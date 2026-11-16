(ns xtdb-skills-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; The tool has no namespace file of its own name; load it without running
;; it (its -main runs only when bb runs the file itself).
(load-file (str (fs/path (fs/parent (fs/parent (fs/absolutize *file*))) "scripts" "xtdb-skills.clj")))

(def parse (resolve 'xtdb-skills/parse))
(def plan (resolve 'xtdb-skills/plan))
(def resolve-invocation (resolve 'xtdb-skills/resolve-invocation))
(def help-text (resolve 'xtdb-skills/help-text))
(def client-version (resolve 'xtdb-skills/client-version))
(def drift-summary (resolve 'xtdb-skills/drift-summary))
(def drift-report (resolve 'xtdb-skills/drift-report))
(def commands @(resolve 'xtdb-skills/commands))

(deftest parse-asks-for-help
  (testing "with no arguments, help or --help"
    (is (= {:help []} (parse [])))
    (is (= {:help []} (parse ["--help"])))
    (is (= {:help []} (parse ["help"]))))
  (testing "for a group named alone or with --help"
    (is (= {:help ["eval"]} (parse ["eval"])))
    (is (= {:help ["node"]} (parse ["node" "--help"]))))
  (testing "for a command with -h anywhere after it"
    (is (= {:help ["eval" "seed"]} (parse ["eval" "seed" "suite" "-h"])))))

(deftest parse-reads-a-command
  (is (= {:command ["eval" "score"] :opts {} :args ["s" "a.edn" "b.edn"]}
         (parse ["eval" "score" "s" "a.edn" "b.edn"])))
  (is (= {:command ["releases"] :opts {:storage true :tags "v2\\.1"} :args ["s"]}
         (parse ["releases" "s" "--storage" "--tags" "v2\\.1"])))
  (is (= {:command ["node" "start"] :opts {:port 1 :version "2.1.0"} :args []}
         (parse ["node" "start" "--port" "1" "--version" "2.1.0"])))
  (is (= {:command ["drift"] :opts {:to "latest"} :args ["skills/a"]}
         (parse ["drift" "skills/a"]))))

(deftest parse-rejects-bad-usage
  (testing "an unknown command or subcommand"
    (is (= [] (:path (parse ["bogus"]))))
    (is (= ["eval"] (:path (parse ["eval" "bogus"])))))
  (testing "too few or too many arguments"
    (is (:error (parse ["eval" "seed"])))
    (is (:error (parse ["eval" "seed" "a" "b"])))
    (is (:error (parse ["eval" "score" "s"])))
    (is (:error (parse ["test" "extra"]))))
  (testing "an option the command doesn't take"
    (is (str/includes? (:error (parse ["eval" "prompt" "s" "--port" "1"])) "--port"))))

(deftest help-lists-every-command
  (let [top (help-text [])]
    (doseq [k (keys commands)]
      (is (str/includes? top (str/join " " k)) (str k " missing from the top-level help")))))

(deftest every-command-has-usage-summary-and-help
  (doseq [[k cmd] commands]
    (is (str/starts-with? (:usage cmd) (str "xtdb-skills " (str/join " " k))) (str k))
    (is (not (str/includes? (:summary cmd) "\n")) (str k))
    (is (str/includes? (help-text k) (:help cmd)) (str k))))

(deftest client-version-reads-deps-edn
  (is (= "2.2.0" (client-version "{:deps {com.xtdb/xtdb-api {:mvn/version \"2.2.0\"}}}"))))

(deftest plans
  (let [p #(plan "/r" "2.2.0" (resolve-invocation (parse %) {:cwd "/home"}))]
    (testing "the eval runs from evals/ with the port in XTDB_PORT"
      (is (= {:cmd ["clojure" "-M:eval" "examples" "a" "b"] :dir "/r/evals" :env {"XTDB_PORT" "9"}}
             (p ["eval" "examples" "a" "b" "--port" "9"]))))
    (testing "prompt and storage start no client, so pass no port"
      (is (= {:cmd ["clojure" "-M:eval" "prompt" "s"] :dir "/r/evals" :env nil}
             (p ["eval" "prompt" "s"]))))
    (testing "the node runs the client's version unless told otherwise"
      (is (= "ghcr.io/xtdb/xtdb:2.2.0" (last (:cmd (p ["node" "start"])))))
      (is (= "ghcr.io/xtdb/xtdb:2.1.0" (last (:cmd (p ["node" "start" "--version" "2.1.0"])))))
      (is (some #{"15432:5432"} (:cmd (p ["node" "start"])))))
    (testing "releases passes its options to the release script"
      (is (= ["bb" "/r/scripts/eval-releases.clj" "s" "--storage" "--tags" "v2"]
             (:cmd (p ["releases" "s" "--storage" "--tags" "v2"])))))
    (testing "test runs the bb task from the checkout"
      (is (= {:cmd ["bb" "test"] :dir "/r"} (p ["test"]))))))

(deftest resolve-invocation-takes-the-port-from-xtdb-port
  (let [r #(get-in (resolve-invocation (parse %1) {:cwd "/home" :env-port %2}) [:opts :port])]
    (testing "XTDB_PORT when --port isn't given, as the eval itself reads it"
      (is (= 16000 (r ["eval" "examples" "s"] 16000)))
      (is (= 16000 (r ["node" "start"] 16000)))
      (is (= 16000 (r ["releases" "s"] 16000))))
    (testing "--port over XTDB_PORT"
      (is (= 9 (r ["eval" "examples" "s" "--port" "9"] 16000))))
    (testing "15432 with neither"
      (is (= 15432 (r ["eval" "seed" "s"] nil))))
    (testing "no port for a command that takes none"
      (is (nil? (r ["eval" "prompt" "s"] 16000))))))

(deftest resolve-invocation-reads-answer-files-from-the-callers-directory
  (testing "the eval runs from evals/, so a relative answer file is resolved first"
    (is (= ["s" "/home/out/a.edn" "/abs/b.edn"]
           (:args (resolve-invocation (parse ["eval" "score" "s" "out/a.edn" "/abs/b.edn"])
                                      {:cwd "/home"})))))
  (testing "a suite name and other commands' arguments stay as given"
    (is (= ["s" "id-1"]
           (:args (resolve-invocation (parse ["eval" "storage" "s" "id-1"]) {:cwd "/home"}))))))

(deftest drift-summary-drops-diff-lines
  (is (= "skills/a\n  [changed] x\n  context"
         (drift-summary "skills/a\n  [changed] x\n            - old\n            + new\n  context"))))

(deftest drift-report-holds-each-drifted-skill
  (let [r (drift-report [["skills/a" "a changed\n            - gone"] ["skills/b" "b moved"]]
                        "https://example.test/run")]
    (is (str/includes? r "[the run's log](https://example.test/run)"))
    (is (str/includes? r "## skills/a\n\n```\na changed\n```"))
    (is (str/includes? r "## skills/b\n\n```\nb moved\n```"))
    (is (not (str/includes? r "gone")))))

(deftest drift-report-cuts-a-long-report-on-a-line
  (let [line (apply str (repeat 99 "x"))
        r (drift-report [["skills/a" (str/join "\n" (repeat 1000 line))]] nil)]
    (is (< (count r) 50100))
    (is (str/ends-with? r "```\n\nCut at 50,000 characters; the whole report is in the run's log.\n"))
    (testing "every code block is closed, wherever the cut lands"
      (doseq [width (range 1 40)
              :let [skill (str "skills/" (apply str (repeat width "s")))
                    r (drift-report (repeat 3000 [skill "x"]) nil)]]
        (is (even? (count (re-seq #"(?m)^```" r))) (str "skill name width " width))))
    (testing "no line is cut short"
      (is (every? #(or (not (str/starts-with? % "x")) (= line %)) (str/split-lines r))))))
