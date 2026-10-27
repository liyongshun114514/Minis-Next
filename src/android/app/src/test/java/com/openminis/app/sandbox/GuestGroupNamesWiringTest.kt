package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FEAT-261028-PROOT-GROUPS] Source-level guard for the part that cannot be
 * unit-tested on a plain JVM: the call sites in the real launch chain.
 *
 * The behaviour lives in [GuestGroupNames] and is covered by
 * [GuestGroupNamesTest]. This test only pins the wiring, so a refactor cannot
 * quietly drop the call and bring the numeric-group warnings back.
 */
class GuestGroupNamesWiringTest {
    private val rootfs = ProductionSources.read("sandbox/RootfsManager.kt")
    private val proot = ProductionSources.read("sandbox/PRootKernel.kt")
    private val helper = ProductionSources.read("sandbox/GuestSupplementaryGroups.kt")
    private val groupNames = ProductionSources.read("sandbox/GuestGroupNames.kt")
    private val chroot = ProductionSources.read("sandbox/ChrootKernel.kt")

    private fun methodBody(source: String, signature: String): String =
        source.substringAfter(signature).substringBefore("\n    }\n")

    @Test
    fun `PRoot boot names host ids before any shell can spawn`() {
        val boot = methodBody(proot, "private suspend fun bootLocked(context: Context) {")
        val call = boot.indexOf("rootfsManager.syncGuestGroupNames()")
        assertTrue("boot calls syncGuestGroupNames()", call >= 0)
        assertTrue("before the kernel is declared booted", boot.indexOf("isBooted = true") > call)
        // ...and after the rootfs exists (install + overlay run first), so a
        // fresh image is covered by the same call.
        assertTrue(boot.indexOf("installIfNeeded()") < call)
        // PRoot only: a chroot-only boot must not rewrite its guest group file.
        val guard = boot.indexOf("SandboxBackendStore.selected(context) == SandboxBackend.PROOT")
        assertTrue("backend guard", guard in 0 until call)
    }

    @Test
    fun `install does not touch the guest group file`() {
        val install = methodBody(
            rootfs,
            "suspend fun installIfNeeded() = withContext(Dispatchers.IO) {",
        )
        // installIfNeeded() also runs for a chroot image, so it must stay
        // untouched; PRoot boot repairs the live rootfs afterwards.
        assertFalse("install must not sync group names", install.contains("syncGuestGroupNames()"))
    }

    @Test
    fun `the id list comes from proc status, not a hidden API or a constant table`() {
        val ids = methodBody(helper, "fun processGroupIds(): List<Int> {")
        assertTrue("reads /proc/self/status", ids.contains("PROC_STATUS"))
        assertFalse("no android.system.Os use", ids.contains("android.system.Os"))
        assertFalse("no reflection", ids.contains("Class.forName"))
        for (reported in listOf("3003", "9997", "20368", "50368")) {
            assertFalse("no hard-coded $reported in the reader", ids.contains(reported))
        }
    }

    @Test
    fun `the helper writes only etc group, with no shell and no privilege change`() {
        for (src in listOf(helper, groupNames)) {
            assertFalse(src.contains("ProcessBuilder"))
            assertFalse(src.contains("Runtime.getRuntime"))
            assertFalse(src.contains("Os.setgroups"))
            assertFalse(src.contains("etc/passwd"))
            assertFalse(src.contains("etc/shadow"))
        }
        assertTrue(groupNames.contains("const val GROUP_FILE = \"etc/group\""))
    }

    @Test
    fun `a failed write is logged and recorded, not swallowed`() {
        assertTrue(rootfs.contains("var lastGuestGroupWarning"))
        assertTrue(rootfs.contains("Log.w(TAG, \"[GuestGroups]"))
        assertTrue(helper.contains("Log.w("))
        assertTrue(helper.contains("could not name host group ids"))
        // ensure() returns the error instead of throwing: boot cannot die here.
        assertTrue(groupNames.contains("val error: String? = null"))
        assertTrue(groupNames.contains("catch (t: Throwable)"))
    }

    @Test
    fun `permissions are inherited from the existing file`() {
        assertTrue(groupNames.contains("getPosixFilePermissions"))
        assertTrue(groupNames.contains("setPosixFilePermissions"))
        assertTrue(groupNames.contains("rw-r--r--"))
        // The old unconditional widen must not come back.
        assertFalse(groupNames.contains("setReadable(true, false)"))
        // A mode-copy failure must abort the replace, not fall back silently.
        val mode = groupNames.substringAfter("private fun applyMode(").substringBefore("\n    }\n")
        assertFalse("mode failure must propagate", mode.contains("runCatching"))
    }

    @Test
    fun `the chroot backend is not touched`() {
        assertFalse(chroot.contains("GuestSupplementaryGroups"))
        assertFalse(chroot.contains("GuestGroupNames"))
    }
}
