# Development-setup brainstorm — 2026-10-22

**Path:** spike (research whose output is a recommendation). Nothing in this report has been applied **except A11's file permissions** (done after your "approve"; see A11). Tier A adds tests,
reports, docs or additive fields and does **not** change when, whether or in what order any guard blocks. Everything that
does (a guard, a hook's trigger, order, timing or exit behaviour, plugin scope, a build file, a dependency) is Tier B and
waits for you. (An earlier draft put four hook changes in Tier A; a review caught it.)
**Method:** an audit of this session's measurements, then five independent read-only lenses (loop speed, guard
effectiveness, agent/skill/plugin cost, KMP tooling, continuity and learning), each run by a separate subagent, then a
synthesis in which the claims I rely on were re-checked against the code. Estimates below are labelled as estimates.

## 1. Evidence base

Measured (from `.git/lw-telemetry/events.jsonl`, snapshot of 168 rows taken about 10:40 UTC on 2026-10-22, 127 of them with
a duration; the live log keeps growing, 173 rows when re-read):

| Fact | Number |
|---|---|
| Total timed gate wall time | 211 min |
| `stop_gate`, all outcomes: runs / median / p90 (nearest rank 77 of 85) / max | 85 / 28 s / 350 s / 459 s |
| `stop_gate` runs over 2 min | 24 of 85 runs (28 %) account for **53 %** of all timed gate time (~112 of 211 min) |
| `stop_gate` warnings | 21 of 85 (25 %), median 31 s, **no reason recorded** (reasons logging only just merged) |
| `bash_guard` passes | 35 in total: 24 are real pre-push gate runs (median 184 s), 11 are sub-second staging checks; 65 min in all |
| `invariant_guard` | 41 runs, 5 blocks, **not timed** (none of its 41 rows has a duration; the "41 rows without one" above are exactly these) |
| `verify.py` end to end | ~5 min (one Gradle `check` + five cold `:desktopApp:run` + 135 Python tests) |
| GateGuard first-touch denials this session | 17, none caught a defect |

Verified in the code or on disk while writing this (so they are facts, not subagent claims):
- The determinism digest hashes **every** `.kt/.kts` under `game-core` and `desktopApp`, tests included (22 test files,
  186 `@Test` functions), so an edit to any of them re-triggers the whole determinism tier. It **omits** three things the
  runner depends on: `game-persistence`, `game-ai` and `game-ui` (their code), the `build` group (root `*.kts`,
  `gradle.properties`, wrapper: a toolchain bump does not re-run G8 at Stop), and `tests/golden/**` plus
  `scenarios.json` (`KOTLIN_INPUTS` only lists `.kt/.kts/gradle` files, so re-recording a golden or editing the scenario
  list triggers nothing at Stop). **The pre-push gate has the same blind spot**: `bash_guard` calls `stop_gate.decide({})`, which
  keys on `kotlin_digest()` (Stop uses `module_digests()` and `determinism_digest()` instead); both filter on the same
  `KOTLIN_INPUTS`, and `decide` skips the Kotlin and determinism tiers when its digest equals the last green one. So a re-recorded golden or an edited `scenarios.json` is checked only Python-side before a push
  (mirror parity, `test_scenarios`); its Kotlin-versus-golden comparison first runs in CI. `CLAUDE.md`'s sentence that the
  pre-push gate "still runs the full `check`" is therefore inaccurate (A4 fixes the text).
  (`tools/hooks/stop_gate.py`: `DETERMINISM_MODULES`, `module_of`, `KOTLIN_INPUTS`, `module_digests`.)
  Note: the 769 in `TESTCOUNT` is a JUnit XML count over all modules and targets, not the 264 `@Test` functions in the
  repository; it may include parameterised cases and stale result files, so treat it as a trend line, not a census.
- `tools/mcp/lw_sim_server.py` `verify` parses only two-token `PASS`/`FAIL` lines, so it drops the `TESTCOUNT` line.
- `.claude/skills/awsbox-build/SKILL.md` and `ci.yml` still install/mention Android SDK 34; the project builds on 36.
- `CLAUDE.md`: 60 lines but 3,648 words (26 KB), re-read every session; most bullets are dated change-log entries.
- The ECC plugin ships 311 skills and 71 agents, all listed in every session; most are irrelevant to a Kotlin game.
- The continuous-learning observations file is 3.5 MB, **mode 644**, and holds raw tool input/output (the lens found
  ~11 % of lines contain secret-looking words; its contents were deliberately not quoted). The observer is off: 0 instincts.
