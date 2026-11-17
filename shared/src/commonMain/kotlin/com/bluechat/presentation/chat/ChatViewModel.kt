package com.bluechat.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.model.DeliveryStatus
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.domain.repository.PeerDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * @param transportId how the peer is currently reachable (Bluetooth address / CoreBluetooth
 *   UUID). The conversation itself is keyed on the peer's stable identity, which is resolved
 *   from this and may only become known once the peer announces itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val transportId: String,
    private val bleController: BleController,
    private val chatRepository: ChatRepository,
    private val peerDirectory: PeerDirectory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    /** Starts as the transport address and switches to the peer id once it is announced. */
    private val conversationKey = peerDirectory.observeConversationKey(transportId)

    val state = combine(
        _uiState,
        conversationKey.flatMapLatest(chatRepository::getMessagesForDevice),
        peerDirectory.observeDisplayName(transportId),
        bleController.connectionState,
        bleController.lastError,
    ) { ui, messages, peerName, connState, error ->
        ui.copy(
            messages = messages,
            deviceName = peerName,
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
            // The same id goes on the wire, so the peer can de-duplicate a re-delivery and
            // later acknowledge this exact message.
            val messageId = Uuid.random().toString()
            val message = ChatMessage(
                deviceId = conversationKey.first(),
                message = text,
                isFromLocalUser = true,
                timestamp = Clock.System.now().toEpochMilliseconds(),
                deliveryStatus = DeliveryStatus.Sending,
            )
            val id = chatRepository.insertMessage(message)
            try {
                bleController.send(messageId, text)
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
