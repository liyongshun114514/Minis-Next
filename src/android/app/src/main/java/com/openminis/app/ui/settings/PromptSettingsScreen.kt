package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.openminis.app.R

/** SOUL is the single user-owned instruction source. */
@Composable
fun PromptSettingsScreen(
    onBack: () -> Unit,
    onIdentityClick: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.settings_soul),
        onBack = onBack,
    ) {
        SettingsSection {
            SettingsRow(
                title = "SOUL",
                subtitle = stringResource(R.string.prompt_catalog_row_identity_subtitle),
                icon = Icons.Outlined.AutoAwesome,
                iconColor = Color(0xFFFF9500),
                onClick = onIdentityClick,
                showDivider = false,
            )
        }
    }
}
