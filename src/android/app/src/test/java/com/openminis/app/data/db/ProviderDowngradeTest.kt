package com.openminis.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.sql.DriverManager

/** Exercises the real migration SQL, then Room's open/validation/DAO path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, manifest = Config.NONE)
class ProviderDowngradeTest {
    @Test fun `v6 downgrade preserves all v5 group fields and unrelated tables`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { s ->
                s.execute("""CREATE TABLE provider_model_groups (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, strategy TEXT NOT NULL,
                    fallback_strategy TEXT NOT NULL, default_thinking_level TEXT,
                    context_limit_tokens INTEGER, last_context_limit_tokens INTEGER,
                    member_entry_ids_json TEXT NOT NULL, sort_order INTEGER NOT NULL, avatar TEXT)""")
                s.execute("INSERT INTO provider_model_groups VALUES ('g','Gemini','ordered','next','high',8192,4096,'[\"p/m\"]',7,'builtin:gemini')")
                s.execute("INSERT INTO provider_model_groups VALUES ('g2','Empty','ordered','next',NULL,NULL,NULL,'[]',9,NULL)")
                s.execute("CREATE TABLE provider_config_meta (key TEXT PRIMARY KEY, value TEXT)")
                s.execute("INSERT INTO provider_config_meta VALUES ('json_sync_hash','unchanged')")
                c.autoCommit = false
                ProviderDatabase.MIGRATION_6_5_SQL.forEach { s.execute(it) }
                c.commit()
                s.executeQuery("SELECT * FROM provider_model_groups ORDER BY sort_order").use { r ->
                    assertTrue(r.next())
                    assertEquals(9, r.metaData.columnCount)
                    assertEquals("g", r.getString("id"))
                    assertEquals("Gemini", r.getString("name"))
                    assertEquals("ordered", r.getString("strategy"))
                    assertEquals("next", r.getString("fallback_strategy"))
                    assertEquals("high", r.getString("default_thinking_level"))
                    assertEquals(8192, r.getInt("context_limit_tokens"))
                    assertEquals(4096, r.getInt("last_context_limit_tokens"))
                    assertEquals("[\"p/m\"]", r.getString("member_entry_ids_json"))
                    assertEquals(7, r.getInt("sort_order"))
                    assertTrue(r.next())
                    assertNull(r.getObject("context_limit_tokens"))
                    assertNull(r.getString("default_thinking_level"))
                    assertFalse(r.next())
                }
                s.executeQuery("SELECT value FROM provider_config_meta").use { r ->
                    assertTrue(r.next()); assertEquals("unchanged", r.getString(1))
                }
                s.executeQuery("PRAGMA foreign_key_check").use { assertFalse(it.next()) }
            }
        }
    }

    @Test fun `Room reopens a v6 database and validates restored v5 schema`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "provider-downgrade-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ProviderDatabase::class.java, name)
            .addMigrations(ProviderDatabase.MIGRATION_6_5).allowMainThreadQueries().build()
        try {
            val first = open()
            first.openHelper.writableDatabase.apply {
                execSQL("INSERT INTO provider_model_groups VALUES ('g','Kept','ordered','next',NULL,NULL,NULL,'[]',4)")
                execSQL("ALTER TABLE provider_model_groups ADD COLUMN avatar TEXT")
                execSQL("UPDATE provider_model_groups SET avatar='builtin:gemini'")
                version = 6
                // Force validation, rather than allowing an old matching identity to hide errors.
                execSQL("UPDATE room_master_table SET identity_hash='v6-schema'")
            }
            first.close()
            repeat(2) {
                val reopened = open()
                try {
                    assertEquals(5, reopened.openHelper.writableDatabase.version)
                    reopened.openHelper.readableDatabase.query("SELECT name,sort_order FROM provider_model_groups").use { r ->
                        assertTrue(r.moveToFirst()); assertEquals("Kept", r.getString(0)); assertEquals(4, r.getInt(1))
                    }
                    assertEquals(0, kotlinx.coroutines.runBlocking { reopened.providerConfigDao().instanceCount() })
                } finally { reopened.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
