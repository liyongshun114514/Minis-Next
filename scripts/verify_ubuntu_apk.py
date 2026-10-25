#!/usr/bin/env python3
"""Fail closed on missing guest/assets/native libraries; verify actual APK signing.

## Why the rootfs asset has two legal names

The Android asset merger in AGP does **not** respect `androidResources.noCompress`
when it comes to the *name* of a gzip asset: `AssetItem` treats every asset whose
last extension is `gz` as "should be un-gzipped" and rewrites its key with
`Files.getNameWithoutExtension(...)`. `MergedAssetWriter$AssetWorkAction` then
inflates it with `GZIPInputStream` and writes the inflated bytes under that key.
(Verified against AGP 8.7.3 / `com.android.tools:sdk-common:31.7.3`.)

So `src/main/assets/ubuntu-base-arm64.tar.gz` is packaged as
`assets/ubuntu-base-arm64.tar` — a raw (uncompressed) tar — and the `.gz` name
never reaches the APK. `noCompress` only controls ZIP compression, not this
rename. `RootfsManager` already opens `ubuntu-base-arm64.tar.gz` first and falls
back to `ubuntu-base-arm64.tar` (extracting it without gunzip), so both names are
valid runtime contracts.

This verifier therefore requires **exactly one** rootfs payload, accepts either
legal name, and validates the archive itself (gzip/tar integrity, Ubuntu 24.04
guest identity, required guest paths). It additionally compares the payload's
*decompressed* SHA-256 with the prepared source asset
(`src/android/app/src/main/assets/ubuntu-base-arm64.tar.gz`, the file the
prepare script checksum-verified), so a wrong guest cannot pass under either
name. When that source file is absent the byte-identity check is reported as
skipped; the structural checks still run. A wrong, truncated, empty or
non-Ubuntu archive still fails; a truly missing payload still fails.
"""
import gzip
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import zipfile
import zlib

# Both names the runtime accepts (RootfsManager.installIfNeeded).
ROOTFS_ASSETS = ('assets/ubuntu-base-arm64.tar.gz', 'assets/ubuntu-base-arm64.tar')

STATIC_REQUIRED = {
    'AndroidManifest.xml', 'classes.dex',
    'lib/arm64-v8a/libproot.so', 'lib/arm64-v8a/libproot-loader.so',
    'lib/arm64-v8a/libproot-loader32.so', 'lib/arm64-v8a/libgojni.so',
    'lib/arm64-v8a/libminis-chroot.so', 'assets/chroot/minis-offload-client',
}

# Guest paths that must exist in the Ubuntu 24.04 arm64 rootfs. These are
# regular files/symlinks in ubuntu-base (usr-merged layout: /bin, /lib are
# symlinks, so the canonical entries are under usr/).
REQUIRED_GUEST_PATHS = {'usr/lib/os-release', 'usr/bin/apt', 'usr/bin/dpkg',
                        'var/lib/dpkg/status'}

# Minimum packaged sizes; the real payload is ~28.6 MB gzipped / ~102 MB raw.
MIN_PAYLOAD_BYTES = {'.gz': 16 * 1024 * 1024, '.tar': 32 * 1024 * 1024}

DEFAULT_SOURCE_ROOTFS = (Path(__file__).resolve().parents[1]
                         / 'src/android/app/src/main/assets/ubuntu-base-arm64.tar.gz')

OS_RELEASE = re.compile(rb'^ID=ubuntu$', re.M)
VERSION_ID = re.compile(rb'^VERSION_ID="?24\.04"?$', re.M)


def rootfs_asset(names):
    """Return the single rootfs payload name present, or fail closed."""
    present = [n for n in ROOTFS_ASSETS if n in names]
    if not present:
        raise AssertionError(f'missing payload: {set(ROOTFS_ASSETS)}')
    if len(present) > 1:
        # AGP un-gzips, so both can only mean a stale/hand-assembled package.
        raise AssertionError(f'ambiguous rootfs payload (both names present): {sorted(present)}')
    return present[0]


def _normalize(name):
    while name.startswith('./'):
        name = name[2:]
    return name


