---
name: xtdb-erase-and-history
description: What XTDB 2.x keeps when you put, patch, delete or erase a document — every write is a new version with the whole document, delete keeps history, erase-docs removes an id's entire history in one table, and there is no partial erase. Use when deleting or erasing data in XTDB 2.x, dropping old versions while keeping the current row, reading a row's history, or working out why history grows. Pinned to XTDB v2.2.0; every claim cites the docs or an example re-run against a live node.
---

# Erase, delete and history (XTDB 2.x)

Written from `xtdb/xtdb` at tag `v2.2.0`. Each claim's footnote points at
the docs section it was read from or at an example in the xtdb-skills eval
that shows it on a live node; `citations.edn` lists them. For how erased
data leaves disk, see the `xtdb-storage-gc` skill.

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

## Related skills

When one of these applies and it's in your skill listing, load it
without asking. If it isn't in your listing, tell the user which skill is
missing and ask whether to install it from nhooey/xtdb-skills; don't
apply it from memory.

- `xtdb-storage-gc`: when erased data leaves disk, and what the garbage collector never deletes.
- `xtdb-storage-growth`: why an XTDB 2.x node's storage keeps growing, and which skill answers each cause.
- `xtdb-history-cost-design`: splitting volatile fields into their own table, so each write's version is smaller.
- `xtdb-write-path`: what a block and a level-0 file are, and what each block writes.
[^system-time]: [time-in-xtdb.md § System time, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/time-in-xtdb.md?plain=1#L35-L81)
[^delete-docs]: [txs.md § `delete-docs`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/txs.md?plain=1#L129-L178)
[^erase-docs]: [txs.md § `erase-docs`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/txs.md?plain=1#L180-L208)
[^obs-delete-hides-current]: Observed on XTDB v2.2.0: example `delete-hides-current` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command.
[^obs-delete-keeps-history]: Observed on XTDB v2.2.0: example `delete-keeps-history` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command.
[^obs-erase-all-versions]: Observed on XTDB v2.2.0: example `erase-removes-all-versions` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command.
[^obs-erase-per-table]: Observed on XTDB v2.2.0: example `erase-is-per-table` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command.
[^obs-erase-then-put]: Observed on XTDB v2.2.0: example `erase-then-put` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command.
[^obs-history-of-id]: Observed on XTDB v2.2.0: example `history-of-id` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command.
[^obs-identical-put]: Observed on XTDB v2.2.0: example `identical-put-adds-version` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command.
[^obs-patch-whole]: Observed on XTDB v2.2.0: example `patch-adds-whole-version` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command.
[^obs-put-then-erase]: Observed on XTDB v2.2.0: example `put-then-erase` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command.
[^obs-put-whole]: Observed on XTDB v2.2.0: example `put-stores-whole-document` in [examples.edn](../../evals/xtdb-erase-and-history/examples.edn), re-run by the eval's `examples` command.
[^patch-docs]: [txs.md § `patch-docs`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/txs.md?plain=1#L68-L127)
[^temporal-filters]: [queries.md § Temporal filters, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L162-L190)
