package com.openminis.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [T-android-auto-compact-settings] The data layer behind the Context
 * Compaction page.
 *
 * Two things are load-bearing and therefore pinned here:
 *
 *  1. `config` is ONE consistent snapshot. A reader must never see the new
 *     threshold with the old model, and a context-free `setEnabled` (the
 *     in-chat one-tap opt-in) must update that snapshot too.
 *  2. Only a positive token count is ever stored. 0 / negative / overflow are
 *     rejected rather than silently clamped, because a zero threshold would
 *     compact on every single turn.
 *
 * Uses plain Robolectric SharedPreferences: these values are preferences, not
 * secrets, so the test deliberately asserts the ordinary store is what is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class AutoCompactPrefsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun reset() {
        prefs.edit().clear().commit()
        AutoCompactPrefs.prime(context)
    }

    @After
    fun cleanUp() {
        prefs.edit().clear().commit()
        AutoCompactPrefs.prime(context)
    }

    // ── Defaults / reload ────────────────────────────────────────────────────

    @Test
    fun `a fresh install starts disabled with no threshold and no model`() {
        assertEquals(AutoCompactConfig(), AutoCompactPrefs.config.value)
        assertFalse(AutoCompactPrefs.isEnabled())
        assertNull(AutoCompactPrefs.thresholdTokens())
        assertNull(AutoCompactPrefs.modelEntryId())
        assertFalse(prefs.contains(KEY_THRESHOLD))
        assertFalse(prefs.contains(KEY_MODEL))
    }

    @Test
    fun `all three values survive a simulated relaunch as one snapshot`() {
        AutoCompactPrefs.setEnabled(context, true)
        AutoCompactPrefs.setThresholdTokens(context, 120_000)
        AutoCompactPrefs.setModelEntryId(context, "instance-1/model-a")

        val expected = AutoCompactConfig(
            enabled = true,
            thresholdTokens = 120_000,
            modelEntryId = "instance-1/model-a",
        )
        assertEquals(expected, AutoCompactPrefs.config.value)

        // prime() is what MinisApp.onCreate calls on the next launch.
        AutoCompactPrefs.prime(context)
        assertEquals(expected, AutoCompactPrefs.config.value)
        assertEquals(120_000, prefs.getInt(KEY_THRESHOLD, 0))
        assertEquals("instance-1/model-a", prefs.getString(KEY_MODEL, null))
    }

    // ── enabled ──────────────────────────────────────────────────────────────

    @Test
    fun `context-free setEnabled updates the published snapshot and the store`() {
        AutoCompactPrefs.setThresholdTokens(context, 4_000)
        AutoCompactPrefs.setModelEntryId(context, "p/m")

        AutoCompactPrefs.setEnabled(true)

        assertEquals(
            AutoCompactConfig(enabled = true, thresholdTokens = 4_000, modelEntryId = "p/m"),
            AutoCompactPrefs.config.value,
        )
        assertTrue(prefs.getBoolean(KEY_ENABLED, false))
    }

    @Test
    fun `changing enabled does not disturb threshold or model`() {
        AutoCompactPrefs.setEnabled(context, true)
        AutoCompactPrefs.setThresholdTokens(context, 64_000)
        AutoCompactPrefs.setModelEntryId(context, "p/m")

        AutoCompactPrefs.setEnabled(context, false)

        assertEquals(
            AutoCompactConfig(enabled = false, thresholdTokens = 64_000, modelEntryId = "p/m"),
            AutoCompactPrefs.config.value,
        )
    }

    // ── threshold ────────────────────────────────────────────────────────────

    @Test
    fun `null restores the capacity default and removes the stored key`() {
        AutoCompactPrefs.setThresholdTokens(context, 32_000)
        assertEquals(32_000, AutoCompactPrefs.thresholdTokens())

        AutoCompactPrefs.setThresholdTokens(context, null)

        assertNull(AutoCompactPrefs.thresholdTokens())
        assertNull(AutoCompactPrefs.config.value.thresholdTokens)
        assertFalse(prefs.contains(KEY_THRESHOLD))
    }

    @Test
    fun `zero and negative thresholds are rejected without overwriting`() {
        AutoCompactPrefs.setThresholdTokens(context, 9_000)

        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            try {
                AutoCompactPrefs.setThresholdTokens(context, bad)
                fail("expected $bad to be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
            assertEquals("$bad must not replace the stored value", 9_000, AutoCompactPrefs.thresholdTokens())
            assertEquals(9_000, prefs.getInt(KEY_THRESHOLD, 0))
        }
    }

    @Test
    fun `Int MAX is a valid upper bound`() {
        AutoCompactPrefs.setThresholdTokens(context, Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, AutoCompactPrefs.thresholdTokens())
        assertEquals(Int.MAX_VALUE, prefs.getInt(KEY_THRESHOLD, 0))
    }

    // ── model ────────────────────────────────────────────────────────────────

    @Test
    fun `blank or null model restores follow the chat model`() {
        AutoCompactPrefs.setModelEntryId(context, "p/m")
        assertEquals("p/m", AutoCompactPrefs.modelEntryId())

        AutoCompactPrefs.setModelEntryId(context, "   ")
        assertNull(AutoCompactPrefs.modelEntryId())
        assertFalse(prefs.contains(KEY_MODEL))

        AutoCompactPrefs.setModelEntryId(context, "p/m")
        AutoCompactPrefs.setModelEntryId(context, null)
        assertNull(AutoCompactPrefs.modelEntryId())
        assertFalse(prefs.contains(KEY_MODEL))
    }

    @Test
    fun `model ids are trimmed but otherwise stored verbatim`() {
        AutoCompactPrefs.setModelEntryId(context, "  instance-9/model-x  ")
        assertEquals("instance-9/model-x", AutoCompactPrefs.modelEntryId())
        assertEquals("instance-9/model-x", prefs.getString(KEY_MODEL, null))
    }

    private companion object {
        // Literals on purpose: the test must fail if the on-disk names change,
        // because the existing boolean key is shared with iOS.
        const val PREFS_NAME = "minis_auto_compact_prefs"
        const val KEY_ENABLED = "autoCompactOnThreshold"
        const val KEY_THRESHOLD = "autoCompactThresholdTokens"
        const val KEY_MODEL = "autoCompactModelEntryId"
    }
}
