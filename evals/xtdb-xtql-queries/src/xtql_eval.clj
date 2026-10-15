(ns xtql-eval
  "Score XTQL answers and the skill's examples against an XTDB node.

    clojure -M:eval seed                     submit seed.edn
    clojure -M:eval prompt                   print the task sheet for an answerer
    clojure -M:eval score ANSWERS.edn ...    score answer files ({task-id query-string})
    clojure -M:eval examples                 run the skill's examples and the observations

  Connects with the XTDB client to localhost:15432 (XTDB_HOST and XTDB_PORT
  override it). Column names compare with `-` and `_` alike and :xt/ keys as
  `_` names; numbers compare as doubles and timestamps as instants."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [xtdb.api :as xt])
  (:import [java.io PushbackReader StringReader]
           [java.time Instant OffsetDateTime ZonedDateTime]
           [java.util Date]))

(defn- conn []
  (xt/client {:host (or (System/getenv "XTDB_HOST") "localhost")
              :port (parse-long (or (System/getenv "XTDB_PORT") "15432"))
              :user "xtdb"}))

(defn- read-edn [file]
  (edn/read-string {:readers (merge default-data-readers *data-readers*)} (slurp file)))

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
  "Rows keyed by normalised column name, every row carrying every column."
  [rows cols]
  (mapv (fn [row]
          (merge (zipmap cols (repeat nil))
                 (into {} (map (fn [[k v]] [(norm-key k) (norm-val v)])) row)))
        rows))

(defn- columns [expected] (set (map norm-key (keys (first expected)))))

(defn- run-query [c query args]
  (try
    {:rows (xt/q c (if (seq args) (into [query] args) query))}
    (catch Exception e
      {:error (or (ex-message e) (str (class e)))})))

(defn- judge
  "[status detail] for one query against an example or task spec."
  [c query {:keys [args expected expect-error ordered?]}]
  (let [{:keys [rows error]} (run-query c query args)]
    (cond
      expect-error (cond
                     (and error (str/includes? error expect-error)) [:pass nil]
                     error [:wrong (str "error " (pr-str error) ", expected one containing " (pr-str expect-error))]
                     :else [:wrong (str "returned " (count rows) " rows, expected an error")])
      error [:error error]
      :else (let [cols (columns expected)
                  got-cols (set (mapcat #(map norm-key (keys %)) rows))]
              (if (and (seq rows) (not (set/subset? got-cols cols)))
                [:wrong (str "columns " (sort got-cols) ", expected " (sort cols))]
                (let [got (norm-rows rows cols)
                      want (norm-rows expected cols)
                      same (if ordered? (= got want) (= (frequencies got) (frequencies want)))]
                  (if same
                    [:pass nil]
                    [:wrong (str "got " (pr-str got) ", expected " (pr-str want))])))))))

;; --- commands --------------------------------------------------------------

(defn- seed! []
  (let [c (conn)]
    (doseq [tx (read-edn "seed.edn")]
      (xt/execute-tx c tx))
    (println "seeded" (count (read-edn "seed.edn")) "transactions")))

(defn- prompt! []
  (let [{:keys [schema tasks]} (read-edn "tasks.edn")]
    (println (str/trim schema))
    (println)
    (doseq [{:keys [id prompt expected]} tasks]
      (println (str id ": " prompt " Output columns: "
                    (str/join ", " (map #(subs (str %) 1) (keys (first expected)))) ".")))))

(defn- read-query [s]
  (binding [*read-eval* false]
    (read-string s)))

(defn- score! [files]
  (let [c (conn)
        {:keys [tasks]} (read-edn "tasks.edn")
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
  (let [lines (vec (str/split-lines (slurp path)))]
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

(defn- examples! []
  (let [c (conn)
        {:keys [skill example]} (read-edn "examples.edn")
        by-id (into {} (map (juxt :id identity)) example)
        report (fn [status id detail] (println (format "%-9s %s%s" (name status) id (if detail (str ": " detail) ""))))
        from-skill (vec (for [[ids forms line] (skill-blocks skill) :when (not= ids ["skip"])]
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
        unused (for [{:keys [id] :as ex} example :when (and (not (contains? ex :query)) (not (used id)))]
                 [id :unused "in examples.edn but not in the skill"])
        all (concat skill-results observations unused)]
    (doseq [[id status detail] all] (report status id detail))
    (let [failed (count (remove #(= :pass (second %)) all))]
      (println (str "\n" (- (count all) failed) "/" (count all) " passed"))
      failed)))

(defn -main [& [cmd & args]]
  (let [failed (case cmd
                 "seed" (do (seed!) 0)
                 "prompt" (do (prompt!) 0)
                 "score" (do (score! args) 0)
                 "examples" (examples!)
                 (do (println (:doc (meta (find-ns (quote xtql-eval))))) 2))]
    (shutdown-agents)
    (System/exit (if (pos? failed) 1 0))))
