# Adversarial pass — 2026-10-22

Follow-up to `docs/reports/recon-2026-10-22.md`. The recon asked “does it work?”; this pass asked “where does it lie?” I
wrote temporary probes that assert the **promises** (README, invariants G1–G10, tests) rather than current behaviour,
ran them, and removed them again. Nothing in the source tree was changed.

## 0. Method

- 11 temporary Kotlin probes in `game-core/src/commonTest` and 3 in `game-persistence/src/commonTest`; each asserts a
  stated invariant or README claim. Run with `:game-core:jvmTest --tests AdversarialProbeTest` and
  `:game-persistence:jvmTest --tests AdversarialPersistenceProbeTest`.
- Additional black-box checks: 365-day run, second 90-day run, Python 90-day parity, Turkish locale + Kiritimati
  timezone, live Hugging Face HEAD requests for both model pins, a secret scan, and direct probes of
  `tools/check_invariants.py`.
- Probes were deleted afterwards; `git status` is clean apart from this report.

## 1. Probe scorecard

| # | Probe (what the project promises) | Actual result | Verdict |
|---|---|---|---|
| 1 | A trade with an unknown seller conserves coins | buyer loses **10 coins**, nobody gains them | **FAIL** |
| 2 | A dead seller cannot be paid | conservation holds, but the corpse is credited and the buyer gets the item | **PARTIAL** — probe asserted only conservation; the missing alive check is in the code |
| 3 | A seller cannot sell stock it does not hold | buyer receives `bread`; seller held none | **FAIL** |
| 4 | A theft moves the item to the thief | owner loses the sword, thief’s inventory is unchanged | **FAIL** |
| 5 | Replaying a recorded event is idempotent | trust `0.7 → 0.5 → 0.3` after the same event twice | **FAIL** |
| 6 | A dead NPC cannot be changed by later routines | dead merchant’s health `0 → 10` | **FAIL** |
| 7 | A location snapshot contains only what is visible there (G2) | snapshot at `village_square` exposes a killing at `tavern` | **FAIL** |
| 8 | NPC and player identities are distinct in relationships | `Npc(x)` and `Player(x)` share one edge (`trust 0.5`) | **FAIL** |
| 9 | G3 rejects an invented claim | `"alden poisoned the well…"` passes with no facts | **FAIL** |
| 10 | A completed investigation stops being an active problem | `HOMICIDE` problem remains | **FAIL** |
| 11 | A causal link points at the event that caused the belief (G6) | belief about theft #1 links to theft #2 | **FAIL** |
| 12 | The fingerprint changes when a belief changes | strong vs weak belief produce the same fingerprint | **FAIL** |
| 13 | The fingerprint changes when player inventory changes | same fingerprint with/without `bread` | **FAIL** |
| 14 | An envelope without a digest is rejected | blank `deterministicDigestHex` accepted | **FAIL** |

Positive results from the same pass:

- 90-day default run twice: 45,615 events, fingerprint `041e2593769b807b` both times (wall 1.29 s / 1.36 s).
- Python mirror for **90 days** default: 45,648 dump lines, **0 differences** (not just the committed 10-day goldens).
- Same 10-day golden under `-Duser.language=tr -Duser.country=TR -Duser.timezone=Pacific/Kiritimati`: `Golden: OK`.
- Live Hugging Face HEAD requests: both pinned models still report the exact catalog size and sha256
  (`344,671,744` / `03e7…2856`, `977,184,032` / `2a72…5de8`).
- `./gradlew check` passes; no secrets found by the repo’s own pattern plus `sk-` / `hf_` / private-key scans.
- 365-day default completes (20/20 alive, no crashes) — but see §5 for what it reveals.

## 2. Confirmed defects

### 2.1 Coins and items are not conserved

**2.1.1 A trade with an unknown seller destroys coins.** `applyTrade` computes `paid` from the buyer, debits it, then
credits a seller only if one exists (`WorldReducer.kt:369`–`:410`). With `buyer=apprentice`, `seller=ghost`, `price=10`,
the probe measured **total coins −10.0**. This is reachable from any caller of the public reducer and breaks the
README’s “nothing minted or destroyed” claim. The existing tests cover an unknown *buyer* only
(`EconomyAndQuestTest.trade_with_unknown_buyer_does_not_pay_seller`), not the mirror case.

