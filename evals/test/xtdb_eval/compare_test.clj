(ns xtdb-eval.compare-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [xtdb-eval.compare :as c])
  (:import [java.time Instant OffsetDateTime ZoneId ZoneOffset ZonedDateTime]
           [java.util Date]))

(deftest norm-key-treats-dash-and-underscore-alike
  (is (= "first_name" (c/norm-key :first-name) (c/norm-key :first_name)))
  (is (= "_id" (c/norm-key :xt/id) (c/norm-key :_id)))
  (is (= "_valid_from" (c/norm-key :xt/valid-from)))
  (testing "a namespace other than xt is dropped, not prefixed"
    (is (= "name" (c/norm-key :person/name)))))

(deftest norm-val-compares-numbers-as-doubles-and-timestamps-as-instants
  (is (= 1.0 (c/norm-val 1) (c/norm-val 1.0) (c/norm-val 1M) (c/norm-val 1N)))
  (let [i (Instant/parse "2026-01-02T03:04:05Z")]
    (is (= "2026-01-02T03:04:05Z"
           (c/norm-val i)
           (c/norm-val (Date/from i))
           (c/norm-val (ZonedDateTime/ofInstant i (ZoneId/of "America/Toronto")))
           (c/norm-val (OffsetDateTime/ofInstant i (ZoneOffset/ofHours 9))))))
  (is (= "x" (c/norm-val "x")))
  (is (nil? (c/norm-val nil)))
  (is (= :k (c/norm-val :k))))

(deftest compare-rows-passes-equal-rows-in-any-order
  (is (= [:pass nil] (c/compare-rows [{:a 1} {:a 2}] {:expected [{:a 2} {:a 1}]})))
  (testing "column names and number types normalise before comparing"
    (is (= [:pass nil] (c/compare-rows [{:xt/id 1 :first_name "a"}]
                                       {:expected [{:_id 1.0 :first-name "a"}]})))))

(deftest compare-rows-with-ordered-fails-on-order
  (is (= :pass (first (c/compare-rows [{:a 1} {:a 2}] {:expected [{:a 1} {:a 2}] :ordered? true}))))
  (is (= :wrong (first (c/compare-rows [{:a 2} {:a 1}] {:expected [{:a 1} {:a 2}] :ordered? true})))))

(deftest compare-rows-counts-duplicates
  (is (= :wrong (first (c/compare-rows [{:a 1} {:a 1}] {:expected [{:a 1}]}))))
  (is (= :wrong (first (c/compare-rows [{:a 1}] {:expected [{:a 1} {:a 1}]})))))

(deftest compare-rows-rejects-an-unexpected-column
  (let [[status detail] (c/compare-rows [{:a 1 :b 2}] {:expected [{:a 1}]})]
    (is (= :wrong status))
    (is (str/starts-with? detail "columns (\"a\" \"b\")"))))

(deftest compare-rows-wants-rows-when-rows-are-expected
  (is (= :wrong (first (c/compare-rows [] {:expected [{:a 1}]}))))
  (is (= [:pass nil] (c/compare-rows [] {:expected []}))))

(deftest compare-rows-absent-column-against-nil
  (testing "by default an absent column equals a nil one"
    (is (= [:pass nil] (c/compare-rows [{:a 1}] {:expected [{:a 1 :b nil}]}))))
  (testing "with :exact-keys? it does not"
    (is (= :wrong (first (c/compare-rows [{:a 1}] {:expected [{:a 1 :b nil}] :exact-keys? true}))))
    (is (= [:pass nil] (c/compare-rows [{:a 1 :b nil}] {:expected [{:a 1 :b nil}] :exact-keys? true})))))

(deftest compare-rows-differing-values-fail-with-both-sides
  (let [[status detail] (c/compare-rows [{:a 1}] {:expected [{:a 2}]})]
    (is (= :wrong status))
    (is (= "got [{\"a\" 1.0}], expected [{\"a\" 2.0}]" detail))))

(deftest expected-error-matches-a-substring
  (is (= [:pass nil] (c/expected-error "boom: xtdb/oops happened" "xtdb/oops")))
  (is (= [:wrong "error \"boom\", expected one containing \"xtdb/oops\""]
         (c/expected-error "boom" "xtdb/oops")))
  (is (= [:wrong "no error, expected one"] (c/expected-error nil "xtdb/oops"))))

(deftest private-tables-rewrites-run-keywords
  (is (= [:put-docs :docs-R-3 {:xt/id 1}]
         (c/private-tables "R" 3 nil [:put-docs :run/docs {:xt/id 1}])))
  (testing "nested forms, and every other keyword left alone"
    (is (= '(from :docs-R-3 [{:xt/id id} :docs])
           (c/private-tables "R" 3 nil '(from :run/docs [{:xt/id id} :docs])))))
  (testing "a different example gets a different table"
    (is (not= (c/private-tables "R" 3 nil :run/docs)
              (c/private-tables "R" 4 nil :run/docs)))))

(deftest private-tables-rewrites-extra-keywords
  (is (= [:users-R-1 :orders-R-1 :other]
         (c/private-tables "R" 1 [:users :orders] [:users :orders :other]))))

(deftest private-tables-rewrites-inside-strings
  (is (= "ASSERT EXISTS (XTQL ($$ (from :docs-R-2 [a]) $$))"
         (c/private-tables "R" 2 nil "ASSERT EXISTS (XTQL ($$ (from :run/docs [a]) $$))")))
  (testing "an extra name only as a whole keyword"
    (is (= "(from :users-R-2 [a]) (from :users2 [a]) (from :users-old [a]) (from :users/x [a])"
           (c/private-tables "R" 2 [:users]
                             "(from :users [a]) (from :users2 [a]) (from :users-old [a]) (from :users/x [a])"))))
  (testing "a hyphenated run name keeps its whole name"
    (is (= "(from :docs-old-R-2 [a])"
           (c/private-tables "R" 2 nil "(from :run/docs-old [a])")))))

(def ^:private skill-text
  (str/join "\n"
            ["# Skill"
             ""
             "<!-- xtql-example: first second -->"
             "```clojure"
             "(from :a [x])"
             "(from :b [y])"
             "```"
             ""
             "```clojure"
             "(from :c [z])"
             "```"
             ""
             "```sh"
             "echo not clojure"
             "```"
             "<!--   xtql-example:   skip   -->"
             "```clojure"
             "(anything)"
             "```"]))

(deftest skill-blocks-finds-each-clojure-block
  (is (= [[["first" "second"] '[(from :a [x]) (from :b [y])] 4]
          [nil '[(from :c [z])] 9]
          [["skip"] '[(anything)] 17]]
         (c/skill-blocks skill-text)))
  (testing "no blocks"
    (is (= [] (c/skill-blocks "# Nothing\n\nhere\n")))))

