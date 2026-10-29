---
name: xtdb-erase-and-history
description: What XTDB 2.x keeps when you put, patch, delete or erase a document — every write is a new version with the whole document, delete keeps history, erase-docs removes an id's entire history in one table, and there is no partial erase. Use when deleting or erasing data in XTDB 2.x, dropping old versions while keeping the current row, reading a row's history, or working out why history grows; when a delete-docs or erase-docs fails with "Table not found", or when giving a transaction its own :system-time. Pinned to XTDB v2.2.0, and each footnote says since which XTDB tag, betas included, it holds; every claim cites the docs, the source or an example re-run against a live node.
---

# Erase, delete and history (XTDB 2.x)

Written from `xtdb/xtdb` at tag `v2.2.0`. Each claim's footnote points at
the docs section or source it was read from or at an example in the
xtdb-skills eval that shows it on a live node; `citations.edn` lists them. For how erased
data leaves disk, see the `xtdb-storage-gc` skill.

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

## Every write is a new version

XTDB keeps a timeline per row in system time: an update closes the old
version and adds a new one, and a delete closes the current version
without removing the old ones, which stay readable by time
travel.[^system-time]

<!-- xtql-example: skip -->
```clojure
[:put-docs :docs {:xt/id 1, :v "same"}]   ; transaction 1
[:put-docs :docs {:xt/id 1, :v "same"}]   ; transaction 2: an identical document
```

Putting a document identical to the current one still records a new
system-time version: the two transactions above leave two
versions.[^obs-identical-put] Each version reads back as the whole
document, so a put that changes one field of a large document adds a
version carrying every field,[^obs-put-whole] and so does `patch-docs`,
which merges the given fields into the current document and records the
result as a new version.[^patch-docs][^obs-patch-whole]

So check before writing: when a write may repeat the stored document,
read the current row and skip the put if nothing changed.

## Reading a row's history

<!-- xtql-example: history-of-id -->
```clojure
(-> (from :docs {:bind [{:xt/id 1} v {:xt/system-from at}]
                 :for-system-time :all-time})
    (order-by at)
    (return v))
```

`:for-system-time :all-time` returns every version,[^temporal-filters][^obs-history-of-id]
and `:xt/system-from` is the system time from which a version was
current,[^system-time] so ordering by it lists the versions in the order
they were written.[^obs-history-of-id]

## Delete versus erase

`delete-docs` ends the current version, optionally only for a valid-time
range,[^delete-docs] so the row drops out of current
queries[^obs-delete-hides-current] while its old versions stay readable
with `:for-system-time :all-time`.[^obs-delete-keeps-history]

`erase-docs` "irrevocably erases documents from the given table
(including through system time), for all valid-time": it takes only a
table and ids, with no time options.[^erase-docs] Afterwards no version of
the id is left in that table, in any valid or system
time.[^obs-erase-all-versions] It erases from one table only; the same id
in another table is untouched.[^obs-erase-per-table]

Both need a table that has been written to. The client and an in-process
node both send `delete-docs` and `erase-docs` as SQL `DELETE` and `ERASE`
statements,[^client-delete][^client-erase][^node-delete-erase] and on a
table nothing has written, planning them fails with "Table not
found".[^obs-delete-unwritten][^obs-erase-unwritten] Earlier tags,
which the footnotes name, committed them instead. The whole
transaction fails with it: a put in the same transaction isn't
written either.[^obs-unwritten-whole-tx] An id the table doesn't hold is
fine; deleting or erasing it commits and changes
nothing.[^obs-absent-id] A query of a table never written returns no
rows instead of failing,[^obs-from-unwritten] so when the table may not
exist yet, read its ids with `from` first and delete only those found.

There is no erase of only the old versions: `erase-docs` takes no time
range.[^erase-docs] To drop a row's history but
keep its current state, read the current document, erase the id, then put
the document again — in that order:

<!-- xtql-example: skip -->
```clojure
;; current = the row read before erasing
[:erase-docs :docs 1]          ; transaction 1
[:put-docs :docs current]      ; transaction 2: the only version left
```

That leaves one version, the re-put document.[^obs-erase-then-put]
Erasing *after* re-putting under the same table and id erases the new
version too, leaving nothing.[^obs-put-then-erase]

## Setting a transaction's system time

