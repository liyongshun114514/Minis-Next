package com.openminis.app.ui.chat

// Adapted from Mangi-11/Eta aec537db, AgentWorkProcess/AgentToolActivity (GPL-3.0).
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import com.openminis.app.R
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.miuix.DropdownMenuItem
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong
import com.openminis.app.ui.util.expandToggleClickable
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val EtaWorkRowHeight = 30.dp
internal val EtaWorkGutterWidth = 18.dp
internal val EtaWorkGutterGap = 8.dp
internal enum class EtaWorkTone { Running, Done, Failed }

@Composable
internal fun EtaWorkHeader(item: FlatChatItem.AssistantWorkGroup, onToggle: () -> Unit) {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val summary = item.summary
    val working = item.active || item.runningTool != null
    val title = when {
        item.runningTool != null -> stringResource(R.string.eta_work_processing, summary.toolCount) +
            " · " + etaToolTitle(item.runningTool, resources::getString)
        working -> stringResource(R.string.eta_work_analyzing)
        else -> buildList {
            if (summary.thinkingCount > 0) add(stringResource(R.string.eta_work_thinking, summary.thinkingCount))
            if (summary.toolCount > 0) add(stringResource(R.string.eta_work_tools, summary.toolCount))
            if (summary.failedCount > 0) add(stringResource(R.string.tool_fold_n_failed, summary.failedCount))
        }.joinToString(" · ")
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
            .heightIn(min = EtaWorkRowHeight).clip(RoundedCornerShape(8.dp))
            .expandToggleClickable(onClick = onToggle).testTag("eta-work:${item.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(EtaWorkGutterWidth), contentAlignment = Alignment.Center) {
            Icon(
                item.runningTool?.let { toolIconFor(it.toolName) }
                    ?: if (working) Icons.Rounded.Lightbulb else Icons.Rounded.Checklist,
                contentDescription = null, modifier = Modifier.size(14.5.dp),
                tint = if (working) MiuixTheme.colorScheme.primary else etaMutedInk(0.45f, 0.50f),
            )
        }
        Spacer(Modifier.width(EtaWorkGutterGap))
        EtaShimmerText(title, working, modifier = Modifier.weight(1f, fill = false))
        EtaExpandChevron(item.expanded, Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
internal fun EtaWorkStep(
    item: FlatChatItem.AssistantWorkStep,
    onToggle: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onStop: ((String) -> Unit)? = null,
    onRerun: ((FlatChatItem.AssistantToolUse) -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    searchController: com.openminis.app.ui.chat.search.ChatSearchController? = null,
    onExpand: () -> Unit = {},
) {
    CompositionLocalProvider(LocalEtaWorkExpand provides onExpand) {
    val tools = item.members.filterIsInstance<FlatChatItem.AssistantToolUse>()
    val thinking = item.members.firstOrNull() as? FlatChatItem.AssistantThinking
    val running = tools.any { it.block.etaRunning() } ||
        (thinking != null && thinking.isLastBlockOverall && thinking.messageIsStreaming)
    val tone = when {
        running -> EtaWorkTone.Running
        tools.any { it.block.etaFailed() } -> EtaWorkTone.Failed
        else -> EtaWorkTone.Done
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("eta-step:${item.key}")) {
        EtaWorkTimelineNode(
            icon = if (thinking != null) Icons.Rounded.Lightbulb else toolIconFor(tools.first().block.toolName),
            tone = tone, isLast = item.isLast,
        ) {
            if (thinking != null) {
                ThinkingBlock(thinking.block,
                    isStreaming = thinking.isLastBlockOverall && thinking.messageIsStreaming,
                    isLast = thinking.isLast, compact = true)
            } else if (tools.size == 1) {
                val row = tools.single()
                EtaWorkTool(row, onOpenDetail, onStop, onRerun, onSearch, searchController)
            } else {
                val latest = tools.last().block
                val failed = tools.count { it.block.etaFailed() }
                val resources = androidx.compose.ui.platform.LocalContext.current.resources
                val name = etaToolName(latest.toolName, resources::getString)
                Column {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = EtaWorkRowHeight)
                            .clip(RoundedCornerShape(10.dp)).expandToggleClickable(onClick = onToggle)
                            .padding(vertical = 6.dp).testTag("eta-tool-group:${item.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            EtaShimmerText(stringResource(R.string.eta_work_tool_calls, name, tools.size), running)
                            val subtitle = when {
                                running -> displayToolTitle(latest)
                                failed > 0 -> stringResource(R.string.tool_fold_n_failed, failed)
                                else -> null
                            }
                            if (subtitle != null) Text(subtitle, fontSize = 11.sp,
                                color = if (!running && failed > 0) MiuixTheme.colorScheme.error else etaMutedInk(),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        EtaExpandChevron(item.expanded, Modifier.padding(start = 6.dp, end = 4.dp))
                    }
                    AnimatedVisibility(item.expanded, enter = etaExpandEnter(), exit = etaCollapseExit()) {
                        Column(Modifier.padding(bottom = 4.dp)) {
                            tools.forEach { row -> key(row.key) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Box(Modifier.padding(top = 13.dp, end = 8.dp).size(5.dp).clip(CircleShape)
                                        .background(if (row.block.etaFailed()) MiuixTheme.colorScheme.error else etaMutedInk()))
                                    EtaWorkTool(row, onOpenDetail, onStop, onRerun, onSearch, searchController, Modifier.weight(1f))
                                }
                            } }
                        }
                    }
                }
            }
        }
    }
    } // Expansion ownership belongs to the chat viewport.
}

@Composable
private fun EtaWorkTool(
    row: FlatChatItem.AssistantToolUse,
    onOpenDetail: (String) -> Unit,
    onStop: ((String) -> Unit)?,
    onRerun: ((FlatChatItem.AssistantToolUse) -> Unit)?,
    onSearch: (() -> Unit)?,
    searchController: com.openminis.app.ui.chat.search.ChatSearchController?,
    modifier: Modifier = Modifier,
) {
    val render: @Composable () -> Unit = {
        EtaToolActivityRow(row.block, onOpenDetail, onStop, modifier,
            onRerun = onRerun?.let { { it(row) } }, onSearch = onSearch)
    }
    if (searchController != null) {
        com.openminis.app.ui.chat.search.ChatSearchRowScope(searchController, row.key, content = render)
    } else render()
}

internal val LocalEtaWorkExpand = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
internal fun EtaWorkTimelineNode(
    icon: ImageVector, tone: EtaWorkTone, isLast: Boolean,
    content: @Composable () -> Unit,
) {
    val lineColor = etaMutedInk(0.085f, 0.12f)
    val halo = tone != EtaWorkTone.Done
    val ink = when (tone) {
        EtaWorkTone.Running -> MiuixTheme.colorScheme.primary
        EtaWorkTone.Failed -> MiuixTheme.colorScheme.error
        EtaWorkTone.Done -> etaMutedInk(0.40f, 0.46f)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val tick = if (tone == EtaWorkTone.Running && !animationsDisabled(context)) rememberDecorativeTick() else null
    Row(Modifier.fillMaxWidth().drawBehind {
        val x = EtaWorkGutterWidth.toPx() / 2f
        val visual = (if (halo) 18.dp else 13.5.dp).toPx()
        val gap = (if (halo) 2.5.dp else 3.5.dp).toPx()
        val top = (EtaWorkRowHeight.toPx() - visual) / 2f
        if (top > gap) drawLine(lineColor, Offset(x, 0f), Offset(x, top - gap), 1.dp.toPx(), StrokeCap.Round)
        val bottom = top + visual + gap
        if (!isLast && size.height > bottom) drawLine(lineColor, Offset(x, bottom), Offset(x, size.height), 1.dp.toPx(), StrokeCap.Round)
    }) {
        Box(Modifier.width(EtaWorkGutterWidth).height(EtaWorkRowHeight), contentAlignment = Alignment.Center) {
            Box(Modifier.size(if (halo) 18.dp else 13.5.dp)
                .graphicsLayer { alpha = tick?.let { 0.55f + 0.45f * decorativePingPong(decorativePhase(it.value, 1640)) } ?: 1f }
                .clip(CircleShape).then(if (halo) Modifier.background(ink.copy(alpha = 0.10f)) else Modifier),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(if (halo) 12.dp else 13.5.dp))
            }
        }
        Spacer(Modifier.width(EtaWorkGutterGap))
        Box(Modifier.weight(1f)) { content() }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun EtaToolActivityRow(
    block: AssistantBlock,
    onOpenDetail: (String) -> Unit,
    onStop: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onRerun: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(block.id) { mutableStateOf(false) }
    var menu by remember(block.id) { mutableStateOf(false) }
    val running = block.etaRunning()
    val clipboard = LocalClipboardManager.current
    val outcome = remember(block) { etaToolOutcome(block) }
    val terminal = block.toolName == "shell_execute"
    val onExpand = LocalEtaWorkExpand.current
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val title = helperControlSummary(block) ?: remember(block.toolTitle, block.toolArgs, resources) {
        etaToolTitle(block, resources::getString)
    }
    val command = remember(block.toolArgs) {
        runCatching { org.json.JSONObject(block.toolArgs).optString("command") }.getOrDefault("")
    }
    val hasDetail = block.toolArgs.isNotBlank() || block.content.isNotBlank() || block.toolName == "browser_use"
    Column(modifier.fillMaxWidth().testTag("eta-tool:${block.id}")) {
        Box {
            Row(
                Modifier.fillMaxWidth().heightIn(min = EtaWorkRowHeight).clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = { if (hasDetail) { if (!expanded) onExpand(); expanded = !expanded } },
                        onLongClick = { menu = true },
                        indication = top.yukonga.miuix.kmp.utils.MiuixIndication(), interactionSource = null,
                    ).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    EtaShimmerText(title, running, searchable = true)
                    if (outcome != null) Text(
                        text = outcome,
                        fontSize = 11.sp, lineHeight = 16.sp,
                        fontFamily = if (terminal) FontFamily.Monospace else FontFamily.Default,
                        color = if (block.etaFailed()) MiuixTheme.colorScheme.error else etaMutedInk(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                if (running && onStop != null) {
                    MinisTextButton(stringResource(R.string.stop_tool), onClick = { onStop(block.id) },
                        modifier = Modifier.padding(start = 4.dp), minHeight = 30.dp)
                }
                if (hasDetail) EtaExpandChevron(expanded, Modifier.padding(start = 6.dp, end = 4.dp))
            }
            MinisMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_copy_details)) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    onClick = { menu = false; clipboard.setText(AnnotatedString(formatToolDetailsForClipboard(block))) },
                )
                if (onRerun != null) DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_rerun_from_here)) },
                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                    onClick = { menu = false; onRerun() },
                )
                if (onSearch != null) DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_search_in_chat)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    onClick = { menu = false; onSearch() },
                )
            }
        }
        AnimatedVisibility(expanded && hasDetail, enter = etaExpandEnter(), exit = etaCollapseExit()) {
            Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (command.isNotBlank()) EtaToolDetailText(command, true)
                else if (block.toolArgs.isNotBlank()) EtaToolDetailText(block.toolArgs.take(8000), false)
                if (block.content.isNotBlank()) EtaToolDetailText(block.content.take(8000), terminal)
                // Heavy output/media and agent controls stay hoisted in the existing
                // detail sheet; inline expansion composes only bounded text (Eta: 12 lines).
                MinisTextButton(stringResource(R.string.eta_work_full_details),
                    onClick = { onOpenDetail(block.id) }, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun EtaToolDetailText(text: String, monospace: Boolean) {
    SelectionContainer {
        Text(text, fontSize = 11.sp, lineHeight = 16.sp,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 12, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

@Composable
internal fun EtaShimmerText(
    text: String, active: Boolean, modifier: Modifier = Modifier,
    style: TextStyle = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp),
    searchable: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tick = if (active && !animationsDisabled(context)) rememberDecorativeTick() else null
    val highlight = MiuixTheme.colorScheme.onSurface
    val ink = if (active) highlight else etaMutedInk()
    val shimmer = if (tick == null) Modifier else Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = maxOf(size.width * 0.35f, 72.dp.toPx())
            val center = -band + (size.width + band * 2f) * decorativePhase(tick.value, 1600)
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, highlight, Color.Transparent),
                startX = center - band, endX = center + band), blendMode = BlendMode.SrcAtop)
        }
    val highlighted = if (searchable) com.openminis.app.ui.chat.search.rememberChatSearchHighlighted(
        text, com.openminis.app.ui.chat.search.ChatSearchUnits.BODY) else AnnotatedString(text)
    Text(highlighted, style = style, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(shimmer))
}

