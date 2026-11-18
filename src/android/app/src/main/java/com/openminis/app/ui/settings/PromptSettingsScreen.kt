package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.openminis.app.R

/** Only the two user-owned instruction sources belong in System Prompt settings. */
@Composable
fun PromptSettingsScreen(
    onBack: () -> Unit,
    onIdentityClick: () -> Unit,
    onWorkspaceInstructionsClick: () -> Unit,
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
            )
            SettingsRow(
                title = stringResource(R.string.settings_workspace_instructions),
                subtitle = stringResource(R.string.settings_workspace_instructions_subtitle),
                icon = Icons.Outlined.Description,
                iconColor = Color(0xFF34C759),
                onClick = onWorkspaceInstructionsClick,
                showDivider = false,
            )
        }
    }
}
