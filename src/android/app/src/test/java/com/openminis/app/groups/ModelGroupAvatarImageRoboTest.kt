package com.openminis.app.groups

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * [T-model-group-avatar] The real bitmap path: a user picture is re-encoded to
 * a bounded PNG data URI whose decoded pixels keep the aspect ratio, and a
 * large detailed picture is stepped down the edge ladder rather than refused.
 *
 * `import` is exercised through a real file URI so the bounds pass, the
 * sampled decode, EXIF orientation and the encode all run — a regression that
 * requires a non-null Bitmap from the bounds-only decode (which returns null by
 * design) would otherwise reject every picture and still pass an encode-only
 * test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelGroupAvatarImageRoboTest {

    private fun solid(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }

    @Test
    fun `a photo becomes a bounded PNG data URI with its aspect ratio`() {
        val source = solid(1200, 900)
        val value = requireNotNull(ModelGroupAvatarImage.encode(source))
        assertTrue(value.startsWith(ModelGroupAvatarValue.DATA_URI_PREFIX))
        assertTrue(value.length <= ModelGroupAvatarValue.MAX_STORED_CHARS)
        assertTrue(ModelGroupAvatarValue.isUsable(value))

        val bytes = requireNotNull(ModelGroupAvatarValue.pngBytes(value))
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertEquals(192, decoded.width)
        assertEquals(144, decoded.height)
    }

    @Test
    fun `a large detailed picture is stepped down instead of rejected`() {
        val width = 900
        val height = 900
        val pixels = IntArray(width * height) { index ->
            // A deterministic high-frequency pattern: PNG cannot compress it
            // into a few bytes, which is exactly the case the ladder exists for.
            Color.rgb(index % 256, (index / 256) % 256, (index * 7) % 256)
        }
        val source = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val value = requireNotNull(ModelGroupAvatarImage.encode(source))
        assertTrue(value.length <= ModelGroupAvatarValue.MAX_STORED_CHARS)
        assertNotNull(ModelGroupAvatarValue.pngBytes(value))
    }

    @Test
    fun `a 1x1 bitmap still produces a valid icon`() {
        val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val value = requireNotNull(ModelGroupAvatarImage.encode(empty))
        assertTrue(ModelGroupAvatarValue.isUsable(value))
    }

    @Test
    fun `a file URI import goes through bounds, decode and encode`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "group-avatar-import-test.png")
        val source = solid(900, 600)
        file.outputStream().use { out ->
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out))
        }
        try {
            // The bounds-only decode returns a null Bitmap BY DESIGN; this
            // must still import the picture, not treat that null as failure.
            val value = requireNotNull(ModelGroupAvatarImage.import(context, Uri.fromFile(file)))
            assertTrue(ModelGroupAvatarValue.isUsable(value))
            val bytes = requireNotNull(ModelGroupAvatarValue.pngBytes(value))
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertEquals(192, decoded.width)
            assertEquals(128, decoded.height)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `an unreadable URI is refused rather than stored broken`() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(
            ModelGroupAvatarImage.import(context, Uri.parse("content://missing.invalid/avatar.png")),
        )
    }
}