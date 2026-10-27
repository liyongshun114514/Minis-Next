package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.SoulPromptRule
import com.openminis.app.agent.SoulPromptRuleTarget
import com.openminis.app.ui.components.MinisTextButton

/** A selectable group in the rule editor. */
data class SoulPromptGroupOption(val id: String, val label: String)

/**
 * A selectable model. Identity is the provider instance + model id pair, not
 * the display name: two providers can expose the same model name and their
 * rules must never be shared.
 */
data class SoulPromptModelOption(
    val providerInstanceId: String,
    val modelId: String,
    val label: String,
)

/**
 * [T-soul-scoped-prompt] Add / edit one custom rule.
 *
 * The editor is still a Dialog (it is opened from inside the settings scroll
 * container, and a sheet hosted by a section can dismiss the whole settings
 * stack). What changed is only the shape of the form:
 *
 *  - scope is a two-segment control, not a tab row, so it reads as a field of
 *    the rule rather than as page navigation;
 *  - the target is a single tappable row that opens one full-height picker,
 *    replacing the old "segmented tab + dropdown menu embedded in a scrolling
 *    dialog". Picking a target is a separate, focused step;
 *  - the field order is scope → target → name → prompt → enabled hint, with no
 *    nested scroll areas other than the form itself.
 *
 * Stored identity is unchanged: a GROUP rule keeps `groupId`; a MODEL rule
 * keeps (`providerInstanceId`, `modelId`). A deleted target is kept verbatim
 * and shown as missing until the user picks a replacement — Save stays
 * disabled for it, so it can never silently become global.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoulPromptRuleEditorDialog(
    existing: SoulPromptRule?,
    groups: List<SoulPromptGroupOption>,
    models: List<SoulPromptModelOption>,
    onDismiss: () -> Unit,
    onSave: (SoulPromptRule) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var target by remember {
        mutableStateOf(
            existing?.target ?: if (groups.isNotEmpty()) {
                SoulPromptRuleTarget.GROUP
            } else {
                SoulPromptRuleTarget.MODEL
            },
        )
    }
    var groupId by remember {
        mutableStateOf(
            if (existing == null) {
                groups.firstOrNull()?.id
            } else {
                // Preserve the stored id even when its group was deleted. The
                // id is shown as "no longer exists" and must be re-picked
                // explicitly; silently selecting the first group would move
                // the user's instructions to an unrelated target.
                existing.groupId
            },
        )
    }
    var selectedModel by remember {
        mutableStateOf(
            if (existing == null) {
                models.firstOrNull()
            } else {
                // Same rule for models: an entry that no longer exists stays
                // null (shown as missing) rather than snapping to the first
                // model in the list.
                models.firstOrNull {
                    it.providerInstanceId == existing.providerInstanceId &&
                        it.modelId == existing.modelId
                }
            },
        )
    }
    var content by remember { mutableStateOf(existing?.content.orEmpty()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var showValidation by remember { mutableStateOf(false) }

    val selectedGroup = groups.firstOrNull { it.id == groupId }
    val selectedModelExists = selectedModel != null && models.any {
        it.providerInstanceId == selectedModel?.providerInstanceId &&
            it.modelId == selectedModel?.modelId
    }
    val hasTarget = when (target) {
        SoulPromptRuleTarget.GROUP -> selectedGroup != null
        SoulPromptRuleTarget.MODEL -> selectedModelExists
    }
    val targetMissing = when (target) {
        SoulPromptRuleTarget.GROUP -> !groupId.isNullOrBlank() && selectedGroup == null
        SoulPromptRuleTarget.MODEL -> existing != null && selectedModel == null
    }
    val canSave = hasTarget && content.isNotBlank()
    val hasOptions = if (target == SoulPromptRuleTarget.GROUP) {
        groups.isNotEmpty()
    } else {
        models.isNotEmpty()
    }

    val targetText = when (target) {
        SoulPromptRuleTarget.GROUP -> selectedGroup?.label
            ?: if (groupId.isNullOrBlank()) {
                stringResource(R.string.soul_custom_rule_choose_group)
            } else {
                stringResource(R.string.soul_custom_rule_target_missing)
            }
        SoulPromptRuleTarget.MODEL -> selectedModel?.label
            ?: if (existing == null) {
                stringResource(R.string.soul_custom_rule_choose_model)
            } else {
                stringResource(R.string.soul_custom_rule_target_missing)
            }
    }

    val save: () -> Unit = {
        if (!canSave) {
            showValidation = true
        } else {
            val base = existing ?: SoulPromptRule()
            onSave(
                base.copy(
                    name = name.trim(),
                    enabled = enabled,
                    target = target,
                    groupId = if (target == SoulPromptRuleTarget.GROUP) groupId else null,
                    providerInstanceId = if (target == SoulPromptRuleTarget.MODEL) {
                        selectedModel?.providerInstanceId
                    } else {
                        null
                    },
                    modelId = if (target == SoulPromptRuleTarget.MODEL) selectedModel?.modelId else null,
                    content = content,
                ),
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.soul_custom_rule_add_title
                    else R.string.soul_custom_rule_edit_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // On/off first: it is the one property that changes behaviour
                // without opening any picker.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.soul_custom_rule_enabled),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.prompt_ui_editor_enabled_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SettingsSwitch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.prompt_ui_editor_applies_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val scopes = listOf(
                        SoulPromptRuleTarget.GROUP to R.string.soul_custom_rule_target_group,
                        SoulPromptRuleTarget.MODEL to R.string.soul_custom_rule_target_model,
                    )
                    scopes.forEachIndexed { index, (scope, labelRes) ->
                        SegmentedButton(
                            selected = target == scope,
                            onClick = { target = scope },
                            shape = SegmentedButtonDefaults.itemShape(index, scopes.size),
                        ) {
                            Text(stringResource(labelRes), fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.prompt_ui_editor_target_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.prompt_ui_editor_target),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                if (!hasOptions) {
                    Text(
                        text = stringResource(
                            if (target == SoulPromptRuleTarget.GROUP) {
                                R.string.soul_custom_rule_no_groups
                            } else {
                                R.string.soul_custom_rule_no_models
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                            .clickable { pickerOpen = true }
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.prompt_ui_editor_change_target),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (targetMissing) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.soul_custom_rule_target_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.soul_custom_rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.soul_custom_rule_content)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                )

                if (showValidation) {
                    val message = when {
                        !hasTarget -> stringResource(R.string.soul_custom_rule_target_required)
                        content.isBlank() -> stringResource(R.string.soul_custom_rule_content_required)
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
            }
        },
        confirmButton = {
            MinisTextButton(onClick = save) {
                Text(stringResource(R.string.soul_save))
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.soul_cancel))
            }
        },
    )

    // A separate picker window instead of a DropdownMenu anchored inside the
    // scrolling form: long model lists get the full dialog height, and the
    // editor's scroll position is untouched by opening/closing the picker.
    if (pickerOpen) {
        SoulPromptTargetPickerDialog(
            target = target,
            groups = groups,
            models = models,
            selectedGroupId = groupId,
            selectedModel = selectedModel,
            onDismiss = { pickerOpen = false },
            onPickGroup = {
                groupId = it
                pickerOpen = false
            },
            onPickModel = {
                selectedModel = it
                pickerOpen = false
            },
        )
    }
}

/**
 * One focused target step: a radio list of every group or every model.
 *
 * The list scrolls independently, so a provider with dozens of models is still
 * usable, and the selected item is a radio instead of a highlighted menu row —
 * the current target stays visible after the list is scrolled past it.
 */
