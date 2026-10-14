#!/usr/bin/env python3
"""Pin a skill's claims to spans of an upstream Git repo, and report drift.

A skill directory holds `citations.toml` (authored: the upstream repo and
tag, watched paths, and one [[cite]] per footnote id with a path and an
anchor) and `citations.lock.json` (generated: the commit the tag resolved
to, each cited file's sha256, and each span's lines and sha256).

    lock   DIR [--source TREE]   resolve every anchor at the pinned tag,
                                 write the lock, regenerate footnote lines
    verify DIR [--source TREE]   fail unless the lock, footnotes and
                                 anchors all agree at the pinned tag
    drift  DIR --to TAG|latest   report each citation at a newer tag, the
                                 passages citing it, and uncited changes
    bump   DIR --to TAG          move the pin to TAG and re-lock

Upstream files are read from a blobless bare clone cached under
$XDG_CACHE_HOME/skill-citations, or, with --source, from a checked-out
tree at the pinned commit (how the Nix check runs, without network).
Standard library only.
"""

import argparse
import difflib
import hashlib
import json
import os
import re
import subprocess
import sys
import tomllib
from pathlib import Path

LOCK_NAME = "citations.lock.json"
TOML_NAME = "citations.toml"
FOOTNOTE_REF = re.compile(r"\[\^([A-Za-z0-9_-]+)\](?!:)")
FOOTNOTE_DEF = re.compile(r"^\[\^([A-Za-z0-9_-]+)\]: ")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
SEMVER_TAG = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]+)(\d*)(?:\.(\d+))?)?$")


class CitationError(Exception):
    pass


# --- upstream access -------------------------------------------------------


class GitUpstream:
    """Reads files at any ref of a blobless bare clone, fetching on demand."""

    def __init__(self, repo):
        cache = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
        self.dir = cache / "skill-citations" / "github.com" / f"{repo}.git"
        self.url = f"https://github.com/{repo}.git"
        if not self.dir.exists():
            self.dir.parent.mkdir(parents=True, exist_ok=True)
            self._run("clone", "--quiet", "--bare", "--filter=blob:none", self.url, str(self.dir), git_dir=False)
        self._fetched = False

    def _run(self, *args, git_dir=True, check=True):
        cmd = ["git"] + (["--git-dir", str(self.dir)] if git_dir else []) + list(args)
        r = subprocess.run(cmd, capture_output=True, text=True)
        if check and r.returncode != 0:
            raise CitationError(f"{' '.join(cmd)}: {r.stderr.strip()}")
        return r

    def fetch(self):
        if not self._fetched:
            self._run("fetch", "--quiet", "--tags", "--force", self.url, "+refs/heads/*:refs/heads/*")
            self._fetched = True

    def commit(self, ref):
        r = self._run("rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}", check=False)
        if r.returncode != 0:
            self.fetch()
            r = self._run("rev-parse", "--verify", f"{ref}^{{commit}}")
        return r.stdout.strip()

    def read(self, ref, path):
        r = self._run("show", f"{ref}:{path}", check=False)
        return r.stdout if r.returncode == 0 else None

    def tags(self):
        self.fetch()
        return self._run("tag", "--list").stdout.split()

    def renamed_to(self, old, new, path):
        """The path a file was renamed to between two refs, if git detects one."""
        out = self._run("diff", "-M", "--name-status", "--diff-filter=R", old, new).stdout
        for line in out.splitlines():
            parts = line.split("\t")
            if len(parts) == 3 and parts[1] == path:
                return parts[2]
        return None

    def grep(self, ref, needle, paths):
        r = self._run("grep", "-l", "-F", "-e", needle, ref, "--", *paths, check=False)
        return [line.split(":", 1)[1] for line in r.stdout.splitlines()]

    def changed(self, old, new, paths):
        return self._run("diff", "--name-only", old, new, "--", *paths).stdout.split()


class TreeUpstream:
    """Reads files from a checked-out tree pinned to one commit."""

    def __init__(self, root, commit):
        self.root, self.pinned = Path(root), commit

    def commit(self, ref):
        return self.pinned

    def read(self, ref, path):
        p = self.root / path
        return p.read_text() if p.is_file() else None


# --- anchors ---------------------------------------------------------------


