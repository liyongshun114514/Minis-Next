package com.openminis.app.ui.components

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.ToolbarPosition
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared page host for a stable themed surface and consistent safe-area handoff. */
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
    recordBodyForActions: Boolean = true,
    overlapTopBar: Boolean = false,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = if (recordBodyForActions && isRuntimeShaderSupported()) rememberLayerBackdrop {
        drawRect(containerColor)
        drawContent()
    } else null
    CompositionLocalProvider(LocalMinisPageWindow provides androidx.compose.ui.platform.LocalView.current.rootView) {
    top.yukonga.miuix.kmp.basic.Scaffold(
        modifier = modifier,
        topBar = { CompositionLocalProvider(LocalMinisActionBackdrop provides backdrop, content = topBar) },
        bottomBar = { CompositionLocalProvider(LocalMinisActionBackdrop provides backdrop, content = bottomBar) },
        floatingActionButton = { CompositionLocalProvider(LocalMinisActionBackdrop provides backdrop, content = floatingActionButton) },
        floatingActionButtonPosition = floatingActionButtonPosition,
        floatingToolbar = floatingToolbar,
        floatingToolbarPosition = floatingToolbarPosition,
        snackbarHost = snackbarHost,
        containerColor = containerColor,
        contentWindowInsets = contentWindowInsets,
        content = { padding ->
            // Sampling controls live in Scaffold slots, outside this opaque body recording.
            Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
                // A sheet or menu opened from this page samples the recorded
                // body instead of the overlay host it is drawn into.
                CompositionLocalProvider(
                    LocalMinisActionBackdrop provides null,
                    LocalMinisPageBackdrop provides backdrop,
                    LocalMinisPageTopInset provides if (overlapTopBar) padding.calculateTopPadding() else 0.dp,
                ) {
                    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
                    content(if (overlapTopBar) PaddingValues(
                        start = padding.calculateStartPadding(direction),
                        end = padding.calculateEndPadding(direction),
                        bottom = padding.calculateBottomPadding(),
                    ) else padding)
                }
            }
        },
    )
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
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        menuMotion = menuMotion,
        size = size,
        onLongClick = onLongClick,
        content = content,
    )
}
