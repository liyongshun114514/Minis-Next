package com.openminis.app.agent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.InflaterInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * [T-character-cards] Where a character card applies.
 *
 * The three scopes are the ONLY prompt layers, in this order:
 * GLOBAL -> GROUP -> MODEL. A scoped card can only ADD text after the global
 * one (append-only), exactly like the SOUL prompt rules it replaces; it can
 * never replace the global persona or the application preamble.
 */
@Serializable
enum class CharacterCardScope { GLOBAL, GROUP, MODEL }

/**
 * [T-character-cards] One character/persona card.
 *
 * This is the single source of truth for identity and prompt text. The legacy
 * SOUL.md model is kept only as a COMPATIBILITY ADAPTER over this type
 * ([SoulStore]); nothing writes SOUL.md any more.
 *
 * Card fields map 1:1 onto the Tavern V2/V3 vocabulary so an imported card
 * round-trips without a translation table in the UI:
 *   name / description / personality / scenario / first_mes / mes_example /
 *   system_prompt / post_history_instructions / creator_notes / tags.
 *
 * `icon` is the display avatar: an emoji literal, a `data:image/png;base64,…`
 * URI, or empty (the UI then draws its default). `legacyEmoji` is the
 * round-trip holder for the retired SOUL.md `emoji:` line — never used by UI.
 *
 * `sourceJson` keeps the ORIGINAL imported document so [CharacterCardImport]'s
 * export path can merge our fields back into the author's V3 object without
 * dropping extensions we do not model.
 *
 * `legacyRuleDerived` marks a GROUP/MODEL card that was projected from (or into)
 * the legacy [SoulPromptRule] list. It lets the compat writer delete only the
 * cards that legacy view owns, never a card authored in the card UI.
 */
@Serializable
data class CharacterCard(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val icon: String = "",
    val enabled: Boolean = true,
    val scope: CharacterCardScope = CharacterCardScope.GLOBAL,
    val groupId: String? = null,
    val providerInstanceId: String? = null,
    val modelId: String? = null,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val messageExamples: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val style: String = "",
    val lang: String = "auto",
    val legacyEmoji: String = "",
    val sourceJson: String = "",
    /**
     * Added (defaulted) field — the fixed API above is never changed.
     * See the class KDoc: only the legacy rule view reads/writes this.
     */
    val legacyRuleDerived: Boolean = false,
) {
    val isGlobal: Boolean get() = scope == CharacterCardScope.GLOBAL

    /**
     * A scoped card is only usable when it names a concrete target. Blank ids
     * are NOT wildcards: an untargeted scoped card is inert, never global.
     */
    val hasValidTarget: Boolean
        get() = when (scope) {
            CharacterCardScope.GLOBAL -> true
            CharacterCardScope.GROUP -> !groupId.isNullOrBlank()
            CharacterCardScope.MODEL ->
                !providerInstanceId.isNullOrBlank() && !modelId.isNullOrBlank()
        }

    /**
     * [T-character-cards-api] The card text the legacy single-body surface
     * (`soul.body` in minis-config, SOUL.md-compatible readers) maps onto.
     *
     * GLOBAL -> [personality] (the old SOUL body). Scoped -> [systemPrompt]
     * (the old prompt-rule `content`). Everything else on the card is preserved
     * by [withPromptContent]; a body edit therefore updates exactly ONE field
     * of exactly ONE card and can never blank the others.
     */
    fun promptContent(): String =
        if (isGlobal) personality else systemPrompt

    /** Copy with [promptContent] replaced; every other field is untouched. */
    fun withPromptContent(text: String): CharacterCard =
        if (isGlobal) copy(personality = text) else copy(systemPrompt = text)

    /** Legacy SOUL.md metadata view of this card (name / emoji / icon / style / lang). */
    fun toSoulMetadata(): SoulMetadata = SoulMetadata(
        name = name.ifBlank { SoulMetadata.DEFAULT.name },
        emoji = legacyEmoji,
        icon = icon,
        style = style,
        lang = lang.ifBlank { SoulMetadata.DEFAULT.lang },
    )

    companion object {
        /**
         * Deterministic id for content-derived cards (legacy migration). Same
         * bytes in -> same id out, which is what makes a restored backup merge
         * idempotently instead of duplicating personas.
         */
        fun stableId(prefix: String, vararg parts: String?): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(prefix.toByteArray(StandardCharsets.UTF_8))
            for (part in parts) {
                digest.update(0)
                digest.update((part ?: "").toByteArray(StandardCharsets.UTF_8))
            }
            val hash = digest.digest()
            // UUID.nameUUIDFromBytes wants 16 bytes; the first 16 of SHA-256
            // are as good as any and keep the id shape every caller expects.
            return UUID.nameUUIDFromBytes(hash.copyOfRange(0, 16)).toString()
        }
    }
}

