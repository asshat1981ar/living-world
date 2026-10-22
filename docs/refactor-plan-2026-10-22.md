# Living World — systematic fix & refactor plan (2026-10-22)

Implements everything found by the recon (`docs/reports/recon-2026-10-22.md`) and the adversarial pass
(`docs/reports/adversarial-2026-10-22.md`). This is the execution plan: waves, commits, tests, gates and rollbacks.
It is written against the repo’s own rules (`INSTRUCTIONS.md` invariants, `docs/backlog.md` queue, `tools/verify.py`
gate, `lw_mirror` ↔ `tests/golden` co-commit rule, `tools/hooks/stop_gate.py` tiers).

**Status:** proposed, nothing implemented yet. **Branch:** `cline/6cjpf2h3` (or a fresh `feature/` branch per wave).

## 0. How to use this document

- §2 fixes the target design (decisions, rejected alternatives). Write an ADR per decision (`docs/adr/`).
- §3–§4 are the waves and the commit-by-commit work. One wave = one branch = one PR.
- §5 maps every finding to the commit that closes it — use it as the completion checklist.
- §6 is the paste-ready backlog re-seed; the `feature-loop` skill then works one item per pass.
- §7 is the CI/tooling specification, §8 the verification matrix (which command proves which wave).
- §10 lists what needs the user’s OK before it can start.

## 1. Non-negotiables (from the repo, not negotiable)

1. **G1–G10 always hold.** No AI imports in `game-core`, no platform code in `commonMain`, no omniscience (G2),
   grounded dialogue (G3), permanent death consequences (G4), append-only history (G5), backward-traceable causes
   (G6), deterministic fallback (G7), bit-for-bit determinism (G8), no platform leaks (G9), measure before optimizing (G10).
2. **One golden re-record per wave, never more.** Behaviour-changing work is grouped into a single declared `golden`
   wave (§4, W2 and W6) and lands with its `lw_mirror` port in the same commit chain. Everything else must leave
   `tests/golden/*.gz` and the 90-day fingerprint **byte-identical**.
3. **Dependency changes need `LW_ALLOW_DEPS=1` and the user’s OK** (`.githooks/pre-commit`, INSTRUCTIONS §3 isolation
   gate). Toolchain bumps (Gradle/AGP/Compose/SDK) are dependency changes.
4. **The tree stays green.** Every commit passes `python3 -I tools/verify.py` (or the local equivalent from W0) and
   `python3 -m unittest discover -s tools -p 'test_*.py'`.
5. **Facts are updated in the same commit**: `CLAUDE.md` fingerprints, `docs/backlog.md`, `docs/handoff/last.md`,
   `docs/open-questions.md`.

## 2. Target design decisions

### D1 — The event contract: validate, then apply, and be idempotent

**Decision.** `DefaultWorldReducer.apply` runs a pre-condition check per event type, then applies, and is **idempotent per
event id**: applying an event whose id is already in the ledger returns the state unchanged. Invalid events raise
`InvalidEventException` (a plain commonMain exception) instead of silently corrupting state.

**Why.** Today the reducer trusts the payload, so an unreachable seller destroys coins, a dead NPC is healed, and a
duplicated event applies twice (A1–A5, A7). Loud failure + idempotency is what makes the ledger authoritative.

**Rejected.** Silent no-op on invalid events (hides producer bugs); a “validating wrapper” outside the reducer (the
Android UI and the ledger-replay path would bypass it).

**Files.** `game-core/.../world/WorldReducer.kt` (branch dispatch), new `game-core/.../world/EventValidator.kt`,
new `game-core/.../world/WorldInvariants.kt`, `game-core/.../events/EventLedger.kt` (`applyAll` with unique-id check).

### D2 — Identity is typed, never reconstructed from a string

**Decision.** `EntityId` gains `val kind: String` (`npc` / `player` / `faction` / `location`). Relationships are keyed by
`EntityKey(kind:id)`, serialised as a string (`"npc:merchant->player:player"`) because kotlinx JSON only allows
primitive/enum map keys. Reducers switch on the entity **type** (`when (entity) { is EntityId.Npc -> … }`) instead of
re-wrapping `identifier` as `NpcId`.

**Why.** `Npc(x)` and `Player(x)` currently share one relationship edge and the reducers can address the wrong entity
(A8). Save schema v3 → v4 migrates old `"a->b"` keys to `"npc:a->npc:b"` where the kind is unknown.

**Files.** `game-core/.../ids/EntityId.kt`, `game-core/.../relationships/Relationship.kt`,
`game-core/.../world/WorldReducer.kt`, `game-persistence/.../SaveManager.kt` (schema v4), `lw_mirror/relationships.py`.

### D3 — Items and coins are conserved by construction

**Decision.** `TradeCompleted` transfers an item **only** if the seller holds it and is alive; the item moves to the
buyer. `ItemStolen` moves the item from owner to thief (player thief → `PlayerState.inventory`). Coin transfers are
checked against both sides and the treasury; `WorldInvariants` asserts
`sum(npc.coins) + player.coins + treasury == constant` for every state.

**Why.** A1–A4 break the README’s “nothing minted or destroyed” and the “closed coin economy” claim; the Turmoil
“theft” currently moves no item at all.

**Files.** `game-core/.../world/WorldReducer.kt` (`applyTrade`, `applyTheft`, `applyRationPurchase`),
`game-core/.../npc/Npc.kt` (item helpers), `game-core/.../world/WorldState.kt` (`PlayerState.inventory` helpers),
`lw_mirror/reducer.py`.

### D4 — One observable contract: a full-state digest plus a versioned dump

**Decision.** Replace the ad-hoc fingerprint with a **canonical state digest** that covers the entire authoritative
state: every NPC (identity, traits, goals, fears, values, beliefs with confidence/source, memories, items, resources,
emotional state, factions), factions (members, leadership, allies/enemies), relationships (all five axes + shared
events), economy (prices, scarcity, treasury, volume), locations, quests (all fields), the player (all fields), and a
rolling digest of the event ledger. Floats use the existing platform-independent `stableFloatText`; every collection is
iterated in a sorted, typed order. `CanonicalDump` gains a `FORMAT 2` header and a full state section; the event lines
stay as they are so old goldens remain comparable before the re-record.

**Why.** Today the fingerprint omits belief content, items, traits, the player, treasury and quests, and the dump omits
the same (A18, A19). A behaviour change in any of those is invisible to every golden.

**Files.** `game-persistence/.../SaveManager.kt` (digest), `desktopApp/.../CanonicalDump.kt` (format v2),
new `game-core/.../world/StateDigest.kt` (shared by both), `lw_mirror/dump.py`, `tools/ledger_tools.py`.

### D5 — Knowledge is scoped, quests have owners, causes point backwards

**Decision.**
- `WorldState.knownEvents(npc)` replaces the global `recentEvents` in `snapshotFor`: an NPC knows events it witnessed or
  acted in, plus events at its location whose type is public (a declared list), plus what its beliefs carry.
- `ProceduralQuestEngine` only produces a quest when a **living** NPC that **knows** of the problem issues it; an
  unwitnessed murder produces no public quest, and a dead NPC never issues one.
- `deriveActiveProblems` excludes problems whose quest is `COMPLETED`/`FAILED` and de-duplicates per victim.
- `Belief` carries the originating `EventId` where known; `CausalLinks.originOf` is time-bounded
  (`event.time <= belief.acquiredAt`) and returns null instead of guessing.

**Why.** A12–A16: the AI saw a killing it could not have witnessed; a dead guard captain issued his own murder
investigation; completed investigations stayed “active”; causal links pointed at later crimes.

**Files.** `game-core/.../world/WorldState.kt`, `game-core/.../world/WorldReducer.kt` (quest issuers),
`game-core/.../beliefs/Belief.kt`, `game-core/.../events/CausalLinks.kt`, `game-core/.../simulation/ActionValidator.kt`,
`game-ai/.../reasoning/AiAdvisedDecisionEngine.kt`, `lw_mirror/{beliefs,causal,reducer}.py`.

