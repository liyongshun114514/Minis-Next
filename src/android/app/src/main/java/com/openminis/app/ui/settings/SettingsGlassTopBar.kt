package com.openminis.app.ui.settings

import androidx.compose.ui.unit.constrainHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import com.openminis.app.ui.components.MinisGlassIconButton
import com.openminis.app.ui.components.MinisGlassTopBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsGlassTopBar(
    title: String,
    backdrop: LayerBackdrop?,
    overlap: () -> Float,
    centerTitle: Boolean,
    onBack: (() -> Unit)?,
    navigation: @Composable (() -> Unit)?,
    actions: @Composable (() -> Unit)?,
) {
    val colors = MiuixTheme.colorScheme
    MinisGlassTopBar(
        backdrop = backdrop,
        background = colors.surface,
        modifier = Modifier.fillMaxWidth(),
        backgroundAlpha = overlap,
    ) {
        Layout(
            modifier = Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp)
                .heightIn(min = 60.dp)
                .padding(vertical = 5.dp),
            content = {
                Box(contentAlignment = Alignment.CenterStart) {
                    when {
                        navigation != null -> navigation()
                        onBack != null -> SettingsGlassBackButton(backdrop, onBack)
                    }
                }
                Box(contentAlignment = if (centerTitle) Alignment.Center else Alignment.CenterStart) {
                    Text(
                        text = title,
                        modifier = Modifier.semantics { heading() },
                        style = MiuixTheme.textStyles.title3,
                        color = colors.onSurface,
                        textAlign = if (centerTitle) TextAlign.Center else TextAlign.Start,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) { actions?.invoke() }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val edge = loose.copy(maxWidth = width / 3)
            val start = measurables[0].measure(edge)
            val end = measurables[2].measure(edge)
            val gap = 10.dp.roundToPx()
            val startSpace = if (start.width > 0) start.width + gap else 0
            val endSpace = if (end.width > 0) end.width + gap else 0
            val reserved = if (centerTitle) 2 * maxOf(startSpace, endSpace) else startSpace + endSpace
            val label = measurables[1].measure(loose.copy(maxWidth = (width - reserved).coerceAtLeast(0)))
            val height = constraints.constrainHeight(maxOf(start.height, end.height, label.height))
            layout(width, height) {
                start.placeRelative(0, (height - start.height) / 2)
                label.placeRelative(if (centerTitle) (width - label.width) / 2 else startSpace, (height - label.height) / 2)
                end.placeRelative(width - end.width, (height - end.height) / 2)
            }
        }
    }
}

@Composable
private fun SettingsGlassBackButton(backdrop: LayerBackdrop?, onBack: () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    MinisGlassIconButton(onClick = onBack, backdrop = backdrop) {
        Icon(
            imageVector = MiuixIcons.ChevronBackward,
            contentDescription = stringResource(R.string.settings_back),
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(23.dp).graphicsLayer { scaleX = if (rtl) -1f else 1f },
        )
    }
}
