package com.openminis.app.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real tests for the root shell's keep-alive lifecycle policy.
 *
 * These run on a plain JVM (no Robolectric, no Compose runtime): they exercise
 * the same [OmniPageLifecycleOwner] the shell installs around every kept-alive
 * page, so the two failure modes the policy exists to prevent are asserted
 * rather than described:
 *
 *  - a page that is NOT the selected tab must never be STARTED/RESUMED, which is
 *    what keeps its `BackHandler` inactive and stops its lifecycle-aware
 *    collectors (the home page hosts `ChatScreen`, which registers two);
 *  - a page must never outrank its parent Activity, so backgrounding the app
 *    parks every page at CREATED instead of leaving four "foreground" pages.
 */
class OmniRootLifecycleTest {

    @Test
    fun inactivePagesNeverRiseAboveCreated() {
        for (parent in listOf(
            Lifecycle.State.RESUMED,
            Lifecycle.State.STARTED,
            Lifecycle.State.CREATED,
            Lifecycle.State.DESTROYED,
        )) {
            assertEquals(
                "inactive page with parent $parent must stay CREATED",
                Lifecycle.State.CREATED,
                OmniPageLifecycleTarget.of(active = false, parentState = parent),
            )
        }
    }

    @Test
    fun activePageIsLimitedByItsParentActivity() {
        assertEquals(
            Lifecycle.State.RESUMED,
            OmniPageLifecycleTarget.of(active = true, parentState = Lifecycle.State.RESUMED),
        )
        assertEquals(
            Lifecycle.State.STARTED,
            OmniPageLifecycleTarget.of(active = true, parentState = Lifecycle.State.STARTED),
        )
        // Backgrounded / stopped app: the selected tab drops with it.
        for (parent in listOf(
            Lifecycle.State.CREATED,
            Lifecycle.State.DESTROYED,
            Lifecycle.State.INITIALIZED,
        )) {
            assertEquals(
                "selected page with parent $parent must stay CREATED",
                Lifecycle.State.CREATED,
                OmniPageLifecycleTarget.of(active = true, parentState = parent),
            )
        }
    }

    @Test
    fun ownerDispatchesRealLifecycleEvents() {
        val owner = OmniPageLifecycleOwner()
        val events = mutableListOf<Lifecycle.Event>()
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event -> events += event })
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)

        owner.setActive(active = true, parentState = Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        assertTrue(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        assertTrue(events.contains(Lifecycle.Event.ON_RESUME))

        // Switching tabs: the page is paused and stopped, NOT destroyed.
        owner.setActive(active = false, parentState = Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
        assertFalse(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        assertTrue(events.contains(Lifecycle.Event.ON_PAUSE))
        assertTrue(events.contains(Lifecycle.Event.ON_STOP))
        assertFalse("a kept-alive page must not be destroyed on a tab switch",
            events.contains(Lifecycle.Event.ON_DESTROY))

        // Coming back resumes the same owner: nothing was torn down and re-created.
        events.clear()
        owner.setActive(active = true, parentState = Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        assertTrue(events.contains(Lifecycle.Event.ON_START))
        assertTrue(events.contains(Lifecycle.Event.ON_RESUME))

        owner.dispose()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test
    fun backgroundedAppParksTheSelectedPageAtCreated() {
        val owner = OmniPageLifecycleOwner()
        owner.setActive(active = true, parentState = Lifecycle.State.RESUMED)
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        // Activity goes to the background while this tab is still selected.
        owner.setActive(active = true, parentState = Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
        assertFalse(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
}
