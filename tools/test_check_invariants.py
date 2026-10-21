"""Tests for tools/check_invariants.py and tools/hooks/invariant_guard.py.  Run: python3 -m unittest tools/test_check_invariants.py"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_invariants as ci  # noqa: E402

CORE = "game-core/src/commonMain/kotlin/org/livingworld/core/X.kt"
UI = "game-ui/src/commonMain/kotlin/org/livingworld/ui/X.kt"
ANDROID = "androidApp/src/main/kotlin/com/livingworld/game/android/X.kt"
HOOK = Path(__file__).resolve().parent / "hooks" / "invariant_guard.py"


def rules(path: str, text: str) -> set[str]:
    return {f.rule for f in ci.check_text(path, text)}


class RuleTests(unittest.TestCase):
    def test_g1_ai_import_in_core(self):
        self.assertIn("ai-import", rules(CORE, "import org.livingworld.ai.reasoning.AiReasoningPort\n"))

    def test_g1_ai_import_allowed_outside_core(self):
        self.assertNotIn("ai-import", rules(UI, "import org.livingworld.ai.reasoning.AiReasoningPort\n"))

    def test_g9_platform_imports(self):
        for imp in ("java.io.File", "javax.crypto.Cipher", "android.util.Log", "platform.Foundation.NSDate"):
            self.assertIn("platform-import", rules(UI, f"import {imp}\n"), imp)

    def test_g9_format_and_system(self):
        self.assertIn("string-format", rules(CORE, 'val s = "%.2f".format(x)\n'))
        self.assertIn("string-format", rules(CORE, 'val s = String.format("%d", 1)\n'))
        self.assertIn("system", rules(CORE, "val t = System.nanoTime()\n"))
        self.assertIn("dispatchers-io", rules(CORE, "withContext(Dispatchers.IO) {}\n"))

    def test_formatted_is_not_format(self):
        self.assertEqual(set(), rules(CORE, 'appendLine(event.time.formatted())\n'))

    def test_g8_randomness_and_collections(self):
        self.assertIn("unseeded-random", rules(CORE, "val r = Random()\n"))
        self.assertIn("unseeded-random", rules(CORE, "val n = Random.nextInt(3)\n"))
        self.assertNotIn("unseeded-random", rules(CORE, "val r = Random(world.seed)\n"))
        self.assertIn("hash-collection", rules(CORE, "val m = HashMap<String, Int>()\n"))
        self.assertIn("hashcode", rules(CORE, "val h = id.hashCode()\n"))

    def test_platform_code_outside_common_is_fine(self):
        self.assertEqual(set(), rules(ANDROID, "import java.io.File\nval s = \"%.2f\".format(x)\n"))

    def test_comments_ignored(self):
        self.assertEqual(set(), rules(CORE, "// import java.io.File\n * String.format(x)\n"))

    def test_introduced_only_counts_new_findings(self):
        old = "val h = a.hashCode()\nimport java.io.File\n"
        self.assertEqual([], ci.introduced(CORE, old, old + "val y = 1\n"))
        new = ci.introduced(CORE, old, old + "import java.net.URL\n")
        self.assertEqual(["platform-import"], [f.rule for f in new])

    def test_isolation_gate(self):
        diff = ("+++ b/game-core/build.gradle.kts\n"
                "+                implementation(\"io.ktor:ktor-client-core:3.0.0\")\n"
                "+// comment\n+++ b/README.md\n+implementation(\"x\")\n")
        hits = ci.isolation_gate(diff)
        self.assertEqual(1, len(hits))
        self.assertIn("ktor", hits[0])


class HookTests(unittest.TestCase):
    def run_hook(self, payload: dict, root: str) -> subprocess.CompletedProcess:
        env = dict(os.environ, CLAUDE_PROJECT_DIR=root)
        return subprocess.run([sys.executable, "-I", str(HOOK)], input=json.dumps(payload), text=True,
                              capture_output=True, env=env)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        self.file = Path(self.root, CORE)
        self.file.parent.mkdir(parents=True)
        self.file.write_text("package x\n\nval a = 1\n")

    def tearDown(self):
        self.tmp.cleanup()

    def test_blocks_introduced_platform_import(self):
        r = self.run_hook({"tool_name": "Edit", "tool_input": {"file_path": str(self.file),
                           "old_string": "val a = 1", "new_string": "import java.io.File\nval a = 1"}}, self.root)
        self.assertEqual(2, r.returncode, r.stderr)
        self.assertIn("G9", r.stderr)

    def test_blocks_write_with_ai_import(self):
        r = self.run_hook({"tool_name": "Write", "tool_input": {"file_path": str(self.file),
                           "content": "import org.livingworld.ai.X\n"}}, self.root)
        self.assertEqual(2, r.returncode)

    def test_allows_clean_edit(self):
        r = self.run_hook({"tool_name": "Edit", "tool_input": {"file_path": str(self.file),
                           "old_string": "val a = 1", "new_string": "val a = 2"}}, self.root)
        self.assertEqual(0, r.returncode, r.stderr)

    def test_allows_fixing_existing_violation(self):
        self.file.write_text("import java.io.File\nimport java.net.URL\n")
        r = self.run_hook({"tool_name": "Edit", "tool_input": {"file_path": str(self.file),
                           "old_string": "import java.io.File\n", "new_string": ""}}, self.root)
        self.assertEqual(0, r.returncode, r.stderr)

    def test_fails_open_on_garbage(self):
        r = subprocess.run([sys.executable, "-I", str(HOOK)], input="not json", text=True, capture_output=True)
        self.assertEqual(0, r.returncode)

    def test_blocks_when_root_and_file_are_spelled_through_different_paths_to_the_same_dir(self):
        # On this phone the project is reachable as /data/... and /root/termux-home/... (a proot mount), and
        # Path.resolve() does not unify them, so the guard used to fail open silently. Simulate that: the root is a
        # symlinked alias and resolve() is a no-op, as it effectively was there.
        import importlib.util
        from contextlib import redirect_stderr
        from io import StringIO
        from unittest import mock
        alias = Path(self.root + "-alias")
        alias.symlink_to(self.root)
        self.addCleanup(alias.unlink)
        spec = importlib.util.spec_from_file_location("invariant_guard_under_test", HOOK)
        guard = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(guard)
        payload = json.dumps({"tool_name": "Edit", "tool_input": {"file_path": str(self.file),
                              "old_string": "val a = 1", "new_string": "import java.io.File\nval a = 1"}})
        err = StringIO()
        with mock.patch.object(Path, "resolve", lambda self, strict=False: self), \
                mock.patch.dict(os.environ, {"CLAUDE_PROJECT_DIR": str(alias)}), \
                mock.patch.object(guard.telemetry, "record"), \
                mock.patch("sys.stdin", StringIO(payload)), redirect_stderr(err):
            code = guard.main()
        self.assertEqual(2, code, err.getvalue())

    def test_ignores_non_kotlin_and_outside_repo(self):
        r = self.run_hook({"tool_name": "Write", "tool_input": {"file_path": "/tmp/x.kt",
                           "content": "import java.io.File\n"}}, self.root)
        self.assertEqual(0, r.returncode)


if __name__ == "__main__":
    unittest.main()