`submit-tx` and `execute-tx` take a `:system-time` that overrides the
transaction's system time; their docstrings say it "mustn't be earlier
than any previous system-time".[^submit-tx-opts] It must be later: a
`:system-time` equal to the latest transaction's is rejected with
`invalid-system-time`, as an earlier one
is.[^resolver-system-time][^obs-system-time-equal] The source gives the
reason: a read basis names a transaction by its system time, so two
transactions can't share one.[^resolver-system-time] When writing with
explicit times, give each transaction a later one than the last.

## Related skills

When one of these applies and it's in your skill listing, load it
without asking. If it isn't in your listing, tell the user which skill is
missing and ask whether to install it from nhooey/xtdb-skills; don't
apply it from memory.

- `xtdb-storage-gc`: when erased data leaves disk, and what the garbage collector never deletes.
- `xtdb-storage-growth`: why an XTDB 2.x node's storage keeps growing, and which skill answers each cause.
- `xtdb-history-cost-design`: splitting volatile fields into their own table, so each write's version is smaller.
- `xtdb-write-path`: what a block and a level-0 file are, and what each block writes.
- `xtdb-1-to-2`: XTDB 1 transaction operations, evict and transaction functions, and what 2.x has instead.
[^system-time]: [time-in-xtdb.md § System time, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/time-in-xtdb.md?plain=1#L35-L81), unchanged since v2.1.0-rc0
[^delete-docs]: [txs.md § `delete-docs`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/txs.md?plain=1#L129-L178), unchanged since v2.1.0-rc0
[^erase-docs]: [txs.md § `erase-docs`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/txs.md?plain=1#L180-L208), unchanged since v2.1.0-rc0
[^obs-delete-hides-current]: Observed on XTDB v2.2.0: example `delete-hides-current` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-delete-keeps-history]: Observed on XTDB v2.2.0: example `delete-keeps-history` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-erase-all-versions]: Observed on XTDB v2.2.0: example `erase-removes-all-versions` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-erase-per-table]: Observed on XTDB v2.2.0: example `erase-is-per-table` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-erase-then-put]: Observed on XTDB v2.2.0: example `erase-then-put` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-history-of-id]: Observed on XTDB v2.2.0: example `history-of-id` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-identical-put]: Observed on XTDB v2.2.0: example `identical-put-adds-version` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-patch-whole]: Observed on XTDB v2.2.0: example `patch-adds-whole-version` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta4; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-put-then-erase]: Observed on XTDB v2.2.0: example `put-then-erase` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-put-whole]: Observed on XTDB v2.2.0: example `put-stores-whole-document` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^patch-docs]: [txs.md § `patch-docs`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/txs.md?plain=1#L68-L127), unchanged since v2.1.0-rc0
[^temporal-filters]: [queries.md § Temporal filters, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L162-L190), unchanged since v2.1.0-rc0
[^client-delete]: [api.clj `(defn- delete-docs!`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/api.clj#L203-L211), unchanged since v2.2.0-rc1; differs in v2.1.1
[^client-erase]: [api.clj `(defn- erase-docs!`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/api.clj#L213-L216), unchanged since v2.2.0-rc1; differs in v2.1.1
[^node-delete-erase]: [adbc.clj `(defn- op->tx-ops`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/adbc.clj#L97-L119), unchanged since v2.2.0-rc1; differs in v2.1.1
[^obs-absent-id]: Observed on XTDB v2.2.0: example `delete-absent-id-commits` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-delete-unwritten]: Observed on XTDB v2.2.0: example `delete-unwritten-table-fails` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.2.0-rc0; fails on v2.1.1; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-erase-unwritten]: Observed on XTDB v2.2.0: example `erase-unwritten-table-fails` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.2.0-rc0; fails on v2.1.1; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-from-unwritten]: Observed on XTDB v2.2.0: example `from-unwritten-table-empty` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-system-time-equal]: Observed on XTDB v2.2.0: example `system-time-equal-rejected` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.2.0-beta3; fails on v2.1.1; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^obs-unwritten-whole-tx]: Observed on XTDB v2.2.0: example `unwritten-table-fails-whole-tx` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.2.0-rc0; fails on v2.1.1; [releases.edn](../../evals/xtdb-erase-and-history/releases.edn) lists the tags.
[^resolver-system-time]: [TxResolver.kt line 215, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/TxResolver.kt#L215-L215), unchanged since v2.2.0-beta3; differs in v2.1.1
[^submit-tx-opts]: [api.clj `(defn submit-tx`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/api.clj#L258-L303), unchanged since v2.2.0-beta2; differs in v2.1.1
