package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.GlassTextFieldDefaults
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
    backgroundColor: androidx.compose.ui.graphics.Color = GlassTextFieldDefaults.textFieldColors().backgroundColor,
    /** Places multiline content at the top while retaining the full field height. */
    topAligned: Boolean = false,
) {
    val colors = GlassTextFieldDefaults.textFieldColors(
        backgroundColor = backgroundColor,
        labelColor = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        borderColor = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
    )
    if (topAligned) {
        TopAlignedDialogTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).then(fieldModifier),
            placeholder = placeholder,
            enabled = enabled,
            readOnly = readOnly,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            textStyle = textStyle.copy(color = MiuixTheme.colorScheme.onSurface),
            singleLine = singleLine,
            trailingIcon = trailingIcon,
            colors = colors,
        )
    } else {
        GlassTextField(
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
}

@Composable
private fun TopAlignedDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    placeholder: String?,
    enabled: Boolean,
    readOnly: Boolean,
    maxLines: Int,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    visualTransformation: VisualTransformation,
    textStyle: TextStyle,
    singleLine: Boolean,
    trailingIcon: @Composable (() -> Unit)?,
    colors: top.yukonga.miuix.kmp.basic.TextFieldColors,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val borderWidth = if (focused) 2.dp else 0.dp
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(colors.borderColor),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(colors.backgroundColor, shape)
                    .border(borderWidth, colors.borderColor, shape),
                contentAlignment = Alignment.TopStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (value.isEmpty() && !placeholder.isNullOrEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle.copy(color = colors.labelColor),
                            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                        )
                    }
                    innerTextField()
                }
                trailingIcon?.let { icon ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 8.dp),
                    ) { icon() }
                }
            }
        },
    )
}
