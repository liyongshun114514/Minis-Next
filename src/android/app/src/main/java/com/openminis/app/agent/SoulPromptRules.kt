package com.openminis.app.agent

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [T-soul-scoped-prompt] Per-group / per-model append-only system prompt rules.
 *
 * The global prompt stays exactly where it always was: the Markdown body of
 * `SOUL.md`, rendered by [SystemPromptBuilder]. This file does NOT migrate,
 * rewrite or replace it — a rule can only ADD text after the global block, so
 * an old install (no `SOUL_PROMPT_RULES.json`) behaves exactly as before and
 * a downgrade simply ignores the extra file.
 *
 * Identity is deliberately explicit:
 *  - a GROUP rule names `modelGroups[].id`;
 *  - a MODEL rule names the pair (`ProviderInstance.id`, `LLMModel.id`).
 *
 * A model's display name is never an identity. Two providers can expose the
 * same model name (e.g. `gpt-4o` via two relays); matching on the name alone
 * would leak one provider's instructions to the other. The instance id is part
 * of the key, so that cannot happen.
 *
 * There is no "global" target. A rule with a blank/missing target is invalid
 * and is skipped by [SoulPromptRuleResolver] — it must never silently fall
 * back to applying everywhere.
 */
@Serializable
enum class SoulPromptRuleTarget {
    GROUP,
    MODEL,
}

@Serializable
data class SoulPromptRule(
    val id: String = UUID.randomUUID().toString(),
    /** Optional human label shown in Settings; never sent to the model. */
    val name: String = "",
    val enabled: Boolean = true,
    val target: SoulPromptRuleTarget = SoulPromptRuleTarget.GROUP,
    /** Set for [SoulPromptRuleTarget.GROUP]. */
    val groupId: String? = null,
    /** Set for [SoulPromptRuleTarget.MODEL], together with [modelId]. */
    val providerInstanceId: String? = null,
    /** Set for [SoulPromptRuleTarget.MODEL], together with [providerInstanceId]. */
    val modelId: String? = null,
    val content: String = "",
) {
    /**
     * A target that can actually match something. Blank ids are NOT treated as
     * wildcards: no target means the rule is inert, not global.
     */
    val hasValidTarget: Boolean
        get() = when (target) {
            SoulPromptRuleTarget.GROUP -> !groupId.isNullOrBlank()
            SoulPromptRuleTarget.MODEL ->
                !providerInstanceId.isNullOrBlank() && !modelId.isNullOrBlank()
        }
}

/** What the runtime currently has selected, captured at request time. */
data class SoulPromptSelection(
    val groupId: String? = null,
    val providerInstanceId: String? = null,
    val modelId: String? = null,
)

/** Which layer a matched rule belongs to. Global is implicit (SOUL.md body). */
enum class SoulPromptRuleTier {
    GROUP,
    MODEL,
}

data class MatchedSoulPromptRule(
    val rule: SoulPromptRule,
    val tier: SoulPromptRuleTier,
)

/**
 * Pure resolution. No Android, no files, no clocks — this is what the unit
 * tests drive directly and what [SystemPromptBuilder] calls at prompt-build
 * time.
 *
 * Ordering is part of the contract and is intentionally boring:
 *   1. group matches, in list order;
 *   2. model matches, in list order.
 *
 * The model tier always comes last because it is the more specific scope.
 * "In list order" is the order the user sees and the order persisted in the
 * JSON document — no hash-map iteration, no sorting by id, so the same config
 * always produces the same prompt bytes (prompt-cache friendly).
 */
object SoulPromptRuleResolver {

    fun matchTier(rule: SoulPromptRule, selection: SoulPromptSelection): SoulPromptRuleTier? {
        if (!rule.enabled) return null
        if (rule.content.isBlank()) return null
        if (!rule.hasValidTarget) return null
        return when (rule.target) {
            SoulPromptRuleTarget.GROUP -> {
                val selected = selection.groupId
                if (selected != null && rule.groupId == selected) SoulPromptRuleTier.GROUP else null
            }
            SoulPromptRuleTarget.MODEL -> {
                val instance = selection.providerInstanceId
                val model = selection.modelId
                if (instance != null && model != null &&
                    rule.providerInstanceId == instance && rule.modelId == model
                ) {
                    SoulPromptRuleTier.MODEL
                } else {
                    null
                }
            }
        }
    }

    fun matching(
        rules: List<SoulPromptRule>,
        selection: SoulPromptSelection,
    ): List<MatchedSoulPromptRule> {
        val group = ArrayList<MatchedSoulPromptRule>()
        val model = ArrayList<MatchedSoulPromptRule>()
        for (rule in rules) {
            when (matchTier(rule, selection)) {
                SoulPromptRuleTier.GROUP -> group.add(MatchedSoulPromptRule(rule, SoulPromptRuleTier.GROUP))
                SoulPromptRuleTier.MODEL -> model.add(MatchedSoulPromptRule(rule, SoulPromptRuleTier.MODEL))
                null -> Unit
            }
        }
        return if (model.isEmpty()) group else group + model
    }
}

