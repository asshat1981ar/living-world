# Handoff — 2026-10-17 (Phase 9 Complete; build verified on awsbox)

## Update 2026-10-17
  - `./gradlew check` passes on awsbox (BUILD SUCCESSFUL, 334 tasks) after commit `f0c9eb6` (wrapper, plugin versions, `androidTarget`, CI).
  - Added `tools/publish_github.sh` (create repo + push via `GITHUB_TOKEN`); `.gitignore` and `.github/workflows/ci.yml` already existed.
  - Stale CLAUDE.md known issues removed (Gradle wrapper/plugins, `desktopApp:run`, `JvmInline` import).
  - CI green on GitHub (`asshat1981ar/living-world`, private) after fixing `setup-android` (`packages: ""`).
  - v6 audit: Rest/Work/Flee replay gap was already fixed (all emit `NpcRoutineCompleted`). Fixed trade coin creation in `WorldReducer.applyTrade` (seller credited `min(price, buyer coins)`; unknown buyer pays nothing) with tests `trade_conserves_coins_when_buyer_cannot_afford_price` and `trade_with_unknown_buyer_does_not_pay_seller`.
  - G8 `hashCode()` warnings fixed (`stateHashOf`, `stableStringHash`); invariant checker now 0 warnings.
  - `simulate_village.py` mirror of the trade fix done (`complete_scarcity_trade` takes `buyer_id`), with a test in `test_ui_sprites.py`.
  - `BeliefStore` Float keys fixed (`stableFloatText` in `Belief.kt`, test `belief_keys_use_platform_independent_float_text`).
  - Dual relationship graphs resolved (option A): removed `Npc.relationships`; the world graph is authoritative. Test `test_legacy_save_with_per_npc_relationship_graph_still_loads` covers old saves.
  - Automation Phase 0 added (see CLAUDE.md "Automation hooks"): `bash_guard`, `session_preflight`, `ci_watch`; tests in `tools/test_phase0_hooks.py`. Phase 1 done: stop gate is tiered per module and adds a G8 determinism tier (`decide_quick`, tests in `tools/test_stop_gate.py`). Phases 2-3 done (conductor mod; `tools/hooks/telemetry.py`). Hooks are live from the session after 2026-10-17.
  - Next: v6 audit list is empty; pick new work. Qwen filename "mismatch" is intentional (download name vs on-device `targetFileName`); documented in CLAUDE.md.

# Phase 9 state (2026-10-14)

## State
  - Phase: 9 (Procedural Crisis & Quest Engine + Interactive Compose Tactical Duel Screen + Red-Team Round 3)
  - Milestones Completed:
    - `combat-tactical-001` (Tactical Stance Matrix & Visual Duel Adapter)
    - `save-migration-002` (SaveSchemaMigrator v1->v2->v3 & 64-bit FNV-1a Deterministic Fingerprint)
    - `ai-jni-bridge-003` (GgufBinaryHeaderParser, AndroidModelAssetManager & AndroidLlamaEngineBridge)
    - `simulation-soak-004` (90-Day / 2,160-Hour / 129,600-Tick Closed-Loop Village Soak)
    - `quest-procedural-005` (ProceduralQuestEngine, WorldProblem Derivation & Trade Quest Completion)
    - `compose-combat-screen-006` (TacticalDuelCanvasState, TacticalDuelViewModel & Compose Duel Tab)
  - Workspace Status: All unit, integration, and 90-day soak tests green across `simulate_village.py`, `test_ui_sprites.py`, `test_huggingface_adapter.py`, and Kotlin Multiplatform modules (`G1`–`G10` verified).

## Completed in Phase 9 (Sprints 5–6 + Red-Team Remediations RT-15..RT-18)
  - **Sprint 5 — Procedural Crisis & Quest Opportunity Engine (`quest-procedural-005`)**:
    - Implemented `WorldProblem` and `ProceduralQuestEngine` (`deriveActiveProblems` & `generateOpportunities`) in `WorldState.kt` and wired `SimulationEngine.dispatchEmergentOpportunities` and `WorldReducer.applyOpportunityDispatched`.
    - Restored `world.activeProblems()` and `world.generateOpportunities()` telemetry in `desktopApp/InspectorMain.kt`.
    - **Red-Team Fix `RT-15`**: Enforced strict quest ID deduplication in `SimulationEngine` and `WorldReducer.applyOpportunityDispatched` so emergent crises never flood `EventLedger`.
    - **Red-Team Fix `RT-16`**: Wired automatic scarcity quest completion (`QuestStatus.COMPLETED`) and player bounty payout (`+quest.reward` coins) into `WorldReducer.applyTrade` when a scarce commodity is traded.
  - **Sprint 6 — Interactive Tactical Duel UI & ViewModel (`compose-combat-screen-006`)**:
    - Added `TacticalDuelCanvasState` and `ChimeraUiLayoutEngine.createTacticalDuelState` in `ChimeraUiComponents.kt`.
    - Added `TacticalDuelViewModel`, `TacticalDuelScreen`, and a 4th navigation tab (`"Combat Duel"`) in `androidApp/MainActivity.kt` with unit tests in `ViewModelTests.kt`.
    - **Red-Team Fix `RT-17`**: Guarded `TacticalDuelViewModel.executeDuelRound` and `TacticalDuelSimulator.execute_round` so a defeated (`!isAlive` or `health <= 0f`) opponent cannot fight additional rounds or emit duplicate `NpcKilled` events (`Invariant G4`).
    - **Red-Team Fix `RT-18`**: Extracted `_step_npc_routine` and `_apply_npc_snapshot` helpers in `simulate_village.py` to flatten nesting depth.

2026-10-18: automation phase 2 (conductor mod, session-local) and phase 3 (tools/hooks/telemetry.py, hook outcome log + advice report) done. Review `python3 tools/hooks/telemetry.py` after a few sessions and demote hooks it flags.
