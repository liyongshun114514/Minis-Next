package com.openminis.app.offload

import app.openminis.rootbridge.RootExecutor
import app.openminis.rootbridge.RootExecutorConfig
import app.openminis.rootbridge.ShellCommand
import app.openminis.rootbridge.WireProtocol
import org.junit.Assert.*
import org.junit.Test

/**
 * Native Root safety boundaries, plus the tri-state grant contract that replaced
 * the old "persistent switch + per-invocation request" pair.
 *
 * Still true, and deliberately unchanged: the executor itself never asks for
 * su on a disabled/denied path, a missing authorizer denies even when the
 * config is enabled, and the authorizer sees the exact request. What the gate
 * adds: 允许 executes directly; 询问 asks once per invocation for the agent and
 * only hints from a system path (Chroot, settings probe); 不允许 refuses; and
 * revoking the level invalidates any approval issued before it.
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

    @Test fun askOnceAgentRequestsOnceButSystemCallOnlyHints() {
        assertEquals(
            RootDecision.REQUEST_ONCE,
            RootGrantPolicy.decide(RootAccess.ASK_ONCE, RootCaller.AGENT, ticketEpoch = null, currentEpoch = 11),
        )
        assertEquals(
            RootDecision.SETTINGS_HINT,
            RootGrantPolicy.decide(RootAccess.ASK_ONCE, RootCaller.SYSTEM, ticketEpoch = null, currentEpoch = 11),
        )
    }

    @Test fun notAllowedGetsNoDialogAtAll() {
        assertEquals(
            RootDecision.DENY,
            RootGrantPolicy.decide(RootAccess.NOT_ALLOWED, RootCaller.AGENT, ticketEpoch = null, currentEpoch = 11),
        )
        assertEquals(
            RootDecision.SETTINGS_HINT,
            RootGrantPolicy.decide(RootAccess.NOT_ALLOWED, RootCaller.SYSTEM, ticketEpoch = null, currentEpoch = 11),
        )
    }

    @Test fun allowedCallUsesTheGrantWithoutAnotherApproval() {
        assertEquals(
            RootDecision.EXECUTE,
            RootGrantPolicy.decide(RootAccess.BYPASS, RootCaller.AGENT, ticketEpoch = null, currentEpoch = 11),
        )
    }

    @Test fun approvalFromBeforeRevocationCannotBeReused() {
        assertEquals(
            RootDecision.DENY,
            RootGrantPolicy.decide(RootAccess.ASK_ONCE, RootCaller.AGENT, ticketEpoch = 11, currentEpoch = 12),
        )
        assertFalse(RootGrantPolicy.ticketStillValid(11, 12))
    }
}