**2.1.2 A dead seller is paid.** There is no `isAlive` check in `applyTrade`; a `TradeCompleted` after `NpcKilled`
credits the corpse and transfers the item. The probe’s conservation assertion passed only because the corpse is still an
NPC in the map — the semantic invariant (the dead cannot trade) failed. `applyRationPurchase` has the same omission.

**2.1.3 A trade mints stock the seller never held.** The buyer branch adds the item (`WorldReducer.kt:383`), while the
seller branch does a list `minus` (`:398`) that is a no-op when the item is absent. The default merchant starts with
`ResourceState.items = emptyList()` (`Npc.kt:81`–`:86`), so every `Intent.Trade(world.player.id, ItemId("goods"))`
sells goods from nowhere. The 90-day default run has six trades, i.e. six minted `goods`; the 365-day run has the same
six. `DeathCascadeTest.npc_to_npc_trade_does_not_pay_the_player` exercises this exact path and only checks the player’s
coins, so the mint is invisible to the suite.

**2.1.4 Theft removes nothing from the owner and gives nothing to the thief.** `applyTheft` updates witnesses and
subtracts from an NPC owner (`WorldReducer.kt:219`–`:256`), but never credits the thief. A theft by the player is a
no-op for both purses and inventories. In `TurmoilScenario.seed` the “stolen” `iron_sword` is taken from a blacksmith who
starts with no items (`TurmoilScenario.kt:30`–`:36`), so the scenario’s theft is a pure witness event: no item moves,
no price changes, no quest is created. The README’s “steal bread and a witness remembers, tells a friend, and trust,
prices and quests shift” describes behaviour that is not wired: there is no `Steal` intent, no theft action in the app,
and `ItemStolen` has exactly one producer (the Turmoil seed).

**2.1.5 A dead NPC can still work, trade and heal.** `applyRoutineCompleted` starts with
`val npc = state.npcs[event.npc] ?: return state` and never checks `isAlive` (`WorldReducer.kt:545`–`:557`). The probe
healed a killed merchant from 0 to 10 with a later routine. `applyTrade`, `applyRationPurchase`, `applyTheft`,
`applyAllegianceChange`, `applyRumor`, `applyPriceAdjustment` and `applyShopInherited` likewise lack alive guards
(`applyShopInherited` checks existence, not life). A malformed or replayed queue can therefore resurrect a corpse’s
resources; `SaveManager.validateWorldIntegrity` will then reject `!identity.isAlive && health > 0`
(`SaveManager.kt:188`), but only at save time, after the in-memory state is already broken.

**2.1.6 `Npc.isAlive` and `identity.isAlive` are two truths that can disagree.** `Npc.isAlive` is
`identity.isAlive && !resources.isDead` (`Npc.kt:116`). An `NpcAttacked` that brings health to zero without the
`AttackEvents` wrapper leaves `identity.isAlive = true` and `isAlive = false`; `SaveManager.validateWorldIntegrity`
checks only the opposite combination (dead identity with positive health), so this state is accepted by the save gate.
The same split exists between `ResourceState.isStarving` (`food <= 0`) and the 0.5 food floor.


### 2.2 Event sourcing, idempotency and identity

**2.2.1 Duplicate events change state twice.** `DefaultWorldReducer.apply` checks the ledger only to decide whether to
*record* the event, then always executes the branch (`WorldReducer.kt:29`–`:36`). Applying the same
`RelationshipShifted` twice moved trust `0.7 → 0.5 → 0.3`; the probe’s expected idempotency failed.
`applying_the_same_event_twice_records_it_once` asserts only `events.size` (`WorldReducerTest.kt:42`–`:47`), and the
double-kill test deliberately pins the non-idempotent cascade (`:438`–`:443`). A retry, a duplicated queue entry, or a
save with a reused event id corrupts state while the ledger still looks clean.

**2.2.2 Entity identity collides across kinds.** `RelationshipGraph.key` is
`"${from.identifier}->${to.identifier}"` (`Relationship.kt:73`), discarding the `EntityId` variant. A probe set an
`Npc(x) → Npc(y)` trust of 0.5 and read it back as `Player(x) → Player(y)` — same edge. This is not a theoretical key
choice: `applyTheft` reconstructs the owner as `NpcId(event.owner.identifier)` (`WorldReducer.kt:244`), and `applyTrade`
does the same for buyer and seller (`:373`–`:374`). A player named like an NPC, a faction named like a location, or any
future mixed-type edge resolves to the wrong entity. `isPlayer` compounds it by accepting any entity whose *identifier*
equals the player id (`:441`–`:442`), regardless of its type.

