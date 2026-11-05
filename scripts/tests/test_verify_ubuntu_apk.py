#!/usr/bin/env python3
"""Synthetic-archive tests for the rootfs payload contract in verify_ubuntu_apk.py.

Covers the AGP behaviour that produced cloud run 40201745266's failure: the
Android asset merger inflates and renames every `.gz` asset
(`ubuntu-base-arm64.tar.gz` -> `ubuntu-base-arm64.tar`), so both names are
legal, exactly one must be present, and its content must still be a genuine
Ubuntu 24.04 guest archive. No real APK, signing tool or network is used.

The `ToolsetContractTests` classes use small *synthetic* preinstalled-toolset
archives (manifest + bootstrap.done + dpkg status + guest paths) to pin the new
contract: `verify()` must reject bare ubuntu-base, while
`validate_rootfs_archive(require_toolset=False)` keeps the structural-only
behaviour. Nothing is extracted, executed or downloaded.

Run:  python3 -m unittest discover -s scripts/tests -p 'test_verify_ubuntu_apk.py' -v
"""

import gzip
import hashlib
import importlib.util
import io
import json
import os
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

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


# ── Synthetic preinstalled-toolset guest ────────────────────────────────────
# Versions are invented but dpkg-shaped; the archive is never executed.
TOOLSET_VERSIONS = {
    'bash': '5.2.21-2ubuntu4',
    'ca-certificates': '20240203',
    'curl': '8.5.0-2ubuntu10.6',
    'wget': '1.21.4-1ubuntu4.1',
    'git': '1:2.43.0-1ubuntu7.3',
    'openssh-client': '1:9.6p1-3ubuntu13.14',
    'less': '590-2ubuntu2.1',
    'procps': '2:4.0.4-4ubuntu3.2',
    'iproute2': '6.1.0-1ubuntu6',
    'unzip': '6.0-28ubuntu4.1',
    'xz-utils': '5.6.1+really5.4.5-1ubuntu0.2',
    'jq': '1.7.1-3ubuntu0.24.04.1',
    'ripgrep': '14.1.0-1',
    'python3': '3.12.3-0ubuntu2',
    'python3-pip': '24.0+dfsg-1ubuntu1.2',
    'python3-venv': '3.12.3-0ubuntu2',
}

TOOLSET_COMMANDS = {
    'bash': 'GNU bash, version 5.2.21(1)-release (aarch64-unknown-linux-gnu)',
    'python3': 'Python 3.12.3',
    'git': 'git version 2.43.0',
    'curl': 'curl 8.5.0 (aarch64-unknown-linux-gnu)',
    'wget': 'GNU Wget 1.21.4',
    'ssh': 'OpenSSH_9.6p1 Ubuntu-3ubuntu13.14',
    'rg': 'ripgrep 14.1.0',
    'jq': 'jq-1.7.1',
    'pip': 'pip 24.0 from /usr/lib/python3/dist-packages/pip (python 3.12)',
    'venv': 'Python 3.12.3',
}

TOOLSET_MANIFEST_PATH = verifier.TOOLSET_MANIFEST
MARKER_PATH = verifier.BOOTSTRAP_DONE_MARKER
TOOLSET_BINARIES = (
    'usr/bin/bash', 'usr/bin/git', 'usr/bin/curl', 'usr/bin/wget',
    'usr/bin/ssh', 'usr/bin/rg', 'usr/bin/jq', 'usr/bin/pip3',
    'usr/bin/less', 'usr/bin/ps', 'usr/bin/ip', 'usr/bin/unzip',
    'usr/bin/xz', 'usr/lib/python3.12/venv/__init__.py',
)


def toolset_manifest(packages=None, commands=None, **overrides):
    manifest = {
        'schema': 1,
        'distro': 'ubuntu24.04',
        'arch': 'arm64',
        'official_base_sha256': verifier.OFFICIAL_BASE_SHA256,
        'packages': dict(TOOLSET_VERSIONS if packages is None else packages),
        'commands': dict(TOOLSET_COMMANDS if commands is None else commands),
    }
    manifest.update(overrides)
    return manifest


