package com.openminis.app.ui.sessions

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.components.OmniGlassStyle
import com.openminis.app.ui.components.OmniGlassTones
import com.openminis.app.ui.components.OmniShapes
import com.openminis.app.ui.components.omniGlassSurface
import com.openminis.app.ui.navigation.OmniMotion
import kotlin.math.roundToInt

/**
 * Layout tokens for the OmniBot home port.
 *
 * The numbers are read off the upstream reference implementation
 * (`ui/lib/widgets/flclash/search_header.dart` for the toolbar and
 * `ui/lib/widgets/flclash/navigation_dock.dart` for the dock) and are
 * reproduced here as an independent Compose port — no Dart code was
 * translated line by line, only the measured geometry and motion constants
 * were carried over.
 *
 *  - toolbar: 72dp tall, 16dp edge inset, 44dp round buttons, 8dp button gap,
 *    24sp/600 title.
 *  - dock: 62dp bar, 4dp inner padding, 21dp edge margin, 28dp glyph, 10sp
 *    label 2dp under the glyph.
 */
internal object OmniHomeSpec {
    // ── Toolbar (search header) ────────────────────────────────────────────
    val ToolbarHeight = 72.dp
    val ToolbarEdge = 16.dp
    val ToolbarButton = 44.dp
    val ToolbarIcon = 24.dp
    val ToolbarGap = 8.dp
    val TitleSize = 24.sp

    // ── Floating dock ──────────────────────────────────────────────────────
    val DockHeight = 62.dp
    val DockPadding = 4.dp
    val DockEdge = 21.dp
    /** `_iconSize` in navigation_dock.dart. */
    val DockIcon = 28.dp
    /** `_labelSize`. */
    val DockLabelSize = 10.sp
    /** `_labelGap`. */
    val DockLabelGap = 2.dp
    /** `_shadowRoom` — the room the shell's shadow is drawn in. */
    val DockShadowRoom = 8.dp
    /** `_hitSlop`. */
    val DockHitSlop = 8.dp
    /** Room the scrolling list leaves under itself so rows clear the dock. */
    val DockContentInset = 104.dp

    // ── Search capsule ─────────────────────────────────────────────────────
    val SearchFieldHeight = 44.dp
    val SearchTextSize = 17.sp

    // ── Adaptive root chrome (`omni_adaptive_layout.dart`) ─────────────────
    /** `OmniLayoutBreakpoints.medium` — at or above this the dock becomes a rail. */
    val RailBreakpoint = 600.dp
    /** `OmniNavigationRail.width`. */
    val RailWidth = 104.dp
}

/**
 * Squircle capsule: the reference's `AppShape.full` / `_dockShape` stand-in.
 *
 * The exact superellipse path — not a rounded rectangle — comes from the shared
 * `OmniShapes` geometry, which the glass shader also consumes. Passing the SAME
 * shape to `omniGlassSurface` is what keeps the pane's optics on its real
 * silhouette instead of falling back to a sharp-cornered box.
 */
internal fun omniCapsuleShape(): Shape = OmniShapes.Capsule

/** A squircle with an explicit 22dp-scale corner (`OmniShapes` default). */
internal fun omniSuperellipseShape(): Shape = OmniShapes.Superellipse

/**
 * The glass style used by every Omni pane on this screen: the reference's
 * regular glass with a neutral tint that follows the current theme instead of
 * a hard-coded light/dark wash.
 */
@Composable
internal fun omniGlassStyle(): OmniGlassStyle {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return OmniGlassStyle.Default.copy(tint = OmniGlassTones.of(isDark))
}

/** Upstream `_floatingSurface`: surfaceBright in light, surfaceContainerHigh in dark. */
@Composable
internal fun omniFloatingSurface(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceBright
    }

