---
name: feature-loop
description: Work the living-world backlog (docs/backlog.md) one item at a time - verify, test first, implement, verify, commit, update the queue. Use when asked to continue, work the backlog, or develop the next feature autonomously.
---

# Feature loop

One backlog item per pass, always leaving the tree green and committed. Repeat passes until the queue's next item needs
the user, then stop and say so.

## A pass

1. **Pick.** Read `docs/backlog.md`; take the top unchecked item. Skip items tagged `[human]`; for `[ask]` items stop and
   ask the user (one short question with a recommendation) before doing anything.
2. **Baseline.** `python3 -I tools/verify.py`. Exit 2 means BLOCKED (AWS session expired): stop and ask the user to log
   in; nothing was verified. Exit 1 on a clean tree means someone else broke it: fix that first, as its own commit.
3. **Test first.** Write the failing test for the item's behaviour and see it fail for the expected reason
   (`python3 -I tools/lw_remote.py --push --gradle ':<module>:jvmTest --tests <Class>'`). Doubtful existing behaviour you
   pin instead of fix: name the test `current_behaviour_*` with a `// Current behaviour, may change:` comment and list
   it in CLAUDE.md.
4. **Implement** the smallest change that passes. Match the surrounding code; G1-G10 (INSTRUCTIONS.md) always hold.
5. **Verify.** `python3 -I tools/verify.py` must exit 0. A golden failure means simulation behaviour changed: if the
   item is not tagged `golden`, the change is wrong; revert it. If it is, `python3 -I tools/regen_golden.py`, port the
   change to `lw_mirror/` until `python3 -I tools/mirror_parity.py` passes, and record new fingerprints in CLAUDE.md.
6. **Commit** locally (`type(scope): what and why`, the Co-Authored-By line). Update CLAUDE.md where the item changes a
   documented fact, and move the item to Done in `docs/backlog.md` with the commit hash.
7. **Report** one short paragraph: what changed, how it was verified, what is next.

## Always stop and ask before

- `git push` (ask as its own question, never bundled with "and start the next item").
- New dependencies or plugin versions (the commit hook requires `LW_ALLOW_DEPS=1`; only with the user's OK).
- Deleting files the user made, or anything outside the repo.
- Any change that alters the goldens when the item is not tagged `golden`.
- A design choice the item does not settle (offer a recommendation).

## Notes

- The stop gate's `warn` (infrastructure problem) is not a pass; only `tools/verify.py` exit 0 is.
- Builds run on awsbox through `tools/lw_remote.py`; one shared remote tree, so do not run two loops at once.
- Keep items small: if one grows past a pass, split it in `docs/backlog.md` and commit the finished part.
