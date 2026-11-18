package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import com.openminis.app.ui.components.miuix.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/** Shared confirmation window using Miuix's native shape, inset handling and animations.
 * All actions use native buttons and stay readable at enlarged font scales.
 */
@Composable
fun MinisAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismissText: String = stringResource(R.string.cancel),
    isDestructive: Boolean = false,
    onDismiss: () -> Unit = onDismissRequest,
    /**
     * Optional third action, rendered between dismiss and confirm. When set,
     * the buttons stack vertically instead of sitting in a row: three labels
     * of real length (the pre-send compact prompt's "Compact & Enable
     * Auto-Compact" is 29 chars, and its zh translations are similar) do not
     * fit side by side on a phone, and a Row would either clip them or shrink
     * the text. Callers that pass nothing keep the original two-button row.
     */
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
    /**
     * The neutral action's color; null keeps the confirm button's. Lets a
     * dialog put a safe primary choice beside a destructive confirm
     * ([T-stop-choice]: "Stop Current Tool Only" next to a red "Stop All").
     */
    neutralColor: Color? = null,
) {
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = true,
        title = title,
        summary = text,
        onDismissRequest = onDismissRequest,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MinisButton(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                colors = if (isDestructive) MinisButtonDefaults.primaryColors(
                    color = MiuixTheme.colorScheme.error,
                    contentColor = MiuixTheme.colorScheme.onError,
                ) else MinisButtonDefaults.primaryColors(),
            ) { Text(confirmText) }
            if (neutralText != null && onNeutral != null) {
                MinisOutlinedButton(
                    onClick = onNeutral,
                    modifier = Modifier.fillMaxWidth(),
                    colors = MinisButtonDefaults.secondaryColors(
                        contentColor = neutralColor ?: MiuixTheme.colorScheme.primary,
                    ),
                ) { Text(neutralText) }
            }
            MinisOutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(dismissText)
            }
        }
    }
}
