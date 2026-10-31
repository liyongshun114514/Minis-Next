package com.openminis.app.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Layout tokens for the OmniBot home port.
 *
 * The numbers are read off the upstream reference implementation
 * (`ui/lib/widgets/flclash/search_header.dart` for the toolbar and
 * `ui/lib/widgets/flclash/navigation_dock.dart` for the dock) and are
 * reproduced here as an independent Compose port — no Dart code was
 * translated line by line, only the measured geometry and motion constants
 * were carried over.
 *
 *  - toolbar: 72dp tall, 16dp edge inset, 44dp round buttons, 8dp button gap,
 *    24sp/600 title.
 *  - dock: 62dp bar, 4dp inner padding, 21dp edge margin, 28dp glyph, 10sp
 *    label 2dp under the glyph.
 */
internal object OmniHomeSpec {
    // ── Toolbar (search header) ────────────────────────────────────────────
    val ToolbarHeight = 72.dp
    val ToolbarEdge = 16.dp
    val ToolbarButton = 44.dp
    val ToolbarIcon = 24.dp
    val ToolbarGap = 8.dp
    val TitleSize = 24.sp

    // ── Floating dock ──────────────────────────────────────────────────────
    val DockHeight = 62.dp
    val DockPadding = 4.dp
    val DockEdge = 21.dp
    val DockIcon = 26.dp
    val DockLabelSize = 10.sp
    val DockLabelGap = 2.dp
    val DockShadowRoom = 8.dp
    /** Room the scrolling list leaves under itself so rows clear the dock. */
    val DockContentInset = 104.dp

    // ── Search capsule ─────────────────────────────────────────────────────
    val SearchFieldHeight = 44.dp
    val SearchTextSize = 17.sp
}

/**
 * Upstream uses `RoundedSuperellipseBorder` (a squircle). Compose has no
 * superellipse shape, so a fully rounded capsule stands in for it: at the
 * dock's 62dp height both read as the same pill, and the difference only
 * shows at large radii.
 */
internal fun omniCapsuleShape(): Shape = RoundedCornerShape(percent = 50)

/** Upstream `_floatingSurface`: surfaceBright in light, surfaceContainerHigh in dark. */
@Composable
internal fun omniFloatingSurface(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceBright
    }

/** Upstream `_selectedSurface`: surfaceContainer in light, surfaceContainerHighest in dark. */
@Composable
internal fun omniSelectedSurface(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

/**
 * The home toolbar, laid out like the reference search header: one 72dp row,
 * 16dp edge inset, a 44dp leading slot, a flexible title and a trailing
 * action cluster with 8dp gaps.
 *
 * Slot names deliberately mirror [androidx.compose.material3.TopAppBar] so the
 * call site keeps its exact content (`title` / `navigationIcon` / `actions`),
 * i.e. every existing entry point and callback stays wired as it was.
 */
@Composable
internal fun OmniHomeTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .heightIn(min = OmniHomeSpec.ToolbarHeight)
                .padding(start = OmniHomeSpec.ToolbarEdge, end = OmniHomeSpec.ToolbarEdge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(OmniHomeSpec.ToolbarButton),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                ) {
                    navigationIcon()
                }
            }
            Spacer(Modifier.width(OmniHomeSpec.ToolbarGap))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                title()
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(OmniHomeSpec.ToolbarGap),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}
