package com.bluechat.data.ble

import com.bluechat.data.protocol.Envelope
import com.bluechat.data.protocol.ProtocolCodec
import com.bluechat.data.protocol.toPeerMessage
import com.bluechat.domain.model.BleDevice
import com.bluechat.domain.model.ConnectionState
import com.bluechat.domain.model.PeerMessage
import com.bluechat.domain.repository.AppPreferences
import com.bluechat.domain.repository.BleController
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

class KableCentralController(
    private val appPreferences: AppPreferences,
) : BleController {

    private val codec = ProtocolCodec(nextMessageId = { nextMessageId++ })
    private var nextMessageId: Int = 0

    /**
     * Bytes we may put in one write, header included. Resolved from the negotiated MTU once
     * connected; until then assume the pessimistic ATT default of 23 (20 usable).
     */
    private var maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val messageCharacteristic = characteristicOf(
        service = BleGattProfile.SERVICE_UUID,
        characteristic = BleGattProfile.CHARACTERISTIC_MESSAGE_UUID,
    )

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** The peripheral we are connected to, paired with its identifier. */
    private val _connection = MutableStateFlow<Pair<String, Peripheral>?>(null)

    private val _connectedDeviceId = MutableStateFlow<String?>(null)
    override val connectedDeviceId: StateFlow<String?> = _connectedDeviceId.asStateFlow()

    private var scanJob: Job? = null
    private val advertisementCache = mutableMapOf<String, Advertisement>()

    private val scanner = Scanner {
        filters {
            match {
                services = listOf(BleGattProfile.SERVICE_UUID)
            }
        }
    }

    /**
     * Follows whichever peripheral is currently connected. Collecting this before a connection
     * exists is safe: it simply produces nothing until one exists, then switches over. The
     * previous `get()`-based version captured a null peripheral once and stayed empty forever.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val incomingMessages: Flow<PeerMessage> =
        _connection.flatMapLatest { entry ->
            if (entry == null) {
                emptyFlow()
            } else {
                val (deviceId, peripheral) = entry
                peripheral.observe(messageCharacteristic)
                    .mapNotNull { frame ->
                        // Frames are reassembled before being decoded; a partial message
                        // yields null and is simply not emitted.
                        codec.decode(deviceId, frame, Clock.System.now().toEpochMilliseconds())
                            ?.toPeerMessage(deviceId)
                    }
                    .catch { e -> _lastError.value = "Lost message stream: ${e.message}" }
            }
        }

    override fun startScan() {
        if (scanJob?.isActive == true) return

        _scannedDevices.value = emptyList()
        advertisementCache.clear()
        _lastError.value = null

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
            .catch { e ->
                // Never swallow this: a failed scan is otherwise indistinguishable from
                // "no devices nearby", which is extremely hard to diagnose.
                _lastError.value = "Scan failed: ${e.message ?: e::class.simpleName}"
            }
            .launchIn(scope)
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    override fun clearError() {
        _lastError.value = null
    }

    override suspend fun connect(deviceId: String) {
        val advertisement = advertisementCache[deviceId]
        if (advertisement == null) {
            _lastError.value = "Device is no longer in range. Scan again."
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        _lastError.value = null
        _connectionState.value = ConnectionState.Connecting

        val p = Peripheral(advertisement)
        _connection.value = deviceId to p
        _connectedDeviceId.value = deviceId

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
                onConnected(p)
                return
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts - 1) {
                    delay(backoffDelays[attempt])
                }
            }
        }

        _connectionState.value = ConnectionState.Disconnected
        _connection.value = null
        _connectedDeviceId.value = null
        _lastError.value = describeConnectFailure(lastException)
        throw lastException!!
    }

    /**
     * A stale GATT service cache on a bonded device is by far the most common cause of a
     * "service not found" failure, and the raw exception gives the user no hint, so spell
     * the recovery out instead.
     */
    private fun describeConnectFailure(cause: Exception?): String {
        val serviceMissing = generateSequence(cause as Throwable?) { it.cause }
            .any { it.message?.contains("not found", ignoreCase = true) == true }
        return if (serviceMissing) {
            "Could not find the chat service on this device. If the two phones are paired, " +
                "Android may be serving a stale GATT cache - unpair them and try again."
        } else {
            val detail = cause?.message ?: cause?.let { it::class.simpleName } ?: "unknown error"
            "Connection failed after 3 attempts: $detail"
        }
    }

    override suspend fun disconnect() {
        val deviceId = _connection.value?.first
        _connection.value?.second?.disconnect()
        _connection.value = null
        _connectedDeviceId.value = null
        deviceId?.let(codec::forget)
        maxFrameSize = DEFAULT_MAX_FRAME_SIZE
    }

    /**
     * Settles the per-write budget and tells the peer who we are. Until the profile arrives the
     * other side only knows our Bluetooth address, which is not an identity.
     */
    private suspend fun onConnected(peripheral: Peripheral) {
        peripheral.requestLargeMtu()
        maxFrameSize = runCatching {
            peripheral.maximumWriteValueLengthForType(WriteType.WithResponse)
        }.getOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_FRAME_SIZE

        runCatching {
            writeEnvelope(
                peripheral,
                Envelope.Profile(
                    senderId = appPreferences.localPeerId,
                    displayName = appPreferences.displayName.first(),
                ),
            )
        }
    }

    override suspend fun send(messageId: String, body: String) {
        val p = _connection.value?.second ?: error("Not connected")
        writeEnvelope(
            p,
            Envelope.Text(
                senderId = appPreferences.localPeerId,
                messageId = messageId,
                sentAt = Clock.System.now().toEpochMilliseconds(),
                body = body,
            ),
        )
    }

    /** Writes an envelope as one or more frames, each within the negotiated MTU. */
    private suspend fun writeEnvelope(peripheral: Peripheral, envelope: Envelope) {
        codec.encode(envelope, maxFrameSize).forEach { frame ->
            peripheral.write(messageCharacteristic, frame, WriteType.WithResponse)
        }
    }

    override fun release() {
        stopScan()
        scope.cancel()
    }

    private companion object {
        /** ATT default MTU of 23 minus the 3-byte ATT header. */
        const val DEFAULT_MAX_FRAME_SIZE = 20
    }
}
