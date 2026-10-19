# xtdb-skills

A collection of [Agent Skills](https://www.anthropic.com/engineering/agent-skills) for [XTDB](https://xtdb.com), compatible with Claude Code, Codex, Gemini CLI, Cursor, and the `npx skills` / `gh skill` CLIs.

## Install

```sh
# via npx
npx skills add nhooey/xtdb-skills

# via gh CLI extension
gh skill install nhooey/xtdb-skills

# manually
git clone https://github.com/nhooey/xtdb-skills.git
cp -r xtdb-skills/skills/* ~/.claude/skills/
```

This repo also works as a [Claude Code plugin marketplace](https://docs.claude.com/en/docs/claude-code/plugins). Add it with:

```sh
/plugin install xtdb-skills --marketplace nhooey/xtdb-skills   # Claude Code 2.1.275+
/plugin marketplace add nhooey/xtdb-skills                      # older versions, then /plugin install
```

### Install via Nix flake

The repo is also a Nix flake built with [`nhooey/agent-skill-flake`](https://github.com/nhooey/agent-skill-flake). The top-level flake aggregates every skill under `skills/`.

The default `nix run` is a **read-only preview** — it lists what would be installed and where, without touching your filesystem. To actually install, use the explicit `#install` app.

```sh
# Preview what would be installed (no side effects)
nix run github:nhooey/xtdb-skills -- --scope=personal

# Actually install
nix run github:nhooey/xtdb-skills#install -- --scope=personal   # into ~/.claude/skills/
nix run github:nhooey/xtdb-skills#install -- --scope=project    # into <project>/.claude/skills/

# Or build a derivation containing the skill files (no install side-effect)
nix build github:nhooey/xtdb-skills                             # all skills, symlinkJoined
```

Home Manager users can install the aggregate declaratively with `agent-skill-flake`'s `homeManagerModules.default`; see its README.

## Development

`nix develop` (or direnv) enters the `xtdb-skills` devShell. On startup it reconciles the authoring skill set pinned in the `skills-devshell/` sub-flake into `.claude/skills/` at project scope, so those skills never become inputs of the root flake.

`nix fmt` formats with treefmt (nixfmt, shfmt, yamlfmt).

## Skills in this repo

| Name | Description | Link |
| --- | --- | --- |
| xtdb-erase-and-history | What put, patch, delete and erase keep in XTDB 2.x history, and how to drop a row's history but keep the row. | [skills/xtdb-erase-and-history](skills/xtdb-erase-and-history) |
| xtdb-history-cost-design | Split a document's volatile fields into their own table so history doesn't store the whole document per change. | [skills/xtdb-history-cost-design](skills/xtdb-history-cost-design) |
| xtdb-storage-gc | Turn on XTDB 2.x's storage garbage collector, off by default, and what it never deletes. | [skills/xtdb-storage-gc](skills/xtdb-storage-gc) |
| xtdb-xtql-queries | Write, read and debug XTQL queries for XTDB 2.x. | [skills/xtdb-xtql-queries](skills/xtdb-xtql-queries) |

Each is pinned to XTDB v2.1.0.

## Citing XTDB sources

Every claim in a skill carries a footnote, of one of two kinds, so a new XTDB release can be checked against only the parts the skill relies on:

- **A span of the upstream docs or source at a Git tag.** `citations.edn` (written by hand) names the repo and tag, the paths to watch, and one `:cite` per footnote id with a path and an anchor: a Markdown `:heading`, the start of a top-level Clojure `:form`, or a unique `:line`. `citations.lock.json` (generated; JSON so Nix can read it) records the commit the tag resolved to, the hash of each cited file, and the lines and hash of each span.
- **An observed result**, for behaviour no source states, such as a form XTDB silently ignores. An `:observed` entry names an example in the eval's `examples.edn`, which the eval re-runs against a live node.

The dev shell's `skill-citations` command (`scripts/skill-citations.clj`, Babashka) does the work:

```sh
skill-citations lock   skills/<skill>                # resolve anchors, write the lock, regenerate footnotes
skill-citations verify skills/<skill>                # lock, footnotes and anchors agree at the pinned tag
skill-citations drift  skills/<skill> --to latest    # each citation unchanged / changed / moved / missing, with diffs
skill-citations bump   skills/<skill> --to <tag>     # move the pin after updating the passages drift named
skill-citations pairs  skills/<skill>                # each citing passage beside what it cites, for a reviewer
```

`nix flake check` runs `verify` for every skill with a lock, as `checks.<system>.citations-<skill>`, fetching only the cited files at the locked commit. GitHub Actions runs it on every push to `master` and every pull request (`.github/workflows/ci.yml`).

Every Monday, `.github/workflows/citation-drift.yml` runs `drift --to latest` for each skill through the flake's `skill-citations` app, pre-release tags included. While any cited span differs, it keeps one open issue holding the latest report and the steps to bump. `drift` skips the observed footnotes, so a bump also moves the eval's XTDB image and client to the new version and re-runs `examples` and `storage`.

## Measuring a skill

A citation check proves a cited span is unchanged, not that the skill reads it correctly. `evals/` measures each skill against a real XTDB instead, with one suite directory per skill. It is Clojure and talks to the node through the XTDB client. Every suite has an `examples.edn` holding the expected result of every example in the skill plus the observations its footnotes cite; an example's `:setup` transactions run first, in tables private to that example. `xtdb-xtql-queries` also has `seed.edn`, its fixture as transactions, and `tasks.edn`, 35 tasks with their expected rows.

```sh
docker run -d --rm --name xtdb-eval -p 15432:5432 ghcr.io/xtdb/xtdb:2.1.0
cd evals
clojure -M:eval seed xtdb-xtql-queries                 # load seed.edn
clojure -M:eval prompt xtdb-xtql-queries > tasks.txt   # the sheet an answerer gets
clojure -M:eval score xtdb-xtql-queries baseline.edn with-skill.edn   # {task-id query-string}
clojure -M:eval examples xtdb-xtql-queries xtdb-erase-and-history xtdb-history-cost-design
clojure -M:eval storage xtdb-storage-gc                # about 2 minutes; needs Docker
```

`storage` starts its own two nodes, one with the garbage collector on and one with it off, writes the same rows to both, and compares their storage directories.

Every ```` ```clojure ```` block in the skill is preceded by `<!-- xtql-example: id… -->`, one id per top-level form; `examples` fails on an unmarked block, so a new example can't go untested. `skill-citations pairs` feeds a reviewer who checks each claim against only what it cites. That catches misreadings, while running queries catches errors in the sources themselves, such as the docs' `exists`.

On 2026-10-15, fresh answerers on Claude Opus 5.5 scored 27/35 from memory and 35/35 with the skill. All 8 misses were subqueries (`exists?`, argument passing, `q` in `return`) and nested-field access, and 4 of those tasks were held out from the skill's revision. A reviewer subagent then checked the skill's claims against only what they cite: 15 of 68 went beyond their citations, and after rewording and observed footnotes, 7 of 62 still did. Those 7 were fixed in turn, and haven't been reviewed a third time.

## Adding a new skill

Each skill lives in its own folder under `skills/`, named with `lowercase-with-hyphens`. The folder must contain a `SKILL.md` whose YAML frontmatter `name` field matches the folder name.

```
skills/
└── my-skill/
    ├── SKILL.md          # required — frontmatter + instructions
    ├── references/       # optional — long-form docs
    └── scripts/          # optional — executable helpers
```

The frontmatter requires two fields:

```yaml
---
name: my-skill
description: What the skill does, and when the model should invoke it.
---
```

Write the `description` so an agent can decide whether to invoke the skill from that one line — describe both *what it does* and *when to use it*.

The top-level flake auto-discovers any subdirectory of `skills/` that contains a `SKILL.md`, so the aggregate package picks up new skills without further changes.

## License

[Apache-2.0](LICENSE)
