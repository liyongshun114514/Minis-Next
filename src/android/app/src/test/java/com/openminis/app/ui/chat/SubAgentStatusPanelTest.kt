package com.openminis.app.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.ProductionSources
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobOrigin
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.agent.jobs.AgentJobThen
import com.openminis.app.agent.jobs.AgentJobTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-subagent-status-panel] Pure behaviour of the read-only sub agent
 * status panel: which jobs it may show, in what order, what a row is allowed to
 * claim, and the height arithmetic that keeps the panel inside the screen.
 *
 * No Compose/Robolectric here on purpose — the panel's only decisions are pure
 * functions, and the composition itself adds nothing but layout that the
 * source guard at the bottom pins (`heightIn(max = …)`, a bounded LazyColumn,
 * no polling and no invented progress).
 */
class SubAgentStatusPanelTest {

    private val parent = "parent-1"

    private fun job(
        id: String,
        state: AgentJobState = AgentJobState.PENDING,
        parentSessionId: String = parent,
        toolUseId: String? = "tool-$id",
        createdAtMs: Long = 1_000L,
        startedAtMs: Long? = null,
        finishedAtMs: Long? = null,
        agentName: String? = null,
        title: String = "title $id",
        summaryLine: String? = null,
        origin: AgentJobOrigin = AgentJobOrigin.TOOL,
        target: AgentJobTarget = AgentJobTarget.ChildOfCurrent(parentSessionId, toolUseId),
        trigger: AgentJobTrigger = AgentJobTrigger.Immediate,
        then: AgentJobThen = AgentJobThen.FollowUpParent(null),
    ): AgentJob = AgentJob(
        id = id,
        label = null,
        title = title,
        origin = origin,
        trigger = trigger,
        target = target,
        prompt = null,
        then = then,
        state = state,
        agentName = agentName,
        summaryLine = summaryLine,
        createdAtMs = createdAtMs,
        startedAtMs = startedAtMs,
        finishedAtMs = finishedAtMs,
    )

    private fun selected(jobs: Collection<AgentJob>, p: String = parent): List<String> =
        subAgentStatusJobs(p, jobs).map { it.id }

    // ---------------------------------------------------------------- selection

    @Test
    fun `only child jobs of this parent are selected`() {
        val jobs = listOf(
            job("mine-1"),
            job("other-parent", parentSessionId = "parent-2"),
            job("follow-up", target = AgentJobTarget.FollowUp(parent)),
            job("rerun", target = AgentJobTarget.Rerun(parent, "m1")),
            job("brand-new", target = AgentJobTarget.New),
            job("mine-2", state = AgentJobState.RUNNING),
        )
        assertEquals(listOf("mine-2", "mine-1"), selected(jobs))
    }

    @Test
    fun `scheduled direct targets are excluded but a scheduled child of this parent is not`() {
        val direct = job(
            "sched-direct",
            origin = AgentJobOrigin.SCHEDULED,
            target = AgentJobTarget.FollowUp("some-other-session"),
            trigger = AgentJobTrigger.Once(60_000L),
        )
        val scheduledChild = job(
            "sched-child",
            origin = AgentJobOrigin.SCHEDULED,
            trigger = AgentJobTrigger.Loop(60_000L, null),
        )
        assertEquals(listOf("sched-child"), selected(listOf(direct, scheduledChild)))
    }

    @Test
    fun `a blank parent selects nothing`() {
        val jobs = listOf(job("a"), job("b", parentSessionId = ""))
        assertTrue(subAgentStatusJobs("", jobs).isEmpty())
        assertTrue(subAgentStatusJobs("   ", jobs).isEmpty())
    }

    @Test
    fun `jobs come back untouched, with their genuine registry state`() {
        val running = job("r", state = AgentJobState.RUNNING)
        val failed = job("f", state = AgentJobState.FAILED, finishedAtMs = 5_000L)
        val out = subAgentStatusJobs(parent, listOf(failed, running))
        assertEquals(listOf(AgentJobState.RUNNING, AgentJobState.FAILED), out.map { it.state })
        // Same instances: the panel must not re-derive a state of its own.
        assertSame(running, out[0])
        assertSame(failed, out[1])
    }

    // ----------------------------------------------------------------- ordering

