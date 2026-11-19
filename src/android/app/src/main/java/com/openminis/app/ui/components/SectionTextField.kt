package com.openminis.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.DpSize
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Text input for use inside a [SectionCard], styled with Miuix TextField
 * while retaining this app's string-based state and cursor-selection contract.
 * Error state uses the Miuix error border and tint.
 *
 * A field is its own filled Miuix surface. Parent/card gutters position that
 * surface; they do not replace the native inner margin between its edge and text.
 * Keep an explicit inner inset in settings, sheets and dialogs alike.
 */
@Composable
fun SectionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    fieldModifier: Modifier = Modifier,
    /** Optional semantic fill override; the default separates the input from its Card. */
    containerColor: Color? = null,
    /** Inner text margin, independent from page/Card/SettingsCardBlock gutters. */
    contentHorizontalPadding: Dp = SectionDesign.RowHorizontalPadding,
) {
    val fieldColors = TextFieldDefaults.textFieldColors(
        backgroundColor = containerColor ?: MiuixTheme.colorScheme.surfaceContainerHigh,
        labelColor = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        borderColor = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
    )
    val mergedTextStyle = textStyle.copy(color = MiuixTheme.colorScheme.onSurface)

    // [T-android-model-id-input-no-hscroll]
    // The legacy `BasicTextField(value: String, ...)` overload reconstructs
    // its internal TextFieldValue from `value` on every recomposition,
    // which forces selection back to the end of the string each time the
    // parent emits a fresh recompose. On a long string in a singleLine
    // field, that means: user taps to move the caret → caret moves
    // briefly → next recompose snaps caret to value.length → the
    // bring-cursor-into-view path never gets to "scroll content sideways
    // so the trailing edge of the text becomes visible" because the
    // caret is always at the END of the (clipped) layout. Net effect: a
    // 60-char model id like "huihui-qwen3.6-35b-…-mtp" has its last
    // characters permanently off-screen and the user can't reach them.
    //
    // Switch to the TextFieldValue overload — selection is owned locally
    // and survives recomposition. The outer caller still passes a plain
    // String; we wrap it, drive onValueChange with the new text only
    // when text actually changed, and re-seed cursor-at-end only when
    // the parent replaces the value out from under us (e.g. screen
    // reset / hydration / a different entry loaded).
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    }
    if (fieldValue.text != value) {
        // External value changed (parent assigned a fresh string).
        // Re-seed with cursor at end — matches the legacy String
        // overload's behavior on the same transition.
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    TextField(
        modifier = modifier.fillMaxWidth()
            .heightIn(min = SectionDesign.TextFieldMinHeight)
            .then(fieldModifier),
        value = fieldValue,
        onValueChange = { newValue ->
            fieldValue = newValue
            if (newValue.text != value) onValueChange(newValue.text)
        },
            enabled = enabled,
            readOnly = readOnly,
            textStyle = mergedTextStyle,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = 1,
            label = placeholder.orEmpty(),
            useLabelAsPlaceholder = true,
            cornerRadius = 12.dp,
            insideMargin = DpSize(contentHorizontalPadding, SectionDesign.RowVerticalPadding),
            trailingIcon = trailingIcon,
        colors = fieldColors,
    )
}
