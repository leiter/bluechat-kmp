package com.bluechat.domain.notification

/**
 * Shows incoming messages as system notifications, **one per conversation** (E4).
 *
 * Only [com.bluechat.domain.messaging.IncomingMessageCoordinator] calls this. It decides
 * *whether* to notify (not muted, not blocked, not on screen); implementations only decide
 * *how*.
 */
interface MessageNotifier {

    /**
     * Adds a message to [conversationId]'s notification, creating it if there is none.
     *
     * A second message in the same conversation updates that notification instead of adding
     * another one, and earlier messages stay listed in it.
     */
    fun showMessage(conversationId: String, senderName: String, text: String, timestamp: Long)

    /** Removes [conversationId]'s notification, if one is showing. */
    fun cancel(conversationId: String)
}
