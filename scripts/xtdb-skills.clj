#!/usr/bin/env bb
;; xtdb-skills: the repository's commands, one tool with a command per job.
;;
;;   xtdb-skills test                              unit tests (no XTDB, Docker or network)
;;   xtdb-skills node start|stop                   the eval's XTDB container
;;   xtdb-skills eval seed|prompt|score|examples|storage SUITE...
;;   xtdb-skills releases SUITE... [--storage] [--tags REGEX]
;;   xtdb-skills drift [SKILL...] [--to TAG] [--report FILE]
;;
;; Every command takes --help. It runs from the checkout holding the current
;; directory, so the dev shell and `nix run .#xtdb-skills` act on the same
;; files. Each command but `drift` builds one process plan, a pure value
;; that --dry-run prints instead of running.
;;
;; Exit status: 0 success, 1 failure (or, for `drift`, a cited span
;; differs), 2 bad usage, 3 `skill-citations` itself failed.

(ns xtdb-skills
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The commands, as data: usage, help and options for each.

(def default-port 15432)
(def container "xtdb-eval")

(def port-opt {:port {:desc "Host port of the eval's XTDB node" :coerce :long :default default-port}})
(def dry-run-opt {:dry-run {:desc "Print the command instead of running it" :coerce :boolean}})

(def commands
  {["test"]
   {:usage "xtdb-skills test"
    :summary "Run the unit tests"
    :help "Run the unit tests: the eval's pure parts, the release script's tag
ordering and merging, this tool's plans, and the skills' names. None needs
XTDB, Docker or the network; `nix flake check` runs them too."
    :spec dry-run-opt}

   ["node" "start"]
   {:usage "xtdb-skills node start [--port 15432] [--version 2.2.0]"
    :summary "Start the eval's XTDB node in Docker"
    :help "Start the eval's XTDB node in Docker, as container `xtdb-eval`, at the
version evals/deps.edn pins for the client unless --version names another."
    :spec (merge port-opt dry-run-opt
                 {:version {:desc "XTDB image tag (default: evals/deps.edn's xtdb-api version)"}})}

   ["node" "stop"]
   {:usage "xtdb-skills node stop"
    :summary "Stop the eval's XTDB node"
    :help "Stop the eval's XTDB container, which removes it."
    :spec dry-run-opt}

   ["eval" "seed"]
   {:usage "xtdb-skills eval seed SUITE [--port 15432]"
    :summary "Load a suite's seed.edn into the node"
    :help "Submit SUITE/seed.edn, the suite's fixture, to the eval's node."
    :args [1 1] :spec (merge port-opt dry-run-opt)}

   ["eval" "prompt"]
   {:usage "xtdb-skills eval prompt SUITE"
    :summary "Print a suite's task sheet"
    :help "Print SUITE/tasks.edn as the task sheet an answerer gets."
    :args [1 1] :spec dry-run-opt}

   ["eval" "score"]
   {:usage "xtdb-skills eval score SUITE ANSWERS.edn... [--port 15432]"
    :summary "Score answer files against a suite's tasks"
    :help "Score answer files, each a map of task id to query string, against
SUITE/tasks.edn on the eval's node."
    :args [2 nil] :spec (merge port-opt dry-run-opt)}

   ["eval" "examples"]
   {:usage "xtdb-skills eval examples SUITE... [--port 15432]"
    :summary "Run suites' examples on the node"
    :help "Run each suite's examples.edn on the eval's node."
    :args [1 nil] :spec (merge port-opt dry-run-opt)}

   ["eval" "storage"]
   {:usage "xtdb-skills eval storage SUITE [ID...]"
    :summary "Run a suite's storage checks on nodes of its own"
    :help "Run SUITE's storage checks, or only the examples named, on nodes of
their own. xtdb-storage-gc and xtdb-write-path need Docker."
    :args [1 nil] :spec dry-run-opt}

   ["releases"]
   {:usage "xtdb-skills releases SUITE... [--storage] [--tags REGEX] [--port 15432]"
    :summary "Run suites on every XTDB 2.x tag, write releases.edn"
    :help "Run suites against every XTDB 2.x tag with an image and a client on
Maven Central, and write evals/SUITE/releases.edn, which `skill-citations
lock` turns into footnotes. --tags narrows the tags and merges into the file."
    :args [1 nil]
    :spec (merge port-opt dry-run-opt
                 {:storage {:desc "Run the storage suites instead of the examples" :coerce :boolean}
                  :tags {:desc "Only the tags this regex finds"}})}

   ["drift"]
   {:usage "xtdb-skills drift [SKILL...] [--to latest] [--report FILE] [--log-url URL]"
    :summary "Check every skill's citations against a newer tag"
    :help "Run `skill-citations drift` for each skill (default: every skill with a
citations.edn), printing its diffs. --report writes an issue body: the steps
to bump, then each drifted skill's report without its diffs, cut to fit in
an issue. Exits 1 when any cited span differs."
    :args [0 nil]
    :spec {:to {:desc "Tag to compare against" :default "latest"}
           :report {:desc "Write the issue body here"}
           :log-url {:desc "Link the issue body gives for the full diffs"}}}})

