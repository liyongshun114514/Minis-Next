package com.openminis.app.ui.chat

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableLongStateOf
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

/**
 * Native Compose port of the Composer UI that used to live in the Xposed
 * module `MinisAllInOne` (Composer 1.9.19-test).
 *
 * What is ported here:
 *  - the particle effort slider, now a Compose Canvas transcription of the
 *    `CodexEffortSlider` in `Bai-2008/dsh-better-panel` @ `4f798ffc`
 *    (`lib/client.js`), not the old Xposed `EffortSlider.java`;
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
 * One animation request for the effort slider: the stop the thumb must rest on,
 * a serial that changes on every completed gesture, and the slide speed picked
 * by the upstream fast/slow hysteresis.
 */
private data class EffortSliderRequest(
    val index: Int,
    val serial: Int,
    val slow: Boolean,
)

/**
 * Discrete particle slider for thinking effort — a native-Compose transcription
 * of upstream `CodexEffortSlider` (`Bai-2008/dsh-better-panel` @
 * `4f798ffc`, `lib/client.js` l.1913-2520 and the stylesheet at l.470-560).
 *
 * Visual model, one-to-one with the upstream DOM:
 *  - a 20dp neutral stadium (`--dsw-alias-border-l1`, 8% label ink) as the
 *    track, with a 28dp white knob floating on top of it (upstream: 28 / 20);
 *  - the fill starts at the pill's left edge, ends exactly at the **knob
 *    centre**, has a 10dp rounded left cap and a square right cap, and is a
 *    horizontal gradient from `#4D93F8` to the colour of the current position
 *    (`#4D93F8` up to 1/3, `#9333EA` at 2/3, `#4C1D95` at the end);
 *  - the purple energy layer (nebula + 2.4s linear sweep) is clipped to the
 *    same filled segment and fades in with `energy = (pct - 1/3) / (2/3)`;
 *  - the star layer is also clipped to the filled segment and carries
 *    `0.6 + 0.4 * energy` opacity; `round(energy * 22)` of the 22 deterministic
 *    stars are drawn, each travelling right -> left over 1.5 * 2 / speed
 *    seconds with a golden-ratio phase and a ±8% duration spread;
 *  - one 3dp tick per stop at the same x as the knob centre (white at 50% when
 *    reached, label ink at 26% otherwise); at the last stop every tick except
 *    the current one is hidden, exactly like upstream `data-max`;
 *  - the knob is a plain white circle with the upstream 1px bottom shadow and
 *    a 5% ring — no press scale (upstream has none).
 *
 * Animation maths: every layer is driven by one [Animatable] in **percent
 * space**, so fill width, energy width, star clip and knob x can never separate.
 * The transition is the upstream CSS pair — `.16s cubic-bezier(.2,.9,.3,1)`
 * (fast) and `.34s cubic-bezier(.45,.05,.35,1)` (slow) — selected by the same
 * 320/200ms hysteresis on the interval between steps. A drag is a detent
 * machine, not finger-following: a press jumps to the **nearest** stop, then
 * every `1/3` of a pitch (`floor(u - 1/3) + 1`) moves exactly one stop, with
 * the upstream press dead-zone only when the nearest stop and the detent
 * disagree.
 *
 * Interaction stays native: press, drag, release commits exactly one level
 * through [onSelect]; a cancelled gesture (sheet dismissed) commits nothing and
 * the thumb animates back to the committed level. Geometry comes from
 * [ThinkingSliderGeometry], whose [ThinkingSliderGeometry.Track] drives both the
 * Canvas and the pointer handler.
 *
 * [levels] must include OFF as the first entry. [onSelect] is called once per
 * completed gesture with a level from [levels].
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

    // Wire values in English (off / low / medium / high / xhigh / max / ultra):
    // they are what the request carries and they match the compact chip.
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

    // The stop the thumb must rest on. Written by the gesture (detent target)
    // and by the ViewModel's truth whenever `current` changes.
    var requestedIndex by remember(levels) { mutableIntStateOf(selectedIndex) }
    var releaseSerial by remember(levels) { mutableIntStateOf(0) }
    var slideSlow by remember(levels) { mutableStateOf(false) }
    var lastStepAtMs by remember(levels) { mutableLongStateOf(0L) }
    var particleClock by remember { mutableFloatStateOf(0f) }

    // Upstream mounts the knob on its stop (CSS transitions do not run on the
    // first layout), so the initial value is the committed stop, not 0.
    val progress = remember(levels) {
        Animatable(ComposerPortMath.pct(selectedIndex, count))
    }

    val fastEase = remember { CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f) }
    val slowEase = remember { CubicBezierEasing(0.45f, 0.05f, 0.35f, 1f) }

    // Keyed on the animatable itself: a model switch can rebuild the level
    // list without changing its size, and the pipeline must follow the new one.
    LaunchedEffect(progress, count, reducedMotion, fastEase, slowEase) {
        var lastSerial = -1
        snapshotFlow { EffortSliderRequest(requestedIndex, releaseSerial, slideSlow) }
            .distinctUntilChanged()
            .collectLatest { request ->
                if (request.serial != lastSerial) lastSerial = request.serial
                progress.animateTo(
                    targetValue = ComposerPortMath.pct(request.index, count),
                    animationSpec = if (reducedMotion) {
                        tween(durationMillis = 1)
                    } else if (request.slow) {
                        tween(durationMillis = 340, easing = slowEase)
                    } else {
                        tween(durationMillis = 160, easing = fastEase)
                    },
                )
            }
    }

    // External truth wins: opening the sheet, another surface changing the
    // level, or setThinkingLevel clamping all land here.
    LaunchedEffect(levels, current) {
        requestedIndex = selectedIndex
    }

    // One clock for the sweep and the stars. The upstream CSS pauses the
    // animations whenever energy is 0; here the loop simply runs while the
    // slider is enabled, and the drawing skips the layers at Off/Low anyway.
    LaunchedEffect(enabled, reducedMotion, progress) {
        if (!enabled) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) {
                particleClock += ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            last = now
        }
    }

    val labelSizePx = with(localDensity) { ThinkingSliderGeometry.LABEL_SIZE_SP.sp.toPx() }
    val slotHeightPx = with(localDensity) { ThinkingSliderGeometry.SLOT_HEIGHT_DP.dp.toPx() }
    val barHeightPx = with(localDensity) { ThinkingSliderGeometry.TRACK_HEIGHT_DP.dp.toPx() }
    val knobRadiusPx = with(localDensity) {
        ThinkingSliderGeometry.knobRadiusPx(ThinkingSliderGeometry.KNOB_SIZE_DP.dp.toPx())
    }
    val edgePadPx = with(localDensity) { ThinkingSliderGeometry.TRACK_EDGE_PAD_DP.dp.toPx() }

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
    // Widest half-label: this sets the symmetric gutter, so the first and last
    // names are never clipped while every stop keeps an identical pitch.
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

    // The vertical slot is unchanged (30dp) and the 20dp pill is centred in
    // it, so this total stays at the old 30 + 5 + 17 = 52dp.
    val canvasHeightDp = ThinkingSliderGeometry.SLOT_HEIGHT_DP +
        ThinkingSliderGeometry.LABEL_GAP_DP +
        ThinkingSliderGeometry.LABEL_BAND_DP

    // Upstream fast/slow decision: gap since the previous step, with a 120ms
    // hysteresis so a speed sitting on the threshold cannot flap.
    fun noteStepTiming() {
        val now = android.os.SystemClock.uptimeMillis()
        val gap = now - lastStepAtMs
        lastStepAtMs = now
        slideSlow = if (slideSlow) {
            gap >= 200L
        } else {
            gap > 320L
        }
    }

    val primaryInk = ChatColors.primaryText
    // Upstream drives the fill colour, energy value and star set from the
    // target stop (`shownPct`), while only the geometry (width / knob x)
    // transitions. The energy layer alone has a 0.2s opacity transition.
    val shownIndexForColor = if (count > 1) requestedIndex.coerceIn(0, count - 1) else 0
    val shownPct = ComposerPortMath.pct(shownIndexForColor, count)
    val energyTarget = ComposerPortMath.energy(shownPct)
    val energyAlpha by animateFloatAsState(
        targetValue = energyTarget,
        animationSpec = tween(durationMillis = if (reducedMotion) 1 else 200),
        label = "effortEnergy",
    )

    val trackBack = if (dark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color(0xFF0F1115).copy(alpha = 0.08f)
    }

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
                slotHeightPx,
                barHeightPx,
                knobRadiusPx,
                edgePadPx,
            ) {
                ThinkingSliderGeometry.track(
                    widthPx = layoutWidthPx,
                    slotHeightPx = slotHeightPx,
                    barHeightPx = barHeightPx,
                    knobRadiusPx = knobRadiusPx,
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
                            val downUnit = track.unitForX(down.position.x)
                            // Press = "tap where you mean": nearest stop, even
                            // when the 1/3 detent would say otherwise. The
                            // dead-zone below only matters in that conflict.
                            val pressUnit = downUnit
                            val pressIndex = ComposerPortMath.nearestIndex(
                                track.valueForX(down.position.x),
                                count,
                            )
                            val pressConflict =
                                ComposerPortMath.detentIndex(downUnit, count) != pressIndex
                            var pressActive = true
                            lastStepAtMs = android.os.SystemClock.uptimeMillis()
                            if (pressIndex != requestedIndex) {
                                noteStepTiming()
                                requestedIndex = pressIndex
                            }
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                    val unit = track.unitForX(change.position.x)
                                    val mapped = ComposerPortMath.detentIndex(unit, count)
                                    val held = ComposerPortMath.deadzoneHolds(
                                        unit = unit,
                                        pressUnit = pressUnit,
                                        mapped = mapped,
                                        pressIndex = pressIndex,
                                        conflict = pressConflict,
                                        active = pressActive,
                                    )
                                    val target = if (held) pressIndex else mapped
                                    if (!held) pressActive = false
                                    if (target != requestedIndex) {
                                        noteStepTiming()
                                        requestedIndex = target
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                    }
                                }
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                // A cancelled gesture (sheet dismissed, another
                                // pointer took over) commits nothing and must
                                // not leave the thumb on an uncommitted stop.
                                requestedIndex = selectedIndex
                                throw cancel
                            }
                            if (!completed) {
                                // Cancelled (sheet dismissed): commit nothing,
                                // animate back to the level the host owns.
                                requestedIndex = selectedIndex
                                return@awaitEachGesture
                            }
                            // Release commits exactly one level; the serial
                            // makes the settle request observable even when the
                            // stop did not change.
                            releaseSerial += 1
                            onSelect(levels[requestedIndex])
                        }
                    },
            ) {
                val pctNow = progress.value
                val shownIndex = if (count > 0) requestedIndex.coerceIn(0, count - 1) else 0
                val knobX = track.xForValue(pctNow)
                val fillWidth = (knobX - track.barLeft).coerceAtLeast(0f)
                val top = track.barTop
                val barH = track.barHeightPx
                val radius = track.trackRadius
                val px = 1.dp.toPx()

                // Track: neutral stadium, no coloured bed (upstream `.ces-track`).
                drawRoundRect(
                    color = trackBack,
                    topLeft = Offset(track.barLeft, top),
                    size = Size(track.barWidth, barH),
                    cornerRadius = CornerRadius(radius),
                )

                if (fillWidth > 0.5f) {
                    // Fill: 10dp rounded left cap, square right cap, ending at
                    // the knob centre; left anchor blue -> position colour.
                    val fillPath = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = track.barLeft,
                                top = top,
                                right = track.barLeft + fillWidth,
                                bottom = top + barH,
                                topLeftCornerRadius = CornerRadius(radius),
                                topRightCornerRadius = CornerRadius(0f),
                                bottomRightCornerRadius = CornerRadius(0f),
                                bottomLeftCornerRadius = CornerRadius(radius),
                            ),
                        )
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(ComposerPortMath.FILL_START_ARGB),
                                Color(ComposerPortMath.fillEndArgb(shownPct)),
                            ),
                            startX = track.barLeft,
                            endX = (track.barLeft + fillWidth).coerceAtLeast(track.barLeft + 1f),
                        ),
                    )

                    clipPath(fillPath) {
                        if (energyAlpha > 0.001f) {
                            // Nebula: fixed 4-stop gradient at layer opacity
                            // energy (upstream `--ces-energy`).
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color(ComposerPortMath.NEBULA_START_ARGB),
                                    0.30f to Color(ComposerPortMath.NEBULA_MID_ARGB),
                                    0.62f to Color(ComposerPortMath.NEBULA_HIGH_ARGB),
                                    1f to Color(ComposerPortMath.NEBULA_END_ARGB),
                                    startX = track.barLeft,
                                    endX = (track.barLeft + fillWidth).coerceAtLeast(track.barLeft + 1f),
                                ),
                                topLeft = Offset(track.barLeft, top),
                                size = Size(fillWidth, barH),
                                alpha = energyAlpha,
                            )
                            // Sweep: 2.4s linear, translateX(100% -> -100%) of
                            // the filled width, display:none under reduced motion.
                            if (!reducedMotion) {
                                val sweepT = (particleClock / 2.4f) % 1f
                                val sweepX = track.barLeft + fillWidth * (1f - 2f * sweepT)
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        0.32f to Color.White.copy(alpha = 0.30f),
                                        0.68f to Color.White.copy(alpha = 0.30f),
                                        1f to Color.Transparent,
                                        startX = sweepX,
                                        endX = sweepX + fillWidth,
                                    ),
                                    topLeft = Offset(sweepX, top),
                                    size = Size(fillWidth, barH),
                                    alpha = energyAlpha,
                                )
                            }
                        }

                        // Stars: clipped to the filled segment (upstream layer
                        // width = knobOffset), so they cross exactly that span.
                        val starCount = ComposerPortMath.starCountFor(shownPct)
                        if (starCount > 0) {
                            val speed = ComposerPortMath.speedFor(shownPct)
                            val layer = ComposerPortMath.starLayerOpacityFor(shownPct)
                            for (i in 0 until starCount) {
                                val duration = ComposerPortMath.starDurationFor(i, speed) *
                                    if (reducedMotion) {
                                        ComposerPortMath.REDUCED_MOTION_SLOWDOWN
                                    } else {
                                        1f
                                    }
                                val starProgress =
                                    (particleClock / duration + ComposerPortMath.starPhase(i)) % 1f
                                val brightness = ComposerPortMath.starBrightnessFor(i)
                                val alpha = ComposerPortMath.starAlpha(
                                    starProgress,
                                    brightness,
                                    layer,
                                )
                                if (alpha <= 0.002f) continue
                                val x = track.barLeft + fillWidth * (1f - starProgress)
                                val y = top + barH * (ComposerPortMath.PARTICLES[i].yPercent / 100f)
                                val starColor = if (dark) Color(0xFFF5F3FF) else Color.White
                                drawCircle(
                                    color = starColor.copy(alpha = alpha * 0.22f),
                                    radius = 2f * px * brightness,
                                    center = Offset(x, y),
                                )
                                drawCircle(
                                    color = starColor.copy(alpha = alpha),
                                    radius = 1.5f * px * brightness,
                                    center = Offset(x, y),
                                )
                            }
                        }
                    }
                }

                // Ticks: 3dp dots on every stop, at the knob centre's x.
                // At the last stop only the current dot stays (hidden under the
                // knob), the rest fade out (upstream `data-max` rule).
                if (count > 1) {
                    val atMax = shownIndex >= count - 1
                    for (i in 0 until count) {
                        val current = i == shownIndex
                        if (atMax && !current) continue
                        val reached = i <= shownIndex
                        val tickColor = if (reached) {
                            Color.White.copy(alpha = 0.50f)
                        } else {
                            primaryInk.copy(alpha = 0.26f)
                        }
                        drawCircle(
                            color = tickColor,
                            radius = 1.5f * px,
                            center = Offset(track.tickX(i), track.cy),
                        )
                    }
                }

                // Knob: plain white circle, 1px bottom shadow + 5% ring,
                // overhanging the pill ends by 4dp exactly like upstream.
                drawCircle(
                    color = Color.Black.copy(alpha = 0.16f),
                    radius = track.knobRadiusPx,
                    center = Offset(knobX, track.cy + px),
                )
                drawCircle(
                    color = Color.White,
                    radius = track.knobRadiusPx,
                    center = Offset(knobX, track.cy),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.05f),
                    radius = track.knobRadiusPx,
                    center = Offset(knobX, track.cy),
                    style = Stroke(width = 0.5f * px),
                )

                // Labels: centred on their own stop (same track.labelX/tickX).
                if (labels.size > 1) {
                    val baseline = ThinkingSliderGeometry.labelBaseline(
                        bandTopPx = slotHeightPx + ThinkingSliderGeometry.LABEL_GAP_DP * density,
                        bandHeightPx = ThinkingSliderGeometry.LABEL_BAND_DP * density,
                        ascent = labelPaint.ascent(),
                        descent = labelPaint.descent(),
                    )
                    val activeIndex = requestedIndex.coerceIn(0, count - 1)
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

/**
 * Compact, always-English slider tick name (`Off`, `Low`, `XHigh`, …).
 *
 * Sentence case, not lowercase: these ticks sit under the composer beside
 * every other sentence-case label in the app, and `xhigh` read as a typo of
 * a single lowercase word rather than as the name of a setting.
 */
private fun ThinkingLevel.effortSliderLabel(): String = displayName
