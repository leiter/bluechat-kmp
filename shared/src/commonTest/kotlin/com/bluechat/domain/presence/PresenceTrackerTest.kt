package com.bluechat.domain.presence

import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import com.bluechat.presentation.conversations.FakePeripheralServer
import com.bluechat.presentation.device.FakeBleController
import com.bluechat.presentation.device.FakeContactRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** B5: who counts as connected, nearby, or neither. */
class PresenceTrackerTest {

    private val ble = FakeBleController()
    private val server = FakePeripheralServer()
    private val contacts = FakeContactRepository()
    private val now = 1_000_000L

    private val tracker = PresenceTracker(ble, server, contacts, now = { now }, tickMillis = 0)

    private suspend fun presence() = tracker.observe().first()

    @Test
    fun `a contact connected to our server is connected`() = runTest {
        contacts.addContact("alice", transportId = "AA:01")
        server.setClients("AA:01")

        assertEquals(mapOf("alice" to Presence.Connected), presence())
    }

    @Test
    fun `the peer we are connected to as client is connected`() = runTest {
        contacts.addContact("bob", transportId = "BB:01")
        ble.connect("BB:01")
        ble.setConnectionState(ConnectionState.Connected)

        assertEquals(mapOf("bob" to Presence.Connected), presence())
    }

    @Test
    fun `a peer we are still connecting to is not yet connected`() = runTest {
        contacts.addContact("bob", transportId = "BB:01")
        ble.connect("BB:01")
        ble.setConnectionState(ConnectionState.Connecting)

        assertEquals(emptyMap(), presence())
    }

    @Test
    fun `a contact seen advertising recently is nearby`() = runTest {
        contacts.addContact("carol", transportId = "CC:01")
        ble.addScannedDevice(BleDevice("CC:01", "Carol", lastSeenAt = now - 5_000))

        assertEquals(mapOf("carol" to Presence.Nearby), presence())
    }

    @Test
    fun `a scan result older than the window no longer counts as nearby`() = runTest {
        contacts.addContact("carol", transportId = "CC:01")
        ble.addScannedDevice(
            BleDevice("CC:01", "Carol", lastSeenAt = now - BleDevice.NEARBY_WINDOW_MILLIS - 1),
        )

        assertEquals(emptyMap(), presence())
    }

    @Test
    fun `connected wins over nearby, and strangers are ignored`() = runTest {
        contacts.addContact("alice", transportId = "AA:01")
        server.setClients("AA:01")
        ble.addScannedDevice(BleDevice("AA:01", "Alice", lastSeenAt = now))
        ble.addScannedDevice(BleDevice("ZZ:99", "Stranger", lastSeenAt = now))

        assertEquals(mapOf("alice" to Presence.Connected), presence())
    }
}
