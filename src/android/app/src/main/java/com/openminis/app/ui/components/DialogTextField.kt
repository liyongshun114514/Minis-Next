package com.openminis.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix text input for use inside a Dialog / ModalBottomSheet /
 * full-screen editor. Sister composable of [SectionTextField], with a
 * visible container for dialog and sheet backgrounds.
 *
 * Layout contract: caller renders the label outside the field
 * (`Text(label) + Spacer(6dp) + DialogTextField(...)`); placeholder remains
 * inside the Miuix field.
 */
@Composable
fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    fieldModifier: Modifier = Modifier,
    backgroundColor: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.surfaceContainerHigh,
) {
    val colors = TextFieldDefaults.textFieldColors(
        backgroundColor = backgroundColor,
        labelColor = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        borderColor = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
    )
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).then(fieldModifier),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle.copy(color = MiuixTheme.colorScheme.onSurface),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        label = placeholder.orEmpty(),
        useLabelAsPlaceholder = true,
        cornerRadius = 12.dp,
        insideMargin = DpSize(16.dp, 10.dp),
        trailingIcon = trailingIcon,
        colors = colors,
    )
}
