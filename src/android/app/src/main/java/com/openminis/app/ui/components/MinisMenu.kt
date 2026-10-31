package com.openminis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.ui.theme.OmniColors
import com.openminis.app.ui.theme.OmniDimens
import com.openminis.app.ui.theme.omniTextStyle

/**
 * App-wide popup menu with a unified look: softer rounded corners, a lifted
 * shadow, and a clean tonal surface that matches the Teal theme.
 *
 * Drop-in replacement for [androidx.compose.material3.DropdownMenu] — pass
 * [DropdownMenuItem] children as before.
 */
@Composable
fun MinisMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    // [T-omnibot-ui-port] OmniBot menu panel radius (OmniMenuStyle.panelRadius
    // = 24dp); rows inside use the 16dp row radius.
    shape: Shape = RoundedCornerShape(24.dp),
    // [T-omnibot-ui-port] Omni menus sit on the flat `surfacePrimary`
    // (#FFFFFF / #202020) — lighter than the #151515 dark page, so the popup
    // still pops out of the chat background — with a hairline edge instead of
    // M3's tonal tint.
    containerColor: Color = OmniColors.surfacePrimary,
    tonalElevation: Dp = 0.dp,
    // [T-android-menu-shadow-clip] Surface's own shadow, capped at
    // [MinisMenuPosition.MAX_POPUP_SHADOW_ELEVATION] (8dp).
    //
    // Why not the launcher-style 24dp halo any more (2d6b3e360): a Compose
    // Popup is its own system window, and AndroidPopup reserves room around
    // its content for a shadow of at most `maxSupportedElevation = 8.dp`
    // (larger values hit a pre-S surface-insets matrix bug that breaks
    // accessibility). A 24dp shadow ran far past that reserve and was cut
    // off in a straight line at the window rectangle — the "shadow looks
    // odd / runs past the right edge" user report (Telegram, Android).
    // Material3's DropdownMenu stays inside the reserve the same way
    // (Surface shadowElevation ≈ 3dp). 6dp keeps a little more lift than
    // that while still fitting.
    //
    // A launcher-size halo would need transparent padding around the
    // content at least as wide as the shadow's spread, with that padding
    // subtracted again in the position provider — deliberately not done.
    shadowElevation: Dp = 6.dp,
    // Hairline edge in BOTH themes (Omni panel has no tonal fill difference, so
    // the border is what bounds it against the page in light mode and against
    // #151515 in dark mode).
    border: BorderStroke? = BorderStroke(0.75.dp, OmniColors.borderSubtle),
    minWidth: Dp = 180.dp,
    // T238: when true, anchor the menu's RIGHT edge to the anchor box's
    // right edge instead of Material3 DropdownMenu's default left-anchor
    // behaviour. Used by the user-message bubble menu so the popup tracks
    // the right-aligned chat bubble — mirrors iOS Messages.app context
    // menu. Vertical placement still goes below the anchor with auto-flip
    // above when there's no room.
    alignEnd: Boolean = false,
    // [T-omnibot-ui-port] Which measured menu transform this popup uses:
    // a control-anchored menu opens in 450ms and returns in 500ms, a context
    // menu (long-press) in 200ms. Right-anchored menus are this app's context
    // menus (bubble / row long-press, per-row overflow actions), so they default
    // to the context clock; pass [OmniPopupMorphStyle.Standard] to override.
    // [T-omnibot-ui-port] Glass panel: the shared container morph carries a
    // refracting pane (sampled from the activity window behind the popup), so the
    // menu is not an opaque card floating over the page. Null keeps the flat
    // surface for callers that must not refract (e.g. a menu over a camera view).
    glassStyle: OmniGlassStyle? = OmniGlassStyle.Clear,
    morphStyle: OmniPopupMorphStyle = if (alignEnd) {
        OmniPopupMorphStyle.Context
    } else {
        OmniPopupMorphStyle.Standard
    },
    content: @Composable ColumnScope.() -> Unit,
) {
    // [T-omnibot-ui-port] One shared-container transform for every menu:
    //   • it grows out of the source control's measured rect over 450ms
    //     (200ms for a context menu) instead of scaling from a corner;
    //   • on dismiss the Popup stays composed for the full 500ms return
    //     (200ms context) and shrinks back into that rect — the previous
    //     enter-only animation unmounted the window on the first frame, so the
    //     close was a hard cut.
    // The panel's rect, corner radius and surface colour are interpolated in
    // OmniMorphMotion (unit-tested), and the content fades in a phased segment
    // inside it. No callback, item layout or dismissal behaviour changes.
    val morph = remember(morphStyle) { OmniPopupMorphClock(morphStyle) }
    LaunchedEffect(expanded) {
        if (expanded) morph.open() else morph.close()
    }
    if (!expanded && !morph.mounted) return
    val density = LocalDensity.current
    val offsetXPx = with(density) { offset.x.roundToPx() }
    val offsetYPx = with(density) { offset.y.roundToPx() }
    val marginXPx = with(density) { MinisMenuPosition.HORIZONTAL_MARGIN.roundToPx() }
    val marginYPx = with(density) { MinisMenuPosition.VERTICAL_MARGIN.roundToPx() }
    // The position provider sees the real anchor rect and the final placement
    // during layout; the panel reads them back so the morph starts at the
    // control, not at the panel's own corner. Both are written only when they
    // change, so the extra layout pass converges immediately.
    val anchorInWindow = remember { mutableStateOf(IntRect.Zero) }
    val popupOffset = remember { mutableStateOf(IntOffset.Zero) }
    Popup(
        onDismissRequest = onDismissRequest,
        // While the panel returns into its control the window must stay composed
        // (that is what makes the 500ms close visible) but must NOT keep stealing
        // focus or swallowing the user's next tap: the dismiss state is already
        // decided, so the window becomes non-focusable for the return.
        properties = if (morph.closing) {
            PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            )
        } else {
            properties
        },
        popupPositionProvider = remember(offsetXPx, offsetYPx, alignEnd, marginXPx, marginYPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val position = MinisMenuPosition.compute(
                        anchorBounds, windowSize, popupContentSize,
                        alignEnd, offsetXPx, offsetYPx, marginXPx, marginYPx,
                    )
                    if (anchorInWindow.value != anchorBounds) anchorInWindow.value = anchorBounds
                    if (popupOffset.value != position) popupOffset.value = position
                    return position
                }
            }
        },
    ) {
        // [T-android-menu-screen-margin] The popup's constraints are the
        // window; cap the menu at the window height minus both vertical
        // margins so a long menu still fits inside the margins and scrolls
        // (its Column is verticalScroll) instead of being pushed off-screen.
        BoxWithConstraints {
            val maxMenuHeight = (maxHeight - MinisMenuPosition.VERTICAL_MARGIN * 2).coerceAtLeast(0.dp)
            val anchor = anchorInWindow.value
                .takeUnless { it.isEmpty }
                ?.let {
                    omniPopupLocalAnchor(
                        anchorInWindow = it,
                        popupPosition = Offset(
                            popupOffset.value.x.toFloat(),
                            popupOffset.value.y.toFloat(),
                        ),
                    )
                }
            OmniPopupMorphSurface(
                clock = morph,
                anchor = anchor,
                glassStyle = glassStyle,
                containerColor = containerColor,
                panelShape = shape,
                // Never past the Popup window's 8dp shadow reserve (see the
                // shadowElevation parameter): anything larger is clipped.
                shadowElevation = shadowElevation.coerceAtMost(
                    MinisMenuPosition.MAX_POPUP_SHADOW_ELEVATION,
                ),
                borderColor = border?.let { stroke ->
                    (stroke.brush as? SolidColor)?.value
                },
                borderWidth = border?.width ?: 0.dp,
                modifier = modifier
                    // [T-android-minis-menu-width] Size to the CONTENT's intrinsic
                    // width (the widest menu item), then clamp to [minWidth, 280dp].
                    // This restores Material3 DropdownMenu's "hug the content" sizing
                    // that the unified-Popup rewrite lost: without IntrinsicSize.Max,
                    // DropdownMenuItem's internal fillMaxWidth expanded the Column to
                    // the 280dp cap, making short menus (the top-bar tools menu) look
                    // far too wide (T252's "blows out" symptom). 280dp stays as the
                    // upper bound so a very long localised item can't span the screen.
                    .width(IntrinsicSize.Max)
                    .widthIn(min = minWidth, max = 280.dp)
                    .heightIn(max = maxMenuHeight),
            ) {
                // Mirrors DropdownMenu's content layout: a vertically-scrollable
                // Column inside the panel (scrollState preserved so long menus
                // still scroll).
                Column(
                    modifier = Modifier.verticalScroll(scrollState),
                    content = content,
                )
            }
        }
    }
}

