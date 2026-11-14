package com.bluechat.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.model.DeliveryStatus
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

class ChatViewModel(
    private val deviceId: String,
    private val bleController: BleController,
    private val chatRepository: ChatRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    val state = combine(
        _uiState,
        chatRepository.getMessagesForDevice(deviceId),
        bleController.connectionState,
        bleController.lastError,
    ) { ui, messages, connState, error ->
        ui.copy(
            messages = messages,
            isConnected = connState == ConnectionState.Connected,
            connectionState = connState,
            errorMessage = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    // Incoming messages are persisted by IncomingMessageCoordinator for the whole process,
    // so this screen only has to read them back out of the database.

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val message = ChatMessage(
                deviceId = deviceId,
                message = text,
                isFromLocalUser = true,
                timestamp = Clock.System.now().toEpochMilliseconds(),
                deliveryStatus = DeliveryStatus.Sending,
            )
            val id = chatRepository.insertMessage(message)
            try {
                bleController.send(text)
                chatRepository.updateMessageStatus(id, DeliveryStatus.Sent)
            } catch (_: Exception) {
                chatRepository.updateMessageStatus(id, DeliveryStatus.Failed)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            bleController.disconnect()
        }
    }
}
