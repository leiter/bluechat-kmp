package com.bluechat.domain.presence

import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.ContactRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock

/**
 * Whether a contact can be reached right now (B5).
 *
 * The roles are asymmetric (decision E3): we can only open a connection to a peer that is
 * advertising, i.e. in Server Mode. Presence says which case applies.
 */
enum class Presence {
    /** A live link exists, in either role. Messages go out immediately. */
    Connected,

    /** Advertising nearby, so we can connect to them. */
    Nearby,

    /**
     * Neither. They are out of range, or in range but not advertising. In that case they
     * can still reach us if *we* are in Server Mode.
     */
    NotNearby,
}

/**
 * Derives every contact's [Presence] from scan results and both roles' connections (B5).
 *
 * Contacts are keyed on their stable id, but the radio only sees transport addresses, so a
 * contact is matched through the address it was last reached at (`Contact.lastTransportId`).
 * Known limit: when a peer's address changes (Android rotates it), it shows as
 * [Presence.NotNearby] until the next connection records the new address.
 *
 * Presence only reflects scan results while something holds the scan; the chat list and the
 * contacts screen do while they are visible.
 *
 * @param now clock, overridable for tests.
 * @param tickMillis how often "nearby" is re-evaluated, so a peer that went quiet drops to
 *   [Presence.NotNearby] even when no new scan result arrives. 0 evaluates only on changes,
 *   which tests use: an endless ticker would keep `advanceUntilIdle()` from ever returning.
 */
class PresenceTracker(
    private val bleController: BleController,
    private val server: BlePeripheralServer,
    private val contactRepository: ContactRepository,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val tickMillis: Long = 5_000L,
) {

    /** Presence per contact id. Contacts not in the map are [Presence.NotNearby]. */
    fun observe(): Flow<Map<String, Presence>> = combine(
        contactRepository.observeAll(),
        bleController.scannedDevices,
        bleController.connectionState,
        bleController.connectedDeviceId,
        combine(server.connectedClients, ticker()) { clients, _ -> clients },
    ) { contacts, scanned, centralState, centralPeer, clients ->
        val connected = buildSet {
            clients.forEach { add(it.identifier) }
            if (centralState == ConnectionState.Connected) centralPeer?.let(::add)
        }
        val cutoff = now() - BleDevice.NEARBY_WINDOW_MILLIS
        val nearby = scanned.filter { it.lastSeenAt >= cutoff }.mapTo(mutableSetOf()) { it.identifier }

        buildMap {
            for (contact in contacts) {
                when (contact.lastTransportId) {
                    in connected -> put(contact.contactId, Presence.Connected)
                    in nearby -> put(contact.contactId, Presence.Nearby)
                }
            }
        }
    }.distinctUntilChanged()

    private fun ticker(): Flow<Unit> = flow {
        if (tickMillis <= 0) {
            emit(Unit)
            return@flow
        }
        while (true) {
            emit(Unit)
            delay(tickMillis)
        }
    }

    companion object {
        /** Scan holder name for screens that show presence. Suffix it per screen. */
        const val SCAN_HOLDER_PREFIX = "presence:"
    }
}
