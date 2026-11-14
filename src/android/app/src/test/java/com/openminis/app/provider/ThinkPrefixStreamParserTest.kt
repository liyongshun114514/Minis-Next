package com.openminis.app.provider

import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.openai.ThinkPrefixStreamParser
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-think-prefix-stream] Port of iOS 22ca4285's algorithm tests.
 *
 * Each case drives the parser the way the SSE loop does — one or more `feed()`
 * calls followed by `finishTurn()` — and asserts the concatenated visible/
 * thinking output.
 */
class ThinkPrefixStreamParserTest {

    /** Run [chunks] through a fresh parser and return (visible, thinking). */
    private fun run(vararg chunks: String): Pair<String, String> {
        val p = ThinkPrefixStreamParser()
        val vis = StringBuilder()
        val think = StringBuilder()
        for (c in chunks) {
            val o = p.feed(c)
            vis.append(o.visible); think.append(o.thinking)
        }
        val fin = p.finishTurn()
        vis.append(fin.visible); think.append(fin.thinking)
        return vis.toString() to think.toString()
    }

    // ── Core MiniMax M3 shape ────────────────────────────────────────────────

    @Test
    fun `think prefix is split and the trailing newlines are dropped`() {
        val (vis, think) = run("<think>reasoning here</think>\n\nActual body text.")
        assertEquals("Actual body text.", vis)
        assertEquals("reasoning here", think)
    }

    @Test
    fun `think-only turn yields a completely empty body`() {
        // The tool-turn case: old code could leave "\n\n", which renders as a
        // blank band because empty-block guards test isEmpty().
        val (vis, think) = run("<think>just planning</think>\n\n")
        assertEquals("", vis)
        assertTrue("body must be empty, not whitespace", vis.isEmpty())
        assertEquals("just planning", think)
    }

    @Test
    fun `leading whitespace before the think tag is tolerated and dropped`() {
        val (vis, think) = run("\n  <think>r</think>\n\nbody")
        assertEquals("body", vis)
        assertEquals("r", think)
    }

    // ── The mid-text regression (worst old defect) ───────────────────────────

    @Test
    fun `mid-text think tag stays verbatim in the body`() {
        // Old behaviour: visible "Use the ", thinking " tag to mark re" — prose
        // silently swallowed into the thinking bubble.
        val input = "Use the <think> tag to mark reasoning."
        val (vis, think) = run(input)
        assertEquals(input, vis)
        assertEquals("", think)
    }

    @Test
    fun `a full think block mid-text is not treated as a prefix`() {
        val input = "Intro text <think>not a prefix</think> tail"
        val (vis, think) = run(input)
        assertEquals(input, vis)
        assertEquals("", think)
    }

    // ── Chunk-splitting (where stream parsers usually break) ─────────────────

    @Test
    fun `open tag split across chunks still enters thinking`() {
        val (vis, think) = run("<thi", "nk>abc</think>\n\nbody")
        assertEquals("body", vis)
        assertEquals("abc", think)
    }

    @Test
    fun `close tag split across chunks still exits thinking`() {
        val (vis, think) = run("<think>abc</thi", "nk>\n\nbody")
        assertEquals("body", vis)
        assertEquals("abc", think)
    }

    @Test
    fun `thinking streams incrementally rather than only at the end`() {
        // Defect 1 on iOS was "no live thinking bubble". Android already streamed
        // deltas; this pins that the parser keeps emitting mid-thinking so the
        // behaviour cannot regress into end-of-turn-only.
        val p = ThinkPrefixStreamParser()
        val first = p.feed("<think>partial reasoning")
        assertTrue("thinking must stream before </think> arrives", first.thinking.isNotEmpty())
        assertEquals("", first.visible)
    }

    @Test
    fun `body whitespace is preserved when interior`() {
        val (vis, _) = run("<think>r</think>\n\nline one\n\nline two")
        assertEquals("line one\n\nline two", vis)
    }

    // ── Plain models must be unaffected ──────────────────────────────────────

    @Test
    fun `plain text without any think tag passes through verbatim`() {
        val input = "Hello, this is a normal reply."
        val (vis, think) = run(input)
        assertEquals(input, vis)
        assertEquals("", think)
    }

    @Test
    fun `plain text arriving in many chunks is reassembled exactly`() {
        val (vis, think) = run("Hel", "lo ", "world", "!")
        assertEquals("Hello world!", vis)
        assertEquals("", think)
    }

