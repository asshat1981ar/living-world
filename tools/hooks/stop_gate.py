#!/usr/bin/env python3
"""Claude Code Stop hook: do not let a turn finish while living-world's checks fail.

Tiers (cheapest first; the first failing tier blocks):
  1. invariants  python3 tools/check_invariants.py --all       (G1/G8/G9 errors)
  2. tool tests  python3 -m unittest discover tools 'test_*.py'
  3. kotlin      ./gradlew check on awsbox via tools/lw_remote.py, only when a Kotlin/Gradle input changed since the
                 last green run (digest kept in .git/lw-stop-gate/last-green). LW_STOP_GATE_REMOTE=0 skips it.

A failing tier blocks: {"decision": "block", "reason": <what failed + output tail>}; Claude continues and must fix it.
Claude Code's own cap (8 consecutive continuations) bounds the loop.
An infrastructure failure (AWS session expired, SSM error, timeout) does NOT block, since it cannot be fixed from inside
the turn; it is reported as a systemMessage instead. Unparseable hook input -> allow (fail open).
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2])
STATE = REPO / ".git" / "lw-stop-gate" / "last-green"
KOTLIN_INPUTS = (".kt", ".kts", "gradle.properties", "gradle-wrapper.properties")
TAIL = 60


@dataclass
class Outcome:
    ok: bool
    output: str
    infra: bool = False  # True: could not run the check at all (not the code's fault)


def _run(argv: list[str], timeout: int) -> Outcome:
    try:
        p = subprocess.run(argv, cwd=REPO, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return Outcome(False, f"timed out after {timeout}s: {' '.join(argv)}", infra=True)
    except OSError as e:
        return Outcome(False, f"could not start {argv[0]}: {e}", infra=True)
    return Outcome(p.returncode == 0, (p.stdout + p.stderr).strip())


def check_invariants() -> Outcome:
    return _run([sys.executable, "-I", "tools/check_invariants.py", "--all"], 120)


def tool_tests() -> Outcome:
    return _run([sys.executable, "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"], 300)


def kotlin_digest() -> str:
    out = subprocess.run(["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=REPO, capture_output=True, check=True)
    h = hashlib.sha256()
    for rel in sorted(r for r in out.stdout.decode().split("\0") if r.endswith(KOTLIN_INPUTS)):
        p = REPO / rel
        if p.is_file():
            h.update(rel.encode() + b"\0" + hashlib.sha256(p.read_bytes()).digest())
    return h.hexdigest()


def gradle_check() -> Outcome:
    o = _run([sys.executable, "-I", "tools/lw_remote.py", "--push", "--gradle", "check", "--tail", "80"], 1700)
    if not o.ok and "GRADLE_EXIT" not in o.output:
        o.infra = True  # push/SSM/AWS failed before Gradle produced a verdict
    return o


def decide(data: dict, run_remote: bool, checks=None, state: Path = STATE) -> dict | None:
    """The hook's JSON answer, or None to allow the stop silently. `checks` is injectable for tests."""
    checks = checks or {"invariants": check_invariants, "tool tests": tool_tests, "kotlin": gradle_check}
    warnings: list[str] = []
    for name in ("invariants", "tool tests"):
        o = checks[name]()
        if not o.ok:
            if o.infra:
                warnings.append(f"stop gate: {name} could not run: {o.output[-300:]}")
                continue
            return _block(name, o.output)
    if run_remote:
        digest = checks.get("digest", kotlin_digest)()
        last = state.read_text().strip() if state.is_file() else ""
        if digest != last:
            o = checks["kotlin"]()
            if o.ok:
                state.parent.mkdir(parents=True, exist_ok=True)
                state.write_text(digest + "\n")
            elif o.infra:
                last_line = o.output.strip().splitlines()[-1][-300:] if o.output.strip() else "no output"
                warnings.append(f"stop gate: Kotlin check on awsbox could not run (not blocking): {last_line}")
            else:
                return _block("kotlin (./gradlew check on awsbox)", o.output)
    return {"systemMessage": "\n".join(warnings)} if warnings else None


def _block(name: str, output: str) -> dict:
    tail = "\n".join(output.strip().splitlines()[-TAIL:])
    return {"decision": "block",
            "reason": f"Stop gate: {name} failed. Fix this before finishing (or say plainly why it cannot be fixed now):\n{tail}"}


def main() -> int:
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0
    if data.get("hook_event_name", "Stop") != "Stop":
        return 0
    answer = decide(data, run_remote=os.environ.get("LW_STOP_GATE_REMOTE", "1") != "0")
    if answer:
        print(json.dumps(answer))
    return 0


if __name__ == "__main__":
    sys.exit(main())
