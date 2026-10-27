package com.openminis.app.ui.chat

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * Native Compose port of the Composer UI that used to live in the Xposed
 * module `MinisAllInOne` (Composer 1.9.19-test).
 *
 * What is ported here:
 *  - the particle effort slider (originally `EffortSlider.java`, a custom
 *    Android View), now drawn with Compose Canvas;
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
 *  - compact mode prints the English tier name (`low` / `medium` / `high` /
 *    `xhigh` / `max` / `ultra` / `off`) because that is the value sent to the
 *    model; the accessibility description stays localized;
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
            .height(if (compact) 34.dp else 30.dp),
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
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (modelInteractionSource != null) {
                        Modifier.clickable(
                            interactionSource = modelInteractionSource,
                            indication = ripple(),
                            onClick = onModelClick,
                        )
                    } else {
                        Modifier.clickable(onClick = onModelClick)
                    },
                )
                .padding(horizontal = 8.dp)
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
                    .height(30.dp)
                    .then(if (compact) Modifier.widthIn(min = 0.dp) else Modifier.widthIn(min = 64.dp))
                    .alpha(if (levelEnabled) 1f else 0.45f)
                    .clip(RoundedCornerShape(15.dp))
                    .background(levelColor.copy(alpha = 0.13f))
                    .clickable(enabled = levelEnabled, onClick = onLevelClick)
                    .padding(horizontal = if (compact) 8.dp else 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Compact prints the English tier (`medium`, `xhigh`, …):
                    // it is the reasoning-effort value the request carries and
                    // matches what the sheet shows. The wider, non-compact
                    // toolbar keeps the localized name.
                    text = if (compact) level.displayName.lowercase() else levelLabel,
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

/**
 * One animation request for the effort slider: the level the thumb must rest
 * on, a serial that changes on every completed gesture (so a release always
 * replays the animation even when the level did not change), and the finger
 * position the settle animation starts from.
 */
private data class EffortSliderRequest(
    val index: Int,
    val serial: Int,
    val origin: Float?,
)

/**
 * Discrete particle slider for thinking effort.
 *
 * The visual model is a faithful port of the upstream Android View: a pill
 * track, a white round thumb that sits INSIDE the pill, blue -> violet ->
 * deep-purple fill, nebula + sweeping highlight + 22 deterministic stars, and
 * one dot per level with its English mode name centred underneath it.
 *
 * Geometry (track inset, dot positions, thumb radius, x -> level mapping) comes
 * from [ThinkingSliderGeometry] — the SAME [ThinkingSliderGeometry.Track] drives
 * the Canvas and the pointer handler, so what is drawn and what is hit-tested
 * cannot drift apart. The symmetric inset is derived from the widest label,
 * which keeps the first/last labels from being clipped while every dot keeps an
 * identical spacing.
 *
 * Animation is state driven, never per-recomposition:
 *  - a single [Animatable] (`progress`) is driven by
 *    `snapshotFlow { EffortSliderRequest(...) }`, so it plays on open
 *    (0 -> current), after a tap, after a drag (finger position -> committed
 *    dot, via `origin`), and when the level changes elsewhere (ViewModel
 *    clamp). The gesture itself only writes plain state (a restricted
 *    suspension context forbids calling Animatable.snapTo there);
 *  - the particle/sweep clock runs from `snapshotFlow` on `enabled`, so the
 *    field is alive at every level (including Off/Low) instead of waiting for
 *    the fill to have "energy" — the old bug: animation stopped for good after
 *    the first settle, and the low stops had zero stars.
 *
 * Interaction stays native-Compose: press anywhere on the track, drag, release
 * to commit exactly one level; a tap also commits. Leaving the sheet without
 * releasing (gesture cancelled) commits nothing and the thumb returns to the
 * committed level.
 *
 * [levels] must include OFF as the first entry (the sheet builds that list).
 * [onSelect] is called once per completed gesture with a level from [levels].
 */
@Composable
internal fun ComposerEffortSlider(
    levels: List<ThinkingLevel>,
    current: ThinkingLevel,
    enabled: Boolean,
    onSelect: (ThinkingLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val localDensity = LocalDensity.current
    val dark = ChatColors.isDark
    val reducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) <= 0f
        }.getOrDefault(false)
    }

    // Mode names are the wire values in English (off / low / medium / high /
    // xhigh / max / ultra) for exactly the levels this model supports. They are
    // deliberately NOT localised: they are what the request carries, they match
    // the compact chip, and seven short names are what lets every dot own a
    // readable label without squeezing the first/last one.
    val labels = remember(levels) { levels.map { it.effortSliderLabel() } }
    val count = levels.size

    val selectedIndex = remember(levels, current) {
        if (current == ThinkingLevel.OFF) {
            0
        } else {
            levels.indexOf(current).takeIf { it >= 0 }
                ?: levels.indexOfLast { it != ThinkingLevel.OFF }.coerceAtLeast(0)
        }
    }

    // The level the thumb should rest on. Written by the gesture on release and
    // by the ViewModel's truth whenever `current` changes, so an out-of-range or
    // clamped request animates back onto a level that really is selected.
    var requestedIndex by remember(levels) { mutableIntStateOf(selectedIndex) }
    var releaseSerial by remember(levels) { mutableIntStateOf(0) }
    var releaseOrigin by remember(levels) { mutableStateOf<Float?>(null) }
    // Non-null only while a gesture is in flight: the thumb follows the finger
    // without touching the animatable, so the settle animation can start from
    // exactly where the finger left off.
    var dragPosition by remember(levels) { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var particleClock by remember { mutableFloatStateOf(0f) }

    // Starts at Off so opening the sheet animates the fill in, then follows the
    // committed level. `progress` is written ONLY by the effect below.
    val progress = remember(levels) { Animatable(0f) }

    // Keyed on the animatable itself: a model switch can rebuild the level
    // list (new `remember(levels)` state) without changing its size, and the
    // pipeline must follow the NEW animatable rather than the dead one.
    LaunchedEffect(progress, count) {
        var lastSerial = -1
        snapshotFlow { EffortSliderRequest(requestedIndex, releaseSerial, releaseOrigin) }
            .distinctUntilChanged()
            .collectLatest { request ->
                if (request.serial != lastSerial) {
                    lastSerial = request.serial
                    // The animatable takes over at the finger's position, so
                    // dropping the drag value is invisible and the settle
                    // animation plays from there.
                    dragPosition = null
                    request.origin?.let { progress.snapTo(it) }
                }
                progress.animateTo(
                    targetValue = ComposerPortMath.pct(request.index, count),
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = 520f),
                )
            }
    }

    // External truth wins: opening the sheet, another surface changing the
    // level, or setThinkingLevel clamping a request all land here.
    LaunchedEffect(levels, current) {
        requestedIndex = selectedIndex
    }

    // Star/sweep clock. The bed is coloured at every level, so the field runs
    // whenever the slider is enabled — including Off and Low, which used to
    // have no "energy" and therefore a frozen frame.
    LaunchedEffect(enabled, reducedMotion, progress) {
        snapshotFlow { enabled }
            .distinctUntilChanged()
            .collectLatest { active ->
                if (!active) return@collectLatest
                var last = 0L
                while (true) {
                    val now = withFrameNanos { it }
                    if (last != 0L) {
                        particleClock += ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    }
                    last = now
                }
            }
    }

    val pressScale by animateFloatAsState(
        targetValue = if (dragging) ThinkingSliderGeometry.PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 900f),
        label = "effortThumbPress",
    )

    val labelSizePx = with(localDensity) { ThinkingSliderGeometry.LABEL_SIZE_SP.sp.toPx() }
    val barHeightPx = with(localDensity) { ThinkingSliderGeometry.TRACK_HEIGHT_DP.dp.toPx() }
    val edgePadPx = with(localDensity) { ThinkingSliderGeometry.TRACK_EDGE_PAD_DP.dp.toPx() }
    val thumbRadiusPx = ThinkingSliderGeometry.thumbRadiusPx(
        barHeightPx = barHeightPx,
        insetPx = with(localDensity) { ThinkingSliderGeometry.THUMB_INSET_DP.dp.toPx() },
        minRadiusPx = with(localDensity) { ThinkingSliderGeometry.MIN_THUMB_RADIUS_DP.dp.toPx() },
    )

    val labelPaint = remember(labelSizePx, dark) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelSizePx
            textAlign = android.graphics.Paint.Align.CENTER
            color = if (dark) 0xFF9C99A6.toInt() else 0xFF7C7A85.toInt()
            isSubpixelText = true
        }
    }
    val activeLabelPaint = remember(labelSizePx) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelSizePx
            textAlign = android.graphics.Paint.Align.CENTER
            isSubpixelText = true
        }
    }
    // Widest half-label: this is what sets the symmetric gutter, so the first
    // and last names are never clipped and every dot stays equally spaced.
    val labelHalfWidthPx = remember(labels, labelSizePx) {
        if (labels.isEmpty()) {
            0f
        } else {
            val probe = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = labelSizePx
            }
            labels.maxOf { probe.measureText(it) } / 2f
        }
    }

    // Colour is a fixed part of the control now: the bed is the same gradient
    // at every level, so the theme only tints the thumb shadow and the labels.
    val canvasHeightDp = ThinkingSliderGeometry.TRACK_HEIGHT_DP +
        ThinkingSliderGeometry.LABEL_GAP_DP +
        ThinkingSliderGeometry.LABEL_BAND_DP

    Column(modifier = modifier.alpha(if (enabled) 1f else 0.45f)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val layoutWidthPx = if (constraints.hasBoundedWidth) {
                constraints.maxWidth.toFloat()
            } else {
                0f
            }
            val track = remember(
                layoutWidthPx,
                count,
                labelHalfWidthPx,
                barHeightPx,
                thumbRadiusPx,
                edgePadPx,
            ) {
                ThinkingSliderGeometry.track(
                    widthPx = layoutWidthPx,
                    barHeightPx = barHeightPx,
                    thumbRadiusPx = thumbRadiusPx,
                    labelHalfWidthPx = labelHalfWidthPx,
                    edgePadPx = edgePadPx,
                    count = count,
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeightDp.dp)
                    .semantics {
                        contentDescription = "Thinking effort: " +
                            labels.getOrElse(selectedIndex) { "" }
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = ComposerPortMath.pct(selectedIndex, count),
                            range = 0f..1f,
                            steps = (count - 2).coerceAtLeast(0),
                        )
                        // TalkBack can still step through every real level.
                        if (enabled && count > 1) {
                            customActions = listOf(
                                CustomAccessibilityAction("Increase thinking effort") {
                                    if (selectedIndex < count - 1) {
                                        onSelect(levels[selectedIndex + 1])
                                        true
                                    } else {
                                        false
                                    }
                                },
                                CustomAccessibilityAction("Decrease thinking effort") {
                                    if (selectedIndex > 0) {
                                        onSelect(levels[selectedIndex - 1])
                                        true
                                    } else {
                                        false
                                    }
                                },
                            )
                        }
                    }
                    .pointerInput(enabled, levels, track) {
                        if (!enabled || count < 2) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            dragging = true
                            dragPosition = track.valueForX(down.position.x)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            var lastX = down.position.x
                            var lastIndex = track.indexForX(lastX)
                            var completed = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    change.consume()
                                    if (!change.pressed) {
                                        completed = true
                                        break
                                    }
                                    lastX = change.position.x
                                    dragPosition = track.valueForX(lastX)
                                    val index = track.indexForX(lastX)
                                    if (index != lastIndex) {
                                        lastIndex = index
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                    }
                                }
                            } finally {
                                // A cancelled gesture (sheet dismissed) leaves
                                // the thumb unpressed, back on the committed
                                // level, and commits nothing.
                                dragging = false
                            }
                            if (!completed) {
                                dragPosition = null
                                return@awaitEachGesture
                            }
                            val released = track.indexForX(lastX)
                            releaseOrigin = track.valueForX(lastX)
                            requestedIndex = released
                            // Bumped even when the level is unchanged so the
                            // settle animation always plays after a release.
                            releaseSerial += 1
                            onSelect(levels[released])
                        }
                    },
            ) {
                // Inside DrawScope `density` is the Float pixel density and
                // Dp.toPx() is available directly (DrawScope : Density).
                val barH = track.barHeightPx
                val cy = track.cy
                // The pill and the tick span are different x ranges: the pill
                // starts at barLeft, while the first dot sits pillRadius inside
                // it, so the round thumb never hangs over the rounded end.
                val left = track.barLeft
                val barWidth = track.barWidth
                val value = dragPosition ?: progress.value
                val energyNow = ComposerPortMath.energy(value)
                val thumbX = track.xForValue(value)
                val fillWidth = (thumbX - left).coerceAtLeast(0f)
                val top = 0f
                val bottom = barH
                val thumbR = (track.thumbRadiusPx * pressScale)
                    .coerceAtMost(barH / 2f - 0.5f.dp.toPx())

                // Outer glow: stacked strokes rather than BlurMaskFilter. The
                // bed is always lit now, so the halo is always on and only
                // deepens with the selected level.
                val haloColor = Color(
                    ComposerPortMath.mixArgb(
                        ComposerPortMath.fillEndArgb(value),
                        0xFFFFFFFF.toInt(),
                        0.35f,
                    ),
                )
                val haloAlpha = 0.14f + 0.30f * energyNow
                for (i in 3 downTo 1) {
                    drawRoundRect(
                        color = haloColor.copy(alpha = haloAlpha / (i * 2f)),
                        topLeft = Offset(left - i * density * 2f, top - i * density * 2f),
                        size = Size(
                            barWidth + i * density * 4f,
                            barH + i * density * 4f,
                        ),
                        cornerRadius = CornerRadius(
                            barH / 2f + i * density * 2f,
                        ),
                        style = Stroke(width = density * 2f),
                    )
                }

                // Coloured bed across the WHOLE pill — Off and Low included.
                // There is no neutral "empty" track left: progress is read from
                // the brighter sheen up to the thumb, the halo, the lit tick and
                // the particle density, never from an unfilled gap.
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(ComposerPortMath.BED_BLUE_ARGB),
                            Color(ComposerPortMath.BED_VIOLET_ARGB),
                            Color(ComposerPortMath.BED_DEEP_ARGB),
                        ),
                        startX = left,
                        endX = left + barWidth,
                    ),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(barH / 2f),
                )
                // A touch of vertical shading so the stadium reads as glass
                // rather than a flat rectangle.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.14f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.14f),
                        ),
                    ),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(barH / 2f),
                )

                val pillPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left,
                            top = top,
                            right = left + barWidth,
                            bottom = bottom,
                            radiusX = barH / 2f,
                            radiusY = barH / 2f,
                        ),
                    )
                }

                clipPath(pillPath) {
                    // Reached stretch: a brighter sheen that ends exactly at
                    // the thumb, so the level is still readable on the bed.
                    if (fillWidth > 0.5f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.04f + 0.10f * energyNow),
                                    Color.White.copy(alpha = 0.16f + 0.20f * energyNow),
                                ),
                                startX = left,
                                endX = (left + fillWidth).coerceAtLeast(left + 1f),
                            ),
                            topLeft = Offset(left, top),
                            size = Size(fillWidth, barH),
                        )
                        // Nebula tint, now only warming the reached stretch
                        // instead of painting the whole bar.
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF3B1178),
                                    Color(0xFF6D28D9),
                                    Color(0xFF9333EA),
                                    Color(0xFFC084FC),
                                ),
                                startX = left,
                                endX = (left + fillWidth).coerceAtLeast(left + 1f),
                            ),
                            topLeft = Offset(left, top),
                            size = Size(fillWidth, barH),
                            alpha = 0.08f + 0.16f * energyNow,
                        )
                    }

                    // Sweep highlight: crosses the WHOLE pill at every level,
                    // with an alpha floor so Off/Low show motion too.
                    if (!reducedMotion) {
                        val sweepT = (particleClock / 3.4f) % 1f
                        val band = barWidth * 0.5f
                        val sweepX = left - band + (barWidth + band) * sweepT
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.09f + 0.16f * energyNow),
                                    Color.Transparent,
                                ),
                                startX = sweepX,
                                endX = sweepX + band,
                            ),
                            topLeft = Offset(sweepX, top),
                            size = Size(band, barH),
                        )
                    }

                    // Stars: the field drifts across the full pill, so Off and
                    // Low are genuinely animated instead of a still dot.
                    val starCount = ComposerPortMath.starCount(energyNow)
                    val speed = ComposerPortMath.particleSpeed(value)
                    val layer = 0.75f + 0.25f * energyNow
                    for (i in 0 until starCount) {
                        val spread = ComposerPortMath.starSpread(i) * if (reducedMotion) {
                            2.6f
                        } else {
                            1f
                        }
                        val duration = 1.5f * 2f / speed * spread
                        val starProgress =
                            ((particleClock / duration) + ComposerPortMath.starPhase(i)) % 1f
                        val brightness = ComposerPortMath.starBrightness(i)
                        val alpha = ComposerPortMath.starAlpha(starProgress, brightness, layer)
                        if (alpha <= 0f) continue
                        val x = left + barWidth * (1f - starProgress)
                        val y = top + barH * (0.18f + 0.64f * ComposerPortMath.starY(i))
                        drawCircle(
                            color = Color(0xFFF5F3FF).copy(alpha = alpha * 0.28f),
                            radius = 4.5f * density * brightness,
                            center = Offset(x, y),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = alpha),
                            radius = 1.5f * density * brightness,
                            center = Offset(x, y),
                        )
                    }
                }

                // One dot per level. All of them are drawn (reached = light,
                // unreached = faint) so each dot lines up with the mode name
                // centred under it; the thumb rests exactly on its own dot.
                if (count > 1) {
                    for (i in 0 until count) {
                        val reached = track.pctFor(i) <= value + 0.0005f
                        drawCircle(
                            color = Color.White.copy(alpha = if (reached) 0.92f else 0.30f),
                            radius = 1.6f * density,
                            center = Offset(track.tickX(i), cy),
                        )
                    }
                }

                // Thumb: a circle strictly inside the pill (radius derived from
                // the bar height minus the inset), with a soft contact shadow.
                drawCircle(
                    color = Color.Black.copy(alpha = if (dark) 0.32f else 0.12f),
                    radius = thumbR,
                    center = Offset(thumbX, cy + 0.5f.dp.toPx()),
                )
                drawCircle(
                    color = Color.White,
                    radius = thumbR,
                    center = Offset(thumbX, cy),
                )

                // Labels: centred on their own dot (same track.tickX/labelX).
                if (labels.size > 1) {
                    val baseline = ThinkingSliderGeometry.labelBaseline(
                        bandTopPx = barH + ThinkingSliderGeometry.LABEL_GAP_DP * density,
                        bandHeightPx = ThinkingSliderGeometry.LABEL_BAND_DP * density,
                        ascent = labelPaint.ascent(),
                        descent = labelPaint.descent(),
                    )
                    val activeIndex = track.indexForX(value)
                    activeLabelPaint.color =
                        ComposerPortMath.levelColorArgb(activeIndex, count)
                    drawIntoCanvas { canvas ->
                        labels.forEachIndexed { i, label ->
                            canvas.nativeCanvas.drawText(
                                label,
                                track.labelX(i),
                                baseline,
                                if (i == activeIndex) activeLabelPaint else labelPaint,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Compact, always-English slider tick name (`off`, `low`, `xhigh`, …). */
private fun ThinkingLevel.effortSliderLabel(): String = displayName.lowercase()
