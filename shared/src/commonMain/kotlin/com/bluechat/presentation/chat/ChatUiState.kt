package com.bluechat.presentation.chat

import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.ConnectionState
import com.bluechat.util.SignalStrength

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isConnected: Boolean = true,
    val deviceName: String? = null,
    val connectionState: ConnectionState = ConnectionState.Connected,
    val errorMessage: String? = null,
    val pendingMessageCount: Int = 0,
    val isRemoteTyping: Boolean = false,
    val reconnectAttempt: Int = 0,
    val signalStrength: SignalStrength = SignalStrength.None,
)
