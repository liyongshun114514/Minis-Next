package com.openminis.app.ui.settings

import top.yukonga.miuix.kmp.theme.MiuixTheme
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.openminis.app.ui.components.MinisPageScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import com.openminis.app.ui.components.minisPageContentPadding
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
import java.io.File
import com.openminis.app.ui.components.MinisTextButton as TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.components.MinisDialog as WindowDialog
import top.yukonga.miuix.kmp.basic.TextField
import androidx.compose.ui.semantics.error
import com.openminis.app.ui.components.MinisTopAppBar as TopAppBar

/**
 * [T-workspace-instructions] Settings → Workspace Instructions.
 *
 * Edits the AGENTS.md / CLAUDE.md chain sent first in the native system
 * channel, ahead of SOUL.md and generated runtime instructions. It exposes
 * the discovered files in broad-to-specific order; more specific workspace
 * files take precedence within the chain.
 *
 * The single user-global file and the project chain are listed separately
 * because their file budgets are separate (65,536 bytes each by default).
 * The fixed application ordering header is outside those file budgets.
 */
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
    val exclusive = remember(refresh) { WorkspaceInstructionStore.exclusive(context) }
    val snapshot = remember(refresh) {
        WorkspaceInstructionStore.snapshot(context).also {
            if (it.error != null) error = it.error
        }
    }

    // [T-workspace-instructions] One user-global file, not two: see
    // WorkspaceInstructionDiscovery.globalChain.
    val globalPaths = listOf(
        WorkspaceInstructionDiscovery.DSH_HOME_LINUX + "/" + WorkspaceInstructionDiscovery.USER_GLOBAL_FILE,
    )
    val projectFiles = snapshot.files.filterNot { it.isUserGlobal }

    Scaffold(
        overlapTopBar = true,
        topBar = {
            TopAppBar(
                title = stringResource(R.string.workspace_instructions_title),
                navigationIcon = {
                    com.openminis.app.ui.components.MinisToolbarIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                expandable = false,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = minisPageContentPadding(),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.workspace_instructions_note),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = { scopeDialog = true },
                    showIndication = true,
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.workspace_scope_title),
                            style = MiuixTheme.textStyles.subtitle,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.workspace_scope_project_root) + ": " + projectRoot,
                            style = MiuixTheme.textStyles.body2,
                        )
                        Text(
                            text = stringResource(R.string.workspace_scope_working_directory) + ": " + workingDirectory,
                            style = MiuixTheme.textStyles.body2,
                        )
                        Text(
                            text = stringResource(R.string.workspace_budget, maxBytes),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }
            item {
                // [K-agents-supreme] The one switch that decides whether AGENTS.md
                // replaces SOUL.md instead of merely preceding it.
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.workspace_exclusive_title),
                                style = MiuixTheme.textStyles.subtitle,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.workspace_exclusive_note),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                        }
                        Switch(
                            checked = exclusive,
                            onCheckedChange = {
                                WorkspaceInstructionStore.setExclusive(context, it)
                                refresh++
                            },
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
                // No create row here on purpose: the page keeps ONE editable
                // AGENTS.md. A project file appears the moment it exists on
                // disk (created from the file browser or by the agent), so the
                // empty state stays a sentence instead of a second entry.
                item {
                    Text(
                        text = stringResource(R.string.workspace_project_empty),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
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
        WindowDialog(
            show = true,
            onDismissRequest = { error = null },
            title = stringResource(R.string.workspace_instructions_title),
            summary = message,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                                    onClick = { error = null },
                                    text = stringResource(R.string.common_close),
                                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                                )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.primary,
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
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        showIndication = true,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
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
                tint = MiuixTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = linuxPath,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (exists) subtitle else stringResource(R.string.workspace_file_missing),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
        }
    }
}

@Composable
private fun ScopeDialog(
    initialProjectRoot: String,
    initialWorkingDirectory: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var root by remember { mutableStateOf(initialProjectRoot) }
    var cwd by remember { mutableStateOf(initialWorkingDirectory) }
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.workspace_scope_title),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                            TextField(
                                value = root,
                                onValueChange = { root = it },
                                singleLine = true,
                                label = stringResource(R.string.workspace_scope_project_root),
                                useLabelAsPlaceholder = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            TextField(
                                value = cwd,
                                onValueChange = { cwd = it },
                                singleLine = true,
                                label = stringResource(R.string.workspace_scope_working_directory),
                                useLabelAsPlaceholder = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.workspace_scope_hint),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                        }
            com.openminis.app.ui.components.MinisDialogActions(
                dismissButton = {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onDismiss,
                        text = stringResource(R.string.common_cancel),
                        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                    )
                },
                confirmButton = {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSave(root, cwd) },
                        text = stringResource(R.string.workspace_save),
                        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                    )
                },
            )
        }
    }
}

/**
 * Editor for one instruction file. Saving creates parents; an empty body is a
 * valid file (dsh renders its heading so the model learns the instruction
 * exists and has no content).
 */
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
        overlapTopBar = true,
        topBar = {
            TopAppBar(
                title = File(linuxPath).name,
                navigationIcon = {
                    com.openminis.app.ui.components.MinisToolbarIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                        val result = WorkspaceInstructionStore.write(context, linuxPath, content)
                        result.onSuccess {
                            error = null
                            saved = true
                        }.onFailure {
                            saved = false
                            error = it.message
                        }
                    },
                        text = stringResource(R.string.workspace_save),
                        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                    )
                },
                expandable = false,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(minisPageContentPadding(padding))
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = linuxPath,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            // A top-aligned editor. Miuix TextField centres its decoration row,
            // so a full-height field painted a short AGENTS.md in the middle of
            // the screen instead of at the first line.
            com.openminis.app.ui.components.DocumentEditorField(
                value = content,
                onValueChange = { content = it },
                textStyle = MiuixTheme.textStyles.body2,
                placeholder = stringResource(R.string.workspace_editor_placeholder),
                outlined = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            if (saved) {
                Text(
                    text = stringResource(R.string.workspace_saved),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}
