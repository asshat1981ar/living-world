#!/usr/bin/env python3
"""One command for "is the tree green?", built for the feature loop (.claude/skills/feature-loop).

    python3 -I tools/verify.py                 # Gradle check (no lint) + 3 goldens on awsbox, invariants + tool tests here
    python3 -I tools/verify.py --tasks ':game-core:jvmTest'   # narrower Gradle run
    python3 -I tools/verify.py --no-goldens    # skip the golden dumps (UI-only change)

Everything remote runs in ONE SSM call (push once, then Gradle and the default/turmoil/feud goldens back to back),
instead of the stop gate's separate calls. Exit codes: 0 all passed, 1 something failed, 2 BLOCKED (the AWS session is
expired or the box is unreachable): BLOCKED means nothing was verified, never treat it as a pass.
"""

import argparse
import importlib.util
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TASKS = "check -x lint -x lintDebug -x lintRelease --continue"
GOLDENS = {
    "default": "10 0",
    "turmoil": "10 0 --scenario=turmoil",
    "feud": "10 0 --scenario=feud",
}


def remote_script(gradle_cmd: str, tasks: str, goldens: bool) -> str:
    """The shell run on awsbox: each step prints `VERIFY <name> <exit>` so one call can report every check."""
    steps = [
        f"{gradle_cmd} {tasks} > /tmp/lw-verify-gradle.log 2>&1; echo \"VERIFY gradle $?\"",
        "grep -E '\\] FAILED|^> Task .*FAILED|What went wrong' /tmp/lw-verify-gradle.log | head -20",
    ]
    if goldens:
        for name, args in GOLDENS.items():
            log = f"/tmp/lw-verify-golden-{name}.log"
            steps.append(
                f"{gradle_cmd} :desktopApp:run --args='{args} --golden=tests/golden/{name}-10d.txt.gz' > {log} 2>&1; "
                f"if grep -q 'Golden *: OK' {log}; then echo \"VERIFY golden-{name} 0\"; "
                f"else echo \"VERIFY golden-{name} 1\"; grep -E 'Golden|mismatch|FAIL' {log} | head -5; fi"
            )
    return "; ".join(steps)


def parse_results(output: str) -> dict:
    """`VERIFY name code` lines -> {name: passed}."""
    results = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[0] == "VERIFY":
            results[parts[1]] = parts[2] == "0"
    return results


def session_ok(check=None) -> bool:
    """True when the AWS session can reach SSM; the loop must stop and ask for a login otherwise."""
    if check is not None:
        return check()
    p = subprocess.run(["aws", "sts", "get-caller-identity"], capture_output=True, text=True, check=False)
    return p.returncode == 0


def _lw_remote():
    spec = importlib.util.spec_from_file_location("lw_remote", ROOT / "tools" / "lw_remote.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _local(name: str, cmd: list) -> tuple:
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, check=False)
    tail = (p.stdout + p.stderr).strip().splitlines()[-3:]
    return name, p.returncode == 0, tail


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Verify the working tree: Gradle + goldens on awsbox, invariants + tool tests here")
    ap.add_argument("--tasks", default=DEFAULT_TASKS, help=f"Gradle tasks (default: {DEFAULT_TASKS})")
    ap.add_argument("--no-goldens", action="store_true", help="skip the default/turmoil/feud golden dumps")
    ap.add_argument("--no-remote", action="store_true", help="only the local checks")
    a = ap.parse_args(argv)

    results = []
    if not a.no_remote:
        if not session_ok():
            print("BLOCKED: the AWS session is expired; ask the user to log in (aws login --remote). Nothing was verified.")
            return 2
        remote = _lw_remote()
        remote.push()
        remote.wait_for_live_job()
        status, out, err = remote.run(remote_script(remote.GRADLE, a.tasks, not a.no_goldens))
        parsed = parse_results(out)
        if "gradle" not in parsed:
            print(f"BLOCKED: the remote run did not report ({status}): {err.strip()[-300:]}")
            return 2
        for name, passed in parsed.items():
            results.append((name, passed, []))
        details = [l for l in out.splitlines() if l and not l.startswith("VERIFY ")]
        if details:
            print("\n".join(details[:30]))

    results.append(_local("invariants", [sys.executable, "-I", "tools/check_invariants.py", "--all"]))
    results.append(_local("tool-tests", [sys.executable, "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"]))

    for name, passed, tail in results:
        print(f"{'PASS' if passed else 'FAIL'}  {name}")
        if not passed:
            for line in tail:
                print(f"      {line}")
    return 0 if all(passed for _, passed, _ in results) else 1


if __name__ == "__main__":
    sys.exit(main())
