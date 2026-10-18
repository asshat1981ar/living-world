"""Tests for tools/hooks/telemetry.py.  Run: python3 -m unittest tools/test_telemetry.py"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "hooks"))
import telemetry as t  # noqa: E402


class TelemetryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.log = Path(self.tmp.name) / "d" / "events.jsonl"

    def tearDown(self):
        self.tmp.cleanup()

    def fill(self, hook, outcome, n, secs=0.0):
        for _ in range(n):
            t.record(hook, outcome, path=self.log, s=secs)

    def test_round_trip_and_bad_lines_are_skipped(self):
        t.record("bash_guard", "block", path=self.log)
        with self.log.open("a") as f:
            f.write("not json\n")
        rows = t.load(self.log)
        self.assertEqual([r["outcome"] for r in rows], ["block"])
        self.assertEqual(t.load(Path(self.tmp.name) / "missing"), [])

    def test_record_never_raises(self):
        t.record("x", "pass", path=Path("/proc/nope/events.jsonl"))

    def test_summary_counts(self):
        self.fill("a", "pass", 3)
        self.fill("a", "block", 1)
        s = t.summarize(t.load(self.log))["a"]
        self.assertEqual((s["fires"], s["pass"], s["block"]), (4, 3, 1))

    def test_advice_needs_history_and_flags_slow_useless_hooks(self):
        self.fill("quiet", "pass", 5)
        self.fill("slow", "pass", 25, secs=120.0)
        self.fill("useful", "block", 3)
        self.fill("useful", "pass", 22)
        self.fill("bypassed", "skip", 10)
        self.fill("bypassed", "pass", 10)
        stats = t.summarize(t.load(self.log))
        self.assertIsNone(t.advice("quiet", stats["quiet"]))
        self.assertIn("demote", t.advice("slow", stats["slow"]))
        self.assertIsNone(t.advice("useful", stats["useful"]))
        self.assertIn("bypassed", t.advice("bypassed", stats["bypassed"]))

    def test_empty_report(self):
        self.assertEqual(t.report([]), "no hook telemetry yet")


if __name__ == "__main__":
    unittest.main()
