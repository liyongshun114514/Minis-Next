package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which terminal tool runs fold into one row after the response ends,
 * independent of reply formatting and the timing of list updates.
 *
 * Block notation: T = succeeded tool, F = failed, C = cancelled, R = unfinished
 * (running / streaming), K = thinking, t = non-empty multi-line text, o =
 * non-empty ONE-line text, e = empty text, B = barrier (info / sub agent /
 * user_ask). Output: one "lo-hi:tools/failed/cancelled" per group, or "none".
 * Like the list, [show] runs markingNotes first.
 */
class ToolFoldPlannerTest {

    private fun parse(s: String): List<ToolFoldPlanner.Input> = s.map { c ->
        when (c) {
            'T' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.SUCCEEDED)
            'F' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.FAILED)
            'C' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.CANCELLED)
            'R' -> ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.UNFINISHED)
            'K' -> ToolFoldPlanner.Input.Thinking
            't', 'o' -> ToolFoldPlanner.Input.Text(isEmpty = false)
            'e' -> ToolFoldPlanner.Input.Text(isEmpty = true)
            else -> ToolFoldPlanner.Input.Barrier
        }
    }

    private fun show(s: String, enabled: Boolean = true, horizon: Int = s.length): String {
        val chars = s.take(horizon)
        val inputs = ToolFoldPlanner.markingNotes(parse(chars)) { chars[it] == 'o' }
        val g = ToolFoldPlanner.plan(inputs, enabled)
        if (g.isEmpty()) return "none"
        return g.joinToString(",") { "${it.range.first}-${it.range.last}:${it.toolCount}/${it.failedCount}/${it.cancelledCount}" }
    }

    private val cases = mapOf(
        "three" to ("TTTt" to "0-2:3/0/0"),
        "two" to ("TTt" to "0-1:2/0/0"),
        "one" to ("Tt" to "none"),
        "oneThink" to ("TKKt" to "none"),
        "leadText" to ("tTTTTt" to "1-4:4/0/0"),
        "interleaved" to ("KTKTTt" to "0-4:3/0/0"),
        "thinkOnly" to ("TKKTt" to "0-3:2/0/0"),
        "thinkMid" to ("TKTKTt" to "0-4:3/0/0"),
        "emptyMid" to ("TTeTt" to "0-3:3/0/0"),
        "infoMid" to ("TTBTTt" to "0-1:2/0/0,3-4:2/0/0"),
        "delegateSplit" to ("TTTBTTTt" to "0-2:3/0/0,4-6:3/0/0"),
        "noReply" to ("tTTTT" to "1-4:4/0/0"),
        "noReplyEmpty" to ("TTTe" to "0-2:3/0/0"),
        "running" to ("TTTRt" to "0-2:3/0/0"),
        "runningFirst" to ("RTTTt" to "1-3:3/0/0"),
        "failCancel" to ("TFCTt" to "0-3:4/1/1"),
        "twoRuns" to ("TTTtTTTTt" to "0-2:3/0/0,4-7:4/0/0"),
        "trailingThink" to ("TTTKt" to "0-2:3/0/0"),
        "allThinking" to ("KKKt" to "none"),
        "noteExample" to ("ToTo" to "0-2:2/0/0"),
        "noteThenReply" to ("ToTt" to "0-2:2/0/0"),
        "notesLong" to ("ToToTTt" to "0-5:4/0/0"),
        "multiLineMid" to ("TTtTTt" to "0-1:2/0/0,3-4:2/0/0"),
        "noteLive" to ("TToR" to "0-2:2/0/0"),
        "noteNoReply" to ("TToTT" to "0-4:4/0/0"),
        "noteOneTool" to ("ToR" to "none"),
        "noteLead" to ("oTTt" to "1-2:2/0/0"),
        "noteBeforeBarrier" to ("TToBt" to "0-1:2/0/0"),
        "noteThink" to ("TKoTt" to "0-3:2/0/0"),
        "noteBeforeRunning" to ("TToTTR" to "0-4:4/0/0"),
        "leadThink" to ("KTTt" to "0-2:2/0/0"),
        "leadThinks" to ("KKeTTt" to "0-4:2/0/0"),
        "leadThinkAfterText" to ("tKTTt" to "1-3:2/0/0"),
        "leadThinkSecondRun" to ("TTtKTTt" to "0-1:2/0/0,3-5:2/0/0"),
        "leadThinkOneTool" to ("KTt" to "none"),
        "leadThinkNote" to ("KoTTt" to "2-3:2/0/0"),
        "leadThinkBarrier" to ("KBTTt" to "2-3:2/0/0"),
        "leadThinkRunning" to ("KRTTt" to "2-3:2/0/0"),
    )

    @Test
    fun `every terminal-run case gives the expected groups`() {
        for ((name, c) in cases) assertEquals(name, c.second, show(c.first))
    }

    @Test
    fun `threshold, setting off`() {
        assertEquals(2, ToolFoldPlanner.MINIMUM_TOOL_COUNT)
        assertEquals("none", show("TTTTt", enabled = false))
    }

    @Test
    fun `only single-line remarks are soft members and multiline replies stay visible`() {
        assertTrue(ToolFoldPlanner.isSingleLine("  Now checking the logs.  \n"))
        assertFalse(ToolFoldPlanner.isSingleLine("line one\nline two"))
        assertTrue(ToolFoldPlanner.isSingleLine("\n\nOne line after blank lines\n\n"))
        assertTrue(ToolFoldPlanner.isSingleLine(""))
        assertEquals("0-1:2/0/0,3-4:2/0/0", show("TTtTTt"))
        assertEquals("0-4:4/0/0", show("TToTTo"))
    }

    // --- AssistantBlock adapters and the live horizon ---------------------------

    private var n = 0
    private fun tool(status: ToolBlockStatus = ToolBlockStatus.SUCCESS, name: String = "shell_execute", ms: Long = 0) =
        AssistantBlock(id = "tu${n++}", kind = "tool_use", toolStatus = status, toolName = name, durationMs = ms)
    private fun text(s: String) = AssistantBlock(id = "x${n++}", kind = "text", content = s)
    private fun think() = AssistantBlock(id = "k${n++}", kind = "thinking", content = "hmm")

    @Test
    fun `block inputs - barriers, statuses and notes`() {
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(AssistantBlock(id = "i", kind = "info")))
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(AssistantBlock(id = "future", kind = "future_card", toolStatus = ToolBlockStatus.SUCCESS)))
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(tool(name = "subagent_task")))
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(tool(name = "delegate_task")))
        assertEquals(ToolFoldPlanner.Input.Barrier, ToolFoldPlanner.input(tool(name = "user_ask")))
        assertEquals(ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.FAILED), ToolFoldPlanner.input(tool(ToolBlockStatus.TIMEOUT)))
        for (s in listOf(ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING, null)) {
            assertEquals(ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.UNFINISHED), ToolFoldPlanner.input(tool(s ?: ToolBlockStatus.RUNNING).copy(toolStatus = s)))
        }
        assertEquals(ToolFoldPlanner.Input.Text(isEmpty = true), ToolFoldPlanner.input(text("  \n ")))
        val blocks = listOf(tool(), text("Now the logs."), tool(), text("Done.\nAll good."))
        assertEquals(ToolFoldPlanner.Input.Note, ToolFoldPlanner.inputs(blocks)[1])
        assertEquals(ToolFoldPlanner.Input.Text(isEmpty = false), ToolFoldPlanner.inputs(blocks)[3])
    }

    @Test
    fun `folded runs carry the first tool's id as key, members and summed time`() {
        val k = think()
        val a = tool(ms = 1200)
        val b = tool(ToolBlockStatus.FAILED, ms = 800)
        val reply = text("Done.\nAll good.")
        val runs = ToolFoldPlanner.foldedRuns(listOf(k, a, b, reply))
        assertEquals(1, runs.size)
        val r = runs[0]
        assertEquals(a.id, r.key)
        assertEquals(k.id, r.firstBlockId)
        assertEquals(listOf(k.id, a.id, b.id), r.memberIds)
        assertEquals(2000L, r.durationMs)
        assertEquals(1, r.group.failedCount)
        // No recorded time at all: no duration.
        assertEquals(null, ToolFoldPlanner.foldedRuns(listOf(tool(), tool(), reply)).single().durationMs)
    }

    @Test
    fun `only ended responses fold regardless of reply line count`() {
        val a = tool(); val b = tool()
        for (reply in listOf(emptyList(), listOf(text("")), listOf(text("Next.")), listOf(text("Done.\nOk.")))) {
            val blocks = listOf(a, b) + reply
            val live = ChatMessage(id = "m", role = "assistant", content = "", isStreaming = true, toolBlocks = blocks)
            assertTrue(ToolFoldPlanner.foldedRuns(live).isEmpty())
            val ended = live.copy(isStreaming = false)
            assertEquals(1, ToolFoldPlanner.foldedRuns(ended).size)
            assertTrue(ToolFoldPlanner.foldedRuns(ended.copy(isAwaitingModelResponse = true)).isEmpty())
        }
    }

    @Test
    fun `all unfinished statuses remain separate while completed prefix folds`() {
        val a = tool(); val b = tool(ToolBlockStatus.FAILED)
        for (status in listOf(ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING, null)) {
            val active = tool().copy(toolStatus = status)
            val blocks = listOf(a, b, active)
            val run = ToolFoldPlanner.foldedRuns(blocks).single()
            assertEquals(listOf(a.id, b.id), run.memberIds)
            val msg = ChatMessage(id = "m", role = "assistant", content = "", isStreaming = true, toolBlocks = blocks)
            val rows = applyToolFolds(rowsFor(msg), mapOf(msg.id to listOf(run)), emptySet())
            assertTrue(rows.any { it is FlatChatItem.AssistantToolUse && it.block.id == active.id })
        }
    }

    @Test
    fun `deterministic mixed snapshots never hide active calls or barriers`() {
        val random = java.util.Random(55L)
        repeat(300) {
            val blocks = List(2 + random.nextInt(35)) {
                when (random.nextInt(8)) {
                    0 -> tool(ToolBlockStatus.RUNNING)
                    1 -> tool(ToolBlockStatus.STREAMING)
                    2 -> tool(ToolBlockStatus.SUCCESS)
                    3 -> tool(ToolBlockStatus.FAILED)
                    4 -> tool(ToolBlockStatus.CANCELLED)
                    5 -> think()
                    6 -> text("Progress.")
                    else -> AssistantBlock(id = "barrier${n++}", kind = "info", content = "Notice")
                }
            }
            val runs = ToolFoldPlanner.foldedRuns(blocks)
            val members = HashSet<String>()
            for (run in runs) {
                assertTrue(run.group.toolCount >= 2)
                for (id in run.memberIds) assertTrue("overlapping groups", members.add(id))
                val hidden = blocks.filter { it.id in run.memberIds }
                assertFalse(hidden.any { ToolFoldPlanner.input(it) == ToolFoldPlanner.Input.Barrier })
                assertFalse(hidden.any { ToolFoldPlanner.input(it) == ToolFoldPlanner.Input.Tool(ToolFoldPlanner.ToolOutcome.UNFINISHED) })
                assertEquals(hidden.count { it.kind == "tool_use" }, run.group.toolCount)
            }
            val live = ChatMessage(id = "m", role = "assistant", content = "", isStreaming = true, toolBlocks = blocks)
            assertTrue(ToolFoldPlanner.foldedRuns(live).isEmpty())
            assertEquals(runs, ToolFoldPlanner.foldedRuns(live.copy(isStreaming = false)))
        }
    }

    // --- Rows ------------------------------------------------------------------

    private fun rowsFor(m: ChatMessage) = buildFlatChatItems(listOf(m))

    @Test
    fun `a folded run becomes one group row where its first member was, members back when expanded`() {
        val k = think(); val a = tool(ms = 1000); val b = tool(ms = 2000); val reply = text("Done.\nAll good.")
        val m = ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = listOf(k, a, b, reply))
        val rows = rowsFor(m)
        val runs = mapOf("m" to ToolFoldPlanner.foldedRuns(m.toolBlocks))
        val folded = applyToolFolds(rows, runs, emptySet())
        val keys = folded.map { it.key }
        assertTrue(keys.toString(), "toolgroup:m:${a.id}" in keys)
        assertFalse("thinking:m:${k.id}" in keys || "tool:m:${a.id}" in keys || "tool:m:${b.id}" in keys)
        assertTrue(keys.any { it.startsWith("mdblock:m:${reply.id}") })
        assertEquals(keys.indexOf("toolgroup:m:${a.id}"), rows.indexOfFirst { it.key == "thinking:m:${k.id}" })
        val g = folded.filterIsInstance<FlatChatItem.AssistantToolGroup>().single()
        assertEquals(2, g.toolCount); assertEquals(3000L, g.durationMs); assertFalse(g.expanded)

        val open = applyToolFolds(rows, runs, setOf(a.id)).map { it.key }
        val gi = open.indexOf("toolgroup:m:${a.id}")
        assertEquals(listOf("thinking:m:${k.id}", "tool:m:${a.id}", "tool:m:${b.id}"), open.subList(gi + 1, gi + 4))
        // Nothing to fold: the same list instance.
        assertSame(rows, applyToolFolds(rows, emptyMap(), emptySet()))
    }

    @Test
    fun `controller defers growing live runs then preserves manual expansion and reload`() {
        val a = tool(); val b = tool()
        val m = ChatMessage(id = "m", role = "assistant", content = "", isStreaming = true, toolBlocks = listOf(a, b))
        val rows = rowsFor(m)
        val c = ToolFoldController()
        assertSame(rows, c.fold(rows, listOf(m), emptyList(), processing = true))
        c.enabled = true
        assertSame(rows, c.fold(rows, listOf(m), emptyList(), processing = true))
        val active = tool(ToolBlockStatus.RUNNING)
        val growing = m.copy(toolBlocks = m.toolBlocks + active)
        val growingRows = rowsFor(growing)
        assertSame(growingRows, c.fold(growingRows, listOf(m), listOf(growing), processing = true))
        val finished = growing.copy(toolBlocks = growing.toolBlocks.dropLast(1) + active.copy(toolStatus = ToolBlockStatus.SUCCESS))
        val finishedRows = rowsFor(finished)
        assertSame(finishedRows, c.fold(finishedRows, listOf(m), listOf(finished), processing = true))
        val ended = finished.copy(isStreaming = false)
        val endedRows = rowsFor(ended)
        // Canonical drain may happen before the outer processing flag clears.
        assertSame(endedRows, c.fold(endedRows, listOf(ended), emptyList(), processing = true))
        val out = c.fold(endedRows, listOf(ended), emptyList(), processing = false)
        val group = out.filterIsInstance<FlatChatItem.AssistantToolGroup>().single()
        assertEquals(a.id, group.runKey)
        assertEquals(3, group.toolCount)
        assertFalse(group.expanded)
        val before = c.changes.value
        c.toggle(a.id)
        assertTrue(c.changes.value > before)
        assertTrue(c.fold(endedRows, listOf(ended), emptyList()).any { it.key == "tool:m:${a.id}" })
        c.enabled = false
        assertSame(endedRows, c.fold(endedRows, listOf(ended), emptyList()))
        c.enabled = true
        assertTrue(c.fold(endedRows, listOf(ended), emptyList()).filterIsInstance<FlatChatItem.AssistantToolGroup>().single().expanded)
        val fresh = ToolFoldController().apply { enabled = true }
        assertEquals(3, fresh.fold(endedRows, listOf(ended), emptyList()).filterIsInstance<FlatChatItem.AssistantToolGroup>().single().toolCount)
    }

    @Test
    fun `live overlay stays open and the final stored message folds`() {
        // The live overlay wins over the stale canonical copy; only the final
        // stored message becomes foldable after the loop ends.
        val a = tool(); val b = tool()
        val sep = text("Next.")
        val c = tool()
        val stored = ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = emptyList(), isStreaming = true)
        val live = stored.copy(toolBlocks = listOf(a, b, sep, c))
        val d = ToolFoldController().apply { enabled = true }
        val liveRows = rowsFor(live)
        assertSame(liveRows, d.fold(liveRows, listOf(stored), listOf(live), processing = true))
        val ended = live.copy(isStreaming = false)
        val out = d.fold(rowsFor(ended), listOf(ended), emptyList(), processing = false)
        assertTrue(out.map { it.key }.toString(), out.any { it is FlatChatItem.AssistantToolGroup && it.runKey == a.id })
    }

    @Test
    fun `ending a response invalidates cached no-fold plan with identical blocks`() {
        val blocks = listOf(tool(), tool())
        val live = ChatMessage(id = "m", role = "assistant", content = "", isStreaming = true, toolBlocks = blocks)
        val d = ToolFoldController().apply { enabled = true }
        val rows = rowsFor(live)
        assertSame(rows, d.fold(rows, listOf(live), emptyList()))
        val waiting = live.copy(isStreaming = false, isAwaitingModelResponse = true)
        assertSame(rows, d.fold(rows, listOf(waiting), emptyList()))
        val ended = waiting.copy(isAwaitingModelResponse = false)
        assertSame(blocks, ended.toolBlocks)
        assertEquals(1, d.fold(rows, listOf(ended), emptyList()).filterIsInstance<FlatChatItem.AssistantToolGroup>().size)
    }

    @Test
    fun `history stays folded during next turn while sealed current bubbles wait for its end`() {
        val history = ChatMessage(id = "old", role = "assistant", content = "", toolBlocks = listOf(tool(), tool()))
        val d = ToolFoldController().apply { enabled = true }
        assertEquals(1, d.fold(rowsFor(history), listOf(history), emptyList()).filterIsInstance<FlatChatItem.AssistantToolGroup>().size)
        // Global processing can become true before the next placeholder arrives.
        assertEquals(1, d.fold(rowsFor(history), listOf(history), emptyList(), processing = true).filterIsInstance<FlatChatItem.AssistantToolGroup>().size)
        val active = history.copy(id = "current", isStreaming = true, toolBlocks = listOf(tool(), tool()))
        val messages = listOf(history, active)
        val during = d.fold(buildFlatChatItems(messages), messages, listOf(active), processing = true)
        assertEquals(listOf("old"), during.filterIsInstance<FlatChatItem.AssistantToolGroup>().map { it.messageId })
        val sealed = active.copy(isStreaming = false)
        val next = active.copy(id = "after-compact", toolBlocks = emptyList())
        val swapped = listOf(history, sealed, next)
        val beforeEnd = d.fold(buildFlatChatItems(swapped), swapped, listOf(next), processing = true)
        assertEquals(listOf("old"), beforeEnd.filterIsInstance<FlatChatItem.AssistantToolGroup>().map { it.messageId })
        val ended = listOf(history, sealed, next.copy(isStreaming = false))
        val afterEnd = d.fold(buildFlatChatItems(ended), ended, emptyList(), processing = false)
        assertEquals(listOf("old", "current"), afterEnd.filterIsInstance<FlatChatItem.AssistantToolGroup>().map { it.messageId })
    }

    @Test
    fun `overlay presence defers folding even with finalized flags`() {
        val message = ChatMessage(id = "m", role = "assistant", content = "", toolBlocks = listOf(tool(), tool()))
        val d = ToolFoldController().apply { enabled = true }
        val rows = rowsFor(message)
        assertEquals(1, d.fold(rows, listOf(message), emptyList()).filterIsInstance<FlatChatItem.AssistantToolGroup>().size)
        assertSame(rows, d.fold(rows, listOf(message), listOf(message)))
        assertTrue(d.runKeyContaining(message.id, message.toolBlocks.first().id) == null)
        assertEquals(1, d.fold(rows, listOf(message), emptyList()).filterIsInstance<FlatChatItem.AssistantToolGroup>().size)
    }

    // --- Wiring ----------------------------------------------------------------

    @Test
    fun `the chat list folds its rows, renders and toggles the group row`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("flatItems = toolFold.fold("))
        assertTrue(screen.contains("toolFold.changes,"))
        val pipeline = screen.substringAfter("kotlinx.coroutines.flow.flowOf(messages),", "")
            .substringBefore("val tickStartNs")
        assertTrue(pipeline.contains("viewModel.isStreaming,"))
        assertTrue(pipeline.contains("Triple(msgs, stream, processing)"))
        assertTrue(screen.substringAfter("flatItems = toolFold.fold(").take(500).contains("processing = processing,"))
        // [T-user-ask-resolved-expand] The tap also anchors the row's top first (ToggleAnchorTest).
        val tap = screen.substringAfter("is FlatChatItem.AssistantToolGroup -> ToolGroupRow(item) {").substringBefore("\n                            }")
        assertTrue(tap.contains("toolFold.toggle(item.runKey)"))
        assertFalse(screen.contains("toolFold.browsing"))
        assertTrue(screen.contains("com.openminis.app.ui.settings.KEY_FOLD_TOOLS, com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT,"))
        assertTrue(screen.contains("sp.getBoolean(key, com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT)"))
        assertFalse(screen.contains("KEY_FOLD_TOOLS, false"))
    }

    @Test
    fun `the setting is on by default, in Appearance and the config registry`() {
        // [PAR-261022-05] Every read site goes through one default, so an
        // untouched install folds while a stored false is kept.
        assertTrue(com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT)
        val app = ProductionSources.read("ui/settings/AppearanceScreen.kt")
        assertTrue(app.contains("const val KEY_FOLD_TOOLS = \"fold_consecutive_tools\""))
        assertTrue(app.contains("mutableStateOf(prefs.getBoolean(KEY_FOLD_TOOLS, FOLD_TOOLS_DEFAULT))"))
        val reg = ProductionSources.read("config/ConfigBuiltins.kt")
        val entry = reg.substringAfter("path = \"chat.foldTools\"").substringBefore(")")
        assertTrue(entry.contains("key = com.openminis.app.ui.settings.KEY_FOLD_TOOLS"))
        assertTrue(entry.contains("defaultValue = com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT"))
    }

    @Test
    fun `every locale has the fold strings`() {
        val res = File(ProductionSources.mainRoot()!!, "../../../../res")
        val locales = listOf("values", "values-de", "values-es", "values-fil", "values-fr", "values-hr", "values-in", "values-ja",
            "values-ko", "values-ms", "values-pl", "values-pt-rBR", "values-ro", "values-ru", "values-th", "values-tr", "values-zh", "values-zh-rTW")
        val keys = listOf("tool_fold_setting_title", "tool_fold_section", "tool_fold_setting_footer", "tool_fold_ran_n_tools",
            "tool_fold_n_failed", "tool_fold_n_cancelled", "tool_fold_expand_hint", "tool_fold_collapse_hint")
        for (l in locales) {
            val f = File(res, "$l/strings_tool_fold.xml")
            assertTrue("missing ${f.path}", f.isFile)
            val text = f.readText()
            for (k in keys) assertTrue("$l lacks $k", text.contains("name=\"$k\""))
            assertTrue("$l count placeholder", text.contains("%1\$d"))
        }
        assertFalse(File(res, "values/strings_tool_fold.xml").readText().lowercase().contains("default"))
    }
}
