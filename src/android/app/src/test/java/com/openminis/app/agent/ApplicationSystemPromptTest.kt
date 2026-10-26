package com.openminis.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationSystemPromptTest {
    @Test fun configuredPromptIsInApplicationSystemSection() {
        val prompt = SystemPromptBuilder.render(SoulFile(SoulMetadata.DEFAULT.copy(name = "Nova"), "Respond concisely in Chinese."))
        assertTrue(prompt.contains("You are Nova,"))
        assertTrue(prompt.contains("Application system prompt (configured in Settings → System Prompt):\nRespond concisely in Chinese."))
        assertFalse(prompt.contains("defer to the user's latest message"))
        assertTrue(prompt.contains("Settings → System Prompt (系统提示词)"))
    }

    @Test fun emptyPromptKeepsIdentityAndSettingsGuidance() {
        val prompt = SystemPromptBuilder.render(SoulFile(SoulMetadata.DEFAULT, "  \n"))
        assertTrue(prompt.contains("You are Minis,"))
        assertTrue(prompt.contains("Settings → System Prompt"))
        assertFalse(prompt.contains("Application system prompt (configured"))
    }

    @Test fun legacyMetadataRemainsCompatible() {
        val prompt = SystemPromptBuilder.render(SoulFile(SoulMetadata.DEFAULT.copy(style = "Use short paragraphs.", lang = "zh"), "Explain clearly."))
        assertTrue(prompt.contains("Explain clearly."))
        assertTrue(prompt.contains("Use short paragraphs."))
    }

    @Test fun avatarIsNeverSentAsPromptText() {
        val avatar = "data:image/png;base64,avatar-only"
        val prompt = SystemPromptBuilder.render(SoulFile(SoulMetadata.DEFAULT.copy(icon = avatar), "Use plain language."))
        assertFalse(prompt.contains(avatar))
        assertTrue(prompt.contains("Use plain language."))
    }
}
