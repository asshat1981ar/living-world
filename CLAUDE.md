# Living-World RPG

Deterministic, event-sourced Kotlin Multiplatform simulation kernel for an Android-first tactical RPG. Read `INSTRUCTIONS.md` for invariants G1–G10 and agent directives; `docs/handoff/last.md` for current phase.

# Modules
- `game-core` — kernel: ids, time, events/EventLedger, beliefs, memory, npc, relationships, factions, world (WorldState/WorldReducer), simulation (SimulationEngine, NpcDecisionEngine, ActionValidator), inspector. Must not import `org.livingworld.ai` (G1).
- `game-persistence` — SaveManager, SaveSchemaMigrator, FNV-1a fingerprint.
- `game-ai` — AiReasoningPort, DeterministicAiFallback, HuggingFace adapters, on-device GGUF ports.
- `game-ui` — sprites, visual adapters, audio, Chimera compose layout engine.
- `androidApp`, `desktopApp` — platform shells.
- Python mirrors/tests at root: `simulate_village.py`, `simulate_soak_90d.py`, `test_*.py`; asset tooling in `scripts/`.

# Build & Test Commands
- Run all multiplatform tests: `./gradlew check`
- Run JVM test suite: `./gradlew :game-core:jvmTest`
- Run 30-day simulation runner: `./gradlew :desktopApp:run --args="30 10"`
- Python tests: `python3 test_ui_sprites.py`, `python3 test_huggingface_adapter.py`

# Code Style & Guidelines
- Immutability: Use standard Kotlin `copy()` patterns for state transitions.
- Event Sourcing: Every state-altering action must emit a corresponding `GameEvent`.
- Invariants: Strict enforcement of G1–G10 rules at all times.

# Known issues (as of 2026-10-16, not yet fixed)
- The Gradle commands above do not run yet: no `gradlew` wrapper; no JDK/Gradle on the Termux device; root `build.gradle.kts` lacks `com.android.application` / `kotlin("android")` plugin versions; KMP modules call `androidTarget()` without the Android library plugin; `desktopApp` has no `application` plugin (so no `run` task).
- `game-core/.../simulation/ActionValidator.kt` uses `@JvmInline` without `import kotlin.jvm.JvmInline`.
- `ModelAssetManager` expects `qwen2.5-0.5b-q4_k_m.gguf`; the file at root is `qwen2.5-0.5b-instruct-q4_k_m.gguf` (kept for AI infra). `assets/models/smollm2-*.gguf` are 303-byte header stubs.
- To re-verify (found in v6 audit): dual relationship graphs (`Npc.relationships` vs `WorldState.relationships`); Rest/Work/Flee mutate state without events (replay gap, G5/G8); trades can create coins; `BeliefStore` keys embed Float `toString` (G8 risk).
- `_archive/pdf-printouts/` holds Google-Docs PDF printouts of an older dump — not source; don't edit.
