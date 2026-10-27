package com.openminis.app.ui.settings

import com.openminis.app.agent.SoulPromptRule
import com.openminis.app.agent.SoulPromptRuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the presentation helpers behind the rules list.
 *
 * These are plain values in / plain values out: no Android, no store, no
 * resolver. The assertions pin the two things the UI must not get wrong —
 * a move never crosses a scope, and a reorder never changes which rules exist.
 */
class SoulPromptRuleUiTest {

    private fun rule(
        id: String,
        target: SoulPromptRuleTarget,
        groupId: String? = null,
        instance: String? = null,
        modelId: String? = null,
        enabled: Boolean = true,
        content: String = "text $id",
    ) = SoulPromptRule(
        id = id,
        enabled = enabled,
        target = target,
        groupId = groupId,
        providerInstanceId = instance,
        modelId = modelId,
        content = content,
    )

    private val g1 = rule("g1", SoulPromptRuleTarget.GROUP, groupId = "group-a")
    private val g2 = rule("g2", SoulPromptRuleTarget.GROUP, groupId = "group-b")
    private val m1 = rule("m1", SoulPromptRuleTarget.MODEL, instance = "inst", modelId = "model-a")

    @Test
    fun `moving up swaps only rules in the same scope`() {
        val rules = listOf(g1, m1, g2)
        val moved = SoulPromptRuleUi.moveWithinTier(rules, "g2", -1)
        assertEquals(listOf("g2", "m1", "g1"), moved.map { it.id })
        // The model rule keeps its absolute slot, so its stored position and
        // the prompt bytes it contributes are unchanged.
        assertEquals("m1", moved[1].id)
    }

    @Test
    fun `moving down within a scope leaves the other scope alone`() {
        val rules = listOf(g1, g2, m1)
        val moved = SoulPromptRuleUi.moveWithinTier(rules, "g1", 1)
        assertEquals(listOf("g2", "g1", "m1"), moved.map { it.id })
    }

    @Test
    fun `a move at the edge or across a scope is a no-op`() {
        val rules = listOf(g1, m1, g2)
        assertSame(rules, SoulPromptRuleUi.moveWithinTier(rules, "g1", -1))
        assertSame(rules, SoulPromptRuleUi.moveWithinTier(rules, "g2", 1))
        assertSame(rules, SoulPromptRuleUi.moveWithinTier(rules, "m1", 1))
        assertSame(rules, SoulPromptRuleUi.moveWithinTier(rules, "missing", 1))
    }

    @Test
    fun `entries are listed in stored order per scope`() {
        val rules = listOf(g1, m1, g2)
        assertEquals(listOf(0, 2), SoulPromptRuleUi.entriesFor(rules, SoulPromptRuleTarget.GROUP).map { it.index })
        assertEquals(listOf(1), SoulPromptRuleUi.entriesFor(rules, SoulPromptRuleTarget.MODEL).map { it.index })
        assertEquals(2, SoulPromptRuleUi.enabledCount(listOf(g1, m1, g2.copy(enabled = false))))
    }

    @Test
    fun `preview collapses whitespace and truncates on a character boundary`() {
        assertEquals("line one line two", SoulPromptRuleUi.preview("  line one\n\nline   two  "))
        assertEquals("", SoulPromptRuleUi.preview(" \n\t "))
        val long = "字".repeat(200)
        val preview = SoulPromptRuleUi.preview(long, maxChars = 10)
        assertTrue(preview.startsWith("字".repeat(10)))
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `target resolution reports a deleted group or model as missing`() {
        val groups = listOf(SoulPromptGroupOption("group-a", "Group A"))
        val models = listOf(SoulPromptModelOption("inst", "model-a", "Model A · Provider"))

        assertEquals(
            SoulPromptTargetRef.Found("Group A"),
            SoulPromptRuleUi.targetRef(g1, groups, models),
        )
        assertEquals(
            SoulPromptTargetRef.Found("Model A · Provider"),
            SoulPromptRuleUi.targetRef(m1, groups, models),
        )
        assertEquals(
            SoulPromptTargetRef.Missing,
            SoulPromptRuleUi.targetRef(g2, groups, models),
        )
        assertEquals(
            SoulPromptTargetRef.Missing,
            SoulPromptRuleUi.targetRef(m1, groups, emptyList()),
        )
    }
}
