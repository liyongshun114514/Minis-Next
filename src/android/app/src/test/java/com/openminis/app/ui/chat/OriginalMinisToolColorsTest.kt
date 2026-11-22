package com.openminis.app.ui.chat

import androidx.compose.ui.graphics.Color
import com.openminis.app.ProductionSources
import com.openminis.app.ui.theme.LightChatPalette
import com.openminis.app.ui.theme.DarkChatPalette
import org.junit.Assert.*
import org.junit.Test

class OriginalMinisToolColorsTest {
    @Test fun `tool result pills retain original neutral colors`() {
        assertEquals(Color(0xFFF2F2F7), LightChatPalette.toolCapsuleBg)
        assertEquals(Color(0xFF28282C), DarkChatPalette.toolCapsuleBg)
        val theme = ProductionSources.read("ui/theme/Theme.kt")
        assertTrue(theme.contains("toolCapsuleBg = base.toolCapsuleBg"))
        val pill = ProductionSources.read("ui/chat/ChatAssistantMessageUI.kt")
            .substringAfter("internal fun ToolCallPill(")
        assertTrue(pill.contains(".background(ChatColors.toolCapsuleBg)"))
        assertFalse(pill.contains(".background(ChatColors.userBubble)"))
    }

    @Test fun `text and file previews use the same original dark screen`() {
        val thumb = ProductionSources.read("ui/chat/ChatComposerWidgets.kt")
            .substringAfter("private fun ToolPreviewThumbnail(")
            .substringBefore("internal object FloatingToolBarMetrics")
        assertTrue(thumb.contains(".background(Color(0xFF1F1F24))"))
        assertFalse(thumb.contains("ChatColors.secondaryBg"))
        assertFalse(thumb.contains("ChatColors.secondaryText"))
        val text = thumb.substringAfter("\"file_read\", \"file_write\" -> {")
            .substringBefore("\"memory_write\", \"memory_get\" -> {")
        assertTrue(text.contains("Color.White.copy(alpha = 0.45f)"))
        assertTrue(text.contains("toolAccent.copy(alpha = 0.85f)"))
    }

    @Test fun `both compact bars share composer glass without duplicate edge or shadow`() {
        val glass = ProductionSources.read("ui/chat/ChatStatusSurface.kt")
        val composer = ProductionSources.read("ui/chat/ChatScreen.kt")
        for (token in listOf("GlassMaterials.puredThinGlass(ChatColors.isDark)",
            "GlassDefaults.style(ChatColors.isDark)", "GlassEdgeStrokes.SmallLight",
            "GlassEdgeStrokes.SmallDark", "shadow = GlassShadows.Regular", "shading = false")) {
            assertTrue(token, glass.contains(token))
            assertTrue(token, composer.contains(token))
        }
        assertFalse(glass.contains("textureBlur("))
        assertFalse(glass.contains("0.72f"))
        for (path in listOf("ui/chat/ChatComposerWidgets.kt", "ui/chat/SubAgentStatusPanel.kt")) {
            val source = ProductionSources.read(path)
            assertTrue(path, source.contains(".chatStatusBackground(backdrop,"))
            assertFalse(path, source.contains(".border(0.5.dp, ChatColors.toolBorder"))
        }
    }

    @Test fun `tool accent colors remain original Minis colors`() {
        assertEquals(Color(0xFF34C759), toolAccentColor("shell_execute"))
        assertEquals(Color(0xFF32ADE6), toolAccentColor("file_read"))
        assertEquals(Color(0xFF007AFF), toolAccentColor("file_write"))
        assertEquals(Color(0xFFFF9500), toolAccentColor("file_edit"))
    }
}
