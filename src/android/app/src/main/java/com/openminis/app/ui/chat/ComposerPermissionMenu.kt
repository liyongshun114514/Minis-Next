package com.openminis.app.ui.chat

import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import com.openminis.app.ui.components.miuix.DropdownMenuItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
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
import com.openminis.app.ui.components.MinisTextButton as TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.components.MinisDialog as WindowDialog
import androidx.compose.foundation.layout.Arrangement

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
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
        },
        leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
        onClick = onClick,
    )
}

/** Selection stays a draft until Save; requested visibility never changes Root access. */
@Composable
internal fun ComposerPermissionDialog(onDismiss: () -> Unit, show: Boolean = true) {
    val current = rememberComposerRootAccess()
    var selection by remember { mutableStateOf(current) }
    androidx.compose.runtime.LaunchedEffect(show) { if (show) selection = current }
    WindowDialog(
        show = show,
        onDismissRequest = onDismiss,
        title = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.composer_permission_title),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    androidx.compose.ui.res.stringResource(com.openminis.app.R.string.composer_permission_explanation),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
                ComposerRootAccessChoices(selection) { selection = it }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.common_cancel),
                    onClick = onDismiss,
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.common_save),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        RootManager.setAccessLevel(selection)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/** Native Preference margins, end-side check and a secondary Card give each choice a clear level. */
@Composable
internal fun ComposerRootAccessChoices(selection: RootAccess, onSelect: (RootAccess) -> Unit) {
    val options = listOf(
        Triple(RootAccess.NOT_ALLOWED, com.openminis.app.R.string.composer_root_denied, com.openminis.app.R.string.composer_root_denied_summary),
        Triple(RootAccess.ASK_ONCE, com.openminis.app.R.string.composer_root_ask, com.openminis.app.R.string.composer_root_ask_summary),
        Triple(RootAccess.BYPASS, com.openminis.app.R.string.composer_root_allowed, com.openminis.app.R.string.composer_root_allowed_summary),
    )
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surface,
        ),
    ) {
        options.forEach { (access, label, summary) ->
            top.yukonga.miuix.kmp.preference.RadioButtonPreference(
                title = androidx.compose.ui.res.stringResource(label),
                summary = androidx.compose.ui.res.stringResource(summary),
                selected = selection == access,
                onClick = { onSelect(access) },
                radioButtonLocation = top.yukonga.miuix.kmp.preference.RadioButtonLocation.End,
            )
        }
    }
}
