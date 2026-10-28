---
name: xtdb-1-to-2
description: What XTDB 1.x (and early 2.x betas) offered that XTDB 2.x no longer has, and what to write instead — so code written from memory of XTDB 1 isn't copied into a 2.x project. Use when porting or reviewing XTDB 1 code, or whenever a query or transaction uses XTDB 1's API or Datalog — `{:find … :where …}` maps, `:rules`, `or`, `or-join`, `not`, `not-join`, `get-attr`, `pull-many`, `xt/db`, `open-db`, `open-q`, `entity-history`, `with-tx`, `::xt/put`, `::xt/match`, `::xt/evict`, `::xt/fn` transaction functions, `crux.api` — a conditional write (insert or update only if a query holds, `::xt/match`, `ASSERT`), or the removed 2.x ops `:put-fn`, `:call`, `:insert-into`, `:update`, `:delete`, `:erase`, `:assert-exists`, `:assert-not-exists`. Pinned to XTDB v2.2.0 and, for XTDB 1, to 1.24.5, and each footnote says since which XTDB tag, betas included, it holds; every claim cites the source or docs, or an example re-run against a live node.
---

# Coming from XTDB 1 (XTDB 2.x)

Written from `xtdb/xtdb` at tag `v2.2.0`, and at `1.24.5`, the last 1.x
release, for what XTDB 1 did. Each footnote cites a span of one of them,
or an example in the xtdb-skills eval that a live 2.2.0 node rejects; a
release that brings a feature back fails that example.
`citations.edn` lists them.

A model's memory of XTDB is mostly XTDB 1, whose API and query language
2.x replaced. Check each XTDB call against this list before writing it,
and write queries with `xtdb-xtql-queries`.

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

## Queries

- **Datalog maps.** XTDB 1's `q` took a Datalog query as a map, vector or
  string.[^v1-q] XTDB 2's `xt/q` throws `:unknown-query-type` for a
  map;[^unknown-query-type][^obs-v1-datalog-map] its queries are XTQL
  lists, from `from`, `rel`, `unify` and `->`.[^grammar]
- **Rules, recursive or not.** XTDB 1 rules were named clauses, and a rule
  could invoke itself.[^v1-rules][^v1-recursive-rules] XTQL has no rule
  operator: an unknown operator fails with
  `:xtql/unknown-query-op`.[^unknown-query-op][^obs-v1-rules] There's no XTQL replacement;
  `xtdb-xtql-queries`' "Graphs and recursion" shows fixed-depth and
  client-side traversals.
- **`or` and `not` clauses.** XTDB 1 had `or` and `not`
  clauses,[^v1-or][^v1-not] but neither is an XTQL unify clause, and both
  fail with `:xtql/unknown-unify-clause`.[^unknown-unify-clause][^obs-v1-or-clause][^obs-v1-not-clause]
  Write `or` and `not` as boolean functions inside
  `where`,[^boolean-fns][^obs-or-in-where] and a "no matching row" test
  as `(not (exists? …))`:[^subqueries]

<!-- xtql-example: or-in-where -->
```clojure
(-> (from :people [name city])
    (where (or (= city "Paris") (= city "Berlin")))
    (return name))
```

- **`not-exists?`.** The subquery forms are `exists?`, `q`, `pull` and
  `pull*`;[^subquery-forms] `not-exists?` isn't one, so its argument is
  read as an ordinary expression and the query
  fails.[^obs-not-exists-fn]
