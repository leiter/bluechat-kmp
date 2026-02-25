package com.bluechat.data.repository

import app.cash.turbine.test
import com.bluechat.data.db.ChatMessageDao
import com.bluechat.data.db.ChatMessageEntity
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.DeliveryStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryImplTest {

    private lateinit var fakeDao: FakeChatMessageDao
    private lateinit var repository: ChatRepositoryImpl

    private val testDeviceId = "test-device-123"

    @BeforeTest
    fun setup() {
        fakeDao = FakeChatMessageDao()
        repository = ChatRepositoryImpl(fakeDao)
    }

    @Test
    fun `getMessagesForDevice returns flow of domain messages`() = runTest {
        val entity = ChatMessageEntity(
            id = 1,
            deviceId = testDeviceId,
            message = "Hello",
            isFromLocalUser = true,
            timestamp = 1000L,
            deliveryStatus = "Sent",
        )
        fakeDao.addMessage(entity)

        repository.getMessagesForDevice(testDeviceId).test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            assertEquals("Hello", messages.first().message)
            assertEquals(DeliveryStatus.Sent, messages.first().deliveryStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `insertMessage converts domain to entity and inserts`() = runTest {
        val message = ChatMessage(
            id = 0,
            deviceId = testDeviceId,
            message = "Test message",
            isFromLocalUser = true,
            timestamp = 2000L,
            deliveryStatus = DeliveryStatus.Sending,
        )

        val insertedId = repository.insertMessage(message)

        assertEquals(1L, insertedId)
        assertEquals(1, fakeDao.insertedEntities.size)
        assertEquals("Test message", fakeDao.insertedEntities.first().message)
        assertEquals("Sending", fakeDao.insertedEntities.first().deliveryStatus)
    }

    @Test
    fun `updateMessageStatus updates with status name`() = runTest {
        repository.updateMessageStatus(1L, DeliveryStatus.Sent)

        assertEquals(1, fakeDao.statusUpdates.size)
        assertEquals(1L to "Sent", fakeDao.statusUpdates.first())
    }

    @Test
    fun `deleteMessagesForDevice calls dao delete`() = runTest {
        repository.deleteMessagesForDevice(testDeviceId)

        assertEquals(1, fakeDao.deletedDeviceIds.size)
        assertEquals(testDeviceId, fakeDao.deletedDeviceIds.first())
    }

    @Test
    fun `getMessagesForDevice handles invalid delivery status gracefully`() = runTest {
        val entity = ChatMessageEntity(
            id = 1,
            deviceId = testDeviceId,
            message = "Hello",
            isFromLocalUser = true,
            timestamp = 1000L,
            deliveryStatus = "InvalidStatus",
        )
        fakeDao.addMessage(entity)

        repository.getMessagesForDevice(testDeviceId).test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            assertEquals(DeliveryStatus.Sent, messages.first().deliveryStatus) // Falls back to Sent
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `messages are filtered by deviceId`() = runTest {
        fakeDao.addMessage(ChatMessageEntity(
            id = 1,
            deviceId = testDeviceId,
            message = "Message 1",
            isFromLocalUser = true,
            timestamp = 1000L,
            deliveryStatus = "Sent",
        ))
        fakeDao.addMessage(ChatMessageEntity(
            id = 2,
            deviceId = "other-device",
            message = "Message 2",
            isFromLocalUser = true,
            timestamp = 2000L,
            deliveryStatus = "Sent",
        ))

        repository.getMessagesForDevice(testDeviceId).test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            assertEquals("Message 1", messages.first().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty repository returns empty list`() = runTest {
        repository.getMessagesForDevice(testDeviceId).test {
            val messages = awaitItem()
            assertTrue(messages.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

// Fake DAO

class FakeChatMessageDao : ChatMessageDao {
    private val _messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    val insertedEntities = mutableListOf<ChatMessageEntity>()
    val statusUpdates = mutableListOf<Pair<Long, String>>()
    val deletedDeviceIds = mutableListOf<String>()
    private var nextId = 1L

    override fun getMessagesForDevice(deviceId: String): Flow<List<ChatMessageEntity>> {
        return MutableStateFlow(_messages.value.filter { it.deviceId == deviceId })
    }

    override suspend fun insert(message: ChatMessageEntity): Long {
        val id = nextId++
        val entityWithId = message.copy(id = id)
        insertedEntities.add(entityWithId)
        _messages.value = _messages.value + entityWithId
        return id
    }

    override suspend fun updateMessageStatus(id: Long, status: String) {
        statusUpdates.add(id to status)
    }

    override suspend fun updateMessageStatusByUuid(messageUuid: String, status: String, readAt: Long?) {
        // No-op for tests
    }

    override suspend fun getMessageByUuid(messageUuid: String): ChatMessageEntity? {
        return _messages.value.find { it.messageUuid == messageUuid }
    }

    override suspend fun getUnreadMessagesForDevice(deviceId: String): List<ChatMessageEntity> {
        return _messages.value.filter { it.deviceId == deviceId && !it.isFromLocalUser && it.readAt == null }
    }

    override suspend fun markMessageAsRead(id: Long, readAt: Long) {
        // No-op for tests
    }

    override suspend fun deleteMessagesForDevice(deviceId: String) {
        deletedDeviceIds.add(deviceId)
        _messages.value = _messages.value.filterNot { it.deviceId == deviceId }
    }

    fun addMessage(entity: ChatMessageEntity) {
        _messages.value = _messages.value + entity
    }
}
