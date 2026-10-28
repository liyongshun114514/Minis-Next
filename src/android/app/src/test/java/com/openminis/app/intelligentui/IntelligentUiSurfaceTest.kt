package com.openminis.app.intelligentui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
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
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                Box(Modifier.size(280.dp, 400.dp).background(behind).testTag("surface-host")) {
                    IntelligentUiCard(block, true, {}, {})
                }
            }
        }
        compose.onNodeWithText("Native content").assertIsDisplayed()
        val card = compose.onNodeWithTag("ui-native-card")
        val cardImage = card.captureToImage().toPixelMap()
        assertColor(surface, cardImage[cardImage.width / 2, cardImage.height - 5])
        val host = compose.onNodeWithTag("surface-host")
        val pixels = host.captureToImage().toPixelMap()
        val bottom = (card.fetchSemanticsNode().boundsInRoot.bottom - host.fetchSemanticsNode().boundsInRoot.top).toInt()
        for (delta in listOf(8, 16, 24)) {
            val y = bottom + delta
            assertTrue("Fixture must include space below card", y < pixels.height)
            assertColor(behind, pixels[pixels.width / 2, y])
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
