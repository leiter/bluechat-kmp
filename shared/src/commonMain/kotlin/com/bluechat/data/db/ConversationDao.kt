package com.bluechat.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    /**
     * The chat list, driven by the messages rather than by a conversations table.
     *
     * A conversation exists because it has messages in it -- there is no separate create step
     * to forget, and no way for the list to disagree with the history it summarises. The
     * `conversations` row is LEFT JOINed because it only exists once a flag has been set, and
     * `contacts` is LEFT JOINed because the `broadcast` thread has no contact and a peer may
     * not have announced an identity yet.
     *
     * `readAt` carries two meanings in `chat_messages` depending on direction: on an outbound
     * message it is when the *peer* read it (a read receipt), on an inbound one it is when *we*
     * did. The `isFromLocalUser = 0` guard is what keeps the unread count on the inbound side
     * and stops our own delivered messages counting as unread to us.
     *
     * Pinned first, then most recent. Archived conversations are included here and filtered
     * above, so one query serves both the main list and an archive view.
     */
    @Query(
        """
        SELECT
            m.deviceId AS conversationId,
            MAX(m.timestamp) AS lastMessageAt,
            (
                SELECT message FROM chat_messages
                WHERE deviceId = m.deviceId ORDER BY timestamp DESC, id DESC LIMIT 1
            ) AS preview,
            (
                SELECT isFromLocalUser FROM chat_messages
                WHERE deviceId = m.deviceId ORDER BY timestamp DESC, id DESC LIMIT 1
            ) AS previewIsFromLocalUser,
            SUM(CASE WHEN m.isFromLocalUser = 0 AND m.readAt IS NULL THEN 1 ELSE 0 END) AS unreadCount,
            c.contactId AS contactId,
            c.announcedName AS announcedName,
            c.nickname AS nickname,
            c.lastTransportId AS peerTransportId,
            c.isBlocked AS isBlocked,
            COALESCE(v.isPinned, 0) AS isPinned,
            COALESCE(v.isMuted, 0) AS isMuted,
            COALESCE(v.isArchived, 0) AS isArchived
        FROM chat_messages m
        LEFT JOIN contacts c ON c.contactId = m.deviceId
        LEFT JOIN conversations v ON v.conversationId = m.deviceId
        GROUP BY m.deviceId
        ORDER BY isPinned DESC, lastMessageAt DESC
        """
    )
    fun observeSummaries(): Flow<List<ConversationSummaryRow>>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE isFromLocalUser = 0 AND readAt IS NULL")
    fun observeTotalUnread(): Flow<Int>

    /**
     * Marks every inbound message in a conversation as read.
     *
     * One statement rather than a loop, because this runs when a screen becomes visible and
     * there may be a backlog. It does not send read receipts: the caller owns that, since only
     * it knows which transport the peer is on.
     */
    @Query(
        """
        UPDATE chat_messages SET readAt = :readAt
        WHERE deviceId = :conversationId AND isFromLocalUser = 0 AND readAt IS NULL
        """
    )
    suspend fun markConversationRead(conversationId: String, readAt: Long)

    // The flag writes below each create the row if it is missing, so callers never have to
    // know whether this conversation has been given one yet.

    @Query(
        """
        INSERT INTO conversations (conversationId, isPinned, isMuted, isArchived)
        VALUES (:conversationId, :pinned, 0, 0)
        ON CONFLICT(conversationId) DO UPDATE SET isPinned = :pinned
        """
    )
    suspend fun setPinned(conversationId: String, pinned: Boolean)

    @Query(
        """
        INSERT INTO conversations (conversationId, isPinned, isMuted, isArchived)
        VALUES (:conversationId, 0, :muted, 0)
        ON CONFLICT(conversationId) DO UPDATE SET isMuted = :muted
        """
    )
    suspend fun setMuted(conversationId: String, muted: Boolean)

    @Query(
        """
        INSERT INTO conversations (conversationId, isPinned, isMuted, isArchived)
        VALUES (:conversationId, 0, 0, :archived)
        ON CONFLICT(conversationId) DO UPDATE SET isArchived = :archived
        """
    )
    suspend fun setArchived(conversationId: String, archived: Boolean)

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId LIMIT 1")
    suspend fun find(conversationId: String): ConversationEntity?

    @Query("DELETE FROM conversations WHERE conversationId = :conversationId")
    suspend fun deleteFlags(conversationId: String)

    @Query("DELETE FROM chat_messages WHERE deviceId = :conversationId")
    suspend fun deleteMessages(conversationId: String)

    @Query("DELETE FROM pending_messages WHERE deviceId = :conversationId")
    suspend fun deletePending(conversationId: String)

    /**
     * Removes a conversation completely: its messages, anything still queued to send, and its
     * flags.
     *
     * One transaction, because a half-deleted conversation is worse than either outcome -- the
     * list is derived from the messages, so deleting those but not the outbox would leave a
     * conversation that has vanished from the UI while still trying to transmit.
     *
     * The *contact* is deliberately untouched, mirroring B3's delete, which keeps the messages.
     * Each one removes only what the user pointed at.
     */
    @Transaction
    suspend fun deleteConversation(conversationId: String) {
        deleteMessages(conversationId)
        deletePending(conversationId)
        deleteFlags(conversationId)
    }
}
