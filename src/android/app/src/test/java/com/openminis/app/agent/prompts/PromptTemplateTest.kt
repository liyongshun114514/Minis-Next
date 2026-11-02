package com.openminis.app.agent.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-prompt-overrides] The template rules on their own: what counts as a
 * `{{slotN}}` placeholder, when an edit is an acceptable replacement, and how
 * substitution behaves.
 *
 * These are the pure functions the storage layer is built on, so this file has
 * no Android, no disk and no catalog — it can be reasoned about (and run)
 * anywhere, which is the point: a placeholder bug is a prompt bug that no type
 * checker would catch.
 */
class PromptTemplateTest {

    @Test
    fun `slot placeholders are found in order with their positions`() {
        val template = "A {{slot2}} B {{slot1}}"
        val found = PromptTemplate.occurrences(template)
        assertEquals(listOf("slot2", "slot1"), found.map { it.token })
        assertEquals("A ", template.substring(0, found[0].start))
        assertEquals("{{slot2}}", template.substring(found[0].start, found[0].endExclusive))
    }

    @Test
    fun `a repeated slot counts every occurrence`() {
        assertEquals(mapOf("slot1" to 2), PromptTemplate.slotCounts("{{slot1}}-{{slot1}}"))
        assertEquals(
            mapOf("slot1" to 2, "slot2" to 1),
            PromptTemplate.slotCounts("{{slot1}} {{slot2}} {{slot1}}"),
        )
    }

    @Test
    fun `only slotN names are placeholders`() {
        assertEquals(emptyList<String>(), PromptTemplate.unknownTokens("{{slot1}} {{slot12}}"))
        // Case matters, spacing matters, an empty token is not a slot, and a
        // differently-named token is unknown rather than silently accepted.
        assertEquals(listOf("SLOT1", "slot 1"), PromptTemplate.unknownTokens("{{SLOT1}} {{slot 1}}"))
        assertEquals(listOf("name"), PromptTemplate.unknownTokens("hello {{name}}"))
        assertEquals(listOf(""), PromptTemplate.unknownTokens("{{}}"))
        // Single braces are text, not a placeholder.
        assertEquals(emptyList<String>(), PromptTemplate.unknownTokens("{{slot1}} {x}"))
    }

    @Test
    fun `an unterminated opening brace is plain text`() {
        // Nothing to substitute, so nothing to refuse: rejecting prose like
        // "use {{ carefully" would block edits for a mistake the renderer
        // cannot act on anyway.
        assertEquals(emptyList<PromptTemplate.Occurrence>(), PromptTemplate.occurrences("use {{slot1"))
        assertEquals(mapOf<String, Int>(), PromptTemplate.slotCounts("use {{slot1"))
    }

    @Test
    fun `triple braces still yield the inner slot`() {
        val template = "{{{slot1}}}"
        assertEquals(listOf("slot1"), PromptTemplate.occurrences(template).map { it.token })
        assertEquals("{v}", PromptTemplate.render(template, mapOf("slot1" to "v")))
    }

    @Test
    fun `render substitutes every occurrence`() {
        val out = PromptTemplate.render(
            "You are {{slot1}}. Path: {{slot2}}. Again: {{slot1}}.",
            mapOf("slot1" to "Minis", "slot2" to "/var/minis"),
        )
        assertEquals("You are Minis. Path: /var/minis. Again: Minis.", out)
    }

    @Test
    fun `render accepts the braced spelling of an argument name`() {
        assertEquals("x", PromptTemplate.render("{{slot1}}", mapOf("{{slot1}}" to "x")))
    }

    @Test
    fun `render leaves a slot with no argument standing`() {
        // Losing a dynamic value silently is worse than showing the slot.
        assertEquals("a {{slot2}} b", PromptTemplate.render("a {{slot2}} b", mapOf("slot1" to "unused")))
    }

    @Test
    fun `render never expands a substituted value twice`() {
        // A model, a file name or a tool output that itself contains {{…}} must
        // come out as those exact characters, not be scanned again and either
        // substituted or left half-resolved.
        val out = PromptTemplate.render(
            "{{slot1}} / {{slot2}}",
            mapOf("slot1" to "keep {{slot2}} literal", "slot2" to "second"),
        )
        assertEquals("keep {{slot2}} literal / second", out)
    }

