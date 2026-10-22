"""tests/golden/scenarios.json is the one list of golden scenarios; everything that names a scenario must agree with it.

Adding a scenario used to mean editing seven files and hoping none was missed. Now it means editing the JSON, the Kotlin
runner (`InspectorMain`), the mirror runner and recording the goldens - and this test says which of those was forgotten.
"""
import importlib.util
import json
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "tests" / "golden"
CONFIG = json.loads((GOLDEN / "scenarios.json").read_text())
NAMES = CONFIG["scenarios"]
DAYS = CONFIG["days"]


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module  # a dataclass in the module looks itself up here
    sys.path.insert(0, str(path.parent))  # hooks import their sibling `telemetry`
    try:
        spec.loader.exec_module(module)
    finally:
        sys.path.pop(0)
    return module


class ScenarioListTests(unittest.TestCase):
    def test_the_list_is_sane(self):
        self.assertIn("default", NAMES)
        for name in NAMES:  # names reach the shell and the Gradle arguments: plain words only
            self.assertRegex(name, r"^[a-z][a-z0-9_]*$")
        self.assertEqual(len(NAMES), len(set(NAMES)), "a scenario is listed twice")
        self.assertGreater(DAYS, 0)

    def test_every_scenario_has_both_goldens_and_no_golden_is_orphaned(self):
        expected = {f"{n}-{DAYS}d.txt.gz" for n in NAMES} | {f"{n}-{DAYS}d.state.txt.gz" for n in NAMES}
        found = {p.name for p in GOLDEN.glob("*.txt.gz")}
        self.assertEqual(set(), expected - found, "a scenario has no golden: run tools/regen_golden.py")
        self.assertEqual(set(), found - expected, "a golden belongs to no listed scenario")

    def test_the_tools_use_the_same_list(self):
        verify = _load("verify", ROOT / "tools" / "verify.py")
        regen = _load("regen_golden", ROOT / "tools" / "regen_golden.py")
        stop_gate = _load("stop_gate", ROOT / "tools" / "hooks" / "stop_gate.py")
        self.assertEqual(NAMES, list(verify.GOLDENS))
        self.assertEqual(DAYS, verify.DAYS)
        script = verify.remote_script("G", "check", goldens=True)
        for name in NAMES:
            self.assertIn(f"{name}-{DAYS}d.txt.gz", script)
            self.assertIn(f"{name}-{DAYS}d.state.txt.gz", script)
        self.assertEqual(NAMES, list(regen.SCENARIOS))
        self.assertEqual([f"golden {n}" for n in NAMES], [label for label, _ in stop_gate.GOLDEN_RUNS])
        for name, (_, args) in zip(NAMES, stop_gate.GOLDEN_RUNS):
            self.assertIn(f"--golden=tests/golden/{name}-{DAYS}d.txt.gz", args)
            self.assertIn(f"--state-golden=tests/golden/{name}-{DAYS}d.state.txt.gz", args)

    def test_the_kotlin_runner_knows_every_scenario(self):
        source = (ROOT / "desktopApp/src/jvmMain/kotlin/com/livingworld/game/desktop/InspectorMain.kt").read_text()
        for name in NAMES:
            if name != "default":
                self.assertRegex(source, rf'"{name}"\s*->', f"InspectorMain has no branch for scenario {name}")

    def test_the_mirror_runner_knows_every_scenario(self):
        sys.path.insert(0, str(ROOT))
        try:
            from lw_mirror import runner
            for name in NAMES:
                runner._final_world(name, 0)  # zero days: seeds the scenario, runs nothing
            with self.assertRaises(ValueError):
                runner._final_world("not_a_scenario", 0)
        finally:
            sys.path.pop(0)

    def test_ci_reads_the_list_instead_of_repeating_it(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("tests/golden/scenarios.json", workflow)
        loop = re.search(r"for s in ([^;]*); do", workflow)
        self.assertIsNotNone(loop, "CI has no scenario loop")
        self.assertNotIn("turmoil", loop.group(1), "CI repeats the scenario names instead of reading the file")


if __name__ == "__main__":
    unittest.main()
