package com.openminis.app.offload

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tri-state Root gate, end to end on the JVM — no device, no su, no Root.
 *
 * What these pin down (the product semantics, not the implementation):
 *  - 允许 executes directly, for both the agent and the Chroot/system path;
 *  - 询问 raises ONE request for an agent call, answering for that call only;
 *    a refusal runs nothing and never flips the level;
 *  - 不允许 refuses everything, and the Chroot path gets a settings hint
 *    instead of a Root dialog;
 *  - the default is 询问, except that a proven Root capability promotes it to
 *    允许 once and persists it — an explicit user choice is never overridden;
 *  - any revocation / downgrade / reset invalidates approvals issued before it.
 */
class RootLevelGateTest {

    @After
    fun tearDown() {
        val field = OffloadPermissionManager::class.java.getDeclaredField("prefs")
        field.isAccessible = true
        // Leave the singleton uninitialised so another test's init() is honest.
        field.set(OffloadPermissionManager, null)
        val cache = OffloadPermissionManager::class.java.getDeclaredField("rootDetectedCache")
        cache.isAccessible = true
        cache.set(OffloadPermissionManager, null)
    }

    // ── Decision table ─────────────────────────────────────────────────────

    @Test
    fun allowedLevelExecutesDirectlyForBothCallers() {
        for (caller in RootCaller.entries) {
            assertEquals(
                RootDecision.EXECUTE,
                RootGrantPolicy.decide(RootAccess.BYPASS, caller, ticketEpoch = null, currentEpoch = 9),
            )
        }
    }

    @Test
    fun askOnceAgentRequestsExactlyOnce() {
        assertEquals(
            RootDecision.REQUEST_ONCE,
            RootGrantPolicy.decide(RootAccess.ASK_ONCE, RootCaller.AGENT, ticketEpoch = null, currentEpoch = 9),
        )
    }

    @Test
    fun askOnceSystemPathGetsSettingsHintNotADialog() {
        assertEquals(
            RootDecision.SETTINGS_HINT,
            RootGrantPolicy.decide(RootAccess.ASK_ONCE, RootCaller.SYSTEM, ticketEpoch = null, currentEpoch = 9),
        )
    }

    @Test
    fun notAllowedDeniesAgentAndHintsSystem() {
        assertEquals(
            RootDecision.DENY,
            RootGrantPolicy.decide(RootAccess.NOT_ALLOWED, RootCaller.AGENT, ticketEpoch = null, currentEpoch = 1),
        )
        assertEquals(
            RootDecision.SETTINGS_HINT,
            RootGrantPolicy.decide(RootAccess.NOT_ALLOWED, RootCaller.SYSTEM, ticketEpoch = null, currentEpoch = 1),
        )
    }

    @Test
    fun levelLabelsMatchTheTriStateRow() {
        assertEquals("允许", RootGrantPolicy.label(RootAccess.BYPASS))
        assertEquals("询问", RootGrantPolicy.label(RootAccess.ASK_ONCE))
        assertEquals("不允许", RootGrantPolicy.label(RootAccess.NOT_ALLOWED))
    }

    // ── One-off agent request: this call only ───────────────────────────────

    @Test
    fun acceptedAgentRequestRunsOnlyThatInvocation() = runBlocking {
        val store = FakePrefs()
        installPermissionPrefs(store)
        OffloadPermissionManager.setRootLevel(RootAccess.ASK_ONCE)
        assertFalse(OffloadPermissionManager.isRootGranted())

        // First invocation: the gate raises one single-use request. The
        // request is published before it suspends, so the answer must come
        // from the test body — answering inside the coroutine would deadlock
        // until the 120 s dialog timeout.
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            OffloadPermissionManager.confirmPrivilegedOperation(
                "root_cli", "'id'", rootAgentAccessRequest("", 5),
            )
        }
        pendingAnswer(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(first.await())
        assertEquals(
            "a one-off approval must not persist 允许",
            RootAccess.ASK_ONCE,
            OffloadPermissionManager.rootLevel(),
        )

        // Second invocation: asks again (nothing was remembered).
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            OffloadPermissionManager.confirmPrivilegedOperation(
                "root_cli", "'id'", rootAgentAccessRequest("", 5),
            )
        }
        pendingAnswer(OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(second.await())
        assertEquals(RootAccess.ASK_ONCE, OffloadPermissionManager.rootLevel())
    }