def resolve(text, cite):
    """(first, last) 1-based lines of the cited span, or raise."""
    lines = text.splitlines()
    if "heading" in cite:
        hits = [(i, len(m.group(1))) for i, l in enumerate(lines) if (m := HEADING.match(l)) and m.group(2) == cite["heading"]]
        kind = f"heading {cite['heading']!r}"
    elif "form" in cite:
        hits = [(i, 0) for i, l in enumerate(lines) if l.startswith(cite["form"])]
        kind = f"form {cite['form']!r}"
    else:
        raise CitationError(f"[{cite['id']}] needs a `heading` or `form` anchor")
    if not hits:
        raise CitationError(f"[{cite['id']}] {kind} not found in {cite['path']}")
    if len(hits) > 1:
        raise CitationError(f"[{cite['id']}] {kind} matches {len(hits)} places in {cite['path']}")
    start, level = hits[0]
    end = len(lines)
    in_fence = False
    for i in range(start + 1, len(lines)):
        l = lines[i]
        if "heading" in cite:
            if l.lstrip().startswith(("```", "~~~")):
                in_fence = not in_fence
            m = None if in_fence else HEADING.match(l)
            if m and (cite.get("intro") or len(m.group(1)) <= level):
                end = i
                break
        elif l.startswith("("):
            end = i
            break
    while end > start + 1 and not lines[end - 1].strip():
        end -= 1
    return start + 1, end


def span_hash(text, first, last):
    span = text.splitlines()[first - 1 : last]
    normalised = "\n".join(re.sub(r"\s+", " ", l).strip() for l in span if l.strip())
    return hashlib.sha256(normalised.encode()).hexdigest()


def file_hash(text):
    return hashlib.sha256(text.encode()).hexdigest()


# --- skill files -----------------------------------------------------------


def load(skill):
    cfg = tomllib.loads((skill / TOML_NAME).read_text())
    ids = [c["id"] for c in cfg.get("cite", [])]
    dupes = {i for i in ids if ids.count(i) > 1}
    if dupes:
        raise CitationError(f"duplicate citation ids: {', '.join(sorted(dupes))}")
    return cfg


def markdown_files(skill):
    return sorted(p for p in skill.rglob("*.md") if p.is_file())


def references(skill):
    """{id: [(file, line, section heading)]} for every footnote reference."""
    refs = {}
    for md in markdown_files(skill):
        section = "(top)"
        in_fence = False
        for n, line in enumerate(md.read_text().splitlines(), 1):
            if line.lstrip().startswith(("```", "~~~")):
                in_fence = not in_fence
            if in_fence or FOOTNOTE_DEF.match(line):
                continue
            if m := HEADING.match(line):
                section = m.group(2)
            for fid in FOOTNOTE_REF.findall(line):
                refs.setdefault(fid, []).append((md.relative_to(skill), n, section))
    return refs


def permalink(repo, tag, entry):
    path, (first, last) = entry["path"], entry["lines"]
    plain = "?plain=1" if path.endswith((".md", ".mdx")) else ""
    return f"https://github.com/{repo}/blob/{tag}/{path}{plain}#L{first}-L{last}"


def footnote(cfg, cite, entry):
    repo, tag = cfg["source"]["repo"], cfg["source"]["tag"]
    name = Path(cite["path"]).name
    where = f"§ {cite['heading']}" if "heading" in cite else f"`{cite['form']}`"
    return f"[^{cite['id']}]: [{name} {where}, {tag}]({permalink(repo, tag, entry)})"


def expected_footnotes(cfg, lock):
    return {c["id"]: footnote(cfg, c, lock["citations"][c["id"]]) for c in cfg["cite"]}


# --- commands --------------------------------------------------------------


def build_lock(cfg, up):
    tag = cfg["source"]["tag"]
    commit = up.commit(tag)
    files, cites = {}, {}
    for c in cfg["cite"]:
        text = up.read(commit, c["path"])
        if text is None:
            raise CitationError(f"[{c['id']}] {c['path']} does not exist at {tag}")
        files[c["path"]] = file_hash(text)
        first, last = resolve(text, c)
        cites[c["id"]] = {"path": c["path"], "lines": [first, last], "sha256": span_hash(text, first, last)}
    return {"repo": cfg["source"]["repo"], "tag": tag, "commit": commit, "files": files, "citations": cites}


def upstream_for(cfg, args, lock=None):
    if args.source:
        if lock is None:
            raise CitationError("--source needs an existing lock to name its commit")
        return TreeUpstream(args.source, lock["commit"])
    return GitUpstream(cfg["source"]["repo"])


