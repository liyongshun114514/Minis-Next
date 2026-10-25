package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM tests for the Android guest distribution contract (Ubuntu 24.04 arm64).
 *
 * These run without an Android runtime: [SandboxDistribution] is pure Kotlin.
 * They are the executable form of the rules documented in
 * docs/android-ubuntu.md (asset name, markers, apt schema, path safety).
 */
class SandboxDistributionTest {

    // ── identity / asset ────────────────────────────────────────────────────

    @Test
    fun `rootfs asset is the ubuntu base tarball`() {
        assertEquals("ubuntu-base-arm64.tar.gz", SandboxDistribution.ROOTFS_ASSET)
        assertEquals("ubuntu-base-arm64.tar", SandboxDistribution.ROOTFS_ASSET_TAR)
        assertFalse(SandboxDistribution.ROOTFS_ASSET.contains("alpine"))
        assertEquals("alpine-minirootfs.tar.gz", SandboxDistribution.LEGACY_ASSET)
    }

    @Test
    fun `distro marker is versioned`() {
        assertEquals("ubuntu-24.04-arm64", SandboxDistribution.MARKER_VALUE)
        assertEquals("noble", SandboxDistribution.CODENAME)
        assertEquals("aarch64", SandboxDistribution.ARCH)
    }

    @Test
    fun `rootfs directory keeps the historical path`() {
        // Five unmodified readers hardcode filesDir/alpine-rootfs; the marker,
        // not the name, identifies the guest.
        assertEquals("alpine-rootfs", SandboxDistribution.ROOTFS_DIR_NAME)
    }

    // ── rootfs detection ────────────────────────────────────────────────────

