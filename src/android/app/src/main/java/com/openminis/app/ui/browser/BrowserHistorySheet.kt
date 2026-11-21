package com.openminis.app.ui.browser

import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.browser.BrowserHistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.openminis.app.ui.components.MinisTextButton as TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.components.MinisDialog as WindowDialog
import androidx.compose.ui.unit.DpSize
import com.openminis.app.ui.components.MinisBottomSheet
import top.yukonga.miuix.kmp.basic.TextField
import androidx.compose.ui.semantics.error

@Composable
fun BrowserHistorySheet(
    historyStore: BrowserHistoryStore,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    val entries = remember(searchQuery, historyStore.getEntries().size) {
        if (searchQuery.isBlank()) historyStore.groupedByDay()
        else mapOf("Results" to historyStore.search(searchQuery))
    }

    MinisBottomSheet(
        show = true,
        deferDismissRequest = true,
        insideMargin = DpSize.Zero,
        onDismissRequest = onDismiss,
    ) {
        val dismissSheet = top.yukonga.miuix.kmp.theme.LocalDismissState.current ?: onDismiss
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.browser_history_title),
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { showClearConfirm = true },
                    enabled = historyStore.getEntries().isNotEmpty(),
                    text = stringResource(R.string.browser_history_clear),
                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.error),
                )
                TextButton(
                    onClick = dismissSheet,
                    text = stringResource(R.string.browser_history_done),
                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                )
            }

            // Search bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body2,
                label = stringResource(R.string.browser_history_search),
                useLabelAsPlaceholder = true,
                modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(8.dp))

            if (entries.values.flatten().isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.browser_history_empty_title),
                            style = MiuixTheme.textStyles.main,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.browser_history_empty_subtitle),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                ) {
                    for ((dayLabel, dayEntries) in entries) {
                        if (dayEntries.isEmpty()) continue
                        item(key = "header_$dayLabel") {
                            Text(
                                dayLabel,
                                style = MiuixTheme.textStyles.footnote1,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(dayEntries, key = { it.id }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigate(entry.url) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.title.ifEmpty { entry.domain },
                                        style = MiuixTheme.textStyles.body1,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        entry.url,
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestamp)),
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f),
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        WindowDialog(
            show = true,
            onDismissRequest = { showClearConfirm = false },
            title = stringResource(R.string.browser_history_clear_dialog_title),
            summary = stringResource(R.string.browser_history_clear_dialog_message),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.openminis.app.ui.components.MinisDialogActions(
                    dismissButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showClearConfirm = false },
                            text = stringResource(R.string.cancel),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                historyStore.clear()
                                showClearConfirm = false
                            },
                            text = stringResource(R.string.browser_history_clear),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.error),
                        )
                    },
                )
            }
        }
    }
}