def dpkg_status(packages=None, statuses=None, versions=None, descriptions=False):
    """dpkg status text for `packages`, with per-package overrides."""
    packages = TOOLSET_VERSIONS if packages is None else packages
    statuses = statuses or {}
    versions = versions or {}
    stanzas = []
    for name, version in packages.items():
        stanza = ('Package: {}\nStatus: {}\nArchitecture: arm64\n'
                  'Version: {}\n'.format(
                      name, statuses.get(name, 'install ok installed'),
                      versions.get(name, version)))
        if descriptions:
            stanza += ('Description: synthetic package {}\n'
                       ' Long description line one.\n'
                       ' .\n'
                       ' Long description line two.\n'.format(name))
        stanzas.append(stanza)
    return '\n'.join(stanzas).encode()


def make_toolset_tar(manifest=None, packages=None, status=None, marker=True,
                     symlink_python3=True, omit=(), raw_manifest=None,
                     filler=b'\0' * 65536, duplicate=()):
    """Tar bytes of a synthetic *preinstalled toolset* Ubuntu guest."""
    packages = dict(TOOLSET_VERSIONS if packages is None else packages)
    if raw_manifest is None:
        manifest = toolset_manifest(packages) if manifest is None else manifest
        raw_manifest = json.dumps(manifest).encode()
    if status is None:
        status = dpkg_status(packages)
    files = {
        'usr/lib/os-release': GUEST_FILES['usr/lib/os-release'],
        'usr/bin/apt': b'#!/bin/sh\nexit 0\n',
        'usr/bin/dpkg': b'#!/bin/sh\nexit 0\n',
        'var/lib/dpkg/status': status,
        TOOLSET_MANIFEST_PATH: raw_manifest,
    }
    if marker:
        files[MARKER_PATH] = b'packages installed 2026-10-26T00:00:00Z\n'
    for path in TOOLSET_BINARIES:
        files[path] = b'#!/bin/sh\nexit 0\n'
    for name in omit:
        files.pop(name, None)
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w') as tar:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = 0o755
            tar.addfile(info, io.BytesIO(data))
        if 'usr/bin/python3' not in omit:
            info = tarfile.TarInfo('usr/bin/python3')
            if symlink_python3:
                info.type = tarfile.SYMTYPE
                info.linkname = 'python3.12'
                tar.addfile(info)
            else:
                data = b'#!/bin/sh\nexit 0\n' * 2
                info.size = len(data)
                info.mode = 0o755
                tar.addfile(info, io.BytesIO(data))
        for name in duplicate:
            info = tarfile.TarInfo(name)
            info.size = 2
            tar.addfile(info, io.BytesIO(b'{}'))
        if filler:
            info = tarfile.TarInfo('usr/share/doc/filler')
            info.size = len(filler)
            tar.addfile(info, io.BytesIO(filler))
    return buf.getvalue()


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


def dot_prefixed(data):
    """Re-pack a tar the way `tar -C rootfs .` does: "./" names + dir members."""
    source = tarfile.open(fileobj=io.BytesIO(data))
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode='w') as tar:
        root = tarfile.TarInfo('./')
        root.type = tarfile.DIRTYPE
        root.mode = 0o755
        tar.addfile(root)
        directories = set()
        for member in source.getmembers():
            name = './' + member.name.lstrip('./')
            parts = name.split('/')[:-1]
            for depth in range(2, len(parts) + 1):
                directory = '/'.join(parts[:depth]) + '/'
                if directory not in directories:
                    directories.add(directory)
                    info = tarfile.TarInfo(directory)
                    info.type = tarfile.DIRTYPE
                    info.mode = 0o755
                    tar.addfile(info)
            member.name = name
            tar.addfile(member, source.extractfile(member) if member.isreg() else None)
    return out.getvalue()


