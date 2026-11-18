package com.bluechat.presentation.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.DeliveryStatus
import com.bluechat.domain.notification.ConversationVisibility
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.domain.repository.PeerDirectory
import com.bluechat.presentation.chat.ChatUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
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
    private val peerDirectory: PeerDirectory,
    private val conversationVisibility: ConversationVisibility,
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

    private var visibilityJob: Job? = null

    /**
     * Called by the screen when it is resumed (true) or paused (false).
     *
     * While visible: no notifications for this conversation (E4), and everything the client
     * sent that is on screen is marked read and acknowledged with a read receipt (C3), both
     * when the screen opens and as new messages arrive. Paused, nothing is acknowledged:
     * "read" means someone actually had it on screen.
     */
    fun onVisibilityChanged(visible: Boolean) {
        visibilityJob?.cancel()
        visibilityJob = if (visible) {
            viewModelScope.launch {
                conversationVisibility.track(conversationKey, this)
                conversationKey
                    .flatMapLatest(chatRepository::getMessagesForDevice)
                    .onEach { acknowledgeUnread() }
                    .launchIn(this)
            }
        } else {
            null
        }
    }

    /**
     * Marks every unread inbound message read and sends the client a receipt for each.
     *
     * Marked read even when the receipt cannot go out (client gone), so the unread badge
     * does not stick. In practice this screen is only reachable for a connected client. A
     * receipt lost that way leaves the client's message at Sent instead of Read.
     */
    private suspend fun acknowledgeUnread() {
        val conversationId = conversationKey.first()
        for (message in chatRepository.getUnreadMessages(conversationId)) {
            if (message.messageUuid.isNotBlank()) {
                runCatching { server.sendReadReceipt(transportId, message.messageUuid) }
            }
            chatRepository.markMessageAsRead(message.id, Clock.System.now().toEpochMilliseconds())
        }
    }

    fun disconnect() {
        // Server doesn't disconnect individual clients; just navigate back
    }
}
