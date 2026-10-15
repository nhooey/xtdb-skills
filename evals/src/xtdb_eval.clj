(ns xtdb-eval
  "Score XTQL answers, and run each skill's examples, against an XTDB node.

  Run from evals/, naming a suite directory (one per skill):

    clojure -M:eval seed SUITE                   submit SUITE/seed.edn
    clojure -M:eval prompt SUITE                 print SUITE/tasks.edn as a task sheet
    clojure -M:eval score SUITE ANSWERS.edn ...  score answer files ({task-id query-string})
    clojure -M:eval examples SUITE ...           run each suite's examples.edn
    clojure -M:eval storage SUITE                compare storage with the GC on and off

  Connects with the XTDB client to localhost:15432 (XTDB_HOST and XTDB_PORT
  override it). Column names compare with `-` and `_` alike and :xt/ keys as
  `_` names; numbers compare as doubles and timestamps as instants.

  An example's :setup transactions run before its query. Keywords in the
  `run` namespace (`:run/docs`) name tables private to one example in one
  run: each becomes `:docs-<run id>-<n>`, so a setup never meets data from
  another example or an earlier run. An example's :private list names more
  tables to treat the same way, for queries quoted in a skill that use plain
  table names."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [xtdb.api :as xt])
  (:import [java.io File PushbackReader StringReader]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant OffsetDateTime ZonedDateTime]
           [java.util Date]))

(defn- conn []
  (xt/client {:host (or (System/getenv "XTDB_HOST") "localhost")
              :port (parse-long (or (System/getenv "XTDB_PORT") "15432"))
              :user "xtdb"}))

(defn- read-edn [file]
  (edn/read-string {:readers (merge default-data-readers *data-readers*)} (slurp (str file))))

(def ^:private run-id (str (System/currentTimeMillis)))
(def ^:private example-count (atom 0))

(defn- private-tables
  "Rewrite `:run/x` keywords, and the keywords in `extra`, to `:x-<run id>-<n>`,
  n unique to one example."
  [n extra form]
  (let [extra (set extra)]
    (walk/postwalk #(if (and (keyword? %) (or (= "run" (namespace %)) (extra %)))
                      (keyword (str (name %) "-" run-id "-" n))
                      %)
                   form)))

;; --- comparison ------------------------------------------------------------

(defn- norm-key [k]
  (-> (if (= "xt" (namespace k)) (str "_" (name k)) (name k))
      (str/replace "-" "_")))

(defn- norm-val [v]
  (cond
    (number? v) (double v)
    (instance? Date v) (str (.toInstant ^Date v))
    (instance? ZonedDateTime v) (str (.toInstant ^ZonedDateTime v))
    (instance? OffsetDateTime v) (str (.toInstant ^OffsetDateTime v))
    (instance? Instant v) (str v)
    :else v))

(defn- norm-rows
  "Rows keyed by normalised column name. Unless `exact-keys?`, every row
  carries every column, absent ones as nil; with it, absent stays absent."
  [rows cols exact-keys?]
  (mapv (fn [row]
          (merge (if exact-keys? {} (zipmap cols (repeat nil)))
                 (into {} (map (fn [[k v]] [(norm-key k) (norm-val v)])) row)))
        rows))

(defn- columns [expected] (set (map norm-key (mapcat keys expected))))

(defn- run-query [c query args]
  (try
    {:rows (xt/q c (if (seq args) (into [query] args) query))}
    (catch Exception e
      {:error (or (ex-message e) (str (class e)))})))

