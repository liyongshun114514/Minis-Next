package com.openminis.app.ui.chat

/**
 * [T-port-fold-tools] Decides which runs of consecutive tool calls in one
 * assistant message the chat list folds into a single "Ran N tools · 9s" row.
 * Pure: it sees only a compact description of each block, so the rule is
 * unit-tested without the list. Originally ported from iOS; Android now
 * folds by terminal tool status rather than reply formatting or request horizon.
 *
 * A run starts at the first tool block and continues through
 * - more finished tool blocks (counted),
 * - thinking blocks (folded along, not counted),
 * - empty text blocks (a text block that has not received content yet),
 * - one-line remarks between tool calls ([Input.NOTE], see [markingNotes]):
 *   folded along, not counted.
 *
 * It ends at a non-empty text block, an info block, a sub agent / user_ask
 * block, an unfinished tool, or the end of the current snapshot. Every run
 * of at least [MINIMUM_TOOL_COUNT] finished tools folds, with or without a
 * later reply. An unfinished tool is never included in the group.
 *
 * Thinking right before the run's first tool (only empty text between them)
 * folds with the run too. Thinking after the last member stays outside, next
 * to the reply it leads into. One-line notes between tools are included.
 */
object ToolFoldPlanner {
    /** The minimum number of tool blocks in a run before it folds. */
    const val MINIMUM_TOOL_COUNT = 2

    enum class ToolOutcome { UNFINISHED, SUCCEEDED, FAILED, CANCELLED }

    /** What the planner needs to know about one block. */
    sealed class Input {
        data class Tool(val outcome: ToolOutcome) : Input()
        object Thinking : Input() { override fun toString() = "Thinking" }
        data class Text(val isEmpty: Boolean) : Input()
        /** A one-line text with a tool call after it - a remark, not the reply. */
        object Note : Input() { override fun toString() = "Note" }
        /** Always visible and ends a run: info rows, sub agent and user_ask blocks. */
        object Barrier : Input() { override fun toString() = "Barrier" }
    }

    data class Group(
        /** Indices of the folded blocks, first member through last member. */
        val range: IntRange,
        val toolCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
    )

    fun plan(blocks: List<Input>, enabled: Boolean): List<Group> {
        if (!enabled) return emptyList()
        val groups = mutableListOf<Group>()
        var start: Int? = null
        var leadThinking: Int? = null
        var lastMember = 0
        var tools = 0
        var failed = 0
        var cancelled = 0
        fun closeRun() {
            val s = start
            if (s != null && tools >= MINIMUM_TOOL_COUNT) {
                groups.add(Group(s..lastMember, tools, failed, cancelled))
            }
            start = null
            tools = 0; failed = 0; cancelled = 0
        }

        for ((i, block) in blocks.withIndex()) {
            when (block) {
                is Input.Barrier -> {
                    closeRun(); leadThinking = null
                }
                is Input.Tool -> {
                    if (block.outcome == ToolOutcome.UNFINISHED) {
                        closeRun(); leadThinking = null
                        continue
                    }
                    if (start == null) start = leadThinking ?: i
                    leadThinking = null
                    lastMember = i
                    tools += 1
                    if (block.outcome == ToolOutcome.FAILED) failed += 1
                    if (block.outcome == ToolOutcome.CANCELLED) cancelled += 1
                }
                is Input.Note -> {
                    // Only after a tool: a remark before the first call is not
                    // part of any run, and it separates thinking before it
                    // from the call after it.
                    val s = start
                    if (s == null) {
                        leadThinking = null
                        continue
                    }
                    lastMember = i
                }
                is Input.Thinking -> if (start == null && leadThinking == null) leadThinking = i
                is Input.Text -> {
                    if (block.isEmpty) continue
                    closeRun()
                    leadThinking = null
                }
            }
        }
        // Terminal calls do not need a later model reply to become foldable.
        closeRun()
        return groups
    }

    /**
     * Marks the one-line remarks a model writes between tool calls as
     * [Input.NOTE]: a non-empty text is a note when [isSingleLine] holds AND a
     * tool call comes after it. Everything after the last tool call is the
     * reply and is never a note.
     */
    fun markingNotes(inputs: List<Input>, isSingleLine: (Int) -> Boolean): List<Input> {
        val lastTool = inputs.indexOfLast { it is Input.Tool }
        if (lastTool < 0) return inputs
        val out = inputs.toMutableList()
        for (i in 0 until lastTool) {
            if (out[i] == Input.Text(isEmpty = false) && isSingleLine(i)) out[i] = Input.Note
        }
        return out
    }

    /** One line by line BREAKS: no newline between the first and last visible character. */
    fun isSingleLine(text: String): Boolean {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return true
        val last = text.indexOfLast { !it.isWhitespace() }
        for (i in first..last) if (text[i] == '\n' || text[i] == '\r') return false
        return true
    }

    // --- AssistantBlock adapters ---------------------------------------------

    /** The planner's view of one block. */
    fun input(block: AssistantBlock): Input = when (block.kind) {
        "text" -> Input.Text(isEmpty = block.content.none { !it.isWhitespace() })
        "thinking" -> Input.Thinking
        "tool_use" -> when {
            // iOS: delegate and user_ask blocks are never folded.
            com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(block.toolName) -> Input.Barrier
            block.toolName == com.openminis.app.agent.userask.UserAskPromptText.TOOL_NAME -> Input.Barrier
            else -> when (block.toolStatus) {
                ToolBlockStatus.SUCCESS -> Input.Tool(ToolOutcome.SUCCEEDED)
                // A timed-out call ended in failure; the capsule shows it as such.
                ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT -> Input.Tool(ToolOutcome.FAILED)
                ToolBlockStatus.CANCELLED -> Input.Tool(ToolOutcome.CANCELLED)
                ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING, null ->
                    Input.Tool(ToolOutcome.UNFINISHED)
            }
        }
        else -> Input.Barrier
    }

    /** The planner's view of a message's blocks, remarks marked as notes. */
    fun inputs(blocks: List<AssistantBlock>): List<Input> =
        markingNotes(blocks.map(::input)) { isSingleLine(blocks[it].content) }

    /** One folded run, as the list draws it. */
    data class FoldedRun(
        /**
         * Expanded-state key: the first TOOL's id (the provider's tool_use
         * id, which survives a reload), not a leading thinking block's.
         */
        val key: String,
        val firstBlockId: String,
        val memberIds: List<String>,
        val group: Group,
        /** Summed execution time of the folded calls; null when none recorded one. */
        val durationMs: Long?,
    )

    fun foldedRuns(blocks: List<AssistantBlock>): List<FoldedRun> =
        plan(inputs(blocks), enabled = true).map { g ->
            val members = blocks.subList(g.range.first, g.range.last + 1)
            val firstTool = members.firstOrNull { it.kind == "tool_use" } ?: members.first()
            val durations = members.filter { it.kind == "tool_use" && it.durationMs > 0 }.map { it.durationMs }
            FoldedRun(
                key = firstTool.id,
                firstBlockId = members.first().id,
                memberIds = members.map { it.id },
                group = g,
                durationMs = if (durations.isEmpty()) null else durations.sum(),
            )
        }

    /** Live and stored messages use the same terminal-status rule. */
    fun foldedRuns(message: ChatMessage): List<FoldedRun> = foldedRuns(message.toolBlocks)
}