    @Test
    fun `render does not interpret replacement metacharacters`() {
        // The reason substitution is a scan + append rather than Regex.replace:
        // `$` and `\` in a value must stay literal.
        assertEquals("cost: $5 \\ ok", PromptTemplate.render("cost: {{slot1}}", mapOf("slot1" to "\$5 \\ ok")))
    }

    @Test
    fun `render is the identity when there is nothing to do`() {
        assertEquals("plain", PromptTemplate.render("plain", emptyMap()))
        assertEquals("{{slot1}}", PromptTemplate.render("{{slot1}}", emptyMap()))
    }

    @Test
    fun `compatibility is about the slot multiset, not the wording`() {
        val reference = "You are {{slot1}}. Tools: {{slot2}}. Repeat: {{slot1}}."
        // Rewording and reordering are fine.
        assertTrue(PromptTemplate.isCompatible("Repeat {{slot1}}; tools {{slot2}}; you {{slot1}}", reference))
        // Dropping one of two occurrences is not.
        assertFalse(PromptTemplate.isCompatible("You are {{slot1}}. Tools: {{slot2}}.", reference))
        // Duplicating a slot is not.
        assertFalse(PromptTemplate.isCompatible("{{slot1}} {{slot1}} {{slot1}} {{slot2}}", reference))
        // An unknown name is refused regardless of counts.
        assertFalse(PromptTemplate.isCompatible("{{slot1}} {{slot2}} {{slot1}} {{user}}", reference))
        // Neither is dropping every slot.
        assertFalse(PromptTemplate.isCompatible("no slots here", reference))
    }

    @Test
    fun `an empty candidate fits only a slot-free reference`() {
        assertTrue(PromptTemplate.isCompatible("", "no slots here"))
        assertFalse(PromptTemplate.isCompatible("", "{{slot1}}"))
    }

    @Test
    fun `describe renders counts for error messages`() {
        assertEquals("no placeholders", PromptTemplate.describe(emptyMap()))
        assertEquals("{{slot1}}", PromptTemplate.describe(mapOf("{{slot1}}" to 1)))
        // Slots in numeric order, then the caller-filled names.
        assertEquals("{{slot1}}×2, {{slot2}}, {{slot10}}, {name}", PromptTemplate.describe(
            mapOf("{name}" to 1, "{{slot10}}" to 1, "{{slot2}}" to 1, "{{slot1}}" to 2),
        ))
    }

    // MARK: - Single-brace {name}

    @Test
    fun `a single brace identifier is a placeholder the caller fills`() {
        // The identity template renders its slots and then does
        // `.replace("{name}", …)`, so this must be recognised (and counted) but
        // never substituted here.
        assertEquals(mapOf("{name}" to 1), PromptTemplate.signature("You are {name}, an assistant."))
        assertEquals(
            mapOf("{{slot1}}" to 1, "{name}" to 2),
            PromptTemplate.signature("{name} uses {{slot1}}; again {name}"),
        )
        assertEquals("You are {name}.", PromptTemplate.render("You are {name}.", mapOf("name" to "x")))
    }

    @Test
    fun `braces that are not identifiers are ordinary text`() {
        // JSON, set notation and arithmetic in prose must not be mistaken for a
        // placeholder the user is then unable to delete.
        assertEquals(
            emptyMap<String, Int>(),
            PromptTemplate.signature("""{"a": 1} {1} { } {a-b} {}"""),
        )
        assertEquals(emptyList<String>(), PromptTemplate.unknownTokens("""{"a": 1}"""))
    }

    @Test
    fun `compatibility also pins how many names survive`() {
        val reference = "You are {name}. Tools: {{slot1}}."
        assertTrue(PromptTemplate.isCompatible("Tools {{slot1}} — you are {name}.", reference))
        // Dropping the name leaves a literal {name} nowhere to be filled from.
        assertFalse(PromptTemplate.isCompatible("You are an assistant. Tools: {{slot1}}.", reference))
        // Adding one creates a placeholder nothing fills.
        assertFalse(PromptTemplate.isCompatible("You are {name} ({name}). Tools: {{slot1}}.", reference))
    }
}
