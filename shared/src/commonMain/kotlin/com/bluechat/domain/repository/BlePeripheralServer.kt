package com.bluechat.domain.repository

import com.bluechat.domain.model.ConnectedClient
import com.bluechat.domain.model.ServerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BlePeripheralServer {
    val serverState: StateFlow<ServerState>
    val connectedClients: StateFlow<List<ConnectedClient>>
    val incomingMessages: Flow<Pair<String, ByteArray>> // (clientId, data)
    fun startAdvertising()
    fun stopAdvertising()
    suspend fun sendToAll(message: String)
    suspend fun sendTo(clientId: String, message: String)
    fun release()
}
