package com.bluechat.presentation.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request from outside the UI to open a conversation, e.g. a tapped notification (E4).
 *
 * The platform posts the conversation id here. `App` returns to the chat list and opens the
 * conversation through the list's own click handling, so it reaches the same client or
 * server chat screen a tap in the list would (C2).
 *
 * Held as state rather than an event so that a request made before the UI exists (cold
 * start from a notification) waits until the chat list is ready.
 */
class ConversationDeepLinks {

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun open(conversationId: String) {
        _pending.value = conversationId
    }

    /** Marks [conversationId] as handled, unless a newer request has replaced it. */
    fun consume(conversationId: String) {
        _pending.compareAndSet(conversationId, null)
    }
}
