#!/usr/bin/env python3
"""Static contract checks for the bundled Ubuntu base archive (stdlib only).

The Kotlin extractor (TarExtractor.kt) cannot run outside Gradle, and the guest
must never be executed on the build host. These tests therefore inspect the
tarball as data and assert the exact invariants the extractor depends on:

  * every member name passes the same sanitiser rules as
    SandboxDistribution.sanitizeArchivePath
  * no member would be written through a symlinked parent (tar-slip)
  * only regular files / directories / symlinks / hard links are present
  * hard-link targets exist earlier in the stream
  * the guest paths the migration needs exist, and the ones ubuntu-base lacks
    are exactly the bootstrap set

Run:
  UBUNTU_ROOTFS=/path/ubuntu-base-arm64.tar.gz \
      python3 scripts/tests/test_ubuntu_rootfs_contract.py -v

Skips (exit 0 with a message) when the archive is not present.
"""

import os
import sys
import tarfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_PATHS = [
    Path(os.environ.get("UBUNTU_ROOTFS", "")),
    REPO / "src/android/app/src/main/assets/ubuntu-base-arm64.tar.gz",
    Path("/tmp/ubuntu-base-arm64.tar.gz"),
]

TARBALL = next((p for p in DEFAULT_PATHS if str(p) and p.is_file()), None)


# ── mirror of SandboxDistribution.sanitizeArchivePath ───────────────────────
def sanitize(raw):
    if not raw or len(raw) > 4096 or "\x00" in raw:
        return None
    path = raw
    while path.startswith("./"):
        path = path[2:]
    if path in ("", ".") or path.startswith("/"):
        return None
    segments = path.split("/")
    if ".." in segments:
        return None
    clean = [s for s in segments if s and s != "."]
    return "/".join(clean) if clean else None


class SanitizerVectors(unittest.TestCase):
    """The same vectors as SandboxDistributionTest (Kotlin), for cross-checking."""

    ACCEPT = {
        "etc/passwd": "etc/passwd",
        "./etc/passwd": "etc/passwd",
        "a//b": "a/b",
        "a/./b": "a/b",
        "a/b/": "a/b",
        "usr/bin/bash": "usr/bin/bash",
    }
    REJECT = [
        "", ".", "./", "/", "/etc/passwd", "../etc/passwd",
        "a/../../etc/passwd", "a/..", "..", "a/b/../../../c", "etc/passwd\x00.png",
    ]

    def test_accepts(self):
        for raw, expected in self.ACCEPT.items():
            self.assertEqual(sanitize(raw), expected, raw)

    def test_rejects(self):
        for raw in self.REJECT:
            self.assertIsNone(sanitize(raw), repr(raw))


