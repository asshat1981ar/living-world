"""Tests for tools/verify.py: one remote call reports every check, and an expired session is BLOCKED, never a pass."""

import importlib.util
import sys
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("verify", Path(__file__).resolve().parent / "verify.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class VerifyTests(unittest.TestCase):
    def test_one_remote_script_runs_gradle_and_all_five_goldens(self):
        script = verify.remote_script("GRADLE", "check", goldens=True)
        self.assertIn("GRADLE check", script)
        for name in verify.GOLDENS:
            self.assertIn(f"--golden=tests/golden/{name}-{verify.DAYS}d.txt.gz", script)
            self.assertIn(f"--state-golden=tests/golden/{name}-{verify.DAYS}d.state.txt.gz", script)
            self.assertIn(f"VERIFY golden-{name}", script)
        self.assertIn("--scenario=turmoil", script)
        self.assertNotIn("golden", verify.remote_script("GRADLE", "check", goldens=False))

    def test_results_are_read_from_verify_lines_only(self):
        out = "noise\nVERIFY gradle 0\n> Task :x FAILED\nVERIFY golden-default 1\nVERIFY golden-feud 0\nVERIFY broken\n"
        self.assertEqual({"gradle": True, "golden-default": False, "golden-feud": True}, verify.parse_results(out))

    def test_an_expired_session_blocks_instead_of_passing(self):
        self.assertFalse(verify.session_ok(check=lambda: False))
        original = verify.session_ok
        verify.session_ok = lambda check=None: False
        try:
            self.assertEqual(2, verify.main([]))
        finally:
            verify.session_ok = original

    def test_local_only_mode_skips_the_remote(self):
        original = verify._local
        verify._local = lambda name, cmd: (name, True, [])
        try:
            self.assertEqual(0, verify.main(["--no-remote"]))
        finally:
            verify._local = original

    def test_local_mode_runs_gradle_and_the_three_goldens(self):
        calls: list[str] = []
        original_local, original_golden = verify._local, verify.local_golden
        original_missing = verify.local_toolchain_missing
        try:
            verify.local_toolchain_missing = lambda: []
            verify._local = lambda name, cmd: (calls.append(name) or (name, True, []))
            verify.local_golden = lambda name, args, root=verify.ROOT: (
                calls.append("golden " + name) or ("golden " + name, True, []))
            self.assertEqual(0, verify.main(["--local"]))
        finally:
            verify._local, verify.local_golden = original_local, original_golden
            verify.local_toolchain_missing = original_missing
        self.assertIn("gradle check", calls)
        self.assertEqual([f"golden {name}" for name in verify.GOLDENS], [c for c in calls if c.startswith("golden ")])

    def test_local_mode_is_blocked_when_the_toolchain_is_missing(self):
        original = verify.local_toolchain_missing
        try:
            verify.local_toolchain_missing = lambda: ["a JDK"]
            self.assertEqual(2, verify.main(["--local"]))
        finally:
            verify.local_toolchain_missing = original

    def test_the_run_length_comes_from_the_scenario_file_not_from_the_code(self):
        original = verify.DAYS
        try:
            verify.DAYS = 7
            script = verify.remote_script("GRADLE", "check", goldens=True)
            self.assertIn("--args='7 0", script)
            self.assertIn("default-7d.txt.gz", script)
            self.assertIn("schism-7d.state.txt.gz", script)
            self.assertNotIn("10d", script, "a golden path still names the old run length")
            self.assertNotIn("--args='10 0", script)
        finally:
            verify.DAYS = original

    def test_a_golden_run_needs_a_zero_gradle_exit_as_well_as_both_verdict_lines(self):
        script = verify.remote_script("GRADLE", "check", goldens=True)
        self.assertIn("rc=$?", script)
        self.assertIn("[ $rc -eq 0 ]", script)

    def test_the_local_golden_run_uses_the_configured_days_too(self):
        import subprocess
        seen = []
        original_run, original_days = subprocess.run, verify.DAYS
        subprocess.run = lambda cmd, **kw: (seen.append(cmd), type("P", (), {"stdout": "", "stderr": "", "returncode": 0})())[1]
        try:
            verify.DAYS = 7
            verify.local_golden("feud", "7 0 --scenario=feud")
        finally:
            subprocess.run, verify.DAYS = original_run, original_days
        self.assertIn("feud-7d.txt.gz", " ".join(seen[0]))
        self.assertNotIn("10d", " ".join(seen[0]))

    def test_the_remote_script_reports_the_kotlin_test_count(self):
        script = verify.remote_script("GRADLE", "check", goldens=False)
        self.assertIn("TESTCOUNT", script)
        self.assertIn("build/test-results", script)

    def test_the_test_count_step_sums_junit_suites(self):
        import subprocess, tempfile
        with tempfile.TemporaryDirectory() as d:
            for i, (tests, failures) in enumerate([(5, 0), (7, 1)]):
                out = Path(d) / f"mod{i}" / "build" / "test-results" / "jvmTest"
                out.mkdir(parents=True)
                (out / f"TEST-x{i}.xml").write_text(
                    f'<?xml version="1.0"?><testsuite name="x{i}" tests="{tests}" skipped="0" failures="{failures}" errors="0">')
            run = subprocess.run(["sh", "-c", verify.TEST_COUNT_STEP], cwd=d, capture_output=True, text=True, check=False)
        self.assertEqual("TESTCOUNT tests=12 failures=1", run.stdout.strip(), run.stderr)

    def test_golden_verdict_reads_the_output_line(self):
        state = "\nStateGolden     : OK (525 lines)"
        self.assertTrue(verify.golden_verdict("Golden          : OK (5117 lines)" + state))
        self.assertFalse(verify.golden_verdict("Golden          : MISMATCH at line 6: expected [a] actual [b]" + state))
        self.assertFalse(verify.golden_verdict("no golden line here"))

    def test_golden_verdict_needs_the_state_golden_too(self):
        ok = "Golden          : OK (5117 lines)\n"
        # A crash after the first verdict leaves no state line: that must fail, not pass.
        self.assertFalse(verify.golden_verdict(ok))
        self.assertTrue(verify.golden_verdict(ok + "StateGolden     : OK (525 lines)"))
        self.assertFalse(verify.golden_verdict(ok + "StateGolden     : MISMATCH at line 3: expected [a] actual [b]"))
        self.assertFalse(verify.golden_verdict(ok + "StateGolden     : MISSING golden file tests/golden/x.state.txt.gz"))
        # A state line alone is not a verdict on the event dump.
        self.assertFalse(verify.golden_verdict("StateGolden     : OK (525 lines)"))


if __name__ == "__main__":
    unittest.main()
