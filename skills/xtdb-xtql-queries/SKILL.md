---
name: xtdb-xtql-queries
description: Write, read and debug XTQL queries for XTDB 2.x — the Clojure/EDN query language built from `from`, `rel`, `unify` and `->` pipelines, with binding specs, temporal filters and subqueries. Use when writing or reviewing XTQL, translating XTDB 1.x Datalog into XTQL, or when code passes a list form to `xt/q`. Pinned to XTDB v2.1.0; every claim cites the docs or source at that tag, or an example re-run against a live node.
---

# XTQL queries (XTDB 2.x)

Written from `xtdb/xtdb` at tag `v2.1.0`. Each claim carries a footnote
that points either at the docs section or source form it was read from,
or, where no source says it, at an example in the xtdb-skills eval that
shows it on a live XTDB node. `citations.edn` names those spans and
examples and `citations.lock.json` records the spans' hashes, so a newer
XTDB release can be diffed against just the parts this skill relies on
(see [Keeping this skill current](#keeping-this-skill-current)). The
citation check proves a cited span still exists unchanged, not that the
claim reads it correctly — when a claim matters, read its footnote.

## Not XTDB 1.x Datalog

An XTDB 2.x XTQL query is a list: a source operator (`from`, `rel` or
`unify`), optionally piped through tail operators with `->`, optionally
wrapped in `(fn [params] …)`. The grammar has no map form.[^grammar]
`xt/q` treats a map such as `{:find [...] :where [...]}` as an unknown
query type and throws,[^src-xt-q][^obs-v1-map] so a map-shaped Datalog
example has to be rewritten as XTQL, not copied.

## Query shape

```text
Query    :: (fn [Param*] Pipeline) | Pipeline
Pipeline :: (-> Source Tail*) | Source
Source   :: From | Rel | Unify
Tail     :: Aggregate | Limit | Offset | OrderBy | Return
          | Where | With | Without | Unnest
```

`->` here is not Clojure's threading macro; it is only the pipeline
symbol.[^grammar] A source operator is valid alone; a tail operator never
starts a pipeline.[^source-operators][^tail-operators]

Inside `unify`, the valid clauses are `from`, `join`, `left-join`, `rel`,
`unnest`, `where` and `with`.[^unify-clauses] `join` and `left-join`
appear in neither the source nor the tail operators, so they exist only
there.[^source-operators][^tail-operators]

## Binding specs

<!-- xtql-example: bind-project bind-rename bind-literal bind-param -->
```clojure
(from :customers [customer-name city])             ; project columns
(from :customers [{:customer-name name} city])     ; rename: column -> var
(from :customers [{:city "London"} customer-name]) ; constrain to a literal
(fn [city]
  (from :customers [{:city city} customer-name]))  ; constrain to a param
```

Keys in a binding map are column keywords; values are symbols (new
variables or params) or literal expressions.[^binding-specs] `[*]`
projects every column, but the docs mark `*` invalid inside `unify`,
because its columns would unify implicitly,[^from] and XTDB rejects it
there with an error.[^obs-star-in-unify]

`from` takes either a binding vector or an options map whose only keys
are `:bind`, `:for-valid-time` and `:for-system-time`.[^from][^src-from-opt-keys]

## Unification is the join

Within one `unify`, a logic variable used in two clauses must take the
same value in both — that is the join condition. A variable that appears
once is just returned.[^unify]

<!-- xtql-example: unify-join -->
```clojure
(unify (from :customers [{:xt/id customer-id} customer-name])
       (from :orders [{:xt/id order-id} customer-id order-value]))
```

Unification applies to logic variables within a single `unify`.[^unify]
In a pipeline, each later operator refers to the columns the previous
step returned.[^expressions]

`with` and `unnest` change shape with context: as tail operators they bind
a column **keyword**, `(with {:full-name …})`; inside `unify` they bind a
logic-variable **symbol**, `(with {full-name …})`.[^with][^unnest]

## Joins and arguments

`join` is an inner join and `left-join` an outer one against a whole
sub-query; their binding vector is both the join condition (for variables
reused in the `unify`) and the list of columns brought out.[^joins]

A sub-query sees only its own parameters, never the outer scope. Pass
values in with an application vector, `[(fn [post-id] …) post-id]`, or
omit the vector when the outer and inner names match — see
[Subqueries](#subqueries) for a form that silently drops
arguments.[^arguments][^subqueries][^obs-exists-with-fn][^obs-args-map-ignored]

## Temporal filters

<!-- xtql-example: valid-time-in -->
```clojure
(from :prices {:bind [{:xt/id product} price]
               :for-valid-time (in #inst "2021-01-01" #inst "2022-01-01")})
```

The filters are `(at t)`, `(from t)`, `(to t)`, `(in t1 t2)` and the
keyword `:all-time`; a missing filter means `at` now in both valid and
system time.[^temporal-filters] The parser takes `:all-time` only as a
keyword and checks the argument count of each form, so `(in t)` or
`all-time` as a symbol is a parse error, not an empty result.[^src-parse-temporal-filter]

## Subqueries

Expression-position subqueries are `(q …)` for a single value, `(exists? …)`
for a boolean, `(pull …)` for one nested map and `(pull* …)` for a vector
of maps.[^subqueries] The boolean form is `exists?` — the docs' grammar
writes `exists`, but the v2.1.0 parser only knows `exists?`; anything else
falls through to an ordinary function call.[^src-parse-expr]

A subquery sees no variables from the outer scope, only its
parameters.[^subqueries] Pass a value in through a `fn` with parameters,
either wrapped in an application vector or, when the outer variable has
the same name, bare:[^src-parse-expr][^arguments]

<!-- xtql-example: not-exists-vector exists-bare -->
```clojure
(-> (from :customers [{:xt/id cid} customer-name])
    (where (not (exists? [(fn [cid] (from :orders [{:customer-id cid}])) cid])))
    (return customer-name))

(-> (from :customers [{:xt/id customer-id} customer-name])
    (where (exists? (fn [customer-id] (from :orders [{:customer-id customer-id}]))))
    (return customer-name))
```

A trailing `{:args […]}` map, as in the docs' `pull` examples,[^subqueries]
passes the parser, which then reads only the first
argument,[^src-parse-expr] so the map is **ignored**. The same `exists?`
filter over customers keeps only those with orders when written with a
`fn`,[^obs-exists-with-fn] but keeps every customer, Dana with no orders
included, when written with `{:args [cid]}`, with no
error.[^obs-args-map-ignored]

`q` must return a single row with a single column, so end it with
`(return col)`.[^subqueries] A count through
`(q … (aggregate {:n (row-count)}))` is 0, not null, when the subquery
matches nothing.[^obs-row-count-zero] Subqueries work in `with`[^obs-q-in-with]
and `where`;[^obs-exists-in-where] in `return`, XTDB v2.1.0 rejects them with
"subquery not allowed in expr here", so compute the value in `with` and
then `return` the column.[^obs-q-in-return]

<!-- xtql-example: q-in-with -->
```clojure
(-> (from :orders [{:xt/id order-id} customer-id])
    (with {:customer-name (q [(fn [cid]
                                (-> (from :customers [{:xt/id cid} customer-name])
                                    (return customer-name)))
                              customer-id])})
    (return order-id customer-name))
```

## Nested fields

Read a key of a nested map with `(. expr field)`, where `field` is a
**symbol**: `(. meta lang)`. A keyword, `(. meta :lang)`, is a parse error
(`xtql/malformed-get`).[^src-parse-expr]

<!-- xtql-example: nested-field -->
```clojure
(-> (from :posts [title meta])
    (where (= (. meta lang) "en"))
    (return title))
```

## Query options

`xt/q` options include `:snapshot-token`, `:current-time` and
`:default-tz`, which together fix the basis, so two queries with the same
basis see the same database; `:await-token` waits for a transaction to be
indexed, and `:key-fn` controls the shape of result keys (default
kebab-case keywords).[^query-options]

## Keeping this skill current

In the xtdb-skills dev shell, from the repo root:

```sh
skill-citations drift skills/xtdb-xtql-queries --to latest
```

reports, for each footnote, whether its cited span is unchanged, changed
(with the diff), moved to another file, or missing at the newer tag, and
which sections above cite it. Files under the watched paths that changed
but are cited nowhere are listed as possible gaps. Update the sections
the report names, then `bump` to the new tag.

[^grammar]: [queries.md § Operators, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L7-L36)
[^source-operators]: [queries.md § Source operators, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L38-L44)
[^tail-operators]: [queries.md § Tail operators, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L47-L59)
[^unify-clauses]: [queries.md § Unify clauses, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L62-L78)
[^binding-specs]: [queries.md § Binding specs, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L545-L588)
[^from]: [queries.md § From, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L105-L190)
[^src-from-opt-keys]: [xtql.clj `(def from-opt-keys`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/api/src/main/clojure/xtdb/xtql.clj#L338-L338)
[^unify]: [queries.md § Unify, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L331-L361)
[^with]: [queries.md § With, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L430-L463)
[^unnest]: [queries.md § Unnest, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L363-L402)
[^joins]: [queries.md § Joins - join, left join, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L192-L213)
[^arguments]: [queries.md § Arguments, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L590-L648)
[^subqueries]: [queries.md § Subqueries, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L487-L543)
[^temporal-filters]: [queries.md § Temporal filters, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L162-L190)
[^src-parse-temporal-filter]: [xtql.clj `(defn parse-temporal-filter`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/api/src/main/clojure/xtdb/xtql.clj#L229-L260)
[^query-options]: [queries.md § Query options, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L650-L698)
[^expressions]: [queries.md § Expressions, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/docs/src/content/docs/reference/main/xtql/queries.md?plain=1#L480-L485)
[^src-parse-expr]: [xtql.clj `(defn parse-expr`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/api/src/main/clojure/xtdb/xtql.clj#L97-L161)
[^obs-args-map-ignored]: Observed on XTDB v2.1.0: example `args-map-ignored` in [examples.edn](../../evals/xtdb-xtql-queries/examples.edn), re-run by the eval's `examples` command.
[^obs-q-in-return]: Observed on XTDB v2.1.0: example `q-in-return` in [examples.edn](../../evals/xtdb-xtql-queries/examples.edn), re-run by the eval's `examples` command.
[^obs-q-in-with]: Observed on XTDB v2.1.0: example `q-in-with` in [examples.edn](../../evals/xtdb-xtql-queries/examples.edn), re-run by the eval's `examples` command.
[^obs-row-count-zero]: Observed on XTDB v2.1.0: example `row-count-zero` in [examples.edn](../../evals/xtdb-xtql-queries/examples.edn), re-run by the eval's `examples` command.
[^obs-star-in-unify]: Observed on XTDB v2.1.0: example `star-in-unify` in [examples.edn](../../evals/xtdb-xtql-queries/examples.edn), re-run by the eval's `examples` command.
[^obs-v1-map]: Observed on XTDB v2.1.0: example `v1-query-map` in [examples.edn](../../evals/xtdb-xtql-queries/examples.edn), re-run by the eval's `examples` command.
[^src-xt-q]: [api.clj `(defn plan-q`, v2.1.0](https://github.com/xtdb/xtdb/blob/v2.1.0/api/src/main/clojure/xtdb/api.clj#L86-L126)
[^obs-exists-in-where]: Observed on XTDB v2.1.0: example `not-exists-vector` in [examples.edn](../../evals/xtdb-xtql-queries/examples.edn), re-run by the eval's `examples` command.
[^obs-exists-with-fn]: Observed on XTDB v2.1.0: example `exists-bare` in [examples.edn](../../evals/xtdb-xtql-queries/examples.edn), re-run by the eval's `examples` command.
