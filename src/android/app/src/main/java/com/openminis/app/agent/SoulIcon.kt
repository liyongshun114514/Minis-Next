package com.openminis.app.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * [T-android-soul-custom-icon] The Soul identity icon: an emoji, or a
 * transparent image stored inline as a data URI.
 *
 * Port of the iOS `SoulIconImage` / emoji-normalization pair
 * (`dfa7a17b5`, `68b9ceaed`). The rules here are contract, not preference —
 * the value syncs between platforms, so both sides must agree on what is
 * storable.
 */
object SoulIcon {

    /** The stored prefix. PNG specifically: it is the format we re-encode to. */
    const val DATA_URI_PREFIX = "data:image/png;base64,"

    /**
     * Longest edge of the stored bitmap, in pixels.
     *
     * [T-soul-avatar-fill] Raised from 96 to 256 when the square crop was
     * dropped: the image now keeps its aspect ratio and is FILLED into the
     * avatar frame, so a 4:3 photo is cropped by the renderer (ContentScale.Crop)
     * and only its middle survives — 96px of a wide photo left too little
     * resolution for a 56dp avatar. A 256px PNG still lands in the tens of KB,
     * far below [MAX_DATA_URI_CHARS].
     */
    const val STORED_PIXELS = 256

    /**
     * Hard cap on the stored string — the frontmatter line's own capacity
     * limit, not a verdict on the user's picture.
     *
     * [T-soul-avatar-import-no-size-cap] A 256px PNG normally lands far below
     * this. The cap exists so a hand-edited or synced-in SOUL.md cannot put an
     * unbounded blob on the single frontmatter line (there is no other size
     * guard on frontmatter, unlike the body which has its own limit), and so
     * the value stays within what the config/backup plumbing will carry.
     * [encode] never REJECTS a picture because of it: it walks
     * [INLINE_EDGE_LADDER] until the rendering fits.
     */
    const val MAX_DATA_URI_CHARS = 64 * 1024

    fun isDataUri(value: String): Boolean = value.startsWith(DATA_URI_PREFIX)

    /**
     * [T-android-soul-icon-sidecar] The PNG sidecar's filename, stored in the
     * `icon:` frontmatter key in place of the bytes. Byte-identical to iOS's
     * `SoulIconImage.sidecarName` — SOUL.md is a cross-device file, so the two
     * platforms must agree on what the key contains.
     */
    const val SIDECAR_NAME = "SOUL.icon.png"

    /** True when a stored `icon:` value NAMES the sidecar rather than holding bytes. */
    fun isSidecarRef(value: String): Boolean = value.trim() == SIDECAR_NAME

    /** The raw PNG bytes behind a `data:image/png;base64,…` URI, or null. */
    fun pngBytes(dataUri: String): ByteArray? {
        if (!isDataUri(dataUri)) return null
        return decodeBase64(dataUri.removePrefix(DATA_URI_PREFIX))
    }

    /** The data URI for raw PNG bytes — the in-memory form every consumer reads. */
    fun dataUri(png: ByteArray): String =
        DATA_URI_PREFIX + Base64.encodeToString(png, Base64.NO_WRAP)

    /**
     * LEGACY: the rounded-rectangle corner radius a previous renderer used.
     *
     * [T-soul-avatar-circle] The avatar is a CIRCLE everywhere now
     * (`ui/components/SoulAvatar.kt`): a 22% rounded rectangle reads as a
     * "squarish tile" and, on the identity card, left the frame's margin
     * visible as a ring of background around the picture. The constant is kept
     * ONLY because `ui/settings/SoulSettingsScreen.kt` still names it on its
     * own renderer; nothing may read it once that call site moves to
     * `SoulAvatarGlyph`. Do not use it for new drawing.
     */
    const val CORNER_RADIUS_FRACTION = 0.22f

