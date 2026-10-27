---
name: xtdb-write-path
description: How a write travels through XTDB 2.x, from the source log through the in-memory live index to the object store, and what it leaves there — what a block is, what decides where its boundary falls (rowsPerBlock rows, default 102400, or flushDuration, default 15 minutes), and what a level-0 file is (each block writes one data file and one meta file per table that took rows). Use when working out which files an XTDB 2.x node writes and when, what "block", "level 0" or "L0" means, why recent writes aren't in storage yet, or when tuning rowsPerBlock or flushDuration. Pinned to XTDB v2.2.0; every claim cites the source or docs, or an observation re-run against a live node.
---

# The write path (XTDB 2.x)

Written from `xtdb/xtdb` at tag `v2.2.0`. Each footnote cites the docs or
source, or the xtdb-skills eval's `storage` command, which writes rows to a
node that cuts a block every 50 rows and runs no compactor or collector,
and lists the files it leaves; `citations.edn` lists them.

## From submit to the live index

A transaction's operations go to the log as one atomic
message.[^txs-atomic] Each database has a source log, the totally
ordered queue of client writes, which clients append to and the
database's leader consumes.[^external-stores] The leader resolves each
transaction and publishes the result to the replica log; followers tail
the replica log and apply each transaction to their own in-memory
index.[^node-processing] Queries read the object store and the recent
transactions in that live in-memory index.[^node-processing]

So a write is queryable before any file in storage holds it: after 10
rows, too few for a block, the node had written no block file and no
level-0 file for the table, its local `LOG` file was not empty, and a
query counted all 10 rows.[^obs-below-block]

## Blocks

A block is the run of transactions between two block
boundaries.[^boundary-order] The
leader counts the rows of each transaction it resolves into the open
block,[^tx-rows] and cuts the block once that count reaches
`rowsPerBlock`.[^block-full] It counts whole transactions, so one is never
split: with `rowsPerBlock` at 50, 10 rows and then 50 cut one
block,[^obs-block-at-rows] and a single transaction of 125 rows after it
cut one more, two block files in all.[^obs-tx-not-split]

A database too quiet to reach `rowsPerBlock` cuts a block every
`flushDuration` anyway, written to or not,[^quiet-flush][^indexer-docs]
unless another block landed in the meantime.[^block-flusher] A block with
no transactions writes no tries, only the block file and its table
blocks.[^empty-block] The defaults are
`rowsPerBlock: 102400` and `flushDuration: PT15M`,[^indexer-config][^indexer-docs]
the docs' "~100k rows/15 minutes";[^leader-block] before v2.2,
`flushDuration` defaulted to `PT4H`.[^flush-changelog]

```yaml
indexer:
  rowsPerBlock: 102400
  flushDuration: PT15M
```

In a Clojure node map, the key is `:indexer`, with `:rows-per-block` and
`:flush-duration`.[^clj-indexer]

## What a block writes

At the boundary the leader turns the live index into files.[^finish-block]
Each table that took rows in the block writes one level-0 trie, keyed by
the block's index:[^l0-key][^live-table-l0] a data file and a meta file,
both `<trie key>.arrow`, under the table's `data/` and `meta/`
directories.[^trie-writer-data][^trie-writer-meta][^data-path][^meta-path]
A key is `l` and the level in hex, `-r` and `c` where a level-0 key has
no recency, then `-b` and the block index in
hex,[^key-format][^key-recency][^key-block][^l0-key] so block 0 writes `data/l00-rc-b00.arrow` and
`meta/l00-rc-b00.arrow`.[^obs-l0-pair] A table that took no rows writes
none:[^no-rows] after a second block that wrote to `:docs` and
`:orders`, `:docs` had level-0 files for blocks 0 and 1, and `:orders`
for block 1 only.[^obs-l0-per-table]

The leader also writes the block file, `blocks/b<index>.binpb`,[^block-file][^block-path]
and signals the compactor,[^signal-compactor] which re-sorts and
re-partitions block files in the object store to make them faster to
query.[^node-processing]

## Related skills

When one of these applies and it's in your skill listing, load it
without asking. If it isn't in your listing, tell the user which skill is
missing and ask whether to install it from nhooey/xtdb-skills; don't
apply it from memory.

