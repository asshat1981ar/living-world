#!/usr/bin/env python3
"""Claude Code PreToolUse hook (Bash): two guards on git commands.

1. Secrets: `git add -A|--all|.` and `git commit -a` stage every untracked file, so block when an untracked,
   non-ignored file looks like a secret (name like .env/*.pem/id_rsa, or a token/private key inside). Explicit
   `git add <path>` is never blocked. Fix: add the file to .gitignore, or stage paths explicitly.
2. Pre-push: before `git push` (or tools/publish_github.sh) run the stop gate, so a red check is found locally
   instead of in CI. It reuses stop_gate.decide(), so it is free when the Kotlin digest already went green.
   `LW_SKIP_PREPUSH=1 git push ...` skips it.

Blocks with exit 2 and the reason on stderr. Unparseable input, other tools and infrastructure failures fail open.
"""

from __future__ import annotations

import importlib.util
import json
import os
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import telemetry  # noqa: E402

REPO = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2])
SEPARATORS = {";", "&&", "||", "|", "&"}
SECRET_NAME = re.compile(
    r"(^|/)(\.env(\..*)?|id_(rsa|ed25519)|[^/]*\.(pem|key|p12|jks|keystore)|[^/]*credentials[^/]*|[^/]*secret[^/]*)$",
    re.IGNORECASE,
)
SECRET_CONTENT = re.compile(
    r"github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----"
)
BINARY_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".gguf", ".zip", ".pdf", ".jar", ".apk", ".aab", ".ogg", ".wav", ".mp3"}
MAX_SCAN_BYTES = 200_000


def segments(command: str) -> list[list[str]]:
    """Split a shell command into simple commands. Raises ValueError on unbalanced quotes."""
    lex = shlex.shlex(command, posix=True, punctuation_chars=True)
    lex.whitespace_split = True
    segs: list[list[str]] = [[]]
    for tok in lex:
        if tok in SEPARATORS:
            segs.append([])
        else:
            segs[-1].append(tok)
    return [s for s in segs if s]


def _safe_segments(command: str) -> list[list[str]]:
    try:
        return segments(command)
    except ValueError:
        return []


def git_sub(seg: list[str]) -> tuple[str, list[str]] | None:
    """('add', args) for `[VAR=x] git [-C p] [-c k=v] add ...`, or None when the segment is not git."""
    i = 0
    while i < len(seg) and re.match(r"^[A-Za-z_]\w*=", seg[i]):
        i += 1
    if i >= len(seg) or os.path.basename(seg[i]) != "git":
        return None
    i += 1
    while i < len(seg) and seg[i].startswith("-"):
        i += 2 if seg[i] in ("-C", "-c") else 1
    return (seg[i], seg[i + 1:]) if i < len(seg) else None


def is_broad_stage(seg: list[str]) -> bool:
    g = git_sub(seg)
    if g is None:
        return False
    sub, args = g
    if sub == "add":
        return any(a in ("-A", "--all", ".") for a in args)
    if sub == "commit":
        return "--all" in args or any(a.startswith("-") and not a.startswith("--") and "a" in a[1:] for a in args)
    return False


def is_push(seg: list[str]) -> bool:
    if any(re.match(r"^LW_SKIP_PREPUSH=(1|true)$", t) for t in seg):
        return False
    g = git_sub(seg)
    if g is not None:
        return g[0] == "push" and "--dry-run" not in g[1]
    return any(os.path.basename(t) == "publish_github.sh" for t in seg[:3])


def suspicious_untracked(root: Path) -> list[str]:
    out = subprocess.run(["git", "ls-files", "-o", "--exclude-standard", "-z"], cwd=root, capture_output=True)
    found = []
    for rel in filter(None, out.stdout.decode(errors="replace").split("\0")):
        if SECRET_NAME.search(rel) and Path(rel).suffix.lower() not in BINARY_EXT:
            found.append(f"{rel}  (name looks like a secret)")
            continue
        p = root / rel
        if p.suffix.lower() in BINARY_EXT or not p.is_file() or p.stat().st_size > MAX_SCAN_BYTES:
            continue
        try:
            if SECRET_CONTENT.search(p.read_text(encoding="utf-8", errors="ignore")):
                found.append(f"{rel}  (contains a token or private key)")
        except OSError:
            continue
    return found


def _load_stop_gate():
    spec = importlib.util.spec_from_file_location("stop_gate", Path(__file__).with_name("stop_gate.py"))
    mod = importlib.util.module_from_spec(spec)
    sys.modules["stop_gate"] = mod
    spec.loader.exec_module(mod)
    return mod


def evaluate(command: str, root: Path = REPO, run_gate=None) -> tuple[int, str]:
    """(exit_code, stderr_message). `run_gate` is injectable for tests; it returns stop_gate.decide()'s answer."""
    try:
        segs = segments(command)
    except ValueError:
        return 0, ""
    for seg in segs:
        if is_broad_stage(seg):
            bad = suspicious_untracked(root)
            if bad:
                return 2, ("Blocked: this git command stages every untracked file, and these look like secrets:\n  "
                           + "\n  ".join(bad)
                           + "\nAdd them to .gitignore, or stage explicit paths (git add <path>).")
    if any(is_push(seg) for seg in segs):
        gate = run_gate or (lambda: _load_stop_gate().decide({}, run_remote=os.environ.get("LW_STOP_GATE_REMOTE", "1") != "0"))
        answer = gate()
        if answer and answer.get("decision") == "block":
            return 2, "Blocked push (pre-push gate). " + answer.get("reason", "")
        if answer and answer.get("systemMessage"):
            return 0, answer["systemMessage"]
    return 0, ""


def main() -> int:
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0
    if data.get("tool_name") != "Bash":
        return 0
    command = (data.get("tool_input") or {}).get("command", "")
    started = time.monotonic()
    code, message = evaluate(command)
    relevant = any(is_broad_stage(g) or is_push(g) for g in _safe_segments(command))
    if relevant:
        telemetry.record("bash_guard", "block" if code else "warn" if message else "pass", started)
    elif "LW_SKIP_PREPUSH=" in command and "push" in command:
        telemetry.record("bash_guard", "skip")
    if message:
        print(message, file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
