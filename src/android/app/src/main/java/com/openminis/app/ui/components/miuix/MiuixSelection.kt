package com.openminis.app.ui.components.miuix

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

object SegmentedButtonDefaults {
    fun itemShape(index: Int, count: Int): Shape = RoundedCornerShape(16.dp)
}

@Composable
fun SingleChoiceSegmentedButtonRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** Native Miuix buttons with explicit radio semantics; not Material's outlined segments. */
@Composable
fun RowScope.SegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    icon: @Composable () -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick = onClick, modifier = Modifier.weight(1f).semantics {
        this.selected = selected; role = Role.RadioButton
    }, colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        content = content)
}

@Composable
fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.semantics { this.selected = selected },
        minHeight = 32.dp, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { label() }
}

object AssistChipDefaults {
    @Composable fun assistChipColors(containerColor: Color = MiuixTheme.colorScheme.secondaryVariant): ButtonColors =
        ButtonDefaults.buttonColors(color = containerColor)
}
@Composable
fun AssistChip(onClick: () -> Unit, label: @Composable () -> Unit, colors: ButtonColors = ButtonDefaults.buttonColors()) {
    Button(onClick = onClick, colors = colors, minHeight = 32.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { label() }
}