### D6 — Grounding v2: a closed vocabulary plus the speaker’s own words

**Decision.** `GroundingCheck` keeps the named-entity/number checks and adds a **content-word allowlist**: every word of
a sentence must appear in the speakable facts, the speaker/location, or a curated small-talk/function-word list
(the existing `COMMON_OPENERS` becomes the seed of a larger, curated list). Anything else is rejected and the kernel
line is used. The player’s text is still sanitised and never whitelisted.

**Why.** A15: `"alden poisoned the well"` passes today because only capitalised and numeric tokens are treated as
claims, so the README’s “anything else is dropped” is false.

**Trade-off.** Stricter grounding rejects more model replies (watch `DialogueStats.rejected`); the built-in line is always
available, so the failure mode is “less AI flavour”, not “wrong information”. Add a metric: rejected/salvaged ratio in
the AI tab.

**Files.** `game-core/.../dialogue/GroundingCheck.kt`, `game-ai/.../dialogue/GroundedDialogueVoice.kt`,
`game-ai/.../dialogue/PregeneratedLines.kt` (key includes location + relationship — A17).

### D7 — One world in the app, with persistence and a tick

**Decision.** A process-scoped `WorldStore` owns the single `WorldState`, exposes `StateFlow<WorldState>`, applies
events through the reducer, ticks `SimulationEngine.advance(…, 1 h)` while the app is foregrounded, and persists via
`SaveManager` (schema v4) on stop / on pause. View models observe the store; they own no world copy. The inventory screen
derives counts from `world.player.inventory`; travel emits a new `PlayerMoved` event; the duel gives the player health
(`PlayerState.health`) and lets the opponent retaliate, with a co-location check.

**Why.** R1/A9: four private worlds, no tick, no save, state lost on Activity recreation, and player travel was not an
event at all.

**Files.** new `androidApp/.../world/WorldStore.kt`, `MainActivity.kt`, the four view models,
`game-core/.../world/WorldState.kt` (`PlayerState.health`, `PlayerMoved` event), `game-persistence/.../SaveManager.kt`.

### D8 — The gate is machine-checked, local, and honest

**Decision.** CI runs: invariants (`--all` **and** a dependency diff against the base branch), Python tests, Gradle
`check`, the three Kotlin goldens, a 90-day fingerprint test, and the debug APK. `tools/verify.py` gains `--local`
(no AWS). `stop_gate.decide` — the pre-push “full” gate — gains the same determinism/golden tier the Stop hook already
has, and the fail-open behaviour is documented in the tool’s docstring. `check_invariants.py` gains the missing rules
(`Thread.`, `Math.random`, `TimeSource.Monotonic`, `kotlin.jvm` import, fully-qualified `Random`). The secret guard
gains more token patterns and scans staged content.

**Why.** A33–A38: CI never compared Kotlin with the goldens, the dependency gate was never enforced, the checker had
holes, and the documented verify path needed an AWS session.

**Files.** `.github/workflows/ci.yml`, `tools/verify.py`, `tools/check_invariants.py`, `tools/hooks/stop_gate.py`,
`tools/hooks/bash_guard.py`, `tools/publish_github.sh`, `tools/test_*.py`.


### D9 — Ledger and memory stop being quadratic (measure first)

**Decision.** After W2 lands, `EventLedger` stores events in blocks (append copies one block, not the whole history),
`find` stays O(1), and `all()` returns a cached flattened list. `MemoryStore` gets the same treatment plus a documented
cap/pruning policy. Save writes become compact (no `prettyPrint`) with optional gzip.

**Why.** A24/A41: 45,615 events in 1.3 s but 184,726 events in 42.4 s — quadratic, and the app will tick hourly.

**Files.** `game-core/.../events/EventLedger.kt`, `game-core/.../memory/Memory.kt`,
`game-persistence/.../SaveManager.kt`, `lw_mirror/ledger.py`, `tools/ledger_tools.py` (ledger JSON shape).

### D10 — Wires that do not exist yet are either built or removed from the docs

**Decision.** The README’s promises are matched to reality: either the feature is implemented (a `Steal` intent and a
player action; `ChangeAllegiance`/`FactionHostilityDeclared` producers; quest acceptance) or the claim is removed.
Unreachable event types are either given a producer or deleted with their tests. Dead code is removed or wired:
`game-ui`→`game-ai` dependency, `AndroidModelAssetManager`, `DeterministicAiFallback.isFallbackProposal` /
`extractLastJsonObject`, `RelationshipGraph.remove`, the empty `safety-gates/` directory, `prompts/prompt-registry-v1.md`,
and the Pillow dependency of `scripts/generate_sprites.py` (documented in `requirements-assets.txt`).

**Why.** R11/A42 plus the README claims falsified in the adversarial pass (“steal bread”, “Everything is an event”).

### D11 — Toolchain and shipping (needs approval)

**Decision.** Gradle 8.14.x, AGP 8.13.x, `compileSdk`/`targetSdk` 36, a current Compose BOM, `distributionSha256Sum` for
the wrapper, optional Gradle dependency-verification metadata, an adaptive app icon, and backup rules that exclude the
model directory.

**Why.** R5/R9: Gradle 8.8 warns it is deprecated, AGP 8.5.2 is the *minimum* KGP 2.4.20 supports (and the source of
the lint noise), and Play requires target API 36 from 2026-08-31.

**Constraint.** Kotlin stays on 2.4.20 (LiteRT-LM requires it). AGP must stay within 8.5.2–9.3.1 and Gradle within
7.6.3–9.7.0 per the official KGP matrix.

## 3. Waves and their order

```
W0 harness & truth ──► W1 kernel contract ──► W2 behaviour wave (1 re-record) ──► W3 test depth
                                  │                        │
                                  └────► W4 app world ◄────┘        └──► W6 realism (2nd re-record)
                                                        └──► W5 AI/Android/toolchain (parallel, needs approval)
                                                             └──► W7 performance & save format
```

| Wave | Goal | Behaviour change? | Golden re-record? | Depends on | Needs approval |
|---|---|---|---|---|---|
| **W0** | Make the gate trustworthy and the docs honest | no | no | – | no |
| **W1** | Kernel contract: validate, idempotent, alive-guards, invariants, save gate | no (by construction) | no (must prove) | W0 | no |
| **W2** | The one behaviour wave: typed identity, conservation, quests, causes, digest, dump v2 | yes | **yes (one)** | W1 | yes (declared golden) |
| **W3** | Tests assert the whole state, not a projection | no | no | W2 | no |
| **W4** | One world in the app: store, tick, save, travel event, duel | yes (app only) | no (desktop runs unchanged) | W2 | no |
| **W5** | AI/Android hardening + toolchain + shipping config | no (app only) | no | W2 | **yes** (dependencies) |
| **W6** | Gameplay realism (starvation, quests, prices, theft, war) | yes | **yes (second)** | W2 | yes (`[ask]`) |
| **W7** | Performance + save format | no | no (digest constant only) | W2 | no |

**Rule:** W2 and W6 are the only waves allowed to change goldens. W7 may change the *digest constant* (a documented
number), never the canonical dump.


## 4. Wave-by-wave execution detail

Every commit ends green, follows `type(scope): what and why`, and updates the facts it changes. “Gate” below means the
commit’s own verification; the wave exit gate is stricter.

### W0 — Harness & truth (no behaviour change)

**Goal.** Make the machine able to catch a regression before this plan is finished, and stop the docs from lying.

**Entry.** Branch `feature/w0-harness` from `main`; baseline recorded: 90-day fingerprint `041e2593769b807b`, three
goldens green, 85 Python tests green, `check` green.

