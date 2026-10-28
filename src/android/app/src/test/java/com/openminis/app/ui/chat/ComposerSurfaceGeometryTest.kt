package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-next-composer-surface] Checks the composer's bottom clearance against the
 * three real states: gesture bar hidden (immersive), navigation bar shown, and
 * the IME open (the outer layout already applies `imePadding()`). The bottom
 * gap must equal the 12dp left/right gap of the card.
 */
class ComposerSurfaceGeometryTest {

    @Test
    fun `hidden navigation bar keeps the same 12dp gap as left and right`() {
        val bottom = ComposerSurfaceGeometry.bottomPaddingDp(
            navigationBarBottomDp = 0f,
            imeVisible = false,
        )
        assertEquals(ComposerSurfaceGeometry.SIDE_INSET_DP, bottom, 0.001f)
        assertEquals(12f, bottom, 0.001f)
    }

    @Test
    fun `visible gesture bar adds its inset once plus the same gap`() {
        // 24dp gesture bar + 12dp gap. NOT 24dp minimum + 24dp bar + 12dp.
        assertEquals(
            36f,
            ComposerSurfaceGeometry.bottomPaddingDp(navigationBarBottomDp = 24f, imeVisible = false),
            0.001f,
        )
    }

    @Test
    fun `three button navigation bar keeps its larger inset plus the gap`() {
        assertEquals(
            48f + ComposerSurfaceGeometry.SIDE_INSET_DP,
            ComposerSurfaceGeometry.bottomPaddingDp(navigationBarBottomDp = 48f, imeVisible = false),
            0.001f,
        )
    }

    @Test
    fun `ime open adds only the 12dp gap because imePadding already lifts the composer`() {
        assertEquals(
            ComposerSurfaceGeometry.SIDE_INSET_DP,
            ComposerSurfaceGeometry.bottomPaddingDp(navigationBarBottomDp = 24f, imeVisible = true),
            0.001f,
        )
        assertEquals(
            ComposerSurfaceGeometry.SIDE_INSET_DP,
            ComposerSurfaceGeometry.bottomPaddingDp(navigationBarBottomDp = 0f, imeVisible = true),
            0.001f,
        )
    }

    @Test
    fun `negative or unknown insets behave like a hidden bar`() {
        assertEquals(
            ComposerSurfaceGeometry.bottomPaddingDp(0f, imeVisible = false),
            ComposerSurfaceGeometry.bottomPaddingDp(-12f, imeVisible = false),
            0.001f,
        )
    }

    @Test
    fun `corner radius stays 40dp and is not a capsule`() {
        assertEquals(40f, ComposerSurfaceGeometry.CORNER_RADIUS_DP, 0.001f)
        assertTrue(ComposerSurfaceGeometry.CORNER_RADIUS_DP < 48f)
        assertEquals(12f, ComposerSurfaceGeometry.SIDE_INSET_DP, 0.001f)
    }
}
