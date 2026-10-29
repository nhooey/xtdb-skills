---
name: xtdb-storage-gc
description: Tune or turn off XTDB 2.x's storage garbage collector, which is on by default and runs on each database's indexing leader at every block boundary, deleting files that compaction has superseded; and know what it never deletes — level-0 files and the source and replica logs. Use when an XTDB 2.x node's storage directory keeps growing, when configuring garbageCollector in node YAML or :garbage-collector in a Clojure node map, when a v2.1 YAML config with approxRunInterval stops starting or a Clojure node map still sets :approx-run-interval, when an idle node's REPLICA_LOG keeps growing, or when asking whether erased data has left disk, what the disk or memory cache (diskCache, memoryCache, maxSizeRatio) holds, whether DETACH DATABASE frees space, or how to drop or truncate a table. Pinned to XTDB v2.2.0, and each footnote says since which XTDB tag, betas included, it holds; every claim cites the source or docs, or an observation re-run against a live node.
---

# Storage garbage collection (XTDB 2.x)

Written from `xtdb/xtdb` at tag `v2.2.0`. The docs at that tag give the
collector one short section,[^gc-docs] so most footnotes cite the
source; the rest cite the xtdb-skills eval's `storage` command, which runs the same
writes on two nodes, with the collector on and off, and compares their
storage directories. Kafka's retention defaults cite `apache/kafka` at
`4.3.1`. `citations.edn` lists them. For what erase does to
query results, see the `xtdb-erase-and-history` skill.

## Versions

