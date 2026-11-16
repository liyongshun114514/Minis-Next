package com.openminis.app.groups

import com.openminis.app.backup.BackupFormat
import com.openminis.app.backup.BackupImporter
import com.openminis.app.backup.BackupProviderMerge
import com.openminis.app.backup.BackupProviderWire
import com.openminis.app.data.db.ProviderDatabase
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * [T-model-group-avatar] Persistence end to end: the model field, Room entity
 * mapping, the v5→v6 migration that adds the column, the JSON mirror, and a
 * backup round trip.
 *
 * The point of every case is the same: a manual icon must survive an app
 * relaunch, a downgrade/re-upgrade and a restore on another device, while a
 * group that never chose an icon must still read as "automatic".
 */
class ModelGroupAvatarPersistenceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun group(avatar: String?) = ModelGroup(
        id = "g1",
        name = "Claude 主力",
        avatar = avatar,
    )

    @Test
    fun `Room keeps the avatar both ways`() {
        val config = ProviderConfig(modelGroups = mutableListOf(group("builtin:claude")))
        val snapshot = config.toSnapshot(json)
        assertEquals("builtin:claude", snapshot.groups.single().avatar)
        assertEquals(
            "builtin:claude",
            snapshot.toProviderConfig(json).modelGroups.single().avatar,
        )
    }

    @Test
    fun `null avatar stays null through Room and is omitted from the mirror`() {
        val config = ProviderConfig(modelGroups = mutableListOf(group(null)))
        val snapshot = config.toSnapshot(json)
        assertNull(snapshot.groups.single().avatar)
        assertNull(snapshot.toProviderConfig(json).modelGroups.single().avatar)
        val encoded = json.encodeToString(ProviderConfig.serializer(), config)
        assertFalse(
            "an automatic group must not write an avatar key older builds have to know",
            encoded.contains("\"avatar\""),
        )
    }

    @Test
    fun `the JSON mirror carries a manual value`() {
        val config = ProviderConfig(modelGroups = mutableListOf(group("builtin:openai")))
        val encoded = json.encodeToString(ProviderConfig.serializer(), config)
        assertTrue(encoded.contains("\"avatar\":\"builtin:openai\""))
        val decoded = json.decodeFromString(ProviderConfig.serializer(), encoded)
        assertEquals("builtin:openai", decoded.modelGroups.single().avatar)
    }

    @Test
    fun `a group written before the field existed decodes as automatic`() {
        val legacy = """
            {"modelGroups":[{"id":"g1","name":"Claude","memberEntryIds":[],
            "strategy":"fallback","fallbackStrategy":"default"}]}
        """.trimIndent()
        val decoded = BackupFormat.json.decodeFromString(ProviderConfig.serializer(), legacy)
        assertNull(decoded.modelGroups.single().avatar)
    }

    @Test
    fun `backup export and import carry the manual icon`() {
        val config = ProviderConfig(modelGroups = mutableListOf(group("builtin:claude")))
        val encoded = BackupProviderWire.encode(config)
        assertTrue(encoded.contains("builtin:claude"))
        val restored = BackupImporter.parseProviderConfigLeniently(encoded).config
        assertEquals("builtin:claude", restored.modelGroups.single().avatar)
    }

    @Test
    fun `an automatic group writes no avatar into a backup`() {
        val encoded = BackupProviderWire.encode(
            ProviderConfig(modelGroups = mutableListOf(group(null))),
        )
        assertFalse(encoded.contains("\"avatar\""))
    }

    @Test
    fun `a same-name restore merge takes the backup's manual icon`() {
        val local = ProviderConfig(
            modelGroups = mutableListOf(ModelGroup(id = "local", name = "Claude 主力")),
        )
        val remote = ProviderConfig(
            modelGroups = mutableListOf(
                ModelGroup(id = "remote", name = "Claude 主力", avatar = "builtin:claude"),
            ),
        )
        val merged = BackupProviderMerge.merge(local, remote).config
        assertEquals("builtin:claude", merged.modelGroups.single().avatar)
        assertEquals("local", merged.modelGroups.single().id)
    }

    @Test
    fun `v5 to v6 ALTER preserves every existing group row`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """
                    CREATE TABLE provider_model_groups (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        strategy TEXT NOT NULL,
                        fallback_strategy TEXT NOT NULL,
                        default_thinking_level TEXT,
                        context_limit_tokens INTEGER,
                        last_context_limit_tokens INTEGER,
                        member_entry_ids_json TEXT NOT NULL,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                s.execute(
                    "INSERT INTO provider_model_groups (id,name,strategy,fallback_strategy," +
                        "default_thinking_level,context_limit_tokens,last_context_limit_tokens," +
                        "member_entry_ids_json,sort_order) VALUES " +
                        "('g1','Kept','fallback','always','high',128000,64000,'[\"p/m\"]',7)",
                )
                s.execute(ProviderDatabase.MIGRATION_5_6_SQL)
                s.executeQuery(
                    "SELECT name, strategy, fallback_strategy, default_thinking_level, " +
                        "context_limit_tokens, last_context_limit_tokens, member_entry_ids_json, " +
                        "sort_order, avatar FROM provider_model_groups WHERE id='g1'",
                ).use { r ->
                    assertTrue(r.next())
                    assertEquals("Kept", r.getString(1))
                    assertEquals("fallback", r.getString(2))
                    assertEquals("always", r.getString(3))
                    assertEquals("high", r.getString(4))
                    assertEquals(128000, r.getInt(5))
                    assertEquals(64000, r.getInt(6))
                    assertEquals("[\"p/m\"]", r.getString(7))
                    assertEquals(7, r.getInt(8))
                    assertNull("the new column backfills to automatic", r.getString(9))
                    assertTrue(!r.next())
                }
            }
        }
    }

    @Test
    fun `the migration is an additive nullable column`() {
        assertEquals(
            "ALTER TABLE provider_model_groups ADD COLUMN avatar TEXT",
            ProviderDatabase.MIGRATION_5_6_SQL,
        )
    }
}