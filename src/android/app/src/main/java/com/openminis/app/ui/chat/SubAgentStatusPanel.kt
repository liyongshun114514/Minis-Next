package com.openminis.app.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.agent.jobs.HelperRunner
import com.openminis.app.ui.components.DecorativeSpinner
import com.openminis.app.ui.theme.ChatColors

// [T-android-subagent-status-panel] A READ-ONLY status viewer for the child
// (sub agent) jobs of the conversation on screen.
//
// Two halves, both controlled by the host:
//
//   * [SubAgentStatusToggle] — the compact entry. ONE line of text,
//     "Sub-agents N", where N is the number of ACTIVE jobs (running + queued).
//     The total is exposed to TalkBack only, because the entry has 27 dp of
//     room in the toolbar's right-hand dead space and a second number would
//     either wrap or shrink the tap target. It keeps no state of its own:
//     `expanded` comes in, `onToggle` goes out.
//   * [SubAgentStatusPanel] — the expanded LIST, and nothing else. It has no
//     header and no `remember` of its own; the host owns the expanded flag and
//     mounts the list above the whole floating tool bar when it is open.
//
// Rows reuse the transcript's own task card (TaskCardFrame / TaskCardTitleRow /
// TaskCardChip / TaskCardTitle / TaskCardStatusRow) so a sub agent reads the
// same here as it does as a [HelperToolBlock] in the conversation: purple
// accent tint + border, a round status disc, the agent badge + task title, then
// the real state, the real model (only when a finished block reported one) and
// the finished duration.
//
// Hard rules this file keeps:
//   * The list NEVER exceeds `maxHeight`. Negative or non-finite budgets hide
//     the list instead of producing an invalid constraint, and tiny budgets
//     clip rather than push a minimum height past the cap (nothing in this file
//     asks for a minimum height).
//   * The list is a LazyColumn bounded by its own heightIn AND by the host's
//     column, so there is no unbounded scrollable-inside-scrollable and no
//     nested-scroll fight with the chat.
//   * No progress percentage and no live timer: the registry has no progress
//     number and no ticker, so a row shows the genuine [AgentJobState] word, the
//     genuine model, and elapsed time only for a FINISHED run (a fixed number).
//   * The model is only ever the one a FINISHED helper block actually recorded
//     (`parseHelperBlock(block).finished?.model`). Nothing is inferred from the
//     agent name, the tier or the group.
//   * A row is openable only when the job can actually be opened: its
//     `ChildOfCurrent.parentToolUseId` must be non-blank AND still available in
//     the parent's detail viewer. Otherwise the card is passed `enabled = false`
//     — no ripple, no click semantics, no silent no-op callback.
//   * No cancel / steer affordances. The registry's stop paths belong to the
//     owning runner; a status panel must not pretend to own the run.

/**
 * Vertical gap the host should keep between the panel and the floating work
 * bar below it. Included inside [subAgentStatusHeightBudget] so callers that
 * subtract the budget from their layout never double-count it.
 */
internal val SubAgentStatusPanelGap = 6.dp

/**
 * Below this the list renders nothing. One task card is ~66 dp with its own
 * outer padding; showing a slice of it would be a clipped, unreadable row, so
 * "constrain or hide" resolves to hide here.
 */
internal val SubAgentStatusPanelMinHeight = 40.dp

/** Max characters of a job's real `summaryLine` kept in one line. */
internal const val SubAgentStatusSummaryMaxChars = 96

/**
 * Height budget for the panel, given the space the host actually has.
 *
 * `min(half of the available area, available − toolbar reserve − 6 dp gap)`,
 * floored at 0 so the result is always a legal constraint (never negative, and
 * never more than half the screen). Zero/negative/garbage inputs are safe:
 *
 *   * `available` not finite or ≤ 0            → 0 dp (list hides)
 *   * `toolbarReserve` not finite              → treated as "everything is
 *     reserved" → 0 dp
 *   * `toolbarReserve` negative                → clamped to 0
 *
 * The result counts the WHOLE list — every row and its padding — and the host
 * is expected to cap the wrapper at its real work area height.
 */
