package com.openminis.app.ui.settings

import com.openminis.app.agent.SoulPromptMode
import com.openminis.app.agent.SoulPromptRule
import com.openminis.app.agent.SoulPromptRuleTarget
import java.util.UUID

/**
 * [T-kairos-prompt-mode] One selectable model. Identity is the provider
 * instance + model id pair, never the display name: two providers can expose
 * the same model name and their prompts must stay separate.
 *
 * [label] is the combined one-line label (kept for callers/tests that build an
 * option without the two halves); [modelName] and [providerLabel] are what the
 * rows display — name on top, source underneath.
 *
 * This type lives here (and not in the Compose editor) so the list algebra
 * below stays plain Kotlin: no Android, no Compose, no store.
 */
data class SoulPromptModelOption(
    val providerInstanceId: String,
    val modelId: String,
    val label: String,
    val modelName: String = "",
    val providerLabel: String = "",
) {
    /** Display name for a row title, falling back to the combined label. */
    val displayName: String get() = modelName.ifBlank { label }
}

/**
 * [T-kairos-prompt-mode] Presentation + list algebra for the per-model prompt
 * page.
 *
 * The runtime contract this file has to keep intact:
 *  - a CUSTOM prompt is keyed by the exact (providerInstanceId, modelId) pair,
 *    never by a model name and never by "all models";
 *  - a pair has at most ONE effective entry. Duplicates are not deleted (that
 *    would silently lose the user's text) — they are stably disabled so the
 *    stored list keeps every prompt the user ever wrote;
 *  - legacy GROUP rules are not shown, not edited and never removed here. The
 *    mode switch makes them inert; keeping them means a user who switches back
 *    to an older build does not lose what they wrote.
 *
 * Everything below is plain values in / plain values out: no Android, no
 * store, no resolver, so the unit tests drive it directly.
 */
object SoulPromptRuleUi {

    /** Longest preview shown for a prompt body, in characters (not bytes). */
    const val PREVIEW_CHARS: Int = 160

    private val WHITESPACE = Regex("\\s+")

    /**
     * One-line preview: newlines and repeated spaces collapse to single
     * spaces so a multi-line prompt cannot stretch a list row, and the tail is
     * cut on a character boundary with an ellipsis. Empty content stays empty
     * (the caller substitutes its own "no prompt text" label).
     */
    fun preview(content: String, maxChars: Int = PREVIEW_CHARS): String {
        val collapsed = content.replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return ""
        if (maxChars <= 0) return ""
        if (collapsed.length <= maxChars) return collapsed
        return collapsed.take(maxChars).trimEnd() + "…"
    }

    /**
     * The rows of the page: MODEL rules only, in stored order. The order is
     * the persisted list order so the list cannot reshuffle between reads,
     * and GROUP rules stay in the file but out of this list.
     */
    fun modelEntries(rules: List<SoulPromptRule>): List<SoulPromptRule> =
        rules.filter { it.target == SoulPromptRuleTarget.MODEL }

    /** Enabled MODEL prompts, for the list summary (at most one per pair). */
    fun enabledModelCount(rules: List<SoulPromptRule>): Int =
        rules.count { it.target == SoulPromptRuleTarget.MODEL && it.enabled }

    /** Legacy GROUP rules kept in the document but not applied in either mode. */
    fun legacyGroupCount(rules: List<SoulPromptRule>): Int =
        rules.count { it.target == SoulPromptRuleTarget.GROUP }

    /**
     * Stable identity of a target pair. `\u0000` cannot appear in an id, so a
     * concatenation cannot collide the way `a + b` on two untrusted strings
     * can (("ab","c") vs ("a","bc")).
     */
    fun pairKey(providerInstanceId: String?, modelId: String?): String? {
        if (providerInstanceId.isNullOrBlank() || modelId.isNullOrBlank()) return null
        return providerInstanceId + "\u0000" + modelId
    }

    /** Pair key of a stored rule, or null when the rule has no usable target. */
    fun pairKey(rule: SoulPromptRule): String? = pairKey(rule.providerInstanceId, rule.modelId)

