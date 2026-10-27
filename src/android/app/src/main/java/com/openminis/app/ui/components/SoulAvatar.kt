package com.openminis.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.openminis.app.agent.SoulIcon
import com.openminis.app.agent.SoulMetadata

/**
 * [T-soul-avatar-circle] The ONE renderer for the Soul identity icon: a
 * DECODED IMAGE FILLED INTO A CIRCLE, an emoji glyph, or the default sparkle.
 *
 * Replaces the per-surface copies (`SoulIconGlyph` in
 * `ui/settings/SoulSettingsScreen.kt` and the overlay's ImageView branches),
 * which drew a 22% rounded RECTANGLE. A rounded rectangle on the identity
 * card reads as a "squarish tile" and leaves the frame's margin visible as a
 * ring of background around a picture that was supposed to be the avatar
 * itself. The avatar is a circle on iOS too, and this value syncs, so the two
 * platforms must agree on what the user sees.
 *
 * Two rules make the picture "fill the circle" rather than sit inside it:
 *
 *  - [ContentScale.Crop] — the stored bitmap keeps its own aspect ratio
 *    ([SoulIcon.encode] deliberately does NOT crop on save, so nothing is
 *    destroyed before the user sees the result). Crop scales it up until both
 *    axes cover the box, so a 4:3 photo reaches all four edges of the circle
 *    and loses only its outside bands, instead of being letterboxed with
 *    blank bars on two sides.
 *  - [CircleShape] clip on the IMAGE itself, with `Modifier.size()` and NO
 *    padding. The clip cuts the overflowing corners to the circle; padding or
 *    an inset size would leave a transparent ring between the artwork and the
 *    frame edge, which is the "transparent padding" this replaces.
 *
 * The bitmap decode is `remember`ed on the icon string: this composable is
 * called from the chat header and the assistant message row, both of which
 * recompose on every streaming tick, and re-decoding base64 there would be
 * pure waste.
 *
 * @param icon the stored `SoulMetadata.icon` value (data URI, emoji, or "").
 * @param sizeDp diameter of the circle, in dp.
 * @param emojiSp font size for the emoji / default-glyph branches.
 * @param sparkleTint gradient applied to the default sparkle when the Soul
 *   has no icon; null keeps a plain tinted icon.
 */
@Composable
fun SoulAvatarGlyph(
    icon: String,
    sizeDp: Dp,
    emojiSp: TextUnit,
    sparkleTint: Brush? = null,
    modifier: Modifier = Modifier,
) {
    val bitmap: Bitmap? = remember(icon) { SoulIcon.decode(icon) }
    val image: ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }
    when {
        image != null -> Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(sizeDp)
                .clip(CircleShape),
        )
        icon.isNotEmpty() -> Text(
            text = icon,
            fontSize = emojiSp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )
        // Unset: byte-for-byte the previous sparkle, so a user who never
        // touches the icon sees no visual change beyond the frame shape.
        sparkleTint != null -> Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Color.White,
            modifier = modifier
                .size(sizeDp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = sparkleTint, blendMode = BlendMode.SrcIn)
                },
        )
        else -> Text(
            text = SoulMetadata.DISPLAY_EMOJI,
            fontSize = emojiSp,
            textAlign = TextAlign.Center,
            modifier = modifier,
        )
    }
}