/** Thrown for a user-supplied card document that cannot be understood. */
class CharacterCardImportException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * [T-character-cards] On-disk envelope.
 *
 * `version` exists so a later format change can migrate instead of guessing.
 * Unknown keys are ignored (a newer build's extra fields survive a downgrade
 * read), and the whole document is validated before it is used.
 */
@Serializable
data class CharacterCardsDocument(
    val version: Int = CURRENT_VERSION,
    val cards: List<CharacterCard> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/**
 * Pure JSON codec. No Android, no files — the store and the unit tests share it.
 */
object CharacterCardJson {

    const val MAX_DOCUMENT_CHARS: Int = 4 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(cards: List<CharacterCard>): String =
        json.encodeToString(
            CharacterCardsDocument.serializer(),
            CharacterCardsDocument(cards = cards),
        )

    /**
     * Lenient read used by the runtime: any parse failure yields an empty list
     * so a damaged file can never crash a chat. Callers that must distinguish
     * "empty" from "corrupt" use [decodeStrict].
     */
    fun decode(text: String): List<CharacterCard> =
        runCatching { decodeStrict(text) }.getOrDefault(emptyList())

    /** Strict read. Throws [CharacterCardImportException] for unreadable input. */
    fun decodeStrict(text: String): List<CharacterCard> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length > MAX_DOCUMENT_CHARS) {
            throw CharacterCardImportException("Character card document is larger than ${MAX_DOCUMENT_CHARS} characters")
        }
        val cards = try {
            if (trimmed.startsWith("[")) {
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CharacterCard.serializer()), trimmed)
            } else {
                json.decodeFromString(CharacterCardsDocument.serializer(), trimmed).cards
            }
        } catch (t: Throwable) {
            throw CharacterCardImportException("Character card document is not valid JSON", t)
        }
        return normalize(cards)
    }

    /** Fill in missing ids only; never reorder, never drop. */
    fun normalize(cards: List<CharacterCard>): List<CharacterCard> =
        cards.map { if (it.id.isBlank()) it.copy(id = UUID.randomUUID().toString()) else it }
}

/**
 * [T-character-cards] Tavern V1/V2/V3 import + PNG (`chara` / `ccv3`) import.
 *
 * Contract:
 *  - INPUT IS DATA, NEVER CODE. Nothing here evaluates, executes, resolves a
 *    path or fetches a URL from the document; every field is a string copied
 *    into a card.
 *  - Bounded: the input is capped at [MAX_INPUT_BYTES], a decoded PNG chunk at
 *    [MAX_JSON_BYTES], and an avatar data URI at [MAX_ICON_CHARS].
 *  - Failures are explicit: [CharacterCardImportException] with a message that
 *    names what is wrong, never a silent empty card.
 */
object CharacterCardImport {

    /** Whole file cap (PNG cards embed a JSON payload plus artwork). */
    const val MAX_INPUT_BYTES: Int = 12 * 1024 * 1024
    /** Decoded `chara` / `ccv3` payload cap — also the decompression cap. */
    const val MAX_JSON_BYTES: Int = 4 * 1024 * 1024
    /** Inline avatar cap; larger artwork is dropped, never truncated silently. */
    const val MAX_ICON_CHARS: Int = 256 * 1024
    /** Original document kept for round-trip export. */
    const val MAX_SOURCE_JSON_CHARS: Int = 2 * 1024 * 1024

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    /**
     * Import one card from raw bytes: PNG (character card embedded in a text
     * chunk) or a JSON document (V1 flat / V2 / V3 envelope).
     */
    fun import(bytes: ByteArray): CharacterCard {
        if (bytes.isEmpty()) throw CharacterCardImportException("Character card file is empty")
        if (bytes.size > MAX_INPUT_BYTES) {
            throw CharacterCardImportException(
                "Character card file is ${bytes.size} bytes; the limit is $MAX_INPUT_BYTES bytes",
            )
        }
        return if (isPng(bytes)) importPng(bytes) else importText(decodeUtf8(bytes), avatar = null)
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val text = String(bytes, StandardCharsets.UTF_8)
        if (text.indexOf('\u0000') >= 0) {
            throw CharacterCardImportException("Character card file is binary, not JSON or PNG")
        }
        return text
    }

