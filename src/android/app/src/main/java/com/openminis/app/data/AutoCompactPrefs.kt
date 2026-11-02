package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

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
 * [thresholdTokensByEntryId] holds the per-model overrides, keyed by
 * `ModelEntry.id` — the `"{instanceId}/{modelId}"` form. The provider instance
 * is part of the key on purpose: two providers can serve a model with the same
 * display name, and an override belongs to the ENTRY the user picked, never to
 * a model name and never to the summarizer entry ([modelEntryId]).
 *
 * A MISSING key means "no override for that entry" and falls back to the
 * legacy global [thresholdTokens] (what a pre-existing install configured
 * before this map existed, and what a chat whose model is not known still
 * gets). A key PRESENT with a `null` value is a deliberate user choice — "use
 * the capacity default for this model" — and must NOT fall back: otherwise the
 * old global line would silently come back the moment the user cleared one
 * model's threshold. [thresholdFor] is the only place that distinction is
 * decided, which is why lookups go through `containsKey` rather than a plain
 * `map[id]`.
 *
 * [modelEntryId] names the `ModelEntry` that writes the summary, in the
 * provider-wide `"{instanceId}/{modelId}"` form `ModelEntry.id` already uses.
 * `null` means "follow the current chat model", which is the default and the
 * behaviour that existed before this setting. A non-null id is NEVER rewritten
 * behind the user's back: if that entry disappears or stops being usable the
 * UI says so and keeps the stored id until the user picks another one.
 *
 * The fields travel together so a StateFlow subscriber can never observe a
 * half-applied change (e.g. the new threshold with the old model).
 */
data class AutoCompactConfig(
    val enabled: Boolean = false,
    val thresholdTokens: Int? = null,
    val modelEntryId: String? = null,
    val thresholdTokensByEntryId: Map<String, Int?> = emptyMap(),
) {
    /**
     * The threshold in force for [entryId], the `ModelEntry.id` of the model
     * this chat is actually running on.
     *
     * Resolution order, and nothing else:
     *  1. the entry has its own stored value (including a stored `null`) —
     *     return it verbatim, so an explicit "capacity default" never leaks
     *     the legacy global line back in;
     *  2. otherwise the legacy global [thresholdTokens], which is `null` on a
     *     fresh install and the pre-map value on an upgraded one.
     *
     * [entryId] null or blank means "the caller does not know the entry"
     * (e.g. a chat with no resolved model); that can only answer with the
     * legacy global value, because guessing an entry is exactly the silent
     * re-association this setting must not do.
     */
    fun thresholdFor(entryId: String?): Int? {
        val key = entryId?.trim()?.takeIf { it.isNotEmpty() } ?: return thresholdTokens
        if (thresholdTokensByEntryId.containsKey(key)) return thresholdTokensByEntryId[key]
        return thresholdTokens
    }
}

/**
 * App-level persisted "auto-compact on threshold" settings.
 *
 * Mirrors iOS `AIChatViewModel.autoCompactEnabled`, whose backing store is the
 * UserDefaults key `autoCompactOnThreshold` (AIChatViewModel.swift:853). The
 * key name is reused verbatim so the two platforms stay greppable as one
 * feature. The newer keys are Android-only for now; they live beside it in the
 * same ordinary (non-encrypted) store because none of these values is a
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
 * can collect one flow instead of reading several keys that could disagree
 * half-way through a change. [prime] reads every key once at startup; the
 * context-free accessors exist for the in-chat path, which has no Context.
 */
object AutoCompactPrefs {
    private const val PREFS = "minis_auto_compact_prefs"
    private const val KEY_ENABLED = "autoCompactOnThreshold"

    // [T-android-auto-compact-settings] Added beside the original boolean.
    // Absent keys mean "capacity default" / "follow the chat model", which is
    // exactly what a fresh install and every pre-existing install should get.
    private const val KEY_THRESHOLD_TOKENS = "autoCompactThresholdTokens"
    private const val KEY_MODEL_ENTRY_ID = "autoCompactModelEntryId"

    // [T-android-auto-compact-per-model] Per-`ModelEntry.id` overrides, one
    // JSON object: {"{instanceId}/{modelId}": 120000, "…": null}. JSON rather
    // than one preference key per model because the entry ids contain "/" and
    // are user-editable, and because the whole map has to be rewritten
    // atomically anyway (a lost update between two setters would silently drop
    // a threshold). The value `null` is load-bearing and therefore written as
    // an explicit JSON null: see [AutoCompactConfig.thresholdFor].
    private const val KEY_THRESHOLD_TOKENS_BY_ENTRY = "autoCompactThresholdsByEntryId"

