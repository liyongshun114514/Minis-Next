package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.*
import org.junit.Test

/** Wiring guards complement the real-provider SSE and pure reducer tests. */
class ToolStreamLifecycleTest {
    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    @Test fun `live tool title uses the unicode aware single pass decoder`() {
        assertTrue(vm.contains("private fun unescapePartialJsonString(s: String): String = PartialJsonString.decode(s)"))
        assertTrue(vm.contains("extractPartialStringValue(\"tool_title\", chunk.accumulated)"))
    }
    @Test fun `complete without start constructs a card before updating it`() {
        val complete = vm.substringAfter("is LLMStreamChunk.ToolCallComplete ->").substringBefore("is LLMStreamChunk.Usage ->")
        assertTrue(complete.contains("if (allToolBlocks.none { it.id == toolCompleteId })"))
        assertTrue(complete.indexOf("allToolBlocks.add(AssistantBlock(") < complete.indexOf("val idx = allToolBlocks.indexOfFirst"))
    }
    @Test fun `uncompleted streams are failed paired and never executed`() {
        val boundary = vm.substringAfter("end while (!collectDone)").substringBefore("// Build assistant contentParts for history")
        assertTrue(boundary.contains("val completedStreamIds = toolCalls.map { it.first }.toSet()"))
        assertTrue(boundary.contains("incompleteStreamToolIds.add(b.id)"))
        assertTrue(boundary.contains("toolStatus = ToolBlockStatus.FAILED"))
        val dispatch = vm.substringAfter("if (id in incompleteStreamToolIds)").substringBefore("slots.withPermit")
        assertTrue(dispatch.contains("AgentContentPart.ToolResult("))
        assertTrue(dispatch.contains("id = id, name = name"))
        assertTrue(dispatch.contains("return@async"))
    }
    @Test fun `retry rollback resets stream identity counters alongside calls`() {
        val rollback = vm.substringAfter("// T307: SB-based per-turn accumulators reset.").substringBefore("continue  // retry on same provider")
        for (s in listOf("toolCalls.clear()", "dedupeStartCounts.clear()", "dedupeCompleteCounts.clear()", "inFlightRenamedId.clear()")) assertTrue(s, rollback.contains(s))
    }
    @Test fun `persisted tool uses retain received arguments when input cache is absent`() {
        assertTrue(vm.contains("toolCallInputs[block.id] ?: block.toolArgs.ifBlank { \"{}\" }"))
    }
}
