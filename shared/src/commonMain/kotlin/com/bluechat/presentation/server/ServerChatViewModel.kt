package com.bluechat.presentation.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.presentation.chat.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

class ServerChatViewModel(
    private val clientId: String,
    private val server: BlePeripheralServer,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    val state = combine(
        _uiState,
        chatRepository.getMessagesForDevice(clientId),
    ) { ui, messages ->
        ui.copy(
            messages = messages,
            isConnected = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    init {
        server.incomingMessages
            .filter { (senderId, _) -> senderId == clientId }
            .onEach { (_, data) ->
                val text = data.decodeToString()
                val message = ChatMessage(
                    deviceId = clientId,
                    message = text,
                    isFromLocalUser = false,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                )
                chatRepository.insertMessage(message)
            }
            .launchIn(viewModelScope)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            server.sendTo(clientId, text)
            val message = ChatMessage(
                deviceId = clientId,
                message = text,
                isFromLocalUser = true,
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )
            chatRepository.insertMessage(message)
        }
    }

    fun disconnect() {
        // Server doesn't disconnect individual clients; just navigate back
    }
}
