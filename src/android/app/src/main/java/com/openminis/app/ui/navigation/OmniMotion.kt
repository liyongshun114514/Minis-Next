package com.openminis.app.ui.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Native presentation parameters corresponding to the reference page motion.
 * See docs/ui-port/provenance.md. Navigation, back dispatch and state owners stay
 * in Navigation Compose; this is not an interactive Flutter route implementation.
 */
internal object OmniMotion {
    const val PageDuration = 450
    const val FadeInDelay = 68 // round(450 * 0.15)
    const val FadeInDuration = 180 // 450 * (0.55 - 0.15)
    const val FadeOutDuration = 135 // 450 * 0.30
    val FadeInEasing: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    // Flutter's two-segment easeInOutCubicEmphasized, rather than the common
    // single cubic approximation. Solve x(t) monotonically in each segment.
    val PageEasing: Easing = Easing { fraction ->
        val x = fraction.coerceIn(0f, 1f)
        if (x <= 0f || x >= 1f) x else if (x <= 0.166666f) {
            solve(x, 0f, 0f, 0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        } else {
            solve(x, 0.166666f, 0.4f, 0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        }
    }

    // ── Root tab pages (`flclash/tab_view.dart`) ─────────────────────────────
    /** `FlClashTabView.duration` (`kTabScrollDuration`) with `Curves.easeOut`. */
    const val TabSlideDuration = 400
    val TabEasing: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    // ── Dock lens / press (`flclash/navigation_dock.dart`) ───────────────────
    //
    // The reference expresses these as Flutter `SpringDescription`s. Compose
    // takes (dampingRatio, stiffness) for the same mass-spring system, so the
    // numbers below are the reference springs converted, not re-invented:
    //   SpringDescription.withDurationAndBounce(duration, bounce)  ⇒
    //     dampingRatio = 1 - bounce,  stiffness = (2π / duration)²
    //   SpringDescription.withDampingRatio(stiffness:, ratio:)      ⇒
    //     dampingRatio = ratio,       stiffness = stiffness
    /** `_travelSpring`: 400ms, bounce 0.15 — the selection lens between items. */
    val DockLensTravel: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.85f, stiffness = 246f)

    /** `_followSpring`: stiffness 1024, ratio 0.9 — the lens following a drag. */
    val DockLensFollow: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.9f, stiffness = 1024f)

    /** `_jellySpring`: mass 1, stiffness 100, ratio 0.4 — the lens's jelly. */
    val DockJelly: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.4f, stiffness = 100f)

    /** `_pressSpring`: stiffness 311, ratio 0.593 — glyph press scale. */
    val DockPress: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.593f, stiffness = 311f)

    /** `_liftSpring`: 250ms — the pressed item lifting off the bar. */
    val DockLift: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 160f)

    /**
     * Interactive back settles in 350ms in the reference page (the push is
     * 450ms, [PageDuration]). Navigation 2.8 seeks the pop itself while the
     * gesture is live (`PredictiveBackHandler` inside `NavHost`), so this is the
     * duration of the COMMITTED pop animation only.
     */
    const val BackSettleDuration = 350

    // ── Header search morph (`flclash/search_header.dart`) ───────────────────
    /** `_searchMorphSpring`: stiffness 241, ratio 0.77 — the capsule rect morph. */
    val SearchMorph: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.77f, stiffness = 241f)

    /** `_SearchFrame` fieldOpacity morph phase (0.62s in, delayed 0.2s). */
    val SearchFieldFadeIn: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 320f)
    const val SearchFieldFadeDelay = 90

    private fun solve(x: Float, x0: Float, y0: Float, x1: Float, y1: Float,
                      x2: Float, y2: Float, x3: Float, y3: Float): Float {
        var low = 0f
        var high = 1f
        repeat(22) {
            val t = (low + high) / 2f
            if (cubic(t, x0, x1, x2, x3) < x) low = t else high = t
        }
        return cubic((low + high) / 2f, y0, y1, y2, y3)
    }

    private fun cubic(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
        val s = 1f - t
        return s * s * s * a + 3f * s * s * t * b + 3f * s * t * t * c + t * t * t * d
    }
}
