package com.bluechat.presentation.device

import app.cash.turbine.test
import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.model.PeerMessage
import com.bluechat.domain.model.Contact
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.ContactRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeBleController: FakeBleController
    private lateinit var fakeContactRepository: FakeContactRepository
    private lateinit var viewModel: DeviceViewModel

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeBleController = FakeBleController()
        fakeContactRepository = FakeContactRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DeviceViewModel {
        return DeviceViewModel(
            bleController = fakeBleController,
            contactRepository = fakeContactRepository,
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

    // --- B1: favourites keyed on identity, not on a Bluetooth address ---

    @Test
    fun `a scanned device is recognised when a contact was last seen at that address`() = runTest {
        fakeContactRepository.addContact(
            contactId = "peer-abc",
            announcedName = "Alice",
            transportId = "AA:BB:CC:DD:EE:FF",
        )
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem()
            fakeBleController.addScannedDevice(
                BleDevice(identifier = "AA:BB:CC:DD:EE:FF", name = "Pixel 7a", rssi = -40)
            )
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertEquals("peer-abc", state.knownByTransportId["AA:BB:CC:DD:EE:FF"]?.contactId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an unrecognised scanned device resolves to no contact so no star is offered`() = runTest {
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem()
            fakeBleController.addScannedDevice(
                BleDevice(identifier = "11:22:33:44:55:66", name = "Someone else", rssi = -70)
            )
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            assertEquals(1, state.scannedDevices.size)
            // The screen keys the star off this being non-null, so a stranger gets none.
            assertNull(state.knownByTransportId["11:22:33:44:55:66"])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a favourite survives the contact moving to a new Bluetooth address`() = runTest {
        // The regression this whole change exists for. Phase 9 stored the favourite against
        // the address, so randomisation detached it from the person.
        fakeContactRepository.addContact(
            contactId = "peer-abc",
            announcedName = "Alice",
            transportId = "AA:BB:CC:DD:EE:FF",
            isFavourite = true,
        )
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem() // stateIn's initial default
            val before = awaitItem()
            assertEquals(1, before.favouriteContacts.size)
            assertEquals("AA:BB:CC:DD:EE:FF", before.favouriteContacts.first().lastTransportId)

            // Same person, new random address -- exactly what Android does periodically.
            fakeContactRepository.moveTo("peer-abc", "99:88:77:66:55:44")
            testDispatcher.scheduler.advanceUntilIdle()

            val after = expectMostRecentItem()
            // Still a favourite, because the favourite was never stored against the address.
            assertTrue(after.favouriteContacts.any { it.contactId == "peer-abc" })
            // And she is now recognised at the new address instead of the old one.
            assertEquals("peer-abc", after.knownByTransportId["99:88:77:66:55:44"]?.contactId)
            assertNull(after.knownByTransportId["AA:BB:CC:DD:EE:FF"])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling a favourite acts on the contact id`() = runTest {
        fakeContactRepository.addContact(contactId = "peer-abc", announcedName = "Alice")
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleFavourite("peer-abc")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(fakeContactRepository.contact("peer-abc")?.isFavourite == true)

        viewModel.toggleFavourite("peer-abc")
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(fakeContactRepository.contact("peer-abc")?.isFavourite == true)
    }

    @Test
    fun `a recognised device is listed under the name we know rather than the advertised one`() = runTest {
        fakeContactRepository.addContact(
            contactId = "peer-abc",
            announcedName = "Alice",
            nickname = "Mum",
            transportId = "AA:BB:CC:DD:EE:FF",
        )
        viewModel = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            awaitItem()
            fakeBleController.addScannedDevice(
                BleDevice(identifier = "AA:BB:CC:DD:EE:FF", name = "Pixel 7a", rssi = -40)
            )
            testDispatcher.scheduler.advanceUntilIdle()

            val state = awaitItem()
            // Nickname beats the announced name, which beats the adapter name.
            assertEquals("Mum", state.knownByTransportId["AA:BB:CC:DD:EE:FF"]?.displayName)
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

class FakeContactRepository : ContactRepository {
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())

    override fun observeAll(): Flow<List<Contact>> = _contacts

    override fun observeFavourites(): Flow<List<Contact>> =
        MutableStateFlow(_contacts.value.filter { it.isFavourite })

    override suspend fun setFavourite(contactId: String, favourite: Boolean) {
        _contacts.value = _contacts.value.map {
            if (it.contactId == contactId) it.copy(isFavourite = favourite) else it
        }
    }

    override suspend fun toggleFavourite(contactId: String) {
        _contacts.value = _contacts.value.map {
            if (it.contactId == contactId) it.copy(isFavourite = !it.isFavourite) else it
        }
    }

    override suspend fun setNickname(contactId: String, nickname: String?) {
        _contacts.value = _contacts.value.map {
            if (it.contactId == contactId) {
                it.copy(nickname = nickname?.trim()?.takeIf(String::isNotEmpty))
            } else {
                it
            }
        }
    }

    override suspend fun setBlocked(contactId: String, blocked: Boolean) {
        _contacts.value = _contacts.value.map {
            if (it.contactId == contactId) it.copy(isBlocked = blocked) else it
        }
    }

    override suspend fun isBlocked(contactId: String): Boolean =
        _contacts.value.any { it.contactId == contactId && it.isBlocked }

    override suspend fun delete(contactId: String) {
        _contacts.value = _contacts.value.filterNot { it.contactId == contactId }
    }

    fun addContact(
        contactId: String,
        announcedName: String? = null,
        nickname: String? = null,
        transportId: String = contactId,
        isFavourite: Boolean = false,
        isBlocked: Boolean = false,
    ) {
        _contacts.value = _contacts.value + Contact(
            contactId = contactId,
            announcedName = announcedName,
            nickname = nickname,
            lastTransportId = transportId,
            firstSeenAt = 0L,
            lastSeenAt = 0L,
            isFavourite = isFavourite,
            isBlocked = isBlocked,
        )
    }

    /** Simulates the same contact reappearing at a new Bluetooth address. */
    fun moveTo(contactId: String, transportId: String) {
        _contacts.value = _contacts.value.map {
            if (it.contactId == contactId) it.copy(lastTransportId = transportId) else it
        }
    }

    fun contact(contactId: String): Contact? = _contacts.value.find { it.contactId == contactId }
}