/**
 * [T-android-menu-screen-margin] Where a [MinisMenu] goes — a pure function so
 * the edge cases are unit-tested (MinisMenuPositionTest).
 *
 * Semantics kept from the original provider: default = popup's left edge on
 * the anchor's left edge, [alignEnd] = popup's right edge on the anchor's
 * right edge; [offsetX]/[offsetY] shift it (callers rely on this: finger-
 * anchored session menus, the 6dp gap under message bubbles); below the
 * anchor, flipped above when it does not fit below and does fit above.
 *
 * What changed: the bounds are the window inset by a margin, not the window
 * itself. The old clamp to [0, window - popup] pushed an overflowing menu flush
 * against the screen edge (measured 2px on a Pixel 4a). Material3's
 * DropdownMenuPositionProvider keeps an 8dp horizontal margin the same way.
 * When the popup is larger than the space between the margins it is centred,
 * so the clamp range is never inverted (min > max would throw).
 */
internal object MinisMenuPosition {
    /** Same as Material3's MenuHorizontalMargin. */
    val HORIZONTAL_MARGIN: Dp = 8.dp

    /**
     * Smaller than Material3's 48dp MenuVerticalMargin on purpose: some of our
     * menus are tall (session long-press, top-bar tools), and 48dp top and
     * bottom would force them to scroll much sooner. The popup window already
     * excludes the status and navigation bars, so 8dp is only breathing room.
     */
    val VERTICAL_MARGIN: Dp = 8.dp

