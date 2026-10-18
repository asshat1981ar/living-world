# Living-World system health report, 2026-10-18

Basis: forensic report v16, three read-only recon agents (invariants, tech debt, AI infrastructure), an advisor pass (Plan agent), and measurements taken on awsbox. Where a claim is an agent's and I did not verify it, it says so.

## 1. Tooling inventory (what is available to fix things)

| Kind | Relevant to this repo |
|---|---|
| Project hooks | `invariant_guard` (G1/G8/G9 on .kt edits), `bash_guard` (secrets + pre-push full gate), `stop_gate` (tiered, two determinism scenarios), `ci_watch`, `session_preflight`, `telemetry` (needs 20+ runs per hook before it advises) |
| Project agents | `invariant-auditor` (read-only), `refactor-microop` (one patch, no edit), `test-verifier` (runs suites via lw-sim MCP) |
| Project skills | `awsbox-build`, `forensic-hotspots`, `drift-audit`, `c4-snapshot` |
| MCP | `lw-sim`: `awsbox_build`, `run_simulation`, `determinism_benchmark`, `ledger_summary`, `ledger_diff`, `causal_trace` |
| Generic agents | `ecc:architect`, `ecc:kotlin-reviewer`, `ecc:kotlin-build-resolver`, `ecc:security-reviewer`, `ecc:silent-failure-hunter`, `ecc:performance-optimizer`, `ecc:pr-test-analyzer`, `ecc:refactor-cleaner`, `ecc:doc-updater`, `ecc:type-design-analyzer`, `Plan`, `Explore` |
| Generic skills | `dispatching-parallel-agents`, `subagent-driven-development`, `test-driven-development`, `verification-before-completion`, `review-team`, `ecc:architecture-decision-records`, `ecc:plan-orchestrate` (router only; cannot carry scope for build/test/docs steps) |
| Gaps | There is no "advisor" tool; the Plan agent filled that role. A Supabase "advisors" tool exists but is unrelated. The Android SDK and JDK exist only on awsbox. The `plugin-authoring` MCP disconnected mid-session. |

## 2. Framework breakdown

Layers (dependency direction downwards):

1. `game-core` (3.3k LOC main, 1.4k test): ids, time, events and `EventLedger`, beliefs, memory, npc, relationships, factions, `WorldState` and `WorldReducer`, `SimulationEngine`, `NpcDecisionEngine`, `ActionValidator`, `CausalLinks`, `DialogueGrounding`, inspector. No project dependencies (G1 enforced by the compiler, plus a regex check).
2. `game-persistence` (0.2k): `SaveManager`, schema migrator, FNV-1a fingerprint.
3. `game-ai` (1.1k): `AiReasoningPort`, `DeterministicAiFallback`, `AiAdvisedDecisionEngine`, Hugging Face adapters, on-device ports, `ModelAssetManager`.
4. `game-ui` (1.4k): sprites, visual adapters, audio, Chimera layout engine.
5. `androidApp` (0.3k) and `desktopApp` (0.15k): shells. Only the shells touch platform APIs.

Data flow of one tick: `SimulationEngine.tick` selects active NPCs, sorted by id; `NpcDecisionEngine.decide` produces candidates from beliefs, memory and co-presence; the chosen action goes through `ActionValidator`; the engine builds a `GameEvent`; `WorldReducer.apply` returns a new state; the event is appended to the ledger.

Invariant coverage: G1 compiler-enforced. G2 decisions read beliefs and `Memory.Told`. G3 `DialogueGrounding` plus a floor-level `GroundingCheck`. G4 death cascade partly done. G5 ledger is append-only with no mutation API. G6 `CausalLinks` for rumors, inheritance and quests only. G7 fallback exists but no app uses the AI. G8 passes with caveats (below). G9 passes. G10 not applied: the superlinear run time below is unprofiled.

## 3. Hotspots (forensic v16, 71 files, 10.8k LOC)

| Score | File | Note |
|---|---|---|
| 15065 | `simulate_village.py` | Python mirror, stale vs Kotlin |
| 4400 | `WorldReducer.kt` | was 3026 in v15; grew from the G4 cascade |
| 2670 | `simulate_soak_90d.py` | Python mirror |
| 1976 | `VisualAdapters.kt` | game-ui, 7 functions in 360 lines |
| 1865 | `generate_sprites.py` | asset tooling |
| 1469 | `SimulationEngine.kt` | `executeValidatedAction` ~177 lines |
| 1258 | `WorldState.kt` | `marketVillage` ~150 lines of seed content |
| 1025 | `NpcDecisionEngine.kt` | was 555; `generateCandidates` ~115 lines |

