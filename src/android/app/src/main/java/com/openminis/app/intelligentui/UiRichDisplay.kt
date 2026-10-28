package com.openminis.app.intelligentui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Native renderer for the "rich display" subset of the Intelligent UI dispatch protocol.
 *
 * The calling layer (`UiNode`/dispatch) owns the node title/text for every type except
 * [card], which lays those fields out inside its own card surface. Card titles/text
 * therefore must not be rendered twice by the caller.
 *
 * This is a fixed native catalog: no HTML, JavaScript, remote code, local files or
 * non-HTTPS media is interpreted here. URL schemes are policed by the parser; this
 * file additionally refuses to hand non-HTTPS URIs to VideoView / ACTION_VIEW.
 */
@Composable
internal fun UiRichDisplay(n: JSONObject) {
    when (val type = n.optString("type")) {
        "card" -> RichCard(n)
        "image" -> RichImageNode(n)
        "gallery" -> RichGallery(n)
        "video" -> RichVideo(n)
        "alert" -> RichAlert(n)
        "progress" -> RichProgress(n)
        "chart" -> RichChart(n)
        "trend" -> RichChart(trendAsChart(n))
        "before_after" -> RichBeforeAfter(n)
        else -> Text(
            text = UiRichLocale.t("不支持的展示类型：", "Unsupported display: ") + type,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("ui-rich-unsupported"),
        )
    }
}

internal object UiRichLocale {
    val isChinese: Boolean get() = Locale.getDefault().language.startsWith("zh")

    fun t(zh: String, en: String): String = if (isChinese) zh else en
}

/**
 * Test seam: production always renders through Coil; JVM tests can provide a local,
 * network-free image surface so rendering assertions never touch the network.
 */
internal val LocalUiRichImageOverride: ProvidableCompositionLocal<(@Composable (String, String?, Modifier) -> Unit)?> =
    compositionLocalOf { null }

