---
name: awsbox-build
description: How to build, test and run living-world on awsbox (JDK 17, Android SDK 34, Gradle wrapper) over SSM. Use whenever a Gradle build, test run, simulation run or toolchain setup for living-world is needed; the phone has no JDK.
---

All Gradle work runs on **awsbox** (instance and region from `LW_AWSBOX_INSTANCE`/`LW_AWS_REGION` or the gitignored `.awsbox.json`) in `~/living-world`. The phone only edits.

- **Never** use `~/distill/foundry-dev/tools/ssm_deploy.py --push` for this repo: it replaces `~/foundry`. Use `tools/lw_remote.py`, which pushes the git-visible tree (`.gitignore` honoured) to `~/living-world`, verifies it by digest, and keeps the remote `.gradle/`, `build/` and `local.properties`.
- Main session: `python3 -I tools/lw_remote.py --push --gradle check` (or `:game-core:jvmTest`, `:desktopApp:run --args='30 10'`). `--run '<cmd>'` runs a shell command; `--pull REMOTE LOCAL` copies a small file back.
- Sub-agents: use the `lw-sim` MCP tools (`awsbox_build`, `run_simulation`, `determinism_benchmark`, `ledger_*`, `causal_trace`). The global fanout-guard blocks them from `aws`.
- Requires a live AWS session. On `Your session has expired`, follow the FIFO `aws login --remote` flow in the global awsbox-ops skill.
- Output is capped at about 24 KB per SSM call. `--gradle` prints the error/summary lines plus a tail.
- `lw_remote.py --gradle` waits while `/tmp/awsbox-live-job` exists (a live-model job owns the cores) and runs Gradle under `nice` with 3 workers, so `foundry-llama-cpu` keeps running.
- One-time toolchain setup (idempotent, sha256-checked): `python3 -I tools/lw_remote.py --push --run 'bash ~/living-world/tools/awsbox_provision.sh'`. Ask the user before running it again: it uses sudo and apt.
- The MCP server runs from `~/.local/share/lw-mcp-venv`. Recreate it with: `/usr/bin/python3 -m venv ~/.local/share/lw-mcp-venv && ~/.local/share/lw-mcp-venv/bin/pip install --no-cache-dir 'mcp>=1.2,<2'`. uv's cache corrupts packages under PRoot here, so don't use uv for this.
- At session end, check that the GPU box (`gpu_instance` in `.awsbox.json`) is still stopped.
