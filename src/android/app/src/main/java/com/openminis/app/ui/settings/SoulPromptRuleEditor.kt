package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.SoulPromptRule
import com.openminis.app.agent.SoulPromptRuleTarget
import com.openminis.app.ui.components.MinisTextButton

/**
 * [T-kairos-prompt-mode] Add / edit the prompt for ONE model.
 *
 * The form is deliberately three things long — model, prompt text, and the
 * Save / Cancel pair — because the page no longer has rule names, a
 * group-vs-model scope switch, or ordering controls:
 *
 *  - the target is a MODEL by construction; a new prompt is a MODEL prompt;
 *  - the model is picked in a separate searchable dialog, so a long provider
 *    account is usable and an existing prompt's model is never silently
 *    swapped for the first entry of the list (a new prompt starts with NO
 *    model selected, and a prompt whose model was deleted stays unbound);
 *  - enabled / disabled lives on the list row, not here, so editing text
 *    cannot accidentally re-enable a paused prompt.
 *
 * A rule whose model was deleted keeps its stored pair, shows as missing and
 * cannot be saved until the user explicitly picks a model again.
 *
 * [saving] is owned by the caller: the dialog stays composed (and therefore
 * keeps the draft) until the write succeeds. [saveError] is rendered inline
 * so a failed write is explained without replacing the form or the text.
 */
