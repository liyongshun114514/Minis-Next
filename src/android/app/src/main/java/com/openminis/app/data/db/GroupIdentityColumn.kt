package com.openminis.app.data.db

/**
 * [T-chat-group-identity] The `messages.group_identity` column, in one place.
 *
 * The upgrade must be IDEMPOTENT: [AppDatabase.MIGRATION_15_14] deliberately
 * keeps the column (a downgrade must not delete captured identity), so a user
 * who downgrades and upgrades again re-runs the 14 → 15 step on a table that
 * already has it. SQLite has no `ADD COLUMN IF NOT EXISTS`, and a bare
 * `ALTER TABLE` would then abort with "duplicate column name" and the app would
 * never open.
 *
 * Split out of the migration lambda so plain-JVM SQLite tests can exercise the
 * decision AND the exact SQL — Room's `SupportSQLiteDatabase` needs an Android
 * runtime, and a migration that is only ever tested on the happy path is how
 * the upgrade-after-downgrade crash ships.
 */
internal object GroupIdentityColumn {

    const val TABLE = "messages"
    const val COLUMN = "group_identity"
    const val ADD_SQL = "ALTER TABLE messages ADD COLUMN group_identity TEXT"

    /** True when the column still has to be added. */
    fun needsAdd(existingColumns: Collection<String>): Boolean = COLUMN !in existingColumns
}