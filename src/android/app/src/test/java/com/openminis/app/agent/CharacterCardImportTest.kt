package com.openminis.app.agent

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-character-cards] Tavern import contract: V1/V2/V3 JSON, PNG text chunks,
 * bounded input, explicit errors, and the original document preserved for a
 * lossless export.
 */
class CharacterCardImportTest {

    private val v2 = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "Nova",
            "description": "A starship navigator.",
            "personality": "Warm, precise.",
            "scenario": "Bridge at night.",
            "first_mes": "Course plotted.",
            "mes_example": "<START>\n{{user}}: Where?\n{{char}}: Home.",
            "creator_notes": "Made for the V2 test.",
            "system_prompt": "Stay in character.",
            "post_history_instructions": "Keep replies short.",
            "tags": ["sci-fi", "calm"]
          }
        }
    """.trimIndent()

    @Test fun v2EnvelopeMapsEveryField() {
        val card = CharacterCardImport.import(v2.toByteArray())
        assertEquals("Nova", card.name)
        assertEquals("A starship navigator.", card.description)
        assertEquals("Warm, precise.", card.personality)
        assertEquals("Bridge at night.", card.scenario)
        assertEquals("Course plotted.", card.firstMessage)
        assertTrue(card.messageExamples.contains("Home."))
        assertEquals("Stay in character.", card.systemPrompt)
        assertEquals("Keep replies short.", card.postHistoryInstructions)
        assertEquals(listOf("sci-fi", "calm"), card.tags)
        assertEquals(CharacterCardScope.GLOBAL, card.scope)
        assertTrue(card.enabled)
        assertEquals(v2, card.sourceJson)
    }

    @Test fun v3ExtensionsSurviveImportAndExport() {
        val v3 = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "Iris",
                "personality": "Dry.",
                "group_only_greetings": ["Hello team."],
                "assets": [{"type": "icon", "uri": "ccdefault:", "name": "main", "ext": "png"}],
                "extensions": {
                  "third_party": {"keep": "me"},
                  "openminis": {"scope": "MODEL", "providerInstanceId": "p1", "modelId": "m1"}
                }
              }
            }
        """.trimIndent()
        val card = CharacterCardImport.import(v3.toByteArray())
        assertEquals(CharacterCardScope.MODEL, card.scope)
        assertEquals("p1", card.providerInstanceId)
        assertEquals("m1", card.modelId)

        // Unknown V3 keys are still in sourceJson and are merged back on export.
        val exported = CharacterCardStore.exportCard(card)
        assertTrue(exported.contains("group_only_greetings"))
        assertTrue(exported.contains("third_party"))
        assertTrue(exported.contains("\"keep\": \"me\""))

        // Export -> import is stable for the state this app owns.
        val again = CharacterCardImport.import(exported.toByteArray())
        assertEquals(card.id, again.id)
        assertEquals(CharacterCardScope.MODEL, again.scope)
        assertEquals("m1", again.modelId)
        assertEquals("Iris", again.name)
    }

    @Test fun v1FlatDocumentImports() {
        val v1 = """
            {"name":"Old","description":"d","personality":"p","scenario":"s",
             "first_mes":"hi","mes_example":"ex","creatorcomment":"note"}
        """.trimIndent()
        val card = CharacterCardImport.import(v1.toByteArray())
        assertEquals("Old", card.name)
        assertEquals("p", card.personality)
        assertEquals("ex", card.messageExamples)
        assertEquals("note", card.creatorNotes)
    }

    @Test fun pngTexChunkCarriesTheCardAndBecomesTheAvatar() {
        val bytes = png("tEXt" to textChunk("chara", base64(v2)))
        val card = CharacterCardImport.import(bytes)
        assertEquals("Nova", card.name)
        assertTrue(card.icon.startsWith(SoulIcon.DATA_URI_PREFIX))
        // The avatar is bytes, never prompt text.
        assertTrue(card.icon.length > SoulIcon.DATA_URI_PREFIX.length)
    }

    @Test fun ccv3WinsOverChara() {
        val v3 = """{"spec":"chara_card_v3","data":{"name":"Newer","personality":"v3 body"}}"""
        val bytes = png(
            "tEXt" to textChunk("chara", base64(v2)),
            "tEXt" to textChunk("ccv3", base64(v3)),
        )
        val card = CharacterCardImport.import(bytes)
        assertEquals("Newer", card.name)
        assertEquals("v3 body", card.personality)
    }

    @Test fun compressedAndInternationalChunksAreRead() {
        // A PNG text chunk always carries the JSON base64-encoded, compressed
        // or not — same payload as the tEXt case, only the transport differs.
        val z = png("zTXt" to ztxtChunk("chara", base64(v2)))
        assertEquals("Nova", CharacterCardImport.import(z).name)

        val i = png("iTXt" to itxtChunk("chara", base64(v2), compressed = false))
        assertEquals("Nova", CharacterCardImport.import(i).name)

        val ic = png(
            "iTXt" to itxtChunk(
                "ccv3",
                base64("""{"spec":"chara_card_v3","data":{"name":"Zip"}}"""),
                compressed = true,
            ),
        )
        assertEquals("Zip", CharacterCardImport.import(ic).name)
    }

    @Test fun badInputFailsLoudlyAndSpecifically() {
        assertThrows(CharacterCardImportException::class.java) {
            CharacterCardImport.import(ByteArray(0))
        }
        assertThrows(CharacterCardImportException::class.java) {
            CharacterCardImport.import("not json".toByteArray())
        }
        assertThrows(CharacterCardImportException::class.java) {
            CharacterCardImport.import("""{"description":"no name"}""".toByteArray())
        }
        assertThrows(CharacterCardImportException::class.java) {
            CharacterCardImport.import(png("tEXt" to textChunk("Comment", "hello")))
        }
        assertThrows(CharacterCardImportException::class.java) {
            CharacterCardImport.import(png("tEXt" to textChunk("chara", "%%% not base64 %%%")))
        }
        assertThrows(CharacterCardImportException::class.java) {
            CharacterCardImport.import(png("zTXt" to ztxtChunk("chara", "not-json")).let {
                // Valid deflate of a non-JSON payload must still be rejected by
                // the JSON layer, not silently accepted.
                it
            })
        }
    }

    @Test fun aDocumentThatIsNotACardIsNotExecutedOrGuessed() {
        val html = "<html><script>alert(1)</script></html>"
        val thrown = assertThrows(CharacterCardImportException::class.java) {
            CharacterCardImport.import(html.toByteArray())
        }
        assertFalse(thrown.message.orEmpty().contains("alert(1)"))
    }

    // -- PNG helpers ---------------------------------------------------

    private fun png(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(out, "IHDR", ByteArray(13))
        chunks.forEach { writeChunk(out, it.first, it.second) }
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeInt(out, data.size)
        out.write(type.toByteArray(StandardCharsets.US_ASCII))
        out.write(data)
        writeInt(out, 0)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun textChunk(keyword: String, value: String): ByteArray =
        (keyword + "\u0000" + value).toByteArray(StandardCharsets.ISO_8859_1)

    private fun ztxtChunk(keyword: String, value: String): ByteArray {
        val raw = value.toByteArray(StandardCharsets.UTF_8)
        val deflated = ByteArrayOutputStream().also { bos ->
            DeflaterOutputStream(bos).use { it.write(raw) }
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write((keyword + "\u0000").toByteArray(StandardCharsets.ISO_8859_1))
        out.write(0)
        out.write(deflated)
        return out.toByteArray()
    }

    private fun itxtChunk(keyword: String, value: String, compressed: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((keyword + "\u0000").toByteArray(StandardCharsets.ISO_8859_1))
        out.write(if (compressed) 1 else 0)
        out.write(0)
        out.write("\u0000".toByteArray()) // empty language tag
        out.write("\u0000".toByteArray()) // empty translated keyword
        if (compressed) {
            DeflaterOutputStream(out).use { it.write(value.toByteArray(StandardCharsets.UTF_8)) }
        } else {
            out.write(value.toByteArray(StandardCharsets.UTF_8))
        }
        return out.toByteArray()
    }

    private fun base64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(StandardCharsets.UTF_8))
}
