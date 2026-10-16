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
        self.calls: list[str] = []

    def tearDown(self):
        self.tmp.cleanup()

    def checks(self, inv=OK, tools=OK, kotlin=OK, digest="d1"):
        def track(name, value):
            def f():
                self.calls.append(name)
                return value
            return f
        return {"invariants": track("inv", inv), "tool tests": track("tools", tools),
                "kotlin": track("kotlin", kotlin), "digest": lambda: digest}

    def test_all_green_allows_and_records_digest(self):
        self.assertIsNone(sg.decide({}, True, self.checks(), self.state))
        self.assertEqual("d1", self.state.read_text().strip())

    def test_invariant_failure_blocks_before_anything_else(self):
        out = sg.decide({}, True, self.checks(inv=FAIL), self.state)
        self.assertEqual("block", out["decision"])
        self.assertIn("invariants failed", out["reason"])
        self.assertEqual(["inv"], self.calls)

    def test_tool_test_failure_blocks_with_output(self):
        out = sg.decide({}, True, self.checks(tools=FAIL), self.state)
        self.assertIn("AssertionError: boom", out["reason"])

    def test_kotlin_skipped_when_unchanged(self):
        self.state.write_text("d1\n")
        self.assertIsNone(sg.decide({}, True, self.checks(kotlin=FAIL), self.state))
        self.assertNotIn("kotlin", self.calls)

    def test_kotlin_failure_blocks_and_keeps_old_digest(self):
        self.state.write_text("old\n")
        out = sg.decide({}, True, self.checks(kotlin=FAIL, digest="new"), self.state)
        self.assertEqual("block", out["decision"])
        self.assertEqual("old", self.state.read_text().strip())

    def test_infra_failure_warns_without_blocking(self):
        out = sg.decide({}, True, self.checks(kotlin=INFRA), self.state)
        self.assertNotIn("decision", out)
        self.assertIn("could not run", out["systemMessage"])
        self.assertFalse(self.state.exists())

    def test_remote_tier_can_be_disabled(self):
        self.assertIsNone(sg.decide({}, False, self.checks(kotlin=FAIL), self.state))
        self.assertNotIn("kotlin", self.calls)

    def test_gradle_verdict_vs_infra_classification(self):
        orig = sg._run
        try:
            sg._run = lambda argv, timeout: sg.Outcome(False, "BUILD FAILED\nGRADLE_EXIT 1")
            self.assertFalse(sg.gradle_check().infra)
            sg._run = lambda argv, timeout: sg.Outcome(False, "aws ssm failed: session expired")
            self.assertTrue(sg.gradle_check().infra)
        finally:
            sg._run = orig

    def test_garbage_input_allows(self):
        p = subprocess.run([sys.executable, "-I", str(HOOK)], input="nope", text=True, capture_output=True)
        self.assertEqual((0, ""), (p.returncode, p.stdout))


if __name__ == "__main__":
    unittest.main()