def cmd_lock(skill, args):
    cfg = load(skill)
    old = read_lock(skill, required=False)
    lock = build_lock(cfg, upstream_for(cfg, args, old))
    (skill / LOCK_NAME).write_text(json.dumps(lock, indent=2) + "\n")
    want = expected_footnotes(cfg, lock)
    defined = set()
    refs = references(skill)
    for md in markdown_files(skill):
        lines = md.read_text().splitlines(keepends=True)
        out = []
        for line in lines:
            m = FOOTNOTE_DEF.match(line)
            if m and m.group(1) in want:
                defined.add(m.group(1))
                line = want[m.group(1)] + "\n"
            out.append(line)
        # A cited id with no definition yet gets one at the end of the file
        # that first cites it.
        rel = md.relative_to(skill)
        for fid in want:
            if fid not in defined and refs.get(fid, [(None,)])[0][0] == rel:
                if out and not out[-1].endswith("\n"):
                    out[-1] += "\n"
                out.append(want[fid] + "\n")
                defined.add(fid)
        if out != lines:
            md.write_text("".join(out))
    print(f"locked {len(lock['citations'])} citations at {lock['tag']} ({lock['commit'][:12]})")
    return verify(skill, cfg, lock, None)


def read_lock(skill, required=True):
    p = skill / LOCK_NAME
    if not p.exists():
        if required:
            raise CitationError(f"{p} is missing; run `lock` first")
        return None
    return json.loads(p.read_text())


def verify(skill, cfg, lock, up):
    """Problems as a list of strings; `up` None skips re-reading upstream."""
    problems = []
    ids = {c["id"] for c in cfg["cite"]}
    if lock["tag"] != cfg["source"]["tag"] or lock["repo"] != cfg["source"]["repo"]:
        problems.append(f"lock pins {lock['repo']}@{lock['tag']}, citations.toml says {cfg['source']['repo']}@{cfg['source']['tag']}")
    if set(lock["citations"]) != ids:
        problems.append(f"lock and citations.toml disagree on ids: {sorted(set(lock['citations']) ^ ids)}")
    refs = references(skill)
    for fid in sorted(set(refs) - ids):
        f, n, _ = refs[fid][0]
        problems.append(f"{f}:{n} cites [^{fid}], which citations.toml does not define")
    for fid in sorted(ids - set(refs)):
        problems.append(f"[^{fid}] is defined but no passage cites it")
    defs = {}
    for md in markdown_files(skill):
        for line in md.read_text().splitlines():
            if m := FOOTNOTE_DEF.match(line):
                defs[m.group(1)] = line
    if not problems:
        for fid, line in expected_footnotes(cfg, lock).items():
            if defs.get(fid) != line:
                problems.append(f"footnote [^{fid}] is stale or missing; run `lock`")
    if up is not None and not problems:
        for c in cfg["cite"]:
            entry = lock["citations"][c["id"]]
            text = up.read(lock["commit"], c["path"])
            if text is None:
                problems.append(f"[{c['id']}] {c['path']} missing at {lock['commit'][:12]}")
                continue
            if file_hash(text) != lock["files"][c["path"]]:
                problems.append(f"[{c['id']}] {c['path']} does not match the locked file hash")
            try:
                first, last = resolve(text, c)
            except CitationError as e:
                problems.append(str(e))
                continue
            if [first, last] != entry["lines"] or span_hash(text, first, last) != entry["sha256"]:
                problems.append(f"[{c['id']}] resolves to lines {first}-{last}, not the locked span; run `lock`")
    return problems


def cmd_verify(skill, args):
    cfg = load(skill)
    lock = read_lock(skill)
    up = upstream_for(cfg, args, lock)
    if up.commit(lock["tag"]) != lock["commit"]:
        return [f"tag {lock['tag']} now resolves to {up.commit(lock['tag'])}, not the locked {lock['commit']}"]
    return verify(skill, cfg, lock, up)


def tag_key(tag):
    m = SEMVER_TAG.match(tag)
    if not m:
        return None
    major, minor, patch, pre, pre_n, pre_sub = m.groups()
    # A release sorts after its own pre-releases; pre-release labels sort by name.
    pre_key = (1, "", 0, 0) if pre is None else (0, pre, int(pre_n or 0), int(pre_sub or 0))
    return (int(major), int(minor), int(patch)) + pre_key


def latest_tag(up, current):
    keyed = [(k, t) for t in up.tags() if (k := tag_key(t))]
    newer = [kt for kt in keyed if kt[0] > tag_key(current)]
    return max(newer)[1] if newer else current


