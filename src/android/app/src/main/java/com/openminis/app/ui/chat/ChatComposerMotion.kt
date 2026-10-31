package com.openminis.app.ui.chat

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.LocalOmniGlass
import com.openminis.app.ui.components.OmniGlassStyle
import com.openminis.app.ui.components.decorativePhase
import com.openminis.app.ui.components.omniGlassSurface
import com.openminis.app.ui.components.rememberDecorativeTick
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.OmniColors
import kotlin.math.sin

/**
 * [T-omnibot-composer-motion] Composer shell motion: the Stadium ⇄ 20dp shape
 * morph, the focus glow, the flowing hairline and the composer's measured
 * geometry.
 *
 * ## Provenance
 *
 * Visual parameters measured from the OmniBot reference
 * (`command_overlay/widgets/chat_input_area_composer.dart`,
 * `chat_composer_surface.dart`, `chat_input_flow_border.dart`, commit
 * `c472873`). OmniBot is AGPL/commercial, so nothing is translated: this file
 * is an independent Jetpack Compose expression of the *numbers* (radii,
 * insets, durations, gradient stops, opacity ramp). The composition — what
 * drives the morph, what stays mounted during it — is Minis's own.
 *
 * ## What actually drives the morph (real state, not decoration)
 *
 * `expanded = focused || hasText || attachments.isNotEmpty()`
 * (`ChatComposerSurface(expanded: …)`). Collapsed is a stadium resolved
 * against the laid-out box (`min(width, height) / 2`); expanded is the
 * composer's resting 20dp card. The SAME editor stays mounted across the
 * transition — the shape is drawn behind the content, so focus, caret, IME
 * session, gestures and hit targets are untouched by the animation.
 *
 * Durations: 220ms for the shape/container, 240ms for the rim
 * (`_compactAnimationDuration` 220 + the 240ms `AnimatedPadding` in the
 * reference). Both collapse to `snap()` when the system animator scale is 0.
 *
 * ## Focus glow
 *
 * Two layers, both from the reference:
 *  - a soft outer shadow in the accent colour (blur 18dp when focused else
 *    12dp, offset (0,6), alpha 0.20/0.18 focused, 0.15/0.12 hovered,
 *    0.10/0.08 idle);
 *  - a 1.5dp flowing gradient ring along the shell edge whose opacity is 1.0
 *    while focused, 0.82 while merely hovered, and a 0.3→0.7 breath when
 *    idle. The ring's horizontal sweep runs faster when focused (1.6×).
 *
 * The ring is decorative, so its phase comes from the shared ~30 fps
 * [rememberDecorativeTick] clock and is READ INSIDE the draw lambda. No
 * recomposition, no per-frame allocation, and reduce-motion freezes it.
 *
 * ## Not claimed
 *
 * No liquid-glass shader/refraction (that is `OmniGlass`'s job, a separate
 * component), and no Flutter superellipse — a Compose `drawRoundRect` is a
 * circular-corner rectangle, not a continuous-curvature one.
 *
 * ## Integration points for the sibling `OmniGlass` / `OmniShapes` agent
 *
 * This file is deliberately the ONLY place the composer shell is painted, and
 * every value a glass/shape implementation needs is already computed here:
 *
 *  1. [Modifier.composerMorphSurface] — its four passes (glow, ambient,
 *     contact, fill) plus the hairline are the plain-surface branch of the
 *     reference's `ChatComposerSurface` (`if (glass) LiquidGlass(...) else
 *     DecoratedBox`). Replace them with the glass renderer; the reference keeps
 *     the flow border only when glass is OFF, so a glass build should also stop
 *     composing [ComposerFlowRing] and keep the hairline.
 *  2. `ComposerMorphMath.cornerRadiusPx(...)` is the `borderRadiusResolver`
 *     the reference feeds to the glass shader:
 *     `size.shortestSide / 2 * (1 - progress) + 20 * progress` — call it with
 *     the laid-out size to get exactly the radius the shader needs, without
 *     profiling a wide stadium path.
 *  3. `ComposerMorphHandle.shapeProgress` is the single morph driver. A
 *     superellipse (`OmniShapes`) implementation should interpolate its
 *     exponent against the same progress and read it in the draw phase, so the
 *     content never recomposes mid-morph.
 *  4. `ComposerMorphSpec` holds every measured number (72/20/1.5/14·8·12·8/
 *     220/240) so a shared shape/shadow token file can replace them by
 *     reference instead of copy.
 */
