package com.openminis.app.sandbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * End-to-end acceptance of the **bundled** Ubuntu image with the production
 * [TarExtractor]: open the release APK, stream `assets/ubuntu-base-arm64.tar`
 * (or `.tar.gz`) straight from the APK into the extractor, and check the
 * resulting tree the way the terminal bootstrap needs it.
 *
 * Nothing is executed: no guest binary is started, no APK is installed, no
 * root/su is used. The guest is only read as bytes.
 *
 * ## Opt-in contract (keeps the default unit-test task green)
 *
 * `testGithubDebugUnitTest` without arguments runs this class in "not
 * requested" mode and skips it. The acceptance run must be explicit:
 *
 * ```
 * MINIS_BUNDLED_APK=/path/to/app-github-release.apk \
 * MINIS_BUNDLED_APK_REQUIRED=1 \
 *   ./gradlew :app:testGithubDebugUnitTest --tests '*UbuntuBundledImageCompatibilityTest'
 * ```
 *
 * With `MINIS_BUNDLED_APK_REQUIRED=1` a missing/unreadable APK is a **failure**,
 * never a skip. With `MINIS_BUNDLED_APK` set the test always runs.
 */
class UbuntuBundledImageCompatibilityTest {

    private var root: File? = null

    private val apkPath: String? =
        System.getenv("MINIS_BUNDLED_APK")?.trim()?.takeIf { it.isNotEmpty() }

    private val required: Boolean =
        System.getenv("MINIS_BUNDLED_APK_REQUIRED")?.trim() == "1"

    @Before
    fun requireApkWhenRequested() {
        if (apkPath != null) return
        if (required) {
            fail("MINIS_BUNDLED_APK_REQUIRED=1 but MINIS_BUNDLED_APK is not set")
        }
        Assume.assumeTrue(
            "MINIS_BUNDLED_APK not set: bundled-image acceptance not requested",
            false,
        )
    }