- Provenance: the telemetry numbers, file sizes, process states and the action versions (looked up with `gh api`) were
  measured in this session. These come from a subagent reading the code or a comment and were **not** independently
  measured: the ~3 GB free on awsbox (a `gradle.properties` comment), the 42.5 s preflight, the ~31 s lost per dead-session
  stop (derived from the median warning duration, which is an inference), and every "estimated saving" in Tier B.
- A second, older Claude session in this terminal is suspended (state `T`) mid pre-push gate with an SSM job stopped for
  2.7 h. That, not a status-line command, is the confirmed cause of a frozen status line (there is no `statusLine` key).
  Nothing in the gate can time it out while it is stopped.

## 2. Ranked plan

Ranking is value per effort. "Strictness" says what each change does to what the gates catch.

### Tier A — safe to do on your word: local, adds checks or measurement, loosens nothing (A11 touches a file outside the repo)
| # | Change | Evidence | Effort | Strictness |
|---|---|---|---|---|
| A1 | **Telemetry completeness** (additive fields only): every outcome records `s`, a short `reason`, `sha`; add `telemetry.py stale` (a guard with zero rows is flagged) | all 41 `invariant_guard` rows lack a duration, 21 warns unexplained | S | adds |
| A3 | **Backlog-vs-git check** (`tools/check_backlog.py`, advisory first): flag an unchecked item whose title/hash/id appears in a recent commit subject or is marked done in `docs/refactor-plan-2026-10-22.md` §15 | 2 of the first 3 backlog items were already done | S | adds |
| A4 | **lw-sim `verify` returns structured counts** (parse `TESTCOUNT`; it also drops `--local`'s three-token lines `PASS  gradle check` and `PASS  golden <name>`; `verify.py:144,147` help text still says three goldens), fix its docstring (`lw_sim_server.py:141` still names three goldens, there are five), the SDK-34 text in the `awsbox-build` skill, and the `CLAUDE.md` sentence about the pre-push gate (it runs `decide()`, which skips an unchanged digest, not always the full `check`) | verified above | S | adds (docs and a parser) |
| A5 | **Guard canary tests**: feed each real hook a synthetic bad input (a banned import under *both* path spellings, `git add -A` with a secret-like file) and assert it blocks; run in the tool tests | `invariant_guard` was a silent no-op for its whole life and was found by accident | M | adds |
| A9 | **Property/mutation-replay tests for the reducer** (seeded, real ledgers mutated: coins and items conserved, idempotent events, replay equals live digest); no new dependency | the adversarial pass broke 13 of 14 probes; NPC trade will touch these paths | S–M | adds |
| A10 | **Chronicle/census report** (new `tools/chronicle.py`, tested against the turmoil census in CLAUDE.md: 2 deaths, 1 theft, 77 rumors, and the schism war and defections) over a long run (deaths by cause, wars, defections, rumors, trades by pair type, coin distribution) and a census diff for golden re-records | the risk register wants a census diff; none exists; emergent politics is only visible in dump lines | S | report, never blocks |
| A11 | **Protect the observations file** — **applied** after your approval: `chmod 700 /root/.local/share/ecc-homunculus` and `chmod 600` on `…/projects/af6b49ce08e5/observations.jsonl`; verified with `stat -c %a` (700 and 600) and no observations file left at another mode. The `+` that `ls -l` shows is on every file in this environment, not a sign of an extra ACL on these. The file is outside any synced or backed-up directory. **Not done:** size-based rotation (it edits a plugin's data file, so it is Tier B, B19) | file was 3.5 MB, mode 644, raw tool input/output | S | privacy |
| A12 | **Loop skills**: feature-loop gets a pre-flight "already done?" step and a negative-check step for any new gate; sprint-loop calls a generated restart brief; rule "one gated first-touch edit per batch" (verify: dry-run the loops on a fixture backlog containing one already-done item) | the three repeated frictions | S | adds |

### Tier B — needs your approval (changes a guard, hook, plugin scope, build file or dependency)
| # | Change | Why it needs you |
|---|---|---|
| B18 | **Fix a fail-open in `stop_gate.decide()` (found by the review of this report)**: it writes the "this digest is green" marker (`last-green`, `stop_gate.py:111-113`) *before* the determinism tier runs; when that tier then hits an infrastructure failure it only warns (`:120-122`), and `last-green-determinism` is written at `:119` but **read nowhere** in `tools/hooks`. So after one infrastructure failure, every later Stop or push on the same code skips Kotlin, G8 and the goldens. Fix: write the marker only after determinism passes (or make the skip depend on both markers), plus a test with an injected infra failure that expects the next call to re-run. Related and by design: `bash_guard` treats an expired AWS session as a warning, so a push can go out with only a warning | changes a guard's skip rule; it **tightens**. Highest value in Tier B: a small change that restores a gate |
| B13 | **Narrow and widen the determinism trigger** (was A2): ignore test source sets; add `game-persistence`/`game-ai`/`game-ui` code, the `build` group, `tests/golden/**` and `scenarios.json` to what re-runs G8 and the goldens at Stop. Write its negative test together with the A5 canary | changes a guard's trigger. It **tightens** by closing three real gaps and **loosens** one thing, stated plainly: after a test-only edit, Stop would no longer re-run the G8 repeat and the goldens (they cannot change from a test-only edit, because tests are not on the runner's classpath, and the `jvmTest` tiers still run). It must change **both** paths: the Stop path (`module_digests`, `determinism_digest`) and the pre-push path (`kotlin_digest`) are separate functions that filter on the same `KOTLIN_INPUTS`, so both digests and that list need widening. Estimated saving ~40 min over the recorded history (a lens estimate) |
| B14 | **Fail-closed wrapper** (was A6): any exception in a *blocking* decision exits 2 and records `crash`. **Scope it**: `bash_guard` runs before every Bash call, so a bug there would block all Bash use. Fail closed only for the push and secret decisions; every other Bash command fails open with a visible warning. Advisory hooks never fail closed | changes exit behaviour of guards; the brick-the-session risk must be accepted explicitly |
| B15 | **Local checks in parallel with the remote run, and test the AWS session first** (was A7) | changes the order and timing inside `verify.py` and the stop gate; same checks, estimated 40–55 s per verify |
| B16 | **Preflight under 5 s** (was A8): the two `aws` calls concurrently with short timeouts, cache the result for ~60 s, always print one line | changes a SessionStart hook's timing; advisory, no protection lost |
| B17 | **CI: replace the stale Android SDK 34 install** (`.github/workflows/ci.yml`, the `sdkmanager … android-34` step) with SDK 36 or none (CLAUDE.md says AGP downloads the missing platform) | a build-workflow change; only a real CI run can validate it (was part of A4) |
| B19 | **Rotate the observations file** (keep the last 2,000 lines once it passes 1 MiB; a small script with a test on a temp file; the threshold is a proposal) | edits a plugin-owned data file; pointless if capture stays off (see Tier C #1) |
| B1 | **GateGuard exemption** for the repo's own source (script already given; the agent cannot edit its own settings) | guard configuration |
| B2 | **One remote script for all scenario runs**: compile once, print the runtime classpath, loop `java -cp …` (separate JVMs, so G8 still means something) for every golden and determinism repeat | changes how the gates run; the lens estimates 4–5 min saved on every stop that triggers the tier, ~15–30 min/day (an estimate; measure first with `--profile`) |
| B3 | **Stop gate tiers**: lint out of the quick tier; `desktopApp`-only edits map to compile + determinism; the slow tier moves to push | **loosens Stop** (a G8 break or a lint failure is then caught at push, not at the turn end). Do after B13/B2 shrink the slow tier; the lens itself ranks it last |
| B4 | **Project-scope plugin trim** (`enabledPlugins` false for marketing/data/product-management/design/cowork-plugin-management; keep `ecc`) | plugin scope; the saving (~5–10k tokens per session and per subagent) is an estimate, and whether a project `false` overrides a synced plugin is unverified |
| B5 | **Silence failing/irrelevant MCP connectors** (Cloudflare 410, Smithery 404, Notion, Linear, Supabase, Slack, Semrush…) | account-level connectors; most likely a manual step in claude.ai settings |
| B6 | **Progress file + bounded waits**: gates write the current step to a file; wall-clock deadlines in `lw_remote.py` below the hook timeout; stale-run report at the next prompt | touches hook timing; cannot help a process that is stopped, only detect it on resume |
| B7 | **Single "what counts as pass" module** for verify, stop gate and CI (partly done: one scenario list, one strict loader) | refactors three gates |
| B8 | **Kotlin/Native `linuxX64` as a test-only target** for `game-core`/`game-persistence`, with the golden digests in common code | build file + first build downloads a ~1 GB toolchain; makes G8 a two-runtime fact and G9 a compiler fact. Check that awsbox is x86_64 first |
| B9 | **Emulator launch/ART-determinism smoke in CI**; **Compose screenshot tests** (Roborazzi) | new test dependencies |
| B10 | **Nightly soak workflow** with a scaling curve (30/90/180/365 days) and hard conservation assertions | new workflow; the lens ranks it high because W7 and the NPC-trade tuning need measured curves |
| B11 | **Supply-chain hardening (a security item, not a speed win)**: pin Actions to SHAs and bump the six actions still on Node 20 (checkout v4→v7, setup-java v4→v6, upload-artifact v4→v7, setup-android v3→v4, gradle actions v4→v6, setup-python v5→v7); Gradle dependency verification metadata | needs a real CI run to validate |
| B12 | **CLAUDE.md split**: a ~100-line core plus linked `docs/knowledge/<topic>.md` (mechanical move, not a rewrite); one generated restart brief instead of a hand-written one | restructures the file every session loads |

### Tier C — decisions only you can make
1. **Continuous-learning observer.** Side effect observed: observation capture stopped at 11:28:48 UTC, when ECC's hook profile was set to `minimal` (the file's last write); that is an inference from timing, not from reading the plugin's hook definitions, but it means capture is probably already off. Both lenses that looked say keep the observer *off* (it would read ~850k tokens of raw
   tool I/O, rediscover what this session already wrote down, and risk copying a secret into a durable instinct). One of
   them suggests also switching observation *capture* off for this project. I recommend: keep the observer off, do A11
   now, and write the five hand-written instincts below instead.