    /** AndroidPopup's `maxSupportedElevation`: the shadow room a Popup window reserves. */
    val MAX_POPUP_SHADOW_ELEVATION: Dp = 8.dp

    fun compute(
        anchorBounds: IntRect,
        windowSize: IntSize,
        popupSize: IntSize,
        alignEnd: Boolean,
        offsetX: Int,
        offsetY: Int,
        marginX: Int,
        marginY: Int,
    ): IntOffset {
        val rawX = if (alignEnd) anchorBounds.right - popupSize.width + offsetX else anchorBounds.left + offsetX
        val x = clampOrCenter(rawX, popupSize.width, windowSize.width, marginX)
        // Below the anchor; flip above when it does not fit below and does fit
        // above (within the margins). Neither fits: stay below and let the
        // clamp slide it up.
        val belowY = anchorBounds.bottom + offsetY
        val aboveY = anchorBounds.top - popupSize.height - offsetY
        val fitsBelow = belowY + popupSize.height <= windowSize.height - marginY
        val y = if (fitsBelow || aboveY < marginY) belowY else aboveY
        return IntOffset(x, clampOrCenter(y, popupSize.height, windowSize.height, marginY))
    }

    /** [value] clamped into [margin, window - margin - size]; centred when [size] does not fit. */
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
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        thickness = OmniDimens.DividerThickness,
        color = OmniColors.borderSubtle.copy(alpha = if (OmniColors.isDark) 0.55f else 0.75f),
    )
}

/**
 * Consistent row padding + colors for menu items. Use this to tighten the
 * default Material3 48dp row to 44dp and give the leading icon breathing room
 * that matches the iOS context-menu look.
 */
object MinisMenuDefaults {
    /** Omni row inset (panel padding 8 + content padding 4 ≈ 12dp). */
    val ItemPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

    @Composable
    fun itemColors(
        textColor: Color = OmniColors.textPrimary,
        leadingIconColor: Color = OmniColors.textPrimary,
        trailingIconColor: Color = OmniColors.textSecondary,
    ): MenuItemColors = MenuDefaults.itemColors(
        textColor = textColor,
        leadingIconColor = leadingIconColor,
        trailingIconColor = trailingIconColor,
    )

    @Composable
    fun destructiveItemColors(): MenuItemColors = MenuDefaults.itemColors(
        textColor = OmniColors.destructive,
        leadingIconColor = OmniColors.destructive,
        trailingIconColor = OmniColors.destructive,
    )

    /** Omni menu row label: 15sp regular, textPrimary (OmniMenuStyle.labelStyle). */
    @Composable
    fun itemTextStyle(): androidx.compose.ui.text.TextStyle = omniTextStyle(
        size = 15.sp,
        color = OmniColors.textPrimary,
    )
}
