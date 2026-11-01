---
name: xtdb-embedded-node
description: Run XTDB 2.x as an embedded, in-process node from Clojure — xtdb-core on the classpath, `xtdb.node/start-node` with a Clojure node map (:log, :storage, :server, :flight-sql, :compactor, :garbage-collector, :indexer), closing it with `with-open`, and the JVM options it needs (--add-opens=java.base/java.nio=ALL-UNNAMED and the rest). Use when starting an XTDB node inside a Clojure app, test or REPL; when a node starts but stops ingesting with "sun.misc.Unsafe or java.nio.DirectByteBuffer.<init>(long, int) not available"; when turning off its Postgres-wire or Flight SQL server (:server nil, :flight-sql); when an uberjar of xtdb-core fails on a case-insensitive filesystem such as macOS's with "Cannot write META-INF/license/... as parent dir is a file from another lib" (netty-tcnative-boringssl-static via Arrow Flight SQL); when xtdb-core's own org.apache.arrow.memory.BufferLedger and arrow-memory-core's clash on the classpath or in an uberjar; or when asking whether a node's storage (the `v06` directory, Storage VERSION 6) carries over between XTDB releases. Pinned to XTDB v2.2.0, and each footnote says since which XTDB tag, betas included, it holds; every claim cites the source or docs, or an observation re-run by the eval.
---

# The embedded node (XTDB 2.x)

Written from `xtdb/xtdb` at tag `v2.2.0`. Each footnote cites the docs or
source, or the xtdb-skills eval's `storage` command, which for this skill
starts nodes of the classpath's xtdb-core in its own JVM and in new ones,
reads the classpath, and builds uberjars with tools.build; no container
runs. Arrow's own code cites `apache/arrow-java` at `v19.0.0`, and
tools.build cites `clojure/tools.build` at `v0.10.10`. What blocks write and when is in the `xtdb-write-path`
skill, and the `:compactor` and `:garbage-collector` keys are in the
`xtdb-storage-gc` skill.

## Versions

