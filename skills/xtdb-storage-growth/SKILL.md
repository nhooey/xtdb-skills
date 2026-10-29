---
name: xtdb-storage-growth
description: Route a question about XTDB 2.x storage growing — disk usage climbing, an object store or storage directory getting bigger, logs or Kafka topics filling up, data not shrinking after a delete or erase, a disk cache filling the disk, a detached database still on disk, "why is XTDB using so much space", "how do I reclaim space", "does XTDB ever delete anything", "does erase free disk" — to the skills that answer each cause. It holds no facts of its own; it sorts the growth into history versions, uncollected files, logs or unerased data, and loads the skill for each. Use whenever an XTDB 2.x node's storage, disk, object store, log or Kafka topic grows, or someone asks how to shrink it.
---

# Why XTDB storage grows (XTDB 2.x)

This skill states nothing about XTDB itself. It sorts a storage-growth
question into its causes and sends each to the skill that answers it,
with that skill's citations. Most real cases have more than one cause,
so go through every branch, not only the first that fits.

## Sort the growth

1. **Is it new versions?** Do writes repeat unchanged documents, or
   rewrite a large document to change a few fields? Load
   `xtdb-erase-and-history` for what each write keeps in history, and `xtdb-history-cost-design` for splitting volatile
   fields into their own table.
2. **Is it files the collector hasn't deleted, or never will?** Is the
   garbage collector on, and what are `blocksToKeep` and
   `garbageLifetime`? Is compaction running? Load `xtdb-storage-gc`, and
   `xtdb-write-path` for what a block and a level-0 file are.
   Is the growth in the disk cache's directory, or in a database that
   was detached? Load `xtdb-storage-gc` for both.
3. **Is it the logs?** Is the growth in the source or replica log, a
   local `LOG` or `REPLICA_LOG` file or a Kafka topic, not the object
   store? Does it grow while the node takes no writes? Load
   `xtdb-storage-gc`, which covers what deletes the logs, what the
   replica log takes with no writes, and Kafka's retention.
4. **Is it data that should have been erased?** Was the data deleted
   rather than erased, or erased but still on disk? Load
   `xtdb-erase-and-history` for delete against erase, and
   `xtdb-storage-gc` for when erased data leaves disk.

Answer from the loaded skills, with their footnotes, not from memory.

## Related skills

When one of these applies and it's in your skill listing, load it
without asking. If it isn't in your listing, tell the user which skill is
missing and ask whether to install it from nhooey/xtdb-skills; don't
apply it from memory.

- `xtdb-erase-and-history`: what each put, patch, delete and erase keeps in history.
- `xtdb-history-cost-design`: splitting volatile fields into their own table, so history grows more slowly.
- `xtdb-storage-gc`: what the garbage collector deletes and never deletes, the logs, and erased data on disk.
- `xtdb-write-path`: what a block and a level-0 file are, and what each block writes.
