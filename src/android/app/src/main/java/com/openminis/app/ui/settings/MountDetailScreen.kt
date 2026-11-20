package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.openminis.app.ui.components.MinisPageScaffold as Scaffold
import com.openminis.app.ui.settings.MinisSmallTopAppBar as SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import com.openminis.app.ui.components.minisPageScrollPadding
import com.openminis.app.ui.components.minisPageContentPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.ui.components.SectionTextField
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch

/**
 * Detail/edit screen for a single mounted folder. Mirrors iOS
 * MountDetailView (external case only). Allows renaming, toggling
 * the user soft-lock on writes, opening the in-app file browser
 * (placeholder toast for T219-2), and unmounting with confirmation.
 *
 * Save is enabled only when there are pending changes AND the new
 * name is valid.
 */
@Composable
fun MountDetailScreen(
    store: MountedFoldersStore,
    mountId: String,
    onBack: () -> Unit,
    onBrowseFiles: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val entries by store.entries.collectAsState()
    val entry = entries.firstOrNull { it.id == mountId }

    if (entry == null) {
        // Entry was removed (e.g. unmount + back-stack pop race). Pop out.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var nameText by remember(entry.id) { mutableStateOf(entry.name) }
    var allowWrite by remember(entry.id) { mutableStateOf(entry.userAllowWrite) }
    var showUnmountConfirm by remember { mutableStateOf(false) }

    val nameTrimmed = nameText.trim()
    val nameChanged = nameTrimmed != entry.name
    val nameValid = isValidMountName(nameText)
    val allowWriteChanged = allowWrite != entry.userAllowWrite
    val hasChanges = nameChanged || allowWriteChanged
    val canSave = hasChanges && (!nameChanged || nameValid)

    Scaffold(
        overlapTopBar = true,
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.mount_detail_title),
                navigationIcon = {
                    com.openminis.app.ui.components.MinisToolbarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            scope.launch {
                                if (nameChanged) store.rename(entry.id, nameTrimmed)
                                if (allowWriteChanged) store.setUserAllowWrite(entry.id, allowWrite)
                                onBack()
                            }
                        },
                        text = stringResource(R.string.save),
                        colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
                    )
                },
                color = MiuixTheme.colorScheme.surface,
            )
        },
        containerColor = MiuixTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()).minisPageScrollPadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            HeaderCard(entry = entry)

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.mount_add_name_label),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
            Spacer(Modifier.height(6.dp))
            SectionTextField(
                value = nameText,
                onValueChange = { nameText = it },
                singleLine = true,
                isError = nameChanged && !nameValid,
            )
            if (nameChanged && !nameValid) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.mount_detail_name_invalid),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.mount_add_name_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            Surface(
                color = MiuixTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.mount_add_allow_writes),
                            style = MiuixTheme.textStyles.main,
                        )
                        Text(
                            text = stringResource(R.string.mount_add_allow_writes_desc),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = allowWrite,
                        onCheckedChange = { allowWrite = it },
                        // Disable when the OS-level grant itself isn't writable.
                        enabled = entry.isWritable,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            ActionRow(
                icon = Icons.Outlined.Folder,
                tint = Color(0xFF007AFF),
                label = stringResource(R.string.mount_detail_browse_files),
                onClick = onBrowseFiles,
            )

            Spacer(Modifier.height(12.dp))
            ActionRow(
                icon = Icons.Outlined.Folder,
                tint = MiuixTheme.colorScheme.error,
                label = stringResource(R.string.mount_unmount_confirm),
                destructive = true,
                onClick = { showUnmountConfirm = true },
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    OverlayDialog(
        title = stringResource(R.string.mount_unmount_title),
        summary = stringResource(R.string.mount_unmount_message),
        show = showUnmountConfirm,
        onDismissRequest = { showUnmountConfirm = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { showUnmountConfirm = false },
                text = stringResource(R.string.cancel),
                colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
            )
            TextButton(
                onClick = {
                showUnmountConfirm = false
                scope.launch {
                    store.remove(entry.id)
                    onBack()
                }
            },
                text = stringResource(R.string.mount_unmount_confirm),
                colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.error),
            )
        }
    }
}

@Composable
private fun HeaderCard(entry: MountedFoldersStore.Entry) {
    Surface(
        color = MiuixTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF9500)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderShared,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "/var/minis/mounts/${entry.name}",
                    style = MiuixTheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val source = entry.sourceDisplayName.ifEmpty {
                    stringResource(R.string.mount_path_unavailable)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "← $source",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = MiuixTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint)
            Text(
                text = label,
                color = if (destructive) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
