package com.bluechat.data.repository

import com.bluechat.data.db.ChatMessageDao
import com.bluechat.data.db.ChatMessageEntity
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepositoryImpl(
    private val dao: ChatMessageDao,
) : ChatRepository {

    override fun getMessagesForDevice(deviceId: String): Flow<List<ChatMessage>> {
        return dao.getMessagesForDevice(deviceId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun insertMessage(message: ChatMessage) {
        dao.insert(message.toEntity())
    }

    override suspend fun deleteMessagesForDevice(deviceId: String) {
        dao.deleteMessagesForDevice(deviceId)
    }
}

private fun ChatMessageEntity.toDomain() = ChatMessage(
    id = id,
    deviceId = deviceId,
    message = message,
    isFromLocalUser = isFromLocalUser,
    timestamp = timestamp,
)

private fun ChatMessage.toEntity() = ChatMessageEntity(
    id = id,
    deviceId = deviceId,
    message = message,
    isFromLocalUser = isFromLocalUser,
    timestamp = timestamp,
)
