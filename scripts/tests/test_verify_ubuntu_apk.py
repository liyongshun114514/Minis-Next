#!/usr/bin/env python3
"""Synthetic-archive tests for the rootfs payload contract in verify_ubuntu_apk.py.

Covers the AGP behaviour that produced cloud run 40201745266's failure: the
Android asset merger inflates and renames every `.gz` asset
(`ubuntu-base-arm64.tar.gz` -> `ubuntu-base-arm64.tar`), so both names are
legal, exactly one must be present, and its content must still be a genuine
Ubuntu 24.04 guest archive. No real APK, signing tool or network is used.

Run:  python3 -m unittest discover -s scripts/tests -p 'test_verify_ubuntu_apk.py' -v
"""

import gzip
import hashlib
import importlib.util
import io
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "verify_ubuntu_apk.py"

spec = importlib.util.spec_from_file_location("verify_ubuntu_apk", SCRIPT)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)

OS_RELEASE = (b'PRETTY_NAME="Ubuntu 24.04.5 LTS"\nNAME="Ubuntu"\n'
              b'VERSION_ID="24.04"\nVERSION_CODENAME=noble\nID=ubuntu\n')

GUEST_FILES = {
    'usr/lib/os-release': OS_RELEASE,
    'usr/bin/apt': b'#!/bin/sh\nexit 0\n',
    'usr/bin/dpkg': b'#!/bin/sh\nexit 0\n',
    'var/lib/dpkg/status': b'Package: base-files\nStatus: install ok installed\n',
}


def make_tar(extra=b'', omit=()):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w') as tar:
        for name, data in GUEST_FILES.items():
            if name in omit:
                continue
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = 0o644
            tar.addfile(info, io.BytesIO(data))
        if extra:
            info = tarfile.TarInfo('usr/share/doc/filler')
            info.size = len(extra)
            tar.addfile(info, io.BytesIO(extra))
    return buf.getvalue()


def make_apk(path, payloads, static=True, alpine=False):
    """Build a minimal APK-shaped zip. `payloads` maps asset name -> bytes."""
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        names = set(verifier.STATIC_REQUIRED) if static else {'AndroidManifest.xml', 'classes.dex'}
        for name in sorted(names):
            z.writestr(name, b'x')
        if alpine:
            z.writestr('assets/alpine-minirootfs.tar.gz', b'old')
        for name, data in payloads.items():
            z.writestr(name, data)
    return path


class RootfsAssetNameTests(unittest.TestCase):
    def test_agp_ungzipped_tar_is_accepted(self):
        self.assertEqual(verifier.rootfs_asset({'assets/ubuntu-base-arm64.tar'}),
                         'assets/ubuntu-base-arm64.tar')

    def test_gzip_name_is_still_accepted(self):
        self.assertEqual(verifier.rootfs_asset({'assets/ubuntu-base-arm64.tar.gz'}),
                         'assets/ubuntu-base-arm64.tar.gz')

    def test_missing_payload_fails(self):
        with self.assertRaises(AssertionError) as ctx:
            verifier.rootfs_asset({'AndroidManifest.xml'})
        self.assertIn('missing payload', str(ctx.exception))

    def test_both_names_is_ambiguous(self):
        with self.assertRaises(AssertionError) as ctx:
            verifier.rootfs_asset(set(verifier.ROOTFS_ASSETS))
        self.assertIn('ambiguous', str(ctx.exception))


class RootfsArchiveContentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='ubuntu-payload-normal-')
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def check(self, name, data, min_bytes=1024, expect_ok=True):
        apk = make_apk(self.dir / 'sample.apk', {name: data})
        with zipfile.ZipFile(apk) as z:
            if expect_ok:
                verifier.validate_rootfs_archive(z, name, min_bytes=min_bytes)
            else:
                with self.assertRaises(AssertionError):
                    verifier.validate_rootfs_archive(z, name, min_bytes=min_bytes)

    def test_raw_tar_from_agp_validates(self):
        self.check('assets/ubuntu-base-arm64.tar', make_tar(b'\0' * 65536))

    def test_gzip_payload_validates(self):
        # Incompressible filler: a gzip of zeros would itself be smaller than the
        # packaged-size floor and fail for the wrong reason.
        filler = b''.join(hashlib.sha256(str(i).encode()).digest() for i in range(512))
        self.check('assets/ubuntu-base-arm64.tar.gz', gzip.compress(make_tar(filler)))

    def test_truncated_tar_is_rejected(self):
        raw = make_tar(b'\0' * 65536)
        self.check('assets/ubuntu-base-arm64.tar', raw[:len(raw) // 2], expect_ok=False)

    def test_truncated_gzip_is_rejected(self):
        filler = b''.join(hashlib.sha256(str(i).encode()).digest() for i in range(512))
        blob = gzip.compress(make_tar(filler))
        self.check('assets/ubuntu-base-arm64.tar.gz', blob[:len(blob) // 2], expect_ok=False)

    def test_gzip_of_non_tar_is_rejected(self):
        self.check('assets/ubuntu-base-arm64.tar.gz', gzip.compress(b'not a tarball'),
                   expect_ok=False)

    def test_tar_without_guest_paths_is_rejected(self):
        self.check('assets/ubuntu-base-arm64.tar', make_tar(b'\0' * 65536, omit=('usr/bin/apt',)),
                   expect_ok=False)

    def test_non_ubuntu_os_release_is_rejected(self):
        global_os = GUEST_FILES['usr/lib/os-release']
        try:
            GUEST_FILES['usr/lib/os-release'] = b'NAME="Alpine Linux"\nID=alpine\n'
            self.check('assets/ubuntu-base-arm64.tar', make_tar(b'\0' * 65536), expect_ok=False)
        finally:
            GUEST_FILES['usr/lib/os-release'] = global_os

    def test_empty_payload_is_rejected(self):
        self.check('assets/ubuntu-base-arm64.tar', b'', expect_ok=False)

    def test_short_payload_is_rejected_even_if_valid_tar(self):
        # Default thresholds reject a real but tiny tar; proves the size floor
        # did not get relaxed along with the accepted names.
        apk = make_apk(self.dir / 'sample.apk',
                       {'assets/ubuntu-base-arm64.tar': make_tar(b'\0' * 65536)})
        with zipfile.ZipFile(apk) as z:
            with self.assertRaises(AssertionError):
                verifier.validate_rootfs_archive(z, 'assets/ubuntu-base-arm64.tar')


class SourceIdentityTests(unittest.TestCase):
    """The APK payload must decompress to the prepare-script-verified source."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='ubuntu-payload-normal-')
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        filler = b''.join(hashlib.sha256(str(i).encode()).digest() for i in range(256))
        self.tar = make_tar(filler)
        self.source = self.dir / 'ubuntu-base-arm64.tar.gz'
        self.source.write_bytes(gzip.compress(self.tar))

    def check(self, name, data, source=None):
        apk = make_apk(self.dir / 'sample.apk', {name: data})
        with zipfile.ZipFile(apk) as z:
            verifier.check_payload_matches_source(
                z, name, self.source if source is None else source)

    def test_agp_raw_tar_matches_source(self):
        self.check('assets/ubuntu-base-arm64.tar', self.tar)

    def test_gzip_payload_matches_source(self):
        self.check('assets/ubuntu-base-arm64.tar.gz', gzip.compress(self.tar))

    def test_different_guest_is_rejected(self):
        other = make_tar(b'other guest' * 64)
        apk = make_apk(self.dir / 'sample.apk', {'assets/ubuntu-base-arm64.tar': other})
        with zipfile.ZipFile(apk) as z:
            with self.assertRaises(AssertionError) as ctx:
                verifier.check_payload_matches_source(
                    z, 'assets/ubuntu-base-arm64.tar', self.source)
        self.assertIn('does not match prepared source', str(ctx.exception))

    def test_tampered_byte_is_rejected(self):
        tampered = bytearray(self.tar)
        tampered[-1] ^= 0xFF
        apk = make_apk(self.dir / 'sample.apk', {'assets/ubuntu-base-arm64.tar': bytes(tampered)})
        with zipfile.ZipFile(apk) as z:
            with self.assertRaises(AssertionError):
                verifier.check_payload_matches_source(
                    z, 'assets/ubuntu-base-arm64.tar', self.source)


class VerifyFailsClosedTests(unittest.TestCase):
    """`verify()` must reject bad payloads before any signing tool is involved."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='ubuntu-payload-normal-')
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def test_missing_rootfs_asset_fails(self):
        apk = make_apk(self.dir / 'missing.apk', {})
        with self.assertRaises(AssertionError) as ctx:
            verifier.verify(apk)
        self.assertIn('missing payload', str(ctx.exception))

    def test_both_rootfs_names_fail(self):
        apk = make_apk(self.dir / 'both.apk', dict.fromkeys(verifier.ROOTFS_ASSETS, b'x'))
        with self.assertRaises(AssertionError) as ctx:
            verifier.verify(apk)
        self.assertIn('ambiguous', str(ctx.exception))

    def test_stale_alpine_asset_still_fails(self):
        apk = make_apk(self.dir / 'alpine.apk', {'assets/ubuntu-base-arm64.tar': b'x'},
                       alpine=True)
        with self.assertRaises(AssertionError) as ctx:
            verifier.verify(apk)
        self.assertIn('Alpine payload', str(ctx.exception))


if __name__ == '__main__':
    unittest.main()