internal fun subAgentStatusHeightBudget(available: Dp, toolbarReserve: Dp): Dp {
    if (!available.value.isFinite() || available <= 0.dp) return 0.dp
    // A non-finite reserve would make `available - reserve` NaN; treat it as
    // "no room left" rather than propagating NaN into a layout constraint.
    val reserve = if (toolbarReserve.value.isFinite()) {
        toolbarReserve.coerceAtLeast(0.dp)
    } else {
        available
    }
    val half = available * 0.5f
    val belowToolbar = available - reserve - SubAgentStatusPanelGap
    return minOf(half, belowToolbar).coerceAtLeast(0.dp)
}

/**
 * Clamp a caller-provided `maxHeight` into something a LazyColumn can be
 * measured inside: finite and non-negative. A non-finite value would hand an
 * infinite max height to the scrollable child, which is exactly the crash this
 * panel must never cause.
 */
internal fun subAgentStatusPanelBudget(maxHeight: Dp): Dp =
    if (maxHeight.value.isFinite()) maxHeight.coerceAtLeast(0.dp) else 0.dp

/**
 * The child jobs of ONE parent conversation, for the status panel.
 *
 * Only `ChildOfCurrent` targets whose parent is [parentSessionId] qualify —
 * `New`, `FollowUp(sessionId)` and `Rerun(...)` are scheduled/direct runs in
 * some other conversation (or in a brand new one) and must not be advertised
 * here as if they belonged to this chat. A CLI child (`parentToolUseId == null`)
 * IS a child of this parent and is included; it is just not openable.
 *
 * Ordering is deterministic and stable:
 *   1. active (PENDING/RUNNING) before finished — what is happening now comes
 *      first,
 *   2. RUNNING before PENDING, then registration order (createdAtMs asc, id),
 *   3. finished after, newest finish first (finishedAtMs desc, then createdAtMs
 *      desc, id), so a run that just ended is visible without hunting.
 *
 * Finished jobs are never dropped: the panel is the record of what the
 * conversation delegated, and a limit on how many ended runs to keep is the
 * host's call, not a silent truncation here.
 */
fun subAgentStatusJobs(parentSessionId: String, jobs: Collection<AgentJob>): List<AgentJob> {
    if (parentSessionId.isBlank()) return emptyList()
    return jobs
        .asSequence()
        .filter { job ->
            val target = job.target as? AgentJobTarget.ChildOfCurrent
            target != null && target.parentSessionId == parentSessionId
        }
        .sortedWith(SubAgentStatusOrder)
        .toList()
}

private val SubAgentStatusOrder: Comparator<AgentJob> = Comparator { a, b ->
    if (a.isActive != b.isActive) return@Comparator if (a.isActive) -1 else 1
    if (a.isActive) {
        val byState = activeRank(a.state).compareTo(activeRank(b.state))
        if (byState != 0) byState else createdAtThenId(a, b)
    } else {
        // Newest finish first. Descending, so compare the OTHER side first.
        val byFinish = compareValues(finishedSortKey(b), finishedSortKey(a))
        if (byFinish != 0) byFinish else createdAtThenId(b, a)
    }
}

private fun activeRank(state: AgentJobState): Int = when (state) {
    AgentJobState.RUNNING -> 0
    AgentJobState.PENDING -> 1
    // Not reachable while `isActive` holds; keeps the mapping total.
    else -> 2
}

private fun finishedSortKey(job: AgentJob): Long = job.finishedAtMs ?: job.createdAtMs

private fun createdAtThenId(a: AgentJob, b: AgentJob): Int {
    val byCreated = a.createdAtMs.compareTo(b.createdAtMs)
    return if (byCreated != 0) byCreated else a.id.compareTo(b.id)
}

/**
 * The tool block a row may open, or null when tapping would have nowhere to
 * go. `parentToolUseId` is null for CLI children and blank for older rows;
 * both must stay non-interactive rather than calling back with a bogus id.
 */
internal fun subAgentStatusOpenToolUseId(
    job: AgentJob,
    availableToolUseIds: Set<String>? = null,
): String? =
    (job.target as? AgentJobTarget.ChildOfCurrent)
        ?.parentToolUseId
        ?.trim()
        ?.takeIf { it.isNotEmpty() && (availableToolUseIds == null || it in availableToolUseIds) }