internal object ComposerMorphSpec {

    /** `minShellHeight` — the shell never measures shorter than this. */
    val MinShellHeight: Dp = 72.dp

    /** `shellRadius` — the expanded (engaged) resting corner radius. */
    val ShellRadius: Dp = ComposerSurfaceGeometry.CORNER_RADIUS_DP.dp

    /** `borderInset` — the rim between the shell edge and the inner surface. */
    val BorderInset: Dp = 1.5.dp

    /**
     * The composer's ONE vertical 8 dp.
     *
     * `contentPadding = EdgeInsets.fromLTRB(14, 8, 12, 8)` is applied by the
     * shell, i.e. it is the card's own top/bottom gutter. The text field inside
     * it used to repeat the same 8 dp as its own `contentPadding`, which stacked
     * into 16 dp above and below the typed line — two sources for one measured
     * number. [ContentPadding] is now the only place it exists and the field
     * brings none ([FieldContentPadding]).
     */
    val VerticalGutter: Dp = 8.dp

    /**
     * `contentPadding = EdgeInsets.fromLTRB(14, 8, 12, 8)`.
     *
     * Applied by the shell, so the attachment row, the text field and the
     * button row share one gutter instead of each carrying its own 12dp.
     */
    val ContentPadding: PaddingValues = PaddingValues(
        start = 14.dp,
        top = VerticalGutter,
        end = 12.dp,
        bottom = VerticalGutter,
    )

    /**
     * Padding handed to the composer's `BasicTextField` decoration box.
     *
     * Material3's default (16 dp all round) has to be neutralised or the field
     * drifts away from the shell's gutter, but nothing may be ADDED here: the
     * vertical rhythm is [VerticalGutter] and the horizontal gutter is
     * [ContentPadding]. One 8 dp, one owner.
     */
    val FieldContentPadding: PaddingValues = PaddingValues(0.dp)

    /** Flow-border stroke width (`strokeWidth: 1.5`). */
    val FlowStroke: Dp = 1.5.dp

    /** Shape / container duration: `_compactAnimationDuration` = 220ms. */
    const val SHAPE_DURATION_MS: Int = 220

    /** Rim duration: the reference's `AnimatedPadding(duration: 240ms)`. */
    const val INSET_DURATION_MS: Int = 240

    /** One full horizontal sweep of the flow ring. */
    const val FLOW_PERIOD_MS: Int = 2400

    /** `Curves.easeOutCubic` as a Compose easing. */
    val Easing: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    /** `stops: [0.0, 0.2, 0.4, 0.62, 0.82, 1.0]`. */
    val FlowStops: FloatArray = floatArrayOf(0f, 0.2f, 0.4f, 0.62f, 0.82f, 1f)

    /** `_kLightComposerFlowGradientColors`. */
    val LightFlowColors: List<Color> = listOf(
        Color(0xFFFF6A01),
        Color(0xFFF8C91C),
        Color(0xFF8A2BE2),
        Color(0xFF00BFFF),
        Color(0xFFFF0055),
        Color(0xFFFF6A01),
    )

    /** `_kDarkComposerFlowGradientColors`. */
    val DarkFlowColors: List<Color> = listOf(
        Color(0xFF8C775D),
        Color(0xFFB5A27D),
        Color(0xFF99AD91),
        Color(0xFFD5C6AB),
        Color(0xFF889B80),
        Color(0xFF8C775D),
    )

    /** Light-mode glow accent (`const Color(0xFF2F7BFF)` in the reference). */
    val LightGlow: Color = Color(0xFF2F7BFF)

    /** Minimum interactive slot for the composer's round icon buttons. */
    val TapSlot: Dp = 48.dp

    /** Visual diameter of those buttons inside [TapSlot]. */
    val IconVisualSize: Dp = 38.dp
}