def cmd_drift(skill, args):
    cfg = load(skill)
    lock = read_lock(skill)
    up = GitUpstream(cfg["source"]["repo"])
    old = lock["commit"]
    to = latest_tag(up, lock["tag"]) if args.to == "latest" else args.to
    new = up.commit(to)
    refs = references(skill)
    report = {"from": lock["tag"], "to": to, "citations": [], "uncited_changes": []}
    for c in cfg["cite"]:
        entry = lock["citations"][c["id"]]
        item = {"id": c["id"], "path": c["path"], "cited_by": [f"{f}:{n} ({s})" for f, n, s in refs.get(c["id"], [])]}
        path = c["path"]
        text = up.read(new, path)
        if text is None:
            moved = up.renamed_to(old, new, path)
            if moved is None:
                needle = c.get("form") or c.get("heading")
                hits = up.grep(new, needle, cfg["source"].get("watch", [])) if needle else []
                moved = hits[0] if len(hits) == 1 else None
            if moved is None:
                item["status"] = "missing"
                report["citations"].append(item)
                continue
            item["moved_to"] = path = moved
            text = up.read(new, path)
        try:
            first, last = resolve(text, {**c, "path": path})
        except CitationError as e:
            item["status"], item["error"] = "unresolved", str(e)
            report["citations"].append(item)
            continue
        item["lines"] = [first, last]
        if span_hash(text, first, last) == entry["sha256"]:
            item["status"] = "moved" if "moved_to" in item else "unchanged"
        else:
            item["status"] = "changed"
            before = up.read(old, c["path"]).splitlines()[entry["lines"][0] - 1 : entry["lines"][1]]
            after = text.splitlines()[first - 1 : last]
            item["diff"] = "\n".join(
                difflib.unified_diff(before, after, f"{lock['tag']}:{c['path']}", f"{to}:{path}", lineterm="", n=1)
            )
        report["citations"].append(item)
    cited = {c["path"] for c in cfg["cite"]} | {i["moved_to"] for i in report["citations"] if "moved_to" in i}
    watch = cfg["source"].get("watch", [])
    if watch:
        report["uncited_changes"] = [p for p in up.changed(old, new, watch) if p not in cited]
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print_drift(report)
    return [] if all(i["status"] == "unchanged" for i in report["citations"]) else ["drift"]


def print_drift(report):
    print(f"citations: {report['from']} -> {report['to']}\n")
    order = {"missing": 0, "unresolved": 1, "changed": 2, "moved": 3, "unchanged": 4}
    for i in sorted(report["citations"], key=lambda i: (order[i["status"]], i["id"])):
        where = f" -> {i['moved_to']}" if "moved_to" in i else ""
        print(f"{i['status']:>10}  [^{i['id']}]  {i['path']}{where}")
        if i["status"] != "unchanged":
            for c in i["cited_by"]:
                print(f"{'':>12}cited by {c}")
            if "error" in i:
                print(f"{'':>12}{i['error']}")
            if "diff" in i:
                print("\n".join(f"{'':>12}{l}" for l in i["diff"].splitlines()))
    if report["uncited_changes"]:
        print("\nchanged under watched paths, cited nowhere:")
        for p in report["uncited_changes"]:
            print(f"  {p}")


def cmd_bump(skill, args):
    p = skill / TOML_NAME
    text = p.read_text()
    new, n = re.subn(r'(?m)^tag = "[^"]*"$', f'tag = "{args.to}"', text, count=1)
    if n != 1:
        raise CitationError(f"no `tag = \"…\"` line in {p}")
    p.write_text(new)
    args.source = None
    return cmd_lock(skill, args)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("lock", "verify", "drift", "bump"):
        sp = sub.add_parser(name)
        sp.add_argument("skill", type=Path)
        if name in ("lock", "verify"):
            sp.add_argument("--source", type=Path, help="checked-out tree at the locked commit")
        if name in ("drift", "bump"):
            sp.add_argument("--to", required=True, help="a tag, or `latest` (drift only)")
        if name == "drift":
            sp.add_argument("--json", action="store_true")
    args = ap.parse_args()
    cmd = {"lock": cmd_lock, "verify": cmd_verify, "drift": cmd_drift, "bump": cmd_bump}[args.cmd]
    try:
        problems = cmd(args.skill, args)
    except CitationError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    for p in problems:
        if p != "drift":
            print(f"problem: {p}", file=sys.stderr)
    if args.cmd == "verify" and not problems:
        print("citations verified")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
