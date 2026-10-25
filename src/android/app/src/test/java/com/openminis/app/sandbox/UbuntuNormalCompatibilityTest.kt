package com.openminis.app.sandbox

import org.junit.Assert.*
import org.junit.Test

/** Ordinary successful guest setup checks; no security-trigger inputs. */
class UbuntuNormalCompatibilityTest {
    @Test fun ubuntuAssetAndSeries() {
        assertEquals("ubuntu-base-arm64.tar.gz", SandboxDistribution.ROOTFS_ASSET)
        assertEquals("noble", SandboxDistribution.CODENAME)
        assertEquals("aarch64", SandboxDistribution.ARCH)
    }
    @Test fun officialSourcesContainMainAndUpdates() {
        val sources = SandboxDistribution.aptSources()
        assertTrue(sources.contains("noble"))
        assertTrue(sources.contains("noble-updates"))
        assertTrue(sources.contains("Signed-By:"))
    }
    @Test fun bootstrapUsesUbuntuPackageManager() {
        val script = SandboxDistribution.bootstrapScript()
        assertTrue(script.contains("apt-get update"))
        assertTrue(script.contains("apt-get install"))
        assertFalse(script.contains("apk add"))
    }
}