class ToolsetContractTests(unittest.TestCase):
    """`require_toolset=True` must prove a preinstalled image, not bare base."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='ubuntu-toolset-normal-')
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def validate(self, data, require_toolset=True, name='assets/ubuntu-base-arm64.tar'):
        apk = make_apk(self.dir / 'toolset.apk', {name: data})
        with zipfile.ZipFile(apk) as z:
            return verifier.validate_rootfs_archive(
                z, name, min_bytes=1024, require_toolset=require_toolset)

    def rejected(self, data, *fragments, require_toolset=True):
        with self.assertRaises(AssertionError) as ctx:
            self.validate(data, require_toolset=require_toolset)
        message = str(ctx.exception)
        self.assertIn('toolset', message)
        for fragment in fragments:
            self.assertIn(fragment, message)
        return message

    def test_preinstalled_toolset_is_accepted(self):
        scan = self.validate(make_toolset_tar())
        self.assertEqual(scan.toolset['packages'], len(verifier.REQUIRED_TOOLSET_PACKAGES))
        self.assertEqual(scan.toolset['official_base_sha256'], verifier.OFFICIAL_BASE_SHA256)
        self.assertEqual(scan.kind('usr/bin/python3'), 'sym')

    def test_gzip_wrapped_toolset_is_accepted(self):
        data = gzip.compress(make_toolset_tar())
        scan = self.validate(data, name='assets/ubuntu-base-arm64.tar.gz')
        self.assertEqual(scan.toolset['packages'], len(verifier.REQUIRED_TOOLSET_PACKAGES))

    def test_regular_python3_is_accepted(self):
        scan = self.validate(make_toolset_tar(symlink_python3=False))
        self.assertEqual(scan.kind('usr/bin/python3'), 'reg')

    def test_bare_base_archive_is_rejected(self):
        self.rejected(make_tar(b'\0' * 65536), 'toolset-manifest.json')

    def test_bare_base_still_accepted_by_structural_only_mode(self):
        # Backwards compatibility for the existing 20 tests and for
        # hand-inspected archives: the opt-in flag is what adds the proof.
        apk = make_apk(self.dir / 'base.apk',
                       {'assets/ubuntu-base-arm64.tar': make_tar(b'\0' * 65536)})
        with zipfile.ZipFile(apk) as z:
            scan = verifier.validate_rootfs_archive(
                z, 'assets/ubuntu-base-arm64.tar', min_bytes=1024)
        self.assertIsNone(scan.toolset)

    def test_missing_marker_is_rejected(self):
        self.rejected(make_toolset_tar(omit=(MARKER_PATH,)), 'bootstrap.done')

    def test_marker_alone_is_rejected(self):
        self.rejected(make_toolset_tar(omit=(TOOLSET_MANIFEST_PATH,)),
                      'toolset-manifest.json')

    def test_manifest_and_marker_without_installed_packages_are_rejected(self):
        base_status = (b'Package: base-files\nStatus: install ok installed\n'
                       b'Version: 13ubuntu10\n')
        self.rejected(make_toolset_tar(status=base_status),
                      'var/lib/dpkg/status')

    def test_manifest_and_marker_without_guest_binaries_are_rejected(self):
        self.rejected(make_toolset_tar(omit=TOOLSET_BINARIES),
                      'required guest executable missing', 'usr/bin/bash')

    def test_package_not_configured_is_rejected(self):
        status = dpkg_status(statuses={'python3': 'deinstall ok config-files'})
        self.rejected(make_toolset_tar(status=status), 'python3', 'install ok installed')

    def test_missing_dpkg_status_is_rejected(self):
        # Fails at the structural Ubuntu-guest check first; either way the
        # archive cannot pass without dpkg state.
        with self.assertRaises(AssertionError) as ctx:
            self.validate(make_toolset_tar(omit=('var/lib/dpkg/status',)))
        self.assertIn('var/lib/dpkg/status', str(ctx.exception))

    def test_missing_required_package_is_rejected(self):
        packages = dict(TOOLSET_VERSIONS)
        packages.pop('ripgrep')
        self.rejected(make_toolset_tar(packages=packages), 'ripgrep')

    def test_manifest_version_mismatch_with_dpkg_is_rejected(self):
        status = dpkg_status(versions={'git': '1:2.40.0-1'})
        self.rejected(make_toolset_tar(status=status), 'git', 'version')

    def test_manifest_base_sha_mismatch_is_rejected(self):
        manifest = toolset_manifest(official_base_sha256='0' * 64)
        self.rejected(make_toolset_tar(manifest=manifest), 'official_base_sha256')

    def test_manifest_identity_fields_are_enforced(self):
        for field, value in (('distro', 'ubuntu22.04'), ('arch', 'x86_64'),
                             ('schema', 2), ('schema', '1'), ('schema', True)):
            with self.subTest(field=field, value=value):
                manifest = toolset_manifest(**{field: value})
                self.rejected(make_toolset_tar(manifest=manifest), field)

    def test_missing_command_result_is_rejected(self):
        commands = dict(TOOLSET_COMMANDS)
        commands.pop('git')
        self.rejected(make_toolset_tar(manifest=toolset_manifest(commands=commands)),
                      'command version results', 'git')

    def test_empty_command_output_is_rejected(self):
        commands = dict(TOOLSET_COMMANDS)
        commands['curl'] = '  \n'
        self.rejected(make_toolset_tar(manifest=toolset_manifest(commands=commands)),
                      'curl')

    def test_missing_guest_binary_is_rejected(self):
        self.rejected(make_toolset_tar(omit=('usr/bin/rg',)), 'usr/bin/rg')

    def test_missing_venv_module_is_rejected(self):
        self.rejected(make_toolset_tar(omit=('usr/lib/python3.12/venv/__init__.py',)),
                      'venv')

    def test_python3_directory_is_not_an_executable(self):
        data = make_toolset_tar(omit=('usr/bin/python3',))
        buf = io.BytesIO(data)
        with tarfile.open(fileobj=buf, mode='a') as tar:
            info = tarfile.TarInfo('usr/bin/python3')
            info.type = tarfile.DIRTYPE
            info.mode = 0o755
            tar.addfile(info)
        self.rejected(buf.getvalue(), 'usr/bin/python3')

    def test_duplicate_manifest_is_rejected(self):
        self.rejected(make_toolset_tar(duplicate=(TOOLSET_MANIFEST_PATH,)),
                      'more than once')

    def test_marker_as_directory_is_rejected(self):
        data = make_toolset_tar(omit=(MARKER_PATH,))
        buf = io.BytesIO(data)
        with tarfile.open(fileobj=buf, mode='a') as tar:
            info = tarfile.TarInfo(MARKER_PATH + '/')
            info.type = tarfile.DIRTYPE
            info.mode = 0o755
            tar.addfile(info)
        self.rejected(buf.getvalue(), 'bootstrap.done')

    def test_dot_prefixed_archive_with_directory_entries_is_accepted(self):
        # `tar -C rootfs .` stores "./" prefixes and directory members; the
        # builder produces exactly that.
        data = dot_prefixed(make_toolset_tar())
        scan = self.validate(data)
        self.assertEqual(scan.kind('usr/bin/python3'), 'sym')
        self.assertEqual(scan.kind('usr/local/share/minis/toolset-manifest.json'), 'reg')

    def test_multiline_dpkg_descriptions_are_accepted(self):
        status = dpkg_status(descriptions=True)
        self.validate(make_toolset_tar(status=status))

    def test_arch_qualified_package_entry_is_accepted(self):
        packages = dict(TOOLSET_VERSIONS)
        status = dpkg_status().replace(b'Package: python3\n',
                                       b'Package: python3:arm64\n')
        self.validate(make_toolset_tar(packages=packages, status=status))


class ToolsetPackageListDriftTests(unittest.TestCase):
    """The verifier's required set must stay the shipped toolset."""

    def test_required_packages_match_builder_list(self):
        list_file = REPO / 'scripts' / 'ubuntu-toolset-packages.txt'
        if not list_file.is_file():
            self.skipTest('toolset package list not present in this checkout')
        listed = [line.strip() for line in list_file.read_text().splitlines()
                  if line.strip()]
        self.assertEqual(set(listed), set(verifier.REQUIRED_TOOLSET_PACKAGES))
        self.assertEqual(len(listed), 16)

    def test_paths_and_pin_match_builder(self):
        script = REPO / 'scripts' / 'build_ubuntu_toolset.sh'
        if not script.is_file():
            self.skipTest('toolset builder not present in this checkout')
        text = script.read_text()
        self.assertIn(verifier.TOOLSET_MANIFEST, text)
        self.assertIn(verifier.BOOTSTRAP_DONE_MARKER, text)
        self.assertIn(verifier.OFFICIAL_BASE_SHA256, text)


