package com.openminis.app.data

import com.openminis.app.backup.BackupFormat
import com.openminis.app.backup.BackupImporter
import com.openminis.app.backup.BackupProviderWire
import com.openminis.app.data.db.ProviderDatabase
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

/**
 * [T-provider-custom-balance-url] Persistence and backup compatibility for the
 * optional balance endpoint: Room entity mapping, DB migration, the JSON
 * mirror/backup shape, and an old document that never had the key.
 */
class CustomBalancePersistenceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val url = "https://relay.example.com/api/balance?token=secret"

    private fun instance(value: String?) = ProviderInstance(
        id = "p1",
        label = "Relay",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = "https://relay.example.com/v1",
        customBalanceURL = value,
    )

    @Test fun `Room snapshot keeps the custom balance URL both ways`() {
        val config = ProviderConfig(instances = mutableListOf(instance(url)))
        val snapshot = config.toSnapshot(json)
        assertEquals(url, snapshot.instances.single().customBalanceURL)
        assertEquals(url, snapshot.toProviderConfig(json).instances.single().customBalanceURL)
    }

    @Test fun `null stays null through Room and is omitted from the mirror`() {
        val config = ProviderConfig(instances = mutableListOf(instance(null)))
        val snapshot = config.toSnapshot(json)
        assertNull(snapshot.instances.single().customBalanceURL)
        assertNull(snapshot.toProviderConfig(json).instances.single().customBalanceURL)
        assertTrue(
            "a default null must not turn into a key older builds would have to know",
            !json.encodeToString(ProviderConfig.serializer(), config).contains("customBalanceURL"),
        )
    }

    @Test fun `backup wire carries the URL and restore reads it back`() {
        val encoded = BackupProviderWire.encode(ProviderConfig(instances = mutableListOf(instance(url))))
        assertTrue(encoded.contains("customBalanceURL"))
        val restored = BackupImporter.parseProviderConfigLeniently(encoded).config
        assertEquals(url, restored.instances.single().customBalanceURL)
    }

    @Test fun `a backup written before the field existed still restores`() {
        val legacy = """
            {"instances":[{"id":"p1","label":"Relay","providerType":"openAI",
            "credentialType":"apiKey","customBaseURL":"https://relay.example.com/v1"}]}
        """.trimIndent()
        val restored = BackupImporter.parseProviderConfigLeniently(legacy).config
        assertEquals(1, restored.instances.size)
        assertNull(restored.instances.single().customBalanceURL)
        assertEquals("https://relay.example.com/v1", restored.instances.single().customBaseURL)
    }

    @Test fun `JSON mirror without the key decodes to null`() {
        val legacy = """{"instances":[{"id":"p1","label":"Relay","providerType":"openAI","credentialType":"apiKey"}]}"""
        val decoded = BackupFormat.json.decodeFromString(ProviderConfig.serializer(), legacy)
        assertNull(decoded.instances.single().customBalanceURL)
    }

    @Test fun `v4 to v5 ALTER preserves every existing row`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """
                    CREATE TABLE provider_instances (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        provider_type TEXT NOT NULL,
                        credential_type TEXT NOT NULL,
                        custom_base_url TEXT,
                        append_v1_suffix INTEGER NOT NULL DEFAULT 1,
                        use_responses_api INTEGER NOT NULL DEFAULT 0,
                        azure_mode INTEGER NOT NULL DEFAULT 0,
                        image_endpoint_mode TEXT,
                        image_endpoint_resolved TEXT,
                        custom_user_agent TEXT,
                        is_enabled INTEGER NOT NULL DEFAULT 1,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                s.execute(
                    "INSERT INTO provider_instances (id,label,provider_type,credential_type,custom_base_url," +
                        "image_endpoint_mode,image_endpoint_resolved,is_enabled,sort_order,created_at) " +
                        "VALUES ('p1','Kept','openAI','apiKey','https://relay.example.com/v1','auto','imagesGenerations',1,3,123)",
                )
                s.execute(ProviderDatabase.MIGRATION_4_5_SQL)
                s.executeQuery(
                    "SELECT label, custom_base_url, image_endpoint_mode, image_endpoint_resolved, is_enabled, " +
                        "sort_order, created_at, custom_balance_url FROM provider_instances WHERE id='p1'",
                ).use { r ->
                    assertTrue(r.next())
                    assertEquals("Kept", r.getString(1))
                    assertEquals("https://relay.example.com/v1", r.getString(2))
                    assertEquals("auto", r.getString(3))
                    assertEquals("imagesGenerations", r.getString(4))
                    assertEquals(1, r.getInt(5))
                    assertEquals(3, r.getInt(6))
                    assertEquals(123L, r.getLong(7))
                    assertNull("new column backfills to automatic detection", r.getString(8))
                    assertTrue(!r.next())
                }
            }
        }
    }
}
