package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

/** Normal construction/identity wiring; no privileged helper is executed. */
class ChrootSharedFilesTest {
    @Test fun appDataDirectoryIsSeparateFromAllowedMountRoots() {
        val args = ChrootKernel.serverArgv(
            launcher = "/app/libminis-chroot.so", rootfs = "/app/files/Ubuntu-rootfs",
            generation = "fixture", allowedHostRoots = listOf("/app/files", "/app/cache"),
            uid = 20123, gid = 20456, rootExecCapable = true, appFilesDir = "/app/files",
        )
        assertEquals("20123", args[args.indexOf("--uid") + 1])
        assertEquals("20456", args[args.indexOf("--gid") + 1])
        assertEquals("/app/files", args[args.indexOf("--app-files") + 1])
        assertTrue(args.contains("--root-exec"))
    }

    @Test fun productionUsesActualUidAndPrimaryGid() {
        val source = ProductionSources.read("sandbox/ChrootKernel.kt")
        assertTrue(source.contains("uid = android.os.Process.myUid()"))
        assertTrue(source.contains("private fun appGid(): Int = android.system.Os.getgid()"))
        assertTrue(source.contains("appFilesDir = context.filesDir.absolutePath"))
        assertTrue(source.contains("rootExec = decision.outcome == RootExecOutcome.ROOT"))
    }
}
