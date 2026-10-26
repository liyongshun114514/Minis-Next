package com.openminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Normal Root grant / refusal / revocation logic, kept on the JVM.
 *
 * These pin the three states the feature has to get right:
 *  - an explicit grant is used directly (no per-command request);
 *  - without it, an agent call requests access once, while a system path
 *    (Chroot switch, settings probe) refuses with a settings hint and never
 *    enables anything;
 *  - revoking invalidates approvals issued before the revocation.
 */
class RootGrantPolicyTest {
    private val noTicket: Long? = null

    @Test
    fun grantedIsUsedDirectlyForBothCallers() {
        assertEquals(
            RootGrantPolicy.Decision.USE_GRANT,
            RootGrantPolicy.decide(
                granted = true,
                caller = RootGrantPolicy.Caller.AGENT,
                ticketEpoch = noTicket,
                currentEpoch = 7,
            ),
        )
        assertEquals(
            RootGrantPolicy.Decision.USE_GRANT,
            RootGrantPolicy.decide(
                granted = true,
                caller = RootGrantPolicy.Caller.SYSTEM,
                ticketEpoch = noTicket,
                currentEpoch = 7,
            ),
        )
    }

    @Test
    fun agentWithoutGrantRequestsAccessOnce() {
        assertEquals(
            RootGrantPolicy.Decision.REQUEST_ACCESS,
            RootGrantPolicy.decide(
                granted = false,
                caller = RootGrantPolicy.Caller.AGENT,
                ticketEpoch = noTicket,
                currentEpoch = 3,
            ),
        )
    }

    @Test
    fun systemWithoutGrantRefusesInsteadOfEnabling() {
        assertEquals(
            RootGrantPolicy.Decision.REFUSE,
            RootGrantPolicy.decide(
                granted = false,
                caller = RootGrantPolicy.Caller.SYSTEM,
                ticketEpoch = noTicket,
                currentEpoch = 3,
            ),
        )
    }

    @Test
    fun matchingTicketRunsWithoutASecondPrompt() {
        assertEquals(
            RootGrantPolicy.Decision.USE_GRANT,
            RootGrantPolicy.decide(
                granted = false,
                caller = RootGrantPolicy.Caller.AGENT,
                ticketEpoch = 3,
                currentEpoch = 3,
            ),
        )
        assertTrue(RootGrantPolicy.ticketStillValid(3, 3))
    }

    @Test
    fun revokeInvalidatesTicketIssuedBeforeIt() {
        assertEquals(
            RootGrantPolicy.Decision.REFUSE,
            RootGrantPolicy.decide(
                granted = false,
                caller = RootGrantPolicy.Caller.AGENT,
                ticketEpoch = 3,
                currentEpoch = 4,
            ),
        )
        assertFalse(RootGrantPolicy.ticketStillValid(3, 4))
    }

    @Test
    fun grantAfterRevokeUsesTheNewGrantNotTheStaleTicket() {
        assertEquals(
            RootGrantPolicy.Decision.USE_GRANT,
            RootGrantPolicy.decide(
                granted = true,
                caller = RootGrantPolicy.Caller.SYSTEM,
                ticketEpoch = 3,
                currentEpoch = 4,
            ),
        )
    }

    @Test
    fun copyStatesPersistentGrantAndNoImplicitEnable() {
        assertTrue(ROOT_ENABLE_MESSAGE.contains("不再逐条询问"))
        assertTrue(ROOT_ENABLE_MESSAGE.contains("su 管理器"))
        assertTrue(ROOT_ENABLE_MESSAGE.contains("随时"))
        assertFalse(ROOT_ENABLE_MESSAGE.contains("每条命令仍须"))

        val request = rootAgentAccessRequest("/data/local/tmp", 5)
        assertTrue(request.contains("不会自动开启"))
        assertTrue(request.contains("拒绝、取消或超时则不执行"))
        assertTrue(request.contains("设置 → 权限 → 原生 Root"))
        assertTrue(request.contains("关键风险："))
        assertTrue(request.contains("5s"))

        assertTrue(ROOT_DISABLED_FOR_SYSTEM.contains("设置 → 权限 → 原生 Root"))
        assertTrue(ROOT_AGENT_REQUEST_DENIED.contains("未执行"))
        assertTrue(ROOT_REVOKED_MESSAGE.contains("新的 Root 操作会被拒绝"))
    }
}
