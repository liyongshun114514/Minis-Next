package com.openminis.app.ui.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.RootAccess
import com.openminis.app.offload.RootGrantPolicy
import com.openminis.app.offload.RootManager

/** A live projection of the same permission used by native Root and chroot. */
@Composable
private fun rememberComposerRootAccess(): RootAccess {
    val context = LocalContext.current.applicationContext
    var access by remember { mutableStateOf(OffloadPermissionManager.rootLevel()) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("offload_permissions", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            access = OffloadPermissionManager.rootLevel()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Register before the first refresh so a settings change cannot be missed.
        access = OffloadPermissionManager.rootLevel()
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return access
}

@Composable
internal fun ComposerPermissionMenuItem(onClick: () -> Unit) {
    val access = rememberComposerRootAccess()
    DropdownMenuItem(
        text = {
            Column {
                Text("权限")
                Text(
                    "Root / chroot · ${RootGrantPolicy.label(access)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
        onClick = onClick,
    )
}

/** Selection is a draft until Save: closing the sheet never grants Root. */
@Composable
internal fun ComposerPermissionDialog(onDismiss: () -> Unit) {
    val current = rememberComposerRootAccess()
    var selection by remember(current) { mutableStateOf(current) }
    val descriptions = listOf(
        RootAccess.NOT_ALLOWED to "拒绝新的原生 Root 和 chroot 操作。",
        RootAccess.ASK_ONCE to "每次命令或新交互终端会话申请单次授权；拒绝、取消、超时均不执行。",
        RootAccess.BYPASS to "允许原生 Root 与 chroot 直接以 uid 0 执行，不再逐次弹应用确认；可随时撤销。",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限") },
        text = {
            Column {
                Text(
                    "与设置中的原生 Root 权限同步。真实 Root 能修改设备和数据；chroot 不是安全隔离边界。设备 su 管理器仍可单独要求授权。已启动的交互会话保留其获准范围，请先停止会话再撤销。",
                    style = MaterialTheme.typography.bodySmall,
                )
                descriptions.forEach { (access, description) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { selection = access }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selection == access, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(RootGrantPolicy.label(access))
                            Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                RootManager.setAccessLevel(selection)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
