#!/usr/bin/env python3
"""Hook telemetry: which guards fire, which actually block, and what they cost.

Hooks call `record(hook, outcome, started)` as they finish; one JSON line goes to .git/lw-telemetry/events.jsonl
(local, untracked). `python3 tools/hooks/telemetry.py [report]` summarises per hook and flags the ones worth
changing. Recording never raises: telemetry must not break a hook.

Outcomes: "pass" (nothing to say), "warn" (message, no block), "block", "skip" (bypassed, e.g. LW_SKIP_PREPUSH).
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
from pathlib import Path

REPO = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2])
LOG = REPO / ".git" / "lw-telemetry" / "events.jsonl"
MIN_FIRES = 20          # below this a hook has too little history to judge
SLOW_SECONDS = 60.0     # mean cost that makes a never-blocking hook worth demoting


def _head_sha(repo: Path = REPO) -> str | None:
    """The short HEAD commit, read from .git without a subprocess (a hook must stay cheap); None if unreadable."""
    try:
        head = (repo / ".git" / "HEAD").read_text().strip()
        if head.startswith("ref:"):
            ref = repo / ".git" / head.split(None, 1)[1]
            head = ref.read_text().strip() if ref.is_file() else ""
        return head[:10] if re.fullmatch(r"[0-9a-f]{7,40}", head) else None
    except OSError:
        return None


def record(hook: str, outcome: str, started: float | None = None, path: Path = LOG, **extra) -> None:
    try:
        row = {"t": int(time.time()), "hook": hook, "outcome": outcome, **extra}
        sha = _head_sha()
        if sha:
            row["sha"] = sha
        if started is not None:
            row["s"] = round(time.monotonic() - started, 2)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a") as f:
            f.write(json.dumps(row, sort_keys=True) + "\n")
    except Exception:
        pass


def load(path: Path = LOG) -> list[dict]:
    rows = []
    try:
        for line in path.read_text().splitlines():
            try:
                rows.append(json.loads(line))
            except ValueError:
                continue
    except OSError:
        pass
    return rows


def summarize(rows: list[dict]) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for r in rows:
        h = out.setdefault(r.get("hook", "?"), {"fires": 0, "pass": 0, "warn": 0, "block": 0, "skip": 0, "secs": 0.0})
        h["fires"] += 1
        h[r.get("outcome", "pass")] = h.get(r.get("outcome", "pass"), 0) + 1
        h["secs"] += float(r.get("s", 0))
    return out


def advice(name: str, h: dict) -> str | None:
    if h["fires"] < MIN_FIRES:
        return None
    if h["skip"] * 4 >= h["fires"]:
        return f"{name}: bypassed in {h['skip']}/{h['fires']} runs; it is probably too slow or too noisy, tune it."
    mean = h["secs"] / h["fires"]
    if h["block"] == 0 and h["warn"] == 0 and mean >= SLOW_SECONDS:
        return f"{name}: never blocked or warned in {h['fires']} runs and costs {mean:.0f}s each; demote it (run it only before push)."
    if h["block"] == 0 and h["warn"] == 0:
        return f"{name}: never blocked or warned in {h['fires']} runs (cheap, keep unless it adds noise)."
    return None


SETTINGS = REPO / ".claude" / "settings.json"
HOOK_SCRIPT = re.compile(r"tools/hooks/([a-z_]+)\.py")
ADVISORY = {"telemetry", "session_preflight", "ci_watch"}  # wired, but they do not decide anything, so they do not log outcomes


def wired_hooks(settings: Path = SETTINGS) -> list[str]:
    """The guard scripts .claude/settings.json actually runs, in order of appearance (advisory ones left out)."""
    try:
        text = settings.read_text()
    except OSError:
        return []
    seen: list[str] = []
    for name in HOOK_SCRIPT.findall(text):
        if name not in seen and name not in ADVISORY:
            seen.append(name)
    return seen


def stale(rows: list[dict], expected: list[str], now: float | None = None, days: float = 7.0) -> list[str]:
    """Problems that silence hides: a wired guard with no recent row, and rows that carry no duration.

    A guard that never runs looks exactly like a guard that never has anything to say, so "never blocked" alone proves
    nothing. This lists the wired guards that logged nothing in the last `days` days.
    """
    now = time.time() if now is None else now
    problems = []
    for name in expected:
        mine = [r for r in rows if r.get("hook") == name]
        recent = [r for r in mine if now - float(r.get("t", 0)) <= days * 86400]
        if not mine:
            problems.append(f"{name}: wired in settings.json but has never logged a row (silent, or never fires)")
        elif not recent:
            problems.append(f"{name}: no row in the last {days:g} days (last {int((now - max(float(r.get('t', 0)) for r in mine)) / 86400)} days ago)")
        untimed = [r for r in mine if "s" not in r]
        if untimed:
            problems.append(f"{name}: {len(untimed)} of {len(mine)} rows have no duration")
    return problems


def report(rows: list[dict]) -> str:
    if not rows:
        return "no hook telemetry yet"
    stats = summarize(rows)
    lines = [f"{'hook':<18}{'fires':>6}{'block':>6}{'warn':>6}{'skip':>6}{'mean s':>8}"]
    for name, h in sorted(stats.items()):
        lines.append(f"{name:<18}{h['fires']:>6}{h['block']:>6}{h['warn']:>6}{h['skip']:>6}{h['secs'] / h['fires']:>8.1f}")
    notes = [a for n, h in sorted(stats.items()) if (a := advice(n, h))]
    return "\n".join(lines + ([""] + notes if notes else []))


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "stale":
        found = stale(load(), wired_hooks())
        print("\n".join(found) if found else "every wired guard has logged recently, and every row carries a duration")
    else:
        print(report(load()))
