"""Static check for the mechanically checkable invariants (INSTRUCTIONS.md G1, G8, G9) and the isolation gate.

One checker shared by the Claude Code PreToolUse hook (tools/hooks/invariant_guard.py), the git pre-commit hook
(.githooks/pre-commit) and the invariant-auditor sub-agent. Line-based on purpose: a tripwire for honest mistakes,
not a Kotlin parser. Comment lines are ignored.

    python3 tools/check_invariants.py --all            # whole tree
    python3 tools/check_invariants.py --files a.kt b.kt
    python3 tools/check_invariants.py --staged         # git index (pre-commit); also runs the isolation gate

Exit 1 when any error is found. Warnings never fail.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
COMMON_MAIN = re.compile(r"(^|/)src/commonMain/")
GAME_CORE = re.compile(r"(^|/)game-core/src/")

# (rule id, invariant, severity, pattern, message)
COMMON_RULES: list[tuple[str, str, str, re.Pattern[str], str]] = [
    ("platform-import", "G9", "error", re.compile(r"^\s*import\s+(java|javax|android|androidx|platform|sun|kotlinx\.cinterop)\."),
     "platform import in commonMain"),
    ("string-format", "G9", "error", re.compile(r"String\.format\(|\"\s*\.format\("), "String.format is JVM-only"),
    ("system", "G9", "error", re.compile(r"(?<![\w.])System\."), "System.* is JVM-only"),
    ("dispatchers-io", "G9", "error", re.compile(r"Dispatchers\.IO\b"), "Dispatchers.IO is not available in commonMain"),
    ("unseeded-random", "G8", "error", re.compile(r"(?<![\w.])Random\s*\(\s*\)|Random\.(Default|next\w*)\b"),
     "unseeded randomness breaks determinism; use Random(seed)"),
    ("clock", "G8", "error", re.compile(r"Clock\.System|currentTimeMillis|nanoTime"), "wall-clock time breaks determinism"),
    ("hash-collection", "G8", "error", re.compile(r"\b(HashMap|HashSet|hashMapOf|hashSetOf)\b"),
     "hash-ordered collection; iteration order is not a cross-platform contract"),
    ("hashcode", "G8", "warn", re.compile(r"\.hashCode\(\)"), "hashCode() is not stable across platforms; use Fnv1a"),
]
CORE_RULES = [
    ("ai-import", "G1", "error", re.compile(r"^\s*import\s+org\.livingworld\.ai\b"), "game-core must not import AI modules"),
]
DEP_LINE = re.compile(r"^\+\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|\w*Implementation)\s*\(|^\+\s*id\(\"|^\+\s*kotlin\(\".*version")


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    rule: str
    invariant: str
    severity: str
    message: str
    text: str

    def key(self) -> tuple[str, str, str]:
        return (self.path, self.rule, self.text.strip())

    def __str__(self) -> str:
        return f"{self.path}:{self.line}: [{self.invariant} {self.severity}] {self.message}: {self.text.strip()[:120]}"


def _is_comment(line: str) -> bool:
    s = line.strip()
    return s.startswith("//") or s.startswith("*") or s.startswith("/*")


def check_text(path: str, text: str) -> list[Finding]:
    """Findings for one file's content. `path` is repo-relative (posix)."""
    if not path.endswith(".kt"):
        return []
    rules = []
    if COMMON_MAIN.search(path):
        rules += COMMON_RULES
    if GAME_CORE.search(path):
        rules += CORE_RULES
    out = []
    for n, line in enumerate(text.splitlines(), 1):
        if _is_comment(line):
            continue
        for rule, inv, sev, pat, msg in rules:
            if pat.search(line):
                out.append(Finding(path, n, rule, inv, sev, msg, line))
    return out


def introduced(path: str, old: str, new: str) -> list[Finding]:
    """Findings present in `new` but not in `old` (so editing a file with a pre-existing issue is not blocked)."""
    before = {f.key() for f in check_text(path, old)}
    return [f for f in check_text(path, new) if f.key() not in before]


def isolation_gate(diff: str) -> list[str]:
    """Isolation gate: added dependency/plugin lines in staged *.gradle.kts files."""
    hits, current = [], ""
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[6:]
        elif current.endswith(".gradle.kts") and DEP_LINE.search(line):
            hits.append(f"{current}: {line[1:].strip()}")
    return hits


def _rel(p: Path) -> str:
    try:
        return p.resolve().relative_to(REPO).as_posix()
    except ValueError:
        return p.as_posix()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--all", action="store_true")
    g.add_argument("--files", nargs="+")
    g.add_argument("--staged", action="store_true")
    a = ap.parse_args(argv)
    findings: list[Finding] = []
    deps: list[str] = []
    if a.staged:
        names = subprocess.run(["git", "diff", "--cached", "--name-only", "--diff-filter=ACM"], cwd=REPO,
                               capture_output=True, text=True, check=True).stdout.split()
        for name in names:
            blob = subprocess.run(["git", "show", f":{name}"], cwd=REPO, capture_output=True, text=True)
            old = subprocess.run(["git", "show", f"HEAD:{name}"], cwd=REPO, capture_output=True, text=True)
            findings += introduced(name, old.stdout if old.returncode == 0 else "", blob.stdout)
        deps = isolation_gate(subprocess.run(["git", "diff", "--cached", "-U0", "--", "*.gradle.kts"], cwd=REPO,
                                             capture_output=True, text=True).stdout)
    else:
        paths = sorted(REPO.glob("*/src/**/*.kt")) if a.all else [Path(f) for f in a.files]
        for p in paths:
            if "_archive" not in p.parts and p.is_file():
                findings += check_text(_rel(p), p.read_text(encoding="utf-8", errors="replace"))
    errors = [f for f in findings if f.severity == "error"]
    for f in findings:
        print(f)
    if deps:
        print("isolation gate: new dependency/plugin lines (set LW_ALLOW_DEPS=1 with a justification to commit):")
        for d in deps:
            print(f"  {d}")
    print(f"{len(errors)} error(s), {len(findings) - len(errors)} warning(s), {len(deps)} dependency change(s)")
    return 1 if errors or (deps and os.environ.get("LW_ALLOW_DEPS") != "1") else 0


if __name__ == "__main__":
    sys.exit(main())
