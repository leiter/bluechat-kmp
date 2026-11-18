package com.bluechat.domain.repository

import com.bluechat.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {

    /** Every conversation, archived ones included. Pinned first, then most recent. */
    fun observeAll(): Flow<List<Conversation>>

    /** Unread messages across all conversations, for an app-level badge. */
    fun observeTotalUnread(): Flow<Int>

    /**
     * Marks everything inbound in a conversation as read locally.
     *
     * Does not send read receipts -- the caller owns that, because only it knows the transport.
     */
    suspend fun markRead(conversationId: String)

    suspend fun setPinned(conversationId: String, pinned: Boolean)

    suspend fun setMuted(conversationId: String, muted: Boolean)

    /** Whether [conversationId] is muted. A muted conversation raises no notification (E4). */
    suspend fun isMuted(conversationId: String): Boolean

    suspend fun setArchived(conversationId: String, archived: Boolean)

    /**
     * Deletes a conversation: its messages, anything still queued to send, and its flags.
     *
     * The contact is left alone. Because the chat list is derived from the messages, this also
     * removes the conversation from the list -- there is no separate "clear history" that
     * leaves an empty conversation behind, since an empty conversation does not exist.
     */
    suspend fun delete(conversationId: String)
}
