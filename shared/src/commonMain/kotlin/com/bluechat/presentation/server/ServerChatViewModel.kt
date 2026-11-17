package com.bluechat.presentation.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.DeliveryStatus
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.domain.repository.ConversationRepository
import com.bluechat.domain.repository.PeerDirectory
import com.bluechat.presentation.chat.ChatUiState
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
 * @param transportId the client's Bluetooth address. As with [com.bluechat.presentation.chat.ChatViewModel],
 *   the conversation is keyed on the peer's stable identity once it announces itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerChatViewModel(
    private val transportId: String,
    private val server: BlePeripheralServer,
    private val chatRepository: ChatRepository,
    private val conversationRepository: ConversationRepository,
    private val peerDirectory: PeerDirectory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    private val conversationKey = peerDirectory.observeConversationKey(transportId)

    val state = combine(
        _uiState,
        conversationKey.flatMapLatest(chatRepository::getMessagesForDevice),
        peerDirectory.observeDisplayName(transportId),
    ) { ui, messages, peerName ->
        ui.copy(
            messages = messages,
            deviceName = peerName,
            isConnected = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    init {
        // Clears the unread badge for this conversation while it is open.
        //
        // Local only: the server still sends no read receipts, so the peer is not told. That
        // gap predates C3 and is tracked separately -- but without this the badge introduced
        // by C1 would stick on the server forever, since nothing else ever set `readAt` on an
        // inbound message here.
        //
        // Must stay *below* `conversationKey`: an init block that reads a property declared
        // later compiles fine but dereferences null if the coroutine ever starts eagerly.
        viewModelScope.launch {
            conversationRepository.markRead(conversationKey.first())
        }
    }

    // Incoming messages are persisted by IncomingMessageCoordinator for the whole process,
    // so this screen only has to read them back out of the database.

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val messageId = Uuid.random().toString()
            val message = ChatMessage(
                deviceId = conversationKey.first(),
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
                server.sendTo(transportId, messageId, text)
                chatRepository.updateMessageStatus(id, DeliveryStatus.Sent)
            } catch (_: Exception) {
                chatRepository.updateMessageStatus(id, DeliveryStatus.Failed)
            }
        }
    }

    fun disconnect() {
        // Server doesn't disconnect individual clients; just navigate back
    }
}
