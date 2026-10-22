"""tests/golden/scenarios.json is the one list of golden scenarios; everything that names a scenario must agree with it.

Adding a scenario used to mean editing seven files and hoping none was missed. Now it means editing the JSON, the Kotlin
runner (`InspectorMain`), the mirror runner and recording the goldens - and this test says which of those was forgotten.
"""
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "tests" / "golden"
CONFIG = json.loads((GOLDEN / "scenarios.json").read_text())
NAMES = CONFIG["scenarios"]
DAYS = CONFIG["days"]


def _load(name, path):
    """Import a tool by path, leaving sys.modules and sys.path as they were."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    previous = sys.modules.get(name)
    sys.modules[name] = module  # a dataclass in the module looks itself up here while it is being created
    sys.path.insert(0, str(path.parent))  # hooks import their sibling `telemetry`
    try:
        spec.loader.exec_module(module)
    finally:
        sys.path.pop(0)
        if previous is None:
            sys.modules.pop(name, None)
        else:
            sys.modules[name] = previous
    return module


# Files every reader must refuse, whatever it is: shared by the jq/shell validator and the Python loader.
BAD_CONFIGS = {
    "empty list": '{"days": 10, "scenarios": []}',
    "no scenarios key": '{"days": 10}',
    "scenarios is a string": '{"days": 10, "scenarios": "default"}',
    "a number among the names": '{"days": 10, "scenarios": ["default", 5]}',
    "a name with a space": '{"days": 10, "scenarios": ["a b"]}',
    "a name that is a shell command": '{"days": 10, "scenarios": ["x;touch pwned"]}',
    "an upper-case name": '{"days": 10, "scenarios": ["Default"]}',
    "a name with an embedded newline": '{"days": 10, "scenarios": ["default\\n"]}',
    "a name with a trailing newline plus another": '{"days": 10, "scenarios": ["a\\nb"]}',
    "a duplicated name": '{"days": 10, "scenarios": ["default", "default"]}',
    "days is a string": '{"days": "10", "scenarios": ["default"]}',
    "days is fractional": '{"days": 10.5, "scenarios": ["default"]}',
    "days is a float spelled 10.0": '{"days": 10.0, "scenarios": ["default"]}',
    "days is true": '{"days": true, "scenarios": ["default"]}',
    "days is zero": '{"days": 0, "scenarios": ["default"]}',
    "days is negative": '{"days": -3, "scenarios": ["default"]}',
    "no days": '{"scenarios": ["default"]}',
    "not json": "not json",
    "empty file": "",
}


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



@unittest.skipUnless(shutil.which("jq"), "jq is needed (CI's ubuntu-latest has it)")
class ListScenariosScriptTests(unittest.TestCase):
    """The CI golden step captures tools/list_scenarios.sh under set -e: every bad file must fail it and print nothing."""

    def run_script(self, content):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "scenarios.json"
            f.write_text(content)
            return subprocess.run(["bash", str(ROOT / "tools" / "list_scenarios.sh"), str(f)],
                                  capture_output=True, text=True, check=False)

    def test_the_real_file_prints_the_days_then_every_scenario(self):
        run = subprocess.run(["bash", str(ROOT / "tools" / "list_scenarios.sh"), str(GOLDEN / "scenarios.json")],
                             capture_output=True, text=True, check=False)
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertEqual([str(DAYS)] + NAMES, run.stdout.split())

    def test_every_kind_of_bad_file_fails_and_prints_nothing(self):
        for label, content in BAD_CONFIGS.items():
            with self.subTest(label):
                run = self.run_script(content)
                self.assertNotEqual(0, run.returncode, f"accepted: {label}")
                self.assertEqual("", run.stdout, "a rejected file must print nothing")

    def test_a_missing_file_fails_too(self):
        run = subprocess.run(["bash", str(ROOT / "tools" / "list_scenarios.sh"), "/nonexistent/scenarios.json"],
                             capture_output=True, text=True, check=False)
        self.assertNotEqual(0, run.returncode)

    def test_ci_uses_the_script_under_set_e(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("config=$(bash tools/list_scenarios.sh tests/golden/scenarios.json)", workflow)
        self.assertIn("set -euo pipefail", workflow)


class StrictLoaderTests(unittest.TestCase):
    """tools/scenarios_config.py is the one Python reader: it refuses exactly what the shell validator refuses."""

    def test_the_real_file_loads(self):
        loader = _load("scenarios_config", ROOT / "tools" / "scenarios_config.py")
        self.assertEqual((DAYS, NAMES), loader.load(GOLDEN / "scenarios.json"))

    def test_every_kind_of_bad_file_is_refused(self):
        loader = _load("scenarios_config", ROOT / "tools" / "scenarios_config.py")
        for label, content in BAD_CONFIGS.items():
            with self.subTest(label), tempfile.TemporaryDirectory() as d:
                f = Path(d) / "scenarios.json"
                f.write_text(content)
                with self.assertRaises(ValueError, msg=f"accepted: {label}"):
                    loader.load(f)

    def test_a_missing_file_is_refused(self):
        loader = _load("scenarios_config", ROOT / "tools" / "scenarios_config.py")
        with self.assertRaises(ValueError):
            loader.load("/nonexistent/scenarios.json")

    def test_every_consumer_reads_through_it(self):
        for tool in ("verify.py", "regen_golden.py", "mirror_parity.py", "hooks/stop_gate.py"):
            self.assertIn("scenarios_config", (ROOT / "tools" / tool).read_text(), f"{tool} has its own reader")


class GoldenSetTests(unittest.TestCase):
    """mirror_parity must see exactly the configured goldens, at the configured length."""

    def setUp(self):
        sys.path.insert(0, str(ROOT / "tools"))
        try:
            import mirror_parity
        finally:
            sys.path.pop(0)
        self.check = mirror_parity.golden_set_problem

    def test_exact_sets_pass(self):
        both = {("default", 10), ("feud", 10)}
        self.assertIsNone(self.check(["default", "feud"], 10, both, both))

    def test_a_scenario_missing_from_both_sets_is_a_problem(self):
        only = {("default", 10)}
        self.assertIsNotNone(self.check(["default", "feud"], 10, only, only))

    def test_a_golden_of_another_length_is_a_problem(self):
        self.assertIsNotNone(self.check(["default"], 10, {("default", 7)}, {("default", 7)}))

    def test_an_extra_unlisted_golden_is_a_problem(self):
        got = {("default", 10), ("ghost", 10)}
        self.assertIsNotNone(self.check(["default"], 10, got, got))

    def test_the_filter_narrows_the_expectation(self):
        one = {("feud", 10)}
        self.assertIsNone(self.check(["default", "feud"], 10, one, one, only="feud"))

    def test_events_without_state_is_a_problem(self):
        self.assertIsNotNone(self.check(["default"], 10, {("default", 10)}, set()))


class CiDeterminismTests(unittest.TestCase):
    """CI must repeat the runs the stop gate repeats (G8), with the same arguments."""

    def test_ci_runs_the_stop_gates_determinism_scenarios_twice(self):
        stop_gate = _load("stop_gate", ROOT / "tools" / "hooks" / "stop_gate.py")
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("Determinism (G8)", workflow)
        for _, args in stop_gate.DETERMINISM_SCENARIOS:
            spec = args.format(days=stop_gate.DETERMINISM_DAYS)
            self.assertIn(f'"{spec}"', workflow, f"CI does not run {spec!r} for the determinism check")
        self.assertIn("first=$(", workflow)
        self.assertIn("second=$(", workflow)


class StopGateFailsClosedTests(unittest.TestCase):
    """A hook that raises exits 1, which Claude Code does not treat as a block: a bad scenario file must BLOCK instead."""

    def gate_with_file(self, content):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            hooks = root / "tools" / "hooks"
            hooks.mkdir(parents=True)
            for f in (ROOT / "tools" / "hooks").glob("*.py"):
                shutil.copy(f, hooks / f.name)
            if content is not None:
                (root / "tests" / "golden").mkdir(parents=True)
                (root / "tests" / "golden" / "scenarios.json").write_text(content)
            gate = _load("stop_gate_probe", hooks / "stop_gate.py")  # importing it must not raise
            return gate.SCENARIOS_ERROR, gate.golden_check()

    def test_a_missing_or_bad_scenario_file_blocks_instead_of_crashing(self):
        for label, content in {"missing": None, "junk": "not json", "empty list": '{"days": 10, "scenarios": []}',
                               "zero days": '{"days": 0, "scenarios": ["default"]}',
                               "no days": '{"scenarios": ["default"]}'}.items():
            with self.subTest(label):
                error, outcome = self.gate_with_file(content)
                self.assertTrue(error, "the problem should be recorded")
                self.assertFalse(outcome.ok, "the golden check must not pass")
                self.assertIn("cannot check the goldens", outcome.output)


if __name__ == "__main__":
    unittest.main()
