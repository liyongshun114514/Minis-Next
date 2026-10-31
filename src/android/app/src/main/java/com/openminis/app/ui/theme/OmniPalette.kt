package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [T-omnibot-ui-port] Neutral visual tokens for the OmniBot-style Compose
 * surface, ported independently from OmniBot-UI's `OmniThemePalette`
 * (`ui/lib/theme/omni_theme_palette.dart`).
 *
 * ## Provenance / licensing
 *
 * OmniBot-UI is distributed under a segmented AGPL + commercial licence, so no
 * OmniBot source was copied here: this is an independent Compose implementation
 * of the *visual parameters* (measured colours, radii and type sizes), the same
 * way a palette table would be re-typed from a screenshot. The layout ideas in
 * [OmniDimens] are generic inset-grouped list metrics.
 *
 * ## Why a second palette next to MaterialTheme
 *
 * OmniBot's look is deliberately monochrome: page grey, a flat card surface
 * that is *lighter than the page*, hairline dividers, and a single restrained
 * brand blue. Material3's tonal `primary`-derived surfaces tint everything, so
 * settings screens that need the flat look read from [OmniColors] instead.
 *
 * ## Theme / font-scale overrides
 *
 * [LocalOmniPalette] is provided by `MinisTheme` (and `MinisAppearance`) from
 * the SAME `darkTheme` flag that selects [LightChatPalette]/[DarkChatPalette]
 * and the Material colour scheme, so the in-app appearance override
 * (theme_mode preference), chat colours and these tokens can never disagree.
 *
 * Nothing here may read the OS dark-mode flag (the guarded API is named in
 * Theme.kt); see `InAppThemeSourceGuardTest` and GH OpenMinis#187.
 *
 * [LocalOmniFontScale] carries the in-app font-scale multiplier (the same
 * value `MinisTheme` folds into its Typography). Fixed-size text in the new
 * components goes through [omniTextStyle] so it follows that override instead
 * of staying pinned at 100%.
 */
@Immutable
data class OmniPalette(
    val isDark: Boolean,
    val pageBackground: Color,
    val surfacePrimary: Color,
    val surfaceSecondary: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accentPrimary: Color,
    val brandPrimary: Color,
    val brandSurface: Color,
    val segmentTrack: Color,
    val segmentThumb: Color,
    val overlayScrim: Color,
    val previewFallback: Color,
    val shadowColor: Color,
    val destructive: Color,
)

/** Light tokens — OmniBot `OmniThemePalette.light` verbatim (visual params). */
val OmniLightPalette = OmniPalette(
    isDark = false,
    pageBackground = Color(0xFFF6F6F6),
    surfacePrimary = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFF0F0F0),
    surfaceElevated = Color(0xFFE7E7E7),
    borderSubtle = Color(0xFFE4E4E4),
    borderStrong = Color(0xFFCECECE),
    textPrimary = Color(0xFF161616),
    textSecondary = Color(0xFF777777),
    textTertiary = Color(0xFF929292),
    accentPrimary = Color(0xFF161616),
    brandPrimary = Color(0xFF2C7FEB),
    brandSurface = Color(0xFFE6EDFF),
    segmentTrack = Color(0xFFF0F0F0),
    segmentThumb = Color(0xFFFFFFFF),
    overlayScrim = Color(0x4D000000),
    previewFallback = Color(0xFFF6F6F6),
    shadowColor = Color(0x0D000000),
    destructive = Color(0xFFE05252),
)

