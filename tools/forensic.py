"""Forensic hotspot report for living-world, in the schema of the forensics/forensic-report-*.json reports.

Heuristic, stdlib-only metrics (same formula as the original v6 report, so reports stay comparable):
  loc        non-blank, non-comment lines
  nesting    max brace depth (Kotlin) / indentation depth (Python)
  mccabe     1 + decision points (if, when-branch arrows, for, while, catch, &&, ||, ?:, elif, except, and, or)
  cognitive  mccabe + nesting
  hotspot    loc * cognitive / 10

    python3 tools/forensic.py --version v14            # writes forensics/forensic-report-v14.json
    python3 tools/forensic.py --version v14 --stdout   # print instead
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
KT_DECISIONS = re.compile(r"\bif\b|\bfor\b|\bwhile\b|\bcatch\b|&&|\|\||\?:|->")
PY_DECISIONS = re.compile(r"\bif\b|\belif\b|\bfor\b|\bwhile\b|\bexcept\b|\band\b|\bor\b")
STRINGS = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')


def _source_files() -> list[Path]:
    out = subprocess.run(["git", "ls-files", "-co", "--exclude-standard"], cwd=REPO, capture_output=True, text=True, check=True)
    return [REPO / p for p in out.stdout.split() if p.endswith((".kt", ".py")) and not p.startswith("tools/")]


def measure(path: Path) -> dict:
    text = path.read_text(encoding="utf-8", errors="replace")
    is_py = path.suffix == ".py"
    loc = nesting = depth = decisions = 0
    in_block = False
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if not is_py:
            if in_block:
                in_block = "*/" not in line
                continue
            if line.startswith("/*"):
                in_block = "*/" not in line
                continue
        if line.startswith("//" if not is_py else "#"):
            continue
        loc += 1
        code = STRINGS.sub('""', line)
        if is_py:
            nesting = max(nesting, (len(raw) - len(raw.lstrip(" "))) // 4)
            decisions += len(PY_DECISIONS.findall(code))
        else:
            for ch in code:
                if ch == "{":
                    depth += 1
                    nesting = max(nesting, depth)
                elif ch == "}":
                    depth = max(0, depth - 1)
            hits = KT_DECISIONS.findall(code)
            decisions += sum(1 for h in hits if h != "->")
            # `->` is a branch only as a `when` arm, not inside a lambda header `{ x -> ...`
            if "->" in code and "{" not in code.split("->", 1)[0]:
                decisions += 1
    mccabe = 1 + decisions
    cognitive = mccabe + nesting
    return {
        "path": "./" + path.relative_to(REPO).as_posix(),
        "loc": loc,
        "nesting": nesting,
        "mccabe_approx": mccabe,
        "cognitive_approx": cognitive,
        "hotspot": round(loc * cognitive / 10, 1),
    }


def report(version: str) -> dict:
    files = sorted((measure(p) for p in _source_files()), key=lambda r: -r["hotspot"])
    return {
        "project": f"living-world-{version}",
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "file_count": len(files),
        "total_loc": sum(f["loc"] for f in files),
        "hotspots": files[:15],
        "all_files": files,
        "notes": [
            "Approximate metrics (Kotlin/Python heuristic, tools/forensic.py). hotspot = loc * (mccabe + nesting) / 10.",
            "Highest hotspot scores should be prioritized for review / micro-refactor under RefactoringMirror rules.",
            "Invariants G1-G10 (INSTRUCTIONS.md) remain mandatory.",
        ],
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True, help="label, e.g. v14")
    ap.add_argument("--stdout", action="store_true")
    a = ap.parse_args()
    rep = report(a.version)
    text = json.dumps(rep, indent=2) + "\n"
    if a.stdout:
        sys.stdout.write(text)
    else:
        out = REPO / "forensics" / f"forensic-report-{a.version}.json"
        out.write_text(text)
        print(f"wrote {out.relative_to(REPO)}: {rep['file_count']} files, {rep['total_loc']} LOC; top: "
              + ", ".join(f"{Path(h['path']).name}={h['hotspot']}" for h in rep["hotspots"][:5]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
