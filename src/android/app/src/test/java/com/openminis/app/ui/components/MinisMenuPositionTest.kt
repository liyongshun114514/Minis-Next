package com.openminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.basic.PopupPositionProvider

/**
 * [T-android-menu-screen-margin] MinisMenu placement keeps an 8dp screen margin
 * (it used to sit flush against the edge), still honours alignEnd / offset /
 * flip-above, and never inverts the clamp range.
 */
class MinisMenuPositionTest {

    // Pixel 4a-like window in px; margin = 8dp at 2.75 px/dp ≈ 22px.
    private val window = IntSize(1080, 2000)
    private val m = 22
    private val popup = IntSize(600, 800)

    private fun pos(
        anchor: IntRect, popupSize: IntSize = popup, alignEnd: Boolean = false,
        offX: Int = 0, offY: Int = 0, win: IntSize = window,
    ): IntOffset = MinisMenuPosition.compute(
        anchor, IntRect(0, 0, win.width, win.height), popupSize, alignEnd, offX, offY, m, m,
    )

    @Test
    fun `default anchors the popup's left edge on the anchor's left edge`() {
        assertEquals(IntOffset(100, 300), pos(IntRect(100, 200, 200, 300)))
    }

    @Test
    fun `alignEnd anchors the popup's right edge on the anchor's right edge`() {
        assertEquals(IntOffset(800 - 600, 300), pos(IntRect(700, 200, 800, 300), alignEnd = true))
    }

    @Test
    fun `an overflow on the right stops at the margin, not flush with the edge`() {
        // The reported case: the top-bar menu ran to 2px from the right edge.
        val p = pos(IntRect(1000, 100, 1070, 180))
        assertEquals(window.width - m - popup.width, p.x)
    }

    @Test
    fun `an overflow on the left stops at the margin`() {
        assertEquals(m, pos(IntRect(0, 100, 50, 180)).x)
        assertEquals(m, pos(IntRect(300, 100, 400, 180), alignEnd = true).x) // 400 - 600 < margin
    }

    @Test
    fun `no room below flips above, and the flipped menu still respects the margin`() {
        val anchor = IntRect(100, 1700, 200, 1780)
        val p = pos(anchor)
        assertEquals(1700 - popup.height, p.y) // above the anchor
        assertTrue(p.y >= m)
        // Fits neither below (400 + 1700 > 2000 - 22) nor above (300 - 1700 < 22):
        // stays below and slides up to the bottom margin.
        val tall = IntSize(600, 1700)
        val q = pos(IntRect(100, 300, 200, 400), popupSize = tall)
        assertEquals(window.height - m - tall.height, q.y)
        assertTrue(q.y >= m)
    }

    @Test
    fun `fitting below needs the bottom margin too`() {
        // belowY + h = 1190 + 800 = 1990: inside the window, but inside the 22px margin.
        val p = pos(IntRect(100, 1100, 200, 1190))
        assertEquals("flipped above rather than touching the bottom edge", 1100 - 800, p.y)
    }

    @Test
    fun `a popup larger than the space between the margins is centred without throwing`() {
        val huge = IntSize(1070, 1990)
        val p = pos(IntRect(500, 500, 600, 600), popupSize = huge)
        assertEquals((1080 - 1070) / 2, p.x)
        assertEquals((2000 - 1990) / 2, p.y)
        // Larger than the whole window: still no exception, still centred.
        val bigger = pos(IntRect(0, 0, 10, 10), popupSize = IntSize(1200, 2400))
        assertEquals(IntOffset(-60, -200), bigger)
    }

    @Test
    fun `offset keeps its meaning - finger anchor and the 6dp bubble gap`() {
        // Session long-press: zero-size anchor at the row origin, menu at the finger.
        val finger = pos(IntRect(0, 400, 0, 400), offX = 300, offY = 120)
        assertEquals(IntOffset(300, 520), finger)
        // Message bubble menu: 6dp (≈16px) below the bubble, right-aligned.
        val bubble = pos(IntRect(400, 600, 1000, 900), alignEnd = true, offY = 16)
        assertEquals(IntOffset(1000 - 600, 916), bubble)
        // Flipped above, the offset is the gap above the anchor.
        val flipped = pos(IntRect(100, 1500, 200, 1600), offY = 16)
        assertEquals(1500 - 800 - 16, flipped.y)
    }

