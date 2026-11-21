package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Adapted from Mangi-11/Eta aec537db (GPL-3.0): AgentChatBody.toTimelineEntries,
// AgentWorkSteps.toWorkSteps and AgentWorkProcess. Keep Minis' lazy text shards
// and tool execution model; only the presentation projection changes here.
internal data class EtaWorkSummary(val thinkingCount: Int, val toolCount: Int, val failedCount: Int)

internal fun AssistantBlock.etaRunning(): Boolean = when (toolStatus) {
    ToolBlockStatus.PENDING, ToolBlockStatus.STREAMING, ToolBlockStatus.RUNNING -> true
    else -> false
}
internal fun AssistantBlock.etaFailed(): Boolean =
    toolStatus == ToolBlockStatus.FAILED || toolStatus == ToolBlockStatus.TIMEOUT

/** Eta's single conclusion line, with Minis' raw shell/JSON results as inputs. */
internal fun etaToolOutcome(block: AssistantBlock): String? {
    if (block.etaRunning()) return null
    // Detail sheets retain the full result. Summary work stays bounded even for
    // multi-megabyte outputs; success uses the tail, failure uses its reason/head.
    val raw = (if (block.etaFailed()) block.content.take(16_000) else block.content.takeLast(16_000)).trim()
    if (raw.isEmpty()) return null
    val summary = if (raw.startsWith("{")) runCatching {
        val json = org.json.JSONObject(raw)
        val error = json.opt("error")
        when {
            error is org.json.JSONObject -> error.optString("message").ifBlank { error.optString("code") }
            error is String -> error
            else -> json.optString("message").ifBlank { json.optString("summary") }
        }.ifBlank { raw }
    }.getOrDefault(raw) else raw
    val lines = summary.lineSequence().map(String::trim).filter { it.isNotEmpty() && it != "…" }.toList()
    val head = lines.firstOrNull() ?: return null
    if (block.etaFailed() || block.toolStatus == ToolBlockStatus.CANCELLED) {
        val reason = listOf("失败 · ", "失敗 · ", "Failed · ").firstOrNull(head::startsWith)
            ?.let(head::removePrefix) ?: head
        return reason.substringBefore(" · code=").takeIf { it !in setOf("失败", "失敗", "Failed") }
            ?: lines.drop(1).lastOrNull()
    }
    val last = lines.last()
    if (last in setOf("完成", "执行完成", "執行完成", "Done", "Completed", "(no output)", "(empty output)")) return null
    return listOf("完成 · ", "Done · ", "Completed · ").firstOrNull(last::startsWith)
        ?.let(last::removePrefix)?.takeIf(String::isNotBlank) ?: last
}

/** Manual overrides survive the automatic running → answer transition, as in Eta. */
internal class EtaWorkTimeline {
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()
    private val manual = HashMap<String, Boolean>()
    private var previous: List<FlatChatItem> = emptyList()
    private val groupOfMember = HashMap<String, Pair<String, String?>>()

    fun invalidate() { _changes.value++ }

    fun toggle(key: String, currentlyExpanded: Boolean) {
        manual[key] = !currentlyExpanded
        _changes.value++
    }
    fun expandForSearch(rowKey: String): Boolean {
        val (group, batch) = groupOfMember[rowKey] ?: return false
        manual[group] = true
        if (batch != null) manual[batch] = true
        _changes.value++
        return true
    }

