package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-next-composer-surface] Checks the composer's bottom clearance against the
 * three real states: gesture bar hidden (immersive), navigation bar shown, and
 * the IME open (the outer layout already applies `imePadding()`). The bottom
 * gap is 12dp and the thin-glass card uses 10dp side gutters.
 */
class ComposerSurfaceGeometryTest {

    @Test
    fun `hidden navigation bar lifts composer 12dp and preserves 10dp sides`() {
        val bottom = ComposerSurfaceGeometry.bottomPaddingDp(
            navigationBarBottomDp = 0f,
            imeVisible = false,
        )
        assertEquals(12f, bottom, 0.001f)
        assertEquals(10f, ComposerSurfaceGeometry.SIDE_INSET_DP, 0.001f)
    }

    @Test
    fun `visible gesture bar adds its inset once plus the same gap`() {
        // 24dp gesture bar + 12dp gap; the navigation inset is counted once.
        assertEquals(
            36f,
            ComposerSurfaceGeometry.bottomPaddingDp(navigationBarBottomDp = 24f, imeVisible = false),
            0.001f,
        )
    }

    @Test
    fun `three button navigation bar keeps its larger inset plus the gap`() {
        assertEquals(
            48f + ComposerSurfaceGeometry.BOTTOM_INSET_DP,
            ComposerSurfaceGeometry.bottomPaddingDp(navigationBarBottomDp = 48f, imeVisible = false),
            0.001f,
        )
    }

    @Test
    fun `ime open adds only the 12dp gap because imePadding already lifts the composer`() {
        assertEquals(
            ComposerSurfaceGeometry.BOTTOM_INSET_DP,
            ComposerSurfaceGeometry.bottomPaddingDp(navigationBarBottomDp = 24f, imeVisible = true),
            0.001f,
        )
        assertEquals(
            ComposerSurfaceGeometry.BOTTOM_INSET_DP,
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
    fun `composer keeps a 56dp baseline editor region`() {
        assertEquals(56f, ComposerSurfaceGeometry.EDITOR_MIN_HEIGHT_DP, 0.001f)
    }

    @Test
    fun `eta proportions use 24dp radius and 10dp gutters with 12dp clearance`() {
        assertEquals(24f, ComposerSurfaceGeometry.CORNER_RADIUS_DP, 0.001f)
        assertEquals(10f, ComposerSurfaceGeometry.SIDE_INSET_DP, 0.001f)
        assertEquals(12f, ComposerSurfaceGeometry.BOTTOM_INSET_DP, 0.001f)
    }
}
