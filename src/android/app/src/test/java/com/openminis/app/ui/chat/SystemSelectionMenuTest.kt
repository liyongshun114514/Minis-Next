package com.openminis.app.ui.chat

import android.app.Activity
import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SystemSelectionMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeMenuHasAllActionsAndSurvivesSelectionMovement() {
        val activity = ApplicationProvider.getApplicationContext<android.content.Context>()
        var callback: ActionMode.Callback? = null
        var shown = true
        var clicks = 0
        val menu = org.robolectric.fakes.RoboMenu(activity)
        val fakeMode = object : ActionMode() {
            override fun setTitle(title: CharSequence?) {}
            override fun setTitle(resId: Int) {}
            override fun setSubtitle(subtitle: CharSequence?) {}
            override fun setSubtitle(resId: Int) {}
            override fun setCustomView(view: View?) {}
            override fun invalidate() { callback?.onPrepareActionMode(this, menu) }
            override fun finish() { shown = false; callback?.onDestroyActionMode(this) }
            override fun getMenu() = menu
            override fun getTitle(): CharSequence = ""
            override fun getSubtitle(): CharSequence = ""
            override fun getCustomView(): View? = null
            override fun getMenuInflater() = android.view.MenuInflater(activity)
        }
        val host = object : View(activity) {
            override fun startActionMode(cb: ActionMode.Callback, type: Int): ActionMode {
                assertEquals(ActionMode.TYPE_FLOATING, type)
                callback = cb
                cb.onCreateActionMode(fakeMode, menu)
                return fakeMode
            }
        }
        var anchor by mutableStateOf(Rect(10f, 20f, 80f, 40f))
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                Box(Modifier.fillMaxSize()) {
                    SystemSelectionMenu(anchor, listOf(SystemSelectionAction("Copy") { clicks++ }), onDismiss = {})
                }
            }
        }
        compose.runOnIdle {
            assertEquals("Copy", menu.getItem(0).title.toString())
            anchor = Rect(10f, 120f, 80f, 140f)
        }
        compose.runOnIdle {
            assertTrue(shown)
            assertTrue(requireNotNull(callback).onActionItemClicked(fakeMode, menu.getItem(0)))
            assertEquals(1, clicks)
            assertFalse(shown)
        }
    }
}
