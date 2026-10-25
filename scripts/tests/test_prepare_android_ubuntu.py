#!/usr/bin/env python3
"""Tests for scripts/prepare_android_ubuntu.sh (stdlib only, no network).

Run:  python3 scripts/tests/test_prepare_android_ubuntu.py -v
"""

import os
import re
import subprocess
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
UBUNTU = REPO / "scripts" / "prepare_android_ubuntu.sh"
SANDBOX = REPO / "scripts" / "prepare_android_sandbox.sh"
ASSET = "ubuntu-base-arm64.tar.gz"


def run(args, **env_overrides):
    env = os.environ.copy()
    env.update(env_overrides)
    return subprocess.run(
        ["bash", str(UBUNTU), *args],
        capture_output=True,
        text=True,
        env=env,
        timeout=120,
    )


class ScriptStaticTests(unittest.TestCase):
    def test_both_scripts_parse(self):
        for script in (UBUNTU, SANDBOX):
            with self.subTest(script=script.name):
                r = subprocess.run(["bash", "-n", str(script)], capture_output=True, text=True)
                self.assertEqual(r.returncode, 0, r.stderr)

    def test_asset_name_and_no_alpine_download(self):
        text = UBUNTU.read_text()
        self.assertIn('ASSET_NAME="ubuntu-base-arm64.tar.gz"', text)
        self.assertIn("ubuntu-base-${UBUNTU_BASE_VERSION}-base-arm64.tar.gz", text)
        # The Alpine image is never downloaded: no Alpine URL, and any curl line
        # that mentions the old name would be a regression.
        self.assertNotIn("alpinelinux.org", text)
        for line in text.splitlines():
            if "alpine-minirootfs" in line:
                self.assertNotIn("curl", line)

    def test_download_is_https_only_and_verifies_signature_and_pin(self):
        text = UBUNTU.read_text()
        self.assertIn("--proto '=https' --tlsv1.2", text)
        self.assertIn("SHA256SUMS.gpg", text)
        self.assertIn("gpgv --keyring", text)
        self.assertIn("843938DF228D22F7B3742BC0D94AA3F0EFE21092", text)
        self.assertIn("pinned_sha256", text)
        self.assertIn("gzip -t", text)
        self.assertIn("REQUIRE_SIGNATURE", text)

    def test_never_extracts_or_executes_the_download(self):
        text = UBUNTU.read_text()
        forbidden = [
            r"\btar\s+-x",
            r"\btar\s+x",
            r"\bchroot\b",
            r"\bexec\s+[\"']?\$",
            r"\bsource\s+[\"']?\$WORK",
            r"\b\.\s+[\"']?\$WORK",
        ]
        for pattern in forbidden:
            with self.subTest(pattern=pattern):
                self.assertIsNone(re.search(pattern, text), f"forbidden pattern {pattern}")

    def test_download_cache_matches_the_ci_cache_path(self):
        text = UBUNTU.read_text()
        self.assertIn('UBUNTU_CACHE_DIR="${UBUNTU_CACHE_DIR:-$PROJECT_ROOT/deps/build/ubuntu-base}"', text)
        self.assertIn('"$UBUNTU_CACHE_DIR/$TARBALL"', text)

    def test_legacy_asset_is_never_left_silently(self):
        text = UBUNTU.read_text()
        self.assertIn("check_legacy_asset", text)
        self.assertIn("MOVE_LEGACY_ASSET", text)
        self.assertIn("verify_ubuntu_apk.py", text)

    def test_sandbox_script_delegates_to_ubuntu_script(self):
        text = SANDBOX.read_text()
        self.assertIn('"$SCRIPT_DIR/prepare_android_ubuntu.sh"', text)
        self.assertIn("proot-aarch64", text)
        self.assertNotIn("alpine-minirootfs-${", text)


class ScriptBehaviourTests(unittest.TestCase):
    def test_help_mentions_the_ubuntu_asset(self):
        r = run(["--help"])
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("ubuntu-base-arm64.tar.gz", r.stdout)
        self.assertIn("SHA256SUMS", r.stdout)

    def test_unknown_argument_is_rejected(self):
        r = run(["--bogus"])
        self.assertEqual(r.returncode, 2)
        self.assertIn("unknown argument", r.stderr)

    def test_unpinned_point_release_fails_closed_before_network(self):
        r = run([], ASSETS_DIR="/tmp/minis-unpinned-test", UBUNTU_BASE_VERSION="24.04.99")
        self.assertEqual(r.returncode, 1)
        self.assertIn("no pinned SHA-256", r.stderr)
        self.assertNotIn("Fetching official SHA256SUMS", r.stdout)

    def test_pinned_digests_are_well_formed(self):
        # Read-only inspection: sourcing the script would execute it.
        text = UBUNTU.read_text()
        digests = re.findall(r"24\.04\.\d\)\s+echo\s+\"([0-9a-f]{64})\"", text)
        self.assertEqual(len(digests), 3, "expected pinned digests for 24.04.3/.4/.5")
        for d in digests:
            self.assertRegex(d, r"^[0-9a-f]{64}$")

    def test_legacy_asset_in_assets_dir_fails_closed(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            assets = Path(tmp) / "assets"
            assets.mkdir()
            (assets / "alpine-minirootfs.tar.gz").write_bytes(b"old")
            cache = Path(tmp) / "cache"
            cache.mkdir()
            # A verified cache entry avoids the network; the legacy asset check
            # runs before anything is installed.
            src = Path("/tmp/ubuntu-base-arm64.tar.gz")
            if not src.is_file():
                self.skipTest("verified tarball not available locally")
            (cache / "ubuntu-base-24.04.5-base-arm64.tar.gz").write_bytes(src.read_bytes())
            r = run([], ASSETS_DIR=str(assets), UBUNTU_CACHE_DIR=str(cache))
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertIn("verify_ubuntu_apk.py", r.stderr)
            self.assertTrue((assets / "alpine-minirootfs.tar.gz").is_file(), "must not delete it")

    def test_0405_pin_matches_official_sums_when_recorded(self):
        # Recorded from https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS
        # (signature by 843938DF228D22F7B3742BC0D94AA3F0EFE21092, verified 2026-10-25).
        expected = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
        text = UBUNTU.read_text()
        self.assertIn(expected, text)


if __name__ == "__main__":
    unittest.main()
