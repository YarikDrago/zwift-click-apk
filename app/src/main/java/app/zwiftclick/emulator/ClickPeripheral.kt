package app.zwiftclick.emulator

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

// MainActivity checks both runtime permissions. Guard BLE calls against concurrent revocation.
@SuppressLint("MissingPermission")
internal class ClickPeripheral(
    private val context: Context,
    private val manager: BluetoothManager,
    private val log: (String) -> Unit,
    private val changed: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    var advertising = "Stopped"
        private set
    val active get() = session != null
    val clientCount get() = session?.clients?.size ?: 0
    val gearReady get() = session?.clients?.values?.any { it.gearReady } == true

    fun start(adapter: BluetoothAdapter, advertiser: BluetoothLeAdvertiser) {
        if (active) return
        val attempt = Session(advertiser)
        session = attempt
        updateStatus("Starting GATT")
        guarded {
            val includeName = adapter.name == ClickProfile.NAME
            if (includeName) {
                attempt.scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
            } else {
                log("Scan response name omitted. Set the phone's Bluetooth name to ${ClickProfile.NAME} to include it.")
            }

            val server = manager.openGattServer(context, attempt.gattCallback)
            if (server == null) {
                finish("Failed", "GATT server initialization failed: openGattServer returned null")
                return@guarded
            }
            attempt.server = server
            log("GATT server opened")
            if (server.addService(ClickProfile.service())) {
                main.postDelayed(attempt.startupTimeout, 12_000)
            } else {
                finish("Failed", "GATT addService rejected")
            }
        }
    }

    fun stop(reason: String) {
        if (active) finish("Stopped", reason)
    }

    fun press(button: RideButton) {
        val label = "Button ${button.name} (0x${button.mask.toString(16).padStart(4, '0')})"
        queueButton(label, RideButtons.frame(button), RideButtons.frame())
    }

    fun press(button: ClickButton) {
        queueButton("Click ${button.name} (0x37)", ClickButtons.frame(button), ClickButtons.frame())
    }

    private fun queueButton(label: String, pressed: ByteArray, released: ByteArray) {
        val current = session ?: return
        val gatt = current.server ?: return
        guarded { current.press(gatt, label, pressed, released) }
    }

    private fun updateStatus(status: String) {
        advertising = status
        changed()
    }

    private fun finish(status: String, message: String) {
        val ended = session ?: return
        session = null
        ended.cancelTimers()
        log(message)
        if (ended.advertisingRequested) {
            release("stopAdvertising") { ended.advertiser.stopAdvertising(ended.advertiseCallback) }
        }
        ended.server?.let { server ->
            for (device in ended.clients.keys) {
                release("cancelConnection") { server.cancelConnection(device) }
            }
            release("GATT close") { server.close() }
        }
        updateStatus(status)
    }

    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: RuntimeException) {
            finish("Failed", "Bluetooth operation failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private inline fun release(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (e: RuntimeException) {
            log("$operation failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private inner class Session(val advertiser: BluetoothLeAdvertiser) {
        var server: BluetoothGattServer? = null
        var scanResponse: AdvertiseData? = null
        var advertisingRequested = false
        val clients = mutableMapOf<BluetoothDevice, Client>()
        private val outgoing = ArrayDeque<Outgoing>()
        private var inFlight: Outgoing? = null
        private var nextTransmissionAt = 0L
        val startupTimeout = Runnable {
            if (session === this) finish("Failed", "$advertising timed out waiting for Android callback")
        }
        private val resumeTransmissions = Runnable {
            if (session === this) server?.let { gatt -> guarded { sendNext(gatt) } }
        }
        private val keepAlive = Runnable {
            if (session === this) server?.let { gatt -> guarded { sendKeepAlive(gatt) } }
        }
        private val transmissionTimeout = Runnable {
            if (session === this) {
                finish("Failed", "${inFlight?.kind?.label} timed out waiting for Android callback")
            }
        }

        fun cancelTimers() {
            main.removeCallbacks(startupTimeout)
            main.removeCallbacks(resumeTransmissions)
            main.removeCallbacks(transmissionTimeout)
            main.removeCallbacks(keepAlive)
        }

        fun press(gatt: BluetoothGattServer, label: String, pressed: ByteArray, released: ByteArray) {
            val targets = clients.filterValues { it.gearReady }
            if (targets.isEmpty()) return
            val lastPressAt = SystemClock.uptimeMillis()
            scheduleKeepAlive(lastPressAt)
            outgoing.removeAll { it.kind == Kind.KEEP_ALIVE }
            if (outgoing.size + targets.size * 2 > MAX_QUEUED_FRAMES) {
                log("$label skipped: Bluetooth send queue is full")
                return
            }

            for ((device, client) in targets) {
                outgoing.addLast(Outgoing(device, client, Kind.PRESS, pressed))
                outgoing.addLast(Outgoing(device, client, Kind.RELEASE, released))
            }
            log("$label queued")
            sendNext(gatt)
        }

        private fun scheduleKeepAlive(from: Long) {
            main.removeCallbacks(keepAlive)
            main.postAtTime(keepAlive, from + KEEP_ALIVE_INTERVAL_MS)
        }

        private fun sendKeepAlive(gatt: BluetoothGattServer) {
            val targets = clients.filterValues { it.gearReady }
            if (targets.isEmpty()) return
            if (inFlight != null || outgoing.isNotEmpty()) {
                main.postDelayed(keepAlive, GEAR_PULSE_MS)
                return
            }

            val neutral = ClickButtons.frame()
            for ((device, client) in targets) {
                outgoing.addLast(Outgoing(device, client, Kind.KEEP_ALIVE, neutral))
            }
            scheduleKeepAlive(SystemClock.uptimeMillis())
            sendNext(gatt)
        }

        private fun cancelKeepAliveWithoutReadyClients() {
            if (clients.values.none { it.gearReady }) main.removeCallbacks(keepAlive)
        }

        // Serialize platform callbacks with Start, Stop, and Activity teardown.
        private fun callback(action: (BluetoothGattServer) -> Unit) {
            main.post {
                if (session === this) server?.let { gatt -> guarded { action(gatt) } }
            }
        }

        val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = callback {
                main.removeCallbacks(startupTimeout)
                log("Advertising started (mode=${settingsInEffect.mode}, connectable=${settingsInEffect.isConnectable})")
                updateStatus("Started")
            }

            override fun onStartFailure(errorCode: Int) = callback {
                finish("Failed", "Advertising failed: ${advertisingError(errorCode)} ($errorCode)")
            }
        }

        val gattCallback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) = callback {
                log("GATT service added callback: ${service.uuid}, status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS || service.uuid != ClickProfile.SERVICE) {
                    finish("Failed", "Click GATT service registration failed")
                    return@callback
                }
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build()
                val name = if (scanResponse == null) "none" else ClickProfile.NAME
                log("Requesting legacy advertising: service FC82, manufacturer 094A / 09 42 42, scan response name=$name")
                updateStatus("Starting advertising")
                advertisingRequested = true
                advertiser.startAdvertising(settings, ClickProfile.advertisement(), scanResponse, advertiseCallback)
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = callback { gatt ->
                val state = when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> "connected"
                    BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                    else -> "state $newState"
                }
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    clients.getOrPut(device) { Client() }
                } else {
                    clients.remove(device)?.let { client ->
                        outgoing.removeAll { it.client === client }
                        if (inFlight?.client === client) {
                            inFlight = null
                            main.removeCallbacks(transmissionTimeout)
                        }
                    }
                    cancelKeepAliveWithoutReadyClients()
                }
                log("GATT client $state (status=$status, clients=${clients.size})")
                changed()
                sendNext(gatt)
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = callback {
                log("GATT MTU changed to $mtu")
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
            ) = callback { gatt ->
                log("Characteristic read: ${characteristic.uuid}, offset=$offset")
                respond(gatt, device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) = callback { gatt ->
                log("Characteristic write: ${characteristic.uuid}, offset=$offset, prepared=$preparedWrite, data=${value.hex()}")
                val status = when {
                    characteristic.uuid != ClickProfile.WRITE -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    preparedWrite -> ATT_REQUEST_NOT_SUPPORTED
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                if (responseNeeded && !respond(gatt, device, requestId, status, offset)) return@callback
                if (status == BluetoothGatt.GATT_SUCCESS && ClickProfile.isRideOn(value)) sendRideOn(gatt, device)
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
            ) = callback { gatt ->
                val uuid = descriptor.characteristic.uuid
                log("Descriptor read: ${descriptor.uuid} on $uuid, offset=$offset")
                val value = clients[device]?.subscriptions?.get(uuid) ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                when {
                    descriptor.uuid != ClickProfile.CCCD ->
                        respond(gatt, device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset)
                    offset !in 0..value.size ->
                        respond(gatt, device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset)
                    else -> respond(gatt, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) = callback { gatt ->
                val uuid = descriptor.characteristic.uuid
                val client = clients[device]
                log("Descriptor write: ${descriptor.uuid} on $uuid, offset=$offset, prepared=$preparedWrite, data=${value.hex()}")
                val enabled = when (uuid) {
                    ClickProfile.INDICATE -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    ClickProfile.NOTIFY -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else -> null
                }
                val status = when {
                    descriptor.uuid != ClickProfile.CCCD || enabled == null -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    preparedWrite -> ATT_REQUEST_NOT_SUPPORTED
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    value.size != 2 -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                    !value.contentEquals(enabled) && !value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) ->
                        ATT_VALUE_NOT_ALLOWED
                    client == null -> BluetoothGatt.GATT_FAILURE
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                if (responseNeeded && !respond(gatt, device, requestId, status, offset)) return@callback
                if (status == BluetoothGatt.GATT_SUCCESS && client != null) {
                    val wasReady = client.gearReady
                    client.subscriptions[uuid] = value.copyOf()
                    updateGearReadiness(device, client, wasReady)
                    sendNext(gatt)
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = callback { gatt ->
                log("Execute write: execute=$execute")
                val status = if (execute) ATT_REQUEST_NOT_SUPPORTED else BluetoothGatt.GATT_SUCCESS
                respond(gatt, device, requestId, status, 0)
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) = callback { gatt ->
                val completed = inFlight?.takeIf { it.device == device } ?: return@callback
                inFlight = null
                main.removeCallbacks(transmissionTimeout)
                log("${completed.kind.label} completion: status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (completed.kind) {
                        Kind.RIDE_ON -> {
                            val client = completed.client
                            val wasReady = client.gearReady
                            client.indicationPending = false
                            client.handshakeComplete = true
                            updateGearReadiness(device, client, wasReady)
                        }
                        Kind.PRESS -> nextTransmissionAt = SystemClock.uptimeMillis() + GEAR_PULSE_MS
                        Kind.NEUTRAL, Kind.RELEASE, Kind.KEEP_ALIVE -> Unit
                    }
                } else {
                    transmissionFailed(gatt, completed)
                }
                sendNext(gatt)
            }
        }

        private fun sendRideOn(gatt: BluetoothGattServer, device: BluetoothDevice) {
            val client = clients[device]
            if (client == null || !client.indicationsEnabled) {
                log("RideOn received before indication subscription")
                return
            }
            if (client.indicationPending) {
                log("RideOn received while an indication is pending")
                return
            }
            client.indicationPending = true
            outgoing.addLast(Outgoing(device, client, Kind.RIDE_ON, ClickProfile.RIDE_ON))
            sendNext(gatt)
        }

        private fun updateGearReadiness(device: BluetoothDevice, client: Client, wasReady: Boolean) {
            if (client.gearReady == wasReady) return
            if (client.gearReady) {
                outgoing.addLast(Outgoing(device, client, Kind.NEUTRAL, RideButtons.frame()))
                if (clients.values.none { it !== client && it.gearReady }) {
                    scheduleKeepAlive(SystemClock.uptimeMillis())
                }
                log("Gear controls ready")
            } else {
                outgoing.removeAll { it.client === client && it.kind != Kind.RIDE_ON }
                cancelKeepAliveWithoutReadyClients()
            }
            changed()
        }

        private fun sendNext(gatt: BluetoothGattServer) {
            main.removeCallbacks(resumeTransmissions)
            if (inFlight != null || outgoing.isEmpty()) return
            if (SystemClock.uptimeMillis() < nextTransmissionAt) {
                main.postAtTime(resumeTransmissions, nextTransmissionAt)
                return
            }

            // Android uses the same completion callback for both indications and notifications.
            while (outgoing.isNotEmpty()) {
                val frame = outgoing.removeFirst()
                if (clients[frame.device] !== frame.client) continue
                val isIndication = frame.kind == Kind.RIDE_ON
                if (isIndication && !frame.client.indicationsEnabled) {
                    frame.client.indicationPending = false
                    continue
                }
                if (!isIndication && !frame.client.gearReady) continue
                val uuid = if (isIndication) ClickProfile.INDICATE else ClickProfile.NOTIFY
                val characteristic = gatt.getService(ClickProfile.SERVICE)?.getCharacteristic(uuid)
                if (characteristic == null) {
                    log("${frame.kind.label} characteristic unavailable: $uuid")
                } else if (transmit(gatt, characteristic, frame, isIndication)) {
                    inFlight = frame
                    main.postDelayed(transmissionTimeout, TRANSMISSION_TIMEOUT_MS)
                    return
                }
                transmissionFailed(gatt, frame)
            }
        }

        private fun transmissionFailed(gatt: BluetoothGattServer, frame: Outgoing) {
            when (frame.kind) {
                Kind.RIDE_ON -> frame.client.indicationPending = false
                Kind.PRESS, Kind.KEEP_ALIVE -> Unit
                Kind.NEUTRAL, Kind.RELEASE -> {
                    frame.client.handshakeComplete = false
                    frame.client.indicationPending = false
                    outgoing.removeAll { it.client === frame.client }
                    cancelKeepAliveWithoutReadyClients()
                    changed()
                    log("GATT connection closing after a failed neutral gear frame")
                    gatt.cancelConnection(frame.device)
                }
            }
        }
    }

    private class Client {
        val subscriptions = mutableMapOf<UUID, ByteArray>()
        var indicationPending = false
        var handshakeComplete = false
        val indicationsEnabled get() = subscriptions[ClickProfile.INDICATE]
            ?.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == true
        val gearReady get() = handshakeComplete && subscriptions[ClickProfile.NOTIFY]
            ?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
    }

    private enum class Kind(val label: String) {
        RIDE_ON("RideOn indication"),
        NEUTRAL("Gear neutral"),
        KEEP_ALIVE("Gear keep-alive"),
        PRESS("Gear press"),
        RELEASE("Gear release")
    }

    private data class Outgoing(
        val device: BluetoothDevice,
        val client: Client,
        val kind: Kind,
        val payload: ByteArray
    )

    private fun respond(
        gatt: BluetoothGattServer, device: BluetoothDevice, requestId: Int,
        status: Int, offset: Int, value: ByteArray? = null
    ): Boolean {
        val queued = gatt.sendResponse(device, requestId, status, offset, value)
        if (!queued || status != BluetoothGatt.GATT_SUCCESS) {
            log("GATT response: request=$requestId, status=$status, queued=$queued")
        }
        return queued
    }

    @Suppress("DEPRECATION")
    private fun transmit(
        gatt: BluetoothGattServer, characteristic: BluetoothGattCharacteristic, frame: Outgoing, confirm: Boolean
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.notifyCharacteristicChanged(frame.device, characteristic, confirm, frame.payload)
            log("${frame.kind.label}: data=${frame.payload.hex()}, Android status=$status")
            return status == BluetoothStatusCodes.SUCCESS
        }
        characteristic.value = frame.payload
        val queued = gatt.notifyCharacteristicChanged(frame.device, characteristic, confirm)
        log("${frame.kind.label}: data=${frame.payload.hex()}, queued=$queued")
        return queued
    }

    private fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun advertisingError(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
        else -> "UNKNOWN"
    }

    private companion object {
        const val ATT_REQUEST_NOT_SUPPORTED = 0x06
        const val ATT_VALUE_NOT_ALLOWED = 0x13
        const val GEAR_PULSE_MS = 100L
        const val KEEP_ALIVE_INTERVAL_MS = 10_000L
        const val TRANSMISSION_TIMEOUT_MS = 5_000L
        const val MAX_QUEUED_FRAMES = 12
    }
}
