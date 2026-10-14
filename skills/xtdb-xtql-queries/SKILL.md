---
name: xtdb-xtql-queries
description: Write, read and debug XTQL queries for XTDB 2.x — the Clojure/EDN query language built from `from`, `rel`, `unify` and `->` pipelines, with binding specs, temporal filters and subqueries. Use when writing or reviewing XTQL, translating SQL or XTDB 1.x Datalog into XTQL, or when code passes a list form to `xt/q`. Pinned to XTDB v2.1.0; every claim cites the docs or source at that tag.
---

# XTQL queries (XTDB 2.x)

Everything here is checked against `xtdb/xtdb` at tag `v2.1.0`. Each claim
carries a footnote that points at the exact docs section or source form it
comes from; `citations.toml` names those spans and `citations.lock.json`
records their hashes, so a newer XTDB release can be diffed against just
the parts this skill relies on (see [Keeping this skill current](#keeping-this-skill-current)).

## Not XTDB 1.x Datalog

XTDB 2.x XTQL is a list form, `(from …)`, `(unify …)` or `(-> source tail…)`;
its grammar has no map form.[^grammar] Most XTDB examples online are 1.x,
whose `{:find [...] :where [...]}` query maps a 2.x node rejects, so
translate such an example rather than copying it.

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
`unnest`, `where` and `with` — `join` and `left-join` exist only
there.[^unify-clauses]

## Binding specs

```clojure
(from :users [username first-name])            ; project columns
(from :users [{:username login} first-name])   ; rename: column -> var
(from :users [{:username "james"} first-name]) ; constrain to a literal
(fn [username]
  (from :users [{:username username} first-name])) ; constrain to a param
```

Keys in a binding map are column keywords; values are symbols (new
variables or params) or literal expressions.[^binding-specs] `[*]`
projects every column, but `*` is rejected inside `unify`, because its
columns would unify implicitly.[^from]

`from` takes either a binding vector or an options map whose only keys
are `:bind`, `:for-valid-time` and `:for-system-time`.[^from][^src-from-opt-keys]

## Unification is the join

Within one `unify`, a logic variable used in two clauses must take the
same value in both — that is the join condition. A variable that appears
once is just returned.[^unify]

```clojure
(unify (from :customers [{:xt/id customer-id} customer-name])
       (from :orders [{:xt/id order-id} customer-id order-value]))
```

Implicit joins happen only inside `unify`. Outside it, an operator sees
the columns the previous step returned; it does not unify with
them.[^unify][^expressions]

`with` and `unnest` change shape with context: as tail operators they bind
a column **keyword**, `(with {:full-name …})`; inside `unify` they bind a
logic-variable **symbol**, `(with {full-name …})`.[^with][^unnest]

## Joins and arguments

`join` is an inner join and `left-join` an outer one against a whole
sub-query; their binding vector is both the join condition (for variables
reused in the `unify`) and the list of columns brought out.[^joins]

A sub-query sees only its own parameters, never the outer scope. Pass
values in with an application vector, `[(fn [post-id] …) post-id]`, or
omit the vector when the outer and inner names match.[^arguments][^subqueries]

## Temporal filters

```clojure
(from :users {:bind [username]
              :for-valid-time (in #inst "2020-01-01" #inst "2021-01-01")
              :for-system-time (at #inst "2023-01-01")})
```

The filters are `(at t)`, `(from t)`, `(to t)`, `(in t1 t2)` and the
keyword `:all-time`; a missing filter means `at` now in both valid and
system time.[^temporal-filters] The parser takes `:all-time` only as a
keyword and checks the argument count of each form, so `(in t)` or
`all-time` as a symbol is a parse error, not an empty result.[^src-parse-temporal-filter]

## Subqueries

Expression-position subqueries are `(q …)` for a single value, `(exists …)`
for a boolean, `(pull …)` for one nested map and `(pull* …)` for a vector
of maps. `q` and `pull` throw if the sub-query returns more than one row.
The boolean form is `exists`, not `exists?`.[^subqueries]

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
