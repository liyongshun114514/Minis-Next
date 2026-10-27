package com.openminis.app.agent

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [T-soul-scoped-prompt] Per-group / per-model append-only system prompt rules.
 *
 * [T-character-cards] The persisted form is now a GROUP/MODEL [CharacterCard]
 * (see [SoulPromptRuleStore]); these classes remain as the pure resolver and as
 * the legacy in-memory shape the old Settings page still speaks. A rule can
 * only ADD text after the global card — it never replaces it.
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
 * [T-character-cards] COMPATIBILITY view of the scoped character cards.
 *
 * There is no `SOUL_PROMPT_RULES.json` any more: a GROUP/MODEL rule is the
 * projection of a GROUP/MODEL [CharacterCard] and [CharacterCardStore] is the
 * only thing that touches disk. Both directions deliberately reuse the rule id
 * as the card id, so the legacy Settings page can edit a scoped card without
 * ever duplicating it or wiping its non-rule fields.
 *
 * Mapping contract (see [SoulPromptRuleCardMapping]):
 *  - rule.content <-> card.systemPrompt (append-only instruction text);
 *  - rule.target/group/provider/model <-> card.scope/ids;
 *  - every other card field is preserved by an edit.
 *
 * Failure policy is inherited from the card store: reads never throw (a
 * damaged document reads as "no rules"), writes throw so the UI can explain
 * itself, and a corrupt document is never silently replaced.
 */
object SoulPromptRuleStore {

    private const val TAG = "SoulPromptRules"
    private const val MEMORY_SUBDIR = "minis-global/memory"
    private const val LEGACY_FILE_NAME = "SOUL_PROMPT_RULES.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * The pre-card file, kept ONLY so callers/tools that ask where the legacy
     * rules lived get an answer. Runtime never reads or writes it; it is
     * archived by [CharacterCardStore.importLegacy].
     */
    fun fileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), LEGACY_FILE_NAME)

    @Synchronized
    fun load(context: Context): List<SoulPromptRule> =
        CharacterCardStore.load(context)
            .filter { !it.isGlobal }
            .map { SoulPromptRuleCardMapping.toRule(it) }

    /** Runtime accessor. The card store already caches, so this is a list read. */
    @Synchronized
    fun cachedOrLoad(context: Context): List<SoulPromptRule> = load(context)

    /**
     * Drop every cache. Called after a backup restore; it now also clears the
     * card snapshot, which is what makes a restored SOUL/rules/card document
     * visible to the next turn.
     */
    @Synchronized
    fun invalidate() {
        CharacterCardStore.invalidate()
    }

    /**
     * Persist [rules] by rewriting the SCOPED cards.
     *
     * The supplied list is the complete projection of every scoped card, so a
     * card that is no longer in it is deleted (that is how a rule delete works)
     * and a card that is stays intact apart from the fields a rule owns.
     * GLOBAL cards are never touched.
     */
    @Synchronized
    fun save(context: Context, rules: List<SoulPromptRule>) {
        val normalized = normalize(rules)
        CharacterCardStore.update(context) { cards ->
            val existing = cards.filter { !it.isGlobal }.associateBy { it.id }
            val globals = cards.filter { it.isGlobal }
            globals + normalized.map { rule ->
                SoulPromptRuleCardMapping.toCard(rule, existing[rule.id])
            }
        }
    }

    /** Pure encoder for the legacy document shape (tests / export). */
    fun encode(rules: List<SoulPromptRule>): String =
        json.encodeToString(
            SoulPromptRulesDocument.serializer(),
            SoulPromptRulesDocument(rules = normalize(rules)),
        )

    /**
     * Pure decoder for the legacy document. Still used by
     * [CharacterCardLegacyMerge] to read a pre-card file during migration, and
     * by the compatibility UI while it still speaks this shape.
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
     * Strict legacy decode. Migration uses this so a malformed file is REPORTED
     * and kept on disk rather than silently read as an empty rule list.
     */
    fun decodeStrict(text: String): List<SoulPromptRule> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val parsed = try {
            if (trimmed.startsWith("[")) {
                json.decodeFromString(ListSerializer(SoulPromptRule.serializer()), trimmed)
            } else {
                json.decodeFromString(SoulPromptRulesDocument.serializer(), trimmed).rules
            }
        } catch (t: Throwable) {
            throw CharacterCardImportException("SOUL_PROMPT_RULES.json is malformed", t)
        }
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
}