/**
 * [T-omnibot-chat-empty-greeting] Entrance geometry of the empty-conversation
 * greeting.
 *
 * Measured from the reference `ChatEmptyGreeting`
 * (`chat_empty_greeting.dart`, commit `c472873`): a `TweenAnimationBuilder`
 * whose 0→1 progress runs for **420 ms** on `Curves.easeOutCubic` and drives
 * `Opacity(1)` together with `Transform.translate(0, 10 * (1 - progress))` —
 * i.e. a 10 dp rise, not a scale or a slide from off-screen. Reduce motion
 * (`ANIMATOR_DURATION_SCALE == 0` on Android) takes the end state directly, as
 * the reference's `disableAnimations` branch does.
 *
 * The reference's own copy is deliberately NOT ported: this app draws the
 * greeting from its existing localized strings and rotates them with the
 * existing placeholder pool ([ComposerPlaceholderRotation]). Only numbers,
 * structure and the reduce-motion rule come from the reference.
 */
internal object ChatEmptyGreetingSpec {

    /** Reference `Duration(milliseconds: 420)`. */
    const val ENTER_MS: Int = 420

    /** Reference `Offset(0, 10 * (1 - eased))`, in dp. */
    const val RISE_DP: Float = 10f

    /** Reference `EdgeInsets.symmetric(horizontal: 28)`. */
    const val SIDE_PADDING_DP: Float = 28f

    /**
     * Reference `_rotationInterval` of its rotating word slot: how long one
     * rotated hint stays before the next one is cross-faded in. The rotation
     * itself rides the existing composer pool and its index rules, so nothing
     * new is invented here — only the cadence.
     */
    const val ROTATE_MS: Long = 1800L

    /** Reference `Curves.easeOutCubic`, already owned by [ComposerMorphSpec]. */
    val Easing: Easing = ComposerMorphSpec.Easing

    /** Entrance offset for a given eased progress: 10 dp at 0, 0 dp at 1. */
    fun riseDp(progress: Float): Float = RISE_DP * (1f - progress.coerceIn(0f, 1f))
}

/**
 * Composer control motion: the reference's `_buttonAnimationDuration` (200ms,
 * `Curves.easeInOut`) with a 120ms exit and a 0.88 start scale, used where a
 * composer control swaps glyph because the user changed input MODE (text ⇄
 * voice) or action (send ⇄ stop).
 */
internal object ComposerButtonMotion {

    const val DURATION_MS: Int = 200

    const val CROSSFADE_OUT_MS: Int = 120

    const val SCALE_FROM: Float = 0.88f

    /** `Curves.easeInOut` as a Compose easing. */
    val Easing: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
}

/** Light/dark flow palette for the current chat theme. */
internal val ComposerFlowColors: List<Color>
    @Composable
    @ReadOnlyComposable
    get() = if (ChatColors.isDark) ComposerMorphSpec.DarkFlowColors else ComposerMorphSpec.LightFlowColors

/**
 * Pure geometry of the shell morph. No Compose, no Android — the whole shape
 * contract is unit-tested on a plain JVM (`ChatComposerMotionTest`).
 */
internal object ComposerMorphMath {

    /**
     * The reference's `expanded` predicate: focus OR a draft OR an attachment.
     *
     * Deliberately not "the keyboard is open": a composer holding a draft must
     * stay a card after the user dismisses the IME, otherwise the shape would
     * collapse under content the user can still see and tap.
     */
    fun isExpanded(focused: Boolean, hasText: Boolean, attachmentCount: Int): Boolean =
        focused || hasText || attachmentCount > 0

    /** Stadium radius: `size.shortestSide / 2`, clamped at zero. */
    fun stadiumRadiusPx(widthPx: Float, heightPx: Float): Float =
        (minOf(widthPx, heightPx) / 2f).coerceAtLeast(0f)

    /**
     * Interpolated corner radius.
     *
     * `progress = 0` is the stadium, `1` is [restingRadiusPx]. The result is
     * clamped to the stadium so a tall multi-line composer can never produce a
     * radius larger than half its shortest side (which would invert the arcs).
     */
    fun cornerRadiusPx(
        progress: Float,
        widthPx: Float,
        heightPx: Float,
        restingRadiusPx: Float,
    ): Float {
        val p = progress.coerceIn(0f, 1f)
        val stadium = stadiumRadiusPx(widthPx, heightPx)
        val resting = restingRadiusPx.coerceIn(0f, stadium)
        return stadium + (resting - stadium) * p
    }