class ToolsetDefaultEnforcementTests(unittest.TestCase):
    """`verify()` is the production entry point and defaults to requiring it."""

    def test_environment_default_is_on(self):
        env = {k: v for k, v in os.environ.items() if k != verifier.TOOLSET_ENV}
        with mock.patch.dict(os.environ, env, clear=True):
            self.assertTrue(verifier.require_toolset_default())

    def test_environment_switch_values(self):
        for value in ('1', 'true', 'YES', 'on'):
            with self.subTest(value=value):
                with mock.patch.dict(os.environ, {verifier.TOOLSET_ENV: value}):
                    self.assertTrue(verifier.require_toolset_default())
        for value in ('0', 'false', 'no', 'off'):
            with self.subTest(value=value):
                with mock.patch.dict(os.environ, {verifier.TOOLSET_ENV: value}):
                    # No bypass: a release APK must carry the toolset.
                    with self.assertRaises(AssertionError) as ctx:
                        verifier.require_toolset_default()
                self.assertIn('bare ubuntu-base', str(ctx.exception))
        with mock.patch.dict(os.environ, {verifier.TOOLSET_ENV: 'maybe'}):
            with self.assertRaises(AssertionError):
                verifier.require_toolset_default()

    def test_verify_parameter_defaults_to_environment(self):
        import inspect
        parameter = inspect.signature(verifier.verify).parameters['require_toolset']
        self.assertIsNone(parameter.default)


