package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Neutral presentation tokens: see docs/ui-port/provenance.md.
// Brand-specific accents remain distinct from the neutral primary controls.

// Neutral grouped-card surfaces (inset-grouped background).
// Override Material3's tonal `surfaceContainer*` so cards don't pick up the
// teal primary tint.
//
// [T-omnibot-ui-port] These are ALIASES of the OmniPalette table, not a second
// copy of the literals: page = pageBackground, flat card = surfacePrimary,
// elevated = surfaceSecondary, hairline = borderSubtle, strong = borderStrong.
// One table, so a token change can never leave the Material scheme behind.
private val NeutralGroupedBg = OmniLightPalette.pageBackground
private val NeutralGroupedCard = OmniLightPalette.surfacePrimary
private val NeutralGroupedCardElevated = OmniLightPalette.surfaceSecondary
private val NeutralOutline = OmniLightPalette.borderStrong
private val NeutralOutlineVariant = OmniLightPalette.borderSubtle

private val NeutralDarkGroupedBg = OmniDarkPalette.pageBackground
private val NeutralDarkGroupedCard = OmniDarkPalette.surfacePrimary
private val NeutralDarkGroupedCardElevated = OmniDarkPalette.surfaceSecondary
private val NeutralDarkOutline = OmniDarkPalette.borderStrong
private val NeutralDarkOutlineVariant = OmniDarkPalette.borderSubtle

private val LightColorScheme = lightColorScheme(
    primary = OmniLightPalette.accentPrimary,
    onPrimary = Color.White,
    primaryContainer = OmniLightPalette.surfaceElevated,
    onPrimaryContainer = OmniLightPalette.textPrimary,
    secondary = OmniLightPalette.textSecondary,
    onSecondary = Color.White,
    secondaryContainer = OmniLightPalette.surfaceSecondary,
    onSecondaryContainer = OmniLightPalette.textPrimary,
    tertiary = OmniLightPalette.textSecondary,
    onTertiary = Color.White,
    tertiaryContainer = OmniLightPalette.surfaceSecondary,
    onTertiaryContainer = OmniLightPalette.textPrimary,
    error = Color(0xFFFF6464),
    onError = Color.White,
    surfaceTint = Color.Transparent,
    background = NeutralGroupedBg,
    // Text roles also come from the Omni table (textPrimary / textSecondary).
    onBackground = OmniLightPalette.textPrimary,
    surface = NeutralGroupedCard,
    onSurface = OmniLightPalette.textPrimary,
    surfaceVariant = NeutralGroupedCard,
    onSurfaceVariant = OmniLightPalette.textSecondary,
    surfaceContainerLowest = NeutralGroupedCard,
    surfaceContainerLow = NeutralGroupedBg,
    inverseSurface = OmniDarkPalette.surfacePrimary,
    inverseOnSurface = OmniDarkPalette.textPrimary,
    inversePrimary = OmniDarkPalette.accentPrimary,
    surfaceBright = OmniLightPalette.surfacePrimary,
    surfaceDim = OmniLightPalette.surfaceElevated,
    surfaceContainer = OmniLightPalette.surfaceSecondary,
    surfaceContainerHigh = OmniLightPalette.surfaceElevated,
    surfaceContainerHighest = OmniLightPalette.surfaceElevated,
    outline = NeutralOutline,
    outlineVariant = NeutralOutlineVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = OmniDarkPalette.accentPrimary,
    onPrimary = OmniLightPalette.textPrimary,
    primaryContainer = OmniDarkPalette.surfaceElevated,
    onPrimaryContainer = OmniDarkPalette.textPrimary,
    secondary = OmniDarkPalette.textSecondary,
    onSecondary = OmniLightPalette.textPrimary,
    secondaryContainer = OmniDarkPalette.surfaceSecondary,
    onSecondaryContainer = OmniDarkPalette.textPrimary,
    tertiary = OmniDarkPalette.textSecondary,
    onTertiary = OmniLightPalette.textPrimary,
    tertiaryContainer = OmniDarkPalette.surfaceSecondary,
    onTertiaryContainer = OmniDarkPalette.textPrimary,
    error = Color(0xFFFF6464),
    onError = Color.White,
    surfaceTint = Color.Transparent,
    background = NeutralDarkGroupedBg,
    onBackground = OmniDarkPalette.textPrimary,
    surface = NeutralDarkGroupedCard,
    onSurface = OmniDarkPalette.textPrimary,
    surfaceVariant = NeutralDarkGroupedCard,
    onSurfaceVariant = OmniDarkPalette.textSecondary,
    surfaceContainerLowest = NeutralDarkGroupedCard,
    surfaceContainerLow = NeutralDarkGroupedBg,
    inverseSurface = OmniLightPalette.surfacePrimary,
    inverseOnSurface = OmniLightPalette.textPrimary,
    inversePrimary = OmniLightPalette.accentPrimary,
    surfaceBright = OmniDarkPalette.surfacePrimary,
    surfaceDim = OmniDarkPalette.surfaceElevated,
    surfaceContainer = OmniDarkPalette.surfaceSecondary,
    surfaceContainerHigh = OmniDarkPalette.surfaceElevated,
    surfaceContainerHighest = OmniDarkPalette.surfaceElevated,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutlineVariant,
)

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// Shared reference geometry; specialised menus/dialogs retain their explicit
// 24dp/20dp shapes, and sheets use the 24dp large-surface shape below.
private val MinisShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colorScheme = animatedOmniColorScheme(if (darkTheme) DarkColorScheme else LightColorScheme)
    val typography = scaledTypography(fontScale)
    val omniPalette = animatedOmniPalette(darkTheme)
    // Derive chat neutrals from the animated palette too: no half-switched
    // frame where the page has faded but its input and labels have snapped.
    val chatPalette = chatPaletteFor(omniPalette)

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MinisShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalChatPalette provides chatPalette,
            LocalOmniPalette provides omniPalette,
            LocalOmniFontScale provides fontScale,
            content = content,
        )
    }
}

