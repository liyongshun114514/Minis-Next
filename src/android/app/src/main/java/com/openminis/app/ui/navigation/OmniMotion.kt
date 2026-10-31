package com.openminis.app.ui.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

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
