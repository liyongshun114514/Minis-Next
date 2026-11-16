package com.openminis.app.groups

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * [T-model-group-avatar] Turns a user-picked picture into the bounded inline
 * form stored in `ModelGroup.avatar`.
 *
 * Why inline: the provider config is persisted twice (Room + the JSON mirror)
 * and carried wholesale by backups. A path into app storage would need a new
 * file transport in both paths and would dangle after a restore on another
 * device; a PNG data URI needs neither and matches what SOUL icons already do.
 *
 * The picture is never refused for being large — the encoder walks
 * [EDGE_LADDER] and keeps the largest rendering that fits
 * [ModelGroupAvatarValue.MAX_STORED_CHARS]. Alpha is preserved and the aspect
 * ratio is not cropped: the renderer fits the image inside its tile, so the
 * stored file keeps every pixel the user chose.
 */
object ModelGroupAvatarImage {

    /** Longest edge of the stored bitmap. */
    const val STORED_PIXELS = 192

    /**
     * Target longest edge when decoding the user's file. [sampleSize] is a
     * power of two, so the decoded edge lands in [MAX_SOURCE_PIXELS,
     * 2×MAX_SOURCE_PIXELS) — close enough that the final scale-to-192 keeps
     * quality without ever allocating a full-resolution photo.
     */
    private const val MAX_SOURCE_PIXELS = 1024

    /** Largest rendering first; the floor always fits the stored cap. */
    private val EDGE_LADDER = intArrayOf(STORED_PIXELS, 128, 96, 64, 48, 32)

    /**
     * Read [uri] through the content resolver and return the stored value, or
     * null when the picture cannot be decoded (a real defect — not a size
     * opinion; size is handled by the ladder).
     */
    fun import(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode RETURNS null by design; only opening/reading
        // the stream can fail. Requiring a non-null bitmap here would reject
        // every valid picture.
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SOURCE_PIXELS)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        val oriented = runCatching { applyOrientation(context, uri, decoded) }
            .getOrElse {
                decoded.recycle()
                return null
            }
        return try {
            encode(oriented)
        } finally {
            oriented.recycle()
        }
    }

    /**
     * Normalize a bitmap: PNG (lossless, alpha preserved), longest edge at the
     * first ladder stop whose base64 fits [ModelGroupAvatarValue.MAX_STORED_CHARS].
     */
    fun encode(source: Bitmap): String? {
        if (source.width <= 0 || source.height <= 0) return null
        var smallest: String? = null
        for (edge in EDGE_LADDER) {
            val png = pngAtEdge(source, edge) ?: continue
            if (png.size > ModelGroupAvatarValue.MAX_IMAGE_BYTES) continue
            val dataUri = ModelGroupAvatarValue.DATA_URI_PREFIX +
                Base64.getEncoder().encodeToString(png)
            if (dataUri.length <= ModelGroupAvatarValue.MAX_STORED_CHARS) return dataUri
            smallest = dataUri
        }
        // The 32px stop is far below the cap; keeping the last attempt is a
        // better outcome than refusing a perfectly good picture.
        return smallest
    }

    /** Longest edge capped at [maxEdge], aspect ratio preserved, never upscaled. */
    internal fun scaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        return (width * scale).toInt().coerceAtLeast(1) to
            (height * scale).toInt().coerceAtLeast(1)
    }

    /** Power-of-two subsample keeping the decoded longest edge >= [maxEdge]. */
    internal fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxEdge) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun pngAtEdge(source: Bitmap, edge: Int): ByteArray? {
        val (w, h) = scaledSize(source.width, source.height, edge)
        if (w <= 0 || h <= 0) return null
        val scaled = if (source.width == w && source.height == h) {
            source
        } else {
            runCatching { Bitmap.createScaledBitmap(source, w, h, true) }.getOrNull()
                ?: return null
        }
        return try {
            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
                out.toByteArray()
            }
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    /**
     * Photo-picker images routinely carry an EXIF rotation. Applying it here
     * keeps a portrait photo from landing sideways in every group list.
     */
    private fun applyOrientation(context: Context, uri: Uri, source: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return source
        }
        if (matrix.isIdentity) return source
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }
}