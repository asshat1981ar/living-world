# Environment tuning proposals — 2026-10-22

Evidence is this session's transcript plus `python3 tools/hooks/telemetry.py`. **Nothing below that loosens a guard has
been applied**: the permission layer refused one attempt to read the plugin's hook scripts as self-modification, and an
unattended session should not relax its own guardrails. Items marked *applied* only tighten or add checks.

## What happened (evidence)

| Observation | Count / size | Cost |
|---|---|---|
| ECC GateGuard "fact-forcing" denials on the first Edit/Write/Bash of each file | 12 in one session | one wasted tool call each, plus a re-plan |
| Denials in **parallel batches** | 5 | the batch's *other* edit still applied, leaving `InspectorMain.kt` referencing undeclared vals and `backlog.md` half-edited until retried |
| Backlog entries already done but still unchecked | 2 of the first 3 (Grounding v2 `d81eafd`, EventValidator `578cff1`) | a whole recon pass before noticing |
| `stop_gate` runs / warns | 80 / 21 (26 %, mean 95 s) | warns are infra (expired AWS session or SSM), not verdicts |
| `invariant_guard` | 26 runs, 5 blocks, ~0 s | the only guard with a high catch rate per second |
| `bash_guard` | 33 runs, 2 blocks, mean 114 s | the mean is the pre-push gate; fine, it runs rarely |

## Proposals

### A. Needs your decision (loosens or reconfigures a guard)

1. **Exempt the repo's own source from GateGuard's Edit/Write fact-forcing.** The repo already has `invariant_guard`
   (blocks G1/G8/G9 regressions), the tiered stop gate, the goldens and git. GateGuard added no catches here, only
   restating. In `.claude/settings.local.json` (gitignored, so personal) set an `env` block with
   `GATEGUARD_EXEMPT_GLOBS` covering `game-*/**`, `androidApp/**`, `desktopApp/**`, `lw_mirror/**`, `tools/**`,
   `tests/**`, `docs/**`, `scripts/**` (check the list syntax in the plugin's `docs/gateguard/design-notes.md`). Leave
   `.github/workflows/**`, `.env*`, keys and `models/` gated (they are the sensitive targets GateGuard exists for).
   Destructive Bash checks stay on. Effective from the next session. Milder fallback:
   `GATEGUARD_FACT_FORCE_MAX_DENIALS=2`.
2. **Do not exempt `.github/workflows/`**; this session's CI edit was the one place the gate was proportionate.

### B. Workflow and skill text (safe, small; deferred only because they edit the skills I run under)

3. `feature-loop` step 1: before starting an item, `git log --oneline -S"<item keyword>"` and check that the named test
   exists; if the work is already in the tree, close the entry with its hash instead of redoing it.
4. `feature-loop` / `sprint-loop`: "make gated first-touch edits **sequentially, never in a parallel batch** with
   dependent edits" — a blocked edit in a batch does not stop its siblings.
5. `feature-loop` step 5: when the item adds a golden or a gate, include a **negative check** (feed the gate a wrong
   golden and see it fail). Done by hand for the state golden this session; worth a line in the skill.

### C. Resources not yet used this session (use them next)

- `test-verifier` agent / `lw-sim` MCP (`awsbox_build`, `ledger_diff`, `causal_trace`, `determinism_benchmark`): run the
  verify loop and diff ledgers off the main context when a golden changes unexpectedly.
- `invariant-auditor` agent after any `game-core` change, before the commit (cheap, read-only, G1/G8/G9).
- `forensic-hotspots` + `refactor-microop` for the next W7 performance item (EventLedger copies, MemoryStore), so a
  profile picks the target instead of guessing.
- `drift-audit` / `c4-snapshot` once the plan's remaining waves land, to refresh `docs/` against the code (the stale
  backlog is the symptom).
- `sprint-loop`'s mandatory adversarial pass for any item that touches `golden`.

### D. Measured follow-ups

6. Investigate the 26 % `stop_gate` warn rate: if mostly "AWS session expired", have `session_preflight` fail the first
   prompt loudly (it already reports) and let the stop gate skip with a single clear line instead of a warn per stop.
7. Telemetry flags hooks only after 20 runs; `invariant_guard` (26) is the one to watch for false positives.

## Applied this session (tightening only)

- State dump v2 and its goldens are required by `verify.py`, the stop gate and CI (`d748c68`), with a negative check.
- The backlog was corrected twice (`e6e238f`, `2cf3748`).
