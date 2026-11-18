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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.miuix.TopAppBar
import com.openminis.app.ui.components.miuix.TopAppBarDefaults
import com.openminis.app.ui.theme.ChatColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.glass.GlassIconButton

private val ChatTopBlur = ProgressiveBlur(startFraction = 0.42f, curve = 0.65f)

/** Draws over the message viewport without sampling the title, controls, or composer. */
@Composable
internal fun ChatGlassTopBar(
    backdrop: LayerBackdrop?,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val background = ChatColors.background
    // The messages can draw behind the bar, but a tap on an empty part of the bar must not
    // activate a link or message underneath. Child controls still get the tap first.
    Box(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onTap = {}) }) {
        if (backdrop != null) {
            Box(
                Modifier.matchParentSize().progressiveTextureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    blurRadius = 38f,
                    gradient = ChatTopBlur,
                ),
            )
        }
        Box(
            Modifier.matchParentSize().drawWithCache {
                // A fading theme tint keeps the title readable; on older Android it is the
                // entire effect, not an opaque white toolbar at the bottom of the viewport.
                val tint = Brush.verticalGradient(
                    0f to background.copy(alpha = if (backdrop == null) 0.98f else 0.85f),
                    0.62f to background.copy(alpha = if (backdrop == null) 0.92f else 0.65f),
                    1f to Color.Transparent,
                )
                onDrawBehind { drawRect(tint) }
            },
        )
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            // Preserve the title/subtitle room and navigation slot used by the chat layout.
            expandedHeight = 80.dp,
        )
    }
}

/** With no backdrop, Miuix's same button uses a flat fill rather than a runtime shader. */
@Composable
internal fun ChatGlassIconButton(
    backdrop: LayerBackdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassIconButton(
        onClick = onClick,
        modifier = modifier.semantics { role = Role.Button },
        backdrop = backdrop,
        size = 48.dp,
        content = content,
    )
}
