package com.bluechat.domain.model

data class PendingMessage(
    val id: Long = 0,
    val deviceId: String,
    val message: String,
    val createdAt: Long,
    val retryCount: Int = 0,
)
