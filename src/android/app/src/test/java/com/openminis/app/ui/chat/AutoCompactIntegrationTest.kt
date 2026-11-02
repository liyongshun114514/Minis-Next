package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.data.CompactRequestBudget
import org.junit.Assert.*
import org.junit.Test

/** Executable budget tests plus guards over the real (Android-bound) VM wiring. */
class AutoCompactIntegrationTest {
    @Test fun `summary respects selected model window and output cap`() {
        assertEquals(8192, CompactRequestBudget.maxOutputTokens(128_000, 10_000, null))
        assertEquals(1024, CompactRequestBudget.maxOutputTokens(32_000, 1_000, 1024))
        assertEquals(300, CompactRequestBudget.maxOutputTokens(8_000, 7_700, 4096))
        assertEquals(256, CompactRequestBudget.maxOutputTokens(8_000, 7_744, null))
        assertEquals(8192, CompactRequestBudget.maxOutputTokens(Int.MAX_VALUE, 0, Int.MAX_VALUE))
    }

    @Test fun `oversize input splits before issuing an impossible request`() {
        for (input in listOf(7_745, 8_000, Int.MAX_VALUE)) {
            try {
                CompactRequestBudget.maxOutputTokens(8_000, input, null)
                fail("oversized input was accepted: $input")
            } catch (_: IllegalStateException) { }
        }
    }

    @Test fun `threshold is checked before trimming in both automatic paths`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val send = vm.substringAfter("private fun checkContextBeforeSend()").substringBefore("private enum class PreSendContextAction")
        assertTrue(send.indexOf("customCompactThresholdReached()") < send.indexOf("replanTrim(\"pre-send\""))
        assertTrue(send.contains("if (customThresholdCrossed) ContextPolicy.CheckResult.NEEDS_COMPACT"))
        val loop = vm.substringAfter("private suspend fun inLoopContextCheck(").substringBefore("private fun settleWithoutCompacting(")
        assertTrue(loop.indexOf("customCompactThresholdReached()") < loop.indexOf("replanTrim(\"in-loop\""))
        assertTrue(loop.contains("!customThresholdCrossed && summaryWouldFreeLittle(tokens)"))
        assertTrue(loop.contains("compactionsSoFar < maxInLoopCompactions"))
        assertTrue(loop.contains("!lastInLoopCompactionMadeNoProgress"))
        assertTrue(vm.contains("AutoCompactPolicy.apply("))
        assertTrue(vm.contains("measureOutboundContextTokens() >= policy.compactThreshold"))
    }

    @Test fun `explicit model resolves locally and recursive segments retain it`() {
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val resolve = vm.substringAfter("private suspend fun resolveCompactProvider(").substringBefore("private suspend fun generateCompactSummary(")
        assertTrue(resolve.contains("requestCredential(instance)"))
        assertTrue(resolve.contains("instance.isEnabled && instance.providerType.isUsable && !entry.isHidden && entry.model.isTextOutput"))
        assertTrue(resolve.contains("overrides = entry.overrides"))
        assertFalse(resolve.contains("currentModel ="))
        assertFalse(resolve.contains("currentProvider ="))
        assertFalse(resolve.contains("_activeEntryId.value ="))
        assertTrue(vm.contains("if (compactEntryId == null) buildFallbackProviders(provider) else emptyList()"))
        assertTrue(vm.contains("generateCompactSummaryWithSplitting(firstHalf, null, depth + 1, compactProvider)"))
        assertTrue(vm.contains("generateCompactSummaryWithSplitting(secondHalf, null, depth + 1, compactProvider)"))
        assertTrue(vm.contains("generateCompactSummary(conversationText, compactProvider)"))
        val summary = vm.substringAfter("private suspend fun generateCompactSummary(").substringBefore("private fun isSegmentRetryableError(")
        assertTrue(summary.contains("val model = provider.model"))
        assertFalse(summary.contains("val model = currentModel"))
        assertFalse(summary.contains("val provider = currentProvider"))
        assertTrue(summary.contains("CompactRequestBudget.maxOutputTokens("))
    }
}
