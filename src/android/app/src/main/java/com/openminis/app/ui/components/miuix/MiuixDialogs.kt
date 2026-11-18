package com.openminis.app.ui.components.miuix

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

/** Slot-preserving application API over the native Miuix dialog, not a Material dialog. */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = MiuixTheme.colorScheme.surface,
) {
    WindowDialog(show = true, onDismissRequest = onDismissRequest, modifier = modifier,
        backgroundColor = containerColor) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            title?.let { it() }
            text?.let { it() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

enum class SheetValue { Hidden, PartiallyExpanded, Expanded }

@Stable
class SheetState internal constructor(private val confirm: (SheetValue) -> Boolean) {
    var currentValue by mutableStateOf(SheetValue.Expanded)
        private set
    val isVisible: Boolean get() = currentValue != SheetValue.Hidden
    suspend fun hide() { if (confirm(SheetValue.Hidden)) currentValue = SheetValue.Hidden }
    suspend fun show() { if (confirm(SheetValue.Expanded)) currentValue = SheetValue.Expanded }
    suspend fun expand() = show()
    internal fun canDismiss(): Boolean = confirm(SheetValue.Hidden)
}

@Composable
fun rememberModalBottomSheetState(
    skipPartiallyExpanded: Boolean = false,
    confirmValueChange: (SheetValue) -> Boolean = { true },
): SheetState {
    val confirm = rememberUpdatedState(confirmValueChange)
    return remember(skipPartiallyExpanded) { SheetState { confirm.value(it) } }
}

data class ModalBottomSheetProperties(val shouldDismissOnBackPress: Boolean = true)

/** Native window sheet also works from activities that do not own a Scaffold popup host. */
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = MiuixTheme.colorScheme.surface,
    dragHandle: (@Composable () -> Unit)? = null,
    contentWindowInsets: @Composable () -> WindowInsets = { WindowInsets(0) },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    WindowBottomSheet(
        show = sheetState.isVisible,
        modifier = modifier,
        backgroundColor = containerColor,
        onDismissRequest = { if (sheetState.canDismiss()) onDismissRequest() },
        allowDismiss = sheetState.canDismiss(),
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        BackHandler(enabled = !properties.shouldDismissOnBackPress) { }
        Column(Modifier.windowInsetsPadding(contentWindowInsets())) {
            dragHandle?.invoke()
            content()
        }
    }
}
