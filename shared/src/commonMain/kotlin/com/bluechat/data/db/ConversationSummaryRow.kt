package com.bluechat.data.db

/**
 * One row of [ConversationDao.observeSummaries]: a conversation joined to the contact it is
 * with and to its own flags, with the message-derived fields computed.
 *
 * Nullable contact columns are the normal case, not an error -- the `broadcast` thread is with
 * nobody, and a conversation can exist with a peer that has not yet announced an identity.
 */
data class ConversationSummaryRow(
    val conversationId: String,
    val lastMessageAt: Long,
    val preview: String?,
    val previewIsFromLocalUser: Boolean,
    val unreadCount: Int,
    val contactId: String?,
    val announcedName: String?,
    val nickname: String?,
    val peerTransportId: String?,
    val isBlocked: Boolean?,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isArchived: Boolean,
)