/**
 * Persisted envelope. Explicit [version] so a later format change can migrate
 * instead of guessing; unknown keys are ignored so a newer build's extra
 * fields do not destroy the list on an older one.
 */
@Serializable
data class SoulPromptRulesDocument(
    val version: Int = CURRENT_VERSION,
    val rules: List<SoulPromptRule> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/**
 * File-backed store for [SoulPromptRule]s.
 *
 * Location: `<filesDir>/minis-global/memory/SOUL_PROMPT_RULES.json`, beside
 * `SOUL.md`. The memory directory is already what backup export walks, so the
 * rules travel with a backup without a second integration.
 *
 * Threading: the public methods are synchronized; the runtime cache is
 * volatile. [cachedOrLoad] does file I/O only on the first call after process
 * start / [invalidate], so a per-request read in the agent loop is a list
 * read, not a disk read.
 *
 * Failure policy: reads NEVER throw — a missing, truncated or unreadable file
 * yields an empty list, i.e. the shipped global-prompt-only behaviour. Writes
 * DO throw so the Settings screen can show the user why a change did not
 * stick.
 */
object SoulPromptRuleStore {

    private const val TAG = "SoulPromptRules"
    private const val MEMORY_SUBDIR = "minis-global/memory"
    private const val FILE_NAME = "SOUL_PROMPT_RULES.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Volatile
    private var cached: List<SoulPromptRule>? = null

    /**
     * Fingerprint of the file the cache was built from. A backup restore (or
     * any other writer outside this process) replaces the file without going
     * through [save], so a pure in-memory cache would keep serving the old
     * rules until the next process start. `lastModified + length` is cheap to
     * check per turn and changes on every rewrite; [invalidate] remains for
     * callers that want an explicit drop.
     */
    @Volatile
    private var cachedFingerprint: String? = null

    fun fileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), FILE_NAME)

    @Synchronized
    fun load(context: Context): List<SoulPromptRule> = readFromDisk(context)

    /**
     * Runtime accessor: serve the in-process snapshot when present, otherwise
     * read the file once. Used by the agent loop at turn start.
     */
    @Synchronized
    fun cachedOrLoad(context: Context): List<SoulPromptRule> {
        val file = fileLocation(context)
        val fingerprint = fingerprintOf(file)
        val hit = cached
        if (hit != null && fingerprint == cachedFingerprint) return hit
        return readFromDisk(context)
    }

    /** Drop the runtime snapshot (e.g. after an external restore/import). */
    @Synchronized
    fun invalidate() {
        cached = null
        cachedFingerprint = null
    }

    @Synchronized
    fun save(context: Context, rules: List<SoulPromptRule>) {
        val normalized = normalize(rules)
        val target = fileLocation(context)
        target.parentFile?.mkdirs()
        val text = encode(normalized)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) {
                // Same defensive fallback as SoulStore.save: keep a filesystem
                // that rejects this rename from losing the write.
                target.writeText(text)
                tmp.delete()
            }
            cached = normalized
            cachedFingerprint = fingerprintOf(target)
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            AppLogger.warning(TAG, "save failed: ${t.message}")
            throw t
        }
    }

    /** Pure encoder (tests use this without a Context). */
    fun encode(rules: List<SoulPromptRule>): String =
        json.encodeToString(
            SoulPromptRulesDocument.serializer(),
            SoulPromptRulesDocument(rules = normalize(rules)),
        )

    /**
     * Pure decoder. Accepts the current object envelope and, defensively, a
     * bare JSON array (a hand-written/third-party file). Any parse failure
     * returns an empty list instead of throwing into the prompt path.
     */
    fun decode(text: String): List<SoulPromptRule> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsed = runCatching {
            if (trimmed.startsWith("[")) {
                json.decodeFromString(ListSerializer(SoulPromptRule.serializer()), trimmed)
            } else {
                json.decodeFromString(SoulPromptRulesDocument.serializer(), trimmed).rules
            }
        }.getOrNull() ?: return emptyList()
        return normalize(parsed)
    }

    /**
     * Fill in a missing id and otherwise keep the list untouched — including
     * rules whose target no longer exists. Keeping an orphaned rule lets the
     * user repair it; it cannot match, so it has no runtime effect.
     */
    fun normalize(rules: List<SoulPromptRule>): List<SoulPromptRule> =
        rules.map { rule ->
            if (rule.id.isBlank()) rule.copy(id = UUID.randomUUID().toString()) else rule
        }

    private fun readFromDisk(context: Context): List<SoulPromptRule> {
        val file = fileLocation(context)
        val rules = if (!file.exists()) {
            emptyList()
        } else {
            runCatching { decode(file.readText()) }.getOrElse {
                AppLogger.warning(TAG, "read failed: ${it.message}")
                emptyList()
            }
        }
        cached = rules
        cachedFingerprint = fingerprintOf(file)
        return rules
    }

    private fun fingerprintOf(file: File): String =
        if (file.isFile) "${file.lastModified()}:${file.length()}" else "missing"
}
