# Living-World Technical Debt Dashboard Snapshot (v15)

**Source**: `tools/forensic.py` (heuristic: regex + brace depth), report `forensics/forensic-report-v15.json`
**Date**: 2026-10-18. v6 came from an external engine, so v6 to v15 deltas are indicative only; v14 to v15 is like for like.

## Headline metrics
- Files analysed: 59 (v14: 59). Total LOC: 9,901 (v14: 9,795).
- Highest overall: `simulate_village.py` (15,065), external Python tool, not kernel.
- Kernel (game-core) hotspots: WorldReducer.kt 3,026 · SimulationEngine.kt 1,448 · WorldState.kt 1,258 · GameEvent.kt 983 · ActionValidator.kt 837.
- Other modules: game-ui VisualAdapters.kt 1,976 (G9 surface: UI only, no kernel link), SpriteManifest.kt 868, game-persistence SaveManager.kt 573.

## Top 5 kernel hotspots
| # | File | LOC | nest | McCabe~ | score |
|---|---|---|---|---|---|
| 1 | world/WorldReducer.kt | 445 | 4 | 64 | 3026 |
| 2 | simulation/SimulationEngine.kt | 284 | 5 | 46 | 1448 |
| 3 | world/WorldState.kt | 370 | 5 | 29 | 1258 |
| 4 | events/GameEvent.kt | 298 | 3 | 30 | 983 |
| 5 | simulation/ActionValidator.kt | 182 | 5 | 41 | 837 |

## Done this pass
- WorldReducer: extracted `witnessOfDeath` from `applyNpcDeath` (extract-method). `applyNpcDeath` 77 to ~40 lines. Verified: `:game-core:jvmTest` green and 30-day fingerprint identical before and after (`dbc7ecf9302ff4be`).

## Next RefactoringMirror candidates (one micro-op each)
1. `WorldReducer.applyTheft` (63 lines): extract the witness-belief update, same shape as `witnessOfDeath`.
2. `WorldReducer.applyTrade` (67 lines): guard-clause the unknown-buyer / unknown-seller paths.
3. `ActionValidator` (McCabe~41 in 182 LOC): lookup-table for per-action checks.
4. `SimulationEngine` tick loop: extract the per-NPC step.

## Alerts
- No G1/G9 findings; invariant guard and gate green. Nesting max in kernel is 5.
- Verify every micro-op with the 30-day fingerprint (G8), not only unit tests.
