package com.openminis.app.ui.sandbox

import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.openminis.app.R

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.openminis.app.ui.components.MinisPageScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import com.openminis.app.ui.components.minisPageScrollPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.SettingsRowDivider
import com.openminis.app.ui.components.SettingsSection
import com.openminis.app.ui.components.MinisTextButton as TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import com.openminis.app.ui.components.MinisDialog as WindowDialog
import com.openminis.app.ui.components.MinisTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.basic.BasicComponent

@Composable
fun RootfsManagementScreen(
    onBack: () -> Unit,
    onBrowseFiles: () -> Unit,
    onMirrorCategoryClick: (MirrorCategory) -> Unit = {},
    viewModel: RootfsManagementViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showResetDialog by remember { mutableStateOf(false) }
    var showResetBackupDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refresh(context)
    }

    // T168: iOS-style inset-grouped background. surfaceContainerLowest sits
    // a hair below `surface`, so the rounded section cards (which use
    // `surface`) read as raised pads against the page bg.
    val groupedBg = MiuixTheme.colorScheme.surface
    Scaffold(
        overlapTopBar = true,
        topBar = {
            TopAppBar(
                title = stringResource(R.string.rootfs_management_title),
                navigationIcon = {
                    com.openminis.app.ui.components.MinisToolbarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                expandable = false,
                color = groupedBg,
            )
        },
        containerColor = groupedBg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(groupedBg)
                .verticalScroll(rememberScrollState()).minisPageScrollPadding(),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // --- Status section ---
            SettingsSection(title = stringResource(R.string.rootfs_status_section)) {
                BasicComponent(
                    endActions = {
                                        Icon(
                                            if (state.isInstalled) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                                            contentDescription = null,
                                            tint = if (state.isInstalled)
                                                MiuixTheme.colorScheme.primary
                                            else
                                                MiuixTheme.colorScheme.error,
                                        )
                                    },
                ) {
                    Text(stringResource(R.string.rootfs_installed_label))
                }
                if (state.isInstalled) {
                    SettingsRowDivider()
                    BasicComponent(
                        endActions = {
                                                if (state.rootfsSize > 0) {
                                                    Text(
                                                        Formatter.formatFileSize(context, state.rootfsSize),
                                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                                    )
                                                } else {
                                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                }
                                            },
                    ) {
                        Text(stringResource(R.string.rootfs_size_label))
                    }
                    SettingsRowDivider()
                    BasicComponent(
                    ) {
                        Text(stringResource(R.string.rootfs_path_label))
                        Text(
                                                        state.rootfsPath,
                                                        style = MiuixTheme.textStyles.body2,
                                                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                                        maxLines = 1,
                                                    )
                    }
                }
            }

            state.migrationError?.let { migrationError ->
                SettingsSection(title = "Rootfs 目录迁移") {
                    BasicComponent(
                        startAction = {
                                                Icon(
                                                    Icons.Filled.Cancel,
                                                    contentDescription = null,
                                                    tint = MiuixTheme.colorScheme.error,
                                                )
                                            },
                    ) {
                        Text("迁移未完成，安装与读取已停止")
                        Text(
                                                        migrationError,
                                                        style = MiuixTheme.textStyles.body2,
                                                        color = MiuixTheme.colorScheme.error,
                                                    )
                    }
                }
            }

            if (state.isInstalled) {
                // --- Browse section ---
                SettingsSection(title = stringResource(R.string.rootfs_browse_section)) {
                    BasicComponent(
                        modifier = Modifier
                                                .fillMaxWidth()
                                                .clickableListItem(onClick = onBrowseFiles),
                        startAction = {
                                                Icon(
                                                    Icons.Filled.Folder,
                                                    contentDescription = null,
                                                    tint = MiuixTheme.colorScheme.primary,
                                                )
                                            },
                        endActions = {
                                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                            },
                    ) {
                        Text(stringResource(R.string.rootfs_browse_files_label))
                    }
                }

                // --- Mirrors section ---
                SettingsSection(title = stringResource(R.string.rootfs_mirrors_section)) {
                    MirrorsSectionView(onNavigate = onMirrorCategoryClick)
                }
            }

            // --- Sandbox backend section (FEAT-261025-CHROOT) ---
            // Placement is deliberate: it sits next to the rootfs it shares and
            // is never shown as a "more features" promo — the row explains what
            // is missing when chroot is unavailable instead.
            SandboxBackendSection()

            // --- Container Network section ---
            // [T-container-network-dns] (OpenMinis#396) Shown before install too:
            // the install itself writes resolv.conf through the same setting.
            SettingsSection(title = stringResource(R.string.rootfs_network_section)) {
                ContainerNetworkSection()
            }

            // --- Actions section ---
            SettingsSection(title = stringResource(R.string.rootfs_actions_section)) {
                if (!state.isInstalled) {
                    BasicComponent(
                        modifier = Modifier
                                                .fillMaxWidth()
                                                .clickableListItem(
                                                    enabled = !state.isProcessing && state.migrationError == null,
                                                    onClick = { viewModel.install(context) },
                                                ),
                        startAction = {
                                                Icon(Icons.Filled.Download, contentDescription = null)
                                            },
                    ) {
                        Text(stringResource(R.string.rootfs_install_label))
                    }
                } else {
                    BasicComponent(
                        modifier = Modifier
                                                .fillMaxWidth()
                                                .clickableListItem(
                                                    enabled = !state.isProcessing,
                                                    onClick = { showResetDialog = true },
                                                ),
                        startAction = {
                                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                            },
                    ) {
                        Text(stringResource(R.string.rootfs_reset_label))
                        Text(stringResource(R.string.rootfs_reset_description))
                    }
                    SettingsRowDivider()
                    BasicComponent(
                        modifier = Modifier
                                                .fillMaxWidth()
                                                .clickableListItem(
                                                    enabled = !state.isProcessing,
                                                    onClick = { showResetBackupDialog = true },
                                                ),
                        startAction = {
                                                Icon(Icons.Filled.Archive, contentDescription = null)
                                            },
                    ) {
                        Text(stringResource(R.string.rootfs_reset_backup_label))
                        Text(stringResource(R.string.rootfs_reset_backup_description))
                    }
                }

                if (state.hasBackup) {
                    SettingsRowDivider()
                    BasicComponent(
                        modifier = Modifier
                                                .fillMaxWidth()
                                                .clickableListItem(
                                                    enabled = !state.isProcessing && state.isInstalled,
                                                    onClick = { viewModel.restoreBackup(context) },
                                                ),
                        startAction = {
                                                Icon(Icons.Filled.Restore, contentDescription = null)
                                            },
                    ) {
                        Text(stringResource(R.string.rootfs_restore_label))
                        Text(stringResource(R.string.rootfs_restore_description))
                    }
                }
            }

            // --- Progress / Result ---
            if (state.isProcessing) {
                SettingsSection(title = stringResource(R.string.rootfs_progress_section)) {
                    BasicComponent(
                        startAction = {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        },
                    ) {
                        Text(state.statusMessage)
                        state.installProgress?.let { progress ->
                            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            state.resultMessage?.let { msg ->
                SettingsSection(title = stringResource(R.string.rootfs_result_section)) {
                    Text(
                        text = msg,
                        style = MiuixTheme.textStyles.body1,
                        color = if (state.lastOperationSuccess)
                            MiuixTheme.colorScheme.primary
                        else
                            MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // --- Info section ---
            // Ubuntu 24.04 LTS guest: text comes from the localized resources,
            // the same DISPLAY_NAME the rest of the app uses.
            SettingsSection(title = stringResource(R.string.rootfs_info_section)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.rootfs_about_title),
                        style = MiuixTheme.textStyles.subtitle,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.rootfs_about_description),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.rootfs_about_operations),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // --- Dialogs ---
    if (showResetDialog) {
        WindowDialog(
            show = true,
            onDismissRequest = { showResetDialog = false },
            title = stringResource(R.string.rootfs_reset_confirm_title),
            summary = stringResource(R.string.rootfs_reset_confirm_message),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.openminis.app.ui.components.MinisDialogActions(
                    dismissButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showResetDialog = false },
                            text = stringResource(R.string.common_cancel),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showResetDialog = false
                                viewModel.resetRootfs(context, keepUserData = false)
                            },
                            text = "Reset",
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.error),
                        )
                    },
                )
            }
        }
    }

    // [FEAT-261025-CHROOT] Kept as a safety net: this screen lives outside
    // ChatScreen's composition, and a Root permission request raised from an
    // agent call that happens while Settings is open must still render. Chroot
    // itself no longer prompts — it uses the persistent grant or refuses.
    com.openminis.app.ui.chat.OffloadPermissionDialog()

    if (showResetBackupDialog) {
        WindowDialog(
            show = true,
            onDismissRequest = { showResetBackupDialog = false },
            title = stringResource(R.string.rootfs_reset_backup_confirm_title),
            summary = stringResource(R.string.rootfs_reset_backup_confirm_message),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.openminis.app.ui.components.MinisDialogActions(
                    dismissButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showResetBackupDialog = false },
                            text = stringResource(R.string.common_cancel),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showResetBackupDialog = false
                                viewModel.resetRootfs(context, keepUserData = true)
                            },
                            text = stringResource(R.string.rootfs_reset_backup_confirm_button),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.error),
                        )
                    },
                )
            }
        }
    }
}

