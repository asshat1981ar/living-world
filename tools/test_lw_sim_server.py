"""Tests for tools/mcp/lw_sim_server.py. Needs the `mcp` SDK (the server's venv); skipped where it is not installed.

Run: ~/.local/share/lw-mcp-venv/bin/python -m unittest discover -s tools -p test_lw_sim_server.py
"""

import importlib.util
import unittest
from pathlib import Path
from unittest import mock



def _have_mcp_sdk() -> bool:
    # Not find_spec("mcp"): tools/mcp itself looks like a namespace package when tests run from tools/.
    try:
        return importlib.util.find_spec("mcp.server.fastmcp") is not None
    except ImportError:
        return False


HAVE_MCP = _have_mcp_sdk()
SERVER = Path(__file__).resolve().parent / "mcp" / "lw_sim_server.py"


def load():
    spec = importlib.util.spec_from_file_location("lw_sim_server_under_test", SERVER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@unittest.skipUnless(HAVE_MCP, "the mcp SDK is not installed (it lives in the lw-sim venv)")
class LwSimServerTests(unittest.TestCase):
    def setUp(self):
        self.server = load()

    def test_verify_reports_each_check_and_passes_only_when_all_pass(self):
        out = "noise\nPASS  gradle\nPASS  golden-default\nFAIL  golden-feud\n      mismatch at line 12\nPASS  invariants\n"
        with mock.patch.object(self.server, "_local", lambda *a, **k: (1, out)):
            result = self.server.verify()
        self.assertEqual("fail", result["status"])
        self.assertEqual({"gradle": True, "golden-default": True, "golden-feud": False, "invariants": True}, result["checks"])

    def test_verify_says_blocked_when_nothing_could_run(self):
        with mock.patch.object(self.server, "_local", lambda *a, **k: (2, "BLOCKED: the AWS session is expired")):
            result = self.server.verify()
        self.assertEqual("blocked", result["status"])
        self.assertEqual({}, result["checks"])

    def test_verify_passes_its_options_to_verify_py(self):
        seen = []
        with mock.patch.object(self.server, "_local", lambda *args, **k: seen.append(args) or (0, "PASS  gradle\n")):
            self.assertEqual("pass", self.server.verify(tasks=":game-core:jvmTest", goldens=False)["status"])
        self.assertIn("--no-goldens", seen[0])
        self.assertIn(":game-core:jvmTest", seen[0])

    def test_scenarios_reach_the_simulation_command(self):
        calls = []
        with mock.patch.object(self.server, "_remote", lambda *a, **k: calls.append(a) or (0, "Fingerprint : abc")):
            self.server.run_simulation(days=15, tail=0, scenario="turmoil")
            self.server.run_simulation(days=15, tail=0)
        self.assertIn("--scenario=turmoil", " ".join(calls[0]))
        self.assertNotIn("--scenario", " ".join(calls[1]))


if __name__ == "__main__":
    unittest.main()
