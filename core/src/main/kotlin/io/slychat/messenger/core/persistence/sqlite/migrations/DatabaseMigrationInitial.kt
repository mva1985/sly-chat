package io.slychat.messenger.core.persistence.sqlite.migrations

import com.almworks.sqlite4java.SQLiteConnection
import io.slychat.messenger.core.crypto.randomPreKeyId
import io.slychat.messenger.core.persistence.sqlite.DatabaseMigration

/** Initial database setup. */
class DatabaseMigrationInitial : DatabaseMigration(0, TABLE_NAMES) {
    companion object {
        /** Table names in creation order. */
        private val TABLE_NAMES = arrayListOf(
            "prekey_ids",
            "signed_prekeys",
            "unsigned_prekeys",
            "contacts",
            "conversation_info",
            "signal_sessions",
            "package_queue"
        )
    }

    private fun initializePreKeyIds(connection: SQLiteConnection) {
        val nextSignedId = randomPreKeyId()
        val nextUnsignedId = randomPreKeyId()
        connection.exec("INSERT INTO prekey_ids (next_signed_id, next_unsigned_id) VALUES ($nextSignedId, $nextUnsignedId)")
    }

    override fun apply(connection: SQLiteConnection) {
        createNewTables(connection)
        initializePreKeyIds(connection)
    }
}