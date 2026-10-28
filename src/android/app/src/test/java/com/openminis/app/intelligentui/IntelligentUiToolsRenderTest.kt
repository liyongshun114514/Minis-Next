package com.openminis.app.intelligentui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Real Compose rendering and input on Robolectric: the tools must react to actual taps and typing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IntelligentUiToolsRenderTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var previousLocale: Locale

    @Before fun useChineseLabels() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    }

    @After fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    private fun show(json: String) {
        val node = JSONObject(json)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                    UiInteractiveTool(node)
                }
            }
        }
    }

    // ------------------------------------------------------------ converter

    @Test fun converterRecalculatesOnTyping() {
        show("""{"type":"converter","value":1,"from":"m","to":"cm","dimension":"length"}""")
        compose.onNodeWithTag("ui-converter-result").assertTextEquals("100 cm")
        compose.onNodeWithTag("ui-converter-value").performTextReplacement("2.5")
        compose.onNodeWithTag("ui-converter-result").assertTextEquals("250 cm")
        compose.onNodeWithTag("ui-converter-value").performTextReplacement("abc")
        compose.onNodeWithTag("ui-tool-error").assertExists()
    }

    @Test fun converterHandlesTemperatureAndAliases() {
        show("""{"type":"converter","value":0,"from":"celsius","to":"fahrenheit","dimension":"temperature"}""")
        compose.onNodeWithTag("ui-converter-from").assertTextContains("C")
        compose.onNodeWithTag("ui-converter-result").assertTextEquals("32 F")
        compose.onNodeWithTag("ui-converter-value").performTextReplacement("-40")
        compose.onNodeWithTag("ui-converter-result").assertTextEquals("-40 F")
    }

    @Test fun converterRejectsDimensionMismatchInsteadOfGuessing() {
        show("""{"type":"converter","value":1,"from":"m","to":"kg","dimension":"length"}""")
        compose.onNodeWithTag("ui-tool-error").assertExists()
    }

    // ------------------------------------------------------------ budget

    @Test fun budgetUpdatesBalanceAndSharesWhileEditing() {
        show(
            """{"type":"budget","currency":"CNY","income":1000,"items":[
               {"label":"Rent","amount":300},{"label":"Food","amount":200}]}""",
        )
        compose.onNodeWithTag("ui-budget-balance").assertTextEquals("\u00A5500.00")
        compose.onNodeWithTag("ui-budget-percent").assertTextEquals("50%")
        compose.onNodeWithText("60%").assertExists()
        compose.onNodeWithText("40%").assertExists()

        compose.onNodeWithTag("ui-budget-income").performTextReplacement("2000")
        compose.onNodeWithTag("ui-budget-balance").assertTextEquals("\u00A51500.00")
        compose.onNodeWithTag("ui-budget-percent").assertTextEquals("75%")

        compose.onNodeWithTag("ui-budget-expense-0").performScrollTo().performTextReplacement("500")
        compose.onNodeWithTag("ui-budget-balance").assertTextEquals("\u00A51300.00")
        compose.onNodeWithTag("ui-budget-percent").assertTextEquals("65%")
    }

    @Test fun budgetMarksInvalidExpenseAndKeepsRunningTotalBounded() {
        show("""{"type":"budget","currency":"CNY","income":100,"items":[{"label":"Only","amount":150}]}""")
        compose.onNodeWithTag("ui-budget-balance").assertTextEquals("\u00A5-50.00")
        compose.onNodeWithTag("ui-budget-expense-0").performTextReplacement("oops")
        compose.onNodeWithTag("ui-tool-error").assertExists()
        compose.onNodeWithTag("ui-budget-balance").assertTextEquals("\u00A5100.00")
    }

    // ------------------------------------------------------------ compare

    @Test fun compareShowsUnitPriceAndSelectionDetail() {
        show(
            """{"type":"compare","currency":"CNY","items":[
               {"name":"Pack A","price":10,"quantity":2,"unit":"件"},
               {"name":"Pack B","price":18,"quantity":3,"unit":"件"}]}""",
        )
        compose.onNodeWithText("最低单价").assertExists()
        compose.onNodeWithText("单价 5.00 / 件", substring = true).assertExists()
        compose.onNodeWithText("单价 6.00 / 件", substring = true).assertExists()

        compose.onNodeWithTag("ui-compare-item-1").performScrollTo().performClick()
        compose.onNodeWithText("已选：Pack B").assertExists()
        compose.onNodeWithText("比同单位最低单价高 20%").assertExists()
        // Selecting is local: the card must not pretend to navigate anywhere.
        compose.onNodeWithText("本地示例数据", substring = true).assertExists()
    }

    @Test fun compareSortChipsAreLocalAndKeepInvalidRowsOutOfRanking() {
        show(
            """{"type":"compare","items":[
               {"name":"Zero","price":10,"quantity":0,"unit":"件"},
               {"name":"Valid","price":6,"quantity":2,"unit":"件"}]}""",
        )
        compose.onNodeWithTag("ui-compare-sort-1").performScrollTo().performClick()
        compose.onNodeWithTag("ui-compare-item-1").assertExists()
        compose.onNodeWithTag("ui-compare-item-0").assertExists()
    }

    // ------------------------------------------------------------ directory

    @Test fun directorySearchesFiltersAndOpensLocalDetail() {
        show(
            """{"type":"directory","currency":"CNY","items":[
               {"name":"Alpha","text":"Alpha detail text","category":"Tools","price":10},
               {"name":"Beta","text":"Beta detail text","category":"Docs","price":30},
               {"name":"Gamma","text":"Gamma detail text","category":"Tools"}]}""",
        )
        compose.onNodeWithTag("ui-directory-count").assertTextEquals("3 项")
        compose.onNodeWithTag("ui-directory-search").performTextReplacement("bet")
        compose.onNodeWithTag("ui-directory-count").assertTextEquals("1 项")
        compose.onNodeWithText("Alpha").assertDoesNotExist()
        compose.onNodeWithText("Beta").assertExists()

        compose.onNodeWithTag("ui-directory-search").performTextReplacement("")
        compose.onNodeWithTag("ui-directory-cat-1").performScrollTo().performClick()
        compose.onNodeWithTag("ui-directory-count").assertTextEquals("1 项")
        compose.onNodeWithText("Beta").assertExists()

        compose.onNodeWithTag("ui-directory-cat-all").performScrollTo().performClick()
        compose.onNodeWithTag("ui-directory-item-0").performScrollTo().performClick()
        compose.onNodeWithTag("ui-directory-detail-0", useUnmergedTree = true).assertTextContains("Alpha detail text")
        compose.onNodeWithText("本地示例数据", substring = true).assertExists()
    }

    // ------------------------------------------------------------ quiz

    @Test fun quizGradesLocallyAndResets() {
        show(
            """{"type":"quiz","questions":[
               {"question":"2 + 2?","options":["3","4"],"answer":1,"explanation":"It is four."},
               {"question":"Capital of France?","options":["Paris","Rome"],"answer":0}]}""",
        )
        compose.onNodeWithText("本地练习", substring = true).assertExists()
        compose.onNodeWithTag("ui-quiz-0-option-1").performScrollTo().performClick()
        compose.onNodeWithTag("ui-quiz-1-option-1").performScrollTo().performClick()
        compose.onNodeWithTag("ui-quiz-submit").performScrollTo().performClick()

        compose.onNodeWithTag("ui-quiz-score").assertTextEquals("得分 1 / 2 · 50%")
        compose.onNodeWithTag("ui-quiz-feedback-0").assertTextEquals("✓ 回答正确")
        compose.onNodeWithTag("ui-quiz-feedback-1").assertTextEquals("✗ 回答错误")
        compose.onNodeWithText("解析：It is four.").assertExists()

        compose.onNodeWithTag("ui-quiz-reset").performScrollTo().performClick()
        compose.onNodeWithTag("ui-quiz-score").assertDoesNotExist()
        compose.onNodeWithTag("ui-quiz-feedback-0").assertDoesNotExist()
    }

    // ------------------------------------------------------------ decision

    @Test fun decisionRanksLiveWhenWeightsChange() {
        show(
            """{"type":"decision","criteria":[{"label":"Price","weight":8},{"label":"Quality","weight":2}],
               "items":[{"name":"Alpha","scores":[9,1]},{"name":"Beta","scores":[5,9]}]}""",
        )
        compose.onNodeWithText("1. Alpha").assertExists()
        compose.onNodeWithTag("ui-decision-weight-0").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("ui-decision-weight-1").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
        compose.onNodeWithText("1. Beta").assertExists()
    }

    // ------------------------------------------------------------ wizard

    @Test fun wizardPagesForwardBackAndFinishesLocally() {
        show("""{"type":"wizard","finishLabel":"Done","pages":[{"title":"First page","text":"One"},{"title":"Second page","text":"Two"}]}""")
        compose.onNodeWithText("First page").assertExists()
        compose.onNodeWithTag("ui-wizard-back").assertIsNotEnabled()
        compose.onNodeWithTag("ui-wizard-next").performClick()
        compose.onNodeWithText("Second page").assertExists()
        compose.onNodeWithTag("ui-wizard-back").performClick()
        compose.onNodeWithText("First page").assertExists()
        compose.onNodeWithTag("ui-wizard-next").performClick()
        compose.onNodeWithTag("ui-wizard-finish").performClick()
        compose.onNodeWithTag("ui-wizard-done").assertTextContains("已完成本地浏览")
        compose.onNodeWithText("未提交", substring = true).assertExists()
        compose.onNodeWithTag("ui-wizard-restart").performClick()
        compose.onNodeWithText("First page").assertExists()
    }

    // ------------------------------------------------------------ robustness

    @Test fun unsupportedTypeShowsLocalErrorInsteadOfCrashing() {
        show("""{"type":"not-a-tool"}""")
        compose.onNodeWithTag("ui-tool-error").assertExists()
    }
}
