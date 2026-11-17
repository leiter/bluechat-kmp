package com.bluechat.data.repository

import com.bluechat.data.db.ConversationDao
import com.bluechat.data.db.ConversationSummaryRow
import com.bluechat.domain.model.Contact
import com.bluechat.domain.model.Conversation
import com.bluechat.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

class ConversationRepositoryImpl(
    private val dao: ConversationDao,
) : ConversationRepository {

    override fun observeAll(): Flow<List<Conversation>> =
        dao.observeSummaries().map { rows -> rows.map(ConversationSummaryRow::toDomain) }

    override fun observeTotalUnread(): Flow<Int> = dao.observeTotalUnread()

    override suspend fun markRead(conversationId: String) {
        dao.markConversationRead(conversationId, Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun setPinned(conversationId: String, pinned: Boolean) {
        dao.setPinned(conversationId, pinned)
    }

    override suspend fun setMuted(conversationId: String, muted: Boolean) {
        dao.setMuted(conversationId, muted)
    }

    override suspend fun setArchived(conversationId: String, archived: Boolean) {
        dao.setArchived(conversationId, archived)
    }

    override suspend fun delete(conversationId: String) {
        dao.deleteConversation(conversationId)
    }
}

private fun ConversationSummaryRow.toDomain(): Conversation {
    val isGroup = conversationId == Conversation.BROADCAST_ID
    return Conversation(
        conversationId = conversationId,
        title = when {
            isGroup -> Conversation.BROADCAST_TITLE
            // Reuses Contact's resolution rule rather than repeating it, so a local nickname
            // wins here exactly as it does everywhere else.
            contactId != null -> Contact(
                contactId = contactId,
                announcedName = announcedName,
                nickname = nickname,
                lastTransportId = "",
                firstSeenAt = 0L,
                lastSeenAt = 0L,
            ).displayName
            else -> Conversation.fallbackTitle(conversationId)
        },
        lastMessageAt = lastMessageAt,
        preview = preview,
        previewIsFromLocalUser = previewIsFromLocalUser,
        unreadCount = unreadCount,
        isGroup = isGroup,
        isKnownContact = contactId != null,
        peerTransportId = peerTransportId,
        isBlocked = isBlocked == true,
        isPinned = isPinned,
        isMuted = isMuted,
        isArchived = isArchived,
    )
}
