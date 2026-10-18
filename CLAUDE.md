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

# Build environment
- Gradle works (wrapper, plugin versions, `androidTarget`, `desktopApp:run` all fixed); `./gradlew check` is green as of 2026-10-17. There is still no JDK on the Termux device, so run Gradle on awsbox: `python3 -I tools/lw_remote.py --push --gradle check` (needs a live `aws login --remote` session).
- CI: `.github/workflows/ci.yml` (invariants + Python tests, Gradle check). `tools/publish_github.sh` creates the GitHub repo (private by default) and pushes using `GITHUB_TOKEN` from `~/.env`.

# Automation hooks (`.claude/settings.json`, scripts in `tools/hooks/`)
- `invariant_guard.py` (before `.kt` edits): blocks newly introduced G1/G8/G9 violations.
- `bash_guard.py` (before Bash): blocks `git add -A|.` / `git commit -a` when an untracked, non-ignored file looks like a secret; runs the stop gate before `git push` or `publish_github.sh` (`LW_SKIP_PREPUSH=1` skips it).
- `session_preflight.py` (SessionStart): reports whether the AWS session is valid and whether the GPU box is running.
- `ci_watch.py` (after a push, then at prompt/session start): polls GitHub Actions in the background and reports pass/fail once, with only the failing step's log tail. Needs `gh` logged in.
- `stop_gate.py` (Stop, tiered): invariants, tool tests, then only the Gradle tasks the changed modules need (game-core -> all four `jvmTest`; one other module -> its `jvmTest`; build files/androidApp/desktopApp -> full `check`), then a G8 determinism tier (30-day `:desktopApp:run` twice, fingerprints must match; `LW_DETERMINISM_RUNS` sets the count) when game-core or desktopApp changed. Per-module digests in `.git/lw-stop-gate/quick.json`. The pre-push gate (`bash_guard`) still runs the full `check`. Measured: unchanged 10 s, one module ~1m50s, first run ~4m50s.
- `telemetry.py`: every guard logs pass/warn/block/skip + seconds to `.git/lw-telemetry/events.jsonl`; `python3 tools/hooks/telemetry.py` prints per-hook counts and flags hooks to demote (never blocked and slow) or tune (bypassed >=25%) once a hook has 20+ runs.
- Conductor mod (outside the repo, `~/.claude/dev-mods/<session>/lw-conductor`): CI status line/toasts from `ci_watch` state, and a login-code `state=` annotation on pasted codes.
- Tests: `python3 -m unittest discover -s tools -p 'test_*.py'`.

# Known issues (as of 2026-10-17, not yet fixed)
- Model filenames are intentional, not a bug: `ModelAssetManager` and `scripts/download_model.py` fetch `qwen2.5-0.5b-instruct-q4_k_m.gguf` from Hugging Face and save it on-device as `qwen2.5-0.5b-q4_k_m.gguf` (`targetFileName`). The root-level `qwen2.5-0.5b-instruct-q4_k_m.gguf` (gitignored) was re-downloaded 2026-10-18; size and sha256 match Hugging Face. The Qwen descriptor's `expectedSizeBytes` and `sha256Checksum` are Hugging Face's published values (2026-10-18); the other descriptors' sizes are unverified and nothing enforces any of them yet. `assets/models/smollm2-*.gguf` are 303-byte header stubs.
- Relationships: `WorldState.relationships` is the single source of truth (the per-NPC `Npc.relationships` graph was removed 2026-10-17; `reputationToward` and `transmitBelief` take the graph as a parameter; old saves still load because `ignoreUnknownKeys = true`).
- Verified 2026-10-17: `BeliefStore` keys no longer embed `Float.toString` (price propositions use `stableFloatText`, same text as JVM for realistic values, so old saves still match). Rest/Work/Flee already emit `NpcRoutineCompleted` (no replay gap). Trade coin creation fixed in `applyTrade` (seller is credited only what the buyer paid). G8 `hashCode()` warnings fixed: `SaveEnvelope.stateHash` now derives from the FNV-1a digest (`SaveManager.stateHashOf`); stance selection uses an explicit string hash. The `simulate_village.py` mirror (`complete_scarcity_trade`) now matches (buyer debited, player credited only what was paid).
- G2 in decisions (2026-10-18): `generateCandidates` no longer reads other NPCs' beliefs (it remembers what it already told them via `Memory.Told`), the player's coins, or the world's dead merchants (apprentice inheritance needs an `EntityKilledNpc` belief). Known gaps: unwitnessed/killerless deaths are never learned (G4 work), and rumors almost never win the utility contest (ShareRumor 0.45 < Work 0.5), so information barely diffuses. The default 30-day run has no deaths or rumors, so its fingerprint does not exercise these paths; `BeliefDrivenDecisionTest` has a scripted killing scenario.
- G4 death cascade (2026-10-18, partial): `applyNpcDeath` now tells absent NPCs who liked the victim (sentiment >0.2) or share an `Npc.factions` entry (hearsay, confidence 0.85), adds `Proposition.NpcDied` for killerless deaths, and fails quests the dead issued. Not done: starvation deaths (food floors at 0.5, so unreachable), asset redistribution, NPC-to-NPC scarce trade paying the player, homicide-problem resolution. `Faction.members` is empty in the TestWorld fixture; `Npc.factions` is what the cascade reads. `DeathCascadeTest` holds the determinism scenario.
- `_archive/pdf-printouts/` holds Google-Docs PDF printouts of an older dump — not source; don't edit.
