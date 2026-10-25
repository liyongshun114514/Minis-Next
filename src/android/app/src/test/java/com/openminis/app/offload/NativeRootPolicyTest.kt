package com.openminis.app.offload

import app.openminis.rootbridge.RootExecutor
import app.openminis.rootbridge.RootExecutorConfig
import app.openminis.rootbridge.ShellCommand
import app.openminis.rootbridge.WireProtocol
import org.junit.Assert.*
import org.junit.Test

/** Denied/disabled paths are tested before su discovery; never launches real su. */
class NativeRootPolicyTest {
    private class Sink : RootExecutor.Sink {
        var exit = -1
        var error = ""
        override fun stdout(data: ByteArray) = true
        override fun stderr(data: ByteArray): Boolean { error += String(data); return true }
        override fun finish(code: Int) { exit = code }
    }
    private val request = WireProtocol.Request(listOf("id"), "", false)
    @Test fun disabledNeverAsksOrExecutes() {
        var asked = false
        val sink = Sink()
        RootExecutor(RootExecutorConfig(), RootExecutor.Authorizer { asked = true; true }).execute(request, sink)
        assertFalse(asked)
        assertEquals(126, sink.exit)
    }
    @Test fun missingAuthorizerDeniesEvenWhenEnabled() {
        val config = RootExecutorConfig().apply { enabled = true }
        val sink = Sink()
        RootExecutor(config).execute(request, sink)
        assertEquals(126, sink.exit)
        assertTrue(sink.error.contains("denied"))
    }
    @Test fun exactRequestMustBeApproved() {
        var seen: WireProtocol.Request? = null
        val config = RootExecutorConfig().apply { enabled = true }
        val sink = Sink()
        RootExecutor(config, RootExecutor.Authorizer { seen = it; false }).execute(request, sink)
        assertSame(request, seen)
        assertEquals(126, sink.exit)
    }
    @Test fun quotePreservesUntrustedArguments() {
        assertEquals("'a'\\''b'", ShellCommand.quote("a'b"))
    }
}