**2.2.3 Player travel is not an event.** `MapViewModel.travelToSelected` mutates the state directly with
`world.copy(player = updatedPlayer)` (`MapViewModel.kt:54`–`:55`), then may append an `EntitiesMet` for the first
co-located NPC (`:62`). No `PlayerMoved` event exists, so replaying the ledger cannot reproduce the player’s location.
This directly contradicts “Every state-altering action must emit a corresponding `GameEvent`” (CLAUDE.md) and “Everything
is an event” (README). The 90-day fingerprint and `CanonicalDump` also omit `player.currentLocation`, so the missing
event is invisible to every golden.

**2.2.4 The save gate validates almost nothing.** `SaveManager.validateWorldIntegrity` checks only NPC map keys, NaN and
negative resources, and one dead/health combination (`SaveManager.kt:174`–`:193`). It does not check:
unique/monotonic event ids or `nextEventNumber`; that every event actor/owner/victim exists; that relationships connect
known entities; that quest issuers/targets exist or are alive; that faction leaders are members; that dead NPCs have zero
health and living NPCs positive health; or that the coin total is conserved. A save with duplicate event ids, a stale
`nextEventNumber`, or a quest issued by a corpse passes. `SaveEnvelope.eventCount`, `savedAtTick` and `stateHash` are
stored but never compared with the decoded world.

**2.2.5 A blank digest disables digest verification.** In the v3 branch, the digest is checked only when it is non-blank
(`SaveManager.kt:83`–`:88`). `SaveEnvelope.deterministicDigestHex` defaults to `""`, so a forged v3 envelope can simply
omit it; the probe’s “blank digest must not be trusted” failed. Combined with the missing ledger/state checks above, the
“verified save” story is much weaker than the README/CLAUDE.md wording suggests.


### 2.3 G2, G3 and G6: the AI and quest layers read the world’s mind

**2.3.1 The AI snapshot is global.** `WorldState.snapshotFor(locationId)` filters *present NPCs* by location but builds
`recentEvents` from `events.all().takeLast(10)` (`WorldState.kt:252`–`:266`). `AiAdvisedDecisionEngine` passes that
snapshot to every advisor (`AiAdvisedDecisionEngine.kt:47`–`:56`). The probe put a killing at `tavern`, snapshotted
`village_square`, and found the killing in `recentEvents`. An AI can therefore choose or explain an action using events
the NPC did not witness, and the recorded `AiAdviceRecorded` explanation can carry that knowledge into the ledger. G2 is
violated even though the action space is still generated from local candidates.

**2.3.2 A dead guard captain issues the investigation of his own murder.** `ProceduralQuestEngine.deriveActiveProblems`
reads every death from the global ledger (`WorldState.kt:84`–`:96`), and `generateOpportunities` hard-codes
`issuer = guard_captain` for `HOMICIDE` (`:143`–`:160`), with no belief or alive check. `SimulationEngineTest` already
pins this: `current_behaviour_any_death_dispatches_one_murder_quest_from_the_guard_captain` kills the guard captain and
then asserts the quest’s issuer is that same dead guard (`SimulationEngineTest.kt:321`–`:333`). A completely unwitnessed
killing also produces a public quest, contradicting the README’s “a killing nobody saw stays a mystery”. This is both a
G2 leak (institutional omniscience) and a G4 failure (dead actors keep acting).

**2.3.3 Completed quests never resolve their problems.** `deriveActiveProblems` does not look at `activeQuests` status;
it re-derives a `HOMICIDE` from every historical death forever. The probe marked `quest_death_farmer_elena` `COMPLETED`
and `activeProblems()` still returned `problem_death_farmer_elena`. The map’s active-crisis count therefore cannot fall
when an investigation is completed. Only scarcity quests have a completion path at all; homicide/war quests remain
`AVAILABLE` forever.

