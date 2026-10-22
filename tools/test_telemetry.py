"""Tests for tools/hooks/telemetry.py.  Run: python3 -m unittest tools/test_telemetry.py"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
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


class StaleReportTests(unittest.TestCase):
    """A guard that never runs looks exactly like a guard with nothing to say; the stale report tells them apart."""

    NOW = 1_800_000_000.0

    def row(self, hook, days_ago, timed=True):
        r = {"t": int(self.NOW - days_ago * 86400), "hook": hook, "outcome": "pass"}
        if timed:
            r["s"] = 0.1
        return r

    def test_a_wired_guard_with_no_rows_is_reported_silent(self):
        out = t.stale([self.row("stop_gate", 1)], ["stop_gate", "invariant_guard"], now=self.NOW)
        self.assertEqual(1, len(out))
        self.assertIn("invariant_guard: wired in settings.json but has never logged", out[0])

    def test_a_guard_whose_last_row_is_old_is_reported(self):
        out = t.stale([self.row("bash_guard", 30)], ["bash_guard"], now=self.NOW, days=7)
        self.assertTrue(any("no row in the last 7 days" in line for line in out), out)

    def test_untimed_rows_are_counted(self):
        rows = [self.row("invariant_guard", 1, timed=False), self.row("invariant_guard", 1, timed=False),
                self.row("invariant_guard", 1)]
        out = t.stale(rows, ["invariant_guard"], now=self.NOW)
        self.assertIn("invariant_guard: 2 of 3 rows have no duration", out)

    def test_a_healthy_log_reports_nothing(self):
        rows = [self.row("stop_gate", 0.5), self.row("bash_guard", 2), self.row("invariant_guard", 1)]
        self.assertEqual([], t.stale(rows, ["stop_gate", "bash_guard", "invariant_guard"], now=self.NOW))

    def test_the_wired_hooks_are_the_real_guards_and_each_has_a_script(self):
        wired = t.wired_hooks()
        self.assertEqual({"invariant_guard", "bash_guard", "stop_gate"}, set(wired))
        for name in wired:
            self.assertTrue((Path(t.__file__).parent / f"{name}.py").is_file(), f"{name} is wired but has no script")

    def test_a_missing_settings_file_gives_no_hooks_not_a_crash(self):
        self.assertEqual([], t.wired_hooks(Path("/nonexistent/settings.json")))


class RecordFieldsTests(unittest.TestCase):
    def test_a_row_carries_the_commit_when_there_is_one(self):
        with tempfile.TemporaryDirectory() as d:
            log = Path(d) / "e.jsonl"
            t.record("stop_gate", "pass", path=log)
            row = json.loads(log.read_text())
            if "sha" in row:  # this checkout has a readable .git/HEAD
                self.assertRegex(row["sha"], r"^[0-9a-f]{7,10}$")

    def test_head_sha_reads_a_detached_head_and_a_ref_without_git(self):
        with tempfile.TemporaryDirectory() as d:
            repo = Path(d)
            (repo / ".git" / "refs" / "heads").mkdir(parents=True)
            (repo / ".git" / "HEAD").write_text("ref: refs/heads/main\n")
            (repo / ".git" / "refs" / "heads" / "main").write_text("abcdef0123456789abcdef0123456789abcdef01\n")
            self.assertEqual("abcdef0123", t._head_sha(repo))
            (repo / ".git" / "HEAD").write_text("1234567890abcdef1234567890abcdef12345678\n")
            self.assertEqual("1234567890", t._head_sha(repo))
            (repo / ".git" / "HEAD").write_text("garbage\n")
            self.assertIsNone(t._head_sha(repo))
        self.assertIsNone(t._head_sha(Path("/nonexistent")))


class InvariantGuardTelemetryTests(unittest.TestCase):
    """Run the real hook: its rows must carry a duration (the 41 untimed rows came from here) and skips a reason."""

    HOOK = Path(__file__).resolve().parent / "hooks" / "invariant_guard.py"

    def run_hook(self, project: Path, file_path: str, content: str):
        payload = {"tool_name": "Write", "tool_input": {"file_path": file_path, "content": content}}
        env = {**os.environ, "CLAUDE_PROJECT_DIR": str(project)}
        return subprocess.run([sys.executable, "-I", str(self.HOOK)], input=json.dumps(payload), env=env,
                              capture_output=True, text=True, check=False)

    def rows(self, project: Path):
        log = project / ".git" / "lw-telemetry" / "events.jsonl"
        return [json.loads(line) for line in log.read_text().splitlines()] if log.is_file() else []

    def test_a_checked_edit_logs_a_duration(self):
        with tempfile.TemporaryDirectory() as d:
            project = Path(d)
            (project / ".git").mkdir()
            target = project / "game-core" / "src" / "commonMain" / "kotlin" / "Ok.kt"
            target.parent.mkdir(parents=True)
            run = self.run_hook(project, str(target), "package x\nclass Ok\n")
            self.assertEqual(0, run.returncode, run.stderr)
            rows = [r for r in self.rows(project) if r["hook"] == "invariant_guard"]
            self.assertEqual(1, len(rows), rows)
            self.assertEqual("pass", rows[0]["outcome"])
            self.assertIn("s", rows[0], "the guard's row has no duration")

    def test_a_blocked_edit_logs_a_duration_and_still_blocks(self):
        with tempfile.TemporaryDirectory() as d:
            project = Path(d)
            (project / ".git").mkdir()
            target = project / "game-core" / "src" / "commonMain" / "kotlin" / "Bad.kt"
            target.parent.mkdir(parents=True)
            run = self.run_hook(project, str(target), "package x\nimport org.livingworld.ai.Foo\nclass Bad\n")
            self.assertEqual(2, run.returncode, "the guard must still block a G1 violation")
            rows = [r for r in self.rows(project) if r["hook"] == "invariant_guard"]
            self.assertEqual("block", rows[0]["outcome"])
            self.assertIn("s", rows[0])

    def test_an_edit_outside_the_repo_is_a_visible_skip_with_a_reason_and_a_duration(self):
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as elsewhere:
            project = Path(d)
            (project / ".git").mkdir()
            run = self.run_hook(project, str(Path(elsewhere) / "Foo.kt"), "class Foo\n")
            self.assertEqual(0, run.returncode, run.stderr)
            row = self.rows(project)[0]
            self.assertEqual("skip", row["outcome"])
            self.assertEqual("outside_repo", row.get("reason"))
            self.assertIn("s", row)


if __name__ == "__main__":
    unittest.main()
