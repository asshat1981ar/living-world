"""Tests for tools/verify.py: one remote call reports every check, and an expired session is BLOCKED, never a pass."""

import importlib.util
import sys
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("verify", Path(__file__).resolve().parent / "verify.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class VerifyTests(unittest.TestCase):
    def test_one_remote_script_runs_gradle_and_all_three_goldens(self):
        script = verify.remote_script("GRADLE", "check", goldens=True)
        self.assertIn("GRADLE check", script)
        for name in ("default", "turmoil", "feud"):
            self.assertIn(f"--golden=tests/golden/{name}-10d.txt.gz", script)
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
        self.assertEqual(["golden default", "golden turmoil", "golden feud"],
                         [c for c in calls if c.startswith("golden ")])

    def test_local_mode_is_blocked_when_the_toolchain_is_missing(self):
        original = verify.local_toolchain_missing
        try:
            verify.local_toolchain_missing = lambda: ["a JDK"]
            self.assertEqual(2, verify.main(["--local"]))
        finally:
            verify.local_toolchain_missing = original

    def test_golden_verdict_reads_the_output_line(self):
        self.assertTrue(verify.golden_verdict("Golden          : OK (5117 lines)"))
        self.assertFalse(verify.golden_verdict("Golden          : MISMATCH at line 6: expected [a] actual [b]"))
        self.assertFalse(verify.golden_verdict("no golden line here"))


if __name__ == "__main__":
    unittest.main()
