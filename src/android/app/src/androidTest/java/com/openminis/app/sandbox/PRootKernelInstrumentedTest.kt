package com.openminis.app.sandbox

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device tests for PRootKernel.
 * Tests boot, command building, bind mount management, and path resolution.
 *
 * Run on real device: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PRootKernelInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // Reset PRootKernel state
        resetKernel()
    }

    @After
    fun tearDown() {
        PRootKernel.clearBindMounts()
        PRootKernel.customEnvironment.clear()
        resetKernel()
    }

    // ==================== boot ====================

    @Test
    fun notBootedInitially() {
        assertFalse(PRootKernel.isBooted)
    }

    @Test
    fun bootSetsIsBootedTrue() = runBlocking {
        org.junit.Assume.assumeTrue(
            "Ubuntu rootfs asset and the extracted proot binary are required",
            canBoot(),
        )

        PRootKernel.boot(context)
        assertTrue(PRootKernel.isBooted)
    }

    @Test
    fun bootIsIdempotent() = runBlocking {
        org.junit.Assume.assumeTrue(
            "Ubuntu rootfs asset and the extracted proot binary are required",
            canBoot(),
        )

        PRootKernel.boot(context)
        PRootKernel.boot(context) // Should not throw
        assertTrue(PRootKernel.isBooted)
    }

    // ==================== bind mounts ====================

    @Test
    fun addBindMountStoresMapping() {
        PRootKernel.addBindMount("/var/minis/workspace", "/data/host/workspace")
        assertEquals("/data/host/workspace", PRootKernel.bindMounts["/var/minis/workspace"])
    }

    @Test
    fun removeBindMountRemovesMapping() {
        PRootKernel.addBindMount("/var/minis/workspace", "/data/host/workspace")
        PRootKernel.removeBindMount("/var/minis/workspace")
        assertNull(PRootKernel.bindMounts["/var/minis/workspace"])
    }

    @Test
    fun clearBindMountsRemovesAll() {
        PRootKernel.addBindMount("/a", "/host/a")
        PRootKernel.addBindMount("/b", "/host/b")
        PRootKernel.clearBindMounts()
        assertTrue(PRootKernel.bindMounts.isEmpty())
    }

    @Test
    fun bindMountOverwritesPreviousValue() {
        PRootKernel.addBindMount("/mnt", "/host/old")
        PRootKernel.addBindMount("/mnt", "/host/new")
        assertEquals("/host/new", PRootKernel.bindMounts["/mnt"])
        assertEquals(1, PRootKernel.bindMounts.size)
    }

    // ==================== buildProotCommand ====================

    @Test
    fun buildProotCommandIncludesBasicFlags() = runBlocking {
        org.junit.Assume.assumeTrue(
            "Ubuntu rootfs asset and the extracted proot binary are required",
            canBoot(),
        )
        PRootKernel.boot(context)

        val cmd = PRootKernel.buildProotCommand("echo hello")

        // Should contain proot binary path
        assertEquals(
            "Should start with the extracted PRoot binary",
            RootfsManager.getInstance(context).prootBinary.absolutePath,
            cmd[0],
        )

        // Should have -0 flag (fake root)
        assertTrue("Should contain -0 flag", cmd.contains("-0"))

        // Should have -r flag with rootfs
        val rIndex = cmd.indexOf("-r")
        assertTrue("Should contain -r flag", rIndex >= 0)
        assertTrue("Rootfs path should follow -r", cmd[rIndex + 1].contains(SandboxDistribution.ROOTFS_DIR_NAME))

        // Should bind /dev, /proc, /sys
        assertTrue("Should bind /dev", cmd.contains("/dev"))
        assertTrue("Should bind /proc", cmd.contains("/proc"))
        assertTrue("Should bind /sys", cmd.contains("/sys"))

        // Should have -w /root
        val wIndex = cmd.indexOf("-w")
        assertTrue("Should contain -w flag", wIndex >= 0)
        assertEquals("/root", cmd[wIndex + 1])

        // Should end with /bin/sh -c "echo hello"
        val lastThree = cmd.takeLast(3)
        assertEquals("/bin/sh", lastThree[0])
        assertEquals("-c", lastThree[1])
        assertEquals("echo hello", lastThree[2])
    }

    @Test
    fun buildProotCommandIncludesBindMounts() = runBlocking {
        org.junit.Assume.assumeTrue(
            "Ubuntu rootfs asset and the extracted proot binary are required",
            canBoot(),
        )
        PRootKernel.boot(context)

        PRootKernel.addBindMount("/var/minis/workspace", "/data/user/0/com.openminis.app/workspace")

        val cmd = PRootKernel.buildProotCommand("ls /var/minis/workspace")

        // Should contain -b host:linux format
        val bindStr = "/data/user/0/com.openminis.app/workspace:/var/minis/workspace"
        assertTrue("Should contain bind mount arg", cmd.contains(bindStr))
    }

    @Test
    fun buildProotCommandWithMultipleBindMounts() = runBlocking {
        org.junit.Assume.assumeTrue(
            "Ubuntu rootfs asset and the extracted proot binary are required",
            canBoot(),
        )
        PRootKernel.boot(context)

        PRootKernel.addBindMount("/mnt/a", "/host/a")
        PRootKernel.addBindMount("/mnt/b", "/host/b")

        val cmd = PRootKernel.buildProotCommand("ls")

        assertTrue(cmd.contains("/host/a:/mnt/a"))
        assertTrue(cmd.contains("/host/b:/mnt/b"))
    }

    @Test(expected = IllegalStateException::class)
    fun buildProotCommandThrowsWhenNotBooted() {
        PRootKernel.buildProotCommand("echo test")
    }

    // ==================== resolveHostPath ====================

    @Test
    fun resolveHostPathMatchesExactMount() {
        PRootKernel.addBindMount("/var/minis/workspace", "/host/workspace")

        val result = PRootKernel.resolveHostPath("/var/minis/workspace")
        assertNotNull(result)
        assertEquals("/host/workspace", result!!.path)
    }

    @Test
    fun resolveHostPathMatchesSubpath() {
        PRootKernel.addBindMount("/var/minis/workspace", "/host/workspace")

        val result = PRootKernel.resolveHostPath("/var/minis/workspace/file.txt")
        assertNotNull(result)
        assertEquals("/host/workspace/file.txt", result!!.path)
    }

    @Test
    fun resolveHostPathUsesLongestPrefixMatch() {
        PRootKernel.addBindMount("/var", "/host/var")
        PRootKernel.addBindMount("/var/minis/workspace", "/host/workspace")

        val result = PRootKernel.resolveHostPath("/var/minis/workspace/deep/file.txt")
        assertNotNull(result)
        assertEquals("/host/workspace/deep/file.txt", result!!.path)
    }

    @Test
    fun resolveHostPathDoesNotMatchPartialPrefix() {
        PRootKernel.addBindMount("/var/minis/work", "/host/work")

        // "/var/minis/workspace" should NOT match "/var/minis/work" (no / after "work")
        val result = PRootKernel.resolveHostPath("/var/minis/workspace/file.txt")
        // Should fallback to rootfs or null, not match /var/minis/work
        assertNotEquals("/host/work/space/file.txt", result?.path ?: "")
    }

    @Test
    fun resolveHostPathReturnsNullWhenNoMountsAndNotBooted() {
        // No mounts, not booted → rootfsManager not initialized
        val result = PRootKernel.resolveHostPath("/some/path")
        assertNull(result)
    }

    @Test
    fun resolveHostPathFallsBackToRootfs() = runBlocking {
        org.junit.Assume.assumeTrue(
            "Ubuntu rootfs asset and the extracted proot binary are required",
            canBoot(),
        )
        PRootKernel.boot(context)

        // No bind mounts → should resolve relative to rootfsDir
        val result = PRootKernel.resolveHostPath("/etc/resolv.conf")
        assertNotNull(result)
        assertTrue("Should resolve inside rootfs", result!!.path.contains(SandboxDistribution.ROOTFS_DIR_NAME))
        assertTrue("Should end with etc/resolv.conf", result.path.endsWith("etc/resolv.conf"))
    }

    // ==================== customEnvironment ====================

    @Test
    fun customEnvironmentIsInitiallyEmpty() {
        assertTrue(PRootKernel.customEnvironment.isEmpty())
    }

    @Test
    fun customEnvironmentCanBeModified() {
        PRootKernel.customEnvironment["MY_VAR"] = "my_value"
        assertEquals("my_value", PRootKernel.customEnvironment["MY_VAR"])
    }

    // ==================== getProotTmpDir ====================

    @Test
    fun getProotTmpDirCreatesDirectory() {
        val tmpDir = PRootKernel.getProotTmpDir(context)
        assertTrue(tmpDir.exists())
        assertTrue(tmpDir.isDirectory)
        assertTrue(tmpDir.path.endsWith("proot-tmp"))
    }

    // ==================== Helpers ====================

    /**
     * Booting needs what the current build actually ships: the Ubuntu base
     * archive (compressed, or the uncompressed fallback AAPT may produce) and
     * the PRoot binary RootfsManager resolves in nativeLibraryDir. The legacy
     * guest archive and the old asset-located proot binary are gone, so
     * checking them would always answer false.
     */
    private fun canBoot(): Boolean {
        val hasArchive = listOf(
            SandboxDistribution.ROOTFS_ASSET,
            SandboxDistribution.ROOTFS_ASSET_TAR,
        ).any { name ->
            try {
                context.assets.open(name).use { }
                true
            } catch (_: Exception) {
                false
            }
        }
        if (!hasArchive) return false
        val manager = RootfsManager.getInstance(context)
        if (manager.rootfsMigrationError != null) return false
        val proot = manager.prootBinary
        return proot.isFile && proot.canExecute()
    }

    private fun resetKernel() {
        try {
            val field = PRootKernel::class.java.getDeclaredField("isBooted")
            field.isAccessible = true
            field.set(PRootKernel, false)
        } catch (_: Exception) { }
        PRootKernel.clearBindMounts()
        PRootKernel.customEnvironment.clear()
    }
}
