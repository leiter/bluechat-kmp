package com.bluechat.domain.repository

import com.bluechat.domain.model.ConnectedClient
import com.bluechat.domain.model.PeerMessage
import com.bluechat.domain.model.ServerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BlePeripheralServer {
    val serverState: StateFlow<ServerState>
    val connectedClients: StateFlow<List<ConnectedClient>>

    /** Decoded messages from connected clients. */
    val incomingMessages: Flow<PeerMessage>

    /** Human-readable reason the server could not start, or `null` if there is none. */
    val lastError: StateFlow<String?>

    fun startAdvertising()
    fun stopAdvertising()

    /** Sends to every connected client. [messageId] is carried on the wire for de-duplication. */
    suspend fun sendToAll(messageId: String, body: String)

    /** Sends to one client, addressed by its transport id. */
    suspend fun sendTo(transportId: String, messageId: String, body: String)

    fun release()
}
