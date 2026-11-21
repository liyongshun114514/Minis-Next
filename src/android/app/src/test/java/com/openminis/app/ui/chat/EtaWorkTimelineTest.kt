package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

/** Eta's process boundaries, manual overrides and same-tool projection on real flat rows. */
class EtaWorkTimelineTest {
    private fun think(id: String) = FlatChatItem.AssistantThinking("m",
        AssistantBlock(id, "thinking", content = "Reasoning $id"), true, false)
    private fun tool(id: String, name: String = "shell_execute", status: ToolBlockStatus = ToolBlockStatus.SUCCESS) =
        FlatChatItem.AssistantToolUse("m", AssistantBlock(id, "tool_use", toolName = name,
            toolStatus = status, toolTitle = "Run $id", toolArgs = "{\"command\":\"echo test\"}"), emptyList())
    private fun answer(id: String = "answer") = FlatChatItem.AssistantText("m",
        AssistantBlock(id, "text", "Answer"), false, "Answer")
    private fun msg(live: Boolean = false) = ChatMessage(id = "m", role = "assistant", content = "", isStreaming = live)
    private fun List<FlatChatItem>.groups() = filterIsInstance<FlatChatItem.AssistantWorkGroup>()
    private fun List<FlatChatItem>.steps() = filterIsInstance<FlatChatItem.AssistantWorkStep>()

    @Test fun reasoningAndToolsBecomeOneProcessAndKeepTheAnswerOutside() {
        val timeline = EtaWorkTimeline()
        val reply = answer()
        val rows = listOf(think("t1"), tool("a", status = ToolBlockStatus.FAILED), think("t2"),
            tool("b", status = ToolBlockStatus.TIMEOUT), think("t3"), reply)
        var out = timeline.project(rows, listOf(msg()))
        val group = out.groups().single()
        assertEquals(EtaWorkSummary(3, 2, 2), group.summary)
        assertFalse(group.expanded)
        assertTrue(out.steps().isEmpty())
        assertSame(reply, out.last())
        timeline.toggle(group.key, group.expanded)
        out = timeline.project(rows, listOf(msg()))
        assertEquals(5, out.steps().size)
        assertSame(rows[0], out.steps().first().members.single())
        assertTrue(out.steps().last().isLast)
        assertSame(reply, out.last())
    }

    @Test fun waitingBetweenReasoningAndToolDoesNotCollapseButAnswerDoes() {
        val timeline = EtaWorkTimeline()
        val thinking = think("t")
        val waiting = listOf(thinking, FlatChatItem.AssistantTyping("m"))
        val first = timeline.project(waiting, listOf(msg(true)), processing = true)
        assertTrue(first.groups().single().expanded)
        val tools = listOf(thinking, tool("a", status = ToolBlockStatus.PENDING))
        val second = timeline.project(tools, listOf(msg(true)), processing = true)
        assertTrue(second.groups().single().expanded)
        assertEquals(first.groups().single().key, second.groups().single().key)
        val withAnswer = timeline.project(tools + answer(), listOf(msg(true)), processing = true)
        assertFalse(withAnswer.groups().single().expanded)
    }

    @Test fun readerChoiceOverridesTheLifecycleBothWays() {
        val timeline = EtaWorkTimeline()
        val rows = listOf(think("t"), tool("a"))
        var group = timeline.project(rows, listOf(msg(true)), processing = true).groups().single()
        timeline.toggle(group.key, group.expanded)
        group = timeline.project(rows, listOf(msg(true)), processing = true).groups().single()
        assertFalse(group.expanded)
        timeline.toggle(group.key, group.expanded)
        group = timeline.project(rows + answer(), listOf(msg())).groups().single()
        assertTrue(group.expanded)
    }

    @Test fun consecutiveSameToolCallsGroupButBrowserAndReasoningRemainIndependent() {
        val timeline = EtaWorkTimeline()
        val rows = listOf(tool("a"), tool("b"), tool("c", "file_read"), tool("d", "browser_use"),
            tool("e", "browser_use"), think("t"), tool("f"))
        val out = timeline.project(rows, listOf(msg()), autoCollapse = false)
        assertEquals(listOf(2, 1, 1, 1, 1, 1), out.steps().map { it.members.size })
        assertEquals(6, out.groups().single().summary.toolCount)
    }

