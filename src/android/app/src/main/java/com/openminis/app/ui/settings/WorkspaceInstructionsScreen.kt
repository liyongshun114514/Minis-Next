package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.workspace.WorkspaceInstructionDiscovery
import com.openminis.app.agent.workspace.WorkspaceInstructionStore
import com.openminis.app.ui.components.MinisTextButton
import java.io.File

/**
 * [T-workspace-instructions] Settings → Workspace Instructions.
 *
 * Edits the AGENTS.md / CLAUDE.md chain the agent reads as workspace guidance
 * (dsh semantics, ported in `agent/workspace`). The page is an editor, not a
 * prompt field: it exposes the same files dsh discovers on disk, in the same
 * broad-to-specific order, so what the user sees here is exactly the chain the
 * next request carries.
 *
 * The two user-global files and the project chain are listed separately because
 * they are budgeted separately (65,536 bytes each by default) and because the
 * precedence rule the model is told — more specific wins, none override system,
 * developer, or direct user instructions — depends on that separation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceInstructionsScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var scopeDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val projectRoot = remember(refresh) { WorkspaceInstructionStore.projectRoot(context) }
    val workingDirectory = remember(refresh) { WorkspaceInstructionStore.workingDirectory(context) }
    val maxBytes = remember(refresh) { WorkspaceInstructionStore.maxBytes(context) }
    val snapshot = remember(refresh) {
        WorkspaceInstructionStore.snapshot(context).also {
            if (it.error != null) error = it.error
        }
    }

    val globalPaths = listOf(
        WorkspaceInstructionDiscovery.DSH_HOME_LINUX + "/" + WorkspaceInstructionDiscovery.USER_GLOBAL_FILE,
        WorkspaceInstructionDiscovery.AGENTS_HOME_LINUX + "/" + WorkspaceInstructionDiscovery.USER_GLOBAL_FILE,
    )
    val projectFiles = snapshot.files.filterNot { it.isUserGlobal }
    val projectRootFile = "$projectRoot/${WorkspaceInstructionDiscovery.USER_GLOBAL_FILE}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workspace_instructions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.workspace_instructions_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickableRow { scopeDialog = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.workspace_scope_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.workspace_scope_project_root) + ": " + projectRoot,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.workspace_scope_working_directory) + ": " + workingDirectory,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.workspace_budget, maxBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SectionHeader(stringResource(R.string.workspace_section_global))
            }
            items(globalPaths) { path ->
                InstructionRow(
                    linuxPath = path,
                    subtitle = stringResource(R.string.workspace_global_row_subtitle),
                    onClick = { onEdit(path) },
                )
            }
            item {
                SectionHeader(stringResource(R.string.workspace_section_project))
            }
            if (projectFiles.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.workspace_project_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                item {
                    InstructionRow(
                        linuxPath = projectRootFile,
                        subtitle = stringResource(R.string.workspace_create_project_file),
                        createHint = true,
                        onClick = { onEdit(projectRootFile) },
                    )
                }
            } else {
                items(projectFiles) { file ->
                    InstructionRow(
                        linuxPath = file.displayPath,
                        subtitle = stringResource(R.string.workspace_project_row_subtitle, file.content.length),
                        existsPath = file.linuxPath,
                        onClick = { onEdit(file.linuxPath) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (scopeDialog) {
        ScopeDialog(
            initialProjectRoot = projectRoot,
            initialWorkingDirectory = workingDirectory,
            onDismiss = { scopeDialog = false },
            onSave = { root, cwd ->
                WorkspaceInstructionStore.setScope(context, root, cwd)
                scopeDialog = false
                refresh += 1
            },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = {
                MinisTextButton(onClick = { error = null }) {
                    Text(stringResource(R.string.common_close))
                }
            },
            title = { Text(stringResource(R.string.workspace_instructions_title)) },
            text = { Text(message) },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/**
 * One instruction row. [linuxPath] is the model-facing / editable path;
 * [absoluteLinuxPath] is accepted for callers that have both, but the editor
 * only ever needs the display path because the store resolves it back.
 */
@Composable
private fun InstructionRow(
    linuxPath: String,
    subtitle: String,
    createHint: Boolean = false,
    existsPath: String = linuxPath,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val exists = remember(existsPath) {
        WorkspaceInstructionStore.resolveExisting(context, existsPath) != null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickableRow(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (createHint && !exists) Icons.Filled.Add else Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = linuxPath,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (exists) subtitle else stringResource(R.string.workspace_file_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

@Composable
private fun ScopeDialog(
    initialProjectRoot: String,
    initialWorkingDirectory: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var root by remember { mutableStateOf(initialProjectRoot) }
    var cwd by remember { mutableStateOf(initialWorkingDirectory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            MinisTextButton(onClick = { onSave(root, cwd) }) {
                Text(stringResource(R.string.workspace_save))
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        title = { Text(stringResource(R.string.workspace_scope_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = root,
                    onValueChange = { root = it },
                    label = { Text(stringResource(R.string.workspace_scope_project_root)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text(stringResource(R.string.workspace_scope_working_directory)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.workspace_scope_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * Editor for one instruction file. Saving creates parents; an empty body is a
 * valid file (dsh renders its heading so the model learns the instruction
 * exists and has no content).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceInstructionEditorScreen(
    linuxPath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var content by remember(linuxPath) {
        mutableStateOf(
            WorkspaceInstructionStore.resolveExisting(context, linuxPath)
                ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("") }
                ?: "",
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = File(linuxPath).name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    MinisTextButton(onClick = {
                        val result = WorkspaceInstructionStore.write(context, linuxPath, content)
                        result.onSuccess {
                            error = null
                            saved = true
                        }.onFailure {
                            saved = false
                            error = it.message
                        }
                    }) {
                        Text(stringResource(R.string.workspace_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = linuxPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text(stringResource(R.string.workspace_editor_placeholder)) },
            )
            if (saved) {
                Text(
                    text = stringResource(R.string.workspace_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}
