package com.bluechat.presentation.conversations

import com.bluechat.domain.model.Conversation

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    /**
     * Transport addresses currently connected to our GATT server.
     *
     * A conversation whose peer is in here is one *they* dialled, so replying has to go out
     * through the peripheral role. Everything else uses the central role. The roles are
     * distinct by decision (E3), so the chat list has to choose, and it cannot tell from the
     * conversation id alone.
     */
    val connectedClientTransportIds: Set<String> = emptySet(),
    val totalUnread: Int = 0,
    val showArchived: Boolean = false,
    /** True only once the first database read has arrived, so "empty" is not shown while loading. */
    val isLoaded: Boolean = false,
) {
    val archivedCount: Int get() = conversations.count { it.isArchived }

    /** What the list shows: the archive, or everything else. */
    val visibleConversations: List<Conversation>
        get() = conversations.filter { it.isArchived == showArchived }

    val isEmpty: Boolean get() = isLoaded && visibleConversations.isEmpty()

    /** True when this conversation must be opened on the server's chat screen, not the client's. */
    fun isServerSide(conversation: Conversation): Boolean =
        conversation.peerTransportId != null &&
            conversation.peerTransportId in connectedClientTransportIds
}