/** The id of the tool block this job was delegated from, blank-safe. */
internal fun subAgentStatusToolUseId(job: AgentJob): String? =
    (job.target as? AgentJobTarget.ChildOfCurrent)
        ?.parentToolUseId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Number of jobs that are actually in flight — RUNNING plus queued (PENDING).
 * This is the number the compact entry prints; the registry snapshot is the
 * only source, so nothing is derived from timings or from the list order.
 */
internal fun subAgentStatusActiveCount(jobs: Collection<AgentJob>): Int = jobs.count { it.isActive }

/**
 * One-line rendering of the runner's REAL summary line (`Summary: tools … ·
 * turns … · tokens …`), or null when the runner never wrote one. Newlines and
 * runs of spaces collapse; long text is truncated with an ellipsis. Nothing is
 * synthesised from state or timings.
 */
internal fun subAgentStatusSummaryText(
    job: AgentJob,
    maxChars: Int = SubAgentStatusSummaryMaxChars,
): String? {
    val raw = job.summaryLine?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val single = raw.replace(Regex("\\s+"), " ")
    val limit = maxChars.coerceAtLeast(1)
    return if (single.length <= limit) single else single.take(limit - 1).trimEnd() + "…"
}

/**
 * Elapsed time of a FINISHED run in millis, or null when there is nothing
 * honest to show. Deliberately null while the job is active: without a ticker
 * the value would freeze at an arbitrary moment and read as a live timer that
 * is not running.
 */
internal fun subAgentStatusFinishedDurationMs(job: AgentJob): Long? {
    if (job.isActive) return null
    val start = job.startedAtMs ?: return null
    val end = job.finishedAtMs ?: return null
    return (end - start).coerceAtLeast(0L)
}

/** Localised word for a registry state — the genuine state, never derived. */
@StringRes
internal fun subAgentStatusStateLabelRes(state: AgentJobState): Int = when (state) {
    AgentJobState.PENDING -> R.string.subagent_status_state_pending
    AgentJobState.RUNNING -> R.string.subagent_status_state_running
    AgentJobState.DONE -> R.string.subagent_status_state_done
    AgentJobState.CANCELLED -> R.string.subagent_status_state_cancelled
    AgentJobState.FAILED -> R.string.subagent_status_state_failed
    AgentJobState.TIMEOUT -> R.string.subagent_status_state_timeout
}

/**
 * The completed run's status in the sub agent card's own vocabulary, so the
 * row can reuse the very glyph and colour [HelperToolBlock] draws for the same
 * outcome. RUNNING/PENDING are not "results" — they map to the live tokens the
 * card already understands, and the row branches on them before asking for a
 * glyph.
 */
internal fun subAgentStatusWireStatus(state: AgentJobState): String = when (state) {
    AgentJobState.DONE -> "completed"
    AgentJobState.CANCELLED -> "cancelled"
    AgentJobState.FAILED -> "failed"
    AgentJobState.TIMEOUT -> "timeout"
    AgentJobState.RUNNING -> "running"
    AgentJobState.PENDING -> "queued"
}

/**
 * The model each delegation's tool block actually REPORTED, keyed by block id.
 *
 * Only blocks written by the delegation tool itself are read
 * ([HelperRunner.isSubAgentToolName]) — every other tool's JSON is unrelated
 * and a stray `model_used` key in one of them must never become a sub agent's
 * model. Only a FINISHED helper block carries `model_used`; [parseHelperBlock]
 * already refuses to call a stale `status: running` finished, so an interrupted
 * run contributes nothing here instead of advertising a model it may never have
 * used. Blocks that are still running, that carry no model, or whose payload is
 * unreadable are simply absent from the map — the row then shows no model.
 */
