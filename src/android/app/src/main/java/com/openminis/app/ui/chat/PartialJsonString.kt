package com.openminis.app.ui.chat

/**
 * Single-pass decoder for the *content* of a JSON string literal that is still
 * streaming in (tool input JSON, partial `toolArgs`, …).
 *
 * [decode] takes the raw, still-escaped source characters of one JSON string
 * value — everything after the opening `"`, already cut at the first unescaped
 * `"` — and returns the text a UI may render right now.
 *
 * Contract:
 *  - All eight standard JSON escapes are handled: `\" \\ \/ \b \f \n \r \t`.
 *  - `\uXXXX` is decoded, including UTF-16 surrogate pairs (`\uD83D\uDE00` → 😀).
 *  - The scan is strictly left-to-right over escapes, so a doubled backslash is
 *    a *literal* backslash: `\\u4e2d` is the six characters `\u4e2d`, never 中.
 *    (Chained `String.replace` calls get this wrong, and also corrupt `\\n`.)
 *  - A trailing fragment that has not fully streamed — a dangling `\`, a short
 *    `\u12` / `\uD83D` at the end of the buffer, or the first half of a
 *    surrogate pair — emits **nothing**. The caller re-decodes the whole prefix
 *    on the next chunk, so the dropped fragment reappears (correctly) once its
 *    remaining characters arrive. That is what keeps a half escape or a lone
 *    surrogate from ever flashing on screen.
 *  - Malformed input never throws and never yields a lone surrogate:
 *      * `\uXXXX` with four characters present but a non-hex digit → U+FFFD
 *        (the four characters are consumed);
 *      * an unknown escape such as `\q` → `q` (backslash dropped, char kept);
 *      * a high surrogate followed by something that is not a low surrogate
 *        escape (`\uD83DX`, `\uD83D\n`, `\uD83D\u0041`) → U+FFFD, and the
 *        following escape is decoded normally;
 *      * a low surrogate without a preceding high surrogate → U+FFFD.
 *  - A literal, unescaped high surrogate at the very end of the buffer is
 *    dropped as well (its low half may be the next chunk).
 *
 * Field extraction (`"key": "…"`, quote finding, keys) is deliberately out of
 * scope — this object only turns already-isolated JSON string content into
 * display text.
 */
internal object PartialJsonString {

    private const val REPLACEMENT = '\uFFFD'

    /** Fewer than four hex digits are available — they may still stream in. */
    private const val PENDING = -1

    /** Four characters are available but at least one is not a hex digit. */
    private const val MALFORMED = -2

    /**
     * Decodes escaped JSON string content. Never throws; incomplete trailing
     * fragments are dropped, malformed ones degrade to U+FFFD or to the
     * literal character.
     */
    fun decode(s: String): String {
        if (s.isEmpty()) return s
        // Fast path: no escapes at all (the common case for plain CJK text).
        // Returns the input unchanged, so re-decoding a growing prefix does not
        // copy it; only a literal half surrogate at the tail has to be dropped.
        if (s.indexOf('\\') < 0) {
            val last = s.length - 1
            return if (last >= 0 && s[last] in '\uD800'..'\uDBFF') s.substring(0, last) else s
        }

        val out = StringBuilder(s.length)
        val n = s.length
        var i = 0
        while (i < n) {
            val c = s[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }

            // Dangling backslash at the end of the buffer: its escape letter has
            // not streamed yet. Emitting it would flash a half-written escape.
            if (i + 1 >= n) break

            when (val e = s[i + 1]) {
                '"' -> { out.append('"'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                '/' -> { out.append('/'); i += 2 }
                'b' -> { out.append('\b'); i += 2 }
                'f' -> { out.append('\u000C'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'u' -> {
                    val hi = hex4(s, i + 2)
                    when {
                        hi == PENDING -> return finish(out)
                        hi == MALFORMED -> { out.append(REPLACEMENT); i += 6 }
                        hi in 0xD800..0xDBFF -> {
                            val lowStart = i + 6
                            if (lowStart >= n) {
                                // High half only, nothing after it yet: the low
                                // half may be the next chunk. Drop the half pair.
                                return finish(out)
                            }
                            if (s[lowStart] != '\\') {
                                out.append(REPLACEMENT)   // lone high surrogate
                                i = lowStart
                            } else if (lowStart + 1 >= n) {
                                return finish(out)        // "\" still streaming
                            } else if (s[lowStart + 1] != 'u') {
                                out.append(REPLACEMENT)   // lone high surrogate
                                i = lowStart
                            } else {
                                when (val lo = hex4(s, lowStart + 2)) {
                                    PENDING -> return finish(out)
                                    MALFORMED -> {
                                        out.append(REPLACEMENT)
                                        i = lowStart + 6
                                    }
                                    in 0xDC00..0xDFFF -> {
                                        out.appendCodePoint(
                                            0x10000 + ((hi - 0xD800) shl 10) + (lo - 0xDC00)
                                        )
                                        i = lowStart + 6
                                    }
                                    else -> {
                                        out.append(REPLACEMENT)  // e.g. another high half
                                        i = lowStart
                                    }
                                }
                            }
                        }
                        hi in 0xDC00..0xDFFF -> { out.append(REPLACEMENT); i += 6 }
                        else -> { out.append(hi.toChar()); i += 6 }
                    }
                }
                else -> {
                    // Unknown escape: keep the character, drop the backslash.
                    out.append(e)
                    i += 2
                }
            }
        }
        return finish(out)
    }

    /**
     * Reads exactly four hex digits starting at [start].
     * Returns [PENDING] when fewer than four characters remain, [MALFORMED]
     * when four are present but one is not a hex digit, else the value.
     * ASCII hex only — `Character.digit` would also accept non-JSON digits.
     */
    private fun hex4(s: String, start: Int): Int {
        if (start + 4 > s.length) return PENDING
        var v = 0
        for (k in start until start + 4) {
            val c = s[k]
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return MALFORMED
            }
            v = (v shl 4) or d
        }
        return v
    }

    /**
     * Drops a literal unpaired high surrogate left at the tail of the buffer
     * (the low half may arrive with the next chunk) and materializes the text.
     */
    private fun finish(out: StringBuilder): String {
        val last = out.length - 1
        if (last >= 0 && out[last] in '\uD800'..'\uDBFF') out.setLength(last)
        return out.toString()
    }
}