- `xtdb-storage-gc`: which of the files a block writes the garbage collector deletes, and which, level-0 files and the logs among them, it never does.
- `xtdb-storage-growth`: why an XTDB 2.x node's storage keeps growing, and which skill answers each cause.
- `xtdb-erase-and-history`: what each put, patch, delete and erase adds to a table's history.
[^block-file]: [BlockCutter.kt line 242, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/BlockCutter.kt#L242-L242)
[^block-flusher]: [BlockFlusher.kt `data class BlockFlusher(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/BlockFlusher.kt#L18-L50)
[^block-full]: [BlockCutter.kt line 124, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/BlockCutter.kt#L124-L124)
[^block-path]: [TableCatalog.kt line 346, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/catalog/TableCatalog.kt#L346-L346)
[^boundary-order]: [BlockCutter.kt line 150, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/BlockCutter.kt#L150-L150)
[^clj-indexer]: [node.clj `(defmethod apply-config! :indexer`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/node.clj#L52-L59)
[^data-path]: [TableSlug.kt line 21, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/table/TableSlug.kt#L21-L21)
[^external-stores]: [dbs-in-xtdb.md § External stores, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/dbs-in-xtdb.md?plain=1#L185-L208)
[^finish-block]: [BlockCutter.kt line 183, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/BlockCutter.kt#L183-L183)
[^flush-changelog]: [config.md line 12, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/ops/config.md?plain=1#L12-L12)
[^indexer-config]: [IndexerConfig.kt `data class IndexerConfig(`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/api/IndexerConfig.kt#L12-L31)
[^indexer-docs]: [config.md § Indexer, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/ops/config.md?plain=1#L244-L283)
[^key-format]: [Trie.kt line 44, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/trie/Trie.kt#L44-L44)
[^l0-key]: [Trie.kt line 54, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/trie/Trie.kt#L54-L54)
[^leader-block]: [dbs-in-xtdb.md line 223, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/dbs-in-xtdb.md?plain=1#L223-L223)
[^live-table-l0]: [LiveTable.kt line 195, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LiveTable.kt#L195-L195)
[^meta-path]: [TableSlug.kt line 25, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/table/TableSlug.kt#L25-L25)
[^next-block]: [LiveIndex.kt line 262, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LiveIndex.kt#L262-L262)
[^no-rows]: [LiveTable.kt line 193, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/LiveTable.kt#L193-L193)
[^node-processing]: [dbs-in-xtdb.md § Node processing, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/dbs-in-xtdb.md?plain=1#L210-L244)
[^obs-below-block]: Observed on XTDB v2.2.0: example `wp-below-block` in [examples.edn](../../evals/xtdb-write-path/examples.edn), re-run by the eval's `storage` command.
[^obs-block-at-rows]: Observed on XTDB v2.2.0: example `wp-block-at-rows` in [examples.edn](../../evals/xtdb-write-path/examples.edn), re-run by the eval's `storage` command.
[^obs-l0-pair]: Observed on XTDB v2.2.0: example `wp-l0-pair` in [examples.edn](../../evals/xtdb-write-path/examples.edn), re-run by the eval's `storage` command.
[^obs-l0-per-table]: Observed on XTDB v2.2.0: example `wp-l0-per-table` in [examples.edn](../../evals/xtdb-write-path/examples.edn), re-run by the eval's `storage` command.
[^obs-tx-not-split]: Observed on XTDB v2.2.0: example `wp-tx-not-split` in [examples.edn](../../evals/xtdb-write-path/examples.edn), re-run by the eval's `storage` command.
[^signal-compactor]: [BlockCutter.kt line 295, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/BlockCutter.kt#L295-L295)
[^trie-writer-data]: [LiveTrieWriter.kt line 14, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/trie/LiveTrieWriter.kt#L14-L14)
[^trie-writer-meta]: [LiveTrieWriter.kt line 15, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/trie/LiveTrieWriter.kt#L15-L15)
[^tx-rows]: [BlockCutter.kt line 141, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/BlockCutter.kt#L141-L141)
[^txs-atomic]: [txs-in-xtdb.md line 11, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/txs-in-xtdb.md?plain=1#L11-L11)
[^empty-block]: [BlockFlusher.kt line 15, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/BlockFlusher.kt#L15-L15)
[^key-block]: [Trie.kt line 47, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/trie/Trie.kt#L47-L47)
[^key-recency]: [Trie.kt line 45, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/trie/Trie.kt#L45-L45)
[^quiet-flush]: [BlockFlusher.kt line 11, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/kotlin/xtdb/indexer/BlockFlusher.kt#L11-L11)
