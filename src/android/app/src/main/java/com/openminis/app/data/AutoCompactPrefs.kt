package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [T-android-auto-compact-settings] The persisted shape of the auto-compact
 * settings, published as one immutable snapshot.
 *
 * [enabled] keeps the original meaning of the single boolean this object used
 * to hold: crossing the compact threshold before a send either prompts
 * (`false`, the default) or compacts silently and then sends (`true`).
 *
 * [thresholdTokens] is the user-chosen token line, or `null` for "use the
 * model/capacity-derived default". Only positive values are ever stored — a
 * zero or negative threshold would compact on every turn, which is never what
 * the user meant.
 *
 * [modelEntryId] names the `ModelEntry` that writes the summary, in the
 * provider-wide `"{instanceId}/{modelId}"` form `ModelEntry.id` already uses.
 * `null` means "follow the current chat model", which is the default and the
 * behaviour that existed before this setting. A non-null id is NEVER rewritten
 * behind the user's back: if that entry disappears or stops being usable the
 * UI says so and keeps the stored id until the user picks another one.
 *
 * The three fields travel together so a StateFlow subscriber can never observe
 * a half-applied change (e.g. the new threshold with the old model).
 */
data class AutoCompactConfig(
    val enabled: Boolean = false,
    val thresholdTokens: Int? = null,
    val modelEntryId: String? = null,
)

/**
 * App-level persisted "auto-compact on threshold" settings.
 *
 * Mirrors iOS `AIChatViewModel.autoCompactEnabled`, whose backing store is the
 * UserDefaults key `autoCompactOnThreshold` (AIChatViewModel.swift:853). The
 * key name is reused verbatim so the two platforms stay greppable as one
 * feature. The two new keys are Android-only for now; they live beside it in
 * the same ordinary (non-encrypted) store because none of these values is a
 * secret — see [prime].
 *
 * Semantics of [isEnabled], matching iOS:
 *   - false (the default — iOS uses a bare `UserDefaults.bool`, which is false
 *     when unset): crossing the compact threshold before a send PROMPTS the
 *     user.
 *   - true: the same crossing compacts silently and then sends.
 *
 * Global rather than per-session on purpose: iOS persists it so "future
 * conversations inherit it", which is the whole point of the one-tap opt-in
 * offered on the prompt.
 *
 * [config] is the single source of truth for readers. Every setter writes the
 * preference and publishes a COMPLETE new snapshot, so a UI or the chat loop
 * can collect one flow instead of reading three keys that could disagree
 * half-way through a change. [prime] reads all three keys once at startup;
 * the context-free accessors exist for the in-chat path, which has no Context.
 */
object AutoCompactPrefs {
    private const val PREFS = "minis_auto_compact_prefs"
    private const val KEY_ENABLED = "autoCompactOnThreshold"

    // [T-android-auto-compact-settings] Added beside the original boolean.
    // Absent keys mean "capacity default" / "follow the chat model", which is
    // exactly what a fresh install and every pre-existing install should get.
    private const val KEY_THRESHOLD_TOKENS = "autoCompactThresholdTokens"
    private const val KEY_MODEL_ENTRY_ID = "autoCompactModelEntryId"

    @Volatile
    private var appContext: Context? = null

    private val _config = MutableStateFlow(AutoCompactConfig())

    /** The complete auto-compact configuration, updated as one snapshot. */
    val config: StateFlow<AutoCompactConfig> = _config.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Capture the app context and load every persisted value. Called from
     * MinisApp.onCreate, so the context-free accessors are safe from call sites
     * that have no Context — the same arrangement [FastModePrefs] uses.
     *
     * This is ORDINARY SharedPreferences on purpose: the values are preferences,
     * not credentials, so they must not move into the encrypted store (and an
     * existing install must not need a migration for a setting it never had).
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        val p = prefs(context)
        // Defensive reads: a value written by a future build (or a corrupt
        // store) must degrade to the default rather than throw during startup
        // and take the whole process with it.
        val enabled = runCatching { p.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)
        val threshold = runCatching { p.getInt(KEY_THRESHOLD_TOKENS, 0) }
            .getOrDefault(0)
            .takeIf { it > 0 }
        val modelEntryId = runCatching { p.getString(KEY_MODEL_ENTRY_ID, null) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        _config.value = AutoCompactConfig(
            enabled = enabled,
            thresholdTokens = threshold,
            modelEntryId = modelEntryId,
        )
    }

    /** Context-free read. False before [prime] runs, matching a fresh install. */
    fun isEnabled(): Boolean = _config.value.enabled

    /** The user-chosen threshold, or null to use the capacity-derived default. */
    fun thresholdTokens(): Int? = _config.value.thresholdTokens

    /** The entry that writes the summary, or null to follow the chat model. */
    fun modelEntryId(): String? = _config.value.modelEntryId

    fun setEnabled(context: Context, enabled: Boolean) {
        appContext = context.applicationContext
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        publish { it.copy(enabled = enabled) }
    }

    /**
     * Context-free write for the in-chat one-tap opt-in ("Compact & always
     * auto-compact"), which runs from the ViewModel where no Activity context
     * is at hand. No-ops the persist step if [prime] never ran, but still
     * updates the published config so the current process behaves as asked.
     */
    fun setEnabled(enabled: Boolean) {
        publish { it.copy(enabled = enabled) }
        appContext?.let { prefs(it).edit().putBoolean(KEY_ENABLED, enabled).apply() }
    }

    /**
     * Persist the token threshold, or clear it back to the default with null.
     *
     * A zero or negative value is rejected rather than silently stored: it
     * would make every turn cross the line, and the settings UI has already
     * validated the text field by the time this is called. Callers that accept
     * user input should keep their draft on [IllegalArgumentException] instead
     * of assuming the write happened.
     */
    fun setThresholdTokens(context: Context, tokens: Int?) {
        require(tokens == null || tokens > 0) {
            "Auto-compact threshold must be a positive token count or null, was $tokens"
        }
        appContext = context.applicationContext
        prefs(context).edit().apply {
            if (tokens == null) remove(KEY_THRESHOLD_TOKENS) else putInt(KEY_THRESHOLD_TOKENS, tokens)
        }.apply()
        publish { it.copy(thresholdTokens = tokens) }
    }

    /**
     * Persist the model entry that writes the summary. null (or blank) restores
     * "follow the current chat model".
     *
     * The id is stored verbatim, without checking that it currently resolves:
     * the provider list is refreshed from the network, and deleting a choice
     * because it is momentarily absent would be the silent switch this setting
     * must never make. The settings screen reports an unresolvable selection
     * and waits for the user to replace it.
     */
    fun setModelEntryId(context: Context, entryId: String?) {
        val normalized = entryId?.trim()?.takeIf { it.isNotEmpty() }
        appContext = context.applicationContext
        prefs(context).edit().apply {
            if (normalized == null) remove(KEY_MODEL_ENTRY_ID) else putString(KEY_MODEL_ENTRY_ID, normalized)
        }.apply()
        publish { it.copy(modelEntryId = normalized) }
    }

    /**
     * Publish a full snapshot. `update` keeps concurrent setters from
     * overwriting each other: each one applies its own field to whatever the
     * current snapshot is, so enabled/threshold/model stay consistent.
     */
    private fun publish(transform: (AutoCompactConfig) -> AutoCompactConfig) {
        _config.update(transform)
    }
}