Each footnote says since which `xtdb/xtdb` tag, betas and release
candidates included, its cited span has read the same ("unchanged
since"), or since which tag the eval saw its example hold ("holds on
every tag the eval ran since"); "differs in" and "fails on" name later
tags off that line, such as a patch release of an older minor version.

Before relying on a claim, find the project's XTDB version: the
`com.xtdb/xtdb-api` or `com.xtdb/xtdb-core` version in `deps.edn` or the
build file. If every footnote on a claim starts after that version, or
names it under "differs in" or "fails on", the claim may not hold there:
say so, and check that version's docs or source, rather than applying it.

## Dependencies and JVM options

An in-process node needs `com.xtdb/xtdb-core` on the classpath, beside
`com.xtdb/xtdb-api`.[^core-artifact] XTDB's docs give four JVM options
for it, "to run Apache Arrow":[^jvm-opts-why][^jvm-opts-alias]

- `--add-opens=java.base/java.nio=ALL-UNNAMED`
- `--enable-native-access=ALL-UNNAMED`
- `-Dio.netty.tryReflectionSetAccessible=true`
- `-Dio.netty.noUnsafe=false`, required on JDK 25 and later, where Netty
  otherwise turns off the `sun.misc.Unsafe` access Arrow's allocator
  depends on.[^jvm-opts-jdk25]

Put them on the alias that starts the JVM, as `:jvm-opts`.[^jvm-opts-alias]
Leaving them out fails late, not at startup. In a JVM without any of
them, on Java 21, a node started and took a transaction, then stopped
ingesting when it reached its first block:
`Ingestion stopped (msgId: null): sun.misc.Unsafe or
java.nio.DirectByteBuffer.<init>(long, int) not available`.[^obs-block-without-opts]
With only `--add-opens=java.base/java.nio=ALL-UNNAMED`, the same node
wrote its block.[^obs-add-opens-only] So a test suite that never writes
enough rows for a block can pass without the options while a real
workload fails.[^obs-block-without-opts]

## Starting and closing a node

`xtdb.node/start-node` takes a node map, an `Xtdb$Config`, or a YAML
file as a `File` or `Path`; with no argument it starts an in-memory
node.[^start-node] The node must be closed, with `.close` or
`with-open`;[^start-node] it is an `AutoCloseable`,[^xtdb-closeable] and
a query after `.close` throws.[^obs-autocloseable]

<!-- xtql-example: skip -->
```clojure
(require '[xtdb.node :as xtn]
         '[xtdb.api :as xt])

(with-open [node (xtn/start-node {:log [:local {:path "data/log"}]
                                  :storage [:local {:path "data/storage"}]
                                  :server nil})]
  (xt/execute-tx node [[:put-docs :docs {:xt/id 1}]])
  (xt/q node '(from :docs [xt/id])))
```

`xtn/->config` turns the map into an `Xtdb$Config`, applying one key at a
time;[^to-config] a key it doesn't know is logged as a warning, "Unknown
configuration key", and otherwise ignored.[^unknown-key] The keys:

| Key | Value | Default |
| --- | --- | --- |
| `:log` | `[:in-memory]`, `[:local {:path …}]` or `[:kafka …]`[^clj-log][^log-in-memory][^log-factory][^log-kafka] | in memory[^config-log-default] |
| `:storage` | `[:in-memory]`, `[:local {:path …}]` or `[:remote {:object-store …}]`[^clj-storage] | in memory[^config-storage-default] |
| `:server` | the Postgres-wire server's options, or `nil` for none[^clj-server][^server-nil] | on[^config-server-default] |
| `:flight-sql` | the Flight SQL server's options; see below[^clj-flight][^flight-clj] | on[^config-flight-default] |
| `:indexer` | `{:rows-per-block … :flush-duration …}` and more[^clj-indexer]; see `xtdb-write-path` | — |
| `:compactor` | `{:threads n}`[^clj-compactor]; see `xtdb-storage-gc` | — |
| `:garbage-collector` | see `xtdb-storage-gc`[^clj-gc] | — |

So a node map with only `:log` and `:storage` still opens two servers.
The Postgres-wire server binds the loopback address[^server-host] on a
port chosen at startup,[^server-port] and the Flight SQL server binds
`127.0.0.1`;[^flight-host] a node from `{}` listened on both, each on a
port of its own.[^obs-default-servers]

### `:server nil`

`:server nil` sets the config's server to none.[^server-nil] The node then
reports a server port of `-1`, and `xt/q` and `xt/execute-tx` on it work
as before.[^obs-server-nil] Use it for a node only the JVM talks to, such
as one in a test.

### Turning off Flight SQL

In v2.2.0, `:flight-sql` in a node map can't turn the server off:

- `:flight-sql nil` changes nothing: the node still opened a Flight SQL
  port.[^obs-flight-nil] The handler only sets the options given.[^flight-clj]
- `:flight-sql {:port -1}` stops `start-node` with `port out of
  range:-1`,[^obs-flight-port-refused] though `FlightSqlConfig` documents
  `-1` as not starting the server.[^flight-port-doc]
- The first `:flight-sql` map a JVM sees is ignored. `xtdb.node` passes
  `:flight-sql` on as `:xtdb.flight-sql/server`,[^clj-flight] while
  `xtdb.flight-sql` defines its handler under `:flight-sql`
  itself,[^flight-clj] replacing `xtdb.node`'s once that namespace is
  loaded. In a new JVM, a first node asking for port 45678 got another
  port, and a second asking for 45679 got it.[^obs-flight-first-map]
  `(require 'xtdb.flight-sql)` before the first `start-node` makes the
  first map count.[^obs-flight-require-first]

To start a node with no Flight SQL server, build the config and clear it:

<!-- xtql-example: skip -->
```clojure
(xtn/start-node (doto (xtn/->config {:server nil})
                  (.setFlightSql nil)))
```

That node reported a Flight SQL port of `-1` and answered
queries.[^obs-flight-off]

## Building an uberjar

### The `META-INF/license` collision

xtdb-core depends on Arrow's `flight-sql` as an API
dependency,[^core-flight-sql] at Arrow 19.0.0,[^flight-sql-lib][^arrow-version]
and Arrow's `flight-core` depends on
`io.netty/netty-tcnative-boringssl-static`.[^flight-core-tcnative]
Resolved from xtdb-core 2.2.0, that is version 2.0.74.Final, through
xtdb-core → flight-sql → flight-core.[^obs-tcnative]

On the eval's classpath that brought five per-platform tcnative jars
(Linux aarch\_64 and x86\_64, macOS aarch\_64 and x86\_64, Windows
x86\_64), each with a `META-INF/license/` directory, and no other jar
has one. 25 other jars there, Arrow's among them, have a
`META-INF/LICENSE` file.[^obs-license-dir] On a case-insensitive
filesystem, such as the eval's on macOS, tools.build's `uber` stops: `Cannot write
META-INF/license/LICENSE.boringssl.txt from
io.netty/netty-tcnative-boringssl-static$linux-x86_64 as parent dir is a
file from another lib. One of them must be excluded.`[^obs-uber-collision]

Excluding tcnative's directory lets the build finish:[^obs-uber-default-ledger]

<!-- xtql-example: skip -->
```clojure
(b/uber {:class-dir "target/classes"
         :uber-file "target/app.jar"
         :basis basis
         :exclude ["META-INF/license/.*"]})
```

The pattern leaves out only the tcnative jars' license texts, since no
other jar has that directory.[^obs-license-dir] The exclusion alone
isn't enough, though; see the next section.

### Which `BufferLedger` runs

xtdb-core ships its own `org.apache.arrow.memory.BufferLedger`, in Arrow's
package,[^ledger-package] so it and arrow-memory-core's copy have the same
class name. They differ in `retain`: XTDB's checks the reference count
and adds to it in one compare-and-set,[^ledger-retain-xtdb] where Arrow's
adds first and then checks that the count it added to was
positive.[^ledger-retain-arrow][^ledger-check-arrow]

On the eval's classpath, built by the Clojure CLI, xtdb-core's jar came
before arrow-memory-core's, and the `BufferLedger` the JVM loaded was
xtdb-core's.[^obs-ledger-from-core] To run the copy xtdb-core ships,
keep xtdb-core ahead of arrow-memory-core wherever you order a classpath
by hand.

An uberjar holds one file per path. tools.build's `uber` keeps the first copy
of a path it writes unless a conflict handler says
otherwise,[^uber-default-ignore] and in the eval's build of xtdb-core 2.2.0
that was arrow-memory-core's.[^obs-uber-default-ledger] A conflict handler
that prefers xtdb-core's copy fixes it:

<!-- xtql-example: skip -->
```clojure
(defn prefer-xtdb-core [{:keys [path in lib]}]
  (when (= 'com.xtdb/xtdb-core lib)
    {:write {path {:stream in}}}))

(b/uber {:class-dir "target/classes"
         :uber-file "target/app.jar"
         :basis basis
         :exclude ["META-INF/license/.*"]
         :conflict-handlers {"org/apache/arrow/memory/BufferLedger(\\$.*)?\\.class"
                             prefer-xtdb-core}})
```

That uberjar held xtdb-core's `BufferLedger.class`, and a node started
from it, with the four JVM options, answered a query.[^obs-uber-fixed]
The eval's `xtdb-eval.uber` namespace runs exactly these builds.

## Storage across releases

A node writes its files under a directory named for the storage
version,[^storage-root] which is 6,[^storage-version] so a node with
`:storage [:local {:path "data/storage"}]` writes under
`data/storage/v06/`.[^obs-storage-v06] The `xtdb-write-path` skill
covers how the name is formed and what goes under it. The source says
to bump the version if the storage format changes in a
backwards-incompatible way;[^storage-bump] the version's footnote says
since which tag it has read 6.

The source also keeps a single-partition database's files at the same
keys as before multi-partition databases,[^single-partition-keys] and
must keep doing so,[^single-partition-layout] because an older version resolves against
the unmarked root: writing under `parts/0/` would leave a rollback with
nothing to read.[^rollback-reads] Before moving a node's storage to
another release, compare the two releases' storage versions.

## Related skills

When one of these applies and it's in your skill listing, load it
without asking. If it isn't in your listing, tell the user which skill is
missing and ask whether to install it from nhooey/xtdb-skills; don't
apply it from memory.

- `xtdb-write-path`: what a block is, when one is cut, and the files it writes under the storage-version directory.
- `xtdb-storage-gc`: the `:garbage-collector` and `:compactor` keys of a node map, and what the collector deletes.
- `xtdb-storage-growth`: why an XTDB 2.x node's storage keeps growing, and which skill answers each cause.
- `xtdb-xtql-queries`: writing the XTQL queries an embedded node runs with `xt/q`.
[^arrow-version]: [libs.versions.toml line 3, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/gradle/libs.versions.toml#L3-L3), unchanged since v2.2.0-beta1; differs in v2.1.1
[^clj-compactor]: [node.clj `(defmethod apply-config! :compactor`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L61-L63), unchanged since v2.0.0-beta6
[^clj-flight]: [node.clj `(defmethod apply-config! :flight-sql`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L72-L73), unchanged since v2.2.0-beta1; differs in v2.1.1
[^clj-gc]: [node.clj `(defmethod apply-config! :garbage-collector`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L84-L85), unchanged since v2.1.0-beta1
[^clj-indexer]: [node.clj `(defmethod apply-config! :indexer`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L52-L59), unchanged since v2.1.0-rc0
[^clj-log]: [node.clj `(defmethod apply-config! :log `, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L40-L41), unchanged since v2.1.0-rc0
[^clj-server]: [node.clj `(defmethod apply-config! :server`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L75-L76), unchanged since v2.0.0-beta2
[^clj-storage]: [buffer_pool.clj `(defmethod xtn/apply-config! ::storage`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/buffer_pool.clj#L40-L46), unchanged since v2.1.0-rc0
[^config-flight-default]: [Xtdb.kt line 1630, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/Xtdb.kt#L1630-L1630), unchanged since v2.2.0-beta1; differs in v2.1.1
[^config-log-default]: [Xtdb.kt line 1633, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/Xtdb.kt#L1633-L1633), unchanged since v2.1.0-rc0
[^config-server-default]: [Xtdb.kt line 1629, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/Xtdb.kt#L1629-L1629), unchanged since v2.0.0-beta3
[^config-storage-default]: [Xtdb.kt line 1634, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/Xtdb.kt#L1634-L1634), unchanged since v2.1.0-rc0
[^core-artifact]: [clojure.md line 65, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/drivers/clojure.md?plain=1#L65-L65), unchanged since v2.1.0-rc0
[^core-flight-sql]: [build.gradle.kts line 47, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/build.gradle.kts#L47-L47), unchanged since v2.2.0-beta1; differs in v2.1.1
[^flight-clj]: [flight_sql.clj `(defmethod xtn/apply-config! :flight-sql`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/flight_sql.clj#L34-L41), unchanged since v2.2.0-rc2; differs in v2.1.1
[^flight-core-tcnative]: [pom.xml line 72, apache/arrow-java v19.0.0](https://github.com/apache/arrow-java/blob/v19.0.0/flight/flight-core/pom.xml#L72-L72)
[^flight-host]: [FlightSqlConfig.kt line 12, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/FlightSqlConfig.kt#L12-L12), unchanged since v2.2.0-beta1; differs in v2.1.1
[^flight-port-doc]: [FlightSqlConfig.kt line 30, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/FlightSqlConfig.kt#L30-L30), unchanged since v2.2.0-beta1; differs in v2.1.1
[^flight-sql-lib]: [libs.versions.toml line 45, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/gradle/libs.versions.toml#L45-L45), unchanged since v2.2.0-beta1; differs in v2.1.1
[^jvm-opts-alias]: [clojure.md line 69, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/drivers/clojure.md?plain=1#L69-L69), unchanged since v2.1.0-rc0
[^jvm-opts-jdk25]: [clojure.md line 89, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/drivers/clojure.md?plain=1#L89-L89), unchanged since v2.2.0-beta3; differs in v2.1.1
[^jvm-opts-why]: [clojure.md line 85, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/drivers/clojure.md?plain=1#L85-L85), unchanged since v2.1.0-rc0
[^ledger-check-arrow]: [BufferLedger.java line 178, apache/arrow-java v19.0.0](https://github.com/apache/arrow-java/blob/v19.0.0/memory/memory-core/src/main/java/org/apache/arrow/memory/BufferLedger.java#L178-L178)
[^ledger-package]: [BufferLedger.java line 17, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/java/org/apache/arrow/memory/BufferLedger.java#L17-L17), unchanged since v2.1.0-rc0
[^ledger-retain-arrow]: [BufferLedger.java line 177, apache/arrow-java v19.0.0](https://github.com/apache/arrow-java/blob/v19.0.0/memory/memory-core/src/main/java/org/apache/arrow/memory/BufferLedger.java#L177-L177)
[^ledger-retain-xtdb]: [BufferLedger.java line 179, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/java/org/apache/arrow/memory/BufferLedger.java#L179-L179), unchanged since v2.1.0-rc0
[^log-factory]: [log.clj `(defmethod ->log-factory ::local `, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/log.clj#L75-L79), unchanged since v2.1.0-rc0
[^obs-add-opens-only]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-rc1; fails on v2.1.1.
[^obs-autocloseable]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-rc1; fails on v2.1.1.
[^obs-block-without-opts]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-rc1; fails on v2.1.1.
[^obs-default-servers]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1.
[^obs-flight-first-map]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1.
[^obs-flight-nil]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1.
[^obs-flight-off]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-rc1; fails on v2.1.1.
[^obs-flight-port-refused]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1.
[^obs-flight-require-first]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1.
[^obs-ledger-from-core]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.1.0-rc0.
[^obs-license-dir]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1.
[^obs-server-nil]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-rc1; fails on v2.1.1.
[^obs-storage-v06]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-rc1; fails on v2.1.1.
[^obs-tcnative]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1.
[^obs-uber-collision]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-beta1; fails on v2.1.1.
[^obs-uber-default-ledger]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.0.0-beta8.4.
[^obs-uber-fixed]: Observed on XTDB v2.2.0, holds on every tag the eval ran since v2.2.0-rc1; fails on v2.1.1.
[^rollback-reads]: [Storage.kt line 68, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/storage/Storage.kt#L68-L68), unchanged since v2.2.0-rc1; differs in v2.1.1
[^server-host]: [ServerConfig.kt line 12, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/ServerConfig.kt#L12-L12), unchanged since v2.1.0-beta1
[^server-nil]: [pgwire.clj `(defmethod xtn/apply-config! ::server`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/pgwire.clj#L1893-L1909), unchanged since v2.2.0-rc0; differs in v2.1.1
[^server-port]: [ServerConfig.kt line 30, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/ServerConfig.kt#L30-L30), unchanged since v2.0.0-beta5
[^single-partition-layout]: [Storage.kt line 67, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/storage/Storage.kt#L67-L67), unchanged since v2.2.0-rc1; differs in v2.1.1
[^start-node]: [node.clj `(defn start-node`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L102-L119), unchanged since v2.1.0-rc0
[^storage-bump]: [Storage.kt line 39, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/storage/Storage.kt#L39-L39), unchanged since v2.0.0-beta6
[^storage-root]: [Storage.kt line 45, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/storage/Storage.kt#L45-L45), unchanged since v2.1.0-rc0
[^storage-version]: [Storage.kt line 40, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/storage/Storage.kt#L40-L40), unchanged since v2.0.0-beta7
[^to-config]: [node.clj `(defn ->config`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L90-L100), unchanged since v2.0.0-beta7
[^uber-default-ignore]: [uber.clj line 258, clojure/tools.build v0.10.10](https://github.com/clojure/tools.build/blob/v0.10.10/src/main/clojure/clojure/tools/build/tasks/uber.clj#L258-L258)
[^unknown-key]: [node.clj `(defmethod apply-config! ::default`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L87-L88), unchanged since v2.0.0-beta3
[^xtdb-closeable]: [Xtdb.kt line 80, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/Xtdb.kt#L80-L80), unchanged since v2.1.0-rc0
[^log-in-memory]: [log.clj line 81, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/log.clj#L81-L81), unchanged since v2.1.0-rc0
[^log-kafka]: [log.clj line 83, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/log.clj#L83-L83), unchanged since v2.1.0-rc0
[^single-partition-keys]: [Storage.kt line 66, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/storage/Storage.kt#L66-L66), unchanged since v2.2.0-rc1; differs in v2.1.1
