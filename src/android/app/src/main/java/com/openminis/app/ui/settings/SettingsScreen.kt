package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.openminis.app.ui.components.OmniBottomSheet as ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.OmniColors
import com.openminis.app.ui.theme.OmniDimens
import com.openminis.app.ui.theme.omniTextStyle
import androidx.compose.ui.res.stringResource
import com.openminis.app.BuildConfig
import com.openminis.app.KairosBrandLinks
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProvidersClick: () -> Unit,
    onModelGroupsClick: () -> Unit,
    onRootfsClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onEnvVarsClick: () -> Unit = {},
    /** [T-net-android-phase2] Settings › Network › Network Routing. */
    onNetworkClick: () -> Unit = {},
    /** [FEAT-261021-04] The other rows of the Network section. */
    onTailscaleClick: () -> Unit = {},
    onProxiesClick: () -> Unit = {},
    onContainerDnsClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    /** [T-p2-agent-settings] Settings › Agents (delegation on/off), below Memory. */
    onAgentsClick: () -> Unit = {},
    onAgentToolsClick: () -> Unit = {},
    // [T-mcp-integration-android] MCP Integrations page, listed directly below
    // Memory. Default no-op for callers that haven't wired the route yet.
    onMcpClick: () -> Unit = {},
    // [T-soul-md] Soul settings page lives between Skills and Memory in the
    // Agent Runtime section; default no-op for callers that haven't wired
    // the route yet.
    onSoulClick: () -> Unit = {},
    /** [T-workspace-instructions] Settings › Workspace Instructions. */
    onWorkspaceInstructionsClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    // T219-2: Mount External Folders entry. Default no-op for any caller
    // that hasn't wired the route yet.
    onMountedFoldersClick: () -> Unit = {},
    // T235: Shared Folders entry (Shared / Skills / Memory). Default no-op
    // for back-compat with callers wired before T235.
    onSharedFoldersClick: () -> Unit = {},
    // T50: Background & Notifications screen (battery optimisation +
    // OEM autostart guidance). Default no-op so older callers/tests
    // don't need to be retrofitted.
    onBackgroundClick: () -> Unit = {},
    // Hook accepted for forward-compat with AppNavigation's About route. The
    // About row below still has a TODO onClick in HEAD; future settings-bucket
    // work will wire this through.
    onAboutClick: () -> Unit = {},
) {
    val context = LocalContext.current
    var showFeedbackSheet by remember { mutableStateOf(false) }
    // [T-omnibot-ui-port] The settings root now uses the same shared scaffold as
    // every sub-page: one 72dp header with a 28sp/w600 leading title, and the
    // row→page container transform (a tapped row expands into its destination
    // over 300ms fastOutSlowIn). Title, back action and callbacks are unchanged.
    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        // -- LLM Providers --
        SettingsSection(
            header = stringResource(R.string.settings_section_llm_providers),
            footer = stringResource(R.string.settings_section_llm_providers_footer),
        ) {
            SettingsRow(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.settings_manage_providers),
                subtitle = stringResource(R.string.settings_manage_providers_subtitle),
                onClick = onProvidersClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Settings,
                title = stringResource(R.string.settings_model_groups),
                subtitle = stringResource(R.string.settings_model_groups_subtitle),
                onClick = onModelGroupsClick,
            )
            SettingsRow(
                icon = Icons.Outlined.BarChart,
                title = stringResource(R.string.settings_token_usage),
                subtitle = stringResource(R.string.settings_token_usage_subtitle),
                onClick = onUsageClick,
                showDivider = false,
            )
        }

        // -- Appearance --
        SettingsSection(header = stringResource(R.string.settings_section_appearance)) {
            SettingsRow(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_section_appearance),
                subtitle = stringResource(R.string.settings_appearance_subtitle),
                onClick = onAppearanceClick,
                showDivider = false,
            )
        }

        // -- Agent Runtime --
        // [T-android-settings-runtime-order] Row order matches iOS
        // (ContentView.swift, Section "Agent Runtime"): Agent Tools, Sub
        // Agents, Skills, Soul, Memory, MCP, Environment Variables.
        //
        // The order is not alphabetical or historical — it runs from what
        // the agent may DO (tools, and who it may hand work to) to what it
        // is made OF (skills, identity, memory) and finally to what it
        // connects to (MCP, env). Android had grown its own order simply by
        // appending each row as it landed, which put the two capability
        // switches in the middle of the identity rows.
        SettingsSection(header = stringResource(R.string.settings_section_agent_runtime)) {
            // [T-tools-granular-switches] Which optional tools the agent may use.
            SettingsRow(
                icon = Icons.Outlined.Handyman,
                title = stringResource(R.string.settings_agent_tools),
                subtitle = stringResource(R.string.settings_agent_tools_subtitle),
                onClick = onAgentToolsClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Groups,
                title = stringResource(R.string.settings_agents),
                subtitle = stringResource(R.string.settings_agents_subtitle),
                onClick = onAgentsClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Extension,
                title = stringResource(R.string.settings_skills),
                subtitle = stringResource(R.string.settings_skills_subtitle),
                onClick = onSkillsClick,
            )
            // [T-soul-md] Between Skills and Memory, as on iOS.
            SettingsRow(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(R.string.settings_soul),
                subtitle = stringResource(R.string.settings_soul_subtitle),
                onClick = onSoulClick,
            )
            // [T-workspace-instructions] Directly below System Prompt: the
            // global prompt is what the agent IS, this is the guidance the
            // project's own AGENTS.md / CLAUDE.md files add on top of it.
            SettingsRow(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.settings_workspace_instructions),
                subtitle = stringResource(R.string.settings_workspace_instructions_subtitle),
                onClick = onWorkspaceInstructionsClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Psychology,
                title = stringResource(R.string.settings_memory),
                subtitle = stringResource(R.string.settings_memory_subtitle),
                onClick = onMemoryClick,
            )
            // [T-mcp-integration-android] MCP Integrations — below Memory.
            // [T-android-mcp-icon-distinct] Dashboard (2x2 block grid) instead of
            // Extension so MCP no longer shares the Skills row's puzzle-piece icon —
            // the grid reads as "multiple composed blocks/servers". teal unchanged.
            SettingsRow(
                icon = Icons.Outlined.Dashboard,
                title = stringResource(R.string.settings_mcp),
                subtitle = stringResource(R.string.settings_mcp_subtitle),
                onClick = onMcpClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Terminal,
                title = stringResource(R.string.settings_env_vars),
                subtitle = stringResource(R.string.settings_env_vars_subtitle),
                onClick = onEnvVarsClick,
                showDivider = false,
            )
        }

        // -- Storage --
        SettingsSection(header = stringResource(R.string.settings_section_storage)) {
            SettingsRow(
                icon = Icons.Outlined.Inventory2,
                title = stringResource(R.string.settings_section_storage),
                subtitle = stringResource(R.string.settings_storage_subtitle),
                onClick = onRootfsClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Folder,
                title = stringResource(R.string.settings_shared_folders),
                subtitle = stringResource(R.string.settings_shared_folders_subtitle),
                onClick = onSharedFoldersClick,
            )
            SettingsRow(
                icon = Icons.Outlined.FolderShared,
                title = stringResource(R.string.settings_mount_external_folders),
                subtitle = stringResource(R.string.settings_mount_external_folders_subtitle),
                onClick = onMountedFoldersClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Backup,
                title = stringResource(R.string.settings_backup_restore),
                subtitle = stringResource(R.string.settings_backup_restore_subtitle),
                onClick = onBackupClick,
                showDivider = false,
            )
        }

        // -- Network -- [FEAT-261021-04, iOS c77be4fb6] its own section below
        // Storage: Network Routing, Tailscale, Proxies and the Shell
        // Container DNS (until now only on the Rootfs page).
        SettingsSection(header = stringResource(R.string.network_title)) {
            SettingsRow(
                icon = Icons.Outlined.Lan,
                title = stringResource(R.string.network_routing_title),
                subtitle = null,
                onClick = onNetworkClick,
            )
            SettingsRow(
                icon = Icons.Outlined.VpnLock,
                title = stringResource(R.string.network_tailscale),
                subtitle = null,
                onClick = onTailscaleClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Router,
                title = stringResource(R.string.network_proxies),
                subtitle = null,
                onClick = onProxiesClick,
            )
            SettingsRow(
                icon = Icons.Outlined.Dns,
                title = stringResource(R.string.network_container_dns),
                subtitle = null,
                onClick = onContainerDnsClick,
                showDivider = false,
            )
        }

        // -- Permissions --
        SettingsSection(header = stringResource(R.string.settings_section_permissions)) {
            SettingsRow(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.settings_section_permissions),
                subtitle = stringResource(R.string.settings_permissions_subtitle),
                onClick = onPermissionsClick,
                showDivider = false,
            )
        }

        // -- Background & Notifications (T50) --
        SettingsSection(
            header = stringResource(R.string.bg_section_header),
            footer = stringResource(R.string.bg_section_footer),
        ) {
            SettingsRow(
                icon = Icons.Outlined.BatteryFull,
                title = stringResource(R.string.bg_section_header),
                subtitle = stringResource(R.string.bg_section_subtitle),
                onClick = onBackgroundClick,
                showDivider = false,
            )
        }

        // -- Logs --
        SettingsSection(header = stringResource(R.string.settings_section_logs)) {
            SettingsRow(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.settings_section_logs),
                subtitle = stringResource(R.string.settings_logs_subtitle),
                onClick = onLogsClick,
                showDivider = false,
            )
        }

        // -- About --
        SettingsSection(header = stringResource(R.string.settings_section_about)) {
            // [T-update-channels] The update badge's Settings layer.
            val updateBadge by com.openminis.app.data.UpdateStateStore.hasUpdateBadge.collectAsState()
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.settings_about_minis),
                subtitle = stringResource(R.string.settings_about_subtitle),
                onClick = onAboutClick,
                // The update dot sits just before the chevron, as before.
                trailing = if (updateBadge) {
                    { UpdateBadgeDot() }
                } else {
                    null
                },
            )
            SettingsRow(
                icon = Icons.Outlined.FrontHand,
                title = stringResource(R.string.settings_privacy_policy),
                subtitle = null,
                // iOS canonical URL — ContentView.swift / AddProviderView.swift
                onClick = { openExternalUrl(context, "https://openminis.github.io/privacy-policy.html") },
            )
            SettingsRow(
                icon = Icons.Outlined.Feedback,
                title = stringResource(R.string.settings_feedback),
                subtitle = null,
                onClick = { showFeedbackSheet = true },
                showDivider = false,
            )
        }

        // Omni page bottom inset.
        Spacer(Modifier.height(OmniDimens.PageVerticalBottom))
    }

    if (showFeedbackSheet) {
        ModalBottomSheet(onDismissRequest = { showFeedbackSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                FeedbackSheetItem(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.kairos_issue_submit_title),
                    subtitle = stringResource(R.string.kairos_repo_subtitle),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildBugReportUrl())
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.AutoMirrored.Outlined.Send,
                    title = stringResource(R.string.kairos_telegram_title),
                    subtitle = stringResource(R.string.kairos_telegram_subtitle),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, KairosBrandLinks.TELEGRAM_URL)
                    },
                )
                FeedbackSheetItem(
                    icon = Icons.Outlined.Email,
                    title = stringResource(R.string.kairos_email_title),
                    subtitle = stringResource(R.string.kairos_email_value),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, buildFeedbackMailto())
                    },
                )
                // [Minis Next brand] Upstream stays one tap away from the feedback
                // sheet so the fork never looks like an original project.
                FeedbackSheetItem(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.kairos_feedback_upstream_title),
                    subtitle = stringResource(R.string.kairos_feedback_upstream_subtitle),
                    onClick = {
                        showFeedbackSheet = false
                        openExternalUrl(context, KairosBrandLinks.UPSTREAM_REPOSITORY_URL)
                    },
                )
            }
        }
    }
}

