package com.bluechat.presentation.device

import app.cash.turbine.test
import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.model.PeerMessage
import com.bluechat.domain.model.FavoriteDevice
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.FavoriteDeviceRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeBleController: FakeBleController
    private lateinit var fakeFavoriteDeviceRepository: FakeFavoriteDeviceRepository
    private lateinit var viewModel: DeviceViewModel

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeBleController = FakeBleController()
        fakeFavoriteDeviceRepository = FakeFavoriteDeviceRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DeviceViewModel {
        return DeviceViewModel(
            bleController = fakeBleController,
            favoriteDeviceRepository = fakeFavoriteDeviceRepository,
        )
    }

    @Test
    fun `initial state has no scanned devices`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.scannedDevices.isEmpty())
            assertFalse(state.isScanning)
            assertFalse(state.isConnecting)
            assertFalse(state.isConnected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startScan sets isScanning to true and calls bleController`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem() // initial state

            viewModel.startScan()
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(fakeBleController.scanStarted)

            val state = awaitItem()
            assertTrue(state.isScanning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stopScan sets isScanning to false and calls bleController`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.startScan()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.stopScan()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(fakeBleController.scanStopped)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isScanning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state updates when scanned devices change`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem() // initial state

            val device = BleDevice(identifier = "device-1", name = "Test Device", rssi = -50)
            fakeBleController.addScannedDevice(device)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertEquals(1, state.scannedDevices.size)
            assertEquals("Test Device", state.scannedDevices.first().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connectToDevice sets connectedDeviceId and calls bleController`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem() // initial state

            viewModel.connectToDevice("device-1")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("device-1", fakeBleController.connectedDeviceId.value)

            val state = awaitItem()
            assertEquals("device-1", state.connectedDeviceId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `connectToDevice sets error message on failure`() = runTest {
        fakeBleController.shouldFailConnect = true
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem() // initial state

            viewModel.connectToDevice("device-1")
            testDispatcher.scheduler.advanceUntilIdle()

            // Wait for state with error message (may take a few emissions)
            var state = awaitItem()
            while (state.errorMessage == null) {
                state = awaitItem()
            }
            assertEquals("Connection failed", state.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state reflects connecting state from bleController`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem() // initial state

            fakeBleController.setConnectionState(ConnectionState.Connecting)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertTrue(state.isConnecting)
            assertFalse(state.isConnected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `state reflects connected state from bleController`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem() // initial state

            fakeBleController.setConnectionState(ConnectionState.Connected)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertFalse(state.isConnecting)
            assertTrue(state.isConnected)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `disconnectFromDevice calls bleController and clears connectedDeviceId`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.connectToDevice("device-1")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.disconnectFromDevice()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(fakeBleController.disconnectCalled)

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(null, state.connectedDeviceId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

// Test doubles

class FakeBleController : BleController {
    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
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

    var scanStarted = false
    var scanStopped = false
    var disconnectCalled = false
    var shouldFailConnect = false

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun addScannedDevice(device: BleDevice) {
        _scannedDevices.value = _scannedDevices.value + device
    }

    fun setError(message: String?) {
        _lastError.value = message
    }

    override fun startScan() {
        scanStarted = true
    }

    override fun stopScan() {
        scanStopped = true
    }

    override fun clearError() {
        _lastError.value = null
    }

    override suspend fun connect(deviceId: String) {
        if (shouldFailConnect) throw Exception("Connection failed")
        _connectedDeviceId.value = deviceId
    }

    override suspend fun disconnect() {
        disconnectCalled = true
        _connectedDeviceId.value = null
    }

    override fun disconnectIntentionally() {
        disconnectCalled = true
    }

    override fun enableAutoReconnect(enabled: Boolean) {}
    override suspend fun send(messageId: String, body: String) {}
    override suspend fun sendReadReceipt(messageId: String) {}
    override suspend fun sendTypingIndicator(isTyping: Boolean) {}
    override fun release() {}
}

class FakeFavoriteDeviceRepository : FavoriteDeviceRepository {
    private val _favorites = MutableStateFlow<List<FavoriteDevice>>(emptyList())

    override fun getAllFavorites(): Flow<List<FavoriteDevice>> = _favorites

    override fun isFavorite(identifier: String): Flow<Boolean> {
        return MutableStateFlow(_favorites.value.any { it.identifier == identifier })
    }

    override suspend fun addFavorite(identifier: String, name: String?) {
        _favorites.value = _favorites.value + FavoriteDevice(identifier, name, kotlin.time.Clock.System.now().toEpochMilliseconds())
    }

    override suspend fun removeFavorite(identifier: String) {
        _favorites.value = _favorites.value.filterNot { it.identifier == identifier }
    }

    override suspend fun toggleFavorite(identifier: String, name: String?) {
        if (_favorites.value.any { it.identifier == identifier }) {
            removeFavorite(identifier)
        } else {
            addFavorite(identifier, name)
        }
    }
}
