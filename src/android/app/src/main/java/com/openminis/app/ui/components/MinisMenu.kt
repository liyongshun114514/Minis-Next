package com.openminis.app.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.ui.components.miuix.MenuDefaults
import com.openminis.app.ui.components.miuix.MenuItemColors
import com.openminis.app.ui.theme.ChatColors
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassPopupAnchor
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import kotlin.math.roundToInt

internal val LocalMinisMenuInteractive = staticCompositionLocalOf { true }

/**
 * Native glass silhouette and rebound in Miuix's overlay host, using a stable theme fill.
 * Only placement is adapted. Keep this composed when [expanded] becomes false so the native
 * component can settle its exit before [onDismissFinished]. Neither host takes the IME focus.
 */
@Composable
fun MinisMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    minWidth: Dp = 164.dp,
    maxPanelWidth: Dp = 252.dp,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop? = null,
    alignEnd: Boolean = false,
    onDismissFinished: (() -> Unit)? = null,
    glassAnchor: GlassPopupAnchor? = null,
    anchorContent: @Composable () -> Unit = {},
    preferAbove: Boolean = false,
    tapPositionInWindow: Offset? = null,
    bottomBoundaryInWindow: Float? = null,
    /** Animate from the real point; placement is independently clamped above the controls. */
    expandFromTap: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var mounted by remember { mutableStateOf(false) }
    val hostVisible = remember { mutableStateOf(false) }
    if (!expanded && !mounted) return
    SideEffect { if (expanded) mounted = true }
    val currentExpanded by rememberUpdatedState(expanded)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val currentFinished by rememberUpdatedState(onDismissFinished)
    val finishDismiss = {
        if (mounted && !currentExpanded) {
            // Close the root host as well as this call site. Otherwise its retained
            // closure can leave a collapsed surface/shadow above the restored button.
            hostVisible.value = false
            mounted = false
            currentFinished?.invoke()
        }
    }
    val sameWindow = LocalMinisOverlayWindow.current === LocalView.current.rootView
    val pageBackdrop = if (sameWindow) backdrop ?: LocalMinisPageBackdrop.current ?: LocalMinisActionBackdrop.current else null
    val scene = LocalMinisSceneBackdrop.current.takeIf { sameWindow && backdrop == null }
    DisposableEffect(scene) {
        val release = scene?.acquire()
        onDispose { release?.invoke() }
    }
    val menuBackdrop = scene?.backdrop ?: pageBackdrop
    val locals = currentCompositionLocalContext
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    val cutout = WindowInsets.displayCutout
    val status = WindowInsets.statusBars
    val navigation = WindowInsets.navigationBars
    val ime = WindowInsets.ime
    val systemBottom = with(density) { maxOf(navigation.getBottom(this), cutout.getBottom(this)) }
    val imeBottom = with(density) { ime.getBottom(this) }
    val safeBounds = with(density) {
        IntRect(
            cutout.getLeft(this, direction), maxOf(status.getTop(this), cutout.getTop(this)),
            windowSize.width - cutout.getRight(this, direction),
            minOf(windowSize.height - maxOf(systemBottom, imeBottom),
                bottomBoundaryInWindow?.takeIf { it.isFinite() }?.roundToInt() ?: windowSize.height)
                .coerceAtLeast(maxOf(status.getTop(this), cutout.getTop(this))),
        )
    }
    val maxWidth = with(density) {
        (safeBounds.width.toDp() - MinisMenuPosition.HORIZONTAL_MARGIN * 2).coerceIn(0.dp, maxPanelWidth)
    }
    val maxHeight = with(density) {
        (safeBounds.height.toDp() - MinisMenuPosition.VERTICAL_MARGIN * 2).coerceIn(0.dp, GlassPopupDefaults.MaxHeight)
    }
    val sizing = GlassPopupSizing(
        minWidth = minWidth.coerceIn(0.dp, maxWidth), maxWidth = maxWidth, maxHeight = maxHeight,
        safeMargin = MinisMenuPosition.HORIZONTAL_MARGIN,
    )
    var parentBounds by remember { mutableStateOf<IntRect?>(null) }
    Spacer(Modifier.onGloballyPositioned { child ->
        child.parentLayoutCoordinates?.takeIf { it.isAttached }?.let { parent ->
            val p = parent.positionInWindow()
            parentBounds = IntRect(p.x.toInt(), p.y.toInt(), p.x.toInt() + parent.size.width, p.y.toInt() + parent.size.height)
        }
    })
    val actualTap = tapPositionInWindow?.takeIf {
        it != Offset.Unspecified && it.x.isFinite() && it.y.isFinite()
    }
    val touchBounds = actualTap?.let { tap ->
        val x = tap.x.roundToInt()
        val y = tap.y.roundToInt().let { if (bottomBoundaryInWindow != null) it.coerceAtMost(safeBounds.bottom) else it }
        IntRect(x, y, x, y)
    }
    val anchorBounds = touchBounds ?: parentBounds ?: return
    val alignAtRight = touchBounds?.let { it.left >= safeBounds.left + safeBounds.width / 2 } ?: alignEnd
    if (windowSize == IntSize.Zero) return
    val offsetX = with(density) { offset.x.roundToPx() }
    val offsetY = with(density) { offset.y.roundToPx() }
    val margin = with(density) { MinisMenuPosition.HORIZONTAL_MARGIN.roundToPx() }
    val visuals = GlassPopupDefaults.visuals()
    val hostContent: @Composable () -> Unit = {
        // First compose CLOSED: native animateFloatAsState/updateTransition otherwise snaps open.
        var show by remember { mutableStateOf(false) }
        var panelSize by remember { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(expanded) {
            if (expanded) {
                withFrameNanos { }
                show = true
            } else {
                show = false
                // A rapid open/close can finish before native content was ever composed.
                if (panelSize == IntSize.Zero) finishDismiss()
            }
        }
        val requestDismiss = { if (currentExpanded) currentDismiss() }
        var hostOrigin by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().onGloballyPositioned { hostOrigin = it.positionInWindow() }) {
            val rows: @Composable ColumnScope.() -> Unit = {
                // Native content is removed only after its exit. Always read the latest state.
                DisposableEffect(Unit) {
                    onDispose { finishDismiss() }
                }
                CompositionLocalProvider(
                    LocalMinisMenuInteractive provides expanded,
                    LocalMinisDialogSurface provides false,
                    LocalMinisDialogActionRole provides null,
                ) {
                    Column(
                        Modifier.onSizeChanged { panelSize = it }
                            .then(modifier).focusGroup()
                            .pointerInput(expanded) {
                                if (expanded) detectTapGestures(onTap = {})
                                else awaitPointerEventScope {
                                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                            .padding(vertical = 4.dp),
                        content = content,
                    )
                }
            }
            if (expandFromTap && actualTap != null) {
                val pointAnchor = rememberGlassPopupAnchor()
                var pointReady by remember { mutableStateOf(false) }
                val localPoint = actualTap - hostOrigin
                // A one-dp anchor is centred on the finger: boundsInWindow discards
                // empty rectangles, so a zero-size marker would incorrectly become (0, 0).
                val halfPointPx = with(density) { 0.5.dp.toPx() }
                Box(
                    Modifier.absoluteOffset {
                        IntOffset((localPoint.x - halfPointPx).roundToInt(), (localPoint.y - halfPointPx).roundToInt())
                    }.size(1.dp).glassPopupAnchor(pointAnchor, cornerRadius = 0.5.dp, floating = true)
                        .testTag("minis-menu-tap-origin")
                        .onGloballyPositioned { pointReady = true },
                )
                CompositionLocalProvider(LocalLayoutDirection provides if (alignAtRight) LayoutDirection.Ltr else LayoutDirection.Rtl) {
                    GlassTransformPopup(
                        show = show && pointReady, onDismissRequest = requestDismiss,
                        anchor = pointAnchor, anchorContent = {}, simplified = true,
                        backdrop = menuBackdrop, sizing = sizing, visuals = visuals,
                        modifier = if (bottomBoundaryInWindow != null) {
                            Modifier.heightIn(max = with(density) { safeBounds.bottom.toDp() })
                        } else Modifier,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides direction) { rows() }
                    }
                }
            } else if (glassAnchor != null) {
                GlassTransformPopup(
                    show = show, onDismissRequest = requestDismiss, anchor = glassAnchor,
                    backdrop = menuBackdrop, anchorContent = anchorContent, sizing = sizing, visuals = visuals,
                    contentPadding = PaddingValues(0.dp), content = rows,
                )
            } else {
                val width = panelSize.width.coerceIn(with(density) { sizing.minWidth.roundToPx() }, with(density) { maxWidth.roundToPx() })
                val height = panelSize.height.coerceAtMost(with(density) { maxHeight.roundToPx() })
                // Match the spring's fixed edge to the side of the finger. Native
                // placement can then grow directly from the point and flip above it.
                val popupDirection = when {
                    touchBounds == null -> direction
                    alignAtRight -> LayoutDirection.Ltr
                    else -> LayoutDirection.Rtl
                }
                val anchor = if (touchBounds != null && imeBottom <= systemBottom && !preferAbove) {
                    val x = touchBounds.left.toFloat()
                    val y = touchBounds.top.toFloat().coerceIn(
                        safeBounds.top.toFloat(), safeBounds.bottom.coerceAtLeast(safeBounds.top).toFloat(),
                    )
                    Rect(x, y, x, y)
                } else {
                    // The native popup's window bounds do not include the IME.
                    MinisMenuPosition.glassAnchor(
                        anchorBounds, safeBounds, IntSize(width, height), alignAtRight,
                        offsetX, offsetY, margin, preferAbove, popupDirection,
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides popupDirection) {
                    GlassDropdownPopup(
                        show = show, onDismissRequest = requestDismiss, anchorBounds = anchor,
                        backdrop = menuBackdrop, sizing = sizing, visuals = visuals,
                        // Constrain native placement too, not just its size probe:
                        // the composer exclusion must hold on the first animation frame.
                        modifier = if (bottomBoundaryInWindow != null) {
                            Modifier.heightIn(max = with(density) { safeBounds.bottom.toDp() })
                        } else Modifier,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides direction) { rows() }
                    }
                }
            }
        }
    }
    if (sameWindow) {
        SideEffect { if (expanded) hostVisible.value = true }
        PopupLayout(
            visible = hostVisible, enableWindowDim = false, enableBackHandler = false,
            enterTransition = EnterTransition.None, exitTransition = ExitTransition.None,
        ) {
            // Root overlays are composed elsewhere; preserve the caller's page/session locals.
            CompositionLocalProvider(locals, content = hostContent)
        }
    } else {
        // A window dialog must not put its menu underneath itself in the activity's root host.
        Popup(
            popupPositionProvider = remember { object : androidx.compose.ui.window.PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
            } },
            onDismissRequest = { if (currentExpanded) currentDismiss() },
            properties = PopupProperties(focusable = false, clippingEnabled = false),
            content = hostContent,
        )
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

    /** Translate our physical offsets to the native end-edge anchor without changing its spring. */
    fun glassAnchor(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        popupSize: IntSize,
        alignEnd: Boolean,
        offsetX: Int,
        offsetY: Int,
        margin: Int,
        preferAbove: Boolean,
        layoutDirection: LayoutDirection,
    ): Rect {
        val point = if (preferAbove) {
            val above = anchorBounds.top - popupSize.height - offsetY
            val y = if (above >= windowBounds.top + margin) above else anchorBounds.bottom + offsetY
            IntOffset(
                windowBounds.left + clampOrCenter(anchorBounds.right - popupSize.width + offsetX - windowBounds.left, popupSize.width, windowBounds.width, margin),
                windowBounds.top + clampOrCenter(y - windowBounds.top, popupSize.height, windowBounds.height, margin),
            )
        } else compute(anchorBounds, windowBounds, popupSize, alignEnd, offsetX, offsetY, margin, margin)
        val edge = if (layoutDirection == LayoutDirection.Ltr) point.x + popupSize.width else point.x
        // Give the native surface an already-clamped target; its measured size includes padding.
        return Rect(edge.toFloat(), point.y.toFloat(), edge.toFloat(), point.y.toFloat())
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
