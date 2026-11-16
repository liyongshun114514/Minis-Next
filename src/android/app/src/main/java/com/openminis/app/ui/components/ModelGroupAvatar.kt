package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.groups.ModelGroupAvatarResolver
import com.openminis.app.groups.ModelGroupAvatarSpec
import com.openminis.app.groups.ModelGroupAvatarValue
import com.openminis.app.groups.ModelGroupIconAssets
import com.openminis.app.groups.ModelIconCatalog
import com.openminis.app.groups.ResolvedModelGroupAvatar
import java.security.MessageDigest

/**
 * [T-model-group-avatar] The icon shown for a model group at EVERY
 * presentation point (group list, group detail, default-group dropdowns,
 * agent-loop pickers, new-chat group menu, session model picker).
 *
 * One composable and one resolver, so the same group cannot show a brand logo
 * in one place and a generic glyph in another. Precedence is manual override →
 * automatic match on the current name → [FallbackGlyph]. See
 * [ModelGroupAvatarResolver] for the rules.
 *
 * The tile is a 22%-rounded square rather than a circle: the bundled marks are
 * square brand logos, and a circle either crops their corners (Crop) or
 * shrinks them (Fit). No padding is added — the asset's own transparent margin
 * is the mark's design.
 */
@Composable
fun rememberModelIconCatalog(): ModelIconCatalog {
    val context = LocalContext.current.applicationContext
    return remember(context) { ModelGroupIconAssets.catalog(context) }
}

@Composable
fun ModelGroupAvatar(
    group: ModelGroup,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    contentDescription: String? = null,
) {
    ModelGroupAvatar(
        name = group.name,
        storedAvatar = group.avatar,
        modifier = modifier,
        size = size,
        contentDescription = contentDescription,
    )
}

@Composable
fun ModelGroupAvatar(
    name: String,
    storedAvatar: String?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    contentDescription: String? = null,
) {
    val catalog = rememberModelIconCatalog()
    val resolved = remember(name, storedAvatar, catalog) {
        ModelGroupAvatarResolver.resolve(name, storedAvatar, catalog)
    }
    ModelGroupAvatarGlyph(
        resolved = resolved,
        modifier = modifier,
        size = size,
        contentDescription = contentDescription,
    )
}

/** The renderer for an already-resolved avatar (used by the picker's previews). */
@Composable
fun ModelGroupAvatarGlyph(
    resolved: ResolvedModelGroupAvatar,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        when (val spec = resolved.spec) {
            is ModelGroupAvatarSpec.Builtin -> {
                val model = remember(spec.entry) {
                    ModelGroupAvatarValue.assetModel(spec.entry.file)
                }
                if (model == null) {
                    FallbackGlyph()
                } else {
                    // [T-model-group-avatar] Brand marks are drawn on a FIXED
                    // light tile, not on the theme surface: several Lobe marks
                    // are near-black on transparency and would disappear on a
                    // dark theme. A light tile keeps every logo legible in both
                    // themes without a ColorFilter, which would flatten the
                    // multicolour marks into one tint.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BRAND_TILE_COLOR),
                        contentAlignment = Alignment.Center,
                    ) {
                        BrandImage(
                        model = model,
                        contentDescription = contentDescription,
                        memoryCacheKey = "model-group-icon:${spec.entry.id}",
                    )
                    }
                }
            }
            is ModelGroupAvatarSpec.Image -> {
                val bytes = remember(spec.dataUri) {
                    ModelGroupAvatarValue.pngBytes(spec.dataUri)
                }
                if (bytes == null) {
                    FallbackGlyph()
                } else {
                    BrandImage(
                        model = bytes,
                        contentDescription = contentDescription,
                        memoryCacheKey = remember(spec.dataUri) {
                            "model-group-image:" + shortHash(spec.dataUri)
                        },
                    )
                }
            }
            ModelGroupAvatarSpec.Fallback -> FallbackGlyph()
        }
    }
}

@Composable
private fun BrandImage(model: Any, contentDescription: String?, memoryCacheKey: String) {
    val context = LocalContext.current
    // [T-model-group-avatar] An explicit key: Coil derives no memory-cache key
    // from a ByteArray, so without this a LazyColumn would re-decode the same
    // custom PNG on every scroll tick. The key is a short hash — never the
    // base64 payload, which would bloat logs.
    val request = remember(model, memoryCacheKey) {
        ImageRequest.Builder(context)
            .data(model)
            .memoryCacheKey(memoryCacheKey)
            .crossfade(false)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        loading = { FallbackGlyph() },
        error = { FallbackGlyph() },
    )
}

/** Short, stable, log-safe cache key for a (potentially huge) data URI. */
private fun shortHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = StringBuilder(16)
    for (i in 0 until 8) {
        hex.append(String.format("%02x", digest[i]))
    }
    return hex.toString()
}

/**
 * [T-model-group-avatar] The brand tile behind every builtin logo. Fixed (not
 * theme-derived) on purpose: near-black marks must stay visible on a dark
 * theme, and this is also the light neutral Lobe's own marks are drawn on.
 */
private val BRAND_TILE_COLOR = Color(0xFFF4F4F5)

/**
 * [T-model-group-avatar] The ONE fallback for every group without a manual
 * icon and without a catalog match. Same glyph, same tint, same shape at every
 * call site — a per-surface variant would be exactly the inconsistency this
 * component exists to prevent.
 */
@Composable
private fun FallbackGlyph() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Layers,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.58f),
        )
    }
}