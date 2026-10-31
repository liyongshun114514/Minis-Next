package com.openminis.app.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.decorativePingPong

// [T-android-split-chat] Self-contained "thinking / streaming" dot indicators
// extracted verbatim from ChatScreen.kt. `internal` so the chat package can
// still reference them. No logic change — code moved as-is.

@Composable
internal fun BouncingDots(color: Color) {
    // [T-android-decorative-anim-perf] Was three infinite transitions read in
    // composition and animated through PADDING — a layout-phase property, so
    // every frame recomposed and re-laid-out the row. Now one shared 30 fps
    // tick, read inside the layer lambda, moving translationY only.
    // [T-omnibot-thinking-dots] Geometry/curve ported from the OmniBot
    // reference `ThinkingDotsIndicator`: 8dp dots (its `dotSize` default),
    // 4dp gaps, a 4dp lift and an alpha ramp of 0.4→1.0 shared with the
    // travel, one 600 ms cycle per dot staggered by 150 ms so the wave reads
    // left-to-right. The tick is still the shared 30 fps one, so this stays a
    // draw-phase-only change.
    //
    // [T-omnibot-composer-motion] Dot size corrected 5dp → 8dp: the comment
    // above has claimed 8dp since the port landed, and 8dp is what the
    // reference actually draws; 5dp made the "is thinking" row read as a
    // shimmer rather than three dots.
    val tick = rememberDecorativeTick()
    val dotSize = ThinkingDotsSpec.DotSize
    val lift = ThinkingDotsSpec.Lift
    Row(horizontalArrangement = Arrangement.spacedBy(ThinkingDotsSpec.Spacing)) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        val p = decorativePingPong(
                            decorativePhase(
                                nanos = tick.value,
                                periodMs = ThinkingDotsSpec.CYCLE_MS,
                                offsetMs = i * ThinkingDotsSpec.STAGGER_MS,
                            ),
                        )
                        translationY = -lift.toPx() * p
                        alpha = ThinkingDotsSpec.ALPHA_MIN +
                            (ThinkingDotsSpec.ALPHA_MAX - ThinkingDotsSpec.ALPHA_MIN) * p
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

// iOS-style streaming "..." after tool title — 3 dots bouncing inline with text
@Composable
internal fun StreamingDotsText() {
    // [T-android-decorative-anim-perf] `Modifier.offset(y = …)` in its
    // non-lambda form is a layout-phase read; the dots re-laid-out the pill
    // title row every frame. Layer translation instead, from the shared tick.
    val tick = rememberDecorativeTick()
    Row {
        repeat(3) { i ->
            Text(
                text = ".",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.graphicsLayer {
                    val p = decorativePingPong(decorativePhase(tick.value, 700, i * 120))
                    translationY = -3.dp.toPx() * p
                },
            )
        }
    }
}

/**
 * [T-omnibot-thinking-dots] Measured geometry of the reference
 * `ThinkingDotsIndicator`, kept in one place so the thinking row and any future
 * "alive" affordance cannot drift apart.
 */
internal object ThinkingDotsSpec {

    /** Reference `dotSize` default: 8. */
    val DotSize = 8.dp

    /** Reference `spacing` default: 4 (half of it on each side of a dot). */
    val Spacing = 4.dp

    /** `Transform.translate(offset: Offset(0, -4 * value))`. */
    val Lift = 4.dp

    /** One cycle per dot, `Curves.easeInOut`, `repeat(reverse: true)`. */
    const val CYCLE_MS = 600

    /** Per-dot start delay, so the wave reads left to right. */
    const val STAGGER_MS = 150

    /** `alpha: 0.4 + 0.6 * value`. */
    const val ALPHA_MIN = 0.4f
    const val ALPHA_MAX = 1f
}

/**
 * [T-omnibot-chat-empty-greeting] The line the conversation area shows while a
 * chat has no messages yet.
 *
 * ## What it is
 *
 * The reference's `ChatEmptyGreeting` ported structurally, not textually: a
 * headline over a rotating hint, entering as one block with a 420 ms
 * `easeOutCubic` fade and a 10 dp rise ([ChatEmptyGreetingSpec]). Reduce motion
 * takes the end state immediately, exactly like the reference's
 * `disableAnimations` branch.
 *
 * ## Why it reads the existing copy instead of new copy
 *
 * The headline is the app's existing localized welcome string, and the rotating
 * line is the EXISTING composer hint pool resolved through
 * `composerPlaceholderText` — the same strings the composer placeholder already
 * rotates, in the same translations. The rotation is
 * [ComposerPlaceholderRotation.nextIndex], i.e. the shipped index rules (never
 * repeat what is on screen, screen reader pins it) rather than a second random
 * scheme. Nothing here invents product copy, feature names or figures: the
 * empty state cannot claim anything the app does not already say elsewhere.
 *
 * ## Rotation cadence
 *
 * The composer rotates on the focus edge because a static composer has no other
 * signal; the greeting is static by definition, so it advances on the
 * reference's 1800 ms `_rotationInterval` instead. The two focus guards of
 * [ComposerPlaceholderRotation.nextIndex] ("first focus in an empty session
 * keeps the default", "a session with messages rotates at once") describe that
 * focus edge and would freeze the greeting forever, so both are passed as
 * "this is a visit" here — the timer IS the visit.
 */
@Composable
internal fun ChatEmptyGreeting(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "Minis Next" }
    val screenReaderOn = rememberScreenReaderEnabled()
    val animate = !animationsDisabled(context)
    val headline = stringResource(R.string.onboarding_welcome_title)

    // rememberSaveable: a rotation or a dark-mode switch must not restart the
    // greeting's entrance, which the user has already seen.
    var hintIndex by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableIntStateOf(ComposerPlaceholderRotation.DEFAULT_INDEX)
    }
    LaunchedEffect(screenReaderOn, animate) {
        // TalkBack: a line that changes under the reader is noise, and the
        // rotation would retrigger announcements mid-read. Same rule as the
        // composer placeholder, same reason.
        if (screenReaderOn) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(ChatEmptyGreetingSpec.ROTATE_MS)
            hintIndex = ComposerPlaceholderRotation.nextIndex(
                current = hintIndex,
                hasFocusedBefore = true,
                sessionHasMessages = true,
                screenReaderOn = false,
                randomIndex = { bound -> kotlin.random.Random.nextInt(bound) },
            )
        }
    }

    val enter = remember {
        androidx.compose.animation.core.Animatable(if (animate) 0f else 1f)
    }
    LaunchedEffect(Unit) {
        if (animate) {
            enter.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = ChatEmptyGreetingSpec.ENTER_MS,
                    easing = ChatEmptyGreetingSpec.Easing,
                ),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ChatEmptyGreetingSpec.SIDE_PADDING_DP.dp)
            // Read in the layer lambda: one 420 ms entrance must not recompose
            // the greeting (or the chat list behind it) per frame.
            .graphicsLayer {
                alpha = enter.value
                translationY = ChatEmptyGreetingSpec.riseDp(enter.value).dp.toPx()
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = headline,
            fontSize = 19.sp,
            lineHeight = 24.7.sp,
            fontWeight = FontWeight.Normal,
            color = ChatColors.primaryText,
        )
        Text(
            text = composerPlaceholderText(ComposerPlaceholderPool.textIndex(hintIndex), soulName),
            fontSize = 15.sp,
            lineHeight = 19.5.sp,
            color = ChatColors.secondaryText,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

// ─── Compaction progress ──────────────────────────────────────────────────────

/**
 * [T-android-compact-progress] Live status while a compaction runs, with a
 * Cancel affordance.
 *
 * Compaction used to present as a single unchanging flag for however long it
 * took — which on a slow or rate-limited model could be many minutes, and is
 * indistinguishable from a hang. This ticks once a second so the elapsed count
 * visibly moves, and names the split segment when the run had to fall back to
 * summarising halves, so "still working" is legible rather than inferred.
 *
 * [T-android-compact-progress-divider] Drawn as the same centred divider the
 * finished compaction leaves behind ([FallbackInfoBlock], iOS
 * compactDividerRow / systemDividerRow with `isCompactLoading`): a thin rule
 * on each side, a small spinner, the status and Cancel in the middle. The
 * spinner gives way to the fold icon and the "N messages compacted" divider
 * (whose info icon opens the summary) when the run ends. Cancel stays in the
 * row: unlike iOS, the composer has no Stop button while compacting.
 */
@Composable
internal fun CompactProgressIndicator(
    progress: ChatViewModel.CompactProgress,
    onCancel: () -> Unit,
) {
    // Re-reads the clock every second; the changing value is what makes the
    // row demonstrably alive.
    var elapsedSec by remember(progress.startedAtMs) { mutableStateOf(0) }
    LaunchedEffect(progress.startedAtMs) {
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - progress.startedAtMs) / 1000L).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }
    val fg = ChatColors.secondaryText
    val rule = ChatColors.separator

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(rule))
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                color = fg,
                strokeWidth = 1.5.dp,
            )
            Text(
                text = if (progress.depth > 0) {
                    // Only surfaced once a split actually happened — saying
                    // "segment 1" on the common single-call path would imply a
                    // complexity that isn't there.
                    stringResource(
                        R.string.compact_progress_split,
                        elapsedSec,
                        progress.callsIssued,
                        progress.callBudget,
                    )
                } else {
                    stringResource(R.string.compact_progress, elapsedSec)
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
                maxLines = 1,
                // [T-compact-progress-cancel-fits] Weighted (not filling) so
                // Cancel - the only way to stop a compaction - is measured
                // first and the status ellipsizes into what is left. Unweighted,
                // a long status (German split text, large font) took the whole
                // width and left Cancel at zero.
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = stringResource(R.string.cancel),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).height(1.dp).background(rule))
    }
}

