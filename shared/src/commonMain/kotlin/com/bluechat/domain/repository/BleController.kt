package com.bluechat.domain.repository

import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BleController {
    val scannedDevices: StateFlow<List<BleDevice>>
    val connectionState: StateFlow<ConnectionState>
    val incomingMessages: Flow<ByteArray>
    val reconnectAttempt: StateFlow<Int>
    val rssi: StateFlow<Int?>

    fun startScan()
    fun stopScan()
    suspend fun connect(deviceId: String)
    suspend fun disconnect()
    fun disconnectIntentionally()
    suspend fun send(message: String)
    suspend fun sendRaw(data: ByteArray)
    suspend fun sendReadReceipt(messageUuid: String)
    suspend fun sendTypingIndicator(isTyping: Boolean)
    fun enableAutoReconnect(enabled: Boolean)
    fun release()
}
