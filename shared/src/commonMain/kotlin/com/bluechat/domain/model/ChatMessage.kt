package com.bluechat.domain.model

data class ChatMessage(
    val id: Long = 0,
    val deviceId: String,
    val message: String,
    val isFromLocalUser: Boolean,
    val timestamp: Long = 0,
)
