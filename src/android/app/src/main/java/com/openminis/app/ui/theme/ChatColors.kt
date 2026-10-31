package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic chat colors derived from the neutral [OmniPalette] tokens, so the
// chat surface and the settings surface cannot drift apart (T-omnibot-ui-port).
//
// Every neutral role now reads its value from the palette table:
//   page / chat background      -> pageBackground
//   card / tool / capsule       -> surfacePrimary / surfaceSecondary
//   text                        -> textPrimary / textSecondary / textTertiary
//   separators + table borders  -> borderSubtle
//   send button + link accent   -> accentPrimary / brandPrimary
// Only the genuinely chat-specific roles (user bubble, code block, warning,
// blockquote, toast, thumbnails) keep hand-picked values.
@Immutable
data class ChatPalette(
    val isDark: Boolean,
    val background: Color,
    val secondaryBg: Color,
    val inputBg: Color,
    val inputIconBg: Color,
    val inputIconBorder: Color,
    val inputBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val disabledText: Color,
    val userBubble: Color,
    val toolBg: Color,
    val toolBorder: Color,
    val toolCapsuleBg: Color,
    val separator: Color,
    val sendButton: Color,
    val sendButtonDisabled: Color,
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val link: Color,
    val blockquoteBar: Color,
    val thinking: Color,
    val warningBg: Color,
    val warningText: Color,
    val tableBorder: Color,
    val inputShadow: Color,
    val toastBg: Color,
    val thumbnailBorder: Color,
    val sheetHeaderBg: Color,
    val sheetHeaderBorder: Color,
    val fabAccent: Color,
)

/**
 * Builds the chat palette from a neutral palette, so `MinisTheme` publishing
 * [LightChatPalette]/[DarkChatPalette] and `LocalOmniPalette` is one decision,
 * not two tables that can silently disagree.
 */
private fun chatPaletteFor(p: OmniPalette): ChatPalette = if (!p.isDark) {
    ChatPalette(
        isDark = false,
        background = p.pageBackground,
        secondaryBg = p.surfaceSecondary,
        inputBg = p.surfacePrimary,
        inputIconBg = p.surfaceSecondary,
        inputIconBorder = Color.Transparent,
        inputBorder = Color(0x4D3C3C43),
        primaryText = p.textPrimary,
        secondaryText = p.textSecondary,
        tertiaryText = p.textTertiary,
        disabledText = p.textTertiary,
        userBubble = Color(0x1E787880),
        toolBg = p.surfaceSecondary,
        toolBorder = p.borderSubtle,
        toolCapsuleBg = p.surfaceSecondary,
        separator = p.borderSubtle,
        sendButton = p.accentPrimary,
        sendButtonDisabled = p.textTertiary,
        codeBlockBg = Color(0xFF000000),
        codeBlockText = Color(0xFF34C759),
        inlineCodeBg = p.surfaceSecondary,
        inlineCodeText = Color(0xFFFF9500),
        link = p.brandPrimary,
        blockquoteBar = Color(0x80FF9500),
        thinking = p.brandPrimary,
        warningBg = Color(0x14FF9500),
        warningText = Color(0x73000000),
        tableBorder = p.borderSubtle,
        inputShadow = Color.Transparent,
        toastBg = Color(0x2E007AFF),
        thumbnailBorder = p.borderStrong,
        sheetHeaderBg = p.surfacePrimary,
        sheetHeaderBorder = p.borderSubtle,
        // Warm beige kept: the iOS New Chat accent, not an Omni token.
        fabAccent = Color(0xFFB7AF96),
    )
} else {
    ChatPalette(
        isDark = true,
        background = p.pageBackground,
        secondaryBg = p.surfaceSecondary,
        inputBg = p.surfacePrimary,
        inputIconBg = p.surfacePrimary,
        inputIconBorder = p.borderStrong,
        inputBorder = Color(0x40545458),
        primaryText = p.textPrimary,
        secondaryText = p.textSecondary,
        tertiaryText = p.textTertiary,
        disabledText = p.textTertiary,
        // [T-android-user-bubble-dark-contrast] The old 0x247676D7 was a 14%-alpha
        // translucent blue-grey that washed out to near-invisible on the #000 chat
        // background on real (≈500-nit) displays. Keep the OPAQUE cool slate-blue so
        // the user's own messages read as a distinct accent; white/f3 text stays
        // legible on it. The new #151515 page does not change that reasoning.
        userBubble = Color(0xFF2F3A5C),
        toolBg = p.surfaceSecondary,
        toolBorder = p.borderSubtle,
        toolCapsuleBg = p.surfacePrimary,
        separator = p.borderSubtle,
        sendButton = p.accentPrimary,
        sendButtonDisabled = p.textTertiary,
        codeBlockBg = Color(0xFF262626),
        codeBlockText = Color(0xFF8CF38C),
        // [T-inline-code-dark-bg-android] Ramp: capsule #202020 < inline #333333 <
        // tool #292929, so a small inline chip still reads against both the wash
        // and fenced blocks.
        inlineCodeBg = p.surfaceElevated,
        inlineCodeText = Color(0xFFFF9F0A),
        link = p.brandPrimary,
        blockquoteBar = Color(0x80FF9F0A),
        thinking = p.brandPrimary,
        warningBg = Color(0x14FF9F0A),
        warningText = Color(0x73FFFFFF),
        tableBorder = p.borderSubtle,
        inputShadow = Color(0x80000000),
        toastBg = Color(0x2E0A84FF),
        thumbnailBorder = p.borderStrong,
        sheetHeaderBg = p.surfaceSecondary,
        sheetHeaderBorder = p.borderSubtle,
        fabAccent = Color(0xFF504C42),
    )
}

val LightChatPalette: ChatPalette = chatPaletteFor(OmniLightPalette)

val DarkChatPalette: ChatPalette = chatPaletteFor(OmniDarkPalette)

val LocalChatPalette = compositionLocalOf { LightChatPalette }

// Short accessor: ChatColors.primaryText instead of LocalChatPalette.current.primaryText
val ChatColors: ChatPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalChatPalette.current