Next micro-ops: `applyTheft`, `applyTrade`, `applyNpcDeath`, `executeValidatedAction`. Kernel hotspots rose because this session added behavior; none can be split safely without tests first.

## 4. Technical debt (ranked by impact)

Sizes S/M/L. Agent claims are marked (agent).

1. AI supply chain integrity (M): see section 5.
2. Python mirror subsystem (S to delete, L to bring to parity): a decision is needed; `CLAUDE.md` already says they are stale (agent).
3. Test gaps (M): no dedicated tests for `WorldReducer`, `SimulationEngine`, `EventLedger`, `WorldTime`, `Memory`, `Faction`, `SimulationInspector`, `HfTransport`, `HuggingFaceReasoningAdapter`, `ModelAssetManager`, `TieredResilientReasoningEngine`, `VisualAdapters`, and `desktopApp` has none (agent). `SimulationKernelTest` is a 478-line monolith.
4. Seed/canned content in code (M): `WorldState.marketVillage`, `AudioStateAdapter`, `TurmoilScenario`.
5. Duplication (M): Compose components in `game-ui` and `androidApp`; the sprite pipeline spans scripts, a 188 KB manifest, `SpriteManifest.kt` and 195 generated files with a committed queue file (agent).
6. Stale artifacts and clutter (S): c4 v6, dashboard v6/v15, forensics v6/v14/v15 committed (v16 untracked), three root `image*.jpg` (~650 KB) tracked, `chmod 777` on build files, root `test_*.py`, tool overlap between `check_invariants`, `stop_gate` and `.githooks` (agent).
7. A 491 MB GGUF in the repo root; gitignored, so no repository cost.

No TODO/FIXME markers exist. The debt is stubs, mirrors and missing tests.

## 5. AI infrastructure

Sound: `AiAdvisedDecisionEngine` only accepts a pick that is one of the kernel's candidates, re-validates it, falls back on any error, and never offers survival rules to the AI. Six tests cover it.

Not real yet (verified by me unless marked):
- Nothing is wired: no app constructs `AiAdvisedDecisionEngine` or an HF adapter. `HfTransport` has only Mock and Offline implementations.
- On-device inference is fake (agent): `LocalGgufInferencePort` returns a fixed index 0 with no runner, and `AndroidLlamaEngineBridge` has no JNI and returns action index 0. The 303-byte stubs load because the header parser invents metadata from the filename.
- Checksum bypass: in `AndroidLlamaEngineBridge.verifyChecksum` a blank checksum or one ending in "verified" is accepted on a length check alone (the interface is declared in `ModelAssetManager.kt`). `expectedSizeBytes` is never checked. Only Qwen has a real sha256.
- `scripts/download_model.py` imports `hashlib` but never verifies, downloads straight to the target (partial files persist), skips if any file exists (including a stub), exits 0 on failure, and uses unpinned `resolve/main` URLs.
- `apiToken` sits in a `@Serializable` data class, and the bearer token is sent to any `customSpaceUrl` with no https or host check.
- No timeouts or retries (agent). Coroutine cancellation is swallowed in two places (agent).
- Prompt injection: the topic is stripped of newlines and capped at 200 characters; quotes and belief text are not escaped.
- `GroundingCheck` holes: sentence-initial names are exempt, lower-case claims are not checked, and the player's own topic words are on the allowlist, so the player can whitelist any name. It is a floor, and `CLAUDE.md` says so.
- AI choices are not recorded in the ledger, and live LLM runs are not reproducible. Replay from events is unaffected.
- The "fallback" substring test and the goal-alignment bonus in `DeterministicAiFallback` are defective (agent): the bonus adds the same constant to every candidate.

## 6. Invariant audit (agent, with my corrections)

G1, G5, G9 pass. G8 passes with caveats. Residual G2: apprentice inheritance checks the world's `isAlive` and occupation of a believed victim; `newsworthiness` takes the whole `WorldState`. The advisor downgraded the food-average ordering finding: the sum iterates in insertion order, which replay preserves; it only matters if map construction order changes on load. Sorting by id is cheap hardening. The audit did not scan `game-ui` for G8, and I have not verified that.

## 7. Widened scope (self-prompt)

Prompt I gave myself: "What else determines whether this system is healthy that no report covers? Performance, simulation behavior, replay and save integrity, reducer purity, dead code, supply chain." Results:

