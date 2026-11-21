package com.openminis.app.sandbox

import android.content.Context
import com.openminis.app.network.NetworkHttp
import com.openminis.app.network.NetworkUrlRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Only CI-accepted, versioned Ubuntu images from this repository are installable. */
internal object UbuntuImagePolicy {
    const val REPOSITORY = "liyongshun114514/Minis-Next"
    const val RELEASES_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=100"
    const val IMAGE_NAME = "ubuntu-base-arm64.tar.gz"
    const val MIN_BYTES = 16L * 1024 * 1024
    const val MAX_BYTES = 1024L * 1024 * 1024
    private val tagPattern = Regex("ubuntu-rootfs-v1-(\\d+)")
    private val hashPattern = Regex("[0-9a-fA-F]{64}")

    data class Image(val tag: String, val build: Long, val url: String, val checksumUrl: String, val size: Long)

    fun latest(json: String): Image {
        val releases = JSONArray(json)
        val images = buildList {
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft")) continue
                val tag = release.optString("tag_name")
                val build = tagPattern.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                val assets = release.optJSONArray("assets") ?: continue
                var imageUrl: String? = null
                var checksumUrl: String? = null
                var size = 0L
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.optString("name")
                    val expected = "https://github.com/$REPOSITORY/releases/download/$tag/$name"
                    if (asset.optString("browser_download_url") != expected) continue
                    when (name) {
                        IMAGE_NAME -> { imageUrl = expected; size = asset.optLong("size") }
                        "$IMAGE_NAME.sha256" -> checksumUrl = expected
                    }
                }
                if (imageUrl != null && checksumUrl != null && size in MIN_BYTES..MAX_BYTES) {
                    add(Image(tag, build, imageUrl, checksumUrl, size))
                }
            }
        }
        return images.maxByOrNull { it.build }
            ?: throw IOException("No verified Ubuntu image is available. Check the network and try again after the build is published.")
    }

    fun checksum(text: String): String {
        val fields = text.trim().split(Regex("\\s+"))
        if (fields.size != 2 || !hashPattern.matches(fields[0]) || fields[1].removePrefix("*") != IMAGE_NAME) {
            throw IOException("Invalid Ubuntu image checksum")
        }
        return fields[0].lowercase()
    }

    fun verify(expectedHash: String, actualHash: String, expectedSize: Long, actualSize: Long) {
        if (actualSize != expectedSize || actualSize !in MIN_BYTES..MAX_BYTES) throw IOException("Ubuntu image download is incomplete")
        if (!expectedHash.equals(actualHash, ignoreCase = true)) throw IOException("Ubuntu image SHA-256 mismatch")
    }
}

internal object UbuntuImageDownload {
    private fun open(url: String) = NetworkUrlRequest.open(
        NetworkHttp.DEFAULT_TAG, url,
        headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "Minis-Next-Ubuntu"),
        connectTimeoutMs = 30_000, readTimeoutMs = 60_000,
    )

    private fun readMetadata(url: String, limit: Long): String = open(url).use { response ->
        if (response.code != 200) throw IOException("Ubuntu image server returned HTTP ${response.code}")
        response.body().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size().toLong() + count > limit) throw IOException("Ubuntu image metadata is too large")
                bytes.write(buffer, 0, count)
            }
            bytes.toString("UTF-8")
        }
    }

    /** Caller owns deletion after extraction; partial and rejected downloads are always removed. */
    suspend fun download(context: Context, onProgress: (Float) -> Unit): File {
        val image = UbuntuImagePolicy.latest(readMetadata(UbuntuImagePolicy.RELEASES_URL, 2 * 1024 * 1024))
        val expectedHash = UbuntuImagePolicy.checksum(readMetadata(image.checksumUrl, 512))
        val target = File.createTempFile("ubuntu-image-", ".tar.gz", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastProgress = -1
            open(image.url).use { response ->
                if (response.code != 200) throw IOException("Ubuntu image server returned HTTP ${response.code}")
                response.body().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > image.size) throw IOException("Ubuntu image exceeds the published size")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = (received * 100 / image.size).toInt()
                        if (percent != lastProgress) { lastProgress = percent; onProgress(received.toFloat() / image.size) }
                    }
                } }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            UbuntuImagePolicy.verify(expectedHash, actualHash, image.size, received)
            return target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }
}