**2.3.4 G3 is lexical, not semantic, and lowercase lies pass.** `GroundingCheck.claimTokens` treats only tokens with a
digit/underscore or an initial capital as claims (`GroundingCheck.kt:33`–`:38`). The probe asked it to reject
`"alden poisoned the well and plans to blame the miller"` with **no facts**; every word is lowercase, so it passed.
`DialogueGroundingTest` intentionally asserts that generic lowercase lines such as `"Aye, it is cold."` are grounded
(`DialogueGroundingTest.kt:54`–`:71`, `:85`), and `GroundedDialogueVoice` additionally allows the speaker’s name
(`GroundedDialogueVoice.kt:117`–`:122`). A model can therefore fabricate arbitrary lowercase claims, including
“I killed the merchant”, as long as it uses no capitalised proper noun or number. The README’s “anything else is
dropped” is false for that class of output.

**2.3.5 Causal links can point forward in time.** `CausalLinks.originOf` returns the **most recent** event matching a
proposition’s fields, with no timestamp filter (`CausalLinks.kt:18`–`:32`). The probe recorded a bread theft at tick 10
and another at tick 100, then asked for the origin of a belief about the first; it got event #2. A rumour can therefore
be causally linked to a later crime, which G6 (“backward-traceable”) forbids. The same pattern affects `deathOf` for
duplicate deaths, `PriceChanged` for any price event of that item, and `ShopInherited` for any inheritance by that heir.

**2.3.6 Prepared AI lines ignore location and relationship.** `PregeneratedLines.keyOf` hashes the NPC id, sanitized
topic and speakable facts, but not `LineRequest.location` or `LineRequest.relationship` (`PregeneratedLines.kt:50`–`:52`).
The prompt itself includes both (`GroundedDialogueVoice.kt:95`–`:100`). A line written when the NPC was hostile at the
tavern is served after the player has become trusted and moved to the square. `PregeneratedLinesTest` covers NPC/topic/
facts but not these two fields.


### 2.4 What the green suite cannot see

**2.4.1 The fingerprint is a projection, not the state.** `SaveManager.computeDeterministicFingerprint`
(`SaveManager.kt:110`–`:145`) does not include belief content/confidence/source (only a count), memory content (only a
count), traits, goals, items, emotional state, the player (coins, inventory, location), treasury, trade volume,
scarcity, quests or faction leadership/members. The probe changed a belief’s confidence from 0.9 to 0.1 and the
fingerprint stayed `4ebe9c52b7edf7e6`; adding `bread` to the player’s inventory left it `07c123c24e946669`.

**2.4.2 The canonical dump has the same blind spots.** `CanonicalDump`’s NPC line prints alive/location/health/coins/
food/fear/anger/grief, **belief count** and **memory count** (`CanonicalDump.kt:50`–`:56`); it never prints belief
content, traits, goals, items, trust/hope/guilt/suspicion/pride, or the player’s location/inventory. The economy section
prints prices/scarcity/volume/treasury and the player line prints only coins (`:58`–`:63`). A belief-source change, an
item transfer, a trait change, a completed quest’s issuer, or a player move can therefore leave every golden
byte-identical. This is why the theft and minting bugs above survive all three scenarios.

**2.4.3 The determinism tests are narrower than their names.** `ReplayDeterminismTest` compares only `time` and `npcs`
between live and replayed states (`ReplayDeterminismTest.kt:26`–`:29`), and the 90-day soak compares only `npcs` between
two runs (`:39`–`:45`). A replay that loses relationships, quests, economy or the ledger still passes. The fingerprint
assertion in `SaveManagerTest` has the same blind spots as §2.4.1.

**2.4.4 The default scenario is not a social simulation.** The 90-day default census is 38,331 routines, 4,320
relationship shifts, 2,154 price changes, 804 rations, 6 trades and **0** rumours, deaths, attacks, thefts, quests or
allegiance changes; the 365-day run has the same zero social events. Only the seeded turmoil/feud goldens touch death,
theft, rumours or combat, and only for 10 days. A regression in most advertised systems is invisible to the default
golden.

