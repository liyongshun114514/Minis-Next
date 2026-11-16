package com.openminis.app.backup

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.groups.ChatGroupIdentityCodec
import com.openminis.app.groups.ChatGroupIdentityKind
import com.openminis.app.groups.ChatGroupIdentityResolver
import com.openminis.app.groups.ChatGroupOwnership
import com.openminis.app.data.model.ModelGroup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-chat-group-identity] A backup round trip must keep the frozen speaker.
 *
 * Otherwise restoring a package would silently re-attribute every old reply to
 * whatever group/model the restored config points at first — the exact
 * relabelling this feature removes. Backups written before the field existed
 * must still import cleanly (null = "no snapshot", every other field intact).
 */
class ChatGroupIdentityBackupTest {

    private fun message(
        id: String,
        groupIdentity: String?,
    ) = MessageEntity(
        id = id,
        sessionId = "s1",
        role = "assistant",
        partsJson = """[{"type":"text","value":"hi"}]""",
        createdAt = 1_700_000_000_000,
        sortOrder = 0,
        modelId = "gpt-5",
        modelDisplayName = "GPT-5",
        providerType = "openAI",
        providerInstanceId = "p1",
        groupIdentity = groupIdentity,
    )

    @Test
    fun `a group snapshot survives export then import unchanged`() {
        val live = ModelGroup(
            id = "g1",
            name = "Work",
            memberEntryIds = mutableListOf(),
        ).also { it.avatar = "builtin:openai" }
        val stamp = ChatGroupIdentityCodec.encode(ChatGroupIdentityResolver.ownership(live))
        val original = message("m1", stamp)

        val record = BackupExporter.messageRecord(original) as JsonObject
        assertEquals(stamp, record["groupIdentity"]?.jsonPrimitive?.content)

        val imported = requireNotNull(BackupRecordMapper.message(record))
        assertEquals(original.groupIdentity, imported.groupIdentity)
        // Identity metadata (model attribution) is untouched by this feature.
        assertEquals(original.modelId, imported.modelId)
        assertEquals(original.modelDisplayName, imported.modelDisplayName)
        assertEquals(original.providerType, imported.providerType)
        assertEquals(original.providerInstanceId, imported.providerInstanceId)

        val identity = ChatGroupIdentityResolver.persisted(
            ChatGroupIdentityCodec.decode(imported.groupIdentity),
            imported.modelId,
            imported.modelDisplayName,
            imported.providerType,
            imported.providerInstanceId,
        )
        assertEquals(ChatGroupIdentityKind.GROUP, identity.kind)
        assertEquals("Work", identity.displayName)
        assertEquals("builtin:openai", identity.groupAvatar)
    }

    @Test
    fun `an explicit ungrouped stamp survives the round trip`() {
        val original = message("m2", ChatGroupIdentityCodec.encode(ChatGroupOwnership.MODEL))
        val record = BackupExporter.messageRecord(original) as JsonObject
        val imported = requireNotNull(BackupRecordMapper.message(record))
        val ownership = ChatGroupIdentityCodec.decode(imported.groupIdentity)
        assertEquals(ChatGroupIdentityKind.MODEL, ownership?.kind)
        assertFalse(ownership!!.isGrouped)
    }

    @Test
    fun `an older package without the field imports with every other field intact`() {
        // A MessageV2 record written by a build that predates this feature.
        val legacy = BackupExporter.messageRecord(message("m3", null)) as JsonObject
        assertNull(legacy["groupIdentity"])

        val imported = requireNotNull(BackupRecordMapper.message(legacy))
        assertNull(imported.groupIdentity)
        assertEquals("s1", imported.sessionId)
        assertEquals("assistant", imported.role)
        assertEquals("""[{"type":"text","value":"hi"}]""", imported.partsJson)
        assertEquals(1_700_000_000_000, imported.createdAt)
        assertEquals("gpt-5", imported.modelId)
        assertEquals("GPT-5", imported.modelDisplayName)
        assertEquals("openAI", imported.providerType)
        assertEquals("p1", imported.providerInstanceId)

        // No snapshot → the row keeps its model identity in the UI.
        val identity = ChatGroupIdentityResolver.persisted(
            ChatGroupIdentityCodec.decode(imported.groupIdentity),
            imported.modelId,
            imported.modelDisplayName,
        )
        assertEquals(ChatGroupIdentityKind.MODEL, identity.kind)
        assertEquals("GPT-5", identity.displayName)
    }

    @Test
    fun `a tampered or unknown-version snapshot degrades to no snapshot`() {
        val record = BackupExporter.messageRecord(message("m4", """{"v":99,"kind":"group"}""")) as JsonObject
        val imported = requireNotNull(BackupRecordMapper.message(record))
        assertEquals("""{"v":99,"kind":"group"}""", imported.groupIdentity)
        assertNull(ChatGroupIdentityCodec.decode(imported.groupIdentity))
    }
}