class VerifyRejectsBareBaseTests(unittest.TestCase):
    """End-to-end `verify()` on a bare base archive above the size floor."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='ubuntu-toolset-verify-')
        cls.dir = Path(cls.tmp.name)
        # > 32 MiB raw, so the packaged-size floor is genuinely satisfied and
        # only the toolset proof can reject the APK.
        cls.tar = make_tar(b'\0' * (33 * 1024 * 1024))
        cls.source = cls.dir / 'ubuntu-base-arm64.tar.gz'
        cls.source.write_bytes(gzip.compress(cls.tar))
        cls.apk = make_apk(cls.dir / 'bare.apk',
                           {'assets/ubuntu-base-arm64.tar': cls.tar})

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def environment(self, extra=None):
        env = {k: v for k, v in os.environ.items()
               if k not in (verifier.TOOLSET_ENV, 'UBUNTU_ROOTFS_SOURCE')}
        env['UBUNTU_ROOTFS_SOURCE'] = str(self.source)  # digest would match
        env.update(extra or {})
        return env

    def test_verify_rejects_bare_base_by_default(self):
        with mock.patch.dict(os.environ, self.environment(), clear=True):
            with self.assertRaises(AssertionError) as ctx:
                verifier.verify(self.apk)
        self.assertIn('toolset-manifest.json', str(ctx.exception))

    def test_verify_honours_forced_environment_switch(self):
        with mock.patch.dict(os.environ,
                             self.environment({verifier.TOOLSET_ENV: '1'}), clear=True):
            with self.assertRaises(AssertionError) as ctx:
                verifier.verify(self.apk)
        self.assertIn('toolset', str(ctx.exception))


if __name__ == '__main__':
    unittest.main()
