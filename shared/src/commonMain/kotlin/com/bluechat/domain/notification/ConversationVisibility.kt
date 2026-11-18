package com.bluechat.domain.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which conversation the user is looking at right now, if any (E4).
 *
 * Used for two things: a message in the visible conversation raises no notification, and
 * opening a conversation clears its notification.
 *
 * "Visible" means the chat screen is *resumed*, not merely that its ViewModel exists. A
 * ViewModel outlives a backgrounded app, and a message arriving then must still notify.
 */
class ConversationVisibility {

    private val _visible = MutableStateFlow<String?>(null)
    val visible: StateFlow<String?> = _visible.asStateFlow()

    fun show(conversationId: String) {
        _visible.value = conversationId
    }

    /** Clears [conversationId], but only if it is still the visible one. */
    fun hide(conversationId: String) {
        _visible.compareAndSet(conversationId, null)
    }

    /**
     * Marks the conversation behind [keys] visible until the returned job is cancelled.
     *
     * The key is a flow because a chat screen starts on the peer's transport address and
     * switches to its stable id once the peer announces itself (A1). Visibility follows it.
     */
    fun track(keys: Flow<String>, scope: CoroutineScope): Job = scope.launch {
        var current: String? = null
        try {
            keys.collect { key ->
                current?.takeIf { it != key }?.let(::hide)
                current = key
                show(key)
            }
            // The key flow ending does not mean the screen closed: stay visible until the
            // caller cancels.
            awaitCancellation()
        } finally {
            current?.let(::hide)
        }
    }
}
