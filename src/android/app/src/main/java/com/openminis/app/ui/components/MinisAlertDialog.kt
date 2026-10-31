package com.openminis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.OmniColors
import com.openminis.app.ui.theme.omniTextStyle

/**
 * App-wide confirmation dialog.
 *
 * [T-omnibot-ui-port] Restyled to the Omni dialog language: flat
 * `surfacePrimary` panel on a 20dp radius with a hairline `borderSubtle` edge
 * (no M3 tonal elevation, no drop shadow), 16sp medium title over 14sp
 * `textSecondary` body, and 20dp inner padding. The confirm action reads
 * `accentPrimary` (black / near-white) and the destructive variant
 * `#E05252`, matching OmniBot's confirm fill. Behaviour, parameters and the
 * three-action stacked layout are unchanged.
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
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = OmniColors.surfacePrimary,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(0.75.dp, OmniColors.borderSubtle),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = omniTextStyle(
                        size = 16.sp,
                        color = OmniColors.textPrimary,
                        weight = FontWeight.Medium,
                        lineHeight = 22.sp,
                    ),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                )
                if (text != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = omniTextStyle(
                            size = 14.sp,
                            color = OmniColors.textSecondary,
                            lineHeight = 20.sp,
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                val confirmColor = if (isDestructive) {
                    OmniColors.destructive
                } else {
                    OmniColors.accentPrimary
                }
                if (neutralText != null && onNeutral != null) {
                    // Stacked layout. Order runs least-committal first
                    // (dismiss) down to the most specific action, so the
                    // primary choice sits closest to the thumb.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        MinisTextButton(onClick = onDismiss) {
                            Text(dismissText)
                        }
                        MinisTextButton(onClick = onConfirm) {
                            Text(text = confirmText, color = confirmColor)
                        }
                        MinisTextButton(onClick = onNeutral) {
                            Text(text = neutralText, color = neutralColor ?: confirmColor)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        MinisTextButton(onClick = onDismiss) {
                            Text(dismissText)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        MinisTextButton(onClick = onConfirm) {
                            Text(text = confirmText, color = confirmColor)
                        }
                    }
                }
            }
        }
    }
}
