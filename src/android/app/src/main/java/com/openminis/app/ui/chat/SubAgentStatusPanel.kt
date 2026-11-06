package com.openminis.app.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.jobs.AgentJob
import com.openminis.app.agent.jobs.AgentJobState
import com.openminis.app.agent.jobs.AgentJobTarget
import com.openminis.app.ui.theme.ChatColors

// [T-android-subagent-status-panel] A READ-ONLY status viewer for the child
// (sub agent) jobs of the conversation on screen.
//
// Placement: ChatScreen mounts it directly above the floating tool/work bar
// that sits above the composer, so the two never overlap. This file owns only
// the panel + the pure helpers; the host owns measurement (available area,
// toolbar reserve) and passes a `maxHeight`.
//
// Hard rules this file keeps:
//   * The panel NEVER exceeds `maxHeight`, collapsed OR expanded. maxHeight is
//     the WHOLE panel: header + list + every vertical padding. Negative or
//     non-finite budgets hide the panel instead of producing an invalid
//     constraint, and tiny budgets clip rather than push a min-height past the
//     cap (nothing in this file asks for a minimum height).
//   * The list is a LazyColumn whose height is bounded by the Column above it
//     (Column hands the remaining max height down), so there is no unbounded
//     scrollable-inside-scrollable and no nested-scroll fight with the chat.
//   * No progress percentage: the registry has no progress number, so showing
//     one would be invented. A row shows the genuine [AgentJobState] word, and
//     elapsed time only for a FINISHED run (a fixed duration). There is no
//     ticker, no polling, no per-job ViewModel.
//   * A row is tappable only when the job can actually be opened: its
//     `ChildOfCurrent.parentToolUseId` must be non-blank AND still available
//     in the parent's detail viewer. Otherwise there is no clickable
//     modifier, no ripple and no click semantics — no fake interaction.
//   * No cancel / steer affordances. The registry's stop paths belong to the
//     owning runner; a status panel must not pretend to own the run.

/**
 * Vertical gap the host should keep between the panel and the floating work
 * bar below it. Included inside [subAgentStatusHeightBudget] so callers that
 * subtract the budget from their layout never double-count it.
 */
internal val SubAgentStatusPanelGap = 6.dp

/**
 * Below this the panel renders nothing. The collapsed header is ~38–40 dp at
 * the default font scale; showing a slice of it would be a clipped, unusable
 * control, so "constrain or hide" resolves to hide here.
 */
internal val SubAgentStatusPanelMinHeight = 40.dp

/** Max characters of a job's real `summaryLine` kept in one row. */
internal const val SubAgentStatusSummaryMaxChars = 96

/**
 * Height budget for the panel, given the space the host actually has.
 *
 * `min(half of the available area, available − toolbar reserve − 6 dp gap)`,
 * floored at 0 so the result is always a legal constraint (never negative, and
 * never more than half the screen). Zero/negative/garbage inputs are safe:
 *
 *   * `available` not finite or ≤ 0            → 0 dp (panel hides)
 *   * `toolbarReserve` not finite              → treated as "everything is
 *     reserved" → 0 dp
 *   * `toolbarReserve` negative                → clamped to 0
 *
 * The result counts the WHOLE panel — header, list and all vertical padding —
 * and the host is expected to cap the wrapper at its real work area height.
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
 * Read-only sub agent status viewer.
 *
 * @param jobs the ALREADY SELECTED rows, normally `subAgentStatusJobs(...)`
 *   applied by the host to the in-process job registry's StateFlow snapshot.
 *   The panel renders them in the given order and does no filtering of its own.
 * @param maxHeight the whole panel's cap (header + list + all vertical
 *   padding). Small or zero values hide the panel; negative ones cannot
 *   produce a negative constraint.
 * @param onOpenTool invoked with the job's `parentToolUseId` when a row is
 *   actually openable. Never invoked for jobs without one.
 * @param availableToolUseIds IDs currently supported by the host detail
 *   viewer; compacted/deleted tool blocks remain visible as read-only statuses.
 * @param modifier host modifier; the host positions the panel.
 */