    /** Radius of the inner surface once the rim has been taken off the edge. */
    fun innerRadiusPx(cornerRadiusPx: Float, rimPx: Float): Float =
        (cornerRadiusPx - rimPx.coerceAtLeast(0f)).coerceAtLeast(0f)

    /** Drawn rim inset: 0 at rest, [rimDp] once fully engaged. */
    fun rimInsetPx(progress: Float, rimPx: Float): Float =
        rimPx.coerceAtLeast(0f) * progress.coerceIn(0f, 1f)
}

/**
 * Pure motion math for the flowing ring and the glow shadow.
 *
 * Every constant here is a measured reference parameter; the tests assert the
 * ramp so a "prettier" tweak cannot silently leave the port.
 */
internal object ComposerFlowMath {

    private val TWO_PI: Double = 2.0 * Math.PI

    /** `(sin(flow * 2π) + 1) / 2` — the idle breath. */
    fun breath(phase: Float): Float {
        val p = phase - kotlin.math.floor(phase)
        return ((sin(p * TWO_PI) + 1.0) / 2.0).toFloat().coerceIn(0f, 1f)
    }

    /** `speed = focused ? 1.6 : 1.0`. */
    fun speed(focused: Boolean): Float = if (focused) 1.6f else 1f

    /** `shift = ((flow * speed) % 1.0) * 2 - 1`, wrapped for negative input. */
    fun shift(phase: Float, focused: Boolean): Float {
        val scaled = phase * speed(focused)
        val wrapped = scaled - kotlin.math.floor(scaled)
        return wrapped * 2f - 1f
    }

    /**
     * Ring opacity: `forceStrong 0.9`, focused 1.0, hovered 0.82,
     * otherwise `0.3 + breath * 0.4`.
     */
    fun opacity(
        focused: Boolean,
        hovered: Boolean,
        breath: Float,
        forceStrong: Boolean = false,
    ): Float {
        val interactive = focused || hovered
        val raw = when {
            forceStrong -> 0.9f
            interactive -> if (focused) 1f else 0.82f
            else -> 0.3f + breath.coerceIn(0f, 1f) * 0.4f
        }
        return raw.coerceIn(0f, 1f)
    }

    /** Focus-glow shadow alpha (light 0.20 / 0.15 / 0.10, dark 0.18 / 0.12 / 0.08). */
    fun glowAlpha(focused: Boolean, hovered: Boolean, isDark: Boolean): Float = when {
        focused -> if (isDark) 0.18f else 0.20f
        hovered -> if (isDark) 0.12f else 0.15f
        else -> if (isDark) 0.08f else 0.10f
    }

    /** `blurRadius: focused ? 18 : 12`. */
    fun glowBlurDp(focused: Boolean): Float = if (focused) 18f else 12f

    /** Gradient stops paired with a colour list, ready for a Compose brush. */
    fun colorStops(colors: List<Color>, alpha: Float): Array<Pair<Float, Color>> {
        val a = alpha.coerceIn(0f, 1f)
        val stops = ComposerMorphSpec.FlowStops
        return Array(minOf(colors.size, stops.size)) { index ->
            stops[index] to colors[index].copy(alpha = colors[index].alpha * a)
        }
    }
}

/**
 * Live handles for one composer shell.
 *
 * [shapeProgress], [rimProgress] and [flowPhase] MUST be read inside a draw
 * lambda: reading them during composition would recompose the whole composer
 * (text field included) once per frame of the 220/240ms morph.
 */
@Stable
internal class ComposerMorphHandle(
    private val shapeState: State<Float>,
    private val rimState: State<Float>,
    private val tick: State<Long>?,
    private val animateFlow: Boolean,
    private val flowPeriodMs: Int = ComposerMorphSpec.FLOW_PERIOD_MS,
) {
    /** 0 = Stadium, 1 = [ComposerMorphSpec.ShellRadius]. Draw-phase read. */
    val shapeProgress: Float get() = shapeState.value

    /** 0 = no rim, 1 = [ComposerMorphSpec.BorderInset]. Draw-phase read. */
    val rimProgress: Float get() = rimState.value

    /** 0..1 sweep phase of the flow ring. Draw-phase read. */
    fun flowPhase(): Float {
        val clock = tick ?: return 0f
        if (!animateFlow) return 0f
        return decorativePhase(clock.value, flowPeriodMs)
    }
}

