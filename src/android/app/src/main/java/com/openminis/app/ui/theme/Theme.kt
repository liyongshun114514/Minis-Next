package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.openminis.app.ui.components.MinisOverlayHost
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun minisFabColor() = MiuixTheme.colorScheme.primary

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val controller = remember(darkTheme) { appearanceController(darkTheme) }
    val textStyles = remember(fontScale) { scaledTextStyles(fontScale) }
    val platformDensity = LocalDensity.current
    val appDensity = remember(platformDensity) {
        if (platformDensity is MinisUiDensity) platformDensity else MinisUiDensity(platformDensity)
    }
    CompositionLocalProvider(LocalDensity provides appDensity) {
        MiuixTheme(controller = controller, textStyles = textStyles) {
            CompositionLocalProvider(LocalChatPalette provides chatPalette(MiuixTheme.colorScheme, darkTheme)) {
                MinisOverlayHost(content)
            }
        }
    }
}

internal const val MINIS_UI_DENSITY_SCALE = 0.92f

/** Compose-only sizing. Keep Android's font converter, including large non-linear font scales. */
internal class MinisUiDensity(private val platform: Density) : Density {
    override val density: Float = platform.density * MINIS_UI_DENSITY_SCALE
    override val fontScale: Float = platform.fontScale

    override fun Dp.toSp(): TextUnit = with(platform) { this@toSp.toSp() }
    override fun TextUnit.toDp(): Dp = with(platform) { this@toDp.toDp() }
}

/** Chat export can request light/dark independently while keeping the user's font scale. */
@Composable
fun MinisAppearance(darkTheme: Boolean, content: @Composable () -> Unit) {
    val current = MiuixTheme.textStyles
    val controller = remember(darkTheme) { appearanceController(darkTheme) }
    MiuixTheme(controller = controller, textStyles = current) {
        CompositionLocalProvider(
            LocalChatPalette provides chatPalette(MiuixTheme.colorScheme, darkTheme),
            content = content,
        )
    }
}

private fun appearanceController(darkTheme: Boolean) = ThemeController(
    colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
    lightColors = lightColorScheme(
        surface = Color(0xFFF1F2F6),
        onSurfaceContainerVariant = Color(0xFF656565),
        onBackgroundVariant = Color(0xFF626A83),
    ),
    darkColors = darkColorScheme(
        surfaceContainerHigh = Color(0xFF303030),
        surfaceContainerHighest = Color(0xFF363636),
        onSurfaceContainerVariant = Color(0xFFADADAD),
        onBackgroundVariant = Color(0xFF9DA5BF),
    ),
)

internal fun chatPalette(colors: Colors, darkTheme: Boolean): ChatPalette {
    val base = if (darkTheme) DarkChatPalette else LightChatPalette
    return base.copy(
        background = colors.surface,
        secondaryBg = colors.surfaceContainer,
        inputBg = colors.surfaceContainer,
        inputIconBg = colors.secondaryContainer,
        inputIconBorder = colors.dividerLine,
        inputBorder = colors.dividerLine,
        primaryText = colors.onSurface,
        secondaryText = colors.onSurfaceSecondary,
        tertiaryText = colors.onSurfaceVariantSummary,
        disabledText = colors.disabledOnSecondaryVariant,
        userBubble = colors.surfaceContainerHigh,
        toolBg = colors.surfaceContainer,
        toolBorder = colors.dividerLine,
        toolCapsuleBg = colors.secondaryContainer,
        separator = colors.dividerLine,
        // Composer actions are neutral in both themes, never the blue primary palette.
        sendButton = colors.onSurface,
        sendButtonDisabled = colors.onSurface.copy(alpha = 0.10f),
        inlineCodeBg = colors.secondaryContainer,
        link = colors.primary,
        thinking = colors.primary,
        tableBorder = colors.dividerLine,
        sheetHeaderBg = colors.surfaceContainer,
        sheetHeaderBorder = colors.dividerLine,
        fabAccent = colors.primary,
    )
}

private fun TextStyle.scale(factor: Float): TextStyle = copy(
    fontSize = fontSize * factor,
    textDirection = TextDirection.Content,
    textAlign = TextAlign.Left,
)

private fun scaledTextStyles(factor: Float): TextStyles {
    val base = defaultTextStyles()
    return base.copy(
        main = base.main.scale(factor),
        paragraph = base.paragraph.scale(factor),
        body1 = base.body1.scale(factor),
        body2 = base.body2.scale(factor),
        button = base.button.scale(factor),
        footnote1 = base.footnote1.scale(factor),
        footnote2 = base.footnote2.scale(factor),
        headline1 = base.headline1.scale(factor),
        headline2 = base.headline2.scale(factor),
        subtitle = base.subtitle.scale(factor),
        title1 = base.title1.scale(factor),
        title2 = base.title2.scale(factor),
        title3 = base.title3.scale(factor),
        title4 = base.title4.scale(factor),
    )
}
