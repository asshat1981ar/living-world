#!/usr/bin/env python3
"""Claude Code Stop hook: do not let a turn finish while living-world's checks fail.

Two modes. `decide` is the FULL gate (used before `git push` by bash_guard); `decide_quick` is what the Stop hook runs,
so a turn that touched one module does not pay for the whole build:
  quick  invariants, tool tests, then only the Gradle tasks the changed modules need (game-core -> every module's
         jvmTest; one other module -> its jvmTest; build files/androidApp/desktopApp -> full check), then a G8
         determinism tier (same 30-day simulation run twice on awsbox, fingerprints must match) when game-core or
         desktopApp changed since it last passed. Per-module digests live in .git/lw-stop-gate/quick.json.
Full gate tiers (cheapest first; the first failing tier blocks):
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
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2])
STATE = REPO / ".git" / "lw-stop-gate" / "last-green"
QUICK_STATE = REPO / ".git" / "lw-stop-gate" / "quick.json"
KOTLIN_INPUTS = (".kt", ".kts", "gradle.properties", "gradle-wrapper.properties")
MODULES = ("game-core", "game-persistence", "game-ai", "game-ui", "androidApp", "desktopApp")
JVM_TEST_MODULES = ("game-core", "game-persistence", "game-ai", "game-ui")
DETERMINISM_MODULES = ("game-core", "desktopApp")
DETERMINISM_DAYS = 30
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


def gradle_check(tasks: str = "check") -> Outcome:
    o = _run([sys.executable, "-I", "tools/lw_remote.py", "--push", "--gradle", tasks, "--tail", "80"], 1700)
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


def module_of(rel: str) -> str:
    top = rel.split("/", 1)[0]
    return top if top in MODULES else "build"


def module_digests() -> dict[str, str]:
    """sha256 of every Kotlin/Gradle input, grouped by module ('build' = root and gradle/ files)."""
    out = subprocess.run(["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=REPO, capture_output=True, check=True)
    groups: dict[str, "hashlib._Hash"] = {}
    for rel in sorted(r for r in out.stdout.decode().split("\0") if r.endswith(KOTLIN_INPUTS)):
        p = REPO / rel
        if p.is_file():
            h = groups.setdefault(module_of(rel), hashlib.sha256())
            h.update(rel.encode() + b"\0" + hashlib.sha256(p.read_bytes()).digest())
    return {m: h.hexdigest() for m, h in groups.items()}


def quick_tasks(changed: set[str]) -> str | None:
    """The smallest Gradle task list that covers `changed` modules; None when nothing changed."""
    if not changed:
        return None
    if changed & {"build", "androidApp", "desktopApp"}:
        return "check"
    if "game-core" in changed:  # every module compiles against the kernel
        return " ".join(f":{m}:jvmTest" for m in JVM_TEST_MODULES)
    return " ".join(f":{m}:jvmTest" for m in JVM_TEST_MODULES if m in changed)


def determinism_digest(digests: dict[str, str]) -> str:
    return hashlib.sha256("|".join(digests.get(m, "") for m in DETERMINISM_MODULES).encode()).hexdigest()


def determinism_check(runs: int | None = None, days: int = DETERMINISM_DAYS) -> Outcome:
    """G8: run the same simulation `runs` times on awsbox; every FNV-1a fingerprint must be identical."""
    runs = runs or int(os.environ.get("LW_DETERMINISM_RUNS", "2"))
    prints: list[str] = []
    for i in range(runs):
        argv = [sys.executable, "-I", "tools/lw_remote.py"] + (["--push"] if i == 0 else []) + [
            "--gradle", f":desktopApp:run --args='{days} 0'", "--tail", "200"]
        o = _run(argv, 900)
        fp = re.search(r"Fingerprint\s*:\s*(\S+)", o.output)
        if not fp:
            return Outcome(False, o.output, infra="GRADLE_EXIT" not in o.output)
        prints.append(fp.group(1))
    if len(set(prints)) != 1:
        return Outcome(False, f"G8 violation: {days}-day simulation gave different fingerprints across {runs} runs: {prints}")
    return Outcome(True, f"{runs} runs, fingerprint {prints[0]}")


def _load_quick(path: Path) -> dict:
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def decide_quick(data: dict, run_remote: bool, checks=None, state: Path = QUICK_STATE) -> dict | None:
    """Stop-hook answer: cheap local tiers, then module-scoped Gradle tasks, then the determinism tier."""
    checks = checks or {"invariants": check_invariants, "tool tests": tool_tests, "kotlin": gradle_check,
                        "digests": module_digests, "determinism": determinism_check}
    warnings: list[str] = []
    for name in ("invariants", "tool tests"):
        o = checks[name]()
        if not o.ok:
            if o.infra:
                warnings.append(f"stop gate: {name} could not run: {o.output[-300:]}")
                continue
            return _block(name, o.output)
    if not run_remote:
        return {"systemMessage": "\n".join(warnings)} if warnings else None

    cur = checks["digests"]()
    saved = _load_quick(state)
    last = saved.get("modules", {})
    remote_ok = True
    tasks = quick_tasks({m for m, d in cur.items() if last.get(m) != d})
    if tasks:
        o = checks["kotlin"](tasks)
        if o.ok:
            saved["modules"] = cur
            _save_quick(state, saved)
        elif o.infra:
            remote_ok = False
            tail = o.output.strip().splitlines()[-1][-300:] if o.output.strip() else "no output"
            warnings.append(f"stop gate: Kotlin tests ({tasks}) on awsbox could not run (not blocking): {tail}")
        else:
            return _block(f"kotlin ({tasks} on awsbox)", o.output)
    det = determinism_digest(cur)
    if remote_ok and saved.get("determinism") != det:
        o = checks["determinism"]()
        if o.ok:
            saved["determinism"] = det
            _save_quick(state, saved)
        elif o.infra:
            warnings.append(f"stop gate: determinism check could not run (not blocking): {o.output.strip()[-200:]}")
        else:
            return _block("determinism (G8)", o.output)
    return {"systemMessage": "\n".join(warnings)} if warnings else None


def _save_quick(state: Path, saved: dict) -> None:
    state.parent.mkdir(parents=True, exist_ok=True)
    state.write_text(json.dumps(saved, indent=2))


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
    answer = decide_quick(data, run_remote=os.environ.get("LW_STOP_GATE_REMOTE", "1") != "0")
    if answer:
        print(json.dumps(answer))
    return 0


if __name__ == "__main__":
    sys.exit(main())
