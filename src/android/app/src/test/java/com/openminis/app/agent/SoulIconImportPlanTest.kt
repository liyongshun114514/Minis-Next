package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-soul-avatar-import-no-size-cap] The IMPORT PLAN — pure JVM arithmetic, no
 * `android.graphics` and no Robolectric, so it runs in the plain `test` task.
 *
 * The real bitmap round trip lives in [SoulIconImportRoboTest]; keeping the
 * plan in its own file is deliberate, so this half can be compiled and run
 * without the Android stubs, the Robolectric runtime or the image fixtures.
 *
 * The old behaviour — `Rejection.TOO_LARGE` for an ordinary photo — is what
 * these assertions exist to prevent from coming back.
 */
class SoulIconImportPlanTest {

    // ── sample planning (the memory bound that replaced the size cap) ────

    @Test
    fun `an image inside the decode edge is not resampled`() {
        assertEquals(1, SoulIcon.sampleSizeFor(1024, 768))
        assertEquals(1, SoulIcon.sampleSizeFor(800, 600))
        assertEquals(1, SoulIcon.sampleSizeFor(1, 1))
    }

    @Test
    fun `one pixel over the edge halves once`() {
        assertEquals(2, SoulIcon.sampleSizeFor(1025, 768))
        assertEquals(2, SoulIcon.sampleSizeFor(2048, 2048))
    }

    @Test
    fun `a camera photo is sampled down by a power of two`() {
        // 4032x3024 (the CtxKit CAMERA preset): 4032/4 = 1008 <= 1024.
        assertEquals(4, SoulIcon.sampleSizeFor(4032, 3024))
        // 10800x8100 (a 100MP-ish scan): 10800/16 = 675.
        assertEquals(16, SoulIcon.sampleSizeFor(10800, 8100))
    }

    @Test
    fun `sample is always a power of two and never below one`() {
        for (w in listOf(1, 2, 999, 1025, 3000, 4032, 10800, 40000)) {
            for (h in listOf(1, 3, 700, 2048, 3024, 32768)) {
                val s = SoulIcon.sampleSizeFor(w, h)
                assertTrue("$w x $h -> $s must be >= 1", s >= 1)
                assertEquals("$w x $h -> $s must be a power of two", 1, Integer.bitCount(s))
            }
        }
    }

    @Test
    fun `the sampled decode then the stored encode both stay inside their bounds`() {
        val cases = listOf(
            4032 to 3024,
            10800 to 8100,
            9000 to 300,
            2760 to 21891,
            6000 to 6000,
        )
        for ((w, h) in cases) {
            val sample = SoulIcon.sampleSizeFor(w, h)
            val decodedW = w / sample
            val decodedH = h / sample
            assertTrue(
                "decoded ${decodedW}x$decodedH for ${w}x$h must fit ${SoulIcon.IMPORT_DECODE_EDGE_PX}px",
                maxOf(decodedW, decodedH) <= SoulIcon.IMPORT_DECODE_EDGE_PX,
            )
            val (sw, sh) = SoulIcon.scaledSize(decodedW, decodedH, SoulIcon.STORED_PIXELS)
            assertTrue(
                "stored ${sw}x$sh must fit ${SoulIcon.STORED_PIXELS}px",
                maxOf(sw, sh) <= SoulIcon.STORED_PIXELS,
            )
        }
    }

    @Test
    fun `a degenerate size does not divide by zero`() {
        assertEquals(1, SoulIcon.sampleSizeFor(0, 0))
        assertEquals(1, SoulIcon.sampleSizeFor(-5, 10))
    }

    @Test
    fun `the decode edge leaves room above the stored size`() {
        assertTrue(
            "decode edge must exceed the stored edge so the final resample has data to work with",
            SoulIcon.IMPORT_DECODE_EDGE_PX >= SoulIcon.STORED_PIXELS * 2,
        )
    }

    // ── the encode ladder (the storage bound) ────────────────────────────

    @Test
    fun `the ladder starts at the stored size and steps down to a floor`() {
        val ladder = SoulIcon.INLINE_EDGE_LADDER
        assertEquals(SoulIcon.STORED_PIXELS, ladder.first())
        assertEquals(32, ladder.last())
        for (i in 1 until ladder.size) {
            assertTrue("ladder must strictly decrease: ${ladder.toList()}", ladder[i] < ladder[i - 1])
        }
        assertTrue("every rung must be positive", ladder.all { it > 0 })
    }

    @Test
    fun `the inline cap is a boundary, not a rejection floor`() {
        assertTrue(SoulIcon.fitsInline(SoulIcon.MAX_DATA_URI_CHARS))
        assertTrue(SoulIcon.fitsInline(0))
        assertTrue(
            "one char over the cap must not fit",
            !SoulIcon.fitsInline(SoulIcon.MAX_DATA_URI_CHARS + 1),
        )
        // A 32px probe is far inside the cap: the ladder's last rung always
        // yields a storable value, which is why no size verdict is needed.
        assertTrue(SoulIcon.fitsInline(SoulIcon.MAX_DATA_URI_CHARS / 4))
    }

    @Test
    fun `there is no size rejection left in the enum`() {
        // [T-soul-avatar-import-no-size-cap] The constant is deleted, not just
        // unreachable, so no UI branch can offer a "picture too large" message.
        val names = SoulIcon.Rejection.entries.map { it.name }
        assertTrue(names.contains("UNREADABLE"))
        assertTrue(!names.contains("TOO_LARGE"))
        assertEquals(1, names.size)
    }

    // ── alpha margins (the "transparent padding" ring) ──────────────────

    @Test
    fun `alpha bounds ignore an imperceptible margin`() {
        val w = 5
        val h = 5
        val pixels = IntArray(w * h)
        // alpha 7 is below the visible threshold; alpha 8 is content.
        pixels[0] = 0x07000000
        pixels[2 * w + 1] = 0xFF112233.toInt()
        pixels[4 * w + 3] = 0x08000000
        val bounds = SoulIcon.alphaBounds(pixels, w, h)
        assertNotNull(bounds)
        assertEquals(listOf(1, 2, 3, 4), bounds!!.toList())
    }

    @Test
    fun `a fully transparent buffer has no bounds`() {
        assertNull(SoulIcon.alphaBounds(IntArray(4 * 4), 4, 4))
    }

    @Test
    fun `alpha bounds of an opaque buffer are the whole frame`() {
        val w = 3
        val h = 2
        val pixels = IntArray(w * h) { 0xFF000000.toInt() }
        assertEquals(listOf(0, 0, 2, 1), SoulIcon.alphaBounds(pixels, w, h)!!.toList())
    }

    @Test
    fun `alpha bounds reject a short buffer instead of reading past it`() {
        assertNull(SoulIcon.alphaBounds(IntArray(3), 4, 4))
    }
}
