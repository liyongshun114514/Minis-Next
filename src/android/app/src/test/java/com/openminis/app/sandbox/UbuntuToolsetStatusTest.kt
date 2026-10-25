package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Ordinary isolated fixture; never executes guest commands or package managers. */
class UbuntuToolsetStatusTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("ubuntu-toolset-state-").toFile()
        try {
            fun write(path: String, content: String) {
                File(root, path).apply { parentFile.mkdirs(); writeText(content) }
            }
            val packages = SandboxDistribution.BOOTSTRAP_PACKAGES
            val entries = packages.joinToString(",") { "\"$it\":\"1.0\"" }
            write(UbuntuToolsetStatus.MANIFEST,
                """{"schema":1,"distro":"ubuntu24.04","arch":"arm64","official_base_sha256":"${UbuntuToolsetStatus.OFFICIAL_BASE_SHA256}","packages":{$entries},"commands":{"python3":"Python 3.12"}}""")
            write(SandboxDistribution.BOOTSTRAP_DONE_MARKER, "verified in CI\n")
            write("var/lib/dpkg/status", packages.joinToString("\n\n", postfix = "\n") {
                "Package: $it\nStatus: install ok installed\nVersion: 1.0\n"
            })
            UbuntuToolsetStatus.requiredCommands.forEach { write("usr/bin/$it", "synthetic fixture, not executable\n") }
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun completePreinstalledFixtureIsReady() = fixture { root ->
        assertNull(UbuntuToolsetStatus.problem(root))
        assertTrue(UbuntuToolsetStatus.isReady(root))
    }

    @Test fun markerAloneDoesNotProveReadiness() = fixture { root ->
        File(root, UbuntuToolsetStatus.MANIFEST).delete()
        assertFalse(UbuntuToolsetStatus.isReady(root))
    }

    @Test fun missingPackageIsNotReady() = fixture { root ->
        val status = File(root, "var/lib/dpkg/status")
        status.writeText(status.readText().replace("Package: git\nStatus: install ok installed", "Package: git\nStatus: install ok unpacked"))
        assertTrue(UbuntuToolsetStatus.problem(root)!!.contains("git"))
    }

    @Test fun missingCommandIsNotReady() = fixture { root ->
        File(root, "usr/bin/python3").delete()
        assertTrue(UbuntuToolsetStatus.problem(root)!!.contains("python3"))
    }

    @Test fun oldFailedMarkerDoesNotOverrideVerifiedImage() = fixture { root ->
        File(root, SandboxDistribution.BOOTSTRAP_FAILED_MARKER).writeText("old attempt\n")
        assertTrue(UbuntuToolsetStatus.isReady(root))
    }

    @Test fun wrongImageIdentityIsNotReady() = fixture { root ->
        val manifest = File(root, UbuntuToolsetStatus.MANIFEST)
        manifest.writeText(manifest.readText().replace("ubuntu24.04", "other"))
        assertFalse(UbuntuToolsetStatus.isReady(root))
    }
}
