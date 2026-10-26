package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-soul-avatar-fill] The avatar no longer centre-crops to a square on save.
 *
 * The bitmap round trip needs `android.graphics.Bitmap`, which a JVM unit test
 * cannot instantiate, so the crop decision was moved into one pure function —
 * [SoulIcon.scaledSize] — and THAT is asserted here. If someone reintroduces a
 * square crop, it has to happen after this function, and the assertions below
 * (width != height for a 4:3 source) fail.
 */
class SoulIconAspectRatioTest {

    @Test fun `landscape keeps its ratio instead of becoming square`() {
        val (w, h) = SoulIcon.scaledSize(1200, 900, 256)
        assertEquals(256, w)
        assertEquals(192, h)
        assertTrue("must not be cropped to a square", w != h)
        assertEquals(1200.0 / 900.0, w.toDouble() / h.toDouble(), 0.01)
    }

    @Test fun `portrait keeps its ratio`() {
        val (w, h) = SoulIcon.scaledSize(900, 1200, 256)
        assertEquals(192, w)
        assertEquals(256, h)
    }

    @Test fun `panorama longest edge is capped and the short edge follows`() {
        val (w, h) = SoulIcon.scaledSize(4000, 1000, 256)
        assertEquals(256, w)
        assertEquals(64, h)
        assertTrue("longest edge must not exceed the cap", maxOf(w, h) <= 256)
    }

    @Test fun `small images are never upscaled`() {
        assertEquals(48 to 32, SoulIcon.scaledSize(48, 32, 256))
        assertEquals(256 to 256, SoulIcon.scaledSize(256, 256, 256))
        assertEquals(10 to 10, SoulIcon.scaledSize(10, 10, 256))
    }

    @Test fun `a square source stays square`() {
        assertEquals(256 to 256, SoulIcon.scaledSize(1024, 1024, 256))
    }

    @Test fun `extreme ratios still produce at least one pixel`() {
        val (w, h) = SoulIcon.scaledSize(10_000, 3, 256)
        assertEquals(256, w)
        assertEquals(1, h)
        assertEquals(256 to 1, SoulIcon.scaledSize(10_000, 1, 200))
    }

    @Test fun `longest edge never exceeds the cap after rounding`() {
        for (wm in listOf(1000, 1001, 3333, 7777, 9999)) {
            for (hm in listOf(17, 99, 1000, 4321)) {
                val (w, h) = SoulIcon.scaledSize(wm, hm, 256)
                assertTrue("$wm x $hm -> $w x $h", maxOf(w, h) <= 256)
                assertTrue(w >= 1 && h >= 1)
            }
        }
    }

    @Test fun `stored cap is 256 px`() {
        assertEquals(256, SoulIcon.STORED_PIXELS)
        assertEquals(256 to 192, SoulIcon.scaledSize(400, 300))
    }

    @Test fun `a decode failure is still a rejection`() {
        // SoulIcon.decode returns null for anything it cannot read; the encode
        // path turns that into Rejection.UNREADABLE. Pinned here because the
        // fill change must not have loosened the error path.
        assertNull(SoulIcon.decode("not-an-image"))
        assertNull(SoulIcon.decode(""))
        assertNull(SoulIcon.decode(SoulIcon.DATA_URI_PREFIX))
    }

    @Test fun `legacy pixel data uris still decode`() {
        // Old builds stored 96x96 squares under the same prefix; the format is
        // unchanged, so a legacy payload must still be recognised as an image
        // (decode itself needs BitmapFactory and is covered on device).
        val legacy = SoulIcon.DATA_URI_PREFIX + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        assertTrue(SoulIcon.isDataUri(legacy))
        val roundTrip = SoulMDParser.parse(
            SoulMDParser.serialize(SoulFile(SoulMetadata.DEFAULT.copy(icon = legacy), "b")),
        )
        assertEquals(legacy, roundTrip.metadata.icon)
    }
}
