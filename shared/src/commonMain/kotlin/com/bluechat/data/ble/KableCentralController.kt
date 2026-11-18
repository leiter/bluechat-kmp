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
import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    private val _reconnectAttempt = MutableStateFlow(0)
    override val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    override val rssi: StateFlow<Int?> = _rssi.asStateFlow()

    /** The peripheral we are connected to, paired with its identifier. */
    private val _connection = MutableStateFlow<Pair<String, Peripheral>?>(null)

    private val _connectedDeviceId = MutableStateFlow<String?>(null)
    override val connectedDeviceId: StateFlow<String?> = _connectedDeviceId.asStateFlow()

    private var scanJob: Job? = null
    private var stateObserverJob: Job? = null
    private var reconnectJob: Job? = null
    private var rssiPollingJob: Job? = null

    private var autoReconnectEnabled = true
    private var intentionalDisconnect = false
    private var currentDeviceId: String? = null

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
     *
     * Because it keys off [_connection], an auto-reconnect that swaps in a new peripheral also
     * re-subscribes here -- which is why the reconnect path must publish to [_connection].
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
        _lastError.value = null
        setScanHold(SCAN_HOLDER_PICKER, true)
    }

    override fun stopScan() {
        setScanHold(SCAN_HOLDER_PICKER, false)
    }

    /**
     * All scan state is touched on this one serial dispatcher, so holders, the scan job and
     * the pending stop never race, without a lock.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scanControl = Dispatchers.Default.limitedParallelism(1)
    private val scanHolders = mutableSetOf<String>()
    private var pendingScanStop: Job? = null

    override fun setScanHold(holder: String, active: Boolean) {
        scope.launch(scanControl) {
            if (active) scanHolders += holder else scanHolders -= holder

            if (scanHolders.isNotEmpty()) {
                pendingScanStop?.cancel()
                pendingScanStop = null
                // Also restarts a scan that died, e.g. on missing permission: the screen
                // re-acquires its hold when it resumes after the permission dialog.
                if (scanJob?.isActive != true) launchScan()
            } else if (scanJob?.isActive == true && pendingScanStop == null) {
                // Stop a little later, so moving between two screens that both hold the scan
                // does not restart it. Android silently stops delivering results to an app
                // that starts more than five scans in 30 seconds.
                pendingScanStop = scope.launch(scanControl) {
                    delay(SCAN_STOP_GRACE_MILLIS)
                    if (scanHolders.isEmpty()) {
                        scanJob?.cancel()
                        scanJob = null
                    }
                    pendingScanStop = null
                }
            }
        }
    }

    private fun launchScan() {
        scanJob = scope.launch {
            // Results age out instead of being cleared on every start (defect #12): a device
            // that stopped advertising leaves the list, one still in range stays.
            val pruning = launch {
                while (true) {
                    pruneStaleDevices()
                    delay(SCAN_PRUNE_INTERVAL_MILLIS)
                }
            }
            try {
                scanner.advertisements.collect { advertisement ->
                    val id = advertisement.identifier.toString()
                    advertisementCache[id] = advertisement
                    _scannedDevices.update { devices ->
                        val existing = devices.indexOfFirst { it.identifier == id }
                        val device = BleDevice(
                            identifier = id,
                            // `peripheralName` is what the device is broadcasting right now;
                            // `name` is the OS's cached name for that address, which can be stale
                            // for as long as the cache lives. Preferring the cache made a peer go
                            // on showing a name it had stopped advertising -- visible as soon as
                            // B4 stopped renaming the adapter, when a server advertising "Pixel 2"
                            // still listed as "Bluechat" on a phone that had seen the old build.
                            name = advertisement.peripheralName ?: advertisement.name,
                            rssi = advertisement.rssi,
                            lastSeenAt = Clock.System.now().toEpochMilliseconds(),
                        )
                        if (existing >= 0) {
                            devices.toMutableList().apply { set(existing, device) }
                        } else {
                            devices + device
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never swallow this: a failed scan is otherwise indistinguishable from
                // "no devices nearby", which is extremely hard to diagnose.
                _lastError.value = "Scan failed: ${e.message ?: e::class.simpleName}"
            } finally {
                // Ends the job, so the next hold starts a fresh scan.
                pruning.cancel()
            }
        }
    }

    private fun pruneStaleDevices() {
        val cutoff = Clock.System.now().toEpochMilliseconds() - BleDevice.NEARBY_WINDOW_MILLIS
        _scannedDevices.update { devices -> devices.filter { it.lastSeenAt >= cutoff } }
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

        intentionalDisconnect = false
        currentDeviceId = deviceId
        _reconnectAttempt.value = 0

        val p = Peripheral(advertisement)
        _connection.value = deviceId to p
        _connectedDeviceId.value = deviceId
        observePeripheralState(p)

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
        currentDeviceId = null
        _lastError.value = describeConnectFailure(lastException)
        throw lastException!!
    }

    /**
     * Mirrors kable's connection state, and turns an *unexpected* drop into a reconnect.
     * A disconnect the user asked for is not retried, which is what [intentionalDisconnect]
     * distinguishes.
     */
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
                        startAutoReconnect()
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        }.launchIn(scope)
    }

    @OptIn(ExperimentalApi::class)
    private fun startRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = scope.launch {
            while (true) {
                runCatching {
                    val p = _connection.value?.second
                    if (p != null && _connectionState.value == ConnectionState.Connected) {
                        _rssi.value = p.rssi()
                    }
                }
                delay(RSSI_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopRssiPolling() {
        rssiPollingJob?.cancel()
        rssiPollingJob = null
    }

    /**
     * Walks an exponential backoff trying to get the same peer back.
     *
     * Note this re-runs [onConnected] on success. A reconnect produces a *new* GATT connection
     * with the MTU back at the 23-byte default and a peer that has forgotten who we are, so
     * skipping that would silently drop every later message back to 20-byte frames.
     */
    private fun startAutoReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            for (attempt in 0 until MAX_RECONNECT_ATTEMPTS) {
                _reconnectAttempt.value = attempt + 1
                _connectionState.value = ConnectionState.Reconnecting

                val deviceId = currentDeviceId ?: break

                if (advertisementCache[deviceId] == null) {
                    // Lost the advertisement; go looking for it again. Its own hold, so this
                    // neither stops nor is stopped by the picker's or the chat list's scan.
                    setScanHold(SCAN_HOLDER_RECONNECT, true)
                    delay(RECONNECT_SCAN_MILLIS)
                    setScanHold(SCAN_HOLDER_RECONNECT, false)
                }

                val ad = advertisementCache[deviceId]
                if (ad != null) {
                    val reconnected = runCatching {
                        val p = Peripheral(ad)
                        // Publish before connecting so incomingMessages re-subscribes to the
                        // new peripheral rather than the dead one.
                        _connection.value = deviceId to p
                        observePeripheralState(p)
                        p.connect()
                        onConnected(p)
                        true
                    }.getOrDefault(false)

                    if (reconnected) {
                        _reconnectAttempt.value = 0
                        return@launch
                    }
                }

                if (attempt < MAX_RECONNECT_ATTEMPTS - 1) {
                    delay(RECONNECT_BACKOFF_MILLIS[attempt])
                }
            }

            _connectionState.value = ConnectionState.Disconnected
            _reconnectAttempt.value = 0
            _lastError.value = "Lost the connection and could not get it back."
            _connection.value = null
            _connectedDeviceId.value = null
            currentDeviceId = null
        }
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
        intentionalDisconnect = true
        reconnectJob?.cancel()
        _reconnectAttempt.value = 0
        stopRssiPolling()
        _rssi.value = null

        val deviceId = _connection.value?.first
        _connection.value?.second?.disconnect()
        _connection.value = null
        _connectedDeviceId.value = null
        currentDeviceId = null
        deviceId?.let(codec::forget)
        maxFrameSize = DEFAULT_MAX_FRAME_SIZE
    }

    override fun disconnectIntentionally() {
        scope.launch { runCatching { disconnect() } }
    }

    override fun enableAutoReconnect(enabled: Boolean) {
        autoReconnectEnabled = enabled
        if (!enabled) {
            reconnectJob?.cancel()
            _reconnectAttempt.value = 0
        }
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

    override suspend fun sendReadReceipt(messageId: String) {
        val p = _connection.value?.second ?: return
        runCatching {
            writeEnvelope(
                p,
                Envelope.ReadReceipt(senderId = appPreferences.localPeerId, messageId = messageId),
            )
        }
    }

    override suspend fun sendTypingIndicator(isTyping: Boolean) {
        val p = _connection.value?.second ?: return
        // Best effort by design: a dropped typing signal is not worth surfacing to the user.
        runCatching {
            writeEnvelope(
                p,
                Envelope.Typing(senderId = appPreferences.localPeerId, isTyping = isTyping),
            )
        }
    }

    /**
     * Writes an envelope as one or more frames, each within the negotiated MTU.
     *
     * [WriteType.WithResponse] is required, not a preference: the characteristic only declares
     * PROPERTY_WRITE, so kable's default of WithoutResponse makes every send fail.
     */
    private suspend fun writeEnvelope(peripheral: Peripheral, envelope: Envelope) {
        codec.encode(envelope, maxFrameSize).forEach { frame ->
            peripheral.write(messageCharacteristic, frame, WriteType.WithResponse)
        }
    }

    override fun release() {
        scanJob?.cancel()
        stopRssiPolling()
        reconnectJob?.cancel()
        stateObserverJob?.cancel()
        scope.cancel()
    }

    private companion object {
        /** ATT default MTU of 23 minus the 3-byte ATT header. */
        const val DEFAULT_MAX_FRAME_SIZE = 20
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val RSSI_POLL_INTERVAL_MILLIS = 2000L
        const val RECONNECT_SCAN_MILLIS = 2000L
        const val SCAN_STOP_GRACE_MILLIS = 2000L
        const val SCAN_PRUNE_INTERVAL_MILLIS = 5000L
        const val SCAN_HOLDER_PICKER = "picker"
        const val SCAN_HOLDER_RECONNECT = "reconnect"
        val RECONNECT_BACKOFF_MILLIS = longArrayOf(1000L, 2000L, 4000L, 8000L, 16000L)
    }
}
