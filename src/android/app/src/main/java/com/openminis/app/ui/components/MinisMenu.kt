package com.openminis.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.components.miuix.MenuDefaults
import com.openminis.app.ui.components.miuix.MenuItemColors
import com.openminis.app.ui.theme.ChatColors
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

internal val LocalMinisMenuInteractive = staticCompositionLocalOf { true }

/**
 * Shared discrete action menu. Miuix owns the spring, directional reveal, window and back gesture;
 * we keep the app's anchor offsets, content-driven width and scroll state. Keep this composed
 * when [expanded] turns false so Miuix can play the exit before disposing its window.
 */
@Composable
fun MinisMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    minWidth: Dp = 180.dp,
    alignEnd: Boolean = false,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Chat can contain many offscreen menu anchors. Keep only the active/closing one mounted.
    val keepMounted = remember { mutableStateOf(false) }
    if (!expanded && !keepMounted.value) return
    SideEffect { if (expanded) keepMounted.value = true }

    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val cutout = WindowInsets.displayCutout
    val maxWidth = with(density) {
        val available = windowWidth - cutout.getLeft(this, direction) - cutout.getRight(this, direction)
        if (windowWidth > 0) {
            (available.toDp() - MinisMenuPosition.HORIZONTAL_MARGIN * 2).coerceIn(0.dp, 280.dp)
        } else {
            280.dp
        }
    }
    val effectiveMinWidth = minWidth.coerceAtMost(maxWidth)
    val offsetXPx = with(density) { offset.x.roundToPx() }
    val offsetYPx = with(density) { offset.y.roundToPx() }
    val positionProvider = remember(alignEnd, offsetXPx, offsetYPx) {
        MinisMenuPosition.provider(alignEnd, offsetXPx, offsetYPx)
    }
    WindowListPopup(
        show = expanded,
        // This is Miuix's initial pivot hint. The provider supplies the final, physical position.
        alignment = if (alignEnd == (direction == LayoutDirection.Ltr)) {
            PopupPositionProvider.Align.End
        } else {
            PopupPositionProvider.Align.Start
        },
        popupPositionProvider = positionProvider,
        enableWindowDim = false,
        minWidth = effectiveMinWidth,
        onDismissRequest = { if (expanded) onDismissRequest() },
        onDismissFinished = {
            keepMounted.value = false
            onDismissFinished?.invoke()
        },
    ) {
        CompositionLocalProvider(LocalMinisMenuInteractive provides expanded) {
            Column(
                modifier = Modifier
                    .widthIn(min = effectiveMinWidth, max = maxWidth)
                    .then(modifier)
                    .width(IntrinsicSize.Max)
                    .focusGroup()
                    .pointerInput(expanded) {
                        if (expanded) {
                            // Blank space within the card is not an outside tap.
                            detectTapGestures(onTap = {})
                        } else {
                            // Don't activate rows or embedded switches during the exit animation.
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    .verticalScroll(scrollState),
                content = content,
            )
        }
    }
}

/** The app's physical left/right alignment and flip-above rules, inside Miuix's safe area. */
internal object MinisMenuPosition {
    val HORIZONTAL_MARGIN: Dp = 8.dp
    val VERTICAL_MARGIN: Dp = 8.dp

    fun provider(alignEnd: Boolean, offsetX: Int, offsetY: Int): PopupPositionProvider =
        object : PopupPositionProvider {
            override fun getMargins() = PaddingValues(horizontal = HORIZONTAL_MARGIN, vertical = VERTICAL_MARGIN)

            override fun calculatePosition(
                anchorBounds: IntRect,
                windowBounds: IntRect,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
                popupMargin: IntRect,
                alignment: PopupPositionProvider.Align,
            ): IntOffset = compute(
                anchorBounds, windowBounds, popupContentSize, alignEnd, offsetX, offsetY,
                popupMargin.left, popupMargin.top,
            )
        }

    fun compute(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        popupSize: IntSize,
        alignEnd: Boolean,
        offsetX: Int,
        offsetY: Int,
        marginX: Int,
        marginY: Int,
    ): IntOffset {
        val rawX = if (alignEnd) anchorBounds.right - popupSize.width + offsetX else anchorBounds.left + offsetX
        val belowY = anchorBounds.bottom + offsetY
        val aboveY = anchorBounds.top - popupSize.height - offsetY
        val fitsBelow = belowY + popupSize.height <= windowBounds.bottom - marginY
        val rawY = if (fitsBelow || aboveY < windowBounds.top + marginY) belowY else aboveY
        return IntOffset(
            windowBounds.left + clampOrCenter(rawX - windowBounds.left, popupSize.width, windowBounds.width, marginX),
            windowBounds.top + clampOrCenter(rawY - windowBounds.top, popupSize.height, windowBounds.height, marginY),
        )
    }

    /** Clamp into the safe area; center if the content cannot fit between the margins. */
    internal fun clampOrCenter(value: Int, size: Int, window: Int, margin: Int): Int {
        val max = window - margin - size
        return if (max < margin) (window - size) / 2 else value.coerceIn(margin, max)
    }
}

/**
 * Subtle inset divider for grouping items inside a [MinisMenu]. T289:
 * `outlineVariant×0.35 + 0.5dp` was effectively invisible on the light
 * surface — the divider lines between sections looked like nothing was
 * there. Bumped to `Color.Black/White × 0.12 + 1.dp` so the rule is
 * legible without feeling heavy. Theme-aware so dark mode uses
 * white instead of black for contrast against the dark surface.
 */
@Composable
fun MinisMenuDivider(modifier: Modifier = Modifier) {
    val tint = if (ChatColors.isDark) Color.White else Color.Black
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        thickness = 1.dp,
        color = tint.copy(alpha = 0.12f),
    )
}

/**
 * Consistent row padding + colors for menu items. Use this to tighten the
 * default Material3 48dp row to 44dp and give the leading icon breathing room
 * that matches the iOS context-menu look.
 */
object MinisMenuDefaults {
    val ItemPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 0.dp)

    @Composable
    fun itemColors(
        textColor: Color = MiuixTheme.colorScheme.onSurface,
        leadingIconColor: Color = MiuixTheme.colorScheme.primary,
        trailingIconColor: Color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
    ): MenuItemColors = MenuDefaults.itemColors(
        textColor = textColor,
        leadingIconColor = leadingIconColor,
        trailingIconColor = trailingIconColor,
    )

    @Composable
    fun destructiveItemColors(): MenuItemColors = MenuDefaults.itemColors(
        textColor = MiuixTheme.colorScheme.error,
        leadingIconColor = MiuixTheme.colorScheme.error,
        trailingIconColor = MiuixTheme.colorScheme.error,
    )
}
