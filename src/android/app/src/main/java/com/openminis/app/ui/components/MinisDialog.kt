package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import top.yukonga.miuix.kmp.glass.GlassDialogDefaults
import top.yukonga.miuix.kmp.layout.DialogDefaults
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.glass.GlassDialog

/** Native inset dialog. Only its background scene is recorded, including through exit. */
@Composable
internal fun MinisDialog(
    show: Boolean,
    onDismissRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    titleColor: Color = DialogDefaults.titleColor(),
    summaryColor: Color = DialogDefaults.summaryColor(),
    backgroundColor: Color = GlassDialogDefaults.containerColor(),
    enableWindowDim: Boolean = true,
    outsideMargin: DpSize = DialogDefaults.outsideMargin,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val sameWindow = LocalMinisOverlayWindow.current === LocalView.current.rootView
    val locals = androidx.compose.runtime.currentCompositionLocalContext
    val scene = LocalMinisSceneBackdrop.current.takeIf { sameWindow }
    val backdrop = if (sameWindow) {
        LocalMinisPageBackdrop.current ?: LocalMinisActionBackdrop.current ?: scene?.backdrop
    } else null

    val requestDismiss = { onDismissRequest?.invoke(); Unit }
    val dialogContent: @Composable () -> Unit = {
        CompositionLocalProvider(locals) {
            DisposableEffect(scene) {
                val release = scene?.acquire()
                onDispose { release?.invoke() }
            }
            CompositionLocalProvider(
                top.yukonga.miuix.kmp.theme.LocalDismissState provides requestDismiss,
                LocalMinisDialogSurface provides true,
                LocalMinisDialogActionRole provides null,
                LocalMinisPageTopInset provides 0.dp,
                content = content,
            )
        }
    }
    if (!sameWindow) {
        // Independent Android windows retain their own host; never sample another window.
        top.yukonga.miuix.kmp.window.WindowDialog(
            show = show, onDismissRequest = onDismissRequest, onDismissFinished = onDismissFinished,
            modifier = modifier, title = title, summary = summary,
            titleColor = titleColor, summaryColor = summaryColor, backgroundColor = backgroundColor,
            enableWindowDim = enableWindowDim, outsideMargin = outsideMargin, insideMargin = insideMargin,
            content = dialogContent,
        )
    } else {
        GlassDialog(
            show = show,
            onDismissRequest = requestDismiss,
            onDismissFinished = onDismissFinished,
            backdrop = scene?.backdrop ?: backdrop,
            modifier = modifier,
            title = title,
            summary = summary,
            titleColor = titleColor,
            summaryColor = summaryColor,
            containerColor = backgroundColor,
            enableWindowDim = enableWindowDim,
            outsideMargin = outsideMargin,
            insideMargin = insideMargin,
            content = dialogContent,
        )
    }
}

/** Scoped to modal content, so inline page actions keep their native TextButton styling. */
internal val LocalMinisDialogSurface = staticCompositionLocalOf { false }