@Composable
fun SoulPromptRuleEditorDialog(
    existing: SoulPromptRule?,
    models: List<SoulPromptModelOption>,
    configuredPairs: Set<String> = emptySet(),
    saving: Boolean = false,
    saveError: String? = null,
    onDismiss: () -> Unit,
    onSave: (SoulPromptRule) -> Unit,
) {
    // Only an existing prompt starts bound, and only when its exact pair is
    // still in the model list. Everything else starts with no selection: an
    // automatic first entry is how text ends up attached to the wrong model.
    var selectedModel by remember(existing?.id) {
        mutableStateOf(existing?.let { SoulPromptRuleUi.modelRef(it, models) })
    }
    // Re-resolve the selection against the CURRENT model list on every
    // recomposition. A provider edit can remove a model while this dialog is
    // open; the remembered option would otherwise stay non-null and let the
    // user save a prompt for a model that no longer exists. This never rebinds
    // to another model — a missing exact pair becomes null and is shown as an
    // error until the user picks again.
    val liveSelected = selectedModel?.let { picked ->
        models.firstOrNull { option ->
            option.providerInstanceId == picked.providerInstanceId &&
                option.modelId == picked.modelId
        }
    }
    var content by remember(existing?.id) { mutableStateOf(existing?.content.orEmpty()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var showValidation by remember { mutableStateOf(false) }
    var submitLocked by remember { mutableStateOf(false) }

    // A failed write unlocks the button so the same draft can be retried.
    LaunchedEffect(saving) {
        if (!saving) submitLocked = false
    }

    val hadSelection = selectedModel != null || existing != null
    val targetMissing = hadSelection && liveSelected == null
    val canSave = liveSelected != null && content.isNotBlank()
    val storedTarget = selectedModel?.let { picked ->
        picked.modelId.ifBlank { picked.providerInstanceId }
    } ?: existing?.modelId.orEmpty().ifBlank { existing?.providerInstanceId.orEmpty() }

    val targetText = liveSelected?.displayName
        ?: if (hadSelection) {
            stringResource(R.string.kairos_prompt_editor_missing_target, storedTarget)
        } else {
            stringResource(R.string.kairos_prompt_editor_choose_model)
        }
    val changeModelDesc = stringResource(R.string.kairos_prompt_editor_model_desc, targetText)

    val dismiss: () -> Unit = {
        if (!saving) onDismiss()
    }

    val save: () -> Unit = {
        // Set synchronously before the callback, so a double tap in the same
        // frame cannot enqueue two writes. The flag is released by the parent
        // turning [saving] back off (failure) or by the dialog closing.
        if (!submitLocked && !saving) {
            val model = liveSelected
            if (!canSave || model == null) {
                showValidation = true
            } else {
                submitLocked = true
                onSave(
                    (existing ?: SoulPromptRule(target = SoulPromptRuleTarget.MODEL)).copy(
                        name = "",
                        target = SoulPromptRuleTarget.MODEL,
                        groupId = null,
                        providerInstanceId = model.providerInstanceId,
                        modelId = model.modelId,
                        content = content,
                    ),
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) {
                        R.string.kairos_prompt_editor_add_title
                    } else {
                        R.string.kairos_prompt_editor_edit_title
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Bounded so the dialog still fits a short screen; the
                    // form scrolls instead of pushing Save off-screen.
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.kairos_prompt_editor_model),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                if (models.isEmpty()) {
                    Text(
                        text = stringResource(R.string.kairos_prompt_picker_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                width = 1.dp,
                                color = if (targetMissing) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable(enabled = !saving, role = Role.Button) {
                                pickerOpen = true
                            }
                            .semantics { contentDescription = changeModelDesc }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = targetText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (targetMissing) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.kairos_prompt_editor_change_model),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // A deleted model is never replaced automatically. Saving is
                // blocked until the user picks one, and the hint names the
                // reason instead of failing silently.
                if (targetMissing) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.kairos_prompt_editor_target_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.kairos_prompt_editor_content)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                )

                if (showValidation) {
                    val message = when {
                        liveSelected == null ->
                            stringResource(R.string.kairos_prompt_editor_target_required)
                        content.isBlank() ->
                            stringResource(R.string.kairos_prompt_editor_content_required)
                        else -> null
                    }
                    if (message != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (saveError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.kairos_prompt_editor_save_error, saveError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            MinisTextButton(
                onClick = save,
                enabled = !saving && !submitLocked,
            ) {
                Text(
                    stringResource(
                        if (saving) R.string.kairos_prompt_editor_saving else R.string.soul_save,
                    ),
                )
            }
        },
        dismissButton = {
            MinisTextButton(onClick = dismiss, enabled = !saving) {
                Text(stringResource(R.string.soul_cancel))
            }
        },
    )

    // A separate window instead of a DropdownMenu anchored inside the
    // scrolling form: long model lists get a search field and their own
    // scroll area, and the editor's scroll position and draft are untouched.
    if (pickerOpen) {
        SoulPromptModelPickerDialog(
            models = models,
            selected = liveSelected,
            configuredPairs = configuredPairs,
            onDismiss = { pickerOpen = false },
            onPick = { picked ->
                selectedModel = picked
                pickerOpen = false
            },
        )
    }
}

/**
 * One focused target step: search + a lazily scrolling list of every
 * configured model.
 *
 * Search matches the display name, the provider label, the provider instance
 * id and the raw model id, so a user who knows either half can find the entry.
 * The selected row is a radio (with a `selectable` role for TalkBack) rather
 * than a highlighted menu row, each row is at least 48dp tall, and each row
 * shows the provider underneath the model name because the pair — not the
 * name — is what gets stored.
 */
@Composable
private fun SoulPromptModelPickerDialog(
    models: List<SoulPromptModelOption>,
    selected: SoulPromptModelOption?,
    configuredPairs: Set<String>,
    onDismiss: () -> Unit,
    onPick: (SoulPromptModelOption) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(query, models) {
        SoulPromptRuleUi.filterModels(models, query)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kairos_prompt_picker_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.kairos_prompt_picker_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A bounded lazy list, not a Column inside the dialog:
                        // long lists scroll on a small screen without the
                        // dialog itself growing past the window.
                        .heightIn(max = 300.dp),
                ) {
                    when {
                        models.isEmpty() -> item {
                            PickerMessage(
                                stringResource(R.string.kairos_prompt_picker_no_models),
                            )
                        }
                        filtered.isEmpty() -> item {
                            PickerMessage(
                                stringResource(R.string.kairos_prompt_picker_empty),
                            )
                        }
                        else -> items(
                            items = filtered,
                            key = { option ->
                                "${option.providerInstanceId}\u0000${option.modelId}"
                            },
                        ) { option ->
                            val key = SoulPromptRuleUi.pairKey(
                                option.providerInstanceId,
                                option.modelId,
                            )
                            PickerOptionRow(
                                label = option.displayName,
                                secondary = option.providerLabel,
                                selected = selected != null &&
                                    selected.providerInstanceId == option.providerInstanceId &&
                                    selected.modelId == option.modelId,
                                configured = key != null && key in configuredPairs,
                                onClick = { onPick(option) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.soul_cancel))
            }
        },
    )
}

@Composable
private fun PickerMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

@Composable
private fun PickerOptionRow(
    label: String,
    secondary: String,
    selected: Boolean,
    configured: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary.isNotBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (configured) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.kairos_prompt_picker_configured),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