@Composable
internal fun EtaExpandChevron(expanded: Boolean, modifier: Modifier = Modifier) {
    val rotation = animateFloatAsState(if (expanded) 90f else 0f, label = "eta-chevron")
    Icon(Icons.Rounded.ChevronRight,
        contentDescription = stringResource(if (expanded) R.string.eta_work_collapse else R.string.eta_work_expand),
        modifier = modifier.size(13.dp).graphicsLayer { rotationZ = rotation.value },
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f))
}

@Composable
internal fun etaMutedInk(light: Float = 0.52f, dark: Float = 0.56f): Color {
    val colors = MiuixTheme.colorScheme
    return colors.onSurface.copy(alpha = if (colors.surface.luminance() < 0.5f) dark else light)
}

/** Port of Eta ThinkingTailWindow: four lines, bottom-aligned, fading at the top. */
@Composable
internal fun EtaThinkingTailWindow(enabled: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!enabled) { Box(modifier) { content() }; return }
    val maxHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        (84.sp * LocalMarkdownFontScale.current).toDp()
    }
    Box(modifier.heightIn(max = maxHeight).clipToBounds()
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.height >= maxHeight.toPx() - 1f) {
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black), blendMode = BlendMode.DstIn)
            }
        }) {
        Box(Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Bottom, unbounded = true)) { content() }
    }
}

internal fun etaExpandEnter() = fadeIn(tween(160)) + expandVertically(expandFrom = Alignment.Top)
internal fun etaCollapseExit() = fadeOut(tween(120)) + shrinkVertically(shrinkTowards = Alignment.Top)