| # | Commit | Files | Tests | Gate |
|---|---|---|---|---|
| C0.1 | `ci: compare Kotlin output with the committed goldens` | `.github/workflows/ci.yml` | – | CI job runs the three `:desktopApp:run --golden=` commands and fails on `MISMATCH` — **delivered as `docs/patches/ci-goldens-and-dependency-gate.patch`** (the pushing App token has no `workflows` scope) |
| C0.2 | `ci: enforce the dependency isolation gate` | `.github/workflows/ci.yml`, `tools/check_invariants.py` (new `--base <ref>` mode) | `tools/test_check_invariants.py`: base-diff mode finds an added `implementation(...)` line and honours `LW_ALLOW_DEPS=1` | CI fails a PR that adds a dependency without the env var — the tool half landed; the CI half is in the same patch |
| C0.3 | `tools: verify.py can run the gate locally` | `tools/verify.py` (`--local`), `tools/test_verify.py` | `--local` runs Gradle + goldens locally; `--no-remote` unchanged | `python3 -I tools/verify.py --local` green on a machine with JDK 17 + SDK |
| C0.4 | `tools: pre-push gate runs the determinism tier` | `tools/hooks/stop_gate.py`, `tools/test_stop_gate.py` | `decide()` calls `determinism_check`; infra failure still warns, not blocks (documented) | `python3 -m unittest discover -s tools` green |
| C0.5 | `tools: invariant checker covers more platform leaks` | `tools/check_invariants.py` (+ `Thread.`, `Math.random`, `TimeSource.Monotonic`, `kotlin.jvm` import warning, fully-qualified `Random`), tests | new rule cases in `tools/test_check_invariants.py` | checker tests green; `--all` still 0 errors on the tree |
| C0.6 | `tools: secret guard sees more token shapes` | `tools/hooks/bash_guard.py` (+ `hf_`, `sk-`, `xoxb-`, JWT, `*_TOKEN=`, `*_SECRET=`, AWS secret key), tests | `tools/test_phase0_hooks.py`: each new pattern is flagged | guard tests green |
| C0.7 | `tools: publish script keeps the token out of argv` | `tools/publish_github.sh` (credential helper / `--config` file with 0600; JSON-encode `NAME`) | – | manual dry run against a scratch repo (no push) |
| C0.8 | `docs: mark the claims the adversarial pass falsified` | `README.md`, `CLAUDE.md`, `docs/backlog.md`, `docs/open-questions.md` | – | every falsified claim now says “known issue, see plan §5” |
| C0.9 | `test: characterise the behaviour we intend to keep` | `game-core/src/commonTest/.../CharacterizationTest.kt` | coin conservation over 30 days; ledger ids unique and increasing over 30 days; replay equals live state **for the full state** (if this fails, file it as a new finding instead of forcing a fix) | new tests green; **goldens unchanged** |
| C0.10 | `build: pin the Gradle distribution checksum` | `gradle/wrapper/gradle-wrapper.properties` (`distributionSha256Sum`) | – | `./gradlew --version` still works; wrapper-validation action passes |

**Exit gate.** `./gradlew check` green; three goldens byte-identical; 90-day fingerprint unchanged; Python tests green;
CI runs the new jobs (push a branch and watch the run).

**Rollback.** Each commit is independent; C0.1/C0.2 can be reverted without touching the rest.

**Risk.** C0.2 needs a base-branch reference that works on `pull_request` (use `origin/main`); C0.7 touches a script that
pushes to GitHub — rehearse on a scratch repo first.


### W1 — Kernel contract hardening (behaviour-neutral by construction)

**Goal.** Make the reducer authoritative: validate, be idempotent, respect life, and expose a validator that the save
gate and the tests share.

| # | Commit | Files | Tests | Gate |
|---|---|---|---|---|
| C1.1 | `feat(core): event pre-conditions and InvalidEventException` | new `game-core/.../world/EventValidator.kt`, `WorldReducer.kt` (dispatch through it) | new `EventValidatorTest`: trade without stock, routine for a dead NPC, theft by an unknown entity are rejected with a typed error | goldens unchanged (the engine never emits such events) |
| C1.2 | `fix(core): applying a recorded event twice changes nothing` | `WorldReducer.kt` | `WorldReducerTest`: second apply leaves state and ledger identical; replace the “pins the bug” duplicate-kill test with a spec test | goldens unchanged |
| C1.3 | `fix(core): the dead do not work, trade or bleed` | `WorldReducer.kt` (alive guards in `applyRoutineCompleted`, `applyTrade`, `applyRationPurchase`, `applyTheft`, `applyRumor`, `applyAllegianceChange`, `applyPriceAdjustment`, `applyShopInherited`) | `CoreBuildingBlocksTest`: a killed NPC ignores a later routine/trade; `SaveManager.validateWorldIntegrity` accepts the resulting state | goldens unchanged |
| C1.4 | `feat(core): WorldInvariants validator` | new `game-core/.../world/WorldInvariants.kt` | coins conserved; dead⇄zero health both directions; every event reference resolves; a quest issuer exists (the *living*-issuer rule lands in W2, where behaviour may change) | goldens unchanged |
| C1.5 | `fix(persistence): a save must carry a digest and consistent metadata` | `game-persistence/.../SaveManager.kt` | blank digest rejected; `eventCount`/`savedAtTick` must match the decoded world; dead-identity/zero-health and alive-identity/positive-health both enforced | `SaveManagerTest` green |
| C1.6 | `fix(persistence): replay rejects duplicate or out-of-order event ids` | `SaveManager.replayEvents` | replay of a ledger with a duplicated id fails loudly; the normal replay still equals the live state | `SaveManagerTest` green |
| C1.7 | `test: the 90-day fingerprint is a test constant` | new `game-core/src/commonTest/.../LongRunFingerprintTest.kt` | asserts `041e2593769b807b` for the 90-day default run (skipped when the run would exceed the CI budget — see §8) | goldens and fingerprint unchanged |

**Exit gate.** Goldens byte-identical, fingerprint unchanged, `check` green, plus the new invariant tests green. If C1.1
rejects an event the engine *does* produce, stop and fix the producer — do not weaken the validator.

**Rollback.** W1 is a series of behaviour-neutral commits; revert individually.

**Risk.** C1.1 can surface a latent producer bug (that is the point); treat it as a finding, not a blocker.


### W2 — The one behaviour wave (single golden re-record)

**Goal.** Fix every kernel-level finding at once, so the project pays exactly one re-record.

**Branch exception (important).** From the first behaviour commit in this wave the golden check and the mirror-parity
test are **expected to fail**; the wave branch is merged only after the final re-record commit turns them green. This is
the only sanctioned exception to “the tree stays green”, and it must be stated in the wave PR description.

