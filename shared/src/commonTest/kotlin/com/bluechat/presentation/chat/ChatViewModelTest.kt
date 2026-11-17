package com.bluechat.presentation.chat

import app.cash.turbine.test
import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ChatMessage
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.model.PeerMessage
import com.bluechat.domain.model.DeliveryStatus
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeBleController: FakeBleController
    private lateinit var fakeChatRepository: FakeChatRepository

    private val testDeviceId = "test-device-123"

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeBleController = FakeBleController()
        fakeChatRepository = FakeChatRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state updates when connection state changes`() = runTest {
        fakeBleController.setConnectionState(ConnectionState.Connected)
        testDispatcher.scheduler.advanceUntilIdle()

        val initialState = fakeBleController.connectionState.value
        assertEquals(ConnectionState.Connected, initialState)

        fakeBleController.setConnectionState(ConnectionState.Disconnected)
        testDispatcher.scheduler.advanceUntilIdle()

        val updatedState = fakeBleController.connectionState.value
        assertEquals(ConnectionState.Disconnected, updatedState)
    }

    @Test
    fun `repository stores and retrieves messages correctly`() = runTest {
        val testMessage = ChatMessage(
            id = 0,
            deviceId = testDeviceId,
            message = "Hello",
            isFromLocalUser = true,
            timestamp = 1000L,
            deliveryStatus = DeliveryStatus.Sent,
        )

        val insertedId = fakeChatRepository.insertMessage(testMessage)
        assertEquals(1L, insertedId)

        fakeChatRepository.getMessagesForDevice(testDeviceId).test {
            val messages = awaitItem()
            assertEquals(1, messages.size)
            assertEquals("Hello", messages.first().message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `repository updates message status correctly`() = runTest {
        val testMessage = ChatMessage(
            id = 0,
            deviceId = testDeviceId,
            message = "Test",
            isFromLocalUser = true,
            timestamp = 1000L,
            deliveryStatus = DeliveryStatus.Sending,
        )

        val id = fakeChatRepository.insertMessage(testMessage)
        fakeChatRepository.updateMessageStatus(id, DeliveryStatus.Sent)

        assertEquals(1, fakeChatRepository.statusUpdates.size)
        assertEquals(id to DeliveryStatus.Sent, fakeChatRepository.statusUpdates.first())
    }

    @Test
    fun `bleController send succeeds and fails as expected`() = runTest {
        // Test success
        fakeBleController.send("msg-1", "Test message")
        assertEquals(1, fakeBleController.sentMessages.size)
        assertEquals("Test message", fakeBleController.sentMessages.first())

        // Test failure
        fakeBleController.shouldFailSend = true
        var caughtException = false
        try {
            fakeBleController.send("msg-2", "Should fail")
        } catch (e: Exception) {
            caughtException = true
        }
        assertTrue(caughtException)
    }

    @Test
    fun `disconnect sets flag correctly`() = runTest {
        assertFalse(fakeBleController.disconnectCalled)
        fakeBleController.disconnect()
        assertTrue(fakeBleController.disconnectCalled)
    }

    @Test
    fun `incoming messages are emitted correctly`() = runTest {
        val incomingFlow = fakeBleController.incomingMessages

        incomingFlow.test {
            fakeBleController.emitIncomingMessage(
                PeerMessage.Text(
                    transportId = testDeviceId,
                    peerId = "peer-1",
                    messageId = "msg-1",
                    sentAt = 0L,
                    body = "Hello from remote",
                )
            )
            val received = awaitItem()
            assertIs<PeerMessage.Text>(received)
            assertEquals("Hello from remote", received.body)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `repository deletes messages for device`() = runTest {
        val testMessage = ChatMessage(
            id = 0,
            deviceId = testDeviceId,
            message = "To be deleted",
            isFromLocalUser = true,
            timestamp = 1000L,
            deliveryStatus = DeliveryStatus.Sent,
        )

        fakeChatRepository.insertMessage(testMessage)
        fakeChatRepository.deleteMessagesForDevice(testDeviceId)

        fakeChatRepository.getMessagesForDevice(testDeviceId).test {
            val messages = awaitItem()
            assertTrue(messages.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

// Test doubles

class FakeBleController : BleController {
    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices

    private val _connectionState = MutableStateFlow(ConnectionState.Connected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _incomingMessages = MutableSharedFlow<PeerMessage>()
    override val incomingMessages: Flow<PeerMessage> = _incomingMessages

    private val _reconnectAttempt = MutableStateFlow(0)
    override val reconnectAttempt: StateFlow<Int> = _reconnectAttempt

    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi

    private val _connectedDeviceId = MutableStateFlow<String?>(null)
    override val connectedDeviceId: StateFlow<String?> = _connectedDeviceId

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError

    var disconnectCalled = false
    var shouldFailSend = false
    val sentMessages = mutableListOf<String>()
    val sentReadReceipts = mutableListOf<String>()
    val typingSignals = mutableListOf<Boolean>()

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    suspend fun emitIncomingMessage(message: PeerMessage) {
        _incomingMessages.emit(message)
    }

    override fun startScan() {}
    override fun stopScan() {}
    override fun clearError() { _lastError.value = null }
    override suspend fun connect(deviceId: String) { _connectedDeviceId.value = deviceId }

    override suspend fun disconnect() {
        disconnectCalled = true
    }

    override fun disconnectIntentionally() {
        disconnectCalled = true
    }

    override fun enableAutoReconnect(enabled: Boolean) {}

    override suspend fun send(messageId: String, body: String) {
        if (shouldFailSend) throw Exception("Send failed")
        sentMessages.add(body)
    }

    override suspend fun sendReadReceipt(messageId: String) {
        sentReadReceipts.add(messageId)
    }

    override suspend fun sendTypingIndicator(isTyping: Boolean) {
        typingSignals.add(isTyping)
    }

    override fun release() {}
}

class FakeChatRepository : ChatRepository {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val insertedMessages = mutableListOf<ChatMessage>()
    val statusUpdates = mutableListOf<Pair<Long, DeliveryStatus>>()
    private var nextId = 1L

    override fun getMessagesForDevice(deviceId: String): Flow<List<ChatMessage>> = _messages

    override suspend fun insertMessage(message: ChatMessage): Long {
        val id = nextId++
        val messageWithId = message.copy(id = id)
        insertedMessages.add(messageWithId)
        _messages.value = _messages.value + messageWithId
        return id
    }

    override suspend fun updateMessageStatus(id: Long, status: DeliveryStatus) {
        statusUpdates.add(id to status)
    }

    override suspend fun updateMessageStatusByUuid(messageUuid: String, status: DeliveryStatus, readAt: Long?) {}

    override suspend fun getMessageByUuid(messageUuid: String): ChatMessage? = null

    override suspend fun getUnreadMessages(deviceId: String): List<ChatMessage> = emptyList()

    override suspend fun markMessageAsRead(id: Long, readAt: Long) {}

    override suspend fun deleteMessagesForDevice(deviceId: String) {
        _messages.value = _messages.value.filterNot { it.deviceId == deviceId }
    }

    fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }
}