/**
 * Remember the shell morph for [expanded].
 *
 * @param expanded `focused || hasText || attachments` — see
 *        [ComposerMorphMath.isExpanded].
 * @param animationsEnabled pass `!animationsDisabled(context)`; when false both
 *        durations become `snap()` and the flow ring freezes, so reduce-motion
 *        users get the end state immediately.
 */
@Composable
internal fun rememberComposerMorph(
    expanded: Boolean,
    animationsEnabled: Boolean = true,
): ComposerMorphHandle {
    val shape = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (animationsEnabled) {
            tween(ComposerMorphSpec.SHAPE_DURATION_MS, easing = ComposerMorphSpec.Easing)
        } else {
            snap()
        },
        label = "composerShapeMorph",
    )
    val rim = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (animationsEnabled) {
            tween(ComposerMorphSpec.INSET_DURATION_MS, easing = ComposerMorphSpec.Easing)
        } else {
            snap()
        },
        label = "composerRimMorph",
    )
    val tick = if (animationsEnabled) rememberDecorativeTick() else null
    return remember(shape, rim, tick, animationsEnabled) {
        ComposerMorphHandle(
            shapeState = shape,
            rimState = rim,
            tick = tick,
            animateFlow = animationsEnabled,
        )
    }
}

/**
 * The composer shell's glass wiring, or null when there is no capture scope.
 *
 * ## Why a resolved bundle instead of two booleans
 *
 * Turning the composer into a glass pane is a single decision that three
 * places must agree on: the shell must stop painting its opaque fill (or the
 * glass would be covered by it), the flow ring must stop running (the reference
 * only keeps the flowing border when glass is OFF), and the shape the glass
 * refracts through must be the same one the hairline strokes. One remembered
 * object keeps them in step; a `Boolean` at each site is how they drift.
 *
 * ## When it engages
 *
 * Only inside an `OmniGlassScope` ([LocalOmniGlass] non-null). The host owns
 * that scope — a single capture of the screen behind the dock — so a composer
 * placed outside it degrades to the plain surface instead of silently drawing
 * glass with no backdrop. Nothing here starts a capture of its own.
 *
 * ## One clock for glass and solid shapes
 *
 * The live CornerSize reads the same 220ms draw-phase radius as the hairline.
 * OmniGlass resolves and compares its actual Outline each draw (not merely
 * the Shape identity), so glass also morphs continuously without rebuilding
 * the editor. Only the system reduced-motion setting snaps the animation.
 */
internal data class ComposerGlassShell(
    val style: OmniGlassStyle,
    val shape: Shape,
)

/**
 * The glass shape for the current expand state.
 *
 * A [RoundedCornerShape] (not a bespoke `Shape`) on purpose: `OmniGlass`'s shape
 * resolver treats `RoundedCornerShape` as EXACT geometry, while any other Shape
 * falls back to "clip to the outline, refract as a sharp box". The corner size
 * is a live [CornerSize], so the resolver — which runs in the draw phase — reads
 * the morph's current radius without recomposing the composer.
 */
internal class ComposerMorphCornerSize(
    private val morph: ComposerMorphHandle,
    private val restingRadius: Dp,
) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float = ComposerMorphMath.cornerRadiusPx(
        progress = morph.shapeProgress,
        widthPx = shapeSize.width,
        heightPx = shapeSize.height,
        restingRadiusPx = with(density) { restingRadius.toPx() },
    )
}

/**
 * Glass bundle for the composer, or null when no [com.openminis.app.ui.components.OmniGlassScope]
 * is above it. [expanded] is a KEY rather than a value: the shape instance is
 * rebuilt when the card changes state so the glass path cache picks up the new
 * silhouette (see [ComposerGlassShell]).
 */
