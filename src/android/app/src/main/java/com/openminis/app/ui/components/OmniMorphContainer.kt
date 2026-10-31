package com.openminis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

/**
 * [T-omnibot-ui-port] Bounds of a settings row (or any control) that can expand
 * into a page, kept in **window coordinates** so the destination page and an
 * app-level overlay can both use it.
 *
 * A row refreshes this from `onGloballyPositioned`, which runs after every layout
 * pass — scrolling, a font-scale change, rotation or a keyboard inset all update
 * the rect before the next tap reads it.
 */
@Stable
class OmniMorphRowSource {
    var bounds: Rect by mutableStateOf(Rect.Zero)
        internal set

    /** Snapshots the current bounds for a click that is about to navigate. */
    fun snapshot(cornerRadius: Dp, color: Color): OmniMorphSource =
        OmniMorphSource(bounds = bounds, cornerRadius = cornerRadius, color = color)
}

/** Remember one row's measured source bounds. */
@Composable
fun rememberOmniMorphRowSource(): OmniMorphRowSource = remember { OmniMorphRowSource() }

/**
 * Keeps [target] in sync with this node's window bounds. Applied to the row's
 * clickable container, so the measured rect is the row the user actually tapped
 * (not the column that also owns its divider).
 */
fun Modifier.omniMorphSource(target: OmniMorphRowSource): Modifier =
    this.onGloballyPositioned { coordinates -> target.bounds = coordinates.boundsInWindow() }

/**
 * [T-omnibot-ui-port] The measured container transform of a destination page: the
 * tapped settings row's rect grows into the page over 300ms `fastOutSlowIn`, the
 * page is revealed through that expanding rounded rect, and the return (back /
 * cancel / save) grows the page underneath back out of the same rect.
 *
 * Deliberately independent of Navigation Compose: `AppNavigation` is owned by
 * another change and every `onClick` / `onBack` lambda must keep its exact
 * signature and timing. The rect is handed over through [OmniMorphBridge] and
 * painted locally, so the transform is real without any global wiring;
 * [OmniMorphRuntime] is only the optional cross-root upgrade.
 */
@Stable
class OmniMorphContainerState {
    /** The rect the container started from, in window coordinates. */
    var source: OmniMorphSource? by mutableStateOf(null)
        private set

    internal var sourceRect = Rect.Zero
    internal var hostOrigin = Offset.Zero
    internal var hostSize = Size.Zero

    /** 0 = the row's rect, 1 = the whole page. */
    internal val progress = Animatable(1f)

    /** True while the container is interpolating. */
    var running: Boolean by mutableStateOf(false)
        private set

    val isActive: Boolean get() = running || progress.value < 1f

    /** Records the source rect, converting the window rect into page-local space. */
    fun begin(source: OmniMorphSource) {
        this.source = source
        reproject()
    }

    internal fun updateHost(origin: Offset, size: IntSize) {
        if (hostOrigin == origin && hostSize.width == size.width.toFloat() &&
            hostSize.height == size.height.toFloat()
        ) {
            return
        }
        hostOrigin = origin
        hostSize = Size(size.width.toFloat(), size.height.toFloat())
        // Re-project once the page's own window origin is known: the first frame
        // records the source before this node has been placed, which would
        // otherwise offset the container by the status-bar inset.
        reproject()
    }

    private fun reproject() {
        val s = source ?: return
        sourceRect = Rect(
            left = s.bounds.left - hostOrigin.x,
            top = s.bounds.top - hostOrigin.y,
            right = s.bounds.right - hostOrigin.x,
            bottom = s.bounds.bottom - hostOrigin.y,
        )
    }

    /** Animates the row's rect out to the full page (300ms, fastOutSlowIn). */
    suspend fun expand() {
        if (progress.value >= 1f) return
        running = true
        try {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = OmniMorphMotion.ContainerOpenMillis,
                    easing = OmniMorphMotion.ContainerEasing,
                ),
            )
        } finally {
            running = false
        }
    }

    /** Drops back to the row's rect (300ms, flipped curve). */
    suspend fun collapse() {
        if (progress.value <= 0f) return
        running = true
        try {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = OmniMorphMotion.ContainerCloseMillis,
                    easing = OmniMorphMotion.ContainerReturnEasing,
                ),
            )
        } finally {
            running = false
        }
    }

    /**
     * The container outline for the current frame: interpolated size, corner
     * radius and colour between the source control and the page. Null once the
     * page has settled, so a settled page draws nothing extra.
     */
    fun container(targetColor: Color, density: Density): OmniMorphContainerFrame? {
        if (hostSize.width <= 0f || hostSize.height <= 0f) return null
        val p = progress.value
        if (p >= 1f) return null
        val target = Rect(Offset.Zero, hostSize)
        val rect = OmniMorphMotion.rectAt(sourceRect, target, p)
        val radius = OmniMorphMotion.radiusAt(
            sourceRadius = with(density) {
                (source?.cornerRadius ?: OmniMorphMotion.PageRadius).toPx()
            },
            targetRadius = with(density) { OmniMorphMotion.PageRadius.toPx() },
            fraction = p,
            rect = rect,
        )
        val startColor = source?.color?.takeIf { it != Color.Unspecified } ?: targetColor
        return OmniMorphContainerFrame(
            progress = p,
            rect = rect,
            cornerRadiusPx = radius,
            color = OmniMorphMotion.colorAt(
                source = startColor,
                target = targetColor,
                fraction = OmniMorphMotion.containerFade(p, closing = false),
            ),
            targetColor = targetColor,
        )
    }

    /**
     * When an app-level host paints the container, the page's phases follow the
     * host clock instead of the local one; otherwise [progress].
     */
    internal var hostProgress: androidx.compose.runtime.State<Float>? = null

    private fun phase(): Float = hostProgress?.value ?: progress.value

    /** Top-bar phase: settles first. */
    fun headerAlpha(): Float = OmniMorphMotion.headerAlpha(phase())

    /** Page-body phase: follows the bar. */
    fun contentAlpha(): Float = OmniMorphMotion.firstBlockAlpha(phase())
}