    @Test
    fun refusedRequestRunsNothingAndKeepsTheLevel() = runBlocking {
        installPermissionPrefs(FakePrefs())
        OffloadPermissionManager.setRootLevel(RootAccess.ASK_ONCE)
        val epochBefore = OffloadPermissionManager.rootAuthorizationEpoch()

        for (response in listOf(
            OffloadPermissionManager.Response.DENY_SESSION,
            OffloadPermissionManager.Response.ALLOW_SESSION, // also refused for privileged ops
        )) {
            val gate = async(start = CoroutineStart.UNDISPATCHED) {
                OffloadPermissionManager.confirmPrivilegedOperation(
                    "root_cli", "'id'", rootAgentAccessRequest("", 5),
                )
            }
            pendingAnswer(response)
            assertFalse(gate.await())
            assertEquals(RootAccess.ASK_ONCE, OffloadPermissionManager.rootLevel())
        }
        // Nothing ran and no state changed.
        assertEquals(epochBefore, OffloadPermissionManager.rootAuthorizationEpoch())
        assertFalse(OffloadPermissionManager.isRootGranted())
    }

    // ── Detected default: 允许 once, persisted, never over an explicit choice ──

    @Test
    fun noRootKeepsAskOnceDefault() {
        installPermissionPrefs(FakePrefs())
        assertEquals(RootAccess.ASK_ONCE, OffloadPermissionManager.rootLevel())
        assertFalse(OffloadPermissionManager.applyDetectedRootDefault(false))
        assertEquals(RootAccess.ASK_ONCE, OffloadPermissionManager.rootLevel())
        assertFalse(OffloadPermissionManager.isRootGranted())
    }

    @Test
    fun detectedRootPromotesToAllowedExactlyOnceAndPersists() {
        val store = FakePrefs()
        installPermissionPrefs(store)
        assertEquals(RootAccess.ASK_ONCE, OffloadPermissionManager.rootLevel())

        assertTrue("first successful probe writes the default", OffloadPermissionManager.applyDetectedRootDefault(true))
        assertEquals(RootAccess.BYPASS, OffloadPermissionManager.rootLevel())
        assertTrue(OffloadPermissionManager.isRootGranted())
        assertEquals(OffloadPermissionManager.PermissionLevel.BYPASS.name, store.values["level_root_cli"])
        assertEquals(1, store.levelWrites)

        // A second probe in the same process writes nothing.
        assertFalse(OffloadPermissionManager.applyDetectedRootDefault(true))
        assertEquals(1, store.levelWrites)

        // Process restart / settings page re-entry: the stored level is read
        // back without any further probe, so the user is not asked again.
        val restarted = store.restart()
        installPermissionPrefs(restarted)
        assertEquals(RootAccess.BYPASS, OffloadPermissionManager.rootLevel())
        assertTrue(OffloadPermissionManager.rootDefaultApplied())
        assertFalse("re-entry must not re-probe or rewrite", OffloadPermissionManager.applyDetectedRootDefault(true))
        assertEquals(0, restarted.levelWrites)
    }

    @Test
    fun explicitNotAllowedIsTheOnlyAnswerDetectionCannotOverride() {
        installPermissionPrefs(FakePrefs())
        // The user deliberately says 不允许 (e.g. after a previous 允许).
        OffloadPermissionManager.setRootLevel(RootAccess.NOT_ALLOWED)
        assertTrue(OffloadPermissionManager.isRootLevelUserChosen())
        assertFalse(OffloadPermissionManager.applyDetectedRootDefault(true))
        assertEquals(RootAccess.NOT_ALLOWED, OffloadPermissionManager.rootLevel())
        assertFalse(OffloadPermissionManager.rootDetectedOnce())
    }

    @Test
    fun askOnceIsPromotedOnceRootIsProven() {
        // 原生 Root 只验证一次: a stored 询问 (the default older builds wrote)
        // must not turn into a permanent per-call prompt on a rooted device.
        installPermissionPrefs(FakePrefs())
        OffloadPermissionManager.setRootLevel(RootAccess.ASK_ONCE)
        assertTrue(OffloadPermissionManager.applyDetectedRootDefault(true))
        assertEquals(RootAccess.BYPASS, OffloadPermissionManager.rootLevel())
        assertTrue(OffloadPermissionManager.rootDetectedOnce())
        assertTrue(OffloadPermissionManager.isRootGranted())
    }