(def groups
  {["node"] "The eval's XTDB node in Docker."
   ["eval"] "The skill evals, run from evals/ with the XTDB client."})

;; ---------------------------------------------------------------------------
;; Pure core: parsing, plans and the drift report.

(defn parse
  "What `args` asks for: {:help path}, {:error msg path}, or {:command path
  :opts opts :args positional}."
  [args]
  (let [[path cmd] (or (some (fn [n] (let [path (vec (take n args))]
                                       (when-let [cmd (commands path)] [path cmd])))
                             [2 1])
                       [nil nil])
        prefix (vec (take 1 args))]
    (cond
      (or (empty? args) (#{"-h" "--help" "help"} (first args))) {:help []}
      (nil? cmd) (if (groups prefix)
                   (if (or (= 1 (count args)) (#{"-h" "--help"} (second args)))
                     {:help prefix}
                     {:error (str "unknown command: " (str/join " " (take 2 args))) :path prefix})
                   {:error (str "unknown command: " (first args)) :path []})
      :else
      (let [rest-args (drop (count path) args)]
        (if (some #{"-h" "--help"} rest-args)
          {:help path}
          (try
            (let [{:keys [opts args]} (cli/parse-args rest-args {:spec (:spec cmd) :restrict true})
                  [lo hi] (or (:args cmd) [0 0])
                  n (count args)]
              (if (or (< n lo) (and hi (> n hi)))
                {:error (str "expected " (if (= lo hi) lo (str lo (if hi (str " to " hi) " or more")))
                             " argument" (when (not= 1 (or hi lo)) "s") ", got " n)
                 :path path}
                {:command path :opts opts :args (vec args)}))
            (catch clojure.lang.ExceptionInfo e
              {:error (ex-message e) :path path})))))))

(defn help-text
  "Help for `path`: the tool's commands at [], a group's, or one command's
usage, help and options."
  [path]
  (if-let [cmd (commands path)]
    (str "Usage: " (:usage cmd) "\n\n" (:help cmd) "\n"
         (when (seq (:spec cmd))
           (str "\nOptions:\n" (cli/format-opts {:spec (:spec cmd)}) "\n")))
    (let [under (sort-by #(str/join " " %) (filter #(= path (vec (take (count path) %))) (keys commands)))]
      (str "Usage: xtdb-skills " (str/join " " (concat path ["<command>"])) " [options]\n\n"
           (or (groups path) "The xtdb-skills repository's commands.") "\n\nCommands:\n"
           (str/join "\n" (for [k under]
                            (format "  %-15s %s" (str/join " " k) (:summary (commands k)))))
           "\n\nRun any command with --help for its arguments and options.\n"))))

(defn client-version
  "The xtdb-api version in `deps-edn`, evals/deps.edn's text."
  [deps-edn]
  (get-in (edn/read-string deps-edn) [:deps 'com.xtdb/xtdb-api :mvn/version]))

(defn plan
  "The process `command` runs: {:cmd [...] :dir dir :env {...}}. `root` is
the checkout; `version` is the client version evals/deps.edn pins."
  [root version {:keys [command opts args]}]
  (let [evals (str (fs/path root "evals"))
        port-env (when (:port opts) {"XTDB_PORT" (str (:port opts))})]
    (case command
      ["test"] {:cmd ["bb" "test"] :dir root}
      ["node" "start"] {:cmd ["docker" "run" "-d" "--rm" "--name" container
                              "-p" (str (:port opts) ":5432")
                              (str "ghcr.io/xtdb/xtdb:" (or (:version opts) version))]
                        :dir root}
      ["node" "stop"] {:cmd ["docker" "stop" container] :dir root}
      ["releases"] {:cmd (cond-> (into ["bb" (str (fs/path root "scripts" "eval-releases.clj"))] args)
                           (:storage opts) (conj "--storage")
                           (:tags opts) (conj "--tags" (:tags opts)))
                    :dir root :env port-env}
      ;; every `eval` command
      {:cmd (into ["clojure" "-M:eval" (second command)] args) :dir evals :env port-env})))

(defn drift-summary
  "`skill-citations drift` output without its diff lines, which can push an
issue body past GitHub's limit."
  [out]
  (str/join "\n" (remove #(re-find #"^ {12}[-+]" %) (str/split-lines out))))

(def report-limit
  "GitHub caps an issue body at 65,536 characters by its own count; stop
well short of it."
  50000)

(defn drift-report
  "The issue body for `drifted`, [[skill output]...]: the steps to bump, a
link to the full diffs, then each skill's summary, cut at `report-limit` on a
line boundary with its code block closed."
  [drifted log-url]
  (let [body (str "A newer XTDB tag changes spans the skills cite. For each skill:\n\n"
                  "1. Fix the claims whose spans changed, then `skill-citations bump <skill> --to <tag>`.\n"
                  "2. Move the eval to the same version: the `ghcr.io/xtdb/xtdb` image and the client in `evals/deps.edn`.\n"
                  "3. Re-run `xtdb-skills eval examples` and `xtdb-skills eval storage`, which re-check the observed footnotes.\n\n"
                  "The diffs of each changed span are in "
                  (if log-url (str "[the run's log](" log-url ")") "the run's log")
                  ", or from `skill-citations drift <skill> --to latest`.\n\n"
                  (str/join (for [[skill out] drifted]
                              (str "## " skill "\n\n```\n" (drift-summary out) "\n```\n\n"))))]
    (if (<= (count body) report-limit)
      body
      (let [cut (subs body 0 report-limit)
            kept (subs cut 0 (inc (str/last-index-of cut "\n")))
            open? (odd? (count (re-seq #"(?m)^```" kept)))]
        (str kept
             (when open? "```\n")
             "\nCut at 50,000 characters; the whole report is in the run's log.\n")))))

;; ---------------------------------------------------------------------------
;; Shell: the checkout, running processes, printing and exiting.

(defn root
  "The checkout holding the current directory."
  []
  (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                    "git" "rev-parse" "--show-toplevel")
        dir (if (zero? exit) (str/trim out) (System/getenv "PRJ_ROOT"))]
    (when-not (and dir (fs/exists? (fs/path dir "skills")) (fs/exists? (fs/path dir "evals")))
      (binding [*out* *err*] (println "xtdb-skills: run it inside an xtdb-skills checkout"))
      (System/exit 1))
    dir))

(defn exec! [{:keys [cmd dir env]}]
  (:exit (apply p/shell {:dir dir :extra-env (or env {}) :continue true} cmd)))

(defn drift! [root {:keys [opts args]}]
  (let [skills (if (seq args)
                 args
                 (sort (map #(str (fs/relativize root (fs/parent %)))
                            (fs/glob root "skills/*/citations.edn"))))
        results (doall
                 (for [skill skills]
                   (let [{:keys [exit out err]} (p/shell {:dir root :out :string :err :string :continue true}
                                                         "skill-citations" "drift" skill "--to" (:to opts))
                         out (str out err)]
                     (println out)
                     [skill exit out])))
        failed (remove #(#{0 1} (second %)) results)
        drifted (for [[skill exit out] results :when (= 1 exit)] [skill out])]
    (when (:report opts)
      (spit (:report opts) (drift-report drifted (:log-url opts))))
    (cond (seq failed) (do (binding [*out* *err*]
                             (println "skill-citations failed on" (str/join ", " (map first failed))))
                           3)
          (seq drifted) 1
          :else 0)))

(defn -main [args]
  (let [{:keys [help error command opts] :as parsed} (parse args)]
    (cond
      help (do (print (help-text help)) (flush) 0)
      error (do (binding [*out* *err*]
                  (println (str "xtdb-skills: " error))
                  (print (help-text (:path parsed))) (flush))
                2)
      (= ["drift"] command) (drift! (root) parsed)
      :else (let [r (root)
                  pl (plan r (client-version (slurp (str (fs/path r "evals" "deps.edn")))) parsed)]
              (if (:dry-run opts)
                (do (println (str (when (seq (:env pl))
                                    (str (str/join " " (for [[k v] (:env pl)] (str k "=" v))) " "))
                                  (str/join " " (:cmd pl))
                                  "  (in " (:dir pl) ")"))
                    0)
                (if (zero? (exec! pl)) 0 1))))))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (-main *command-line-args*)))
