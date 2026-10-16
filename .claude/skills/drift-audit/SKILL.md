---
name: drift-audit
description: Drift audit for living-world after a refactor or prompt change. Compares the two newest forensic reports against the prompt registry's 5% rule and checks test and invariant regressions. Use after accepting a micro-refactor or editing prompts/prompt-registry-v1.md.
---

Rule (`prompts/prompt-registry-v1.md`, "Drift audit"): reject a change if total LOC or decision density regresses by more than 5% without justification.

1. If the newest report predates the change, regenerate it with the `forensic-hotspots` skill.
2. Compare the newest two `forensics/forensic-report-*.json` that were both produced by `tools/forensic.py` (note says "tools/forensic.py"):
   - total LOC delta %;
   - decision density = sum(mccabe_approx) / total_loc, delta %;
   - per-file hotspot deltas over +5%.
3. Invariants: `python3 -I tools/check_invariants.py --all` must show 0 errors.
4. Tests: delegate to the `test-verifier` agent. The passing-test count must not drop, and `determinism_benchmark` must stay deterministic.
5. Write the verdict (ACCEPT / REJECT, with the numbers) into `safety-gates/safety-gates-baseline.json` under `gates` and `reasons`, keeping its existing keys (`astChurn`, `mirrorMatch`, `hygiene`, `isolation`).