    @Test
    fun detectedGrantIsStickyAcrossRestartsAndRevocableByTheUser() {
        installPermissionPrefs(FakePrefs())
        assertTrue(OffloadPermissionManager.applyDetectedRootDefault(true))
        assertEquals(RootAccess.BYPASS, OffloadPermissionManager.rootLevel())
        restartPermissionPrefs()
        // No second probe, no dialog: straight back to 允许.
        assertTrue(OffloadPermissionManager.rootDetectedOnce())
        assertEquals(RootAccess.BYPASS, OffloadPermissionManager.rootLevel())
        assertFalse("already proven; nothing left to detect",
            OffloadPermissionManager.applyDetectedRootDefault(true))

        // 不允许 is how the user revokes it, and it clears the marker.
        OffloadPermissionManager.setRootLevel(RootAccess.NOT_ALLOWED)
        assertFalse(OffloadPermissionManager.rootDetectedOnce())
        assertEquals(RootAccess.NOT_ALLOWED, OffloadPermissionManager.rootLevel())
        assertFalse(OffloadPermissionManager.applyDetectedRootDefault(true))
        assertEquals(RootAccess.NOT_ALLOWED, OffloadPermissionManager.rootLevel())
    }

    @Test
    fun promotionAndExplicitChoiceSurviveARestart() {
        installPermissionPrefs(FakePrefs())
        OffloadPermissionManager.applyDetectedRootDefault(true)
        restartPermissionPrefs()
        assertEquals(RootAccess.BYPASS, OffloadPermissionManager.rootLevel())
        // Now the user downgrades; the choice survives a restart too.
        OffloadPermissionManager.setRootLevel(RootAccess.NOT_ALLOWED)
        restartPermissionPrefs()
        assertEquals(RootAccess.NOT_ALLOWED, OffloadPermissionManager.rootLevel())
        assertTrue(OffloadPermissionManager.isRootLevelUserChosen())
        assertFalse(OffloadPermissionManager.applyDetectedRootDefault(true))
    }

    @Test
    fun legacyStoredLevelCountsAsAnExplicitChoice() {
        // A level written by an older build (there was only a switch) is the
        // user's decision, not a default: detection must not touch it.
        val store = FakePrefs(mapOf("level_root_cli" to OffloadPermissionManager.PermissionLevel.NOT_ALLOWED.name))
        installPermissionPrefs(store)
        assertTrue(OffloadPermissionManager.isRootLevelUserChosen())
        assertFalse(OffloadPermissionManager.applyDetectedRootDefault(true))
        assertEquals(RootAccess.NOT_ALLOWED, OffloadPermissionManager.rootLevel())
    }

    @Test
    fun resetAllReturnsToTheUndecidedDefaultAndDropsApprovals() {
        installPermissionPrefs(FakePrefs())
        OffloadPermissionManager.applyDetectedRootDefault(true)
        assertEquals(RootAccess.BYPASS, OffloadPermissionManager.rootLevel())
        val epoch = OffloadPermissionManager.rootAuthorizationEpoch()

        OffloadPermissionManager.resetAll()

        assertTrue("revocation bumps the epoch", OffloadPermissionManager.rootAuthorizationEpoch() > epoch)
        assertFalse(OffloadPermissionManager.isRootGranted())
        assertFalse(OffloadPermissionManager.isRootLevelUserChosen())
        assertFalse(OffloadPermissionManager.rootDefaultApplied())
        assertEquals(RootAccess.ASK_ONCE, OffloadPermissionManager.rootLevel())
    }

    // ── Revocation invalidates an approval already issued ───────────────────

