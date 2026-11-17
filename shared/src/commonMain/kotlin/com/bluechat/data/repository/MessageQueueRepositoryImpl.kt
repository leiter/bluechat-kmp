package com.bluechat.data.repository

import com.bluechat.data.db.PendingMessageDao
import com.bluechat.data.db.PendingMessageEntity
import com.bluechat.domain.model.PendingMessage
import com.bluechat.domain.repository.MessageQueueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

class MessageQueueRepositoryImpl(
    private val dao: PendingMessageDao,
) : MessageQueueRepository {

    override fun getPendingMessages(deviceId: String): Flow<List<PendingMessage>> {
        return dao.getPendingMessagesForDevice(deviceId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getPendingCount(deviceId: String): Flow<Int> {
        return dao.getPendingCountForDevice(deviceId)
    }

    override suspend fun queueMessage(deviceId: String, message: String): Long {
        val entity = PendingMessageEntity(
            deviceId = deviceId,
            message = message,
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
        return dao.insert(entity)
    }

    override suspend fun removeMessage(id: Long) {
        dao.delete(id)
    }

    override suspend fun incrementRetryCount(id: Long) {
        dao.incrementRetryCount(id)
    }

    override suspend fun getMessagesToSend(deviceId: String): List<PendingMessage> {
        return dao.getPendingMessagesForDeviceOnce(deviceId).map { it.toDomain() }
    }

    override suspend fun clearQueue(deviceId: String) {
        dao.deleteAllForDevice(deviceId)
    }
}

private fun PendingMessageEntity.toDomain() = PendingMessage(
    id = id,
    deviceId = deviceId,
    message = message,
    createdAt = createdAt,
    retryCount = retryCount,
)
