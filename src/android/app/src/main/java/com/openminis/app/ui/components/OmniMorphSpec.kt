package com.openminis.app.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.OmniColors
import com.openminis.app.ui.theme.OmniDimens

/**
 * [T-omnibot-ui-port] The measured source control a container transform starts
 * from (a settings row, a menu's owning button, a toolbar control).
 *
 * [bounds] is in **window coordinates** (`LayoutCoordinates.boundsInWindow()`),
 * which is the only space shared by the page that owns the control, the page it
 * expands into, and an optional app-level overlay. Whoever draws the transform
 * converts it once, locally, so a status-bar / cutout / IME inset can never make
 * the container jump.
 */
@Immutable
data class OmniMorphSource(
    val bounds: Rect,
    /** The control's own corner radius (22dp for a row inside a grouped card). */
    val cornerRadius: Dp = OmniMorphMotion.RowSourceRadius,
    /** The surface the control paints — interpolated into the destination's. */
    val color: Color = Color.Unspecified,
)

/**
 * [T-omnibot-ui-port] Where a container transform ends: the destination page.
 * Registered by [com.openminis.app.ui.settings.SettingsScaffold] on first layout
 * (window coordinates), so the host overlay can grow into the *real* page rect
 * instead of the raw window.
 */
@Immutable
data class OmniMorphTarget(
    val bounds: Rect,
    val cornerRadius: Dp = OmniMorphMotion.PageRadius,
    val color: Color = Color.Unspecified,
)

/**
 * [T-omnibot-ui-port] Global layer entry point for the settings container
 * transform, so the navigation owner can keep the outgoing page visible while it
 * shrinks.
 *
 * This branch does **not** modify `AppNavigation`: the morph is fully
 * self-contained inside the destination page (see `OmniMorphContainer.kt`), and
 * this interface is the one-line hook for the integration owner —
 *
 * ```kotlin
 * OmniMorphHost {            // wraps NavHost, in AppNavigation.kt
 *     NavHost(...)
 * }
 * ```
 *
 * With the host installed the container surface is painted once, above the
 * NavHost, while the destination page registers its real bounds through
 * [registerTarget]. Without it nothing is lost: the destination page paints and
 * clips the same surface itself.
 */
interface OmniMorphRuntime {
    /**
     * The in-flight container progress (0 = the row's rect, 1 = the whole page),
     * exposed as Compose [State] so a destination page can phase its own content
     * against the app-level clock in the draw and layer phases — no recomposition
     * per frame, and no second clock that could drift from the painted container.
     */
    val openProgress: androidx.compose.runtime.State<Float>

    /** Records [source], starts the container transform and runs the navigation. */
    fun launch(source: OmniMorphSource, navigate: () -> Unit)

    /** The destination page's real bounds, in window coordinates. */
    fun registerTarget(target: OmniMorphTarget)

    /**
     * Plays the reverse transform: the container shrinks from the page back into
     * the source row while the page underneath is revealed. Called by the page
     * that consumes a return hand-off, so "back / cancel / save" restores with the
     * same 300ms flipped curve as the expansion.
     */
    fun playReturn(source: OmniMorphSource)

    /** The destination page left the composition (pop / back / cancel / save). */
    fun clearTarget()
}

/** Null when no app-level host is installed — the normal, self-contained case. */
val LocalOmniMorphRuntime = staticCompositionLocalOf<OmniMorphRuntime?> { null }

/**
 * [T-omnibot-ui-port] Hand-off between the page that owns a source control and
 * the page it expands into.
 *
 * Navigation Compose disposes the destination's composition before the new page
 * mounts, and the `onClick` callbacks are plain `() -> Unit` lambdas that must keep
 * working for every existing caller, so the two sides cannot hand a `Rect` over
 * through a parameter. A tiny single-slot bridge carries it instead, with a
 * freshness window: a request that no page consumed is dropped rather than
 * replayed as a surprise animation minutes later.
 *
 * Written from the main thread only (click handlers, composition effects).
 */
object OmniMorphBridge {
    private const val FRESHNESS_MILLIS = 1_500L

    private class Entry(val source: OmniMorphSource, val atMillis: Long)

    @Volatile private var open: Entry? = null
    @Volatile private var returnTo: Entry? = null

    /** A row/control was tapped: the *next* mounted settings page may expand out of it. */
    fun recordOpen(source: OmniMorphSource, nowMillis: Long = now()) {
        open = Entry(source, nowMillis)
        returnTo = null
    }

    /** A destination page was popped: the *next* mounted page may reveal itself from the row. */
    fun recordReturn(source: OmniMorphSource, nowMillis: Long = now()) {
        returnTo = Entry(source, nowMillis)
        open = null
    }

    /** Consumes a pending expansion, if it is still fresh. */
    fun consumeOpen(nowMillis: Long = now()): OmniMorphSource? {
        val entry = open ?: return null
        open = null
        return entry.source.takeIf { fresh(entry, nowMillis) }
    }

    /** Consumes a pending return-reveal, if it is still fresh. */
    fun consumeReturn(nowMillis: Long = now()): OmniMorphSource? {
        val entry = returnTo ?: return null
        returnTo = null
        return entry.source.takeIf { fresh(entry, nowMillis) }
    }

    /** Test/preview helper: drops both slots. */
    fun clear() {
        open = null
        returnTo = null
    }

    private fun fresh(entry: Entry, nowMillis: Long): Boolean =
        nowMillis - entry.atMillis in 0..FRESHNESS_MILLIS

    private fun now(): Long = System.nanoTime() / 1_000_000L
}

/**
 * [T-omnibot-ui-port] Navigates through the container transform when a runtime is
 * installed and always records the source for the destination page.
 *
 * Rows call this instead of their `onClick` directly; the callback itself is
 * invoked unchanged (same timing, same thread, same intent), so every business
 * callback keeps its contract whether or not the visual host is present.
 */
fun omniMorphNavigate(source: OmniMorphSource, onClick: () -> Unit) {
    OmniMorphBridge.recordOpen(source)
    onClick()
}

/**
 * Fallback surface colour for a source control whose caller did not state one:
 * the grouped-card surface the control is painted on.
 */
val OmniMorphSourceColor: Color
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = OmniColors.surfacePrimary

/** Default corner radius of a settings row's container (the grouped card). */
val OmniMorphRowRadius: Dp = OmniDimens.SurfaceRadius
