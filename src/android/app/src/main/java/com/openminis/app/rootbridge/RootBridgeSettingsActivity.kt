package com.openminis.app.rootbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.offload.RootManager
import com.openminis.app.ui.chat.OffloadPermissionDialog
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsSwitchRow
import com.openminis.app.ui.settings.rootStateLabel
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native permission walkthrough parallel to the Shizuku screen, no module/socket required. */
class RootBridgeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MinisTheme { RootPermissionScreen(onBack = { finish() }); OffloadPermissionDialog() } }
    }
}

@Composable
fun RootPermissionScreen(onBack: () -> Unit) {
    val snap by RootManager.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var showEnable by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    // The permission level lives in SharedPreferences, not in Compose state;
    // mirror it locally so the switch reflects an immediate off/on change.
    var enabled by remember { mutableStateOf(RootManager.isEnabled()) }
    LaunchedEffect(Unit) { RootManager.refresh(); enabled = RootManager.isEnabled() }
    SettingsScaffold(title = "原生 Root", onBack = onBack) {
        SettingsSection(header = "Root 状态", footer = "与 Shizuku 独立，使用设备 su。启用不会执行命令；所有 Root 调用仍须逐次确认。") {
            SettingsSwitchRow(title = "允许请求原生 Root", checked = enabled,
                onCheckedChange = {
                    if (it) {
                        showEnable = true
                    } else {
                        RootManager.setEnabled(false)
                        enabled = false
                    }
                })
            // Same short Chinese state label the Permissions page shows in its
            // "系统授权" trailing slot, so both screens agree word for word.
            SettingsRow(title = rootStateLabel(snap.state),
                subtitle = "${snap.suPath ?: "未发现 su"}\n${snap.message}",
                showChevron = false)
            SettingsRow(title = if (probing) "正在等待确认或 Root 管理器…" else "验证 Root（id）",
                subtitle = "只执行 /system/bin/id，不修改设备；授权属于此 APK 的独立包名。",
                onClick = {
                    if (!probing && RootManager.isEnabled()) {
                        probing = true
                        scope.launch {
                            try { withContext(Dispatchers.IO) { RootManager.runProcess(arrayOf("/system/bin/id"), timeoutMs = 30_000) } }
                            finally { probing = false }
                        }
                    }
                }, showDivider = false)
        }
        SettingsSection(header = "命令能力", footer = "Chroot 另由沙盒后端页面授权；Root CLI 的许可不等于 Chroot 已启动。") {
            SettingsRow(title = "android-root-cli", subtitle = "package / permission / activity / display / settings / user / network / input / notification / file / device / service / exec", showChevron = false)
            SettingsRow(title = "执行前逐次确认", subtitle = "确认卡展示确切命令、目录及超时。取消或超时不执行；不继承整会话许可。", showChevron = false, showDivider = false)
        }
    }
    if (showEnable) AlertDialog(onDismissRequest = { showEnable = false },
        title = { Text("启用原生 Root 请求？") },
        text = { Text("Root 命令能控制完整设备，误操作可能丢失数据或导致系统无法启动。此开关仅开放请求入口，不运行 su；逐次确认之后仍由你的 Root 管理器决定授权。无需 Xposed 或 Shizuku 服务。") },
        confirmButton = { TextButton(onClick = {
            RootManager.setEnabled(true)
            enabled = true
            showEnable = false
        }) { Text("启用请求入口") } },
        dismissButton = { TextButton(onClick = { showEnable = false }) { Text("取消") } })
}