Each footnote says since which `xtdb/xtdb` tag, betas and release
candidates included, its cited span has read the same ("unchanged
since"), or since which tag the eval saw its example hold ("holds on
every tag the eval ran since"); "differs in" and "fails on" name later
tags off that line, such as a patch release of an older minor version.
`evals/<skill>/releases.edn` lists the result on every tag.

Before relying on a claim, find the project's XTDB version: the
`com.xtdb/xtdb-api` or `com.xtdb/xtdb-core` version in `deps.edn` or the
build file, or the `ghcr.io/xtdb/xtdb` image tag. If every footnote on a
claim starts after that version, or names it under "differs in" or
"fails on", the claim may not hold there: say so, and check that
version's docs or source, rather than applying it.

## It is on by default

`GarbageCollectorConfig` defaults to `enabled = true`, `blocksToKeep =
10` and `garbageLifetime = 24h`.[^gc-config] Each database's indexing
leader builds two collectors from the node's config, one for block
files and one for trie files,[^leader-gc][^db-gc] runs them for as long
as it leads,[^leader-gc-run] and signals both at every block
boundary;[^block-signal] `enabled` gates only that signal.[^gc-config][^trie-gc][^block-gc]
Compaction marks the files it supersedes as garbage,[^compactor-publish][^leader-add-tries][^supersede]
and with the collector on they go: of two nodes given the same writes,
the one with the collector off kept 76 level-1+ files to the other's
24.[^obs-superseded]

There is no `approxRunInterval` any more: nothing runs on a timer, only
at block boundaries.[^gc-config][^block-signal] YAML that still sets it
stops the node from starting.[^yaml-config][^obs-legacy-refused] A
Clojure node map that still sets `:approx-run-interval` starts, and the
key does nothing: only `:enabled?`, `:blocks-to-keep` and
`:garbage-lifetime` are read.[^clj-config]

## Configuring it

In node YAML, the key is `garbageCollector`, a field of the node
config,[^node-config][^yaml-config] with durations in ISO-8601
form:[^duration-serde][^obs-yaml-starts]

```yaml
garbageCollector:
  enabled: true
  blocksToKeep: 10
  garbageLifetime: PT24H
```

In a Clojure node map, the key is `:garbage-collector`,[^node-clj-key]
with `:enabled?`, `:blocks-to-keep` and `:garbage-lifetime`.[^clj-config]
The duration goes to the config's setter as it is, and that takes a
`java.time.Duration`:[^clj-config][^gc-config]

<!-- xtql-example: skip -->
```clojure
{:garbage-collector {:enabled? true
                     :blocks-to-keep 10
                     :garbage-lifetime (java.time.Duration/ofHours 24)}}
```

To turn it off, set `enabled: false` (`:enabled? false`).[^gc-config][^clj-config]

## What each setting trades

- **`blocksToKeep`.** Each cycle deletes every block file more than
  `blocksToKeep` blocks behind the latest, the global ones and each
  table's, along with log payloads offloaded to storage for those
  blocks;[^block-gc][^obs-keeps-blocks] it must be at least 1.[^block-gc]
  The cutoff for trie files is taken from the block that many back from
  the latest.[^trie-gc] More blocks kept moves the cutoff back, so
  superseded files wait longer.
- **`garbageLifetime`.** A superseded trie file is deleted only if it
  became garbage at or before that block's last transaction time minus
  `garbageLifetime`.[^trie-gc][^garbage-tries][^garbage-fn] With zero,
  the cutoff is that block's own time.[^trie-gc] The window is there for
  queries: XTDB's design notes call it the grace period in which an
  in-flight query may still reference a superseded trie,[^gc-grace] and a
  snapshot held open past it may find the collector has deleted trie
  files it still references.[^snapshot-lifetime] So keep it longer than
  your longest query.
- **When it runs.** A cycle starts at each block boundary, and
  boundaries that arrive during a cycle coalesce into one more.[^block-signal][^trie-gc]
  Each cycle covers every table in the catalog,[^trie-gc][^block-gc]
  and garbage marked after a boundary waits for the next one.[^block-signal]

## What it deletes, and what it never does

The trie collector deletes the data and meta files of tries the catalog
marks as garbage, then removes them from the catalog,[^trie-gc][^garbage-tries][^db-gc]
but never at level 0: the catalog drops superseded level-0 tries
instead of marking them,[^drop-l0] its garbage test is false at level
0,[^garbage-fn] and the collector refuses a level-0 key outright, since
those files are what `reset-compactor!` rebuilds from.[^trie-gc] Both
nodes kept as many level-0 files, 48 each.[^obs-keeps-l0]

It never touches the logs: it deletes only block files, offloaded
payloads and trie files through the buffer pool.[^block-gc][^trie-gc]
Each database now has two logs, a source log for client writes and a
replica log for the leader's output.[^log-split][^dbs-leader] A local
log keeps them as `LOG` and `REPLICA_LOG` files in its
directory,[^local-log][^local-replica-log][^log-local-disk] append-only
but for a rollback of a write that failed,[^local-log] and they didn't
shrink while the collector ran.[^obs-log-untouched] For Kafka, XTDB
creates both topics with only `message.timestamp.type` set, no
retention,[^kafka-topic][^kafka-replica-topic] and the docs leave
`retention.ms` to the operator, for both topics.[^kafka-setup][^kafka-settings]
Bumping the log's epoch makes nodes ignore the old log but doesn't
delete it.[^epoch]

The replica log grows with no writes at all. Each time a node opens a
database it isn't attached to read-only, it claims leadership by
appending a record to the replica log,[^claim-leadership][^read-only-follows]
so a node opened only to run queries still writes one. The leader then
appends a no-op whenever an assert interval passes with nothing else to
append,[^assert-noop][^assert-timeout][^election-driver] 100 ms on a
local or in-memory log and a second on any other, such as
Kafka.[^assert-interval] Each database
has its own replica log, so each grows this way.[^log-split] The log
docs call the in-memory and local logs "unaffected" by the source and
replica split;[^log-split-local] a local log still writes both files.

A topic with no `retention.ms` takes the broker's: `log.retention.ms`,
else `log.retention.minutes`, else
`log.retention.hours`.[^kafka-synonyms][^kafka-ms-doc][^kafka-minutes-doc]
In Kafka 4.3.1, the kafka-clients version XTDB v2.2.0's Kafka module
uses,[^kafka-module-clients][^kafka-version] `log.retention.hours`
defaults to 168 and a topic's `retention.ms` to 604800000, both seven
days.[^kafka-default-ms][^kafka-hours][^kafka-topic-ms] The XTDB docs call
the default one day; it is seven.[^kafka-settings][^kafka-default-ms]

Compaction is on by default, with half the processors as threads, at
least one,[^compactor-threads][^compactor-config] and a `threads` of 0
or less turns it off.[^compactor-factory] A node with compaction off
marks nothing as garbage, so the trie collector finds nothing to
delete.[^compactor-publish][^garbage-tries]

## Secondary databases

A node opens its primary database, `xtdb`, then each attached secondary
database the primary's catalog lists, each through the same
path.[^db-catalog][^db-open] Every database elects its own leader, and
that leader runs the collectors with the node's `garbageCollector`
settings,[^dbs-leader][^leader-gc][^db-gc] so secondary databases are
collected too.

Nothing sets collection per database. `garbageCollector` is a field of
the node config only,[^node-config] and a database's own config holds
its log, storage and mode, no collector
settings.[^db-config-log][^db-config-storage][^db-config-mode][^dbs-attach]
A database attached read-only never claims leadership, so its node
neither compacts nor collects there; a read-write cluster sharing the
database does.[^read-only-follows][^read-only-compactor][^dbs-attach][^dbs-shared]

## Erased data on disk

`erase-docs` irrevocably erases an id's history, through system time and
for all valid time,[^erase-docs] but the docs say erased data is
"guaranteed to be fully erased only once all background index processing
has completed and the changes have been written to the remote object
storage."[^erase-disk] Compaction merges an id's events newest
first,[^event-order][^segment-merge] and once it meets an erase, it
copies none of that id's older events into the new
file.[^polygon-erase][^segment-merge] The files it supersedes still
hold them until the collector deletes them,[^trie-gc] which with the
collector off never happens.[^gc-config][^obs-superseded] Nothing
deletes level-0 files[^garbage-fn][^obs-keeps-l0] or the
logs.[^local-log][^obs-log-untouched]

So erase controls what queries see, not disk space. It frees space only
in compacted files, once the collector deletes the ones compaction
superseded,[^polygon-erase][^trie-gc] and none in level-0 files or the
logs, which keep the erased data for good.[^garbage-fn][^obs-keeps-l0][^local-log][^obs-log-untouched]
The docs' "fully erased" waits on that background processing.[^erase-disk]
For what erase removes from query results, see the
`xtdb-erase-and-history` skill.

## Caches, detached databases and dropped tables

A node keeps two caches of object-store files: one in memory, and one on
local disk, which a remote object store requires; each has a
`maxSizeBytes`, or else a `maxSizeRatio`.[^cache-docs] The disk cache's
ratio defaults to 0.75 of its filesystem's total
space,[^disk-cache-ratio][^disk-cache-size] and the memory cache's to
0.5 of the JVM's direct memory.[^memory-cache-ratio] So a disk that fills
on a node with a remote object store may be its cache, not its data; set
`diskCache.maxSizeBytes` to cap it.

`DETACH DATABASE` returns once the database is no longer queryable, and
its cleanup closes the log subscription and flushes
buffers.[^detach-docs] Closing it closes its partitions, logs and
allocator,[^db-close] and deletes neither its log nor its storage: those
stay until you remove them.

XTDB's statements create tables but have no `DROP TABLE` or
`TRUNCATE`;[^create-table-only] to empty a table, erase each of its
ids.[^erase-docs]

## Checking it's running

Set the logger `xtdb.garbage_collector` to DEBUG. The collectors log
through `System.Logger`,[^system-logger] which the Docker image hands to
log4j, not logback, so the level goes in a log4j configuration, as the
eval's `log4j2.xml` does.[^obs-yaml-starts] The leader logs `Starting TrieGarbageCollector
(enabled=…, blocksToKeep=…, garbageLifetime=…)` and the same for
`BlockGarbageCollector` whether or not it is enabled, then, when
enabled, `Garbage collecting tries older than …` and `Garbage collecting
blocks, keeping …` each cycle.[^trie-gc][^block-gc][^obs-yaml-starts] A
node with it off logs the start with `enabled=false` and no
cycles.[^obs-off-idle]

To run one cycle by hand, call `.gcAll` on a database:
`(.gcAll (xtdb.db-catalog/primary-db node))` for the primary. It runs
the block and trie collectors once, `enabled` or not, and waits for
both; on a node that isn't the database's leader it does
nothing.[^gc-all][^gc-await][^primary-db]

## Related skills

When one of these applies and it's in your skill listing, load it
without asking. If it isn't in your listing, tell the user which skill is
missing and ask whether to install it from nhooey/xtdb-skills; don't
apply it from memory.

- `xtdb-write-path`: what a block and a level-0 file are, and what each block writes.
- `xtdb-storage-growth`: why an XTDB 2.x node's storage keeps growing, and which skill answers each cause.
- `xtdb-erase-and-history`: what erase removes from query results, and what each write adds to history.
- `xtdb-history-cost-design`: splitting volatile fields into their own table, so history grows more slowly.
[^block-gc]: [BlockGarbageCollector.kt `class BlockGarbageCollector(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/garbage_collector/BlockGarbageCollector.kt#L39-L218), unchanged since v2.2.0-beta2; differs in v2.1.1
[^block-signal]: [LeaderLogProcessor.kt line 162, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LeaderLogProcessor.kt#L162-L162), unchanged since v2.2.0-beta2; differs in v2.1.1
[^clj-config]: [garbage_collector.clj `(defmethod xtn/apply-config! :xtdb/garbage-collector`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/garbage_collector.clj#L5-L11), unchanged since v2.2.0-beta1; differs in v2.1.1
[^compactor-config]: [CompactorConfig.kt line 10, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/CompactorConfig.kt#L10-L10), unchanged since v2.0.0-beta6
[^compactor-factory]: [compactor.clj `(defn ->factory`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/compactor.clj#L11-L19), unchanged since v2.2.0-beta1; differs in v2.1.1
[^compactor-publish]: [Compactor.kt line 151, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/compactor/Compactor.kt#L151-L151), unchanged since v2.2.0-beta1; differs in v2.1.1
[^compactor-threads]: [CompactorConfig.kt line 14, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/CompactorConfig.kt#L14-L14), unchanged since v2.0.0-beta7
[^db-catalog]: [DatabaseCatalog.kt line 214, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/database/DatabaseCatalog.kt#L214-L214), unchanged since v2.2.0-beta2; differs in v2.1.1
[^db-config-log]: [Database.kt line 440, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/database/Database.kt#L440-L440), unchanged since v2.1.0-beta1
[^db-config-mode]: [Database.kt line 442, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/database/Database.kt#L442-L442), unchanged since v2.2.0-beta1; differs in v2.1.1
[^db-config-storage]: [Database.kt line 441, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/database/Database.kt#L441-L441), unchanged since v2.1.0-rc0
[^db-gc]: [GarbageCollector.kt `internal class GarbageCollector(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/GarbageCollector.kt#L22-L139), unchanged since v2.2.0-beta2; differs in v2.1.1
[^db-open]: [DatabaseCatalog.kt line 121, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/database/DatabaseCatalog.kt#L121-L121), unchanged since v2.2.0-rc0; differs in v2.1.1
[^dbs-attach]: [dbs-in-xtdb.md § Attaching/Detaching secondary databases (v2.1+), v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/dbs-in-xtdb.md?plain=1#L246-L289), unchanged since v2.2.0-rc0; differs in v2.1.1
[^dbs-leader]: [dbs-in-xtdb.md § Node processing, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/dbs-in-xtdb.md?plain=1#L210-L244), unchanged since v2.2.0-beta2; differs in v2.1.1
[^dbs-shared]: [dbs-in-xtdb.md line 57, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/dbs-in-xtdb.md?plain=1#L57-L57), unchanged since v2.1.0-rc0
[^drop-l0]: [trie_catalog.clj `(defn- drop-superseded-l0`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/trie_catalog.clj#L183-L191), unchanged since v2.2.0-beta1; differs in v2.1.1
[^duration-serde]: [Serde.kt `object DurationSerde`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/kotlin/xtdb/Serde.kt#L31-L64), unchanged since v2.1.0-rc0
[^epoch]: [log.md § Bumping an Epoch, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/ops/config/log.md?plain=1#L103-L118), unchanged since v2.1.0-rc0
[^erase-disk]: [sql-overview.mdx § ERASE as 'hard' delete, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/quickstart/sql-overview.mdx?plain=1#L437-L461), unchanged since v2.0.0-beta7
[^erase-docs]: [txs.md § `erase-docs`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/txs.md?plain=1#L180-L208), unchanged since v2.1.0-rc0
[^event-order]: [EventRowPointer.kt `class EventRowPointer(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/trie/EventRowPointer.kt#L10-L125), unchanged since v2.2.0-rc2; differs in v2.1.1
[^garbage-fn]: [trie_catalog.clj `(defn garbage-fn`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/trie_catalog.clj#L324-L327), unchanged since v2.1.0-beta1
[^garbage-tries]: [trie_catalog.clj `(defn garbage-tries`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/trie_catalog.clj#L329-L331), unchanged since v2.1.0-beta1
[^gc-all]: [Database.kt line 193, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/database/Database.kt#L193-L193), unchanged since v2.2.0-beta1; differs in v2.1.1
[^gc-await]: [LogProcessor.kt line 514, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LogProcessor.kt#L514-L514), unchanged since v2.2.0-beta2; differs in v2.1.1
[^gc-config]: [GarbageCollectorConfig.kt `data class GarbageCollectorConfig(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/GarbageCollectorConfig.kt#L9-L21), unchanged since v2.2.0-rc1; differs in v2.1.1
[^kafka-default-ms]: [LogConfig.java line 128, apache/kafka 4.3.1](https://github.com/apache/kafka/blob/4.3.1/storage/src/main/java/org/apache/kafka/storage/internals/log/LogConfig.java#L128-L128)
[^kafka-hours]: [LogConfig.java line 160, apache/kafka 4.3.1](https://github.com/apache/kafka/blob/4.3.1/storage/src/main/java/org/apache/kafka/storage/internals/log/LogConfig.java#L160-L160)
[^kafka-minutes-doc]: [ServerLogConfigs.java line 77, apache/kafka 4.3.1](https://github.com/apache/kafka/blob/4.3.1/server-common/src/main/java/org/apache/kafka/server/config/ServerLogConfigs.java#L77-L77)
[^kafka-module-clients]: [build.gradle.kts line 30, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/modules/kafka/build.gradle.kts#L30-L30), unchanged since v2.2.0-beta1; differs in v2.1.1
[^kafka-ms-doc]: [ServerLogConfigs.java line 76, apache/kafka 4.3.1](https://github.com/apache/kafka/blob/4.3.1/server-common/src/main/java/org/apache/kafka/server/config/ServerLogConfigs.java#L76-L76)
[^kafka-replica-topic]: [KafkaCluster.kt line 426, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/modules/kafka/src/main/kotlin/xtdb/api/log/KafkaCluster.kt#L426-L426), unchanged since v2.2.0-beta1; differs in v2.1.1
[^kafka-settings]: [kafka.md § Settings, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/ops/config/log/kafka.md?plain=1#L111-L134), new or changed in v2.2.0
[^kafka-setup]: [kafka.md § Setup, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/ops/config/log/kafka.md?plain=1#L95-L109), unchanged since v2.2.0-beta2; differs in v2.1.1
[^kafka-synonyms]: [ServerTopicConfigSynonyms.java line 64, apache/kafka 4.3.1](https://github.com/apache/kafka/blob/4.3.1/server-common/src/main/java/org/apache/kafka/server/config/ServerTopicConfigSynonyms.java#L64-L64)
[^kafka-topic]: [KafkaCluster.kt `private fun AdminClient.ensureTopicExists(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/modules/kafka/src/main/kotlin/xtdb/api/log/KafkaCluster.kt#L122-L474), unchanged since v2.2.0-rc2; differs in v2.1.1
[^kafka-topic-ms]: [LogConfig.java line 202, apache/kafka 4.3.1](https://github.com/apache/kafka/blob/4.3.1/storage/src/main/java/org/apache/kafka/storage/internals/log/LogConfig.java#L202-L202)
[^kafka-version]: [libs.versions.toml line 14, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/gradle/libs.versions.toml#L14-L14), unchanged since v2.2.0-beta2; differs in v2.1.1
[^leader-add-tries]: [LeaderLogProcessor.kt line 198, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LeaderLogProcessor.kt#L198-L198), unchanged since v2.2.0-beta2; differs in v2.1.1
[^leader-gc]: [LeaderLogProcessor.kt line 79, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LeaderLogProcessor.kt#L79-L79), unchanged since v2.2.0-beta2; differs in v2.1.1
[^leader-gc-run]: [LeaderLogProcessor.kt line 241, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LeaderLogProcessor.kt#L241-L241), unchanged since v2.2.0-beta2; differs in v2.1.1
[^local-log]: [LocalLog.kt `class LocalLog<M> @JvmOverloads constructor(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/log/LocalLog.kt#L45-L362), unchanged since v2.2.0-beta3; differs in v2.1.1
[^local-replica-log]: [LocalLog.kt line 350, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/log/LocalLog.kt#L350-L350), unchanged since v2.2.0-beta2; differs in v2.1.1
[^log-local-disk]: [log.md § Local disk, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/ops/config/log.md?plain=1#L54-L67), unchanged since v2.2.0-beta2; differs in v2.1.1
[^log-split]: [log.md line 21, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/ops/config/log.md?plain=1#L21-L21), unchanged since v2.2.0-beta1; differs in v2.1.1
[^node-clj-key]: [node.clj `(defmethod apply-config! :garbage-collector`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L84-L85), unchanged since v2.1.0-beta1
[^node-config]: [Xtdb.kt line 1642, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/Xtdb.kt#L1642-L1642), unchanged since v2.0.0-beta7
[^obs-keeps-blocks]: Observed on XTDB v2.2.0: example `gc-keeps-recent-blocks` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1; [releases.edn](../../evals/xtdb-storage-gc/releases.edn) lists the tags.
[^obs-keeps-l0]: Observed on XTDB v2.2.0: example `gc-keeps-l0` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command, holds on every tag the eval ran since v2.1.0-rc0; [releases.edn](../../evals/xtdb-storage-gc/releases.edn) lists the tags.
[^obs-legacy-refused]: Observed on XTDB v2.2.0: example `gc-legacy-interval-refused` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1; [releases.edn](../../evals/xtdb-storage-gc/releases.edn) lists the tags.
[^obs-log-untouched]: Observed on XTDB v2.2.0: example `gc-log-untouched` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command, holds on every tag the eval ran since v2.1.0-rc0; [releases.edn](../../evals/xtdb-storage-gc/releases.edn) lists the tags.
[^obs-off-idle]: Observed on XTDB v2.2.0: example `gc-off-idle` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1; [releases.edn](../../evals/xtdb-storage-gc/releases.edn) lists the tags.
[^obs-superseded]: Observed on XTDB v2.2.0: example `gc-deletes-superseded` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1; [releases.edn](../../evals/xtdb-storage-gc/releases.edn) lists the tags.
[^obs-yaml-starts]: Observed on XTDB v2.2.0: example `gc-yaml-starts` in [examples.edn](../../evals/xtdb-storage-gc/examples.edn), re-run by the eval's `storage` command, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1; [releases.edn](../../evals/xtdb-storage-gc/releases.edn) lists the tags.
[^polygon-erase]: [PolygonCalculator.kt `class PolygonCalculator(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/bitemporal/PolygonCalculator.kt#L7-L48), unchanged since v2.2.0-rc2; differs in v2.1.1
[^primary-db]: [db_catalog.clj `(defn primary-db`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/db_catalog.clj#L20-L23), unchanged since v2.1.0-beta1
[^read-only-compactor]: [Database.kt line 328, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/database/Database.kt#L328-L328), unchanged since v2.2.0-rc0; differs in v2.1.1
[^read-only-follows]: [LogProcessor.kt line 280, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LogProcessor.kt#L280-L280), unchanged since v2.2.0-beta2; differs in v2.1.1
[^segment-merge]: [SegmentMerge.kt `internal class SegmentMerge(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/compactor/SegmentMerge.kt#L115-L316), unchanged since v2.2.0-rc2; differs in v2.1.1
[^supersede]: [trie_catalog.clj `(defn- supersede-by-block-idx`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/trie_catalog.clj#L169-L181), unchanged since v2.2.0-beta1; differs in v2.1.1
[^trie-gc]: [TrieGarbageCollector.kt `class TrieGarbageCollector(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/garbage_collector/TrieGarbageCollector.kt#L52-L216), unchanged since v2.2.0-beta2; differs in v2.1.1
[^yaml-config]: [YamlSerde.kt `fun nodeConfig(yamlString: String)`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/YamlSerde.kt#L90-L101), unchanged since v2.2.0-rc0; differs in v2.1.1
[^system-logger]: [SystemLogger.kt line 8, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/util/SystemLogger.kt#L8-L8), unchanged since v2.0.0-beta6
[^gc-docs]: [config.md § Garbage Collector, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/ops/config.md?plain=1#L285-L301), unchanged since v2.2.0-rc1; differs in v2.1.1
[^gc-grace]: [gc.allium line 278, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/dev/doc/gc.allium#L278-L278), unchanged since v2.2.0-beta1; differs in v2.1.1
[^snapshot-lifetime]: [TrieCatalog.kt line 40, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/trie/TrieCatalog.kt#L40-L40), unchanged since v2.2.0-rc0; differs in v2.1.1
[^cache-docs]: [config.md § Caching, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/ops/config.md?plain=1#L79-L107), unchanged since v2.2.0-rc0; differs in v2.1.1
[^create-table-only]: [Sql.g4 line 62, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/antlr/xtdb/antlr/Sql.g4#L62-L62), unchanged since v2.2.0-rc0; differs in v2.1.1
[^db-close]: [Database.kt line 154, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/database/Database.kt#L154-L154), unchanged since v2.2.0-beta2; differs in v2.1.1
[^detach-docs]: [dbs-in-xtdb.md line 286, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/dbs-in-xtdb.md?plain=1#L286-L286), unchanged since v2.2.0-rc0; differs in v2.1.1
[^disk-cache-ratio]: [DiskCache.kt line 33, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/cache/DiskCache.kt#L33-L33), unchanged since v2.1.0-beta1
[^disk-cache-size]: [DiskCache.kt line 41, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/cache/DiskCache.kt#L41-L41), unchanged since v2.1.0-beta1
[^memory-cache-ratio]: [MemoryCache.kt line 250, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/cache/MemoryCache.kt#L250-L250), unchanged since v2.1.0-beta1
[^assert-interval]: [ElectionDriver.kt `class RealElectionDriver(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/ElectionDriver.kt#L30-L64), unchanged since v2.2.0-beta2; differs in v2.1.1
[^assert-noop]: [ReplicaLogAppender.kt line 65, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/ReplicaLogAppender.kt#L65-L65), unchanged since v2.2.0-beta2; differs in v2.1.1
[^assert-timeout]: [ElectionDriver.kt line 48, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/ElectionDriver.kt#L48-L48), unchanged since v2.2.0-beta2; differs in v2.1.1
[^claim-leadership]: [LogProcessor.kt line 287, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LogProcessor.kt#L287-L287), unchanged since v2.2.0-beta3; differs in v2.1.1
[^election-driver]: [LogProcessor.kt line 96, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LogProcessor.kt#L96-L96), unchanged since v2.2.0-beta2; differs in v2.1.1
[^log-split-local]: [log.md line 23, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/ops/config/log.md?plain=1#L23-L23), unchanged since v2.2.0-beta1; differs in v2.1.1
