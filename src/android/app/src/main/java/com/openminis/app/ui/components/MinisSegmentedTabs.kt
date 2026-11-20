package com.openminis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixIndication

/**
 * A fixed-track segmented control in the Miuix idiom.
 *
 * `TabRowWithContour` puts the tabs in a LazyRow: a horizontal drag then moves
 * the whole row (and the pill riding on its scroll offset) instead of leaving
 * the track alone, and its press indication is clipped to a rounded tab shape.
 * Here the track is a static squircle, only the selection pill animates, and a
 * press paints the native rectangular [MiuixIndication] inside the track — square
 * at the seams, rounded only where the track itself rounds.
 */
@Composable
internal fun MinisSegmentedTabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 45.dp,
    cornerRadius: Dp = 8.dp,
    contourPadding: Dp = 5.dp,
) {
    if (tabs.isEmpty()) return
    val colors = MiuixTheme.colorScheme
    val selected = selectedTabIndex.coerceIn(tabs.indices)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val indicator = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        indicator.animateTo(selected.toFloat(), tween(durationMillis = 200, easing = LinearEasing))
    }
    val trackShape = RoundedCornerShape(cornerRadius)
    val trackOuterShape = RoundedCornerShape(cornerRadius + contourPadding)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(color = colors.secondaryContainer, shape = trackOuterShape)
            .padding(contourPadding)
            // The static track owns the outer corners: press rectangles stay
            // square at every interior seam and are rounded only here.
            .clip(trackShape),
    ) {
        val tabWidth = maxWidth / tabs.size
        val tabWidthPx = with(density) { tabWidth.toPx() }
        Box(
            Modifier
                .offset { IntOffset((indicator.value * tabWidthPx).fastRoundToInt(), 0) }
                .width(tabWidth)
                .fillMaxHeight()
                .background(color = colors.surfaceContainer, shape = trackShape),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { index, label ->
                val isSelected = index == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = MiuixIndication(colors.onSurface),
                            onClick = { onTabSelected(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) colors.onSurface else colors.onSurfaceVariantSummary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
