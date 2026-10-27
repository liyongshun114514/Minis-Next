package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.CharacterCard
import com.openminis.app.agent.CharacterCardScope
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton

/**
 * Preview for a card parsed by `CharacterCardStore.importCard`.
 *
 * Import is deliberately two-step: the backend only produces a DRAFT, and this
 * sheet is the only place that can add it to the saved list. There is no
 * silent overwrite and no automatic enabling — the card is always saved with
 * `enabled = false`, even if the imported JSON claimed otherwise. The user
 * picks the scope here, before anything is written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterCardImportPreviewSheet(
    draft: CharacterCard,
    groups: List<CharacterCardGroupOption>,
    models: List<CharacterCardModelOption>,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (
        scope: CharacterCardScope,
        groupId: String?,
        providerInstanceId: String?,
        modelId: String?,
    ) -> Unit,
) {
    var scope by remember(draft.id) { mutableStateOf(draft.scope) }
    var groupId by remember(draft.id) { mutableStateOf(draft.groupId) }
    var providerInstanceId by remember(draft.id) { mutableStateOf(draft.providerInstanceId) }
    var modelId by remember(draft.id) { mutableStateOf(draft.modelId) }
    var showTargetPicker by remember { mutableStateOf(false) }

    val name = draft.name.ifBlank { stringResource(R.string.character_cards_import_name_fallback) }
    val missingBinding = when (scope) {
        CharacterCardScope.GLOBAL -> false
        CharacterCardScope.GROUP -> groupId.isNullOrBlank()
        CharacterCardScope.MODEL -> providerInstanceId.isNullOrBlank() || modelId.isNullOrBlank()
    }
    val targetLabel = when (scope) {
        CharacterCardScope.GLOBAL -> ""
        CharacterCardScope.GROUP -> groups.firstOrNull { it.id == groupId }?.label
            ?: groupId?.let { stringResource(R.string.character_cards_target_missing_id, it) }
            ?: stringResource(R.string.character_card_editor_bind_none)
        CharacterCardScope.MODEL -> models.firstOrNull {
            it.providerInstanceId == providerInstanceId && it.modelId == modelId
        }?.label ?: if (providerInstanceId.isNullOrBlank() || modelId.isNullOrBlank()) {
            stringResource(R.string.character_card_editor_bind_none)
        } else {
            "$providerInstanceId / $modelId"
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.character_cards_import_preview_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.character_cards_import_preview_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            Text(text = name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(
                text = stringResource(
                    R.string.character_cards_import_stat,
                    draft.description.length + draft.personality.length +
                        draft.scenario.length + draft.systemPrompt.length,
                    draft.tags.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (draft.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                PreviewBlock(
                    label = stringResource(R.string.character_card_editor_description),
                    body = draft.description,
                )
            }
            if (draft.personality.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                PreviewBlock(
                    label = stringResource(R.string.character_card_editor_personality),
                    body = draft.personality,
                )
            }
            if (draft.scenario.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                PreviewBlock(
                    label = stringResource(R.string.character_card_editor_scenario),
                    body = draft.scenario,
                )
            }
            if (draft.firstMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                PreviewBlock(
                    label = stringResource(R.string.character_card_editor_first_message),
                    body = draft.firstMessage,
                )
            }
            if (draft.creatorNotes.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                PreviewBlock(
                    label = stringResource(R.string.character_card_editor_creator_notes),
                    body = draft.creatorNotes,
                )
            }
            if (draft.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.character_card_editor_tags) + ": " +
                        draft.tags.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.character_cards_import_scope),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            CharacterCardScopeSelector(
                scope = scope,
                onScopeChange = { next ->
                    scope = next
                    if (next == CharacterCardScope.GLOBAL) {
                        groupId = null
                        providerInstanceId = null
                        modelId = null
                    }
                },
            )
            if (scope != CharacterCardScope.GLOBAL) {
                Spacer(Modifier.height(8.dp))
                MinisOutlinedButton(
                    onClick = { showTargetPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (missingBinding) {
                            stringResource(
                                if (scope == CharacterCardScope.GROUP) {
                                    R.string.character_card_editor_pick_group
                                } else {
                                    R.string.character_card_editor_pick_model
                                },
                            )
                        } else {
                            targetLabel
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            error?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(18.dp))
            MinisButton(
                onClick = {
                    onAdd(
                        scope,
                        if (scope == CharacterCardScope.GROUP) groupId else null,
                        if (scope == CharacterCardScope.MODEL) providerInstanceId else null,
                        if (scope == CharacterCardScope.MODEL) modelId else null,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving && !missingBinding && draft.name.isNotBlank(),
            ) {
                Text(
                    stringResource(
                        if (saving) R.string.character_card_editor_saving
                        else R.string.character_cards_import_add,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            MinisOutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
            ) { Text(stringResource(R.string.character_cards_cancel)) }
            if (draft.name.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.character_card_editor_name_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showTargetPicker) {
        CharacterCardTargetPickerSheet(
            scope = scope,
            groups = groups,
            models = models,
            selectedGroupId = groupId,
            selectedProviderInstanceId = providerInstanceId,
            selectedModelId = modelId,
            onPickGroup = {
                groupId = it
                providerInstanceId = null
                modelId = null
                showTargetPicker = false
            },
            onPickModel = {
                providerInstanceId = it.providerInstanceId
                modelId = it.modelId
                groupId = null
                showTargetPicker = false
            },
            onDismiss = { showTargetPicker = false },
        )
    }
}

@Composable
private fun PreviewBlock(label: String, body: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 5,
        overflow = TextOverflow.Ellipsis,
        lineHeight = 18.sp,
    )
}