    @Test
    fun `text with leading whitespace and no think tag keeps that whitespace`() {
        // Whitespace is only dropped when it precedes a <think>; otherwise it is
        // genuine body content.
        val (vis, _) = run("  indented start")
        assertEquals("  indented start", vis)
    }

    // ── Robustness ───────────────────────────────────────────────────────────

    @Test
    fun `unterminated think block is reported as thinking not body`() {
        val (vis, think) = run("<think>reasoning that never closes")
        assertEquals("", vis)
        assertEquals("reasoning that never closes", think)
    }

    @Test
    fun `finishTurn is idempotent across the finish_reason and DONE double flush`() {
        val p = ThinkPrefixStreamParser()
        p.feed("<think>r</think>\n\nbody")
        val a = p.finishTurn()
        val b = p.finishTurn()
        assertEquals("", b.visible)
        assertEquals("", b.thinking)
        // (a may legitimately be empty too — everything already streamed.)
        assertEquals("", a.thinking)
    }

    @Test
    fun `ordinary text is released by feed itself, leaving nothing to resolve`() {
        // "Some text" can never become "<think>", so feed() commits straight to
        // BODY and returns it immediately — resolveAtToolBoundary has nothing
        // left to do. (Asserting the opposite would be wrong: it would imply
        // text is needlessly withheld until a tool boundary.)
        val p = ThinkPrefixStreamParser()
        assertEquals("Some text", p.feed("Some text").visible)
        assertEquals("", p.resolveAtToolBoundary().visible)
    }

    @Test
    fun `resolveAtToolBoundary releases whitespace held in the undecided state`() {
        // Pure whitespace is genuinely withheld: it could still be the padding
        // before a <think>. If a tool boundary arrives first, it must be
        // released so the pre-tool snapshot isn't missing it.
        val p = ThinkPrefixStreamParser()
        assertEquals("", p.feed("  ").visible)
        assertEquals("  ", p.resolveAtToolBoundary().visible)
    }

    @Test
    fun `resolveAtToolBoundary does not flush a possible partial open tag`() {
        val p = ThinkPrefixStreamParser()
        p.feed("<thi")
        val out = p.resolveAtToolBoundary()
        assertEquals("", out.visible)
        // …and the tag still resolves once the rest arrives.
        val o2 = p.feed("nk>abc</think>\n\nbody")
        assertEquals("abc", o2.thinking)
    }

    // ── [T-android-think-prefix-orphan-close] Explicit CLOSING marker at the
    // ── turn start: the reasoning itself arrived through the provider's
    // ── reasoning field, so only `</think>` reached `content`. It is a tag, not
    // ── prose, and must not be rendered in the reply body.

    /** Same as [run], but pins that the shipped adapter agrees with it. */
    private fun runBoth(vararg chunks: String): Pair<String, String> {
        val parser = run(*chunks)
        val shipped = runShipped(*chunks)
        assertEquals("parser and shipped adapter must agree", parser, shipped)
        return parser
    }