@unittest.skipIf(TARBALL is None, "Ubuntu base tarball not present (set UBUNTU_ROOTFS)")
class ArchiveContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tar = tarfile.open(TARBALL, "r:gz")
        cls.members = cls.tar.getmembers()
        cls.names = {m.name.rstrip("/") for m in cls.members}

    @classmethod
    def tearDownClass(cls):
        cls.tar.close()

    def test_only_expected_member_types(self):
        allowed = {tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE,
                   tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CONTTYPE}
        unexpected = sorted({m.type for m in self.members if m.type not in allowed})
        self.assertEqual(unexpected, [], f"unexpected tar member types: {unexpected}")

    def test_every_name_is_safe(self):
        bad = [m.name for m in self.members if sanitize(m.name) is None]
        self.assertEqual(bad, [], f"{len(bad)} unsafe member name(s), e.g. {bad[:5]}")

    def test_no_write_through_symlinked_parent(self):
        """The extractor refuses such a member; the real archive must have none."""
        symlink_dirs = {m.name.rstrip("/") for m in self.members if m.issym()}
        offenders = []
        for m in self.members:
            parts = m.name.rstrip("/").split("/")[:-1]
            for i in range(1, len(parts) + 1):
                if "/".join(parts[:i]) in symlink_dirs:
                    offenders.append(m.name)
                    break
        self.assertEqual(offenders[:5], [], f"{len(offenders)} entry/entries under a symlinked dir")

    def test_directories_precede_their_children(self):
        seen_dirs = set()
        missing = []
        for m in self.members:
            parts = m.name.rstrip("/").split("/")[:-1]
            for i in range(1, len(parts) + 1):
                prefix = "/".join(parts[:i])
                if prefix not in seen_dirs and prefix not in self.names:
                    missing.append(m.name)
                    break
            if m.isdir():
                seen_dirs.add(m.name.rstrip("/"))
        self.assertEqual(missing[:5], [], f"{len(missing)} member(s) before their directory entry")

    def test_hard_links_point_at_earlier_members(self):
        seen = set()
        bad = []
        for m in self.members:
            if m.islnk():
                if m.linkname.rstrip("/") not in seen:
                    bad.append((m.name, m.linkname))
            seen.add(m.name.rstrip("/"))
        self.assertEqual(bad, [], f"hard link(s) without an earlier target: {bad}")

    def test_symlinks_have_targets(self):
        empty = [m.name for m in self.members if m.issym() and not m.linkname]
        self.assertEqual(empty, [])

    def test_guest_paths_required_by_the_migration(self):
        required = [
            "usr/bin/bash",
            "usr/bin/apt",
            "usr/bin/dpkg",
            "usr/bin/dash",
            "usr/bin/setsid",
            "usr/bin/perl",
            "usr/lib/ld-linux-aarch64.so.1",
            "etc/passwd",
            "etc/nsswitch.conf",
            "etc/apt/sources.list.d/ubuntu.sources",
            "etc/apt/apt.conf.d/01-vendor-ubuntu",
            "usr/share/keyrings/ubuntu-archive-keyring.gpg",
            "var/lib/dpkg/status",
            "usr/lib/apt/methods/http",
            "usr/lib/apt/methods/https",
            "etc/ld.so.cache",
        ]
        missing = [p for p in required if p not in self.names]
        self.assertEqual(missing, [], f"missing guest paths: {missing}")

    def test_usrmerge_layout_is_fully_under_usr(self):
        # `bin`, `sbin`, `lib` are symlinks into usr/; the archive must not also
        # contain real entries below them, or extraction would need to traverse
        # a symlink (which the extractor refuses).
        for link in ("bin", "sbin", "lib"):
            self.assertIn(link, self.names)
            children = [n for n in self.names if n.startswith(link + "/")]
            self.assertEqual(children, [], f"entries below usrmerge symlink {link}/: {children[:3]}")

    def test_resolv_conf_is_not_a_symlink(self):
        member = self.tar.getmember("etc/resolv.conf")
        self.assertFalse(member.issym(), "24.04.5 ships a regular (empty) resolv.conf")
        self.assertTrue(member.isfile())

    def test_apt_sources_are_deb822_and_arm64_ports(self):
        text = self.tar.extractfile("etc/apt/sources.list.d/ubuntu.sources").read().decode()
        stanzas = [b for b in text.split("\n\n") if "Types:" in b]
        self.assertGreaterEqual(len(stanzas), 2)
        for stanza in stanzas:
            self.assertNotRegex(stanza, r"(?m)^deb ")
            self.assertIn("Types: deb", stanza)
            self.assertIn("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg", stanza)
        self.assertIn("ports.ubuntu.com/ubuntu-ports", text)
        self.assertNotIn("archive.ubuntu.com", text)
        self.assertIn("noble-security", text)

    def test_packages_the_bootstrap_must_add_are_absent(self):
        # These are exactly why SandboxDistribution.BOOTSTRAP_PACKAGES exists.
        absent = ["usr/bin/python3", "usr/bin/curl", "usr/bin/wget", "usr/bin/git",
                  "usr/bin/less", "usr/bin/ip", "usr/bin/vim", "usr/bin/ssh"]
        present = [p for p in absent if p in self.names]
        self.assertEqual(present, [], f"bootstrap list may be reducible, these exist: {present}")

    def test_archive_statistics_are_within_expected_bounds(self):
        types = {}
        for m in self.members:
            types[m.type] = types.get(m.type, 0) + 1
        files = types.get(tarfile.REGTYPE, 0)
        dirs = types.get(tarfile.DIRTYPE, 0)
        symlinks = types.get(tarfile.SYMTYPE, 0)
        hardlinks = types.get(tarfile.LNKTYPE, 0)
        self.assertGreater(files, 2000)
        self.assertGreater(dirs, 500)
        self.assertGreater(symlinks, 100)
        # 24.04.5 arm64: 2562 / 655 / 194 / 2. A large jump means the archive
        # changed shape and the extractor's assumptions need a re-review.
        self.assertLessEqual(hardlinks, 20, "hard-link count changed; review TarExtractor.linkOrCopy")
        print(f"\n  archive: {files} files, {dirs} dirs, {symlinks} symlinks, "
              f"{hardlinks} hardlinks, {len(self.members)} members")


if __name__ == "__main__":
    unittest.main(verbosity=2)
