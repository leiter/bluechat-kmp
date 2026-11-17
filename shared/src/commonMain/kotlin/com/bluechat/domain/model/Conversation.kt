package com.bluechat.domain.model

/**
 * One row of the chat list.
 *
 * [lastMessageAt], [preview] and [unreadCount] are computed from the messages on every read
 * rather than stored, so this can never disagree with the history it summarises.
 */
data class Conversation(
    val conversationId: String,
    val title: String,
    val lastMessageAt: Long,
    val preview: String?,
    val previewIsFromLocalUser: Boolean,
    val unreadCount: Int,
    val isGroup: Boolean,
    val isKnownContact: Boolean,
    /**
     * Where this peer was last reachable, or `null` for the group thread / an unknown peer.
     *
     * Carried so the chat list can tell which BLE role owns the conversation. The roles are
     * asymmetric by decision (E3), and a conversation with a client of our GATT server has to
     * open the server's chat screen -- opening the client one would try to reply down a
     * central-role connection that does not exist.
     */
    val peerTransportId: String?,
    val isBlocked: Boolean,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isArchived: Boolean,
) {
    val hasUnread: Boolean get() = unreadCount > 0

    companion object {
        /** Conversation id of Server Mode's group thread. Mirrors `IncomingMessageCoordinator`. */
        const val BROADCAST_ID = "broadcast"
        const val BROADCAST_TITLE = "Group Chat"

        /**
         * Title for a conversation whose peer never announced an identity.
         *
         * The id is a raw Bluetooth address in that case, which is worth showing rather than
         * hiding: it is the honest answer to "who is this", and it is how a conversation
         * started before A1 still looks.
         */
        fun fallbackTitle(conversationId: String): String = conversationId
    }
}
