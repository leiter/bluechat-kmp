package com.bluechat.domain.repository

import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BleController {
    val scannedDevices: StateFlow<List<BleDevice>>
    val connectionState: StateFlow<ConnectionState>

    /** Identifier of the peripheral we are currently connected (or connecting) to. */
    val connectedDeviceId: StateFlow<String?>

    /** Incoming payloads paired with the identifier of the peripheral they came from. */
    val incomingMessages: Flow<Pair<String, ByteArray>>

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
    suspend fun send(message: String)
    fun release()
}
