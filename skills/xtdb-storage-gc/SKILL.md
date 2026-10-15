---
name: xtdb-storage-gc
description: Turn on and tune XTDB 2.x's storage garbage collector, which is off by default, so files that compaction has superseded get deleted; and know what it never deletes — level-0 files and the transaction log. Use when an XTDB 2.x node's storage directory keeps growing, when configuring garbageCollector in node YAML or :garbage-collector in a Clojure node map, or when asking whether erased data has left disk. Pinned to XTDB v2.1.0; every claim cites the source or docs, or an observation re-run against a live node.
---

# Storage garbage collection (XTDB 2.x)

Written from `xtdb/xtdb` at tag `v2.1.0`. The docs at that tag don't
mention the garbage collector, so most footnotes cite the source; the
rest cite the xtdb-skills eval's `storage` command, which runs the same
writes on two nodes, with the collector on and off, and compares their
storage directories. `citations.edn` lists them. For what erase does to
query results, see the `xtdb-erase-and-history` skill.

## It is off by default

`GarbageCollectorConfig` defaults to `enabled = false`, `blocksToKeep =
10`, `garbageLifetime = 24h` and `approxRunInterval = 10m`.[^gc-config]
The node always builds the collector, but starts its loop only when
enabled, and only for the primary database.[^gc-init] Compaction only
marks the files it supersedes as garbage,[^compactor-add][^supersede] so
with the collector off they stay: of two nodes given the same writes, the
one with the collector off kept 76 level-1+ files to the other's
24.[^obs-superseded]

## Turning it on

In node YAML, the key is `garbageCollector`, a field of the node
config,[^node-config][^yaml-config] with durations in ISO-8601
form:[^duration-serde][^obs-yaml-starts]

```yaml
garbageCollector:
  enabled: true
  blocksToKeep: 10
  garbageLifetime: PT24H
  approxRunInterval: PT10M
```

In a Clojure node map, the key is `:garbage-collector`,[^node-clj-key]
with `:enabled?`, `:blocks-to-keep`, `:garbage-lifetime` and
`:approx-run-interval`.[^clj-config] The durations go to the config's
setters as they are, and those take `java.time.Duration`:[^clj-config][^gc-config]

<!-- xtql-example: skip -->
```clojure
{:garbage-collector {:enabled? true
                     :blocks-to-keep 10
                     :garbage-lifetime (java.time.Duration/ofHours 24)
                     :approx-run-interval (java.time.Duration/ofMinutes 10)}}
```

## What each setting trades

- **`blocksToKeep`.** Each run deletes all but the newest
  `blocksToKeep` block files, the global ones and those of up to 100
  tables,[^block-gc][^obs-keeps-blocks] and the cutoff for data files is
  taken from the block that many back from the latest.[^gc-class] More
  blocks kept moves the cutoff back, so superseded files wait longer.
- **`garbageLifetime`.** A superseded data file is deleted only if it
  became garbage at or before that block's last transaction time minus
  `garbageLifetime`.[^gc-class][^garbage-tries][^garbage-fn] With zero,
  the cutoff is that block's own time.[^gc-class]
- **`approxRunInterval`.** The first run waits a random part of the
  interval, then the collector runs once per interval.[^gc-class] Each
  run looks at up to 100 tables, chosen at random, so a node with more
  tables than that takes several runs to cover them.[^gc-class][^block-gc]

## What it deletes, and what it never does

The collector deletes the data and meta files of tries the catalog
marks as garbage,[^gc-class][^garbage-tries] but never at level 0: the
catalog's garbage test is false for every level-0 file,[^garbage-fn] and
both nodes kept as many level-0 files, 48 each.[^obs-keeps-l0]

It never touches the transaction log: it deletes only block files and
trie files through the buffer pool.[^gc-class][^block-gc] A local log is
one append-only `LOG` file, truncated only to roll back a write that
failed,[^local-log][^log-local-disk] and it didn't shrink while the
collector ran.[^obs-log-untouched] For Kafka, XTDB creates the topic with
only `message.timestamp.type` set, no retention,[^kafka-topic] and the
docs leave `retention.ms` to the operator, deleting old messages after
it.[^kafka-setup] Bumping the log's epoch makes nodes ignore the old log
but doesn't delete it.[^epoch]

Compaction is on by default, with half the processors as
threads,[^compactor-threads][^compactor-config] and a `threads` of 0 or
less turns it off,[^compactor-init] so on a default node compaction keeps
marking files as garbage and nothing deletes them.

## Erased data on disk

`erase-docs` irrevocably erases an id's history, through system time and
for all valid time,[^erase-docs] but the docs say erased data is
"guaranteed to be fully erased only once all background index processing
has completed and the changes have been written to the remote object
storage."[^erase-disk] Compaction merges an id's events newest
first,[^event-order] and once it meets an erase, it copies none of that
id's older events into the new file.[^polygon-erase][^segment-merge] The
files it supersedes still hold them until the collector deletes
them,[^gc-class] which with the collector off never
happens.[^gc-init][^obs-superseded] Nothing deletes level-0
files[^garbage-fn][^obs-keeps-l0] or a local log.[^local-log][^obs-log-untouched]

