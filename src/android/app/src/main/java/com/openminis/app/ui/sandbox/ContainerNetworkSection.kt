package com.openminis.app.ui.sandbox

import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.sandbox.ContainerDns
import com.openminis.app.sandbox.ContainerDns.DnsMode
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.ui.components.SettingsRowDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import top.yukonga.miuix.kmp.basic.BasicComponent

/**
 * [T-container-network-dns] (OpenMinis#396) Settings > Rootfs > Container
 * Network: whether the sandbox's resolv.conf follows the system DNS (Auto,
 * the default) or lists exactly the servers the user enters (Custom).
 *
 * Mode changes apply at once; the server list applies on Save, and only when
 * every entry is a valid IPv4 / IPv6 literal, so a typo can never reach
 * resolv.conf. Custom with no saved server keeps following the system (see
 * ContainerDns.plan) — the footer line always says which one is in effect.
 * Storage is shared with minis-config (`network.dnsMode`,
 * `network.dnsCustomServers`).
 */
@Composable
internal fun ContainerNetworkSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(ContainerDns.mode(context)) }
    var savedServers by remember { mutableStateOf(ContainerDns.rawServers(context)) }
    var draft by remember { mutableStateOf(savedServers) }
    var effective by remember { mutableStateOf(ContainerDns.plan(context)) }

    val parsed = ContainerDns.parse(draft)
    val savedMsg = stringResource(R.string.rootfs_dns_saved)

    /** Persist, rewrite resolv.conf off the main thread, refresh the footer. */
    fun persist(newMode: DnsMode, servers: String, toast: Boolean) {
        ContainerDns.save(context, newMode, servers)
        mode = newMode
        savedServers = ContainerDns.rawServers(context)
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { RootfsManager.getInstance(context).refreshDns() } }
            effective = ContainerDns.plan(context)
            if (toast) Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show()
        }
    }

    Column {
        Text(
            stringResource(R.string.rootfs_dns_mode),
            style = MiuixTheme.textStyles.subtitle,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        ModeRow(
            label = stringResource(R.string.rootfs_dns_auto),
            description = stringResource(R.string.rootfs_dns_auto_desc),
            selected = mode == DnsMode.AUTO,
            onClick = { if (mode != DnsMode.AUTO) persist(DnsMode.AUTO, savedServers, toast = false) },
        )
        ModeRow(
            label = stringResource(R.string.rootfs_dns_custom),
            description = stringResource(R.string.rootfs_dns_custom_desc),
            selected = mode == DnsMode.CUSTOM,
            onClick = { if (mode != DnsMode.CUSTOM) persist(DnsMode.CUSTOM, savedServers, toast = false) },
        )

        if (mode == DnsMode.CUSTOM) {
            SettingsRowDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Column(Modifier.fillMaxWidth() .semantics { if (!parsed.isValid) error("Invalid value") }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(R.string.rootfs_dns_servers), style = MiuixTheme.textStyles.body2)
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = MiuixTheme.textStyles.body1.copy(fontFamily = FontFamily.Monospace),
                        minLines = 3,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        colors = TextFieldDefaults.textFieldColors().let { if (!parsed.isValid) it.copy(borderColor = MiuixTheme.colorScheme.error) else it },
                        label = "223.5.5.5\n2400:3200::1",
                        useLabelAsPlaceholder = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val msg = when {
                                                !parsed.isValid -> stringResource(R.string.rootfs_dns_invalid, parsed.invalid.joinToString(", "))
                                                parsed.servers.isEmpty() -> stringResource(R.string.rootfs_dns_empty)
                                                parsed.servers.size > ContainerDns.RESOLVER_MAX_SERVERS ->
                                                    stringResource(R.string.rootfs_dns_too_many, ContainerDns.RESOLVER_MAX_SERVERS)
                                                else -> stringResource(R.string.rootfs_dns_servers_hint)
                                            }
                                            Text(msg)
                }
                val normalizedDraft = parsed.servers.joinToString("\n")
                TextButton(
                    onClick = { persist(DnsMode.CUSTOM, draft, toast = true); draft = normalizedDraft },
                    enabled = parsed.isValid && normalizedDraft != savedServers,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                    text = stringResource(R.string.rootfs_dns_save),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }

        SettingsRowDivider()
        Text(
            when (val p = effective) {
                is ContainerDns.Plan.Custom -> stringResource(R.string.rootfs_dns_effective_custom, p.servers.joinToString(", "))
                ContainerDns.Plan.System -> stringResource(R.string.rootfs_dns_effective_system)
            },
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ModeRow(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    BasicComponent(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        startAction = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = onClick)
                }
            },
    ) {
        Text(label)
        Text(description, style = MiuixTheme.textStyles.body2)
    }
}