@Composable
fun SubAgentStatusPanel(
    jobs: List<AgentJob>,
    maxHeight: Dp,
    onOpenTool: (String) -> Unit,
    availableToolUseIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    // Nothing to report, or no room to report it in. Both resolve to "draw
    // nothing" — an empty shell above the toolbar would just be noise.
    val heightBudget = subAgentStatusPanelBudget(maxHeight)
    if (jobs.isEmpty() || heightBudget < SubAgentStatusPanelMinHeight) return

    var expanded by remember { mutableStateOf(false) }
    val activeCount = jobs.count { it.isActive }
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The single hard height gate. `heightIn` has no `min`, so a tiny
            // budget shrinks the panel (clipped by the shape) instead of being
            // overridden by the header's natural height. This also bounds the
            // LazyColumn below: Column passes the remaining max height down,
            // which is what makes the list scroll rather than overflow.
            .heightIn(max = heightBudget)
            .clip(shape)
            .background(ChatColors.inputBg, shape)
            .border(0.5.dp, ChatColors.toolBorder, shape),
    ) {
        SubAgentStatusHeader(
            activeCount = activeCount,
            totalCount = jobs.size,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                // Inside the budget like everything else.
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    SubAgentStatusRow(
                        job = job,
                        onOpenTool = onOpenTool,
                        availableToolUseIds = availableToolUseIds,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubAgentStatusHeader(
    activeCount: Int,
    totalCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val expandLabel = stringResource(R.string.subagent_status_expand)
    val collapseLabel = stringResource(R.string.subagent_status_collapse)
    val expandedState = stringResource(R.string.subagent_status_expanded)
    val collapsedState = stringResource(R.string.subagent_status_collapsed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // mergeDescendants first: the whole header is ONE TalkBack node —
            // "Sub-agents, 1 active · 3 total, Collapsed, button" — instead of
            // three separate announcements.
            .semantics(mergeDescendants = true) {
                stateDescription = if (expanded) expandedState else collapsedState
            }
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) collapseLabel else expandLabel,
                onClick = onToggle,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.subagent_status_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.subagent_status_counts, activeCount, totalCount),
            fontSize = 11.sp,
            color = ChatColors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null, // the header's state/action labels carry this
            tint = ChatColors.secondaryText,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SubAgentStatusRow(
    job: AgentJob,
    onOpenTool: (String) -> Unit,
    availableToolUseIds: Set<String>,
) {
    val stateLabel = stringResource(subAgentStatusStateLabelRes(job.state))
    val openToolUseId = subAgentStatusOpenToolUseId(job, availableToolUseIds)
    val openLabel = stringResource(R.string.subagent_status_open_tool)

    // Primary line: the named sub agent when there is one (that is what the
    // parent asked for), else the job's own title.
    val agentName = job.agentName?.trim()?.takeIf { it.isNotEmpty() }
    val primary = agentName ?: job.title
    val secondary = job.title.trim().takeIf { it.isNotEmpty() && it != primary }
    val summary = subAgentStatusSummaryText(job)
    val duration = subAgentStatusFinishedDurationMs(job)
        ?.let { formatStepDuration(it / 1000, stillRunning = false) }
    val stateColor = subAgentStatusStateColor(job.state)

    val rowModifier = Modifier
        .fillMaxWidth()
        // One announcement per row, and it includes the state word.
        .semantics(mergeDescendants = true) { stateDescription = stateLabel }
        .let { base ->
            // No click modifier at all when the row is not openable: no ripple,
            // no "double tap to activate", no silent no-op callback.
            if (openToolUseId != null) {
                base.clickable(role = Role.Button, onClickLabel = openLabel) {
                    onOpenTool(openToolUseId)
                }
            } else {
                base
            }
        }
        .padding(horizontal = 12.dp, vertical = 6.dp)

    Row(modifier = rowModifier, verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(7.dp)
                .background(stateColor, CircleShape),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    fontSize = 12.sp,
                    color = ChatColors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = 11.sp,
                    color = ChatColors.tertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = stateLabel,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = stateColor,
                maxLines = 1,
            )
            if (duration != null) {
                Text(
                    text = duration,
                    fontSize = 11.sp,
                    color = ChatColors.tertiaryText,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Reuses the tool-row state colours so the panel reads as part of chat. */
@Composable
private fun subAgentStatusStateColor(state: AgentJobState): Color = when (state) {
    AgentJobState.RUNNING -> ChatColors.thinking
    AgentJobState.PENDING -> ChatColors.secondaryText
    AgentJobState.DONE -> ToolCheckColor
    AgentJobState.CANCELLED -> ToolCancelColor
    AgentJobState.FAILED, AgentJobState.TIMEOUT -> ToolErrorColor
}