    private fun tempRootfs(): File =
        File(System.getProperty("java.io.tmpdir"), "distro-test-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `ubuntu markers are required for isUbuntuRootfs`() {
        val dir = tempRootfs()
        try {
            assertFalse(SandboxDistribution.isUbuntuRootfs(dir))

            File(dir, "usr/bin").mkdirs()
            File(dir, "usr/bin/apt").writeText("")
            File(dir, "var/lib/dpkg").mkdirs()
            File(dir, "var/lib/dpkg/status").writeText("")
            File(dir, "etc").mkdirs()
            File(dir, "etc/os-release").writeText("ID=ubuntu\nVERSION_ID=\"24.04\"\n")

            // No markers yet.
            assertFalse(SandboxDistribution.isUbuntuRootfs(dir))

            File(dir, SandboxDistribution.ARCH_MARKER_FILE).writeText("aarch64")
            File(dir, SandboxDistribution.MARKER_FILE).writeText(SandboxDistribution.MARKER_VALUE)
            assertTrue(SandboxDistribution.isUbuntuRootfs(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `legacy alpine tree is detected and never treated as installed`() {
        val dir = tempRootfs()
        try {
            File(dir, "etc").mkdirs()
            File(dir, "etc/alpine-release").writeText("3.21.3\n")
            File(dir, "etc/apk").mkdirs()
            File(dir, "etc/apk/world").writeText("busybox\n")
            File(dir, SandboxDistribution.ARCH_MARKER_FILE).writeText("aarch64")

            assertFalse(SandboxDistribution.isUbuntuRootfs(dir))
            assertTrue(SandboxDistribution.isLegacyNonUbuntuRootfs(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `empty directory is not a legacy rootfs`() {
        val dir = tempRootfs()
        try {
            assertFalse(SandboxDistribution.isLegacyNonUbuntuRootfs(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── archive path sanitising ─────────────────────────────────────────────

    @Test
    fun `sanitize accepts normal and redundant paths`() {
        assertEquals("etc/passwd", SandboxDistribution.sanitizeArchivePath("etc/passwd"))
        assertEquals("etc/passwd", SandboxDistribution.sanitizeArchivePath("./etc/passwd"))
        assertEquals("a/b", SandboxDistribution.sanitizeArchivePath("a//b"))
        assertEquals("a/b", SandboxDistribution.sanitizeArchivePath("a/./b"))
        assertEquals("a/b", SandboxDistribution.sanitizeArchivePath("a/b/"))
        assertEquals("usr/bin/bash", SandboxDistribution.sanitizeArchivePath("usr/bin/bash"))
    }

    @Test
    fun `sanitize rejects traversal and absolute paths`() {
        val rejected = listOf(
            "",
            ".",
            "./",
            "/",
            "/etc/passwd",
            "../etc/passwd",
            "a/../../etc/passwd",
            "a/..",
            "..",
            "a/b/../../../c",
            "etc/passwd\u0000.png",
        )
        for (raw in rejected) {
            assertNull("must reject '$raw'", SandboxDistribution.sanitizeArchivePath(raw))
        }
    }

    @Test
    fun `sanitize keeps deep but legal paths`() {
        val deep = "usr/lib/aarch64-linux-gnu/perl-base/" + "a".repeat(120)
        assertEquals(deep, SandboxDistribution.sanitizeArchivePath(deep))
    }

    // ── apt sources (deb822) ────────────────────────────────────────────────

    @Test
    fun `apt sources are deb822 with archive and security suites`() {
        val text = SandboxDistribution.aptSources("https://ports.ubuntu.com/ubuntu-ports")
        assertTrue(text.contains("Types: deb"))
        assertTrue(text.contains("URIs: https://ports.ubuntu.com/ubuntu-ports/"))
        assertTrue(text.contains("Suites: noble noble-updates noble-backports"))
        assertTrue(text.contains("Suites: noble-security"))
        assertTrue(text.contains("Components: main restricted universe multiverse"))
        assertTrue(text.contains("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"))
        // deb822 files must not carry one-line `deb ...` entries.
        assertFalse(text.lineSequence().any { it.startsWith("deb ") })
        assertEquals(2, text.split("Types: deb").size - 1)
    }

    @Test
    fun `apt sources follow the selected mirror and normalise the slash`() {
        val a = SandboxDistribution.aptSources("https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/")
        val b = SandboxDistribution.aptSources("https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports")
        assertEquals(a, b)
        assertTrue(a.contains("URIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/"))
    }

    @Test
    fun `apt sources reject injection and fall back to the official archive`() {
        assertNull(SandboxDistribution.normalizeMirrorBase("https://mirror.example/ubuntu-ports\nSuites: evil"))
        assertNull(SandboxDistribution.normalizeMirrorBase("ftp://mirror.example/ubuntu"))
        assertNull(SandboxDistribution.normalizeMirrorBase("https://user@mirror.example/ubuntu"))
        assertNull(SandboxDistribution.normalizeMirrorBase("https://mirror.example/ubuntu?x=1"))
        assertNull(SandboxDistribution.normalizeMirrorBase(""))

        val text = SandboxDistribution.aptSources("javascript:alert(1)")
        assertTrue(text.contains("URIs: " + SandboxDistribution.OFFICIAL_UBUNTU_PORTS))
        assertFalse(text.contains("javascript"))
    }

    // ── apt under PRoot ─────────────────────────────────────────────────────

    @Test
    fun `apt conf disables the _apt drop and seccomp for PRoot`() {
        val text = SandboxDistribution.aptProotConf()
        assertTrue(text.contains("APT::Sandbox::User \"root\";"))
        assertTrue(text.contains("APT::Sandbox::Seccomp \"false\";"))
    }

    // ── guest defaults / bootstrap ──────────────────────────────────────────

    @Test
    fun `hosts file is non-empty and names localhost`() {
        // ubuntu-base ships /etc/hosts as a 0-byte file.
        val text = SandboxDistribution.hostsFile()
        assertTrue(text.contains("127.0.0.1\tlocalhost"))
        assertTrue(text.contains("::1"))
        assertTrue(text.contains(SandboxDistribution.HOSTNAME))
    }

    @Test
    fun `bootstrap covers the tools ubuntu-base lacks`() {
        val packages = SandboxDistribution.BOOTSTRAP_PACKAGES
        // Verified absent from ubuntu-base 24.04.5 arm64.
        for (required in listOf("python3", "curl", "wget", "git", "ca-certificates", "less")) {
            assertTrue("missing $required", packages.contains(required))
        }
        // Must stay apt-only: this list is consumed by apt-get.
        assertFalse(packages.any { it.startsWith("apk") })
    }

    @Test
    fun `bootstrap script is idempotent and uses apt-get`() {
        val text = SandboxDistribution.bootstrapScript()
        assertTrue(text.startsWith("#!/bin/sh\n"))
        assertTrue(text.contains("/var/lib/minis/bootstrap.done"))
        assertTrue(text.contains("apt-get update"))
        assertTrue(text.contains("apt-get install -y --no-install-recommends"))
        assertTrue(text.contains("DEBIAN_FRONTEND=noninteractive"))
        // Locking: two shells must not run apt concurrently.
        assertTrue(text.contains("/var/lib/minis/bootstrap.lock"))
        assertTrue(text.contains("mkdir "))
        // Explicit failure, never silent: log + failed marker + retry hint.
        assertTrue(text.contains("/var/lib/minis/bootstrap.failed"))
        assertTrue(text.contains("/var/log/minis-bootstrap.log"))
        assertTrue(text.contains("minis-bootstrap: FAILED"))
    }

    @Test
    fun `bootstrap profile hook reports progress and failure`() {
        val text = SandboxDistribution.bootstrapProfileScript()
        assertTrue(text.contains("minis-bootstrap"))
        assertTrue(text.contains("/var/lib/minis/bootstrap.failed"))
        assertTrue(text.contains("FAILED"))
        assertTrue(text.contains("minis-bootstrap --force"))
        assertTrue(text.contains("installing default tools"))
        // Only interactive shells: never block a non-interactive `sh -c`.
        assertTrue(text.contains("case \"$-\" in"))
    }

    @Test
    fun `profile script exports the documented PATH`() {
        val text = SandboxDistribution.profileScript()
        assertTrue(text.contains("/opt/bin"))
        assertTrue(text.contains("C.UTF-8"))
        assertTrue(text.contains("HISTFILE"))
    }
}
