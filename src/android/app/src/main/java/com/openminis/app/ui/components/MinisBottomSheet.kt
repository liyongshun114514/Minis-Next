package com.openminis.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassBottomSheetDefaults
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.theme.LocalDismissState

/**
 * One Miuix sheet policy: native glass surface, gestures, insets and exit.
 * Root scene capture excludes the root popup host/scrim, including nested sheets.
 * Legacy callers with show=true request dismissal before dropping their content;
 * explicit show callers retain their existing onDismissFinished contract.
 */
@Composable
fun MinisBottomSheet(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
    backgroundColor: Color = GlassBottomSheetDefaults.containerColor(),
    enableWindowDim: Boolean = true,
    cornerRadius: Dp = BottomSheetDefaults.cornerRadius,
    sheetMaxWidth: Dp = BottomSheetDefaults.maxWidth,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    outsideMargin: DpSize = BottomSheetDefaults.outsideMargin,
    insideMargin: DpSize = BottomSheetDefaults.insideMargin,
    defaultWindowInsetsPadding: Boolean = true,
    dragHandleColor: Color = BottomSheetDefaults.dragHandleColor(),
    allowDismiss: Boolean = true,
    enableNestedScroll: Boolean = true,
    renderInRootScaffold: Boolean = true,
    deferDismissRequest: Boolean = false,
    // A page recording (bars included, sheet excluded) beats the shared scene:
    // sampling the scene from inside the overlay host would read the sheet itself.
    backdrop: Backdrop? = null,
    content: @Composable () -> Unit,
) {
    val sameWindow = LocalMinisOverlayWindow.current === LocalView.current.rootView
    val locals = androidx.compose.runtime.currentCompositionLocalContext
    val scene = LocalMinisSceneBackdrop.current.takeIf { sameWindow }
    val surfaceBackdrop = backdrop ?: LocalMinisActionBackdrop.current ?: scene?.backdrop
    // A new controlled show session cancels an older internal close.
    var closing by remember(show) { mutableStateOf(false) }
    val currentShow by rememberUpdatedState(show)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val currentFinished by rememberUpdatedState(onDismissFinished)
    val requestDismiss: () -> Unit = {
        if (deferDismissRequest) closing = true else currentDismiss?.invoke()
    }
    var completionSent by remember(show) { mutableStateOf(false) }
    val finishDismiss: () -> Unit = {
        if ((!currentShow || closing) && !completionSent) {
            completionSent = true
            if (closing && currentShow) currentDismiss?.invoke()
            currentFinished?.invoke()
        }
    }
    val sheetContent: @Composable () -> Unit = {
        CompositionLocalProvider(locals) {
            // Native content survives through exit. Its disposal, not requested
            // visibility, releases recording even after a rapid open/close.
            DisposableEffect(scene) {
                val release = scene?.acquire()
                onDispose { release?.invoke() }
            }
            CompositionLocalProvider(LocalDismissState provides requestDismiss, content = content)
        }
    }
    // A surface launched inside an independent Android Dialog must not move
    // under that window or sample another window's RenderNode. This is still
    // a native Miuix sheet, with its readable opaque platform fallback.
    if (!sameWindow) {
        top.yukonga.miuix.kmp.window.WindowBottomSheet(
            show = show && !closing,
            modifier = modifier,
            title = title,
            startAction = startAction,
            endAction = endAction,
            backgroundColor = backgroundColor,
            enableWindowDim = enableWindowDim,
            cornerRadius = cornerRadius,
            sheetMaxWidth = sheetMaxWidth,
            onDismissRequest = requestDismiss,
            onDismissFinished = finishDismiss,
            outsideMargin = outsideMargin,
            insideMargin = insideMargin,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding,
            dragHandleColor = dragHandleColor,
            allowDismiss = allowDismiss,
            enableNestedScroll = enableNestedScroll,
        ) {
            CompositionLocalProvider(LocalDismissState provides requestDismiss, content = content)
        }
        return
    }
    GlassBottomSheet(
        show = show && !closing,
        onDismissRequest = requestDismiss,
        backdrop = surfaceBackdrop,
        modifier = modifier,
        title = title,
        startAction = startAction,
        endAction = endAction,
        containerColor = backgroundColor,
        enableWindowDim = enableWindowDim,
        cornerRadius = cornerRadius,
        sheetMaxWidth = sheetMaxWidth,
        outsideMargin = outsideMargin,
        insideMargin = insideMargin,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        dragHandleColor = dragHandleColor,
        allowDismiss = allowDismiss,
        enableNestedScroll = enableNestedScroll,
        renderInRootScaffold = renderInRootScaffold,
        onDismissFinished = finishDismiss,
        content = sheetContent,
    )
}
