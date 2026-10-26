package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Normal Root tri-state logic, kept on the JVM.
 *
 * These pin the three states the feature has to get right:
 *  - 允许 is used directly (no per-command request), for the agent and Chroot;
 *  - 询问 asks ONE agent request that covers that invocation only, while a
 *    system path (Chroot switch, settings probe) gets a settings hint and
 *    never a dialog;
 *  - 不允许 refuses everything;
 *  - revoking invalidates approvals issued before the revocation.
 *
 * @see RootLevelGateTest for the persisted default (detected → 允许 once).
 */
class RootGrantPolicyTest {

    @Test
    fun allowedIsUsedDirectlyForBothCallers() {
        for (caller in RootCaller.entries) {
            assertEquals(
                RootDecision.EXECUTE,
                RootGrantPolicy.decide(RootAccess.BYPASS, caller, ticketEpoch = null, currentEpoch = 7),
            )
        }
    }

    @Test
    fun askOnceAgentRequestsAccessOnce() {
        assertEquals(
            RootDecision.REQUEST_ONCE,
            RootGrantPolicy.decide(RootAccess.ASK_ONCE, RootCaller.AGENT, ticketEpoch = null, currentEpoch = 3),
        )
    }

    @Test
    fun askOnceSystemPathGetsASettingsHintInsteadOfEnabling() {
        assertEquals(
            RootDecision.SETTINGS_HINT,
            RootGrantPolicy.decide(RootAccess.ASK_ONCE, RootCaller.SYSTEM, ticketEpoch = null, currentEpoch = 3),
        )
    }

    @Test
    fun notAllowedRefusesAgentAndHintsSystem() {
        assertEquals(
            RootDecision.DENY,
            RootGrantPolicy.decide(RootAccess.NOT_ALLOWED, RootCaller.AGENT, ticketEpoch = null, currentEpoch = 3),
        )
        assertEquals(
            RootDecision.SETTINGS_HINT,
            RootGrantPolicy.decide(RootAccess.NOT_ALLOWED, RootCaller.SYSTEM, ticketEpoch = null, currentEpoch = 3),
        )
    }

    @Test
    fun matchingTicketRunsWithoutASecondPrompt() {
        assertEquals(
            RootDecision.EXECUTE,
            RootGrantPolicy.decide(RootAccess.ASK_ONCE, RootCaller.AGENT, ticketEpoch = 3, currentEpoch = 3),
        )
        assertTrue(RootGrantPolicy.ticketStillValid(3, 3))
    }

    @Test
    fun revokeInvalidatesTicketIssuedBeforeIt() {
        assertEquals(
            RootDecision.DENY,
            RootGrantPolicy.decide(RootAccess.ASK_ONCE, RootCaller.AGENT, ticketEpoch = 3, currentEpoch = 4),
        )
        assertFalse(RootGrantPolicy.ticketStillValid(3, 4))
    }

    @Test
    fun grantAfterRevokeUsesTheNewGrantNotTheStaleTicket() {
        assertEquals(
            RootDecision.EXECUTE,
            RootGrantPolicy.decide(RootAccess.BYPASS, RootCaller.SYSTEM, ticketEpoch = 3, currentEpoch = 4),
        )
    }

    @Test
    fun levelMappingRoundTrips() {
        for (level in OffloadPermissionManager.PermissionLevel.entries) {
            assertEquals(level, RootGrantPolicy.toLevel(RootGrantPolicy.levelOf(level)))
        }
    }

    @Test
    fun detectedDefaultIsAllowedOnlyWhileUndecided() {
        // Undecided + detected → 允许 ...
        assertEquals(RootAccess.BYPASS, RootDefaultPolicy.defaultLevel(userChosen = false, rootDetected = true))
        // ... and 询问 otherwise (a failed probe never means 允许).
        assertEquals(RootAccess.ASK_ONCE, RootDefaultPolicy.defaultLevel(userChosen = false, rootDetected = false))
        assertEquals(RootAccess.ASK_ONCE, RootDefaultPolicy.defaultLevel(userChosen = true, rootDetected = true))
        assertTrue(RootDefaultPolicy.shouldPromote(true, false, false) == false)
        assertTrue(RootDefaultPolicy.shouldPromote(false, false, true))
        assertFalse(RootDefaultPolicy.shouldPromote(false, true, true))
        assertFalse(RootDefaultPolicy.shouldPromote(true, false, true))
    }

    @Test
    fun storedLevelWinsOverTheDetectionDefault() {
        // A stored 不允许 survives detection, and a failed probe keeps 询问.
        assertEquals(
            RootAccess.NOT_ALLOWED,
            RootDefaultPolicy.effectiveLevel(
                OffloadPermissionManager.PermissionLevel.NOT_ALLOWED, userChosen = true, rootDetected = true,
            ),
        )
        assertEquals(
            RootAccess.ASK_ONCE,
            RootDefaultPolicy.effectiveLevel(null, userChosen = false, rootDetected = false),
        )
    }

    @Test
    fun copyStatesTheTriStateAndNoImplicitEnable() {
        assertTrue(ROOT_ENABLE_MESSAGE.contains("不再逐条询问"))
        assertTrue(ROOT_ENABLE_MESSAGE.contains("su 管理器"))
        assertTrue(ROOT_ENABLE_MESSAGE.contains("随时"))
        assertFalse(ROOT_ENABLE_MESSAGE.contains("每条命令仍须"))

        val request = rootAgentAccessRequest("/data/local/tmp", 5)
        assertTrue(request.contains("之后直接用，不再逐次询问"))
        assertTrue(request.contains("拒绝、取消或超时则不执行"))
        assertTrue(request.contains("设置 → 权限 → 原生 Root"))
        assertTrue(request.contains("关键风险："))
        assertTrue(request.contains("5s"))

        assertTrue(ROOT_DISABLED_FOR_SYSTEM.contains("设置 → 权限 → 原生 Root"))
        assertTrue(ROOT_AGENT_REQUEST_DENIED.contains("未执行"))
        assertTrue(ROOT_REVOKED_MESSAGE.contains("新的 Root 操作会被拒绝"))
    }
}
