package com.bluechat.domain.repository

import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.DeliveryStatus
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getMessagesForDevice(deviceId: String): Flow<List<ChatMessage>>
    suspend fun insertMessage(message: ChatMessage): Long
    suspend fun updateMessageStatus(id: Long, status: DeliveryStatus)
    suspend fun deleteMessagesForDevice(deviceId: String)
}