**2.4.5 Tests that codify the bugs.** These are not neutral coverage gaps; they assert the wrong behaviour:
`WorldReducerTest` ledger-only duplicate check (`:42`–`:47`); `SimulationEngineTest` dead guard-captain issuer
(`:321`–`:333`); `DeathCascadeTest.npc_to_npc_trade_does_not_pay_the_player` (`:95`–`:99`) which never checks stock or
conservation; `DialogueGroundingTest` accepting generic lowercase claims (`:54`–`:71`); `ViewModelTests` inventory
counts that only exercise the local map (`:78`–`:96`); `PregeneratedLinesTest` omitting location/relationship from the
key (`:33`–`:44`). Fixing the code requires changing these tests first.

### 2.5 Long-run behaviour and cost

**2.5.1 The economy is closed but not stable.** The 365-day default run ends with **15 of 20 NPCs at 0 coins** (median
0), while the baker and brewer hold 573 each; treasury 35, player 10. Total coins still sum to 1300, so conservation
holds in the absence of the unknown-seller bug, but the distribution collapses. The 90-day run had only three NPCs at
0 and a median of 60.875; the drift is monotonic and much worse than the 90-day snapshot suggests.

**2.5.2 The ledger is quadratic.** `EventLedger.append` does `eventsList + event` (`EventLedger.kt:36`–`:43`), copying
the whole history on every event. Measured: 90 days = 45,615 events / **1.29 s**; 365 days = 184,726 events / **42.4 s**.
That is 4.05× the events and 32.9× the time — clearly superlinear. `MemoryStore.add` (`Memory.kt:67`) and
`Relationship.sharedEvents` appends have the same shape. The 365-day run still fits in memory, but a phone tick loop
plus a pretty-printed JSON save of 184k events will not be pleasant.

**2.5.3 The default world never exercises most systems.** The 365-day run has 0 attacks, 0 deaths, 0 rumours, 0
thefts, 0 allegiance changes, 0 wars and 0 quests. The advertised “world does not wait for the player” is, for a quiet
village, 20 NPCs working, socialising, repricing and eating for a year. The seeded scenarios are the only evidence that
the other systems work.


## 3. Security and supply chain

**3.1 The model is in the Auto Backup scope.** `android:allowBackup="true"` with no backup rules
(`AndroidManifest.xml:6`) includes `filesDir/models` (`AiController.kt:47`), where the 344,671,744-byte model lives.
Auto Backup’s 25 MB quota cannot hold it; the backup will fail or be skipped, and there is no data-extraction policy for
Android 12+. Exclude `models/` (and decide whether AI prefs are worth backing up).

**3.2 A same-size model is trusted without its digest.** `ModelDownloader.isInstalled` checks size only
(`AiSetup.kt:106`–`:107`); `AiController` calls it on attach and status (`:147`, `:171`). The sha256 is verified during
download only. `AndroidModelAssetManager.verifyChecksum` does the right check but is used only by tests. A replaced or
bit-rotted same-size file is accepted.

**3.3 Redirects are followed without rechecking the host.** `AiController.openHttps` validates the initial
`https://huggingface.co/…` URL in `ModelDownloader`, then enables redirects (`AiController.kt:191`–`:198`) without
validating the final `Location`. The pinned digest prevents malicious content, but not an unexpected request target or a
downgrade to cleartext. `scripts/download_model.py` has the same shape (`urllib` follows redirects; `check_url` only
checks the input URL).

**3.4 A release leaves a request hanging for two minutes.** `OnDeviceChatModel.release` unbinds but does not fail
`pending` (`OnDeviceChatModel.kt:109`–`:115`); an in-flight request waits out `REPLY_TIMEOUT_MS = 120_000`
(`:83`, `:134`) after `onStop`.

**3.5 The publish script exposes the token in process arguments.** `tools/publish_github.sh` passes the token as a
`curl -H "Authorization: Bearer $GITHUB_TOKEN"` argument (`:27`) and a `git -c http.extraheader=…` argument (`:46`);
both are visible in `ps` while running. The script correctly keeps it out of `.git/config` and the remote URL, but a
long-lived `curl`/`git` process on a shared box leaks it. `NAME` from argv is also interpolated unescaped into the JSON
body (`:35`), so a quote breaks the request.

**3.6 The secret guard has known blind spots.** `SECRET_CONTENT` covers GitHub, AWS access-key-id and PEM keys
(`bash_guard.py:35`–`:38`), but not `hf_…`, `sk-…`, GitLab, Slack, JWTs or generic `*_TOKEN=` values; it skips files over
200 KB and binary extensions; and explicit `git add secret.txt` is allowed by design (`:100`–`:109`). The tests cover
only the patterns it already knows (`test_phase0_hooks.py:62`). A broader pattern set and a content scan for explicit
stages would be cheap.