(defn- judge
  "[status detail] for one query against an example or task spec."
  [c query {:keys [args setup private expected expect-error ordered? exact-keys?]}]
  (let [n (swap! example-count inc)
        setup-error (try (doseq [tx (private-tables n private setup)] (xt/execute-tx c tx)) nil
                         (catch Exception e (or (ex-message e) (str (class e)))))
        {:keys [rows error]} (if setup-error
                               {:error (str "setup failed: " setup-error)}
                               (run-query c (private-tables n private query) args))]
    (cond
      setup-error [:error error]
      expect-error (cond
                     (and error (str/includes? error expect-error)) [:pass nil]
                     error [:wrong (str "error " (pr-str error) ", expected one containing " (pr-str expect-error))]
                     :else [:wrong (str "returned " (count rows) " rows, expected an error")])
      error [:error error]
      :else (let [cols (columns expected)
                  got-cols (set (mapcat #(map norm-key (keys %)) rows))]
              (if (and (seq rows) (not (set/subset? got-cols cols)))
                [:wrong (str "columns " (sort got-cols) ", expected " (sort cols))]
                (let [got (norm-rows rows cols exact-keys?)
                      want (norm-rows expected cols exact-keys?)
                      same (if ordered? (= got want) (= (frequencies got) (frequencies want)))]
                  (if same
                    [:pass nil]
                    [:wrong (str "got " (pr-str got) ", expected " (pr-str want))])))))))

;; --- commands --------------------------------------------------------------

(defn- seed! [suite]
  (let [c (conn)
        txs (read-edn (io/file suite "seed.edn"))]
    (doseq [tx txs] (xt/execute-tx c tx))
    (println "seeded" (count txs) "transactions")))

(defn- prompt! [suite]
  (let [{:keys [schema tasks]} (read-edn (io/file suite "tasks.edn"))]
    (println (str/trim schema))
    (println)
    (doseq [{:keys [id prompt expected]} tasks]
      (println (str id ": " prompt " Output columns: "
                    (str/join ", " (map #(subs (str %) 1) (keys (first expected)))) ".")))))

(defn- read-query [s]
  (binding [*read-eval* false]
    (read-string s)))

(defn- score! [suite files]
  (let [c (conn)
        {:keys [tasks]} (read-edn (io/file suite "tasks.edn"))
        answers (into (sorted-map) (for [f files] [(str/replace (.getName (io/file f)) #"\.edn$" "") (read-edn f)]))
        results (into {} (for [[n ans] answers]
                           [n (into {} (for [{:keys [id] :as t} tasks]
                                         [id (if-let [q (get ans id)]
                                               (try (judge c (read-query q) t)
                                                    (catch Exception e [:error (str "unreadable: " (ex-message e))]))
                                               [:missing "no answer"])]))]))
        width (apply max (map (comp count :id) tasks))
        row (fn [label cells] (println (apply str (format (str "%-" width "s  ") label)
                                              (map #(format "%-12s" %) cells))))]
    (row "task" (keys answers))
    (doseq [{:keys [id]} tasks]
      (row id (for [n (keys answers)] (name (first (get-in results [n id]))))))
    (row "passed" (for [n (keys answers)]
                    (str (count (filter #(= :pass (first %)) (vals (results n)))) "/" (count tasks))))
    (println)
    (doseq [n (keys answers), {:keys [id]} tasks
            :let [[status detail] (get-in results [n id])] :when (not= :pass status)]
      (println (str n " " id " " (name status) ": " detail)))))

(defn- skill-blocks
  "[[ids-or-nil forms line]] for every ```clojure block in the skill."
  [path]
  (let [lines (vec (str/split-lines (slurp (str path))))]
    (loop [i 0, out []]
      (if (>= i (count lines))
        out
        (if (= "```clojure" (str/trim (lines i)))
          (let [j (loop [j (inc i)] (if (= "```" (str/trim (lines j))) j (recur (inc j))))
                marker (when (pos? i) (re-matches #"^<!--\s*xtql-example:\s*(.*?)\s*-->\s*$" (lines (dec i))))
                rdr (PushbackReader. (StringReader. (str/join "\n" (subvec lines (inc i) j))))
                forms (binding [*read-eval* false]
                        (loop [fs []]
                          (let [f (read {:eof ::eof} rdr)]
                            (if (= ::eof f) fs (recur (conj fs f))))))]
            (recur (inc j) (conj out [(some-> marker second (str/split #"\s+")) forms (inc i)])))
          (recur (inc i) out))))))

(defn- examples!
  "Run one suite's examples; returns the number that failed."
  [c suite]
  (let [{:keys [skill example]} (read-edn (io/file suite "examples.edn"))
        skill-file (io/file suite skill)
        by-id (into {} (map (juxt :id identity)) example)
        report (fn [status id detail] (println (format "%-9s %s%s" (name status) id (if detail (str ": " detail) ""))))
        from-skill (vec (for [[ids forms line] (skill-blocks skill-file) :when (not= ids ["skip"])]
                          (cond
                            (nil? ids) [:unmarked (str "SKILL.md:" line) "precede it with <!-- xtql-example: id… --> or skip"]
                            (not= (count ids) (count forms)) [:mismatch (str "SKILL.md:" line) (str (count ids) " ids for " (count forms) " forms")]
                            :else (for [[id form] (map vector ids forms)]
                                    (if-let [ex (by-id id)]
                                      (into [id] (judge c form ex))
                                      [id :unknown (str "SKILL.md:" line ": not in examples.edn")])))))
        skill-results (mapcat #(if (keyword? (first %)) [[(second %) (first %) (nth % 2)]] %) from-skill)
        used (set (map first skill-results))
        observations (for [{:keys [id query]} example :when (contains? (by-id id) :query)]
                       (into [id] (judge c query (by-id id))))
        unused (for [{:keys [id] :as ex} example :when (and (not (contains? ex :query)) (not (contains? ex :check)) (not (used id)))]
                 [id :unused "in examples.edn but not in the skill"])
        all (concat skill-results observations unused)]
    (println (str "== " suite))
    (doseq [[id status detail] all] (report status id detail))
    (let [failed (count (remove #(= :pass (second %)) all))]
      (println (str (- (count all) failed) "/" (count all) " passed\n"))
      failed)))

;; --- storage ---------------------------------------------------------------

(defn- sh! [& args]
  (let [{:keys [exit out err]} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str (str/join " " args) " failed: " err) {})))
    (str out err)))

(defn- storage-run
  "Start `image` with SUITE/`config` on an empty data directory, write the
  same rows `rounds` times, wait `settle-ms`, and return the files it left
  and the node's log."
  [suite config {:keys [image port rounds rows settle-ms]}]
  (let [data (.toFile (Files/createTempDirectory "xtdb-storage" (make-array FileAttribute 0)))
        container (str "xtdb-eval-storage-" run-id)]
    (sh! "docker" "run" "-d" "--rm" "--name" container "-p" (str port ":5432")
         "-v" (str (.getCanonicalPath (io/file suite)) ":/cfg")
         "-v" (str (.getCanonicalPath data) ":/var/lib/xtdb")
         "-e" "JAVA_TOOL_OPTIONS=-Dlogback.configurationFile=/cfg/logback.xml"
         image "-f" (str "/cfg/" config))
    (try
      (let [c (xt/client {:host "localhost" :port port :user "xtdb"})]
        (loop [tries 60]
          (when-not (try (xt/q c '(rel [{:a 1}] [a])) (catch Exception _ nil))
            (when (zero? tries) (throw (ex-info "node did not start" {})))
            (Thread/sleep 1000)
            (recur (dec tries))))
        (dotimes [round rounds]
          (xt/execute-tx c [(into [:put-docs :docs]
                                  (for [i (range rows)]
                                    {:xt/id i, :round round, :pad (apply str (repeat 200 "x"))}))]))
        (let [log-after-writes (.length (io/file data "log" "LOG"))]
          (Thread/sleep settle-ms)
          {:log-after-writes log-after-writes
           :files (set (for [^File f (file-seq data) :when (.isFile f)]
                         (str (.relativize (.toPath data) (.toPath f)))))
           :log-bytes (.length (io/file data "log" "LOG"))
           :node-log (sh! "docker" "logs" container)}))
      (finally (sh! "docker" "stop" container)))))

(defn- storage-facts
  "Each check's [passed? detail], from the runs with the GC on and off."
  [on off keep]
  (let [count-of (fn [run re] (count (filter #(re-find re %) (:files run))))
        global-blocks #"^buffers/v\d+/blocks/"
        l0 #"/(data|meta)/l00-"
        l1+ #"/(data|meta)/l0[1-9]-"
        both (fn [re] (str "on " (count-of on re) ", off " (count-of off re)))]
    {:gc-starts [(str/includes? (:node-log on) "Starting GarbageCollector")
                 "gc-on.yaml's node logs the collector starting"]
     :gc-off-silent [(not (str/includes? (:node-log off) "GarbageCollector"))
                     "gc-off.yaml's node logs no collector"]
     :keeps-recent-blocks [(and (= keep (count-of on global-blocks)) (> (count-of off global-blocks) keep))
                           (str "global block files: " (both global-blocks))]
     :deletes-superseded [(< (count-of on l1+) (count-of off l1+))
                          (str "level-1+ files: " (both l1+))]
     :keeps-l0 [(and (pos? (count-of on l0)) (= (count-of on l0) (count-of off l0)))
                (str "level-0 files: " (both l0))]
     :log-untouched [(and (pos? (:log-after-writes on)) (>= (:log-bytes on) (:log-after-writes on)))
                     (str "GC on, LOG bytes after the writes " (:log-after-writes on)
                          ", after the collector ran " (:log-bytes on) "; GC off, " (:log-bytes off))]}))

(defn- storage!
  "Run one suite's storage checks; returns the number that failed."
  [suite]
  (let [{:keys [storage example]} (read-edn (io/file suite "examples.edn"))
        on (storage-run suite "gc-on.yaml" storage)
        off (storage-run suite "gc-off.yaml" storage)
        facts (storage-facts on off (:blocks-to-keep storage))
        results (for [{:keys [id check]} example :when check]
                  (let [[ok detail] (get facts check [false (str "unknown check " check)])]
                    [id ok detail]))]
    (println (str "== " suite))
    (doseq [[id ok detail] results]
      (println (format "%-9s %s: %s" (if ok "pass" "wrong") id detail)))
    (let [failed (count (remove second results))]
      (println (str (- (count results) failed) "/" (count results) " passed\n"))
      failed)))

(defn -main [& [cmd suite & args]]
  (let [failed (case cmd
                 "seed" (do (seed! suite) 0)
                 "storage" (storage! suite)
                 "prompt" (do (prompt! suite) 0)
                 "score" (do (score! suite args) 0)
                 "examples" (let [c (conn)] (reduce + (map #(examples! c %) (cons suite args))))
                 (do (println (:doc (meta (find-ns 'xtdb-eval)))) 2))]
    (shutdown-agents)
    (System/exit (if (pos? failed) 1 0))))