    @Test
    fun `active jobs first, running before pending, then finished newest first`() {
        val jobs = listOf(
            job("pending", state = AgentJobState.PENDING, createdAtMs = 10L),
            job("running", state = AgentJobState.RUNNING, createdAtMs = 20L),
            job("done", state = AgentJobState.DONE, createdAtMs = 30L, finishedAtMs = 90L),
            job("failed", state = AgentJobState.FAILED, createdAtMs = 40L, finishedAtMs = 100L),
            job("cancelled", state = AgentJobState.CANCELLED, createdAtMs = 50L, finishedAtMs = 80L),
            job("timeout", state = AgentJobState.TIMEOUT, createdAtMs = 60L, finishedAtMs = 95L),
        )
        assertEquals(
            listOf("running", "pending", "failed", "timeout", "done", "cancelled"),
            selected(jobs),
        )
    }

    @Test
    fun `finished jobs are never dropped`() {
        val jobs = AgentJobState.values().map { state ->
            job(state.name.lowercase(), state = state, createdAtMs = 1_000L + state.ordinal)
        }
        val out = subAgentStatusJobs(parent, jobs)
        assertEquals(AgentJobState.values().size, out.size)
        assertEquals(
            AgentJobState.values().toSet(),
            out.map { it.state }.toSet(),
        )
    }

    @Test
    fun `ordering does not depend on the order the registry happened to hand over`() {
        val a = job("a", state = AgentJobState.RUNNING, createdAtMs = 2L)
        val b = job("b", state = AgentJobState.PENDING, createdAtMs = 1L)
        val c = job("c", state = AgentJobState.DONE, createdAtMs = 3L, finishedAtMs = 10L)
        val d = job("d", state = AgentJobState.FAILED, createdAtMs = 4L, finishedAtMs = 20L)
        val forward = selected(listOf(a, b, c, d))
        val backward = selected(listOf(d, c, b, a))
        assertEquals(forward, backward)
        assertEquals(listOf("a", "b", "d", "c"), forward)
    }

    @Test
    fun `identical timestamps fall back to the id so the list cannot jitter`() {
        val jobs = listOf(
            job("zeta", state = AgentJobState.RUNNING, createdAtMs = 7L),
            job("alpha", state = AgentJobState.RUNNING, createdAtMs = 7L),
        )
        assertEquals(listOf("alpha", "zeta"), selected(jobs))
        assertEquals(listOf("alpha", "zeta"), selected(jobs.reversed()))
    }

    // --------------------------------------------------------- row interaction

    @Test
    fun `a row is openable only when parentToolUseId is non-blank`() {
        assertEquals("tool-1", subAgentStatusOpenToolUseId(job("x", toolUseId = "tool-1")))
        assertEquals("tool-1", subAgentStatusOpenToolUseId(job("x", toolUseId = " tool-1 ")))
        assertNull(subAgentStatusOpenToolUseId(job("x", toolUseId = null)))
        assertNull(subAgentStatusOpenToolUseId(job("x", toolUseId = "   ")))
        // A row that is not a child at all can never be opened either.
        assertNull(subAgentStatusOpenToolUseId(job("x", target = AgentJobTarget.New)))
    }

    @Test
    fun `compacted or deleted tool blocks have no fake detail action`() {
        val child = job("x", toolUseId = "tool-x")
        assertEquals("tool-x", subAgentStatusOpenToolUseId(child, setOf("tool-x", "other")))
        assertNull(subAgentStatusOpenToolUseId(child, emptySet()))
        assertNull(subAgentStatusOpenToolUseId(child, setOf("other")))
    }