    @Test fun searchOpensTheProcessAndSameToolBatch() {
        val timeline = EtaWorkTimeline()
        val a = tool("a")
        val b = tool("b")
        val rows = listOf(a, b, answer())
        timeline.project(rows, listOf(msg()))
        assertTrue(timeline.expandForSearch(b.key))
        val out = timeline.project(rows, listOf(msg()))
        assertTrue(out.groups().single().expanded)
        assertTrue(out.steps().single().expanded)
        assertSame(b, out.steps().single().members.last())
        assertFalse(timeline.expandForSearch("missing"))
    }

    @Test fun stableWorkGroupSurvivesPagingAndNeverDuplicatesAKeyWhenSplit() {
        val timeline = EtaWorkTimeline()
        val first = think("start")
        val tail = think("tail")
        val a = timeline.project(listOf(tail), listOf(msg()))
        val b = timeline.project(listOf(first, tail), listOf(msg()))
        assertEquals(a.groups().single().key, b.groups().single().key)
        val split = timeline.project(listOf(first, answer(), tail), listOf(msg()))
        assertEquals(split.size, split.map { it.key }.distinct().size)
    }

    @Test fun unchangedProcessReusesItsSummaryAndNodeInstancesWhenAnswerGrows() {
        val timeline = EtaWorkTimeline()
        val rows = listOf(think("t"), tool("a"))
        val first = timeline.project(rows + answer(), listOf(msg()), autoCollapse = false)
        val second = timeline.project(rows + answer("growing"), listOf(msg()), autoCollapse = false)
        assertSame(first.groups().single(), second.groups().single())
        first.steps().zip(second.steps()).forEach { (a, b) -> assertSame(a, b) }
    }

    @Test fun hiddenReasoningIsNotCountedAndInteractiveCardsStayOutside() {
        val timeline = EtaWorkTimeline()
        val question = tool("q", "user_ask")
        val agent = tool("agent", "subagent_task")
        val rows = listOf(think("hidden"), tool("a"), question, agent, tool("b"))
        val out = timeline.project(rows, listOf(msg()), thinkingEnabled = false)
        assertEquals(2, out.groups().size)
        assertTrue(out.groups().all { it.summary.thinkingCount == 0 })
        assertTrue(out.any { it === question })
        assertTrue(out.any { it === agent })
    }

    @Test fun toolTitleShowsCategoryDescriptionAndCommandTarget() {
        val string: (Int) -> String = { when (it) {
            com.openminis.app.R.string.eta_tool_terminal -> "Terminal"
            com.openminis.app.R.string.eta_tool_execute -> "Execute command"
            else -> "Tool"
        } }
        val base = tool("a").block.copy(toolTitle = "Check device")
        assertEquals("Terminal · Check device · Linux", etaToolTitle(base, string))
        assertEquals("Terminal · Check device · Android", etaToolTitle(base.copy(toolArgs = "{\"command\":\"android-device info\"}"), string))
        assertEquals("Terminal · Execute command", etaToolTitle(base.copy(toolTitle = "", toolArgs = "{"), string))
    }

    @Test fun outcomeUsesTerminalTailAndFailureReasonAndSuppressesTrivialSuccess() {
        val base = tool("a").block
        assertEquals("last line", etaToolOutcome(base.copy(content = "Completed\nfirst line\nlast line")))
        assertNull(etaToolOutcome(base.copy(content = "Completed")))
        assertNull(etaToolOutcome(base.copy(content = "partial output", toolStatus = ToolBlockStatus.RUNNING)))
        assertEquals("permission denied", etaToolOutcome(base.copy(content = "Failed · permission denied · code=1", toolStatus = ToolBlockStatus.FAILED)))
        assertEquals("No Linux environment", etaToolOutcome(base.copy(content = "{\"ok\":false,\"error\":{\"message\":\"No Linux environment\"}}", toolStatus = ToolBlockStatus.FAILED)))
    }
}
