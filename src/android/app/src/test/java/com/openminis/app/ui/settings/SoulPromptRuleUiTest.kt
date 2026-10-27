package com.openminis.app.ui.settings

import com.openminis.app.agent.SoulPromptMode
import com.openminis.app.agent.SoulPromptRule
import com.openminis.app.agent.SoulPromptRuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the presentation helpers behind the per-model prompt page.
 *
 * These are plain values in / plain values out: no Android, no store, no
 * resolver. The assertions pin the contract the UI must not break:
 *  - only MODEL prompts are listed;
 *  - identity is the (provider instance, model) pair;
 *  - a pair has at most one effective prompt, but no stored text is ever
 *    dropped just because an old file contains duplicates;
 *  - a missing target is never rebound automatically.
 */
class SoulPromptRuleUiTest {

    private fun modelRule(
        id: String,
        instance: String? = "inst",
        modelId: String? = "model-a",
        enabled: Boolean = true,
        content: String = "text $id",
        name: String = "",
    ) = SoulPromptRule(
        id = id,
        name = name,
        enabled = enabled,
        target = SoulPromptRuleTarget.MODEL,
        providerInstanceId = instance,
        modelId = modelId,
        content = content,
    )

    private fun groupRule(
        id: String,
        groupId: String? = "group-a",
        enabled: Boolean = true,
        content: String = "group $id",
    ) = SoulPromptRule(
        id = id,
        enabled = enabled,
        target = SoulPromptRuleTarget.GROUP,
        groupId = groupId,
        content = content,
    )

    private val modelA = SoulPromptModelOption(
        providerInstanceId = "inst",
        modelId = "model-a",
        label = "Model A · Provider A",
        modelName = "Model A",
        providerLabel = "Provider A",
    )
    private val modelB = SoulPromptModelOption(
        providerInstanceId = "inst-2",
        modelId = "model-b",
        label = "Model B · Provider B",
        modelName = "Model B",
        providerLabel = "Provider B",
    )
    private val models = listOf(modelA, modelB)

    @Test
    fun `preview collapses whitespace and truncates on a character boundary`() {
        assertEquals("line one line two", SoulPromptRuleUi.preview("  line one\n\nline   two  "))
        assertEquals("", SoulPromptRuleUi.preview(" \n\t "))
        val long = "字".repeat(200)
        val preview = SoulPromptRuleUi.preview(long, maxChars = 10)
        assertTrue(preview.startsWith("字".repeat(10)))
        assertTrue(preview.endsWith("…"))
        assertEquals("", SoulPromptRuleUi.preview("text", maxChars = 0))
    }

    @Test
    fun `only model prompts are listed and stored order is kept`() {
        val rules = listOf(
            groupRule("g1"),
            modelRule("m1", modelId = "model-a"),
            groupRule("g2"),
            modelRule("m2", modelId = "model-b"),
        )
        assertEquals(listOf("m1", "m2"), SoulPromptRuleUi.modelEntries(rules).map { it.id })
        assertEquals(2, SoulPromptRuleUi.legacyGroupCount(rules))
        assertEquals(2, SoulPromptRuleUi.enabledModelCount(rules))
        assertEquals(
            1,
            SoulPromptRuleUi.enabledModelCount(
                listOf(groupRule("g"), modelRule("m1"), modelRule("m2", enabled = false)),
            ),
        )
    }

    @Test
    fun `pair identity cannot collide across concatenation boundaries`() {
        val first = SoulPromptRuleUi.pairKey("ab", "c")
        val second = SoulPromptRuleUi.pairKey("a", "bc")
        assertNotEquals(first, second)
        assertNull(SoulPromptRuleUi.pairKey("", "c"))
        assertNull(SoulPromptRuleUi.pairKey("a", " "))
        assertNull(SoulPromptRuleUi.pairKey(modelRule("m", instance = null)))
    }

    @Test
    fun `configured pairs ignore groups and targetless rows`() {
        val rules = listOf(
            modelRule("m1"),
            modelRule("m2", modelId = "model-b"),
            modelRule("m3", instance = null),
            groupRule("g"),
        )
        assertEquals(
            setOf(
                SoulPromptRuleUi.pairKey("inst", "model-a"),
                SoulPromptRuleUi.pairKey("inst", "model-b"),
            ),
            SoulPromptRuleUi.configuredPairs(rules),
        )
    }

