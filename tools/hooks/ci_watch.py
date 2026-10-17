#!/usr/bin/env python3
"""Watch GitHub Actions for a pushed commit without blocking the session.

  post    PostToolUse (Bash) hook. After a `git push` / publish_github.sh, start a detached watcher for HEAD.
  watch   (internal) poll `gh run list --commit SHA` until every run completes, then write .git/lw-ci/status.json
          with pass/fail and, for failures, only the tail of the failing step's log (never the whole log).
  report  UserPromptSubmit / SessionStart hook. Print the finished result once (stdout becomes context).

Needs `gh` logged in. Every failure here is silent: this is a convenience, never a gate.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bash_guard import REPO, is_push, segments  # noqa: E402

STATE = REPO / ".git" / "lw-ci" / "status.json"
POLL_SECONDS = 20
TIMEOUT_SECONDS = 25 * 60
FAIL_TAIL_LINES = 40


def read_state(path: Path = STATE) -> dict | None:
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


def write_state(state: dict, path: Path = STATE) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, indent=2))


def verdict(runs: list[dict]) -> str | None:
    """'pending' while any run is unfinished, else 'pass'/'fail'. None when GitHub has not created a run yet."""
    if not runs:
        return None
    if any(r.get("status") != "completed" for r in runs):
        return "pending"
    return "pass" if all(r.get("conclusion") in ("success", "skipped", "neutral") for r in runs) else "fail"


def format_report(state: dict) -> str:
    sha = state.get("sha", "")[:7]
    if state.get("state") == "pass":
        return f"CI for {sha} passed ({', '.join(r['name'] for r in state.get('runs', []))})."
    if state.get("state") == "timeout":
        return f"CI for {sha} was still running after {TIMEOUT_SECONDS // 60} min; check the Actions tab."
    lines = [f"CI for {sha} FAILED:"]
    for r in state.get("runs", []):
        if r.get("conclusion") not in ("success", "skipped", "neutral"):
            lines.append(f"- {r.get('name')}: {r.get('conclusion')} {r.get('url', '')}")
    if state.get("failure_tail"):
        lines.append("Failing step log (tail):\n" + state["failure_tail"])
    return "\n".join(lines)


def _gh(*args: str, timeout: int = 60) -> str:
    p = subprocess.run(["gh", *args], cwd=REPO, capture_output=True, text=True, timeout=timeout)
    return p.stdout if p.returncode == 0 else ""


def watch(sha: str) -> None:
    write_state({"sha": sha, "state": "pending", "reported": False})
    deadline = time.time() + TIMEOUT_SECONDS
    runs: list[dict] = []
    while time.time() < deadline:
        try:
            runs = json.loads(_gh("run", "list", "--commit", sha, "--json", "databaseId,name,status,conclusion,url") or "[]")
        except (ValueError, subprocess.SubprocessError, OSError):
            runs = []
        v = verdict(runs)
        if v in ("pass", "fail"):
            tail = ""
            if v == "fail":
                for r in runs:
                    if r.get("conclusion") not in ("success", "skipped", "neutral"):
                        log = _gh("run", "view", str(r["databaseId"]), "--log-failed", timeout=120)
                        tail += "\n".join(log.strip().splitlines()[-FAIL_TAIL_LINES:]) + "\n"
            write_state({"sha": sha, "state": v, "runs": runs, "failure_tail": tail.strip(), "reported": False})
            return
        time.sleep(POLL_SECONDS)
    write_state({"sha": sha, "state": "timeout", "runs": runs, "reported": False})


def post() -> int:
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return 0
    if data.get("tool_name") != "Bash":
        return 0
    command = (data.get("tool_input") or {}).get("command", "")
    try:
        if not any(is_push(s) for s in segments(command)):
            return 0
        sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO, capture_output=True, text=True).stdout.strip()
    except (ValueError, OSError):
        return 0
    prior = read_state()
    if not sha or (prior and prior.get("sha") == sha and prior.get("state") == "pending"):
        return 0
    subprocess.Popen([sys.executable, "-I", str(Path(__file__).resolve()), "watch", sha], cwd=REPO,
                     start_new_session=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return 0


def report() -> int:
    sys.stdin.read()
    state = read_state()
    if not state or state.get("reported") or state.get("state") == "pending":
        return 0
    print(format_report(state))
    state["reported"] = True
    write_state(state)
    return 0


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "watch" and len(sys.argv) > 2:
        watch(sys.argv[2])
        return 0
    if mode == "post":
        return post()
    if mode == "report":
        return report()
    return 0


if __name__ == "__main__":
    sys.exit(main())
