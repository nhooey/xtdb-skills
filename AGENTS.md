# xtdb-skills

Agent skills for XTDB 2.x, built and installed through the Nix flake.

## Rules

- **No SQL and no Python in this repository.** Scripts are Babashka; code
  that talks to XTDB is Clojure through the XTDB client (`xt/q` with XTQL
  data, `xt/submit-tx` with `:put-docs` ops); data files are EDN, except
  where Nix must read them (JSON). Skills cover XTQL and XTDB 2.x only,
  not XTDB's SQL. The one exception is a conditional write's guard: an
  `[:sql "ASSERT [NOT] EXISTS (XTQL ($$ … $$, ?))" args]` op whose
  predicate is an XTQL query, since nothing else checks a query inside a
  transaction.
- Every claim in a skill carries a footnote: a span of `xtdb/xtdb` at the
  pinned tag (`citations.edn`), or an observed result that
  `evals/` re-runs against XTDB. Run `skill-citations lock` after editing
  a skill.
