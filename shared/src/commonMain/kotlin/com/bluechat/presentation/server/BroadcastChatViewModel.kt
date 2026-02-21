package com.bluechat.presentation.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.DeliveryStatus
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.presentation.chat.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

class BroadcastChatViewModel(
    private val server: BlePeripheralServer,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    val state = combine(
        _uiState,
        chatRepository.getMessagesForDevice(BROADCAST_DEVICE_ID),
    ) { ui, messages ->
        ui.copy(
            messages = messages,
            isConnected = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    init {
        server.incomingMessages
            .onEach { (_, data) ->
                val text = data.decodeToString()
                val message = ChatMessage(
                    deviceId = BROADCAST_DEVICE_ID,
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
            val message = ChatMessage(
                deviceId = BROADCAST_DEVICE_ID,
                message = text,
                isFromLocalUser = true,
                timestamp = Clock.System.now().toEpochMilliseconds(),
                deliveryStatus = DeliveryStatus.Sending,
            )
            val id = chatRepository.insertMessage(message)
            try {
                server.sendToAll(text)
                chatRepository.updateMessageStatus(id, DeliveryStatus.Sent)
            } catch (_: Exception) {
                chatRepository.updateMessageStatus(id, DeliveryStatus.Failed)
            }
        }
    }

    fun disconnect() { /* Navigate back */ }

    companion object {
        const val BROADCAST_DEVICE_ID = "broadcast"
    }
}