2. **Whether to merge/stage the NPC-to-NPC trade** after A9 and A10 exist, so its fingerprint move arrives with a
   before/after census and economy curve.
3. **Retire the stale `ck_2026-10-18` memory entry** (its "next steps" are out of date).
4. **Pushing.** `origin/main` is at `a70304b`, the starvation commit that was later reverted and redone; local `main` and
   the working branch are about two dozen commits ahead of it, so a push is a fast-forward (`git log origin/main..HEAD`). Nothing after `3b7be85` has been
   pushed. CI only runs on pushes to `main`, on pull requests and on manual dispatch.

Five hand-written instincts worth saving (each atomic, evidence-backed):
1. About to edit a file for the first time under GateGuard → probe it in its own call, then edit; never batch first-touch edits.
2. Adding or changing a gate → also write a negative test, and check that an exception makes it fail closed.
3. Adding a check to CI or the stop gate → diff its command list against the other gates before calling it done.
4. Starting from a backlog or plan item → `git log -S "<keyword>"` and grep the symbol first.
5. About to write a branch name or commit count into a doc → don't; link to the generated brief or date it.

## 3. Where the lenses disagreed or I overrode them
- **Atomic parallel batches (guard lens):** not feasible from outside the plugin; the report keeps a convention plus detection.
- **Running the five goldens in parallel (speed lens):** rejected by that lens too: the box has ~3 GB free beside another
  job, and after B2 the five runs are estimated (not measured) to cost ~10 s in total.