/** One drawn frame of the container transform. */
data class OmniMorphContainerFrame(
    val progress: Float,
    val rect: Rect,
    val cornerRadiusPx: Float,
    val color: Color,
    val targetColor: Color,
)

/**
 * Consumes a pending container hand-off once, on first composition of a settings
 * page, and expands into the page.
 *
 * [enabled] lets a page opt out; the default is on.
 */
@Composable
fun rememberOmniMorphContainer(enabled: Boolean = true): OmniMorphContainerState {
    val state = remember { OmniMorphContainerState() }
    val scope = rememberCoroutineScope()
    val runtime = LocalOmniMorphRuntime.current
    LaunchedEffect(Unit) {
        if (!enabled) return@LaunchedEffect
        val opening = OmniMorphBridge.consumeOpen()
        if (opening != null) {
            state.begin(opening)
            // With an app-level host the container is painted above the NavHost
            // by OmniMorphHostRuntime; the page only fades its content in.
            if (runtime == null) {
                scope.launch { state.expand() }
            } else {
                // Phase the page's own content against the host's painted clock.
                state.hostProgress = runtime.openProgress
            }
            return@LaunchedEffect
        }
        val returning = OmniMorphBridge.consumeReturn() ?: return@LaunchedEffect
        state.begin(returning)
        if (runtime != null) {
            // Reverse transform: the host shrinks the container back into the row
            // while this page is already whole underneath.
            runtime.playReturn(returning)
        } else {
            // Self-contained reveal: the page grows out of the row again.
            scope.launch { state.expand() }
        }
    }
    return state
}

/**
 * Paints the expanding container and keeps the host geometry current. Sits at the
 * bottom of the destination page's root Box.
 */
@Composable
fun OmniMorphContainerSurface(
    state: OmniMorphContainerState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val targetColor = com.openminis.app.ui.theme.OmniColors.pageBackground
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                state.updateHost(coordinates.boundsInWindow().topLeft, coordinates.size)
            }
            .drawBehind {
                val frame = state.container(targetColor, density) ?: return@drawBehind
                drawPath(frame.roundRectPath(), frame.color)
            },
    )
}

internal fun OmniMorphContainerFrame.roundRectPath(): Path = Path().apply {
    addRoundRect(RoundRect(rect = rect, cornerRadius = CornerRadius(cornerRadiusPx)))
}

/**
 * Reveals [this] page through the container: clipped to the animated rounded rect
 * and faded per phase. Both are read in the draw/layer phase only, so the 300ms
 * transform never recomposes the page subtree.
 */
fun Modifier.omniMorphPage(
    state: OmniMorphContainerState,
    targetColor: Color,
    density: Density,
): Modifier = this
    .graphicsLayer { alpha = state.contentAlpha() }
    .drawWithContent {
        val frame = state.container(targetColor, density)
        if (frame == null) {
            drawContent()
            return@drawWithContent
        }
        clipPath(frame.roundRectPath()) { this@drawWithContent.drawContent() }
    }

/** Alpha the top bar uses while the page is still expanding. */
fun Modifier.omniMorphHeaderAlpha(state: OmniMorphContainerState): Modifier =
    this.graphicsLayer { alpha = state.headerAlpha() }

/**
 * Wraps a destination page in the container transform: page background, expanding
 * container, then the page revealed through it.
 *
 * The page background is painted first so the frame between "row rect" and "page
 * rect" never flashes the window background. The container then interpolates the
 * source surface colour into the page colour, and the page fades in over it.
 */
@Composable
fun OmniMorphContainerBox(
    state: OmniMorphContainerState,
    modifier: Modifier = Modifier,
    /**
     * False when an app-level [OmniMorphHost] paints the container above the
     * NavHost: this page then keeps the page background and the phased fade, but
     * does not draw or clip a second container.
     */
    drawContainer: Boolean = true,
    content: @Composable () -> Unit,
) {
    val background = com.openminis.app.ui.theme.OmniColors.pageBackground
    val density = LocalDensity.current
    if (!drawContainer) {
        // App-level host paints the container above the NavHost, so this page
        // cannot clip itself. Only its CONTENT fades over the always-opaque page
        // background — otherwise the window background would show through the
        // page while the host's container is still small.
        Box(modifier = modifier.fillMaxSize().drawBehind { drawRect(background) }) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = state.contentAlpha() }) {
                content()
            }
        }
        return
    }
    Box(modifier.fillMaxSize().drawBehind { drawRect(background) }) {
        OmniMorphContainerSurface(state)
        Box(Modifier.fillMaxSize().omniMorphPage(state, background, density)) {
            content()
        }
    }
}

/**
 * Records the return hand-off for the page being dismissed, so the page underneath
 * grows back out of the row the user came from.
 *
 * Called from the destination page's dispose (pop, system back, cancel, save)
 * before its composition is torn down; the source page consumes it on mount.
 */
@Composable
fun OmniMorphReturnOnDispose(state: OmniMorphContainerState) {
    DisposableEffect(Unit) {
        onDispose {
            state.source?.let { OmniMorphBridge.recordReturn(it) }
        }
    }
}