// ─── Typing Indicator (three dots pulsing) ────────────────────────────────────

/**
 * [T-android-retry-badge] With [retryAttempt] > 0 and a click handler, a
 * yellow "↻ n/[retryMax]" badge leads the line while an automatic retry is in
 * progress; it goes when the retried request answers, the ladder ends or the
 * user stops. The read-only sub-agent transcript passes nothing.
 */
@Composable
internal fun TypingIndicator(
    retryAttempt: Int = 0,
    retryMax: Int = 0,
    onRetryBadgeClick: (() -> Unit)? = null,
) {
    // [T-android-decorative-anim-perf] The dot marks now come from
    // [BouncingDots], which owns the shared 30 fps tick; this row no longer
    // needs a tick of its own.
    // Live Soul name → "<custom name> is thinking…" when the user renamed
    // the assistant in Soul settings. SoulStore.cachedMetadata is a StateFlow
    // that's updated on save (SoulSettingsScreen) and at app start
    // (MinisApp.onCreate via refreshCache); collectAsState makes Compose
    // recompose the indicator immediately when it changes.
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "Minis Next" }

    Row(
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (retryAttempt > 0 && onRetryBadgeClick != null) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                RetryBadge(attempt = retryAttempt, maxAttempts = retryMax, onClick = onRetryBadgeClick)
            }
        }
        Text(
            text = stringResource(R.string.chat_typing_indicator, soulName),
            fontSize = 15.sp,
            color = ChatColors.tertiaryText,
        )
        // [T-omnibot-thinking-dots] The trailing "..." glyphs are replaced by
        // the same dot primitive the streaming rows use, so the thinking row
        // and every other "alive" affordance share one visual language. The
        // "<name> is thinking…" label is kept verbatim (a11y + i18n string
        // resource) — only the dot marks changed.
        Box(
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(start = 6.dp),
        ) {
            BouncingDots(ChatColors.tertiaryText)
        }
    }
}