    @Test
    fun `host subscribes to current chat and constrains the complete overlay`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("AgentJobRegistry.jobs.collectAsState()"))
        assertTrue(screen.contains("subAgentStatusJobs(sessionId, registryJobs.values)"))
        assertTrue(screen.contains(".onGloballyPositioned { workAreaHeightPx = it.size.height }"))
        assertTrue(screen.contains(".heightIn(max = workAreaHeightDp)"))
        assertTrue(screen.contains("Modifier.clipToBounds()"))
        assertTrue(screen.contains("statusPanelMaxHeight >= SubAgentStatusPanelMinHeight"))
        assertTrue(screen.contains("toolsHeight + statusPanelHeightDp + panelGap"))
        val panel = screen.substringAfter("SubAgentStatusPanel(").substringBefore("if (lastToolBlocks.isNotEmpty()) FloatingToolStatusBar(")
        assertTrue(panel.contains("onOpenTool = { viewModel.openToolDetail(it) }"))
        assertTrue(panel.contains("availableToolUseIds = remember(lastToolBlocks)"))
        assertTrue(screen.contains("subAgentPanelHeightPx = 0"))
        assertTrue(screen.contains("val upBaseBottom = scrollControlsBottom"))
        assertTrue(screen.contains("val fabBottomPadding = scrollControlsBottom"))
    }

    @Test
    fun `summary is one truncated line of the real summary and null when absent`() {
        assertNull(subAgentStatusSummaryText(job("x", summaryLine = null)))
        assertNull(subAgentStatusSummaryText(job("x", summaryLine = "   ")))
        assertEquals(
            "Summary: tools 3 · turns 2 · tokens 1.2k",
            subAgentStatusSummaryText(job("x", summaryLine = " Summary: tools 3 · turns 2 · tokens 1.2k\n")),
        )
        assertEquals(
            "Summary: a b c",
            subAgentStatusSummaryText(job("x", summaryLine = "Summary: a\nb\tc")),
        )
        val long = subAgentStatusSummaryText(job("x", summaryLine = "x".repeat(500)))
        assertNotNull(long)
        assertEquals(SubAgentStatusSummaryMaxChars, long!!.length)
        assertTrue(long.endsWith("…"))
        assertEquals("…", subAgentStatusSummaryText(job("x", summaryLine = "abcdef"), maxChars = 1))
    }

    @Test
    fun `elapsed is a fixed finished duration and never a fake live timer`() {
        val running = job("r", state = AgentJobState.RUNNING, startedAtMs = 1_000L)
        assertNull(subAgentStatusFinishedDurationMs(running))
        val pending = job("p", state = AgentJobState.PENDING, startedAtMs = 1_000L)
        assertNull(subAgentStatusFinishedDurationMs(pending))
        val done = job(
            "d",
            state = AgentJobState.DONE,
            startedAtMs = 1_000L,
            finishedAtMs = 4_500L,
        )
        assertEquals(3_500L, subAgentStatusFinishedDurationMs(done)!!)
        val backwards = job(
            "b",
            state = AgentJobState.FAILED,
            startedAtMs = 9_000L,
            finishedAtMs = 4_000L,
        )
        assertEquals(0L, subAgentStatusFinishedDurationMs(backwards)!!)
        val noFinish = job("n", state = AgentJobState.TIMEOUT, startedAtMs = 1_000L)
        assertNull(subAgentStatusFinishedDurationMs(noFinish))
    }

    @Test
    fun `every registry state has its own label`() {
        val ids = AgentJobState.values().map { subAgentStatusStateLabelRes(it) }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(R.string.subagent_status_state_running, subAgentStatusStateLabelRes(AgentJobState.RUNNING))
        assertEquals(R.string.subagent_status_state_timeout, subAgentStatusStateLabelRes(AgentJobState.TIMEOUT))
    }

    // --------------------------------------------------------------- height cap

    private fun assertDp(expected: Dp, actual: Dp, label: String) {
        assertEquals(label, expected.value, actual.value, 0.001f)
    }

    @Test
    fun `height budget is half the area at most and never overlaps the toolbar`() {
        // Half the area wins on a tall screen.
        assertDp(300.dp, subAgentStatusHeightBudget(available = 600.dp, toolbarReserve = 100.dp), "half")
        // Toolbar reserve wins when it is tight: 600 - 560 - 6 = 34.
        assertDp(34.dp, subAgentStatusHeightBudget(available = 600.dp, toolbarReserve = 560.dp), "reserve")
        // Tall toolbar: floor at zero instead of a negative constraint.
        assertDp(0.dp, subAgentStatusHeightBudget(available = 600.dp, toolbarReserve = 600.dp), "equal")
        assertDp(0.dp, subAgentStatusHeightBudget(available = 600.dp, toolbarReserve = 700.dp), "taller")
        // Negative reserve is nonsense; clamp it rather than adding height.
        assertDp(300.dp, subAgentStatusHeightBudget(available = 600.dp, toolbarReserve = (-20).dp), "negative reserve")
        // Tiny but positive stays positive — the panel decides to hide.
        assertDp(9.dp, subAgentStatusHeightBudget(available = 60.dp, toolbarReserve = 45.dp), "tiny")
    }

    @Test
    fun `height budget stays a legal constraint for zero, negative and non-finite input`() {
        assertDp(0.dp, subAgentStatusHeightBudget(available = 0.dp, toolbarReserve = 0.dp), "zero")
        assertDp(0.dp, subAgentStatusHeightBudget(available = 0.dp, toolbarReserve = 80.dp), "zero available")
        assertDp(0.dp, subAgentStatusHeightBudget(available = (-50).dp, toolbarReserve = 0.dp), "negative")
        assertDp(0.dp, subAgentStatusHeightBudget(available = Dp.Infinity, toolbarReserve = 0.dp), "infinite available")
        assertDp(0.dp, subAgentStatusHeightBudget(available = 600.dp, toolbarReserve = Dp.Infinity), "infinite reserve")
        assertDp(0.dp, subAgentStatusHeightBudget(available = Dp.Infinity, toolbarReserve = Dp.Infinity), "both infinite")
        // A NaN would poison every downstream constraint; it must not survive.
        val nan = Dp(Float.NaN)
        assertDp(0.dp, subAgentStatusHeightBudget(available = nan, toolbarReserve = 10.dp), "nan available")
        assertDp(0.dp, subAgentStatusHeightBudget(available = 600.dp, toolbarReserve = nan), "nan reserve")
    }

    @Test
    fun `panel budget clamps a caller-supplied max height to something measurable`() {
        assertDp(240.dp, subAgentStatusPanelBudget(240.dp), "kept")
        assertDp(0.dp, subAgentStatusPanelBudget((-12).dp), "negative")
        assertDp(0.dp, subAgentStatusPanelBudget(Dp.Infinity), "infinite")
        assertDp(0.dp, subAgentStatusPanelBudget(Dp(Float.NaN)), "nan")
        assertTrue("panel hides below its collapsed header", 24.dp < SubAgentStatusPanelMinHeight)
    }

    // --------------------------------------------------------- strings + source

    private fun stringsFile(dir: String): File {
        val root = ProductionSources.mainRoot()
        assertNotNull("production sources not found", root)
        val res = File(root!!.path.substringBefore("/java/") + "/res")
        return File(res, "$dir/strings_subagent_status_panel.xml")
    }

    private fun strings(dir: String): Map<String, String> {
        val f = stringsFile(dir)
        assertTrue("missing ${f.path}", f.isFile)
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(f.readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    @Test
    fun `every panel string is translated and keeps its format arguments`() {
        val en = strings("values")
        val zh = strings("values-zh")
        assertEquals("English and Chinese must define the same keys", en.keys, zh.keys)
        assertTrue("unexpectedly few strings: ${en.keys}", en.size >= 13)
        for ((key, value) in en) {
            assertTrue("$key is empty in zh", zh.getValue(key).isNotBlank())
            val args = Regex("""%\d\$[sd]""").findAll(value).map { it.value }.toSet()
            for (arg in args) {
                assertTrue("zh/$key lost $arg", zh.getValue(key).contains(arg))
            }
        }
        assertEquals("子代理", zh["subagent_status_title"])
        assertTrue("counts must carry both numbers", en.getValue("subagent_status_counts").contains("%1\$d"))
        assertTrue(en.getValue("subagent_status_counts").contains("%2\$d"))
    }

    @Test
    fun `the panel is height-bounded, scrolls internally and never invents progress`() {
        val src = ProductionSources.read("ui/chat/SubAgentStatusPanel.kt")
        assertTrue("panel signature changed", src.contains("fun SubAgentStatusPanel("))
        assertTrue(src.contains("maxHeight: Dp"))
        assertTrue(src.contains("onOpenTool: (String) -> Unit"))
        // The single hard cap, and no min that could defeat it.
        assertTrue("the panel must clamp itself", src.contains(".heightIn(max = heightBudget)"))
        assertTrue("no min-height may exceed the cap", !src.contains("heightIn(min"))
        // List scrolls inside the panel instead of growing the panel.
        assertTrue(src.contains("LazyColumn("))
        // No polling, no registry subscription, no per-job ViewModel.
        assertFalse(src.contains("AgentJobRegistry"))
        assertFalse(src.contains("LaunchedEffect"))
        assertFalse(src.contains("delay("))
        // No invented progress percentage and no fake affordances.
        assertFalse(src.contains("LinearProgressIndicator"))
        assertFalse(src.contains("CircularProgressIndicator"))
        // Tap only through the real tool-block id.
        assertTrue(src.contains("subAgentStatusOpenToolUseId(job, availableToolUseIds)"))
    }
}