    // -- JSON ----------------------------------------------------------

    fun importText(text: String, avatar: String?): CharacterCard {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw CharacterCardImportException("Character card JSON is empty")
        if (trimmed.length > MAX_JSON_BYTES) {
            throw CharacterCardImportException("Character card JSON exceeds $MAX_JSON_BYTES characters")
        }
        if (!trimmed.startsWith("{")) {
            throw CharacterCardImportException("Character card JSON must be an object")
        }
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrElse {
            throw CharacterCardImportException("Character card JSON is malformed", it)
        }
        val spec = (root["spec"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val data = root["data"] as? JsonObject
        val isEnvelope = data != null && spec.startsWith("chara_card", ignoreCase = true)
        if (data == null && !root.containsKey("name") && !root.containsKey("description")) {
            throw CharacterCardImportException("JSON is not a Tavern character card (no name/description)")
        }
        val fields = if (isEnvelope) data!! else root

        fun str(vararg keys: String): String {
            for (key in keys) {
                val v = fields[key] as? JsonPrimitive ?: continue
                if (v is JsonNull) continue
                val s = v.contentOrNull ?: continue
                if (s.isNotEmpty()) return s
            }
            return ""
        }

        val name = str("name", "char_name").ifBlank {
            (root["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        if (name.isBlank()) throw CharacterCardImportException("Character card has no name")

        val tags = (fields["tags"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull?.takeIf { t -> t.isNotBlank() }
        } ?: emptyList()

        val extensions = (fields["extensions"] as? JsonObject)
        val om = extensions?.get("openminis") as? JsonObject

        val jsonAvatar = str("avatar", "icon").takeIf { it.startsWith("data:") && it.length <= MAX_ICON_CHARS }
        val icon = avatar?.takeIf { it.isNotEmpty() } ?: jsonAvatar.orEmpty()

        val id = om?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val scope = parseScope((om?.get("scope") as? JsonPrimitive)?.contentOrNull)
        val enabled = (om?.get("enabled") as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val style = om?.get("style")?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val lang = om?.get("lang")?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val legacyEmoji = om?.get("legacyEmoji")?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

        val source = if (trimmed.length <= MAX_SOURCE_JSON_CHARS) trimmed else ""

        return CharacterCard(
            id = id.ifBlank { CharacterCard.stableId("tavern", name, str("personality"), str("description")) },
            name = name,
            icon = icon,
            enabled = enabled,
            scope = scope,
            groupId = om?.stringOrNull("groupId"),
            providerInstanceId = om?.stringOrNull("providerInstanceId"),
            modelId = om?.stringOrNull("modelId"),
            description = str("description"),
            personality = str("personality"),
            scenario = str("scenario"),
            firstMessage = str("first_mes", "firstMessage"),
            messageExamples = str("mes_example", "messageExamples", "example_dialogue"),
            systemPrompt = str("system_prompt", "systemPrompt"),
            postHistoryInstructions = str("post_history_instructions", "postHistoryInstructions"),
            creatorNotes = str("creator_notes", "creatorcomment", "creatorNotes"),
            tags = tags,
            style = style,
            lang = lang.ifBlank { "auto" },
            legacyEmoji = legacyEmoji,
            sourceJson = source,
            legacyRuleDerived = false,
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun parseScope(raw: String?): CharacterCardScope = when (raw?.uppercase()) {
        "GROUP" -> CharacterCardScope.GROUP
        "MODEL" -> CharacterCardScope.MODEL
        else -> CharacterCardScope.GLOBAL
    }

    // -- PNG -----------------------------------------------------------

    /**
     * Character card PNG: the JSON lives in a text chunk, base64-encoded.
     * `ccv3` (V3) wins over `chara` (V2/V1) when both are present. tEXt is the
     * common case; zTXt and iTXt (compressed or not) are read because other
     * card editors emit those, and a file that silently imports as an empty
     * card would be worse than a clear error.
     */
    fun importPng(bytes: ByteArray): CharacterCard {
        var p = PNG_SIGNATURE.size
        var v3Payload: String? = null
        var v2Payload: String? = null
        var sawChunk = false
        while (p + 12 <= bytes.size) {
            val length = readInt(bytes, p)
            if (length < 0 || p + 12 + length > bytes.size) {
                throw CharacterCardImportException("PNG is truncated (chunk length $length at offset $p)")
            }
            val type = String(bytes, p + 4, 4, StandardCharsets.US_ASCII)
            val dataStart = p + 8
            val dataEnd = dataStart + length
            when (type) {
                "tEXt" -> {
                    val (keyword, value) = splitKeyword(bytes, dataStart, dataEnd)
                    if (isCardKeyword(keyword)) {
                        sawChunk = true
                        if (keyword.equals("ccv3", ignoreCase = true)) v3Payload = value else v2Payload = value
                    }
                }
                "zTXt" -> {
                    val nul = indexOfNul(bytes, dataStart, dataEnd)
                    if (nul < 0) throw CharacterCardImportException("PNG zTXt chunk has no keyword terminator")
                    val keyword = String(bytes, dataStart, nul - dataStart, StandardCharsets.ISO_8859_1)
                    if (isCardKeyword(keyword)) {
                        sawChunk = true
                        if (nul + 2 > dataEnd) throw CharacterCardImportException("PNG zTXt chunk is truncated")
                        val inflated = inflate(bytes, nul + 2, dataEnd)
                        if (keyword.equals("ccv3", ignoreCase = true)) v3Payload = inflated else v2Payload = inflated
                    }
                }
                "iTXt" -> {
                    val nul = indexOfNul(bytes, dataStart, dataEnd)
                    if (nul < 0) throw CharacterCardImportException("PNG iTXt chunk has no keyword terminator")
                    val keyword = String(bytes, dataStart, nul - dataStart, StandardCharsets.ISO_8859_1)
                    if (isCardKeyword(keyword)) {
                        sawChunk = true
                        if (nul + 3 > dataEnd) throw CharacterCardImportException("PNG iTXt chunk is truncated")
                        val compressed = bytes[nul + 1].toInt() != 0
                        var cursor = nul + 3
                        // language tag, then translated keyword, both NUL-terminated.
                        for (skip in 0 until 2) {
                            val end = indexOfNul(bytes, cursor, dataEnd)
                            if (end < 0) throw CharacterCardImportException("PNG iTXt chunk is malformed")
                            cursor = end + 1
                        }
                        val text = if (compressed) {
                            inflate(bytes, cursor, dataEnd)
                        } else {
                            String(bytes, cursor, dataEnd - cursor, StandardCharsets.UTF_8)
                        }
                        if (keyword.equals("ccv3", ignoreCase = true)) v3Payload = text else v2Payload = text
                    }
                }
                "IEND" -> break
            }
            p = dataEnd + 4
        }
        val payload = v3Payload ?: v2Payload
        if (payload == null) {
            if (!sawChunk) {
                throw CharacterCardImportException(
                    "PNG has no character card data (expected a tEXt/iTXt/zTXt chunk named chara or ccv3)",
                )
            }
            throw CharacterCardImportException("PNG character card chunk is empty")
        }
        val decoded = decodeBase64(payload)
        val text = String(decoded, StandardCharsets.UTF_8).trim()
        if (text.length > MAX_JSON_BYTES) {
            throw CharacterCardImportException("Decoded character card JSON exceeds $MAX_JSON_BYTES characters")
        }
        val avatar = runCatching {
            if (bytes.size <= MAX_ICON_CHARS * 3 / 4) {
                SoulIcon.DATA_URI_PREFIX + Base64.getEncoder().encodeToString(bytes)
            } else {
                ""
            }
        }.getOrDefault("")
        return importText(text, avatar = avatar.ifEmpty { null })
    }

    private fun isCardKeyword(keyword: String): Boolean =
        keyword.equals("chara", ignoreCase = true) || keyword.equals("ccv3", ignoreCase = true)

    private fun splitKeyword(bytes: ByteArray, start: Int, end: Int): Pair<String, String> {
        val nul = indexOfNul(bytes, start, end)
        if (nul < 0) throw CharacterCardImportException("PNG tEXt chunk has no keyword terminator")
        val keyword = String(bytes, start, nul - start, StandardCharsets.ISO_8859_1)
        val value = String(bytes, nul + 1, end - nul - 1, StandardCharsets.ISO_8859_1)
        return keyword to value
    }

    private fun indexOfNul(bytes: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            if (bytes[i].toInt() == 0) return i
            i++
        }
        return -1
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun decodeBase64(payload: String): ByteArray {
        val cleaned = payload.filterNot { it.isWhitespace() }
        val decoded = runCatching { Base64.getMimeDecoder().decode(cleaned) }
            .recoverCatching { Base64.getUrlDecoder().decode(cleaned) }
            .getOrElse {
                throw CharacterCardImportException("PNG character card chunk is not valid base64", it)
            }
        if (decoded.size > MAX_JSON_BYTES) {
            throw CharacterCardImportException("Decoded character card JSON exceeds $MAX_JSON_BYTES bytes")
        }
        return decoded
    }

    /** Bounded inflate: never allocates more than [MAX_JSON_BYTES]. */
    private fun inflate(bytes: ByteArray, start: Int, end: Int): String {
        val raw = bytes.copyOfRange(start, end)
        val out = java.io.ByteArrayOutputStream()
        try {
            InflaterInputStream(raw.inputStream()).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > MAX_JSON_BYTES) {
                        throw CharacterCardImportException("Compressed character card chunk exceeds $MAX_JSON_BYTES bytes")
                    }
                    out.write(buffer, 0, read)
                }
            }
        } catch (e: CharacterCardImportException) {
            throw e
        } catch (t: Throwable) {
            throw CharacterCardImportException("PNG compressed character card chunk is corrupt", t)
        }
        return out.toString("UTF-8")
    }
}

/**
 * [T-character-cards] Export a card as Tavern V3-flavoured JSON.
 *
 * When [CharacterCard.sourceJson] is a V2/V3 document, its object is the base
 * and only the fields this app owns are overwritten — author extensions,
 * assets and V3-only keys therefore survive the round trip. Otherwise a fresh
 * V2 envelope is produced. OpenMinis-only state (scope/targets/style/lang) is
 * written under `data.extensions.openminis`, which is where [CharacterCardImport]
 * reads it back from.
 */
object CharacterCardExport {

    private val json = Json { prettyPrint = true; encodeDefaults = false }

    fun encode(card: CharacterCard): String {
        val source = runCatching {
            card.sourceJson.takeIf { it.isNotBlank() }?.let { json.parseToJsonElement(it) as? JsonObject }
        }.getOrNull()

        val base: JsonObject = source ?: buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            putJsonObject("data") {}
        }
        val data = (base["data"] as? JsonObject) ?: buildJsonObject {}
        val extensions = (data["extensions"] as? JsonObject) ?: buildJsonObject {}

        val openminis = buildJsonObject {
            put("id", card.id)
            put("scope", card.scope.name)
            put("enabled", card.enabled)
            card.groupId?.let { put("groupId", it) }
            card.providerInstanceId?.let { put("providerInstanceId", it) }
            card.modelId?.let { put("modelId", it) }
            put("style", card.style)
            put("lang", card.lang)
            put("legacyEmoji", card.legacyEmoji)
        }

        val mergedData = buildJsonObject {
            data.forEach { (key, value) -> put(key, value) }
            put("name", card.name)
            put("description", card.description)
            put("personality", card.personality)
            put("scenario", card.scenario)
            put("first_mes", card.firstMessage)
            put("mes_example", card.messageExamples)
            put("creator_notes", card.creatorNotes)
            put("system_prompt", card.systemPrompt)
            put("post_history_instructions", card.postHistoryInstructions)
            put("tags", JsonArray(card.tags.map { JsonPrimitive(it) }))
            if (card.icon.startsWith("data:")) put("avatar", card.icon) else if (data["avatar"] == null) put("avatar", "none")
            put("extensions", buildJsonObject {
                extensions.forEach { (key, value) -> put(key, value) }
                put("openminis", openminis)
            })
        }

        val merged = buildJsonObject {
            base.forEach { (key, value) -> if (key != "data") put(key, value) }
            if (base["spec"] == null) put("spec", "chara_card_v2")
            put("data", mergedData)
        }
        return json.encodeToString(JsonElement.serializer(), merged)
    }
}
