package com.openminis.app.intelligentui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ToolBlockStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Android Compose rendering and taps on the cloud JVM; not screenshot-only assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IntelligentUiRenderTest {
    @get:Rule val compose = createComposeRule()
    private fun block(node: String): AssistantBlock {
        val args = JSONObject().put("document", JSONObject("""{"version":1,"title":"Interactive answer","nodes":[$node]}""")).toString()
        val result = IntelligentUiTool.execute(args); assertTrue(result.output, result.success)
        return AssistantBlock("ui-1", "tool_use", content = result.output, toolName = IntelligentUiTool.NAME, toolStatus = ToolBlockStatus.SUCCESS)
    }
    @Test fun choiceIsNativeAndSendsOnlyOnTap() {
        var sent: String? = null
        val b = block("""{"id":"choice","type":"choices","options":["Choice A","Choice B"]}""")
        compose.setContent { MaterialTheme { IntelligentUiCard(b,true,{sent=it},{}) } }
        compose.onNodeWithText("Interactive answer").assertIsDisplayed()
        assertNull(sent)
        compose.onNodeWithText("Choice B").performClick()
        compose.runOnIdle { assertTrue(sent!!.contains("Choice B")) }
    }
    @Test fun busyConversationCannotSendChoice() {
        var sent = false
        val b = block("""{"id":"choice","type":"choices","options":["A","B"]}""")
        compose.setContent { MaterialTheme { IntelligentUiCard(b,false,{sent=true},{}) } }
        compose.onNodeWithText("A").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(sent) }
    }
    @Test fun calculatorUpdatesWithoutModelCall() {
        var sent = false
        val b=block("""{"id":"calc","type":"calculator","fields":[{"id":"total","label":"Total","kind":"number","value":120},{"id":"people","label":"People","kind":"number","value":3,"min":1}],"expression":"total/people","unit":"CNY"}""")
        compose.setContent { MaterialTheme { IntelligentUiCard(b,true,{sent=true},{}) } }
        compose.onNodeWithText("40 CNY").assertIsDisplayed()
        compose.onNodeWithText("Total").performTextReplacement("150")
        compose.onNodeWithText("50 CNY").assertIsDisplayed()
        compose.runOnIdle { assertFalse(sent) }
    }
    @Test fun tableRendersExactValues() {
        val b=block("""{"id":"table","type":"table","columns":["Plan","Cost"],"rows":[["Basic","12"]]}""")
        compose.setContent { MaterialTheme { IntelligentUiCard(b,true,{}, {}) } }
        compose.onNodeWithText("Basic").assertIsDisplayed(); compose.onNodeWithText("12").assertIsDisplayed()
    }
    @Test fun failedOrPartialCallsUseExistingErrorPill() {
        var fallback = false
        val b=AssistantBlock("bad","tool_use",content="{partial",toolStatus=ToolBlockStatus.FAILED,toolName=IntelligentUiTool.NAME)
        compose.setContent { MaterialTheme { IntelligentUiCard(b,true,{}, { androidx.compose.runtime.SideEffect { fallback=true } }) } }
        compose.runOnIdle { assertTrue(fallback) }
    }
}
