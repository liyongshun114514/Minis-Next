package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FEAT-261027-CHROOT-ROOT] The pure gate: no Android, no su, no chroot.
 *
 * These tests pin the three properties the interruption review asked for:
 *  - 不允许 never downgrades to an app-uid command (it refuses);
 *  - a 每次询问 approval is single-use and bound to ONE exact command +
 *    epoch, so a cached "approved" boolean cannot be replayed;
 *  - 完全允许 is the only dialog-free level.
 */
class RootExecPolicyTest {

    private val cmd = RootExecRequest(
        command = "apt-get install -y curl",
        interactive = false,
        sessionId = "s1",
        required = true,
    )

    private fun grant(request: RootExecRequest = cmd, epoch: Long = 7L, at: Long = 1_000L) =
        RootExecGrant(request.fingerprint, epoch, at)

    // ── 不允许 ────────────────────────────────────────────────────────────

    @Test
    fun notAllowedRefusesCommandAndNeverDowngrades() {
        val decision = RootExecPolicy.decide(
            RootAccess.NOT_ALLOWED, cmd, grant = null, currentEpoch = 7L, nowMs = 1_000L,
        )
        assertEquals(RootExecOutcome.REFUSED, decision.outcome)
        assertTrue(decision.reason.contains("不会静默降级"))
        assertTrue(decision.reason.contains("不允许"))
    }

    @Test
    fun notAllowedRefusesEvenWithAStaleGrant() {
        val decision = RootExecPolicy.decide(
            RootAccess.NOT_ALLOWED, cmd, grant = grant(), currentEpoch = 7L, nowMs = 1_000L,
        )
        assertEquals(RootExecOutcome.REFUSED, decision.outcome)
    }

    @Test
    fun notAllowedRefusesInteractiveSessions() {
        val shell = RootExecRequest(command = "", interactive = true, sessionId = "s1")
        val decision = RootExecPolicy.decide(
            RootAccess.NOT_ALLOWED, shell, grant = null, currentEpoch = 0L, nowMs = 0L,
        )
        assertEquals(RootExecOutcome.REFUSED, decision.outcome)
    }

    // ── 完全允许 ─────────────────────────────────────────────────────────

    @Test
    fun bypassRunsAsRootWithoutAnyGrant() {
        val decision = RootExecPolicy.decide(
            RootAccess.BYPASS, cmd, grant = null, currentEpoch = 0L, nowMs = 0L,
        )
        assertEquals(RootExecOutcome.ROOT, decision.outcome)
        assertTrue(RootExecPolicy.isPersistentlyAllowed(RootAccess.BYPASS))
        assertFalse(RootExecPolicy.isPersistentlyAllowed(RootAccess.ASK_ONCE))
    }

    // ── 每次询问 ─────────────────────────────────────────────────────────

    @Test
    fun askWithoutApprovalNeedsOneConfirmationAndSaysNothingRuns() {
        val decision = RootExecPolicy.decide(
            RootAccess.ASK_ONCE, cmd, grant = null, currentEpoch = 3L, nowMs = 1_000L,
        )
        assertEquals(RootExecOutcome.NEED_APPROVAL, decision.outcome)
        assertTrue(decision.reason.contains("取消"))
        assertTrue(decision.reason.contains("不会把级别改成“完全允许”"))
    }

    @Test
    fun askWithMatchingGrantRunsAsRoot() {
        val decision = RootExecPolicy.decide(
            RootAccess.ASK_ONCE, cmd, grant = grant(), currentEpoch = 7L, nowMs = 2_000L,
        )
        assertEquals(RootExecOutcome.ROOT, decision.outcome)
    }

    @Test
    fun grantForAnotherCommandCannotAuthoriseThisOne() {
        val other = cmd.copy(command = "apt-get install -y wget")
        assertNotEquals(other.fingerprint, cmd.fingerprint)
        val decision = RootExecPolicy.decide(
            RootAccess.ASK_ONCE, cmd, grant = grant(other), currentEpoch = 7L, nowMs = 2_000L,
        )
        assertEquals(RootExecOutcome.NEED_APPROVAL, decision.outcome)
    }

    @Test
    fun grantForAnotherSessionCannotAuthoriseThisOne() {
        val other = cmd.copy(sessionId = "s2")
        assertEquals(
            RootExecOutcome.NEED_APPROVAL,
            RootExecPolicy.decide(
                RootAccess.ASK_ONCE, cmd, grant = grant(other), currentEpoch = 7L, nowMs = 2_000L,
            ).outcome,
        )
    }

    @Test
    fun whitespaceVariantIsADifferentCommand() {
        val variant = cmd.copy(command = "apt-get install -y curl ")
        assertEquals(
            RootExecOutcome.NEED_APPROVAL,
            RootExecPolicy.decide(
                RootAccess.ASK_ONCE, cmd, grant = grant(variant), currentEpoch = 7L, nowMs = 2_000L,
            ).outcome,
        )
    }

    @Test
    fun interactiveGrantDoesNotAuthoriseANonInteractiveCommand() {
        val shell = RootExecRequest(command = "", interactive = true, sessionId = "s1")
        assertEquals(
            RootExecOutcome.NEED_APPROVAL,
            RootExecPolicy.decide(
                RootAccess.ASK_ONCE, cmd, grant = grant(shell), currentEpoch = 7L, nowMs = 1_000L,
            ).outcome,
        )
    }

    @Test
    fun grantFromAnOlderEpochIsDeadAfterARevoke() {
        val decision = RootExecPolicy.decide(
            RootAccess.ASK_ONCE, cmd, grant = grant(epoch = 7L), currentEpoch = 8L, nowMs = 1_000L,
        )
        assertEquals(RootExecOutcome.NEED_APPROVAL, decision.outcome)
    }

    @Test
    fun grantExpiresAfterItsTtl() {
        val at = 1_000L
        val still = RootExecPolicy.decide(
            RootAccess.ASK_ONCE, cmd, grant = grant(at = at),
            currentEpoch = 7L, nowMs = at + RootExecPolicy.GRANT_TTL_MS,
        )
        assertEquals(RootExecOutcome.ROOT, still.outcome)
        val expired = RootExecPolicy.decide(
            RootAccess.ASK_ONCE, cmd, grant = grant(at = at),
            currentEpoch = 7L, nowMs = at + RootExecPolicy.GRANT_TTL_MS + 1,
        )
        assertEquals(RootExecOutcome.NEED_APPROVAL, expired.outcome)
    }

    @Test
    fun grantMintedInTheFutureIsNotValid() {
        assertFalse(
            RootExecPolicy.grantStillValid(
                grant(at = 5_000L), cmd, currentEpoch = 7L, nowMs = 4_000L,
            ),
        )
    }

    // ── fingerprint / capability ─────────────────────────────────────────

    @Test
    fun fingerprintIsStableAndRequestSpecific() {
        assertEquals(cmd.fingerprint, cmd.copy().fingerprint)
        assertEquals(64, cmd.fingerprint.length)
        assertNotEquals(cmd.fingerprint, cmd.copy(required = false).fingerprint)
    }

    @Test
    fun helperCapabilityExistsAtAskAndBypassButNotAtNotAllowed() {
        assertTrue(RootExecPolicy.helperMayRunRoot(RootAccess.ASK_ONCE))
        assertTrue(RootExecPolicy.helperMayRunRoot(RootAccess.BYPASS))
        assertFalse(RootExecPolicy.helperMayRunRoot(RootAccess.NOT_ALLOWED))
    }
}
