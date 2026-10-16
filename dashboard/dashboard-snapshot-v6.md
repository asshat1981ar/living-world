# Living-World Technical Debt Dashboard Snapshot (v6)

**Source**: forensic-code-engine on living-world-v6  
**Date**: 2026-10-13

## Headline metrics
- Source files analysed: 20
- Total logical LOC: ~2981
- Highest hotspot: `simulate_village.py` (score ~4020)
- Core hotspots: WorldReducer, SimulationEngine, GameEvent, NpcDecisionEngine, ActionValidator

## Priority backlog (for RefactoringMirror)
1. Review / extract from WorldReducer.kt (high decision density)
2. SimulationEngine.kt – ensure tick loop stays under cognitive thresholds
3. GameEvent.kt – consider sealed hierarchy already present; watch growth
4. NpcDecisionEngine / ActionValidator – candidates for guard-clause / extract-method
5. Keep simulate_village.py as external tool; do not import into game-core (G1)

## Alerts
- No nesting > 7 observed in the heuristic pass
- No file > 700 LOC except the external Python script
- G1–G10 invariants remain the primary safety surface

## Next actions
- Any AI micro-refactor must go through governed-prompt-registry → RefactoringMirror → ai-pr-safety-gates
- Re-run forensic after each accepted change and upload new report to living-world/forensics/
