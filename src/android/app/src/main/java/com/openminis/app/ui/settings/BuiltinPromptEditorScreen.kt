package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.prompts.PromptEntry
import com.openminis.app.agent.prompts.PromptOverrides
import com.openminis.app.agent.prompts.PromptTemplate
import com.openminis.app.ui.components.DocumentEditorField
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [K-unified-prompt-workspace] Full-text editor for ONE built-in prompt.
 *
 * The hub hands over an id; the entry (title, group, source, default) is looked
 * up in the catalog, and the text shown is the saved override if there is one,
 * otherwise the built-in default. Saving writes an override through
 * [PromptOverrides.save]; "Restore default" calls [PromptOverrides.reset],
 * which REMOVES the override — the default is never copied in as a
 * replacement, so a future catalog change to the default is picked up again.
 *
 * Saving runs off the main thread. Both Save and Restore default act on a
 * SNAPSHOT of the draft taken when the button is tapped: text typed while the
 * write is in flight is neither reported as saved (the baseline is the text
 * that reached disk) nor discarded by a concurrent reset (the field is only
 * replaced when it has not moved since the tap). A failed write leaves the
 * draft exactly as typed and reports the failure; it never silently drops the
 * edit. That covers both I/O failures and PromptOverrides' own validation: an
 * edit must keep the default template's placeholders (and their counts) and
 * may not invent new ones, because a dropped slot would silently remove live
 * context from the prompt. The refusal message is shown verbatim under
 * "Couldn't save", and the required placeholders are listed on the page before
 * the user types.
 */
@Composable
fun BuiltinPromptEditorScreen(
    promptId: String,
    onBack: () -> Unit,
) {
    // Every remembered value in this page belongs to ONE catalog entry. Keying
    // by promptId throws the whole editor away when the route's argument
    // changes, so a previous entry's title, text and baseline can never be
    // shown under a new id — not even for the frame before the catalog read
    // lands. (Navigation normally builds a fresh destination per id; this makes
    // the page correct when a host reuses the composition instead.)
    key(promptId) {
        BuiltinPromptEditorContent(promptId = promptId, onBack = onBack)
    }
}

