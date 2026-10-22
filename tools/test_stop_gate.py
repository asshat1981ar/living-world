"""Tests for tools/hooks/stop_gate.py decision logic.  Run: python3 -m unittest tools/test_stop_gate.py"""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HOOK = Path(__file__).resolve().parent / "hooks" / "stop_gate.py"
spec = importlib.util.spec_from_file_location("stop_gate", HOOK)
sg = importlib.util.module_from_spec(spec)
sys.modules["stop_gate"] = sg
spec.loader.exec_module(sg)
OK, FAIL = sg.Outcome(True, "fine"), sg.Outcome(False, "line1\nAssertionError: boom")
INFRA = sg.Outcome(False, "aws ssm send-command failed: session expired", infra=True)


class StopGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state = Path(self.tmp.name, "last-green")
        self.det_state = Path(self.tmp.name, "last-green-determinism")
        self.calls: list[str] = []

    def decide(self, *args, **kwargs):
        """decide() with the determinism marker kept inside the temp directory."""
        kwargs.setdefault("det_state", self.det_state)
        return sg.decide(*args, **kwargs)

    def tearDown(self):
        self.tmp.cleanup()

    def checks(self, inv=OK, tools=OK, kotlin=OK, det=OK, digest="d1"):
        def track(name, value):
            def f():
                self.calls.append(name)
                return value
            return f
        return {"invariants": track("inv", inv), "tool tests": track("tools", tools),
                "kotlin": track("kotlin", kotlin), "determinism": track("det", det),
                "digest": lambda: digest}

    def test_all_green_allows_and_records_digest(self):
        self.assertIsNone(self.decide({}, True, self.checks(), self.state))
        self.assertEqual("d1", self.state.read_text().strip())

    def test_invariant_failure_blocks_before_anything_else(self):
        out = self.decide({}, True, self.checks(inv=FAIL), self.state)
        self.assertEqual("block", out["decision"])
        self.assertIn("invariants failed", out["reason"])
        self.assertEqual(["inv"], self.calls)

    def test_tool_test_failure_blocks_with_output(self):
        out = self.decide({}, True, self.checks(tools=FAIL), self.state)
        self.assertIn("AssertionError: boom", out["reason"])

    def test_kotlin_skipped_when_unchanged(self):
        self.state.write_text("d1\n")
        self.assertIsNone(self.decide({}, True, self.checks(kotlin=FAIL), self.state))
        self.assertNotIn("kotlin", self.calls)

    def test_kotlin_failure_blocks_and_keeps_old_digest(self):
        self.state.write_text("old\n")
        out = self.decide({}, True, self.checks(kotlin=FAIL, digest="new"), self.state)
        self.assertEqual("block", out["decision"])
        self.assertEqual("old", self.state.read_text().strip())

    def test_infra_failure_warns_without_blocking(self):
        out = self.decide({}, True, self.checks(kotlin=INFRA), self.state)
        self.assertNotIn("decision", out)
        self.assertIn("could not run", out["systemMessage"])
        self.assertFalse(self.state.exists())

    def test_remote_tier_can_be_disabled(self):
        self.assertIsNone(self.decide({}, False, self.checks(kotlin=FAIL), self.state))
        self.assertNotIn("kotlin", self.calls)

    def test_pre_push_gate_also_proves_determinism_and_the_goldens(self):
        self.assertIsNone(self.decide({}, True, self.checks(), self.state))
        self.assertIn("det", self.calls, "the pre-push gate must run the G8/golden tier")

    def test_a_determinism_failure_blocks_the_push(self):
        out = self.decide({}, True, self.checks(det=FAIL), self.state)
        self.assertEqual("block", out["decision"])
        self.assertIn("determinism", out["reason"])

    # The pre-push gate (decide) must not remember "green" for code whose G8/golden tier did not pass. It used to write
    # the marker before that tier ran, so a blocked push went through when retried (demonstrated in the 2026-10-22 review).
    def test_a_blocked_push_is_blocked_again_when_retried_on_the_same_code(self):
        first = self.decide({}, True, self.checks(det=FAIL), self.state)
        self.assertEqual("block", first["decision"])
        self.calls.clear()
        second = self.decide({}, True, self.checks(det=FAIL), self.state)
        self.assertEqual("block", second["decision"], "the retry of a blocked push must not be allowed")
        self.assertIn("det", self.calls, "the determinism tier must run again on the retry")

    def test_a_determinism_infra_failure_is_retried_on_the_next_push(self):
        first = self.decide({}, True, self.checks(det=INFRA), self.state)
        self.assertIn("could not run", first["systemMessage"])
        self.calls.clear()
        second = self.decide({}, True, self.checks(det=OK), self.state)
        self.assertIsNone(second)
        self.assertEqual(["inv", "tools", "det"], self.calls, "only the determinism tier should have been repeated")
        self.assertEqual("d1", self.det_state.read_text().strip())

    def test_a_passing_push_is_remembered_so_the_next_one_is_cheap(self):
        self.assertIsNone(self.decide({}, True, self.checks(), self.state))
        self.calls.clear()
        self.assertIsNone(self.decide({}, True, self.checks(kotlin=FAIL, det=FAIL), self.state))
        self.assertEqual(["inv", "tools"], self.calls, "unchanged code that already passed should skip both slow tiers")

    def test_new_code_after_a_pass_runs_both_slow_tiers_again(self):
        self.assertIsNone(self.decide({}, True, self.checks(digest="d1"), self.state))
        self.calls.clear()
        self.assertIsNone(self.decide({}, True, self.checks(digest="d2"), self.state))
        self.assertIn("kotlin", self.calls)
        self.assertIn("det", self.calls)

    def test_a_kotlin_infra_failure_does_not_mark_anything_green(self):
        self.decide({}, True, self.checks(kotlin=INFRA), self.state)
        self.assertFalse(self.state.exists())
        self.assertFalse(self.det_state.exists())

    def test_a_determinism_infra_failure_only_warns(self):
        out = self.decide({}, True, self.checks(det=INFRA), self.state)
        self.assertNotIn("decision", out)
        self.assertIn("could not run", out["systemMessage"])

    def test_gradle_verdict_vs_infra_classification(self):
        orig = sg._run
        try:
            sg._run = lambda argv, timeout: sg.Outcome(False, "BUILD FAILED\nGRADLE_EXIT 1")
            self.assertFalse(sg.gradle_check().infra)
            sg._run = lambda argv, timeout: sg.Outcome(False, "aws ssm failed: session expired")
            self.assertTrue(sg.gradle_check().infra)
        finally:
            sg._run = orig

    def test_a_warning_is_logged_with_its_reason(self):
        # 18 of 72 runs were "warn" with no reason recorded; the log must say why a turn went unverified.
        from io import StringIO
        from unittest import mock
        logged = []
        answer = {"systemMessage": "stop gate: Kotlin tests (:game-core:jvmTest) on awsbox could not run: session expired"}
        with mock.patch.object(sg, "decide_quick", lambda data, run_remote: answer), \
                mock.patch.object(sg.telemetry, "record", lambda *a, **kw: logged.append((a, kw))), \
                mock.patch("sys.stdin", StringIO(json.dumps({"hook_event_name": "Stop"}))), \
                mock.patch("sys.stdout", StringIO()):
            sg.main()
        (args, kwargs), = logged
        self.assertEqual("warn", args[1])
        self.assertIn("session expired", kwargs.get("reason", ""))

    def test_garbage_input_allows(self):
        p = subprocess.run([sys.executable, "-I", str(HOOK)], input="nope", text=True, capture_output=True)
        self.assertEqual((0, ""), (p.returncode, p.stdout))


