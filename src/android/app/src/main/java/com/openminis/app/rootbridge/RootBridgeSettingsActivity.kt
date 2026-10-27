package com.openminis.app.rootbridge

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.openminis.app.offload.ROOT_ENABLE_MESSAGE
import com.openminis.app.offload.RootAccess
import com.openminis.app.offload.RootCaller
import com.openminis.app.offload.RootGrantPolicy
import com.openminis.app.offload.RootManager
import com.openminis.app.offload.rootLevelNote
import com.openminis.app.ui.chat.OffloadPermissionDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.settings.KEY_FONT_APP_BASE
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import com.openminis.app.ui.settings.fontScaleForLevel
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.settings.rootStateLabel
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native Root settings page.
 *
 * [FEAT-261027-ROOT explicit] The page shows the SAME unified tri-state as
 * 设置 → 权限 (`不允许 / 每次询问 / 完全允许`) instead of a binary switch:
 *  - 完全允许 is only ever chosen here, after an explicit confirmation;
 *  - 每次询问 executes nothing without one approval per command/session;
 *  - 不允许 refuses, and [RootManager.setAccessLevel] also revokes the running
 *    chroot helper, its autostart and any controllable sandbox activity.
 *
 * The status bar follows the app's own appearance preference (System/Light/
 * Dark) rather than the system theme, which is what made this page's status
 * bar icons invert against the rest of Settings.
 */
class RootBridgeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appearance = getAppearancePrefs(this)
        val dark = when (appearance.getInt(KEY_THEME_MODE, 0)) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        // Match MainActivity's transparent bars, but choose the icon polarity
        // EXPLICITLY. SystemBarStyle.auto() leaves detection to the system, so
        // "app forced light + system dark" still produced white icons. light()
        // with both scrims transparent fixes the light case; the Compose
        // SideEffect below also sets the WindowInsetsController flags so the
        // two disagreeing sources can never win.
        val barStyle = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        setContent {
            MinisTheme(
                darkTheme = dark,
                fontScale = fontScaleForLevel(appearance.getInt(KEY_FONT_APP_BASE, 0)),
            ) {
                SideEffect {
                    androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                RootPermissionScreen(onBack = { finish() })
                OffloadPermissionDialog()
            }
        }
    }
}

@Composable
fun RootPermissionScreen(onBack: () -> Unit) {
    val snap by RootManager.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf(RootManager.accessLevel()) }
    var expanded by remember { mutableStateOf(false) }
    var confirmBypass by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }

    // The level lives in SharedPreferences: mirror it so a change made on the
    // Permissions page (or by a revoke) shows up here without a recomposition
    // of the whole tree.
    LaunchedEffect(Unit) {
        RootManager.refresh()
        level = RootManager.accessLevel()
    }

    SettingsScaffold(title = "原生 Root", onBack = onBack) {
        SettingsSection(
            header = "Root 权限",
            footer = "与 Shizuku 独立，使用设备 su；su 授权由你的 Root 管理器决定，本应用无法绕过。" +
                "三态对所有入口生效：android-root-cli、chroot 命令与交互终端。" +
                "“完全允许”代表真实 uid0，可修改设备与容器内文件；chroot 不是安全边界，" +
                "apt/dpkg 需要它。降级或撤销时本机会停止可控的 shell 活动，" +
                "已在运行的交互会话（终端）需先停止该会话再撤销。",
        ) {
            SettingsRow(
                title = "Root 权限级别",
                subtitle = currentNote(level),
                showChevron = true,
                showDivider = true,
                trailing = {
                    Text(
                        text = RootGrantPolicy.label(level),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                onClick = { expanded = true },
            )
            Box {
                MinisMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    alignEnd = true,
                ) {
                    for (option in RootAccess.entries) {
                        DropdownMenuItem(
                            text = { Text(RootGrantPolicy.label(option)) },
                            onClick = {
                                expanded = false
                                if (option == RootAccess.BYPASS) {
                                    // 完全允许 must be an explicit, read decision.
                                    confirmBypass = true
                                } else {
                                    // Also revokes the helper, autostart and
                                    // controllable activity (RootManager).
                                    RootManager.setAccessLevel(option)
                                    level = RootManager.accessLevel()
                                }
                            },
                        )
                    }
                }
            }
            SettingsRow(
                title = rootStateLabel(snap.state),
                subtitle = "${snap.suPath ?: "未发现 su"}\n${snap.message}",
                showChevron = false,
            )
            SettingsRow(
                title = if (probing) "正在等待 Root 管理器…" else "验证 Root（id）",
                subtitle = "只执行 /system/bin/id，不修改设备。这是你主动发起的验证：" +
                    "“每次询问”下会先确认一次；设备 su 管理器可能单独询问，授权属于此 APK 的独立包名。",
                onClick = {
                    if (!probing && level != RootAccess.NOT_ALLOWED) {
                        probing = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    RootManager.runProcess(
                                        arrayOf("/system/bin/id"),
                                        timeoutMs = 30_000,
                                        // An in-app action the user just pressed:
                                        // under 每次询问 this is ONE dialog, never
                                        // an automatic su.
                                        caller = RootCaller.USER,
                                    )
                                }
                            } finally {
                                probing = false
                                level = RootManager.accessLevel()
                            }
                        }
                    }
                },
                showDivider = false,
            )
        }
        SettingsSection(
            header = "命令能力",
            footer = "Chroot 与 android-root-cli 共用这一级别。Chroot 是否启动仍由 设置 → Shell 容器 的执行后端控制；" +
                "“每次询问”下自动重启不会静默执行 su。",
        ) {
            SettingsRow(
                title = "android-root-cli",
                subtitle = "package / permission / activity / display / settings / user / network / input / notification / file / device / service / exec",
                showChevron = false,
                showDivider = false,
            )
        }
    }

    if (confirmBypass) {
        AlertDialog(
            onDismissRequest = { confirmBypass = false },
            title = { Text("设为“完全允许”？") },
            text = { Text(ROOT_ENABLE_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    RootManager.setAccessLevel(RootAccess.BYPASS)
                    level = RootManager.accessLevel()
                    confirmBypass = false
                }) { Text("完全允许") }
            },
            dismissButton = { TextButton(onClick = { confirmBypass = false }) { Text("取消") } },
        )
    }
}

private fun currentNote(level: RootAccess): String = rootLevelNote(level)
