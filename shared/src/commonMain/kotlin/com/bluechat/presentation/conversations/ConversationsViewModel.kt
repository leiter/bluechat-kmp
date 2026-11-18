package com.bluechat.presentation.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.presence.PresenceTracker
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ConversationsViewModel(
    private val conversationRepository: ConversationRepository,
    server: BlePeripheralServer,
    presenceTracker: PresenceTracker,
    private val bleController: BleController,
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationsUiState())

    val state = combine(
        _state,
        conversationRepository.observeAll(),
        conversationRepository.observeTotalUnread(),
        server.connectedClients,
        presenceTracker.observe(),
    ) { state, conversations, totalUnread, clients, presence ->
        state.copy(
            presence = presence,
            conversations = conversations,
            totalUnread = totalUnread,
            connectedClientTransportIds = clients.map { it.identifier }.toSet(),
            isLoaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConversationsUiState())

    /**
     * The screen is resumed (true) or paused (false). While visible, it holds the scan so
     * presence (B5) shows who is nearby now; paused, it releases it, so nothing scans in the
     * background on its behalf.
     */
    fun onVisibilityChanged(visible: Boolean) {
        bleController.setScanHold(SCAN_HOLDER, visible)
    }

    override fun onCleared() {
        // Only this screen's own hold. The controller itself is a singleton and stays alive.
        bleController.setScanHold(SCAN_HOLDER, false)
    }

    fun toggleShowArchived() {
        _state.update { it.copy(showArchived = !it.showArchived) }
    }

    fun togglePinned(conversationId: String, pinned: Boolean) {
        viewModelScope.launch { conversationRepository.setPinned(conversationId, pinned) }
    }

    fun toggleMuted(conversationId: String, muted: Boolean) {
        viewModelScope.launch { conversationRepository.setMuted(conversationId, muted) }
    }

    fun setArchived(conversationId: String, archived: Boolean) {
        viewModelScope.launch { conversationRepository.setArchived(conversationId, archived) }
    }

    /** Deletes the conversation and its history. The contact, if any, is kept. */
    fun delete(conversationId: String) {
        viewModelScope.launch { conversationRepository.delete(conversationId) }
    }

    private companion object {
        const val SCAN_HOLDER = PresenceTracker.SCAN_HOLDER_PREFIX + "conversations"
    }
}
