package com.openminis.app.ui.settings.backup

import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.backup.remote.RcloneChunkedUpload
import com.openminis.app.backup.remote.RcloneRemoteStore
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.components.MinisTextButton as TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import com.openminis.app.ui.components.MinisDialog as WindowDialog
import androidx.compose.foundation.layout.Arrangement

/**
 * [T-backup-destination-browse] What is actually stored on one destination.
 *
 * Reached by tapping a destination in a backup record, so "did this really
 * land?" is answerable without hunting through Settings for the server —
 * which is the question a user opens an old record to ask.
 *
 * Scoped deliberately to browsing and deleting. iOS's
 * `BackupDestinationDetailView` also edits the connection, renames, tests and
 * removes the destination; Android already has those under Manage
 * Destinations, and duplicating them here would give the same server two
 * places to be edited from.
 */
@Composable
fun BackupDestinationBrowseScreen(
    remote: RcloneRemoteStore.Remote,
    vm: BackupViewModel,
    onBack: () -> Unit,
) {
    val packages by vm.serverPackages.collectAsState()
    val running by vm.isRunning.collectAsState()
    val error by vm.errorText.collectAsState()
    var pendingDelete by remember { mutableStateOf<RcloneChunkedUpload.RemotePackage?>(null) }

    LaunchedEffect(remote.name) { vm.clearError(); vm.listServerPackages(remote) }

    SettingsScaffold(
        title = remote.name,
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.backup_dest_stored_here),
            footer = stringResource(
                if (packages.isEmpty()) R.string.backup_dest_browse_footer_empty
                else R.string.backup_dest_browse_footer,
            ),
        ) {
            when {
                running -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.backup_dest_loading),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
                packages.isEmpty() -> {
                    Text(
                        stringResource(R.string.backup_dest_no_packages),
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                }
                else -> packages.forEachIndexed { i, pkg ->
                    PackageRow(
                        pkg = pkg,
                        showDivider = i < packages.lastIndex,
                        onDelete = { pendingDelete = pkg },
                    )
                }
            }
        }
        // A failed delete leaves the list unchanged, so without this the user
        // sees the file still there and no reason why.
        error?.let {
            Text(
                it,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    pendingDelete?.let { pkg ->
        WindowDialog(
            show = true,
            onDismissRequest = { pendingDelete = null },
            title = stringResource(R.string.backup_dest_delete_title),
            summary = stringResource(R.string.backup_dest_delete_note, pkg.displayName),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.openminis.app.ui.components.MinisDialogActions(
                    dismissButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { pendingDelete = null },
                            text = stringResource(R.string.backup_dest_cancel),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                vm.deleteServerPackage(remote, pkg)
                                pendingDelete = null
                            },
                            text = stringResource(R.string.backup_dest_delete_confirm),
                            colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.error),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun PackageRow(
    pkg: RcloneChunkedUpload.RemotePackage,
    showDivider: Boolean,
    onDelete: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    pkg.displayName,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    buildString {
                        append(humanBytes(pkg.size))
                        pkg.modified?.let { append(" · "); append(formatTimestamp(it)) }
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.backup_dest_delete_confirm),
                    tint = MiuixTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 14.dp)
                    .height(0.5.dp).background(MiuixTheme.colorScheme.dividerLine),
            )
        }
    }
}
