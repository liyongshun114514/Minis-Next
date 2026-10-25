package com.openminis.app.ui.sandbox

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.SettingsRowDivider
import com.openminis.app.ui.components.SettingsSection

@OptIn(ExperimentalMaterial3Api::class)
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
    val groupedBg = MaterialTheme.colorScheme.surfaceContainerLowest
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rootfs_management_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = groupedBg),
            )
        },
        containerColor = groupedBg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(groupedBg)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // --- Status section ---
            SettingsSection(title = stringResource(R.string.rootfs_status_section)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.rootfs_installed_label)) },
                    trailingContent = {
                        Icon(
                            if (state.isInstalled) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = if (state.isInstalled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                        )
                    },
                    colors = transparentListItemColors(),
                )
                if (state.isInstalled) {
                    SettingsRowDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_size_label)) },
                        trailingContent = {
                            if (state.rootfsSize > 0) {
                                Text(
                                    Formatter.formatFileSize(context, state.rootfsSize),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        },
                        colors = transparentListItemColors(),
                    )
                    SettingsRowDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_path_label)) },
                        supportingContent = {
                            Text(
                                state.rootfsPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        },
                        colors = transparentListItemColors(),
                    )
                }
            }

            if (state.isInstalled) {
                // --- Browse section ---
                SettingsSection(title = stringResource(R.string.rootfs_browse_section)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_browse_files_label)) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableListItem(onClick = onBrowseFiles),
                        colors = transparentListItemColors(),
                    )
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
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_install_label)) },
                        leadingContent = {
                            Icon(Icons.Filled.Download, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableListItem(
                                enabled = !state.isProcessing,
                                onClick = { viewModel.install(context) },
                            ),
                        colors = transparentListItemColors(),
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_reset_label)) },
                        supportingContent = { Text(stringResource(R.string.rootfs_reset_description)) },
                        leadingContent = {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableListItem(
                                enabled = !state.isProcessing,
                                onClick = { showResetDialog = true },
                            ),
                        colors = transparentListItemColors(),
                    )
                    SettingsRowDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_reset_backup_label)) },
                        supportingContent = { Text(stringResource(R.string.rootfs_reset_backup_description)) },
                        leadingContent = {
                            Icon(Icons.Filled.Archive, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableListItem(
                                enabled = !state.isProcessing,
                                onClick = { showResetBackupDialog = true },
                            ),
                        colors = transparentListItemColors(),
                    )
                }

                if (state.hasBackup) {
                    SettingsRowDivider()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.rootfs_restore_label)) },
                        supportingContent = { Text(stringResource(R.string.rootfs_restore_description)) },
                        leadingContent = {
                            Icon(Icons.Filled.Restore, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableListItem(
                                enabled = !state.isProcessing && state.isInstalled,
                                onClick = { viewModel.restoreBackup(context) },
                            ),
                        colors = transparentListItemColors(),
                    )
                }
            }

            // --- Progress / Result ---
            if (state.isProcessing) {
                SettingsSection(title = stringResource(R.string.rootfs_progress_section)) {
                    ListItem(
                        headlineContent = { Text(state.statusMessage) },
                        supportingContent = state.installProgress?.let { p ->
                            {
                                LinearProgressIndicator(
                                    progress = { p },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                        leadingContent = {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        },
                        colors = transparentListItemColors(),
                    )
                }
            }

            state.resultMessage?.let { msg ->
                SettingsSection(title = stringResource(R.string.rootfs_result_section)) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.lastOperationSuccess)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // --- Info section ---
            SettingsSection(title = stringResource(R.string.rootfs_info_section)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.rootfs_about_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The rootfs contains the Alpine Linux filesystem used by " +
                            "the sandbox. Resetting will delete all data and restore to " +
                            "factory state.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Reset: Delete everything\n" +
                            "Backup: Save /root directory\n" +
                            "Restore: Recover saved data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // --- Dialogs ---
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.rootfs_reset_confirm_title)) },
            text = { Text(stringResource(R.string.rootfs_reset_confirm_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showResetDialog = false
                    viewModel.resetRootfs(context, keepUserData = false)
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showResetBackupDialog) {
        AlertDialog(
            onDismissRequest = { showResetBackupDialog = false },
            title = { Text(stringResource(R.string.rootfs_reset_backup_confirm_title)) },
            text = {
                Text(stringResource(R.string.rootfs_reset_backup_confirm_message))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    showResetBackupDialog = false
                    viewModel.resetRootfs(context, keepUserData = true)
                }) {
                    Text(stringResource(R.string.rootfs_reset_backup_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showResetBackupDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * ListItem default `containerColor` is `colorScheme.surface`. Inside a
 * SettingsSection card we already supply `surface` as the section's
 * Surface bg, so the per-row default doubles up and breaks the rounded
 * card edges where ListItem extends past the curve. Force the ListItem
 * container transparent so the section card alone owns the visible bg.
 */
@Composable
private fun transparentListItemColors() =
    ListItemDefaults.colors(containerColor = Color.Transparent)

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
    var availability by remember { mutableStateOf(com.openminis.app.sandbox.ChrootKernel.availability(context)) }
    var showRiskDialog by remember { mutableStateOf(false) }
    var pendingTasks by remember { mutableStateOf<List<com.openminis.app.sandbox.ActiveSandboxTask>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        selected = com.openminis.app.sandbox.SandboxBackendStore.selected(context)
        availability = com.openminis.app.sandbox.ChrootKernel.availability(context)
    }

    fun apply(target: com.openminis.app.sandbox.SandboxBackend, stopActive: Boolean) {
        scope.launch {
            when (val d = com.openminis.app.sandbox.SandboxSwitchController.requestSwitch(context, target, stopActive)) {
                is com.openminis.app.sandbox.BackendSwitchDecision.Allowed -> {
                    message = if (target == com.openminis.app.sandbox.SandboxBackend.CHROOT) {
                        "已切换到 chroot。新的命令使用真实 chroot。"
                    } else {
                        "已切回 PRoot。数据未删除。"
                    }
                    pendingTasks = emptyList()
                }
                is com.openminis.app.sandbox.BackendSwitchDecision.RefusedActiveTasks -> {
                    pendingTasks = d.tasks
                    message = "有 ${d.tasks.size} 个任务在运行，未切换。"
                }
                else -> {
                    message = describeRefusal(d)
                }
            }
            refresh()
        }
    }

    SettingsSection(title = "执行后端") {
        ListItem(
            headlineContent = { Text("当前后端") },
            supportingContent = { Text(if (selected == com.openminis.app.sandbox.SandboxBackend.PROOT) "PRoot（默认，无需 root）" else "Chroot（真实 chroot，需运行时 root 授权）") },
            colors = transparentListItemColors(),
        )
        SettingsRowDivider()
        ListItem(
            headlineContent = { Text("启用 Chroot 后端") },
            supportingContent = {
                Text(
                    buildString {
                        append(availability.summary())
                        availability.lastError?.let { append("\n最近错误：").append(it) }
                    },
                )
            },
            trailingContent = {
                if (selected == com.openminis.app.sandbox.SandboxBackend.CHROOT) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth().clickableListItem(
                enabled = selected != com.openminis.app.sandbox.SandboxBackend.CHROOT,
                onClick = {
                    // Two gates, in this order: the persisted risk
                    // acknowledgement, then the root bridge's own
                    // per-command confirmation inside requestSwitch().
                    if (com.openminis.app.sandbox.SandboxBackendStore.authorizedAt(context) <= 0L) {
                        showRiskDialog = true
                    } else {
                        apply(com.openminis.app.sandbox.SandboxBackend.CHROOT, stopActive = false)
                    }
                },
            ),
            colors = transparentListItemColors(),
        )
        SettingsRowDivider()
        ListItem(
            headlineContent = { Text("切回 PRoot") },
            supportingContent = { Text("停止 chroot helper，恢复 PRoot 的 Minis 工具；rootfs 与用户数据保持不变") },
            modifier = Modifier.fillMaxWidth().clickableListItem(
                enabled = selected != com.openminis.app.sandbox.SandboxBackend.PROOT,
                onClick = { apply(com.openminis.app.sandbox.SandboxBackend.PROOT, stopActive = false) },
            ),
            colors = transparentListItemColors(),
        )
        SettingsRowDivider()
        ListItem(
            headlineContent = { Text("Root Bridge 设置") },
            supportingContent = { Text("查看 / 撤销已授权的 root 通道（逐命令确认）") },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.fillMaxWidth().clickableListItem(onClick = {
                val intent = android.content.Intent().setClassName(
                    context.packageName,
                    "com.openminis.app.rootbridge.RootBridgeSettingsActivity",
                )
                try {
                    context.startActivity(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    message = "本版本尚未包含 Root Bridge 设置页"
                }
            }),
            colors = transparentListItemColors(),
        )
        message?.let { text ->
            ListItem(
                headlineContent = { Text(text, style = MaterialTheme.typography.bodySmall) },
                colors = transparentListItemColors(),
            )
        }
    }

    if (showRiskDialog) {
        AlertDialog(
            onDismissRequest = { showRiskDialog = false },
            title = { Text("启用真实 chroot？") },
            text = {
                Text(
                    "Chroot 后端会用内核 chroot(2) 共享同一个 rootfs，因此需要一次 root 授权。\n\n" +
                        "• 每个命令在私有 mount namespace 中运行，命令本身以本应用 uid 执行\n" +
                        "• 只绑定最小设备节点、procfs、sysfs(read-only) 与 /var/minis 中已允许的目录\n" +
                        "• 不修改 SELinux、不重挂载任何分区、不改启动项\n" +
                        "• root 授权仅本次应用运行有效，重启即失效；随时可切回 PRoot，数据不删除\n" +
                        "• 若 guest offload client 未打包，Android/Minis 工具会明确报不可用\n" +
                        "• 命令以本应用 uid 运行：apt/dpkg 等需要 root 的包管理会拒绝；装包请先切回 PRoot（rootfs 共用，装完再切回）",
                )
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    showRiskDialog = false
                    com.openminis.app.sandbox.SandboxSwitchController.authorizeChroot(context)
                    apply(com.openminis.app.sandbox.SandboxBackend.CHROOT, stopActive = false)
                }) { Text("我了解风险，继续") }
            },
            dismissButton = {
                MinisTextButton(onClick = { showRiskDialog = false }) { Text("取消") }
            },
        )
    }

    if (pendingTasks.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingTasks = emptyList() },
            title = { Text("有任务正在运行") },
            text = {
                Text(
                    pendingTasks.joinToString("\n") { "• ${it.label}" } +
                        "\n\n切换后端会中断这些任务。请选择先停止再切换，或取消。",
                )
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    val target = com.openminis.app.sandbox.SandboxBackendStore.selected(context).let {
                        if (it == com.openminis.app.sandbox.SandboxBackend.PROOT) {
                            com.openminis.app.sandbox.SandboxBackend.CHROOT
                        } else {
                            com.openminis.app.sandbox.SandboxBackend.PROOT
                        }
                    }
                    pendingTasks = emptyList()
                    apply(target, stopActive = true)
                }) { Text("停止并切换", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { pendingTasks = emptyList() }) { Text("取消") }
            },
        )
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
