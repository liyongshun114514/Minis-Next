package com.openminis.app.sandbox

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual manager construction, not a replacement installer or a mocked migration. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RootfsDirectoryMigrationIntegrationTest {
    private fun fixture(block: (File, Context) -> Unit) {
        val dir = Files.createTempDirectory("next-ubuntu-path-integration-").toFile()
        val app = RuntimeEnvironment.getApplication()
        val appInfo = android.content.pm.ApplicationInfo(app.applicationInfo).apply {
            nativeLibraryDir = File(dir, "lib").absolutePath
        }
        val context = object : ContextWrapper(app) {
            override fun getApplicationInfo(): android.content.pm.ApplicationInfo = appInfo
            override fun getFilesDir(): File = dir
            override fun getCacheDir(): File = File(dir, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        try { block(dir, context) } finally { GuestTreeFiles.deleteTree(dir) }
    }

    private fun manager(context: Context): RootfsManager =
        RootfsManager::class.java.getDeclaredConstructor(Context::class.java).run {
            isAccessible = true
            newInstance(context)
        }

    private fun readyUbuntu(root: File) {
        fun write(path: String, value: String) {
            File(root, path).apply { parentFile.mkdirs(); writeText(value) }
        }
        write(SandboxDistribution.ARCH_MARKER_FILE, SandboxDistribution.ARCH)
        write(SandboxDistribution.MARKER_FILE, SandboxDistribution.MARKER_VALUE)
        val packages = SandboxDistribution.BOOTSTRAP_PACKAGES
        val entries = packages.joinToString(",") { "\"$it\":\"1.0\"" }
        write(UbuntuToolsetStatus.MANIFEST,
            """{"schema":1,"distro":"ubuntu24.04","arch":"arm64","official_base_sha256":"${UbuntuToolsetStatus.OFFICIAL_BASE_SHA256}","packages":{$entries},"commands":{"python3":"Python 3.12"}}""")
        write(SandboxDistribution.BOOTSTRAP_DONE_MARKER, "verified synthetic fixture\n")
        write("var/lib/dpkg/status", packages.joinToString("\n\n", postfix = "\n") {
            "Package: $it\nStatus: install ok installed\nVersion: 1.0\n"
        })
        UbuntuToolsetStatus.requiredCommands.forEach { write("usr/bin/$it", "isolated fixture\n") }
    }

    @Test fun existingUbuntuIsMovedBeforeInstalledStateIsReadWithoutUnpacking() = fixture { dir, context ->
        val old = File(dir, "alpine-rootfs")
        readyUbuntu(old)
        val data = File(old, "var/minis/workspace/keep.txt").apply {
            parentFile.mkdirs(); writeText("user content must survive\n")
        }
        File(old, "root/.custom-setting").apply { parentFile.mkdirs(); writeText("retained\n") }
        val originalTime = data.lastModified()
        val m = manager(context)
        assertEquals(File(dir, "Ubuntu-rootfs"), m.rootfsDir)
        assertTrue("Existing verified Ubuntu is installed, not a fresh-extract candidate", m.isInstalled)
        assertTrue(m.installState.value is RootfsInstallState.Installed)
        assertTrue(Files.isSymbolicLink(old.toPath()))
        assertEquals("Ubuntu-rootfs", Files.readSymbolicLink(old.toPath()).toString())
        assertEquals("user content must survive\n", File(m.rootfsDir, "var/minis/workspace/keep.txt").readText())
        assertEquals(originalTime, File(m.rootfsDir, "var/minis/workspace/keep.txt").lastModified())
        assertEquals("retained\n", File(m.rootfsDir, "root/.custom-setting").readText())
        // No bundled asset access is needed when the existing installation is ready.
        runBlocking { m.installIfNeeded() }
        assertTrue(m.isInstalled)
    }

    @Test fun oldAndNewNonemptyTreesStayIntactAndInstallCannotOverwriteEither() = fixture { dir, context ->
        val old = File(dir, "alpine-rootfs")
        val current = File(dir, "Ubuntu-rootfs")
        readyUbuntu(old)
        readyUbuntu(current)
        File(old, "old-only.txt").writeText("old tree")
        File(current, "new-only.txt").writeText("new tree")
        val m = manager(context)
        assertFalse(m.isInstalled)
        assertTrue(m.installState.value is RootfsInstallState.Failed)
        val result = runCatching { runBlocking { m.installIfNeeded() } }
        assertTrue("Conflicting directories must stop installation explicitly", result.isFailure)
        assertEquals("old tree", File(old, "old-only.txt").readText())
        assertEquals("new tree", File(current, "new-only.txt").readText())
        assertTrue(runCatching { runBlocking { m.reset() } }.isFailure)
        assertTrue(runCatching { runBlocking { m.applyDefaultMountOverlay() } }.isFailure)
        m.refreshDns()
        assertFalse(File(current, "etc/resolv.conf").exists())
        assertEquals("old tree", File(old, "old-only.txt").readText())
        assertEquals("new tree", File(current, "new-only.txt").readText())
    }

    @Test fun oldPreservedBackupsRemainVisible() = fixture { dir, context ->
        val oldBackup = File(dir, "alpine-rootfs.pre-ubuntu-20261028-000000").apply { mkdirs() }
        val newBackup = File(dir, "Ubuntu-rootfs.previous-20261028-000001").apply { mkdirs() }
        val m = manager(context)
        assertTrue(m.legacyBackupDirs.contains(oldBackup))
        assertTrue(m.legacyBackupDirs.contains(newBackup))
    }
}
