package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.openminis.app.agent.CharacterCardScope

/**
 * A selectable model group. Kept separate from [SoulPromptGroupOption] because
 * the character-card screen is the only owner of this picker; the legacy rule
 * section keeps its own option types.
 */
data class CharacterCardGroupOption(val id: String, val label: String)

/**
 * A selectable chat model. Identity is the (provider instance, model id) pair,
 * never the display name: two providers can expose the same model name and a
 * card bound to one must not silently apply to the other.
 */
data class CharacterCardModelOption(
    val providerInstanceId: String,
    val modelId: String,
    val providerLabel: String,
    val label: String,
)

/**
 * The only scope control used by the page and the editor: a three-segment
 * Minis/Material 3 button row. Two top-level segments ("Global" / "Custom")
 * are page navigation; inside a custom card this row picks how the card is
 * bound (group or exact provider + model).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterCardScopeSelector(
    scope: CharacterCardScope,
    onScopeChange: (CharacterCardScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scopes = listOf(
        CharacterCardScope.GLOBAL to R.string.character_card_editor_scope_global,
        CharacterCardScope.GROUP to R.string.character_card_editor_scope_group,
        CharacterCardScope.MODEL to R.string.character_card_editor_scope_model,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        scopes.forEachIndexed { index, (value, labelRes) ->
            SegmentedButton(
                selected = scope == value,
                onClick = { onScopeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, scopes.size),
            ) {
                Text(
                    text = stringResource(labelRes),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Full-height target picker. A sheet with a single scroll surface: a search
 * field for long model lists, provider headers, then one choice row per model.
 * Selecting a row closes the sheet, so there is no nested "pick then confirm".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterCardTargetPickerSheet(
    scope: CharacterCardScope,
    groups: List<CharacterCardGroupOption>,
    models: List<CharacterCardModelOption>,
    selectedGroupId: String?,
    selectedProviderInstanceId: String?,
    selectedModelId: String?,
    onPickGroup: (String) -> Unit,
    onPickModel: (CharacterCardModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(
                    if (scope == CharacterCardScope.GROUP) {
                        R.string.character_card_editor_pick_group
                    } else {
                        R.string.character_card_editor_pick_model
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            if (scope == CharacterCardScope.GROUP) {
                if (groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.character_card_editor_no_groups),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(groups, key = { it.id }) { group ->
                            SettingsChoiceRow(
                                title = group.label,
                                selected = group.id == selectedGroupId,
                                onSelect = { onPickGroup(group.id) },
                            )
                        }
                    }
                }
            } else {
                var query by remember { mutableStateOf("") }
                if (models.isEmpty()) {
                    Text(
                        text = stringResource(R.string.character_card_editor_no_models),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.character_card_editor_search_models)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val filtered = remember(query, models) {
                        val q = query.trim()
                        if (q.isEmpty()) models
                        else models.filter {
                            it.label.contains(q, ignoreCase = true) ||
                                it.providerLabel.contains(q, ignoreCase = true)
                        }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        filtered.groupBy { it.providerLabel }.forEach { (provider, providerModels) ->
                            item(key = "provider:$provider") {
                                Text(
                                    text = provider,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = 16.dp,
                                        end = 16.dp,
                                        top = 12.dp,
                                        bottom = 4.dp,
                                    ),
                                )
                            }
                            items(
                                items = providerModels,
                                key = { it.providerInstanceId + "\u0000" + it.modelId },
                            ) { model ->
                                SettingsChoiceRow(
                                    title = model.label,
                                    selected = model.providerInstanceId == selectedProviderInstanceId &&
                                        model.modelId == selectedModelId,
                                    onSelect = { onPickModel(model) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
