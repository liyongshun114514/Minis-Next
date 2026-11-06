package com.openminis.app.ui.chat

import androidx.compose.ui.unit.dp
import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-toolbar-gap] The last row sits the same distance above the
 * floating tool bar whether or not Tool preview draws its thumbnail.
 */
class ToolbarGapTest {

    private fun reserve(preview: Boolean) =
        FloatingToolBarMetrics.visibleTop(preview) + FloatingToolBarMetrics.ROW_GAP

    @Test fun `the gap above the overlay's visible top is the same in both states`() {
        assertEquals("preview ON keeps the reserve it always had (65 + 14)", 79.dp, reserve(true))
        assertEquals("preview OFF reserves for the 38dp bar, not the 65dp thumbnail", 52.dp, reserve(false))
        assertEquals(reserve(true) - FloatingToolBarMetrics.visibleTop(true), reserve(false) - FloatingToolBarMetrics.visibleTop(false))
        assertEquals("the difference is exactly the thumbnail overhang", 27.dp, reserve(true) - reserve(false))
    }

    @Test fun `count entry uses existing top right space without increasing preview height`() {
        assertEquals(27.dp, FloatingToolBarMetrics.STATUS_ENTRY_HEIGHT)
        assertEquals(108.dp, FloatingToolBarMetrics.STATUS_ENTRY_START)
        assertEquals(0.dp, FloatingToolBarMetrics.THUMBNAIL_INSET)
        assertEquals(71.dp, FloatingToolBarMetrics.visibleTopWithStatus(true, true))
        assertEquals(FloatingToolBarMetrics.visibleTop(true), FloatingToolBarMetrics.visibleTopWithStatus(true, true))
        assertEquals(71.dp, FloatingToolBarMetrics.visibleTopWithStatus(false, true))
        assertEquals(44.dp, FloatingToolBarMetrics.visibleTopWithStatus(false, false))
        val bar = ProductionSources.read("ui/chat/ChatComposerWidgets.kt")
        assertTrue(bar.contains(".height(barHeight + topSlotHeight)"))
        assertTrue(bar.contains(".align(Alignment.TopEnd)"))
        assertTrue(bar.contains("FloatingToolBarMetrics.STATUS_ENTRY_START"))
        assertTrue(bar.contains(".height(FloatingToolBarMetrics.STATUS_ENTRY_HEIGHT)"))
        assertFalse("old padding displaced thumbnail below its intended top", bar.contains(".padding(top = if (previewEnabled) thumbnailOverhang"))
    }

    @Test fun `thumbnail and work text follow the composer left edge`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        val overlay = screen.substringAfter(".onGloballyPositioned { toolBarHeightPx = it.size.height }").substringBefore("Column {")
        assertTrue(overlay.contains(".padding(horizontal = ComposerSurfaceGeometry.SIDE_INSET_DP.dp)"))
        assertTrue(overlay.contains("WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)"))
        val bar = ProductionSources.read("ui/chat/ChatComposerWidgets.kt")
        assertTrue(bar.contains("val barStartPadding = if (previewEnabled) FloatingToolBarMetrics.STATUS_ENTRY_START else 12.dp"))
        assertTrue(bar.contains(".padding(start = if (previewEnabled) FloatingToolBarMetrics.STATUS_ENTRY_START else 0.dp)"))
    }

    @Test fun `the list and the bar read one geometry and one preview flag`() {
        val screen = ProductionSources.read("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("FloatingToolBarMetrics.visibleTop(toolPreviewEnabled) + FloatingToolBarMetrics.ROW_GAP"))
        assertFalse("no reserve pinned to the thumbnail any more", screen.contains("val visualOverlayHeight = 65.dp"))
        assertTrue(screen.contains(".padding(bottom = FloatingToolBarMetrics.WRAPPER_BOTTOM_PADDING),"))
        assertTrue("a toggle re-pins the list", screen.contains("LaunchedEffect(bottomReserve) {"))
        val bar = ProductionSources.read("ui/chat/ChatComposerWidgets.kt")
        assertTrue(bar.contains("val thumbnailHeight = FloatingToolBarMetrics.THUMBNAIL_HEIGHT"))
        assertTrue(bar.contains("val barHeight = FloatingToolBarMetrics.BAR_HEIGHT"))
    }
}