- **Looping scenarios inside one JVM:** rejected as the G8 proof (it hides per-JVM nondeterminism); fine only for the
  golden comparison.
- **Kotlin/JS as a second target:** rejected (JS numbers are doubles, so the float-heavy kernel would diverge for reasons
  that do not apply to the shipping targets); Native is the iOS-like check.
- **Demote any guard on cost alone:** rejected; a never-blocking guard is also what a silent no-op looks like (A5 first).
- **Fail closed everywhere:** rejected; an advisory hook that crashes should warn, not brick every prompt.

## 4. Suggested order
1. ~~A11 first~~ (done). **B18 next** if you approve only one guard change: it is a small fix to a real fail-open.
2. A1 (so later decisions have reasons to read), A4, A3, A12.
3. A5 (the canary tests) before any gate change, so that each Tier B change to a guard has a test that proves the guard
   still fires; then A9 and A10, which must exist before any NPC-trade work.
4. Then ask for B13 (with its negative test), B1 and B2: the largest day-to-day speed and friction wins. B11 is the
   biggest *security* item, not a speed one. B14–B16 after B13. B8/B10 last.

## 5. Process notes (honesty about this session)
- The CI/pipeline change (one scenario list in `tests/golden/scenarios.json`, `tools/list_scenarios.sh`, per-run
  concurrency, a Kotlin test-count line) went through **three** santa-loop rounds. Round 1: both reviewers FAIL (5 issues
  fixed). Round 2: Reviewer A FAIL (2 issues fixed: a bad config made the stop gate fail *open*, and CI could run zero
  goldens); Reviewer B did not run (its service connection closed) and was rerun, not counted as approval. Round 3:
  Reviewer A PASS, Reviewer B FAIL with 5 issues. **The loop ended unresolved at its three-round cap**, which the skill
  defines as an escalation, not a pass. Those five issues (integer-only `days`, newline names, exact golden set in
  `mirror_parity`, a CI determinism repeat, test gaps) were then fixed *after* the loop with tests; they have **not**
  been independently re-reviewed.
- Nothing was pushed after `3b7be85`. Local `main` trails the working branch by the commits made since it was last moved (check `git status -sb`).
- Reviewer B (Codex) completed 2 of 3 runs; one failed on a closed WebSocket. One data point, not a failure rate.
