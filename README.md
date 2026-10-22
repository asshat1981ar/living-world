# Living World

A tactical RPG where the world does not wait for the player. Every villager has a job, needs, coins, memories, beliefs
and grudges; they work, trade, gossip, fall out and die, and the story is what that simulation produces. The player is
one more actor in it: steal bread and a witness remembers, tells a friend, and trust, prices and quests shift.

<img src="docs/art/concept-portrait-1.jpg" alt="Ink concept portrait" width="280">

Android-first, written in Kotlin Multiplatform. Early and experimental: the simulation kernel is solid and heavily
tested; the Android app is a set of screens over it, and the on-device AI dialogue has not yet been tried on a phone.

## What makes it different

- **Knowledge is not truth.** NPCs act on what they believe (seen, heard, rumoured), never on the world's hidden state.
  A killing nobody saw stays a mystery until someone learns of it.
- **Everything is an event.** Every change is recorded in an append-only ledger with causal links, so any moment can be
  replayed, inspected and explained ("why does the guard hate me?").
- **Deterministic.** The same start produces the same world, bit for bit. Recorded "golden" runs and an independent
  Python port of the kernel catch any unintended change in behaviour.
- **AI is optional and grounded.** An on-device model (no account, no internet after a one-time download) may reword
  what an NPC says, but only to state facts that NPC believes; anything else is dropped and the NPC uses its built-in
  line. AI advice for NPC decisions is recorded, so even AI-steered runs repeat exactly.

## What is in the simulation today

A village of NPCs with beliefs, memories and relationships; a closed coin economy (wages from a treasury, food bought
from producers, tax and daily upkeep, nothing minted or destroyed); rumours that spread and fade; factions with
leadership and succession; deaths that ripple out (witnesses, grieving friends, inheritance, failed quests, murder
investigations); quests that emerge from real problems; and stance-based tactical duels.

## Project layout

| Module | What it holds |
|---|---|
| `game-core` | The kernel: ids and time, events and the ledger, beliefs, memory, NPCs, relationships, factions, `WorldState` / `WorldReducer`, `SimulationEngine`, decisions and the action validator, dialogue grounding, the inspector. No platform or AI code. |
| `game-persistence` | Saves, schema migration and FNV-1a state fingerprints. |
| `game-ai` | Optional AI: the grounded dialogue voice, prepared dialogue lines, the AI advisor for decisions, model integrity checks. |
| `game-ui` | Sprites, visual adapters and the shared Compose layout. |
| `androidApp` | The Android game, including the opt-in on-device AI (Google LiteRT-LM running Qwen3 in a separate process). |
| `desktopApp` | A command-line runner for long simulations, fingerprints and canonical dumps. |
| `lw_mirror/` | A dependency-free Python port of the kernel, held byte for byte to the Kotlin golden dumps in `tests/golden/`. |
| `tools/` | Invariant checks, golden and mirror tooling, build helpers and their tests. |

The rules every change must keep (invariants G1-G10) are in [`INSTRUCTIONS.md`](INSTRUCTIONS.md).

## Build and run

Needs JDK 17 and the Android SDK (platform 34) for the Android module.

```sh
./gradlew check                                   # all tests and lint
./gradlew :game-core:jvmTest                      # the kernel's tests
./gradlew :desktopApp:run --args="30 10"          # simulate 30 days, print the last 10 events and a fingerprint
./gradlew :desktopApp:run --args="15 0 --scenario=turmoil"   # a scenario that starts with deaths and a theft
./gradlew :androidApp:assembleDebug               # the Android APK
```

Python checks (no dependencies):

```sh
python3 tools/check_invariants.py --all           # G1/G8/G9 static checks
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/mirror_parity.py                    # the Python kernel matches the Kotlin golden dumps
```

## Try it on a phone

Every CI run on `main` uploads a debug APK. With the GitHub CLI:

```sh
gh run download -R asshat1981ar/living-world -n living-world-debug-apk
```

Install `androidApp-debug.apk` (allow installs from unknown sources once). The AI tab is off by default; on a 64-bit
phone with about 4 GB of RAM or more it offers a one-time model download (345 MB) and a speed test.

## Where it is heading

One living world that ticks and saves in the app; real hunger, sleep and investigations that resolve; seasons and
travel between villages; a chronicle screen that tells the story of any NPC; moddable scenarios. The working queue is
[`docs/backlog.md`](docs/backlog.md).

## Status

The simulation kernel is solid and verified; the Android shell around it is early (screens over the kernel, no shared
world yet, no save). Two reviews from 2026-10-22 list what still needs fixing —
[`recon`](docs/reports/recon-2026-10-22.md) and
[`adversarial`](docs/reports/adversarial-2026-10-22.md) — and
[`docs/refactor-plan-2026-10-22.md`](docs/refactor-plan-2026-10-22.md) is the plan that fixes them.

## License

[Apache-2.0](LICENSE).
