package com.openminis.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.accessibility.RestrictedSettingsManager
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.RootManager
import com.openminis.app.offload.ROOT_TRI_STATE_FOOTER
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OffloadPermissionScreen(
    onBack: () -> Unit,
    // [T-android-privileged-backend] Navigate to the multi-backend
    // (Shizuku + AXManager) screen from the privileged-backend integration row.
    onOpenPrivilegedBackend: () -> Unit = {},
) {
    val grouped = OffloadPermissionManager.toolRegistry
        .filter { it.showInSettings }
        .groupBy { it.category }
    // T336: Integrations category gets dedicated handcrafted SectionCards
    // (one per CLI) with system-layer state + action rows. Skip it from
    // the auto-rendered loop below.
    val autoCategories = grouped.entries
        .filter { it.key != OffloadPermissionManager.PermissionCategory.INTEGRATIONS }

    var showResetConfirm by remember { mutableStateOf(false) }

    val configEnabled by com.openminis.app.config.MinisConfigPermissionStore.enabled.collectAsState()

    val context = LocalContext.current

    var a11yEnabled by remember { mutableStateOf(isA11yServiceEnabled(context)) }
    // [T-android-restricted-settings] Android 13+ refuses to arm the
    // accessibility toggle for installs flagged as restricted; surfaced below
    // so the "Open accessibility settings" action doesn't dead-end.
    var a11yRestricted by remember { mutableStateOf(false) }
    var unrestricting by remember { mutableStateOf(false) }
    var unrestrictFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // [T-android-root-cli-parity] Native Root card is the same anatomy as the
    // android-shizuku-cli card above it, INCLUDING the tri-state agent gate.
    // Both are pure state reads: the stored level is read from prefs and the
    // su lookup stays in RootManager / the walkthrough page, so rendering this
    // page never executes a root command or raises a Root probe.
    val rootSnap by RootManager.snapshot.collectAsState()
    LaunchedEffect(Unit) {
        // Re-poll once a second so coming back from system Accessibility
        // settings flips the row without a manual refresh.
        while (true) {
            a11yEnabled = isA11yServiceEnabled(context) || MinisAccessibilityService.getInstance() != null
            // Re-probed each tick so the section disappears by itself once the
            // user allows restricted settings and returns.
            a11yRestricted = !a11yEnabled && RestrictedSettingsManager.isRestricted(context)
            // The Root level may have been changed on the walkthrough page, or
            // promoted by detection; back-navigation does not recreate us.
            // refresh() reads prefs only and never runs su.
            RootManager.refresh()
            delay(1000)
        }
    }
    val shizukuSnap by ShizukuManager.snapshot.collectAsState()

    SettingsScaffold(
        title = stringResource(R.string.perm_title),
        onBack = onBack,
        actions = {
            MinisTextButton(onClick = { showResetConfirm = true }) {
                Text(stringResource(R.string.perm_reset_all))
            }
        },
    ) {
        // T-config: master switch for the minis-config CLI surface.
        SettingsSection(
            header = stringResource(R.string.perm_section_config_tool),
            footer = stringResource(R.string.perm_minis_config_desc),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.perm_allow_minis_config),
                checked = configEnabled,
                onCheckedChange = {
                    com.openminis.app.config.MinisConfigPermissionStore.setEnabled(it)
                },
                showDivider = false,
            )
        }

        autoCategories.forEach { (category, tools) ->
            SettingsSection(header = stringResource(categoryHeaderRes(category))) {
                tools.forEachIndexed { idx, tool ->
                    PermissionRow(
                        tool = tool,
                        showDivider = idx < tools.size - 1,
                    )
                }
            }
        }

        // T336 / T345-2: dedicated SectionCard per integration CLI, rendered
        // after the Privacy/Media/System auto-categories so the information
        // hierarchy reads: configuration → privacy → system integrations.
        // Each card shows the CLI description, the tri-state agent gate, the
        // system-layer status, and (when system-layer is not satisfied) a
        // deeplink back to the OS settings page that fixes it.
        IntegrationSection(
            iconVector = Icons.Outlined.Accessibility,
            iconTint = Color(0xFF34C759),
            sectionHeaderRes = R.string.perm_section_a11y,
            sectionFooterRes = R.string.perm_a11y_section_footer,
            toolName = "a11y_cli",
            descriptionRes = R.string.perm_a11y_cli_description,
            systemReady = a11yEnabled,
            systemStatusTitleRes =
                if (a11yEnabled) R.string.perm_a11y_system_ready
                else R.string.perm_a11y_system_disabled,
            systemActionTitleRes = R.string.perm_a11y_open_settings,
            onSystemAction = { openAccessibilitySettings(context) },
        )

        // [T-android-restricted-settings] Shown ONLY when the OS has actually
        // flagged this install (appop ACCESS_RESTRICTED_SETTINGS ==
        // MODE_ERRORED) and the service is still off. That is precisely the
        // state where the "Open accessibility settings" action right above
        // leads to a toggle the user cannot move: Android 13+ blocks the
        // accessibility toggle for packages installed by an installer that
        // declared PACKAGE_SOURCE_LOCAL_FILE / _DOWNLOADED_FILE, which is what
        // a browser or file manager opening a downloaded APK does. Without
        // this the user follows our own instruction into a dead end with no
        // explanation — the reported HyperOS / Android 14 case.
        //
        // We cannot clear the flag ourselves: it is set on our own uid and
        // clearing it needs the signature permission MANAGE_APP_OPS_MODES.
        // That is deliberate, since the policy exists to stop a sideloaded app
        // from talking the user into granting it screen-reading power. So the
        // manual route is the primary affordance and stays visible even when
        // the Shizuku one-tap shortcut is also offered.
        if (a11yRestricted) {
            SettingsSection(
                header = stringResource(R.string.system_permissions_a11y_restricted_header),
                footer = stringResource(R.string.system_permissions_a11y_restricted_footer),
            ) {
                if (shizukuSnap.state == ShizukuManager.State.READY) {
                    SettingsRow(
                        title = stringResource(R.string.system_permissions_a11y_restricted_shizuku),
                        subtitle = when {
                            unrestricting ->
                                stringResource(R.string.system_permissions_a11y_restricted_working)
                            unrestrictFailed ->
                                stringResource(R.string.system_permissions_a11y_restricted_failed)
                            else ->
                                stringResource(R.string.system_permissions_a11y_restricted_shizuku_sub)
                        },
                        onClick = {
                            if (unrestricting) return@SettingsRow
                            unrestricting = true
                            unrestrictFailed = false
                            scope.launch {
                                val ok = RestrictedSettingsManager.clearWithShizuku(context)
                                unrestricting = false
                                unrestrictFailed = !ok
                                // On success the poll above clears
                                // a11yRestricted and this section vanishes.
                            }
                        },
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.system_permissions_a11y_restricted_manual),
                    subtitle = stringResource(R.string.system_permissions_a11y_restricted_manual_sub),
                    // Lands on Minis' own App info page, where "Allow
                    // restricted settings" lives in the overflow menu.
                    onClick = { openAppDetailsSettings(context) },
                    showDivider = false,
                )
            }
        }

        IntegrationSection(
            iconVector = Icons.Outlined.Shield,
            iconTint = Color(0xFFAF52DE),
            // [T-android-privileged-backend] One section covers both Shizuku
            // and AXManager (they share the same binder slot + protocol);
            // toolName stays "shizuku_cli" so user authz is preserved.
            sectionHeaderRes = R.string.perm_section_privileged_backend,
            sectionFooterRes = R.string.perm_shizuku_section_footer,
            toolName = "shizuku_cli",
            descriptionRes = R.string.perm_privileged_cli_description,
            systemReady = ShizukuManager.isReady(),
            systemStatusTitleRes = shizukuSubtitleRes(shizukuSnap.state),
            systemActionTitleRes = shizukuActionTitleRes(shizukuSnap.state),
            // Open the unified Shizuku-protocol screen for setup actions.
            onSystemAction = onOpenPrivilegedBackend,
            // The status row itself opens the same page so users can revisit
            // the setup walkthrough even after the manager is already ready.
            onStatusRowClick = onOpenPrivilegedBackend,
        )

        // [T-android-root-cli-parity] Native Root uses the SAME card anatomy and
        // the SAME tri-state gate as the android-shizuku-cli card right above:
        // colored leading icon + CLI title + description, then the 允许 / 询问 /
        // 不允许 row, then the chevron "System authorization" row that opens the
        // walkthrough page. There is no separate switch and no enable dialog —
        // the level IS the authorisation (RootManager / RootGrantPolicy enforce
        // it for android-root-cli and the Chroot backend).
        IntegrationSection(
            iconVector = Icons.Outlined.Terminal,
            iconTint = Color(0xFFFF9500),
            sectionHeaderRes = 0,
            sectionFooterRes = 0,
            sectionHeaderText = "原生 Root",
            sectionFooterText = ROOT_TRI_STATE_FOOTER,
            toolName = "root_cli",
            descriptionRes = 0,
            titleText = "android-root-cli",
            descriptionText = "su 执行 package / settings / input / file 等设备命令，与 Shizuku 相互独立",
            systemReady = rootSnap.state == RootManager.State.READY,
            systemStatusTitleRes = 0,
            systemStatusText = rootStateLabel(rootSnap.state),
            systemActionTitleRes = 0,
            onSystemAction = { openRootBridgeSettings(context) },
            // Same interaction as the Shizuku row: the status line itself opens
            // the detail page, whatever the current state is.
            onStatusRowClick = { openRootBridgeSettings(context) },
        )

        Spacer(Modifier.height(16.dp))
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.perm_reset_confirm_title)) },
            text = { Text(stringResource(R.string.perm_reset_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    OffloadPermissionManager.resetAll()
                    com.openminis.app.config.MinisConfigPermissionStore.setEnabled(true)
                    RootManager.refresh()
                    AppLogger.info("PermissionsScreen", "user confirmed Reset All — all tool permissions cleared, minis-config switch reset to default")
                    showResetConfirm = false
                }) {
                    Text(stringResource(R.string.perm_reset_confirm))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * T336: composite SectionCard for a single integration CLI. Layout:
 *
 *   [icon]  CLI title
 *           CLI description (one-liner)
 *   ─────────
 *   Agent permission        [tri-state ▾]
 *   ─────────
 *   System authorization   [status text in color]
 *   ─────────
 *   (only when systemReady=false): [Open system settings →]
 *
 * The system-status row is read-only — the user goes to the OS settings
 * page to flip it. systemReady drives both the status row's color/icon
 * and the visibility of the action row below.
 */
@Composable
private fun IntegrationSection(
    iconVector: ImageVector,
    iconTint: Color,
    sectionHeaderRes: Int,
    sectionFooterRes: Int,
    toolName: String,
    descriptionRes: Int,
    systemReady: Boolean,
    systemStatusTitleRes: Int,
    systemActionTitleRes: Int,
    onSystemAction: () -> Unit,
    // [T-android-privileged-backend] When set, the system-status row becomes a
    // chevron-clickable entry to a dedicated detail screen (the multi-backend
    // Shizuku/AXManager page) — shown REGARDLESS of systemReady, so the user can
    // open it even when the backend is already authorized. Null keeps the
    // original read-only status row + conditional action behavior (a11y row).
    onStatusRowClick: (() -> Unit)? = null,
    // [T-android-root-cli-parity] Inline-copy overrides so a card can be added
    // here without a strings.xml edit (Root card). Null keeps the resource id,
    // so every pre-existing caller renders unchanged.
    sectionHeaderText: String? = null,
    sectionFooterText: String? = null,
    titleText: String? = null,
    descriptionText: String? = null,
    systemStatusText: String? = null,
) {
    SettingsSection(
        header = sectionHeaderText ?: stringResource(sectionHeaderRes),
        footer = sectionFooterText ?: stringResource(sectionFooterRes),
    ) {
        // Header row: CLI title + description, with a colored leading icon.
        SettingsRow(
            icon = iconVector,
            title = titleText ?: stringResource(toolTitleRes(toolName)),
            subtitle = descriptionText ?: stringResource(descriptionRes),
            showChevron = false,
        )

        // Agent tri-state policy — the SAME row for every integration CLI.
        AgentPolicyRow(toolName = toolName, showDivider = true)

        // System-layer status. When [onStatusRowClick] is provided the row is a
        // navigation entry (chevron, always shown); otherwise it's read-only.
        SettingsRow(
            title = stringResource(R.string.perm_system_authorization),
            onClick = onStatusRowClick,
            showChevron = onStatusRowClick != null,
            showDivider = onStatusRowClick == null && !systemReady,
            trailing = {
                Text(
                    text = systemStatusText ?: stringResource(systemStatusTitleRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (systemReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
            },
        )

        // Conditional action row: only for the read-only (a11y) variant, and
        // only when the system layer needs attention. The privileged-backend
        // variant routes everything through [onStatusRowClick] above instead.
        if (onStatusRowClick == null && !systemReady) {
            SettingsRow(
                title = stringResource(systemActionTitleRes),
                onClick = onSystemAction,
                showDivider = false,
            )
        }
    }
}

/**
 * Shared tri-state row. Reused by the dedicated IntegrationSection cards
 * (including Native Root) AND by the auto-rendered category loop below —
 * same dropdown menu, same chip in trailing.
 *
 * Root stores its level through [RootManager.setAccessLevel], which also
 * records the "user chose this" marker: detection must never overwrite an
 * explicit 询问 / 不允许 afterwards.
 */
@Composable
private fun AgentPolicyRow(
    toolName: String,
    showDivider: Boolean,
) {
    var currentLevel by remember { mutableStateOf(currentRootLevel(toolName)) }
    var expanded by remember { mutableStateOf(false) }
    val displayedLevel = permissionLevelDisplayName(toolName, currentLevel)
    Box {
        SettingsRow(
            title = stringResource(R.string.perm_agent_policy),
            onClick = { expanded = true },
            // [T-android-perm-row-affordance] This row OPENS A DROPDOWN, but with
            // showChevron=false it looked like a read-only status line — nothing
            // hinted it was tappable. It also broke alignment with the sibling
            // "system authorization" row: a chevron costs 4dp spacer + 20dp icon,
            // so that row's value text sits 24dp further left and the two values
            // visibly failed to line up inside the same card.
            // Showing the chevron fixes both at once.
            showChevron = true,
            showDivider = showDivider,
            trailing = {
                Text(
                    text = displayedLevel,
                    style = MaterialTheme.typography.labelLarge,
                    color = levelColor(currentLevel),
                )
            },
        )
        // T336-followup: anchor the menu's right edge to the row's right
        // edge so it grows down-and-left from the trailing chip instead
        // of Material3's default down-and-right (which on a narrow phone
        // pushed the menu off the screen edge).
        MinisMenu(expanded = expanded, onDismissRequest = { expanded = false }, alignEnd = true) {
            for (level in OffloadPermissionManager.PermissionLevel.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            permissionLevelDisplayName(toolName, level),
                            color = levelColor(level),
                        )
                    },
                    onClick = {
                        currentLevel = level
                        setLevelFromUi(toolName, level)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The stored level for a row: Root resolves its default without probing. */
private fun currentRootLevel(toolName: String): OffloadPermissionManager.PermissionLevel =
    if (toolName == ROOT_TOOL) {
        com.openminis.app.offload.RootGrantPolicy.toLevel(RootManager.accessLevel())
    } else {
        OffloadPermissionManager.getLevel(toolName)
    }

/** One write path for every AgentPolicyRow (Root also marks the user's choice). */
private fun setLevelFromUi(toolName: String, level: OffloadPermissionManager.PermissionLevel) {
    if (toolName == ROOT_TOOL) {
        RootManager.setAccessLevel(com.openminis.app.offload.RootGrantPolicy.levelOf(level), byUser = true)
    } else {
        OffloadPermissionManager.setLevel(toolName, level)
    }
}

private const val ROOT_TOOL = "root_cli"

@Composable
private fun PermissionRow(
    tool: OffloadPermissionManager.ToolPermissionInfo,
    showDivider: Boolean,
) {
    var currentLevel by remember { mutableStateOf(OffloadPermissionManager.getLevel(tool.toolName)) }
    var expanded by remember { mutableStateOf(false) }

    Box {
        SettingsRow(
            title = toolTitle(tool),
            subtitle = tool.toolName,
            onClick = { expanded = true },
            // [T-android-perm-row-affordance] Same dropdown affordance as
            // AgentPolicyRow — this row opens the same tri-state menu, so it gets
            // the same chevron. Keeping the two in sync also keeps every value in
            // the Privacy list on one right edge.
            showChevron = true,
            showDivider = showDivider,
            trailing = {
                Text(
                    text = levelDisplayName(currentLevel),
                    style = MaterialTheme.typography.labelLarge,
                    color = levelColor(currentLevel),
                )
            },
        )
        // T336-followup: anchor the menu's right edge to the row's right
        // edge so it grows down-and-left from the trailing chip instead
        // of Material3's default down-and-right (which on a narrow phone
        // pushed the menu off the screen edge).
        MinisMenu(expanded = expanded, onDismissRequest = { expanded = false }, alignEnd = true) {
            for (level in OffloadPermissionManager.PermissionLevel.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            levelDisplayName(level),
                            color = levelColor(level),
                        )
                    },
                    onClick = {
                        currentLevel = level
                        OffloadPermissionManager.setLevel(tool.toolName, level)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun categoryHeaderRes(category: OffloadPermissionManager.PermissionCategory): Int = when (category) {
    OffloadPermissionManager.PermissionCategory.PRIVACY -> R.string.perm_section_privacy
    OffloadPermissionManager.PermissionCategory.MEDIA -> R.string.perm_section_media
    OffloadPermissionManager.PermissionCategory.SYSTEM -> R.string.perm_section_system
    // INTEGRATIONS is rendered by IntegrationSection above; this branch is
    // unreachable through the auto-loop but kept exhaustive for `when`
    // exhaustiveness. Reuse the a11y header as a harmless fallback.
    OffloadPermissionManager.PermissionCategory.INTEGRATIONS -> R.string.perm_section_a11y
}

@Composable
private fun toolTitle(tool: OffloadPermissionManager.ToolPermissionInfo): String {
    val res = toolTitleRes(tool.toolName)
    if (res == 0) return tool.displayName
    return stringResource(res)
}

private fun toolTitleRes(toolName: String): Int = when (toolName) {
    "calendar" -> R.string.perm_tool_calendar
    "location" -> R.string.perm_tool_location
    "clipboard" -> R.string.perm_tool_clipboard
    "contacts" -> R.string.perm_tool_contacts
    "photos" -> R.string.perm_tool_photos
    "a11y_cli" -> R.string.perm_tool_a11y_cli
    "shizuku_cli" -> R.string.perm_tool_shizuku_cli
    else -> 0
}

@Composable
private fun permissionLevelDisplayName(
    toolName: String,
    level: OffloadPermissionManager.PermissionLevel,
): String = if (toolName == ROOT_TOOL) {
    com.openminis.app.offload.RootGrantPolicy.label(
        com.openminis.app.offload.RootGrantPolicy.levelOf(level),
    )
} else levelDisplayName(level)

@Composable
private fun levelDisplayName(level: OffloadPermissionManager.PermissionLevel): String = stringResource(
    when (level) {
        OffloadPermissionManager.PermissionLevel.BYPASS -> R.string.perm_level_bypass
        OffloadPermissionManager.PermissionLevel.ASK_ONCE -> R.string.perm_level_ask_once
        OffloadPermissionManager.PermissionLevel.NOT_ALLOWED -> R.string.perm_level_not_allowed
    },
)

@Composable
private fun levelColor(level: OffloadPermissionManager.PermissionLevel): Color = when (level) {
    OffloadPermissionManager.PermissionLevel.BYPASS -> MaterialTheme.colorScheme.primary
    OffloadPermissionManager.PermissionLevel.ASK_ONCE -> MaterialTheme.colorScheme.tertiary
    OffloadPermissionManager.PermissionLevel.NOT_ALLOWED -> MaterialTheme.colorScheme.error
}

private fun isA11yServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${MinisAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Throwable) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Throwable) {}
    }
}

/**
 * [T-android-restricted-settings] Open Minis' own App info page — "Allow
 * restricted settings" lives in that page's overflow (⋮) menu, and there is no
 * public intent that opens the menu item directly.
 */
private fun openAppDetailsSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Throwable) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Throwable) {}
    }
}

/**
 * [T-android-root-cli-parity] Root state label rendered in the card's "System
 * authorization" trailing slot, in the same short-label style as
 * [shizukuSubtitleRes]. Pure state read — it never probes su.
 */
fun rootStateLabel(state: RootManager.State): String = when (state) {
    RootManager.State.DISABLED -> "未启用"
    RootManager.State.NOT_FOUND -> "未检测到 su"
    RootManager.State.NEED_PERMISSION -> "等待 Root 授权"
    RootManager.State.READY -> "已授权"
    RootManager.State.DENIED -> "已拒绝"
}

/** Opens the native-Root walkthrough page (su lookup, one-shot verify). */
private fun openRootBridgeSettings(context: Context) {
    try {
        context.startActivity(
            Intent(context, com.openminis.app.rootbridge.RootBridgeSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Throwable) {}
}

private fun shizukuSubtitleRes(state: ShizukuManager.State): Int = when (state) {
    ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_state_not_installed
    ShizukuManager.State.NOT_RUNNING -> R.string.shizuku_state_not_running
    ShizukuManager.State.NEED_PERMISSION -> R.string.shizuku_state_need_permission
    ShizukuManager.State.READY -> R.string.shizuku_state_ready
}

// T336: state-dependent action row title — different verbs per phase of
// the Shizuku setup funnel.
private fun shizukuActionTitleRes(state: ShizukuManager.State): Int = when (state) {
    ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_install_btn
    // Every other phase routes to the same walkthrough page; the READY row is
    // never rendered, but the `when` still has to be exhaustive and Int-typed.
    ShizukuManager.State.NOT_RUNNING,
    ShizukuManager.State.NEED_PERMISSION,
    ShizukuManager.State.READY,
    -> R.string.shizuku_open_btn
}
