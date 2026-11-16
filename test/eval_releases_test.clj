(ns eval-releases-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]))

;; The script has no namespace file of its own name; load it without running
;; it (its -main runs only when bb runs the file itself).
(load-file (str (fs/path (fs/parent (fs/parent (fs/absolutize *file*))) "scripts" "eval-releases.clj")))

(def order-tags (resolve 'eval-releases/order-tags))
(def merge-record (resolve 'eval-releases/merge-record))

(deftest order-tags-keeps-only-2x-tags
  (is (= ["v2.0.0"] (map :tag (order-tags ["v1.24.5" "v2.0.0" "nightly" "v2.0.0-alpha1" "v3.0.0"])))))

(deftest order-tags-sorts-by-version-number
  (is (= ["v2.2.0" "v2.10.0"] (map :tag (order-tags ["v2.10.0" "v2.2.0"])))))

(deftest order-tags-puts-prereleases-before-their-release
  (is (= ["v2.0.0-beta1" "v2.0.0-rc1" "v2.0.0" "v2.0.1"]
         (map :tag (order-tags ["v2.0.0-beta1" "v2.0.0" "v2.0.0-rc1" "v2.0.1"])))))

(deftest order-tags-orders-prereleases-by-commit-date
  (testing "beta10 after beta9 because it came later, not by its name"
    (is (= ["v2.0.0-beta9" "v2.0.0-beta10" "v2.0.0"]
           (map :tag (order-tags ["v2.0.0-beta9" "v2.0.0-beta10" "v2.0.0"]))))))

(deftest order-tags-gives-each-tag-its-version
  (is (= [{:tag "v2.0.0-beta8.4" :version "2.0.0-beta8.4"} {:tag "v2.1.0" :version "2.1.0"}]
         (order-tags ["v2.0.0-beta8.4" "v2.1.0"]))))

(def ^:private ordered ["v1" "v2" "v3" "v4"])

(deftest merge-record-replaces-a-tested-tag
  (is (= {:tags [["v1" :tested] ["v2" :tested]]
          :results {"ex" [["v1" :pass] ["v2" :wrong]]}}
         (merge-record {:tags [["v1" :tested] ["v2" :tested]]
                        :results {"ex" [["v1" :pass] ["v2" :pass]]}}
                       {:tags [["v2" :tested]] :results {"ex" [["v2" :wrong]]}}
                       ordered))))

(deftest merge-record-keeps-the-old-tag-over-a-new-untested-one
  (is (= {:tags [["v1" :tested]] :results {"ex" [["v1" :pass]]}}
         (merge-record {:tags [["v1" :tested]] :results {"ex" [["v1" :pass]]}}
                       {:tags [["v1" :no-image]] :results {}}
                       ordered))))

(deftest merge-record-adds-an-untested-tag-the-old-record-lacks
  (is (= {:tags [["v1" :tested] ["v3" :no-client]] :results {"ex" [["v1" :pass]]}}
         (merge-record {:tags [["v1" :tested]] :results {"ex" [["v1" :pass]]}}
                       {:tags [["v3" :no-client]] :results {}}
                       ordered))))

(deftest merge-record-replaces-with-a-refused-config
  (testing "a refused config drops the tag's old results"
    (is (= {:tags [["v1" :tested] ["v2" :config-refused]] :results {"ex" [["v1" :pass]]}}
           (merge-record {:tags [["v1" :tested] ["v2" :tested]]
                          :results {"ex" [["v1" :pass] ["v2" :pass]]}}
                         {:tags [["v2" :config-refused]] :results {}}
                         ordered)))))

(deftest merge-record-puts-tags-and-results-in-release-order
  (is (= {:tags [["v1" :tested] ["v2" :tested] ["v4" :tested]]
          :results {"a" [["v1" :pass] ["v2" :pass] ["v4" :pass]]
                    "b" [["v2" :pass]]}}
         (merge-record {:tags [["v4" :tested] ["v1" :tested]]
                        :results {"a" [["v4" :pass] ["v1" :pass]]}}
                       {:tags [["v2" :tested]] :results {"a" [["v2" :pass]] "b" [["v2" :pass]]}}
                       ordered))))

(deftest merge-record-drops-an-example-left-with-no-results
  (is (= {:tags [["v1" :tested]] :results {"new" [["v1" :pass]]}}
         (merge-record {:tags [["v1" :tested]] :results {"gone" [["v1" :pass]]}}
                       {:tags [["v1" :tested]] :results {"new" [["v1" :pass]]}}
                       ordered))))

(deftest merge-record-ignores-results-for-a-tag-it-does-not-take
  (is (= {:tags [["v1" :tested]] :results {"ex" [["v1" :pass]]}}
         (merge-record {:tags [["v1" :tested]] :results {"ex" [["v1" :pass]]}}
                       {:tags [["v1" :runner-error]] :results {"ex" [["v1" :wrong]]}}
                       ordered))))
