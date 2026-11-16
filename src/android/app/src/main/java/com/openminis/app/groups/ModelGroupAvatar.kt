package com.openminis.app.groups

import java.util.Base64
import java.util.Locale

/**
 * [T-model-group-avatar] The stored avatar value: how a manual icon choice is
 * written into `ModelGroup.avatar`, and how an existing value is validated.
 *
 * Two forms only, both self-contained (no absolute paths, nothing that a
 * config JSON mirror / backup cannot carry, nothing a restored package could
 * point outside the app):
 *
 *  - `builtin:<catalog id>` — a bundled brand icon.
 *  - `data:image/png:base64,…` — a user picture, re-encoded to a bounded PNG
 *    by [ModelGroupAvatarImage]. This is the same inline convention SOUL icons
 *    already use, so the config/backup plumbing needs no new file transport.
 *
 * Empty/null means AUTOMATIC (match the icon from the group name). Anything
 * unrecognized also reads as automatic — a value written by a newer build, or
 * a `builtin:` id a catalog update dropped, must not break a group list; the
 * stored string is still preserved, so re-adding the icon brings it back.
 */
object ModelGroupAvatarValue {

    const val BUILTIN_PREFIX = "builtin:"
    const val DATA_URI_PREFIX = "data:image/png;base64,"

    /**
     * Hard cap on the stored string. Sits on the config/backup plumbing (a
     * single JSON value) rather than on the user's picture: the encoder walks
     * its edge ladder until the PNG fits, so a picture is normalized, never
     * rejected for being detailed.
     */
    const val MAX_STORED_CHARS = 64 * 1024

    /** Decoded-byte ceiling behind [MAX_STORED_CHARS] (base64 inflates by 4/3). */
    const val MAX_IMAGE_BYTES = 44 * 1024

    /** A parsed manual override. */
    sealed interface Manual {
        /** A catalog brand icon, id already lower-cased and validated. */
        data class Builtin(val id: String) : Manual

        /** A bounded PNG data URI, already structurally validated. */
        data class Image(val dataUri: String) : Manual
    }

    /** `builtin:<id>` for a valid catalog id, or null when the id is unsafe. */
    fun builtinValue(id: String): String? =
        normalizedId(id)?.let { BUILTIN_PREFIX + it }

    /** Parse a stored value; null means "automatic" (or an unusable value). */
    fun parse(stored: String?): Manual? {
        val value = stored?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith(BUILTIN_PREFIX)) {
            val id = normalizedId(value.removePrefix(BUILTIN_PREFIX)) ?: return null
            return Manual.Builtin(id)
        }
        if (value.startsWith(DATA_URI_PREFIX)) {
            return if (isValidPngDataUri(value)) Manual.Image(value) else null
        }
        return null
    }

    /** True when [stored] is a value this build can render as a manual icon. */
    fun isUsable(stored: String?): Boolean = parse(stored) != null

    /**
     * A catalog id: 1–64 chars, starts alphanumeric, then alphanumerics plus
     * `.`, `_`, `-`. Lower-cased so `builtin:OpenAI` and `builtin:openai`
     * resolve identically and round-trip deterministically.
     */
    fun normalizedId(raw: String): String? {
        val id = raw.trim().lowercase(Locale.ROOT)
        if (id.isEmpty() || id.length > 64) return null
        if (!id[0].isLetterOrDigit()) return null
        for (ch in id) {
            val ok = ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-'
            if (!ok) return null
        }
        return id
    }

    /**
     * A catalog `file` turned into a safe relative asset path, or null.
     *
     * The catalog ships with the APK; this is not a general file reader. Still,
     * the value reaches a URI, so absolute paths, schemes, backslashes, `.`/`..`
     * segments, hidden segments and URL-unsafe characters are refused. Only the
     * image extensions the catalog schema allows survive.
     */
    fun sanitizeAssetFile(raw: String): String? {
        val file = raw.trim()
        if (file.isEmpty() || file.length > 200) return null
        if (file.startsWith("/") || file.startsWith(".")) return null
        if (file.contains('\\') || file.contains('?') || file.contains('#') || file.contains("://")) return null
        val segments = file.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        for (segment in segments) {
            if (segment.startsWith(".")) return null
            for (ch in segment) {
                val ok = ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-'
                if (!ok) return null
            }
        }
        val extension = file.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in setOf("png", "webp", "jpg", "jpeg")) return null
        return file
    }

    /** The Coil model for a catalog file; null when the file is unsafe. */
    fun assetModel(rawFile: String): String? =
        sanitizeAssetFile(rawFile)?.let { "file:///android_asset/model-icons/$it" }

    /** Raw PNG bytes behind a validated data URI, or null when unusable. */
    fun pngBytes(dataUri: String): ByteArray? {
        if (!dataUri.startsWith(DATA_URI_PREFIX)) return null
        val payload = dataUri.substring(DATA_URI_PREFIX.length)
        val bytes = runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull() ?: return null
        if (bytes.size > MAX_IMAGE_BYTES) return null
        return if (isPng(bytes)) bytes else null
    }

    /**
     * Structural validation without decoding the whole picture: bounded
     * length, base64 alphabet only, and a PNG signature in the first bytes.
     * The 8-byte signature is checked on the decoded head so a `data:` string
     * carrying, say, a JPEG can never reach the renderer as a group icon.
     */
    internal fun isValidPngDataUri(value: String): Boolean {
        if (value.length <= DATA_URI_PREFIX.length) return false
        if (value.length > MAX_STORED_CHARS) return false
        val payload = value.substring(DATA_URI_PREFIX.length)
        for (ch in payload) {
            val base64 = ch.isLetterOrDigit() || ch == '+' || ch == '/' || ch == '='
            if (!base64) return false
        }
        // 12 base64 chars decode to 9 bytes: enough for the 8-byte signature.
        val head = payload.take(12)
        if (head.length < 12) return false
        val bytes = runCatching { Base64.getDecoder().decode(head) }.getOrNull() ?: return false
        return isPng(bytes)
    }

    internal fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < PNG_SIGNATURE.size) return false
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) return false
        }
        return true
    }

    /** The 8-byte PNG file signature (ISO/IEC 15948). */
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}

