package com.openminis.app.ui.chat

import android.provider.Settings
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
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
 * Discrete particle slider for thinking effort.
 *
 * The visual model is a faithful port of the upstream Android View:
 * 28dp pill, white knob, blue -> violet -> deep-purple fill, nebula +
 * sweeping highlight + 22 deterministic stars, and level labels centred on
 * their own tick. Interaction is deliberately native-Compose: drag anywhere
 * on the track, release to commit one discrete level; a tap commits directly.
 *
 * [levels] must include OFF as the first entry (the sheet builds that list).
 * [onSelect] is called once per gesture with a level from [levels].
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

    val labels = remember(levels, context) { levels.map { it.localizedName(context) } }
    val selectedIndex = remember(levels, current) {
        if (current == ThinkingLevel.OFF) {
            0
        } else {
            levels.indexOf(current).takeIf { it >= 0 }
                ?: levels.indexOfLast { it != ThinkingLevel.OFF }.coerceAtLeast(0)
        }
    }
    var position by remember(levels) {
        mutableFloatStateOf(ComposerPortMath.pct(selectedIndex, levels.size))
    }
    var dragging by remember { mutableStateOf(false) }
    var starClock by remember { mutableFloatStateOf(0f) }
    var sweepClock by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(levels, selectedIndex, dragging) {
        if (!dragging) {
            animate(
                initialValue = position,
                targetValue = ComposerPortMath.pct(selectedIndex, levels.size),
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 620f),
            ) { value, _ -> position = value }
        }
    }

    val energy = ComposerPortMath.energy(position)
    val shouldAnimate = enabled && (energy > 0.001f || dragging)
    LaunchedEffect(shouldAnimate, reducedMotion) {
        if (!shouldAnimate) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            if (ComposerPortMath.energy(position) <= 0.001f) break
            starClock += dt
            if (!reducedMotion) sweepClock += dt
        }
    }

    val labelSizePx = with(localDensity) { 10.sp.toPx() }
    val labelPaint = remember(labelSizePx, dark) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelSizePx
            textAlign = android.graphics.Paint.Align.CENTER
            color = if (dark) 0xFF9C99A6.toInt() else 0xFF7C7A85.toInt()
            isSubpixelText = true
        }
    }
    val trackColor = if (dark) Color(0x26FFFFFF) else Color(0x140F1115)
    val tickColor = if (dark) Color(0x42FFFFFF) else Color(0x420F1115)

    Column(modifier = modifier.alpha(if (enabled) 1f else 0.45f)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .semantics {
                    contentDescription = "Thinking effort: ${labels.getOrElse(selectedIndex) { "" }}"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = ComposerPortMath.pct(selectedIndex, levels.size),
                        range = 0f..1f,
                        steps = (levels.size - 2).coerceAtLeast(0),
                    )
                }
                .pointerInput(enabled, levels) {
                    if (!enabled || levels.size < 2) return@pointerInput
                    fun valueForX(x: Float): Float {
                        val inset = 14.dp.toPx()
                        val usable = (size.width - inset * 2f).coerceAtLeast(1f)
                        return ((x - inset) / usable).coerceIn(0f, 1f)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        position = valueForX(down.position.x)
                        var lastIndex = ComposerPortMath.nearestIndex(position, levels.size)
                        down.consume()
                        var event: PointerEvent
                        do {
                            event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                position = valueForX(change.position.x)
                                val index = ComposerPortMath.nearestIndex(position, levels.size)
                                if (index != lastIndex) {
                                    lastIndex = index
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        dragging = false
                        val index = ComposerPortMath.nearestIndex(position, levels.size)
                        onSelect(levels[index])
                    }
                },
        ) {
            // Inside DrawScope `density` is the Float pixel density and
            // Dp.toPx() is available directly (DrawScope : Density).
            val r = 14.dp.toPx()
            val band = if (labels.size > 1) 17.dp.toPx() else 0f
            val cy = (size.height - band) / 2f
            val left = r
            val right = size.width - r
            val trackWidth = (right - left).coerceAtLeast(1f)
            val knobX = left + position * trackWidth
            val fillWidth = (knobX - left).coerceAtLeast(0f)
            val top = cy - r
            val bottom = cy + r
            val height = bottom - top
            val energyNow = ComposerPortMath.energy(position)

            // Outer glow: stacked strokes rather than BlurMaskFilter.
            if (energyNow > 0.02f) {
                val haloAlpha = (0.5f * energyNow).coerceIn(0f, 0.5f)
                for (i in 3 downTo 1) {
                    drawRoundRect(
                        color = Color(0xFFA855F7).copy(alpha = haloAlpha / (i * 2f)),
                        topLeft = Offset(left - i * density * 2f, top - i * density * 2f),
                        size = Size(
                            trackWidth + i * density * 4f,
                            height + i * density * 4f,
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + i * density * 2f),
                        style = Stroke(width = density * 2f),
                    )
                }
            }

            // Track.
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(left, top),
                size = Size(trackWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
            )

            if (fillWidth > 0.5f) {
                val fillPath = Path().apply {
                    val rad = r.coerceAtMost(fillWidth)
                    moveTo(left + rad, top)
                    lineTo(left + fillWidth, top)
                    lineTo(left + fillWidth, bottom)
                    lineTo(left + rad, bottom)
                    quadraticTo(left, bottom, left, bottom - rad)
                    lineTo(left, top + rad)
                    quadraticTo(left, top, left + rad, top)
                    close()
                }
                val endColor = Color(ComposerPortMath.fillEndArgb(position))
                drawPath(
                    path = fillPath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(ComposerPortMath.BLUE_ARGB), endColor),
                        startX = left,
                        endX = (left + fillWidth).coerceAtLeast(left + 1f),
                    ),
                )

                clipPath(fillPath) {
                    // Nebula layer.
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
                        size = Size(fillWidth, height),
                        alpha = energyNow,
                    )

                    // Sweep highlight.
                    if (!reducedMotion && energyNow > 0.01f) {
                        val sweepT = (sweepClock / 2.4f) % 1f
                        val shift = fillWidth * (1f - 2f * sweepT)
                        translate(shift, 0f) {
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.30f),
                                        Color.Transparent,
                                    ),
                                    start = Offset(left, bottom),
                                    end = Offset(left + fillWidth, top),
                                ),
                                topLeft = Offset(left, top),
                                size = Size(fillWidth, height),
                            )
                        }
                    }

                    // Stars.
                    val count = ComposerPortMath.starCount(energyNow)
                    val speed = ComposerPortMath.speedAt(position)
                    val layer = 0.6f + 0.4f * energyNow
                    for (i in 0 until count) {
                        val spread = ComposerPortMath.starSpread(i) * if (reducedMotion) 2.6f else 1f
                        val duration = 1.5f * 2f / speed * spread
                        val progress = ((starClock / duration) + ComposerPortMath.starPhase(i)) % 1f
                        val brightness = ComposerPortMath.starBrightness(i)
                        val alpha = ComposerPortMath.starAlpha(progress, brightness, layer)
                        if (alpha <= 0f) continue
                        val x = left + fillWidth * (1f - progress)
                        val y = top + ComposerPortMath.starY(i) * height
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
            }

            // Unreached ticks.
            if (levels.size > 1) {
                for (i in 0 until levels.size) {
                    val p = ComposerPortMath.pct(i, levels.size)
                    if (p <= position) continue
                    drawCircle(
                        color = tickColor,
                        radius = 1.5f * density,
                        center = Offset(left + p * trackWidth, cy),
                    )
                }
            }

            // Knob.
            drawCircle(
                color = Color.White,
                radius = r,
                center = Offset(knobX, cy),
            )

            // Labels, centred on their own tick (same pct() as the dots).
            if (labels.size > 1) {
                drawIntoCanvas { canvas ->
                    val baseline = size.height - band / 2f -
                        (labelPaint.ascent() + labelPaint.descent()) / 2f
                    labels.forEachIndexed { i, label ->
                        val x = left + ComposerPortMath.pct(i, labels.size) * trackWidth
                        canvas.nativeCanvas.drawText(label, x, baseline, labelPaint)
                    }
                }
            }
        }
    }
}
