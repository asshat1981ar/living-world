#!/usr/bin/env python3
"""Claude Code SessionStart hook: tell Claude (stdout becomes context) whether awsbox is reachable before any work.

An expired AWS session otherwise surfaces mid-task, after a push or build has already failed. Also reports the GPU
box if it is running, since it costs money and should normally be stopped. Never blocks; every failure is reported
as a plain line.
"""

from __future__ import annotations

import json
import subprocess
import sys

REGION = "us-east-2"
GPU_INSTANCE = "i-03f2750336b3e1f62"
LOGIN_HINT = (
    "AWS session expired: Gradle/awsbox work (tools/lw_remote.py) will fail. Start the FIFO flow from the awsbox-ops "
    "skill (`aws login --remote` fed through a FIFO). Each login attempt has its own URL; a pasted code's trailing "
    "base64 must decode to that attempt's `state=`, so check that before submitting it."
)


def run(argv: list[str], timeout: int = 25) -> tuple[int, str]:
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        return 127, "aws CLI not found"
    except subprocess.TimeoutExpired:
        return 124, "timed out"
    return p.returncode, (p.stdout + p.stderr).strip()


def preflight(runner=run) -> list[str]:
    code, out = runner(["aws", "sts", "get-caller-identity", "--output", "json"])
    if code != 0:
        if "expired" in out.lower() or "reauthenticate" in out.lower() or "login" in out.lower():
            return [LOGIN_HINT]
        return [f"preflight: could not check AWS ({out[-160:]}); awsbox may be unreachable."]
    lines = ["preflight: AWS session OK."]
    code, out = runner(["aws", "ec2", "describe-instances", "--region", REGION, "--instance-ids", GPU_INSTANCE,
                        "--query", "Reservations[].Instances[].State.Name", "--output", "json"])
    if code == 0:
        try:
            states = json.loads(out)
        except ValueError:
            states = []
        if "running" in states or "pending" in states:
            lines.append(f"WARNING: the GPU box {GPU_INSTANCE} is running and costs money; stop it unless a job needs it.")
    return lines


def main() -> int:
    sys.stdin.read()
    print("\n".join(preflight()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
