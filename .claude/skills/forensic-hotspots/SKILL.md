---
name: forensic-hotspots
description: Generate and summarize the living-world forensic hotspot report (LOC, nesting, approximate McCabe/cognitive complexity) into forensics/forensic-report-<version>.json. Use when asked for hotspots, technical debt, complexity, or which file to refactor next.
argument-hint: "[version-label, e.g. v15]"
---

1. Run `python3 -I tools/forensic.py --version $ARGUMENTS` (use the next free label after the newest `forensics/forensic-report-*.json` if none is given). It writes the report in the same schema as `forensic-report-v6.json`: `hotspot = loc * (mccabe + nesting) / 10`.
2. Summarize with the registry prompt "forensic-summary" (`prompts/prompt-registry-v1.md`): rank by hotspot score, map each file to its module (core / ai / persistence / ui / android / desktop / python tooling), and flag G1–G10 risks. Python scripts (`simulate_*.py`, `scripts/`) are external tools; report them separately from the kernel.
3. Update `dashboard/dashboard-snapshot-<version>.md` with: headline metrics, the top 5 kernel hotspots, and the next RefactoringMirror candidates (single micro-ops for the `refactor-microop` agent).

The metrics are heuristics (regex plus brace depth), not a parser. Only compare reports produced by `tools/forensic.py`. The v6 report came from an external engine, so v6→v14 deltas are indicative only.
