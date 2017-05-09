package io.slychat.messenger.core.persistence.sqlite

import com.almworks.sqlite4java.SQLiteConnection
import com.almworks.sqlite4java.SQLiteConstants
import com.almworks.sqlite4java.SQLiteException
import com.almworks.sqlite4java.SQLiteStatement
import com.fasterxml.jackson.databind.ObjectMapper
import io.slychat.messenger.core.UserId
import io.slychat.messenger.core.files.SharedFrom
import io.slychat.messenger.core.files.UserMetadata
import io.slychat.messenger.core.persistence.*
import nl.komponents.kovenant.Promise
import java.util.*

internal fun deleteExpiringMessagesForConversation(connection: SQLiteConnection, conversationId: ConversationId) {
    connection.withPrepared("DELETE FROM expiring_messages WHERE conversation_id=?") { stmt ->
        stmt.bind(1, conversationId)
        stmt.step()
    }
}

private fun SQLiteStatement.bind(name: String, state: ReceivedAttachmentState) {
    bind(name, state.toString())
}

private fun SQLiteStatement.columnReceivedAttachmentState(index: Int): ReceivedAttachmentState {
    return ReceivedAttachmentState.valueOf(columnString(index))
}

/** Depends on SQLiteContactsPersistenceManager for creating and deleting conversation tables. */
class SQLiteMessagePersistenceManager(
    private val sqlitePersistenceManager: SQLitePersistenceManager
) : MessagePersistenceManager {
    private val conversationInfoUtils = ConversationInfoUtils()

    private val objectMapper = ObjectMapper()

    fun addMessages(conversationId: ConversationId, messages: Collection<ConversationMessageInfo>): Promise<Unit, Exception> {
        if (messages.isEmpty())
            return Promise.ofSuccess(Unit)

        return sqlitePersistenceManager.runQuery { connection ->
            connection.withTransaction {
                messages.forEach { insertMessage(connection, conversationId, it) }

                updateConversationInfo(connection, conversationId)
            }
        }
    }


    override fun getUndeliveredMessages(): Promise<Map<ConversationId, List<ConversationMessageInfo>>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        TODO()
    }

    private fun isMissingConvTableError(e: SQLiteException): Boolean =
        e.message?.let { "no such table: conv_" in it } ?: false

    private fun conversationMessageInfoToRow(conversationMessageInfo: ConversationMessageInfo, stmt: SQLiteStatement) {
        val messageInfo = conversationMessageInfo.info
        stmt.bind(":id", messageInfo.id)
        stmt.bind(":speakerContactId", conversationMessageInfo.speaker)
        stmt.bind(":timestamp", messageInfo.timestamp)
        stmt.bind(":receivedTimestamp", messageInfo.receivedTimestamp)
        stmt.bind(":isRead", messageInfo.isRead)
        stmt.bind(":isExpired", messageInfo.isExpired)
        stmt.bind(":ttl", messageInfo.ttlMs)
        stmt.bind(":expiresAt", messageInfo.expiresAt)
        stmt.bind(":isDelivered", messageInfo.isDelivered)
        stmt.bind(":message", messageInfo.message)
        stmt.bind(":hasFailures", conversationMessageInfo.failures.isNotEmpty())
    }

    /** Throws InvalidGroupException if group_conv table was missing, else rethrows the given exception. */
    //change this to invalidconversation or something instead
    private fun handleInvalidConversationException(e: SQLiteException, conversationId: ConversationId): Nothing {
        if (isMissingConvTableError(e))
            throw InvalidConversationException(conversationId)
        else
            throw e
    }

    override fun getConversationInfo(conversationId: ConversationId): Promise<ConversationInfo?, Exception> = sqlitePersistenceManager.runQuery { connection ->
        conversationInfoUtils.getConversationInfo(connection, conversationId)
    }

    override fun getUserConversation(userId: UserId): Promise<UserConversation?, Exception> = sqlitePersistenceManager.runQuery {
        conversationInfoUtils.getUserConversation(it, userId)
    }

    override fun getGroupConversation(groupId: GroupId): Promise<GroupConversation?, Exception> = sqlitePersistenceManager.runQuery {
        conversationInfoUtils.getGroupConversation(it, groupId)
    }

    override fun getAllGroupConversations(): Promise<List<GroupConversation>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        conversationInfoUtils.getAllGroupConversations(connection)
    }

    override fun getAllUserConversations(): Promise<List<UserConversation>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        conversationInfoUtils.getAllUserConversations(connection)
    }

    override fun addMessage(conversationId: ConversationId, conversationMessageInfo: ConversationMessageInfo, receivedAttachments: List<ReceivedAttachment>): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            try {
                insertMessage(connection, conversationId, conversationMessageInfo)
            }
            catch (e: SQLiteException) {
                if (isMissingConvTableError(e)) {
                    when (conversationId) {
                        is ConversationId.User -> throw InvalidMessageLevelException(conversationId.id)
                        is ConversationId.Group -> throw InvalidConversationException(conversationId)
                    }
                }
                else
                    throw e
            }

            insertReceivedAttachments(connection, conversationId, conversationMessageInfo.info.id, receivedAttachments)

            updateConversationInfo(connection, conversationId)
        }
    }

    private fun updateAttachmentRefCount(connection: SQLiteConnection, fileId: String, adjustment: Int) {
        val initial = if (adjustment > 0) adjustment else 0

        val sql = """
INSERT OR REPLACE INTO attachment_cache_refcounts
    (file_id, ref_count)
VALUES
    (:fileId, coalesce((SELECT ref_count + $adjustment FROM attachment_cache_refcounts WHERE file_id = :fileId), $initial))
"""
        connection.withPrepared(sql) {
            it.bind(":fileId", fileId)
            it.step()
        }
    }

    private fun insertReceivedAttachments(connection: SQLiteConnection, conversationId: ConversationId, messageId: String, receivedAttachments: List<ReceivedAttachment>) {
        //language=SQLite
        val sql = """
INSERT INTO
    received_attachments
    (conversation_id, message_id, n, file_id, their_share_key, file_key, cipher_id, directory, file_name, shared_from_user_id, shared_from_group_id, state)
VALUES
    (:conversationId, :messageId, :n, :fileId, :theirShareKey, :fileKey, :cipherId, :directory, :fileName, :sharedFromUserId, :sharedFromGroupId, :state)
"""

        connection.withPrepared(sql) { stmt ->
            stmt.bind(":conversationId", conversationId)
            stmt.bind(":messageId", messageId)

            receivedAttachments.forEach {
                stmt.bind(":n", it.id.n)
                stmt.bind(":fileId", it.fileId)
                stmt.bind(":theirShareKey", it.theirShareKey)
                stmt.bind(":fileKey", it.userMetadata.fileKey)
                stmt.bind(":cipherId", it.userMetadata.cipherId)
                stmt.bind(":directory", it.userMetadata.directory)
                stmt.bind(":fileName", it.userMetadata.fileName)
                val sharedFrom = it.userMetadata.sharedFrom ?: throw IllegalArgumentException("ReceivedAttachment.sharedFrom should not be null")
                stmt.bind(":sharedFromUserId", sharedFrom.userId)
                stmt.bind(":sharedFromGroupId", sharedFrom.groupId)
                stmt.bind(":state", it.state)

                try {
                    stmt.step()
                }
                catch (e: SQLiteException) {
                    if (e.errorCode == SQLiteConstants.SQLITE_CONSTRAINT_FOREIGNKEY)
                        throw InvalidAttachmentException(AttachmentId(conversationId, messageId, it.id.n))
                    else
                        throw e
                }

                stmt.reset(false)

                updateAttachmentRefCount(connection, it.fileId, 1)
            }
        }
    }

    private fun rowToReceivedAttachment(stmt: SQLiteStatement): ReceivedAttachment {
        return ReceivedAttachment(
            AttachmentId(
                stmt.columnConversationId(0),
                stmt.columnString(1),
                stmt.columnInt(2)
            ),
            stmt.columnString(3),
            stmt.columnString(4),
            UserMetadata(
                stmt.columnKey(5),
                stmt.columnCipherId(6),
                stmt.columnString(7),
                stmt.columnString(8),
                SharedFrom(
                    stmt.columnUserId(9),
                    stmt.columnNullableGroupId(10)
                )
            ),
            stmt.columnReceivedAttachmentState(11),
            null
        )

    }

    override fun getAllReceivedAttachments(): Promise<List<ReceivedAttachment>, Exception> = sqlitePersistenceManager.runQuery {
        //language=SQLite
        val sql = """
SELECT
    conversation_id,
    message_id,
    n,
    file_id,
    their_share_key,
    file_key,
    cipher_id,
    directory,
    file_name,
    shared_from_user_id,
    shared_from_group_id,
    state
FROM
    received_attachments
"""
        it.withPrepared(sql) {
            it.map {
                rowToReceivedAttachment(it)
            }
        }
    }

    override fun getReceivedAttachments(conversationId: ConversationId, messageId: String): Promise<List<ReceivedAttachment>, Exception> = sqlitePersistenceManager.runQuery {
        //language=SQLite
        val sql = """
SELECT
    conversation_id,
    message_id,
    n,
    file_id,
    their_share_key,
    file_key,
    cipher_id,
    directory,
    file_name,
    shared_from_user_id,
    shared_from_group_id,
    state
FROM
    received_attachments
WHERE
    conversation_id = :conversationId
AND
    message_id = :messageId
"""
        it.withPrepared(sql) {
            it.bind(":conversationId", conversationId)
            it.bind(":messageId", messageId)
            it.map {
                rowToReceivedAttachment(it)
            }
        }
    }

    private fun deleteReceivedAttachments(connection: SQLiteConnection, ids: List<AttachmentId>) {
        //language=SQLite
        val sql = """
DELETE FROM
    received_attachments
WHERE
    conversation_id = :conversationId
AND
    message_id = :messageId
AND
    n = :n
"""

        connection.withPrepared(sql) { stmt ->
            ids.forEach { id ->
                stmt.bind(":conversationId", id.conversationId)
                stmt.bind(":messageId", id.messageId)
                stmt.bind(":n", id.n)
                stmt.step()
                stmt.reset(false)
            }
        }
    }

    private fun markAttachmentsInline(connection: SQLiteConnection, ids: List<AttachmentId>) {
        ids.forEach { id ->
            //language=SQLite
            val sql = """
UPDATE
    attachments
SET
    is_inline=1
WHERE
    conversation_id = :conversationId
AND
    message_id = :messageId
AND
    n = :n
"""
            connection.withPrepared(sql) { stmt ->
                ids.forEach { id ->
                    stmt.bind(":conversationId", id.conversationId)
                    stmt.bind(":messageId", id.messageId)
                    stmt.bind(":n", id.n)
                    stmt.step()
                    stmt.reset(false)
                }
            }
        }
    }

    override fun deleteReceivedAttachments(completed: List<AttachmentId>, markInline: List<AttachmentId>): Promise<Unit, Exception> {
        return if (completed.isEmpty() && markInline.isEmpty())
            Promise.ofSuccess(Unit)
        else
            sqlitePersistenceManager.runQuery { connection ->
                connection.withTransaction {
                    deleteReceivedAttachments(connection, completed)
                    markAttachmentsInline(connection, markInline)
                }
            }
    }

    override fun updateReceivedAttachmentState(ids: List<AttachmentId>, newState: ReceivedAttachmentState): Promise<Unit, Exception> {
        return if (ids.isEmpty()) {
            Promise.ofSuccess(Unit)
        }
        else sqlitePersistenceManager.runQuery { connection ->
            //language=SQLite
            val sql = """
UPDATE
    received_attachments
SET
    state = :state
WHERE
    conversation_id = :conversationId
AND
    message_id = :messageId
AND
    n = :n
"""
            connection.withPrepared(sql) { stmt ->
                stmt.bind(":state", newState)
                ids.forEach { id ->
                    stmt.bind(":conversationId", id.conversationId)
                    stmt.bind(":messageId", id.messageId)
                    stmt.bind(":n", id.n)
                    stmt.step()
                    stmt.reset(false)
                }
            }
        }
    }

    private fun getUnreadCount(connection: SQLiteConnection, conversationId: ConversationId): Int {
        val tableName = ConversationTable.getTablename(conversationId)
        val sql = """
SELECT
    count(is_read)
FROM
    $tableName
WHERE
    is_read=0
"""

        return connection.withPrepared(sql) { stmt ->
            stmt.step()
            stmt.columnInt(0)
        }
    }

    private fun updateConversationInfo(connection: SQLiteConnection, conversationId: ConversationId) {
        val unreadCount = getUnreadCount(connection, conversationId)
        val lastMessageInfo = getLastConvoMessage(connection, conversationId)

        if (lastMessageInfo == null)
            insertOrReplaceNewConversationInfo(connection, conversationId)
        else {
            val sql = """
UPDATE
    conversation_info
SET
    last_speaker_contact_id=?,
    last_message=?,
    last_timestamp=?,
    unread_count=?
WHERE
    conversation_id=?
"""
            connection.withPrepared(sql) { stmt ->
                val info = lastMessageInfo.info
                val message = if (info.ttlMs <= 0)
                    info.message
                else
                    null

                stmt.bind(1, lastMessageInfo.speaker)
                stmt.bind(2, message)
                stmt.bind(3, info.timestamp)
                stmt.bind(4, unreadCount)
                stmt.bind(5, conversationId)

                stmt.step()
            }
        }
    }

    private fun isMessageIdValid(connection: SQLiteConnection, conversationId: ConversationId, messageId: String): Boolean {
        val tableName = ConversationTable.getTablename(conversationId)
        return connection.withPrepared("SELECT 1 FROM $tableName WHERE id=?") { stmt ->
            stmt.bind(1, messageId)
            stmt.step()
        }
    }

    private fun insertMessage(connection: SQLiteConnection, conversationId: ConversationId, conversationMessageInfo: ConversationMessageInfo) {
        val tableName = ConversationTable.getTablename(conversationId)

        val sql = """
INSERT INTO $tableName
    (id, speaker_contact_id, timestamp, received_timestamp, is_read, is_expired, ttl, expires_at, is_delivered, message, has_failures, n)
VALUES
    (:id, :speakerContactId, :timestamp, :receivedTimestamp, :isRead, :isExpired, :ttl, :expiresAt, :isDelivered, :message, :hasFailures,
        (SELECT count(n)
         FROM   $tableName
         WHERE  timestamp = :timestamp)+1)
"""
        try {
            connection.withPrepared(sql) { stmt ->
                conversationMessageInfoToRow(conversationMessageInfo, stmt)
                stmt.step()
            }

            insertAttachments(connection, conversationId, conversationMessageInfo.info.id, conversationMessageInfo.info.attachments)

            insertFailures(connection, conversationId, conversationMessageInfo.info.id, conversationMessageInfo.failures)
        }
        catch (e: SQLiteException) {
            val message = e.message

            //ignores duplicates
            if (message != null) {
                if (e.baseErrorCode == SQLiteConstants.SQLITE_CONSTRAINT &&
                    message.contains("UNIQUE constraint failed: conv_[^.]+.id]".toRegex()))
                    return
            }

            throw e
        }
    }

    private fun insertAttachments(connection: SQLiteConnection, conversationId: ConversationId, messageId: String, attachments: List<MessageAttachmentInfo>) {
        //language=SQLite
        val sql = """
INSERT INTO
    attachments
    (conversation_id, message_id, n, display_name, is_inline, file_id)
VALUES
    (:conversationId, :messageId, :n, :displayName, :isInline, :fileId)
"""

        connection.withPrepared(sql) { stmt ->
            attachments.forEach {
                stmt.bind(":conversationId", conversationId)
                stmt.bind(":messageId", messageId)
                stmt.bind(":n", it.n)
                stmt.bind(":displayName", it.displayName)
                stmt.bind(":isInline", it.isInline)
                stmt.bind(":fileId", it.fileId)
                stmt.step()

                stmt.reset()
            }
        }
    }

    private fun deleteFailures(connection: SQLiteConnection, conversationId: ConversationId, messageIds: Collection<String>) {
        connection.prepare("DELETE FROM message_failures WHERE conversation_id=? AND message_id IN (${getPlaceholders(messageIds.size)})").use { stmt ->
            stmt.bind(1, conversationId)

            messageIds.forEachIndexed { i, messageId ->
                stmt.bind(i + 2, messageId)
            }

            stmt.step()
        }
    }

    private fun decAttachmentRefCountForMessages(connection: SQLiteConnection, conversationId: ConversationId, messageIds: Collection<String>) {
        val sql = """
UPDATE
    attachment_cache_refcounts
SET
    ref_count = ref_count - 1
WHERE
    file_id IN (SELECT file_id FROM attachments WHERE conversation_id = :conversationId AND message_id = :messageId)
"""

        connection.withPrepared(sql) { stmt ->
            stmt.bind(":conversationId", conversationId)

            messageIds.forEach {
                stmt.bind(":messageId", it)
                stmt.step()
                stmt.reset(false)
            }
        }
    }

    override fun deleteMessages(conversationId: ConversationId, messageIds: Collection<String>): Promise<Unit, Exception> {
        if (messageIds.isEmpty())
            return Promise.of(Unit)

        return sqlitePersistenceManager.runQuery { connection ->
            connection.withTransaction {
                decAttachmentRefCountForMessages(connection, conversationId, messageIds)

                val tableName = ConversationTable.getTablename(conversationId)

                try {
                    connection.prepare("DELETE FROM $tableName WHERE id IN (${getPlaceholders(messageIds.size)})").use { stmt ->
                        messageIds.forEachIndexed { i, messageId ->
                            stmt.bind(i + 1, messageId)
                        }

                        stmt.step()
                    }

                    deleteFailures(connection, conversationId, messageIds)
                }
                catch (e: SQLiteException) {
                    handleInvalidConversationException(e, conversationId)
                }

                deleteExpiringMessages(connection, conversationId, messageIds)

                updateConversationInfo(connection, conversationId)
            }
        }
    }

    private fun getLastConvoMessage(connection: SQLiteConnection, conversationId: ConversationId): ConversationMessageInfo? {
        val tableName = ConversationTable.getTablename(conversationId)

        val sql =
            """
SELECT
    id,
    speaker_contact_id,
    timestamp,
    received_timestamp,
    is_read,
    is_expired,
    ttl,
    expires_at,
    is_delivered,
    message,
    has_failures
FROM
    $tableName
WHERE
    is_expired = 0
ORDER BY
    timestamp DESC, n DESC
LIMIT
    1
"""
        return connection.withPrepared(sql) { stmt ->
            if (!stmt.step())
                null
            else
                rowToConversationMessageInfo(connection, stmt, conversationId)
        }
    }

    private fun getLastConvoTimestamp(connection: SQLiteConnection, conversationId: ConversationId): Long? {
        val tableName = ConversationTable.getTablename(conversationId)

        val sql = """
SELECT
    timestamp
FROM
    $tableName
ORDER BY
    timestamp DESC, n DESC
LIMIT
    1
"""

        return connection.withPrepared(sql) { stmt ->
            if (!stmt.step())
                null
            else
                stmt.columnLong(0)
        }
    }

    private fun decAttachmentRefCountForConvo(connection: SQLiteConnection, conversationId: ConversationId) {
        //language=SQLite
        val sql = """
UPDATE
    attachment_cache_refcounts
SET
    ref_count = ref_count - 1
WHERE
    file_id IN (SELECT file_id FROM attachments WHERE conversation_id = :conversationId)
"""
        connection.withPrepared(sql) {
            it.bind(":conversationId", conversationId)
            it.step()
        }
    }

    override fun deleteAllMessages(conversationId: ConversationId): Promise<Long?, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            val lastMessageTimestamp = getLastConvoTimestamp(connection, conversationId)

            //no last message
            if (lastMessageTimestamp == null) {
                null
            }
            else {
                decAttachmentRefCountForConvo(connection, conversationId)
                val tableName = ConversationTable.getTablename(conversationId)
                connection.withPrepared("DELETE FROM $tableName", SQLiteStatement::step)

                deleteExpiringMessagesForConversation(connection, conversationId)

                deleteFailuresForConversation(connection, conversationId)

                insertOrReplaceNewConversationInfo(connection, conversationId)

                lastMessageTimestamp
            }
        }
    }

    private fun deleteFailuresForConversation(connection: SQLiteConnection, conversationId: ConversationId) {
        connection.withPrepared("DELETE FROM message_failures WHERE conversation_id=?") { stmt ->
            stmt.bind(1, conversationId)
            stmt.step()
        }
    }

    private fun deleteExpiringEntriesUntil(connection: SQLiteConnection, conversationId: ConversationId, timestamp: Long) {
        val tableName = ConversationTable.getTablename(conversationId)
        val sql = """
DELETE FROM
    expiring_messages
WHERE message_id IN (
    SELECT
        e.message_id
    FROM
        expiring_messages e
    JOIN
        $tableName c
    ON
        e.message_id=c.id
    WHERE
        e.conversation_id=?
    AND
        c.timestamp <= ?
)
"""
        connection.withPrepared(sql) { stmt ->
            stmt.bind(1, conversationId)
            stmt.bind(2, timestamp)
            stmt.step()
        }
    }

    private fun deleteAllConvoMessagesUntil(connection: SQLiteConnection, conversationId: ConversationId, timestamp: Long) {
        val tableName = ConversationTable.getTablename(conversationId)
        val sql = """
DELETE FROM
    $tableName
WHERE
    timestamp <= ?
"""

        connection.withPrepared(sql) { stmt ->
            stmt.bind(1, timestamp)
            stmt.step()
        }
    }

    private fun decAttachmentRefCountUntil(connection: SQLiteConnection, conversationId: ConversationId, timestamp: Long) {
        val tableName = ConversationTable.getTablename(conversationId)
        val sql = """
UPDATE
    attachment_cache_refcounts
SET
    ref_count = ref_count - 1
WHERE
    file_id IN (
        SELECT
            file_id
        FROM
            attachments a
        JOIN
            $tableName c
        ON
            a.conversation_id = :conversationId
        AND
            a.message_id = c.id
        WHERE
            c.timestamp <= :timestamp
    )
"""
        connection.withPrepared(sql) {
            it.bind(":conversationId", conversationId)
            it.bind(":timestamp", timestamp)
            it.step()
        }
    }

    override fun deleteAllMessagesUntil(conversationId: ConversationId, timestamp: Long): Promise<Unit, Exception> = sqlitePersistenceManager.runQuery { connection ->
        connection.withTransaction {
            decAttachmentRefCountUntil(connection, conversationId, timestamp)
            deleteExpiringEntriesUntil(connection, conversationId, timestamp)
            deleteFailuresUntil(connection, conversationId, timestamp)
            deleteAllConvoMessagesUntil(connection, conversationId, timestamp)
            updateConversationInfo(connection, conversationId)
        }
    }

    private fun deleteFailuresUntil(connection: SQLiteConnection, conversationId: ConversationId, timestamp: Long) {
        val tableName = ConversationTable.getTablename(conversationId)
        val sql = """
DELETE FROM
    message_failures
WHERE
    conversation_id = ?
AND
    message_id IN (
        SELECT
            f.message_id
        FROM
            message_failures f
        JOIN
            $tableName c
        ON
            f.message_id=c.id
        WHERE
            f.conversation_id=?
        AND
            c.timestamp <= ?
    )
"""
        connection.withPrepared(sql) { stmt ->
            stmt.bind(1, conversationId)
            stmt.bind(2, conversationId)
            stmt.bind(3, timestamp)
            stmt.step()
        }
    }

    override fun markMessageAsDelivered(conversationId: ConversationId, messageId: String, timestamp: Long): Promise<ConversationMessageInfo?, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val tableName = ConversationTable.getTablename(conversationId)

        val sql = "UPDATE $tableName SET is_delivered=1, received_timestamp=? WHERE id=?"

        val currentInfo = try {
            getConversationMessageInfo(connection, conversationId, messageId) ?: throw InvalidConversationMessageException(conversationId, messageId)
        }
        catch (e: SQLiteException) {
            //FIXME
            handleInvalidConversationException(e, conversationId)
        }

        if (!currentInfo.info.isDelivered) {
            try {
                connection.withPrepared(sql) { stmt ->
                    stmt.bind(1, timestamp)
                    stmt.bind(2, messageId)
                    stmt.step()
                }
            }
            catch (e: SQLiteException) {
                handleInvalidConversationException(e, conversationId)
            }

            if (connection.changes <= 0)
                throw InvalidConversationMessageException(conversationId, messageId)

            getConversationMessageInfo(connection, conversationId, messageId) ?: throw InvalidConversationMessageException(conversationId, messageId)
        }
        else
            null
    }

    override fun markConversationAsRead(conversationId: ConversationId): Promise<List<String>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val unreadMessageIds = try {
            getUnreadMessageIds(connection, conversationId)
        }
        catch (e: SQLiteException) {
            if (isMissingConvTableError(e))
                throw InvalidConversationException(conversationId)

            throw e
        }

        connection.withTransaction {
            connection.withPrepared("UPDATE conversation_info set unread_count=0 WHERE conversation_id=?") { stmt ->
                stmt.bind(1, conversationId)
                stmt.step()
            }

            if (connection.changes == 0)
                throw InvalidConversationException(conversationId)

            val tableName = ConversationTable.getTablename(conversationId)
            connection.exec("UPDATE $tableName SET is_read=1 WHERE is_read=0")
        }

        unreadMessageIds
    }

    private fun getUnreadMessageIdsOrderedLimit(connection: SQLiteConnection, conversationId: ConversationId, limit: Int): List<String> {
        val tableName = ConversationTable.getTablename(conversationId)
        val sql = """
SELECT
    id
FROM
    $tableName
WHERE
    is_read = 0
ORDER BY
    timestamp DESC, n DESC
LIMIT
    $limit
"""
        return connection.withPrepared(sql) { stmt ->
            stmt.map { it.columnString(0) }
        }
    }

    private fun getUnreadMessageIds(connection: SQLiteConnection, conversationId: ConversationId): List<String> {
        val tableName = ConversationTable.getTablename(conversationId)
        val sql = """
SELECT
    id
FROM
    $tableName
WHERE
    is_read = 0
"""
        return connection.withPrepared(sql) { stmt ->
            stmt.map { it.columnString(0) }
        }
    }

    private fun markConversationMessagesAsRead(connection: SQLiteConnection, conversationId: ConversationId, messageIds: Collection<String>): List<String> {
        val tableName = ConversationTable.getTablename(conversationId)
        val sql = """
UPDATE
    $tableName
SET
    is_read=1
WHERE
    id=?
AND
    is_read=0
"""
        val r = ArrayList<String>()

        connection.withPrepared(sql) { stmt ->
            messageIds.forEach {
                stmt.bind(1, it)
                stmt.step()
                if (connection.changes > 0)
                    r.add(it)
                stmt.reset(true)
            }
        }

        return r
    }

    override fun markConversationMessagesAsRead(conversationId: ConversationId, messageIds: Collection<String>): Promise<List<String>, Exception> {
        if (messageIds.isEmpty())
            return Promise.ofSuccess(emptyList())

        return sqlitePersistenceManager.runQuery { connection ->
            try {
                connection.withTransaction {
                    val unreadMessageIds = markConversationMessagesAsRead(connection, conversationId, messageIds)

                    updateConversationInfo(connection, conversationId)

                    unreadMessageIds
                }
            }
            catch (e: SQLiteException) {
                if (isMissingConvTableError(e))
                    throw InvalidConversationException(conversationId)

                throw e
            }
        }
    }

    private fun getConversationMessageInfo(connection: SQLiteConnection, conversationId: ConversationId, messageId: String): ConversationMessageInfo? {
        val tableName = ConversationTable.getTablename(conversationId)
        val sql =
            """
SELECT
    id,
    speaker_contact_id,
    timestamp,
    received_timestamp,
    is_read,
    is_expired,
    ttl,
    expires_at,
    is_delivered,
    message,
    has_failures
FROM
    $tableName
WHERE
    id=?
"""
        return connection.withPrepared(sql) { stmt ->
            stmt.bind(1, messageId)
            if (stmt.step())
                rowToConversationMessageInfo(connection, stmt, conversationId)
            else
                null
        }
    }

    override fun getLastMessages(conversationId: ConversationId, startingAt: Int, count: Int): Promise<List<ConversationMessageInfo>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val tableName = ConversationTable.getTablename(conversationId)
        val sql =
            """
SELECT
    id,
    speaker_contact_id,
    timestamp,
    received_timestamp,
    is_read,
    is_expired,
    ttl,
    expires_at,
    is_delivered,
    message,
    has_failures
FROM
    $tableName
ORDER BY
    timestamp DESC, n DESC
LIMIT
    $count
OFFSET
    $startingAt
"""
        try {
            connection.withPrepared(sql) { stmt ->
                stmt.map{
                    rowToConversationMessageInfo(connection, stmt, conversationId)
                }
            }
        }
        catch (e: SQLiteException) {
            handleInvalidConversationException(e, conversationId)
        }
    }

    private fun queryMessageInfo(connection: SQLiteConnection, conversationId: ConversationId, messageId: String): ConversationMessageInfo? {
        val tableName = ConversationTable.getTablename(conversationId)

        val sql =
            """
SELECT
    id,
    speaker_contact_id,
    timestamp,
    received_timestamp,
    is_read,
    is_expired,
    ttl,
    expires_at,
    is_delivered,
    message,
    has_failures
FROM
    $tableName
WHERE
    id=?
ORDER BY
    timestamp DESC, n DESC
LIMIT
    1
"""
        return connection.withPrepared(sql) { stmt ->
            stmt.bind(1, messageId)

            if (!stmt.step())
                null
            else {
                rowToConversationMessageInfo(connection, stmt, conversationId)
            }
        }
    }

    override fun get(conversationId: ConversationId, messageId: String): Promise<ConversationMessageInfo?, Exception> = sqlitePersistenceManager.runQuery {
        queryMessageInfo(it, conversationId, messageId)
    }

    private fun updateMessageSetExpired(connection: SQLiteConnection, conversationId: ConversationId, messageIds: Collection<String>) {
        val tableName = ConversationTable.getTablename(conversationId)
        val updateSql = """
UPDATE
    $tableName
SET
    message="",
    is_expired=1,
    ttl=0,
    expires_at=0,
    is_read=1
WHERE
    id=?
"""
        connection.withPrepared(updateSql) { stmt ->
            messageIds.forEach { messageId ->
                stmt.bind(1, messageId)
                stmt.step()
                stmt.reset(false)
            }
        }
    }

    private fun deleteExpiringMessages(connection: SQLiteConnection, conversationId: ConversationId, messageIds: Collection<String>) {
        val deleteSql = """
DELETE FROM
    expiring_messages
WHERE
    conversation_id=?
AND
    message_id=?
"""

        connection.withPrepared(deleteSql) { stmt ->
            stmt.bind(1, conversationId)

            messageIds.forEach { messageId ->
                stmt.bind(2, messageId)
                stmt.step()
                stmt.reset(false)
            }
        }
    }

    override fun expireMessages(messages: Map<ConversationId, Collection<String>>): Promise<Unit, Exception> {
        if (messages.isEmpty())
            return Promise.ofSuccess(Unit)

        return sqlitePersistenceManager.runQuery { connection ->
            connection.withTransaction {
                for ((conversationId, messageIds) in messages) {
                    updateMessageSetExpired(connection, conversationId, messageIds)
                    deleteExpiringMessages(connection, conversationId, messageIds)
                    updateConversationInfo(connection, conversationId)
                }
            }
        }
    }

    private fun updateMessageSetExpiring(connection: SQLiteConnection, conversationId: ConversationId, messageId: String, expiresAt: Long) {
        val tableName = ConversationTable.getTablename(conversationId)

        val sql = """
UPDATE
    $tableName
SET
    expires_at=?
WHERE
    id=?
"""
        connection.withPrepared(sql) { stmt ->
            stmt.bind(1, expiresAt)
            stmt.bind(2, messageId)
            stmt.step()
            if (connection.changes <= 0)
                throw InvalidConversationMessageException(conversationId, messageId)
        }
    }

    private fun insertExpiringMessage(connection: SQLiteConnection, conversationId: ConversationId, messageId: String, expiresAt: Long) {
        val sql = """
INSERT INTO
    expiring_messages
    (conversation_id, message_id, expires_at)
VALUES
    (?, ?, ?)
"""
        connection.withPrepared(sql) { stmt ->
            stmt.bind(1, conversationId)
            stmt.bind(2, messageId)
            stmt.bind(3, expiresAt)
            stmt.step()
        }
    }

    override fun setExpiration(conversationId: ConversationId, messageId: String, expiresAt: Long): Promise<Boolean, Exception> {
        require(expiresAt > 0) { "expiresAt must be > 0, got $expiresAt" }

        return sqlitePersistenceManager.runQuery { connection ->
            try {
                connection.withTransaction {
                    insertExpiringMessage(connection, conversationId, messageId, expiresAt)
                    updateMessageSetExpiring(connection, conversationId, messageId, expiresAt)
                }

                true
            }
            catch (e: SQLiteException) {
                val message = e.message

                if (message == null)
                    throw e
                else {
                    if (e.baseErrorCode == SQLiteConstants.SQLITE_CONSTRAINT &&
                        message.contains("UNIQUE constraint failed: expiring_messages.conversation_id, expiring_messages.message_id".toRegex()))
                        false
                    else
                        throw e
                }
            }
        }
    }

    override fun getMessagesAwaitingExpiration(): Promise<List<ExpiringMessage>, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val sql = """
SELECT
    conversation_id,
    message_id,
    expires_at
FROM
    expiring_messages
"""

        connection.withPrepared(sql) { stmt ->
            stmt.map { rowToExpiringMessage(stmt) }
        }
    }

    private fun rowToExpiringMessage(stmt: SQLiteStatement): ExpiringMessage {
        return ExpiringMessage(
            stmt.columnConversationId(0),
            stmt.columnString(1),
            stmt.columnLong(2)
        )
    }

    private fun getGroupName(connection: SQLiteConnection, groupId: GroupId): String {
        val sql = """
SELECT
    name
FROM
    groups
WHERE
    id=?
"""
        return connection.withPrepared(sql) { stmt ->
            stmt.bind(1, groupId)
            if (!stmt.step())
                throw InvalidGroupException(groupId)

            stmt.columnString(0)
        }
    }

    private fun getUserName(connection: SQLiteConnection, userId: UserId): String {
        val sql = """
SELECT
    name
FROM
    contacts
WHERE
    id=?
"""
        return connection.withPrepared(sql) { stmt ->
            stmt.bind(1, userId)
            if (!stmt.step())
                throw InvalidContactException(userId)

            stmt.columnString(0)
        }
    }

    override fun getConversationDisplayInfo(conversationId: ConversationId): Promise<ConversationDisplayInfo, Exception> = sqlitePersistenceManager.runQuery { connection ->
        val conversationInfo = conversationInfoUtils.getConversationInfo(connection, conversationId) ?: throw InvalidConversationException(conversationId)

        val conversationName = when (conversationId) {
            is ConversationId.Group -> getGroupName(connection, conversationId.id)
            is ConversationId.User -> getUserName(connection, conversationId.id)
        }

        val speakerId = conversationInfo.lastSpeaker

        val lastMessageData = if (conversationInfo.lastTimestamp != null) {
            val speakerName = speakerId?.let { getUserName(connection, speakerId) }
            LastMessageData(speakerName, speakerId, conversationInfo.lastMessage, conversationInfo.lastTimestamp)
        }
        else
            null

        val lastMessageIds = getUnreadMessageIdsOrderedLimit(connection, conversationId, 10)

        ConversationDisplayInfo(conversationId, conversationName, conversationInfo.unreadMessageCount, lastMessageIds, lastMessageData)
    }

    private fun insertFailures(connection: SQLiteConnection, conversationId: ConversationId, messageId: String, failures: Map<UserId, MessageSendFailure>) {
        if (failures.isEmpty())
            return

        val sql = """
INSERT OR REPLACE INTO
    message_failures
    (conversation_id, message_id, contact_id, reason)
VALUES
    (?, ?, ?, ?)
"""

        connection.withPrepared(sql) { stmt ->
            for ((userId, reason) in failures) {
                stmt.bind(1, conversationId)
                stmt.bind(2, messageId)
                stmt.bind(3, userId)
                stmt.bind(4, objectMapper.writeValueAsBytes(reason))
                stmt.step()
                stmt.reset()
            }
        }
    }

    private fun updateMessageFailureState(connection: SQLiteConnection, conversationId: ConversationId, messageId: String) {
        connection.withPrepared("UPDATE ${ConversationTable.getTablename(conversationId)} SET has_failures=1 WHERE id=?") { stmt ->
            stmt.bind(1, messageId)
            stmt.step()
        }
    }

    override fun addFailures(conversationId: ConversationId, messageId: String, failures: Map<UserId, MessageSendFailure>): Promise<ConversationMessageInfo, Exception> = sqlitePersistenceManager.runQuery { connection ->
        try {
            if (!isMessageIdValid(connection, conversationId, messageId))
                throw InvalidConversationMessageException(conversationId, messageId)
        }
        catch (e: SQLiteException) {
            handleInvalidConversationException(e, conversationId)
        }

        connection.withTransaction {
            insertFailures(connection, conversationId, messageId, failures)
            updateMessageFailureState(connection, conversationId, messageId)
            queryMessageInfo(connection, conversationId, messageId)!!
        }
    }

    private fun queryFailures(connection: SQLiteConnection, conversationId: ConversationId, messageId: String): Map<UserId, MessageSendFailure> {
        val sql = """
SELECT
    contact_id,
    reason
FROM
    message_failures
WHERE
    conversation_id=?
AND
    message_id=?
"""

        return connection.withPrepared(sql) { stmt ->
            stmt.bind(1, conversationId)
            stmt.bind(2, messageId)
            val r = HashMap<UserId, MessageSendFailure>()

            stmt.foreach {
                val userId = UserId(stmt.columnLong(0))
                val reason = objectMapper.readValue(stmt.columnBlob(1), MessageSendFailure::class.java)
                r[userId] = reason
            }

            r
        }
    }

    private fun selectAttachments(connection: SQLiteConnection, conversationId: ConversationId, messageId: String): List<MessageAttachmentInfo> {
        //language=SQLite
        val sql = """
SELECT
    n,
    display_name,
    file_id,
    is_inline
FROM
    attachments
WHERE
    conversation_id = :conversationId
AND
    message_id = :messageId
ORDER BY
    n
"""
        return connection.withPrepared(sql) { stmt ->
            stmt.bind(":conversationId", conversationId)
            stmt.bind(":messageId", messageId)
            stmt.map {
                MessageAttachmentInfo(
                    stmt.columnInt(0),
                    stmt.columnString(1),
                    stmt.columnString(2),
                    stmt.columnBool(3)
                )
            }
        }
    }

    private fun rowToConversationMessageInfo(connection: SQLiteConnection, stmt: SQLiteStatement, conversationId: ConversationId): ConversationMessageInfo {
        val id = stmt.columnString(0)
        val attachments = selectAttachments(connection, conversationId, id)

        val speaker = stmt.columnNullableLong(1)?.let(::UserId)
        val timestamp = stmt.columnLong(2)
        val receivedTimestamp = stmt.columnLong(3)
        val isRead = stmt.columnBool(4)
        val isDestroyed = stmt.columnBool(5)
        val ttl = stmt.columnLong(6)
        val expiresAt = stmt.columnLong(7)
        val isDelivered = stmt.columnBool(8)
        val message = stmt.columnString(9)

        val hasFailures = stmt.columnBool(10)
        val failures = if (hasFailures)
            queryFailures(connection, conversationId, id)
        else
            emptyMap()

        return ConversationMessageInfo(
            speaker,
            MessageInfo(
                id,
                message,
                timestamp,
                receivedTimestamp,
                speaker == null,
                isDelivered,
                isRead,
                isDestroyed,
                ttl,
                expiresAt,
                attachments
            ),
            failures
        )
    }

    /* test use only */
    internal fun internalMessageExists(conversationId: ConversationId, messageId: String): Boolean = sqlitePersistenceManager.syncRunQuery { connection ->
        connection.withPrepared("SELECT 1 FROM ${ConversationTable.getTablename(conversationId)} WHERE id=?") { stmt ->
            stmt.bind(1, messageId)
            stmt.step()
        }
    }

    internal fun internalGetAllMessages(conversationId: ConversationId): List<ConversationMessageInfo> = sqlitePersistenceManager.syncRunQuery { connection ->
        val tableName = ConversationTable.getTablename(conversationId)
        val sql =
            """
SELECT
    id,
    speaker_contact_id,
    timestamp,
    received_timestamp,
    is_read,
    is_expired,
    ttl,
    expires_at,
    is_delivered,
    message,
    has_failures
FROM
    $tableName
ORDER BY
    timestamp, n
"""
        connection.withPrepared(sql) { stmt ->
            stmt.map {
                rowToConversationMessageInfo(connection, stmt, conversationId)
            }
        }
    }

    internal fun internalGetFailures(conversationId: ConversationId, messageId: String): Map<UserId, MessageSendFailure> = sqlitePersistenceManager.syncRunQuery {
        queryFailures(it, conversationId, messageId)
    }
}