    /** Every pair that already has a stored MODEL prompt (enabled or not). */
    fun configuredPairs(rules: List<SoulPromptRule>): Set<String> =
        rules.asSequence()
            .filter { it.target == SoulPromptRuleTarget.MODEL }
            .mapNotNull { pairKey(it) }
            .toSet()

    /** The live model option a rule points at, or null when it was removed. */
    fun modelRef(
        rule: SoulPromptRule,
        models: List<SoulPromptModelOption>,
    ): SoulPromptModelOption? {
        val key = pairKey(rule) ?: return null
        return models.firstOrNull { pairKey(it.providerInstanceId, it.modelId) == key }
    }

    /**
     * Human title for a stored rule: the model's display name when it still
     * exists, otherwise the stored model id (so the user can recognise which
     * prompt it is), otherwise empty.
     */
    fun modelTitle(
        rule: SoulPromptRule,
        models: List<SoulPromptModelOption>,
    ): String = modelRef(rule, models)?.displayName ?: rule.modelId.orEmpty()

    /**
     * Source line for a row: the provider's display label when the model still
     * exists, otherwise the stored provider instance id. Never fabricated.
     */
    fun sourceLabel(
        rule: SoulPromptRule,
        models: List<SoulPromptModelOption>,
    ): String {
        val found = modelRef(rule, models)?.providerLabel.orEmpty()
        return found.ifBlank { rule.providerInstanceId.orEmpty() }
    }

    /**
     * Enable / disable one rule. Turning a rule ON also stably disables any
     * other enabled rule for the same pair, because only one entry per pair is
     * allowed to be effective — a toggle must not create the duplicate the
     * editor/save path refuses to create.
     */
    fun setEnabled(
        rules: List<SoulPromptRule>,
        ruleId: String,
        enabled: Boolean,
    ): List<SoulPromptRule> {
        val current = rules.firstOrNull { it.id == ruleId } ?: return rules
        if (current.enabled == enabled) return rules
        val updated = rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
        if (!enabled) return updated
        return disableOtherPairs(updated, current.id)
    }

    /**
     * Insert or replace one MODEL prompt, preserving everything else.
     *
     *  - an existing rule with the same [SoulPromptRule.id] keeps its slot
     *    (the list order is stable, so editing never reorders the page) and
     *    keeps its enabled flag (the editor has no on/off control);
     *  - a new prompt for a pair that is already configured UPDATES the
     *    effective rule for that pair instead of creating a second row: that
     *    is what "one prompt per model" means, and it keeps the old text of
     *    every OTHER model untouched;
     *  - a genuinely new pair is appended;
     *  - any other enabled rule for the same pair is disabled, never deleted.
     *
     * Rule names are not part of this UI, so they are cleared rather than
     * silently kept and rendered into the prompt heading where the user could
     * not see or edit them.
     *
     * Returns the same instance when the incoming rule has no usable target or
     * when nothing changed.
     */
    fun upsertModelRule(
        rules: List<SoulPromptRule>,
        rule: SoulPromptRule,
    ): List<SoulPromptRule> {
        val key = pairKey(rule.providerInstanceId, rule.modelId) ?: return rules
        val normalized = rule.copy(
            name = "",
            target = SoulPromptRuleTarget.MODEL,
            groupId = null,
        )
        val idOwner = rules.firstOrNull { it.id == normalized.id }
        val byId = if (idOwner?.target == SoulPromptRuleTarget.MODEL) {
            rules.indexOfFirst { it.id == normalized.id }
        } else {
            -1
        }
        if (byId >= 0) {
            val previous = rules[byId]
            val replacement = normalized.copy(enabled = previous.enabled)
            val merged = rules.toMutableList().also { it[byId] = replacement }
            return disableOtherPairs(merged, replacement.id)
        }

        // Prefer the row that is actually in use for this pair, then any row
        // for the pair, so "add" edits the visible effective prompt instead of
        // appending an inert duplicate next to it.
        val existingIndex = rules.indexOfFirst {
            it.target == SoulPromptRuleTarget.MODEL && it.enabled && pairKey(it) == key
        }.takeIf { it >= 0 } ?: rules.indexOfFirst {
            it.target == SoulPromptRuleTarget.MODEL && pairKey(it) == key
        }
        if (existingIndex >= 0) {
            val previous = rules[existingIndex]
            // An id collision with a non-MODEL rule would create two rules
            // with one id; give the new MODEL rule a fresh id instead.
            val replacement = normalized.copy(
                id = if (idOwner != null && idOwner.target != SoulPromptRuleTarget.MODEL) {
                    UUID.randomUUID().toString()
                } else {
                    previous.id
                },
                enabled = previous.enabled,
            )
            val merged = rules.toMutableList().also { it[existingIndex] = replacement }
            return disableOtherPairs(merged, replacement.id)
        }

        val appended = normalized.copy(
            id = if (idOwner != null) UUID.randomUUID().toString() else normalized.id,
        )
        return disableOtherPairs(rules + appended, appended.id)
    }

