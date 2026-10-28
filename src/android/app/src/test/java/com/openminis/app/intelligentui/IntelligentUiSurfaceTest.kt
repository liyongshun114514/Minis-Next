package com.openminis.app.intelligentui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ToolBlockStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual native pixels: no tonal darkening inside, no slab beneath the card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntelligentUiSurfaceTest {
    @get:Rule val compose = createComposeRule()
    private fun checkSurface(dark: Boolean) {
        val surface = if (dark) Color(0xFF19202A) else Color.White
        val behind = if (dark) Color(0xFF080B12) else Color(0xFFE4F2FF)
        val colors = if (dark) darkColorScheme(surface = surface) else lightColorScheme(surface = surface)
        val block = AssistantBlock("surface", "tool_use", toolName = IntelligentUiTool.NAME,
            toolStatus = ToolBlockStatus.SUCCESS,
            content = """{"native_ui":{"version":1,"title":"Surface regression","nodes":[{"id":"t","type":"text","text":"Native content"}]}}""")
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            MaterialTheme(colorScheme = colors) {
                Box(Modifier.size(280.dp, 400.dp).background(behind).testTag("surface-host")) {
                    IntelligentUiCard(block, true, {}, {})
                }
            }
        }
        compose.onNodeWithText("Native content").assertIsDisplayed()
        val cardBounds = compose.onNodeWithTag("ui-native-card").fetchSemanticsNode().boundsInRoot
        val hostBounds = compose.onNodeWithTag("surface-host").fetchSemanticsNode().boundsInRoot
        // captureToImage uses asynchronous PixelCopy, whose native callback does
        // not complete on this Robolectric runner. Draw the REAL composed view
        // into a native Skia bitmap instead; no fake pixels or skipped checks.
        lateinit var pixels: Bitmap
        compose.runOnIdle {
            pixels = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(pixels))
        }
        try {
            val x = cardBounds.center.x.toInt()
            assertColor(surface, Color(pixels.getPixel(x, cardBounds.bottom.toInt() - 5)))
            for (delta in listOf(8, 16, 24)) {
                val y = cardBounds.bottom.toInt() + delta
                assertTrue("Fixture must include space below card", y < hostBounds.bottom && y < pixels.height)
                assertColor(behind, Color(pixels.getPixel(x, y)))
            }
        } finally {
            pixels.recycle()
        }
    }
    private fun assertColor(expected: Color, actual: Color) {
        assertEquals(expected.red.toDouble(), actual.red.toDouble(), 0.015)
        assertEquals(expected.green.toDouble(), actual.green.toDouble(), 0.015)
        assertEquals(expected.blue.toDouble(), actual.blue.toDouble(), 0.015)
    }
    @Test fun lightCardHasNoDarkSlabOrTonalOverlay() = checkSurface(false)
    @Test fun darkCardKeepsThemeSurfaceAndBackground() = checkSurface(true)
}
