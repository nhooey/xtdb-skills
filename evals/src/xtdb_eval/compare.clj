(ns xtdb-eval.compare
  "The eval's pure parts: rewriting tables private to one example, comparing
  a query's rows or error with what an example expects, and finding the
  ```clojure blocks in a skill. Nothing here talks to XTDB, so Babashka runs
  its tests (`bb test`) without a node."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.io PushbackReader StringReader]
           [java.time Instant OffsetDateTime ZonedDateTime]
           [java.util Date]))

(defn private-tables
  "Rewrite `:run/x` keywords, and the keywords in `extra`, to
  `:x-<run-id>-<n>`, n unique to one example; also where they're written
  inside a string, such as the XTQL embedded in an [:sql ...] op."
  [run-id n extra form]
  (let [extra (set extra)
        private (fn [k] (keyword (str (name k) "-" run-id "-" n)))
        in-string (re-pattern (str ":(run/[\\w-]+"
                                   (str/join (for [k extra] (str "|" (java.util.regex.Pattern/quote (name k)))))
                                   ")(?![\\w/-])"))]
    (walk/postwalk #(cond
                      (and (keyword? %) (or (= "run" (namespace %)) (extra %))) (private %)
                      (string? %) (str/replace % in-string (fn [[_ k]] (str (private (keyword k)))))
                      :else %)
                   form)))

(defn norm-key
  "A column name as compared: `-` and `_` alike, and :xt/ keys as `_` names."
  [k]
  (-> (if (= "xt" (namespace k)) (str "_" (name k)) (name k))
      (str/replace "-" "_")))

(defn norm-val
  "A value as compared: numbers as doubles, timestamps as instants."
  [v]
  (cond
    (number? v) (double v)
    (instance? Date v) (str (.toInstant ^Date v))
    (instance? ZonedDateTime v) (str (.toInstant ^ZonedDateTime v))
    (instance? OffsetDateTime v) (str (.toInstant ^OffsetDateTime v))
    (instance? Instant v) (str v)
    :else v))

(defn norm-rows
  "Rows keyed by normalised column name. Unless `exact-keys?`, every row
  carries every column, absent ones as nil; with it, absent stays absent."
  [rows cols exact-keys?]
  (mapv (fn [row]
          (merge (if exact-keys? {} (zipmap cols (repeat nil)))
                 (into {} (map (fn [[k v]] [(norm-key k) (norm-val v)])) row)))
        rows))

(defn columns [expected] (set (map norm-key (mapcat keys expected))))

(defn compare-rows
  "[status detail] for a query's `rows` against an example's :expected,
  in order with :ordered?, and with absent columns distinct from nil ones
  with :exact-keys?."
  [rows {:keys [expected ordered? exact-keys?]}]
  (let [cols (columns expected)
        got-cols (set (mapcat #(map norm-key (keys %)) rows))]
    (if (and (seq rows) (not (set/subset? got-cols cols)))
      [:wrong (str "columns " (sort got-cols) ", expected " (sort cols))]
      (let [got (norm-rows rows cols exact-keys?)
            want (norm-rows expected cols exact-keys?)
            same (if ordered? (= got want) (= (frequencies got) (frequencies want)))]
        (if same
          [:pass nil]
          [:wrong (str "got " (pr-str got) ", expected " (pr-str want))])))))

(defn expected-error
  "[status detail] for an `error` message, or nil for none, against an
  example's :expect-error, a substring the message must contain."
  [error expect-error]
  (if (and error (str/includes? error expect-error))
    [:pass nil]
    [:wrong (if error
              (str "error " (pr-str error) ", expected one containing " (pr-str expect-error))
              "no error, expected one")]))

(defn skill-blocks
  "[[ids-or-nil forms line]] for every ```clojure block in a skill's text:
  the ids of the `<!-- xtql-example: id… -->` line before it, its top-level
  forms, and the line of its opening fence."
  [text]
  (let [lines (vec (str/split-lines text))]
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
