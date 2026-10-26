package com.openminis.app.offload

import app.openminis.rootbridge.RootExecutor
import app.openminis.rootbridge.RootExecutorConfig
import app.openminis.rootbridge.ShellCommand
import app.openminis.rootbridge.WireProtocol
import org.junit.Assert.*
import org.junit.Test

/**
 * Native Root safety boundaries, plus the grant contract that replaced the old
 * "every command asks again" model.
 *
 * Still true, and deliberately unchanged: the executor itself never asks for
 * su on a disabled/denied path, a missing authorizer denies even when the
 * config is enabled, and the authorizer sees the exact request. What changed:
 * the persistent grant (Settings → Permissions → Native Root) is used directly,
 * an ungranted AGENT call asks once per invocation, a SYSTEM call (Chroot,
 * settings probe) is refused with a settings hint, and revoking the grant
 * invalidates any approval issued before it.
 *
 * Denied/disabled paths are tested before su discovery; never launches real su.
 */
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

    @Test fun ungrantedAgentAsksOnceButSystemCallOnlyRefuses() {
        assertEquals(
            RootGrantPolicy.Decision.REQUEST_ACCESS,
            RootGrantPolicy.decide(
                granted = false,
                caller = RootGrantPolicy.Caller.AGENT,
                ticketEpoch = null,
                currentEpoch = 11,
            ),
        )
        assertEquals(
            RootGrantPolicy.Decision.REFUSE,
            RootGrantPolicy.decide(
                granted = false,
                caller = RootGrantPolicy.Caller.SYSTEM,
                ticketEpoch = null,
                currentEpoch = 11,
            ),
        )
    }

    @Test fun grantedCallUsesTheGrantWithoutAnotherApproval() {
        assertEquals(
            RootGrantPolicy.Decision.USE_GRANT,
            RootGrantPolicy.decide(
                granted = true,
                caller = RootGrantPolicy.Caller.AGENT,
                ticketEpoch = null,
                currentEpoch = 11,
            ),
        )
    }

    @Test fun approvalFromBeforeRevocationCannotBeReused() {
        assertEquals(
            RootGrantPolicy.Decision.REFUSE,
            RootGrantPolicy.decide(
                granted = false,
                caller = RootGrantPolicy.Caller.AGENT,
                ticketEpoch = 11,
                currentEpoch = 12,
            ),
        )
        assertFalse(RootGrantPolicy.ticketStillValid(11, 12))
    }
}
