package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.ToolbarPosition
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalPageActionBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Capture only the body: neither toolbar buttons nor floating actions sample themselves. */
@Composable
internal fun MinisPageScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    floatingToolbar: @Composable () -> Unit = {},
    floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
    snackbarHost: @Composable () -> Unit = {},
    containerColor: Color = MiuixTheme.colorScheme.surface,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = if (isRuntimeShaderSupported()) rememberLayerBackdrop() else null
    CompositionLocalProvider(LocalPageActionBackdrop provides backdrop) {
        top.yukonga.miuix.kmp.basic.Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = floatingActionButtonPosition,
            floatingToolbar = floatingToolbar,
            floatingToolbarPosition = floatingToolbarPosition,
            snackbarHost = snackbarHost,
            containerColor = containerColor,
            contentWindowInsets = contentWindowInsets,
        ) { padding ->
            Box(Modifier.fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(containerColor)) { content(padding) }
        }
    }
}

/** Right-side icon actions share one material and one spring; textual actions stay textual. */
@Composable
internal fun MinisToolbarIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    menuMotion: Boolean = false,
    size: Dp = 48.dp,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    MinisGlassIconButton(
        backdrop = LocalPageActionBackdrop.current,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        menuMotion = menuMotion,
        size = size,
        onLongClick = onLongClick,
        content = content,
    )
}
