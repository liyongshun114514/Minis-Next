package com.openminis.app.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextButtonColors
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalMinisDialogActionRole = staticCompositionLocalOf<GlassDialogButtonRole?> { null }

/** Preserve native page buttons; modal actions share the filled glass-dialog treatment. */
@Composable
internal fun MinisTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: TextButtonColors = ButtonDefaults.textButtonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    textStyle: TextStyle? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
) {
    if (!LocalMinisDialogSurface.current) {
        TextButton(text, onClick, modifier, enabled, cornerRadius, minWidth, minHeight,
            colors, insideMargin, textStyle, interactionSource, indication)
        return
    }
    val scheme = MiuixTheme.colorScheme
    val secondaryAction = text in setOf(
        stringResource(R.string.common_cancel), stringResource(R.string.cancel),
        stringResource(R.string.common_close), stringResource(android.R.string.cancel),
    )
    val slotRole = LocalMinisDialogActionRole.current
    val role = when {
        colors.textColor == scheme.error || colors.color == scheme.error -> GlassDialogButtonRole.Destructive
        slotRole != null -> slotRole
        secondaryAction -> GlassDialogButtonRole.Normal
        else -> GlassDialogButtonRole.Primary
    }
    GlassDialogButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        role = role,
        cornerRadius = cornerRadius,
        minWidth = 0.dp,
        minHeight = maxOf(50.dp, minHeight),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        interactionSource = interactionSource,
    ) { Text(text, style = textStyle ?: MiuixTheme.textStyles.button) }
}