**3.7 Supply chain is unpinned below the action tags.** CI uses moving major tags (`actions/checkout@v4`,
`android-actions/setup-android@v3`, …), the Gradle wrapper has no `distributionSha256Sum`
(`gradle-wrapper.properties`), and there is no Gradle dependency verification metadata. Dependency versions themselves
are pinned, which is good; the delivery chain is not.

**3.8 What held.** No repository secrets were found (including `hf_`, `sk-` and private-key patterns); both model pins
match the live Hugging Face metadata; `scripts/download_model.py` verifies size+sha256 and uses `.part` + `os.replace`;
the MCP server validates its inputs and uses fixed slugs; `lw_remote.py` uses argv lists and `shlex.quote`; the manifest
does not export the AI service. `targetSdk 34` is now below Play’s API-36 requirement (new apps/updates from
2026-08-31), and the launcher icon is still `@android:drawable/sym_def_app_icon`; those are shipping blockers, not
security holes.


## 4. Why the normal green signals missed all this

**4.1 The pre-push “full” gate is weaker than the Stop hook.** `bash_guard` calls `stop_gate.decide`
(`bash_guard.py:136`), which runs invariants, tool tests and `./gradlew check` — but **not** `golden_check` or
`determinism_check` (`stop_gate.py:90`–`:118`). The quick Stop hook does run them (`:207`–`:240`). A push made outside
an agent session, or after `LW_SKIP_PREPUSH=1`, can therefore skip the only Kotlin→golden comparison. The docstring
calls `decide` the “FULL gate”, which is misleading.

**4.2 CI does not run the Kotlin goldens.** `.github/workflows/ci.yml:48`–`:51` runs `check` and `assembleDebug`; the
three `:desktopApp:run --golden=…` commands exist only in `stop_gate.py:156`–`:159`. The Python mirror is held to the
goldens by `test_lw_mirror.py:81`, so a changed Kotlin kernel with unchanged mirror/goldens is caught locally (if the
hook runs) and not in CI.

**4.3 CI does not enforce the dependency isolation gate.** `check_invariants.py --all` never computes the dependency
diff; only `--staged` does (`check_invariants.py:117`–`:131`). CI runs `--all`, and the git pre-commit hook that would
run `--staged` is not enabled (`core.hooksPath` is unset), so a PR can add a dependency without `LW_ALLOW_DEPS=1`.

**4.4 The invariant checker is a grep with documented holes.** Direct probes of `check_text`:
`Thread.sleep(1)`, `Math.random()`, `kotlin.time.TimeSource.Monotonic.markNow()` and `import kotlin.jvm.JvmInline`
produce **no finding**, while `import java.util.Locale` correctly errors. `java.lang` needs no import on JVM, so a
commonMain file can use `Thread`/`Math` and pass; `TimeSource.Monotonic` is a common API but not deterministic; the
`kotlin.jvm` import is required for `@JvmInline` but is a literal platform-package import. The checker also doesn’t
parse code, so fully-qualified forms (`kotlin.random.Random.Default`) can slip past the negative lookbehind in the
regex. It is a useful tripwire, not a proof.

**4.5 `verify.py` cannot verify locally.** Its default path returns BLOCKED when `aws sts get-caller-identity` fails
(`verify.py:84`–`:86`), and `--no-remote` skips every Gradle task (`:83`). The full gate passes locally in 85 seconds,
so the one-command workflow is needlessly tied to an AWS session.

**4.6 Infra failures fail open.** Both `stop_gate` paths turn an AWS/SSM failure into a warning, not a block
(`stop_gate.py:97`–`:99`, `:109`–`:111`, `:232`–`:237`). That is a reasonable choice for a local agent, but it means
“green” can mean “never checked”.

**4.7 Tests encode the wrong behaviour.** See §2.4.5. The suite is large (598 executions) but several of its assertions
are the bug reports: ledger-only idempotency, dead issuer, stock-free trade, lowercase grounding, local inventory,
location-less line cache.

## 5. Corrections to the recon, and what genuinely survived

