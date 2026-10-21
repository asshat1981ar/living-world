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


if __name__ == "__main__":
    unittest.main()