- Performance (measured): 30-day run 1.65 s, 90-day run 12.5 s. Three times the events took 7.5 times as long. That is superlinear and unprofiled. The advisor's suspect: the food-average filter and sum runs on every resource-change event. Other suspects: ledger scans, `CausalLinks`, `ProceduralQuestEngine` each tick.
- Simulation health (measured): the default 90-day world has 43,200 events, 85% routine, 6 trades, 2,154 price adjustments (the greedy merchant re-prices bread daily), and 0 opportunities, 0 faction hostility, 0 deaths and 0 rumors. It is nearly static. Only the turmoil scenario produces rumors (77 in 15 days). I count this as the top product gap.
- CI (measured): 18 of the last 20 runs green; median 458 s. Kotlin 2.0.21.
- Not yet measured, to commission: a per-event-type cost profile; a replay and snapshot round-trip check using `ledger_diff`; a reducer purity grep (ordering, `Double` sums); a dead and unwired code report using `drift-audit`; dependency and secrets hygiene using `ecc:security-review`. Save-format versioning has not been checked.

## 8. Remediation workflow

Orchestration constraint: all builds run on one awsbox and a push replaces the remote tree, so builds are serialized. Agents author patches in isolated worktrees without pushing; one integrator applies them in order and runs the gate. Pattern: `dispatching-parallel-agents` for authoring, `subagent-driven-development` per package, `verification-before-completion` at each gate.

| ID | Package | Size | Depends | Owner | Gate |
|---|---|---|---|---|---|
| P0 | Baseline: golden hashes (default + turmoil), CI state, profile | S | none | `test-verifier` | baseline recorded |
| P1 | Model asset integrity: reject blank/"verified" hashes, check size, atomic verified download, non-zero exit, pinned revision | M | P0 | `ecc:security-review` + TDD | new checksum tests + gate |
| P2 | HF transport safety: https/host allowlist, token out of serializable class, timeouts, rethrow cancellation | M | P0 | `ecc:silent-failure-hunter` + TDD | mock-transport tests |
| P3 | Grounding and prompt hardening: sanitiser, belief floor in the adapter, GroundingCheck holes | M | P0 | `ecc:security-review` | adversarial corpus |
| P4 | Profile, then fix hot paths | M | P0 | `ecc:performance-optimizer`, `refactor-microop` | 90-day time down, hashes unchanged |
| P5 | Gameplay realism: starvation, asset redistribution, price damping, opportunity/faction triggers | L | P4 (and P9 first) | `brainstorming`, `writing-plans`, TDD | declared golden changes only |
| P6 | Test gaps and split `SimulationKernelTest` | M | P0 | TDD, `ecc:pr-test-analyzer` | coverage delta |
| P7 | Micro-ops on `applyTheft`, `applyTrade`, `applyNpcDeath`, `executeValidatedAction` | M each | P6 | `refactor-microop`, one patch at a time | hashes identical |
| P8 | Residual G2 items | S | P6 | `invariant-auditor` | audit pass |
| P9 | Record AI choices as ledger events, reproducible runs | M | P2, P3 | `ecc:architecture-decision-records` | ADR + replay test |
| P10 | Python mirrors: delete or freeze (user decision) | S | none | `ecc:refactor-cleaner` | decision recorded |
| P11 | Stale artifacts, clutter, UI dedupe | S | P10 | `ecc:doc-updater` | CI green |

Waves: 0 baseline (read-only agents in parallel); 1 P1, P2, P3, P6, P10, P11 authored in parallel and verified serially in that order; 2 P4 then P7 serialized, with P8 applied after P7; 3 P9, then P5 as one golden re-record.

Gates between waves: full gate green with both determinism scenarios, golden hashes unchanged except where a package declares a change, `ecc:kotlin-review` on the diff, CI green. Rollback: drop the failing patch and re-apply the rest; a behavior-neutral package that changes a hash is rejected without debugging. Stop and ask the user if determinism diverges twice, the gate fails after one `kotlin-build-resolver` attempt, a hook blocks a needed edit, CI falls below baseline, or a package fails three times.

Top risks: build-box contention; refactors silently changing determinism; P7 before P6; optimizing before profiling; AI work leaking into the deterministic core.

## 9. Corrections to my earlier claims in this session

- The checksum bypass lives in the Android bridge, not in `ModelAssetManager` itself.
- The food-average ordering is a hardening item, not a determinism bug.
- Hotspot growth in `WorldReducer` and `NpcDecisionEngine` came from this session's features.