internal fun subAgentStatusBlockModels(blocks: List<AssistantBlock>): Map<String, String> {
    if (blocks.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (block in blocks) {
        if (!HelperRunner.isSubAgentToolName(block.toolName)) continue
        val model = parseHelperBlock(block).finished?.model?.trim().orEmpty()
        if (model.isEmpty()) continue
        out.putIfAbsent(block.id, model)
    }
    return out
}

/** The real model for [job], or null when its block never reported one. */
internal fun subAgentStatusModelFor(job: AgentJob, models: Map<String, String>): String? {
    val id = subAgentStatusToolUseId(job) ?: return null
    return models[id]?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The compact entry: one line, `Sub-agents N` (N = active jobs), nothing else.
 *
 * Deliberately stateless. The host owns `expanded` and places this in the
 * toolbar's right-hand dead space; the list itself is a separate composable
 * mounted above the whole floating bar. The total is carried in the
 * accessibility description (and the expanded/collapsed state in
 * `stateDescription`) so a TalkBack user still learns how many runs the panel
 * is holding without the visible line growing a second number.
 *
 * @param jobs the ALREADY SELECTED rows (`subAgentStatusJobs(...)`); an empty
 *   list draws nothing at all — there is no such thing as a "0" entry.
 */
@Composable
fun SubAgentStatusToggle(
    jobs: List<AgentJob>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    contentStartPadding: Dp = 12.dp,
) {
    if (jobs.isEmpty()) return

    val active = subAgentStatusActiveCount(jobs)
    val total = jobs.size
    val expandLabel = stringResource(R.string.subagent_status_expand)
    val collapseLabel = stringResource(R.string.subagent_status_collapse)
    val expandedState = stringResource(R.string.subagent_status_expanded)
    val collapsedState = stringResource(R.string.subagent_status_collapsed)
    // Resolved out here: the semantics lambda is not composable.
    val description = stringResource(R.string.subagent_status_toggle_description, active, total)

    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier.fillMaxWidth()
            // A compact version of the work bar: same surface, border and
            // corners, equal to the work bar and half of the 65dp preview.
            .height(FloatingToolBarMetrics.BAR_HEIGHT)
            .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(shape)
            .background(ChatColors.inputBg, shape)
            .border(0.5.dp, ChatColors.toolBorder, shape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = if (expanded) expandedState else collapsedState
            }
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) collapseLabel else expandLabel,
                onClick = onToggle,
            )
            // Inset CONTENT only: keep the surface continuous under the preview.
            .padding(start = contentStartPadding, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Groups, contentDescription = null, tint = helperAccent(), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.subagent_status_toggle, active),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * The expanded list of child jobs — no header, no internal expanded state.
 *
 * @param jobs the ALREADY SELECTED rows, normally `subAgentStatusJobs(...)`
 *   applied by the host to the in-process job registry's StateFlow snapshot.
 *   The list renders them in the given order and does no filtering of its own.
 * @param maxHeight the whole list's cap (every row and its padding). Small or
 *   zero values hide the list; negative ones cannot produce a negative
 *   constraint.
 * @param onOpenTool invoked with the job's `parentToolUseId` when a row is
 *   actually openable. Never invoked for jobs without one.
 * @param availableToolUseIds IDs currently supported by the host detail
 *   viewer; compacted/deleted tool blocks remain visible as read-only statuses.
 * @param toolBlocks the conversation's tool blocks, used for ONE thing: the
 *   real model a finished delegation reported. Missing/partial blocks simply
 *   mean no model on that row — never a guess.
 * @param modifier host modifier; the host positions the list.
 */
@Composable
fun SubAgentStatusPanel(
    jobs: List<AgentJob>,
    maxHeight: Dp,
    onOpenTool: (String) -> Unit,
    availableToolUseIds: Set<String>,
    toolBlocks: List<AssistantBlock> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // Nothing to report, or no room to report it in. Both resolve to "draw
    // nothing"; the host's toggle still tells the user there are jobs.
    val heightBudget = subAgentStatusPanelBudget(maxHeight)
    if (jobs.isEmpty() || heightBudget < SubAgentStatusPanelMinHeight) return

    val models = remember(toolBlocks) { subAgentStatusBlockModels(toolBlocks) }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            // The single hard height gate. `heightIn` has no `min`, so a tiny
            // budget shrinks the list (clipped) instead of being overridden by
            // a row's natural height, and the LazyColumn is never handed an
            // infinite constraint.
            .heightIn(max = heightBudget),
        contentPadding = PaddingValues(bottom = 2.dp),
    ) {
        items(jobs, key = { it.id }) { job ->
            SubAgentStatusRow(
                job = job,
                model = subAgentStatusModelFor(job, models),
                onOpenTool = onOpenTool,
                availableToolUseIds = availableToolUseIds,
            )
        }
    }
}

