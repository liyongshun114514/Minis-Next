package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Eta-inspired conversation presentation (Mangi-11/Eta aec537db, GPL-3.0).
 * Only chat responses opt in; file previews, tools and exports retain their own owners.
 * Parsing, selection, media handling and streaming remain the existing native pipeline.
 */
internal enum class AssistantMarkdownTone { Answer, Thinking }
internal val LocalAssistantMarkdownTone = compositionLocalOf<AssistantMarkdownTone?> { null }

@Composable
internal fun AssistantAnswer(
    firstFragment: Boolean = true,
    lastFragment: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAssistantMarkdownTone provides AssistantMarkdownTone.Answer) {
        // The transcript already owns 16dp gutters: +4dp matches Eta's 20dp reading inset.
        Box(Modifier.fillMaxWidth().padding(
            start = 4.dp, end = 4.dp,
            top = if (firstFragment) 4.dp else 0.dp,
            bottom = if (lastFragment) 6.dp else 0.dp,
        )) { content() }
    }
}
