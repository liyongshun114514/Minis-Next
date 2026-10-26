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

## Why the preinstalled toolset is mandatory

Users no longer accept a first-terminal `apt-get install`. The CI job builds the
toolset image (`scripts/build_ubuntu_toolset.sh`) and this verifier proves the
packaged tar is that image — from evidence *inside* the tar only, never by
extracting or running anything:

  * `usr/local/share/minis/toolset-manifest.json` — schema 1, distro
    `ubuntu24.04`, arch `arm64`, the pinned `official_base_sha256`, a
    `packages` map of concrete dpkg versions and a `commands` map of version
    probe output;
  * `var/lib/minis/bootstrap.done` — written by the builder only after every
    package was `install ok installed`, `dpkg --audit` was clean and every
    command probe ran;
  * `var/lib/dpkg/status` — every required package `Status: install ok
    installed` with exactly the manifest's version;
  * the ordinary software paths (`usr/bin/python3`, git, curl, wget, ssh, rg,
    jq, pip, the venv module). `python3` is normally a symlink and is accepted
    as such.

The marker or the manifest alone never passes: each is cross-checked against
dpkg status and the guest paths. The verifier cannot re-run apt/dpkg inside a
tar, so it re-proves the recorded post-install state instead of trusting the
marker. `validate_rootfs_archive()` keeps its
structural-only behaviour unless `require_toolset=True`, while `verify()` — the
production entry point — defaults to requiring the toolset (`MINIS_REQUIRE_TOOLSET`
may force it on; a bare base archive can never pass).
"""
import gzip
import hashlib
import json
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

# ── Preinstalled-toolset contract ───────────────────────────────────────────
# Paths and identity of the rootfs the CI toolset job produces
# (scripts/build_ubuntu_toolset.sh) and this build is allowed to ship.
TOOLSET_MANIFEST = 'usr/local/share/minis/toolset-manifest.json'
BOOTSTRAP_DONE_MARKER = 'var/lib/minis/bootstrap.done'
DPKG_STATUS = 'var/lib/dpkg/status'
TOOLSET_MANIFEST_SCHEMA = 1
TOOLSET_DISTRO = 'ubuntu24.04'
TOOLSET_ARCH = 'arm64'
# Pinned SHA-256 of the official ubuntu-base 24.04.5 arm64 archive, i.e. the
# provenance of the image the toolset was installed into.
OFFICIAL_BASE_SHA256 = 'a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2'

REQUIRED_TOOLSET_PACKAGES = (
    'bash', 'ca-certificates', 'curl', 'wget', 'git', 'openssh-client',
    'less', 'procps', 'iproute2', 'unzip', 'xz-utils', 'jq', 'ripgrep',
    'python3', 'python3-pip', 'python3-venv',
)

# Version probes recorded in the manifest. Each tuple is an alias group: the
# builder must record at least one name with non-empty output. It records
# bash/python3/git/curl/wget/ssh/rg/jq/pip/venv/less/ps/ip/unzip/xz and the
# ca-certificates store check; these are the agent-facing essentials.
REQUIRED_TOOLSET_COMMANDS = (
    ('bash',), ('python3',), ('git',), ('curl',), ('wget',), ('ssh',),
    ('rg',), ('jq',), ('pip', 'pip3'), ('venv',),
)

# Ordinary guest executables. Any of reg/sym/lnk counts; python3 is a symlink
# in Ubuntu and is never followed, extracted or executed. Mirrors the archive
# paths scripts/build_ubuntu_toolset.sh asserts and UbuntuToolsetStatus reads.
REQUIRED_TOOLSET_PATHS = (
    ('usr/bin/bash',), ('usr/bin/python3',), ('usr/bin/git',),
    ('usr/bin/curl',), ('usr/bin/wget',), ('usr/bin/ssh',), ('usr/bin/rg',),
    ('usr/bin/jq',), ('usr/bin/pip3', 'usr/bin/pip'), ('usr/bin/less',),
    ('usr/bin/ps',), ('usr/bin/ip', 'usr/sbin/ip', 'sbin/ip'), ('usr/bin/unzip',), ('usr/bin/xz',),
)
# python3-venv ships the venv module under usr/lib/python3.X/venv/.
TOOLSET_PATH_PATTERNS = (
    re.compile(r'^usr/lib/python3(?:\.\d+)?/venv/[^/]+$'),
)
EXECUTABLE_TYPES = ('reg', 'sym', 'lnk')
_TOOLSET_READ_LIMIT = 8 * 1024 * 1024
_TOOLSET_READS = frozenset((TOOLSET_MANIFEST, DPKG_STATUS))
_DPKG_VERSION_RE = re.compile(r'^[0-9][A-Za-z0-9.+~:-]*$')
TOOLSET_ENV = 'MINIS_REQUIRE_TOOLSET'
_TRUTHY = ('1', 'true', 'yes', 'on')
_FALSY = ('0', 'false', 'no', 'off')

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


class RootfsScan:
    """Facts gathered by one read-only pass over the packaged rootfs tar.

    Only the guest identity, the toolset manifest / marker / dpkg status bytes
    and the member table are read. Nothing is extracted to disk and no guest
    program is executed: python3 stays a symlink and is only recorded as one.
    """

    def __init__(self, payload):
        self.payload = payload
        self.guest_paths = set()
        self.os_release = b''
        self.os_release_seen = False
        self.types = {}       # normalized path -> dir|reg|sym|lnk|other
        self.duplicates = set()
        self.contents = {}    # toolset manifest / dpkg status -> bytes
        self.toolset = None   # summary from validate_preinstalled_toolset

    def kind(self, path):
        return self.types.get(path)


def _member_kind(member):
    if member.isdir():
        return 'dir'
    if member.issym():
        return 'sym'
    if member.islnk():
        return 'lnk'
    if member.isreg():
        return 'reg'
    return 'other'


def _scan_rootfs(z, name):
    scan = RootfsScan(name)
    gzipped = name.endswith('.gz')
    try:
        with z.open(name) as raw:
            stream = gzip.GzipFile(fileobj=raw, mode='rb') if gzipped else raw
            with tarfile.open(fileobj=stream, mode='r|') as tar:
                for member in tar:
                    path = _normalize(member.name)
                    if not path:
                        continue
                    key = path.rstrip('/') or path
                    kind = _member_kind(member)
                    if key in scan.types:
                        scan.duplicates.add(key)
                    else:
                        scan.types[key] = kind
                    if path in REQUIRED_GUEST_PATHS:
                        scan.guest_paths.add(path)
                    if path == 'usr/lib/os-release' and kind == 'reg':
                        if not scan.os_release_seen:
                            scan.os_release_seen = True
                            handle = tar.extractfile(member)
                            if handle is not None:
                                scan.os_release = handle.read(8192)
                    elif key in _TOOLSET_READS and kind == 'reg':
                        handle = tar.extractfile(member)
                        data = handle.read(_TOOLSET_READ_LIMIT + 1) if handle is not None else b''
                        assert len(data) <= _TOOLSET_READ_LIMIT, (
                            f'rootfs payload {name}: {key} is larger than '
                            f'{_TOOLSET_READ_LIMIT} bytes')
                        scan.contents.setdefault(key, data)
            if gzipped:
                # Drain the gzip stream so its CRC/length trailer is checked.
                while stream.read(1 << 20):
                    pass
    except (tarfile.TarError, OSError, EOFError, zlib.error) as exc:
        raise AssertionError(
            f'rootfs payload {name} is not a valid {"gzip/tar" if gzipped else "tar"} stream: {exc}') from exc
    return scan


def validate_rootfs_archive(z, name, min_bytes=None, require_toolset=False):
    """Prove `name` inside `z` is a genuine Ubuntu 24.04 guest rootfs tar.

    `require_toolset=True` additionally proves it is the *preinstalled toolset*
    image (manifest + marker + dpkg status + guest binaries), not bare
    ubuntu-base. The default stays structural-only so callers that check a
    hand-made archive keep working; `verify()` passes True in production.
    """
    assert name in ROOTFS_ASSETS, f'unexpected rootfs asset name: {name}'
    gzipped = name.endswith('.gz')
    info = z.getinfo(name)
    minimum = MIN_PAYLOAD_BYTES['.gz' if gzipped else '.tar'] if min_bytes is None else min_bytes
    assert info.file_size >= minimum, (
        f'rootfs payload {name} is truncated/suspiciously small: '
        f'{info.file_size} bytes < {minimum}')

    scan = _scan_rootfs(z, name)
    missing = REQUIRED_GUEST_PATHS - scan.guest_paths
    assert not missing, f'rootfs payload {name} is not an Ubuntu guest: missing {sorted(missing)}'
    assert OS_RELEASE.search(scan.os_release), f'rootfs payload {name}: /usr/lib/os-release is not Ubuntu'
    assert VERSION_ID.search(scan.os_release), (
        f'rootfs payload {name}: /usr/lib/os-release is not Ubuntu 24.04')
    if require_toolset:
        scan.toolset = validate_preinstalled_toolset(scan)
    return scan


# ── Preinstalled-toolset proof ──────────────────────────────────────────────

def _toolset_file(scan, path, why):
    """Bytes of a critical toolset file, failing closed on absence/duplicates."""
    kind = scan.kind(path)
    assert kind is not None, f'toolset: {path} missing from rootfs ({why})'
    assert kind == 'reg', f'toolset: {path} is a {kind} member, not a regular file ({why})'
    assert path not in scan.duplicates, f'toolset: {path} appears more than once in the archive'
    data = scan.contents.get(path)
    assert data is not None, f'toolset: {path} could not be read from the archive'
    return data


def _load_toolset_manifest(scan):
    raw = _toolset_file(
        scan, TOOLSET_MANIFEST,
        'a release APK must ship the preinstalled toolset image, not bare ubuntu-base')
    try:
        manifest = json.loads(raw.decode('utf-8'))
    except (UnicodeDecodeError, ValueError) as exc:
        raise AssertionError(
            f'toolset: {TOOLSET_MANIFEST} is not valid UTF-8 JSON: {exc}') from exc
    assert isinstance(manifest, dict), (
        f'toolset: {TOOLSET_MANIFEST} must contain a JSON object, got {type(manifest).__name__}')
    return manifest


def _parse_dpkg_status(raw):
    """Return ({package: {versions installed}}, {package: {statuses seen}})."""
    try:
        text = raw.decode('utf-8')
    except UnicodeDecodeError as exc:
        raise AssertionError(f'toolset: {DPKG_STATUS} is not valid UTF-8: {exc}') from exc
    stanzas = []
    current = {}
    last_field = None
    for lineno, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            if current:
                stanzas.append(current)
                current = {}
                last_field = None
            continue
        if line[0] in ' \t':
            assert last_field is not None, (
                f'toolset: {DPKG_STATUS} line {lineno} is a continuation without a field')
            current[last_field] += '\n' + line.strip()
            continue
        field, sep, value = line.partition(':')
        assert sep, f'toolset: {DPKG_STATUS} line {lineno} is not a "Field: value" line'
        last_field = field.strip()
        current[last_field] = value.strip()
    if current:
        stanzas.append(current)

    installed = {}
    statuses = {}
    for stanza in stanzas:
        package = stanza.get('Package', '').strip()
        if not package:
            continue
        base = package.split(':', 1)[0]
        status = stanza.get('Status', '').strip()
        statuses.setdefault(base, set()).add(status)
        if status == 'install ok installed':
            installed.setdefault(base, set()).add(stanza.get('Version', '').strip())
    return installed, statuses


def _validate_toolset_manifest(manifest):
    schema = manifest.get('schema')
    assert type(schema) is int and schema == TOOLSET_MANIFEST_SCHEMA, (
        f'toolset: manifest schema must be the integer {TOOLSET_MANIFEST_SCHEMA}, got {schema!r}')
    assert manifest.get('distro') == TOOLSET_DISTRO, (
        f"toolset: manifest distro {manifest.get('distro')!r} != {TOOLSET_DISTRO!r}")
    assert manifest.get('arch') == TOOLSET_ARCH, (
        f"toolset: manifest arch {manifest.get('arch')!r} != {TOOLSET_ARCH!r}")
    base_sha = manifest.get('official_base_sha256')
    assert isinstance(base_sha, str) and base_sha.strip().lower() == OFFICIAL_BASE_SHA256, (
        'toolset: manifest official_base_sha256 is not the pinned ubuntu-base '
        f'24.04.5 arm64 archive: {base_sha!r}')

    packages = manifest.get('packages')
    assert isinstance(packages, dict) and packages, (
        'toolset: manifest packages must be a non-empty {"name": "version"} object')
    for package, version in packages.items():
        assert isinstance(version, str) and _DPKG_VERSION_RE.match(version), (
            f'toolset: manifest version for {package!r} is not a concrete dpkg '
            f'version: {version!r}')
    missing = sorted(set(REQUIRED_TOOLSET_PACKAGES) - set(packages))
    assert not missing, f'toolset: manifest does not list required packages {missing}'

    commands = manifest.get('commands')
    assert isinstance(commands, dict) and commands, (
        'toolset: manifest commands must be a non-empty {"name": "version output"} object')
    for name, output in commands.items():
        assert isinstance(output, str) and output.strip(), (
            f'toolset: manifest records no version output for command {name!r}')
    missing_commands = [group[0] for group in REQUIRED_TOOLSET_COMMANDS
                        if not any(name in commands for name in group)]
    assert not missing_commands, (
        f'toolset: manifest lacks command version results for {missing_commands}')
    return packages, commands


def validate_preinstalled_toolset(scan):
    """Prove the tar is the CI preinstalled-toolset image, not bare ubuntu-base.

    The marker and the manifest are necessary but never sufficient: every
    required package must also be `install ok installed` in dpkg status with
    the manifest's exact version, and its guest executable must be present.
    """
    manifest = _load_toolset_manifest(scan)
    packages, commands = _validate_toolset_manifest(manifest)

    marker_kind = scan.kind(BOOTSTRAP_DONE_MARKER)
    assert marker_kind is not None, (
        'toolset: var/lib/minis/bootstrap.done missing — the guest would install '
        'packages on first use, which this build does not accept')
    assert marker_kind == 'reg', (
        f'toolset: {BOOTSTRAP_DONE_MARKER} is a {marker_kind} member, not the marker file')
    assert BOOTSTRAP_DONE_MARKER not in scan.duplicates, (
        f'toolset: {BOOTSTRAP_DONE_MARKER} appears more than once in the archive')

    status_raw = _toolset_file(
        scan, DPKG_STATUS, 'dpkg status is required to prove the packages are installed')
    installed, statuses = _parse_dpkg_status(status_raw)
    for package in REQUIRED_TOOLSET_PACKAGES:
        versions = installed.get(package)
        assert versions, (
            f'toolset: {package} is not "install ok installed" in {DPKG_STATUS} '
            f'(statuses seen: {sorted(statuses.get(package, ()))})')
        expected = packages[package]
        assert expected in versions, (
            f'toolset: {package} installed version(s) {sorted(versions)} != '
            f'manifest version {expected}')

    for group in REQUIRED_TOOLSET_PATHS:
        found = [path for path in group if scan.kind(path) in EXECUTABLE_TYPES]
        assert found, (
            'toolset: required guest executable missing: '
            f'{" or ".join(group)} (python3 is normally a symlink and is accepted as one)')
    for pattern in TOOLSET_PATH_PATTERNS:
        assert any(pattern.match(path) and kind in EXECUTABLE_TYPES
                   for path, kind in scan.types.items()), (
            f'toolset: no guest member matches {pattern.pattern} — python3-venv is not installed')

    return {
        'packages': len(REQUIRED_TOOLSET_PACKAGES),
        'commands': len(commands),
        'official_base_sha256': OFFICIAL_BASE_SHA256,
    }


def require_toolset_default():
    """Production default: the preinstalled toolset is mandatory.

    CI may force it on with `MINIS_REQUIRE_TOOLSET=1`. There is deliberately no
    off switch here: a bare ubuntu-base archive must never pass as a release
    APK. Structural-only inspection is available explicitly through
    `validate_rootfs_archive(..., require_toolset=False)`.
    """
    raw = os.environ.get(TOOLSET_ENV)
    if raw is None or not raw.strip():
        return True
    value = raw.strip().lower()
    if value in _TRUTHY:
        return True
    if value in _FALSY:
        raise AssertionError(
            f'{TOOLSET_ENV}={raw!r} would disable the preinstalled-toolset check; '
            'a release APK must ship the toolset, not bare ubuntu-base '
            '(use validate_rootfs_archive(require_toolset=False) for a '
            'structural-only inspection)')
    raise AssertionError(f'{TOOLSET_ENV}={raw!r} is not a boolean')


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


MAIN_ACTION = 'android.intent.action.MAIN'
LAUNCHER_CATEGORY = 'android.intent.category.LAUNCHER'


class ManifestNode:
    """One element from `aapt2 dump xmltree`, with compiled attribute values."""

    def __init__(self, tag, line):
        self.tag = tag
        self.line = line
        self.attrs = {}
        self.children = []

    def attr(self, name, default=None):
        return self.attrs.get(name, default)

    def bool_attr(self, name, default):
        value = self.attrs.get(name)
        if value is None:
            return default
        if value in ('true', 'false'):
            return value == 'true'
        # Older aapt2 prints compiled booleans as `(type 0x12)0xffffffff`/`0x0`.
        match = _TYPED_BOOL_RE.match(value)
        if match:
            return int(match.group(1), 16) != 0
        raise AssertionError(
            f'<{self.tag}> android:{name} is not a boolean: {value!r} (line {self.line})')

    def find_all(self, tag):
        return [child for child in self.children if child.tag == tag]

    def __repr__(self):
        return f'<{self.tag} line={self.line} {self.attrs}>'


_ATTR_NAME_RE = re.compile(r'^[A-Za-z0-9_]+$')
_TYPED_BOOL_RE = re.compile(r'^\(type 0x[0-9a-fA-F]+\)0x([0-9a-fA-F]+)$')
_TYPED_INT_RE = re.compile(r'^\(type 0x[0-9a-fA-F]+\)0x([0-9a-fA-F]+)$')
_ELEMENT_RE = re.compile(r'^(\s*)E: ([A-Za-z0-9_.-]+)(?: \(line=(\d+)\))?\s*$')


def _attr_value(raw):
    """`"x" (Raw: "x")`, `true`, `35` -> the compiled value."""
    value = raw.split(' (Raw:', 1)[0].strip()
    if len(value) >= 2 and value[0] == value[-1] == '"':
        value = value[1:-1]
    return value


def parse_xmltree(text):
    """Parse `aapt2 dump xmltree --file AndroidManifest.xml` into a tree.

    Each `A:` line belongs to the most recent `E:` element; element nesting is
    driven by indentation. Attribute names are reduced to their local name
    (`android:name(0x01010003)` -> `name`), which is what the checks use.
    """
    root = None
    stack = []
    for line in text.splitlines():
        match = _ELEMENT_RE.match(line)
        if match:
            indent = len(match.group(1))
            node = ManifestNode(match.group(2), int(match.group(3) or 0))
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if stack:
                stack[-1][1].children.append(node)
            elif root is None:
                root = node
            else:
                raise AssertionError(f'aapt2 xmltree has multiple roots (line {node.line})')
            stack.append((indent, node))
            continue
        stripped = line.strip()
        if stripped.startswith('A: '):
            if not stack:
                raise AssertionError(f'aapt2 xmltree attribute without element: {stripped}')
            body = stripped[3:]
            key, sep, value = body.partition('=')
            if not sep:
                raise AssertionError(f'unparsable aapt2 attribute: {stripped}')
            key = key.strip()
            if key.endswith(')') and '(' in key:
                key = key[:key.rindex('(')]
            name = key.rsplit(':', 1)[-1]
            if not _ATTR_NAME_RE.match(name):
                raise AssertionError(f'unparsable aapt2 attribute name: {stripped}')
            stack[-1][1].attrs[name] = _attr_value(value)
    assert root is not None, 'aapt2 xmltree is empty'
    assert root.tag == 'manifest', f'aapt2 xmltree root is <{root.tag}>, not <manifest>'
    return root


def _resolve_class(package, name):
    """Android manifest class-name rules: `.X`/`X` are relative to the package."""
    if name.startswith('.'):
        return package + name
    if '.' not in name:
        return package + '.' + name
    return name


def _int_value(value):
    match = _TYPED_INT_RE.match(value)
    return int(match.group(1), 16) if match else int(value)


def _target_sdk(manifest):
    target = manifest.attr('targetSdkVersion')
    if target is not None:
        return _int_value(target)
    uses_sdk = manifest.find_all('uses-sdk')
    if uses_sdk:
        target = uses_sdk[0].attr('targetSdkVersion')
        if target is not None:
            return _int_value(target)
    return 1


def _has_launcher_filter(component):
    for intent_filter in component.find_all('intent-filter'):
        actions = {a.attr('name') for a in intent_filter.find_all('action')}
        categories = {c.attr('name') for c in intent_filter.find_all('category')}
        # Both must belong to the SAME filter; text elsewhere proves nothing.
        if MAIN_ACTION in actions and LAUNCHER_CATEGORY in categories:
            return True
    return False


def _component_enabled(component):
    return component.bool_attr('enabled', True)


def _component_exported(component, target_sdk):
    if component.attr('exported') is not None:
        return component.bool_attr('exported', False)
    # Android default: true only when the component has an intent filter and
    # the app targets pre-31 (API 31 made android:exported mandatory).
    return bool(component.find_all('intent-filter')) and target_sdk < 31


def validate_launcher_manifest(xmltree, expected_package=None):
    """Return the launchable component declared by an aapt2 xmltree dump/tree.

    Enforces, on the *compiled* manifest rather than aapt badging text:
      * <application> is enabled (default true);
      * the launchable entry is an <activity> or <activity-alias>, enabled
        (default true) and exported;
      * one single <intent-filter> holds both MAIN and LAUNCHER;
      * an <activity-alias> target is a declared, enabled <activity>
        (default true), not another alias and not a missing name.
    """
    tree = parse_xmltree(xmltree) if isinstance(xmltree, str) else xmltree
    package = tree.attr('package')
    assert package, 'compiled manifest has no package name'
    if expected_package is not None:
        assert package == expected_package, (
            f'compiled manifest package {package!r} != expected {expected_package!r}')

    applications = tree.find_all('application')
    assert len(applications) == 1, f'expected exactly one <application>, found {len(applications)}'
    application = applications[0]
    assert application.bool_attr('enabled', True), '<application> is disabled'

    target_sdk = _target_sdk(tree)
    activities = {}
    for activity in application.find_all('activity'):
        name = activity.attr('name')
        assert name, f'<activity> without android:name (line {activity.line})'
        activities[_resolve_class(package, name)] = activity

    problems = []
    launchable = []
    for component in application.children:
        if component.tag not in ('activity', 'activity-alias'):
            continue
        name = component.attr('name')
        if not name:
            problems.append(f'<{component.tag}> without android:name (line {component.line})')
            continue
        full_name = _resolve_class(package, name)
        if not _has_launcher_filter(component):
            continue
        if not _component_enabled(component):
            problems.append(f'{full_name}: MAIN/LAUNCHER component is disabled')
            continue
        if not _component_exported(component, target_sdk):
            problems.append(
                f'{full_name}: MAIN/LAUNCHER component is not exported '
                f'(targetSdk {target_sdk})')
            continue
        if component.tag == 'activity-alias':
            target = component.attr('targetActivity')
            if not target:
                problems.append(f'{full_name}: activity-alias has no targetActivity')
                continue
            target_name = _resolve_class(package, target)
            target_activity = activities.get(target_name)
            if target_activity is None:
                problems.append(
                    f'{full_name}: targetActivity {target_name} is not a declared <activity>')
                continue
            if not _component_enabled(target_activity):
                problems.append(f'{full_name}: targetActivity {target_name} is disabled')
                continue
        launchable.append(full_name)

    assert launchable, (
        'no launchable activity/activity-alias in compiled manifest '
        '(need MAIN + LAUNCHER in one filter, enabled, exported, valid alias target); '
        + ('; '.join(problems) if problems else 'no component declares MAIN/LAUNCHER'))
    return launchable[0]


# Backwards-compatible alias for callers that pass an already-parsed tree.
verify_launcher_manifest = validate_launcher_manifest


def verify(apk: Path, source_rootfs=None, require_toolset=None):
    """Verify a release APK end to end.

    `require_toolset` defaults to `require_toolset_default()` (True;
    `MINIS_REQUIRE_TOOLSET=1` can only force it on), so a bare ubuntu-base
    archive can never pass as a production APK.
    """
    if require_toolset is None:
        require_toolset = require_toolset_default()
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None, f'corrupt ZIP: {apk}'
        names = set(z.namelist())
        missing = STATIC_REQUIRED - names
        assert not missing, f'missing payload: {missing}'
        rootfs = rootfs_asset(names)
        assert 'assets/alpine-minirootfs.tar.gz' not in names, 'old Alpine payload still bundled'
        assert not any(n.startswith('lib/') and n.split('/')[1] != 'arm64-v8a' for n in names), 'unexpected ABI'
        scan = validate_rootfs_archive(z, rootfs, require_toolset=require_toolset)
        source = (Path(source_rootfs) if source_rootfs is not None else
                  Path(os.environ.get('UBUNTU_ROOTFS_SOURCE') or DEFAULT_SOURCE_ROOTFS))
        if source.is_file():
            check_payload_matches_source(z, rootfs, source)
        elif os.environ.get('UBUNTU_ROOTFS_SOURCE'):
            raise AssertionError(f'UBUNTU_ROOTFS_SOURCE={source} does not exist')
        else:
            print(f'note: prepared source {source} absent; skipped byte-identity check',
                  file=sys.stderr)
    toolset = scan.toolset or {}
    toolset_note = (f"toolset={toolset['packages']} packages "
                    f"base={toolset['official_base_sha256'][:12]}" if scan.toolset
                    else 'toolset=NOT CHECKED (require_toolset=False)')
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    tools = sdk / 'build-tools' / '35.0.0'
    badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    package = 'com.openminis.app.ubuntu.next'
    assert f"package: name='{package}'" in badging, 'wrong installation package id'
    # aapt2 badging omits `launchable-activity` when the launcher entry is an
    # <activity-alias> (the dynamic-icon case), so the compiled manifest is the
    # authority: require a real enabled/exported activity or valid alias whose
    # single intent-filter carries MAIN + LAUNCHER.
    xmltree = subprocess.check_output(
        [str(tools / 'aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'],
        text=True)
    launcher = validate_launcher_manifest(xmltree, package)
    subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', str(apk)], check=True)
    subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    apk.with_suffix('.apk.sha256').write_text(f'{digest}  {apk.name}\n')
    print(f'PASS {apk} {apk.stat().st_size} bytes SHA256={digest}; '
          f'rootfs={rootfs}; {toolset_note}; launcher={launcher}; not installed or run')


if __name__ == '__main__':
    paths = sorted(Path(sys.argv[1]).rglob('*.apk'))
    if not paths:
        raise SystemExit('No APK found; build is not a success')
    for path in paths:
        verify(path)