| # | Commit | Files | Tests (new) | Notes |
|---|---|---|---|---|
| C2.1 | `feat(core): typed entity keys` | `EntityId.kt`, `Relationship.kt`, `WorldReducer.kt` (typed `when`), `SaveManager.kt` (schema v4 + v3→v4 key migration) | `Npc(x)` and `Player(x)` no longer share an edge; old saves migrate | dump `R|` lines change → part of the re-record |
| C2.2 | `fix(core): a trade needs a living seller who holds the item` | `WorldReducer.kt`, `DefaultVillageFactory` (merchants start with stock), `SimulationEngine` (trade candidate only when stock exists) | unknown seller rejected; dead seller rejected; no item minted; default run still trades and the player’s coins and the merchant’s stock both fall | changes the default run (trade count) → re-record |
| C2.3 | `fix(core): a theft moves the item` | `WorldReducer.kt` (`applyTheft`), `TurmoilScenario` (give the blacksmith a sword so the scenario is a real theft), `lw_mirror/scenarios.py` | thief receives the item; player victim loses it; turmoil scenario now moves an item | re-record |
| C2.4 | `fix(core): quests need a living, informed issuer; problems resolve` | `WorldState.kt` (`deriveActiveProblems` filters by quest status, de-duplicates per victim), `SimulationEngine` (issuer selection), `WorldReducer` (quest issuer must be alive) | unwitnessed death → no public quest; the dead guard captain cannot issue his own investigation; a completed quest removes the problem | changes turmoil/feud events → re-record |
| C2.5 | `fix(core): causes point backwards` | `Belief.kt` (+`originEventId`), `CausalLinks.kt` (time-bounded, null instead of guessing), producers that know the origin | a belief about theft #1 links to #1, never #2; a belief with no unique origin has no link | rumor/death events’ `causalEventId` may change → re-record |
| C2.6 | `fix(core): the AI only sees what the NPC could know` | `WorldState.kt` (`knownEvents`), `AiAdvisedDecisionEngine.kt` | a killing at another location is absent from the snapshot | AI-only; no golden impact |
| C2.7 | `feat(core,persistence): full-state digest and dump format v2` | new `StateDigest.kt`, `SaveManager.kt` (digest v2), `CanonicalDump.kt` (`FORMAT 2` header + state section: beliefs, items, traits, player, quests, factions) | belief/inventory/quest changes now move the digest; dump contains the new fields | dump format changes → re-record |
| C2.8 | `feat(mirror): port the new kernel semantics` | `lw_mirror/{reducer,relationships,beliefs,causal,engine,dump,scenarios}.py`, `tools/ledger_tools.py` | `tools/mirror_parity.py` green against the **new** goldens | mirror and goldens move together |
| C2.9 | `chore(goldens): re-record the three scenarios and the fingerprint` | `tests/golden/*.gz`, `CLAUDE.md`, `docs/backlog.md`, `docs/handoff/last.md` | `python3 tools/mirror_parity.py` green; `--golden` runs green; new 90-day fingerprint recorded | the single re-record |
| C2.10 | `fix(core,ai): grounding v2 — a closed vocabulary plus the speaker’s words` | `GroundingCheck.kt`, `GroundedDialogueVoice.kt` | `"alden poisoned the well"` with no facts is rejected; grounded lines still pass; `DialogueStats` counts the rejections | AI-only; no golden impact |
| C2.11 | `feat(core): the ledger knows which entities exist` | `WorldState.kt`, `EventLedger.kt` | every event actor/owner/victim resolves; a relation to an unknown entity is rejected | no golden impact (no such event in the scenarios) |
| C2.12 | `test(core): a fourth golden scenario for the uncovered paths` | new scenario in `desktopApp` (`TurmoilScenario`), `lw_mirror/scenarios.py`, `tests/golden/` | a scenario that exercises `ChangeAllegiance`, `Flee`, `FactionHostilityDeclared`, NPC↔NPC trade and `EntitiesMet`; mirror parity covers it | adds a golden file (still one re-record) |

**Exit gate.** `python3 tools/mirror_parity.py` green; all three `--golden` runs green; `check` green; Python tests
green; the 90-day fingerprint constant updated in `CLAUDE.md` and in the JUnit constant; `WorldInvariants` green over a
90-day run; coin total conserved over 90 days; **no** unresolved “known issue” markers left for the findings closed here.

**Rollback.** The wave is one PR; if the mirror port cannot be made exact, revert the whole wave (the pre-wave state is
green) rather than shipping a half-ported mirror.

**Risk (highest in the plan).** The mirror is a hand-written port; C2.1–C2.7 change semantics it mirrors. Mitigation:
split C2.8 into C2.8a relationships, C2.8b trade/theft, C2.8c quests/causes, C2.8d dump v2, and keep `mirror_parity`
green *per area* during development; the committed goldens change once, in C2.9.


### W3 — Test depth (no behaviour change)

| # | Commit | Files | Tests |
|---|---|---|---|
| C3.1 | `test(core): replay and soak compare the whole state` | `game-core/src/commonTest/.../ReplayDeterminismTest.kt` | replay equals live state for npcs, factions, relationships, economy, quests, player and ledger; the 90-day soak compares the same |
| C3.2 | `test(core): coin and item conservation over 30 days` | new `ConservationTest.kt` | total coins constant; every trade preserves item counts; no item appears or vanishes |
| C3.3 | `test(core): the ledger is append-only and strictly ordered` | `EventLedgerTest.kt` | ids unique and increasing; a re-applied event changes nothing; `traceCausalChain` never points forward |
| C3.4 | `test(core): turn the codified-bug tests into spec tests` | `WorldReducerTest.kt`, `SimulationEngineTest.kt`, `DeathCascadeTest.kt`, `EconomyAndQuestTest.kt` | the six tests listed in §5 now assert the corrected behaviour |

**Exit gate.** All green; no golden change; the 90-day soak runs in CI within its budget (§7).

### W4 — One world in the app (app-only changes)

| # | Commit | Files | Notes |
|---|---|---|---|
| C4.1 | `feat(android): WorldStore owns the single world` | new `androidApp/.../world/WorldStore.kt`, `MainActivity.kt`, the four view models | view models observe `StateFlow<WorldState>`; no private world copies; `currentWorldState` accessors removed |
| C4.2 | `feat(android): the world ticks and saves` | `WorldStore.kt` (tick loop, `SaveManager` v4 write on stop/pause, restore on start) | first run creates `marketVillage()`; later runs restore |
| C4.3 | `fix(android): the inventory comes from the world` | `InventoryViewModel.kt` | counts derived from `world.player.inventory`; catalog refreshed after a trade; selling something not held is impossible |
| C4.4 | `feat(core,android): player travel is an event` | new `GameEvent.PlayerMoved`, `WorldReducer.kt`, `MapViewModel.kt`, `CanonicalDump.kt`, `lw_mirror/events.py` | travel increments the ledger; the dump can express it (desktop runs are unaffected) |
| C4.5 | `feat(core,android): a duel can be lost` | `PlayerState.health`, `MainActivity.kt` (which holds `TacticalDuelViewModel`), `TacticalCombatResolver` usage | opponent retaliates, player health is tracked and persisted, co-location is required |
| C4.6 | `fix(ai,android): dialogue uses the real player id and fresh lines` | `DialogueViewModel.kt` (`world.player.id`), `PregeneratedLines.kt` (key includes location + relationship), `OnDeviceChatModel.kt` (fail `pending` on release) | fixes A17/A29 and the hardcoded player id |

**Exit gate.** `check` green (incl. `androidApp` unit tests); a manual pass on an emulator: a trade in one tab is
visible in another, the world survives a process restart, travel is in the ledger, the duel can be lost.

**Rollback.** App-only; reverting the wave restores the previous (broken but working) behaviour.

### W5 — AI/Android hardening and toolchain (needs approval)

