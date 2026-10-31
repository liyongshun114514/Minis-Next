package com.openminis.app.ui.theme

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/** Reference theme changes interpolate all surfaces/text together for 220ms.
 * Each palette is a single transition, so mid-animation overlays follow the
 * same colours as their host rather than snapping ahead of the page.
 * Export re-theming deliberately bypasses this animation (MinisAppearance).
 */
@Composable
internal fun animatedOmniPalette(dark: Boolean): OmniPalette {
    val transition = updateTransition(dark, label = "omni-theme")
    val pageBackground by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "pageBackground") { if (it) OmniDarkPalette.pageBackground else OmniLightPalette.pageBackground }
    val surfacePrimary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfacePrimary") { if (it) OmniDarkPalette.surfacePrimary else OmniLightPalette.surfacePrimary }
    val surfaceSecondary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceSecondary") { if (it) OmniDarkPalette.surfaceSecondary else OmniLightPalette.surfaceSecondary }
    val surfaceElevated by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceElevated") { if (it) OmniDarkPalette.surfaceElevated else OmniLightPalette.surfaceElevated }
    val borderSubtle by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "borderSubtle") { if (it) OmniDarkPalette.borderSubtle else OmniLightPalette.borderSubtle }
    val borderStrong by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "borderStrong") { if (it) OmniDarkPalette.borderStrong else OmniLightPalette.borderStrong }
    val textPrimary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "textPrimary") { if (it) OmniDarkPalette.textPrimary else OmniLightPalette.textPrimary }
    val textSecondary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "textSecondary") { if (it) OmniDarkPalette.textSecondary else OmniLightPalette.textSecondary }
    val textTertiary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "textTertiary") { if (it) OmniDarkPalette.textTertiary else OmniLightPalette.textTertiary }
    val accentPrimary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "accentPrimary") { if (it) OmniDarkPalette.accentPrimary else OmniLightPalette.accentPrimary }
    val brandPrimary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "brandPrimary") { if (it) OmniDarkPalette.brandPrimary else OmniLightPalette.brandPrimary }
    val brandSurface by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "brandSurface") { if (it) OmniDarkPalette.brandSurface else OmniLightPalette.brandSurface }
    val segmentTrack by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "segmentTrack") { if (it) OmniDarkPalette.segmentTrack else OmniLightPalette.segmentTrack }
    val segmentThumb by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "segmentThumb") { if (it) OmniDarkPalette.segmentThumb else OmniLightPalette.segmentThumb }
    val overlayScrim by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "overlayScrim") { if (it) OmniDarkPalette.overlayScrim else OmniLightPalette.overlayScrim }
    val previewFallback by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "previewFallback") { if (it) OmniDarkPalette.previewFallback else OmniLightPalette.previewFallback }
    val shadowColor by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "shadowColor") { if (it) OmniDarkPalette.shadowColor else OmniLightPalette.shadowColor }
    val destructive by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "destructive") { if (it) OmniDarkPalette.destructive else OmniLightPalette.destructive }
    return OmniPalette(isDark = dark,
        pageBackground = pageBackground,
        surfacePrimary = surfacePrimary,
        surfaceSecondary = surfaceSecondary,
        surfaceElevated = surfaceElevated,
        borderSubtle = borderSubtle,
        borderStrong = borderStrong,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textTertiary = textTertiary,
        accentPrimary = accentPrimary,
        brandPrimary = brandPrimary,
        brandSurface = brandSurface,
        segmentTrack = segmentTrack,
        segmentThumb = segmentThumb,
        overlayScrim = overlayScrim,
        previewFallback = previewFallback,
        shadowColor = shadowColor,
        destructive = destructive,
    )
}

@Composable
internal fun animatedOmniColorScheme(target: ColorScheme): ColorScheme {
    val transition = updateTransition(target, label = "omni-material-theme")
    val primary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "primary") { it.primary }
    val onPrimary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onPrimary") { it.onPrimary }
    val primaryContainer by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "primaryContainer") { it.primaryContainer }
    val onPrimaryContainer by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onPrimaryContainer") { it.onPrimaryContainer }
    val secondary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "secondary") { it.secondary }
    val onSecondary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onSecondary") { it.onSecondary }
    val secondaryContainer by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "secondaryContainer") { it.secondaryContainer }
    val onSecondaryContainer by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onSecondaryContainer") { it.onSecondaryContainer }
    val tertiary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "tertiary") { it.tertiary }
    val onTertiary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onTertiary") { it.onTertiary }
    val tertiaryContainer by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "tertiaryContainer") { it.tertiaryContainer }
    val onTertiaryContainer by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onTertiaryContainer") { it.onTertiaryContainer }
    val background by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "background") { it.background }
    val onBackground by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onBackground") { it.onBackground }
    val surface by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surface") { it.surface }
    val onSurface by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onSurface") { it.onSurface }
    val surfaceVariant by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceVariant") { it.surfaceVariant }
    val onSurfaceVariant by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onSurfaceVariant") { it.onSurfaceVariant }
    val surfaceTint by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceTint") { it.surfaceTint }
    val inverseSurface by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "inverseSurface") { it.inverseSurface }
    val inverseOnSurface by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "inverseOnSurface") { it.inverseOnSurface }
    val inversePrimary by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "inversePrimary") { it.inversePrimary }
    val error by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "error") { it.error }
    val onError by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onError") { it.onError }
    val errorContainer by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "errorContainer") { it.errorContainer }
    val onErrorContainer by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "onErrorContainer") { it.onErrorContainer }
    val outline by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "outline") { it.outline }
    val outlineVariant by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "outlineVariant") { it.outlineVariant }
    val scrim by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "scrim") { it.scrim }
    val surfaceBright by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceBright") { it.surfaceBright }
    val surfaceDim by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceDim") { it.surfaceDim }
    val surfaceContainerLowest by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceContainerLowest") { it.surfaceContainerLowest }
    val surfaceContainerLow by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceContainerLow") { it.surfaceContainerLow }
    val surfaceContainer by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceContainer") { it.surfaceContainer }
    val surfaceContainerHigh by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceContainerHigh") { it.surfaceContainerHigh }
    val surfaceContainerHighest by transition.animateColor(transitionSpec = { tween(220, easing = LinearEasing) }, label = "surfaceContainerHighest") { it.surfaceContainerHighest }
    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = surfaceTint,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        inversePrimary = inversePrimary,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = scrim,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
}
