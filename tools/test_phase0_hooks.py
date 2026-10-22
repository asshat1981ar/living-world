"""Tests for tools/hooks/{bash_guard,ci_watch,session_preflight}.py.  Run: python3 -m unittest tools/test_phase0_hooks.py"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import os
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "hooks"))
import bash_guard as bg  # noqa: E402
import ci_watch as cw  # noqa: E402
import session_preflight as sp  # noqa: E402


def stage(cmd: str) -> bool:
    return any(bg.is_broad_stage(s) for s in bg.segments(cmd))


def push(cmd: str) -> bool:
    return any(bg.is_push(s) for s in bg.segments(cmd))


class CommandClassificationTests(unittest.TestCase):
    def test_broad_staging_is_detected(self):
        for cmd in ("git add -A", "git add .", "git add --all", "git commit -am 'x'", "git commit -a -m x",
                    "cd repo && git add .", "git -C repo add .", "FOO=1 git add -A", "git add -A; git status"):
            self.assertTrue(stage(cmd), cmd)

    def test_explicit_staging_and_lookalikes_are_not(self):
        for cmd in ("git add game-core/x.kt", "git add CLAUDE.md docs/handoff/last.md", "git commit -m 'fix -a thing'",
                    "echo git add -A", "git status", "ls ."):
            self.assertFalse(stage(cmd), cmd)

    def test_push_detection(self):
        self.assertTrue(push("git push origin main"))
        self.assertTrue(push("cd x && git push"))
        self.assertTrue(push("tools/publish_github.sh living-world"))
        self.assertFalse(push("git push --dry-run"))
        self.assertFalse(push("LW_SKIP_PREPUSH=1 git push"))
        self.assertFalse(push("git pull"))
        self.assertFalse(push("git log --oneline"))


class SecretScanTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    def tearDown(self):
        self.tmp.cleanup()

    def test_untracked_env_is_flagged_and_gitignore_clears_it(self):
        (self.root / ".env").write_text("X=1\n")
        self.assertEqual(len(bg.suspicious_untracked(self.root)), 1)
        (self.root / ".gitignore").write_text(".env\n")
        self.assertEqual(bg.suspicious_untracked(self.root), [])

    def test_token_inside_a_file_is_flagged(self):
        (self.root / "notes.txt").write_text("key = github_pat_" + "A" * 40 + "\n")
        (self.root / "ok.txt").write_text("nothing here\n")
        found = bg.suspicious_untracked(self.root)
        self.assertEqual(len(found), 1)
        self.assertIn("notes.txt", found[0])

    def test_images_named_token_are_not_flagged(self):
        (self.root / "npc_lyra_token.png").write_bytes(b"\x89PNG")
        (self.root / "secret_art.png").write_bytes(b"\x89PNG")
        self.assertEqual(bg.suspicious_untracked(self.root), [])

    def test_more_token_shapes_are_flagged(self):
        for i, token in enumerate([
            "xoxb-" + "a" * 20,
            "sk-" + "b" * 30,
            "AIza" + "c" * 35,
            "hf_" + "d" * 30,
            "glpat-" + "e" * 20,
            "eyJ" + "f" * 12 + "." + "g" * 12 + "." + "h" * 12,
        ]):
            (self.root / f"t{i}.txt").write_text(f"value = {token}\n")
        self.assertEqual(6, len(bg.suspicious_untracked(self.root)))

    def test_placeholders_are_not_flagged(self):
        (self.root / "a.txt").write_text('password = "changeme-please"\napi_key = "<your-token>"\n')
        (self.root / "b.txt").write_text("token = ghp_" + "A" * 40 + " # example\n")
        self.assertEqual(bg.suspicious_untracked(self.root), [])

    def test_a_token_in_staged_content_is_flagged(self):
        (self.root / "staged.txt").write_text("hf_" + "z" * 30 + "\n")
        subprocess.run(["git", "add", "staged.txt"], cwd=self.root, check=True)
        found = bg.suspicious_staged(self.root)
        self.assertEqual(len(found), 1)
        self.assertIn("staged.txt", found[0])
        code, msg = bg.evaluate("git add -A", self.root)
        self.assertEqual(code, 2)
        self.assertIn("staged.txt", msg)

    def test_evaluate_blocks_broad_add_but_not_explicit_add(self):
        (self.root / ".env").write_text("X=1\n")
        code, msg = bg.evaluate("git add -A", self.root)
        self.assertEqual(code, 2)
        self.assertIn(".env", msg)
        self.assertEqual(bg.evaluate("git add README.md", self.root)[0], 0)


class PrePushTests(unittest.TestCase):
    def test_push_blocked_when_gate_blocks(self):
        code, msg = bg.evaluate("git push origin main", Path("."), run_gate=lambda: {"decision": "block", "reason": "kotlin failed"})
        self.assertEqual(code, 2)
        self.assertIn("kotlin failed", msg)

    def test_push_allowed_when_gate_passes_or_is_infra_warning(self):
        self.assertEqual(bg.evaluate("git push", Path("."), run_gate=lambda: None), (0, ""))
        code, msg = bg.evaluate("git push", Path("."), run_gate=lambda: {"systemMessage": "aws expired"})
        self.assertEqual((code, msg), (0, "aws expired"))

    def test_gate_is_not_run_for_other_commands_or_bad_quoting(self):
        def boom():
            raise AssertionError("gate must not run")
        self.assertEqual(bg.evaluate("git status", Path("."), run_gate=boom), (0, ""))
        self.assertEqual(bg.evaluate("echo 'unterminated", Path("."), run_gate=boom), (0, ""))


class CiWatchTests(unittest.TestCase):
    def test_verdict(self):
        done = {"status": "completed", "conclusion": "success"}
        self.assertIsNone(cw.verdict([]))
        self.assertEqual(cw.verdict([done, {"status": "in_progress"}]), "pending")
        self.assertEqual(cw.verdict([done, done]), "pass")
        self.assertEqual(cw.verdict([done, {"status": "completed", "conclusion": "failure"}]), "fail")

    def test_report_names_failures_and_includes_only_the_tail(self):
        state = {"sha": "abcdef123456", "state": "fail", "failure_tail": "step X failed\nsdkmanager exit 1",
                 "runs": [{"name": "CI", "status": "completed", "conclusion": "failure", "url": "http://x"},
                          {"name": "Lint", "status": "completed", "conclusion": "success"}]}
        text = cw.format_report(state)
        self.assertIn("abcdef1 FAILED", text)
        self.assertIn("CI: failure", text)
        self.assertNotIn("Lint", text)
        self.assertIn("sdkmanager exit 1", text)
        self.assertIn("passed", cw.format_report({"sha": "abcdef123456", "state": "pass", "runs": [{"name": "CI"}]}))

    def test_state_round_trip(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "lw-ci" / "status.json"
            self.assertIsNone(cw.read_state(p))
            cw.write_state({"sha": "x", "state": "pass"}, p)
            self.assertEqual(cw.read_state(p)["state"], "pass")


class PreflightTests(unittest.TestCase):
    def test_expired_session_gives_the_login_hint(self):
        out = sp.preflight(lambda argv: (255, "Your session has expired. Please reauthenticate using 'aws login'."))
        self.assertEqual(out, [sp.LOGIN_HINT])

    def test_running_gpu_box_is_warned_about(self):
        os.environ["LW_GPU_INSTANCE"] = "i-test"
        self.addCleanup(os.environ.pop, "LW_GPU_INSTANCE", None)
        def runner(argv):
            return (0, '{"UserId": "x"}') if "sts" in argv else (0, '["running"]')
        out = sp.preflight(runner)
        self.assertEqual(out[0], "preflight: AWS session OK.")
        self.assertIn("GPU box", out[1])

    def test_stopped_gpu_box_is_quiet_and_other_errors_do_not_claim_expiry(self):
        def runner(argv):
            return (0, "{}") if "sts" in argv else (0, '["stopped"]')
        self.assertEqual(sp.preflight(runner), ["preflight: AWS session OK."])
        out = sp.preflight(lambda argv: (1, "Could not connect to the endpoint URL"))
        self.assertNotEqual(out, [sp.LOGIN_HINT])


if __name__ == "__main__":
    unittest.main()