@Composable
internal fun rememberComposerGlassShell(
    enabled: Boolean,
    expanded: Boolean,
    morph: ComposerMorphHandle,
): ComposerGlassShell? {
    if (!enabled) return null
    val shape = remember(expanded, morph) {
        RoundedCornerShape(
            ComposerMorphCornerSize(morph, ComposerMorphSpec.ShellRadius),
        )
    }
    return remember(shape) { ComposerGlassShell(OmniGlassStyle.Clear, shape) }
}

/**
 * Draws the composer shell behind its content: fill, focus glow, ambient and
 * contact shadows and the inner hairline. The flowing gradient ring lives in
 * [ComposerFlowRing], a sibling overlay with its own layer.
 *
 * Draw-only on purpose. It owns no layout, no clip and no touch handling, so
 * the caret, the attachment chips, the long-press menus, the swipe-to-send
 * gesture and every hit target above it are unaffected by the morph.
 */
@Composable
internal fun Modifier.composerMorphSurface(
    morph: ComposerMorphHandle,
    focused: Boolean,
    hovered: Boolean = false,
    fill: Color = ChatColors.inputBg,
    hairline: Color = ChatColors.toolBorder,
    glow: Color = if (ChatColors.isDark) OmniColors.accentPrimary else ComposerMorphSpec.LightGlow,
    /**
     * [T-omnibot-composer-glass] Non-null turns the shell into a real glass pane
     * (see [rememberComposerGlassShell]). The opaque fill, its focus glow and its
     * ambient/contact shadows are then NOT drawn — they would sit on top of the
     * refracted backdrop and turn the pane back into a solid card. The hairline
     * stays: it is what separates the pane from the content behind it, and the
     * reference keeps it in its glass branch.
     */
    glass: ComposerGlassShell? = null,
): Modifier {
    val isDark = ChatColors.isDark
    val restingRadius = ComposerMorphSpec.ShellRadius
    val rim = ComposerMorphSpec.BorderInset
    val fillArgb = fill.toArgb()
    val hairlineColor = hairline
    val glowColor = glow
    // One Paint per shell instance: the morph redraws on every tick of the
    // flow ring, so it must not be allocated per frame.
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            isDither = true
        }
    }
    // The glass pane is OUTER, so the hairline drawn by the `drawBehind` below
    // lands on top of it instead of under it.
    val glassModifier = if (glass != null) {
        Modifier.omniGlassSurface(shape = glass.shape, style = glass.style)
    } else {
        Modifier
    }

    return this.then(glassModifier).drawBehind {
        if (size.width <= 0f || size.height <= 0f) return@drawBehind

        val progress = morph.shapeProgress
        val radius = ComposerMorphMath.cornerRadiusPx(
            progress = progress,
            widthPx = size.width,
            heightPx = size.height,
            restingRadiusPx = restingRadius.toPx(),
        )
        val canvas = drawContext.canvas.nativeCanvas

        // Glass owns the surface: no fill, no glow, no shadow passes. Only the
        // hairline below is ours.
        if (glass != null) {
            drawComposerHairline(morph, radius, hairlineColor, rim.toPx())
            return@drawBehind
        }

        paint.color = fillArgb

        // 1. Focus glow: the accent-coloured soft shadow. Drawn first so the
        //    opaque fill of pass 4 covers whatever lands inside the shape.
        val glowAlpha = ComposerFlowMath.glowAlpha(focused = focused, hovered = hovered, isDark = isDark)
        if (glowAlpha > 0f) {
            paint.setShadowLayer(
                ComposerFlowMath.glowBlurDp(focused).dp.toPx(),
                0f,
                6.dp.toPx(),
                glowColor.copy(alpha = glowAlpha).toArgb(),
            )
            canvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
        }

        // 2. Ambient + key light (0, 6) — the reference's container shadow.
        paint.setShadowLayer(
            (if (isDark) 22f else 20f).dp.toPx(),
            0f,
            6.dp.toPx(),
            android.graphics.Color.argb(if (isDark) 46 else 18, 0, 0, 0),
        )
        canvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)

        // 3. Tight contact shadow so the card still sits on white.
        paint.setShadowLayer(
            3.dp.toPx(),
            0f,
            1.dp.toPx(),
            android.graphics.Color.argb(if (isDark) 54 else 22, 0, 0, 0),
        )
        canvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)

        // 4. The fill itself, with no shadow layer left over.
        paint.clearShadowLayer()
        canvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)

        // 5. Inner hairline at the animated rim inset.
        drawComposerHairline(morph, radius, hairlineColor, rim.toPx())

        // The flowing ring is NOT drawn here: it repaints at ~30 fps and would
        // drag these four blurred passes (and the whole composer card) with it
        // on every tick. It lives in [ComposerFlowRing], a sibling overlay with
        // its own layer — the same split the reference makes with
        // `Positioned.fill { RepaintBoundary { CustomPaint(flowBorder) } }`.
    }
}

