"""lw-sim: MCP server exposing living-world build/simulation/ledger tools (INSTRUCTIONS.md §4 Step 3).

Every tool runs a FIXED, allowlisted command; inputs are validated (enums, bounded ints, slug names), never passed as
free shell. Builds and simulations run on awsbox through tools/lw_remote.py (SSM). This is the sanctioned way for
sub-agents to build remotely: the global fanout-guard hook blocks them from calling `aws` / deploy scripts directly.

Runtime: a dedicated venv with the `mcp` SDK (see .mcp.json and the awsbox-build skill):
    /usr/bin/python3 -m venv ~/.local/share/lw-mcp-venv && ~/.local/share/lw-mcp-venv/bin/pip install --no-cache-dir 'mcp>=1.2,<2'
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Literal

from mcp.server.fastmcp import FastMCP

REPO = Path(__file__).resolve().parents[2]
REMOTE = [sys.executable, "-I", str(REPO / "tools" / "lw_remote.py")]
SAVE_DIR = "build/lw-saves"
SLUG = re.compile(r"^[a-z0-9][a-z0-9_-]{0,40}$")
BuildTask = Literal[
    "check",
    ":game-core:jvmTest",
    ":game-persistence:jvmTest",
    ":game-ai:jvmTest",
    ":game-ui:jvmTest",
    ":androidApp:testDebugUnitTest",
    "assemble",
]

mcp = FastMCP("lw-sim")


def _remote(*args: str, timeout: int = 3600) -> tuple[int, str]:
    p = subprocess.run([*REMOTE, *args], cwd=REPO, capture_output=True, text=True, timeout=timeout)
    out = (p.stdout + ("\n[stderr] " + p.stderr[-1500:] if p.stderr.strip() else "")).strip()
    return p.returncode, out[-20000:]


def _slug(name: str) -> str:
    if not SLUG.match(name):
        raise ValueError("save name must match [a-z0-9][a-z0-9_-]{0,40}")
    return name


def _ledger(*args: str) -> dict:
    code, out = _remote("--run", "cd ~/living-world && python3 -I tools/ledger_tools.py " + " ".join(args))
    try:
        return json.loads(out[out.index("{"):out.rindex("}") + 1])
    except ValueError:
        return {"error": out[-3000:], "exit": code}


def _simulate(days: int, tail: int, save: str | None) -> tuple[int, str]:
    extra = f" --save={SAVE_DIR}/{save}.json" if save else ""
    # --args is consumed by the remote shell; all parts are validated ints / slugs.
    return _remote("--push", "--gradle", f":desktopApp:run --args='{days} {tail}{extra}'", "--tail", "200")


@mcp.tool()
def awsbox_build(task: BuildTask = "check") -> str:
    """Push the working tree to awsbox and run one allowlisted Gradle task there; returns the build summary and log tail."""
    code, out = _remote("--push", "--gradle", task)
    return f"exit={code}\n{out}"


@mcp.tool()
def run_simulation(days: int = 30, tail: int = 10, save_name: str | None = None) -> str:
    """Run the headless simulation inspector (:desktopApp:run) for `days` (1-365) on awsbox. Prints the event census,
    the deterministic fingerprint and the last `tail` (0-50) causal cards. With `save_name`, also writes the save envelope
    to build/lw-saves/<save_name>.json on awsbox for ledger_summary / ledger_diff / causal_trace."""
    if not 1 <= days <= 365 or not 0 <= tail <= 50:
        raise ValueError("days must be 1..365 and tail 0..50")
    code, out = _simulate(days, tail, _slug(save_name) if save_name else None)
    return f"exit={code}\n{out}"


@mcp.tool()
def ledger_summary(save_name: str) -> dict:
    """Event counts by type, id range and duplicate ids for a save written by run_simulation."""
    return _ledger("summary", f"{SAVE_DIR}/{_slug(save_name)}.json")


@mcp.tool()
def ledger_diff(old_save: str, new_save: str) -> dict:
    """G5 append-only check between two saves: removed or rewritten historical events are violations."""
    return _ledger("diff", f"{SAVE_DIR}/{_slug(old_save)}.json", f"{SAVE_DIR}/{_slug(new_save)}.json")


@mcp.tool()
def causal_trace(save_name: str, event_id: int) -> dict:
    """G6 causal chain (root first) ending at `event_id`, the same walk as EventLedger.traceCausalChain."""
    if event_id < 1:
        raise ValueError("event_id must be >= 1")
    return _ledger("trace", f"{SAVE_DIR}/{_slug(save_name)}.json", str(int(event_id)))


@mcp.tool()
def determinism_benchmark(days: int = 30, runs: int = 3) -> dict:
    """G8 check: run the same simulation `runs` (2-5) times on awsbox; all FNV-1a fingerprints must match.
    Also reports simulation wall time per run."""
    if not 1 <= days <= 365 or not 2 <= runs <= 5:
        raise ValueError("days must be 1..365 and runs 2..5")
    prints, walls, failures = [], [], []
    for i in range(runs):
        code, out = _simulate(days, 0, None)
        fp = re.search(r"Fingerprint\s*:\s*(\S+)", out)
        wall = re.search(r"Wall time\s*:\s*(\d+)ms", out)
        if code or not fp:
            failures.append({"run": i + 1, "exit": code, "tail": out[-1500:]})
            continue
        prints.append(fp.group(1))
        walls.append(int(wall.group(1)) if wall else None)
    return {
        "days": days,
        "runs": runs,
        "fingerprints": prints,
        "deterministic": bool(prints) and len(set(prints)) == 1 and not failures,
        "wall_ms": walls,
        "failures": failures,
    }


if __name__ == "__main__":
    mcp.run()