    /**
     * Longest edge, in pixels, an imported picture is DECODED at, before
     * [encode] shrinks it to [STORED_PIXELS].
     *
     * [T-soul-avatar-import-no-size-cap] This is a SAFETY BOUND, not a
     * user-facing limit: it exists so a 10800x8100 phone photo or a 100MP scan
     * is never materialised at full resolution (which is what used to make the
     * import path OOM or die on `Bitmap` allocation) while still leaving 4x the
     * stored resolution for a clean final resample. Images of ANY byte size and
     * ANY pixel count are accepted; they are simply sampled down while
     * decoding. Rejecting a picture because of its size was the behaviour this
     * replaced.
     */
    const val IMPORT_DECODE_EDGE_PX = 1024

    /**
     * [T-soul-avatar-import-no-size-cap] Edges [encode] tries, in order, when
     * the PNG of the largest one would not fit [MAX_DATA_URI_CHARS].
     *
     * The cap is a *storage-format* invariant (a SOUL.md frontmatter line must
     * stay small enough to be parsed and synced), NOT a verdict on the user's
     * picture: instead of refusing an image that compresses badly — a detailed
     * 256px photo with noise can exceed 64KB of base64 — the encoder steps the
     * edge down until it fits. 32px is the floor and its PNG is a few KB even
     * for pure noise, so the loop always terminates in a stored icon. Small
     * sources are never upscaled: an edge above the source's own longest edge
     * is a no-op step.
     */
    val INLINE_EDGE_LADDER: IntArray = intArrayOf(STORED_PIXELS, 192, 128, 96, 64, 32)

    /**
     * Why a picked image was refused.
     *
     * [T-soul-avatar-import-no-size-cap] There is exactly ONE reason left:
     * the data could not be read. `TOO_LARGE` used to be the other, and it
     * was an opinion about the user's file (byte size, then encoded size)
     * rather than a defect. It is GONE from the enum on purpose: oversize
     * sources are sampled while decoding ([importFrom]) and an encode that
     * would not fit the inline cap steps down [INLINE_EDGE_LADDER] to its
     * 32px floor, which always fits. Deleting the constant is what makes it
     * impossible for a UI branch to offer a "picture too large" message
     * again.
     */
    enum class Rejection { UNREADABLE }

    sealed class EncodeResult {
        data class Success(val dataUri: String) : EncodeResult()
        data class Failure(val reason: Rejection) : EncodeResult()
    }

    /**
     * Normalize a bitmap into the stored form: aspect ratio preserved, longest
     * edge downscaled to [STORED_PIXELS] (or the first edge of
     * [INLINE_EDGE_LADDER] whose PNG fits [MAX_DATA_URI_CHARS]), PNG, base64
     * data URI.
     *
     * [T-soul-avatar-circle] Not centre-cropped to a square. The avatar is
     * drawn with `ContentScale.Crop` inside a CIRCLE, so a non-square image
     * fills the frame instead of letterboxing inside it; the crop happens at
     * draw time, not by destroying pixels on save.
     *
     * [T-soul-avatar-import-no-size-cap] **No image is refused for being too
     * large.** An earlier version failed with `Rejection.TOO_LARGE` whenever
     * the encoded string passed 64KB, which a perfectly ordinary detailed
     * photo of a few hundred pixels hit — an arbitrary limit on the user's
     * file, not a safety measure. The encoder now walks [INLINE_EDGE_LADDER]
     * and keeps the largest rendering that fits, so the stored value stays
     * parseable and the picture is still accepted.
     *
     * **Opaque images are accepted.** An earlier version refused anything
     * without an alpha channel, reasoning that an unframed opaque rectangle
     * reads as a broken tile. That reasoning was about PRESENTATION, so the
     * fix belongs in the renderer — every image is clipped to a circle
     * wherever it is drawn. Follows iOS `fe2f3ae8b`, which reversed the same
     * rule for the same reason; the two platforms must agree, since the value
     * syncs.
     *
     * Alpha is still PRESERVED (PNG, never flattened) — it is simply no
     * longer required. Null-ish bitmaps and compression failures remain
     * [Rejection.UNREADABLE]: those are real defects, not size opinions.
     */
    fun encode(source: Bitmap): EncodeResult {
        if (source.width <= 0 || source.height <= 0) {
            return EncodeResult.Failure(Rejection.UNREADABLE)
        }
        // [T-soul-avatar-alpha-fill] A PNG with a transparent border would be
        // CENTRED, not filled, inside the avatar circle: the circle clip
        // cannot shrink an image that is already the right aspect ratio, so
        // the empty margin survives as a ring of nothing between the artwork
        // and the frame edge. Trimming the OUTER transparent rows/columns
        // makes the visible content reach the frame; interior transparency
        // (holes, soft edges, a circular logo's corners) is left alone. Only
        // images that actually carry alpha are scanned.
        val content = trimTransparentEdges(source)
        return try {
            encodeLadder(content)
        } finally {
            if (content !== source) content.recycle()
        }
    }

