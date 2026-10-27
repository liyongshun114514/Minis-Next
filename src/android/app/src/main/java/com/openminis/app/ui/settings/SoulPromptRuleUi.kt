package com.openminis.app.ui.settings

import com.openminis.app.agent.SoulPromptRule
import com.openminis.app.agent.SoulPromptRuleTarget

/**
 * Presentation helpers for the scoped-prompt rules list.
 *
 * This file is deliberately Android-free: the rules list is persisted by
 * [com.openminis.app.agent.SoulPromptRuleStore] and resolved by
 * [com.openminis.app.agent.SoulPromptRuleResolver], and neither of those is
 * changed here. The functions below only decide how the stored list is shown
 * and how the up/down buttons rewrite it.
 *
 * Ordering contract (kept identical to the resolver):
 *  - the persisted list order is the injection order within a scope;
 *  - group matches are appended before model matches, no matter where a rule
 *    sits in the list.
 *
 * Because of that second point, a reorder is only ever allowed *inside* one
 * scope. Swapping a group rule with a model rule would change the stored list
 * but not the prompt, which would look like the button did nothing.
 */
object SoulPromptRuleUi {

    /** Longest preview shown for a rule body, in characters (not bytes). */
    const val PREVIEW_CHARS: Int = 160

    private val WHITESPACE = Regex("\\s+")

    /** Rules of [target] in stored order, each carrying its absolute index. */
    fun entriesFor(
        rules: List<SoulPromptRule>,
        target: SoulPromptRuleTarget,
    ): List<IndexedValue<SoulPromptRule>> =
        rules.withIndex().filter { it.value.target == target }

    fun enabledCount(rules: List<SoulPromptRule>): Int = rules.count { it.enabled }

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
     * Move [ruleId] by [delta] (-1 = up, +1 = down) among rules of the SAME
     * scope. Every other rule keeps its absolute slot, so rules of the other
     * scope cannot be displaced by a move that was never offered.
     *
     * Returns the original list instance when the move is a no-op (unknown id,
     * wrong scope, or already at the edge) so callers can skip a write.
     */
    fun moveWithinTier(
        rules: List<SoulPromptRule>,
        ruleId: String,
        delta: Int,
    ): List<SoulPromptRule> {
        if (delta == 0) return rules
        val rule = rules.firstOrNull { it.id == ruleId } ?: return rules
        val tierIndices = rules.indices.filter { rules[it].target == rule.target }
        val position = tierIndices.indexOfFirst { rules[it].id == ruleId }
        if (position < 0) return rules
        val destination = position + delta
        if (destination !in tierIndices.indices) return rules
        val from = tierIndices[position]
        val to = tierIndices[destination]
        return rules.toMutableList().also {
            it[from] = rules[to]
            it[to] = rules[from]
        }
    }

    /** Resolve a rule's stored target id against the live group/model lists. */
    fun targetRef(
        rule: SoulPromptRule,
        groups: List<SoulPromptGroupOption>,
        models: List<SoulPromptModelOption>,
    ): SoulPromptTargetRef = when (rule.target) {
        SoulPromptRuleTarget.GROUP -> {
            val id = rule.groupId
            val found = if (id.isNullOrBlank()) null else groups.firstOrNull { it.id == id }
            if (found == null) SoulPromptTargetRef.Missing else SoulPromptTargetRef.Found(found.label)
        }
        SoulPromptRuleTarget.MODEL -> {
            val instance = rule.providerInstanceId
            val model = rule.modelId
            val found = if (instance.isNullOrBlank() || model.isNullOrBlank()) {
                null
            } else {
                models.firstOrNull {
                    it.providerInstanceId == instance && it.modelId == model
                }
            }
            if (found == null) SoulPromptTargetRef.Missing else SoulPromptTargetRef.Found(found.label)
        }
    }
}

/** Display resolution of a stored target. */
sealed interface SoulPromptTargetRef {
    data class Found(val label: String) : SoulPromptTargetRef
    /** The group/model was deleted or the stored id is blank. Never global. */
    data object Missing : SoulPromptTargetRef
}