class QuickTierTests(unittest.TestCase):
    ALL = {m: "d-" + m for m in ("game-core", "game-persistence", "game-ai", "game-ui", "androidApp", "desktopApp", "build")}

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state = Path(self.tmp.name, "quick.json")
        self.kotlin_calls: list[str] = []
        self.det_calls = 0
        self.digests = dict(self.ALL)
        self.kotlin_result = OK
        self.det_result = OK

    def tearDown(self):
        self.tmp.cleanup()

    def checks(self):
        def kotlin(tasks):
            self.kotlin_calls.append(tasks)
            return self.kotlin_result

        def det():
            self.det_calls += 1
            return self.det_result
        return {"invariants": lambda: OK, "tool tests": lambda: OK, "kotlin": kotlin,
                "digests": lambda: dict(self.digests), "determinism": det}

    def run_gate(self, remote=True):
        return sg.decide_quick({}, remote, checks=self.checks(), state=self.state)

    def test_module_mapping_and_task_selection(self):
        self.assertEqual(sg.module_of("game-core/src/x.kt"), "game-core")
        self.assertEqual(sg.module_of("build.gradle.kts"), "build")
        self.assertEqual(sg.module_of("gradle/wrapper/gradle-wrapper.properties"), "build")
        self.assertIsNone(sg.quick_tasks(set()))
        self.assertEqual(sg.quick_tasks({"game-persistence"}), ":game-persistence:jvmTest")
        self.assertEqual(sg.quick_tasks({"game-ai", "game-ui"}), ":game-ai:jvmTest :game-ui:jvmTest")
        self.assertEqual(sg.quick_tasks({"game-core"}).count("jvmTest"), 4)
        for m in ("build", "androidApp", "desktopApp"):
            self.assertEqual(sg.quick_tasks({m, "game-ai"}), "check")

    def test_first_run_checks_everything_then_unchanged_run_is_free(self):
        self.assertIsNone(self.run_gate())
        self.assertEqual(self.kotlin_calls, ["check"])
        self.assertEqual(self.det_calls, 1)
        self.assertIsNone(self.run_gate())
        self.assertEqual((len(self.kotlin_calls), self.det_calls), (1, 1))

    def test_single_module_change_runs_only_its_tests_and_skips_determinism(self):
        self.run_gate()
        self.digests["game-persistence"] = "changed"
        self.assertIsNone(self.run_gate())
        self.assertEqual(self.kotlin_calls[-1], ":game-persistence:jvmTest")
        self.assertEqual(self.det_calls, 1)

    def test_kernel_change_runs_all_jvm_tests_and_determinism(self):
        self.run_gate()
        self.digests["game-core"] = "changed"
        self.assertIsNone(self.run_gate())
        self.assertEqual(self.kotlin_calls[-1].count("jvmTest"), 4)
        self.assertEqual(self.det_calls, 2)

    def test_failing_tests_block_and_are_retried_next_time(self):
        self.kotlin_result = FAIL
        answer = self.run_gate()
        self.assertEqual(answer["decision"], "block")
        self.assertEqual(self.det_calls, 0)
        self.kotlin_result = OK
        self.assertIsNone(self.run_gate())
        self.assertEqual(len(self.kotlin_calls), 2)

    def test_infra_failure_warns_and_skips_determinism(self):
        self.kotlin_result = INFRA
        answer = self.run_gate()
        self.assertNotIn("decision", answer)
        self.assertIn("could not run", answer["systemMessage"])
        self.assertEqual(self.det_calls, 0)

    def test_nondeterminism_blocks_and_is_retried(self):
        self.det_result = sg.Outcome(False, "G8 violation: fingerprints differ")
        answer = self.run_gate()
        self.assertEqual(answer["decision"], "block")
        self.assertIn("G8", answer["reason"])
        self.det_result = OK
        self.assertIsNone(self.run_gate())
        self.assertEqual(self.det_calls, 2)

    golden = "OK (4832 lines)"
    state_golden = "OK (525 lines)"  # None = the run printed no StateGolden line (it crashed)
    gradle_exit = "GRADLE_EXIT 0"

    def _det_with(self, fingerprints):
        """Run determinism_check with _run faked; returns (outcome, argv list seen)."""
        seen, it = [], iter(fingerprints)
        orig = sg._run
        def fake(argv, timeout):
            seen.append(argv)
            if any("--golden=" in a for a in argv):
                state = "" if self.state_golden is None else "\nStateGolden     : " + self.state_golden
                return sg.Outcome(True, "Golden          : " + self.golden + state + "\n" + self.gradle_exit)
            return sg.Outcome(True, f"Fingerprint     : {next(it)}\nGRADLE_EXIT 0")
        sg._run = fake
        try:
            return sg.determinism_check(runs=2), seen
        finally:
            sg._run = orig

    def test_determinism_runs_the_turmoil_scenario_too(self):
        out, seen = self._det_with(["aa", "aa", "bb", "bb"])
        self.assertTrue(out.ok)
        self.assertIn("default aa", out.output)
        self.assertIn("turmoil bb", out.output)
        self.assertEqual(len(seen), 4 + len(sg.GOLDEN_RUNS))
        self.assertTrue(any("--scenario=turmoil" in " ".join(a) for a in seen[2:]))
        self.assertEqual(sum("--push" in a for a in seen), 1)

    def test_a_kotlin_golden_mismatch_blocks_with_regeneration_advice(self):
        self.golden = "MISMATCH at line 12: expected [a] actual [b]"
        out, _ = self._det_with(["aa", "aa", "bb", "bb"])
        self.assertFalse(out.ok)
        self.assertIn("no longer matches tests/golden", out.output)
        self.golden = "OK (4832 lines)"

    def test_a_state_golden_mismatch_blocks(self):
        self.state_golden = "MISMATCH at line 4: expected [a] actual [b]"
        try:
            out, _ = self._det_with(["aa", "aa", "bb", "bb"])
        finally:
            self.state_golden = "OK (525 lines)"
        self.assertFalse(out.ok)
        self.assertIn("end state no longer matches", out.output)

    def test_a_run_that_crashed_before_the_state_check_blocks(self):
        self.state_golden = None
        try:
            out, _ = self._det_with(["aa", "aa", "bb", "bb"])
        finally:
            self.state_golden = "OK (525 lines)"
        self.assertFalse(out.ok)
        self.assertIn("did not finish its state check", out.output)
        self.assertFalse(out.infra, "Gradle reported an exit, so this is a verdict, not an infrastructure problem")

    def test_a_failed_gradle_exit_after_both_golden_lines_still_blocks(self):
        self.gradle_exit = "GRADLE_EXIT 1"
        try:
            out, _ = self._det_with(["aa", "aa", "bb", "bb"])
        finally:
            self.gradle_exit = "GRADLE_EXIT 0"
        self.assertFalse(out.ok)

    def test_determinism_blocks_when_only_the_turmoil_scenario_differs(self):
        out, _ = self._det_with(["aa", "aa", "bb", "cc"])
        self.assertFalse(out.ok)
        self.assertIn("turmoil", out.output)

    def test_determinism_digest_only_tracks_kernel_and_desktop(self):
        base = sg.determinism_digest(self.ALL)
        self.assertEqual(base, sg.determinism_digest({**self.ALL, "game-ai": "x", "game-ui": "y", "build": "z"}))
        self.assertNotEqual(base, sg.determinism_digest({**self.ALL, "game-core": "x"}))
        self.assertNotEqual(base, sg.determinism_digest({**self.ALL, "desktopApp": "x"}))

    def test_remote_disabled_runs_only_local_tiers(self):
        self.assertIsNone(self.run_gate(remote=False))
        self.assertEqual((self.kotlin_calls, self.det_calls), ([], 0))


if __name__ == "__main__":
    unittest.main()
