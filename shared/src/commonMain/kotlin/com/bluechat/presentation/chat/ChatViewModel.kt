package com.bluechat.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluechat.data.ble.BleMessage
import com.bluechat.data.ble.BleMessageProtocol
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.model.DeliveryStatus
import com.bluechat.domain.notification.MessageNotifier
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.ChatRepository
import com.bluechat.domain.repository.MessageQueueRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import com.bluechat.util.SignalStrength

class ChatViewModel(
    private val deviceId: String,
    private val bleController: BleController,
    private val chatRepository: ChatRepository,
    private val messageNotifier: MessageNotifier,
    private val messageQueueRepository: MessageQueueRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    private var typingJob: Job? = null
    private var isCurrentlyTyping = false
    private val typingTimeoutMs = 3000L

    val state = combine(
        _uiState,
        chatRepository.getMessagesForDevice(deviceId),
        bleController.connectionState,
        messageQueueRepository.getPendingCount(deviceId),
        bleController.reconnectAttempt,
        bleController.rssi,
    ) { flows ->
        val ui = flows[0] as ChatUiState
        @Suppress("UNCHECKED_CAST")
        val messages = flows[1] as List<ChatMessage>
        val connState = flows[2] as ConnectionState
        val pendingCount = flows[3] as Int
        val reconnectAttempt = flows[4] as Int
        val rssi = flows[5] as Int?
        ui.copy(
            messages = messages,
            isConnected = connState == ConnectionState.Connected,
            connectionState = connState,
            pendingMessageCount = pendingCount,
            reconnectAttempt = reconnectAttempt,
            signalStrength = SignalStrength.fromRssi(rssi),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    init {
        // Handle incoming messages using new protocol
        bleController.incomingMessages
            .onEach { data ->
                handleIncomingData(data)
            }
            .launchIn(viewModelScope)

        // Auto-send queued messages when connected
        bleController.connectionState
            .map { it == ConnectionState.Connected }
            .distinctUntilChanged()
            .onEach { isConnected ->
                if (isConnected) {
                    sendQueuedMessages()
                    // Send read receipts for any unread messages we received while disconnected
                    sendPendingReadReceipts()
                }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun handleIncomingData(data: ByteArray) {
        when (val message = BleMessageProtocol.decode(data)) {
            is BleMessage.ChatMessage -> {
                // New protocol chat message with UUID
                val chatMessage = ChatMessage(
                    deviceId = deviceId,
                    message = message.content,
                    isFromLocalUser = false,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                    messageUuid = message.messageUuid,
                )
                chatRepository.insertMessage(chatMessage)
                messageNotifier.showMessageNotification("Remote", message.content)

                // Send read receipt back immediately
                try {
                    bleController.sendReadReceipt(message.messageUuid)
                } catch (_: Exception) {
                    // Will retry later
                }
            }

            is BleMessage.LegacyChatMessage -> {
                // Legacy message without UUID (for backwards compatibility)
                val chatMessage = ChatMessage(
                    deviceId = deviceId,
                    message = message.content,
                    isFromLocalUser = false,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                )
                chatRepository.insertMessage(chatMessage)
                messageNotifier.showMessageNotification("Remote", message.content)
            }

            is BleMessage.ReadReceipt -> {
                // Update message status to Read
                val readAt = Clock.System.now().toEpochMilliseconds()
                chatRepository.updateMessageStatusByUuid(message.messageUuid, DeliveryStatus.Read, readAt)
            }

            is BleMessage.TypingIndicator -> {
                _uiState.value = _uiState.value.copy(isRemoteTyping = message.isTyping)
            }

            is BleMessage.Unknown -> {
                // Try to treat as plain text (backwards compatibility)
                val text = data.decodeToString()
                val chatMessage = ChatMessage(
                    deviceId = deviceId,
                    message = text,
                    isFromLocalUser = false,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                )
                chatRepository.insertMessage(chatMessage)
                messageNotifier.showMessageNotification("Remote", text)
            }
        }
    }

    private suspend fun sendPendingReadReceipts() {
        // Send read receipts for messages we received but couldn't acknowledge
        val unreadMessages = chatRepository.getUnreadMessages(deviceId)
        for (msg in unreadMessages) {
            if (msg.messageUuid.isNotBlank()) {
                try {
                    bleController.sendReadReceipt(msg.messageUuid)
                    chatRepository.markMessageAsRead(msg.id, Clock.System.now().toEpochMilliseconds())
                } catch (_: Exception) {
                    // Will retry later
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val isConnected = bleController.connectionState.value == ConnectionState.Connected
            val messageUuid = BleMessageProtocol.generateMessageUuid()

            if (isConnected) {
                // Send immediately using new protocol
                val message = ChatMessage(
                    deviceId = deviceId,
                    message = text,
                    isFromLocalUser = true,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                    deliveryStatus = DeliveryStatus.Sending,
                    messageUuid = messageUuid,
                )
                val id = chatRepository.insertMessage(message)
                try {
                    val data = BleMessageProtocol.encodeChatMessage(messageUuid, text)
                    bleController.sendRaw(data)
                    chatRepository.updateMessageStatus(id, DeliveryStatus.Sent)
                } catch (_: Exception) {
                    chatRepository.updateMessageStatus(id, DeliveryStatus.Failed)
                }
            } else {
                // Queue for later
                val message = ChatMessage(
                    deviceId = deviceId,
                    message = text,
                    isFromLocalUser = true,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                    deliveryStatus = DeliveryStatus.Queued,
                    messageUuid = messageUuid,
                )
                val chatMessageId = chatRepository.insertMessage(message)
                messageQueueRepository.queueMessage(deviceId, "$chatMessageId:$messageUuid:$text")
            }
        }
    }

    private suspend fun sendQueuedMessages() {
        val pendingMessages = messageQueueRepository.getMessagesToSend(deviceId)
        for (pending in pendingMessages) {
            try {
                // Parse the message format "chatMessageId:messageUuid:actualMessage"
                val parts = pending.message.split(":", limit = 3)
                val chatMessageId = parts.getOrNull(0)?.toLongOrNull()
                val messageUuid = parts.getOrNull(1) ?: BleMessageProtocol.generateMessageUuid()
                val actualMessage = parts.getOrNull(2) ?: pending.message

                // Update status to Sending
                if (chatMessageId != null) {
                    chatRepository.updateMessageStatus(chatMessageId, DeliveryStatus.Sending)
                }

                // Send the message with protocol encoding
                val data = BleMessageProtocol.encodeChatMessage(messageUuid, actualMessage)
                bleController.sendRaw(data)

                // Update status to Sent and remove from queue
                if (chatMessageId != null) {
                    chatRepository.updateMessageStatus(chatMessageId, DeliveryStatus.Sent)
                }
                messageQueueRepository.removeMessage(pending.id)
            } catch (_: Exception) {
                // Keep in queue for retry, increment retry count
                messageQueueRepository.incrementRetryCount(pending.id)
                val parts = pending.message.split(":", limit = 3)
                val chatMessageId = parts.getOrNull(0)?.toLongOrNull()
                if (chatMessageId != null) {
                    chatRepository.updateMessageStatus(chatMessageId, DeliveryStatus.Failed)
                }
            }
        }
    }

    fun onTextChanged(text: String) {
        if (text.isNotBlank() && !isCurrentlyTyping) {
            sendTypingIndicator(true)
        }

        // Reset the typing timeout
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(typingTimeoutMs)
            sendTypingIndicator(false)
        }
    }

    private fun sendTypingIndicator(isTyping: Boolean) {
        if (isCurrentlyTyping == isTyping) return
        isCurrentlyTyping = isTyping

        viewModelScope.launch {
            try {
                bleController.sendTypingIndicator(isTyping)
            } catch (_: Exception) {
                // Ignore errors for typing indicators
            }
        }
    }

    fun disconnect() {
        // Stop typing indicator before disconnecting
        if (isCurrentlyTyping) {
            sendTypingIndicator(false)
        }
        typingJob?.cancel()

        viewModelScope.launch {
            bleController.disconnect()
        }
    }
}
