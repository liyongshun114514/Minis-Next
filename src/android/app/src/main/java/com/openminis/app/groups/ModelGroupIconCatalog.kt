package com.openminis.app.groups

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.text.Normalizer
import java.util.Locale

/**
 * [T-model-group-avatar] One bundled brand icon.
 *
 * The catalog lives at `assets/model-icons/catalog.json` as a JSON ARRAY of
 * these objects:
 *
 * ```json
 * [{"id":"openai","name":"OpenAI","aliases":["gpt","chatgpt"],"file":"openai.png"}]
 * ```
 *
 * `file` is relative to the `model-icons/` asset directory. Defaults make a
 * hand-edited entry missing `name`/`aliases` still usable.
 *
 * [mono] is `true` when the bundled PNG is a single-colour (near-black) mark:
 * upstream's `Mono` rendering, or a colour mark whose shipped rendering is a
 * solid dark glyph. The renderer draws every icon on a TRANSPARENT tile, so a
 * dark mark would disappear on a dark theme; mono entries are therefore tinted
 * with the theme's content colour instead of getting a backdrop. An entry
 * without the key is colour and is drawn untinted.
 */
@Serializable
data class ModelIconCatalogEntry(
    val id: String = "",
    val name: String = "",
    val aliases: List<String> = emptyList(),
    val file: String = "",
    val mono: Boolean = false,
)

/**
 * [T-model-group-avatar] The parsed, SANITIZED model-icon catalog.
 *
 * Parsing is deliberately per-entry tolerant: one malformed element (or one
 * unsafe `file`) is skipped, never the whole catalog — a bad asset update must
 * degrade to the generic fallback icon, not blank every group avatar at once.
 *
 * [entry] resolves the `builtin:<id>` form. [autoMatch] is the name → brand
 * matcher described on [ModelGroupAvatarResolver].
 *
 * Pure Kotlin (no Android types) so the matching rules are unit-testable.
 */
class ModelIconCatalog internal constructor(
    val entries: List<ModelIconCatalogEntry>,
) {
    private val byId: Map<String, ModelIconCatalogEntry> =
        entries.associateBy { it.id.lowercase(Locale.ROOT) }

    /** Every normalized search term, in catalog order. Built once, not per row. */
    internal val searchTerms: List<SearchTerm> = buildList {
        entries.forEachIndexed { order, entry ->
            val seen = HashSet<String>()
            // The file stem is a useful brand spelling (e.g. "openai.png") and
            // costs nothing to consider; it is not a site-specific rule.
            val fileStem = entry.file.substringBeforeLast('.').substringAfterLast('/')
            for (raw in listOf(entry.id, entry.name) + entry.aliases + fileStem) {
                val tokens = ModelGroupName.tokens(raw)
                if (tokens.isEmpty()) continue
                if (!seen.add(tokens.joinToString(" "))) continue
                add(SearchTerm(entry, tokens, order))
            }
        }
    }

    /** The catalog entry whose id equals [id] (case-insensitive), or null. */
    fun entry(id: String): ModelIconCatalogEntry? =
        byId[id.trim().lowercase(Locale.ROOT)]

    companion object {
        val EMPTY = ModelIconCatalog(emptyList())

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        /**
         * Parse a catalog document. A missing/malformed root yields [EMPTY];
         * an individual bad element is skipped. Unsafe or blank `file` values
         * are dropped because the renderer turns them into an asset URI.
         */
        fun parse(text: String): ModelIconCatalog {
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray
                ?: return EMPTY
            val out = ArrayList<ModelIconCatalogEntry>(root.size)
            val ids = HashSet<String>()
            for (element in root) {
                val raw = runCatching {
                    json.decodeFromJsonElement<ModelIconCatalogEntry>(element)
                }.getOrNull() ?: continue
                val id = ModelGroupAvatarValue.normalizedId(raw.id) ?: continue
                val file = ModelGroupAvatarValue.sanitizeAssetFile(raw.file) ?: continue
                if (!ids.add(id)) continue // first entry wins, deterministically
                out += ModelIconCatalogEntry(
                    id = id,
                    name = raw.name.trim().ifEmpty { id },
                    aliases = raw.aliases
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct(),
                    file = file,
                    mono = raw.mono,
                )
            }
            return if (out.isEmpty()) EMPTY else ModelIconCatalog(out)
        }
    }
}

/** Internal pre-computed matcher row. */
internal data class SearchTerm(
    val entry: ModelIconCatalogEntry,
    val tokens: List<String>,
    val order: Int,
)

