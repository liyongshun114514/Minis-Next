package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * [T-stop-choice] "What should stop?" - shown instead of an immediate Stop
 * when the conversation has background work a Stop would also end (sub
 * agents, scheduled tasks; [StopSummary.hasBackground]).
 *
 * Lists what is running and how many of each, then offers:
 *  - Stop Current Tool Only ([onToolOnly], the safe primary choice, nearest the
 *    thumb) - only when this chat's own turn is running ([canStopToolOnly]);
 *    with only background work there is no "current tool" to stop;
 *  - Stop All ([onStopAll], destructive);
 *  - Cancel.
 *
 * Also used by New Chat's "a task is running" guard, with its own title and
 * button labels and the [body] override.
 *
 * Native Miuix glass dialog with the existing stop-scope callbacks.
 */
@Composable
internal fun StopChoiceDialog(
    summary: StopSummary,
    canStopToolOnly: Boolean,
    onDismiss: () -> Unit,
    onToolOnly: () -> Unit,
    onStopAll: () -> Unit,
    title: String = stringResource(R.string.chat_stop_choice_title),
    toolOnlyText: String = stringResource(R.string.chat_stop_choice_tool_only),
    stopAllText: String = stringResource(R.string.chat_stop_choice_all),
    body: String? = null,
) {
    val lines = buildList {
        if (canStopToolOnly) {
            add(
                if (summary.runningTools.isEmpty()) {
                    stringResource(R.string.chat_stop_choice_current_reply)
                } else {
                    stringResource(R.string.chat_stop_choice_current_tools, summary.runningTools.distinct().joinToString(", "))
                },
            )
        }
        if (summary.subAgentsRunning > 0) {
            add(pluralStringResource(R.plurals.chat_stop_choice_subagents, summary.subAgentsRunning, summary.subAgentsRunning))
        }
        if (summary.subAgentsQueued > 0) {
            add(pluralStringResource(R.plurals.chat_stop_choice_subagents_queued, summary.subAgentsQueued, summary.subAgentsQueued))
        }
        if (summary.scheduledTasks > 0) {
            add(pluralStringResource(R.plurals.chat_stop_choice_scheduled, summary.scheduledTasks, summary.scheduledTasks))
        }
    }
    val footer = buildList {
        if (canStopToolOnly) add(stringResource(R.string.chat_stop_choice_tool_only_footer))
        add(stringResource(R.string.chat_stop_choice_all_footer))
    }.joinToString(" ")
    val message = buildString {
        body?.let { append(it).append("\n\n") }
        append(lines.joinToString("\n"))
        if (lines.isNotEmpty()) append("\n\n")
        append(footer)
    }
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    var pending by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<(() -> Unit)?>(null) }
    fun closeThen(action: () -> Unit) {
        if (!shown) return
        pending = action
        shown = false
    }
    StopActionSheet(
        show = shown,
        title = title,
        message = message,
        onDismiss = { closeThen(onDismiss) },
        onDismissFinished = { pending?.invoke() },
        actions = buildList {
            if (canStopToolOnly) add(SheetAction(toolOnlyText, destructive = false) { closeThen(onToolOnly) })
            add(SheetAction(stopAllText, destructive = true) { closeThen(onStopAll) })
        },
    )
}

private class SheetAction(val label: String, val destructive: Boolean, val onClick: () -> Unit)

@Composable
private fun StopActionSheet(
    show: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    actions: List<SheetAction>,
) {
    com.openminis.app.ui.components.MinisDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            Text(
                message,
                style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2,
                modifier = Modifier.weight(1f, fill = false)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            )
            actions.forEach { action ->
                com.openminis.app.ui.components.MinisTextButton(
                    text = action.label,
                    onClick = action.onClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (action.destructive) {
                        top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                            textColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.error,
                        )
                    } else top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            com.openminis.app.ui.components.MinisTextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