/** Dark tokens — OmniBot `OmniThemePalette.dark` verbatim (visual params). */
val OmniDarkPalette = OmniPalette(
    isDark = true,
    pageBackground = Color(0xFF151515),
    surfacePrimary = Color(0xFF202020),
    surfaceSecondary = Color(0xFF292929),
    surfaceElevated = Color(0xFF333333),
    borderSubtle = Color(0xFF383838),
    borderStrong = Color(0xFF4A4A4A),
    textPrimary = Color(0xFFF3F3F3),
    textSecondary = Color(0xFFB0B0B0),
    textTertiary = Color(0xFF8E8E8E),
    accentPrimary = Color(0xFFF3F3F3),
    brandPrimary = Color(0xFF98AD90),
    brandSurface = Color(0xFF2E382D),
    segmentTrack = Color(0xFF292929),
    segmentThumb = Color(0xFF3A3A3A),
    overlayScrim = Color(0xA6000000),
    previewFallback = Color(0xFF1B1B1B),
    shadowColor = Color(0x26000000),
    destructive = Color(0xFFE05252),
)

/** Provided by `MinisTheme`; dynamic (not static) so an appearance change invalidates readers. */
val LocalOmniPalette = compositionLocalOf { OmniLightPalette }

/** In-app font-scale multiplier, published by `MinisTheme`/`MinisAppearance`. */
val LocalOmniFontScale = compositionLocalOf { 1f }

/** Short accessor, mirroring [ChatColors]: `OmniColors.textSecondary`. */
val OmniColors: OmniPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalOmniPalette.current

/**
 * Fixed-size text with the in-app font scale applied.
 *
 * `MinisTheme(fontScale = …)` only rescales MaterialTheme's Typography, so a
 * hard-coded `fontSize = 15.sp` would ignore Settings → Appearance → Text size.
 * Every hand-rolled omni size goes through this helper instead.
 */
@Composable
@ReadOnlyComposable
fun omniSp(size: TextUnit): TextUnit = (size.value * LocalOmniFontScale.current).sp

@Composable
@ReadOnlyComposable
fun omniTextStyle(
    size: TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit? = null,
    letterSpacing: TextUnit? = null,
): TextStyle = TextStyle(
    color = color,
    fontSize = omniSp(size),
    fontWeight = weight,
    lineHeight = lineHeight?.let { omniSp(it) } ?: TextUnit.Unspecified,
    letterSpacing = letterSpacing ?: TextUnit.Unspecified,
)

/**
 * Layout metrics of the OmniBot settings language, taken from the Flutter main
 * settings page (`settings_page.dart`): 22dp page inset, 26dp between groups,
 * 22dp radius shadowless grouped cards, and hairlines inset to the text column.
 * Kept in one object so every ported screen moves together.
 */
object OmniDimens {
    /** Page inset from the screen edge (left/right) — Flutter 22dp. */
    val PageHorizontal = 22.dp

    /** Page inset above the first group / below the last. */
    val PageVerticalTop: Dp = 12.dp
    val PageVerticalBottom: Dp = 32.dp

    /** Gap between two groups — Flutter 26dp. */
    val SectionGap: Dp = 26.dp

    /** Flat (shadowless) surface radius for groups, cards and fields. */
    val SurfaceRadius: Dp = 22.dp

    /** Hairline used between rows inside one group. */
    val DividerThickness: Dp = 0.65.dp

    /** Divider start inset — aligned to the row title column inside the card
     *  (row inset 18 + icon box 22 + gap 14). Card page margin is separate. */
    val DividerStartInset: Dp = 54.dp

    /** Row content insets. */
    val RowHorizontal: Dp = 18.dp
    val RowVertical: Dp = 16.dp

    /** Leading icon box: 22dp wide, 20dp glyph, 14dp gap to the title. */
    val LeadingIconBox: Dp = 22.dp
    val LeadingIconSize: Dp = 20.dp
    val LeadingIconGap: Dp = 14.dp

    /** Text sizes (scaled through [omniSp]). */
    val GroupLabelSize: TextUnit = 14.sp
    val RowTitleSize: TextUnit = 15.sp
    val RowSubtitleSize: TextUnit = 12.sp
    val RowValueSize: TextUnit = 14.sp
    val FooterSize: TextUnit = 12.sp

    /** Centred top-bar title size. */
    val BarTitleSize: TextUnit = 17.sp

    /** Minimum row height so single-line and two-line rows stay uniform. */
    val RowMinHeight: Dp = 56.dp
}