    @Test
    fun revokingAllowedInvalidatesTheOldTicket() {
        installPermissionPrefs(FakePrefs())
        OffloadPermissionManager.applyDetectedRootDefault(true)
        val ticketEpoch = OffloadPermissionManager.rootAuthorizationEpoch()
        assertEquals(
            RootDecision.EXECUTE,
            RootGrantPolicy.decide(RootAccess.BYPASS, RootCaller.AGENT, null, ticketEpoch),
        )

        // 允许 → 询问 (and → 不允许) must kill the outstanding approval.
        OffloadPermissionManager.setRootLevel(RootAccess.ASK_ONCE)
        val after = OffloadPermissionManager.rootAuthorizationEpoch()
        assertTrue(after > ticketEpoch)
        assertFalse(RootGrantPolicy.ticketStillValid(ticketEpoch, after))
        assertEquals(
            "a ticket from the 允许 era never runs under 询问",
            RootDecision.DENY,
            RootGrantPolicy.decide(RootAccess.ASK_ONCE, RootCaller.AGENT, ticketEpoch, after),
        )

        OffloadPermissionManager.setRootLevel(RootAccess.NOT_ALLOWED)
        assertFalse(
            RootGrantPolicy.ticketStillValid(after, OffloadPermissionManager.rootAuthorizationEpoch()),
        )
        assertEquals(
            "and never becomes a dialog",
            RootDecision.DENY,
            RootGrantPolicy.decide(
                RootAccess.NOT_ALLOWED, RootCaller.AGENT, after,
                OffloadPermissionManager.rootAuthorizationEpoch(),
            ),
        )
    }

    // ── Chroot backend ─────────────────────────────────────────────────────

    @Test
    fun chrootUnderNotAllowedIsRefusedWithASettingsHint() {
        assertEquals(
            RootDecision.SETTINGS_HINT,
            RootGrantPolicy.decide(RootAccess.NOT_ALLOWED, RootCaller.SYSTEM, null, 4),
        )
        // The hint text points at the permission page, never at a Root dialog.
        assertTrue(ROOT_DISABLED_FOR_SYSTEM.contains("设置 → 权限 → 原生 Root"))
        assertTrue(ROOT_DISABLED_FOR_SYSTEM.contains("允许"))
        assertFalse(ROOT_DISABLED_FOR_SYSTEM.contains("确认"))
    }

    @Test
    fun chrootUnderAskOnceAsksOnceForThatSwitch() = runBlocking {
        installPermissionPrefs(FakePrefs())
        OffloadPermissionManager.setRootLevel(RootAccess.ASK_ONCE)

        val switch = async(start = CoroutineStart.UNDISPATCHED) {
            OffloadPermissionManager.confirmPrivilegedOperation(
                "root_cli", "'/data/app/lib/libminis-chroot.so' …", rootChrootSwitchRequest(20),
            )
        }
        pendingAnswer(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(switch.await())
        assertEquals("the switch does not enable Root", RootAccess.ASK_ONCE, OffloadPermissionManager.rootLevel())
        // Refusing the next switch leaves a settings hint, not a new level.
        val refused = async(start = CoroutineStart.UNDISPATCHED) {
            OffloadPermissionManager.confirmPrivilegedOperation(
                "root_cli", "'/data/app/lib/libminis-chroot.so' …", rootChrootSwitchRequest(20),
            )
        }
        pendingAnswer(OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(refused.await())
        assertEquals(RootAccess.ASK_ONCE, OffloadPermissionManager.rootLevel())
    }

    @Test
    fun copyDescribesTheTriStateAccurately() {
        assertTrue(ROOT_TRI_STATE_FOOTER.contains("允许"))
        assertTrue(ROOT_TRI_STATE_FOOTER.contains("询问"))
        assertTrue(ROOT_TRI_STATE_FOOTER.contains("不允许"))
        assertTrue(ROOT_TRI_STATE_FOOTER.contains("su 管理器"))
        assertTrue(ROOT_TRI_STATE_FOOTER.contains("默认允许"))
        assertTrue(ROOT_TRI_STATE_FOOTER.contains("可随时更改"))
        for (level in RootAccess.entries) assertTrue(rootLevelNote(level).isNotEmpty())
        val request = rootAgentAccessRequest("/data/local/tmp", 5)
        assertTrue(request.contains("不会把权限改成“允许”"))
        assertTrue(request.contains("超时"))
        assertTrue(request.contains("关键风险："))
        val switch = rootChrootSwitchRequest(20)
        assertTrue(switch.contains("保持“询问”"))
        assertTrue(ROOT_AGENT_REQUEST_DENIED.contains("未执行"))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Answer the request the gate just published; fails if none is pending. */
    private fun pendingAnswer(response: OffloadPermissionManager.Response) {
        val request = OffloadPermissionManager.pendingRequest.value
        requireNotNull(request) { "gate published no request to answer" }
        OffloadPermissionManager.respondToRequest(request, response)
        assertNull(OffloadPermissionManager.pendingRequest.value)
    }
}
