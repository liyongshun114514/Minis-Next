package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import com.openminis.app.ui.components.miuix.LocalTextStyle

@Composable
fun minisFabColor() = MiuixTheme.colorScheme.primary

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val controller = remember(darkTheme) {
        ThemeController(colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light)
    }
    val textStyles = remember(fontScale) { scaledTextStyles(fontScale) }
    MiuixTheme(controller = controller, textStyles = textStyles) {
        val colors = MiuixTheme.colorScheme
        val base = if (darkTheme) DarkChatPalette else LightChatPalette
        val chatPalette = base.copy(
            background = colors.background,
            secondaryBg = colors.surfaceContainer,
            inputBg = colors.surfaceContainer,
            inputIconBg = colors.secondaryContainer,
            inputIconBorder = colors.dividerLine,
            inputBorder = colors.dividerLine,
            primaryText = colors.onSurface,
            secondaryText = colors.onSurfaceContainerVariant,
            tertiaryText = colors.onSurfaceContainerVariant.copy(alpha = 0.6f),
            disabledText = colors.disabledOnSecondaryVariant,
            userBubble = colors.primaryContainer,
            toolBg = colors.surfaceContainerHigh,
            toolBorder = colors.dividerLine,
            toolCapsuleBg = colors.secondaryContainer,
            separator = colors.dividerLine,
            sendButton = colors.primary,
            sendButtonDisabled = colors.disabledPrimaryButton,
            inlineCodeBg = colors.secondaryContainer,
            link = colors.primary,
            thinking = colors.primary,
            tableBorder = colors.dividerLine,
            sheetHeaderBg = colors.surfaceContainer,
            sheetHeaderBorder = colors.dividerLine,
            fabAccent = colors.primary,
        )
        CompositionLocalProvider(
            LocalChatPalette provides chatPalette,
            LocalTextStyle provides textStyles.main,
        ) {
            // Root host also covers dialogs declared as siblings of a screen Scaffold.
            top.yukonga.miuix.kmp.basic.Scaffold(
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                containerColor = colors.background,
            ) { content() }
        }
    }
}

/** Chat export can request light/dark independently while keeping the user's font scale. */
@Composable
fun MinisAppearance(darkTheme: Boolean, content: @Composable () -> Unit) {
    val current = MiuixTheme.textStyles
    val controller = remember(darkTheme) {
        ThemeController(colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light)
    }
    MiuixTheme(controller = controller, textStyles = current) {
        val colors = MiuixTheme.colorScheme
        val palette = (if (darkTheme) DarkChatPalette else LightChatPalette).copy(
            background = colors.background, secondaryBg = colors.surfaceContainer,
            primaryText = colors.onSurface, secondaryText = colors.onSurfaceContainerVariant,
            userBubble = colors.primaryContainer, link = colors.primary,
            toolBg = colors.surfaceContainerHigh, toolCapsuleBg = colors.secondaryContainer,
            separator = colors.dividerLine, sheetHeaderBg = colors.surfaceContainer,
        )
        CompositionLocalProvider(LocalChatPalette provides palette, content = content)
    }
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
