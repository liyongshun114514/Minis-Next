package com.openminis.app.ui.chat.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.groups.ChatGroupIdentity
import com.openminis.app.ui.chat.ChatGroupIdentityAvatar
import com.openminis.app.ui.theme.LocalChatPalette
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-multi-select-share] The export's header (iOS ChatExportHeaderView): the
 * assistant's avatar as a ringed circle, the title and "<model> · <date>" under it,
 * the Open Minis icon and name at the right — avatar, title block and brand on one
 * centre line — and a hairline below. Shared by the long image and the PDF.
 */
@Composable
internal fun ChatExportHeaderView(
    title: String,
    subtitle: List<String>,
    widthDp: Float,
    pageColor: Color,
    /**
     * [T-chat-group-identity] Frozen identity of the exported turn (or of the
     * chat the export describes). The header shows the SAME group/model the
     * in-chat reply header shows, so a shared image cannot claim a different
     * speaker than the chat it came from.
     *
     * Null renders the shared fallback glyph — deliberately NOT the per-chat
     * SOUL identity: chat attribution is per message now, and a single global
     * avatar in the header would reintroduce exactly the mislabelling this
     * feature removes.
     */
    identity: ChatGroupIdentity? = null,
) {
    val palette = LocalChatPalette.current
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.width(widthDp.dp).background(pageColor).padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ExportAvatar(identity)
            // Takes the width the brand leaves, so "model · date" wraps only when
            // it truly runs into the brand.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                // Explicit styles, no inherited body tracking / line height, so the
                // three header lines are set alike (iOS: plain system font).
                Text(
                    text = title,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                    color = palette.primaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) SubtitleLine(subtitle, palette.secondaryText)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val context = LocalContext.current
                val icon = remember(context) { appIconBitmap(context) }
                if (icon != null) {
                    // The icon is pale on white: a hairline keeps its edge visible
                    // on a light page.
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(5.5.dp))
                            .border(0.5.dp, palette.separator, RoundedCornerShape(5.5.dp)),
                    )
                }
                // Product name: not localized.
                Text(
                    text = "Open Minis",
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
                    color = palette.primaryText,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.separator)
    }
}

/**
 * "model · date" on one line when it fits; otherwise one part per line, so a narrow
 * page never splits the date itself. Chosen by measuring, not by gluing characters:
 * joiners inside a CJK date make a PDF's text layer map 月 / 日 to look-alike
 * radicals, breaking copy and search (iOS 9c23a4003).
 */
@Composable
private fun SubtitleLine(parts: List<String>, color: Color) {
    val style = TextStyle(fontSize = 13.sp, color = color, letterSpacing = 0.sp)
    val joined = parts.joinToString(" · ")
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val fits = remember(joined, constraints.maxWidth) {
            measurer.measure(joined, style, maxLines = 1, softWrap = false).size.width <= constraints.maxWidth
        }
        if (fits) {
            Text(text = joined, style = style, maxLines = 1, softWrap = false)
        } else {
            Column {
                parts.forEachIndexed { i, part ->
                    Text(text = if (i < parts.lastIndex) "$part ·" else part, style = style)
                }
            }
        }
    }
}

/**
 * [T-chat-group-identity] The export's avatar = the chat header's avatar.
 *
 * Same renderer (`ModelGroupAvatar`) as every in-chat identity surface, at the
 * same 28dp, with no backdrop painted: a transparent custom picture stays
 * transparent on the page instead of picking up a tile behind it.
 */
@Composable
private fun ExportAvatar(identity: ChatGroupIdentity?) {
    ChatGroupIdentityAvatar(
        identity = identity ?: ChatGroupIdentity.NONE,
        size = AVATAR,
    )
}

private val AVATAR = 28.dp

/**
 * The launcher icon as a square picture (the adaptive icon's layers full-bleed, the
 * way the iOS app icon is square), or null when it cannot be loaded.
 */
private fun appIconBitmap(context: Context): ImageBitmap? = runCatching {
    val drawable = context.packageManager.getApplicationIcon(context.packageName)
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (drawable is AdaptiveIconDrawable) {
        // Layers are 108dp with the visible 72dp in the middle: draw them at 1.5x,
        // centred, so the square shows exactly the visible part.
        val inset = size / 4
        for (layer in listOfNotNull(drawable.background, drawable.foreground)) {
            layer.setBounds(-inset, -inset, size + inset, size + inset)
            layer.draw(canvas)
        }
    } else {
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
    }
    bitmap.asImageBitmap()
}.getOrNull()

/** The header's line under the title: the model and the date, either dropped when unknown. */
internal fun chatExportSubtitle(model: String?, dateMs: Long?, locale: Locale = Locale.getDefault()): List<String> {
    val date = dateMs?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it)) }
    return listOfNotNull(model?.trim(), date).filter { it.isNotEmpty() }
}
