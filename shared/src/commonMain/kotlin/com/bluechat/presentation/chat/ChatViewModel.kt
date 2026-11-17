package com.bluechat.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.model.DeliveryStatus
import com.bluechat.domain.model.PeerMessage
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.domain.repository.MessageQueueRepository
import com.bluechat.domain.repository.PeerDirectory
import com.bluechat.util.SignalStrength
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    private val messageQueueRepository: MessageQueueRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())

    private var typingJob: Job? = null
    private var isCurrentlyTyping = false

    /** Starts as the transport address and switches to the peer id once it is announced. */
    private val conversationKey = peerDirectory.observeConversationKey(transportId)

    private val messages = conversationKey.flatMapLatest(chatRepository::getMessagesForDevice)

    val state = combine(
        combine(
            _uiState,
            messages,
            peerDirectory.observeDisplayName(transportId),
            bleController.connectionState,
            bleController.lastError,
        ) { ui, msgs, peerName, connState, error ->
            ui.copy(
                messages = msgs,
                deviceName = peerName,
                isConnected = connState == ConnectionState.Connected,
                connectionState = connState,
                errorMessage = error,
            )
        },
        conversationKey.flatMapLatest(messageQueueRepository::getPendingCount),
        bleController.reconnectAttempt,
        bleController.rssi,
    ) { base, pendingCount, reconnectAttempt, rssi ->
        base.copy(
            pendingMessageCount = pendingCount,
            reconnectAttempt = reconnectAttempt,
            signalStrength = SignalStrength.fromRssi(rssi),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    init {
        // NOTE: this screen deliberately does NOT subscribe to inbound chat messages.
        // IncomingMessageCoordinator owns the only such subscription, for the whole process,
        // because a per-screen collector drops anything that arrives while the screen is
        // closed -- the message is then lost permanently.
        //
        // Typing is the exception: it is transient UI state that is only meaningful while
        // someone is looking at the conversation, so it never needs to be persisted.
        bleController.incomingMessages
            .filterIsInstance<PeerMessage.Typing>()
            .onEach { typing ->
                _uiState.value = _uiState.value.copy(isRemoteTyping = typing.isTyping)
            }
            .launchIn(viewModelScope)

        bleController.connectionState
            .map { it == ConnectionState.Connected }
            .distinctUntilChanged()
            .onEach { isConnected ->
                if (isConnected) {
                    sendQueuedMessages()
                    acknowledgeUnreadMessages()
                }
            }
            .launchIn(viewModelScope)

        // Having the conversation on screen is what "read" means, so acknowledge whatever is
        // showing as soon as it arrives.
        messages
            .onEach { if (bleController.connectionState.value == ConnectionState.Connected) {
                acknowledgeUnreadMessages()
            } }
            .launchIn(viewModelScope)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            // The same id goes on the wire, so the peer can de-duplicate a re-delivery and
            // acknowledge this exact message with a read receipt.
            val messageId = Uuid.random().toString()
            val conversationId = conversationKey.first()
            val connected = bleController.connectionState.value == ConnectionState.Connected

            val message = ChatMessage(
                deviceId = conversationId,
                message = text,
                isFromLocalUser = true,
                timestamp = Clock.System.now().toEpochMilliseconds(),
                deliveryStatus = if (connected) DeliveryStatus.Sending else DeliveryStatus.Queued,
                messageUuid = messageId,
            )
            val id = chatRepository.insertMessage(message)

            if (!connected) {
                messageQueueRepository.queueMessage(conversationId, "$id:$messageId:$text")
                return@launch
            }

            try {
                bleController.send(messageId, text)
                chatRepository.updateMessageStatus(id, DeliveryStatus.Sent)
            } catch (_: Exception) {
                chatRepository.updateMessageStatus(id, DeliveryStatus.Failed)
            }
        }
    }

    /** Drains the outbox. Queue entries are `"chatMessageId:messageUuid:text"`. */
    private suspend fun sendQueuedMessages() {
        val conversationId = conversationKey.first()
        for (pending in messageQueueRepository.getMessagesToSend(conversationId)) {
            val parts = pending.message.split(":", limit = 3)
            val chatMessageId = parts.getOrNull(0)?.toLongOrNull()
            val messageId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: Uuid.random().toString()
            val body = parts.getOrNull(2) ?: pending.message

            try {
                chatMessageId?.let { chatRepository.updateMessageStatus(it, DeliveryStatus.Sending) }
                bleController.send(messageId, body)
                chatMessageId?.let { chatRepository.updateMessageStatus(it, DeliveryStatus.Sent) }
                messageQueueRepository.removeMessage(pending.id)
            } catch (_: Exception) {
                messageQueueRepository.incrementRetryCount(pending.id)
                chatMessageId?.let { chatRepository.updateMessageStatus(it, DeliveryStatus.Failed) }
            }
        }
    }

    /** Tells the peer we have seen everything of theirs that is on screen. */
    private suspend fun acknowledgeUnreadMessages() {
        val conversationId = conversationKey.first()
        for (msg in chatRepository.getUnreadMessages(conversationId)) {
            if (msg.messageUuid.isBlank()) continue
            runCatching {
                bleController.sendReadReceipt(msg.messageUuid)
                chatRepository.markMessageAsRead(
                    msg.id,
                    Clock.System.now().toEpochMilliseconds(),
                )
            }
        }
    }

    fun onTextChanged(text: String) {
        if (text.isNotBlank() && !isCurrentlyTyping) {
            setTyping(true)
        }
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(TYPING_TIMEOUT_MILLIS)
            setTyping(false)
        }
    }

    private fun setTyping(isTyping: Boolean) {
        if (isCurrentlyTyping == isTyping) return
        isCurrentlyTyping = isTyping
        viewModelScope.launch { runCatching { bleController.sendTypingIndicator(isTyping) } }
    }

    fun disconnect() {
        if (isCurrentlyTyping) setTyping(false)
        typingJob?.cancel()
        // Intentional: the user asked to leave, so do not auto-reconnect behind their back.
        viewModelScope.launch { bleController.disconnect() }
    }

    private companion object {
        const val TYPING_TIMEOUT_MILLIS = 3000L
    }
}