    /**
     * The ladder itself: the largest rendering that fits the inline cap, and
     * — because [INLINE_EDGE_LADDER] ends at 32px — never a refusal.
     */
    private fun encodeLadder(source: Bitmap): EncodeResult {
        var smallest: String? = null
        for (edge in INLINE_EDGE_LADDER) {
            val png = pngAtEdge(source, edge) ?: return EncodeResult.Failure(Rejection.UNREADABLE)
            // NO_WRAP is mandatory: the default inserts newlines, and the
            // frontmatter parser is strictly line-oriented — a wrapped payload
            // would be truncated at the first break.
            val encoded = dataUri(png)
            if (fitsInline(encoded.length)) return EncodeResult.Success(encoded)
            smallest = encoded
        }
        // Unreachable: a 32x32 ARGB PNG is at most 4KB of pixels, i.e. ~5.5KB
        // of base64, far below the 64KB cap. If a codec ever managed to exceed
        // it, storing the floor rendering is still better than telling the
        // user their perfectly good picture is "too large" — the cap is a
        // format constraint, not a verdict on their file.
        return EncodeResult.Success(smallest!!)
    }

    /**
     * [T-soul-avatar-alpha-fill] Crop the fully transparent outer margin, so
     * the visible pixels fill the avatar frame.
     *
     * Returns [source] itself when there is nothing to trim (no alpha, all
     * opaque, fully transparent, or a scan failure), so the caller can tell
     * the two cases apart by identity and recycle only what it created.
     */
    internal fun trimTransparentEdges(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        val pixels = IntArray(w * h)
        runCatching { source.getPixels(pixels, 0, w, 0, 0, w, h) }.getOrElse { return source }
        val bounds = alphaBounds(pixels, w, h) ?: return source
        val (left, top, right, bottom) = bounds
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return source
        return runCatching {
            Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
        }.getOrNull() ?: source
    }

