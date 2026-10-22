---
name: sprint-loop
description: Run living-world as short sprints over docs/backlog.md - set a sprint goal, research what is unknown, build items one at a time with feature-loop, review and retrospect, adjust the next sprint, and attack your own work adversarially. Use when asked to work autonomously over several goals, run a sprint, or keep building.
---

# Sprint loop

A sprint is 1-5 backlog items that serve one stated goal. `feature-loop` does each item; this skill decides *which*
items, *what to learn first*, *when to stop*, and *what to change about the process*. Never run two loops at once (one
shared remote tree).

## 0. Orient (every sprint, 2 minutes)

`git fetch`, `git status -sb`, `docs/handoff/last.md`, `docs/backlog.md`, then `python3 -I tools/verify.py`.
- Exit 2 (BLOCKED): stop, ask the user for `! aws login --remote`. Nothing was verified.
- Red on a clean tree, or someone else's commit broke it: the sprint goal is "green again" and nothing else starts
  (find the cause with `git show` / `git log -S`; check for weakened assertions and for reducers that emit events).
- Incoming commits from another agent: read their diffs for weakened tests and `[ask]` work done unasked before you
  build on them.

## 1. Plan

- Write the **sprint goal** as one sentence of user-visible or invariant-visible outcome, and a **definition of done**:
  `verify.py` exit 0, new behaviour has a test that failed first, goldens/mirror/CLAUDE.md updated if `golden`.
- Pick items by value and risk from the top of the backlog. Size by the last two sprints' actual throughput, not hope:
  if the last sprint finished fewer items than planned, plan fewer.
- Items tagged `[ask]` / `[human]`, new dependencies, `git push`, or anything touching goldens without a `golden` tag
  are **not** in the sprint until the user says yes. Ask up front, with a recommendation, and plan around "no".
  `git push` stays its own separate question, as in `feature-loop`; a sprint ends with commits, not a push.
- At most **3 sprints per invocation**, then report and let the user steer.

## 2. Research spike (only for unknowns)

Time-box a spike when an item depends on a fact you do not hold (a library API, a tuning constant, a design choice).
Prefer cheap and local first: read the code, prototype on `lw_mirror` (it runs 90 days in seconds), query docs
(context7, primary sources). Fan out to a subagent only for independent, parallel reading (`Explore`) or an independent
check (`invariant-auditor`, `test-verifier`, `ecc:kotlin-reviewer`). Write the finding where the next reader will look
(CLAUDE.md or the item), with the date. A spike that does not change the plan was too big; cut it.

## 3. Build

Per item, run `feature-loop` unchanged. Between items, **self-adjust** by checking these signals and acting:

| Signal | Adjustment |
|---|---|
| An item took more than 2x its size | split it, commit the finished part, re-plan the rest |
| The same test or hook fails twice for the same reason | stop patching; find the cause (`systematic-debugging`) |
| A golden changed and the item is not `golden` | revert; the change is wrong |
| Verify is slow or flaky | run the targeted test while iterating, full `verify.py` before each commit |
| A new fact contradicts CLAUDE.md | fix CLAUDE.md in the same commit |
| The goal changed because of what you learned | amend the sprint goal in writing, say why |

## 4. Review (end of sprint)

Demonstrate with evidence: verify output, golden diffs, the new test names. State what is *not* proven (for example a
path no golden exercises). Update `docs/backlog.md` (done, split, new items found) and `docs/handoff/last.md`.

## 5. Adversarial pass (mandatory, before the retro)

Attack the sprint's own work as a hostile reviewer. Each attack needs a command or a test, not an opinion.
1. **Fake green:** would the new tests still pass if the feature were deleted or half-wrong? Mutate the code (flip a
   constant, drop a branch) and confirm a test fails. A passing golden proves nothing about paths it does not run.
2. **Invariants G1-G10:** `invariant-auditor`; replay the ledger and compare `StateDigest`; coins and items conserved.
3. **Boundaries:** zero, negative, huge, dead, unknown ids, saves from the previous schema version.
4. **Process:** did anything run unasked, bypass a hook, or widen scope? Did any assertion get weaker?
5. **Cost of being wrong:** what is the quickest way a user would notice this is broken?
Two rules learned the hard way: a verification command that prints no result line (expired AWS token, crashed
tool) is BLOCKED, not a pass, so grep for the error as well as the verdict; and mutate in the foreground and
`git checkout` the file before ending the turn, because the stop gate pushes whatever is on disk and will fail on a
live mutant.
Fix what you find as its own commit, or put it in the backlog with the evidence. Report findings plainly even if none.

## 6. Retrospective (3 lines, written down)

What slowed us, what to keep, what to change in this file or the backlog. When a rule failed, *propose* the edit to this
skill in the report; apply it only if the user agrees (a loop that rewrites its own rules unasked is the scope creep
step 5.4 hunts). Backlog and handoff edits are fine. Then start the next sprint at step 0, unless a stop condition holds.

## Stop and hand back when

The next item is `[ask]`/`[human]`; verify is BLOCKED; two sprints in a row ended with a lower item count than
planned (the plan is wrong, not the effort: ask the user); or a design choice is not settled by the backlog.
