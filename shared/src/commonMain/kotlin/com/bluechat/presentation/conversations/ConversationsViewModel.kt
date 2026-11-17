package com.bluechat.presentation.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationsUiState())

    val state = combine(
        _state,
        conversationRepository.observeAll(),
        conversationRepository.observeTotalUnread(),
        server.connectedClients,
    ) { state, conversations, totalUnread, clients ->
        state.copy(
            conversations = conversations,
            totalUnread = totalUnread,
            connectedClientTransportIds = clients.map { it.identifier }.toSet(),
            isLoaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConversationsUiState())

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
}
