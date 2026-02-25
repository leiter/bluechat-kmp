package com.bluechat.domain.repository

import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.DeliveryStatus
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getMessagesForDevice(deviceId: String): Flow<List<ChatMessage>>
    suspend fun insertMessage(message: ChatMessage): Long
    suspend fun updateMessageStatus(id: Long, status: DeliveryStatus)
    suspend fun updateMessageStatusByUuid(messageUuid: String, status: DeliveryStatus, readAt: Long? = null)
    suspend fun getMessageByUuid(messageUuid: String): ChatMessage?
    suspend fun getUnreadMessages(deviceId: String): List<ChatMessage>
    suspend fun markMessageAsRead(id: Long, readAt: Long)
    suspend fun deleteMessagesForDevice(deviceId: String)
}
