package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable

/** The System Prompt route renders the sole SOUL editor, without a directory hop. */
@Composable
fun PromptSettingsScreen(onBack: () -> Unit) {
    SoulSettingsScreen(onBack = onBack)
}
