package com.openminis.app.intelligentui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=android.app.Application::class)
class IntelligentUiFieldsRenderTest {
    @get:Rule val compose=createComposeRule()
    private var sent:String?=null
    private fun show(raw:String, busy:Boolean=false) {
        val n=JSONObject(raw)
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { UiAdvancedFields("Fields",n,!busy,{sent=it}) } } }
    }
    @Test fun requiredValidationCancelResetAndExplicitSubmission() {
        show("""{"id":"f","type":"form","fields":[{"id":"name","label":"Name","kind":"text","value":"","required":true}]}""")
        compose.onNodeWithTag("ui_submit").performClick()
        compose.runOnIdle { assertNull(sent) }
        compose.onNodeWithText("Name is required").assertExists()
        compose.onNodeWithTag("field_name").performTextReplacement("Ada")
        compose.onNodeWithTag("ui_cancel").performClick()
        compose.runOnIdle { assertNull(sent) }
        compose.onNodeWithTag("ui_resume").performClick()
        compose.onNodeWithTag("ui_submit").performClick()
        compose.runOnIdle { assertTrue(sent!!.contains("Ada")) }
        compose.onNodeWithTag("ui_reset").performClick()
        compose.runOnIdle { sent=null }
        compose.onNodeWithTag("ui_submit").performClick()
        compose.onNodeWithText("Name is required").assertExists()
        compose.runOnIdle { assertNull(sent) }
    }
    @Test fun disabledAndBusyDoNotSendButEditsRemainLocal() {
        show("""{"id":"f","type":"form","fields":[{"id":"locked","label":"Locked","kind":"text","value":"fixed","disabled":true},{"id":"notes","label":"Notes","kind":"textarea","value":""}]}""",true)
        compose.onNodeWithTag("field_locked").assertIsNotEnabled()
        compose.onNodeWithTag("field_notes").performTextReplacement("local draft")
        compose.onNodeWithTag("ui_submit").assertIsNotEnabled()
        compose.runOnIdle { assertNull(sent) }
    }
    @Test fun multiOutputCalculatorUpdatesFromSliderAndTyping() {
        show("""{"id":"c","type":"calculator","fields":[{"id":"p","label":"Price","kind":"number","value":10},{"id":"q","label":"Count","kind":"slider","value":2,"min":1,"max":10}],"outputs":[{"label":"Total","expression":"p*q","unit":"CNY"},{"label":"Discount","expression":"p*q*0.9"}]}""")
        compose.onNodeWithTag("ui_output_0").assertTextContains("20 CNY")
        compose.onNodeWithTag("field_p").performTextReplacement("15")
        compose.onNodeWithTag("ui_output_0").assertTextContains("30 CNY")
        compose.onNodeWithTag("ui_output_1").assertTextContains("27")
        compose.runOnIdle { assertNull(sent) }
    }
    @Test fun localConfirmDialogCanBeCancelledWithoutSending() {
        val n=JSONObject("""{"id":"a","type":"actions","items":[{"label":"Continue task","kind":"followup","value":"Continue","confirm":"Send normal message?"}]}""")
        compose.setContent { MaterialTheme { UiActions("Actions",n,true,{sent=it}) } }
        compose.onNodeWithText("Continue task").performClick()
        compose.onNodeWithText("Send normal message?").assertExists()
        compose.runOnIdle { assertNull(sent) }
        compose.onNodeWithText("取消 / Cancel").performClick()
        compose.runOnIdle { assertNull(sent) }
        compose.onNodeWithText("Continue task").performClick()
        compose.onNodeWithText("确认 / Confirm").performClick()
        compose.runOnIdle { assertTrue(sent!!.contains("Continue")) }
    }
    @Test fun tabsCarouselAndAccordionRenderActualSelectedContent() {
        val n=JSONObject("""{"id":"tabs","type":"tabs","items":[{"label":"First tab","nodes":[{"id":"one","type":"text","text":"First content"}]},{"label":"Second tab","nodes":[{"id":"fold","type":"accordion","label":"Toggle fold","children":[{"id":"nested","type":"text","text":"Expanded content"}]}]}]}""")
        compose.setContent { MaterialTheme { Column { UiNode("Layout",n,true,{sent=it}) } } }
        compose.onNodeWithText("First content").assertExists()
        compose.onNodeWithText("Second tab").performClick()
        compose.onNodeWithText("First content").assertDoesNotExist()
        compose.onNodeWithText("Expanded content").assertDoesNotExist()
        compose.onNodeWithText("Toggle fold").performClick()
        compose.onNodeWithText("Expanded content").assertExists()
        compose.runOnIdle { assertNull(sent) }
    }
}
