package com.openminis.app.sandbox

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Ordinary generated shell-wrapper tests; no Android/Root or native client execution. */
class HandlerStubRoutingTest {
    @Test fun generatedWrapperIsValidShell() {
        val process = ProcessBuilder("/bin/sh", "-n").start()
        process.outputStream.bufferedWriter().use { it.write(HandlerStub.content("android-device")) }
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(process.errorStream.bufferedReader().readText(), 0, process.exitValue())
    }

    @Test fun absentBackendReportsFailureAndForwardsNoCommand() {
        val process = ProcessBuilder("/bin/sh").apply {
            environment().remove("MINIS_SANDBOX_BACKEND")
        }.start()
        process.outputStream.bufferedWriter().use { it.write(HandlerStub.content("android-device")) }
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(HandlerStub.EXIT_UNAVAILABLE, process.exitValue())
        assertTrue(process.errorStream.bufferedReader().readText().contains("android-device: unavailable"))
        assertEquals("", process.inputStream.bufferedReader().readText())
    }

    @Test fun chrootRoutePreservesArgvAndRequiresAnInstalledClient() {
        val wrapper = HandlerStub.content("android-device")
        assertTrue(wrapper.contains("[ \"\${MINIS_SANDBOX_BACKEND:-}\" = CHROOT ]"))
        assertTrue(wrapper.contains("[ -x /usr/local/lib/minis/minis-offload-client ]"))
        assertTrue(wrapper.contains("exec /usr/local/lib/minis/minis-offload-client \"\$0\" \"\$@\""))
        assertFalse(wrapper.contains("su -c"))
        assertFalse(wrapper.contains("eval "))
        assertTrue(wrapper.endsWith(HandlerStub.previousContent("android-device").removePrefix("#!/bin/sh\n")))
    }

    @Test fun onlyKnownAppWrappersAreMigrationCandidates() {
        val previous = HandlerStub.previousContent("android-device")
        assertNotEquals(previous, HandlerStub.content("android-device"))
        assertNotEquals(previous, "#!/bin/sh\necho user-script\n")
        assertTrue(previous.startsWith("#!/bin/sh\n"))
    }
}
