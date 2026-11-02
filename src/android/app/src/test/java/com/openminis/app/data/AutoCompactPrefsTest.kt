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
import org.json.JSONObject
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

    // ── per-model thresholds (keyed by ModelEntry.id) ───────────────────────

    @Test
    fun `per-model thresholds are isolated between two entries`() {
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, 100_000)
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_B, 200_000)

        assertEquals(100_000, AutoCompactPrefs.thresholdTokens(ENTRY_A))
        assertEquals(200_000, AutoCompactPrefs.thresholdTokens(ENTRY_B))
        assertEquals(
            mapOf(ENTRY_A to 100_000, ENTRY_B to 200_000),
            AutoCompactPrefs.config.value.thresholdTokensByEntryId,
        )
        // Neither write may touch the legacy global line.
        assertNull(AutoCompactPrefs.thresholdTokens())
        assertFalse(prefs.contains(KEY_THRESHOLD))
    }

    @Test
    fun `the same model name on two providers keeps two thresholds`() {
        // ModelEntry.id is "{instanceId}/{modelId}". The display name is the
        // same here on purpose: the override belongs to the provider entry the
        // user picked, never to the model name.
        val providerOne = "provider-one/gpt-x"
        val providerTwo = "provider-two/gpt-x"
        AutoCompactPrefs.setThresholdTokens(context, providerOne, 30_000)
        AutoCompactPrefs.setThresholdTokens(context, providerTwo, Int.MAX_VALUE)

        assertEquals(30_000, AutoCompactPrefs.thresholdTokens(providerOne))
        assertEquals(Int.MAX_VALUE, AutoCompactPrefs.thresholdTokens(providerTwo))
        // A bare model name is not an entry id: nothing may answer for it.
        assertNull(AutoCompactPrefs.thresholdTokens("gpt-x"))

        // …and the pair survives a relaunch, still side by side.
        AutoCompactPrefs.prime(context)
        assertEquals(30_000, AutoCompactPrefs.thresholdTokens(providerOne))
        assertEquals(Int.MAX_VALUE, AutoCompactPrefs.thresholdTokens(providerTwo))
    }

    @Test
    fun `clearing one entry leaves the other alone and does not revive the legacy line`() {
        // A pre-map install: the old global line is all it has.
        AutoCompactPrefs.setThresholdTokens(context, 50_000)
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, 120_000)

        // B never got its own row, so the legacy line still governs it.
        assertEquals(50_000, AutoCompactPrefs.thresholdTokens(ENTRY_B))

        // The user clears A: that must mean "capacity default for A", not
        // "forget A and fall back to the old global value again".
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, null)

        assertNull(
            "an explicit per-model clear must beat the legacy fallback",
            AutoCompactPrefs.thresholdTokens(ENTRY_A),
        )
        assertEquals("B is a different model and must not move", 50_000, AutoCompactPrefs.thresholdTokens(ENTRY_B))
        assertEquals("the legacy value stays for unconfigured models", 50_000, AutoCompactPrefs.thresholdTokens())
        assertEquals(mapOf(ENTRY_A to null), AutoCompactPrefs.config.value.thresholdTokensByEntryId)

        // A relaunch must not turn that clear back into "inherit 50K".
        AutoCompactPrefs.prime(context)
        assertNull(AutoCompactPrefs.thresholdTokens(ENTRY_A))
        assertEquals(50_000, AutoCompactPrefs.thresholdTokens(ENTRY_B))
    }

    @Test
    fun `an explicit clear is stored as JSON null rather than a removed row`() {
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, 120_000)
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, null)

        val raw = prefs.getString(KEY_BY_ENTRY, null)
        assertTrue("the row must stay, otherwise the legacy line returns", raw != null)
        val json = JSONObject(raw!!)
        assertTrue(json.has(ENTRY_A))
        assertTrue(json.isNull(ENTRY_A))
        assertNull(AutoCompactPrefs.thresholdTokens(ENTRY_A))
    }

    @Test
    fun `the legacy global threshold is never migrated onto a model entry`() {
        // Exactly what an install configured before this feature has on disk:
        // the old threshold and a summarizer pick, no per-model blob.
        prefs.edit().putInt(KEY_THRESHOLD, 60_000).putString(KEY_MODEL, SUMMARIZER).commit()
        AutoCompactPrefs.prime(context)

        assertEquals(60_000, AutoCompactPrefs.thresholdTokens())
        assertTrue(AutoCompactPrefs.config.value.thresholdTokensByEntryId.isEmpty())
        assertFalse(prefs.contains(KEY_BY_ENTRY))

        // It applies as a fallback for any model, and is NOT attributed to the
        // summarizer entry (nor to any other) behind the user's back.
        assertEquals(60_000, AutoCompactPrefs.thresholdTokens(SUMMARIZER))
        assertFalse(AutoCompactPrefs.config.value.thresholdTokensByEntryId.containsKey(SUMMARIZER))

        // Changing the summarizer pick invents nothing either…
        AutoCompactPrefs.setModelEntryId(context, "instance-8/summarizer-2")
        assertFalse(prefs.contains(KEY_BY_ENTRY))

        // …and adding a per-model threshold neither deletes nor duplicates it.
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, 10_000)
        assertEquals(60_000, AutoCompactPrefs.thresholdTokens())
        assertEquals(60_000, AutoCompactPrefs.thresholdTokens(SUMMARIZER))
        assertEquals(60_000, AutoCompactPrefs.thresholdTokens(ENTRY_B))
        assertEquals(10_000, AutoCompactPrefs.thresholdTokens(ENTRY_A))
        assertEquals(setOf(ENTRY_A), AutoCompactPrefs.config.value.thresholdTokensByEntryId.keys)
    }

    @Test
    fun `numeric and explicit-null thresholds survive a simulated relaunch`() {
        AutoCompactPrefs.setEnabled(context, true)
        AutoCompactPrefs.setModelEntryId(context, SUMMARIZER)
        AutoCompactPrefs.setThresholdTokens(context, 50_000)
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, 120_000)
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_B, null)

        AutoCompactPrefs.prime(context)

        val config = AutoCompactPrefs.config.value
        assertEquals(mapOf(ENTRY_A to 120_000, ENTRY_B to null), config.thresholdTokensByEntryId)
        assertTrue("a cleared model must keep its row", config.thresholdTokensByEntryId.containsKey(ENTRY_B))
        assertEquals(120_000, config.thresholdFor(ENTRY_A))
        assertNull(config.thresholdFor(ENTRY_B))
        // The unrelated parts of the snapshot are untouched.
        assertEquals(50_000, config.thresholdTokens)
        assertTrue(config.enabled)
        assertEquals(SUMMARIZER, config.modelEntryId)

        // On disk the null is explicit and the number still a number.
        val json = JSONObject(prefs.getString(KEY_BY_ENTRY, "{}")!!)
        assertTrue(json.has(ENTRY_B))
        assertTrue(json.isNull(ENTRY_B))
        assertEquals(120_000, json.getInt(ENTRY_A))
    }

    @Test
    fun `unreadable JSON degrades to no overrides instead of throwing`() {
        AutoCompactPrefs.setThresholdTokens(context, 50_000)

        for (raw in listOf("", "   ", "{", "not json", "[]", "[1,2,3]", "42", "null", "\"text\"", "{\"a\":}")) {
            prefs.edit().putString(KEY_BY_ENTRY, raw).commit()
            AutoCompactPrefs.prime(context) // must not throw during startup
            assertTrue("raw=<$raw>", AutoCompactPrefs.config.value.thresholdTokensByEntryId.isEmpty())
            assertEquals(
                "the legacy fallback must survive raw=<$raw>",
                50_000,
                AutoCompactPrefs.thresholdTokens(ENTRY_A),
            )
        }
    }

    @Test
    fun `unusable rows are ignored while good rows survive`() {
        val raw = """
            {
              "provider-a/model-1": 90000,
              "provider-b/model-1": "120000",
              "provider-c/model-1": true,
              "provider-d/model-1": 0,
              "provider-e/model-1": -5,
              "provider-f/model-1": 3000000000,
              "provider-g/model-1": 1.5,
              "provider-h/model-1": {"nested": 1},
              "provider-j/model-1": [1],
              "   ": 50000,
              "provider-i/model-1": null
            }
        """.trimIndent()
        prefs.edit().putString(KEY_BY_ENTRY, raw).commit()
        AutoCompactPrefs.prime(context)

        val map = AutoCompactPrefs.config.value.thresholdTokensByEntryId
        assertEquals(mapOf("provider-a/model-1" to 90000, "provider-i/model-1" to null), map)
        assertEquals(90_000, AutoCompactPrefs.thresholdTokens("provider-a/model-1"))
        assertNull(AutoCompactPrefs.thresholdTokens("provider-i/model-1"))
        val ignored = listOf(
            "provider-b", "provider-c", "provider-d", "provider-e",
            "provider-f", "provider-g", "provider-h", "provider-j",
        )
        for (bad in ignored) {
            assertNull("$bad/model-1 must be ignored", AutoCompactPrefs.thresholdTokens("$bad/model-1"))
        }
    }

    @Test
    fun `per-model setter rejects blank ids and non-positive values without writing`() {
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, 10_000)
        val before = prefs.getString(KEY_BY_ENTRY, null)

        for (badId in listOf("", "   ")) {
            try {
                AutoCompactPrefs.setThresholdTokens(context, badId, 20_000)
                fail("expected blank id <$badId> to be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected: there is nothing to key the override by.
            }
        }
        for (bad in listOf(0, -1, Int.MIN_VALUE)) {
            try {
                AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, bad)
                fail("expected $bad to be rejected")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }

        assertEquals(mapOf(ENTRY_A to 10_000), AutoCompactPrefs.config.value.thresholdTokensByEntryId)
        assertEquals(10_000, AutoCompactPrefs.thresholdTokens(ENTRY_A))
        assertEquals("the stored JSON must be untouched", before, prefs.getString(KEY_BY_ENTRY, null))
    }

    @Test
    fun `entry ids are trimmed but otherwise stored verbatim`() {
        AutoCompactPrefs.setThresholdTokens(context, "  provider-9/model-x  ", 42_000)

        assertEquals(42_000, AutoCompactPrefs.thresholdTokens("provider-9/model-x"))
        assertEquals(42_000, AutoCompactPrefs.thresholdTokens("  provider-9/model-x  "))
        assertEquals(setOf("provider-9/model-x"), AutoCompactPrefs.config.value.thresholdTokensByEntryId.keys)
    }

    @Test
    fun `thresholdFor is null-safe for unknown blank and null entry ids`() {
        assertEquals(AutoCompactConfig(), AutoCompactPrefs.config.value)
        assertNull(AutoCompactPrefs.thresholdTokens(null))
        assertNull(AutoCompactPrefs.thresholdTokens(""))
        assertNull(AutoCompactPrefs.thresholdTokens("   "))
        assertNull(AutoCompactPrefs.config.value.thresholdFor(null))

        AutoCompactPrefs.setThresholdTokens(context, 50_000)
        assertEquals(50_000, AutoCompactPrefs.thresholdTokens(null))
        assertEquals(50_000, AutoCompactPrefs.thresholdTokens("  "))
        assertEquals(50_000, AutoCompactPrefs.config.value.thresholdFor(null))
        // An entry nobody configured inherits the legacy line.
        assertEquals(50_000, AutoCompactPrefs.thresholdTokens(ENTRY_A))

        // …but a configured one always answers for itself.
        AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, 7_000)
        assertEquals(7_000, AutoCompactPrefs.thresholdTokens(ENTRY_A))
        assertEquals(
            "an unknown entry still falls back to the legacy line",
            50_000,
            AutoCompactPrefs.thresholdTokens(null),
        )
    }

    @Test
    fun `setting a per-model threshold keeps the rest of the snapshot intact`() {
        AutoCompactPrefs.setEnabled(context, true)
        AutoCompactPrefs.setModelEntryId(context, SUMMARIZER)
        AutoCompactPrefs.setThresholdTokens(context, 64_000)

        AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, 32_000)

        assertEquals(
            AutoCompactConfig(
                enabled = true,
                thresholdTokens = 64_000,
                modelEntryId = SUMMARIZER,
                thresholdTokensByEntryId = mapOf(ENTRY_A to 32_000),
            ),
            AutoCompactPrefs.config.value,
        )
        // The legacy setter must not drop the map either.
        AutoCompactPrefs.setThresholdTokens(context, null)
        assertEquals(mapOf(ENTRY_A to 32_000), AutoCompactPrefs.config.value.thresholdTokensByEntryId)
    }

    @Test
    fun `concurrent per-model writes and reloads leave the snapshot equal to the stored map`() {
        // Three writers (two models + the enabled/model/legacy settings) and
        // one thread calling prime() in a loop: the load must never republish
        // a snapshot older than a write that already landed, and neither
        // model's row may be lost to a read-modify-write race.
        val workers = listOf(
            Thread {
                repeat(40) { AutoCompactPrefs.setThresholdTokens(context, ENTRY_A, 2_000 + it) }
            },
            Thread {
                repeat(40) { AutoCompactPrefs.setThresholdTokens(context, ENTRY_B, 4_000 + it) }
            },
            Thread {
                repeat(40) {
                    AutoCompactPrefs.setEnabled(context, true)
                    AutoCompactPrefs.setModelEntryId(context, SUMMARIZER)
                    AutoCompactPrefs.setThresholdTokens(context, 33_000)
                }
            },
            Thread {
                repeat(40) { AutoCompactPrefs.prime(context) }
            },
        )
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        // Deliberately NO prime() here: what the last lock holder published is
        // what must be in the store, read back independently below.
        val snapshot = AutoCompactPrefs.config.value
        val stored = JSONObject(prefs.getString(KEY_BY_ENTRY, "{}")!!)
        val storedMap = stored.keys().asSequence().associateWith { key ->
            if (stored.isNull(key)) null else stored.getInt(key)
        }

        assertEquals(setOf(ENTRY_A, ENTRY_B), snapshot.thresholdTokensByEntryId.keys)
        assertEquals(2_039, snapshot.thresholdFor(ENTRY_A))
        assertEquals(4_039, snapshot.thresholdFor(ENTRY_B))
        assertEquals(storedMap, snapshot.thresholdTokensByEntryId)
        assertEquals(33_000, snapshot.thresholdTokens)
        assertTrue(snapshot.enabled)
        assertEquals(SUMMARIZER, snapshot.modelEntryId)
        assertEquals(2_039, stored.getInt(ENTRY_A))
        assertEquals(4_039, stored.getInt(ENTRY_B))
    }

    private companion object {
        // Literals on purpose: the test must fail if the on-disk names change,
        // because the existing boolean key is shared with iOS.
        const val PREFS_NAME = "minis_auto_compact_prefs"
        const val KEY_ENABLED = "autoCompactOnThreshold"
        const val KEY_THRESHOLD = "autoCompactThresholdTokens"
        const val KEY_MODEL = "autoCompactModelEntryId"

        // [T-android-auto-compact-per-model] The JSON blob and the two entry
        // ids used by the isolation tests: same model suffix, different
        // provider, which is the case a name-keyed map would get wrong.
        const val KEY_BY_ENTRY = "autoCompactThresholdsByEntryId"
        const val ENTRY_A = "instance-1/model-a"
        const val ENTRY_B = "instance-2/model-a"
        const val SUMMARIZER = "instance-9/summarizer"
    }
}