/**
 * [T-model-group-avatar] Name normalization shared by the matcher.
 *
 * Case-folded, diacritics stripped, every non-letter/digit run collapsed to a
 * single space, then split into tokens. Tokens (rather than raw substrings)
 * are what keeps "main" from matching an "ai" alias while "GPT-4o 主力" still
 * matches "gpt".
 */
internal object ModelGroupName {
    fun tokens(text: String): List<String> {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (isCombiningMark(ch)) continue
            sb.append(if (ch.isLetterOrDigit()) ch.lowercaseChar() else ' ')
        }
        return sb.toString().split(' ').filter { it.isNotEmpty() }
    }

    private fun isCombiningMark(ch: Char): Boolean {
        val type = Character.getType(ch)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}

/**
 * [T-model-group-avatar] Automatic brand matching for a group name.
 *
 * Two deterministic stages, no per-vendor special cases:
 *
 * 1. **Exact** — the name's normalized tokens equal a catalog term's tokens
 *    ("OpenAI", "open ai", "openai!" all match `openai`).
 * 2. **Contained** — a catalog term appears as a contiguous whole-token run
 *    inside the name ("GPT-4o 主力" → `gpt`). A term shorter than 3 ASCII
 *    characters (or 2 non-ASCII characters) is never used for containment,
 *    so a 2-letter fragment cannot fire on an unrelated word; exact matching
 *    is still allowed.
 *
 * The longest matched term wins (most specific brand spelling), then the
 * earliest occurrence, then catalog order. Ties are stable across launches.
 */
internal object ModelGroupNameMatcher {
    data class Match(val entry: ModelIconCatalogEntry, val term: String)

    fun match(name: String, catalog: ModelIconCatalog): Match? {
        val nameTokens = ModelGroupName.tokens(name)
        if (nameTokens.isEmpty()) return null
        var best: Runner? = null
        for (term in catalog.searchTerms) {
            val candidate = score(nameTokens, term) ?: continue
            if (best == null || candidate.outranks(best)) best = candidate
        }
        return best?.let { Match(it.entry, it.term) }
    }

    private data class Runner(
        val entry: ModelIconCatalogEntry,
        val term: String,
        val exact: Boolean,
        val weight: Int,
        val position: Int,
        val order: Int,
    ) {
        /** Exact beats contained; then the longest term; then catalog order. */
        fun outranks(other: Runner): Boolean = when {
            exact != other.exact -> exact
            weight != other.weight -> weight > other.weight
            order != other.order -> order < other.order
            else -> position < other.position
        }
    }

    private fun score(nameTokens: List<String>, term: SearchTerm): Runner? {
        val termTokens = term.tokens
        val termLength = termTokens.sumOf { it.length }
        val compactName = nameTokens.joinToString("")
        val compactTerm = termTokens.joinToString("")
        // A brand written with separators counts as an exact spelling:
        // "Open AI" / "chat-gpt" normalize to the same compact token as the
        // catalog's "openai" / "chatgpt". Compact EQUALITY only — never a
        // compact substring — so "metadata" cannot match "meta".
        if (nameTokens == termTokens || compactName == compactTerm) {
            return Runner(
                entry = term.entry,
                term = compactTerm,
                exact = true,
                weight = termLength,
                position = 0,
                order = term.order,
            )
        }
        val minimum = if (termTokens.any { token -> token.any { it.code > 127 } }) 2 else 3
        if (termLength < minimum) return null
        val position = indexOfSlice(nameTokens, termTokens)
        if (position >= 0) {
            return Runner(
                entry = term.entry,
                term = termTokens.joinToString(" "),
                exact = false,
                weight = termLength,
                position = position,
                order = term.order,
            )
        }
        // CJK group names have no spaces ("豆包主力"), so token containment
        // alone would never match a Chinese alias. For non-ASCII terms only,
        // fall back to a substring search over the compact name — ASCII terms
        // keep their whole-word rule so "main" still cannot match "ai".
        if (termTokens.any { token -> token.any { it.code > 127 } }) {
            val compactName = nameTokens.joinToString("")
            val compactTerm = termTokens.joinToString("")
            val index = compactName.indexOf(compactTerm)
            if (index >= 0) {
                return Runner(
                    entry = term.entry,
                    term = compactTerm,
                    exact = false,
                    weight = termLength,
                    position = index,
                    order = term.order,
                )
            }
        }
        return null
    }

    private fun indexOfSlice(haystack: List<String>, needle: List<String>): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        for (start in 0..(haystack.size - needle.size)) {
            var all = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    all = false
                    break
                }
            }
            if (all) return start
        }
        return -1
    }
}