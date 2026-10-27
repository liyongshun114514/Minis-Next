package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.SoulPromptRule
import com.openminis.app.agent.SoulPromptRuleTarget
import com.openminis.app.ui.components.MinisOutlinedButton
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
 * Deliberately a Dialog, not a nested sheet: the editor is opened from inside
 * the Settings page's scroll container, and a sheet hosted by a section can
 * dismiss the whole settings stack (the same navigation trap the thinking-rule
 * editor avoids).
 *
 * The only target choices are Group and Model. There is no "all" / "global"
 * option, so an unfinished rule cannot accidentally apply everywhere: Save is
 * disabled until a concrete target is selected.
 */
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
    var groupMenuOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var showValidation by remember { mutableStateOf(false) }

    val selectedGroupExists = groups.any { it.id == groupId }
    val selectedModelExists = selectedModel != null && models.any {
        it.providerInstanceId == selectedModel?.providerInstanceId && it.modelId == selectedModel?.modelId
    }
    val selectedGroupLabel = groups.firstOrNull { it.id == groupId }?.label
    val hasTarget = when (target) {
        SoulPromptRuleTarget.GROUP -> selectedGroupExists
        SoulPromptRuleTarget.MODEL -> selectedModelExists
    }
    val targetMissing = when (target) {
        SoulPromptRuleTarget.GROUP -> !groupId.isNullOrBlank() && !selectedGroupExists
        SoulPromptRuleTarget.MODEL -> existing != null && selectedModel == null
    }
    val canSave = hasTarget && content.isNotBlank()

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
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.soul_custom_rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.soul_custom_rule_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    SettingsSwitch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(Modifier.height(10.dp))

                TabRow(selectedTabIndex = if (target == SoulPromptRuleTarget.GROUP) 0 else 1) {
                    Tab(
                        selected = target == SoulPromptRuleTarget.GROUP,
                        onClick = { target = SoulPromptRuleTarget.GROUP },
                    ) {
                        Text(
                            stringResource(R.string.soul_custom_rule_target_group),
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    Tab(
                        selected = target == SoulPromptRuleTarget.MODEL,
                        onClick = { target = SoulPromptRuleTarget.MODEL },
                    ) {
                        Text(
                            stringResource(R.string.soul_custom_rule_target_model),
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                when (target) {
                    SoulPromptRuleTarget.GROUP -> {
                        if (groups.isEmpty()) {
                            Text(
                                stringResource(R.string.soul_custom_rule_no_groups),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (targetMissing) {
                                Text(
                                    stringResource(R.string.soul_custom_rule_target_missing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            val current = groups.firstOrNull { it.id == groupId }
                            Box {
                                MinisOutlinedButton(
                                    onClick = { groupMenuOpen = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        current?.label
                                            ?: if (groupId.isNullOrBlank()) {
                                                stringResource(R.string.soul_custom_rule_choose_group)
                                            } else {
                                                stringResource(R.string.soul_custom_rule_target_missing)
                                            },
                                    )
                                }
                                DropdownMenu(
                                    expanded = groupMenuOpen,
                                    onDismissRequest = { groupMenuOpen = false },
                                ) {
                                    groups.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                groupId = option.id
                                                groupMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SoulPromptRuleTarget.MODEL -> {
                        if (models.isEmpty()) {
                            Text(
                                stringResource(R.string.soul_custom_rule_no_models),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (targetMissing) {
                                Text(
                                    stringResource(R.string.soul_custom_rule_target_missing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Box {
                                MinisOutlinedButton(
                                    onClick = { modelMenuOpen = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        selectedModel?.label
                                            ?: if (existing == null) {
                                                stringResource(R.string.soul_custom_rule_choose_model)
                                            } else {
                                                stringResource(R.string.soul_custom_rule_target_missing)
                                            },
                                    )
                                }
                                DropdownMenu(
                                    expanded = modelMenuOpen,
                                    onDismissRequest = { modelMenuOpen = false },
                                ) {
                                    models.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                selectedModel = option
                                                modelMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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
}
