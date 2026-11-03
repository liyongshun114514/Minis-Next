package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.R
import com.openminis.app.agent.prompts.PromptCatalogScope
import com.openminis.app.agent.prompts.PromptEntry
import com.openminis.app.agent.prompts.PromptOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [K-unified-prompt-workspace] Settings → System Prompt, hub.
 *
 * One page owns every prompt the app sends:
 *
 *  - identity + the global SOUL body (the existing SoulSettingsScreen, one
 *    level down at [onIdentityClick]);
 *  - workspace instructions (the existing WorkspaceInstructionsScreen, still
 *    reachable directly through Routes.WORKSPACE_INSTRUCTIONS for old deep
 *    links);
 *  - the GLOBAL built-in prompts from the catalog asset
 *    ([PromptCatalogScope]), grouped by the catalog's own `group` field and
 *    shown as title + source. Per-tool schema text, scenario prompts and
 *    runtime reminders are deliberately not listed here: they are owned by
 *    the feature that sends them, and putting them on this page implied they
 *    were part of the global system prompt.
 *
 * The page is a directory, not an editor: a group never inlines a text field
 * for each of its entries. Tapping a row navigates to
 * [BuiltinPromptEditorScreen] with the entry id, which is the only place the
 * full template is edited.
 *
 * Prompt assembly is untouched. Nothing here is injected into the chat system
 * prompt by itself; each scenario still pulls its own entry through
 * [PromptOverrides] at send time.
 */
@Composable
fun PromptSettingsScreen(
    onBack: () -> Unit,
    onIdentityClick: () -> Unit,
    onWorkspaceInstructionsClick: () -> Unit,
    onEditBuiltin: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<PromptEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    // Bumped on every ON_RESUME (see the observer below). The "Edited" badges
    // are derived state read from PromptOverrides, so a recomposition is what
    // makes them honest again after the user edits an entry and comes back.
    var overrideRevision by remember { mutableStateOf(0) }
    val focusManager = LocalFocusManager.current

    // The catalog is a static asset, so it is read once per visit. Returning
    // from the editor does NOT reliably re-run this effect: the hub's
    // composition is kept in the back stack, so Navigation restores the very
    // same node and `LaunchedEffect(Unit)` stays alive. Refresh the override
    // view on RESUMED instead — the destination is resumed again when the
    // editor pops, so edited rows cannot keep a stale badge.
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching { PromptOverrides.entries() }
        }
        result
            .onSuccess {
                // [K-system-prompt-scope] Only global prompts are system-prompt
                // settings. Tool descriptions, voice/vision/title/compaction
                // prompts and runtime reminders stay editable by id but are not
                // presented here as if they were part of this prompt.
                entries = PromptCatalogScope.globalOnly(it)
                loaded = true
            }
            .onFailure { loadFailed = true }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // addObserver replays the current state, so an already-RESUMED
            // destination refreshes once on attach as well.
            if (event == Lifecycle.Event.ON_RESUME) overrideRevision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Which catalog ids currently have an applied override. Recomputed when the
    // catalog arrives and on every resume; PromptOverrides.overrideFor() is an
    // in-memory lookup, so this stays cheap for the whole catalog.
    val overriddenIds = remember(entries, overrideRevision) {
        entries.filter { PromptOverrides.overrideFor(it.id) != null }.map { it.id }.toSet()
    }

    // Filtering matches what the row actually shows (title, source) plus the
    // group header and the raw id, so "workspace" finds the group even when no
    // individual title contains it.
    val visible = remember(entries, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                entry.title.contains(q, ignoreCase = true) ||
                    entry.group.contains(q, ignoreCase = true) ||
                    entry.source.contains(q, ignoreCase = true) ||
                    entry.id.contains(q, ignoreCase = true)
            }
        }
    }
    // groupBy keeps first-appearance order, so the catalog decides the order of
    // the groups; nothing is sorted here.
    val groups = remember(visible) { visible.groupBy { it.group } }
    // Resolved once, outside the loop: the header lambda below is inline, and a
    // string lookup is better done in the composable scope than inside ifBlank.
    val groupFallback = stringResource(R.string.prompt_catalog_group_other)

    SettingsScaffold(
        title = stringResource(R.string.settings_soul),
        onBack = onBack,
    ) {
        SettingsSection(footer = stringResource(R.string.prompt_catalog_intro)) {
            SettingsRow(
                title = stringResource(R.string.prompt_catalog_row_identity),
                subtitle = stringResource(R.string.prompt_catalog_row_identity_subtitle),
                icon = Icons.Outlined.AutoAwesome,
                iconColor = Color(0xFFFF9500),
                onClick = onIdentityClick,
            )
            SettingsRow(
                title = stringResource(R.string.settings_workspace_instructions),
                subtitle = stringResource(R.string.settings_workspace_instructions_subtitle),
                icon = Icons.Outlined.Description,
                iconColor = Color(0xFF34C759),
                onClick = onWorkspaceInstructionsClick,
                showDivider = false,
            )
        }

        // Search sits above the catalog so the list below can stay long without
        // burying the way to narrow it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.prompt_catalog_search_hint)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.prompt_catalog_search_clear),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
        }

        when {
            loadFailed -> SettingsSection {
                CatalogNote(stringResource(R.string.prompt_catalog_load_error))
            }

            // A failed read is reported above; an in-flight one needs no spinner
            // because the catalog is a local asset read.
            !loaded -> Unit

            entries.isEmpty() -> SettingsSection {
                CatalogNote(stringResource(R.string.prompt_catalog_empty))
            }

            groups.isEmpty() -> SettingsSection {
                CatalogNote(stringResource(R.string.prompt_catalog_no_match, query.trim()))
            }

            else -> groups.forEach { (group, groupEntries) ->
                SettingsSection(
                    header = group.ifBlank { groupFallback },
                ) {
                    groupEntries.forEachIndexed { index, entry ->
                        // Read from the resume-refreshed set rather than
                        // calling overrideFor() inline: an override is a
                        // durable fact, and the read has to be invalidated
                        // when the destination resumes, not only when this
                        // composition is first built.
                        val overridden = entry.id in overriddenIds
                        SettingsRow(
                            title = entry.title.ifBlank { entry.id },
                            subtitle = entry.source.takeIf { it.isNotBlank() },
                            icon = Icons.Outlined.Description,
                            iconColor = MaterialTheme.colorScheme.primary,
                            onClick = { onEditBuiltin(entry.id) },
                            showDivider = index != groupEntries.lastIndex,
                            trailing = {
                                if (overridden) {
                                    Text(
                                        text = stringResource(R.string.prompt_catalog_badge_edited),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A quiet paragraph inside a settings card — empty states and load failures. */
@Composable
private fun CatalogNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}