/** [T-model-group-avatar] Where a rendered avatar came from. */
enum class ModelGroupAvatarSource {
    /** A manual choice (builtin icon or custom picture). */
    MANUAL,

    /** No manual choice; matched from the current group name. */
    AUTO,

    /** No manual choice and no brand match: the one generic fallback. */
    FALLBACK,
}

/** [T-model-group-avatar] What to draw. */
sealed interface ModelGroupAvatarSpec {
    data class Builtin(val entry: ModelIconCatalogEntry) : ModelGroupAvatarSpec
    data class Image(val dataUri: String) : ModelGroupAvatarSpec

    /** The unified fallback drawn for every unmatched group. */
    data object Fallback : ModelGroupAvatarSpec
}

/** [T-model-group-avatar] Resolver output: the drawing spec plus its provenance. */
data class ResolvedModelGroupAvatar(
    val spec: ModelGroupAvatarSpec,
    val source: ModelGroupAvatarSource,
    /** The parsed manual value, even when it could not be resolved. */
    val manual: ModelGroupAvatarValue.Manual? = null,
    /** The catalog term that produced an AUTO match ("gpt", "OpenAI", …). */
    val matchedTerm: String? = null,
)

/**
 * [T-model-group-avatar] One resolver for every group presentation point.
 *
 * Precedence, in order:
 *
 * 1. **Manual** — a valid `builtin:<id>` that exists in the catalog, or a
 *    valid PNG data URI.
 * 2. **Automatic** — [ModelGroupNameMatcher] against the CURRENT name. Because
 *    this is computed, renaming a group updates an automatic icon on the next
 *    render, while a stored manual choice is carried through every rename
 *    unchanged (the persisted field is never rewritten by the resolver).
 * 3. **Fallback** — [ModelGroupAvatarSpec.Fallback], one generic glyph for
 *    every group that matched nothing (no per-site variants).
 *
 * No vendor is special-cased here: the only input is the catalog, and the
 * only matching rules are exact token equality and whole-token containment.
 */
object ModelGroupAvatarResolver {

    fun resolve(
        name: String,
        stored: String?,
        catalog: ModelIconCatalog,
    ): ResolvedModelGroupAvatar {
        val manual = ModelGroupAvatarValue.parse(stored)
        when (manual) {
            is ModelGroupAvatarValue.Manual.Builtin -> {
                catalog.entry(manual.id)?.let { entry ->
                    return ResolvedModelGroupAvatar(
                        spec = ModelGroupAvatarSpec.Builtin(entry),
                        source = ModelGroupAvatarSource.MANUAL,
                        manual = manual,
                        matchedTerm = entry.id,
                    )
                }
                // The id is well-formed but this catalog does not have it:
                // degrade to automatic rather than drawing a broken tile.
            }
            is ModelGroupAvatarValue.Manual.Image -> {
                return ResolvedModelGroupAvatar(
                    spec = ModelGroupAvatarSpec.Image(manual.dataUri),
                    source = ModelGroupAvatarSource.MANUAL,
                    manual = manual,
                )
            }
            null -> Unit
        }

        val match = ModelGroupNameMatcher.match(name, catalog)
        if (match != null) {
            return ResolvedModelGroupAvatar(
                spec = ModelGroupAvatarSpec.Builtin(match.entry),
                source = ModelGroupAvatarSource.AUTO,
                manual = manual,
                matchedTerm = match.term,
            )
        }
        return ResolvedModelGroupAvatar(
            spec = ModelGroupAvatarSpec.Fallback,
            source = ModelGroupAvatarSource.FALLBACK,
            manual = manual,
        )
    }

    /**
     * The automatic match alone, for the picker's "Automatic — matches …"
     * preview. Null when the current name matches nothing.
     */
    fun autoMatch(name: String, catalog: ModelIconCatalog): ModelIconCatalogEntry? =
        ModelGroupNameMatcher.match(name, catalog)?.entry
}