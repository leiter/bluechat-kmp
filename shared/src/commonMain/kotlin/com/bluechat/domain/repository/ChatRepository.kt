package com.bluechat.domain.repository

import com.bluechat.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getMessagesForDevice(deviceId: String): Flow<List<ChatMessage>>
    suspend fun insertMessage(message: ChatMessage)
    suspend fun deleteMessagesForDevice(deviceId: String)
}
