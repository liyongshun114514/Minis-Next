package com.openminis.app.agent

import com.openminis.app.ctx.CtxKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream

/**
 * [T-soul-avatar-import-no-size-cap] The import path on REAL pixels: a
 * generated 2000x1500 PNG (4:3) through [SoulIcon.importFrom], then the
 * stored value decoded back.
 *
 * This half needs `BitmapFactory`, so it runs under Robolectric with NATIVE
 * graphics — the same configuration the project's other graphics tests use
 * (`@Config(manifest = Config.NONE, sdk = [35])`, [GraphicsMode.Mode.NATIVE]) and the same generator
 * (`CtxKit`). It must NOT be downgraded to a plan-only assertion: the plan is
 * covered by [SoulIconImportPlanTest], and these tests are the ones that would
 * catch a decode that silently crops, skips the sampler, or refuses a picture.
 *
 * `@Config(manifest = Config.NONE, sdk = [35])` is explicit rather than
 * inherited: NONE keeps the real Application (native libs, databases, network)
 * out of these BitmapFactory/Canvas tests, and the pinned SDK makes a missing
 * runtime fail loudly here instead of the suite quietly passing without ever
 * decoding a bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoulIconImportRoboTest {

    /** A real 2000x1500 PNG — 4:3, and big enough that the sampler must step down. */
    private fun bigPng(): ByteArray = CtxKit.png(2000, 1500)

    @Test
    fun `a large 4 by 3 picture imports, keeps its ratio and is stored at 256`() {
        val png = bigPng()
        val result = SoulIcon.importFrom { ByteArrayInputStream(png) }
        assertTrue("a 2000x1500 PNG must import, got $result", result is SoulIcon.EncodeResult.Success)
        val stored = (result as SoulIcon.EncodeResult.Success).dataUri
        assertTrue(SoulIcon.isDataUri(stored))
        assertTrue(
            "stored value must stay inside the inline cap",
            SoulIcon.fitsInline(stored.length),
        )

        val bitmap = SoulIcon.decode(stored)
        assertNotNull("the stored value must decode", bitmap)
        assertEquals(SoulIcon.STORED_PIXELS, maxOf(bitmap!!.width, bitmap.height))
        // 4:3 preserved: 256x192. A square crop on save would be the old
        // behaviour, and the avatar's ContentScale.Crop would then have no
        // spare pixels to fill the circle with.
        assertEquals(256, bitmap.width)
        assertEquals(192, bitmap.height)
    }

    @Test
    fun `importBytes accepts the same picture`() {
        val result = SoulIcon.importBytes(bigPng())
        assertTrue("bytes import must succeed, got $result", result is SoulIcon.EncodeResult.Success)
    }

    @Test
    fun `a small picture is accepted as-is rather than padded or upscaled`() {
        val result = SoulIcon.importFrom { ByteArrayInputStream(CtxKit.png(64, 48)) }
        val stored = (result as? SoulIcon.EncodeResult.Success)
            ?: throw AssertionError("64x48 must import, got $result")
        val bitmap = SoulIcon.decode(stored.dataUri)!!
        assertEquals(64, bitmap.width)
        assertEquals(48, bitmap.height)
    }

    @Test
    fun `the importer opens the stream twice and never reuses a closed one`() {
        val png = bigPng()
        var opens = 0
        val result = SoulIcon.importFrom {
            opens++
            ByteArrayInputStream(png)
        }
        assertTrue(result is SoulIcon.EncodeResult.Success)
        assertEquals("header pass + decode pass", 2, opens)
    }

    @Test
    fun `no picture is refused for its byte size`() {
        // The exact regression: an ordinary photo used to come back as
        // TOO_LARGE. Anything decodable must now be stored.
        for (dims in listOf(1200 to 900, 2000 to 1500, 3000 to 2000)) {
            val png = CtxKit.png(dims.first, dims.second)
            val result = SoulIcon.importFrom { ByteArrayInputStream(png) }
            assertTrue(
                "${dims.first}x${dims.second} (${png.size} bytes) must not be refused, got $result",
                result is SoulIcon.EncodeResult.Success,
            )
        }
    }

    // ── failures that must SURVIVE the removal of the size cap ───────────

    @Test
    fun `garbage is still unreadable`() {
        val result = SoulIcon.importFrom { ByteArrayInputStream(ByteArray(512) { (it % 251).toByte() }) }
        assertEquals(SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE), result)
    }

    @Test
    fun `an io failure is still unreadable`() {
        val result = SoulIcon.importFrom { null }
        assertEquals(SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE), result)
    }

    @Test
    fun `a stream that throws on open is unreadable, not a crash`() {
        val result = SoulIcon.importFrom { throw java.io.IOException("content provider died") }
        assertEquals(SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE), result)
    }

    @Test
    fun `a truncated stream is unreadable`() {
        val truncated = bigPng().copyOfRange(0, 64)
        val result = SoulIcon.importFrom { ByteArrayInputStream(truncated) }
        assertEquals(SoulIcon.EncodeResult.Failure(SoulIcon.Rejection.UNREADABLE), result)
    }

    // ── alpha margins, on real pixels ───────────────────────────────────

    /** [content]x[content] of opaque red centred in a transparent [pad]-wide margin. */
    private fun paddedBitmap(content: Int, pad: Int): android.graphics.Bitmap {
        val side = content + pad * 2
        val bitmap = android.graphics.Bitmap.createBitmap(
            side, side, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.RED }
        canvas.drawRect(
            pad.toFloat(),
            pad.toFloat(),
            (pad + content).toFloat(),
            (pad + content).toFloat(),
            paint,
        )
        return bitmap
    }

    @Test
    fun `a transparent border is trimmed so the artwork fills the circle`() {
        // [T-soul-avatar-alpha-fill] Without the trim the stored image keeps
        // the empty margin, and the circle clip leaves it visible as a ring of
        // nothing between the picture and the frame — the "not filled" bug.
        val bitmap = paddedBitmap(content = 100, pad = 10)
        val result = SoulIcon.encode(bitmap)
        val stored = (result as? SoulIcon.EncodeResult.Success)
            ?: throw AssertionError("a padded PNG must import, got $result")
        val decoded = SoulIcon.decode(stored.dataUri)!!
        assertEquals("the 10px transparent margin must be gone", 100, decoded.width)
        assertEquals(100, decoded.height)
        assertEquals(
            "the corner of the stored image must now be opaque content",
            0xFF,
            (decoded.getPixel(0, 0) ushr 24) and 0xFF,
        )
    }

    @Test
    fun `interior transparency is preserved, not stripped`() {
        // A hole in the middle is part of the artwork; only the OUTER margin
        // may be removed.
        val bitmap = android.graphics.Bitmap.createBitmap(
            60, 60, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLUE }
        canvas.drawRect(0f, 0f, 60f, 60f, paint)
        val clear = android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        canvas.drawRect(20f, 20f, 40f, 40f, clear)

        val result = SoulIcon.encode(bitmap)
        val stored = (result as? SoulIcon.EncodeResult.Success)
            ?: throw AssertionError("a holed bitmap must import, got $result")
        val decoded = SoulIcon.decode(stored.dataUri)!!
        assertEquals("no outer margin exists, so nothing may be cropped", 60, decoded.width)
        assertEquals("the hole must survive", 0, (decoded.getPixel(30, 30) ushr 24) and 0xFF)
    }

    @Test
    fun `an image that is entirely transparent is stored, not refused`() {
        val empty = android.graphics.Bitmap.createBitmap(
            40, 40, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val result = SoulIcon.encode(empty)
        assertTrue("no size/empty verdict may be added, got $result", result is SoulIcon.EncodeResult.Success)
    }
}