    /**
     * Serialises EVERY load and durable write of this object: [prime]'s whole
     * read-and-publish, and each setter's persist-then-publish. SharedPreferences
     * is thread-safe per key, but two things here are not atomic on their own:
     * the read-modify-write of [KEY_THRESHOLD_TOKENS_BY_ENTRY] (a lost update
     * would silently drop one model's threshold), and a startup load racing a
     * setter (it would republish the snapshot from before the write).
     */
    private val lock = Any()

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
     *
     * The read AND the publish happen under [lock], so a load that started
     * before a setter stored something can never republish the older snapshot
     * afterwards.
     */
    fun prime(context: Context) {
        appContext = context.applicationContext
        val p = prefs(context)
        synchronized(lock) {
            // Defensive reads: a value written by a future build (or a corrupt
            // store) must degrade to the default rather than throw during
            // startup and take the whole process with it.
            val enabled = runCatching { p.getBoolean(KEY_ENABLED, false) }.getOrDefault(false)
            val threshold = runCatching { p.getInt(KEY_THRESHOLD_TOKENS, 0) }
                .getOrDefault(0)
                .takeIf { it > 0 }
            val modelEntryId = runCatching { p.getString(KEY_MODEL_ENTRY_ID, null) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            // A wrong value type under this key (or a JSON blob truncated by a
            // killed process) is not worth failing startup over:
            // [decodeThresholdMap] answers with an empty map, which is exactly
            // "no overrides" and keeps the legacy threshold in force.
            val byEntry: Map<String, Int?> = runCatching {
                p.getString(KEY_THRESHOLD_TOKENS_BY_ENTRY, null)
            }.getOrNull().let(::decodeThresholdMap)
            _config.value = AutoCompactConfig(
                enabled = enabled,
                thresholdTokens = threshold,
                modelEntryId = modelEntryId,
                thresholdTokensByEntryId = byEntry,
            )
        }
    }

    /** Context-free read. False before [prime] runs, matching a fresh install. */
    fun isEnabled(): Boolean = _config.value.enabled

    /** The user-chosen threshold, or null to use the capacity-derived default. */
    fun thresholdTokens(): Int? = _config.value.thresholdTokens

    /**
     * [T-android-auto-compact-per-model] The threshold in force for a specific
     * `ModelEntry.id` ("{instanceId}/{modelId}"), which is what every
     * threshold-sensitive call site should ask once it knows the chat model.
     *
     * Delegates straight to [AutoCompactConfig.thresholdFor], so the
     * missing-key / explicit-null distinction lives in exactly one place: a
     * stored `null` for this entry answers null even when the legacy global
     * threshold is still set. [entryId] null or blank answers with that legacy
     * value.
     */
    fun thresholdTokens(entryId: String?): Int? = _config.value.thresholdFor(entryId)

    /** The entry that writes the summary, or null to follow the chat model. */
    fun modelEntryId(): String? = _config.value.modelEntryId

    fun setEnabled(context: Context, enabled: Boolean) {
        appContext = context.applicationContext
        val p = prefs(context)
        synchronized(lock) {
            p.edit().putBoolean(KEY_ENABLED, enabled).apply()
            publish { it.copy(enabled = enabled) }
        }
    }

    /**
     * Context-free write for the in-chat one-tap opt-in ("Compact & always
     * auto-compact"), which runs from the ViewModel where no Activity context
     * is at hand. No-ops the persist step if [prime] never ran, but still
     * updates the published config so the current process behaves as asked.
     * Persist-then-publish under [lock], like every other setter, so a
     * concurrent [prime] cannot put the old value back in the snapshot.
     */
    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            appContext?.let { prefs(it).edit().putBoolean(KEY_ENABLED, enabled).apply() }
            publish { it.copy(enabled = enabled) }
        }
    }

    /**
     * Persist the token threshold, or clear it back to the default with null.
     *
     * A zero or negative value is rejected rather than silently stored: it
     * would make every turn cross the line, and the settings UI has already
     * validated the text field by the time this is called. Callers that accept
     * user input should keep their draft on [IllegalArgumentException] instead
     * of assuming the write happened.
     *
     * [T-android-auto-compact-per-model] This is the LEGACY global value. It is
     * kept so an install configured before per-model thresholds existed keeps
     * the line it chose, and so the older tests/builds that call it still
     * compile and behave. It is deliberately NOT migrated onto any model: the
     * old value cannot be attributed to the summarizer entry (or to any other
     * entry) without inventing a choice the user never made. New UI and the
     * ViewModel write [setThresholdTokens] with an entry id instead.
     */
    fun setThresholdTokens(context: Context, tokens: Int?) {
        require(tokens == null || tokens > 0) {
            "Auto-compact threshold must be a positive token count or null, was $tokens"
        }
        appContext = context.applicationContext
        val p = prefs(context)
        synchronized(lock) {
            p.edit().apply {
                if (tokens == null) remove(KEY_THRESHOLD_TOKENS) else putInt(KEY_THRESHOLD_TOKENS, tokens)
            }.apply()
            publish { it.copy(thresholdTokens = tokens) }
        }
    }

    /**
     * [T-android-auto-compact-per-model] Persist the threshold for ONE
     * `ModelEntry`, keyed by its `"{instanceId}/{modelId}"` id.
     *
     * [tokens] null is not "delete the row": it records an explicit null, which
     * means "use this model's capacity-derived default" and suppresses the
     * legacy global fallback for that entry. That is what the settings UI means
     * by clearing a model's threshold, and it has to be distinguishable from
     * "this model was never configured" so an upgraded install's old global
     * line cannot silently reappear on one model while the user is looking at
     * another.
     *
     * The write is a read-modify-write of one JSON blob, so it runs under
     * [lock] — two models configured at almost the same moment must both land,
     * and the published snapshot must describe what was actually stored.
     *
     * @throws IllegalArgumentException on a blank entry id (there is nothing to
     *   key the override by; associations are never guessed) or a non-positive
     *   [tokens] (it would compact on every turn).
     */
    fun setThresholdTokens(context: Context, entryId: String, tokens: Int?) {
        val key = entryId.trim()
        require(key.isNotEmpty()) {
            "Auto-compact threshold needs a non-blank ModelEntry id, was \"$entryId\""
        }
        require(tokens == null || tokens > 0) {
            "Auto-compact threshold must be a positive token count or null, was $tokens"
        }
        appContext = context.applicationContext
        val p = prefs(context)
        synchronized(lock) {
            val next = LinkedHashMap(_config.value.thresholdTokensByEntryId)
            next[key] = tokens
            writeThresholdMap(p, next)
            publish { it.copy(thresholdTokensByEntryId = next) }
        }
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
        val p = prefs(context)
        synchronized(lock) {
            p.edit().apply {
                if (normalized == null) remove(KEY_MODEL_ENTRY_ID) else putString(KEY_MODEL_ENTRY_ID, normalized)
            }.apply()
            publish { it.copy(modelEntryId = normalized) }
        }
    }

    /**
     * Publish a full snapshot. `update` keeps concurrent setters from
     * overwriting each other: each one applies its own field to whatever the
     * current snapshot is, so enabled/threshold/model/map stay consistent.
     */
    private fun publish(transform: (AutoCompactConfig) -> AutoCompactConfig) {
        _config.update(transform)
    }

    // ── Per-entry threshold JSON ─────────────────────────────────────────────

    /**
     * Store [map] as the single JSON blob, or drop the key entirely when there
     * is nothing to override ("no overrides" is the absence of the key, so a
     * cleared install looks exactly like a fresh one).
     */
    private fun writeThresholdMap(prefs: SharedPreferences, map: Map<String, Int?>) {
        prefs.edit().apply {
            if (map.isEmpty()) {
                remove(KEY_THRESHOLD_TOKENS_BY_ENTRY)
            } else {
                putString(KEY_THRESHOLD_TOKENS_BY_ENTRY, encodeThresholdMap(map))
            }
        }.apply()
    }

    /**
     * `{"{instanceId}/{modelId}": 120000, "…": null}`, keys sorted so the
     * stored text is stable (and a diff of the prefs file is readable).
     */
    private fun encodeThresholdMap(map: Map<String, Int?>): String = JSONObject().apply {
        for (key in map.keys.sorted()) {
            // `JSONObject.put(key, null)` REMOVES the key on Android, which
            // would erase exactly the explicit null this setting needs; the
            // sentinel must be passed through instead.
            put(key, map[key] ?: JSONObject.NULL)
        }
    }.toString()

    /**
     * Read the map back defensively. Anything that is not a usable entry is
     * dropped rather than corrected: an unreadable blob means "no overrides"
     * (the legacy global threshold still applies), and a single bad row must
     * not cost the user every other model's threshold. Nothing here throws —
     * this runs from [prime].
     */
    private fun decodeThresholdMap(raw: String?): Map<String, Int?> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Int?>()
        val keys = runCatching { obj.keys().asSequence().toList() }.getOrDefault(emptyList())
        for (rawKey in keys) {
            val key = rawKey.trim()
            // Blank ids cannot be written through the setter, and a duplicate
            // (e.g. hand-edited " a" and "a") resolves to the first one so the
            // result never depends on iteration order.
            if (key.isEmpty() || out.containsKey(key)) continue
            // Explicit JSON null is the user's "use this model's capacity
            // default" and MUST survive the round trip: a value read through a
            // plain `map[key]` would be indistinguishable from a missing key,
            // which is why thresholdFor() checks containsKey.
            if (runCatching { obj.isNull(rawKey) }.getOrDefault(false)) {
                out[key] = null
                continue
            }
            val tokens = positiveIntOrNull(runCatching { obj.opt(rawKey) }.getOrNull()) ?: continue
            out[key] = tokens
        }
        return out
    }

    /**
     * A JSON value as a usable threshold, or null when it is not one. Accepts
     * only positive integers that fit [Int]; strings, booleans, objects,
     * fractional numbers, 0, negatives and over-large values are all rejected
     * so a hand-edited or future-written blob degrades to "no override".
     */
    private fun positiveIntOrNull(value: Any?): Int? = when (value) {
        is Int -> value.takeIf { it > 0 }
        is Long -> value.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
        is Number -> {
            val d = value.toDouble()
            if (d.isFinite() && d >= 1.0 && d <= Int.MAX_VALUE.toDouble() && d % 1.0 == 0.0) d.toInt() else null
        }
        else -> null
    }
}