/**
 * One child job, drawn with the transcript's own task card so it reads exactly
 * like the [HelperToolBlock] the delegation came from: purple tint + border,
 * round status disc, agent badge + title, then state · model · duration.
 *
 * The card is only interactive when [subAgentStatusOpenToolUseId] resolves;
 * otherwise it is passed `enabled = false` so there is no ripple, no click
 * semantics and no callback that would go nowhere.
 */
@Composable
private fun SubAgentStatusRow(
    job: AgentJob,
    model: String?,
    onOpenTool: (String) -> Unit,
    availableToolUseIds: Set<String>,
) {
    val accent = helperAccent()
    val stateLabel = stringResource(subAgentStatusStateLabelRes(job.state))
    val openToolUseId = subAgentStatusOpenToolUseId(job, availableToolUseIds)
    val openLabel = stringResource(R.string.subagent_status_open_tool)

    // The badge names the sub agent once the registry has a name for it; the
    // generic "Sub-agents" word until then. The title is the task the parent
    // asked for, and is never dropped — a card of badges says nothing.
    val agentName = job.agentName?.trim()?.takeIf { it.isNotEmpty() }
    val chip = agentName ?: stringResource(R.string.subagent_status_title)
    val title = job.title.trim().ifEmpty { chip }
    val stateColor = subAgentStatusStateColor(job.state)
    val wireStatus = subAgentStatusWireStatus(job.state)
    val duration = subAgentStatusFinishedDurationMs(job)
        ?.let { formatStepDuration(it / 1000, stillRunning = false) }

    TaskCardFrame(
        accent = accent,
        // A card that cannot be opened is passed enabled = false, so
        // TaskCardFrame itself drops the clickable: one clickable path in the
        // card, no ripple and no click semantics on a read-only row.
        enabled = openToolUseId != null,
        onClick = { openToolUseId?.let(onOpenTool) },
        onClickLabel = if (openToolUseId != null) openLabel else null,
        modifier = Modifier.semantics(mergeDescendants = true) {
            stateDescription = stateLabel
        },
        icon = {
            when (job.state) {
                // Live work: the same decorative spinner the transcript's card
                // uses. Not a progress indicator — the registry has no
                // percentage to show.
                AgentJobState.RUNNING -> DecorativeSpinner(
                    color = accent,
                    modifier = Modifier.size(TaskCardMetrics.iconGlyph),
                    strokeWidth = 2.dp,
                )
                // Waiting for a slot is paused work, so it wears the pause
                // glyph, not the generic Groups mark of a run that is starting.
                AgentJobState.PENDING -> Icon(
                    Icons.Filled.Pause,
                    contentDescription = null,
                    tint = stateColor,
                    modifier = Modifier.size(TaskCardMetrics.iconGlyph),
                )
                else -> Icon(
                    helperStatusGlyph(wireStatus),
                    contentDescription = null,
                    tint = stateColor,
                    modifier = Modifier.size(TaskCardMetrics.iconGlyph),
                )
            }
        },
    ) {
        TaskCardTitleRow {
            TaskCardChip(chip, accent)
            TaskCardTitle(title)
        }
        TaskCardStatusRow {
            Text(
                text = stateLabel,
                fontSize = TaskCardMetrics.subtitleSize,
                lineHeight = TaskCardMetrics.subtitleLineHeight,
                fontWeight = FontWeight.SemiBold,
                color = stateColor,
                maxLines = 1,
            )
            if (model != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = model,
                    fontSize = TaskCardMetrics.subtitleSize,
                    lineHeight = TaskCardMetrics.subtitleLineHeight,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.tertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Shrinks before the duration does; a long model id must
                    // not push the completion time off the card.
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (duration != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = duration,
                    fontSize = TaskCardMetrics.subtitleSize,
                    lineHeight = TaskCardMetrics.subtitleLineHeight,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.tertiaryText,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The state word's colour, taken from the same vocabulary the sub agent card
 * uses for a finished run. Live states are not results: a running job is the
 * panel's own accent (matching its spinner) and a queued one is neutral grey.
 */
@Composable
private fun subAgentStatusStateColor(state: AgentJobState): Color = when (state) {
    AgentJobState.RUNNING -> helperAccent()
    AgentJobState.PENDING -> ChatColors.secondaryText
    else -> helperStatusColor(subAgentStatusWireStatus(state))
}
