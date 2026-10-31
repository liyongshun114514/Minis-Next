package com.openminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniSheetPhysicsTest {
    @Test fun referenceConstants() {
        assertEquals(0.55f, OmniSheetPhysics.Resistance, 0f)
        assertEquals(100f, OmniSheetPhysics.LimitDp, 0f)
        assertEquals(333.33334f, OmniSheetPhysics.Stiffness, 0.001f)
    }
    @Test fun upwardPullIsResisted() {
        assertEquals(-5.5f, OmniSheetPhysics.pull(0f, -10f, 100f), 0.0001f)
    }
    @Test fun resistanceGrowsNearLimit() {
        val atRest = -OmniSheetPhysics.pull(0f, -10f, 100f)
        val nearLimit = -(OmniSheetPhysics.pull(-90f, -10f, 100f) + 90f)
        assertTrue(nearLimit < atRest)
    }
    @Test fun neverOvershootsHardLimit() {
        assertEquals(-100f, OmniSheetPhysics.pull(-90f, -10000f, 100f), 0f)
    }
    @Test fun releaseCannotBecomePositiveTranslation() {
        assertEquals(0f, OmniSheetPhysics.pull(-10f, 1000f, 100f), 0f)
    }
    @Test fun invalidInputDoesNotPoisonGraphicsLayer() {
        assertEquals(0f, OmniSheetPhysics.pull(Float.NaN, -10f, 100f), 0f)
        assertEquals(0f, OmniSheetPhysics.pull(0f, Float.NEGATIVE_INFINITY, 100f), 0f)
        assertEquals(0f, OmniSheetPhysics.pull(0f, -10f, 0f), 0f)
    }
    @Test fun repeatedPullsStayBounded() {
        var position = 0f
        repeat(1000) {
            position = OmniSheetPhysics.pull(position, -4f, 100f)
            assertTrue(position in -100f..0f)
        }
        assertTrue(position < -99f)
    }
}