The adversarial pass falsified three statements I made in `recon-2026-10-22.md`, all now corrected there:
the workspace is a **shallow clone** (the “one commit” history claim was wrong; after `git fetch --unshallow` five of
six cited hashes resolve, only `dbc7ecf` does not); the 90-day **minimum food is 11**, not 15; and `lw_mirror` is
**2,191** Python LOC, not 5,714 (the latter was all Python in the repo). It also sharpened two claims: the 90-day default
run has **6 trades that mint goods**, and the 365-day economy is far worse than the 90-day snapshot (15 NPCs at 0 coins).

Genuinely strong parts, retested under attack:

- Determinism of the default kernel on JVM: two 90-day runs produced the same `041e2593769b807b`.
- The Python port is exact beyond the committed goldens: 45,648 lines identical over 90 days.
- Locale/timezone independence of the canonical dump on JVM.
- Model supply-chain pins match the live Hub metadata; the downloader’s atomic size+sha256 install is sound.
- Event ordering and float formatting in the kernel are disciplined; no non-deterministic collection/random/clock use
  was found in `game-core/src/commonMain` (the checker and manual grep agree).
- No in-repo secrets and no exported AI service.
- The full build, lint and 598 test executions pass; the issues are semantics and coverage, not a broken build.


## 6. Fix order that keeps the goldens honest

Do these as separate commits, tests first. “Golden” means the behaviour change is intended and requires
`tools/regen_golden.py` plus the matching `lw_mirror` port; anything not marked golden must leave all three goldens and
the 90-day fingerprint bytes identical.

**P0 — conservation and replay (do first, one golden re-record for the group)**

1. Turn the five probes in §2.1 into real tests: unknown seller, dead seller, missing stock, theft transfer, player
   theft. Add a coin-total invariant assertion to `EconomyAndQuestTest` and `DeathCascadeTest`.
2. Fix `applyTrade` to reject a seller that is missing/dead or lacks the item, and to credit only an existing seller;
   fix `applyTheft` to move the item to an NPC/player thief and remove it from a player owner; add alive guards to every
   reducer branch that mutates an NPC (`applyTrade`, `applyRationPurchase`, `applyRoutineCompleted`, `applyTheft`,
   `applyRumor`, `applyAllegianceChange`, `applyPriceAdjustment`, `applyShopInherited`).
3. Make `apply` idempotent for an event id already in the ledger; update the duplicate-kill test from “pins the bug” to
   “must not repeat”. Verify that ordinary runs are unchanged (unique ids), so the goldens should stay identical before
   the stock/theft fixes.
4. Add a `WorldInvariants` object used by `SaveManager.validateWorldIntegrity` and tests: unique/monotonic event ids,
   `nextEventNumber > max id`, known event references, dead⇔zero-health consistency both ways, faction leader membership,
   quest issuer/target existence and life, and `npcs.coins + player.coins + treasury` conservation. Reject a v3 envelope
   with a blank digest and compare `eventCount`/`savedAtTick` with the decoded world.

Golden impact: stock/dead-seller checks remove the default merchant’s six minted `goods` sales and may remove
`TradeCompleted` events, so expect a default/turmoil/feud re-record and an `lw_mirror` port. The idempotency/alive guards
should not change runs with unique event ids.

**P0 — G2/G3/G6**

5. Restrict `snapshotFor`’s `recentEvents` to what the NPC could know: events where it is an actor/witness, or events at
   its location that are public by rule. Add a test that a killing at another location is absent; keep the inspector’s
   full view separate (`SimulationInspector` already has the ledger).
6. Generate quests only from an issuer that is alive and has the relevant belief; add a test that a hidden death creates
   no quest, and that killing the guard captain cannot produce his own investigation. Decide whether “nobody saw it”
   means no quest at all (the README says it stays a mystery).
7. Make `deriveActiveProblems` consult quest status and remove completed/FAILED problems; add the completed-quest probe
   to `WorldState`/quest tests.
8. Fix the causal lookup: store the originating `EventId` on the belief where possible, or pass `acquiredAt` to
   `originOf` and filter `event.time <= acquiredAt`; add the two-theft probe. If a proposition has no unique origin, return
   null rather than a guess.
