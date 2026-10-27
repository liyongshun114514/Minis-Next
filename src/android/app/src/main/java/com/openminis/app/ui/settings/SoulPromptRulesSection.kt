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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.SoulPromptRule
import com.openminis.app.agent.SoulPromptRuleTarget
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.ui.res.stringResource

/**
 * The custom-rules half of the system-prompt page.
 *
 * Layout decisions, all UI-only:
 *  - one card per page section, with a compact summary row at the top
 *    ("3 rules · 2 enabled") so the list state is readable before scrolling;
 *  - rules are grouped by scope (group first, then model) because that is the
 *    order the resolver appends them in. A single flat list would imply that a
 *    model rule can be dragged above a group rule, which changes storage but
 *    not the prompt;
 *  - every row states scope, target, on/off and a body preview, so the list is
 *    useful without opening the editor;
 *  - up/down only move within the visible scope block; edit/delete are the
 *    existing immediate-persist actions.
 */
@Composable
fun SoulCustomRulesSection(
    rules: List<SoulPromptRule>,
    groups: List<SoulPromptGroupOption>,
    models: List<SoulPromptModelOption>,
    canAdd: Boolean,
    onToggle: (SoulPromptRule, Boolean) -> Unit,
    onMove: (SoulPromptRule, Int) -> Unit,
    onEdit: (SoulPromptRule) -> Unit,
    onDelete: (SoulPromptRule) -> Unit,
    onAdd: () -> Unit,
) {
    val groupEntries = remember(rules) {
        SoulPromptRuleUi.entriesFor(rules, SoulPromptRuleTarget.GROUP)
    }
    val modelEntries = remember(rules) {
        SoulPromptRuleUi.entriesFor(rules, SoulPromptRuleTarget.MODEL)
    }

    SettingsSection(
        header = stringResource(R.string.soul_custom_rules_header),
        footer = stringResource(R.string.soul_custom_rules_intro),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(
                        R.string.prompt_ui_rules_summary,
                        rules.size,
                        SoulPromptRuleUi.enabledCount(rules),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.prompt_ui_rules_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            if (rules.isEmpty()) {
                Text(
                    text = stringResource(R.string.soul_custom_rules_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            } else {
                if (groupEntries.isNotEmpty()) {
                    SoulRuleTierBlock(
                        title = stringResource(R.string.prompt_ui_tier_group, groupEntries.size),
                        entries = groupEntries,
                        groups = groups,
                        models = models,
                        onToggle = onToggle,
                        onMove = onMove,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
                if (modelEntries.isNotEmpty()) {
                    SoulRuleTierBlock(
                        title = stringResource(R.string.prompt_ui_tier_model, modelEntries.size),
                        entries = modelEntries,
                        groups = groups,
                        models = models,
                        onToggle = onToggle,
                        onMove = onMove,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }

            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                MinisButton(
                    onClick = onAdd,
                    enabled = canAdd,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.soul_custom_rules_add)) }
            }
        }
    }
}

@Composable
private fun SoulRuleTierBlock(
    title: String,
    entries: List<IndexedValue<SoulPromptRule>>,
    groups: List<SoulPromptGroupOption>,
    models: List<SoulPromptModelOption>,
    onToggle: (SoulPromptRule, Boolean) -> Unit,
    onMove: (SoulPromptRule, Int) -> Unit,
    onEdit: (SoulPromptRule) -> Unit,
    onDelete: (SoulPromptRule) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
    )
    entries.forEachIndexed { position, entry ->
        SoulRuleRow(
            position = position + 1,
            rule = entry.value,
            groups = groups,
            models = models,
            canMoveUp = position > 0,
            canMoveDown = position < entries.lastIndex,
            onToggle = onToggle,
            onMove = onMove,
            onEdit = onEdit,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun SoulRuleRow(
    position: Int,
    rule: SoulPromptRule,
    groups: List<SoulPromptGroupOption>,
    models: List<SoulPromptModelOption>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (SoulPromptRule, Boolean) -> Unit,
    onMove: (SoulPromptRule, Int) -> Unit,
    onEdit: (SoulPromptRule) -> Unit,
    onDelete: (SoulPromptRule) -> Unit,
) {
    val unnamed = stringResource(R.string.soul_custom_rule_unnamed)
    val targetRef = remember(rule, groups, models) {
        SoulPromptRuleUi.targetRef(rule, groups, models)
    }
    val targetText = when (targetRef) {
        is SoulPromptTargetRef.Found -> when (rule.target) {
            SoulPromptRuleTarget.GROUP -> stringResource(
                R.string.soul_custom_rule_group_label,
                targetRef.label,
            )
            SoulPromptRuleTarget.MODEL -> stringResource(
                R.string.soul_custom_rule_model_label,
                targetRef.label,
            )
        }
        SoulPromptTargetRef.Missing -> stringResource(R.string.soul_custom_rule_target_missing)
    }
    val preview = remember(rule.content) { SoulPromptRuleUi.preview(rule.content) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyColor = if (rule.enabled) muted else muted.copy(alpha = 0.6f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "#$position",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.width(26.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name.ifBlank { unnamed },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScopeChip(
                        text = stringResource(
                            when (rule.target) {
                                SoulPromptRuleTarget.GROUP -> R.string.soul_custom_rule_target_group
                                SoulPromptRuleTarget.MODEL -> R.string.soul_custom_rule_target_model
                            },
                        ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = targetText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (targetRef is SoulPromptTargetRef.Missing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            muted
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!rule.enabled) {
                        Spacer(Modifier.width(6.dp))
                        ScopeChip(text = stringResource(R.string.prompt_ui_rule_disabled))
                    }
                }
            }
            SettingsSwitch(
                checked = rule.enabled,
                onCheckedChange = { onToggle(rule, it) },
            )
        }

        Text(
            text = preview.ifEmpty { stringResource(R.string.prompt_ui_rule_no_content) },
            style = MaterialTheme.typography.bodySmall,
            color = bodyColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 40.dp, end = 14.dp, top = 6.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 34.dp, end = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(
                onClick = { onMove(rule, -1) },
                enabled = canMoveUp,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.prompt_ui_rule_move_up),
                    tint = if (canMoveUp) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        muted.copy(alpha = 0.38f)
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = { onMove(rule, 1) },
                enabled = canMoveDown,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.prompt_ui_rule_move_down),
                    tint = if (canMoveDown) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        muted.copy(alpha = 0.38f)
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            MinisTextButton(onClick = { onEdit(rule) }) {
                Text(stringResource(R.string.soul_custom_rule_edit))
            }
            MinisTextButton(onClick = { onDelete(rule) }) {
                Text(
                    text = stringResource(R.string.soul_custom_rule_delete),
                    color = MaterialTheme.colorScheme.error,
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

/** Small scope / status pill used inside a rule row. */
@Composable
private fun ScopeChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
