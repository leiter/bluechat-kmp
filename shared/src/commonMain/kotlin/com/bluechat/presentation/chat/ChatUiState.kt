package com.bluechat.presentation.chat

import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.ConnectionState

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isConnected: Boolean = true,
    val deviceName: String? = null,
    val connectionState: ConnectionState = ConnectionState.Connected,
)
