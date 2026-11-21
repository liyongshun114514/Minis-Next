package com.openminis.app.ui.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Shared push/pop motion for settings and the phone's home/chat pair. */
internal object PageSlideMotion {
    private val easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    fun pushEnter() = slideInHorizontally(tween(260, easing = easing)) { it }
    fun pushExit() = slideOutHorizontally(tween(260, easing = easing)) { -it / 4 }
    fun popEnter() = slideInHorizontally(tween(260, easing = easing)) { -it / 4 }
    fun popExit() = slideOutHorizontally(tween(260, easing = easing)) { it }
}
