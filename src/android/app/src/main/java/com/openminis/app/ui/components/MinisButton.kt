package com.openminis.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.LocalContentColor as LegacyContentColor
import androidx.compose.material3.LocalTextStyle as LegacyTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Phone actions keep a 48dp touch target; embedded card actions use 32dp.
val MinisButtonHeight = 48.dp
val MinisSmallButtonHeight = 32.dp
private val SmallButtonContentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)

/** Miuix has primary and secondary buttons, not Material outlined/text variants.
 * Keep the public action names while rendering native Miuix squircle buttons.
 */
object MinisButtonDefaults {
    @Composable
    fun buttonColors(
        containerColor: Color = MiuixTheme.colorScheme.primary,
        contentColor: Color = MiuixTheme.colorScheme.onPrimary,
    ): ButtonColors = primaryColors(containerColor, contentColor)

    @Composable
    fun outlinedButtonColors(
        contentColor: Color = MiuixTheme.colorScheme.onSecondaryVariant,
    ): ButtonColors = secondaryColors(contentColor)

    @Composable
    fun textButtonColors(
        contentColor: Color = MiuixTheme.colorScheme.primary,
    ): ButtonColors = textColors(contentColor)

    @Composable
    fun primaryColors(
        color: Color = MiuixTheme.colorScheme.primary,
        contentColor: Color = MiuixTheme.colorScheme.onPrimary,
    ): ButtonColors = ButtonDefaults.buttonColorsPrimary(color = color, contentColor = contentColor)

    @Composable
    fun secondaryColors(
        contentColor: Color = MiuixTheme.colorScheme.onSecondaryVariant,
    ): ButtonColors = ButtonDefaults.buttonColors(contentColor = contentColor)

    @Composable
    fun textColors(
        contentColor: Color = MiuixTheme.colorScheme.primary,
    ): ButtonColors = ButtonDefaults.buttonColors(
        color = Color.Transparent,
        disabledColor = Color.Transparent,
        contentColor = contentColor,
        disabledContentColor = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
    )
}

@Composable
fun MinisButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = MinisButtonDefaults.primaryColors(),
    contentPadding: PaddingValues = ButtonDefaults.InsideMargin,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    NativeMinisButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        minHeight = MinisButtonHeight,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun MinisOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = MinisButtonDefaults.secondaryColors(),
    contentPadding: PaddingValues = ButtonDefaults.InsideMargin,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    NativeMinisButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        minHeight = MinisButtonHeight,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun MinisTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = MinisButtonDefaults.textColors(),
    contentPadding: PaddingValues = ButtonDefaults.InsideMargin,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    NativeMinisButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        minHeight = MinisButtonHeight,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun MinisSmallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = MinisButtonDefaults.primaryColors(),
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    NativeMinisButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        minHeight = MinisSmallButtonHeight,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun MinisSmallOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = MinisButtonDefaults.secondaryColors(),
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    NativeMinisButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        minHeight = MinisSmallButtonHeight,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun MinisSmallTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = MinisButtonDefaults.textColors(),
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    NativeMinisButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        minHeight = MinisSmallButtonHeight,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
private fun NativeMinisButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    colors: ButtonColors,
    minHeight: Dp,
    contentPadding: PaddingValues,
    interactionSource: MutableInteractionSource?,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = minHeight,
        colors = colors,
        insideMargin = contentPadding,
        interactionSource = interactionSource,
    ) {
        // Only a content-local bridge for legacy children during migration;
        // the button, shape, press indication and disabled state are all Miuix.
        CompositionLocalProvider(
            LegacyContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor,
            LegacyTextStyle provides MiuixTheme.textStyles.button,
        ) {
            content()
        }
    }
}