    /**
     * [T-soul-avatar-alpha-fill] The box of non-invisible pixels in an ARGB
     * buffer, as `(left, top, right, bottomInclusive)`, or null when every
     * pixel is (near) transparent.
     *
     * A "visible" threshold of 8/255 rather than 1: an alpha of 1–7 is
     * imperceptible on any screen and is exactly what encoders leave in the
     * margin, so counting it would keep a one-pixel ghost border and defeat
     * the trim.
     *
     * Pure arithmetic over an IntArray, so the trim decision is asserted on
     * the JVM where a Bitmap cannot be built.
     */
    internal fun alphaBounds(pixels: IntArray, width: Int, height: Int): IntArray? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size < width * height) return null
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val alpha = pixels[row + x] ushr 24
                if (alpha < VISIBLE_ALPHA) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return null
        return intArrayOf(left, top, right, bottom)
    }

    /** Alpha at or above this is treated as visible content. */
    private const val VISIBLE_ALPHA = 8

    /**
     * PNG bytes for [source] with its longest edge at [edge], never upscaled.
     * Null when the bitmap cannot be scaled or compressed at all.
     */
    private fun pngAtEdge(source: Bitmap, edge: Int): ByteArray? {
        val (w, h) = scaledSize(source.width, source.height, edge)
        val scaled = if (source.width == w && source.height == h) {
            source
        } else {
            runCatching { Bitmap.createScaledBitmap(source, w, h, true) }.getOrNull()
                ?: return null
        }
        return runCatching {
            ByteArrayOutputStream().use { out ->
                // PNG is lossless and ignores the quality argument, so alpha
                // and exact pixels survive the round trip.
                if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
                out.toByteArray()
            }
        }.getOrNull()
    }

    /**
     * True when a base64 data URI of [encodedChars] characters may be stored
     * inline. Pure arithmetic so the "does it fit?" decision — the only thing
     * left that can look like a size limit — is asserted on the JVM.
     */
    internal fun fitsInline(encodedChars: Int, cap: Int = MAX_DATA_URI_CHARS): Boolean =
        encodedChars <= cap

    // ── Import (streaming, downsampled) ──────────────────────────────────

    /**
     * [T-soul-avatar-import-no-size-cap] Turn a user-picked picture into the
     * stored icon WITHOUT ever materialising it at full resolution.
     *
     * The old path was `BitmapFactory.decodeStream(input)` followed by a byte
     * check: a 10800x8100 camera photo (350MB as ARGB_8888) was decoded whole
     * and then judged, so the app either died on the allocation (OOM, which
     * no `runCatching` can make good on) or refused a perfectly ordinary
     * picture for being "too large". Neither is acceptable.
     *
     * Now the stream is read TWICE, which is cheap because the decoder only
     * parses a header on the first pass:
     *
     *  1. bounds only (`inJustDecodeBounds`) — pixels are never allocated;
     *  2. one real decode at [sampleSizeFor]'s power-of-two sample, so a
     *     100MP source arrives as ~1MP (≈4MB), then [encode] resamples that
     *     down to [STORED_PIXELS].
     *
     * Any byte size and any pixel count is accepted, as long as the platform
     * can decode the format. [open] must return a FRESH stream on every call
     * (a `content://` URI can be opened repeatedly; a one-shot stream cannot).
     *
     * Failure is reserved for real defects: an unreadable/unrecognised file,
     * a truncated stream, or an I/O error while reading it. Those surface as
     * [Rejection.UNREADABLE] — the "can't read your file" verdict, never
     * "your file is too big".
     */
    fun importFrom(open: () -> java.io.InputStream?): EncodeResult {
        val bounds = readBounds(open) ?: return EncodeResult.Failure(Rejection.UNREADABLE)
        val sample = sampleSizeFor(bounds.first, bounds.second)
        val bitmap = decodeSampled(open, sample)
            ?: return EncodeResult.Failure(Rejection.UNREADABLE)
        return try {
            encode(bitmap)
        } finally {
            // The caller handed us no bitmap, so nothing else can be holding
            // it: free ~4MB before the settings screen redraws. encode() has
            // already copied what it needs into the PNG bytes.
            bitmap.recycle()
        }
    }

    /** [importFrom] over bytes already in memory (minis-config, shared files). */
    fun importBytes(bytes: ByteArray): EncodeResult =
        importFrom { java.io.ByteArrayInputStream(bytes) }

    /**
     * The `inSampleSize` that keeps a [width]×[height] decode at or under
     * [maxEdge] on its longest side, as a power of two (what
     * `BitmapFactory` actually honours).
     *
     * Pure arithmetic so the memory bound — the thing that replaced the size
     * rejection — is asserted on the JVM. A source already within [maxEdge]
     * is sampled at 1 (no resampling, no upscale). The result is always >= 1,
     * so a 1px image is not divided into zero.
     */
    internal fun sampleSizeFor(
        width: Int,
        height: Int,
        maxEdge: Int = IMPORT_DECODE_EDGE_PX,
    ): Int {
        require(maxEdge > 0) { "maxEdge must be positive" }
        if (width <= 0 || height <= 0) return 1
        val longest = maxOf(width, height)
        var sample = 1
        // Double (not multiply-by-anything) because inSampleSize is specified
        // as a power of two; any other value is rounded down by the platform
        // and would silently decode larger than we budgeted for.
        while (longest / sample > maxEdge && sample <= Int.MAX_VALUE / 2) {
            sample *= 2
        }
        return sample
    }

    /** Image dimensions from a header-only pass; null when nothing decodable. */
    private fun readBounds(open: () -> java.io.InputStream?): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open()?.use { BitmapFactory.decodeStream(it, null, options) } }
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private fun decodeSampled(open: () -> java.io.InputStream?, sample: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // Explicit ARGB_8888: alpha must survive (PNG storage), and the
            // default can vary with the source format.
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        // runCatching also absorbs OutOfMemoryError from a pathological
        // decode; the sample above is what keeps that from happening, and a
        // remaining OOM is reported as a read failure rather than a crash.
        return runCatching { open()?.use { BitmapFactory.decodeStream(it, null, options) } }
            .getOrNull()
    }


    /** Decode a stored data URI. Returns null for an emoji value or garbage. */
    fun decode(value: String): Bitmap? {
        if (!isDataUri(value)) return null
        return runCatching {
            val bytes = Base64.decode(value.removePrefix(DATA_URI_PREFIX), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /**
     * [T-android-soul-icon-config-images] Turn a `minis-config` value into
     * bitmap bytes.
     *
     * Mirrors iOS `fe2f3ae8b`: an address is only an IMPORT SOURCE. Whatever
     * it resolves to goes through the same [encode] the Settings picker uses,
     * and only the RESULT is stored — so the source file can be deleted
     * afterwards and the icon still survives attachment cleanup and syncing.
     *
     * Deliberately NOT supported on Android: `http(s)://`. iOS resolves those
     * in an async phase before its confirmation sheet; Android's
     * `ConfigField.write` is synchronous, and doing a network fetch inside it
     * would block the caller and hand a model-supplied URL a request from
     * inside the app — an SSRF surface that needs the same host-blocklist
     * treatment iOS built. Rather than ship a weaker version of that, remote
     * URLs are refused with a message pointing at the local forms. Everything
     * that does not need the network is supported.
     */
    sealed class Source {
        data class Bytes(val data: ByteArray) : Source() {
            // ByteArray needs structural equals for the data class to behave.
            override fun equals(other: Any?): Boolean =
                this === other || (other is Bytes && data.contentEquals(other.data))
            override fun hashCode(): Int = data.contentHashCode()
        }
        data class LinuxPath(val path: String) : Source()
        data class Unsupported(val reason: String) : Source()
    }

    /** Classify a config value that is not an emoji and not empty. */
    fun classifySource(raw: String): Source {
        val v = raw.trim()
        return when {
            v.startsWith("http://", true) || v.startsWith("https://", true) ->
                Source.Unsupported(
                    "remote URLs aren't supported on Android — download the file first, " +
                        "then pass a path like /var/minis/attachments/icon.png",
                )
            v.startsWith("minis://") -> {
                // minis://attachments/x.png -> /var/minis/attachments/x.png
                val rest = v.removePrefix("minis://").trimStart('/')
                if (rest.isEmpty()) Source.Unsupported("empty minis:// path")
                else Source.LinuxPath("/var/minis/$rest")
            }
            v.startsWith("data:") -> {
                val comma = v.indexOf(',')
                val meta = if (comma > 0) v.substring(0, comma) else ""
                if (comma < 0 || !meta.contains(";base64")) {
                    Source.Unsupported("only base64 data URIs are supported")
                } else {
                    decodeBase64(v.substring(comma + 1))
                        ?.let { Source.Bytes(it) }
                        ?: Source.Unsupported("the data URI's base64 could not be decoded")
                }
            }
            v.startsWith("/") -> Source.LinuxPath(v)
            // Bare base64 — auto-detected, matching iOS. Checked last so it
            // cannot shadow any of the addressed forms.
            looksLikeBareBase64(v) ->
                decodeBase64(v)?.let { Source.Bytes(it) }
                    ?: Source.Unsupported("that base64 could not be decoded")
            else -> Source.Unsupported(
                "not an emoji, a data URI, base64, a minis:// resource or a /var/minis path",
            )
        }
    }

    private fun decodeBase64(s: String): ByteArray? = runCatching {
        Base64.decode(s.trim(), Base64.DEFAULT)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun looksLikeBareBase64(v: String): Boolean =
        v.length >= 32 && v.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }

    /**
     * The linux directories a config-supplied path may read from.
     *
     * Containment resolves symlinks on BOTH sides before comparing (same
     * construction as the backup extractor), so a symlink inside an allowed
     * directory cannot point out of it. Without the canonicalisation a
     * model-supplied `/var/minis/attachments/../../../databases/x` would walk
     * straight out of the sandbox.
     */
    val ALLOWED_LINUX_ROOTS = listOf(
        "/var/minis/attachments",
        "/var/minis/workspace",
        "/var/minis/offloads",
        "/var/minis/shared",
        "/var/minis/memory",
        "/var/minis/skills",
    )

    /** True when [candidate] really sits inside [root] after both are resolved. */
    fun isContained(candidate: java.io.File, root: java.io.File): Boolean {
        val c = runCatching { candidate.canonicalFile }.getOrElse { return false }
        val r = runCatching { root.canonicalFile }.getOrElse { return false }
        // The separator matters: it stops "/a/bc" matching root "/a/b".
        return c == r || c.path.startsWith(r.path + java.io.File.separator)
    }

    /**
     * [T-soul-avatar-fill] The stored pixel size for an image of
     * [width]×[height]: aspect ratio preserved, longest edge capped at
     * [maxEdge], never enlarged.
     *
     * Pure arithmetic, no Bitmap — the unit tests assert it on the JVM where
     * `android.graphics.Bitmap` cannot run. A degenerate source yields
     * 1×1 rather than 0 (a 0 would make `createScaledBitmap` throw).
     */
    internal fun scaledSize(width: Int, height: Int, maxEdge: Int = STORED_PIXELS): Pair<Int, Int> {
        require(width > 0 && height > 0) { "image has no pixels" }
        require(maxEdge > 0) { "maxEdge must be positive" }
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val ratio = maxEdge.toDouble() / longest.toDouble()
        val w = Math.round(width * ratio).toInt().coerceAtLeast(1)
        val h = Math.round(height * ratio).toInt().coerceAtLeast(1)
        // Rounding can push the scaled longest edge one pixel over; clamp it
        // so "longest edge <= maxEdge" is an invariant the caller can rely on.
        val overW = (w - maxEdge).coerceAtLeast(0)
        val overH = (h - maxEdge).coerceAtLeast(0)
        return (w - overW) to (h - overH)
    }

    // ── Emoji ────────────────────────────────────────────────────────────

    /**
     * Suggestions offered in the picker.
     *
     * **All Unicode 6.0 (2010).** Non-negotiable: this value syncs to iOS, and
     * a 2021-or-later emoji renders as a blank box on a device with an older
     * font, which reads as data loss rather than a style choice. No skin tones
     * and no ZWJ sequences, for the same reason.
     */
    val SUGGESTED_EMOJI: List<String> = listOf(
        "✨", "🤖", "🐱", "🦊", "🐧", "🦉", "🌟", "⚡",
        "🧠", "💡", "🔮", "🚀", "🌊", "🍀", "🎯", "🐳",
    )

    /**
     * Keep at most one emoji, preferring whatever the user just added.
     *
     * Called PER KEYSTROKE, not on submit: typing a second emoji replaces the
     * first (we take the LAST one present), and non-emoji input is silently
     * dropped rather than surfaced as an error.
     *
     * Operates on grapheme clusters, so a flag (🇯🇵), keycap (1️⃣), ZWJ
     * sequence (👩‍💻) or skin-toned emoji (👍🏽) each survive as ONE glyph
     * instead of being sliced apart.
     */
    fun normalizeEmojiInput(input: String): String =
        graphemeClusters(input).lastOrNull { isEmojiGlyph(it) } ?: ""

    /**
     * True for a grapheme cluster that is genuinely an emoji.
     *
     * The trap this exists for: a naive "has the Emoji property" test accepts
     * bare ASCII digits, `#` and `*`, because Unicode gives those the Emoji
     * property — they are the bases of keycap sequences like 1️⃣. So a lone
     * "1" would pass and be stored as the identity icon.
     *
     * The rule, matching iOS: a MULTI-scalar cluster is accepted when any
     * scalar is emoji-ish (that keeps 1️⃣, 🇯🇵, 👩‍💻, 👍🏽); a LONE scalar
     * must have default emoji presentation (which excludes plain digits,
     * letters, CJK and punctuation).
     */
    fun isEmojiGlyph(cluster: String): Boolean {
        if (cluster.isEmpty()) return false
        val codePoints = cluster.codePoints().toArray()
        if (codePoints.isEmpty()) return false

        if (codePoints.size > 1) {
            // A variation selector-16 or keycap makes an otherwise-text base
            // render as emoji; a ZWJ or regional-indicator pair is emoji by
            // construction.
            if (codePoints.any { it == 0xFE0F || it == 0x20E3 || it == 0x200D }) return true
            if (codePoints.all { it in 0x1F1E6..0x1F1FF }) return true
            return codePoints.any { hasEmojiPresentation(it) }
        }
        return hasEmojiPresentation(codePoints[0])
    }

    /**
     * Approximates Unicode's Emoji_Presentation property: code points that
     * render as emoji by default, with no variation selector.
     *
     * Kotlin/Java exposes no Emoji_Presentation query (and
     * `Character.isEmoji` is API 35+, far above our minSdk), so this is an
     * explicit range list. It deliberately EXCLUDES the keycap bases
     * (`0-9`, `#`, `*`) and other text-default symbols, which is exactly the
     * case that a property-based test gets wrong.
     */
    private fun hasEmojiPresentation(cp: Int): Boolean = when (cp) {
        0x231A, 0x231B,                                  // watch, hourglass
        0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23F0, 0x23F3,
        0x25FD, 0x25FE,
        0x2614, 0x2615,
        0x2648, 0x2649, 0x264A, 0x264B, 0x264C, 0x264D,  // zodiac
        0x264E, 0x264F, 0x2650, 0x2651, 0x2652, 0x2653,
        0x267F, 0x2693, 0x26A1, 0x26AA, 0x26AB,          // ⚡ is here
        0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26D4,
        0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26FA, 0x26FD,
        0x2705, 0x270A, 0x270B,
        0x2728,                                          // ✨ the default
        0x274C, 0x274E,
        0x2753, 0x2754, 0x2755, 0x2757,
        0x2795, 0x2796, 0x2797,
        0x27B0, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B55,
        -> true
        else -> when (cp) {
            in 0x2B05..0x2B07 -> false                   // text-default arrows
            in 0x1F004..0x1F0CF -> true                  // mahjong, playing card
            in 0x1F18E..0x1F19A -> true
            in 0x1F1E6..0x1F1FF -> false                 // lone regional indicator
            in 0x1F200..0x1F251 -> true
            in 0x1F300..0x1F320 -> true
            in 0x1F32D..0x1F335 -> true
            in 0x1F337..0x1F37C -> true
            in 0x1F37E..0x1F393 -> true
            in 0x1F3A0..0x1F3CA -> true
            in 0x1F3CF..0x1F3D3 -> true
            in 0x1F3E0..0x1F3F0 -> true
            in 0x1F3F4..0x1F3F4 -> true
            in 0x1F3F8..0x1F43E -> true
            in 0x1F440..0x1F4FC -> true
            in 0x1F4FF..0x1F53D -> true
            in 0x1F54B..0x1F54E -> true
            in 0x1F550..0x1F567 -> true
            in 0x1F57A..0x1F57A -> true
            in 0x1F595..0x1F596 -> true
            in 0x1F5A4..0x1F5A4 -> true
            in 0x1F5FB..0x1F64F -> true
            in 0x1F680..0x1F6C5 -> true
            in 0x1F6CC..0x1F6CC -> true
            in 0x1F6D0..0x1F6D2 -> true
            in 0x1F6EB..0x1F6EC -> true
            in 0x1F6F4..0x1F6FC -> true
            in 0x1F7E0..0x1F7EB -> true
            in 0x1F90C..0x1F93A -> true
            in 0x1F93C..0x1F945 -> true
            in 0x1F947..0x1F978 -> true
            in 0x1F97A..0x1F9CB -> true
            in 0x1F9CD..0x1F9FF -> true
            in 0x1FA70..0x1FAFF -> true
            else -> false
        }
    }

    /**
     * Split into extended grapheme clusters, for the emoji subset we care
     * about: ZWJ sequences, regional-indicator flag pairs, variation
     * selectors, keycaps and skin-tone modifiers.
     *
     * Hand-rolled rather than using `BreakIterator.getCharacterInstance()`.
     * That was the first implementation and it is **wrong for exactly the
     * cases this feature depends on** — measured on JDK 17, it reports
     * 🇯🇵 as 2 clusters, 👩‍💻 as 3 and 👍🏽 as 2, because the JDK's
     * BreakIterator implements legacy boundaries, not UAX #29 *extended*
     * grapheme clusters. Android's ICU gets those right, so a
     * BreakIterator-based version would pass on device and fail on the JVM —
     * i.e. the unit tests would be the only thing telling the truth, and only
     * by disagreeing with production. One implementation that behaves
     * identically in both places is worth more than the shared-library
     * shortcut.
     */
    fun graphemeClusters(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val cps = input.codePoints().toArray()
        var i = 0
        while (i < cps.size) {
            val start = i
            i++
            // A flag is exactly two regional indicators.
            if (isRegionalIndicator(cps[start]) &&
                i < cps.size && isRegionalIndicator(cps[i])
            ) {
                i++
            } else {
                // Absorb trailing modifiers, then any ZWJ-joined segment
                // (each of which may itself carry modifiers).
                i = absorbModifiers(cps, i)
                while (i < cps.size && cps[i] == ZWJ && i + 1 < cps.size) {
                    i += 2                     // the ZWJ and the joined base
                    i = absorbModifiers(cps, i)
                }
            }
            out.add(String(cps, start, i - start))
        }
        return out
    }

    private const val ZWJ = 0x200D

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** Variation selectors, keycap, skin tones, and combining marks. */
    private fun absorbModifiers(cps: IntArray, from: Int): Int {
        var i = from
        while (i < cps.size) {
            val cp = cps[i]
            val isModifier = cp == 0xFE0E || cp == 0xFE0F ||   // variation selectors
                cp == 0x20E3 ||                                // combining keycap
                cp in 0x1F3FB..0x1F3FF ||                      // skin tones
                cp in 0x0300..0x036F ||                        // combining diacritics
                cp in 0xE0020..0xE007F                         // tag chars (subdivision flags)
            if (!isModifier) break
            i++
        }
        return i
    }
}
