"""Behavior checks for the CI SDK cache, without live network dependencies."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("prepare_sdks", ROOT / "scripts/prepare_robolectric_sdks.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PrepareSdkTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.destination = self.root / "sdks"
        self.content = b"verified test SDK"
        self.manifest = self.root / "manifest.json"
        self.manifest.write_text(json.dumps({
            "repositories": ["https://first.example/maven2", "https://second.example/maven2"],
            "sdks": [{"api": 35, "version": "test-i7", "sha512": hashlib.sha512(self.content).hexdigest()}],
        }))
        self.target = self.destination / "android-all-instrumented-test-i7.jar"
        self.calls = []

    def prepare(self, downloader):
        with contextlib.redirect_stdout(io.StringIO()):
            return MODULE.prepare(self.destination, self.manifest, downloader)

    def downloader(self, responses):
        def run(args, **kwargs):
            self.calls.append(args)
            payload, status = responses.pop(0)
            Path(args[args.index("--output") + 1]).write_bytes(payload)
            return subprocess.CompletedProcess(args, status)
        return run

    def test_download_is_flat_verified_and_atomic(self):
        self.prepare(self.downloader([(self.content, 0)]))
        self.assertEqual(self.target.read_bytes(), self.content)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(sorted(p.name for p in self.destination.iterdir()), [self.target.name])

    def test_verified_cache_never_downloads(self):
        self.destination.mkdir()
        self.target.write_bytes(self.content)
        self.prepare(self.downloader([]))
        self.assertEqual(self.calls, [])

    def test_corrupt_cache_is_replaced(self):
        self.destination.mkdir()
        self.target.write_bytes(b"truncated")
        self.prepare(self.downloader([(self.content, 0)]))
        self.assertEqual(self.target.read_bytes(), self.content)
        self.assertEqual(len(self.calls), 1)

    def test_http_failure_falls_back_to_next_mirror(self):
        self.prepare(self.downloader([(b"429 Too Many Requests", 22), (self.content, 0)]))
        self.assertEqual(self.target.read_bytes(), self.content)
        self.assertIn("https://second.example/", self.calls[1][-1])

    def test_successful_http_with_wrong_hash_also_falls_back(self):
        self.prepare(self.downloader([(b"wrong SDK", 0), (self.content, 0)]))
        self.assertEqual(self.target.read_bytes(), self.content)
        self.assertEqual(len(self.calls), 2)

    def test_exhausted_mirrors_fail_without_partial_or_unverified_sdk(self):
        with self.assertRaisesRegex(RuntimeError, "No verified download"):
            self.prepare(self.downloader([(b"bad", 0), (b"bad", 22)]))
        self.assertEqual(list(self.destination.iterdir()), [])

    def test_every_test_worker_receives_the_offline_directory(self):
        script = (ROOT / "src/android/app/build.gradle.kts").read_text()
        tests = script.split("tasks.withType<Test>().configureEach {", 1)[1]
        self.assertIn('providers.environmentVariable("ROBOLECTRIC_SDK_DIR")', script)
        self.assertIn('systemProperty("robolectric.offline", "true")', tests)
        self.assertIn('systemProperty("robolectric.dependency.dir", sdkDirectory)', tests)
        workflow = (ROOT / ".github/workflows/android-ubuntu.yml").read_text()
        self.assertLess(workflow.index("Prepare verified offline Robolectric SDKs"), workflow.index("- name: Build APK"))


if __name__ == "__main__":
    unittest.main()