    @After
    fun cleanUp() {
        val temporaryRoot = root ?: return
        // An Ubuntu tree contains absolute guest symlinks. Never use Kotlin's
        // FileTreeWalk here: cleanup must unlink them, not traverse host paths.
        // walkFileTree has no FOLLOW_LINKS option, including for broken links.
        Files.walkFileTree(temporaryRoot.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    @Test
    fun bundledImageExtractsAndIsReadyForTheTerminal() {
        val apk = File(apkPath!!)
        assertTrue("APK not found: $apk", apk.isFile)
        assertTrue("APK looks truncated: ${apk.length()} bytes", apk.length() > 10L * 1024 * 1024)

        val dir = Files.createTempDirectory("minis-bundled-image-").toFile()
        root = dir

        var assetNameUsed = ""
        var gzippedUsed = false
        ZipFile(apk).use { zip ->
            // Mirror RootfsManager: prefer the .tar.gz name, fall back to the
            // plain .tar that AAPT may leave behind. The suffix alone selects
            // the gzip wrapper — no magic sniffing, so the test cannot hide a
            // mismatch between asset name and payload.
            val gzName = "assets/" + SandboxDistribution.ROOTFS_ASSET
            val tarName = "assets/" + SandboxDistribution.ROOTFS_ASSET_TAR
            val assetName = when {
                zip.getEntry(gzName) != null -> gzName
                zip.getEntry(tarName) != null -> tarName
                else -> throw AssertionError("APK has no $gzName or $tarName")
            }
            val gzipped = assetName.endsWith(".gz")
            assetNameUsed = assetName
            gzippedUsed = gzipped
            val entry = zip.getEntry(assetName)
            // Same thresholds as scripts/verify_ubuntu_apk.py MIN_PAYLOAD_BYTES.
            val minimum = if (gzipped) 16L * 1024 * 1024 else 32L * 1024 * 1024
            assertTrue(
                "rootfs payload $assetName is truncated: ${entry.size} < $minimum bytes",
                entry.size >= minimum,
            )

            zip.getInputStream(entry).use { raw ->
                val payload = if (gzipped) {
                    GZIPInputStream(raw, 1 shl 16)
                } else {
                    BufferedInputStream(raw, 1 shl 16)
                }
                payload.use {
                    TarExtractor.extract(it, dir) { message -> println("TarExtractor: $message") }
                }
            }
        }

        // ── root member + normal prefixed children ─────────────────────────
        assertTrue("extraction root missing", dir.isDirectory)
        assertTrue("etc/passwd missing after root entry", File(dir, "etc/passwd").isFile)
        assertTrue("usr directory missing", File(dir, "usr").isDirectory)

        // ── shell / loader the terminal bootstrap needs ────────────────────
        // RootfsManager's own post-extract sanity check.
        assertTrue("RootfsManager postcheck: /bin/sh missing", File(dir, "bin/sh").isFile)
        assertTrue(
            "RootfsManager postcheck: /lib/ld-linux-aarch64.so.1 missing",
            File(dir, "lib/ld-linux-aarch64.so.1").isFile,
        )
        assertFile(dir, "usr/bin/bash", minBytes = 1_000_000)
        assertFile(dir, "usr/bin/dash", minBytes = 50_000)
        assertFile(dir, "usr/bin/apt")
        assertFile(dir, "usr/bin/dpkg")

        assertEquals("bin must be the usrmerge symlink", "usr/bin", linkTarget(File(dir, "bin")))
        assertEquals("sbin must be the usrmerge symlink", "usr/sbin", linkTarget(File(dir, "sbin")))
        assertEquals("lib must be the usrmerge symlink", "usr/lib", linkTarget(File(dir, "lib")))
        assertTrue("bin/ does not resolve to usr/bin", File(dir, "bin/bash").isFile)

        val loader = File(dir, "usr/lib/ld-linux-aarch64.so.1")
        val loaderTarget = linkTarget(loader)
        assertNotNull("dynamic loader must be a symlink", loaderTarget)
        assertTrue("loader target must stay relative: $loaderTarget", !File(loaderTarget!!).isAbsolute)
        assertEquals("aarch64-linux-gnu/ld-linux-aarch64.so.1", loaderTarget)
        assertFile(dir, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1", minBytes = 100_000)
        assertTrue("loader does not resolve inside the rootfs", loader.isFile)

        // ── python (symlink + real interpreter) ────────────────────────────
        assertEquals("python3.12", linkTarget(File(dir, "usr/bin/python3")))
        assertFile(dir, "usr/bin/python3.12", minBytes = 1_000_000)
        assertTrue("stdlib dir missing", File(dir, "usr/lib/python3.12").isDirectory)
        assertTrue("libpython missing", File(dir, "usr/lib/aarch64-linux-gnu").isDirectory)
        assertTrue("python3 does not resolve", File(dir, "usr/bin/python3").isFile)

        // ── hard links materialised as copies ──────────────────────────────
        assertEquals(
            "perl hard link was not materialised",
            File(dir, "usr/bin/perl").length(),
            File(dir, "usr/bin/perl5.38.2").length(),
        )
        assertTrue(File(dir, "usr/bin/perl5.38.2").isFile)
        assertTrue(File(dir, "usr/bin/uncompress").isFile)
        assertEquals(
            File(dir, "usr/bin/gunzip").length(),
            File(dir, "usr/bin/uncompress").length(),
        )

        // ── preinstalled toolset manifest + completion marker ──────────────
        val manifest = File(dir, UbuntuToolsetStatus.MANIFEST)
        assertTrue("toolset manifest missing", manifest.isFile)
        val manifestText = manifest.readText()
        assertTrue("manifest has no distro identity", manifestText.contains("ubuntu24.04"))
        assertTrue("manifest has no arch", manifestText.contains("arm64"))
        assertTrue(
            "manifest does not pin the official base",
            manifestText.contains(UbuntuToolsetStatus.OFFICIAL_BASE_SHA256),
        )
        assertFile(dir, SandboxDistribution.BOOTSTRAP_DONE_MARKER)
        assertFile(dir, "var/lib/dpkg/status", minBytes = 100_000)
        for (command in UbuntuToolsetStatus.requiredCommands) {
            assertTrue("required command missing: usr/bin/$command", File(dir, "usr/bin/$command").isFile)
        }

        val problem = UbuntuToolsetStatus.problem(dir)
        assertTrue("UbuntuToolsetStatus.isReady=false: $problem", problem == null)

        // ── no archive-root or dot entries materialised as files ───────────
        assertTrue(!File(dir, "./").isFile)
        println(
            "BUNDLED_IMAGE_OK asset=$assetNameUsed gzip=$gzippedUsed " +
                "manifest=${manifest.length()}B distro=ubuntu24.04 arch=arm64 " +
                "baseSha=${UbuntuToolsetStatus.OFFICIAL_BASE_SHA256} " +
                "bash=${File(dir, "usr/bin/bash").length()}B " +
                "loader=${File(dir, "usr/lib/ld-linux-aarch64.so.1").length()}B " +
                "toolsetReady=true",
        )
        assertTrue(!File(dir, ".").isFile)
    }

    private fun assertFile(root: File, rel: String, minBytes: Long = 1) {
        val file = File(root, rel)
        assertTrue("missing file: $rel", file.isFile)
        assertTrue("$rel is too small (${file.length()} bytes)", file.length() >= minBytes)
    }

    private fun linkTarget(file: File): String? =
        if (Files.isSymbolicLink(file.toPath())) {
            Files.readSymbolicLink(file.toPath()).toString()
        } else {
            null
        }
}
