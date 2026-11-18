package com.openminis.app.ui.settings

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.graphics.Color

/**
 * [T-kimi-oauth] Device-code login dialog for Kimi Code (RFC 8628): shows the
 * short user code (tap to copy), an "Open verification page" button (Chrome
 * Custom Tab — same in-app browser experience as the other OAuth providers;
 * NO FLAG_ACTIVITY_NEW_TASK, MainActivity is singleTask), and a waiting
 * spinner while the manager polls the token endpoint behind the dialog.
 *
 * The dialog is intentionally driven by REAL state handed up from
 * KimiOAuthManager.login's onDeviceCode callback — the iOS lesson was a
 * write-only state nobody rendered, making the login button look dead.
 * Dismissing cancels the login coroutine (the caller wires [onCancel]).
 */
@Composable
fun KimiDeviceLoginDialog(
    userCode: String,
    verificationUrl: String,
    onCancel: () -> Unit,
    /**
     * [T-copilot-provider] Title / instructions are parameterised so the same
     * dialog serves every RFC 8628 provider — the mechanics (show a code,
     * open a URL, poll behind the dialog) are identical, and forking it would
     * mean two copies of the copy-to-clipboard and Custom Tab handling.
     */
    title: String = stringResource(R.string.kimi_login_title),
    instructions: String = stringResource(R.string.kimi_login_instructions),
    /**
     * Optional warning shown above the code, in the error colour. Used by the
     * unofficial Copilot integration so the risk is on screen at the moment
     * of sign-in — not only on the settings row that led here.
     */
    riskNotice: String? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    OverlayDialog(
        show = true,
        onDismissRequest = onCancel,
        title = title,
    ) {
    Column {
        Text(
            instructions,
            style = MiuixTheme.textStyles.body1,
        )
        riskNotice?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(16.dp))
        // The user code — monospace, tap to copy.
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MiuixTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    clipboard.setText(AnnotatedString(userCode))
                    copied = true
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
            ) {
                Text(
                    userCode,
                    style = MiuixTheme.textStyles.headline2.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.kimi_login_copy_code),
                    tint = if (copied) {
                        androidx.compose.ui.graphics.Color(0xFF34C759)
                    } else {
                        MiuixTheme.colorScheme.onSurfaceContainerVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    // In-app browser, consistent with Claude/Codex/xAI.
                    val customTabsIntent = CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                    customTabsIntent.launchUrl(context, verificationUrl.toUri())
                }
                .padding(vertical = 8.dp),
        ) {
            Icon(
                Icons.Default.OpenInBrowser,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.kimi_login_open_verification),
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.kimi_login_waiting),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = onCancel,
                text = stringResource(R.string.common_cancel),
                colors = ButtonDefaults.textButtonColors(color = Color.Transparent, disabledColor = Color.Transparent, textColor = MiuixTheme.colorScheme.primary),
            )
        }
    }
    }
}