/** Upstream `_selectedSurface`: surfaceContainer in light, surfaceContainerHighest in dark. */
@Composable
internal fun omniSelectedSurface(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

/**
 * The home toolbar, laid out like the reference search header: one 72dp row,
 * 16dp edge inset, a 44dp leading slot, a flexible title and a trailing
 * action cluster with 8dp gaps.
 *
 * Slot names deliberately mirror [androidx.compose.material3.TopAppBar] so the
 * call site keeps its exact content (`title` / `navigationIcon` / `actions`),
 * i.e. every existing entry point and callback stays wired as it was.
 */
@Composable
internal fun OmniHomeTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // [ui/omnibot-port] Glass header: a pane rounded on its lower edge only, so
    // it reads as the sheet the content slides under. No opaque fill — the pane
    // is the surface (see OmniGlass.kt); without an enclosing OmniGlassScope it
    // degrades to its steady tint rather than throwing.
    val barShape = OmniShapes.superellipse(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomEnd = 22.dp,
        bottomStart = 22.dp,
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .omniGlassSurface(shape = barShape, style = omniGlassStyle())
            .clip(barShape),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .heightIn(min = OmniHomeSpec.ToolbarHeight)
                .padding(start = OmniHomeSpec.ToolbarEdge, end = OmniHomeSpec.ToolbarEdge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(OmniHomeSpec.ToolbarButton),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                ) {
                    navigationIcon()
                }
            }
            Spacer(Modifier.width(OmniHomeSpec.ToolbarGap))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                title()
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(OmniHomeSpec.ToolbarGap),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/**
 * Where the header search capsule expands from and to, in the coordinate space
 * of the overlay's host.
 *
 * [target] is the capsule's settled rectangle: the toolbar's vertical band,
 * inset by [OmniHomeSpec.ToolbarEdge] on both sides. [source] is the control
 * the user tapped (a 44dp round button) — the capsule grows out of exactly that
 * rectangle and collapses back into it, which is the reference header's
 * "rectangle morph" rather than a second, unrelated search box fading in.
 */
internal fun omniSearchMorphTarget(
    toolbarBounds: Rect?,
    rootOrigin: androidx.compose.ui.unit.IntOffset,
    rootWidthPx: Float,
    edgePx: Float,
    heightPx: Float,
): Rect {
    val bandTop = toolbarBounds?.let { it.top - rootOrigin.y }
    val bandBottom = toolbarBounds?.let { it.bottom - rootOrigin.y }
    val centre = if (bandTop != null && bandBottom != null) {
        (bandTop + bandBottom) / 2f
    } else {
        heightPx
    }
    val top = centre - heightPx / 2f
    return Rect(
        left = edgePx,
        top = top,
        right = (rootWidthPx - edgePx).coerceAtLeast(edgePx),
        bottom = top + heightPx,
    )
}

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

private fun lerpRect(from: Rect, to: Rect, t: Float): Rect = Rect(
    lerp(from.left, to.left, t),
    lerp(from.top, to.top, t),
    lerp(from.right, to.right, t),
    lerp(from.bottom, to.bottom, t),
)

/**
 * The header's source-control morph.
 *
 * The reference opens search by growing the field out of the button the user
 * pressed (`flclash/search_header.dart`, `_searchMorphSpring`: stiffness 241,
 * ratio 0.77) and shrinking it back on close, with the field's own content
 * fading in a beat later. This composes a single capsule whose RECTANGLE is
 * interpolated between the source control and the settled header slot, so the
 * surface the user touched is the surface that becomes the field — not an
 * independent floating search box that appears somewhere else on screen.
 *
 * [field] receives the content alpha to apply; the caller supplies the field
 * itself (the same [OmniSearchCapsule] the header uses).
 */
@Composable
internal fun OmniSearchMorph(
    visible: Boolean,
    source: Rect?,
    target: Rect,
    modifier: Modifier = Modifier,
    field: @Composable (alpha: Float) -> Unit,
) {
    // The source rect disappears from composition together with the control it
    // was measured from while search is open (the dock item is covered by the
    // expanded capsule), so remember the last one for the collapse.
    var lastSource by remember { mutableStateOf(source) }
    if (source != null) lastSource = source
    val morph = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) morph.animateTo(1f, OmniMotion.SearchMorph)
        else morph.animateTo(0f, OmniMotion.SearchMorph)
    }
    val progress = morph.value
    // Nothing to draw once collapsed and staying collapsed.
    if (!visible && progress <= 0.001f) return
    val from = lastSource ?: target
    val rect = lerpRect(from, target, progress)
    if (rect.width <= 1f || rect.height <= 1f) return
    // Reference: fieldOpacity morphs over 0.62s of the 0.7s open, i.e. it lags
    // the geometry instead of riding it linearly.
    val contentAlpha = ((progress - 0.22f) / 0.62f).coerceIn(0f, 1f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val offsetX = rect.left.roundToInt()
    val offsetY = rect.top.roundToInt()
    val widthDp = with(density) { rect.width.toDp() }
    val heightDp = with(density) { rect.height.toDp() }
    Box(
        modifier = modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(width = widthDp, height = heightDp),
    ) {
        field(contentAlpha)
    }
}
