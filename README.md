# xtdb-skills

[![built with garnix](https://img.shields.io/endpoint.svg?url=https%3A%2F%2Fgarnix.io%2Fapi%2Fbadges%2Fnhooey%2Fxtdb-skills)](https://garnix.io/repo/nhooey/xtdb-skills)

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
| xtdb-xtql-queries | Write, read and debug XTQL queries for XTDB 2.x, pinned to XTDB v2.1.0. | [skills/xtdb-xtql-queries](skills/xtdb-xtql-queries) |

## Citing XTDB sources

A skill can pin each claim to a span of the upstream docs or source at a Git tag, so a new XTDB release can be checked against only the parts the skill relies on:

- `citations.toml` (written by hand) names the repo and tag, the paths to watch, and one `[[cite]]` per footnote id with a path and an anchor: a Markdown `heading`, or the start of a top-level Clojure `form`.
- `citations.lock.json` (generated) records the commit the tag resolved to, the hash of each cited file, and the lines and hash of each span.

The dev shell's `skill-citations` command (`scripts/skill_citations.py`) does the work:

```sh
skill-citations lock   skills/<skill>                # resolve anchors, write the lock, regenerate footnotes
skill-citations verify skills/<skill>                # lock, footnotes and anchors agree at the pinned tag
skill-citations drift  skills/<skill> --to latest    # each citation unchanged / changed / moved / missing, with diffs
skill-citations bump   skills/<skill> --to <tag>     # move the pin after updating the passages drift named
```

`nix flake check` runs `verify` for every skill with a lock, as `checks.<system>.citations-<skill>`, fetching only the cited files at the locked commit.

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
