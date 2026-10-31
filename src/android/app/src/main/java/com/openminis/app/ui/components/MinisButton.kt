package com.openminis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.OmniColors

// Material3 ButtonDefaults.MinHeight = 40dp; tuned to 48dp for touch
// ergonomics on phones. IconButton family is unaffected (icon-sized).
val MinisButtonHeight = 48.dp

// Compact button height for actions embedded inside section cards
// (e.g. "Sign out" inside a credentials card, "Set Bearer Token" inside
// a token section). Visually subordinate to MinisButtonHeight (48dp)
// which remains the size for primary screen actions ("Add Custom Model",
// TopAppBar Save, AlertDialog confirm).
val MinisSmallButtonHeight = 32.dp

private val SmallButtonContentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)

/**
 * [T-omnibot-ui-port] Omni button language: 14dp rounded rectangle (was a
 * Material pill), 48dp tall, 15sp medium label, filled with the monochrome
 * `accentPrimary` — OmniBot's confirm/primary fill (utils/ui.dart, 44–48dp,
 * 14dp radius) rather than a brand-tinted Material primary.
 *
 * The `colors`/`shape` parameters are kept on all six entry points, so any
 * caller that passes its own styling renders exactly as before.
 */
private val OmniButtonShape = RoundedCornerShape(14.dp)

/** Filled: accent fill + page-coloured label (Omni inverts light/dark). */
@Composable
fun omniFilledButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = OmniColors.accentPrimary,
    contentColor = OmniColors.pageBackground,
    disabledContainerColor = OmniColors.surfaceElevated,
    disabledContentColor = OmniColors.textTertiary,
)

/** Outlined: transparent fill + hairline `borderStrong`, text in textPrimary. */
@Composable
fun omniOutlinedButtonColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
    contentColor = OmniColors.textPrimary,
    disabledContentColor = OmniColors.textTertiary,
)

/** Text: accent label, no container. */
@Composable
fun omniTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(
    contentColor = OmniColors.accentPrimary,
    disabledContentColor = OmniColors.textTertiary,
)

/** Hairline outline for the outlined variants. */
@Composable
fun omniOutlinedButtonBorder(enabled: Boolean = true): BorderStroke = BorderStroke(
    0.75.dp,
    if (enabled) OmniColors.borderStrong else OmniColors.borderSubtle,
)

@Composable
fun MinisButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = OmniButtonShape,
    colors: ButtonColors = omniFilledButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = OmniButtonShape,
    colors: ButtonColors = omniOutlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = omniOutlinedButtonBorder(enabled = enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = OmniButtonShape,
    colors: ButtonColors = omniTextButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

// defaultMinSize is also pinned at MinisSmallButtonHeight so Material3's
// internal 40dp floor (ButtonDefaults.MinHeight) doesn't override the
// heightIn modifier and keep the button at 40dp.
@Composable
fun MinisSmallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = OmniButtonShape,
    colors: ButtonColors = omniFilledButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisSmallOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = OmniButtonShape,
    colors: ButtonColors = omniOutlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = omniOutlinedButtonBorder(enabled = enabled),
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisSmallTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = OmniButtonShape,
    colors: ButtonColors = omniTextButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}