def validate_rootfs_archive(z, name, min_bytes=None):
    """Prove `name` inside `z` is a genuine Ubuntu 24.04 guest rootfs tar."""
    assert name in ROOTFS_ASSETS, f'unexpected rootfs asset name: {name}'
    gzipped = name.endswith('.gz')
    info = z.getinfo(name)
    minimum = MIN_PAYLOAD_BYTES['.gz' if gzipped else '.tar'] if min_bytes is None else min_bytes
    assert info.file_size >= minimum, (
        f'rootfs payload {name} is truncated/suspiciously small: '
        f'{info.file_size} bytes < {minimum}')

    seen = set()
    os_release = b''
    try:
        with z.open(name) as raw:
            stream = gzip.GzipFile(fileobj=raw, mode='rb') if gzipped else raw
            with tarfile.open(fileobj=stream, mode='r|') as tar:
                for member in tar:
                    path = _normalize(member.name)
                    if path in REQUIRED_GUEST_PATHS:
                        seen.add(path)
                    if path == 'usr/lib/os-release' and member.isreg():
                        handle = tar.extractfile(member)
                        if handle is not None:
                            os_release = handle.read(8192)
            if gzipped:
                # Drain the gzip stream so its CRC/length trailer is checked.
                while stream.read(1 << 20):
                    pass
    except (tarfile.TarError, OSError, EOFError, zlib.error) as exc:
        raise AssertionError(
            f'rootfs payload {name} is not a valid {"gzip/tar" if gzipped else "tar"} stream: {exc}') from exc

    missing = REQUIRED_GUEST_PATHS - seen
    assert not missing, f'rootfs payload {name} is not an Ubuntu guest: missing {sorted(missing)}'
    assert OS_RELEASE.search(os_release), f'rootfs payload {name}: /usr/lib/os-release is not Ubuntu'
    assert VERSION_ID.search(os_release), (
        f'rootfs payload {name}: /usr/lib/os-release is not Ubuntu 24.04')


def _decompressed_digest(stream):
    digest = hashlib.sha256()
    total = 0
    while True:
        chunk = stream.read(1 << 20)
        if not chunk:
            return digest.hexdigest(), total
        digest.update(chunk)
        total += len(chunk)


def apk_payload_digest(z, name):
    """SHA-256 of the rootfs tar bytes, after gunzip when the APK kept .gz."""
    try:
        with z.open(name) as raw:
            if name.endswith('.gz'):
                with gzip.GzipFile(fileobj=raw, mode='rb') as stream:
                    return _decompressed_digest(stream)
            return _decompressed_digest(raw)
    except (OSError, EOFError, zlib.error) as exc:
        raise AssertionError(f'cannot digest rootfs payload {name}: {exc}') from exc


def source_payload_digest(source: Path):
    try:
        with gzip.open(source, 'rb') as stream:
            return _decompressed_digest(stream)
    except (OSError, EOFError, zlib.error) as exc:
        raise AssertionError(f'prepared source {source} is not a valid gzip tar: {exc}') from exc


def check_payload_matches_source(z, name, source: Path):
    expected = source_payload_digest(source)
    actual = apk_payload_digest(z, name)
    assert actual == expected, (
        f'APK rootfs payload {name} does not match prepared source {source}: '
        f'decompressed sha256 {actual[0]} ({actual[1]} bytes) != '
        f'{expected[0]} ({expected[1]} bytes)')


def verify(apk: Path, source_rootfs=None):
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None, f'corrupt ZIP: {apk}'
        names = set(z.namelist())
        missing = STATIC_REQUIRED - names
        assert not missing, f'missing payload: {missing}'
        rootfs = rootfs_asset(names)
        assert 'assets/alpine-minirootfs.tar.gz' not in names, 'old Alpine payload still bundled'
        assert not any(n.startswith('lib/') and n.split('/')[1] != 'arm64-v8a' for n in names), 'unexpected ABI'
        validate_rootfs_archive(z, rootfs)
        source = (Path(source_rootfs) if source_rootfs is not None else
                  Path(os.environ.get('UBUNTU_ROOTFS_SOURCE') or DEFAULT_SOURCE_ROOTFS))
        if source.is_file():
            check_payload_matches_source(z, rootfs, source)
        elif os.environ.get('UBUNTU_ROOTFS_SOURCE'):
            raise AssertionError(f'UBUNTU_ROOTFS_SOURCE={source} does not exist')
        else:
            print(f'note: prepared source {source} absent; skipped byte-identity check',
                  file=sys.stderr)
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    tools = sdk / 'build-tools' / '35.0.0'
    badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    assert "package: name='com.openminis.app.ubuntu'" in badging, 'wrong installation package id'
    assert 'launchable-activity:' in badging, 'launcher entry missing'
    subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', str(apk)], check=True)
    subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    apk.with_suffix('.apk.sha256').write_text(f'{digest}  {apk.name}\n')
    print(f'PASS {apk} {apk.stat().st_size} bytes SHA256={digest}; '
          f'rootfs={rootfs}; not installed or run')


if __name__ == '__main__':
    paths = sorted(Path(sys.argv[1]).rglob('*.apk'))
    if not paths:
        raise SystemExit('No APK found; build is not a success')
    for path in paths:
        verify(path)
