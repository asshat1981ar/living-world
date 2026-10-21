# Backlog (feature loop queue)

The queue the feature loop works from (`.claude/skills/feature-loop/SKILL.md`). Top unchecked item first unless it is
tagged `[ask]` or `[human]`. Seeded 2026-10-21 from a read-only recon of the repo (evidence in brackets). Supersedes
the P0-P11 table in `docs/reports/system-health-2026-10-18.md` as the live queue (P0-P4 and P6-P11 are done).

Tags: size S/M/L · `golden` = changes simulation behaviour, so goldens are re-recorded and `lw_mirror` must match ·
`[ask]` = needs the user's go-ahead first · `[human]` = needs the user's hands (a phone, a login).

## Queue

- [ ] **Push and let CI pass** `[ask]` S. Local commits are not on GitHub yet; the on-device AI commits have not been
      through CI.
- [ ] **Smoke-test on-device AI on a real phone** `[human]` M. Never run on a device (CLAUDE.md, On-device AI). CI now
      uploads the debug APK (`living-world-debug-apk` artifact): `gh run download -n living-world-debug-apk`. Check:
      the AI tab, the download, the speed test, ✨ lines, and that killing the AI process turns the AI off.
- [ ] **One shared world in the Android app** L. `MainActivity` hands one `marketVillage()` to four view models that
      each keep a private copy (`InventoryViewModel.kt` `private var world`); nothing ticks the simulation or saves
      (`SaveManager` is only used by desktopApp); state is lost on Activity recreation. No golden change.
- [ ] **game-ai cleanups** S. `DeterministicAiFallback` adds the same goal bonus to every candidate and treats any text
      containing "fallback" as a fallback (`AiReasoningPort.kt`); `extractLastJsonObject` / `isFallbackProposal` have
      no callers since the Hugging Face code was removed; `game-ui` declares a `game-ai` dependency only its tests use.
      Also fix the "half-life of 14 days" comment in `Memory.kt` (it is 20). No golden change.
- [ ] **A golden scenario for uncovered kernel paths** M. `lw_mirror` ports of ChangeAllegiance, Flee/survival,
      faction war, NPC-to-NPC trades and EntitiesMet are unverified (CLAUDE.md, Python kernel mirror). Adds a fourth
      golden; may expose mirror bugs. Lowers the risk of the P5 re-record.
- [ ] **P5 gameplay realism** `[ask]` `golden` L. Starvation is impossible (food floors at 0.5 in `WorldReducer`),
      GREEDY merchants re-price constantly, NPCs work through the night and three of them drain to 0 coins, every death
      becomes an unresolved homicide. Fold in the clearest `current_behaviour_*` pins (theft from the player takes
      nothing; a second kill repeats the cascade). One golden re-record + mirror port.
- [ ] **Toolchain** `[ask]` M. Gradle 8.8 (deprecated by Kotlin 2.4's plugin) and AGP 8.5.2 (its lint prints harmless
      "incompatible version" lines). Dependency changes need `LW_ALLOW_DEPS=1` and the user's OK.
- [ ] **MemoryStore copies its list on every add** S. O(n²) over long runs (`Memory.kt` `add`); must keep the 90-day
      fingerprint identical. Low priority until profiled (G10).

## Done

- [x] P9 AI advice recorded as events, runs repeat exactly (`11ee900`).
- [x] P6 remainder: WorldTime, Memory, Faction, inspector tests (`21e2c1c`).
- [x] Repeat-ask AI line fix (`6e266a5`).
- [x] Dev loop: `tools/verify.py` (one remote call, BLOCKED on an expired session); CI uploads the debug APK.
