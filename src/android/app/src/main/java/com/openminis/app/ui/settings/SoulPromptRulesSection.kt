package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.SoulPromptRule
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

/**
 * [T-kairos-prompt-mode] The CUSTOM half of the system-prompt page.
 *
 * A row is exactly what a per-model prompt is: model name, provider source,
 * on/off, a short body preview, and Edit / Delete. There is deliberately no
 * scope chip, no rule name, no group selector and no up/down arrows — a row
 * cannot be anything but one model's prompt, and the stored order is not a
 * user-facing control.
 *
 * Legacy GROUP rules are kept in storage by the store but never listed here:
 * the backend resolver does not match them in either mode, so showing them
 * would imply an effect they do not have. [legacyCount] only produces a quiet
 * note that the records are preserved.
 *
 * [busy] disables every write control while one save is in flight. The page
 * never optimistically flips a switch: a failed write must not leave the UI
 * saying "enabled" while the disk says otherwise.
 */
@Composable
fun SoulCustomRulesSection(
    rules: List<SoulPromptRule>,
    models: List<SoulPromptModelOption>,
    canAdd: Boolean,
    busy: Boolean,
    onToggle: (SoulPromptRule, Boolean) -> Unit,
    onEdit: (SoulPromptRule) -> Unit,
    onDelete: (SoulPromptRule) -> Unit,
    onAdd: () -> Unit,
) {
    val modelEntries = remember(rules) { SoulPromptRuleUi.modelEntries(rules) }
    val enabledCount = remember(rules) { SoulPromptRuleUi.enabledModelCount(rules) }
    val legacyCount = remember(rules) { SoulPromptRuleUi.legacyGroupCount(rules) }

    val intro = stringResource(R.string.kairos_prompt_custom_intro)
    val legacyNote = if (legacyCount > 0) {
        stringResource(R.string.kairos_prompt_custom_legacy, legacyCount)
    } else {
        null
    }

    SettingsSection(
        header = stringResource(R.string.kairos_prompt_custom_header),
        footer = if (legacyNote != null) "$intro\n\n$legacyNote" else intro,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(
                        R.string.kairos_prompt_custom_summary,
                        modelEntries.size,
                        enabledCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.kairos_prompt_custom_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            if (modelEntries.isEmpty()) {
                Text(
                    text = stringResource(
                        if (models.isEmpty()) {
                            R.string.kairos_prompt_custom_empty_no_models
                        } else {
                            R.string.kairos_prompt_custom_empty
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            } else {
                modelEntries.forEach { rule ->
                    SoulModelPromptRow(
                        rule = rule,
                        models = models,
                        busy = busy,
                        onToggle = onToggle,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }

            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                MinisButton(
                    onClick = onAdd,
                    enabled = canAdd && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.kairos_prompt_custom_add)) }
            }
        }
    }
}

@Composable
private fun SoulModelPromptRow(
    rule: SoulPromptRule,
    models: List<SoulPromptModelOption>,
    busy: Boolean,
    onToggle: (SoulPromptRule, Boolean) -> Unit,
    onEdit: (SoulPromptRule) -> Unit,
    onDelete: (SoulPromptRule) -> Unit,
) {
    val unknown = stringResource(R.string.kairos_prompt_custom_unknown_model)
    val targetRef = remember(rule, models) { SoulPromptRuleUi.modelRef(rule, models) }
    val title = remember(rule, models) {
        SoulPromptRuleUi.modelTitle(rule, models).ifBlank { unknown }
    }
    val source = remember(rule, models) { SoulPromptRuleUi.sourceLabel(rule, models) }
    val preview = remember(rule.content) { SoulPromptRuleUi.preview(rule.content) }
    val missing = targetRef == null

    val switchDesc = stringResource(R.string.kairos_prompt_custom_switch_desc, title)
    val editDesc = stringResource(R.string.kairos_prompt_custom_edit_desc, title)
    val deleteDesc = stringResource(R.string.kairos_prompt_custom_delete_desc, title)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (source.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.kairos_prompt_custom_source, source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (missing || !rule.enabled) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (missing) {
                            StatusChip(
                                text = stringResource(R.string.kairos_prompt_custom_missing_model),
                                error = true,
                            )
                            if (!rule.enabled) Spacer(Modifier.width(6.dp))
                        }
                        if (!rule.enabled) {
                            StatusChip(
                                text = stringResource(R.string.kairos_prompt_custom_disabled),
                                error = false,
                            )
                        }
                    }
                }
            }
            SettingsSwitch(
                checked = rule.enabled,
                onCheckedChange = { onToggle(rule, it) },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = switchDesc },
            )
        }

        Text(
            text = preview.ifEmpty { stringResource(R.string.kairos_prompt_custom_no_content) },
            style = MaterialTheme.typography.bodySmall,
            color = if (rule.enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            MinisTextButton(
                onClick = { onEdit(rule) },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = editDesc },
            ) { Text(stringResource(R.string.kairos_prompt_custom_edit)) }
            MinisTextButton(
                onClick = { onDelete(rule) },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = deleteDesc },
            ) {
                Text(
                    text = stringResource(R.string.kairos_prompt_custom_delete),
                    color = if (busy) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(start = 14.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** Small status pill used inside a model row. */
@Composable
private fun StatusChip(text: String, error: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (error) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
