package com.bluechat.domain.repository

import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.model.PeerMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BleController {
    val scannedDevices: StateFlow<List<BleDevice>>
    val connectionState: StateFlow<ConnectionState>

    /** Transport address of the peripheral we are currently connected (or connecting) to. */
    val connectedDeviceId: StateFlow<String?>

    /** Decoded messages from the connected peripheral. */
    val incomingMessages: Flow<PeerMessage>

    /** How many reconnect attempts have been made for the current drop; 0 when connected. */
    val reconnectAttempt: StateFlow<Int>

    /** Signal strength of the live connection, or `null` when it is not known. */
    val rssi: StateFlow<Int?>

    /**
     * Last scan/connect failure, or `null` if there is nothing to report. Errors are surfaced
     * here rather than thrown away so the UI can tell the user why nothing is happening.
     */
    val lastError: StateFlow<String?>

    fun startScan()
    fun stopScan()
    fun clearError()
    suspend fun connect(deviceId: String)
    suspend fun disconnect()

    /** Disconnects and suppresses auto-reconnect, for when the user asked to leave. */
    fun disconnectIntentionally()

    fun enableAutoReconnect(enabled: Boolean)

    /** Sends a chat message. [messageId] is carried on the wire so the peer can de-duplicate. */
    suspend fun send(messageId: String, body: String)

    /** Tells the peer we have read their message [messageId]. */
    suspend fun sendReadReceipt(messageId: String)

    /** Transient typing signal. Best-effort: failures are not worth reporting. */
    suspend fun sendTypingIndicator(isTyping: Boolean)

    fun release()
}
