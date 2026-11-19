package com.openminis.app.ui.chat

import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.openminis.app.ui.components.minisJellyPress
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import com.openminis.app.ui.components.miuix.DropdownMenuItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.theme.ChatColors
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled

/**
 * Native Compose port of the Composer UI that used to live in the Xposed
 * module `MinisAllInOne` (Composer 1.9.19-test).
 *
 * What is ported here:
 *  - the effort selector, using the native Miuix Slider and tier buttons;
 *  - the merged "+" menu rows (attachments stay native, slash commands are
 *    appended below them) — see [ComposerCommandMenuRows];
 *  - small menu headings used by the merged menu.
 *
 * Deliberately NOT ported: the Xposed hooks, the obfuscated host symbol
 * table, `title.dex`, and the custom title View. The host already exposes
 * native Compose state and callbacks for those surfaces, and this port binds
 * to them instead of reflecting into them.
 */

/** Section heading inside the merged "+" menu. */
@Composable
internal fun ComposerMenuHeader(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = ChatColors.secondaryText,
        maxLines = 1,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

/**
 * [T-native-composer-bottom-bar] Bottom model + thinking-level toolbar.
 *
 * Upstream `ComposerViews.Toolbar` put two live items on the composer's
 * bottom row, right after "+": the active model name (tap = model picker)
 * and a compact chip for the current thinking level (tap = level sheet),
 * followed by flexible space. That row is what the old module's DEX-bound
 * build supplied; the previous native port only reused the top-bar subtitle
 * and therefore showed no bottom bar at all.
 *
 * This is the native replacement. It reads the SAME live state the host
 * already owns — `ChatViewModel.modelName`, `thinkingLevel`,
 * `availableThinkingLevels`, `currentModelSupportsReasoning` — and calls the
 * SAME native callbacks (`showModelPicker`, `showThinkingLevelSheet`). There is
 * no reflection, no DEX table and no host-symbol hash, so a host rebuild cannot
 * silently drop this bar the way the old module's hash gate did. A renamed or
 * re-signatured host API is still a code change: it shows up at compile time
 * or as a failed static contract, never as a quietly missing toolbar.
 *
 * Persistence and clamping stay in `ChatViewModel`.
 *
 * [groupLabel] keeps the group binding visible as a short auxiliary label. The
 * upstream bottom bar showed only the model, but removing the top-bar group row
 * without a replacement would read as "routing was lost"; the label is omitted
 * when no group is bound (a direct model pick), which is exactly when there is
 * nothing to show.
 *
 * Layout contract (mirrors upstream and survives narrow screens):
 *  - 30dp row, model name 13sp with 8dp side padding, one line, ellipsis;
 *  - [T-composer-level-follows-model] the level chip sits IMMEDIATELY to the
 *    right of the model name and travels with it. Two measurements make that
 *    safe without guessing pixel widths:
 *      • the chip is a non-weighted child, so Row measures it first at its
 *        intrinsic width and that width is reserved before the name is
 *        measured — the chip can never be pushed off the row;
 *      • the name is `weight(1f, fill = false)`, so a short name keeps its
 *        natural width (chip stays next to it, slack stays to the right) and
 *        a long name expands up to the space the chip left, ellipsising only
 *        past that point.
 *    The caller assigns the whole bar `weight(1f)` on the "+" row, so the
 *    slack a short name leaves is absorbed by the bar itself instead of by a
 *    trailing weight spacer competing for the same space.
 *  - level chip 13sp bold, 64dp minimum width (0dp when compact), 10dp side
 *    padding, 15dp corner radius, 13% alpha of the blue→violet level colour;
 *  - compact mode prints the English tier name in the same sentence case as
 *    `ThinkingLevel.displayName` (`Off` / `Low` / `Medium` / `High` / `X-High` /
 *    `Max` / `Ultra`) — the wording it maps to on the wire is lowercase, but the
 *    visible label is a label, not a wire token; the accessibility description
 *    stays localized;
 *  - non-reasoning model (or fewer than two levels): chip is dimmed to 45%,
 *    grey, and not clickable — same disabled look as upstream.
 *
 * [leading] carries native-only status the host already had on the top bar
 * (speed tier and quota dot) so moving model+thinking down does not silently
 * delete them.
 */
@Composable
internal fun ComposerModelThinkingBar(
    modelName: String,
    providerName: String,
    level: ThinkingLevel,
    availableLevels: List<ThinkingLevel>,
    reasoningSupported: Boolean,
    onModelClick: () -> Unit,
    onLevelClick: () -> Unit,
    modifier: Modifier = Modifier,
    modelInteractionSource: MutableInteractionSource? = null,
    groupLabel: String? = null,
    leading: @Composable () -> Unit = {},
    // [T-composer-inline-chips] Lays the chips out on the "+" row instead of
    // owning a full-width row of their own. Nothing is weighted or stretched
    // by the bar itself: the model name takes its natural width when short,
    // expands before ellipsising when long, and the level chip follows it
    // immediately. The caller gives the whole bar `weight(1f)` so the bar owns
    // the row's slack — a competing trailing weight spacer would split the
    // space with the name and cap a long name at half the row.
    compact: Boolean = false,
    // [T-composer-level-follows-model] Kept for callers that want the name
    // alone; the composer passes true so the level chip travels with the name
    // instead of being pinned to the mic slot.
    showLevel: Boolean = true,
) {
    val context = LocalContext.current
    // OFF is always offered (it switches thinking off); availableLevels never
    // contains OFF. De-duplicate defensively so a future caller cannot make
    // the slider / chip count disagree with the sheet.
    val rows = remember(availableLevels) {
        listOf(ThinkingLevel.OFF) + availableLevels.filter { it != ThinkingLevel.OFF }
    }
    val selectedIndex = remember(rows, level) {
        rows.indexOf(level).coerceAtLeast(0)
    }
    val levelEnabled = reasoningSupported && rows.size > 1
    val levelColor = if (levelEnabled) {
        Color(ComposerPortMath.levelColorArgb(selectedIndex, rows.size))
    } else {
        ChatColors.secondaryText
    }
    val levelLabel = level.localizedName(context)
    val modelLabel = modelName.ifBlank { providerName }.ifBlank { "选择模型" }

    Row(
        modifier = modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .heightIn(min = if (compact) 40.dp else 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Group binding, as a short auxiliary label: Layers glyph + the bound
        // group's name, truncated hard (56dp) so it cannot crowd out the model
        // name or the level chip on a narrow phone. Omitted for a direct model
        // pick, where no group is bound.
        if (!groupLabel.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics {
                    contentDescription = "当前分组 $groupLabel"
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = ChatColors.tertiaryText,
                    modifier = Modifier.size(10.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = groupLabel,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = ChatColors.tertiaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 56.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }

        leading()

        Text(
            text = modelLabel,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            color = ChatColors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                // fill=false on purpose: the name measures at its natural
                // width when short (so the chip stays glued to it) and is
                // capped only by the width the chip left, where it ellipsises.
                // A fill=true weight would park the name's clickable box
                // across the whole row and push the chip to the far edge.
                .weight(1f, fill = false)
                .minisJellyPress()
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (modelInteractionSource != null) {
                        Modifier.clickable(
                            interactionSource = modelInteractionSource,
                            indication = top.yukonga.miuix.kmp.utils.MiuixIndication(color = MiuixTheme.colorScheme.onSurface),
                            onClick = onModelClick,
                        )
                    } else {
                        Modifier.clickable(onClick = onModelClick)
                    },
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .semantics {
                    contentDescription = "选择模型，当前 $modelLabel"
                },
        )

        if (showLevel) {
            // 6dp gap plus the name's own 8dp end padding keeps the chip
            // visibly adjacent without crowding the ellipsis.
            Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))

            Box(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .minisJellyPress(levelEnabled)
                    .then(if (compact) Modifier.widthIn(min = 0.dp) else Modifier.widthIn(min = 64.dp))
                    .alpha(if (levelEnabled) 1f else 0.45f)
                    .clip(RoundedCornerShape(15.dp))
                    .background(levelColor.copy(alpha = 0.13f))
                    .clickable(enabled = levelEnabled, onClick = onLevelClick)
                    .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Compact prints the English tier (`Medium`, `X-High`, …)
                    // straight from displayName: it names the reasoning-effort
                    // level the request carries and matches the effort slider
                    // ticks, which use the same sentence case. Lowercasing it
                    // turned `X-High` into `x-high`, which reads awkwardly. The
                    // wider, non-compact toolbar keeps the localized name.
                    text = if (compact) level.displayName else levelLabel,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = levelColor,
                    maxLines = 1,
                    modifier = Modifier.semantics {
                        contentDescription = if (levelEnabled) {
                            "当前思考等级 $levelLabel，点击选择"
                        } else {
                            "当前模型不支持推理等级"
                        }
                    },
                )
            }
        }
    }
}

/**
 * Slash-command rows for the merged "+" menu. The caller keeps the native
 * attachment items above this block, so camera / photos / files continue to
 * use the exact launchers they used before.
 *
 * [onPick] receives the same [SlashCommand] objects the native "/" picker
 * uses; command dispatch stays in `ChatViewModel.executeSlashCommand`, so
 * clear / compact / memory / skill / MCP behavior is not reimplemented here.
 */
@Composable
internal fun ComposerCommandMenuRows(
    commands: List<SlashCommand>,
    onPick: (SlashCommand) -> Unit,
) {
    if (commands.isEmpty()) {
        DropdownMenuItem(
            text = { Text("No commands available", fontSize = 13.sp) },
            enabled = false,
            onClick = {},
        )
        return
    }
    commands.forEach { cmd ->
        DropdownMenuItem(
            text = {
                Column {
                    Text(
                        text = "/${cmd.title.lowercase()}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ChatColors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (cmd.subtitle.isNotBlank()) {
                        Text(
                            text = cmd.subtitle,
                            fontSize = 11.sp,
                            color = ChatColors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            leadingIcon = {
                Icon(
                    imageVector = cmd.icon,
                    contentDescription = null,
                    tint = ChatColors.primaryText,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = { onPick(cmd) },
        )
    }
}

/** Native Miuix discrete slider. Drafts commit on release; tier buttons wrap at large text. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ComposerEffortSlider(
    levels: List<ThinkingLevel>,
    current: ThinkingLevel,
    enabled: Boolean,
    onSelect: (ThinkingLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (levels.isEmpty()) return
    val labels = remember(levels) { levels.map { it.effortSliderLabel() } }
    val selectedIndex = levels.indexOf(current).takeIf { it >= 0 } ?: 0
    var draft by remember(levels, current) { mutableFloatStateOf(selectedIndex.toFloat()) }
    var pointerActive by remember { mutableStateOf(false) }
    var released by remember { mutableStateOf(false) }
    val canChange = enabled && levels.size > 1
    val maxIndex = (levels.size - 1).coerceAtLeast(1).toFloat()
    val commit = {
        if (canChange && released) onSelect(levels[draft.roundToInt().coerceIn(levels.indices)])
        else draft = selectedIndex.toFloat()
        released = false
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        top.yukonga.miuix.kmp.basic.Slider(
            value = draft,
            onValueChange = { value ->
                if (canChange) {
                    draft = value.roundToInt().coerceIn(levels.indices).toFloat()
                    // Native setProgress is an accessibility action, not a drag.
                    if (!pointerActive) onSelect(levels[draft.toInt()])
                }
            },
            enabled = canChange,
            valueRange = 0f..maxIndex,
            steps = (levels.size - 2).coerceAtLeast(0),
            keyPoints = levels.indices.map { it.toFloat() },
            showKeyPoints = true,
            onValueChangeFinished = commit,
            modifier = Modifier.fillMaxWidth()
                .semantics {
                    contentDescription = "Thinking effort: " + labels[selectedIndex]
                    if (!canChange) disabled()
                }
                .pointerInput(canChange, levels, current) {
                    if (!canChange) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        pointerActive = true
                        released = false
                        try {
                            do {
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change != null && change.previousPressed && !change.pressed) released = true
                            } while (event.changes.any { it.pressed })
                            // Native onDragStopped runs after UP. A cancelled gesture
                            // never satisfies the released guard and restores its draft.
                        } finally {
                            pointerActive = false
                            if (!released) draft = selectedIndex.toFloat()
                        }
                    }
                },
        )
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            levels.forEachIndexed { index, level ->
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = labels[index],
                    enabled = canChange,
                    onClick = { if (canChange) onSelect(level) },
                    colors = if (index == selectedIndex) top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary()
                        else top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(),
                )
            }
        }
    }
}

/**
 * Compact, always-English slider tick name (`Off`, `Low`, `X-High`, …).
 *
 * Sentence case, not lowercase: these ticks sit under the composer beside
 * every other sentence-case label in the app, and `xhigh` read as a typo of
 * a single lowercase word rather than as the name of a setting.
 */
private fun ThinkingLevel.effortSliderLabel(): String = displayName
