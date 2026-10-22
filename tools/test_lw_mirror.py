"""Tests for the lw_mirror Python kernel mirror (float emulation, text rules, parity with the Kotlin goldens)."""
import gzip
import json
import math
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DAYS = json.loads((ROOT / "tests" / "golden" / "scenarios.json").read_text())["days"]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))

from lw_mirror.f32 import f32, f4, stable_float_text, java_round, stable_float_text as sft  # noqa: E402
from lw_mirror import runner  # noqa: E402
from lw_mirror.combat import stable_string_hash  # noqa: E402
import mirror_parity  # noqa: E402


def golden(name):
    return gzip.open(ROOT / "tests" / "golden" / name, "rt").read().splitlines()


class F32Test(unittest.TestCase):
    def test_rounding(self):
        self.assertEqual(f32(0.1), 0.10000000149011612)
        self.assertEqual(f32(16777217), 16777216.0)
        self.assertEqual(f32(16777219), 16777220.0)
        self.assertEqual(f32(1e39), math.inf)
        self.assertEqual(f32(-1e39), -math.inf)
        self.assertEqual(f32(0.5), 0.5)
        self.assertEqual(f32(f32(0.1) + f32(0.2)), f32(0.30000001192092896))
        self.assertNotEqual(0.1 + 0.2, f32(0.1) + f32(0.2))

    def test_string_hash_wraps(self):
        self.assertEqual(stable_string_hash("a"), 97)
        h = 0
        for ch in "blacksmith_krag":
            h = (31 * h + ord(ch)) & 0xFFFFFFFF
        self.assertEqual(stable_string_hash("blacksmith_krag") & 0xFFFFFFFF, h)
        self.assertTrue(-2**31 <= stable_string_hash("blacksmith_krag") < 2**31)


class F4Test(unittest.TestCase):
    def test_basic(self):
        for value, text in [
            (0.0, "0.0"), (100.0, "100.0"), (0.25, "0.25"), (-0.25, "-0.25"), (f32(12.35), "12.35"),
            (f32(0.1), "0.1"), (f32(-0.02), "-0.02"), (1e9, "1000000000.0"), (999999936.0, "999999936.0"),
            (f32(0.2745), "0.2745"), (-0.0, "0.0"), (float("nan"), "NaN"),
        ]:
            self.assertEqual(f4(value), text, value)

    def test_ties(self):
        # x = m/32 makes x*10000 an exact .5 tie: f4 rounds half up (Math.round), stableFloatText half to even.
        self.assertEqual(f4(1 / 32), "0.0313")
        self.assertEqual(f4(-1 / 32), "-0.0312")
        self.assertEqual(sft(1 / 32), "0.0312")
        self.assertEqual(sft(-1 / 32), "-0.0312")
        self.assertEqual(f4(3 / 32), "0.0938")
        self.assertEqual(sft(3 / 32), "0.0938")

    def test_java_round(self):
        self.assertEqual(java_round(2.5), 3)
        self.assertEqual(java_round(-2.5), -2)
        self.assertEqual(java_round(0.49999999999999994), 0)
        self.assertEqual(java_round(float("nan")), 0)

    def test_stable_float_text_large(self):
        self.assertTrue(stable_float_text(1.0e9).startswith("bits:"))
        self.assertEqual(stable_float_text(14.0), "14.0")
        self.assertEqual(stable_float_text(7.5), "7.5")
        self.assertEqual(stable_float_text(float("inf")), "Infinity")


class ParityTest(unittest.TestCase):
    def test_first_lines_two_days(self):
        for scenario in ("default", "turmoil"):
            with self.subTest(scenario=scenario):
                lines = runner.run(scenario, 2)
                self.assertEqual(lines[:50], golden(f"{scenario}-{DAYS}d.txt.gz")[:50])

    def test_full_parity(self):
        self.assertEqual(mirror_parity.main([]), 0)


if __name__ == "__main__":
    unittest.main()