/**
 * The 1 dp hairline the shell strokes at the animated rim inset.
 *
 * Shared by the plain and the glass branch so the two cannot disagree about
 * where the card's inner edge is — in glass mode this stroke is the ONLY thing
 * `composerMorphSurface` draws.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawComposerHairline(
    morph: ComposerMorphHandle,
    radius: Float,
    color: Color,
    rimPx: Float,
) {
    val inset = ComposerMorphMath.rimInsetPx(morph.rimProgress, rimPx)
    val hairlineStroke = 1.dp.toPx()
    val hairlineInset = inset + hairlineStroke / 2f
    if (size.width <= hairlineInset * 2f || size.height <= hairlineInset * 2f) return
    val innerRadius = ComposerMorphMath.innerRadiusPx(radius, hairlineInset)
    drawRoundRect(
        color = color,
        topLeft = Offset(hairlineInset, hairlineInset),
        size = Size(size.width - hairlineInset * 2f, size.height - hairlineInset * 2f),
        cornerRadius = CornerRadius(innerRadius, innerRadius),
        style = Stroke(width = hairlineStroke),
    )
}

/**
 * The flowing gradient ring along the composer's edge, as a SIBLING overlay.
 *
 * Why it is not part of [composerMorphSurface]: the ring's sweep is decorative
 * and runs at the shared clock's ~30 fps, while the shell around it is four
 * blurred shadow passes. Drawing both from one node means every tick re-records
 * the card's shadows *and* the text inside the composer. The reference makes
 * the same split (`Positioned.fill { IgnorePointer { RepaintBoundary {
 * CustomPaint(flowBorder) } } }`); here the separation is
 * `Modifier.graphicsLayer()` — its own layer — plus a draw-modifier that reads
 * only the clock.
 *
 * Place it with `Modifier.matchParentSize()` inside the composer's Box: it is
 * decoration, owns no layout and no touch handling, and a 1.5dp stroke sits
 * inside the inset the content never reaches.
 *
 * @param morph the same handle the shell uses, so ring and shell agree on the
 *        radius mid-morph.
 * @param focused drives opacity (1.0) and sweep speed (1.6×); unfocused rings
 *        breathe between 0.3 and 0.7 instead of holding a constant strength.
 */
@Composable
internal fun ComposerFlowRing(
    morph: ComposerMorphHandle,
    focused: Boolean,
    hovered: Boolean = false,
    /**
     * Whether the composer it decorates is actually on screen. In-chat search
     * keeps the composer laid out but invisible (`alpha(0f)`), so a ring that
     * only read [focused] would keep glowing over the search bar.
     */
    visible: Boolean = true,
    /**
     * [T-omnibot-composer-glass] In glass mode the ring does not run at all: an
     * animated gradient stroke over a refracting pane reads as a second,
     * competing edge, and the reference keeps its flowing border only for the
     * decorated (non-glass) surface. The pane keeps the plain hairline instead.
     */
    glass: Boolean = false,
    flowColors: List<Color> = ComposerFlowColors,
    modifier: Modifier = Modifier,
) {
    if (!visible || glass) return
    val restingRadius = ComposerMorphSpec.ShellRadius
    val stroke = ComposerMorphSpec.FlowStroke
    val palette = flowColors
    Box(
        modifier = modifier
            // Own layer: the per-tick re-record must stop here instead of
            // reaching the composer card's shadows and its text.
            .graphicsLayer()
            .drawBehind {
                if (size.width <= 0f || size.height <= 0f) return@drawBehind
                val radius = ComposerMorphMath.cornerRadiusPx(
                    progress = morph.shapeProgress,
                    widthPx = size.width,
                    heightPx = size.height,
                    restingRadiusPx = restingRadius.toPx(),
                )
                val phase = morph.flowPhase()
                val ringAlpha = ComposerFlowMath.opacity(
                    focused = focused,
                    hovered = hovered,
                    breath = ComposerFlowMath.breath(phase),
                )
                if (ringAlpha <= 0f) return@drawBehind
                val strokePx = stroke.toPx()
                val ringInset = strokePx / 2f
                val ringRadius = ComposerMorphMath.innerRadiusPx(radius, ringInset)
                val shift = ComposerFlowMath.shift(phase, focused)
                val span = size.width
                val brush = Brush.horizontalGradient(
                    colorStops = ComposerFlowMath.colorStops(palette, ringAlpha),
                    startX = shift * span,
                    endX = span + shift * span,
                )
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(ringInset, ringInset),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(ringRadius, ringRadius),
                    style = Stroke(width = strokePx),
                )
            },
    ) {
        // Decoration only: no children, so nothing can swallow a tap meant for
        // the composer underneath.
    }
}

