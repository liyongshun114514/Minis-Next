package com.openminis.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.openminis.app.AppBuildInfo
import com.openminis.app.BuildConfig
import com.openminis.app.KairosBrandLinks
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val tileBlue = Color(0xFF007AFF)

    SettingsScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val iconPainter = remember(context) {
                // painterResource() can't load adaptive-icon XML drawables (mipmap-anydpi-v26),
                // so fetch the launcher icon as a Drawable and convert to a Bitmap.
                val drawable = context.packageManager.getApplicationIcon(context.packageName)
                BitmapPainter(drawable.toBitmap(width = 192, height = 192).asImageBitmap())
            }
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = iconPainter,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                )
            }
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(
                    R.string.about_version_format,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE.toString(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // [T-android-about-build-date] Selectable so it can be copied
            // into a bug report.
            val buildDate = remember(context) { AppBuildInfo.buildDate(context) }
            SelectionContainer {
                Text(
                    stringResource(R.string.about_built_format, buildDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Text(
                stringResource(R.string.about_minis_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            // [Minis Next brand] Make the fork identity explicit; upstream credit
            // follows in its own section below.
            Text(
                stringResource(R.string.kairos_maintainer_line),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }

        // [Minis Next brand] All contact entries point at the Minis Next repository /
        // maintainer. They use openExternalUrl directly for the same reason as
        // the old single GitHub row: nothing wraps Settings with an
        // InAppBrowserHost, so the ambient launcher is a no-op here.
        SettingsSection(header = stringResource(R.string.kairos_brand_section)) {
            SettingsRow(
                icon = Icons.Outlined.Code,
                iconColor = tileBlue,
                title = stringResource(R.string.kairos_repo_title),
                subtitle = stringResource(R.string.kairos_repo_subtitle),
                onClick = { openExternalUrl(context, KairosBrandLinks.REPOSITORY_URL) },
                trailing = { ExternalLinkIcon() },
            )
            SettingsRow(
                icon = Icons.Outlined.BugReport,
                iconColor = tileBlue,
                title = stringResource(R.string.kairos_issue_title),
                subtitle = stringResource(R.string.kairos_issue_subtitle),
                onClick = { openExternalUrl(context, KairosBrandLinks.NEW_ISSUE_URL) },
                trailing = { ExternalLinkIcon() },
            )
            SettingsRow(
                icon = Icons.Outlined.Email,
                iconColor = tileBlue,
                title = stringResource(R.string.kairos_email_title),
                subtitle = stringResource(R.string.kairos_email_value),
                onClick = { openExternalUrl(context, "mailto:" + KairosBrandLinks.EMAIL) },
                trailing = { ExternalLinkIcon() },
            )
            SettingsRow(
                icon = Icons.AutoMirrored.Outlined.Send,
                iconColor = tileBlue,
                title = stringResource(R.string.kairos_telegram_title),
                subtitle = stringResource(R.string.kairos_telegram_subtitle),
                onClick = { openExternalUrl(context, KairosBrandLinks.TELEGRAM_URL) },
                trailing = { ExternalLinkIcon() },
                showDivider = false,
            )
        }

        // [Minis Next brand] Attribution is a first-class entry, not a footnote:
        // the original project, its issue tracker and the GPL-3.0 credit stay
        // reachable from About.
        SettingsSection(header = stringResource(R.string.kairos_upstream_section)) {
            SettingsRow(
                icon = Icons.Outlined.Code,
                iconColor = tileBlue,
                title = stringResource(R.string.kairos_upstream_title),
                subtitle = stringResource(R.string.kairos_upstream_subtitle),
                onClick = { openExternalUrl(context, KairosBrandLinks.UPSTREAM_REPOSITORY_URL) },
                trailing = { ExternalLinkIcon() },
            )
            SettingsRow(
                icon = Icons.Outlined.BugReport,
                iconColor = tileBlue,
                title = stringResource(R.string.kairos_upstream_issues_title),
                subtitle = stringResource(R.string.kairos_upstream_issues_subtitle),
                onClick = { openExternalUrl(context, KairosBrandLinks.UPSTREAM_ISSUES_URL) },
                trailing = { ExternalLinkIcon() },
                showDivider = false,
            )
        }
        Text(
            stringResource(R.string.kairos_upstream_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        // T122: surface the existing UpdateChecker entry on the About screen.
        // The composable was already implemented but never wired anywhere, so
        // users had no way to trigger a check.
        CheckUpdateSection()

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExternalLinkIcon() {
    Text(
        "↗",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Back-compat shim for legacy call sites that still call [openUrl] with a
 * Context. Dispatches as a system Intent — the in-app preview path is the new
 * `LocalInAppBrowserLauncher` ambient; prefer that at the call site.
 */
internal fun openUrl(context: android.content.Context, url: String) {
    openExternalUrl(context, url)
}
