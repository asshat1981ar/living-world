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
import sys
import time
from pathlib import Path

REPO = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path(__file__).resolve().parents[2])
LOG = REPO / ".git" / "lw-telemetry" / "events.jsonl"
MIN_FIRES = 20          # below this a hook has too little history to judge
SLOW_SECONDS = 60.0     # mean cost that makes a never-blocking hook worth demoting


def record(hook: str, outcome: str, started: float | None = None, path: Path = LOG, **extra) -> None:
    try:
        row = {"t": int(time.time()), "hook": hook, "outcome": outcome, **extra}
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
    print(report(load()))
