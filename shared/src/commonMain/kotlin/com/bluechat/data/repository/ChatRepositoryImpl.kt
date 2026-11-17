package com.bluechat.data.repository

import com.bluechat.data.db.ChatMessageDao
import com.bluechat.data.db.ChatMessageEntity
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.DeliveryStatus
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

    override suspend fun insertMessage(message: ChatMessage): Long {
        return dao.insert(message.toEntity())
    }

    override suspend fun updateMessageStatus(id: Long, status: DeliveryStatus) {
        dao.updateMessageStatus(id, status.name)
    }

    override suspend fun updateMessageStatusByUuid(messageUuid: String, status: DeliveryStatus, readAt: Long?) {
        dao.updateMessageStatusByUuid(messageUuid, status.name, readAt)
    }

    override suspend fun getMessageByUuid(messageUuid: String): ChatMessage? {
        return dao.getMessageByUuid(messageUuid)?.toDomain()
    }

    override suspend fun getUnreadMessages(deviceId: String): List<ChatMessage> {
        return dao.getUnreadMessagesForDevice(deviceId).map { it.toDomain() }
    }

    override suspend fun markMessageAsRead(id: Long, readAt: Long) {
        dao.markMessageAsRead(id, readAt)
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
    deliveryStatus = try { DeliveryStatus.valueOf(deliveryStatus) } catch (_: Exception) { DeliveryStatus.Sent },
    messageUuid = messageUuid,
    readAt = readAt,
)

private fun ChatMessage.toEntity() = ChatMessageEntity(
    id = id,
    deviceId = deviceId,
    message = message,
    isFromLocalUser = isFromLocalUser,
    timestamp = timestamp,
    deliveryStatus = deliveryStatus.name,
    messageUuid = messageUuid,
    readAt = readAt,
)
