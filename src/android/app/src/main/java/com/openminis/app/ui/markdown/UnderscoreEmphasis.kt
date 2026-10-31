package com.openminis.app.ui.markdown

/** Underscore emphasis uses delimiter boundaries, not indexOf('_').
 * In particular, the underscores in shell_execute and tool_title are literal.
 * Star emphasis intentionally has different (intra-word) rules.
 */
internal object UnderscoreEmphasis {
    fun runLength(text: String, at: Int): Int {
        var end = at
        while (end < text.length && text[end] == '_') end++
        return end - at
    }

    private fun whitespace(cp: Int?): Boolean =
        cp == null || Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    private fun punctuation(cp: Int?): Boolean {
        if (cp == null) return false
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(), Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt() -> true
            else -> false
        }
    }

    private data class Flanking(val left: Boolean, val right: Boolean, val beforePunct: Boolean, val afterPunct: Boolean)

    private fun flanking(text: String, at: Int, width: Int): Flanking {
        val before = if (at > 0) text.codePointBefore(at) else null
        val after = if (at + width < text.length) text.codePointAt(at + width) else null
        val bw = whitespace(before); val aw = whitespace(after)
        val bp = punctuation(before); val ap = punctuation(after)
        return Flanking(!aw && (!ap || bw || bp), !bw && (!bp || aw || ap), bp, ap)
    }

    fun canOpen(text: String, at: Int, width: Int): Boolean = flanking(text, at, width).let {
        it.left && (!it.right || it.beforePunct)
    }

    private fun canClose(text: String, at: Int, width: Int): Boolean = flanking(text, at, width).let {
        it.right && (!it.left || it.afterPunct)
    }

    /** Return an exact-width closing run; escaped markers and code are literal.
     * This is the renderer's bounded 1/2/3-marker subset, not a full CommonMark
     * delimiter-stack implementation. Never split a larger run to fake a pair.
     */
    fun findClose(text: String, at: Int, width: Int): Int {
        if (width !in 1..3 || !canOpen(text, at, width)) return -1
        var i = at + width
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += if (i + 1 < text.length) 2 else 1
                '`' -> {
                    val close = text.indexOf('`', i + 1)
                    val newline = text.indexOf('\n', i + 1)
                    i = if (close >= 0 && (newline < 0 || close < newline)) close + 1 else i + 1
                }
                '_' -> {
                    val run = runLength(text, i)
                    if (run == width && i > at + width && canClose(text, i, run)) return i
                    i += run
                }
                else -> i++
            }
        }
        return -1
    }
}
