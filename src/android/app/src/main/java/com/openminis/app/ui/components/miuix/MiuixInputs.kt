package com.openminis.app.ui.components.miuix

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldColors
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** App-local style for rich document editors; all visual controls are Miuix. */
val LocalTextStyle = compositionLocalOf { TextStyle.Default }

object OutlinedTextFieldDefaults {
    @Composable
    fun DecorationBox(
        value: String,
        innerTextField: @Composable () -> Unit,
        enabled: Boolean,
        singleLine: Boolean,
        visualTransformation: VisualTransformation,
        interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource,
        placeholder: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit)? = null,
        isError: Boolean = false,
        leadingIcon: (@Composable () -> Unit)? = null,
        trailingIcon: (@Composable () -> Unit)? = null,
        colors: TextFieldColors = TextFieldDefaults.textFieldColors(),
        contentPadding: PaddingValues = PaddingValues(16.dp),
        container: @Composable () -> Unit = {},
    ) {
        top.yukonga.miuix.kmp.basic.Surface(
            color = colors.backgroundColor,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(contentPadding), verticalAlignment = Alignment.CenterVertically) {
                leadingIcon?.invoke()
                Box(Modifier.weight(1f)) {
                    innerTextField()
                    if (value.isEmpty()) placeholder?.invoke()
                }
                trailingIcon?.invoke()
            }
        }
    }

    // DecorationBox owns the native Miuix surface; these legacy slots must not draw a second layer.
    @Composable
    fun Container(enabled: Boolean, isError: Boolean,
        interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource,
        colors: TextFieldColors, shape: Shape) { }
    @Composable
    fun ContainerBox(enabled: Boolean, isError: Boolean,
        interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource,
        colors: TextFieldColors, shape: Shape) { }

    @Composable
    fun colors(
        focusedContainerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
        unfocusedContainerColor: Color = focusedContainerColor,
        disabledContainerColor: Color = unfocusedContainerColor,
        focusedBorderColor: Color = MiuixTheme.colorScheme.primary,
        unfocusedBorderColor: Color = Color.Transparent,
        focusedTextColor: Color = MiuixTheme.colorScheme.onSurface,
        unfocusedTextColor: Color = focusedTextColor,
        cursorColor: Color = focusedBorderColor,
        disabledBorderColor: Color = Color.Transparent,
        errorBorderColor: Color = MiuixTheme.colorScheme.error,
        errorContainerColor: Color = focusedContainerColor,
        focusedLabelColor: Color = MiuixTheme.colorScheme.primary,
        unfocusedLabelColor: Color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
    ): TextFieldColors = TextFieldDefaults.textFieldColors(
        backgroundColor = unfocusedContainerColor, borderColor = focusedBorderColor,
    )
}

object InputTextFieldDefaults {
    @Composable
    fun colors(
        focusedContainerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
        unfocusedContainerColor: Color = focusedContainerColor,
        disabledContainerColor: Color = unfocusedContainerColor,
        focusedIndicatorColor: Color = MiuixTheme.colorScheme.primary,
        unfocusedIndicatorColor: Color = Color.Transparent,
        disabledIndicatorColor: Color = Color.Transparent,
        errorIndicatorColor: Color = MiuixTheme.colorScheme.error,
    ): TextFieldColors = TextFieldDefaults.textFieldColors(
        backgroundColor = unfocusedContainerColor, borderColor = focusedIndicatorColor,
    )
}

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape? = null,
    colors: TextFieldColors = TextFieldDefaults.textFieldColors(),
) {
    // Preserve selection/composition across string updates just as the native value overload does.
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    val current = if (field.text == value) field else field.copy(text = value)
    OutlinedTextField(
        value = current,
        onValueChange = { field = it; if (it.text != value) onValueChange(it.text) },
        modifier = modifier, enabled = enabled, readOnly = readOnly, textStyle = textStyle,
        label = label, placeholder = placeholder, leadingIcon = leadingIcon,
        trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, singleLine = singleLine, maxLines = maxLines,
        minLines = minLines, colors = colors, shape = shape,
    )
}

@Composable
fun OutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape? = null,
    colors: TextFieldColors = TextFieldDefaults.textFieldColors(),
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.invoke()
        Box(contentAlignment = Alignment.CenterStart) {
            TextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().then(
                    if (isError) Modifier.semantics { error("Invalid value") } else Modifier,
                ),
                enabled = enabled, readOnly = readOnly, textStyle = textStyle,
                leadingIcon = leadingIcon, trailingIcon = trailingIcon,
                visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions, singleLine = singleLine,
                maxLines = maxLines, minLines = minLines,
                colors = if (isError) colors.copy(borderColor = MiuixTheme.colorScheme.error) else colors,
                cornerRadius = TextFieldDefaults.CornerRadius,
            )
            if (value.text.isEmpty() && placeholder != null) {
                Box(Modifier.padding(start = if (leadingIcon == null) 16.dp else 48.dp, end = 16.dp)) {
                    placeholder()
                }
            }
        }
        supportingText?.invoke()
    }
}