    @Test
    fun `missing model is shown by stored ids and is never rebound`() {
        val rule = modelRule("m1", instance = "gone-provider", modelId = "gone-model")
        assertNull(SoulPromptRuleUi.modelRef(rule, models))
        assertEquals("gone-model", SoulPromptRuleUi.modelTitle(rule, models))
        assertEquals("gone-provider", SoulPromptRuleUi.sourceLabel(rule, models))

        val live = modelRule("m2")
        assertEquals(modelA, SoulPromptRuleUi.modelRef(live, models))
        assertEquals("Model A", SoulPromptRuleUi.modelTitle(live, models))
        assertEquals("Provider A", SoulPromptRuleUi.sourceLabel(live, models))
    }

    @Test
    fun `enabling a duplicate stably disables the other prompt for the same pair`() {
        val first = modelRule("a", content = "first")
        val duplicate = modelRule("dup", enabled = false, content = "duplicate")
        val other = modelRule("b", modelId = "model-b", content = "other")
        val rules = listOf(first, duplicate, other)

        val enabled = SoulPromptRuleUi.setEnabled(rules, "dup", true)
        assertFalse(enabled.first { it.id == "a" }.enabled)
        assertTrue(enabled.first { it.id == "dup" }.enabled)
        assertTrue("other pair untouched", enabled.first { it.id == "b" }.enabled)
        assertEquals("all three rows retained", 3, enabled.size)

        // Repeating the same operation is a no-op (same instance), which is
        // what makes a rapid switch tap cheap and stable.
        assertSame(enabled, SoulPromptRuleUi.setEnabled(enabled, "dup", true))
    }

    @Test
    fun `disabling a prompt never touches another pair`() {
        val rules = listOf(modelRule("a"), modelRule("b", modelId = "model-b"))
        val disabled = SoulPromptRuleUi.setEnabled(rules, "a", false)
        assertFalse(disabled.first { it.id == "a" }.enabled)
        assertTrue(disabled.first { it.id == "b" }.enabled)
        assertSame(rules, SoulPromptRuleUi.setEnabled(rules, "missing", false))
    }

    @Test
    fun `editing a rule keeps its slot and enabled flag and clears its hidden name`() {
        val a = modelRule("a", enabled = false, content = "old", name = "invisible")
        val b = modelRule("b", modelId = "model-b")
        val rules = listOf(groupRule("g"), a, b)

        val updated = SoulPromptRuleUi.upsertModelRule(
            rules,
            a.copy(content = "new text", name = "still invisible", enabled = true),
        )

        assertEquals(listOf("g", "a", "b"), updated.map { it.id })
        assertEquals("new text", updated.first { it.id == "a" }.content)
        assertFalse("editor does not silently re-enable", updated.first { it.id == "a" }.enabled)
        assertEquals("", updated.first { it.id == "a" }.name)
        assertEquals("b unchanged", b, updated.first { it.id == "b" })
        assertEquals("group retained", 1, SoulPromptRuleUi.legacyGroupCount(updated))
    }

    @Test
    fun `adding an existing pair updates that row instead of appending a duplicate`() {
        val first = modelRule("a", content = "old")
        val other = modelRule("b", modelId = "model-b", content = "other")
        val rules = listOf(first, other)

        val updated = SoulPromptRuleUi.upsertModelRule(
            rules,
            modelRule("brand-new-id", content = "replacement"),
        )

        assertEquals("no duplicate row", listOf("a", "b"), updated.map { it.id })
        assertEquals("replacement", updated.first { it.id == "a" }.content)
        assertEquals("other untouched", other, updated.first { it.id == "b" })
        assertTrue(updated.first { it.id == "a" }.enabled)
    }

    @Test
    fun `adding to a duplicated pair edits the effective row and leaves the duplicate stored`() {
        val disabledOld = modelRule("old", enabled = false, content = "old")
        val effective = modelRule("effective", content = "effective")
        val other = modelRule("b", modelId = "model-b", content = "other")
        val rules = listOf(disabledOld, effective, other)

        val updated = SoulPromptRuleUi.upsertModelRule(
            rules,
            modelRule("new-id", content = "updated"),
        )

        assertEquals(listOf("old", "effective", "b"), updated.map { it.id })
        assertEquals("old text retained", "old", updated.first { it.id == "old" }.content)
        assertEquals("updated", updated.first { it.id == "effective" }.content)
        assertFalse("duplicate stays disabled", updated.first { it.id == "old" }.enabled)
        assertEquals(other, updated.first { it.id == "b" })
    }