- **`get-attr`.** XTDB 1 offered `get-attr` as a lookup in place of a
  triple clause.[^v1-get-attr] In XTDB 2 it fails ("get_attr not
  applicable");[^obs-v1-get-attr] bind the column in `from` instead.
- **`pull` and `pull-many`.** XTDB 1's API fetched one or many entities by
  a projection spec.[^v1-pull][^v1-pull-many] In XTDB 2 they're
  subquery expressions inside a query, `pull` for one row and `pull*`
  for many,[^subquery-forms] and `xtdb.api` has no `pull` or
  `pull-many`.[^obs-v1-api-gone]
- **The db value: `db`, `open-db` and its valid time.** XTDB 1 queried a
  db snapshot, which could be taken as of a valid
  time.[^v1-db] XTDB 2 has no db value (`xtdb.api`
  has no `db` or `open-db`);[^obs-v1-api-gone] `xt/q` takes the node,
  with `:snapshot-time` and `:current-time` as
  options,[^q-opts] and each `from` takes its own `:for-valid-time` and
  `:for-system-time`.[^temporal-filters]
- **`entity-history`.** XTDB 1 read an entity's versions with
  `entity-history`;[^v1-history] XTDB 2 has none,[^obs-v1-api-gone] and
  a `from` with `:for-valid-time :all-time` returns every
  version:[^temporal-filters][^obs-history-all-time]

<!-- xtql-example: history-all-time -->
```clojure
(-> (from :people {:bind [{:xt/id 1} city] :for-valid-time :all-time})
    (return city))
```

- **`open-q`.** XTDB 1's lazy cursor[^v1-open-q] is gone;[^obs-v1-api-gone]
  `xt/plan-q` returns a reducible that streams the
  results.[^plan-q]
- **`with-tx`.** XTDB 1 could apply transaction operations speculatively
  to a db value and query the result.[^v1-with-tx] XTDB 2 has no
  `with-tx`,[^obs-v1-api-gone] and no XTQL replacement.

## Transactions

- **XTDB 1 operations.** `::xt/put`, `::xt/match`, `::xt/evict` and
  `::xt/fn` all fail with
  `:xtql/unknown-tx-op`.[^unknown-tx-op][^obs-v1-put-op][^obs-v1-match-op][^obs-v1-evict-op][^obs-v1-fn-op]
  XTDB 2's operations are `:put-docs`, `:patch-docs`, `:delete-docs` and
  `:erase-docs`, each naming its table.[^tx-ops]
- **Evict.** XTDB 1's evict removed every version of a
  document;[^v1-evict] `:erase-docs` is its
  replacement.[^erase-docs] `xtdb-erase-and-history` covers it.
- **Match.** XTDB 1's match stopped the transaction if an entity didn't
  match a document.[^v1-match] The 2.x `:assert-exists` and
  `:assert-not-exists` ops are removed
  too;[^assert-exists-removed][^assert-not-exists-removed][^obs-assert-exists-removed][^obs-assert-not-exists-removed]
  write an XTQL guard instead, under "Conditional writes" below.
- **Transaction functions.** XTDB 1 stored Clojure functions that ran
  while the transaction was indexed,[^v1-tx-fns] with a db value that
  included the transaction's earlier operations, which they could
  query.[^v1-tx-fn-db][^v1-tx-fn-speculative] XTDB 2.x had them as
  `:put-fn` and `:call`, and removed both in
  2.0.0-beta7.[^put-fn-removed][^call-removed][^obs-put-fn-removed][^obs-call-removed]
- **XTQL DML.** The early 2.x betas wrote with XTQL: `:insert-into`,
  `:update`, `:delete`, `:erase`, `:assert-exists` and
  `:assert-not-exists`. All six were removed in 2.0.0-beta7 and now
  throw
  `:xtdb/xtql-dml-removed`.[^insert-into-removed][^update-removed][^delete-removed][^erase-removed][^assert-exists-removed][^assert-not-exists-removed][^obs-insert-into-removed][^obs-update-removed][^obs-delete-removed][^obs-erase-removed][^obs-assert-exists-removed][^obs-assert-not-exists-removed]

The errors for both name SQL DML and ASSERT as the
replacement.[^put-fn-removed][^insert-into-removed]

## Conditional writes

A write that must happen only if a query holds, such as "insert this user
unless the email is taken", is one transaction: an `ASSERT` guard, then
the writes. A transaction's operations go together, atomically,[^txs-atomic]
and `ASSERT` rolls the transaction back if its predicate is
false.[^sql-assert] The guard is an `[:sql …]` op,[^sql-op] the one SQL
these skills write, and its predicate is an XTQL query, embedded as
`XTQL ($$ query $$, ?)`, with one `?` per `fn` parameter, filled in order
from the vector after the string:[^xtql-in-sql]

<!-- xtql-example: skip -->
```clojure
(def email-unused
  "ASSERT NOT EXISTS (XTQL ($$ (fn [e] (from :users [{:email e}])) $$, ?))")

(xt/execute-tx node
  [[:sql email-unused ["c@x"]]
   [:put-docs :users {:xt/id 7 :email "c@x"}]])
```

- **When the guard holds,** the writes commit.[^obs-guard-commits]
- **When it fails,** `execute-tx` throws[^execute-tx-throws] "Assert failed"
  (code `:xtdb/assert-failed`) and nothing in the transaction is written: the
  row already there is the only one.[^obs-guard-aborts] `submit-tx`
  doesn't throw; check the transaction's row in `:xt/txs`.[^sql-assert]
- **Parameters need the parentheses:** `XTQL $$ … $$, ?` without them
  fails to parse;[^obs-guard-params-need-parens] with no parameters, the
  bare `XTQL $$ … $$` works.[^xtql-in-sql][^obs-guard-no-params]

Reading with `xt/q` and then submitting the write isn't atomic: another
transaction can land between the two. Use the guard. Writes computed
from a query, and DML generally, are SQL these skills don't cover: say
so and ask, rather than reaching for an XTDB 1 or beta API.

## Queries as data

In XTDB 1, a query was data, a map, vector or string,[^v1-q] and a
transaction function could query the in-transaction db.[^v1-tx-fn-db]
In XTDB 2, an XTQL query is still a Clojure form, which `xt/q` prints
into a SQL `XTQL ($$ … $$)` call,[^xtql-sql] and `xt/template` builds
one with `~` and `~@` like a syntax quote.[^template] Inside a
transaction, a query runs only as an `ASSERT` guard: transaction
functions are gone.[^put-fn-removed][^call-removed]

## Related skills

When one of these applies and it's in your skill listing, load it
without asking. If it isn't in your listing, tell the user which skill is
missing and ask whether to install it from nhooey/xtdb-skills; don't
apply it from memory.

- `xtdb-xtql-queries`: writing the XTQL that replaces a Datalog query, including graph traversals without rules.
- `xtdb-erase-and-history`: what `:put-docs`, `:delete-docs` and `:erase-docs` keep in history, and reading it.
[^assert-exists-removed]: [tx_ops.clj `(defmethod parse-tx-op :assert-exists`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/tx_ops.clj#L133-L137), unchanged since v2.2.0-beta1; differs in v2.1.1
[^assert-not-exists-removed]: [tx_ops.clj `(defmethod parse-tx-op :assert-not-exists`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/tx_ops.clj#L139-L143), unchanged since v2.2.0-beta1; differs in v2.1.1
[^boolean-fns]: [predicates.md § Boolean functions, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/stdlib/predicates.md?plain=1#L29-L50), unchanged since v2.2.0-beta1; differs in v2.1.1
[^call-removed]: [tx_ops.clj `(defmethod parse-tx-op :call`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/tx_ops.clj#L145-L149), unchanged since v2.2.0-beta1; differs in v2.1.1
[^delete-removed]: [tx_ops.clj `(defmethod parse-tx-op :delete [`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/tx_ops.clj#L106-L110), unchanged since v2.2.0-beta1; differs in v2.1.1
[^erase-docs]: [txs.md § `erase-docs`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/txs.md?plain=1#L180-L208), unchanged since v2.1.0-rc0
[^erase-removed]: [tx_ops.clj `(defmethod parse-tx-op :erase [`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/tx_ops.clj#L122-L126), unchanged since v2.2.0-beta1; differs in v2.1.1
[^grammar]: [queries.md § Operators, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L7-L36), unchanged since v2.1.0-rc0
[^insert-into-removed]: [tx_ops.clj `(defmethod parse-tx-op :insert-into`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/tx_ops.clj#L94-L98), unchanged since v2.2.0-beta1; differs in v2.1.1
[^obs-assert-exists-removed]: Observed on XTDB v2.2.0: example `assert-exists-removed` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta7; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-assert-not-exists-removed]: Observed on XTDB v2.2.0: example `assert-not-exists-removed` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta7; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-call-removed]: Observed on XTDB v2.2.0: example `call-removed` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta7; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-delete-removed]: Observed on XTDB v2.2.0: example `delete-removed` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta7; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-erase-removed]: Observed on XTDB v2.2.0: example `erase-removed` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta7; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-history-all-time]: Observed on XTDB v2.2.0: example `history-all-time` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-insert-into-removed]: Observed on XTDB v2.2.0: example `insert-into-removed` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta7; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-not-exists-fn]: Observed on XTDB v2.2.0: example `not-exists-fn` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-or-in-where]: Observed on XTDB v2.2.0: example `or-in-where` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-put-fn-removed]: Observed on XTDB v2.2.0: example `put-fn-removed` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta7; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-update-removed]: Observed on XTDB v2.2.0: example `update-removed` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta7; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-v1-api-gone]: Observed on XTDB v2.2.0: example `v1-api-gone` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-v1-datalog-map]: Observed on XTDB v2.2.0: example `v1-datalog-map` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-v1-evict-op]: Observed on XTDB v2.2.0: example `v1-evict-op` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-v1-fn-op]: Observed on XTDB v2.2.0: example `v1-fn-op` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-v1-get-attr]: Observed on XTDB v2.2.0: example `v1-get-attr` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-v1-match-op]: Observed on XTDB v2.2.0: example `v1-match-op` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-v1-not-clause]: Observed on XTDB v2.2.0: example `v1-not-clause` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-v1-or-clause]: Observed on XTDB v2.2.0: example `v1-or-clause` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-v1-put-op]: Observed on XTDB v2.2.0: example `v1-put-op` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-v1-rules]: Observed on XTDB v2.2.0: example `v1-rules` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^plan-q]: [api.clj `(defn plan-q`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/api.clj#L94-L118), unchanged since v2.2.0-rc1; differs in v2.1.1
[^put-fn-removed]: [tx_ops.clj `(defmethod parse-tx-op :put-fn`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/tx_ops.clj#L75-L79), unchanged since v2.2.0-beta1; differs in v2.1.1
[^q-opts]: [api.clj `(defn q`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/api.clj#L120-L164), unchanged since v2.1.0-rc0
[^subqueries]: [queries.md § Subqueries, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L487-L543), unchanged since v2.1.0-rc0
[^subquery-forms]: [xtql.clj line 141, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/xtql.clj#L141-L141), unchanged since v2.2.0-rc0; differs in v2.1.1
[^template]: [api.clj `(defmacro template`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/api.clj#L468-L481), unchanged since v2.0.0-beta1
[^temporal-filters]: [queries.md § Temporal filters, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L162-L190), unchanged since v2.1.0-rc0
[^tx-ops]: [txs.md § Transaction operations, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/xtql/txs.md?plain=1#L10-L208), unchanged since v2.1.0-rc0
[^unknown-query-op]: [xtql.clj line 45, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/xtql.clj#L45-L45), unchanged since v2.2.0-rc0; differs in v2.1.1
[^unknown-query-type]: [api.clj line 116, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/api.clj#L116-L116), unchanged since v2.0.0-beta8.3
[^unknown-tx-op]: [tx_ops.clj line 27, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/tx_ops.clj#L27-L27), unchanged since v2.2.0-beta1; differs in v2.1.1
[^unknown-unify-clause]: [xtql.clj line 78, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/clojure/xtdb/xtql.clj#L78-L78), unchanged since v2.2.0-rc0; differs in v2.1.1
[^update-removed]: [tx_ops.clj `(defmethod parse-tx-op :update [`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/tx_ops.clj#L100-L104), unchanged since v2.2.0-beta1; differs in v2.1.1
[^v1-db]: [api.clj line 356, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/core/src/xtdb/api.clj#L356-L356)
[^v1-evict]: [datalog-transactions.adoc line 196, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/docs/language-reference/modules/ROOT/pages/datalog-transactions.adoc#L196-L196)
[^v1-get-attr]: [datalog-queries.adoc line 314, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/docs/language-reference/modules/ROOT/pages/datalog-queries.adoc#L314-L314)
[^v1-history]: [api.clj line 230, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/core/src/xtdb/api.clj#L230-L230)
[^v1-match]: [datalog-transactions.adoc line 155, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/docs/language-reference/modules/ROOT/pages/datalog-transactions.adoc#L155-L155)
[^v1-not]: [datalog-queries.adoc line 436, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/docs/language-reference/modules/ROOT/pages/datalog-queries.adoc#L436-L436)
[^v1-open-q]: [api.clj `(defn open-q`, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/core/src/xtdb/api.clj#L416-L433)
[^v1-or]: [datalog-queries.adoc line 481, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/docs/language-reference/modules/ROOT/pages/datalog-queries.adoc#L481-L481)
[^v1-pull]: [api.clj line 212, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/core/src/xtdb/api.clj#L212-L212)
[^v1-pull-many]: [api.clj line 220, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/core/src/xtdb/api.clj#L220-L220)
[^v1-q]: [api.clj `(defn q`, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/core/src/xtdb/api.clj#L405-L411)
[^v1-recursive-rules]: [datalog-queries.adoc line 668, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/docs/language-reference/modules/ROOT/pages/datalog-queries.adoc#L668-L668)
[^v1-rules]: [datalog-queries.adoc line 651, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/docs/language-reference/modules/ROOT/pages/datalog-queries.adoc#L651-L651)
[^v1-tx-fn-db]: [datalog-transactions.adoc line 244, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/docs/language-reference/modules/ROOT/pages/datalog-transactions.adoc#L244-L244)
[^v1-tx-fn-speculative]: [datalog-transactions.adoc line 251, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/docs/language-reference/modules/ROOT/pages/datalog-transactions.adoc#L251-L251)
[^v1-tx-fns]: [datalog-transactions.adoc line 226, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/docs/language-reference/modules/ROOT/pages/datalog-transactions.adoc#L226-L226)
[^v1-with-tx]: [api.clj line 278, xtdb/xtdb 1.24.5](https://github.com/xtdb/xtdb/blob/1.24.5/core/src/xtdb/api.clj#L278-L278)
[^xtql-sql]: [api.clj `(defn- xtql->sql`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/api.clj#L61-L71), unchanged since v2.0.0-beta8.3
[^execute-tx-throws]: [api.clj line 320, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/api.clj#L320-L320), unchanged since v2.0.0-beta8.3
[^obs-guard-aborts]: Observed on XTDB v2.2.0: example `guard-aborts` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta8; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-guard-commits]: Observed on XTDB v2.2.0: example `guard-commits` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta8; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-guard-no-params]: Observed on XTDB v2.2.0: example `guard-no-params` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta8; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^obs-guard-params-need-parens]: Observed on XTDB v2.2.0: example `guard-params-need-parens` in [examples.edn](../../evals/xtdb-1-to-2/examples.edn), re-run by the eval's `examples` command, holds on every tag the eval ran since v2.0.0-beta2; [releases.edn](../../evals/xtdb-1-to-2/releases.edn) lists the tags.
[^sql-assert]: [txs.md § `ASSERT`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/reference/main/sql/txs.md?plain=1#L99-L132), unchanged since v2.1.0-rc0
[^sql-op]: [tx_ops.clj `(defmethod parse-tx-op :sql [`, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/api/src/main/clojure/xtdb/tx_ops.clj#L53-L57), unchanged since v2.2.0-rc1; differs in v2.1.1
[^txs-atomic]: [txs-in-xtdb.md line 34, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/docs/src/content/docs/about/txs-in-xtdb.md?plain=1#L34-L34), unchanged since v2.1.0-rc0
[^xtql-in-sql]: [Sql.g4 line 704, v2.2.0](https://github.com/xtdb/xtdb/blob/v2.2.0/core/src/main/antlr/xtdb/antlr/Sql.g4#L704-L704), unchanged since v2.0.0-beta8
