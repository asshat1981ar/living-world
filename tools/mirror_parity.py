#!/usr/bin/env python3
"""Compare the Python kernel mirror (lw_mirror) with the Kotlin kernel's canonical dumps in tests/golden.

    python3 tools/mirror_parity.py                      # every golden
    python3 tools/mirror_parity.py --scenario turmoil   # one scenario
    python3 tools/mirror_parity.py --context 4          # more lines around the first divergence

A golden is tests/golden/<scenario>-<days>d.txt.gz, written by `:desktopApp:run --args='<days> 0 [--scenario=turmoil]
--dump=<path>'`. The mirror must expose lw_mirror.runner.run(scenario: str, days: int) -> list[str] of the same lines.
Exit code 0 only when every compared golden matches line for line.
"""

from __future__ import annotations

import argparse
import gzip
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "tests" / "golden"


def goldens(scenario: str | None):
    for path in sorted(GOLDEN.glob("*-*d.txt.gz")):
        m = re.fullmatch(r"(\w+)-(\d+)d\.txt\.gz", path.name)
        if m and (scenario is None or m.group(1) == scenario):
            yield m.group(1), int(m.group(2)), path


def state_goldens(scenario: str | None):
    """The state dumps (CanonicalStateDump): tests/golden/<scenario>-<days>d.state.txt.gz."""
    for path in sorted(GOLDEN.glob("*-*d.state.txt.gz")):
        m = re.fullmatch(r"(\w+)-(\d+)d\.state\.txt\.gz", path.name)
        if m and (scenario is None or m.group(1) == scenario):
            yield m.group(1), int(m.group(2)), path


def compare(expected: list[str], actual: list[str], context: int) -> str | None:
    """None when identical, else a report of the first divergence."""
    for i in range(max(len(expected), len(actual))):
        e = expected[i] if i < len(expected) else "<missing>"
        a = actual[i] if i < len(actual) else "<missing>"
        if e != a:
            lo = max(0, i - context)
            lines = [f"first difference at line {i + 1} (of {len(expected)} expected, {len(actual)} produced)"]
            for j in range(lo, i):
                lines.append(f"   {j + 1}: {expected[j]}")
            lines.append(f" - {i + 1}: {e}")
            lines.append(f" + {i + 1}: {a}")
            return "\n".join(lines)
    return None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--scenario", choices=["default", "turmoil", "feud", "famine", "schism"])
    ap.add_argument("--context", type=int, default=2)
    args = ap.parse_args(argv)

    sys.path.insert(0, str(ROOT))
    try:
        from lw_mirror import runner
    except ImportError as e:
        print(f"lw_mirror.runner is not importable yet: {e}")
        return 2

    failures = 0
    # Every scenario needs both goldens: a deleted or never-committed state golden must not read as a pass.
    events = {(s, d) for s, d, _ in goldens(args.scenario)}
    states = {(s, d) for s, d, _ in state_goldens(args.scenario)}
    if not events or events != states:
        failures += 1
        print(f"FAIL golden set: events {sorted(events)} vs state {sorted(states)} (each scenario needs both, none may be missing)")
    for scenario, days, path in goldens(args.scenario):
        expected = gzip.open(path, "rt").read().splitlines()
        actual = runner.run(scenario, days)
        report = compare(expected, actual, args.context)
        if report is None:
            print(f"PASS {scenario} {days}d: {len(expected)} lines identical")
        else:
            failures += 1
            print(f"FAIL {scenario} {days}d: {report}")
    for scenario, days, path in state_goldens(args.scenario):
        expected = gzip.open(path, "rt").read().splitlines()
        actual = runner.run_state(scenario, days)
        report = compare(expected, actual, args.context)
        if report is None:
            print(f"PASS {scenario} {days}d state: {len(expected)} lines identical")
        else:
            failures += 1
            print(f"FAIL {scenario} {days}d state: {report}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
