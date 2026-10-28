package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-next-composer-surface] Checks the composer's bottom clearance against the
 * three real states: gesture bar hidden (immersive), navigation bar shown, and
 * the IME open (the outer layout already applies `imePadding()`). The bottom
 * gap is 16dp while the original 12dp side gutters remain unchanged.
 */
class ComposerSurfaceGeometryTest {

    @Test
    fun `hidden navigation bar lifts composer 16dp and preserves 12dp sides`() {
        val bottom = ComposerSurfaceGeometry.bottomPaddingDp(
            navigationBarBottomDp = 0f,
            imeVisible = false,
        )
        assertEquals(16f, bottom, 0.001f)
        assertEquals(12f, ComposerSurfaceGeometry.SIDE_INSET_DP, 0.001f)
    }

    @Test
    fun `visible gesture bar adds its inset once plus the same gap`() {
        // 24dp gesture bar + 16dp gap; the navigation inset is counted once.
        assertEquals(
            40f,
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
    fun `ime open adds only the 16dp gap because imePadding already lifts the composer`() {
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
    fun `restore original 20dp radius without undoing the extra bottom lift`() {
        assertEquals(20f, ComposerSurfaceGeometry.CORNER_RADIUS_DP, 0.001f)
        assertEquals(12f, ComposerSurfaceGeometry.SIDE_INSET_DP, 0.001f)
        assertEquals(16f, ComposerSurfaceGeometry.BOTTOM_INSET_DP, 0.001f)
    }
}
