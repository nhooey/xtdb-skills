(ns xtdb-eval.uber
  "Build uberjars of xtdb-core with tools.build and report what they hold,
  for the xtdb-embedded-node suite's uberjar checks.

    clojure -M:uber VERSION DIR

  VERSION is the xtdb-core to build from and DIR an empty directory for the
  jars. It prints one EDN map: each check's [passed? detail]. The storage
  command runs it in its own JVM, so tools.build's dependencies never join
  the classpath the eval's node runs on."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.security MessageDigest]
           [java.util.zip ZipFile]))

(def ledger "org/apache/arrow/memory/BufferLedger.class")

(def license-exclusion
  "Leaves out netty-tcnative-boringssl-static's META-INF/license/ directory."
  "META-INF/license/.*")

(defn- prefer-xtdb-core
  "A tools.build conflict handler: keep xtdb-core's copy of a class, whichever
  lib the uber task meets first."
  [{:keys [path in lib]}]
  (when (= 'com.xtdb/xtdb-core lib)
    {:write {path {:stream in}}}))

(defn- sha [^bytes bs]
  (str/join (map #(format "%02x" %) (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn- entry-sha [jar path]
  (with-open [z (ZipFile. (str jar))]
    (when-let [e (.getEntry z path)]
      (with-open [in (.getInputStream z e)]
        (sha (.readAllBytes in))))))

(defn- lib-jar [basis lib]
  (first (filter #(str/ends-with? % ".jar") (get-in basis [:libs lib :paths]))))

(defn- uber! [basis file opts]
  (try
    (b/uber (merge {:class-dir (str file ".classes") :uber-file (str file) :basis basis} opts))
    nil
    (catch Exception e (ex-message e))))

(defn- case-insensitive? [dir]
  (let [probe (io/file dir "CaseProbe")]
    (spit probe "")
    (try (.exists (io/file dir "caseprobe")) (finally (.delete probe)))))

(defn- runs-a-node?
  "Start a node from `jar` in a JVM of its own, with the JVM options XTDB's
  docs give for an in-process node, and return what it printed."
  [jar]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        {:keys [out err]} (shell/sh java "--add-opens=java.base/java.nio=ALL-UNNAMED"
                                    "--enable-native-access=ALL-UNNAMED"
                                    "-Dio.netty.tryReflectionSetAccessible=true"
                                    "-Dio.netty.noUnsafe=false"
                                    "-cp" (str jar) "clojure.main" "-e"
                                    (pr-str '(with-open [n ((requiring-resolve 'xtdb.node/start-node) {:server nil})]
                                               (prn ((requiring-resolve 'xtdb.api/q) n '(rel [{:a 1}] [a])))
                                               (shutdown-agents))))]
    (or (last (str/split-lines (str/trim out))) (str/trim err))))

(defn facts [version dir]
  (let [basis (b/create-basis {:project {:deps {'com.xtdb/xtdb-core {:mvn/version version}}}})
        core-sha (entry-sha (lib-jar basis 'com.xtdb/xtdb-core) ledger)
        arrow-sha (entry-sha (lib-jar basis 'org.apache.arrow/arrow-memory-core) ledger)
        tcnative (get-in basis [:libs 'io.netty/netty-tcnative-boringssl-static])
        chain (first (sort (:parents tcnative)))
        fs (if (case-insensitive? dir) "case-insensitive" "case-sensitive")
        plain-error (uber! basis (io/file dir "plain.jar") {})
        excluded (io/file dir "excluded.jar")
        excluded-error (uber! basis excluded {:exclude [license-exclusion]})
        fixed (io/file dir "fixed.jar")
        fixed-error (uber! basis fixed {:exclude [license-exclusion]
                                        :conflict-handlers {"org/apache/arrow/memory/BufferLedger(\\$.*)?\\.class"
                                                            prefer-xtdb-core}})
        which (fn [jar] (condp = (entry-sha jar ledger) core-sha "xtdb-core's" arrow-sha "arrow-memory-core's" "neither's"))
        node-out (when-not fixed-error (runs-a-node? fixed))]
    {:tcnative-from-flight-sql
     [(and (some #{'org.apache.arrow/flight-sql} chain) (some? (:mvn/version tcnative)))
      (str "io.netty/netty-tcnative-boringssl-static " (:mvn/version tcnative) " comes in through "
           (str/join " -> " chain))]
     :uber-license-collision
     [(boolean (some-> plain-error (str/includes? "META-INF/license/")))
      (str "on a " fs " filesystem, tools.build's uber "
           (if plain-error (str "fails: " plain-error) "builds"))]
     :uber-default-ledger
     [(and (nil? excluded-error) (= arrow-sha (entry-sha excluded ledger)))
      (str "excluding " license-exclusion ", the uberjar "
           (if excluded-error (str "fails: " excluded-error) (str "holds " (which excluded) " BufferLedger.class")))]
     :uber-fixed-runs
     [(and (nil? fixed-error) (= core-sha (entry-sha fixed ledger)) (= "[{:a 1}]" node-out))
      (if fixed-error
        (str "the uberjar fails: " fixed-error)
        (str "with the exclusion and a conflict handler preferring xtdb-core, the uberjar holds "
             (which fixed) " BufferLedger.class, and a node started from it prints " node-out))]}))

(defn -main [version dir]
  (prn (facts version dir))
  (shutdown-agents))