/**
 * Real touch geometry of one composer control.
 *
 * Measured from the reference, which draws these as Material `IconButton`s: the
 * painted circle is 38 dp, but the tappable/focusable target Material guarantees
 * is 48 dp. [Slot] is a real laid-out size, not a minimum constraint padded
 * around an inner clickable — the clickable lives ON the 48 dp node (see
 * [ComposerActionSlot]), so a tap 5 dp outside the painted circle still lands.
 */
internal object ComposerTapSpec {

    /** The hit region: what the pointer and the accessibility node cover. */
    val Slot: Dp = ComposerMorphSpec.TapSlot

    /** The painted circle inside it, unchanged from the ported 38 dp. */
    val Visual: Dp = ComposerMorphSpec.IconVisualSize

    /** Gap between the circle's edge and the slot's edge (5 dp per side). */
    val VisualInset: Dp = (Slot - Visual) / 2f
}

/**
 * One composer round control: a 48 dp hit slot with the 38 dp painted circle
 * centred inside it.
 *
 * The gesture modifier is applied to the OUTER 48 dp node and the visual is a
 * child, which is what makes the whole slot tappable. Applying `clickable` to an
 * inner 38 dp circle and wrapping it in `sizeIn(minWidth = 48.dp)` does the
 * opposite — the layout box grows but the pointer region stays on the circle.
 *
 * Every composer control (+, mic, send, stop) goes through this one composable,
 * so there is a single place where "the hit region is 48 dp" can be true or
 * false; `ComposerHitRegionTest` injects touches at the slot's corners to prove
 * it against the real layout.
 *
 * @param onClick null renders a slot with no gesture (a disabled control that
 *        must not swallow presses meant for the composer).
 * @param onLongClick when set, `combinedClickable` is used so tap and long-press
 *        are distinguished (the Send/Stop choosers and the mic's long-press).
 * @param visualModifier background/border of the painted circle.
 * @param slotSemantics merged onto the slot node (custom actions, roles).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComposerActionSlot(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    visualModifier: Modifier = Modifier,
    slotSemantics: (SemanticsPropertyReceiver.() -> Unit)? = null,
    /**
     * Popup/menu hosts that must be anchored to the SLOT, not to the painted
     * circle: a `Popup` positions against the layout node it is declared in, so
     * declaring the chooser here makes it grow out of the 48 dp target the user
     * actually pressed. Laid out in the slot's centre and sized by the popup
     * itself, so it never affects the slot's measured size.
     */
    overlay: @Composable () -> Unit = {},
    visualContent: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            // A REAL size, so the node's pointer region is exactly the slot.
            .size(ComposerTapSpec.Slot)
            // Round ripple/clip; the clip keeps a circular silhouette (a touch
            // in the clipped corner is deliberately outside the button, which
            // is what a round control means).
            .clip(CircleShape)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(enabled = onClick != null) { onClick?.invoke() }
                },
            )
            .then(
                if (slotSemantics != null) Modifier.semantics(properties = slotSemantics)
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ComposerTapSpec.Visual)
                .then(visualModifier),
            contentAlignment = Alignment.Center,
            content = visualContent,
        )
        overlay()
    }
}