/**
 * [T-multi-select-share] Re-themes a subtree light or dark regardless of the app's
 * own appearance, keeping the app's typography (font scale) and shapes. The chat
 * export uses it so a dark app can share a light page and the other way round:
 * every chat row reads its colours from MaterialTheme / LocalChatPalette.
 */
@Composable
fun MinisAppearance(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
    ) {
        CompositionLocalProvider(
            LocalChatPalette provides if (darkTheme) DarkChatPalette else LightChatPalette,
            LocalOmniPalette provides if (darkTheme) OmniDarkPalette else OmniLightPalette,
            // Font scale is inherited from the app's MinisTheme so a re-themed
            // subtree keeps the user's text-size override.
            LocalOmniFontScale provides LocalOmniFontScale.current,
            content = content,
        )
    }
}

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(
        fontSize = fontSize * factor,
        lineHeight = if (lineHeight.isSp) lineHeight * factor else lineHeight,
    )

/**
 * [T-fa-locale] Each paragraph takes its direction from its own text, but
 * stays left-aligned.
 *
 * The app keeps a left-to-right layout in every language (LocaleWrap.forceLtr),
 * and under an LTR layout Compose otherwise lays every paragraph out LTR too.
 * Persian text that mixes in Latin words ("کلیدهای API و ورود با OAuth") then
 * comes out in left-to-right word order, which a Persian reader reads
 * scrambled. Content direction fixes the word order; Left (not Start, which
 * would follow an RTL paragraph to the right edge) keeps every label where it
 * sits today. For left-to-right text both are what it already had.
 */
private fun TextStyle.bidi(): TextStyle =
    copy(textDirection = TextDirection.Content, textAlign = TextAlign.Left)

private fun scaledTypography(factor: Float): Typography {
    // Platform default font family is intentional, matching the reference's
    // active theme (its historical PingFang constant is not globally applied).
    val defaults = Typography()
    val base = Typography(
        displayLarge = defaults.displayLarge,
        displayMedium = defaults.displayMedium,
        displaySmall = defaults.displaySmall,
        headlineLarge = defaults.headlineLarge.copy(fontSize = 30.sp),
        headlineMedium = defaults.headlineMedium.copy(fontSize = 28.sp),
        headlineSmall = defaults.headlineSmall.copy(fontSize = 20.sp),
        titleLarge = defaults.titleLarge.copy(fontSize = 18.sp),
        titleMedium = defaults.titleMedium.copy(fontSize = 15.sp),
        titleSmall = defaults.titleSmall.copy(fontSize = 14.sp),
        bodyLarge = defaults.bodyLarge.copy(fontSize = 15.sp),
        bodyMedium = defaults.bodyMedium.copy(fontSize = 14.sp),
        bodySmall = defaults.bodySmall.copy(fontSize = 12.sp),
        labelLarge = defaults.labelLarge.copy(fontSize = 14.sp),
        labelMedium = defaults.labelMedium.copy(fontSize = 12.sp),
        labelSmall = defaults.labelSmall.copy(fontSize = 10.sp),
    )
    return Typography(
        displayLarge = base.displayLarge.scale(factor).bidi(),
        displayMedium = base.displayMedium.scale(factor).bidi(),
        displaySmall = base.displaySmall.scale(factor).bidi(),
        headlineLarge = base.headlineLarge.scale(factor).bidi(),
        headlineMedium = base.headlineMedium.scale(factor).bidi(),
        headlineSmall = base.headlineSmall.scale(factor).bidi(),
        titleLarge = base.titleLarge.scale(factor).bidi(),
        titleMedium = base.titleMedium.scale(factor).bidi(),
        titleSmall = base.titleSmall.scale(factor).bidi(),
        bodyLarge = base.bodyLarge.scale(factor).bidi(),
        bodyMedium = base.bodyMedium.scale(factor).bidi(),
        bodySmall = base.bodySmall.scale(factor).bidi(),
        labelLarge = base.labelLarge.scale(factor).bidi(),
        labelMedium = base.labelMedium.scale(factor).bidi(),
        labelSmall = base.labelSmall.scale(factor).bidi(),
    )
}
