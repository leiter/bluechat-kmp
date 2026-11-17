package com.bluechat.presentation.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.messaging.IncomingMessageCoordinator
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.DeliveryStatus
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.presentation.chat.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

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

    // Incoming messages are mirrored into this thread by IncomingMessageCoordinator, so this
    // screen only has to read them back out of the database.

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val messageId = Uuid.random().toString()
            val message = ChatMessage(
                deviceId = BROADCAST_DEVICE_ID,
                message = text,
                isFromLocalUser = true,
                timestamp = Clock.System.now().toEpochMilliseconds(),
                deliveryStatus = DeliveryStatus.Sending,
                // Must match the id put on the wire, or the peer's read receipt has no row
                // to update and is silently dropped.
                messageUuid = messageId,
            )
            val id = chatRepository.insertMessage(message)
            try {
                server.sendToAll(messageId, text)
                chatRepository.updateMessageStatus(id, DeliveryStatus.Sent)
            } catch (_: Exception) {
                chatRepository.updateMessageStatus(id, DeliveryStatus.Failed)
            }
        }
    }

    fun disconnect() { /* Navigate back */ }

    companion object {
        const val BROADCAST_DEVICE_ID = IncomingMessageCoordinator.BROADCAST_DEVICE_ID
    }
}
