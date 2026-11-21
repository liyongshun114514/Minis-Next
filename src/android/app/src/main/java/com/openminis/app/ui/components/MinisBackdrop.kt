package com.openminis.app.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/** Only siblings outside a capture may sample it (top/bottom bars and floating actions). */
internal val LocalMinisActionBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Window owning this page capture, including independent preview dialogs. */
internal val LocalMinisPageWindow = staticCompositionLocalOf<android.view.View?> { null }

/** A body may hand this to an overlay, but must never sample its own recording inline. */
internal val LocalMinisPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** A scene is recorded only while an in-window menu owns a lease, including its exit. */
@Stable
internal class MinisSceneBackdrop(val backdrop: LayerBackdrop) {
    private var consumers by mutableIntStateOf(0)
    private var modalConsumers by mutableIntStateOf(0)
    val recording: Boolean get() = consumers > 0
    val backgroundBlurred: Boolean get() = modalConsumers > 0

    fun acquire(blurBackground: Boolean = false): () -> Unit {
        consumers++
        if (blurBackground) modalConsumers++
        var released = false
        return {
            if (!released) {
                released = true
                consumers--
                if (blurBackground) modalConsumers--
            }
        }
    }
}

internal val LocalMinisSceneBackdrop = staticCompositionLocalOf<MinisSceneBackdrop?> { null }
