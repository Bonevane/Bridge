package com.bonevane.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The short-range half of Bridge: notifications and clipboard over Bluetooth LE,
 * so the Mac doesn't have to hold an internet tunnel open all day just to carry
 * a few hundred bytes.
 *
 * The phone is the peripheral: it advertises a Bridge service and pushes
 * messages to whichever Mac has subscribed. Characteristics require encryption,
 * so only a *bonded* (paired) Mac can read your notifications.
 *
 * What this can and can't do:
 *  - notifications phone → Mac: needs no privileges at all
 *  - clipboard Mac → phone: a normal app may *write* the clipboard in the background
 *  - clipboard phone → Mac: impossible here. Android 10+ only lets the focused
 *    app or the keyboard *read* the clipboard, so that direction still needs the
 *    shell daemon (see ControlProxy's CLIP stream).
 */
class BleLink(private val context: Context) {

    companion object {
        val SERVICE: UUID = UUID.fromString("b71d0001-5c8f-4b1e-9a3a-3f1f0a7c9e11")
        /** Phone → Mac, by notification. */
        val TX: UUID = UUID.fromString("b71d0002-5c8f-4b1e-9a3a-3f1f0a7c9e11")
        /** Mac → phone, by write. */
        val RX: UUID = UUID.fromString("b71d0003-5c8f-4b1e-9a3a-3f1f0a7c9e11")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val TYPE_NOTIFICATION: Byte = 1
        const val TYPE_CLIPBOARD: Byte = 2
        /** Proof the link is alive: the Mac reconnects if these stop arriving. */
        const val TYPE_PING: Byte = 3
        /** What the phone can currently do, so the Mac can say so plainly. */
        const val TYPE_STATUS: Byte = 4
        /** The Mac asking for something, e.g. "tunnel on". */
        const val TYPE_COMMAND: Byte = 5
        /**
         * The pairing handshake: "challenge <nonce>" from the phone, then
         * "ok <hmac>" once the Mac has answered. Nothing else is sent, and no
         * command is obeyed, until this has completed. Bonding proves the
         * other device is *a* device you've paired with; this proves it is
         * *your Mac*, the one that knows the pairing secret.
         */
        const val TYPE_AUTH: Byte = 6

        /** Conservative: the default ATT MTU is 23, of which 3 bytes are overhead. */
        private const val MIN_PAYLOAD = 20
    }

    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val subscribers = HashSet<BluetoothDevice>()
    private var mtuPayload = MIN_PAYLOAD

    /** Chunks waiting to go out; BLE only allows one notification in flight. */
    private val outbox = ArrayDeque<ByteArray>()
    private val sending = AtomicBoolean(false)
    /** When `sending` was last set, so a lost onNotificationSent can't wedge the queue for good. */
    @Volatile private var sendingSince = 0L

    /** Reassembly buffer for messages coming from the Mac. */
    private val inbox = StringBuilder()

    @Volatile var connected = false
        private set(value) { field = value; TunnelState.macLinked = value }
    /** True once the subscribed Mac has answered the challenge. */
    @Volatile private var verified = false
    private var pendingNonce: String? = null
    @Volatile private var beating = false

    private val notificationListener: (String) -> Unit = { line ->
        send(TYPE_NOTIFICATION, line)
    }

    // MARK: - Lifecycle

    fun start() {
        if (server != null) return          // already advertising
        if (!hasPermissions()) {
            TunnelState.log("Bluetooth: permissions not granted yet")
            return
        }
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            TunnelState.log("Bluetooth: turned off")
            return
        }

        val characteristicTx = BluetoothGattCharacteristic(
            TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    // Subscribing needs an encrypted (bonded) link, so a
                    // passer-by can't sign up for your notifications.
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                        BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
                )
            )
        }
        val characteristicRx = BluetoothGattCharacteristic(
            RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(characteristicTx)
            addCharacteristic(characteristicRx)
        }

        server = openServer(manager)?.also { it.addService(service) }
        tx = characteristicTx
        advertise(adapter)
        NotificationRelay.subscribe(notificationListener)
        startHeartbeat()
        TunnelState.log("Bluetooth: advertising")
    }

    fun stop() {
        beating = false
        NotificationRelay.unsubscribe(notificationListener)
        runCatching { stopAdvertising() }
        runCatching { server?.close() }
        server = null
        subscribers.clear()
        connected = false
    }

    /**
     * When this app's process is replaced (an update, a crash), the Bluetooth
     * link often survives at the controller level even though the GATT service
     * is gone, and the Mac has no way to tell. A ping every 30 s gives it one.
     */
    private fun startHeartbeat() {
        if (beating) return
        beating = true
        Thread({
            while (beating) {
                Thread.sleep(30_000)
                if (subscribers.isNotEmpty()) sendStatus()
            }
        }, "ble-heartbeat").apply { isDaemon = true }.start()
    }

    /**
     * "keep on at=<millis>" from the Mac. Whichever side changed the setting
     * most recently wins, so neither device can silently overwrite the other:
     * if our copy is newer we keep it and answer with the truth instead.
     */
    private fun keepReady(message: String) {
        val words = message.trim().split(" ")
        val on = words.getOrNull(1) == "on"
        val theirs = words.firstOrNull { it.startsWith("at=") }
            ?.removePrefix("at=")?.toLongOrNull() ?: 0
        val ours = Prefs.keepReadyAt(context)
        if (theirs >= ours) {
            Prefs.setKeepReady(context, on, theirs)
            TunnelState.log("Mac set \"keep ready\" to $on (its change was newer)")
            if (on) TunnelService.current?.policy?.maybeStart("Mac asked to keep ready")
        } else {
            TunnelState.log("Ignored the Mac's \"keep ready\": this phone changed it more recently")
        }
        sendStatus()
    }

    /**
     * Tells the Mac what works right now. Doubles as the heartbeat, so the Mac's
     * picture of the phone is never more than 30 s stale.
     */
    fun sendStatus() {
        // A Mac on the Bluetooth link is very much still there, even though it
        // hasn't spoken over the tunnel. Without this the idle watchdog decides
        // it vanished and locks the phone down, which quietly kills the daemon
        // and drops the clipboard to Mac-to-phone only.
        if (subscribers.isNotEmpty()) TunnelState.macSeen()
        val daemon = DaemonManager.isDaemonAlive()
        val paused = TunnelService.current?.policy?.isPaused == true
        // The phone owns these settings; the Mac mirrors whatever it reports here.
        send(
            TYPE_STATUS,
            "daemon=${if (daemon) 1 else 0}" +
                " tunnel=${if (TunnelState.tunnelOn) 1 else 0}" +
                " keep=${if (Prefs.keepReady(context)) 1 else 0}" +
                " keepAt=${Prefs.keepReadyAt(context)}" +
                " paused=${if (paused) 1 else 0}"
        )
    }

    // MARK: - Sending

    /** Queues one message for the Mac, split into MTU-sized chunks. */
    @SuppressLint("MissingPermission")
    fun send(type: Byte, text: String) {
        if (subscribers.isEmpty()) return
        if (!verified && type != TYPE_AUTH) return      // strangers hear only the challenge
        val bytes = text.toByteArray()
        val room = mtuPayload - 2      // type + "more" flag
        synchronized(outbox) {
            if (bytes.isEmpty()) outbox.add(byteArrayOf(type, 0))
            var offset = 0
            while (offset < bytes.size) {
                val size = minOf(room, bytes.size - offset)
                val more = if (offset + size < bytes.size) 1.toByte() else 0.toByte()
                outbox.add(byteArrayOf(type, more) + bytes.copyOfRange(offset, offset + size))
                offset += size
            }
        }
        pump()
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        // The stack promises onNotificationSent for every notify, but if the
        // Mac drops in between it never arrives. Without this guard `sending`
        // stayed true forever, and every later message (heartbeats included)
        // was queued and never sent: the Mac saw a link that never spoke,
        // dropped it after 90 s, reconnected, and looped like that all night.
        if (sending.get() && System.currentTimeMillis() - sendingSince > 3_000) {
            TunnelState.log("BLE: send acknowledgement never came; resetting")
            sending.set(false)
        }
        if (!sending.compareAndSet(false, true)) return
        sendingSince = System.currentTimeMillis()
        val chunk = synchronized(outbox) { outbox.poll() }
        val characteristic = tx
        if (chunk == null || characteristic == null) { sending.set(false); return }
        val device = subscribers.firstOrNull()
        if (device == null) { sending.set(false); return }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                server?.notifyCharacteristicChanged(device, characteristic, false, chunk) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                server?.notifyCharacteristicChanged(device, characteristic, false) == true
            }
        }.onFailure { TunnelState.log("BLE notify threw: $it") }.getOrDefault(false)
        if (!ok) {
            // Couldn't hand it over; drop this chunk rather than wedge the queue.
            sending.set(false)
            if (synchronized(outbox) { outbox.isNotEmpty() }) pump()
        }
    }

    // MARK: - GATT server

    @SuppressLint("MissingPermission")
    private fun openServer(manager: BluetoothManager): BluetoothGattServer? =
        manager.openGattServer(context, object : BluetoothGattServerCallback() {

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Android quirk: a GATT server has to claim the connection
                    // or the stack treats it as idle and lets it lapse; the Mac
                    // then sees "connection timed out" (reason 6) every few
                    // seconds and relinks. This is what stops that.
                    runCatching { server?.connect(device, false) }
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    subscribers.remove(device)
                    verified = false
                    connected = false
                    // Anything queued was for a Mac that's gone; start clean.
                    synchronized(outbox) { outbox.clear() }
                    sending.set(false)
                    TunnelState.log("Bluetooth: a Mac disconnected")
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
            ) {
                // The Mac subscribing (or unsubscribing) to notifications.
                if (descriptor.uuid == CCCD) {
                    val on = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    // Encryption on the descriptor is enforced by the stack; the
                    // bond is checked here as well, because an encrypted-but-
                    // unbonded link is possible with some pairing modes.
                    if (on && device.bondState != BluetoothDevice.BOND_BONDED) {
                        TunnelState.log("Bluetooth: refused an unpaired device")
                        if (responseNeeded) server?.sendResponse(device, requestId,
                            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, 0, null)
                        return
                    }
                    if (on) subscribers.add(device) else subscribers.remove(device)
                    verified = false
                    connected = false
                    if (responseNeeded) {
                        server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                    if (on) {
                        TunnelState.log("Bluetooth: a paired device subscribed; challenging it")
                        val nonce = Pairing.nonce()
                        pendingNonce = nonce
                        send(TYPE_AUTH, "challenge $nonce")
                    } else {
                        TunnelState.log("Bluetooth: Mac left")
                    }
                    return
                }
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
            ) {
                if (characteristic.uuid == RX) receive(value)
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                sending.set(false)
                pump()      // next chunk, now that the stack is ready
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                mtuPayload = (mtu - 3).coerceAtLeast(MIN_PAYLOAD)
            }
        })

    /** Reassembles a message from the Mac and acts on it. */
    private fun receive(chunk: ByteArray) {
        if (chunk.size < 2) return
        val type = chunk[0]
        val more = chunk[1] == 1.toByte()
        inbox.append(String(chunk, 2, chunk.size - 2))
        if (more) return
        val message = inbox.toString()
        inbox.setLength(0)
        if (!verified) {
            // Only the answer to our challenge gets through: "auth <hmac> <their nonce>".
            val words = message.trim().split(' ')
            val nonce = pendingNonce
            if (type == TYPE_COMMAND && words.size == 3 && words[0] == "auth" && nonce != null &&
                Pairing.hmacMatches(Prefs.pairSecret(context), nonce, words[1])
            ) {
                pendingNonce = null
                verified = true
                connected = true
                TunnelState.log("Bluetooth: Mac verified")
                // Prove ourselves back, then the usual first status report.
                send(TYPE_AUTH, "ok " + Pairing.hmac(Prefs.pairSecret(context), words[2]))
                TunnelState.macSeen()
                sendStatus()
            } else {
                TunnelState.log("Bluetooth: ignored a message from an unverified device")
            }
            return
        }
        if (type == TYPE_COMMAND) {
            // The Mac can switch the tunnel on from across the room, so the phone
            // can sit in the cheap Bluetooth-only mode until mirroring is wanted.
            when (message.trim()) {
                "tunnel on" -> {
                    TunnelState.log("Mac asked for the tunnel over Bluetooth")
                    TunnelService.setTunnel(context, true)
                }
                "tunnel off" -> {
                    TunnelState.log("Mac turned the tunnel off over Bluetooth")
                    TunnelService.setTunnel(context, false)
                }
                "status" -> sendStatus()        // the Mac's refresh button
                "session over" -> {
                    // The Mac finished mirroring. Same as the tunnel's STOP, but
                    // over Bluetooth, which still works when the tunnel is the
                    // very thing that just died.
                    TunnelState.log("Mac ended the session (Bluetooth): ${DaemonManager.stop(context)}")
                    sendStatus()
                }
                // "keep on at=<millis>" carries a timestamp, so it can't match exactly.
                else -> when {
                    message.startsWith("keep ") -> keepReady(message)
                    // "macapps com.whatsapp org.telegram.messenger": the Android
                    // packages whose Mac twin is open, so their notifications
                    // would only be duplicates. Empty list = none open.
                    message.startsWith("macapps") ->
                        NotificationFilter.setOpenOnMac(message.removePrefix("macapps").trim().split(' ').filter { it.isNotEmpty() })
                    else -> TunnelState.log("Unknown Bluetooth command: $message")
                }
            }
            return
        }
        if (type == TYPE_CLIPBOARD) {
            // A normal app may write the clipboard in the background (reading it
            // is what Android forbids), so this direction needs no daemon.
            runCatching {
                // Remember it first: the watcher will see this very change come
                // back from the daemon, and must not send it to the Mac again.
                TunnelService.current?.clipboard?.lastValue = message
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Bridge", message))
                TunnelState.log("Clipboard from the Mac over Bluetooth")
            }
        }
    }

    // MARK: - Advertising

    private var advertiseCallback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    private fun advertise(adapter: android.bluetooth.BluetoothAdapter) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            TunnelState.log("Bluetooth: this phone can't advertise")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)          // keep advertising
            .build()
        // The name would push us past the 31-byte limit next to a 128-bit UUID,
        // so it goes in the scan response instead.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                TunnelState.log("Bluetooth: advertising failed ($errorCode)")
            }
        }
        advertiseCallback = callback
        runCatching { advertiser.startAdvertising(settings, data, scanResponse, callback) }
            .onFailure { TunnelState.log("Bluetooth: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        advertiseCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        advertiseCallback = null
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return listOf(
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
        ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