    @Test
    fun `glass point anchor preserves physical placement in both text directions`() {
        val source = IntRect(700, 200, 1000, 300)
        for (direction in LayoutDirection.entries) {
            val anchor = MinisMenuPosition.glassAnchor(
                source, IntRect(0, 0, window.width, window.height), popup,
                alignEnd = true, offsetX = -16, offsetY = 16, margin = m,
                preferAbove = false, layoutDirection = direction,
            )
            val expected = pos(source, alignEnd = true, offX = -16, offY = 16)
            val x = if (direction == LayoutDirection.Ltr) anchor.right - popup.width else anchor.left
            assertEquals(expected.x.toFloat(), x, 0f)
            assertEquals(expected.y.toFloat(), anchor.top, 0f)
        }
    }

    @Test
    fun `glass composer anchor prefers above and stays clear of IME and system insets`() {
        val safe = IntRect(32, 96, 1020, 1200)
        val source = IntRect(950, 1100, 1000, 1150)
        val anchor = MinisMenuPosition.glassAnchor(
            source, safe, popup, alignEnd = true, offsetX = 0, offsetY = 16,
            margin = m, preferAbove = true, layoutDirection = LayoutDirection.Ltr,
        )
        assertEquals((safe.right - m).toFloat(), anchor.right, 0f)
        assertEquals((source.top - popup.height - 16).toFloat(), anchor.top, 0f)
        assertTrue(anchor.top >= safe.top + m)
        assertTrue(anchor.top + popup.height <= safe.bottom - m)
    }

    @Test
    fun `thinking panel stays above the composer boundary in both text directions`() {
        val panel = IntSize(900, 280)
        for (bottom in listOf(1600, 980)) {
            val safe = IntRect(0, 96, 1080, bottom)
            for (direction in LayoutDirection.entries) {
                val anchor = MinisMenuPosition.glassAnchor(
                    IntRect(900, bottom, 900, bottom), safe, panel,
                    alignEnd = true, offsetX = 0, offsetY = 0, margin = m,
                    preferAbove = true, layoutDirection = direction,
                )
                assertTrue("whole panel clears composer/IME", anchor.top + panel.height <= bottom - m)
                assertTrue("panel remains under status bar", anchor.top >= safe.top + m)
            }
        }
    }

    @Test
    fun `glass long press anchor retains finger position and flips near bottom`() {
        val safe = IntRect(0, 96, 1080, 1920)
        val anchor = MinisMenuPosition.glassAnchor(
            IntRect(0, 400, 0, 400), safe, popup, alignEnd = false, offsetX = 300, offsetY = 120,
            margin = m, preferAbove = false, layoutDirection = LayoutDirection.Ltr,
        )
        assertEquals(900f, anchor.right, 0f)
        assertEquals(520f, anchor.top, 0f)
        val flipped = MinisMenuPosition.glassAnchor(
            IntRect(800, 1700, 801, 1701), safe, popup, alignEnd = true, offsetX = 0, offsetY = 16,
            margin = m, preferAbove = false, layoutDirection = LayoutDirection.Ltr,
        )
        assertEquals((1700 - popup.height - 16).toFloat(), flipped.top, 0f)
    }

    @Test
    fun `clampOrCenter never inverts its range`() {
        assertEquals(22, MinisMenuPosition.clampOrCenter(-50, 100, 1080, 22))
        assertEquals(1080 - 22 - 100, MinisMenuPosition.clampOrCenter(5000, 100, 1080, 22))
        assertEquals((300 - 290) / 2, MinisMenuPosition.clampOrCenter(0, 290, 300, 22)) // max(-12) < margin
    }

    @Test
    fun `Miuix provider respects status bar cutout and side insets`() {
        val provider = MinisMenuPosition.provider(alignEnd = true, offsetX = 0, offsetY = 16)
        val p = provider.calculatePosition(
            anchorBounds = IntRect(1010, 50, 1060, 80),
            windowBounds = IntRect(32, 96, 1020, 1920),
            layoutDirection = LayoutDirection.Rtl,
            popupContentSize = popup,
            popupMargin = IntRect(m, m, m, m),
            alignment = PopupPositionProvider.Align.Start,
        )
        assertEquals(IntOffset(1020 - m - popup.width, 96 + m), p)
    }
}
