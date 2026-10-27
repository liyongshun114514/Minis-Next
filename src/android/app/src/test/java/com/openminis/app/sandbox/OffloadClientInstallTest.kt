package com.openminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-chroot-offload-client-refresh] The install rule that keeps a rootfs from
 * pinning a stale guest offload client forever.
 *
 * Regression: the check was "the file exists and is executable", so the first
 * client written into a rootfs was never replaced. Once chroot commands began
 * running as real uid 0, that stale client connected to the offload socket as
 * uid 0, NativeOffloadServer refused it, and every Android/minis tool inside a
 * chroot shell printed "malformed reply ... exit 69" - surviving app updates.
 */
class OffloadClientInstallTest {

    @Test
    fun `a missing client is installed`() {
        assertTrue(OffloadClientInstall.needsRefresh(assetCrc = 42, stampCrc = null, targetExists = false, targetCrc = null))
    }

    @Test
    fun `an unreadable target is replaced`() {
        assertTrue(OffloadClientInstall.needsRefresh(assetCrc = 42, stampCrc = 42, targetExists = true, targetCrc = null))
    }

    @Test
    fun `matching bytes and stamp are left alone`() {
        assertFalse(OffloadClientInstall.needsRefresh(assetCrc = 42, stampCrc = 42, targetExists = true, targetCrc = 42))
    }

    @Test
    fun `a client from another build is replaced`() {
        // The exact reported case: right name, right path, old binary.
        assertTrue(OffloadClientInstall.needsRefresh(assetCrc = 42, stampCrc = 41, targetExists = true, targetCrc = 41))
    }

    @Test
    fun `a missing stamp forces one refresh`() {
        // Upgrading from the install-once build: no stamp exists yet, so the
        // bytes are compared and the client is rewritten once.
        assertTrue(OffloadClientInstall.needsRefresh(assetCrc = 42, stampCrc = null, targetExists = true, targetCrc = 41))
        assertTrue(OffloadClientInstall.needsRefresh(assetCrc = 42, stampCrc = null, targetExists = true, targetCrc = 42))
    }

    @Test
    fun `a target truncated behind a valid stamp is replaced`() {
        assertTrue(OffloadClientInstall.needsRefresh(assetCrc = 42, stampCrc = 42, targetExists = true, targetCrc = 17))
    }

    @Test
    fun `the stamp lives in the client directory`() {
        assertTrue(OffloadClientInstall.STAMP_NAME.startsWith("."))
        assertFalse(OffloadClientInstall.STAMP_NAME.contains('/'))
    }
}