    @Test
    fun `adding a new pair appends and preserves every other prompt`() {
        val rules = listOf(modelRule("a"), modelRule("b", modelId = "model-b"))
        val added = SoulPromptRuleUi.upsertModelRule(
            rules,
            modelRule("c", instance = "inst-3", modelId = "model-c", content = "new pair"),
        )
        assertEquals(listOf("a", "b", "c"), added.map { it.id })
        assertSame("existing prompt object reused", rules[0], added[0])
        assertSame("existing prompt object reused", rules[1], added[1])
    }

    @Test
    fun `a targetless prompt is not inserted`() {
        val rules = listOf(modelRule("a"))
        val unchanged = SoulPromptRuleUi.upsertModelRule(
            rules,
            modelRule("b", instance = null, content = "no target"),
        )
        assertSame(rules, unchanged)
    }

    @Test
    fun `stabilize keeps the first enabled prompt per pair and preserves all text`() {
        val first = modelRule("first", content = "one")
        val second = modelRule("second", content = "two")
        val other = modelRule("other", modelId = "model-b")
        val invalid = modelRule("invalid", instance = null)
        val group = groupRule("g")

        val stable = SoulPromptRuleUi.stabilize(listOf(first, second, other, invalid, group))

        assertEquals(listOf("first", "second", "other", "invalid", "g"), stable.map { it.id })
        assertTrue(stable.first { it.id == "first" }.enabled)
        assertFalse(stable.first { it.id == "second" }.enabled)
        assertTrue(stable.first { it.id == "other" }.enabled)
        assertFalse("targetless rule is inert", stable.first { it.id == "invalid" }.enabled)
        assertTrue("group untouched here", stable.first { it.id == "g" }.enabled)
        assertEquals("text is never deleted", "two", stable.first { it.id == "second" }.content)
        assertSame("already stable list is returned as-is", stable, SoulPromptRuleUi.stabilize(stable))
    }

    @Test
    fun `remove deletes exactly one id and keeps groups`() {
        val rules = listOf(groupRule("g"), modelRule("a"), modelRule("b", modelId = "model-b"))
        assertEquals(listOf("g", "b"), SoulPromptRuleUi.removeRule(rules, "a").map { it.id })
        assertSame(rules, SoulPromptRuleUi.removeRule(rules, "missing"))
    }

    @Test
    fun `picker search matches name, provider label, instance and model id`() {
        assertEquals(listOf(modelA.idKey()), SoulPromptRuleUi.filterModels(models, "Model A").map { it.idKey() })
        assertEquals(listOf(modelB.idKey()), SoulPromptRuleUi.filterModels(models, "Provider B").map { it.idKey() })
        assertEquals(listOf(modelB.idKey()), SoulPromptRuleUi.filterModels(models, "inst-2").map { it.idKey() })
        assertEquals(listOf(modelA.idKey()), SoulPromptRuleUi.filterModels(models, "model-a").map { it.idKey() })
        assertSame(models, SoulPromptRuleUi.filterModels(models, "   "))
        assertTrue(SoulPromptRuleUi.filterModels(models, "nothing").isEmpty())
    }

    @Test
    fun `mode switch only writes on a real change after load`() {
        assertTrue(
            SoulPromptRuleUi.shouldSwitchMode(
                SoulPromptMode.GLOBAL,
                SoulPromptMode.CUSTOM,
                saving = false,
                loaded = true,
            ),
        )
        assertFalse(
            "same mode is not a redundant write",
            SoulPromptRuleUi.shouldSwitchMode(
                SoulPromptMode.GLOBAL,
                SoulPromptMode.GLOBAL,
                saving = false,
                loaded = true,
            ),
        )
        assertFalse(
            "one write at a time",
            SoulPromptRuleUi.shouldSwitchMode(
                SoulPromptMode.GLOBAL,
                SoulPromptMode.CUSTOM,
                saving = true,
                loaded = true,
            ),
        )
        assertFalse(
            "not before the persisted mode is read",
            SoulPromptRuleUi.shouldSwitchMode(
                SoulPromptMode.GLOBAL,
                SoulPromptMode.CUSTOM,
                saving = false,
                loaded = false,
            ),
        )
    }

    private fun SoulPromptModelOption.idKey(): String =
        SoulPromptRuleUi.pairKey(providerInstanceId, modelId).orEmpty()
}
