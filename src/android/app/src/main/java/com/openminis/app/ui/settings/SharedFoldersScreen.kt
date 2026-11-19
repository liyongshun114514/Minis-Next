package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Psychology
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.openminis.app.ui.components.MinisPageScaffold as Scaffold
import com.openminis.app.ui.settings.MinisSmallTopAppBar as SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * Settings → Shared Folders. Mirrors iOS SharedFoldersView.
 *
 * Three fixed entries — Shared (R/W), Skills (read-only), Memory (read-only) —
 * exposing the first-class /var/minis/{shared,skills,memory} dirs to a native
 * browse UI. Skills and Memory are agent-tool-maintained and intentionally
 * read-only here so the user doesn't desync them by hand.
 */
@Composable
fun SharedFoldersScreen(
    onBack: () -> Unit,
    onFolderClick: (folderId: String) -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.shared_folders_title),
                navigationIcon = {
                    com.openminis.app.ui.components.MinisToolbarIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                color = MiuixTheme.colorScheme.surface,
            )
        },
        containerColor = MiuixTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            InfoBanner()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(SharedFolderRegistry.entries, key = { it.id }) { folder ->
                    Surface(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        SharedFolderRow(folder = folder, onClick = { onFolderClick(folder.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBanner() {
    Surface(
        color = MiuixTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(18.dp)),
    ) {
        Text(
            text = stringResource(R.string.shared_folders_info_banner),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun SharedFolderRow(
    folder: SharedFolderEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = folder.icon,
            contentDescription = null,
            tint = folder.iconColor,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(folder.nameRes),
                    style = MiuixTheme.textStyles.main,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                AccessBadge(writable = folder.writable)
            }
            Text(
                text = folder.linuxPath,
                style = MiuixTheme.textStyles.body2,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AccessBadge(writable: Boolean) {
    val (textRes, color) = if (writable) {
        R.string.mount_badge_rw to Color(0xFF34C759)
    } else {
        R.string.mount_badge_readonly to Color(0xFFFF9500)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Text(
            text = stringResource(textRes),
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

internal data class SharedFolderEntry(
    val id: String,
    val nameRes: Int,
    val linuxPath: String,
    val writable: Boolean,
    val icon: ImageVector,
    val iconColor: Color,
)

internal object SharedFolderRegistry {
    val entries: List<SharedFolderEntry> = listOf(
        SharedFolderEntry(
            id = "shared",
            nameRes = R.string.shared_folder_name_shared,
            linuxPath = "/var/minis/shared",
            writable = true,
            icon = Icons.Outlined.Folder,
            iconColor = Color(0xFF007AFF),
        ),
        SharedFolderEntry(
            id = "skills",
            nameRes = R.string.shared_folder_name_skills,
            linuxPath = "/var/minis/skills",
            writable = false,
            icon = Icons.Outlined.AutoAwesome,
            iconColor = Color(0xFFAF52DE),
        ),
        SharedFolderEntry(
            id = "memory",
            nameRes = R.string.shared_folder_name_memory,
            linuxPath = "/var/minis/memory",
            writable = false,
            icon = Icons.Outlined.Psychology,
            iconColor = Color(0xFFFF2D55),
        ),
    )

    fun find(id: String): SharedFolderEntry? = entries.firstOrNull { it.id == id }
}
