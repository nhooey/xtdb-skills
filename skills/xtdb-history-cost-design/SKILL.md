---
name: xtdb-history-cost-design
description: Keep XTDB 2.x history small by splitting a document's fast-changing fields into their own table, so a price or stock change doesn't store the whole document again. Use when designing XTDB 2.x tables for large documents with a few volatile fields, when storage grows faster than the data, or when reading a split entity back as of a time. Pinned to XTDB v2.2.0; every claim cites the docs or an example re-run against a live node.
---

# Splitting volatile fields (XTDB 2.x)

Written from `xtdb/xtdb` at tag `v2.2.0`. Each claim's footnote points at
the docs section it was read from or at an example in the xtdb-skills eval
that shows it on a live node; `citations.edn` lists them. For what each
write keeps, see the `xtdb-erase-and-history` skill.

## Why a whole document costs history

Every put adds a version carrying the whole document, unchanged fields
included.[^obs-whole-versions] A large product record whose price changes
daily gains a version a day, each carrying its description.

## The split

Put the fields that change often in their own table under the same
`:xt/id`, and the stable ones in another. Each table gets a version only
when its own fields change: an initial price and two changes add three
versions to the volatile table[^obs-split-volatile] and leave the stable
one with one.[^obs-split-stable] A price change then adds a version
carrying only the volatile fields.

Group fields by how often they change together, not by meaning: a field
that changes with the price belongs with the price. Write both halves of a
new entity in one transaction, whose operations go to the log as one
atomic message,[^atomic-txs] and skip the put of a half whose fields did not
change, since an identical put still adds a version.[^obs-identical-put]

## Reading it back as of a time

Join the halves on `:xt/id` in `unify`, with the same temporal filter on
each `from`:[^unify-clauses][^temporal-filters]

<!-- xtql-example: split-as-of -->
```clojure
(fn [t]
  (unify (from :products {:bind [{:xt/id id} title] :for-valid-time (at t)})
         (from :product-prices {:bind [{:xt/id id} price stock] :for-valid-time (at t)})))
```

Each `from` picks the version current at `t` in its own table, so the
result is the entity as it was at `t`, though the halves changed at
different times.[^obs-split-as-of] Without `:for-valid-time` both read
the current versions.[^temporal-filters] Use `:for-system-time` instead
to ask what the database had recorded by a time, system time being when
each version was current in the database.[^temporal-filters][^system-time]

`unify` drops an entity missing either half.[^obs-split-missing] Where
the volatile half can be absent, bring it in with `left-join`, which keeps
the row with the volatile columns null, so absent from the returned
map:[^joins][^obs-split-left-join]

<!-- xtql-example: skip -->
```clojure
(unify (from :products [{:xt/id id} title])
       (left-join (from :product-prices [{:xt/id id} price]) [id price]))
```

[^atomic-txs]: [txs-in-xtdb.md line 11, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/txs-in-xtdb.md?plain=1#L11-L11)
[^joins]: [queries.md § Joins - join, left join, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L192-L213)
[^obs-identical-put]: Observed on XTDB v2.2.0: example `identical-put-adds-version` in [examples.edn](../../evals/xtdb-history-cost-design/examples.edn), re-run by the eval's `examples` command.
[^obs-split-as-of]: Observed on XTDB v2.2.0: example `split-as-of` in [examples.edn](../../evals/xtdb-history-cost-design/examples.edn), re-run by the eval's `examples` command.
[^obs-split-left-join]: Observed on XTDB v2.2.0: example `split-left-join` in [examples.edn](../../evals/xtdb-history-cost-design/examples.edn), re-run by the eval's `examples` command.
[^obs-split-missing]: Observed on XTDB v2.2.0: example `split-missing-half` in [examples.edn](../../evals/xtdb-history-cost-design/examples.edn), re-run by the eval's `examples` command.
[^obs-split-stable]: Observed on XTDB v2.2.0: example `split-stable-versions` in [examples.edn](../../evals/xtdb-history-cost-design/examples.edn), re-run by the eval's `examples` command.
[^obs-split-volatile]: Observed on XTDB v2.2.0: example `split-volatile-versions` in [examples.edn](../../evals/xtdb-history-cost-design/examples.edn), re-run by the eval's `examples` command.
[^obs-whole-versions]: Observed on XTDB v2.2.0: example `whole-document-versions` in [examples.edn](../../evals/xtdb-history-cost-design/examples.edn), re-run by the eval's `examples` command.
[^temporal-filters]: [queries.md § Temporal filters, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L162-L190)
[^unify-clauses]: [queries.md § Unify clauses, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L62-L78)
[^system-time]: [time-in-xtdb.md § System time, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/time-in-xtdb.md?plain=1#L35-L81)
