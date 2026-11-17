package com.bluechat.domain.session

import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.model.ServerState
import com.bluechat.domain.repository.BleController
import com.bluechat.domain.repository.BlePeripheralServer
import com.bluechat.domain.repository.PeerDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A single answer to "is this app currently holding a Bluetooth session open?", folded from
 * both BLE roles.
 *
 * This exists so the platform lifecycle layer has one thing to watch. On Android a foreground
 * service is started while [BleSession.isActive] is true and stopped when it goes false; the
 * same flow supplies the text for that service's ongoing notification. iOS has no equivalent
 * requirement, so nothing consumes it there yet.
 *
 * It is deliberately a passive observer: it never starts or stops either role, it only reports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleSessionMonitor(
    server: BlePeripheralServer,
    bleController: BleController,
    peerDirectory: PeerDirectory,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session: StateFlow<BleSession> = combine(
        server.serverState,
        server.connectedClients,
        bleController.connectionState,
        bleController.connectedDeviceId,
    ) { serverState, clients, connectionState, transportId ->
        BleSession(
            isServerActive = serverState == ServerState.Advertising,
            connectedClientCount = clients.size,
            isClientConnected = connectionState == ConnectionState.Connected,
            clientTransportId = transportId,
        )
    }.flatMapLatest { snapshot ->
        // Resolve the peer's announced name so the notification can say who we are talking to
        // rather than quoting a MAC address at the user.
        val transportId = snapshot.clientTransportId
        if (transportId == null || !snapshot.isClientConnected) {
            flowOf(snapshot)
        } else {
            peerDirectory.observeDisplayName(transportId)
                .map { name -> snapshot.copy(clientPeerName = name) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, BleSession())
}

/** Immutable snapshot of what the radio is doing, in whichever role(s) are live. */
data class BleSession(
    val isServerActive: Boolean = false,
    val connectedClientCount: Int = 0,
    val isClientConnected: Boolean = false,
    val clientTransportId: String? = null,
    val clientPeerName: String? = null,
) {
    /**
     * True while messaging can actually happen. This is what gates the Android foreground
     * service: anything less and the process has no reason to outlive its last screen.
     */
    val isActive: Boolean get() = isServerActive || isClientConnected
}
