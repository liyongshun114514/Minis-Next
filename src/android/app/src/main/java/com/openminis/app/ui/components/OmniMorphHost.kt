package com.openminis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [T-omnibot-ui-port] Optional app-level layer for the settings container
 * transform.
 *
 * ## Why this exists
 *
 * The transform is fully functional without it: every settings page paints and
 * clips its own container (`OmniMorphContainerBox`), so a row expands into its
 * destination on a plain Navigation-Compose graph. What the app level adds is the
 * reference's other half — the *outgoing* page staying visible while the container
 * grows — because only a layer above the `NavHost` can paint across a destination
 * that is being replaced.
 *
 * ## Integration (one line, owned by the navigation change)
 *
 * ```kotlin
 * // AppNavigation.kt
 * OmniMorphHost {
 *     NavHost(navController, startDestination = …) { … }
 * }
 * ```
 *
 * Everything else is already wired: the source rect is recorded by the row, the
 * clock lives here, the destination registers its real bounds from
 * `SettingsScaffold`, and the overlay is excluded from accessibility. No route,
 * callback, view model, permission or data code is touched. Until it is installed
 * `LocalOmniMorphRuntime` stays null and the destination page paints the same
 * container itself.
 */
@Composable
fun OmniMorphHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val runtime = remember(scope) { OmniMorphHostRuntime(scope) }
    val targetColor = com.openminis.app.ui.theme.OmniColors.pageBackground

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                runtime.hostOrigin = coordinates.boundsInWindow().topLeft
                runtime.hostSize = coordinates.size
            },
    ) {
        CompositionLocalProvider(LocalOmniMorphRuntime provides runtime) {
            content()
        }
        // Decorative only: never announced, never tappable, so an accessibility
        // service or a stray tap goes straight through to the page underneath.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { }
                .drawBehind {
                    val frame = runtime.frame(targetColor, density) ?: return@drawBehind
                    // The container is opaque and sits above the destination page,
                    // so it must hand the screen back as that page fades in:
                    // it dissolves over the same window in which the page body
                    // becomes opaque, instead of vanishing at the last frame.
                    val handOff = 1f - OmniMorphMotion.segment(frame.progress, 0.55f, 1f)
                    drawPath(frame.roundRectPath(), frame.color.copy(alpha = handOff))
                },
        )
    }
}

/**
 * Host-side clock. Kept separate from [OmniMorphContainerState] because it lives
 * above the `NavHost` (it survives the destination swap) while the page-side state
 * lives inside the destination that is replaced.
 */
@Stable
internal class OmniMorphHostRuntime(
    private val scope: CoroutineScope,
) : OmniMorphRuntime {
    internal var source: OmniMorphSource? by mutableStateOf(null)
    internal var target: OmniMorphTarget? by mutableStateOf(null)
    internal var hostOrigin: Offset = Offset.Zero
    internal var hostSize: IntSize = IntSize.Zero
    internal val clock = Animatable(1f)
    internal var closing by mutableStateOf(false)

    // Reads the Animatable's own snapshot state at the point of observation, so a
    // reader in the draw/layer phase invalidates only drawing.
    override val openProgress: androidx.compose.runtime.State<Float> =
        object : androidx.compose.runtime.State<Float> {
            override val value: Float get() = clock.value
        }

    override fun launch(source: OmniMorphSource, navigate: () -> Unit) {
        this.source = source
        this.target = null
        closing = false
        // The callback keeps its exact timing: it runs synchronously, before the
        // transform's first frame, exactly where the row's onClick used to.
        navigate()
        scope.launch {
            clock.snapTo(0f)
            clock.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = OmniMorphMotion.ContainerOpenMillis,
                    easing = OmniMorphMotion.ContainerEasing,
                ),
            )
        }
    }

    override fun playReturn(source: OmniMorphSource) {
        this.source = source
        this.target = null
        closing = true
        scope.launch {
            clock.snapTo(1f)
            clock.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = OmniMorphMotion.ContainerCloseMillis,
                    easing = OmniMorphMotion.ContainerReturnEasing,
                ),
            )
        }
    }

    override fun registerTarget(target: OmniMorphTarget) {
        this.target = target
    }

    override fun clearTarget() {
        // The destination records its own return hand-off before disposal
        // (OmniMorphReturnOnDispose), so the host only forgets the target.
        target = null
    }

    internal fun frame(targetColor: Color, density: Density): OmniMorphContainerFrame? {
        val source = source ?: return null
        val progress = clock.value
        if (!closing && progress >= 1f) return null
        if (closing && progress <= 0f) return null
        if (hostSize.width == 0 || hostSize.height == 0) return null
        val full = Rect(Offset.Zero, Size(hostSize.width.toFloat(), hostSize.height.toFloat()))
        val targetRect = target?.let {
            Rect(
                left = it.bounds.left - hostOrigin.x,
                top = it.bounds.top - hostOrigin.y,
                right = it.bounds.right - hostOrigin.x,
                bottom = it.bounds.bottom - hostOrigin.y,
            )
        } ?: full
        val sourceRect = Rect(
            left = source.bounds.left - hostOrigin.x,
            top = source.bounds.top - hostOrigin.y,
            right = source.bounds.right - hostOrigin.x,
            bottom = source.bounds.bottom - hostOrigin.y,
        )
        val rect = OmniMorphMotion.rectAt(sourceRect, targetRect, progress)
        val radius = OmniMorphMotion.radiusAt(
            sourceRadius = with(density) { source.cornerRadius.toPx() },
            targetRadius = with(density) { OmniMorphMotion.PageRadius.toPx() },
            fraction = progress,
            rect = rect,
        )
        val startColor = source.color.takeIf { it != Color.Unspecified } ?: targetColor
        return OmniMorphContainerFrame(
            progress = progress,
            rect = rect,
            cornerRadiusPx = radius,
            color = OmniMorphMotion.colorAt(
                source = startColor,
                target = targetColor,
                fraction = OmniMorphMotion.containerFade(progress, closing = closing),
            ),
            targetColor = targetColor,
        )
    }
}
