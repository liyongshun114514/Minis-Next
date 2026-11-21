package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Two native dialog actions share one bottom row: cancel left, confirm right. */
@Composable
internal fun MinisDialogActions(
    dismissButton: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalMinisDialogActionRole provides top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Normal,
                content = dismissButton,
            )
        }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalMinisDialogActionRole provides top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Primary,
                content = confirmButton,
            )
        }
    }
}
