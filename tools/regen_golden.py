#!/usr/bin/env python3
"""Re-record tests/golden from the Kotlin kernel on awsbox (needs a live `aws login --remote` session).

    python3 tools/regen_golden.py            # all scenarios
    python3 tools/regen_golden.py feud       # one scenario

Run this only after an INTENDED change in kernel behaviour, then make `python3 tools/mirror_parity.py` pass again by updating
lw_mirror to match, and commit goldens and mirror together.
"""

from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# The scenario list lives in tests/golden/scenarios.json (one source for verify, the stop gate, regen, parity and CI).
def _load_scenarios(root):
    """The strict reader of tests/golden/scenarios.json (tools/scenarios_config.py), loaded by path because -I hides this directory."""
    spec = importlib.util.spec_from_file_location("scenarios_config", root / "tools" / "scenarios_config.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.load(root / "tests" / "golden" / "scenarios.json")


DAYS, _NAMES = _load_scenarios(ROOT)
SCENARIOS = {name: "" if name == "default" else f" --scenario={name}" for name in _NAMES}


def remote(*args: str) -> None:
    subprocess.run([sys.executable, "-I", str(ROOT / "tools" / "lw_remote.py"), *args], cwd=ROOT, check=True)


def main(argv=None) -> int:
    wanted = (argv if argv is not None else sys.argv[1:]) or list(SCENARIOS)
    unknown = [s for s in wanted if s not in SCENARIOS]
    if unknown:
        print(f"unknown scenario(s): {unknown}; choose from {list(SCENARIOS)}")
        return 2
    (ROOT / "tests" / "golden").mkdir(parents=True, exist_ok=True)
    for i, name in enumerate(wanted):
        dump = f"/tmp/lw-golden-{name}.txt"
        state_dump = f"/tmp/lw-golden-{name}.state.txt"
        rel = f"tests/golden/{name}-{DAYS}d.txt.gz"
        state_rel = f"tests/golden/{name}-{DAYS}d.state.txt.gz"
        remote(*(["--push"] if i == 0 else []), "--gradle",
               f":desktopApp:run --args='{DAYS} 0{SCENARIOS[name]} --dump={dump} --state-dump={state_dump}'", "--tail", "5")
        remote("--run", f"mkdir -p ~/living-world/tests/golden && gzip -9 -n -c {dump} > ~/living-world/{rel}"
                        f" && gzip -9 -n -c {state_dump} > ~/living-world/{state_rel}")
        remote("--pull", rel, rel)
        remote("--pull", state_rel, state_rel)
    print("goldens re-recorded; now run: python3 tools/mirror_parity.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