| # | Commit | Files | Notes |
|---|---|---|---|
| C5.1 | `fix(android): verify the model digest on every load` | `ModelDownloader`, `AndroidModelAssetManager` (wire it or delete it) | sha256 checked at attach/status; cache a verification stamp (size+mtime+digest) so launch is not slowed by hashing 345 MB every time |
| C5.2 | `fix(android): keep the model out of backups` | `AndroidManifest.xml`, new `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | `fullBackupContent`/`dataExtractionRules` exclude `models/`; decide `allowBackup` |
| C5.3 | `fix(android): validate the redirect target` | `AiController.openHttps` | follow redirects manually, re-check scheme + host on every hop |
| C5.4 | `build: Gradle 8.14, AGP 8.13, SDK 36, Compose refresh` | `gradle/wrapper/gradle-wrapper.properties`, `build.gradle.kts`, `androidApp/build.gradle.kts`, CI workflow | `[ask]` + `LW_ALLOW_DEPS=1`; Kotlin stays 2.4.20; removes the lint noise; target API 36 for Play |
| C5.5 | `feat(android): adaptive app icon` | `androidApp/src/main/res/**`, manifest | replaces `@android:drawable/sym_def_app_icon` |
| C5.6 | `chore(build): dependency verification metadata` | `gradle/verification-metadata.xml` | optional but recommended once C5.4 lands |

**Exit gate.** `check` green, APK assembles, CI green on the new SDK, Play target-API requirement satisfied.


### W6 — Gameplay realism (second, declared golden wave)

| # | Commit | Notes |
|---|---|---|
| C6.1 | `feat(core): hunger, exhaustion and night work` | food can fall below the floor and has consequences; NPCs sleep instead of working at night; the economy stops the 15-of-20-at-zero collapse (A23) |
| C6.2 | `feat(core): theft has consequences` | a theft creates a problem, a price effect and a quest; the README’s “prices and quests shift” becomes true |
| C6.3 | `feat(core): quests can be accepted and completed` | `QuestStatus.ACTIVE`, an investigation resolution path, problems cleared on completion |
| C6.4 | `feat(core): factions act` | `ChangeAllegiance`/`FactionHostilityDeclared` get producers, or the event types are deleted with their tests (D10) |
| C6.5 | `feat(core): a player steal action` | the `Steal` intent, the app action and the quest/price effects — or the README claim is removed instead |
| C6.6 | `docs: regenerate the architecture snapshots and correct the claims` | `c4/`, `dashboard/`, `forensics/`, `docs/reports/`, `README.md`, `CLAUDE.md`, `.claude/skills/*` | the snapshots match the current tree (no deleted `simulate_village.py`, no GGUF/HF references); the README claims match the code |

**Exit gate.** goldens re-recorded once more, mirror ported, 30/90/365-day economy reports attached to the PR (coin
distribution, deaths, rumours) so the balance change is visible rather than asserted.

### W7 — Performance and save format

| # | Commit | Notes |
|---|---|---|
| C7.1 | `perf(core): block-structured EventLedger` | append copies one block; `find` stays O(1); `all()` cached; `tools/ledger_tools.py` reads the new ledger shape |
| C7.2 | `perf(core): block-structured MemoryStore + pruning policy` | removes the O(n²) growth; documented caps |
| C7.3 | `perf(persistence): compact and optionally gzipped saves` | no `prettyPrint`; measured save/load times for 90/365-day worlds |
| C7.4 | `perf: measure on an emulator` | numbers recorded in `docs/reports/`, replacing the JVM-only measurements |

**Exit gate.** 365-day run ≤ 10 s on the JVM and ≤ 60 s on an emulator (or the numbers are recorded and accepted); the
digest constant is updated if the digest algorithm changed (the dump must not change).


## 5. Traceability matrix — every finding, and the commit that closes it

IDs: **R** = recon report, **A** = adversarial report. “Golden” marks commits that may change `tests/golden` or the
90-day fingerprint constant.

### 5.1 Kernel correctness

| ID | Finding | Closed by | Golden? |
|---|---|---|---|
| A1 | trade with an unknown seller destroys coins | C1.1, C2.2 | yes (default trades change) |
| A2 | a dead seller is paid | C1.3, C2.2 | no |
| A3 | a trade mints stock the seller lacks | C1.1, C2.2 | yes |
| A4 | theft removes from the owner, gives nothing to the thief | C1.1, C2.3 | yes (turmoil) |
| A5 | dead NPCs still work/trade/heal | C1.3 | no |
| A6 | `Npc.isAlive` vs `identity.isAlive` disagree | C1.3, C1.5 | no |
| A7 | re-applying a recorded event changes state twice | C1.2 | no |
| A8 | `Npc(x)` and `Player(x)` share relationship edges | C2.1 | yes (dump `R|` lines) |
| A9 | player travel is not an event | C4.4 | no (app-only) |
| A10 | the save gate validates almost nothing | C1.4, C1.5, C1.6 | no |
| A11 | a blank digest disables verification | C1.5 | no |
| A12 | the AI snapshot is global (G2) | C2.6 | no |
| A13 | a dead guard captain issues his own murder quest; unwitnessed deaths become public | C2.4 | yes (turmoil/feud) |
| A14 | completed quests never resolve their problems | C2.4 | yes |
| A15 | G3 lets lowercase invented claims through | D6 / C2.10 | no |
| A16 | causal links can point forward in time | C2.5 | yes (rumour links) |
| A17 | prepared AI lines ignore location and relationship | C4.6 | no |
| A18 | the fingerprint is a projection of the state | C2.7 | no (constant changes) |
| A19 | the canonical dump has the same blind spots | C2.7 | yes (format v2) |
| A20 | determinism tests compare only `npcs`/`time` | C3.1 | no |
| A22 | six tests codify the bugs | C3.4 | no |
| A23 | economy collapses (15/20 NPCs at 0 coins by day 365) | C6.1 | yes |
| A24 | the ledger is quadratic (42 s for 184,726 events) | C7.1, C7.2 | no |
| A25 | the default scenario exercises almost no systems | C2.12 (new scenario) | yes |
| A39 | stale docs (C4/dashboard/forensics/skills/counts/comments) | C0.8, C6.6 | no |
| R4 | the reducer is not idempotent | C1.2 | no |
| R3 | item-conservation bugs | C2.2, C2.3 | yes |
| R11 | unreachable features (no steal action, no faction producers, price repricing, `EntitiesMet` for one NPC only) | C6.2–C6.5 | yes |

### 5.2 App, AI and shipping

| ID | Finding | Closed by | Golden? |
|---|---|---|---|
| R1 | four private worlds, no tick, no save | C4.1, C4.2, C4.3 | no |
| R5 | shipping blockers: `targetSdk 34`, placeholder icon, model in backup scope | C5.2, C5.4, C5.5 | no |
| R8 | model integrity: size-only check, dead `AndroidModelAssetManager`, redirect host, hanging request | C5.1, C5.3, C4.6 | no |
| A26 | the model directory is inside the Auto Backup scope | C5.2 | no |
| A27 | a same-size model is trusted without its digest | C5.1 | no |
| A28 | redirects are not re-checked | C5.3 | no |
| A29 | a release leaves a request hanging for two minutes | C4.6 | no |
| R6 | determinism asserted more narrowly than documented | C3.1, C2.7 | no |

### 5.3 Tooling, CI and supply chain

| ID | Finding | Closed by | Golden? |
|---|---|---|---|
| R2 / A34 | CI never compares Kotlin output with the goldens | C0.1 | no |
| A33 | the pre-push “full” gate skips the determinism/golden tier | C0.4 | no |
| A35 | CI never enforces the dependency isolation gate | C0.2 | no |
| A36 | the invariant checker has holes | C0.5 | no |
| A37 | `verify.py` needs an AWS session | C0.3 | no |
| A38 | infra failures fail open (documented, not fixed — keep, but say so) | C0.4 (docstring) | no |
| A30 | the publish script exposes the token in argv | C0.7 | no |
| A31 | the secret guard misses common token shapes | C0.6 | no |
| A32 | actions/wrapper/dependencies are not pinned | C0.10, C5.4, C5.6 | no |
| R9 | toolchain drift (Gradle deprecation, AGP floor, Play API 36) | C5.4 | no |
| R10 | docs/tooling drift | C0.8, C6.6 | no |
| R7 | the default golden cannot see the social systems | C2.12 | yes |
| A41 | save size and JSON pretty-printing | C7.3 | no |


## 6. Backlog re-seed (paste-ready)

Replace the body of `docs/backlog.md`’s **Queue** with these items, in this order. Tags: `[ask]` = needs the user’s go-ahead,
`[human]` = needs the user’s hands, `golden` = changes simulation behaviour, so the goldens are re-recorded and
`lw_mirror` must match. Sizes follow the repo’s S/M/L convention.

**W0 — harness (no behaviour change)**

- [ ] **CI compares Kotlin output with the committed goldens** S. Evidence: `ci.yml:48`–`:51` never runs `--golden`; the
      check exists only in `stop_gate.py:156`. Verify: a deliberately wrong golden fails the CI job.
- [ ] **CI enforces the dependency isolation gate** S. Evidence: `check_invariants.py` computes the dependency diff
      only in `--staged`, and the pre-commit hook is not enabled. Verify: a PR adding `implementation(...)` fails CI.
- [ ] **`tools/verify.py --local` runs the whole gate without AWS** S. Evidence: `verify.py:84` returns BLOCKED without a
      session and `--no-remote` skips Gradle. Verify: `python3 -I tools/verify.py --local` is green on a JDK machine.
- [ ] **Pre-push gate runs the determinism/golden tier** S. Evidence: `stop_gate.decide` (`:90`–`:118`) skips
      `determinism_check`, which `decide_quick` runs. Verify: `tools/test_stop_gate.py` asserts the tier is called.
- [ ] **Invariant checker covers `Thread.`, `Math.random`, `TimeSource.Monotonic`, `kotlin.jvm`** S. Evidence: direct probes
      of `check_text` produce no finding. Verify: new cases in `tools/test_check_invariants.py`.
- [ ] **Secret guard knows more token shapes** S. Evidence: `bash_guard.py:35`–`:38` misses `hf_`, `sk-`, `xoxb-`, JWTs.
      Verify: new cases in `tools/test_phase0_hooks.py`.
- [ ] **Publish script keeps the token out of argv** S. Evidence: `publish_github.sh:27`, `:46`. Verify: `ps` shows no
      token during a dry run.
- [ ] **Docs mark the falsified claims as known issues** S. Evidence: `docs/reports/adversarial-2026-10-22.md` §2–§3.
      Verify: every claim listed there is annotated.
- [ ] **Characterisation tests for behaviour we intend to keep** M. Evidence: coins conserved, ledger ordered, replay
      equals live state. Verify: green, goldens unchanged.
- [ ] **Pin the Gradle distribution checksum** S. Evidence: `gradle-wrapper.properties` has none. Verify: wrapper
      validation passes.

**W1 — kernel contract (no behaviour change)**

- [ ] **Event pre-conditions + `InvalidEventException`** M. Evidence: the reducer trusts payloads (§2.1–§2.2 of the
      adversarial report). Verify: new `EventValidatorTest`; goldens unchanged.
- [ ] **Applying a recorded event twice changes nothing** S. Evidence: probe moved trust 0.5 → 0.3. Verify: the
      duplicate test now asserts the state, not just the ledger.
- [ ] **The dead do not work, trade or heal** M. Evidence: probe healed a killed merchant. Verify: new tests; goldens
      unchanged.
- [ ] **`WorldInvariants` validator** M. Evidence: `validateWorldIntegrity` checks only NPC resources. Verify: new
      tests; used by the save gate.
- [ ] **A save must carry a digest and consistent metadata** M. Evidence: blank digest accepted; `eventCount` unchecked.
      Verify: `SaveManagerTest`.
- [ ] **Replay rejects duplicate/out-of-order event ids** S. Evidence: A7. Verify: `SaveManagerTest`.
- [ ] **The 90-day fingerprint is a test constant** S. Evidence: `041e2593769b807b` is only in prose. Verify: the test
      passes; skip on slow CI (§7).


**W2 — one behaviour wave (`golden`, `[ask]`)**

- [ ] **Typed entity keys + save schema v4** M `golden`. Evidence: `Npc(x)`/`Player(x)` share an edge. Verify: probe
      test; mirror port; goldens re-recorded once at the end of the wave.
- [ ] **A trade needs a living seller who holds the item** M `golden`. Evidence: unknown seller destroys coins; items
      are minted. Verify: conservation tests; default run still trades.
- [ ] **A theft moves the item** M `golden`. Evidence: the stolen sword vanished. Verify: turmoil scenario now moves an
      item; mirror matches.
- [ ] **Quests need a living, informed issuer; problems resolve** M `golden`. Evidence: the dead guard captain issued
      his own murder quest; completed quests stayed active. Verify: new tests.
- [ ] **Causes point backwards** M `golden`. Evidence: theft #1 linked to theft #2. Verify: new test; mirror port.
- [ ] **The AI only sees what the NPC could know** M. Evidence: a tavern killing leaked into a square snapshot. Verify:
      new test; no golden impact.
- [ ] **Full-state digest + dump format v2** M `golden`. Evidence: belief/inventory changes left the fingerprint
      unchanged. Verify: the digest moves when belief confidence changes.
- [ ] **Grounding v2** M. Evidence: `"alden poisoned the well"` passed with no facts. Verify: new tests; watch the
      rejected-rate metric.
- [ ] **Mirror port of the new semantics** M `golden`. Evidence: the mirror must match byte for byte. Verify:
      `python3 tools/mirror_parity.py`.
- [ ] **A fourth golden scenario for the uncovered paths** M `golden`. Evidence: allegiance, flee, war, NPC↔NPC trade
      and `EntitiesMet` are unexercised. Verify: the new golden is covered by the mirror.
- [ ] **Re-record the goldens and the fingerprint** S `golden`. Verify: three `--golden` runs and `mirror_parity` green.

**W3–W5**

- [ ] **Replay and soak compare the whole state** M. Evidence: only `npcs`/`time` were compared. Verify: new tests.
- [ ] **Conservation and append-only tests over 30 days** M. Evidence: no such test existed. Verify: new tests.
- [ ] **Turn the codified-bug tests into spec tests** M. Evidence: six tests assert the bugs (§5 of the adversarial
      report). Verify: they now assert the corrected behaviour.
- [ ] **One shared world in the Android app** L. Evidence: four private worlds, no tick, no save. Verify: emulator pass.
- [ ] **The inventory comes from the world** M. Evidence: hardcoded `mapOf("bread" to 3, …)`. Verify: ViewModel tests.
- [ ] **Player travel is an event** M. Evidence: `world.copy(player = …)` with no event. Verify: ledger grows on travel.
- [ ] **A duel can be lost** M. Evidence: one-sided duel, no player health. Verify: emulator pass.
- [ ] **Verify the model digest on every load** M. Evidence: size-only `isInstalled`. Verify: same-size/wrong-bytes test.
- [ ] **Keep the model out of backups** S. Evidence: `allowBackup=true`, no rules. Verify: manifest + rules.
- [ ] **Validate the redirect target** S. Evidence: `openHttps` follows redirects blindly. Verify: test.
- [ ] **Toolchain: Gradle 8.14, AGP 8.13, SDK 36, Compose refresh** `[ask]` M. Evidence: Gradle deprecation warning;
      AGP 8.5.2 is the KGP 2.4.20 floor; Play needs API 36. Verify: CI green, APK builds.
- [ ] **Adaptive app icon** S. Evidence: `@android:drawable/sym_def_app_icon`. Verify: APK launcher icon.

**W6–W7 (optional, `[ask]`)**

- [ ] **P5 gameplay realism** `[ask]` `golden` L. Evidence: 15/20 NPCs at 0 coins by day 365; starvation impossible;
      every death is an unresolved homicide. Verify: 30/90/365-day economy report attached.
- [ ] **Block-structured ledger and memory store** M. Evidence: 184,726 events in 42.4 s. Verify: measured before/after.
- [ ] **Compact saves** M. Evidence: pretty-printed JSON for 184k events. Verify: measured save/load time and size.

## 7. CI and tooling specification

```yaml
# .github/workflows/ci.yml (target shape)
jobs:
  invariants-and-python:
    steps:
      - run: python3 tools/check_invariants.py --all
      - run: python3 tools/check_invariants.py --base origin/main   # isolation gate on the diff
      - run: python3 -m unittest discover -s tools -p 'test_*.py'
  kotlin-check:
    steps:
      - run: ./gradlew check --no-daemon --console=plain
      - run: ./gradlew :desktopApp:run --args='10 0 --golden=tests/golden/default-10d.txt.gz'
      - run: ./gradlew :desktopApp:run --args='10 0 --scenario=turmoil --golden=tests/golden/turmoil-10d.txt.gz'
      - run: ./gradlew :desktopApp:run --args='10 0 --scenario=feud --golden=tests/golden/feud-10d.txt.gz'
      - run: ./gradlew :game-core:jvmTest --tests '*LongRunFingerprintTest'   # 90-day constant
      - run: ./gradlew :androidApp:assembleDebug --no-daemon --console=plain
```

New `check_invariants.py` rules (all line-based, so they must stay cheap):

| Rule id | Pattern | Why |
|---|---|---|
| `jvm-lang-class` | `Thread.`, `Runtime.`, `ProcessBuilder` | `java.lang` needs no import, so the import rule misses it |
| `unseeded-random-qualified` | `kotlin.random.Random.` | the negative lookbehind lets the qualified form through |
| `monotonic-clock` | `TimeSource.Monotonic`, `TimeSource.Monotonic.markNow()` | common API, but not deterministic |
| `jvm-inline-import` (warning) | `import kotlin.jvm.` | allowed today for `@JvmInline`, but should be visible in the report |

`verify.py --local` contract: run `check_invariants --all`, the Python tests, `./gradlew check`, the three goldens and the
90-day fingerprint locally; exit 0/1 only, never 2 (2 stays “AWS blocked”).


## 8. Verification matrix

| Command | Proves | Used by |
|---|---|---|
| `python3 tools/check_invariants.py --all` | no G1/G8/G9 violation in the tree | every commit |
| `python3 tools/check_invariants.py --base origin/main` | no unapproved dependency added | W0, CI |
| `python3 -m unittest discover -s tools -p 'test_*.py'` | tooling, mirror parity, hook behaviour | every commit |
| `python3 tools/mirror_parity.py` | the Python port still matches the goldens | W2 (per area), every commit after a re-record |
| `./gradlew :game-core:jvmTest` | kernel behaviour, including the new invariant/conservation tests | every commit |
| `./gradlew :game-persistence:jvmTest` | save/migration/digest behaviour | W1, W2, W4 |
| `./gradlew :androidApp:testDebugUnitTest` | view models, AI setup, asset integrity | W4, W5 |
| `./gradlew :desktopApp:run --args='10 0 --golden=…'` ×3 | Kotlin behaviour still matches the goldens | W0 (CI), W2 (after re-record) |
| `./gradlew :desktopApp:run --args='90 0'` + fingerprint | long-run determinism and the economy drift | W2, W6 |
| `./gradlew check` | everything above plus lint | every wave |
| `python3 -I tools/verify.py --local` | the whole gate on one machine, no AWS | release of each wave |
| emulator pass (manual) | the app world is shared, persists, and the duel can be lost | W4, W5 |

**CI budget.** The 90-day fingerprint test takes ~1.3 s of simulation plus JVM start-up, so it fits; the 365-day run
(42 s) does not belong in CI — keep it as a nightly/manual measurement.

## 9. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| The mirror port drifts from the Kotlin kernel | high | high (CI red, or worse: a false green) | port one area per commit, keep `mirror_parity` green per area, re-record once at the end |
| The golden re-record hides a behaviour change | medium | high | record *why* each expected diff appears in the PR; run the 90-day fingerprint before/after and diff the census |
| The new validator rejects events the engine produces | medium | medium | treat each rejection as a producer bug to fix; never weaken the validator to make a test pass |
| Grounding v2 rejects too much model output | medium | low (worse tone, not wrong facts) | keep the kernel line as fallback; track `DialogueStats.rejected`; grow the curated vocabulary only with a test per word |
| Quest semantics change the feel of the game | high | medium | keep “no public quest for an unwitnessed death”, but give the *knowing* NPCs a path to spread it (a rumour → a quest) |
| Schema v4 breaks existing saves | medium | medium | ship the v3→v4 migration with a test that loads a v3 fixture (the migrator already has v1/v2 paths) |
| AGP/Gradle/SDK bump breaks the Android build or lint | medium | medium | do it in its own commit after W2, with the previous toolchain one `git revert` away |
| Performance work changes the digest | medium | low | keep the digest algorithm stable in W7; only the *constant* may change, never the dump |
| Scope creep: the plan grows instead of shrinking | high | medium | one wave = one PR; new findings become new backlog items, not additions to the current wave |
| The plan stalls on approvals | medium | medium | W0–W4 need no approval; ask once per wave (§10) instead of per commit |

## 10. What needs the user’s OK before it can start

1. **The W2 behaviour wave** — it changes simulation behaviour and re-records the goldens (`[ask]`, `golden`).
2. **Dependency and toolchain changes** (C5.4 Gradle/AGP/Compose/SDK 36, and any new library) — `LW_ALLOW_DEPS=1` plus an
   explicit yes (`[ask]`).
3. **Deleting stale artefacts** — `c4/c4-snapshot-v6.md`, `dashboard/`, `forensics/`, `prompts/`, the empty
   `safety-gates/`, dead code (`game-ui`→`game-ai`, `AndroidModelAssetManager`, `DeterministicAiFallback` helpers,
   `RelationshipGraph.remove`) — the skill says to ask before deleting files the user made.
4. **Behaviour choices that change what players see** — grounding strictness (D6), quest issuance (D5), and the realism
   wave W6.
5. **A phone test** — the on-device AI smoke test and the W4/W5 emulator passes need the user’s hands (`[human]`).


## 11. Size and sequencing

| Wave | Size (S/M/L) | Rough effort | Golden re-records | Blocked on |
|---|---|---|---|---|
| W0 | 8×S, 2×M | 2–3 days | 0 | – |
| W1 | 4×M, 3×S | 3–4 days | 0 | W0 (needs the local gate) |
| W2 | 8×M, 1×L | 8–12 days | 1 (+1 new scenario file) | W1, user approval |
| W3 | 4×M | 2–3 days | 0 | W2 |
| W4 | 1×L, 3×M, 2×S | 6–8 days | 0 | W2 |
| W5 | 3×M, 3×S | 3–4 days | 0 | W2, user approval (deps) |
| W6 | 5×M, 1×L | 5–8 days | 1 | W2, user approval |
| W7 | 3×M | 3–4 days | 0 | W2 |

Total ≈ 32–46 engineer-days for W0–W5, plus 8–12 optional for W6/W7. The critical path is **W1 → W2 → (W3, W4, W5)**;
W0 can be prepared in parallel with W1 but should land first, because it is what proves W1 did not break anything.

## 12. Definition of Done (per wave)

- [ ] The wave’s commits are in one branch/PR, each green, with `type(scope): what and why` messages.
- [ ] Every finding the wave claims to close has a **permanent regression test** (not a probe that gets deleted).
- [ ] `./gradlew check` green; Python tests green; invariants 0 errors.
- [ ] Goldens: unchanged (unless the wave is a declared `golden` wave, in which case they are re-recorded **once** and the
      mirror matches byte for byte).
- [ ] `CLAUDE.md` (fingerprints, known issues), `docs/backlog.md`, `docs/handoff/last.md` and `docs/open-questions.md`
      updated in the same commit as the change.
- [ ] New public API in `game-core` (typed keys, `WorldInvariants`, `StateDigest`, `EventValidator`) has KDoc explaining
      the invariant it enforces.
- [ ] No new AI import in `game-core` (G1), no platform code in `commonMain` (G9), no unseeded randomness or wall clock
      (G8) — the checker enforces this.
- [ ] An ADR is written for each design decision in §2 that changes a public contract.

## 13. Out of scope for this plan

- iOS: the iOS targets stay declared-only; a macOS CI job is a separate decision (the G8 wording must be softened
  meanwhile).
- Multiplayer, networking, cloud saves.
- New content (new NPCs, biomes, quest lines) beyond what W6 needs to make the existing systems work.
- Rewriting the Python mirror in another language, or dropping the mirror — it is the only independent check on the
  kernel’s determinism.
- The art pipeline, the sprite cron and the story text.

## 14. How to start (next action)

1. Get approval for W2 and for the dependency bumps (§10).
2. Re-seed `docs/backlog.md` from §6 and open the first item: **CI compares Kotlin output with the committed goldens**
   (C0.1) — it is small, it needs no approval, and it is what will protect every later wave.
3. Work the queue one item per pass with `feature-loop`, verifying with `python3 -I tools/verify.py --local` once W0.3
   lands (until then, use the remote gate or a local JDK).

## 15. Progress log

| Item | State | Commit |
|---|---|---|
| C0.1 CI goldens | the CI half is delivered as `docs/patches/ci-goldens-and-dependency-gate.patch` (the pushing App token has no `workflows` scope); the step itself is written and was verified locally, including that a corrupted golden fails it | — |
| C0.2 dependency gate | tool half done (`check_invariants.py --base <rev>`), CI half in the same patch | `312c1af` |
| C0.3 `verify.py --local` | done — the whole gate (invariants, tool tests, Gradle check, three goldens) now runs on one machine with no AWS session | `6c447ed` |
| C0.4 pre-push determinism tier | done — `decide()` now proves G8 and the goldens; an infra failure still only warns | `1c4b654` |
| C0.5 checker rules | done — four new rules; the tree now reports 6 `kotlin.jvm` visibility warnings (no errors) | `d48ccd3` |
| C0.6 secret guard | done — more token shapes, placeholders ignored, staged content scanned | `0967b8c` |
| C0.7 publish script | done — the token no longer appears in `ps` | `0e45222` |
| C0.8 docs mark the falsified claims | done (CLAUDE.md known issues, README status) | `8cb209d` |
| C0.9 characterisation tests | done — coins conserved over 30 days, a strictly ordered ledger, and a replay that rebuilds the whole world | `8128cd6` |
| C0.10 Gradle checksum | done | `890a356` |
| C1.2 idempotency | done — a recorded event applies once (goldens unchanged) | `c704c76` |
| C1.3 life guards | done — the dead do not work, trade or heal (goldens unchanged) | `c704c76` |
| C1.4 `WorldInvariants` | done | `3e6f2fa` |
| C1.5 save gate | done — the world's own rules, a required digest and metadata cross-checks | `240a342` |
| C1.6 replay refuses a repeated id | done | `240a342` |
| C1.7 90-day fingerprint test | done — the documented fingerprint is now a test, not a sentence in a file | `6a82338` |
| C1.1 `EventValidator` | **deferred to W2**: the pre-conditions are only worth enforcing once the stock and life rules exist (W2), and the reducer now refuses the dead cases directly. Throwing in the reducer before that would break tests that deliberately apply odd events. | — |
| C2.1 typed entity keys | **done in W2a** — edges are `npc:merchant->player:player`, the reducers resolve parties by kind, save schema v4 migrates v1/v2/v3; the dump keeps the old edge form, so the goldens are unchanged | `89b8d05` |
| C2.6 knowledge-scoped snapshot | **done in W2a** — `knownEvents(npc)`: what the NPC witnessed, did, or saw happen in public where it stands; the advisor gets that instead of the world's last ten events | `158971a` |
| C2.7 full-state digest | **done in W2a** — `StateDigest` sees beliefs, memories, items, traits, feelings, the player, the treasury, the quests, the places and the ledger; the 90-day fingerprint is now `e1aea58880101b51` | `86fae5a` |

**W2a exit gate (verified 2026-10-22):** the three goldens are byte-identical, the 90-day run is unchanged
(45,615 events, ~1.3 s) and only its fingerprint moved, `python3 -I tools/verify.py --local` is green, and the Python
mirror still matches the goldens (the mirror keys relationships by kind as well).

### W2b — the behaviour half (one golden re-record)

| Item | State | Commit |
|---|---|---|
| C2.2 trade rules | **done** — a trade needs two parties that exist, a living seller that holds the item and a buyer that can pay; the merchants keep stock (3 bread, 6 goods) | `55d9ee5` |
| C2.3 theft moves the item | **done** — the item goes from the owner's hands to the thief's (player victim loses it, player thief gains it); the Turmoil scenario gives the blacksmith the blade | `55d9ee5` |
| C2.4 quest issuers and problem lifecycle | **done** — only a living NPC that *knows* asks for an investigation (the guard captain no longer issues a quest for his own murder, a murder nobody saw produces no quest), one problem per victim, and a settled investigation stops being an open problem | `55d9ee5` |
| C2.5 causes point backwards | **done** — `CausalLinks.originOf` is bounded by the belief's time | `f68e250` |
| mirror port + one re-record | **done** — the mirror follows every rule and the three goldens were re-recorded once | `c15f053` |

**W2b exit gate (verified 2026-10-22):** `python3 -I tools/verify.py --local` is green (invariants, 98 tool tests, Gradle
check, all three goldens), the Python mirror matches the new goldens byte for byte, and the 90-day default run is
unchanged (45,615 events, ~1.3 s) with the fingerprint `7ae29cd5c158da35` (the digest sees the whole world, so it moved
with the behaviour).

**Still open in W2:** C2.12 a fourth scenario that exercises allegiance, flee, war and NPC-to-NPC trades, and the dump v2
format (the state section of the dump - best written as a separate state dump so the goldens do not have to be
re-recorded again).

### W2 leftovers, done (2026-10-22)

| Item | State | Commit |
|---|---|---|
| Grounding v2 | **done** — every word of a reply must be known (the facts, the speaker and place, or a curated small-talk vocabulary), so an invented lower-case sentence is dropped and the kernel line is used | `d81eafd` |
| `EventValidator` | **done** — one object holds the pre-conditions (the entities an event names must exist, the dead must not act, a trade needs a living seller that holds the item); the reducer and the Python mirror ask it the same question; an event that fails is not recorded at all | `578cff1` |
| C5.4 toolchain | **done** — Gradle 8.14.6 (sha256-pinned), AGP 8.13.0, compileSdk/targetSdk 36, Compose BOM 2026.06.01. AGP downloads the missing platform 36 itself, so CI needs no workflow change (`docs/patches/ci-android-sdk-36.patch` stays as a fallback). The goldens and the 90-day fingerprint are unchanged | `799cde6` |

### W4 — one world in the app (branch `feature/w4-app-world`)

| Item | State | Commit |
|---|---|---|
| C4.1 one shared world | **done** — a `WorldStore` owns the single `WorldState`; the four screens read and write it instead of keeping private copies | `6728870` |
| C4.2 the world ticks and is saved | **done** — the store ticks an hour at a time while the app is in front, and saves/restores the world (`SaveManager` envelope in `filesDir`) | `6728870` |
| C4.3 the inventory comes from the world | **done** — the counts are `world.player.inventory`, the catalog is refreshed, and you cannot sell what you do not have | `6728870` |
| C4.4 player travel is an event | **done** — `GameEvent.PlayerMoved` through the reducer, so a replay reproduces where the player stands (the mirror knows it too) | `e6b2e80` |
| C4.5 a duel can be lost | **done** — the player has health in the world, the opponent answers with a counter-blow (`GameEvent.PlayerAttacked`), and the duel screen shows the wounds; both fighters must be in the same place | `77ecee6` |
| C4.6 dialogue and AI | **done** — the dialogue addresses the world's player id, the prepared-line cache carries the place and the facts, and a released request fails at once | `68c1521` |
| W5.1 model digest on load | **done** — the installed model is verified by sha256 (a stamp file keeps the common case free); a same-size file no longer passes | `68c1521` |
| W5.2 backup rules | **done** — `fullBackupContent` and `dataExtractionRules` exclude the 345 MB model directory | `68c1521` |
| W5.3 redirect target | **done** — redirects are followed only to https hosts on huggingface.co | `68c1521` |
| W3.1 replay and soak compare the whole world | **done** — factions, relationships, economy, quests, the player and the ledger are compared too; they all match, so the ledger really does rebuild the world | `53206d7` |
| W3.2 item conservation | **done** — the coins *and* the objects are pinned over thirty days: a trade, a theft and an inheritance all move items, so the total must be the same afterwards | `ed0a48b` |

**W1 exit gate (verified 2026-10-22):** the three goldens are byte-identical, the 90-day fingerprint is unchanged, the
full local gate (`python3 -I tools/verify.py --local`) is green, and a 30-day world still saves through the hardened
gate.


