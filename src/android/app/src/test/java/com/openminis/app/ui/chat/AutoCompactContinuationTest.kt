package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

class AutoCompactContinuationTest {
    @Test fun `only a successful compact of the same unfinished run resumes`() {
        for (ok in listOf(false, true))
            for (pending in listOf(false, true))
                for (same in listOf(false, true))
                    for (cancelled in listOf(false, true)) {
                        assertEquals("ok=$ok pending=$pending same=$same cancelled=$cancelled",
                            ok && pending && same && !cancelled,
                            AutoCompactContinuation.shouldResume(ok, pending, same, cancelled))
                    }
    }

    @Test fun `continuation is bounded to current work and does not repeat completed actions`() {
        val text = AutoCompactContinuation.MESSAGE
        assertTrue(text.contains("latest unfinished request"))
        assertTrue(text.contains("do not repeat completed actions"))
        assertTrue(text.contains("If the request is already complete"))
        assertTrue(text.startsWith("<system-reminder>"))
    }

    @Test fun `successful in-loop compact adds the continuation before the next request`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val branch = vm.substringAfter("InLoopContextAction.COMPACTED -> {")
            .substringBefore("InLoopContextAction.STOP -> {")
        assertTrue(branch.contains("AutoCompactContinuation.shouldResume("))
        assertTrue(branch.contains("sameRun = liveTurn.isLive(turnGen)"))
        assertTrue(branch.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(branch.contains("appendCompactContinuation()"))
        assertTrue(branch.indexOf("appendCompactContinuation()") < branch.lastIndexOf("continue"))
        assertFalse(branch.contains("sendMessage("))
        assertFalse(branch.contains("runAgentLoop("))
        val append = vm.substringAfter("private suspend fun appendCompactContinuation()")
            .substringBefore("private suspend fun compactCompletedTurnIfNeeded(")
        assertTrue(append.contains("AutoCompactContinuation.MESSAGE"))
        assertTrue(append.contains("chatRepository.appendMessage("))
        assertTrue(append.contains("agentHistory.add("))
    }

    @Test fun `completed turn may compact but never sends a new work order`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val idle = vm.substringAfter("private suspend fun compactCompletedTurnIfNeeded(")
            .substringBefore("System prompt for the single-shot")
        assertTrue(idle.contains("AutoCompactPrefs.isEnabled()"))
        assertTrue(idle.contains("policy.check(tokens, window)"))
        assertTrue(idle.contains("awaitCompaction()"))
        assertFalse(idle.contains("appendCompactContinuation()"))
        assertFalse(idle.contains("sendMessage("))
        assertFalse(idle.contains("runAgentLoop("))
        assertFalse(idle.contains("AutoCompactContinuation.MESSAGE"))
        assertTrue(vm.contains("loopExitedNormally && stopDiagExit == \"no-tool-calls\""))
    }

    @Test fun `cancelled wait cancels only its own compact and callback is one shot`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val await = vm.substringAfter("private suspend fun awaitCompaction(): Boolean =")
            .substringBefore("private suspend fun appendCompactContinuation()")
        assertTrue(await.contains("AtomicBoolean(false)"))
        assertTrue(await.contains("compareAndSet(false, true)"))
        assertTrue(await.contains("takeUnless { it === previousJob }"))
        assertTrue(await.contains("invokeOnCancellation"))
        assertTrue(await.contains("awaitedJob?.cancel("))
    }
}
