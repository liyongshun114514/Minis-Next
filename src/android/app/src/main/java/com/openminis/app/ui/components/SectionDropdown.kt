package com.openminis.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Typed selection with Miuix's native press feedback, anchor and selection popup. */
@Composable
fun <T> SectionDropdown(
    selected: T,
    items: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    itemLabel: (T) -> String = { it.toString() },
    textStyle: TextStyle = MiuixTheme.textStyles.main,
) {
    Surface(color = SectionDesign.cardColor(), shape = SectionDesign.CardShape, modifier = modifier.fillMaxWidth()) {
        MiuixTheme(textStyles = MiuixTheme.textStyles.copy(main = textStyle)) {
            WindowSpinnerPreference(
                title = itemLabel(selected),
                items = items.map { DropdownItem(text = itemLabel(it)) },
                selectedIndex = items.indexOf(selected).coerceAtLeast(0),
                enabled = enabled,
                showValue = false,
                onSelectedIndexChange = { onSelect(items[it]) },
            )
        }
    }
}
