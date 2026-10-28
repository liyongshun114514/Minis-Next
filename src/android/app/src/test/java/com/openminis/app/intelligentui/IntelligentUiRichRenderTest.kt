package com.openminis.app.intelligentui

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Real Compose semantics/gesture tests on Robolectric (sdk 35), matching the existing
 * IntelligentUiRenderTest style. Remote images are replaced by a local surface so no test
 * touches the network; video is never auto-instantiated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IntelligentUiRichRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun content(json: String): @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalUiRichImageOverride provides { _, description, modifier ->
                Box(
                    modifier
                        .background(Color.LightGray)
                        .semantics { contentDescription = description ?: "test-image" },
                )
            },
        ) {
            MaterialTheme { UiRichDisplay(JSONObject(json)) }
        }
    }

    private fun render(json: String) = compose.setContent(content(json))

    @Test
    fun cardRendersNativeContentAndExpandsDetails() {
        render(
            """{"type":"card","title":"Order status","text":"Ships today","value":"42","unit":"CNY",
               "variant":"product","icon":"truck","expandable":true,"details":"Tracking 123"}""",
        )
        compose.onNodeWithTag("ui-rich-card-product").assertExists()
        compose.onNodeWithText("Order status").assertIsDisplayed()
        compose.onNodeWithText("Ships today").assertIsDisplayed()
        compose.onNodeWithText("42 CNY").assertIsDisplayed()
        // Unknown icon falls back to a text badge instead of a broken image.
        compose.onNodeWithTag("ui-rich-card-badge").assertExists()
        compose.onNodeWithTag("ui-rich-card-details").assertDoesNotExist()

        compose.onNodeWithTag("ui-rich-card-toggle").performClick()
        compose.onNodeWithText("Tracking 123").assertIsDisplayed()
        compose.onNodeWithTag("ui-rich-card-details").assertExists()

        compose.onNodeWithTag("ui-rich-card-toggle").performClick()
        compose.onNodeWithTag("ui-rich-card-details").assertDoesNotExist()
    }

    @Test
    fun expandedCardStateSurvivesSaveRestore() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent(
            content("""{"type":"card","title":"Summary","expandable":true,"details":"Saved detail text"}"""),
        )
        compose.onNodeWithTag("ui-rich-card-details").assertDoesNotExist()
        compose.onNodeWithTag("ui-rich-card-toggle").performClick()
        compose.onNodeWithTag("ui-rich-card-details").assertExists()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("ui-rich-card-details").assertExists()
        compose.onNodeWithText("Saved detail text").assertIsDisplayed()
    }

    @Test
    fun alertUsesStatusSemantics() {
        render("""{"type":"alert","status":"warning","title":"Storage","text":"Almost full"}""")
        compose.onNodeWithTag("ui-rich-alert-warning").assertExists()
        compose.onNodeWithText("Storage").assertIsDisplayed()
        compose.onNodeWithText("Almost full").assertIsDisplayed()
    }

    @Test
    fun progressShowsExactPercentAndUnit() {
        render("""{"type":"progress","value":0.62,"label":"Upload","unit":"3/5"}""")
        compose.onNodeWithTag("ui-rich-progress-value").assertTextContains("62%", substring = true)
        compose.onNodeWithTag("ui-rich-progress-value").assertTextContains("3/5", substring = true)
        compose.onNodeWithText("Upload").assertIsDisplayed()
    }

    @Test
    fun barChartHasAxesUnitExactValuesAndTapSelection() {
        render(
            """{"type":"chart","labels":["Jan","Feb","Mar"],"values":[10,20,30],"style":"bar",
               "unit":"kg","xLabel":"Month","yLabel":"Weight"}""",
        )
        compose.onNodeWithTag("ui-rich-chart-bar").assertExists()
        compose.onNodeWithTag("ui-rich-chart-axis").assertTextContains("kg", substring = true)
        compose.onNodeWithTag("ui-rich-chart-axis").assertTextContains("Month", substring = true)
        compose.onNodeWithTag("ui-rich-chart-value-0").assertTextContains("Jan", substring = true)
        compose.onNodeWithTag("ui-rich-chart-value-0").assertTextContains("10 kg", substring = true)
        compose.onNodeWithTag("ui-rich-chart-value-2").assertTextContains("30 kg", substring = true)
        compose.onNodeWithTag("ui-rich-chart-selected").assertDoesNotExist()

        compose.onNodeWithTag("ui-rich-chart-canvas").performTouchInput { click(center) }

        compose.onNodeWithTag("ui-rich-chart-selected").assertExists()
        compose.onNodeWithTag("ui-rich-chart-selected").assertTextContains("Feb", substring = true)
        compose.onNodeWithTag("ui-rich-chart-selected").assertTextContains("20 kg", substring = true)
    }

    @Test
    fun multiSeriesLegendIsOperableAndComparesValues() {
        render(
            """{"type":"chart","labels":["Q1","Q2"],"values":[0,0],"style":"bar","unit":"k",
               "series":[{"name":"Plan A","values":[10,12]},{"name":"Plan B","values":[8,16]}]}""",
        )
        compose.onNodeWithTag("ui-rich-chart-value-0")
            .assertTextContains("Plan A 10 k", substring = true)
        compose.onNodeWithTag("ui-rich-chart-value-0")
            .assertTextContains("Plan B 8 k", substring = true)
        compose.onNodeWithTag("ui-rich-series-0").assertIsSelected()

        compose.onNodeWithTag("ui-rich-series-0").performClick()

        compose.onNodeWithTag("ui-rich-series-0").assertIsNotSelected()
        compose.onNodeWithTag("ui-rich-chart-value-0").assertTextEquals("Q1 · Plan B 8 k")
        compose.onNodeWithTag("ui-rich-chart-value-1").assertTextEquals("Q2 · Plan B 16 k")
    }

    @Test
    fun pieChartShowsPercentagesAndNonNegativeValues() {
        render("""{"type":"chart","labels":["Used","Free"],"values":[1,3],"style":"pie","unit":"GB"}""")
        compose.onNodeWithTag("ui-rich-chart-legend-1").assertTextContains("75%", substring = true)
        compose.onNodeWithTag("ui-rich-chart-legend-1").assertTextContains("3 GB", substring = true)
        compose.onNodeWithTag("ui-rich-chart-value-0").assertTextContains("25%", substring = true)
    }

    @Test
    fun trendReusesLineChartAndKeepsExactValues() {
        render("""{"type":"trend","labels":["Mon","Tue"],"values":[1.5,2],"unit":"GB"}""")
        compose.onNodeWithTag("ui-rich-chart-line").assertExists()
        compose.onNodeWithTag("ui-rich-chart-value-0").assertTextContains("1.5 GB", substring = true)
        compose.onNodeWithTag("ui-rich-chart-value-1").assertTextContains("2 GB", substring = true)
    }

    @Test
    fun beforeAfterComparesEachMetric() {
        render("""{"type":"before_after","labels":["CPU","RAM"],"before":[10,20],"after":[15,30],"unit":"%"}""")
        compose.onNodeWithTag("ui-rich-before-after").assertExists()
        compose.onNodeWithTag("ui-rich-chart-bar").assertExists()
        compose.onNodeWithTag("ui-rich-before-after-0").assertTextContains("10 %", substring = true)
        compose.onNodeWithTag("ui-rich-before-after-0").assertTextContains("15 %", substring = true)
        compose.onNodeWithTag("ui-rich-before-after-0").assertTextContains("+50%", substring = true)
    }

    @Test
    fun galleryNextPreviousAndThumbnailsChangeSelection() {
        render(
            """{"type":"gallery","style":"carousel","images":[
               {"url":"https://example.com/a.jpg","caption":"First","description":"First description"},
               {"url":"https://example.com/b.jpg","caption":"Second","description":"Second description"}]}""",
        )
        compose.onNodeWithTag("ui-rich-gallery-caption").assertTextEquals("First")
        compose.onNodeWithTag("ui-rich-gallery-prev").assertIsNotEnabled()

        compose.onNodeWithTag("ui-rich-gallery-next").performClick()
        compose.onNodeWithTag("ui-rich-gallery-caption").assertTextEquals("Second")
        compose.onNodeWithTag("ui-rich-gallery-counter").assertTextEquals("2 / 2")
        compose.onNodeWithTag("ui-rich-gallery-next").assertIsNotEnabled()

        compose.onNodeWithTag("ui-rich-gallery-thumb-0").performClick()
        compose.onNodeWithTag("ui-rich-gallery-caption").assertTextEquals("First")
        compose.onNodeWithTag("ui-rich-gallery-counter").assertTextEquals("1 / 2")
    }

    @Test
    fun videoNeverAutoplaysAndExternalPlayerUsesActionView() {
        val app = RuntimeEnvironment.getApplication() as Application
        val url = "https://example.com/clip.mp4"
        render("""{"type":"video","url":"$url","poster":"https://example.com/p.jpg","caption":"Demo clip"}""")

        // Not started, so no native VideoView exists yet; only the explicit play affordance.
        compose.onNodeWithTag("ui-rich-video-play").assertExists()
        compose.onNodeWithTag("ui-rich-video-stop").assertDoesNotExist()
        compose.onNodeWithText("Demo clip").assertIsDisplayed()

        compose.onNodeWithTag("ui-rich-video-external").performClick()

        var started: Intent? = null
        repeat(8) {
            val next = shadowOf(app).nextStartedActivity ?: return@repeat
            if (next.action == Intent.ACTION_VIEW) started = next
        }
        assertNotNull("External player must receive an ACTION_VIEW intent", started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(url, started!!.dataString)
    }

    @Test
    fun nonHttpsMediaIsRejectedInsteadOfLoaded() {
        render("""{"type":"image","url":"file:///etc/passwd","caption":"local"}""")
        compose.onNodeWithTag("ui-rich-image-invalid").assertExists()
        compose.onNodeWithTag("ui-rich-image-caption").assertDoesNotExist()
    }

    @Test
    fun galleryDropsNonHttpsItems() {
        render("""{"type":"gallery","images":[{"url":"file:///tmp/a.jpg","caption":"Nope"}]}""")
        compose.onNodeWithTag("ui-rich-gallery-empty").assertExists()
    }
}
