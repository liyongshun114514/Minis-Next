package com.openminis.app.ui.sandbox

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

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

            state.legacyBackupPath?.let { backupPath ->
                SettingsSection(title = "迁移保留的旧环境") {
                    ListItem(
                        headlineContent = { Text("原客体数据已保留，未自动删除") },
                        supportingContent = { Text(backupPath, style = MaterialTheme.typography.bodySmall) },
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

    // [FEAT-261025-CHROOT] This screen is reached from Settings, i.e. OUTSIDE
    // ChatScreen's composition, and that is where OffloadPermissionDialog is
    // normally rendered. Starting chroot asks RootManager for a per-command
    // confirmation; without this, the request would block with no UI until it
    // times out. Rendering it here makes the prompt appear on this screen.
    com.openminis.app.ui.chat.OffloadPermissionDialog()

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
    var pendingTarget by remember { mutableStateOf<com.openminis.app.sandbox.SandboxBackend?>(null) }
    var switching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        selected = com.openminis.app.sandbox.SandboxBackendStore.selected(context)
        availability = com.openminis.app.sandbox.ChrootKernel.availability(context)
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
                            "已切换到 chroot。新的命令使用真实 chroot；运行时授权失效或 helper 停止后，再点一次 Chroot 即可重新授权启动。"
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
                            "Chroot 已在运行，运行时授权有效；如需重新授权，先切回 PRoot 再切回 Chroot。"
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

    // The two segments are the only entry points to a backend change, so both
    // gates live here: the persisted risk acknowledgement, then the switch
    // controller (availability, active tasks, root bridge confirmation).
    fun selectBackend(target: com.openminis.app.sandbox.SandboxBackend) {
        if (target == com.openminis.app.sandbox.SandboxBackend.CHROOT &&
            com.openminis.app.sandbox.SandboxBackendStore.authorizedAt(context) <= 0L
        ) {
            showRiskDialog = true
            return
        }
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
    val statusLine = buildString {
        if (chrootSelected) {
            append("当前使用 Chroot：每个命令在私有 mount namespace 中以本应用 uid 运行；rootfs 与用户数据同 PRoot 共用，切回不删除。")
        } else {
            append("当前使用 PRoot：默认后端，无需 root；与 Chroot 共用同一个 rootfs，切换不删除数据。")
        }
        append("\nChroot 可用性：").append(availability.summary())
        availability.lastError?.let { append("\n最近错误：").append(it) }
        if (chrootSelected && !availability.serverRunning) {
            append("\n运行时授权已失效或 helper 未运行：再点一次 Chroot 会重新授权并启动。")
        }
    }

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
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (switching) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
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
                        "• 命令以本应用 uid 运行：apt/dpkg 等需要 root 的包管理会拒绝\n" +
                        "• 默认镜像是预装完整环境包（工具链已随镜像内置），无需再装；确需维护包时切回 PRoot 维护同一个 rootfs，完成后再切回 Chroot",
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
                }) { Text("停止并切换", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { pendingTasks = emptyList(); pendingTarget = null }) { Text("取消") }
            },
        )
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
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
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