    /**
     * Remove exactly one rule. Legacy GROUP rules are only removable by
     * passing their own id — the page never calls this with a group id.
     */
    fun removeRule(rules: List<SoulPromptRule>, ruleId: String): List<SoulPromptRule> =
        if (rules.none { it.id == ruleId }) rules else rules.filterNot { it.id == ruleId }

    /**
     * Enforce "at most one enabled MODEL rule per pair" without deleting any
     * text. The first enabled rule for a pair in stored order wins; later
     * enabled duplicates become disabled, and a MODEL rule without a usable
     * target is disabled because it is inert anyway. GROUP rules are returned
     * untouched. Returns the same instance when the list is already stable.
     */
    fun stabilize(rules: List<SoulPromptRule>): List<SoulPromptRule> {
        val claimed = HashSet<String>()
        var changed = false
        val next = rules.map { rule ->
            if (rule.target != SoulPromptRuleTarget.MODEL || !rule.enabled) {
                rule
            } else {
                val key = pairKey(rule)
                when {
                    key == null -> {
                        changed = true
                        rule.copy(enabled = false)
                    }
                    !claimed.add(key) -> {
                        changed = true
                        rule.copy(enabled = false)
                    }
                    else -> rule
                }
            }
        }
        return if (changed) next else rules
    }

    /**
     * Picker filter. Matches the display name, the provider label, the
     * provider instance id and the raw model id, so a user who knows either
     * half can find the entry. Blank query returns the list unchanged.
     */
    fun filterModels(
        models: List<SoulPromptModelOption>,
        query: String,
    ): List<SoulPromptModelOption> {
        val needle = query.trim()
        if (needle.isEmpty()) return models
        return models.filter { option ->
            option.displayName.contains(needle, ignoreCase = true) ||
                option.providerLabel.contains(needle, ignoreCase = true) ||
                option.providerInstanceId.contains(needle, ignoreCase = true) ||
                option.modelId.contains(needle, ignoreCase = true)
        }
    }

    /**
     * Whether a tap on the mode switch should start a write. Loading must have
     * finished (the segmented control is disabled before that), a write must
     * not already be in flight, and tapping the current mode is a no-op — not
     * a redundant disk write that could fail and confuse the user.
     */
    fun shouldSwitchMode(
        current: SoulPromptMode,
        requested: SoulPromptMode,
        saving: Boolean,
        loaded: Boolean,
    ): Boolean = loaded && !saving && current != requested

    /**
     * Disable every enabled MODEL rule that shares [keeperId]'s pair. Only an
     * ENABLED keeper claims the pair: a disabled draft must not knock out the
     * entry that is actually in use. Returns the same instance when nothing
     * changed.
     */
    private fun disableOtherPairs(
        rules: List<SoulPromptRule>,
        keeperId: String,
    ): List<SoulPromptRule> {
        val keeper = rules.firstOrNull { it.id == keeperId } ?: return rules
        if (!keeper.enabled) return rules
        val key = pairKey(keeper) ?: return rules
        var changed = false
        val next = rules.map { rule ->
            if (rule.id == keeperId ||
                rule.target != SoulPromptRuleTarget.MODEL ||
                !rule.enabled ||
                pairKey(rule) != key
            ) {
                rule
            } else {
                changed = true
                rule.copy(enabled = false)
            }
        }
        return if (changed) next else rules
    }
}
