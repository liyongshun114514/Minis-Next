package com.openminis.app.data.db

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * [T-chat-group-identity] The 14 ⇄ 15 migration pair, run against real SQLite.
 *
 * Not just the happy path: [AppDatabase.MIGRATION_15_14] keeps the column on
 * purpose, so the second 14 → 15 run happens on a table that ALREADY has it.
 * A bare `ALTER TABLE … ADD COLUMN` fails there with "duplicate column name"
 * and the app never opens, which is exactly the kind of bug a one-way test
 * misses. This drives the same decision + SQL the migration lambda uses
 * ([GroupIdentityColumn]) so the guard and the statement cannot drift.
 */
class ChatGroupIdentityMigrationTest {

    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            // The v14 `messages` shape (the columns this feature cares about).
            st.execute(
                """
                CREATE TABLE messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    parts_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    token_usage TEXT,
                    sort_order INTEGER NOT NULL,
                    model_id TEXT,
                    model_display_name TEXT,
                    provider_type TEXT,
                    provider_instance_id TEXT
                )
                """.trimIndent(),
            )
            st.execute(
                "INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order, " +
                    "model_id, model_display_name) VALUES " +
                    "('m1','s1','assistant','[]',1,0,'gpt-5','GPT-5')," +
                    "('m2','s1','assistant','[]',2,1,'claude-opus-4-8','Claude Opus 4.8')",
            )
        }
    }

    @After
    fun close() = db.close()

    private fun columns(): List<String> {
        val out = ArrayList<String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`messages`)").use { rs ->
                while (rs.next()) out.add(rs.getString("name"))
            }
        }
        return out
    }

    /** The body of [AppDatabase.MIGRATION_14_15]. */
    private fun upgrade14to15() {
        if (GroupIdentityColumn.needsAdd(columns())) {
            db.createStatement().use { it.execute(GroupIdentityColumn.ADD_SQL) }
        }
    }

    @Test
    fun `14 to 15 adds one nullable column and keeps every row`() {
        upgrade14to15()
        assertTrue(columns().contains("group_identity"))
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) AS n FROM messages").use { rs ->
                assertTrue(rs.next())
                assertEquals(2, rs.getInt("n"))
            }
            // Pre-existing rows read NULL = "no snapshot", which is the UI's
            // signal to use the row's own model identity.
            st.executeQuery("SELECT group_identity FROM messages WHERE id='m1'").use { rs ->
                assertTrue(rs.next())
                assertNull(rs.getString(1))
            }
        }
    }

    @Test
    fun `downgrade keeps the column so the upgrade back is lossless`() {
        upgrade14to15()
        db.createStatement().use {
            it.execute("UPDATE messages SET group_identity='{\"v\":1,\"kind\":\"group\",\"name\":\"Work\"}' WHERE id='m1'")
        }

        // [AppDatabase.MIGRATION_15_14] is a documented no-op: the identity
        // captured by the newer build must survive a downgrade.

        upgrade14to15() // must not throw "duplicate column name"
        db.createStatement().use { st ->
            st.executeQuery("SELECT group_identity FROM messages WHERE id='m1'").use { rs ->
                assertTrue(rs.next())
                assertEquals("""{"v":1,"kind":"group","name":"Work"}""", rs.getString(1))
            }
        }
        assertEquals(1, columns().count { it == "group_identity" })
    }

    @Test
    fun `the second upgrade performs no ALTER at all`() {
        upgrade14to15()
        val before = columns()
        assertFalse(GroupIdentityColumn.needsAdd(before))
        upgrade14to15()
        assertEquals(before, columns())
    }

    /**
     * The exported schema, wherever this test happens to run from: Gradle's
     * unit-test JVM uses the module dir (`src/android/app`), the offline
     * verification harness uses the repo root. Search both (and walk up a few
     * levels) instead of hard-coding one.
     */
    private fun schemaFile(): File {
        val rel = "schemas/com.openminis.app.data.db.AppDatabase/15.json"
        val direct = listOf(File(rel), File("src/android/app/$rel"))
        direct.firstOrNull { it.isFile }?.let { return it }
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val candidate = dir?.let { File(it, rel) }
            if (candidate != null && candidate.isFile) return candidate
            val moduleRelative = dir?.let { File(it, "src/android/app/$rel") }
            if (moduleRelative != null && moduleRelative.isFile) return moduleRelative
            dir = dir?.parentFile
        }
        return File(rel)
    }

    @Test
    fun `the exported v15 schema declares the column and matches the guard`() {
        val schema = schemaFile()
        assertTrue("missing ${schema.absolutePath}", schema.isFile)
        val database = JSONObject(schema.readText()).getJSONObject("database")
        assertEquals(15, database.getInt("version"))
        assertEquals(15, DatabaseVersionGuard.CODE_DB_VERSION)

        var messages: JSONObject? = null
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            if (e.getString("tableName") == "messages") messages = e
        }
        val msg = requireNotNull(messages) { "messages table missing" }
        val fields = msg.getJSONArray("fields")
        val createSql = msg.getString("createSql")
        var found = false
        for (i in 0 until fields.length()) {
            val f = fields.getJSONObject(i)
            if (f.getString("columnName") == "group_identity") {
                found = true
                assertEquals("TEXT", f.getString("affinity"))
                assertFalse("must be nullable", f.getBoolean("notNull"))
            }
        }
        assertTrue("group_identity missing from the exported fields", found)
        assertTrue(createSql.contains("`group_identity` TEXT"))
    }
}