@Composable
private fun FeedbackSheetItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = OmniDimens.RowHorizontal, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = OmniColors.textPrimary,
            modifier = Modifier.size(OmniDimens.LeadingIconSize),
        )
        Spacer(Modifier.width(OmniDimens.LeadingIconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = omniTextStyle(size = OmniDimens.RowTitleSize, color = OmniColors.textPrimary),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = omniTextStyle(
                        size = OmniDimens.RowSubtitleSize,
                        color = OmniColors.textSecondary,
                    ),
                )
            }
        }
    }
}

/**
 * Build the GitHub Issues "new bug report" URL with the body pre-filled
 * from the existing bug-report template. Platform / OS version / app
 * version / device model are injected so the report arrives ready to
 * triage instead of asking the user to fill in environment details.
 *
 * URL shape:
 *   https://github.com/liyongshun114514/Minis-Next/issues/new
 *     ?template=bug_report.md
 *     &title=[Bug]
 *     &body=<percent-encoded markdown>
 *
 * The body is a Markdown template with sections for Problem Summary,
 * Basic Information (table — auto-filled), Steps to Reproduce, Error
 * Details (fenced code block), Expected Behavior, and Additional
 * Information.
 */
private fun buildBugReportUrl(): String {
    val osVersion = android.os.Build.VERSION.RELEASE
    val sdkInt = android.os.Build.VERSION.SDK_INT
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE
    val manufacturer = android.os.Build.MANUFACTURER
    val model = android.os.Build.MODEL

    // Body matches the spec template. Triple-backtick fences are written
    // as "```" — they survive percent-encoding cleanly. Indentation here
    // is significant: trimIndent() removes the common Kotlin indentation
    // but preserves the Markdown structure as-is.
    val body = """
        ## 📝 Problem Summary

        <!-- Briefly describe the issue you encountered -->


        ## 📱 Basic Information

        | Field | Value |
        |-------|-------|
        | Platform | Android |
        | OS Version | Android $osVersion (API $sdkInt) |
        | Minis Next Version | $versionName (build $versionCode) |
        | Device Model | $manufacturer $model |

        ## 🔁 Steps to Reproduce

        1.
        2.
        3.

        ## ❌ Error Details

        ```
        paste error here
        ```

        ## ✅ Expected Behavior



        ## 🗂️ Additional Information

    """.trimIndent()

    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    // Title carries a "[Bug] " prefix with a trailing space so the cursor
    // lands after it on GitHub's page; encode the space as %20 explicitly
    // since URLEncoder turns spaces into '+' which GitHub also accepts but
    // the spec calls for the literal "[Bug] " form.
    val title = java.net.URLEncoder.encode("[Bug] ", "UTF-8")
    return KairosBrandLinks.NEW_ISSUE_URL +
        "?template=bug_report.md" +
        "&title=$title" +
        "&body=$encodedBody"
}

/**
 * Compose a `mailto:` URL with a prefilled subject and body that include
 * app version, Android version, and device model. Mirrors iOS
 * `ContentView.makeFeedbackEmailURL()`.
 */
private fun buildFeedbackMailto(): String {
    val body = """
        Please describe your feedback:


        ---
        App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        Android Version: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})
        Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}

        Screenshot (optional): Please attach a screenshot if relevant.
    """.trimIndent()
    val subject = java.net.URLEncoder.encode("Minis Next Feedback", "UTF-8")
    val encodedBody = java.net.URLEncoder.encode(body, "UTF-8")
    return "mailto:" + KairosBrandLinks.EMAIL + "?subject=$subject&body=$encodedBody"
}