## Checking it's running

Set the logger `xtdb.garbage_collector` to DEBUG, as the eval's
`logback.xml` does. A node with the collector on logs `Starting
GarbageCollector with approxRunInterval: …` at start, then each run's
block and trie collection;[^gc-class][^obs-yaml-starts] a node with it
off logs neither.[^gc-init][^obs-off-silent]

`xtdb.garbage-collector/garbage-collector` returns a node's collector,
which exists even when disabled,[^gc-accessor][^gc-init] and
`(.collectGarbage gc)` runs one collection at once.[^gc-class]
[^block-gc]: [BlockGarbageCollector.kt `class BlockGarbageCollector(`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/garbage_collector/BlockGarbageCollector.kt#L11-L54)
[^clj-config]: [garbage_collector.clj `(defmethod xtn/apply-config! :xtdb/garbage-collector`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/clojure/xtdb/garbage_collector.clj#L9-L16)
[^compactor-add]: [Compactor.kt line 214, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/compactor/Compactor.kt#L214-L214)
[^compactor-config]: [CompactorConfig.kt line 10, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/api/CompactorConfig.kt#L10-L10)
[^compactor-init]: [compactor.clj `(defmethod ig/init-key :xtdb/compactor`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/clojure/xtdb/compactor.clj#L119-L122)
[^compactor-threads]: [CompactorConfig.kt line 14, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/api/CompactorConfig.kt#L14-L14)
[^duration-serde]: [Serde.kt `object DurationSerde`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/api/src/main/kotlin/xtdb/Serde.kt#L31-L64)
[^epoch]: [log.md § Bumping an Epoch, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/ops/config/log.md?plain=1#L88-L103)
[^erase-disk]: [sql-overview.mdx § ERASE as 'hard' delete, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/quickstart/sql-overview.mdx?plain=1#L421-L445)
[^erase-docs]: [txs.md § `erase-docs`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/txs.md?plain=1#L180-L208)
[^event-order]: [EventRowPointer.kt line 65, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/trie/EventRowPointer.kt#L65-L65)
[^garbage-fn]: [trie_catalog.clj `(defn garbage-fn`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/clojure/xtdb/trie_catalog.clj#L240-L243)
[^garbage-tries]: [trie_catalog.clj `(defn garbage-tries`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/clojure/xtdb/trie_catalog.clj#L245-L247)
[^gc-accessor]: [garbage_collector.clj `(defn garbage-collector`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/clojure/xtdb/garbage_collector.clj#L35-L36)
[^gc-class]: [GarbageCollector.kt `class GarbageCollector @JvmOverloads constructor(`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/garbage_collector/GarbageCollector.kt#L19-L92)
[^gc-config]: [GarbageCollectorConfig.kt `data class GarbageCollectorConfig(`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/api/GarbageCollectorConfig.kt#L9-L19)
[^gc-init]: [garbage_collector.clj `(defmethod ig/init-key :xtdb/garbage-collector`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/clojure/xtdb/garbage_collector.clj#L25-L29)
[^kafka-setup]: [kafka.md § Setup, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/ops/config/log/kafka.md?plain=1#L42-L78)
[^kafka-topic]: [KafkaCluster.kt `private fun AdminClient.ensureTopicExists(`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/modules/kafka/src/main/kotlin/xtdb/api/log/KafkaCluster.kt#L81-L297)
[^local-log]: [LocalLog.kt `class LocalLog(`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/api/log/LocalLog.kt#L41-L321)
[^log-local-disk]: [log.md § Local disk, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/ops/config/log.md?plain=1#L37-L55)
[^node-clj-key]: [node.clj `(defmethod apply-config! :garbage-collector`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/clojure/xtdb/node.clj#L76-L77)
[^node-config]: [Xtdb.kt line 77, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/api/Xtdb.kt#L77-L77)
[^obs-keeps-blocks]: Observed on XTDB v2.1.0: example `gc-keeps-recent-blocks` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command.
[^obs-keeps-l0]: Observed on XTDB v2.1.0: example `gc-keeps-l0` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command.
[^obs-log-untouched]: Observed on XTDB v2.1.0: example `gc-log-untouched` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command.
[^obs-off-silent]: Observed on XTDB v2.1.0: example `gc-off-silent` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command.
[^obs-superseded]: Observed on XTDB v2.1.0: example `gc-deletes-superseded` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command.
[^obs-yaml-starts]: Observed on XTDB v2.1.0: example `gc-yaml-starts` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command.
[^polygon-erase]: [PolygonCalculator.kt `class PolygonCalculator(`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/bitemporal/PolygonCalculator.kt#L7-L49)
[^segment-merge]: [SegmentMerge.kt `internal class SegmentMerge(`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/compactor/SegmentMerge.kt#L113-L300)
[^supersede]: [trie_catalog.clj `(defn- supersede-by-block-idx`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/clojure/xtdb/trie_catalog.clj#L118-L128)
[^yaml-config]: [YamlSerde.kt `fun nodeConfig(yamlString: String)`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/core/src/main/kotlin/xtdb/api/YamlSerde.kt#L157-L158)
