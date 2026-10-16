"""Tests for tools/ledger_tools.py.  Run: python3 -m unittest tools/test_ledger_tools.py"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ledger_tools as lt  # noqa: E402

P = "org.livingworld.core.events.GameEvent."


def ev(i, t, cause=None, **extra):
    return {"type": P + t, "id": i, "time": i * 60, "causalEventId": cause, **extra}


BASE = [ev(1, "RumorTransmitted"), ev(2, "AllegianceChanged", 1), ev(3, "NpcAttacked", 2), ev(4, "NpcKilled", 3)]


class LedgerToolTests(unittest.TestCase):
    def test_load_envelope_and_raw_world(self):
        with tempfile.TemporaryDirectory() as d:
            env = Path(d, "env.json")
            env.write_text(json.dumps({"schemaVersion": 3, "worldState": {"events": {"eventsList": BASE, "nextEventNumber": 5}}}))
            raw = Path(d, "raw.json")
            raw.write_text(json.dumps({"seed": 42, "events": {"eventsList": BASE[:2]}}))
            self.assertEqual(4, len(lt.load_events(env)))
            self.assertEqual(2, len(lt.load_events(raw)))

    def test_trace_root_first(self):
        chain = lt.trace(BASE, 4)
        self.assertEqual([1, 2, 3, 4], [c["id"] for c in chain])
        self.assertEqual("RumorTransmitted", chain[0]["type"])

    def test_trace_is_cycle_safe(self):
        loop = [ev(1, "A", 2), ev(2, "B", 1)]
        self.assertEqual(2, len(lt.trace(loop, 2)))

    def test_diff_append_only_ok(self):
        d = lt.diff(BASE[:2], BASE)
        self.assertTrue(d["g5_ok"])
        self.assertEqual(2, d["appended_count"])

    def test_diff_flags_rewritten_history(self):
        tampered = [dict(BASE[0], causalEventId=99)] + BASE[1:]
        d = lt.diff(BASE, tampered)
        self.assertFalse(d["g5_ok"])
        self.assertEqual([1], d["changed_ids"])

    def test_diff_flags_removed_history(self):
        d = lt.diff(BASE, BASE[1:])
        self.assertEqual([1], d["removed_ids"])
        self.assertFalse(d["g5_ok"])

    def test_summary_duplicates(self):
        s = lt.summary(BASE + [ev(4, "NpcKilled")])
        self.assertEqual([4], s["duplicate_ids"])
        self.assertEqual(2, s["by_type"]["NpcKilled"])


if __name__ == "__main__":
    unittest.main()