@Composable
private fun SoulPromptTargetPickerDialog(
    target: SoulPromptRuleTarget,
    groups: List<SoulPromptGroupOption>,
    models: List<SoulPromptModelOption>,
    selectedGroupId: String?,
    selectedModel: SoulPromptModelOption?,
    onDismiss: () -> Unit,
    onPickGroup: (String) -> Unit,
    onPickModel: (SoulPromptModelOption) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (target == SoulPromptRuleTarget.GROUP) {
                        R.string.soul_custom_rule_choose_group
                    } else {
                        R.string.soul_custom_rule_choose_model
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    target == SoulPromptRuleTarget.GROUP && groups.isEmpty() -> Text(
                        stringResource(R.string.soul_custom_rule_no_groups),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    target == SoulPromptRuleTarget.MODEL && models.isEmpty() -> Text(
                        stringResource(R.string.soul_custom_rule_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    target == SoulPromptRuleTarget.GROUP -> groups.forEach { option ->
                        PickerOptionRow(
                            label = option.label,
                            selected = option.id == selectedGroupId,
                            onClick = { onPickGroup(option.id) },
                        )
                    }
                    else -> models.forEach { option ->
                        PickerOptionRow(
                            label = option.label,
                            selected = selectedModel != null &&
                                option.providerInstanceId == selectedModel.providerInstanceId &&
                                option.modelId == selectedModel.modelId,
                            onClick = { onPickModel(option) },
                        )
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
private fun PickerOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