    /**
     * Drives [text] through the shipped adapter (`splitThinkingPrefix()`, the
     * path every provider stream takes) and returns (visible, thinking).
     */
    private fun runShipped(vararg chunks: String): Pair<String, String> = runBlocking {
        val events = chunks.map { LLMStreamChunk.Text(it) } + LLMStreamChunk.Finished("stop")
        val out = events.asFlow().splitThinkingPrefix().toList()
        out.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text } to
            out.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text }
    }

    /**
     * Drives [text] through every 2-way chunking (both whitespace modes) and
     * every 3-way chunking (parser default mode). A streaming split must never
     * change the split result.
     */
    private fun assertEverySplit(text: String, expectVisible: String, expectThinking: String) {
        for (cut in 0..text.length) {
            val pair = runBoth(text.substring(0, cut), text.substring(cut))
            assertEquals("cut=$cut visible", expectVisible, pair.first)
            assertEquals("cut=$cut thinking", expectThinking, pair.second)
        }
        for (a in 0..text.length) for (b in a..text.length) {
            val pair = run(text.substring(0, a), text.substring(a, b), text.substring(b))
            assertEquals("cuts=$a,$b visible", expectVisible, pair.first)
            assertEquals("cuts=$a,$b thinking", expectThinking, pair.second)
        }
    }

    @Test
    fun `orphan closing tag at the turn start is dropped, not rendered`() {
        // Both spellings, both of which relays leave behind in `content` when
        // they route the reasoning into `reasoning_content`.
        assertEverySplit("</think>\n\nAnswer", "Answer", "")
        assertEverySplit("</thinking>\n\nAnswer", "Answer", "")
    }

    @Test
    fun `orphan closing tag is dropped with no separator or with padding`() {
        assertEverySplit("</think>Answer", "Answer", "")
        assertEverySplit("\n  </thinking>\n\nAnswer", "Answer", "")
    }

    @Test
    fun `orphan closing tag only turn leaves a completely empty body`() {
        // A think-only tool turn: no whitespace-only text block may survive.
        assertEverySplit("</think>\n\n", "", "")
        assertEquals("", runBoth("</thinking>").first)
    }

    @Test
    fun `a closing tag after any body text stays literal`() {
        // The rule is strictly positional: only offset 0 (modulo padding) is a
        // marker. Prose and code examples that mention the tag are untouched.
        for (input in listOf(
            "Intro </think> tail",
            " x</think>",
            "Use `</thinking>` to end reasoning.",
            "```xml\n<thinking>example</thinking>\n```",
        )) {
            val (vis, think) = runBoth(input)
            assertEquals(input, vis)
            assertEquals("", think)
        }
    }

    @Test
    fun `a close tag prefix that never completes is ordinary body text`() {
        // Nothing proves "</thi" was a tag at end of turn; dropping it would eat
        // body bytes, so it stays verbatim (same rule as a partial `<thin`).
        val (vis, think) = runBoth("</thi")
        assertEquals("</thi", vis)
        assertEquals("", think)
    }

    @Test
    fun `a possible partial closing marker stays buffered across a tool boundary`() {
        val p = ThinkPrefixStreamParser()
        assertEquals("", p.feed("</thi").visible)
        assertEquals("", p.resolveAtToolBoundary().visible)
        // …and is still dropped once the marker completes.
        val rest = p.feed("nk>\n\nAnswer")
        assertEquals("Answer", rest.visible)
        assertEquals("", rest.thinking)
    }

    // ── [T-android-think-prefix-orphan-close] Prefix padding must include a
    // ── BOM: Char.isWhitespace(U+FEFF) is false, so one prepended BOM used to
    // ── make the turn look like plain body and stream ALL reasoning verbatim.

    @Test
    fun `a leading BOM before the open tag does not disable the split`() {
        assertEverySplit("\uFEFF<thinking>reasoning</thinking>\n\nBody", "Body", "reasoning")
        assertEverySplit("\uFEFF<think>reasoning</think>\n\nBody", "Body", "reasoning")
    }

    @Test
    fun `a leading BOM before an orphan closing tag is padding too`() {
        assertEverySplit("\uFEFF</think>\n\nAnswer", "Answer", "")
    }

    @Test
    fun `a BOM in a plain turn is still body content`() {
        // Padding is only dropped when an explicit tag follows it.
        val (vis, think) = runBoth("\uFEFFplain reply")
        assertEquals("\uFEFFplain reply", vis)
        assertEquals("", think)
    }

    // ── Shipped adapter (`preserveBodyWhitespace = true`), the path every
    // ── provider stream goes through before UI accumulation/persistence.

    @Test
    fun `shipped adapter renders no marker when reasoning arrived natively`() = runBlocking {
        // The reported transition: the provider streams the chain of thought on
        // its reasoning field, then `content` starts with the leftover closing
        // marker. The body must be exactly "Answer" and no tag may reach Text.
        val events = listOf(
            LLMStreamChunk.ThinkingDelta("native plan"),
            LLMStreamChunk.Text("</thi"),
            LLMStreamChunk.Text("nking>\n\nAn"),
            LLMStreamChunk.Text("swer"),
            LLMStreamChunk.Finished("stop"),
        )
        val out = events.asFlow().splitThinkingPrefix().toList()
        assertEquals("Answer", out.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
        assertTrue(out.none { it is LLMStreamChunk.Text && it.text.contains("think") })
        assertEquals("native plan", out.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text })
        assertTrue(out.last() is LLMStreamChunk.Finished)
    }

    @Test
    fun `shipped adapter emits no empty text block for an orphan-marker-only turn`() = runBlocking {
        val out = listOf(
            LLMStreamChunk.Text("</thinking>\n\n"),
            LLMStreamChunk.ToolUseStart("t", "shell_execute"),
            LLMStreamChunk.Finished("tool_calls"),
        ).asFlow().splitThinkingPrefix().toList()
        assertTrue("no visible text may be emitted", out.none { it is LLMStreamChunk.Text })
        assertTrue("no thinking may be emitted", out.none { it is LLMStreamChunk.ThinkingDelta })
        assertTrue(out.any { it is LLMStreamChunk.ToolUseStart })
    }
}
