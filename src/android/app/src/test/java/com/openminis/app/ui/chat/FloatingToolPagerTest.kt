package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import com.openminis.app.agent.jobs.HelperRunner
import org.junit.Assert.*
import org.junit.Test

class FloatingToolPagerTest {
    private fun tool(id: String, name: String, state: ToolBlockStatus) = AssistantBlock(
        id = id, kind = "tool_use", toolName = name, toolStatus = state,
    )
    @Test fun `active main tool wins over background delegate in either order`() {
        val main = tool("main", "shell_execute", ToolBlockStatus.RUNNING)
        val child = tool("child", HelperRunner.TOOL_NAME, ToolBlockStatus.RUNNING)
        assertEquals(0, floatingToolPreferredIndex(listOf(main, child)))
        assertEquals(1, floatingToolPreferredIndex(listOf(child, main)))
    }
    @Test fun `idle bar shows latest main result instead of jumping back to live child`() {
        val child = tool("child", HelperRunner.TOOL_NAME, ToolBlockStatus.RUNNING)
        val main = tool("main", "file_read", ToolBlockStatus.SUCCESS)
        assertEquals(1, floatingToolPreferredIndex(listOf(child, main)))
    }
    @Test fun `newest pending main tool wins and legacy delegates are excluded`() {
        val list = listOf(tool("a", "shell_execute", ToolBlockStatus.RUNNING),
            tool("b", "browser_use", ToolBlockStatus.PENDING),
            tool("c", HelperRunner.LEGACY_TOOL_NAME, ToolBlockStatus.RUNNING))
        assertEquals(1, floatingToolPreferredIndex(list))
        assertEquals(-1, floatingToolPreferredIndex(emptyList()))
    }
    @Test fun `selection survives child updates and appended tools until follow is restored`() {
        val main = tool("main", "file_read", ToolBlockStatus.SUCCESS)
        val child = tool("child", HelperRunner.TOOL_NAME, ToolBlockStatus.RUNNING)
        val next = tool("next", "shell_execute", ToolBlockStatus.RUNNING)
        assertEquals(0, floatingToolSelectedIndex(listOf(main, child), "main", false))
        assertEquals(0, floatingToolSelectedIndex(listOf(main, child, next), "main", false))
        assertEquals(1, floatingToolSelectedIndex(listOf(main, child, next), "child", false))
        assertEquals(2, floatingToolSelectedIndex(listOf(main, child, next), "main", true))
        assertEquals(2, floatingToolSelectedIndex(listOf(main, child, next), "removed", false))
        assertEquals(0, floatingToolPreferredIndex(listOf(main, child)))
    }
    @Test fun `manual paging uses stable ids and never status based snap back`() {
        val bar = ProductionSources.read("ui/chat/ChatComposerWidgets.kt")
        assertTrue(bar.contains("selectedToolId = it.id"))
        assertTrue(bar.contains("followLatest = false"))
        assertTrue(bar.contains("floatingToolSelectedIndex(toolBlocks, selectedToolId, followLatest)"))
        assertTrue(bar.contains("onClick = { selectTool(currentIndex - 1) }"))
        assertTrue(bar.contains("if (canStepNext) selectTool(currentIndex + 1)"))
        assertTrue(bar.contains("else followLatest = true"))
        assertTrue(bar.contains("remember(selectionScope)"))
        assertTrue(bar.contains("LaunchedEffect(selectionScope, selectedStillExists)"))
        val host = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(host.contains("selectionScope = sessionId to messages.lastOrNull { it.rendersAsUserBubble() }?.id"))
        assertTrue(bar.contains("onLongClick = { followLatest = true }"))
        assertFalse(bar.contains("LaunchedEffect(lastIndex, currentStatus)"))
        assertTrue(bar.contains("block = block,"))
    }
}
