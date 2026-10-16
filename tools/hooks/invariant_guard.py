#!/usr/bin/env python3
"""Claude Code PreToolUse hook (Edit|Write|MultiEdit): block edits that INTRODUCE a G1/G8/G9 violation.

Reconstructs the file as it would be after the edit, runs tools/check_invariants.py rules on old vs new content and
blocks (exit 2, reason on stderr) only on newly introduced errors, so fixing a file that already has an issue is allowed.
Unparseable input or files outside the repo are allowed (fail open: a broken guard must not stop all work).
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_invariants import introduced  # noqa: E402


def _after(tool: str, ti: dict, old: str) -> str | None:
    if tool == "Write":
        return ti.get("content", "")
    edits = ti.get("edits") if tool == "MultiEdit" else [ti]
    new = old
    for e in edits or []:
        a, b = e.get("old_string", ""), e.get("new_string", "")
        if a and a not in new:
            return None  # the edit itself will fail; let the tool report it
        new = new.replace(a, b) if e.get("replace_all") else new.replace(a, b, 1)
    return new


def main() -> int:
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        print("invariant_guard: unparseable hook input; allowing", file=sys.stderr)
        return 0
    tool, ti = data.get("tool_name", ""), data.get("tool_input") or {}
    path = ti.get("file_path", "")
    if tool not in ("Edit", "Write", "MultiEdit") or not path.endswith(".kt"):
        return 0
    root = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2]).resolve()
    try:
        rel = Path(path).resolve().relative_to(root).as_posix()
    except ValueError:
        return 0
    p = Path(path)
    old = p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""
    new = _after(tool, ti, old)
    if new is None:
        return 0
    errors = [f for f in introduced(rel, old, new) if f.severity == "error"]
    if not errors:
        return 0
    print("Blocked by living-world invariant guard (INSTRUCTIONS.md G1/G8/G9):", file=sys.stderr)
    for f in errors:
        print(f"  {f}", file=sys.stderr)
    print("Keep commonMain platform-free and deterministic; put platform code in androidApp/desktopApp or an expect/actual.",
          file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
