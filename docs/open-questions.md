# Living World — Architecture Decision Records & Open Questions

## Invariants
- G1: Zero AI imports in game-core.
- G2: Knowledge != Truth (partitioned beliefs, no omniscience).
- G3: Dialogue facts must be grounded in NPC beliefs.
- G4: Death has permanent cascading consequences.
- G5: History persists (append-only ledger).
- G6: Explanations derived causally from events.
- G7: Deterministic fallback functional without LLM.
- G8: Strict bit-for-bit determinism.
- G9: Zero platform leaks in commonMain.
- G10: No premature optimization.
