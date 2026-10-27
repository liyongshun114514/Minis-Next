package com.openminis.app.offload

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FEAT-261027-CHROOT-ROOT] The single-use grant registry.
 *
 * Pins the audit finding: a pre-authorisation cannot be reused, cannot be
 * matched to another command, and dies with the Root epoch. Android is stubbed
 * on the JVM; no su and no chroot are involved.
 */
class RootExecPermissionTest {

    private val request = RootExecRequest(
        command = "apt-get install -y curl",
        interactive = false,
        sessionId = "s1",
        required = true,
    )

    @Before
    fun setUp() {
        RootExecPermission.clear()
        installPermissionPrefs(FakePrefs())
        OffloadPermissionManager.setRootLevel(RootAccess.ASK_ONCE)
    }

    @After
    fun tearDown() {
        RootExecPermission.clear()
        OffloadPermissionManager.resetAll()
    }

    private fun epoch(): Long = OffloadPermissionManager.rootAuthorizationEpoch()

    @Test
    fun stashedGrantIsConsumedExactlyOnce() {
        val e = epoch()
        RootExecPermission.stash(request, epoch = e, nowMs = 1_000L)
        val first = RootExecPermission.consume(request, epoch = e, nowMs = 1_000L)
        assertNotNull(first)
        val second = RootExecPermission.consume(request, epoch = e, nowMs = 1_000L)
        assertNull("a grant must not be replayable", second)
    }

    @Test
    fun grantForAnotherCommandIsNotConsumed() {
        val e = epoch()
        val other = request.copy(command = "id")
        RootExecPermission.stash(other, epoch = e, nowMs = 1_000L)
        assertNull(RootExecPermission.consume(request, epoch = e, nowMs = 1_000L))
        // ... and the other command's own grant is still there for its spawn.
        assertNotNull(RootExecPermission.consume(other, epoch = e, nowMs = 1_000L))
    }

    @Test
    fun revokeInvalidatesAndDestroysTheGrant() {
        val e = epoch()
        RootExecPermission.stash(request, epoch = e, nowMs = 1_000L)
        assertNull(RootExecPermission.consume(request, epoch = e + 1, nowMs = 1_000L))
        assertNull(RootExecPermission.consume(request, epoch = e, nowMs = 1_000L))
    }

    @Test
    fun notAllowedNeverConsumes() {
        val e = epoch()
        RootExecPermission.stash(request, epoch = e, nowMs = 1_000L)
        assertNull(
            RootExecPermission.consume(
                request, level = RootAccess.NOT_ALLOWED, epoch = e, nowMs = 1_000L,
            ),
        )
        assertNull(RootExecPermission.consume(request, epoch = e, nowMs = 1_000L))
    }

    @Test
    fun expiredGrantIsRejected() {
        val e = epoch()
        RootExecPermission.stash(request, epoch = e, nowMs = 1_000L)
        assertNull(
            RootExecPermission.consume(
                request, epoch = e, nowMs = 1_000L + RootExecPolicy.GRANT_TTL_MS + 1,
            ),
        )
    }

    @Test
    fun approvalIsNeverRequestedFromAnUnpromptableThread() {
        // The JVM has no Android main looper (unitTests.isReturnDefaultValues
        // returns null), so the gate must refuse rather than block a UI
        // thread for two minutes.
        assertFalse(RootExecPermission.canPromptHere())
        assertNull(RootExecPermission.requestApproval(request, RootAccess.ASK_ONCE))
        assertTrue(RootExecPermission.rootExecRequestDescription(request).contains("只授权这一次"))
    }

    @Test
    fun discardDropsOnlyTheAbortedCommand() {
        val e = epoch()
        val other = request.copy(command = "id")
        RootExecPermission.stash(request, epoch = e, nowMs = 1_000L)
        RootExecPermission.stash(other, epoch = e, nowMs = 1_000L)
        RootExecPermission.discard(request)
        assertNull(RootExecPermission.consume(request, epoch = e, nowMs = 1_000L))
        assertNotNull(
            "an unrelated pending confirmation must survive",
            RootExecPermission.consume(other, epoch = e, nowMs = 1_000L),
        )
    }

    @Test
    fun confirmationShowsTheCommandVerbatimAndNeverClaimsPrivacy() {
        val long = "apt-get install -y " + (1..80).joinToString(" ") { "package$it" }
        val text = RootExecPermission.rootExecRequestDescription(
            request.copy(command = long),
        )
        assertTrue("the whole command must be visible", text.contains(long))
        assertTrue(text.contains("真实 uid0"))
        assertTrue(text.contains("不是安全边界"))
        assertFalse(text.contains("仅存在于"))
        assertFalse(text.contains("uid0 仅在"))
    }
}
