package com.openminis.app.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-chat-group-identity] The persisted snapshot codec.
 *
 * These are the contracts the chat UI depends on: a frozen group survives a
 * round trip EXACTLY (including a full-size custom picture), a malformed value
 * reads as "no snapshot" instead of inventing one, and a stamp written by a
 * future build is not misinterpreted by this one.
 */
class ChatGroupIdentityCodecTest {

    private fun group(
        id: String? = "g1",
        name: String? = "Work",
        avatar: String? = "builtin:openai",
    ) = ChatGroupOwnership(
        kind = ChatGroupIdentityKind.GROUP,
        groupId = id,
        groupName = name,
        groupAvatar = avatar,
    )

    @Test
    fun `a grouped stamp round-trips id name and avatar`() {
        val decoded = ChatGroupIdentityCodec.decode(ChatGroupIdentityCodec.encode(group()))
        assertEquals(
            ChatGroupOwnership(
                kind = ChatGroupIdentityKind.GROUP,
                groupId = "g1",
                groupName = "Work",
                groupAvatar = "builtin:openai",
            ),
            decoded,
        )
    }

    @Test
    fun `ungrouped and none stamps stay distinguishable from no snapshot`() {
        val model = ChatGroupIdentityCodec.decode(
            ChatGroupIdentityCodec.encode(ChatGroupOwnership.MODEL),
        )
        assertEquals(ChatGroupIdentityKind.MODEL, model?.kind)
        assertNull(model?.groupId)

        val none = ChatGroupIdentityCodec.decode(
            ChatGroupIdentityCodec.encode(ChatGroupOwnership.NONE),
        )
        assertEquals(ChatGroupIdentityKind.NONE, none?.kind)

        // null raw = a row written before the feature, NOT an ungrouped stamp.
        assertNull(ChatGroupIdentityCodec.decode(null))
        assertNull(ChatGroupIdentityCodec.decode(""))
        assertNull(ChatGroupIdentityCodec.encode(null))
    }

    @Test
    fun `names with json metacharacters survive`() {
        val names = listOf(
            "Work \"quoted\"",
            "back\\slash",
            "line\nbreak\r\ttab",
            "中文分组 · 工作",
            "emoji 🚀 group",
            "{}[]:,\u0001",
        )
        for (name in names) {
            val decoded = ChatGroupIdentityCodec.decode(
                ChatGroupIdentityCodec.encode(group(name = name)),
            )
            assertEquals("name '$name'", name, decoded?.groupName)
        }
    }

    @Test
    fun `a full-size custom picture is persisted whole, never truncated`() {
        // A real PNG-signature data URI larger than any "small value" cutoff:
        // the snapshot keeps it verbatim so an old turn keeps rendering the
        // picture it was sent with.
        val bytes = ByteArray(30_000)
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        signature.copyInto(bytes)
        val dataUri = ModelGroupAvatarValue.DATA_URI_PREFIX +
            java.util.Base64.getEncoder().encodeToString(bytes)
        assertTrue("fixture must be a usable stored avatar", ModelGroupAvatarValue.isUsable(dataUri))
        assertTrue("fixture must exceed the 8 KiB tier", dataUri.length > 8 * 1024)

        val decoded = ChatGroupIdentityCodec.decode(
            ChatGroupIdentityCodec.encode(group(avatar = dataUri)),
        )
        assertEquals(dataUri, decoded?.groupAvatar)
    }

    @Test
    fun `an unusable avatar is dropped but the group name is kept`() {
        for (bad in listOf("javascript:alert(1)", "builtin:../escape", "data:image/png;base64,@@@", "  ")) {
            val decoded = ChatGroupIdentityCodec.decode(
                ChatGroupIdentityCodec.encode(group(avatar = bad)),
            )
            assertNotNull("stamp for '$bad'", decoded)
            assertEquals("Work", decoded?.groupName)
            assertNull("avatar '$bad'", decoded?.groupAvatar)
        }
    }

    @Test
    fun `malformed or hostile json yields no snapshot`() {
        val bad = listOf(
            "{",
            "{}",
            "null",
            "[]",
            "not json",
            // trailing comma: the previous hand-rolled reader accepted this
            """{"v":1,"kind":"group","id":"g1",}""",
            // wrong types
            """{"v":"1","kind":"group"}""",
            """{"v":1,"kind":7}""",
            """{"v":1,"kind":"group","id":{"nested":true}}""",
            // unknown kind
            """{"v":1,"kind":"team"}""",
        )
        for (raw in bad) {
            assertNull("raw='$raw'", ChatGroupIdentityCodec.decode(raw))
        }
    }

    @Test
    fun `unknown keys are ignored and a future version is refused`() {
        val ok = ChatGroupIdentityCodec.decode(
            """{"v":1,"kind":"group","id":"g1","name":"Work","future":true}""",
        )
        assertEquals("Work", ok?.groupName)

        assertNull(ChatGroupIdentityCodec.decode("""{"v":2,"kind":"group","id":"g1"}"""))
        assertNull(ChatGroupIdentityCodec.decode("""{"v":0,"kind":"group","id":"g1"}"""))
    }

    @Test
    fun `a group stamp without an id keeps its name`() {
        val decoded = ChatGroupIdentityCodec.decode(
            """{"v":1,"kind":"group","name":"Deleted group"}""",
        )
        assertEquals(ChatGroupIdentityKind.GROUP, decoded?.kind)
        assertNull(decoded?.groupId)
        assertEquals("Deleted group", decoded?.groupName)
    }
}