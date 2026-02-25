package com.bluechat.data.ble

import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.repository.BleController
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class KableCentralController : BleController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val messageCharacteristic = characteristicOf(
        service = BleGattProfile.SERVICE_UUID,
        characteristic = BleGattProfile.CHARACTERISTIC_MESSAGE_UUID,
    )

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    override val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    private var scanJob: Job? = null
    private var peripheral: Peripheral? = null
    private var stateObserverJob: Job? = null
    private var reconnectJob: Job? = null
    private var rssiPollingJob: Job? = null
    private val advertisementCache = mutableMapOf<String, Advertisement>()

    private var autoReconnectEnabled = true
    private var intentionalDisconnect = false
    private var currentDeviceId: String? = null

    // Exponential backoff delays: 1s, 2s, 4s, 8s, 16s
    private val reconnectBackoffDelays = longArrayOf(1000L, 2000L, 4000L, 8000L, 16000L)
    private val maxReconnectAttempts = 5

    private val scanner = Scanner {
        filters {
            match {
                services = listOf(BleGattProfile.SERVICE_UUID)
            }
        }
    }

    override val incomingMessages: Flow<ByteArray>
        get() = peripheral?.observe(messageCharacteristic) ?: emptyFlow()

    override fun startScan() {
        if (scanJob?.isActive == true) return

        _scannedDevices.value = emptyList()
        advertisementCache.clear()

        scanJob = scanner.advertisements
            .onEach { advertisement ->
                val id = advertisement.identifier.toString()
                advertisementCache[id] = advertisement
                _scannedDevices.update { devices ->
                    val existing = devices.indexOfFirst { it.identifier == id }
                    val device = BleDevice(
                        identifier = id,
                        name = advertisement.name ?: advertisement.peripheralName,
                        rssi = advertisement.rssi,
                    )
                    if (existing >= 0) {
                        devices.toMutableList().apply { set(existing, device) }
                    } else {
                        devices + device
                    }
                }
            }
            .catch { /* scan ended or error */ }
            .launchIn(scope)
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    override suspend fun connect(deviceId: String) {
        val advertisement = advertisementCache[deviceId] ?: return
        _connectionState.value = ConnectionState.Connecting
        intentionalDisconnect = false
        currentDeviceId = deviceId
        _reconnectAttempt.value = 0

        val p = Peripheral(advertisement)
        peripheral = p

        observePeripheralState(p)

        val maxAttempts = 3
        val backoffDelays = longArrayOf(1000L, 2000L, 4000L)
        var lastException: Exception? = null

        for (attempt in 0 until maxAttempts) {
            try {
                p.connect()
                return
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts - 1) {
                    delay(backoffDelays[attempt])
                }
            }
        }

        _connectionState.value = ConnectionState.Disconnected
        peripheral = null
        currentDeviceId = null
        throw lastException!!
    }

    private fun observePeripheralState(p: Peripheral) {
        stateObserverJob?.cancel()
        stateObserverJob = p.state.onEach { state ->
            when (state) {
                is State.Connecting -> _connectionState.value = ConnectionState.Connecting
                is State.Connected -> {
                    _connectionState.value = ConnectionState.Connected
                    _reconnectAttempt.value = 0
                    startRssiPolling()
                }
                is State.Disconnecting -> {
                    _connectionState.value = ConnectionState.Disconnecting
                    stopRssiPolling()
                }
                is State.Disconnected -> {
                    stopRssiPolling()
                    _rssi.value = null
                    if (!intentionalDisconnect && autoReconnectEnabled && currentDeviceId != null) {
                        // Unexpected disconnection - try to reconnect
                        startAutoReconnect()
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        }.launchIn(scope)
    }

    @OptIn(com.juul.kable.ExperimentalApi::class)
    private fun startRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = scope.launch {
            while (true) {
                try {
                    val p = peripheral
                    if (p != null && _connectionState.value == ConnectionState.Connected) {
                        val rssiValue = p.rssi()
                        _rssi.value = rssiValue
                    }
                } catch (_: Exception) {
                    // Ignore RSSI read errors
                }
                delay(2000) // Poll every 2 seconds
            }
        }
    }

    private fun stopRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = null
    }

    private fun startAutoReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            for (attempt in 0 until maxReconnectAttempts) {
                _reconnectAttempt.value = attempt + 1
                _connectionState.value = ConnectionState.Reconnecting

                val deviceId = currentDeviceId ?: break
                val advertisement = advertisementCache[deviceId]

                if (advertisement == null) {
                    // Try to find the device via scan
                    startScan()
                    delay(2000) // Give scan time to find devices
                    stopScan()
                }

                val ad = advertisementCache[deviceId]
                if (ad != null) {
                    try {
                        val p = Peripheral(ad)
                        peripheral = p
                        observePeripheralState(p)
                        p.connect()
                        // Success!
                        _reconnectAttempt.value = 0
                        return@launch
                    } catch (_: Exception) {
                        // Connection failed, continue to next attempt
                    }
                }

                if (attempt < maxReconnectAttempts - 1) {
                    delay(reconnectBackoffDelays[attempt])
                }
            }

            // All attempts failed
            _connectionState.value = ConnectionState.Disconnected
            _reconnectAttempt.value = 0
            peripheral = null
            currentDeviceId = null
        }
    }

    override suspend fun disconnect() {
        intentionalDisconnect = true
        reconnectJob?.cancel()
        _reconnectAttempt.value = 0
        peripheral?.disconnect()
        peripheral = null
        currentDeviceId = null
    }

    override fun disconnectIntentionally() {
        scope.launch {
            disconnect()
        }
    }

    override suspend fun send(message: String) {
        val p = peripheral ?: return
        // Use new protocol format with UUID
        val messageUuid = BleMessageProtocol.generateMessageUuid()
        val data = BleMessageProtocol.encodeChatMessage(messageUuid, message)
        p.write(messageCharacteristic, data)
    }

    override suspend fun sendRaw(data: ByteArray) {
        val p = peripheral ?: return
        p.write(messageCharacteristic, data)
    }

    override suspend fun sendReadReceipt(messageUuid: String) {
        val p = peripheral ?: return
        val data = BleMessageProtocol.encodeReadReceipt(messageUuid)
        p.write(messageCharacteristic, data)
    }

    override suspend fun sendTypingIndicator(isTyping: Boolean) {
        val p = peripheral ?: return
        val data = BleMessageProtocol.encodeTypingIndicator(isTyping)
        p.write(messageCharacteristic, data)
    }

    override fun enableAutoReconnect(enabled: Boolean) {
        autoReconnectEnabled = enabled
        if (!enabled) {
            reconnectJob?.cancel()
            _reconnectAttempt.value = 0
        }
    }

    override fun release() {
        stopScan()
        stopRssiPolling()
        reconnectJob?.cancel()
        stateObserverJob?.cancel()
        scope.cancel()
    }
}
