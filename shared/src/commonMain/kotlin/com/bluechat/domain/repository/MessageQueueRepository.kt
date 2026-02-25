package com.bluechat.domain.repository

import com.bluechat.domain.model.PendingMessage
import kotlinx.coroutines.flow.Flow

interface MessageQueueRepository {
    fun getPendingMessages(deviceId: String): Flow<List<PendingMessage>>
    fun getPendingCount(deviceId: String): Flow<Int>
    suspend fun queueMessage(deviceId: String, message: String): Long
    suspend fun removeMessage(id: Long)
    suspend fun incrementRetryCount(id: Long)
    suspend fun getMessagesToSend(deviceId: String): List<PendingMessage>
    suspend fun clearQueue(deviceId: String)
}
