package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.OffloadPermissionManager.PermissionRequest
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton

/**
 * Dialog shown when an ASK_ONCE tool requests permission.
 *
 * T338: three session-scoped options. None of them ever upgrade the
 * stored PermissionLevel — to make a permanent change the user goes
 * to Settings → Permissions and picks Bypass / Not Allowed there.
 *
 *   • Allow in this session — grant for the remaining session, no
 *     more prompts for this tool until the session ends.
 *   • Allow once            — grant only this call; next call re-prompts.
 *   • Deny in this session  — refuse + remember, so the agent can't
 *     spam the same dialog after the user already said no.
 *
 * Dismissing via tap-outside or system back maps to DENY_SESSION too;
 * a silent dismiss should not invite the agent to keep retrying.
 *
 * Root requests ([PermissionRequest.singleUse]) use the compact layout
 * below instead: short title, the exact command (expandable, never
 * replaced by a summary), one risk line, and only "allow this once" /
 * "deny". No session or always-allow choice is rendered, and the manager
 * still accepts only ALLOW_ONCE for these requests.
 */
@Composable
fun OffloadPermissionDialog() {
    val request by OffloadPermissionManager.pendingRequest.collectAsState()
    val req = request ?: return

    if (req.singleUse) {
        RootCommandConfirmDialog(req)
        return
    }

    AlertDialog(
        onDismissRequest = {
            OffloadPermissionManager.respondToRequest(req, OffloadPermissionManager.Response.DENY_SESSION)
        },
        title = {
            Text(
                stringResource(R.string.offload_perm_dialog_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.offload_perm_dialog_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    req.toolTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.offload_perm_dialog_tool_label, req.toolName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    req.description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Root requests never reach this branch; kept as a guard so a
                // future singleUse caller cannot acquire a session grant.
                if (!req.singleUse) MinisButton(
                    onClick = { OffloadPermissionManager.respondToRequest(req, OffloadPermissionManager.Response.ALLOW_SESSION) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.offload_perm_allow_session))
                }
                // Allow once — no caching; next call re-prompts.
                MinisOutlinedButton(
                    onClick = { OffloadPermissionManager.respondToRequest(req, OffloadPermissionManager.Response.ALLOW_ONCE) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.offload_perm_allow_once))
                }
                // Deny in this session — refuse + remember.
                MinisTextButton(
                    onClick = { OffloadPermissionManager.respondToRequest(req, OffloadPermissionManager.Response.DENY_SESSION) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.offload_perm_deny_session))
                }
            }
        },
        dismissButton = null,
    )
}

/**
 * Root confirmation: title + verbatim command + one risk line + two actions.
 *
 * The command is shown in a monospace block clamped to three lines; when it
 * overflows, an explicit "expand full command" control reveals the entire
 * text, so no part of what will run is hidden. `req.toolTitle` is the exact
 * quoted argv, and `req.description` is the risk/context line produced by
 * RootManager. Tapping outside, back, or "deny" all answer DENY_SESSION,
 * which [OffloadPermissionManager.confirmPrivilegedOperation] treats as a
 * refusal (only ALLOW_ONCE returns true); a timeout also refuses.
 */
@Composable
private fun RootCommandConfirmDialog(req: PermissionRequest) {
    var expanded by remember(req) { mutableStateOf(false) }
    var canExpand by remember(req) { mutableStateOf(false) }
    val deny = {
        OffloadPermissionManager.respondToRequest(req, OffloadPermissionManager.Response.DENY_SESSION)
    }

    AlertDialog(
        onDismissRequest = deny,
        title = {
            Text(
                "允许本次 Root 命令？",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    req.toolTitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        )
                        .padding(10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = {
                        if (!expanded) canExpand = canExpand || it.hasVisualOverflow
                    },
                )
                if (canExpand || expanded) {
                    MinisTextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (expanded) "收起命令" else "展开完整命令")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    req.description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MinisButton(
                    onClick = {
                        OffloadPermissionManager.respondToRequest(req, OffloadPermissionManager.Response.ALLOW_ONCE)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("仅本次允许")
                }
                MinisTextButton(
                    onClick = deny,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("拒绝")
                }
            }
        },
        dismissButton = null,
    )
}
