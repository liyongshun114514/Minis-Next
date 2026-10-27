package com.openminis.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulMetadata

/**
 * The old SOUL settings route now opens the character-card page.
 *
 * Kept as a thin wrapper (rather than renaming the route/symbol) so navigation
 * and every existing call site keep compiling. SOUL.md itself is untouched:
 * its store and prompt assembly still live in the agent layer; this page only
 * replaces the settings surface that used to edit it.
 */
@Composable
fun SoulSettingsScreen(onBack: () -> Unit) {
    CharacterCardsScreen(onBack = onBack)
}

/**
 * [T-android-soul-custom-icon] The icon renderer shared by the chat header,
 * export header and the character-card editor.
 *
 * The signature is deliberately unchanged: callers pass an icon string, a
 * size, an emoji font size and an optional sparkle brush. The bitmap decode is
 * `remember`ed on the icon string — chat headers recompose on every streaming
 * tick, so decoding base64 here would be a per-frame cost.
 */
@Composable
internal fun SoulIconGlyph(
    icon: String,
    sizeDp: Dp,
    emojiSp: TextUnit,
    sparkleTint: Brush? = null,
) {
    val bitmap = remember(icon) { SoulIcon.decode(icon) }
    when {
        // [T-soul-avatar-fill] ContentScale.Crop: the stored image keeps its
        // own aspect ratio, so a non-square one must FILL the frame rather
        // than be letterboxed inside it. The default (Fit) left blank bands on
        // two sides — the "white edges" a square crop used to hide. With Crop
        // the image covers every pixel of the box, the rounded clip below cuts
        // the corners, and .clip() also clamps the overflow a scaled-up image
        // would otherwise paint outside the box.
        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp)
                .clip(RoundedCornerShape(sizeDp * SoulIcon.CORNER_RADIUS_FRACTION)),
        )
        icon.isNotEmpty() -> Text(text = icon, fontSize = emojiSp)
        // Unset: byte-for-byte the previous sparkle, so a user who never
        // touches this sees no visual change at all.
        sparkleTint != null -> Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(sizeDp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = sparkleTint, blendMode = BlendMode.SrcIn)
                },
        )
        else -> Text(text = SoulMetadata.DISPLAY_EMOJI, fontSize = emojiSp)
    }
}