@Composable
private fun RichImage(url: String, description: String?, modifier: Modifier) {
    val override = LocalUiRichImageOverride.current
    if (override != null) {
        override(url, description, modifier)
    } else {
        var failed by remember(url) { mutableStateOf(false) }
        Box(modifier, contentAlignment = Alignment.Center) {
            AsyncImage(
                model = url,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
                onSuccess = { failed = false },
            )
            if (failed) Text(UiRichLocale.t("图片加载失败", "Image unavailable"),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(8.dp).testTag("ui-rich-image-error"))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// card
// ---------------------------------------------------------------------------------------------

private fun variantAccent(variant: String): Color = when (variant) {
    "success" -> Color(0xFF2E7D32)
    "warning" -> Color(0xFFEF6C00)
    "error" -> Color(0xFFC62828)
    "product" -> Color(0xFF1565C0)
    "person" -> Color(0xFF00838F)
    "place" -> Color(0xFF00695C)
    "summary" -> Color(0xFF5E35B1)
    "recommendation" -> Color(0xFFAD1457)
    else -> Color(0xFF3F51B5)
}

private fun cardIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector? = when (name.lowercase(Locale.ROOT)) {
    "info" -> Icons.Outlined.Info
    "success", "check", "checkcircle" -> Icons.Outlined.CheckCircle
    "warning", "warn" -> Icons.Outlined.Warning
    "error", "danger" -> Icons.Outlined.ErrorOutline
    "person", "user", "profile" -> Icons.Outlined.Person
    "place", "location", "map" -> Icons.Outlined.Place
    "product", "shopping", "cart" -> Icons.Outlined.ShoppingBag
    "star", "favorite", "recommend" -> Icons.Outlined.Star
    "summary", "document", "text" -> Icons.Outlined.Description
    "image", "photo" -> Icons.Outlined.Image
    "chart", "analytics", "data" -> Icons.Outlined.BarChart
    "link", "url" -> Icons.Outlined.Link
    "time", "schedule", "clock" -> Icons.Outlined.Schedule
    "money", "payment", "price" -> Icons.Outlined.Payments
    "calendar", "date", "event" -> Icons.Outlined.CalendarMonth
    "mail", "email" -> Icons.Outlined.Mail
    "phone", "call" -> Icons.Outlined.Phone
    "home", "house" -> Icons.Outlined.Home
    "notification", "bell" -> Icons.Outlined.Notifications
    "search" -> Icons.Outlined.Search
    "settings" -> Icons.Outlined.Settings
    "lock", "secure" -> Icons.Outlined.Lock
    "cloud" -> Icons.Outlined.Cloud
    "folder" -> Icons.Outlined.Folder
    "help" -> Icons.Outlined.HelpOutline
    "flag" -> Icons.Outlined.Flag
    else -> null
}

@Composable
private fun RichCard(n: JSONObject) {
    val variant = n.optString("variant", "info").lowercase(Locale.ROOT)
    val accent = variantAccent(variant)
    val dark = isSystemInDarkTheme()
    val title = n.optString("title").take(200)
    val text = n.optString("text").take(4000)
    val details = n.optString("details").take(4000)
    val value = n.optString("value").take(200)
    val unit = n.optString("unit").take(80)
    val image = n.optString("image").takeIf { it.startsWith("https://") }
    val horizontal = n.optBoolean("horizontal")
    val expandable = n.optBoolean("expandable") && details.isNotBlank()
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ui-rich-card-$variant"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (dark) accent.copy(alpha = 0.14f) else accent.copy(alpha = 0.06f),
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.32f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (horizontal && image != null) {
                    RichImage(
                        url = image,
                        description = title.ifBlank { null },
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RichCardIcon(n.optString("icon"), title, accent)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (title.isNotBlank()) {
                                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            if (value.isNotBlank()) {
                                Text(
                                    value + if (unit.isBlank()) "" else " $unit",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = accent,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (image != null && !horizontal) {
                RichImage(
                    url = image,
                    description = title.ifBlank { null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
            if (details.isNotBlank() && (!expandable || expanded)) {
                HorizontalDivider(color = accent.copy(alpha = 0.2f))
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("ui-rich-card-details"),
                )
            }
            if (expandable) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("ui-rich-card-toggle"),
                ) {
                    Text(
                        if (expanded) UiRichLocale.t("收起详情", "Hide details")
                        else UiRichLocale.t("展开详情", "Show details"),
                    )
                }
            }
        }
    }
}

@Composable
private fun RichCardIcon(raw: String, title: String, accent: Color) {
    val icon = cardIcon(raw)
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        } else {
            val badge = raw.trim().take(2).ifBlank { title.trim().take(1) }.uppercase(Locale.getDefault())
            Text(
                badge.ifBlank { "i" },
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("ui-rich-card-badge"),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// image / gallery
// ---------------------------------------------------------------------------------------------

private data class RichImageItem(val url: String, val caption: String, val description: String)

private fun parseGallery(n: JSONObject, maxItems: Int = 24): List<RichImageItem> {
    val array = n.optJSONArray("images") ?: return emptyList()
    return (0 until min(array.length(), maxItems)).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val url = o.optString("url")
        if (!url.startsWith("https://")) null
        else RichImageItem(url, o.optString("caption").take(200), o.optString("description").take(1000))
    }
}

@Composable
private fun RichImageNode(n: JSONObject) {
    val url = n.optString("url")
    if (!url.startsWith("https://")) {
        Text(
            UiRichLocale.t("图片地址必须是 HTTPS", "Image URL must be HTTPS"),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("ui-rich-image-invalid"),
        )
        return
    }
    val caption = n.optString("caption").take(200)
    val description = n.optString("description").take(1000)
    Column(Modifier.fillMaxWidth().testTag("ui-rich-image"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RichImage(
            url = url,
            description = caption.ifBlank { description.ifBlank { UiRichLocale.t("图片", "Image") } },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 320.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        if (caption.isNotBlank()) {
            Text(caption, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("ui-rich-image-caption"))
        }
        if (description.isNotBlank()) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RichGallery(n: JSONObject) {
    val items = remember(n.toString()) { parseGallery(n) }
    val style = n.optString("style", "carousel")
    if (items.isEmpty()) {
        Text(
            UiRichLocale.t("没有可显示的图片", "No images to display"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("ui-rich-gallery-empty"),
        )
        return
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val safe = index.coerceIn(0, items.lastIndex)
    if (style == "grid") {
        RichGalleryGrid(items) { index = it }
    } else {
        RichGalleryCarousel(items, safe, onSelect = { index = it })
    }
}

@Composable
private fun RichGalleryCarousel(items: List<RichImageItem>, index: Int, onSelect: (Int) -> Unit) {
    val current = items[index]
    Column(Modifier.fillMaxWidth().testTag("ui-rich-gallery"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            RichImage(
                url = current.url,
                description = current.caption.ifBlank { UiRichLocale.t("画廊图片", "Gallery image") },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onSelect((index - 1).coerceAtLeast(0)) },
                enabled = index > 0,
                modifier = Modifier.testTag("ui-rich-gallery-prev"),
            ) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = UiRichLocale.t("上一张", "Previous image"))
            }
            Text(
                "${index + 1} / ${items.size}",
                modifier = Modifier.weight(1f).testTag("ui-rich-gallery-counter"),
                style = MaterialTheme.typography.labelMedium,
            )
            IconButton(
                onClick = { onSelect((index + 1).coerceAtMost(items.lastIndex)) },
                enabled = index < items.lastIndex,
                modifier = Modifier.testTag("ui-rich-gallery-next"),
            ) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = UiRichLocale.t("下一张", "Next image"))
            }
        }
        if (current.caption.isNotBlank()) {
            Text(current.caption, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("ui-rich-gallery-caption"))
        }
        if (current.description.isNotBlank()) {
            Text(
                current.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ui-rich-gallery-description"),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEachIndexed { i, item ->
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = if (i == index) 2.dp else 1.dp,
                            color = if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { onSelect(i) }
                        .testTag("ui-rich-gallery-thumb-$i"),
                ) {
                    RichImage(item.url, item.caption.ifBlank { null }, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun RichGalleryGrid(items: List<RichImageItem>, onSelect: (Int) -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxWidth().testTag("ui-rich-gallery-grid"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.withIndex().chunked(2).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { (i, item) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onSelect(i); selected = i },
                    ) {
                        RichImage(
                            item.url,
                            item.caption.ifBlank { null },
                            Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        if (item.caption.isNotBlank()) {
                            Text(item.caption, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (selected in items.indices && items[selected].description.isNotBlank()) {
            Text(
                items[selected].description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ui-rich-gallery-grid-description"),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// video (native VideoView, never WebView, never autoplay)
// ---------------------------------------------------------------------------------------------

@Composable
private fun RichVideo(n: JSONObject) {
    val url = n.optString("url")
    val poster = n.optString("poster").takeIf { it.startsWith("https://") }
    val caption = n.optString("caption").take(200)
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // Deliberately not rememberSaveable: returning to the screen must never auto-resume
    // playback. Playback only starts from a real tap.
    var playing by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(false) }
    var externalError by rememberSaveable { mutableStateOf(false) }
    var videoRef by remember { mutableStateOf<VideoView?>(null) }
    val https = url.startsWith("https://")

    fun openExternally() {
        if (!https) {
            externalError = true
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            externalError = false
        } catch (_: ActivityNotFoundException) {
            externalError = true
        } catch (_: SecurityException) {
            externalError = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                videoRef?.stopPlayback()
                playing = false
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                videoRef?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            videoRef?.stopPlayback()
            videoRef = null
        }
    }

    Column(Modifier.fillMaxWidth().testTag("ui-rich-video"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .testTag("ui-rich-video-stage"),
        ) {
            if (playing && https) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).also { view ->
                            videoRef = view
                            view.setOnErrorListener { _, _, _ ->
                                error = true
                                playing = false
                                true
                            }
                            view.setOnCompletionListener { playing = false }
                            runCatching {
                                view.setVideoURI(Uri.parse(url))
                                view.setOnPreparedListener { mp ->
                                    mp.isLooping = false
                                    view.start()
                                }
                            }.onFailure {
                                error = true
                                playing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                if (poster != null) {
                    RichImage(poster, caption.ifBlank { null }, Modifier.fillMaxSize())
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        // Click-to-play only; no autoplay.
                        .clickable(enabled = https) { playing = true }
                        .testTag("ui-rich-video-play"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.PlayCircleOutline,
                            contentDescription = UiRichLocale.t("播放视频", "Play video"),
                            tint = Color.White,
                            modifier = Modifier.size(52.dp),
                        )
                        Text(
                            if (https) UiRichLocale.t("点击播放", "Tap to play")
                            else UiRichLocale.t("仅支持 HTTPS 视频", "HTTPS video required"),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        if (playing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    UiRichLocale.t("正在使用原生播放器", "Playing in native player"),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(
                    onClick = {
                        videoRef?.stopPlayback()
                        playing = false
                    },
                    modifier = Modifier.testTag("ui-rich-video-stop"),
                ) { Text(UiRichLocale.t("停止", "Stop")) }
            }
        }
        if (error) {
            Text(
                UiRichLocale.t("视频无法在应用内播放，可在外部播放器中打开。", "This video could not be played here. You can open it in an external player."),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ui-rich-video-error"),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (caption.isNotBlank()) {
                Text(caption, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            } else Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { openExternally() },
                modifier = Modifier.testTag("ui-rich-video-external"),
            ) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(UiRichLocale.t("外部播放器", "Open externally"))
            }
        }
        if (externalError) {
            Text(
                UiRichLocale.t("没有可处理该视频的应用，或链接无效。", "No app can open this video, or the link is invalid."),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ui-rich-video-external-error"),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// alert / progress
// ---------------------------------------------------------------------------------------------

@Composable
private fun RichAlert(n: JSONObject) {
    val status = n.optString("status", "info").lowercase(Locale.ROOT)
    val accent = when (status) {
        "success" -> Color(0xFF2E7D32)
        "warning" -> Color(0xFFEF6C00)
        "error" -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.primary
    }
    val dark = isSystemInDarkTheme()
    val icon = when (status) {
        "success" -> Icons.Outlined.CheckCircle
        "warning" -> Icons.Outlined.Warning
        "error" -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.Info
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = if (dark) 0.16f else 0.08f))
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp)
            .testTag("ui-rich-alert-$status"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val title = n.optString("title").take(200)
            if (title.isNotBlank()) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(n.optString("text").take(4000), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RichProgress(n: JSONObject) {
    val value = n.optDouble("value", 0.0).let { if (it.isFinite()) it else 0.0 }.coerceIn(0.0, 1.0).toFloat()
    val label = n.optString("label").take(200)
    val unit = n.optString("unit").take(80)
    val percent = (value * 100).roundToInt()
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("ui-rich-progress")
            .semantics { contentDescription = "${percent}% ${label} $unit".trim() },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (label.isNotBlank()) Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            else Spacer(Modifier.weight(1f))
            Text(
                "$percent%" + if (unit.isBlank()) "" else " · $unit",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("ui-rich-progress-value"),
            )
        }
        LinearProgressIndicator(
            progress = { value },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// chart / trend / before_after
// ---------------------------------------------------------------------------------------------

private data class RichSeries(val name: String, val values: List<Double>)

private data class RichChartData(
    val labels: List<String>,
    val values: List<Double>,
    val style: String,
    val unit: String,
    val xValues: List<Double>?,
    val series: List<RichSeries>,
    val xLabel: String,
    val yLabel: String,
) {
    val seriesCount: Int get() = if (series.isEmpty()) 1 else series.size

    fun primaryValues(): List<Double> = series.firstOrNull()?.values ?: values
}

private val richChartStyles = setOf("bar", "line", "pie", "scatter", "histogram", "ranking")

private val richPalette = listOf(
    Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFFE15759),
    Color(0xFF76B7B2), Color(0xFF59A14F), Color(0xFFEDC948),
    Color(0xFFB07AA1), Color(0xFF9C755F),
)

private fun parseRichChart(n: JSONObject): RichChartData {
    val rawLabels = n.optJSONArray("labels")
    val rawValues = n.optJSONArray("values")
    val count = rawValues?.length() ?: rawLabels?.length() ?: 0
    val labels = (0 until count).map { i ->
        rawLabels?.optString(i)?.takeIf { it.isNotBlank() } ?: (i + 1).toString()
    }
    val values = (0 until count).map { i -> rawValues?.optDouble(i, 0.0) ?: 0.0 }
    val series = mutableListOf<RichSeries>()
    val array = n.optJSONArray("series")
    if (array != null) {
        for (s in 0 until min(array.length(), 6)) {
            val o = array.optJSONObject(s) ?: continue
            val vs = o.optJSONArray("values") ?: continue
            if (vs.length() != count) continue
            series += RichSeries(
                o.optString("name").take(60).ifBlank { "S${s + 1}" },
                (0 until count).map { vs.optDouble(it, 0.0) },
            )
        }
    }
    val xArray = n.optJSONArray("xValues")
    val xs = if (xArray != null && xArray.length() == count) {
        (0 until count).map { xArray.optDouble(it, it.toDouble()) }
    } else null
    return RichChartData(
        labels = labels,
        values = values,
        style = n.optString("style", "bar").lowercase(Locale.ROOT).takeIf { it in richChartStyles } ?: "bar",
        unit = n.optString("unit").take(80),
        xValues = xs,
        series = series,
        xLabel = n.optString("xLabel").take(120),
        yLabel = n.optString("yLabel").take(120),
    )
}

private fun trendAsChart(n: JSONObject): JSONObject = JSONObject().apply {
    put("labels", n.optJSONArray("labels") ?: JSONArray())
    put("values", n.optJSONArray("values") ?: JSONArray())
    put("style", "line")
    if (n.has("unit")) put("unit", n.optString("unit"))
}

@Composable
private fun RichBeforeAfter(n: JSONObject) {
    val labels = n.optJSONArray("labels") ?: JSONArray()
    val before = n.optJSONArray("before") ?: JSONArray()
    val after = n.optJSONArray("after") ?: JSONArray()
    val unit = n.optString("unit").take(80)
    val series = JSONArray()
        .put(JSONObject().put("name", UiRichLocale.t("之前", "Before")).put("values", before))
        .put(JSONObject().put("name", UiRichLocale.t("之后", "After")).put("values", after))
    val chart = JSONObject()
        .put("labels", labels)
        .put("style", "bar")
        .put("series", series)
        .apply { if (unit.isNotBlank()) put("unit", unit) }

    Column(Modifier.fillMaxWidth().testTag("ui-rich-before-after"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RichChart(chart)
        val count = min(min(labels.length(), before.length()), after.length()).coerceAtMost(32)
        if (count > 0) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("ui-rich-before-after-list"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(count) { i ->
                    val b = before.optDouble(i, 0.0)
                    val a = after.optDouble(i, 0.0)
                    val delta = a - b
                    val pct = if (abs(b) > 1e-9) delta / abs(b) * 100 else null
                    val pctText = pct?.let { (if (it >= 0) "+" else "") + richNumber(it) + "%" } ?: "—"
                    Text(
                        "${labels.optString(i, (i + 1).toString())}: ${richNumber(b)}${if (unit.isBlank()) "" else " $unit"} → " +
                            "${richNumber(a)}${if (unit.isBlank()) "" else " $unit"} ($pctText)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("ui-rich-before-after-$i"),
                    )
                }
            }
        }
    }
}

@Composable
private fun RichChart(n: JSONObject) {
    val data = remember(n.toString()) { parseRichChart(n) }
    if (data.labels.isEmpty()) {
        Text(
            UiRichLocale.t("没有图表数据", "No chart data"),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("ui-rich-chart-empty"),
        )
        return
    }
    var hiddenCsv by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val hidden = hiddenCsv.split(',').mapNotNull { it.toIntOrNull() }.toSet()
    val visibleSeries = if (data.series.isEmpty()) {
        listOf(RichSeries("", data.values))
    } else {
        data.series.filterIndexed { i, _ -> i !in hidden }
    }
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val style = data.style
    val multi = data.series.isNotEmpty()
    val description = remember(data, hiddenCsv) { chartDescription(data, visibleSeries) }

    Column(Modifier.fillMaxWidth().testTag("ui-rich-chart-$style"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (visibleSeries.isEmpty()) {
            Text(
                UiRichLocale.t("所有数据系列已隐藏", "All series are hidden"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ui-rich-chart-no-series"),
            )
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .testTag("ui-rich-chart-canvas")
                    .semantics { contentDescription = description }
                    .pointerInput(data, hiddenCsv) {
                        detectTapGestures { tap ->
                            val left = 46.dp.toPx()
                            val right = 10.dp.toPx()
                            val top = 14.dp.toPx()
                            val bottom = 30.dp.toPx()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            selected = pickChartIndex(style, tap, w, h, data, visibleSeries, left, right, top, bottom, 12.dp.toPx())
                        }
                    },
            ) {
                drawRichChart(data, visibleSeries, selected, axisColor, gridColor, highlight)
            }
        }

        if (multi) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                data.series.forEachIndexed { i, s ->
                    FilterChip(
                        selected = i !in hidden,
                        onClick = {
                            hiddenCsv = if (i in hidden) (hidden - i).sorted().joinToString(",")
                            else (hidden + i).sorted().joinToString(",")
                        },
                        label = { Text(s.name) },
                        modifier = Modifier.testTag("ui-rich-series-$i"),
                    )
                }
            }
        }

        val axisParts = buildList {
            if (data.unit.isNotBlank()) add(UiRichLocale.t("单位", "Unit") + ": " + data.unit)
            if (data.xLabel.isNotBlank()) add("X: " + data.xLabel)
            if (data.yLabel.isNotBlank()) add("Y: " + data.yLabel)
        }
        if (axisParts.isNotEmpty()) {
            Text(
                axisParts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ui-rich-chart-axis"),
            )
        }

        if (selected in data.labels.indices && visibleSeries.isNotEmpty()) {
            Text(
                UiRichLocale.t("已选", "Selected") + ": " + selectedRow(data, visibleSeries, selected),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("ui-rich-chart-selected"),
            )
        }

        if (style == "pie" && visibleSeries.isNotEmpty()) {
            RichPieLegend(data, visibleSeries.first())
        }

        // Exact values stay readable on narrow screens and for screen readers, without a
        // LazyColumn inside a scrolling card (which would need an infinite height).
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState())
                .testTag("ui-rich-chart-values"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            data.labels.indices.forEach { i ->
                Text(
                    selectedRow(data, visibleSeries, i),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("ui-rich-chart-value-$i"),
                )
            }
        }
    }
}

@Composable
private fun RichPieLegend(data: RichChartData, series: RichSeries) {
    val total = series.values.sumOf { max(it, 0.0) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 150.dp)
            .verticalScroll(rememberScrollState())
            .testTag("ui-rich-chart-legend"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        data.labels.indices.forEach { i ->
            val v = series.values.getOrElse(i) { 0.0 }
            val pct = if (total > 0) v / total * 100 else 0.0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(richPalette[i % richPalette.size]),
                )
                Text(
                    "${data.labels[i]}  ${richNumber(v)}${if (data.unit.isBlank()) "" else " " + data.unit}  ${richPercent(pct)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("ui-rich-chart-legend-$i"),
                )
            }
        }
    }
}

private fun chartDescription(data: RichChartData, visible: List<RichSeries>): String {
    val unit = if (data.unit.isBlank()) "" else " ${data.unit}"
    val head = UiRichLocale.t("图表", "Chart") + " " + data.style + ". "
    val axes = buildList {
        if (data.xLabel.isNotBlank()) add("X ${data.xLabel}")
        if (data.yLabel.isNotBlank()) add("Y ${data.yLabel}")
        if (data.unit.isNotBlank()) add(UiRichLocale.t("单位 ", "unit ") + data.unit)
    }.joinToString(", ")
    val rows = data.labels.indices.take(24).joinToString("; ") { i ->
        val values = visible.joinToString(", ") { s ->
            (if (s.name.isBlank()) "" else s.name + " ") + richNumber(s.values.getOrElse(i) { 0.0 }) + unit
        }
        data.labels[i] + ": " + values
    }
    val more = if (data.labels.size > 24) " +${data.labels.size - 24}" else ""
    return (head + axes + ". " + rows + more).take(2000)
}

private fun selectedRow(data: RichChartData, visible: List<RichSeries>, i: Int): String {
    val unit = if (data.unit.isBlank()) "" else " " + data.unit
    val parts = visible.joinToString(" · ") { s ->
        val prefix = if (s.name.isBlank()) "" else s.name + " "
        prefix + richNumber(s.values.getOrElse(i) { 0.0 }) + unit
    }
    return if (data.style == "pie") {
        val total = visible.firstOrNull()?.values?.sumOf { max(it, 0.0) } ?: 0.0
        val v = visible.firstOrNull()?.values?.getOrElse(i) { 0.0 } ?: 0.0
        val pct = if (total > 0) v / total * 100 else 0.0
        data.labels[i] + " · " + parts + " · " + richPercent(pct)
    } else {
        data.labels[i] + " · " + parts
    }
}

private fun pickChartIndex(
    style: String,
    tap: Offset,
    width: Float,
    height: Float,
    data: RichChartData,
    visible: List<RichSeries>,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    piePadding: Float,
): Int {
    val count = data.labels.size
    if (count == 0) return -1
    if (style == "pie") {
        val values = visible.firstOrNull()?.values ?: return -1
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - piePadding
        val dx = tap.x - cx
        val dy = tap.y - cy
        if (sqrt(dx * dx + dy * dy) > radius) return -1
        val total = values.sumOf { max(it, 0.0) }
        if (total <= 0) return -1
        var angle = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / Math.PI + 90.0 + 360.0) % 360.0
        var acc = 0.0
        values.forEachIndexed { i, v ->
            val sweep = max(v, 0.0) / total * 360.0
            if (angle >= acc && angle < acc + sweep) return i
            acc += sweep
        }
        return (count - 1).coerceAtLeast(0)
    }
    if (style == "ranking") {
        val idx = floor((tap.y - top) / ((height - top - bottom).coerceAtLeast(1f)) * count).toInt()
        val order = visible.firstOrNull()?.values?.indices?.sortedByDescending { visible.first().values[it] } ?: return -1
        return order[idx.coerceIn(0, count - 1)]
    }
    if ((style == "line" || style == "scatter") && data.xValues != null) {
        val xs = data.xValues
        val minX = xs.minOrNull() ?: 0.0
        val maxX = xs.maxOrNull() ?: 1.0
        val span = (maxX - minX).takeIf { abs(it) > 1e-12 } ?: 1.0
        val plotW = (width - left - right).coerceAtLeast(1f)
        return xs.indices.minByOrNull { i ->
            val px = left + plotW * ((xs[i] - minX) / span).toFloat()
            abs(px - tap.x)
        } ?: 0
    }
    val plotW = (width - left - right).coerceAtLeast(1f)
    val slot = plotW / count
    return floor((tap.x - left) / slot).toInt().coerceIn(0, count - 1)
}

private fun DrawScope.drawRichChart(
    data: RichChartData,
    visible: List<RichSeries>,
    selected: Int,
    axisColor: Color,
    gridColor: Color,
    highlight: Color,
) {
    val count = data.labels.size
    val left = 46.dp.toPx()
    val right = 10.dp.toPx()
    val top = 14.dp.toPx()
    val bottom = 30.dp.toPx()
    val width = size.width
    val height = size.height
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.sp.toPx()
        color = axisColor.toArgb()
    }

    fun labelText(text: String, x: Float, y: Float, align: Paint.Align, color: Color = axisColor) {
        paint.textAlign = align
        paint.color = color.toArgb()
        drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
    }

    if (data.style == "pie") {
        val series = visible.first()
        val total = series.values.sumOf { max(it, 0.0) }
        if (total <= 0) {
            labelText(UiRichLocale.t("没有可显示的正数值", "No non-negative data"), width / 2, height / 2, Paint.Align.CENTER)
            return
        }
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 12.dp.toPx()
        val arcSize = Size(radius * 2, radius * 2)
        var start = -90f
        val gap = if (count > 1) 1.2f else 0f
        series.values.forEachIndexed { i, raw ->
            val v = max(raw, 0.0)
            val sweep = (v / total * 360.0).toFloat()
            val color = richPalette[i % richPalette.size]
            drawArc(
                color = if (selected == i) color else color.copy(alpha = 0.88f),
                startAngle = start,
                sweepAngle = max(sweep - gap, 0.5f),
                useCenter = true,
                topLeft = Offset(cx - radius, cy - radius),
                size = arcSize,
            )
            start += sweep
        }
        // Centre cut-out makes exact legend values easier to associate with slices.
        drawCircle(color = Color.White.copy(alpha = 0.0f), radius = 0f, center = Offset(cx, cy))
        return
    }

    if (data.style == "ranking") {
        val series = visible.first()
        val order = series.values.indices.sortedByDescending { series.values[it] }
        val maxValue = (order.maxOfOrNull { abs(series.values[it]) } ?: 0.0).coerceAtLeast(1e-9)
        val labelW = min(84.dp.toPx(), width * 0.36f)
        val barStart = labelW + 4.dp.toPx()
        val barMax = (width - right - 44.dp.toPx() - barStart).coerceAtLeast(8f)
        val rowH = ((height - top - bottom) / count).coerceAtLeast(1f)
        order.forEachIndexed { rank, i ->
            val rowTop = top + rowH * rank
            if (selected == i) drawRect(highlight, Offset(0f, rowTop), Size(width, rowH))
            val v = series.values[i]
            val barW = (barMax * (abs(v) / maxValue)).toFloat().coerceIn(0f, barMax)
            drawRoundRect(
                color = richPalette[0],
                topLeft = Offset(barStart, rowTop + rowH * 0.24f),
                size = Size(max(barW, 2f), rowH * 0.52f),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
            labelText(truncate(data.labels[i], 10), 2.dp.toPx(), rowTop + rowH * 0.65f, Paint.Align.LEFT)
            labelText(richNumber(v) + if (data.unit.isBlank()) "" else " " + data.unit, width - 2.dp.toPx(), rowTop + rowH * 0.65f, Paint.Align.RIGHT)
        }
        return
    }

    val all = visible.flatMap { it.values }.ifEmpty { listOf(0.0) }
    var lo = min(0.0, all.min())
    var hi = max(0.0, all.max())
    if (hi - lo < 1e-9) hi = lo + 1.0
    val plotW = (width - left - right).coerceAtLeast(1f)
    val plotH = (height - top - bottom).coerceAtLeast(1f)

    fun y(v: Double): Float = top + (plotH * (1.0 - (v - lo) / (hi - lo))).toFloat()
    fun categoryX(i: Int): Float = left + plotW * (i + 0.5f) / count
    val xs = data.xValues
    val minX = xs?.minOrNull() ?: 0.0
    val maxX = xs?.maxOrNull() ?: 1.0
    val spanX = (maxX - minX).takeIf { abs(it) > 1e-12 } ?: 1.0
    fun valueX(v: Double): Float = left + plotW * ((v - minX) / spanX).toFloat()
    fun pointX(i: Int): Float = if ((data.style == "line" || data.style == "scatter") && xs != null) valueX(xs[i]) else categoryX(i)

    // Y ticks, grid and axis.
    repeat(5) { t ->
        val v = lo + (hi - lo) * t / 4.0
        val py = y(v)
        drawLine(gridColor.copy(alpha = 0.55f), Offset(left, py), Offset(width - right, py), 1f)
        labelText(richNumber(v), left - 4.dp.toPx(), py + 3.dp.toPx(), Paint.Align.RIGHT)
    }
    drawLine(axisColor.copy(alpha = 0.6f), Offset(left, top), Offset(left, top + plotH), 1f)
    drawLine(axisColor.copy(alpha = 0.6f), Offset(left, top + plotH), Offset(width - right, top + plotH), 1f)

    // X ticks: numeric for scatter/line with xValues, category labels otherwise.
    if ((data.style == "line" || data.style == "scatter") && xs != null) {
        repeat(5) { t ->
            val v = minX + spanX * t / 4.0
            val px = valueX(v)
            labelText(richNumber(v), px, top + plotH + 14.dp.toPx(), Paint.Align.CENTER)
        }
    } else {
        val step = ceil(count / 6.0).toInt().coerceAtLeast(1)
        var i = 0
        while (i < count) {
            labelText(truncate(data.labels[i], 8), categoryX(i), top + plotH + 14.dp.toPx(), Paint.Align.CENTER)
            i += step
        }
    }
    if (data.xLabel.isNotBlank()) {
        labelText(truncate(data.xLabel, 16), width - right, height - 2.dp.toPx(), Paint.Align.RIGHT)
    }
    if (data.yLabel.isNotBlank()) {
        labelText(truncate(data.yLabel, 14), 2.dp.toPx(), 10.dp.toPx(), Paint.Align.LEFT)
    }

    val slot = plotW / count
    if (selected in 0 until count) {
        drawRect(highlight, Offset(left + slot * selected, top), Size(slot, plotH))
    }

    when (data.style) {
        "line" -> visible.forEachIndexed { si, s ->
            val color = richPalette[si % richPalette.size]
            val path = Path()
            var last = Offset.Zero
            s.values.indices.forEach { i ->
                val p = Offset(pointX(i), y(s.values[i]))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                last = p
            }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            s.values.indices.forEach { i ->
                val p = Offset(pointX(i), y(s.values[i]))
                drawCircle(color, if (selected == i) 5.dp.toPx() else 3.dp.toPx(), p)
            }
            if (selected in s.values.indices && s.values.isNotEmpty()) {
                drawCircle(Color.White, 2.dp.toPx(), last)
            }
        }
        "scatter" -> visible.forEachIndexed { si, s ->
            val color = richPalette[si % richPalette.size]
            s.values.indices.forEach { i ->
                drawCircle(color, if (selected == i) 6.dp.toPx() else 4.dp.toPx(), Offset(pointX(i), y(s.values[i])))
                if (selected == i) drawCircle(axisColor, 1.5.dp.toPx(), Offset(pointX(i), y(s.values[i])), style = Stroke(1.5.dp.toPx()))
            }
        }
        "histogram" -> {
            val seriesCount = visible.size.coerceAtLeast(1)
            val binW = slot / seriesCount
            visible.forEachIndexed { si, s ->
                val color = richPalette[si % richPalette.size]
                s.values.indices.forEach { i ->
                    val base = y(0.0)
                    val py = y(s.values[i])
                    drawRect(
                        color,
                        Offset(left + slot * i + binW * si, min(base, py)),
                        Size(max(binW - 1f, 1f), max(abs(base - py), 1f)),
                    )
                }
            }
        }
        else -> {
            // bar (default): grouped bars for multi-series comparison.
            val seriesCount = visible.size.coerceAtLeast(1)
            val group = slot * 0.8f
            val barW = group / seriesCount
            visible.forEachIndexed { si, s ->
                val color = richPalette[si % richPalette.size]
                s.values.indices.forEach { i ->
                    val base = y(0.0)
                    val py = y(s.values[i])
                    val x = left + slot * i + slot * 0.1f + barW * si
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x, min(base, py)),
                        size = Size(max(barW - 1.5f, 1f), max(abs(base - py), 1f)),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
    }
}

private fun truncate(value: String, maxChars: Int): String =
    if (value.length <= maxChars) value else value.take((maxChars - 1).coerceAtLeast(1)) + "…"

internal fun richNumber(v: Double): String {
    if (!v.isFinite()) return "—"
    if (abs(v) < 1e-9) return "0"
    if (abs(v - round(v)) < 1e-9 && abs(v) < 1e15) return round(v).toLong().toString()
    return String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
}

internal fun richPercent(pct: Double): String {
    if (!pct.isFinite()) return "—"
    val rounded = round(pct * 10) / 10
    return if (abs(rounded - round(rounded)) < 1e-9) {
        "${rounded.toLong()}%"
    } else {
        String.format(Locale.US, "%.1f%%", rounded)
    }
}