/**
 * ListItem default `containerColor` is `colorScheme.surface`. Inside a
 * SettingsSection card we already supply `surface` as the section's
 * Surface bg, so the per-row default doubles up and breaks the rounded
 * card edges where ListItem extends past the curve. Force the ListItem
 * container transparent so the section card alone owns the visible bg.
 */
private fun Modifier.clickableListItem(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    return if (enabled) {
        this.then(Modifier.clickable(onClick = onClick))
    } else {
        this
    }
}

/**
 * [FEAT-261025-CHROOT] PRoot / chroot switch.
 *
 * Rules this UI enforces, in order:
 *  - PRoot is the default and is never labelled "unsafe";
 *  - enabling chroot requires an explicit risk acknowledgement, recorded with
 *    a timestamp, and then a runtime root grant through the Minis root bridge;
 *  - a switch with running commands is REFUSED and lists them — the user can
 *    confirm "stop them and switch", nothing is killed silently;
 *  - switching back to PRoot never deletes anything: the rootfs is shared.
 */
@Composable
private fun SandboxBackendSection() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var selected by remember { mutableStateOf(com.openminis.app.sandbox.SandboxBackendStore.selected(context)) }
    var pendingTasks by remember { mutableStateOf<List<com.openminis.app.sandbox.ActiveSandboxTask>>(emptyList()) }
    var pendingTarget by remember { mutableStateOf<com.openminis.app.sandbox.SandboxBackend?>(null) }
    var switching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        selected = com.openminis.app.sandbox.SandboxBackendStore.selected(context)
    }

    fun apply(target: com.openminis.app.sandbox.SandboxBackend, stopActive: Boolean) {
        if (switching) return
        switching = true
        pendingTasks = emptyList()
        pendingTarget = null
        scope.launch {
            try {
                when (val d = com.openminis.app.sandbox.SandboxSwitchController.requestSwitch(context, target, stopActive)) {
                    is com.openminis.app.sandbox.BackendSwitchDecision.Allowed -> {
                        message = if (target == com.openminis.app.sandbox.SandboxBackend.CHROOT) {
                            "已切换到 chroot。新的命令使用真实 chroot；helper 停止后再点一次 Chroot 即可重新启动。"
                        } else {
                            "已切回 PRoot。数据未删除。"
                        }
                    }
                    is com.openminis.app.sandbox.BackendSwitchDecision.RefusedActiveTasks -> {
                        pendingTasks = d.tasks
                        pendingTarget = target
                        message = "有 ${d.tasks.size} 个任务在运行，未切换。"
                    }
                    is com.openminis.app.sandbox.BackendSwitchDecision.NoChange -> {
                        message = if (target == com.openminis.app.sandbox.SandboxBackend.CHROOT) {
                            "Chroot 已在运行；如需重启 helper，先切回 PRoot 再切回 Chroot。"
                        } else {
                            "当前已是 PRoot（默认后端）。"
                        }
                    }
                    else -> {
                        message = describeRefusal(d)
                    }
                }
            } finally {
                switching = false
                refresh()
            }
        }
    }

    // The two segments are the only entry points to a backend change. There is
    // no separate chroot risk dialog: the persistent Native Root grant is the
    // single authorisation, and the switch controller refuses with a settings
    // hint when it is off (never enabling it from here).
    fun selectBackend(target: com.openminis.app.sandbox.SandboxBackend) {
        if (target == com.openminis.app.sandbox.SandboxBackend.PROOT &&
            selected == com.openminis.app.sandbox.SandboxBackend.PROOT
        ) {
            message = "当前已是 PRoot（默认后端）。"
            return
        }
        // Chroot is requested even while already selected: requestSwitch()
        // restarts a helper whose runtime grant/TTL lapsed, and answers
        // NoChange (handled above) when it is genuinely still running.
        apply(target, stopActive = false)
    }

    val chrootSelected = selected == com.openminis.app.sandbox.SandboxBackend.CHROOT

    SettingsSection(title = "执行后端") {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackendSegmentButton(
                    title = "PRoot",
                    caption = "默认 · 无需 root",
                    selected = !chrootSelected,
                    enabled = !switching,
                    onClick = { selectBackend(com.openminis.app.sandbox.SandboxBackend.PROOT) },
                )
                BackendSegmentButton(
                    title = "Chroot",
                    caption = "真实 chroot · 需 root",
                    selected = chrootSelected,
                    enabled = !switching,
                    onClick = { selectBackend(com.openminis.app.sandbox.SandboxBackend.CHROOT) },
                )
            }
            if (switching) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        message?.let { text ->
            BasicComponent(
            ) {
                Text(text, style = MiuixTheme.textStyles.body2)
            }
        }
    }

    if (pendingTasks.isNotEmpty()) {
        WindowDialog(
            show = true,
            onDismissRequest = { pendingTasks = emptyList() },
            title = "有任务正在运行",
            summary = pendingTasks.joinToString("\n") { "• ${it.label}" } +
                                "\n\n切换后端会中断这些任务。请选择先停止再切换，或取消。",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                                    onClick = {
                                    val target = pendingTarget
                                        ?: com.openminis.app.sandbox.SandboxBackendStore.selected(context).let {
                                            if (it == com.openminis.app.sandbox.SandboxBackend.PROOT) {
                                                com.openminis.app.sandbox.SandboxBackend.CHROOT
                                            } else {
                                                com.openminis.app.sandbox.SandboxBackend.PROOT
                                            }
                                        }
                                    pendingTasks = emptyList()
                                    pendingTarget = null
                                    apply(target, stopActive = true)
                                },
                                    text = "停止并切换",
                                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.error),
                                )
                TextButton(
                                    onClick = { pendingTasks = emptyList(); pendingTarget = null },
                                    text = "取消",
                                    colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                                )
                }
            }
        }
    }
}