@Composable
private fun BuiltinPromptEditorContent(
    promptId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var entry by remember { mutableStateOf<PromptEntry?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    // What disk currently holds (override or default), so "dirty" is a value
    // comparison rather than a flag that can drift.
    var baseline by remember { mutableStateOf("") }
    var overridden by remember { mutableStateOf(false) }
    // One in-flight write at a time, for both Save and Restore default.
    var saving by remember { mutableStateOf(false) }
    var saveFailure by remember { mutableStateOf<String?>(null) }
    var resetFailure by remember { mutableStateOf<String?>(null) }
    var restored by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(promptId) {
        // Reset before reading, not after: if this composition is reused for a
        // different id, the page renders the loading state rather than the
        // previous entry while the (IO) catalog read is in flight.
        entry = null
        loaded = false
        missing = false
        loadFailed = false
        draft = ""
        baseline = ""
        overridden = false
        saving = false
        saveFailure = null
        resetFailure = null
        restored = false

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val found = PromptOverrides.entries().firstOrNull { it.id == promptId }
                if (found == null) null else found to PromptOverrides.overrideFor(promptId)
            }
        }
        result
            .onSuccess { pair ->
                if (pair == null) {
                    missing = true
                } else {
                    entry = pair.first
                    overridden = pair.second != null
                    baseline = pair.second ?: pair.first.defaultTemplate
                    draft = baseline
                }
            }
            .onFailure { loadFailed = true }
        loaded = true
    }

    val isDirty = loaded && draft != baseline
    val current = entry

    // [K-unified-prompt-workspace] The author-visible placeholder contract of
    // the DEFAULT template, as token + occurrence count. Counts matter because
    // PromptOverrides.save compares MULTISETS: dropping one of two identical
    // {{slot1}} occurrences is refused even though the slot is still present.
    val slots = remember(current) {
        current?.let { builtinSlotUsage(it.defaultTemplate) }.orEmpty()
    }

    val save: () -> Unit = {
        // Snapshot the draft at TAP time and write this exact text. Reading
        // `draft` inside the coroutine would pick up whatever was typed while
        // the write was in flight, and then the success path would record that
        // never-written text as saved.
        val submitted = draft
        if (!saving && loaded && submitted != baseline) {
            saving = true
            saveFailure = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val previous = PromptOverrides.overrideFor(promptId)
                        PromptOverrides.save(promptId, submitted)
                        try {
                            com.openminis.app.agent.prompts.BuiltinSkillPrompt.apply(context, promptId, submitted)
                        } catch (failure: Exception) {
                            if (previous == null) PromptOverrides.reset(promptId)
                            else PromptOverrides.save(promptId, previous)
                            throw failure
                        }
                    }
                }
                result
                    .onSuccess {
                        // The baseline is the text that actually reached disk,
                        // never the current draft: anything typed during the
                        // write stays dirty ("Unsaved changes", Save enabled)
                        // instead of being reported as saved.
                        baseline = submitted
                        overridden = true
                        restored = false
                    }
                    .onFailure { saveFailure = it.message ?: "save failed" }
                saving = false
            }
        }
        Unit
    }

    val restoreDefault: () -> Unit = {
        // Same snapshot rule: reset() is async and the field stays editable.
        // Text typed while the reset runs is a NEW edit the user wants, so it
        // must survive; only an untouched field is replaced by the default.
        val submitted = draft
        if (!saving) {
            saving = true
            resetFailure = null
            scope.launch {
                // reset() removes the stored override; the built-in text is
                // NOT written back as a new override.
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val previous = PromptOverrides.overrideFor(promptId)
                        PromptOverrides.reset(promptId)
                        try {
                            com.openminis.app.agent.prompts.BuiltinSkillPrompt.apply(
                                context, promptId, current?.defaultTemplate.orEmpty(),
                            )
                        } catch (failure: Exception) {
                            if (previous != null) PromptOverrides.save(promptId, previous)
                            throw failure
                        }
                    }
                }
                result
                    .onSuccess {
                        val default = current?.defaultTemplate.orEmpty()
                        baseline = default
                        if (draft == submitted) {
                            draft = default
                        }
                        // Only claim "restored" when an applied override was
                        // actually removed AND nothing was typed meanwhile; on
                        // a pristine prompt reset() is a documented no-op, and
                        // a concurrent edit leaves the page dirty, not saved.
                        restored = overridden && draft == default
                        overridden = false
                    }
                    .onFailure { resetFailure = it.message ?: "reset failed" }
                saving = false
            }
        }
        Unit
    }

    // One guard for the app-bar arrow and the system back gesture, so they
    // cannot disagree about whether unsaved edits need confirming.
    val attemptBack: () -> Unit = { if (isDirty) showDiscard = true else onBack() }
    BackHandler(enabled = isDirty) { showDiscard = true }

    val title = current?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.settings_soul)

    SettingsScaffold(title = title, onBack = attemptBack) {
        when {
            loadFailed || missing -> SettingsSection {
                Text(
                    text = stringResource(
                        if (loadFailed) R.string.prompt_catalog_load_error
                        else R.string.prompt_catalog_editor_missing,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
            }

            current == null -> Unit

            else -> {
                val unknownSource = stringResource(R.string.prompt_catalog_editor_source_unknown)
                val statusText = when {
                    saving -> stringResource(R.string.system_prompt_saving)
                    !loaded -> ""
                    isDirty -> stringResource(R.string.prompt_catalog_status_unsaved)
                    restored -> stringResource(R.string.prompt_catalog_status_restored)
                    else -> stringResource(R.string.prompt_catalog_status_saved)
                }
                val slotsText = if (slots.isEmpty()) {
                    stringResource(R.string.prompt_catalog_editor_slots_none)
                } else {
                    slots.joinToString(", ") { (token, count) ->
                        if (count > 1) "$token ×$count" else token
                    }
                }

                SettingsSection(
                    header = stringResource(R.string.prompt_catalog_editor_section_template),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        DocumentEditorField(
                            value = draft,
                            onValueChange = {
                                draft = it
                                restored = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            placeholder = stringResource(R.string.prompt_catalog_editor_placeholder),
                            contentPadding = PaddingValues(16.dp),
                            outlined = true,
                            ownScroll = false,
                            minHeight = 260.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isDirty || saving) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.weight(1f),
                            )
                            MinisButton(
                                onClick = save,
                                enabled = loaded && isDirty && !saving,
                            ) { Text(stringResource(R.string.common_save)) }
                        }
                    }
                }

                SettingsSection(
                    header = stringResource(R.string.prompt_catalog_editor_section_details),
                    footer = stringResource(R.string.prompt_catalog_editor_slots_note),
                ) {
                    SettingsRow(
                        title = stringResource(R.string.prompt_catalog_editor_source),
                        subtitle = current.source.ifBlank { unknownSource },
                        showChevron = false,
                    )
                    SettingsRow(
                        title = stringResource(R.string.prompt_catalog_editor_slots),
                        subtitle = slotsText,
                        showChevron = false,
                        showDivider = false,
                    )
                }

                SettingsSection(
                    header = stringResource(R.string.prompt_catalog_editor_section_reset),
                ) {
                    SettingsRow(
                        title = stringResource(R.string.prompt_catalog_editor_reset),
                        subtitle = stringResource(
                            if (overridden) R.string.prompt_catalog_editor_reset_active
                            else R.string.prompt_catalog_editor_reset_idle,
                        ),
                        icon = Icons.Outlined.Restore,
                        // Always tappable, on purpose:
                        //  - reset() is idempotent, so a pristine prompt does
                        //    nothing;
                        //  - it also discards an uncommitted draft, which is the
                        //    only way back to the shipped text short of retyping;
                        //  - and it can still delete an override on disk that
                        //    overrideFor() reports as null because it no longer
                        //    matches the shipped template's slots. Gating on
                        //    `overridden` would strand exactly that text.
                        onClick = { showResetConfirm = true },
                        showDivider = false,
                    )
                }
            }
        }
    }

    // Unsaved-changes guard: dismissing by tapping outside keeps the edits.
    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.prompt_catalog_discard_title)) },
            text = { Text(stringResource(R.string.prompt_catalog_discard_body)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showDiscard = false
                    onBack()
                }) { Text(stringResource(R.string.prompt_catalog_discard_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDiscard = false }) {
                    Text(stringResource(R.string.prompt_catalog_discard_cancel))
                }
            },
        )
    }

    // Restore default is destructive (the override is deleted), so it asks
    // first even though it is reversible only by retyping the text.
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.prompt_catalog_editor_reset_confirm_title)) },
            text = { Text(stringResource(R.string.prompt_catalog_editor_reset_confirm_body)) },
            confirmButton = {
                MinisButton(onClick = {
                    showResetConfirm = false
                    restoreDefault()
                }) { Text(stringResource(R.string.prompt_catalog_editor_reset_confirm)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    saveFailure?.let { message ->
        AlertDialog(
            onDismissRequest = { saveFailure = null },
            title = { Text(stringResource(R.string.prompt_catalog_editor_save_error_title)) },
            text = { Text(message) },
            confirmButton = {
                MinisButton(onClick = { saveFailure = null }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }

    resetFailure?.let { message ->
        AlertDialog(
            onDismissRequest = { resetFailure = null },
            title = { Text(stringResource(R.string.prompt_catalog_editor_reset_error_title)) },
            text = { Text(message) },
            confirmButton = {
                MinisButton(onClick = { resetFailure = null }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}

/**
 * The DEFAULT template's placeholders as token + occurrence count, in display
 * order: `{{slotN}}` numerically, then every other kind alphabetically.
 *
 * Delegates to [PromptTemplate.signature] — the same parser [PromptOverrides.save]
 * validates with — instead of scanning the text here. A local `{{slot\d+}}`
 * regex silently drops the other two kinds the validator tracks: the
 * single-brace `{name}` the caller fills after rendering, and a default's own
 * `{{result}}` (scheduled-task prompts). Those are exactly the tokens the
 * editor must show, and a second scanner would drift from the validator the
 * first time the template language changes.
 *
 * Pure and side-effect free so a test can pin ordering and counts.
 */
internal fun builtinSlotUsage(template: String): List<Pair<String, Int>> {
    val signature = PromptTemplate.signature(template)
    val slots = signature.entries
        .filter { entry -> slotNumber(entry.key) != null }
        .sortedBy { entry -> slotNumber(entry.key) ?: Int.MAX_VALUE }
    val others = signature.entries
        .filter { entry -> slotNumber(entry.key) == null }
        .sortedBy { entry -> entry.key }
    return (slots + others).map { entry -> entry.key to entry.value }
}

/**
 * The `N` of a signature key written as `{{slotN}}`, or null for any other key.
 *
 * Signature keys are already tokenised, so this only CLASSIFIES them — it does
 * not re-parse the template text.
 */
private fun slotNumber(token: String): Int? {
    if (!token.startsWith("{{slot") || !token.endsWith("}}")) return null
    val digits = token.substring("{{slot".length, token.length - "}}".length)
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    return digits.toIntOrNull()
}
