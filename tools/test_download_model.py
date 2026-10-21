"""Tests for scripts/download_model.py: verified, atomic, honest about failure, and in step with the Kotlin catalog."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("download_model", ROOT / "scripts" / "download_model.py")
dm = importlib.util.module_from_spec(spec)
sys.modules["download_model"] = dm
spec.loader.exec_module(dm)

BODY = bytes(range(64))
SHA = hashlib.sha256(BODY).hexdigest()


def opener(data: bytes):
    return lambda url: io.BytesIO(data)


class DownloadModelTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dest = str(Path(self.tmp.name, "m.litertlm"))

    def tearDown(self):
        self.tmp.cleanup()

    def leftovers(self):
        return sorted(p.name for p in Path(self.tmp.name).iterdir())

    def test_good_download_is_installed_and_verified(self):
        dm.download_verified("https://huggingface.co/x", self.dest, len(BODY), SHA, open_url=opener(BODY))
        self.assertEqual(Path(self.dest).read_bytes(), BODY)
        self.assertEqual(self.leftovers(), ["m.litertlm"])

    def test_wrong_digest_leaves_nothing_behind(self):
        with self.assertRaises(dm.IntegrityError):
            dm.download_verified("https://huggingface.co/x", self.dest, len(BODY), "0" * 64, open_url=opener(BODY))
        self.assertEqual(self.leftovers(), [])

    def test_truncated_and_oversized_downloads_are_rejected_without_partial_files(self):
        for data in (BODY[:-1], BODY + b"x"):
            with self.assertRaises(dm.IntegrityError):
                dm.download_verified("https://huggingface.co/x", self.dest, len(BODY), SHA, open_url=opener(data))
            self.assertEqual(self.leftovers(), [])

    def test_a_dropped_connection_leaves_no_partial_file(self):
        def broken(url):
            raise OSError("connection reset")
        with self.assertRaises(OSError):
            dm.download_verified("https://huggingface.co/x", self.dest, len(BODY), SHA, open_url=broken)
        self.assertEqual(self.leftovers(), [])

    def test_only_https_huggingface_hosts_are_fetched(self):
        for bad in ("http://huggingface.co/x", "https://evil.example/x", "https://huggingface.co.evil.example/x",
                    "https://huggingface.co@evil.example/x", "file:///etc/passwd"):
            with self.assertRaises(dm.IntegrityError, msg=bad):
                dm.check_url(bad)
        dm.check_url("https://huggingface.co/a/b")
        dm.check_url("https://cdn-lfs.huggingface.co/a")

    def test_main_replaces_a_wrong_existing_file_instead_of_skipping_it(self):
        model = dict(dm.AVAILABLE_MODELS["qwen3-1.7b"], size_bytes=len(BODY), sha256=SHA)
        dm.AVAILABLE_MODELS["qwen3-1.7b"], saved = model, dm.AVAILABLE_MODELS["qwen3-1.7b"]
        try:
            stub = Path(self.tmp.name, model["target_filename"])
            stub.write_bytes(bytes(303))
            src = Path(self.tmp.name, "real.bin")
            src.write_bytes(BODY)
            self.assertEqual(dm.main(["--dest-dir", self.tmp.name, "--copy-from", str(src)]), 0)
            self.assertEqual(stub.read_bytes(), BODY)
        finally:
            dm.AVAILABLE_MODELS["qwen3-1.7b"] = saved

    def test_main_reports_failure_with_a_nonzero_exit_and_installs_nothing(self):
        src = Path(self.tmp.name, "bad.bin")
        src.write_bytes(b"not a model")
        self.assertEqual(dm.main(["--dest-dir", self.tmp.name, "--copy-from", str(src)]), 1)
        self.assertEqual(dm.main(["--dest-dir", self.tmp.name, "--verify-only"]), 1)
        self.assertEqual([p.name for p in Path(self.tmp.name).iterdir() if p.suffix == ".litertlm"], [])

    def test_python_table_matches_the_kotlin_catalog(self):
        kt = (ROOT / "game-ai/src/commonMain/kotlin/org/livingworld/ai/ondevice/ModelAssetManager.kt").read_text()
        for key, m in dm.AVAILABLE_MODELS.items():
            self.assertRegex(m["sha256"], r"^[0-9a-f]{64}$", key)
            self.assertRegex(m["revision"], r"^[0-9a-f]{40}$", key)
            self.assertIn(dm.model_url(m), kt, f"{key}: pinned URL differs from Kotlin")
            self.assertIn(m["sha256"], kt, f"{key}: sha256 differs from Kotlin")
            self.assertIn(f"{m['size_bytes']:_}L", kt, f"{key}: size differs from Kotlin")
            self.assertIn(f'targetFileName = "{m["target_filename"]}"', kt)


if __name__ == "__main__":
    unittest.main()
