package com.bluechat.data.ble

import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.repository.BleController
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update

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

    private var scanJob: Job? = null
    private var peripheral: Peripheral? = null
    private val advertisementCache = mutableMapOf<String, Advertisement>()

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

        val p = Peripheral(advertisement)
        peripheral = p

        p.state.onEach { state ->
            _connectionState.value = when (state) {
                is State.Connecting -> ConnectionState.Connecting
                is State.Connected -> ConnectionState.Connected
                is State.Disconnecting -> ConnectionState.Disconnecting
                is State.Disconnected -> ConnectionState.Disconnected
            }
        }.launchIn(scope)

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
        throw lastException!!
    }

    override suspend fun disconnect() {
        peripheral?.disconnect()
        peripheral = null
    }

    override suspend fun send(message: String) {
        val p = peripheral ?: return
        p.write(messageCharacteristic, message.encodeToByteArray(), WriteType.WithResponse)
    }

    override fun release() {
        stopScan()
        scope.cancel()
    }
}