9. For G3, decide the policy explicitly. A lexical allowlist cannot distinguish an invented lowercase sentence from
   small talk. Minimum: a test that `"alden poisoned the well"` is rejected when `alden` is not in facts (requires a
   name/verb policy or an LLM-side structured fact list). If that is too strong, change the README/CLAUDE wording from
   “anything else is dropped” to “named entities and numbers are checked” and add the limitation to the AI docs.

**P1 — identity and event sourcing**

10. Replace the `identifier`-only relationship key with a typed key (`npc:merchant->player:player`, or a data-class
    pair); update `applyTheft`/`applyTrade` to use an exhaustive `when (EntityId)` instead of re-wrapping the
    identifier as `NpcId`; make `isPlayer` type-based. Add the `Npc(x)`/`Player(x)` probe. This is a save-format change
    if edge keys are persisted; add a migration or keep a typed key function only in memory and change the serialized
    form once.
11. Add a `PlayerMoved` event and emit it from `MapViewModel`; add the event to `CanonicalDump`/mirror if it belongs in
    the golden contract, and add a `player.currentLocation` line to the dump regardless. Add a ViewModel test asserting
    travel increases the ledger.
12. Add a save round-trip for a 90-day world and a ledger-consistency test with a duplicated event id; validate the
    ledger before replay. `SaveManager.replayEvents` should reject an event sequence whose ids are not unique.


**P1 — tooling and CI (cheap, high leverage)**

13. Add the three Kotlin `--golden=` runs to CI (the snippet in the recon report); add one 90-day fingerprint assertion
    as a JVM test; keep both.
14. Make the pre-push gate run the same determinism/golden tier as the Stop hook (`stop_gate.decide` should call
    `determinism_check`, or `bash_guard` should call `decide_quick`), and document that infra failures are fail-open.
15. Enforce the isolation gate in CI: compare `git diff` against the base branch for `*.gradle.kts` dependency lines and
    require `LW_ALLOW_DEPS=1`; or run `check_invariants.py --staged` in a temporary index. Add the missing
    `Thread.sleep`/`Math.random`/`TimeSource.Monotonic`/`kotlin.jvm` rules to the checker and its tests.
16. Give `verify.py` a `--local` mode that runs the same Gradle tasks and goldens without AWS; keep the remote mode for
    the maintainer’s box. Document `git config core.hooksPath .githooks`.

**P1 — AI/Android hardening**

17. Verify the sha256 on every attach/status (wire `AndroidModelAssetManager.verifyChecksum` or hash in
    `ModelDownloader.isInstalled`), fail `pending` on `release`, recheck the final redirect host, and add backup rules
    excluding `models/`. Add a test for a same-size/wrong-bytes installed file.
18. Remove the token from `publish_github.sh` argv (use a temporary `GIT_CONFIG`/credential helper or `curl --config`
    with a 0600 file), escape `NAME`, and broaden `bash_guard` secret patterns.

**P2 — performance, save size, docs**

19. Profile the ledger before optimizing (G10), but the measurement is already damning: 184,726 events in 42 s versus
    45,615 in 1.3 s. Batch events per tick or use a persistent/chunked list; do the same for `MemoryStore.add`; measure
    on Android, not just JVM.
20. Add a streaming/compact save format or at least skip `prettyPrint` for large worlds; measure a 90/365-day save and
    load.
21. Regenerate or archive the C4/dashboard/forensics snapshots, fix the stale `simulate_village.py`/GGUF/Hugging Face
    references, the 25-vs-20 test-suite count, the `Memory.kt` half-life comment and the `.mcp.json` absolute path.
22. Update README/CLAUDE claims that are now falsified: “steal bread” (no steal action), “Everything is an event”
    (player travel is not), “nothing minted or destroyed” (unknown seller), “anything else is dropped” (lowercase
    grounding), and “same start same world” (true for the tested JVM paths, not proven cross-platform).

## 7. Bottom line

The core is deterministic and the mirror is unusually good, but the project is currently **over-trusted by its own
tests**. The three recurring root causes are: (a) reducers trust event payloads and only the producer validates;
(b) the observable contract (fingerprint, dump, goldens) is much smaller than the state; and (c) the automation that
would catch drift is optional, local, or blind to dependencies. Fix the P0 conservation/idempotency and G2/G3/G6 items
before adding features; then make CI run the goldens and the dependency gate so the next regression is found by a
machine rather than by an adversarial pass.

