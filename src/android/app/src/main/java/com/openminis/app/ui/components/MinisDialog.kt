package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.glass.GlassDialog

/** Native inset dialog. Only its background scene is recorded, including through exit. */
@Composable
internal fun MinisDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val sameWindow = LocalMinisOverlayWindow.current === LocalView.current.rootView
    val locals = androidx.compose.runtime.currentCompositionLocalContext
    val scene = LocalMinisSceneBackdrop.current.takeIf { sameWindow }
    val backdrop = if (sameWindow) {
        LocalMinisPageBackdrop.current ?: LocalMinisActionBackdrop.current ?: scene?.backdrop
    } else null

    GlassDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        backdrop = backdrop,
        modifier = modifier,
        title = title,
    ) {
        CompositionLocalProvider(locals) {
            DisposableEffect(scene) {
                val release = scene?.acquire()
                onDispose { release?.invoke() }
            }
            // Keep the native dialog's dismiss handler and system/IME insets.
            CompositionLocalProvider(
                top.yukonga.miuix.kmp.theme.LocalDismissState provides onDismissRequest,
                LocalMinisPageTopInset provides 0.dp,
                content = content,
            )
        }
    }
}
