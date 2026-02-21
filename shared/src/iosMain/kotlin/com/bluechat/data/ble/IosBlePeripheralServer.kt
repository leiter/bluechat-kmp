package com.bluechat.data.ble

import com.bluechat.domain.model.ConnectedClient
import com.bluechat.domain.model.ServerState
import com.bluechat.domain.repository.BlePeripheralServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

class IosBlePeripheralServer : BlePeripheralServer {

    private val _serverState = MutableStateFlow(ServerState.Idle)
    override val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ConnectedClient>>(emptyList())
    override val connectedClients: StateFlow<List<ConnectedClient>> = _connectedClients.asStateFlow()

    override val incomingMessages: Flow<Pair<String, ByteArray>> = emptyFlow()

    override fun startAdvertising() {
        // CBPeripheralManager implementation placeholder
        _serverState.value = ServerState.Error
    }

    override fun stopAdvertising() {
        _serverState.value = ServerState.Idle
    }

    override suspend fun sendToAll(message: String) {
        // No-op stub
    }

    override suspend fun sendTo(clientId: String, message: String) {
        // No-op stub
    }

    override fun release() {
        // No-op stub
    }
}
