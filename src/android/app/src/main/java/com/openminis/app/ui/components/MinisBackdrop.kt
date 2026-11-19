package com.openminis.app.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/** A page bar samples only its body, never the bar/buttons that consume it. */
internal val LocalMinisActionBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** A scene is recorded only while an in-window menu owns a lease, including its exit. */
@Stable
internal class MinisSceneBackdrop(val backdrop: LayerBackdrop) {
    private var consumers by mutableIntStateOf(0)
    val recording: Boolean get() = consumers > 0

    fun acquire(): () -> Unit {
        consumers++
        var released = false
        return {
            if (!released) {
                released = true
                consumers--
            }
        }
    }
}

internal val LocalMinisSceneBackdrop = staticCompositionLocalOf<MinisSceneBackdrop?> { null }
