package app.aaps.plugins.eversense

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import app.aaps.plugins.eversense.enums.EversenseSecurityType
import app.aaps.plugins.eversense.exceptions.EversenseWriteException
import app.aaps.plugins.eversense.packets.Eversense365Communicator
import app.aaps.plugins.eversense.packets.EversenseBasePacket
import app.aaps.plugins.eversense.packets.EversenseE3Communicator
import app.aaps.plugins.eversense.packets.e365.AuthIdentityPacket
import app.aaps.plugins.eversense.packets.e365.AuthStartPacket
import app.aaps.plugins.eversense.packets.e365.AuthWhoAmIPacket
import app.aaps.plugins.eversense.packets.e365.Eversense365Packets
import app.aaps.plugins.eversense.packets.e365.KeepAlivePacket
import app.aaps.plugins.eversense.packets.e3.EversenseE3Packets
import app.aaps.plugins.eversense.packets.e3.SaveBondingInformationPacket
import app.aaps.plugins.eversense.util.EversenseCrypto365Util
import app.aaps.plugins.eversense.util.EversenseHttp365Util
import app.aaps.plugins.eversense.util.EversenseLogger
import app.aaps.plugins.eversense.util.StorageKeys
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.Throws

class EversenseGattCallback(
    private val plugin: EversenseCGMPlugin,
    private val preferences: SharedPreferences
) : BluetoothGattCallback() {

    companion object {
        private const val TAG = "EversenseGattCallback"

        const val serviceUUID = "c3230001-9308-47ae-ac12-3d030892a211"

        private const val requestUUID = "6eb0f021-a7ba-7e7d-66c9-6d813f01d273"
        private const val requestSecureV2UUID = "c3230002-9308-47ae-ac12-3d030892a211"

        private const val responseUUID = "6eb0f024-bd60-7aaa-25a7-0029573f4f23"
        private const val responseSecureV2UUID = "c3230003-9308-47ae-ac12-3d030892a211"
        private const val magicDescriptorUUID = "00002902-0000-1000-8000-00805f9b34fb"

        private const val WRITE_TIMEOUT_MS = 5000L
        private const val CALIBRATION_TIMEOUT_MS = 15000L

        // Number of consecutive authV2flow failures before abandoning the shortcut path
        // and forcing a full re-auth (WhoAmI + fleet certificate). This handles the case
        // where the BLE stack resets (e.g. charger plug-in) and the session key is lost.
        // A value of 3 allows transient glitches to recover without internet, while still
        // falling back to full auth after sustained failures.
        private const val SHORTCUT_FAIL_THRESHOLD = 3
    }

    // FIX 1: Dedicated BLE executor for callbacks; separate network executor for HTTP calls
    // so that network operations in authV2flow() cannot block BLE processing.
    private var bleExecutor = Executors.newSingleThreadExecutor()
    private var networkExecutor = Executors.newSingleThreadExecutor()

    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null
    private var eversenseBluetoothService: BluetoothGattService? = null
    private var requestCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null

    private var payloadSize: Int = 20
    private var security: EversenseSecurityType = EversenseSecurityType.None
    private var cryptoUtil = EversenseCrypto365Util(preferences)

    // Multi-notification response reassembly (SecureV2/365 only). Each notification is framed
    // with a chunk header: chunk 1 = [chunkIndex=1, totalChunks, 0x01] (3 bytes), chunk 2+ =
    // [chunkIndex, totalChunks] (2 bytes), followed by that chunk's slice of the [prefix+ciphertext]
    // blob. Most responses fit in one chunk, but bulk historical log reads can span several —
    // decrypting a lone chunk 1 of N>1 always fails the CCM MAC check since the auth tag covers
    // the full ciphertext, so chunks must be buffered until the full message has arrived.
    private var chunkAccumulator: ByteArray = ByteArray(0)
    private var chunkTotalExpected: Int = 1
    private var chunkNextIndex: Int = 1

    // FIX 2: Use AtomicReference for currentPacket to avoid the race condition where a stale
    // BLE notification could be processed against the wrong packet between assignment and write.
    var currentPacket: AtomicReference<EversenseBasePacket?> = AtomicReference(null)

    // FIX 3: Track connection state with a dedicated flag rather than relying on bluetoothGatt
    // being non-null, which is not a reliable indicator of actual connection state.
    @Volatile
    private var connected: Boolean = false
    private var transmitterReady: Boolean = false

    // Tracks consecutive status-19 failures to detect transmitter placement issues
    @Volatile
    private var failedConnectionAttempts: Int = 0
    private val PLACEMENT_WARNING_THRESHOLD = 3

    // Tracks consecutive general reconnect attempts (reset on successful connection).
    // Used to compute exponential backoff so AAPS retries quickly after boot (when the
    // official Eversense app temporarily holds the BLE connection) and backs off for
    // sustained failures to avoid draining the battery.
    @Volatile
    private var reconnectAttempts: Int = 0

    @Volatile
    private var autoReconnectEnabled: Boolean = true

    // Persistent reconnect: retries every 60s indefinitely while disconnected.
    // Android autoConnect gives up silently after ~30 min on Samsung devices.
    private val persistentReconnectRunnable = object : Runnable {
        override fun run() {
            if (autoReconnectEnabled && !connected) {
                EversenseLogger.info(TAG, "Persistent reconnect tick — still disconnected, retrying...")
                plugin.connect(null)
                handler.postDelayed(this, 60_000L)
            }
        }
    }

    // FIX 12: Tracks consecutive authV2flow failures while using the shortcut path.
    // After SHORTCUT_FAIL_THRESHOLD failures, disallowUseShortcut() is called to force
    // a full re-auth on the next connection. This handles BLE stack resets (e.g. charger
    // plug-in) that invalidate the session key without needing internet on every reconnect.
    @Volatile
    private var shortcutFailCount: Int = 0

    fun isConnected(): Boolean = connected && transmitterReady
    fun isBleConnected(): Boolean = connected
    fun is365(): Boolean = security == EversenseSecurityType.SecureV2

    // Submit a task to the bleExecutor and return a Future so callers can block until complete.
    // This ensures calibration and other ad-hoc BLE operations are serialised with Keep Alive
    // cycles and do not race with currentPacket assignment.
    fun submitToExecutor(task: () -> Unit): java.util.concurrent.Future<*> =
        bleExecutor.submit(task)

    fun enableAutoReconnect() {
        autoReconnectEnabled = true
    }

    fun trackGatt(gatt: BluetoothGatt) {
        bluetoothGatt = gatt
    }

    fun invalidateAuthentication() {
        cryptoUtil.disallowUseShortcut()
        shortcutFailCount = 0
    }

    // A user/plugin initiated disconnect must stop every scheduled reconnect. Internal recovery
    // paths call BluetoothGatt.disconnect() directly and therefore keep auto-reconnect enabled.
    @SuppressLint("MissingPermission")
    fun disconnect() {
        autoReconnectEnabled = false
        handler.removeCallbacksAndMessages(null)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connected = false
        transmitterReady = false
        currentPacket.set(null)
        EversenseLogger.info(TAG, "GATT disconnected and auto-reconnect disabled")
    }

    @SuppressLint("MissingPermission")
    fun cleanUp() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connected = false
        transmitterReady = false
        currentPacket.set(null)
        resetChunkAccumulator()
        bleExecutor.shutdownNow()
        bleExecutor = Executors.newSingleThreadExecutor()
        EversenseLogger.info(TAG, "GATT cleaned up before reconnect")
    }

    @SuppressLint("MissingPermission")
    fun readRssi() {
        bluetoothGatt?.readRemoteRssi() ?: EversenseLogger.warning(TAG, "Cannot read RSSI — not connected")
    }

    @SuppressLint("MissingPermission")
    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            EversenseLogger.debug(TAG, "RSSI: $rssi dBm")
            plugin.onRssiRead(rssi)
        } else {
            EversenseLogger.warning(TAG, "Failed to read RSSI - status: $status")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        EversenseLogger.info(TAG, "Connection state changed - status: $status, newState: $newState, device: ${gatt.device.name}")

        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            bluetoothGatt = gatt
            connected = true
            reconnectAttempts = 0
            failedConnectionAttempts = 0
            handler.removeCallbacks(persistentReconnectRunnable)

            preferences.edit(commit = true) {
                putString(StorageKeys.REMOTE_DEVICE_KEY, gatt.device.address)
            }

            handler.post {
                plugin.watchers.forEach { it.onConnectionChanged(true) }
            }

            if (!gatt.requestMtu(512)) {
                EversenseLogger.warning(TAG, "requestMtu returned false — skipping to discoverServices with default payload size")
                payloadSize = 20
                gatt.discoverServices()
            }
            return
        }

        if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            EversenseLogger.warning(TAG, "Disconnected or failed - status: $status, newState: $newState")

            if (status == 19 && is365() && autoReconnectEnabled) {
                connected = false
                transmitterReady = false
                handler.post {
                    plugin.watchers.forEach { it.onConnectionChanged(false) }
                }
                EversenseLogger.debug(TAG, "365 post-sync disconnect (status 19) — reusing GATT for reconnect")
                gatt.connect()
                return
            }

            gatt.close()
            bluetoothGatt = null
            connected = false
            transmitterReady = false

            handler.post {
                plugin.watchers.forEach { it.onConnectionChanged(false) }
            }

            if (status == 19) {
                failedConnectionAttempts++
                EversenseLogger.warning(TAG, "Connection terminated by transmitter (status 19) — attempt $failedConnectionAttempts")
                if (failedConnectionAttempts >= PLACEMENT_WARNING_THRESHOLD) {
                    handler.post { plugin.watchers.forEach { it.onTransmitterNotPlaced() } }
                }
            } else {
                failedConnectionAttempts = 0
            }

            val storedAddress = preferences.getString(StorageKeys.REMOTE_DEVICE_KEY, null)
            if (storedAddress != null && autoReconnectEnabled) {
                val delayMs: Long = when {
                    status == 19 -> 30_000L
                    status == BluetoothGatt.GATT_SUCCESS -> 5_000L
                    else -> {
                        val attempt = reconnectAttempts++
                        minOf(5_000L * (1L shl minOf(attempt, 4)), 60_000L)
                    }
                }
                EversenseLogger.info(TAG, "Scheduling auto-reconnect in ${delayMs / 1000}s (status: $status, attempt: $reconnectAttempts)")
                handler.postDelayed({
                    EversenseLogger.info(TAG, "Attempting auto-reconnect (attempt $reconnectAttempts)...")
                    plugin.connect(null)
                }, delayMs)
                handler.removeCallbacks(persistentReconnectRunnable)
                handler.postDelayed(persistentReconnectRunnable, 60_000L)
            } else {
                EversenseLogger.info(TAG, "Auto-reconnect skipped (enabled=$autoReconnectEnabled, storedAddress=${storedAddress != null})")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
        if (status != 0) {
            EversenseLogger.warning(TAG, "MTU negotiation failed (status: $status) — using default payload size of 20")
            payloadSize = 20
        } else {
            payloadSize = mtu - 3
        }
        EversenseLogger.debug(TAG, "New payload size: $payloadSize")

        val success = gatt?.discoverServices()
        EversenseLogger.info(TAG, "Trigger discover services - success: $success")
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        EversenseLogger.info(TAG, "Discovered services - status: $status")

        if (gatt == null) {
            EversenseLogger.error(TAG, "Gatt is null")
            return
        }

        val service = gatt.services.firstOrNull { it.uuid.toString() == serviceUUID }
        if (service == null) {
            EversenseLogger.error(TAG, "Required service not found -> disconnecting")
            gatt.disconnect()
            return
        }

        eversenseBluetoothService = service
        if (service.characteristics.isEmpty()) {
            EversenseLogger.error(TAG, "Service has no characteristics -> disconnecting")
            gatt.disconnect()
            return
        }

        var requestChar = service.characteristics.find { it.uuid.toString() == requestUUID }
        var responseChar = service.characteristics.find { it.uuid.toString() == responseUUID }
        if (requestChar != null && responseChar != null) {
            EversenseLogger.info(TAG, "Connected to Eversense E3!")
            security = EversenseSecurityType.None
            requestCharacteristic = requestChar
            responseCharacteristic = responseChar

            gatt.setCharacteristicNotification(requestChar, true)
            gatt.setCharacteristicNotification(responseChar, true)
            enableNotify(gatt, responseChar)
            return
        }

        requestChar = service.characteristics.find { it.uuid.toString() == requestSecureV2UUID }
        responseChar = service.characteristics.find { it.uuid.toString() == responseSecureV2UUID }
        if (requestChar == null || responseChar == null) {
            EversenseLogger.error(TAG, "No Eversense request/response characteristic found -> disconnecting")
            gatt.disconnect()
            return
        }

        EversenseLogger.info(TAG, "Connected to Eversense 365!")
        security = EversenseSecurityType.SecureV2
        requestCharacteristic = requestChar
        responseCharacteristic = responseChar

        gatt.setCharacteristicNotification(requestChar, true)
        gatt.setCharacteristicNotification(responseChar, true)
        enableNotify(gatt, responseChar)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        EversenseLogger.debug(TAG, "onDescriptorWrite (${descriptor.uuid}) for characteristic (${descriptor.characteristic.uuid}) - status $status")

        if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid.toString() == magicDescriptorUUID) {
            if (descriptor.characteristic.uuid.toString() == responseUUID) {
                bleExecutor.submit { authE3flow() }
            } else if (descriptor.characteristic.uuid.toString() == responseSecureV2UUID) {
                bleExecutor.submit { authV2flow() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalStdlibApi::class)
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        handleCharacteristicChanged(gatt, value)
    }

    @Deprecated("Deprecated in API 33 — overridden for compatibility with Android < 13")
    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalStdlibApi::class)
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        handleCharacteristicChanged(gatt, characteristic.value)
    }

    private fun resetChunkAccumulator() {
        chunkAccumulator = ByteArray(0)
        chunkTotalExpected = 1
        chunkNextIndex = 1
    }

    private fun accumulateChunk(rawData: ByteArray): ByteArray? {
        if (rawData.size < 2) {
            EversenseLogger.warning(TAG, "Chunk too short to contain a header - size: ${rawData.size}")
            resetChunkAccumulator()
            return null
        }

        val chunkIndex = rawData[0].toInt() and 0xFF
        val totalChunks = rawData[1].toInt() and 0xFF
        if (totalChunks == 0 || chunkIndex !in 1..totalChunks) {
            EversenseLogger.warning(TAG, "Invalid chunk header - chunk $chunkIndex of $totalChunks")
            resetChunkAccumulator()
            return null
        }

        val headerSize = if (chunkIndex == 1) 3 else 2
        if (rawData.size < headerSize) {
            EversenseLogger.warning(TAG, "Chunk $chunkIndex/$totalChunks has a truncated header - size: ${rawData.size}")
            resetChunkAccumulator()
            return null
        }

        if (chunkIndex == 1) {
            if (chunkNextIndex != 1) {
                EversenseLogger.warning(TAG, "New chunk sequence started before previous one (chunk $chunkNextIndex/$chunkTotalExpected) completed - discarding partial data")
            }
            chunkAccumulator = rawData.copyOfRange(headerSize, rawData.size)
            chunkTotalExpected = totalChunks
            chunkNextIndex = 2
        } else {
            if (chunkIndex != chunkNextIndex || totalChunks != chunkTotalExpected) {
                EversenseLogger.warning(TAG, "Out-of-sequence chunk (got $chunkIndex/$totalChunks, expected $chunkNextIndex/$chunkTotalExpected) - discarding in-progress message")
                resetChunkAccumulator()
                return null
            }
            chunkAccumulator += rawData.copyOfRange(headerSize, rawData.size)
            chunkNextIndex++
        }

        if (chunkNextIndex <= chunkTotalExpected) return null

        val complete = chunkAccumulator
        resetChunkAccumulator()
        if (complete.isEmpty()) {
            EversenseLogger.warning(TAG, "Completed chunk sequence contained no payload")
            return null
        }
        return complete
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalStdlibApi::class)
    private fun handleCharacteristicChanged(gatt: BluetoothGatt, rawData: ByteArray) {
        if (rawData.isEmpty()) {
            EversenseLogger.warning(TAG, "Ignoring empty BLE notification")
            return
        }
        EversenseLogger.debug(TAG, "Received data: ${rawData.toHexString()}")

        var data = rawData
        if (security == EversenseSecurityType.SecureV2) {
            data = accumulateChunk(rawData) ?: return

            if (data[0] != Eversense365Packets.AuthenticateResponseId) {
                data = cryptoUtil.decrypt(data)
                EversenseLogger.debug(TAG, "Decrypted data -> ${data.toHexString()}")
                if (data.isEmpty()) {
                    EversenseLogger.error(TAG, "Failed to decrypt data — disconnecting, will retry shortcut on next connection")
                    gatt.disconnect()
                    return
                }
            }
        }

        if (!is365() && EversenseE3Packets.isPushPacket(data[0])) {
            EversenseLogger.debug(TAG, "Keep Alive packet received (E3)!")
            bleExecutor.submit {
                try {
                    val currentDatetime = writePacket<app.aaps.plugins.eversense.packets.e3.GetCurrentDatetimePacket.Response>(
                        app.aaps.plugins.eversense.packets.e3.GetCurrentDatetimePacket()
                    )
                    if (currentDatetime.needsTimeSync) {
                        EversenseLogger.info(TAG, "Clock drift detected before glucose read — syncing transmitter clock")
                        writePacket<app.aaps.plugins.eversense.packets.e3.SetCurrentDatetimePacket.Response>(
                            app.aaps.plugins.eversense.packets.e3.SetCurrentDatetimePacket()
                        )
                    }
                } catch (e: Exception) {
                    EversenseLogger.warning(TAG, "Pre-glucose clock sync failed (non-fatal): $e")
                }
                EversenseE3Communicator.readGlucose(this, preferences, plugin.watchers)
                EversenseE3Communicator.fullSync(this, preferences, plugin.watchers)
            }
            return
        }

        if (data.size >= 2 && Eversense365Packets.isKeepAlivePacket(data[0], data[1])) {
            EversenseLogger.debug(TAG, "Keep Alive packet received (365)!")

            val packet = KeepAlivePacket()
            packet.appendData(data.toUByteArray())
            val response = packet.parseResponse() ?: return

            val fourHalfMinAgo = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(270)
            bleExecutor.submit {
                if (response.glucoseDatetime > fourHalfMinAgo) {
                    Eversense365Communicator.readGlucose(this, preferences, plugin.watchers)
                    Eversense365Communicator.fullSync(this, preferences, plugin.watchers)
                }
            }
            return
        } else if (data.size >= 4 && data[0] == Eversense365Packets.NotificationResponseId && data[1] == 0x03.toByte()) {
            val alarmCode = data[2].toInt() and 0xFF
            val alarm = app.aaps.plugins.eversense.models.ActiveAlarm(
                code = app.aaps.plugins.eversense.enums.EversenseAlarm.from(alarmCode),
                codeRaw = alarmCode,
                flag = 0,
                priority = 0
            )
            EversenseLogger.info(TAG, "Push alarm received: ${alarm.code.title}")
            handler.post {
                plugin.watchers.forEach { it.onAlarmReceived(alarm) }
            }
            return
        } else if (Eversense365Packets.isNotificationPacket(data[0])) {
            EversenseLogger.warning(TAG, "Unknown notification packet received")
            return
        }

        if (security == EversenseSecurityType.SecureV2 && data.size < 2) {
            EversenseLogger.warning(TAG, "Ignoring truncated secure response: ${data.size} byte(s)")
            return
        }

        val packet = currentPacket.get() ?: run {
            EversenseLogger.warning(TAG, "currentPacket is null -> skipping packet")
            return
        }

        synchronized(packet) {
            val packetAnnotation = packet.getAnnotation() ?: run {
                EversenseLogger.warning(TAG, "annotation is null -> skipping packet")
                return
            }

            if (EversenseE3Packets.isErrorPacket(data[0]) && packetAnnotation.responseId != data[0]) {
                EversenseLogger.error(TAG, "Received error response - data: ${data.toHexString()}")
                packet.isErrorResponse = true
                packet.responseReceived = true
                packet.notifyAll()
                return
            }

            if (security == EversenseSecurityType.None) {
                if (!packet.skipResponseIdValidation && packetAnnotation.responseId != data[0]) {
                    EversenseLogger.warning(TAG, "Incorrect responseId - expected: ${packetAnnotation.responseId}, got: ${data[0]}")
                    return
                }
                packet.appendData(data.toUByteArray())
                packet.responseReceived = true
                packet.notifyAll()
            } else {
                if (packetAnnotation.responseId != data[0]) {
                    EversenseLogger.warning(TAG, "Incorrect responseId - expected: ${packetAnnotation.responseId}, got: ${data[0]}")
                    return
                }
                if (packetAnnotation.typeId != data[1]) {
                    EversenseLogger.warning(TAG, "Incorrect responseType - expected: ${packetAnnotation.typeId}, got: ${data[1]}")
                    return
                }
                packet.appendData(data.toUByteArray())
                packet.responseReceived = true
                packet.notifyAll()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalStdlibApi::class)
    @Throws(EversenseWriteException::class)
    fun <T : EversenseBasePacket.Response> writePacket(packet: EversenseBasePacket, timeoutMs: Long = WRITE_TIMEOUT_MS): T {
        val gatt = bluetoothGatt ?: throw EversenseWriteException("Gatt is null — not connected")
        val requestCharacteristic = requestCharacteristic
            ?: throw EversenseWriteException("requestCharacteristic is null")
        val requestData = packet.buildRequest(cryptoUtil, payloadSize)
            ?: throw EversenseWriteException("Failed to build request data")

        currentPacket.set(packet)
        try {
            EversenseLogger.debug(TAG, "Writing data: ${requestData.toHexString()}")
            @Suppress("DEPRECATION")
            requestCharacteristic.setValue(requestData)
            @Suppress("DEPRECATION")
            if (!gatt.writeCharacteristic(requestCharacteristic)) {
                throw EversenseWriteException("Bluetooth stack rejected characteristic write")
            }

            synchronized(packet) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!packet.responseReceived && !packet.isErrorResponse) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) {
                        throw EversenseWriteException(
                            "Timed out waiting for response after ${timeoutMs}ms — packet: ${packet.getAnnotation()?.responseId}"
                        )
                    }
                    try {
                        packet.wait(remaining)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw EversenseWriteException("Interrupted while waiting for transmitter response")
                    }
                }
                if (packet.isErrorResponse) {
                    throw EversenseWriteException("Transmitter returned error response — packet: ${packet.getAnnotation()?.responseId}")
                }
            }

            return packet.parseResponse() as? T
                ?: throw EversenseWriteException("Unable to parse response — packet: ${packet.getAnnotation()?.responseId}")
        } catch (e: EversenseWriteException) {
            throw e
        } catch (e: Exception) {
            throw EversenseWriteException("Failed to process response: $e")
        } finally {
            currentPacket.compareAndSet(packet, null)
        }
    }

    private fun authE3flow() {
        EversenseLogger.info(TAG, "Starting auth flow E3...")
        try {
            writePacket<SaveBondingInformationPacket.SaveBondingInformationResponse>(SaveBondingInformationPacket())
        } catch (exception: Exception) {
            EversenseLogger.error(TAG, "Auth flow E3 failed: $exception")
            return
        }

        EversenseLogger.info(TAG, "E3 auth complete — notifying watchers")
        transmitterReady = true
        handler.post { plugin.watchers.forEach { it.onTransmitterReady() } }
    }

    @SuppressLint("MissingPermission")
    private fun authV2flow() {
        try {
            if (!cryptoUtil.generateKeyPairIfNotExists()) {
                bluetoothGatt?.disconnect()
                return
            }

            if (!cryptoUtil.canUseShortcut()) {
                val clientId = cryptoUtil.getClientId()
                val whoAmI = writePacket<AuthWhoAmIPacket.Response>(AuthWhoAmIPacket(clientId))

                if (plugin.username.isNotEmpty() && plugin.password.isNotEmpty()) {
                    val stateJson = preferences.getString(StorageKeys.SECURE_STATE, null) ?: "{}"
                    val secureState = kotlinx.serialization.json.Json.decodeFromString<app.aaps.plugins.eversense.models.EversenseSecureState>(stateJson)
                    secureState.username = plugin.username
                    secureState.password = plugin.password
                    preferences.edit()
                        .putString(
                            StorageKeys.SECURE_STATE,
                            kotlinx.serialization.json.Json.encodeToString(
                                app.aaps.plugins.eversense.models.EversenseSecureState.serializer(),
                                secureState
                            )
                        )
                        .apply()
                }

                val authSession = networkExecutor.submit {
                    EversenseHttp365Util.login(preferences)
                }.get() ?: throw EversenseWriteException("E365 DMS login failed")

                val expiryMs = System.currentTimeMillis() + (authSession.expires_in * 1000L)
                preferences.edit()
                    .putString(StorageKeys.ACCESS_TOKEN, authSession.access_token)
                    .putLong(StorageKeys.ACCESS_TOKEN_EXPIRY, expiryMs)
                    .apply()

                val fleetResponse = networkExecutor.submit {
                    EversenseHttp365Util.getFleetSecretV2(
                        accessToken = authSession.access_token,
                        serialNumber = whoAmI.serialNumber,
                        nonce = whoAmI.nonce,
                        flags = whoAmI.flags,
                        publicKey = cryptoUtil.getClientPublicKey()
                    )
                }.get() ?: throw EversenseWriteException("E365 transmitter certificate request failed")

                val certificate = fleetResponse.Result.Certificate
                    ?: throw EversenseWriteException("E365 transmitter certificate was empty")
                @OptIn(ExperimentalStdlibApi::class)
                writePacket<AuthIdentityPacket.Response>(AuthIdentityPacket(certificate.hexToByteArray()))
                cryptoUtil.allowUseShortcut()
            }

            val signature = cryptoUtil.generateEphem()
                ?: throw EversenseWriteException("Failed to generate E365 ephemeral signature")
            val session = writePacket<AuthStartPacket.Response>(AuthStartPacket(cryptoUtil.getStartSecret(signature)))
            if (!cryptoUtil.generateSessionKey(session.sessionPublicKey)) {
                throw EversenseWriteException("Failed to derive E365 session key")
            }

            shortcutFailCount = 0
            EversenseLogger.info(TAG, "E365 authentication complete")
            Eversense365Communicator.fullSync(this, preferences, plugin.watchers, force = true)
            try {
                Eversense365Communicator.readGlucose(this, preferences, plugin.watchers)
            } catch (e: Exception) {
                EversenseLogger.warning(TAG, "E365 read after authentication failed (non-fatal): $e")
            }
            transmitterReady = true
            handler.post { plugin.watchers.forEach { it.onTransmitterReady() } }
        } catch (exception: Exception) {
            EversenseLogger.error(TAG, "E365 authentication failed: $exception")
            if (cryptoUtil.canUseShortcut()) {
                shortcutFailCount++
                if (shortcutFailCount >= SHORTCUT_FAIL_THRESHOLD) {
                    EversenseLogger.warning(TAG, "E365 shortcut failed repeatedly — forcing full authentication on next connection")
                    cryptoUtil.disallowUseShortcut()
                    shortcutFailCount = 0
                }
            }
            bluetoothGatt?.disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun enableNotify(gatt: BluetoothGatt, responseCharacteristic: BluetoothGattCharacteristic) {
        val descriptor = responseCharacteristic.getDescriptor(UUID.fromString(magicDescriptorUUID)) ?: return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }
}