    fun project(
        rows: List<FlatChatItem>,
        messages: List<ChatMessage>,
        live: List<ChatMessage> = emptyList(),
        processing: Boolean = false,
        autoCollapse: Boolean = true,
        thinkingEnabled: Boolean = true,
    ): List<FlatChatItem> {
        val liveById = live.associateBy { it.id }
        val activeId = if (processing) messages.lastOrNull()?.takeIf { it.role == "assistant" }?.id else null
        val activeMessages = messages.asSequence().filter {
            val m = liveById[it.id] ?: it
            m.isStreaming || m.isAwaitingModelResponse || it.id == activeId
        }.mapTo(HashSet()) { it.id }
        val previousGroups = previous.filterIsInstance<FlatChatItem.AssistantWorkGroup>()
        val oldByMember = HashMap<String, FlatChatItem.AssistantWorkGroup>()
        previousGroups.forEach { group -> group.memberKeys.forEach { oldByMember[it] = group } }
        val oldRows = previous.associateBy { it.key }
        groupOfMember.clear()
        val out = ArrayList<FlatChatItem>(rows.size)
        val work = ArrayList<FlatChatItem>()
        val usedKeys = HashSet<String>()

        fun flush(next: FlatChatItem?) {
            if (work.isEmpty()) return
            val owner = work.first().foldMessageId()!!
            val keys = work.map { it.key }
            val keySet = keys.toHashSet()
            // Paging may prepend the beginning of a process. Retain the old key
            // only when all its members are still in this one group (no split collisions).
            val oldGroup = work.firstNotNullOfOrNull { oldByMember[it.key] }?.takeIf {
                it.memberKeys.all(keySet::contains) && it.key !in usedKeys
            }
            val groupKey = oldGroup?.key ?: "work:${work.first().key}"
            usedKeys.add(groupKey)
            val active = owner in activeMessages && (next == null || next is FlatChatItem.AssistantTyping)
            val expanded = manual[groupKey] ?: (!autoCollapse || active)
            val blocks = work.mapNotNull { row -> when (row) {
                is FlatChatItem.AssistantThinking -> row.block
                is FlatChatItem.AssistantToolUse -> row.block
                else -> null
            } }
            val summary = EtaWorkSummary(
                thinkingCount = blocks.count { it.kind == "thinking" },
                toolCount = blocks.count { it.kind == "tool_use" },
                failedCount = blocks.count { it.kind == "tool_use" && it.etaFailed() },
            )
            val group = FlatChatItem.AssistantWorkGroup(owner, groupKey, summary, active,
                blocks.lastOrNull { it.kind == "tool_use" && it.etaRunning() }, expanded, keys)
            out.add(if (oldGroup != null && oldGroup == group) oldGroup else group)
            val steps = ArrayList<List<FlatChatItem>>()
            val run = ArrayList<FlatChatItem>()
            fun flushRun() { if (run.isNotEmpty()) { steps.add(run.toList()); run.clear() } }
            work.forEach { row ->
                val tool = row as? FlatChatItem.AssistantToolUse
                // Eta leaves browser/subagent nodes independent. Interactive question
                // and delegation cards are barriers owned by their existing UI.
                if (tool != null && tool.block.toolName != "browser_use") {
                    val first = run.firstOrNull() as? FlatChatItem.AssistantToolUse
                    if (first != null && first.block.toolName != tool.block.toolName) flushRun()
                    run.add(row)
                } else { flushRun(); steps.add(listOf(row)) }
            }
            flushRun()
            steps.forEachIndexed { index, members ->
                val batchKey = "workstep:${members.first().key}"
                val batchExpanded = manual[batchKey] ?: false
                members.forEach { groupOfMember[it.key] = groupKey to batchKey.takeIf { members.size > 1 } }
                if (expanded) {
                    val step = FlatChatItem.AssistantWorkStep(owner, batchKey, members, index == steps.lastIndex, batchExpanded)
                    val old = oldRows[batchKey] as? FlatChatItem.AssistantWorkStep
                    out.add(if (old != null && old.isLast == step.isLast && old.expanded == step.expanded &&
                        old.members.size == members.size && members.indices.all { members[it] === old.members[it] }) old else step)
                }
            }
            work.clear()
        }

        rows.forEach { row ->
            val eligible = when (row) {
                is FlatChatItem.AssistantThinking -> row.messageThinkingLevel?.isEnabled ?: thinkingEnabled
                is FlatChatItem.AssistantToolUse ->
                    row.block.toolName != com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME &&
                    !com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(row.block.toolName)
                else -> false
            }
            // Hidden reasoning must not break or add a phantom node to a process.
            if (row is FlatChatItem.AssistantThinking && !eligible) return@forEach
            if (eligible) {
                if (work.isNotEmpty() && work.first().foldMessageId() != row.foldMessageId()) flush(row)
                work.add(row)
            } else { flush(row); out.add(row) }
        }
        flush(null)
        previous = out
        return out
    }
}
