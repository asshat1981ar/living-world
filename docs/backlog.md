# Backlog (feature loop queue)

The queue the feature loop works from (`.claude/skills/feature-loop/SKILL.md`). Top unchecked item first unless it is
tagged `[ask]` or `[human]`. Seeded 2026-10-21 from a read-only recon of the repo (evidence in brackets). Supersedes
the P0-P11 table in `docs/reports/system-health-2026-10-18.md` as the live queue (P0-P4 and P6-P11 are done).

Tags: size S/M/L · `golden` = changes simulation behaviour, so goldens are re-recorded and `lw_mirror` must match ·
`[ask]` = needs the user's go-ahead first · `[human]` = needs the user's hands (a phone, a login).

**Plan (2026-10-22).** The full fix-and-refactor plan for everything the recon (`docs/reports/recon-2026-10-22.md`) and
the adversarial pass (`docs/reports/adversarial-2026-10-22.md`) found is `docs/refactor-plan-2026-10-22.md`: eight waves
(W0 harness → W7 performance), 55 commits, a traceability matrix from every finding to the commit that closes it, and a
paste-ready item list in its §6. Work the queue in that order; the plan fixes the ordering of the items below.
Coverage of the items still listed here: "One shared world" → W4, "game-ai cleanups" → C0.8/C5.1, "A golden scenario
for uncovered kernel paths" → C2.12, "P5 gameplay realism" → W6, "Toolchain" → C5.4, "MemoryStore copies its list on
every add" → C7.2.

## Queue

Re-seeded 2026-10-22 from `docs/refactor-plan-2026-10-22.md` §6 (W0–W2 are done; see §15 of the plan for the progress
log and the commits). The first items need no approval and no golden re-record.

**W2 leftovers**

- [ ] **`EventValidator` for entity existence** M. The reducer's pre-checks are inline; make them one reusable object and
      cover "every event actor, owner and victim exists" (plan C1.1/C2.11).
- [ ] **A separate state dump (dump v2)** M. The canonical dump still omits belief content, items, traits, the player,
      the quests and the places, so changes there stay invisible to the goldens (the digest sees them now, the dump does
      not). Write it as its own file so the goldens need no re-record.
- [ ] **A golden scenario for uncovered kernel paths** M. Allegiance, flee, faction war, NPC-to-NPC trades and
      `EntitiesMet` are still unexercised by any golden, and the mirror ports of those paths are unverified. Adds a
      fourth scenario (a new golden file, not a re-record).
**W3 — test depth**

- [x] **Replay and soak compare the whole state** M. Factions, relationships, economy, quests, the player and the
      ledger are compared too - and they all match, so the ledger really does rebuild the world.
- [x] **Conservation and append-only tests over 30 days** M. The coins *and* the objects are pinned: a trade, a theft
      and an inheritance all move items, so the total must be the same afterwards.

**W4 — one world in the app** (no golden change; on `feature/w4-app-world`)

- [x] **One shared world in the Android app** L. A `WorldStore` owns the single world; the four screens read and write it
      instead of keeping private copies, so a trade in the market is a trade in the village.
- [x] **The world ticks and is saved** M. The store ticks an hour at a time while the app is in front and saves/restores
      the world, so the village survives the process.
- [x] **The inventory comes from the world** M. The counts are `world.player.inventory`, the catalog is refreshed after a
      trade, and you cannot sell what you do not have.
- [x] **Player travel is an event** M. `GameEvent.PlayerMoved` goes through the reducer, so a replay reproduces where the
      player stands.
- [x] **A duel can be lost** M. The player has health in the world, the opponent answers with a counter-blow, and
      the duel screen shows the wounds.

**W5 — AI, Android and shipping** (`[ask]` for the dependency part)

- [x] **Verify the model digest on every load** M. The installed model is checked by sha256, with a stamp file so the
      common case stays free; a same-size file no longer passes.
- [x] **Keep the model out of backups** S. `fullBackupContent` and `dataExtractionRules` exclude the model directory.
- [x] **Validate the redirect target** S. Redirects are followed only to https hosts on huggingface.co.
- [x] **Fail a pending request on release** S. A released request fails at once instead of hanging for 120 s.
- [ ] **game-ai cleanups** S. `DeterministicAiFallback` adds the same goal bonus to every candidate and treats any text
      containing "fallback" as a fallback (`AiReasoningPort.kt`); `extractLastJsonObject` / `isFallbackProposal` have no
      callers; `game-ui` declares a `game-ai` dependency only its tests use. Also fix the "half-life of 14 days" comment
      in `Memory.kt` (it is 20).
- [ ] **Toolchain** `[ask]` M. Gradle 8.8 (deprecated by Kotlin 2.4's plugin) and AGP 8.5.2 (its lint prints harmless
      "incompatible version" lines). Also `targetSdk 34` is below Play's API-36 requirement and the launcher icon is
      still the system placeholder. Dependency changes need `LW_ALLOW_DEPS=1` and the user's OK.
- [ ] **Smoke-test the app on a real phone** `[human]` M. The AI has never run on a device (CLAUDE.md). CI uploads the
      debug APK (`living-world-debug-apk`): `gh run download -n living-world-debug-apk`. Check the AI tab, the download,
      the speed test, ✨ lines, and that killing the AI process turns the AI off.

**W6/W7 — realism and performance** (`[ask]` for W6)

- [ ] **P5 gameplay realism** `[ask]` `golden` L. NPCs work through the night, three of them drain to 0 coins by day 90
      and 15 by day 365, prices are repriced ~24 times a day, and a completed investigation leaves the problem open.
      One golden re-record + mirror port.
- [ ] **MemoryStore copies its list on every add** S. O(n²) over long runs (`Memory.kt` `add`); must keep the 90-day
      fingerprint identical. Low priority until profiled (G10).
- [ ] **Block-structured EventLedger** M. `EventLedger.append` copies the whole history on every event: 45,615 events in
      1.3 s but 184,726 in 42 s. Batch or chunk it, and measure on an emulator.
- [ ] **Compact saves** M. A 90-day world is a ~10 MB pretty-printed JSON; measure save/load and make it compact.

## Done

- [x] **Grounding v2** M. Every word of a reply must be known (facts, speaker and place, or the curated small-talk
      vocabulary); "alden poisoned the well" is rejected without facts (`d81eafd`, test
      `an_invented_lowercase_sentence_is_rejected`).
- [x] **Push and let CI pass** `[ask]` S. PR #1 merged into `main` (`c152626`); CI now runs the goldens and the dependency
      isolation gate, and the `main` run is green.
- [x] W0 — the gate: `verify.py --local`, the pre-push determinism tier, the extra invariant rules, the wider secret
      guard, the token-free publish script, the Gradle checksum, the characterisation tests, the docs truth pass.
- [x] W1 — the kernel contract: idempotent events, the dead do not act, `WorldInvariants`, the hardened save gate, the
      90-day fingerprint as a test.
- [x] W2a — typed entity keys, the full-state digest, save schema v4, knowledge-scoped AI snapshots.
- [x] W2b — the closed economy (a trade needs a living seller that holds the item, a theft moves the item), quest
      issuers that know, causes that point backwards, the mirror port and one golden re-record.
- [x] P9 AI advice recorded as events, runs repeat exactly (`11ee900`).
- [x] P6 remainder: WorldTime, Memory, Faction, inspector tests (`21e2c1c`).
- [x] Repeat-ask AI line fix (`6e266a5`).
- [x] Dev loop: `tools/verify.py` (one remote call, BLOCKED on an expired session; `--local` needs no session); CI uploads
      the debug APK.
