package com.openminis.app.ui.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.ChatColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import com.openminis.app.ui.components.MinisGlassTopBar
import com.openminis.app.ui.components.RichTitleAppBar

/** Draws over the message viewport without sampling the title, controls, or composer. */
@Composable
internal fun ChatGlassTopBar(
    backdrop: LayerBackdrop?,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    MinisGlassTopBar(backdrop = backdrop, background = ChatColors.background) {
        // Block only the foreground bar; the fading overhang must not eat message gestures.
        Box(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onTap = {}) }) {
            RichTitleAppBar(
                title = title,
                navigationIcon = navigationIcon,
                actions = actions,
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                color = Color.Transparent,
                blurBackground = false,
                // Preserve the title/subtitle room and navigation slot used by the chat layout.
                expandedHeight = 80.dp,
            )
        }
    }
}