/**
 * [FEAT-261025-CHROOT] One half of the PRoot / chroot segmented switch.
 *
 * Both halves are equal width ([weight] = 1f), PRoot on the left and chroot on
 * the right. The selected half is filled with `primary` and carries a check
 * mark, so the active backend is readable without reading the body text. This
 * is presentation only: every tap still goes through [selectBackend] and the
 * switch controller, which keeps the availability and active-task gates.
 */
@Composable
private fun RowScope.BackendSegmentButton(
    title: String,
    caption: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        },
        contentColor = if (selected) {
            MiuixTheme.colorScheme.onPrimary
        } else {
            MiuixTheme.colorScheme.onSurface
        },
        border = if (selected) null else BorderStroke(1.dp, MiuixTheme.colorScheme.dividerLine),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = caption,
                style = MiuixTheme.textStyles.footnote2,
                color = if (selected) {
                    MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                } else {
                    MiuixTheme.colorScheme.onSurfaceContainerVariant
                },
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** [FEAT-261025-CHROOT] A refusal is a sentence, never a silent no-op. */
private fun describeRefusal(decision: com.openminis.app.sandbox.BackendSwitchDecision): String =
    when (decision) {
        is com.openminis.app.sandbox.BackendSwitchDecision.RefusedNotAuthorized -> decision.reason
        is com.openminis.app.sandbox.BackendSwitchDecision.RefusedUnavailable -> decision.reason
        is com.openminis.app.sandbox.BackendSwitchDecision.RefusedActiveTasks ->
            "有 ${decision.tasks.size} 个任务在运行，未切换。"
        is com.openminis.app.sandbox.BackendSwitchDecision.Allowed -> "已切换"
        is com.openminis.app.sandbox.BackendSwitchDecision.NoChange -> "当前已是该后端"
    }
