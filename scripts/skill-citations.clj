#!/usr/bin/env bb
;; Pin a skill's claims to spans of an upstream Git repo, and report drift.
;;
;; A skill directory holds `citations.edn` (written by hand: the upstream
;; repo and tag, watched paths, one :cite per footnote id with a path and an
;; anchor, and one :observed per footnote backed by an eval example) and
;; `citations.lock.json` (generated, JSON so Nix can read it: the commit the
;; tag resolved to, each cited file's sha256, each span's lines and sha256).
;;
;;   lock   DIR [--source TREE]  resolve every anchor at the pinned tag, write
;;                               the lock, regenerate the footnote lines
;;   verify DIR [--source TREE]  fail unless lock, footnotes and anchors agree
;;   drift  DIR --to TAG|latest  report each citation at a newer tag, the
;;                               passages citing it, and uncited changes
;;   bump   DIR --to TAG         move the pin to TAG and re-lock
;;   pairs  DIR                  print each citing passage beside what it
;;                               cites, for a reviewer to judge
;;
;; Upstream files come from a blobless bare clone cached under
;; $XDG_CACHE_HOME/skill-citations, or with --source from a checked-out tree
;; at the locked commit (how the Nix check runs, without network).
;;
;; Anchors: :heading (a Markdown heading's text; the span runs to the next
;; heading at the same or a higher level, or with :intro true to the next
;; heading of any level), :form (the start of a top-level form in column 0;
;; the span runs to the next one) or :line (text on exactly one line).
;;
;; A :cite may name its own :source {:repo … :tag …}, for a claim about a
;; second upstream. The lock keeps such a source's commit and file hashes
;; under "sources", keyed "repo@tag", and with --source reads its files from
;; TREE/_sources/repo@tag/. drift and bump move only the skill's own source.
;;
;; lock also records each citation's history: the earliest tag on the pinned
;; tag's history (releases, betas and release candidates) since which its
;; span reads the same, and later tags off that history where it differs;
;; and, from the eval's releases.edn (scripts/eval-releases.clj), the same
;; for each observed example. The footnotes print both. With --source, lock
;; keeps the history the previous lock recorded.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(import '[java.security MessageDigest])

(def lock-name "citations.lock.json")
(def edn-name "citations.edn")
(def footnote-ref #"\[\^([A-Za-z0-9_-]+)\](?!:)")
(def footnote-def #"^\[\^([A-Za-z0-9_-]+)\]: ")
(def heading-re #"^(#{1,6})\s+(.*?)\s*#*\s*$")
(def tag-re #"^v?(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]+)(\d*)(?:\.(\d+))?)?$")
;; The tags a citation's history is checked at: releases, betas and release
;; candidates, written with a leading v.
(def release-tag-re #"^v(\d+)\.\d+\.\d+(?:-(?:beta|rc)\d+(?:\.\d+)?)?$")

(defn fail! [msg] (throw (ex-info msg {::citation true})))

(defn sha256 [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn fence? [line] (or (str/starts-with? (str/triml line) "```")
                        (str/starts-with? (str/triml line) "~~~")))

(defn heading [line] (when-let [[_ hashes text] (re-matches heading-re line)]
                       [(count hashes) text]))

;; --- upstream access -------------------------------------------------------

(defn git-upstream [repo]
  (let [cache (or (System/getenv "XDG_CACHE_HOME") (str (fs/home) "/.cache"))
        dir (str cache "/skill-citations/github.com/" repo ".git")
        url (str "https://github.com/" repo ".git")
        git (fn [& args]
              (let [r (apply p/shell {:out :string :err :string :continue true}
                             "git" "--git-dir" dir args)]
                r))
        fetched (atom false)
        fetch! #(when-not @fetched
                  (git "fetch" "--quiet" "--tags" "--force" url "+refs/heads/*:refs/heads/*")
                  (reset! fetched true))]
    (when-not (fs/exists? dir)
      (fs/create-dirs (fs/parent dir))
      (p/shell "git" "clone" "--quiet" "--bare" "--filter=blob:none" url dir))
    {:commit (fn [ref]
               (let [r (git "rev-parse" "--verify" "--quiet" (str ref "^{commit}"))]
                 (if (zero? (:exit r))
                   (str/trim (:out r))
                   (do (fetch!)
                       (let [r (git "rev-parse" "--verify" (str ref "^{commit}"))]
                         (when-not (zero? (:exit r)) (fail! (str "unknown ref " ref)))
                         (str/trim (:out r)))))))
     :read (fn [ref path]
             (let [r (git "show" (str ref ":" path))]
               (when (zero? (:exit r)) (:out r))))
     :tags (fn [] (fetch!) (str/split-lines (:out (git "tag" "--list"))))
     :history (fn [pinned]
                (let [major (second (re-matches #"^v?(\d+)\..*" pinned))]
                  (vec (for [t (str/split-lines (:out (git "for-each-ref" "--sort=creatordate"
                                                           "--format=%(refname:short)" "refs/tags")))
                             :when (when-let [[_ ma] (re-matches release-tag-re t)] (= ma major))]
                         {:tag t
                          :on-line? (zero? (:exit (git "merge-base" "--is-ancestor" t pinned)))}))))
     :renamed-to (fn [old new path]
                   (some (fn [line]
                           (let [[_ from to] (str/split line #"\t")]
                             (when (= from path) to)))
                         (str/split-lines (:out (git "diff" "-M" "--name-status" "--diff-filter=R" old new)))))
     :grep (fn [ref needle paths]
             (->> (str/split-lines (:out (apply git "grep" "-l" "-F" "-e" needle ref "--" paths)))
                  (remove str/blank?)
                  (map #(second (str/split % #":" 2)))))
     :changed (fn [old new paths]
                (remove str/blank? (str/split-lines (:out (apply git "diff" "--name-only" old new "--" paths)))))}))

(defn tree-upstream [root commit]
  {:commit (constantly commit)
   :read (fn [_ path] (let [f (fs/path root path)] (when (fs/regular-file? f) (slurp (str f)))))})

;; --- anchors ---------------------------------------------------------------

(defn resolve-span
  "[first last], 1-based lines of the cited span, or throws."
  [text {:keys [id path] :as cite}]
  (let [lines (vec (str/split-lines text))
        [kind hits] (cond
                      (:heading cite) [(str "heading " (pr-str (:heading cite)))
                                       (keep-indexed (fn [i l] (when-let [[lvl t] (heading l)]
                                                                 (when (= t (:heading cite)) [i lvl])))
                                                     lines)]
                      (:form cite) [(str "form " (pr-str (:form cite)))
                                    (keep-indexed (fn [i l] (when (str/starts-with? l (:form cite)) [i 0])) lines)]
                      (:line cite) [(str "line " (pr-str (:line cite)))
                                    (keep-indexed (fn [i l] (when (str/includes? l (:line cite)) [i 0])) lines)]
                      :else (fail! (str "[" id "] needs a :heading, :form or :line anchor")))]
    (cond
      (empty? hits) (fail! (str "[" id "] " kind " not found in " path))
      (< 1 (count hits)) (fail! (str "[" id "] " kind " matches " (count hits) " places in " path)))
    (let [[start level] (first hits)]
      (if (:line cite)
        [(inc start) (inc start)]
        (let [end (loop [i (inc start), in-fence false]
                    (if (>= i (count lines))
                      (count lines)
                      (let [l (nth lines i)]
                        (if (:heading cite)
                          (let [in-fence (if (fence? l) (not in-fence) in-fence)
                                h (when-not in-fence (heading l))]
                            (if (and h (or (:intro cite) (<= (first h) level)))
                              i
                              (recur (inc i) in-fence)))
                          (if (str/starts-with? l "(") i (recur (inc i) in-fence))))))
              end (loop [end end]
                    (if (and (> end (inc start)) (str/blank? (nth lines (dec end))))
                      (recur (dec end))
                      end))]
          [(inc start) end])))))

(defn span-lines [text [first-line last-line]]
  (subvec (vec (str/split-lines text)) (dec first-line) last-line))

(defn span-hash [text span]
  (->> (span-lines text span)
       (remove str/blank?)
       (map #(str/trim (str/replace % #"\s+" " ")))
       (str/join "\n")
       sha256))

;; --- skill files -----------------------------------------------------------

(defn load-config [skill]
  (let [cfg (edn/read-string (slurp (str (fs/path skill edn-name))))
        ids (concat (map :id (:cite cfg)) (map :id (:observed cfg)))
        dupes (for [[id n] (frequencies ids) :when (> n 1)] id)]
    (when (seq dupes) (fail! (str "duplicate citation ids: " (str/join ", " (sort dupes)))))
    cfg))

(defn markdown-files [skill]
  (->> (fs/glob skill "**.md") (filter fs/regular-file?) (map str) sort))

(defn rel [skill f] (str (fs/relativize skill f)))

(defn references
  "{id [[file line section]]} for every footnote reference."
  [skill]
  (reduce
   (fn [acc md]
     (loop [[line & more] (str/split-lines (slurp md)), n 1, section "(top)", in-fence false, acc acc]
       (if (nil? line)
         acc
         (let [in-fence (if (fence? line) (not in-fence) in-fence)
               section (if-let [[_ t] (and (not in-fence) (heading line))] t section)
               acc (if (or in-fence (re-find footnote-def line))
                     acc
                     (reduce (fn [a [_ id]] (update a id (fnil conj []) [(rel skill md) n section]))
                             acc (re-seq footnote-ref line)))]
           (recur more (inc n) section in-fence acc)))))
   {} (markdown-files skill)))

(defn permalink [repo tag {:keys [path lines]}]
  (let [[a b] lines
        plain (if (re-find #"\.mdx?$" path) "?plain=1" "")]
    (str "https://github.com/" repo "/blob/" tag "/" path plain "#L" a "-L" b)))

(defn own-source [cfg] (select-keys (:source cfg) [:repo :tag]))

(defn cite-source
  "The {:repo :tag} a citation reads from: its own :source, or the skill's."
  [cfg c]
  (if (:source c) (select-keys (:source c) [:repo :tag]) (own-source cfg)))

(defn foreign? [cfg c] (not= (cite-source cfg c) (own-source cfg)))

(defn source-key [{:keys [repo tag]}] (str repo "@" tag))

(defn lock-source
  "The lock's record of the source citation `id` reads from, with \"commit\" and \"files\"."
  [lock id]
  (if-let [k (get-in lock ["citations" id "source"])]
    (get-in lock ["sources" k])
    lock))

(defn history-note
  "\", unchanged since v2.0.0-beta7; differs in v2.1.1\" and the like, or \"\"
  when the lock records no history."
  [pinned since others since-words other-words]
  (str (cond (nil? since) ""
             (= since pinned) (str ", new or changed in " pinned)
             :else (str ", " since-words since))
       (when (seq others) (str "; " other-words (str/join ", " others)))))

(defn expected-footnotes [cfg lock]
  (let [{:keys [tag]} (:source cfg)]
    (merge
     (into {} (for [{:keys [id path] :as c} (:cite cfg)
                    :let [entry (get-in lock ["citations" id])
                          src (cite-source cfg c)
                          at (if (foreign? cfg c) (str (:repo src) " " (:tag src)) (:tag src))
                          where (cond (:heading c) (str "§ " (:heading c))
                                      (:form c) (str "`" (:form c) "`")
                                      :else (str "line " (first (get entry "lines"))))]]
                [id (str "[^" id "]: [" (fs/file-name path) " " where ", " at "]("
                         (permalink (:repo src) (:tag src) {:path path :lines (get entry "lines")}) ")"
                         (history-note tag (get entry "since") (get entry "differs") "unchanged since " "differs in "))]))
     (into {} (for [{:keys [id example]} (:observed cfg)
                    :let [{:strs [since fails]} (get-in lock ["observed" id])]]
                [id (str "[^" id "]: Observed on XTDB " (:xtdb (:source cfg) tag) ": example `" example
                         "` in [examples.edn](" (:observations cfg) "), re-run by the eval's `" (:observed-by cfg "examples") "` command"
                         (if (get-in lock ["observed" id])
                           (str (history-note tag since fails "holds on every tag the eval ran since " "fails on ")
                                "; [releases.edn](" (str (fs/parent (:observations cfg)) "/releases.edn") ") lists the tags")
                           "")
                         ".")])))))

;; --- commands --------------------------------------------------------------

(defn build-lock [cfg ups]
  (let [own (own-source cfg)
        commit-of (memoize (fn [src] ((:commit (ups src)) (:tag src))))]
    (reduce (fn [lock {:keys [id path] :as c}]
              (let [src (cite-source cfg c)
                    foreign (foreign? cfg c)
                    commit (commit-of src)
                    text (or ((:read (ups src)) commit path) (fail! (str "[" id "] " path " does not exist at " (:tag src))))
                    span (resolve-span text c)
                    at (if foreign ["sources" (source-key src)] [])]
                (-> (if foreign
                      (update-in lock at #(or % {"repo" (:repo src) "tag" (:tag src) "commit" commit "files" {}}))
                      lock)
                    (assoc-in (conj at "files" path) (sha256 text))
                    (assoc-in ["citations" id] (cond-> {"path" path "lines" span "sha256" (span-hash text span)}
                                                 foreign (assoc "source" (source-key src)))))))
            {"repo" (:repo own) "tag" (:tag own) "commit" (commit-of own) "files" {} "citations" {}}
            (:cite cfg))))

(defn releases-file
  "The eval's record of each example on every tag, beside its examples.edn."
  [skill cfg]
  (when-let [f (:observations cfg)]
    (fs/path skill (fs/parent f) "releases.edn")))

(defn with-history
  "Add to each citation of the skill's own source the earliest tag on the
  pinned tag's history since which its span is unchanged (\"since\"), and the
  later tags off that history, such as patch releases of an older line, where
  it differs (\"differs\"). Add to each observation the same from the eval's
  releases.edn, where the example passed rather than matched. Without git
  history (--source), keep what the previous lock recorded."
  [skill cfg ups lock old]
  (let [own (own-source cfg)
        pinned (:tag own)
        history (:history (ups own))]
    (if-not history
      (-> lock
          (assoc "observed" (get old "observed" {}))
          (update "citations" (fn [cs] (into {} (for [[id e] cs]
                                                  [id (merge (select-keys (get-in old ["citations" id]) ["since" "differs"]) e)])))))
      (let [tags (history pinned)
            order (into {} (map-indexed (fn [i t] [(:tag t) i]) tags))
            line (filterv #(and (:on-line? %) (<= (order (:tag %)) (order pinned 0))) tags)
            off-line (remove :on-line? tags)
            since-of (fn [ok?] (if (ok? pinned)
                                 (:tag (or (last (take-while #(ok? (:tag %)) (rseq line))) {:tag pinned}))))
            later-than (fn [since] (filter #(> (order (:tag %)) (order since)) off-line))
            read (memoize (fn [t path] ((:read (ups own)) t path)))
            same? (fn [{:keys [id path] :as c} t]
                    (when-let [text (read t path)]
                      (when-let [span (try (resolve-span text c) (catch Exception _ nil))]
                        (= (span-hash text span) (get-in lock ["citations" id "sha256"])))))
            releases (some-> (releases-file skill cfg) str (#(when (fs/exists? %) (edn/read-string (slurp %)))))
            tested (set (for [[t st] (:tags releases) :when (= :tested st)] t))
            lock (reduce (fn [lock {:keys [id] :as c}]
                           (if (foreign? cfg c)
                             lock
                             (let [since (since-of #(same? c %))]
                               (update-in lock ["citations" id] assoc
                                          "since" since
                                          "differs" (vec (for [t (later-than since) :when (not (same? c (:tag t)))] (:tag t)))))))
                         lock (:cite cfg))]
        (assoc lock "observed"
               (into (sorted-map)
                     (for [{:keys [id example]} (:observed cfg)
                           :let [r (into {} (get-in releases [:results example]))
                                 pass? #(and (tested %) (= :pass (r %)))
                                 ;; Untested tags neither break the run nor extend it.
                                 run (filterv #(tested (:tag %)) line)
                                 since (when (pass? pinned)
                                         (:tag (or (last (take-while #(pass? (:tag %)) (rseq run))) {:tag pinned})))
                                 ;; With nothing older tested, there is no history to report.
                                 since (when (some #(and (not= pinned (:tag %)) (tested (:tag %))) run) since)]
                           :when (seq r)]
                       [id {"since" since
                            "fails" (vec (for [t (later-than (or since pinned))
                                               :when (and (tested (:tag t)) (not (pass? (:tag t))))]
                                           (:tag t)))}])))))))

(defn read-lock [skill & {:keys [required] :or {required true}}]
  (let [f (str (fs/path skill lock-name))]
    (cond (fs/exists? f) (json/parse-string (slurp f))
          required (fail! (str f " is missing; run `lock` first")))))

(defn upstreams-for
  "A function from a source {:repo :tag} to its upstream."
  [cfg {:keys [source]} lock]
  (memoize
   (fn [src]
     (let [foreign (not= src (own-source cfg))]
       (if source
         (let [commit (if foreign (get-in lock ["sources" (source-key src) "commit"]) (get lock "commit"))]
           (when-not lock (fail! "--source needs an existing lock to name its commit"))
           (when-not commit (fail! (str "the lock has no commit for " (source-key src) "; run `lock`")))
           (tree-upstream (if foreign (str (fs/path source "_sources" (source-key src))) source) commit))
         (git-upstream (:repo src)))))))

(defn observation-ids [skill cfg]
  (when-let [f (:observations cfg)]
    (let [path (fs/path skill f)]
      (when (fs/exists? path)
        (set (map :id (:example (edn/read-string {:readers {'inst identity}} (slurp (str path))))))))))

(defn verify [skill cfg lock ups]
  (let [cite-ids (set (map :id (:cite cfg)))
        ids (into cite-ids (map :id (:observed cfg)))
        refs (references skill)
        defs (into {} (for [md (markdown-files skill), line (str/split-lines (slurp md))
                            :let [[_ id] (re-find footnote-def line)] :when id]
                        [id line]))
        known-examples (observation-ids skill cfg)
        problems (concat
                  (when (not= [(get lock "repo") (get lock "tag")] [(get-in cfg [:source :repo]) (get-in cfg [:source :tag])])
                    [(str "lock pins " (get lock "repo") "@" (get lock "tag") ", citations.edn says "
                          (get-in cfg [:source :repo]) "@" (get-in cfg [:source :tag]))])
                  (when (not= (set (keys (get lock "citations"))) cite-ids)
                    ["lock and citations.edn disagree on ids; run `lock`"])
                  (for [c (:cite cfg)
                        :let [want (when (foreign? cfg c) (source-key (cite-source cfg c)))
                              have (get-in lock ["citations" (:id c) "source"])]
                        :when (or (not= want have) (and want (not (get-in lock ["sources" want]))))]
                    (str "[" (:id c) "] cites " (or want "the skill's source") ", the lock says "
                         (or have "the skill's source") "; run `lock`"))
                  (for [id (sort (remove ids (keys refs)))
                        :let [[f n] (first (refs id))]]
                    (str f ":" n " cites [^" id "], which citations.edn does not define"))
                  (for [id (sort (remove (set (keys refs)) ids))]
                    (str "[^" id "] is defined but no passage cites it"))
                  (when known-examples
                    (for [{:keys [id example]} (:observed cfg) :when (not (known-examples example))]
                      (str "[^" id "] observes example `" example "`, which " (:observations cfg) " lacks"))))]
    (if (seq problems)
      problems
      (concat
       (for [[id line] (expected-footnotes cfg lock) :when (not= (defs id) line)]
         (str "footnote [^" id "] is stale or missing; run `lock`"))
       (when ups
         (for [{:keys [id path] :as c} (:cite cfg)
               :let [entry (get-in lock ["citations" id])
                     src (lock-source lock id)
                     text ((:read (ups (cite-source cfg c))) (get src "commit") path)
                     problem (cond
                               (nil? text) (str "[" id "] " path " missing at the locked commit")
                               (not= (sha256 text) (get-in src ["files" path])) (str "[" id "] " path " does not match the locked file hash")
                               :else (let [span (try (resolve-span text c) (catch Exception e (ex-message e)))]
                                       (cond (string? span) span
                                             (or (not= span (get entry "lines")) (not= (span-hash text span) (get entry "sha256")))
                                             (str "[" id "] resolves to lines " (str/join "-" span) ", not the locked span; run `lock`"))))]
               :when problem]
           problem))))))

(defn cmd-lock [skill opts]
  (let [cfg (load-config skill)
        old (read-lock skill :required false)
        ups (upstreams-for cfg opts old)
        lock (with-history skill cfg ups (build-lock cfg ups) old)
        want (expected-footnotes cfg lock)
        refs (references skill)]
    (spit (str (fs/path skill lock-name)) (str (json/generate-string lock {:pretty true}) "\n"))
    (doseq [md (markdown-files skill)]
      (let [lines (str/split-lines (slurp md))
            out (mapv (fn [l] (if-let [[_ id] (re-find footnote-def l)] (get want id l) l)) lines)
            defined (set (keep #(second (re-find footnote-def %)) out))
            ;; A cited id with no definition yet goes at the end of the file that first cites it.
            missing (for [[id line] (sort-by key want)
                          :when (and (not (defined id)) (= (ffirst (refs id)) (rel skill md)))]
                      line)
            out (into out missing)]
        (when (not= out lines) (spit md (str (str/join "\n" out) "\n")))))
    (println (str "locked " (count (get lock "citations")) " citations at " (get lock "tag")
                  " (" (subs (get lock "commit") 0 12) ")"))
    (verify skill cfg lock nil)))

(defn cmd-verify [skill opts]
  (let [cfg (load-config skill)
        lock (read-lock skill)
        ups (upstreams-for cfg opts lock)
        moved (for [{:strs [repo tag commit]} (cons lock (vals (get lock "sources")))
                    :let [now ((:commit (ups {:repo repo :tag tag})) tag)]
                    :when (not= now commit)]
                (str "tag " tag " of " repo " now resolves to " now ", not the locked " commit))]
    (if (seq moved)
      moved
      (verify skill cfg lock ups))))

(defn tag-key [tag]
  (when-let [[_ ma mi pa pre pre-n pre-sub] (re-matches tag-re tag)]
    (into [(parse-long ma) (parse-long mi) (parse-long pa)]
          ;; A release sorts after its own pre-releases.
          (if pre [0 pre (parse-long (if (str/blank? pre-n) "0" pre-n)) (parse-long (or pre-sub "0"))] [1 "" 0 0]))))

(defn latest-tag [up current]
  (let [cur (tag-key current)
        newer (for [t ((:tags up)) :let [k (tag-key t)] :when (and k (pos? (compare k cur)))] [k t])]
    (if (seq newer) (second (last (sort-by first newer))) current)))

(defn unified-diff [before after]
  ;; Minimal line diff: shared prefix and suffix kept as context, the middle as -/+.
  (let [pre (count (take-while true? (map = before after)))
        b (drop pre before), a (drop pre after)
        suf (count (take-while true? (map = (reverse b) (reverse a))))
        b (drop-last suf b), a (drop-last suf a)]
    (concat (map #(str "-" %) b) (map #(str "+" %) a))))

(defn cmd-drift [skill {:keys [to json]}]
  (let [cfg (load-config skill)
        lock (read-lock skill)
        up (git-upstream (get-in cfg [:source :repo]))
        old (get lock "commit")
        to (if (= to "latest") (latest-tag up (get lock "tag")) to)
        new ((:commit up) to)
        refs (references skill)
        watch (get-in cfg [:source :watch])
        own-cites (remove #(foreign? cfg %) (:cite cfg))
        items (for [{:keys [id path] :as c} own-cites]
                (let [entry (get-in lock ["citations" id])
                      base {:id id :path path :cited-by (mapv (fn [[f n s]] (str f ":" n " (" s ")")) (refs id))}
                      text ((:read up) new path)
                      moved (when-not text
                              (or ((:renamed-to up) old new path)
                                  (let [hits ((:grep up) new (or (:form c) (:line c) (:heading c)) watch)]
                                    (when (= 1 (count hits)) (first hits)))))
                      path' (or moved path)
                      text (or text (when moved ((:read up) new moved)))]
                  (cond
                    (nil? text) (assoc base :status "missing")
                    :else
                    (let [span (try (resolve-span text (assoc c :path path')) (catch Exception e (ex-message e)))
                          base (cond-> base moved (assoc :moved-to moved))]
                      (cond
                        (string? span) (assoc base :status "unresolved" :error span)
                        (= (span-hash text span) (get entry "sha256"))
                        (assoc base :status (if moved "moved" "unchanged") :lines span)
                        :else
                        (assoc base :status "changed" :lines span
                               :diff (unified-diff (span-lines ((:read up) old path) (get entry "lines"))
                                                   (span-lines text span))))))))
        cited (into (set (map :path own-cites)) (keep :moved-to items))
        report {:from (get lock "tag") :to to :citations (vec items)
                :uncited-changes (if (seq watch) (vec (remove cited ((:changed up) old new watch))) [])}]
    (if json
      (println (json/generate-string report {:pretty true}))
      (let [order {"missing" 0 "unresolved" 1 "changed" 2 "moved" 3 "unchanged" 4}]
        (println (str "citations: " (:from report) " -> " (:to report) "\n"))
        (doseq [{:keys [status id path moved-to cited-by error diff]} (sort-by (juxt (comp order :status) :id) items)]
          (println (format "%10s  [^%s]  %s%s" status id path (if moved-to (str " -> " moved-to) "")))
          (when (not= status "unchanged")
            (doseq [c cited-by] (println (str "            cited by " c)))
            (when error (println (str "            " error)))
            (doseq [l diff] (println (str "            " l)))))
        (when (seq (:uncited-changes report))
          (println "\nchanged under watched paths, cited nowhere:")
          (doseq [p (:uncited-changes report)] (println (str "  " p))))))
    (if (every? #(= "unchanged" (:status %)) items) [] [:drift])))

(defn passages
  "[[file line section text]] for every paragraph that cites a footnote."
  [skill]
  (mapcat
   (fn [md]
     (loop [[line & more] (conj (str/split-lines (slurp md)) ""), n 1, section "(top)", in-fence false,
            para [], start 0, out []]
       (if (nil? line)
         out
         (let [is-fence (fence? line)
               in-fence (if is-fence (not in-fence) in-fence)
               h (heading line)
               boundary (and (not in-fence) (not is-fence)
                             (or (str/blank? line) (re-find footnote-def line) h))]
           (if boundary
             (let [text (str/join "\n" para)
                   out (if (and (seq para) (re-find footnote-ref text)) (conj out [(rel skill md) start section text]) out)]
               (recur more (inc n) (if h (second h) section) in-fence [] 0 out))
             (recur more (inc n) section in-fence (conj para line) (if (empty? para) n start) out))))))
   (markdown-files skill)))

(defn cmd-pairs [skill _]
  (let [cfg (load-config skill)
        lock (read-lock skill)
        ups (upstreams-for cfg {} lock)
        cites (into {} (map (juxt :id identity)) (:cite cfg))
        observed (into {} (map (juxt :id :example) (:observed cfg)))
        examples (when-let [f (:observations cfg)]
                   (into {} (map (juxt :id identity))
                         (:example (edn/read-string {:readers {'inst identity}} (slurp (str (fs/path skill f)))))))]
    (println (str "# Claims and their sources: " (get lock "repo") " at " (get lock "tag")
                  " (" (subs (get lock "commit") 0 12) ")\n"))
    (doseq [[k [f line section text]] (map-indexed vector (passages skill))]
      (println (str "## Passage " (inc k) ": " f ":" line " (section: " section ")\n"))
      (println (str (str/join "\n" (map #(str "> " %) (str/split-lines text))) "\n"))
      (doseq [id (distinct (map second (re-seq footnote-ref text)))]
        (if-let [ex (some-> (observed id) examples)]
          (println (str "### Source [^" id "]: observed on a live XTDB node, example `" (:id ex) "`\n\n````clojure\n"
                        (binding [*print-namespace-maps* false] (pr-str ex)) "\n````\n"))
          (let [{:strs [path lines]} (get-in lock ["citations" id])]
            (println (str "### Source [^" id "]: " path " lines " (str/join "-" lines) "\n"))
            (println (str "````text\n" (str/join "\n" (span-lines ((:read (ups (cite-source cfg (cites id)))) (get (lock-source lock id) "commit") path) lines)) "\n````\n"))))))
    []))

(defn cmd-bump [skill {:keys [to] :as opts}]
  (let [f (str (fs/path skill edn-name))
        text (slurp f)
        new (str/replace-first text #":tag \"[^\"]*\"" (str ":tag \"" to "\""))]
    (when (= new text) (fail! (str "no `:tag \"…\"` in " f)))
    (spit f new)
    (cmd-lock skill (dissoc opts :source))))

(defn parse-args [args]
  (loop [[a & more] args, opts {}]
    (cond (nil? a) opts
          (= a "--source") (recur (rest more) (assoc opts :source (first more)))
          (= a "--to") (recur (rest more) (assoc opts :to (first more)))
          (= a "--json") (recur more (assoc opts :json true))
          :else (recur more (assoc opts :skill a)))))

(defn -main [[cmd & args]]
  (let [cmds {"lock" cmd-lock "verify" cmd-verify "drift" cmd-drift "bump" cmd-bump "pairs" cmd-pairs}
        {:keys [skill] :as opts} (parse-args args)]
    (when-not (and (cmds cmd) skill)
      (binding [*out* *err*] (println "usage: skill-citations lock|verify|drift|bump|pairs SKILL-DIR [--source TREE] [--to TAG|latest] [--json]"))
      (System/exit 2))
    (when (and (#{"drift" "bump"} cmd) (not (:to opts)))
      (binding [*out* *err*] (println (str cmd " needs --to")))
      (System/exit 2))
    (try
      (let [problems ((cmds cmd) (str (fs/absolutize skill)) opts)]
        (doseq [p problems :when (string? p)] (binding [*out* *err*] (println (str "problem: " p))))
        (when (and (= cmd "verify") (empty? problems)) (println "citations verified"))
        (System/exit (if (seq problems) 1 0)))
      (catch clojure.lang.ExceptionInfo e
        (if (::citation (ex-data e))
          (do (binding [*out* *err*] (println (str "error: " (ex-message e)))) (System/exit 2))
          (throw e))))))

(-main *command-line-args*)
