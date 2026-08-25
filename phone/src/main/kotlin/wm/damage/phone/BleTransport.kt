package wm.damage.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.util.Log

/**
 * The CFW BLE transport on Android — the GATT glue under [CfwTransportBase],
 * which is the protocol brain the simulator exercises on every selfcheck.
 * Rebuilt for the finishing build (HANDOFF.md §8.2 "Phone") on two working
 * sources: G2CC's driver, which runs on real glasses daily (stock firmware),
 * and the CFW reference's connect sequence:
 *
 *   scan by advertised name ("Even G2_.._L_.." / "_R_") or a cached address
 *   → connect RIGHT then LEFT (retry(10, 500), no autoConnect — the session
 *     keeper owns reconnects)
 *   → per arm in initialize(): MTU 512 requested and the negotiated value
 *     checked (>= 245 carries a 242 B AA packet), connection priority HIGH,
 *     notifications on 5402 with any failure surfaced
 *   → the base settles ~800 ms and runs the sid-0x01 prelude, capability
 *     gate, carrier CREATE, FB lease on both arms, warmup
 *
 * ⚠ Never run on hardware yet: the glasses stay on stock 2.2.2.20 until Adam
 * flashes. Two guards: the target defaults to SIM, and the base's capability
 * gate refuses any firmware that does not answer with an EVENCFW string.
 *
 * Wire lineage: UUIDs from G2CC ble/G2Constants.kt + docs/G2_BLE_PROTOCOL.md
 * §1.3 (post-drift: service 5450 holds chars 5401 write / 5402 notify); the
 * arm split and window discipline from overview.md §2/§8.1.
 */
@SuppressLint("MissingPermission")   // callers gate on runtime permissions first
class BleTransport(
    private val context: Context,
    scope: CoroutineScope,
    /** Remembered pair addresses (Prefs): accepted by the scanner next to the
     *  name match, and updated after every successful connect. */
    private val cachedAddresses: () -> Pair<String?, String?> = { null to null },
    private val rememberAddresses: (left: String, right: String) -> Unit = { _, _ -> },
) : CfwTransportBase(scope, "ble") {

    private fun uuid(suffix: Int): UUID =
        UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e%04x".format(suffix))

    private val serviceUuid = uuid(0x5450)
    private val writeUuid = uuid(0x5401)
    private val notifyUuid = uuid(0x5402)

    private inner class ArmManager(private val arm: Arm) : BleManager(context) {
        var write: BluetoothGattCharacteristic? = null
        var notify: BluetoothGattCharacteristic? = null

        /** True between this arm's connect completing and its link ending —
         *  a disconnect report that arrives while it is false belongs to a
         *  connection we no longer count on (a late report from an earlier
         *  one) and must not end a session that is being set up. */
        @Volatile var linkUp = false

        /** The negotiated MTU, from the request's callback; -1 until known. */
        @Volatile var negotiatedMtu = -1
        @Volatile var initFailure: String? = null

        init {
            setConnectionObserver(object : ConnectionObserver {
                override fun onDeviceConnecting(device: BluetoothDevice) {}
                override fun onDeviceConnected(device: BluetoothDevice) {}
                override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {}
                override fun onDeviceReady(device: BluetoothDevice) {}
                override fun onDeviceDisconnecting(device: BluetoothDevice) {}
                override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                    if (!linkUp) {
                        Log.i("ble", "$arm disconnect report for a connection not in use (${reasonName(reason)})")
                        return
                    }
                    linkUp = false
                    if (!running) return
                    // an unexpected end of the link: the base clears the session
                    // (pending acks, window permits, queues) and the keeper
                    // starts a new one
                    onLinkDown("$arm disconnected: ${reasonName(reason)}")
                }
            })
        }

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val svc = gatt.getService(serviceUuid)
            if (svc == null) {
                initFailure = "$arm: service ${serviceUuid} not found — services: " +
                    gatt.services.joinToString { it.uuid.toString() }
                return false
            }
            write = svc.getCharacteristic(writeUuid)
            notify = svc.getCharacteristic(notifyUuid)
            if (write == null || notify == null) {
                initFailure = "$arm: chars 5401/5402 missing under 5450 — have: " +
                    svc.characteristics.joinToString { it.uuid.toString() }
                return false
            }
            return true
        }

        override fun initialize() {
            setNotificationCallback(notify).with { _, data ->
                data.value?.let { onNotifyPacket(arm, it) }
            }
            // Every step observed: a failed MTU request or CCCD write would
            // otherwise leave notifications dead and the capability gate
            // waiting in silence. Order per the CFW reference: priority, MTU,
            // notifications.
            beginAtomicRequestQueue()
                .add(requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    .fail { _, status -> Log.w("ble", "$arm connection priority request status $status (continuing)") })
                .add(requestMtu(REQUESTED_MTU)
                    .with { _, mtu -> negotiatedMtu = mtu; Log.i("ble", "$arm MTU negotiated $mtu") }
                    .fail { _, status -> initFailure = "$arm MTU request failed: status $status" })
                .add(enableNotifications(notify)
                    .fail { _, status -> initFailure = "$arm notification enable failed: status $status — acks would never arrive" })
                .done { Log.i("ble", "$arm initialized (priority + MTU + notifications)") }
                .enqueue()
        }

        override fun onServicesInvalidated() {
            write = null
            notify = null
        }

        suspend fun writePacket(bytes: ByteArray) {
            val c = write ?: throw IllegalStateException("$arm write characteristic gone")
            // Write-without-response, one AA packet per write — G2CC's proven
            // path and the reference's WRITE_TYPE. Nordic serialises its own
            // queue; the base serialises messages above it.
            writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                .suspend()
        }

        suspend fun rssi(): Int = readRssi().suspend()
    }

    private val managers = mapOf(Arm.LEFT to ArmManager(Arm.LEFT), Arm.RIGHT to ArmManager(Arm.RIGHT))

    private fun reasonName(reason: Int): String = when (reason) {
        ConnectionObserver.REASON_SUCCESS -> "clean"
        ConnectionObserver.REASON_TERMINATE_LOCAL_HOST -> "ended by phone"
        ConnectionObserver.REASON_TERMINATE_PEER_USER -> "ended by glasses"
        ConnectionObserver.REASON_LINK_LOSS -> "link loss"
        ConnectionObserver.REASON_NOT_SUPPORTED -> "not supported"
        ConnectionObserver.REASON_CANCELLED -> "cancelled"
        ConnectionObserver.REASON_TIMEOUT -> "supervision timeout"
        else -> "reason $reason"
    }

    override suspend fun connectLink() {
        // a previous session that ended in a link loss may have left the
        // surviving arm's linkUp standing: this session counts only the
        // connections it makes itself
        for (m in managers.values) m.linkUp = false
        val (left, right) = scanForPair()
        Log.i("ble", "connecting R=${right.address} then L=${left.address}")
        // RIGHT first, then LEFT — the CFW reference's order (control + events
        // ride RIGHT; bulk pixels go LEFT)
        for ((arm, dev) in listOf(Arm.RIGHT to right, Arm.LEFT to left)) {
            val m = managers.getValue(arm)
            m.negotiatedMtu = -1
            m.initFailure = null
            m.connect(dev).retry(CONNECT_RETRIES, CONNECT_RETRY_MS).useAutoConnect(false).suspend()
            m.initFailure?.let { throw IllegalStateException(it) }
            if (m.negotiatedMtu in 0 until MIN_MTU) {
                throw IllegalStateException("$arm MTU ${m.negotiatedMtu} < $MIN_MTU: a 242 B AA packet would not fit")
            }
            if (m.negotiatedMtu < 0) Log.w("ble", "$arm MTU unknown (no callback) — proceeding on the request's default")
            m.linkUp = true
            // a drop in the gap between the connect resolving and the mark
            // would be reported as "not in use" and lost: look once, loudly
            if (!m.isConnected) {
                m.linkUp = false
                throw IllegalStateException("$arm dropped right after connecting")
            }
        }
        rememberAddresses(left.address, right.address)
        Log.i("ble", "both arms connected")
        // RSSI for the status-bar link cell, from the RIGHT (command) arm
        scope.launch {
            while (running) {
                delay(RSSI_POLL_MS)
                if (!running) break
                try {
                    val rssi = managers.getValue(Arm.RIGHT).rssi()
                    updateState { it.copy(rssiDbm = rssi) }
                } catch (e: Exception) {
                    Log.w("ble", "rssi read failed: ${e.message}")
                }
            }
        }
    }

    override suspend fun disconnectLink() {
        for (m in managers.values) {
            m.linkUp = false
            try {
                m.disconnect().suspend()
            } catch (e: Exception) {
                Log.w("ble", "disconnect: ${e.message}")
            }
        }
    }

    override suspend fun writeArm(arm: Arm, packet: ByteArray) {
        managers.getValue(arm).writePacket(packet)
    }

    /** Find both lenses: by advertised name — "Even G2_XX_L_YYYY" / "_R_"
     *  (G2CC G2Constants: the pair advertises as two devices) — or by a
     *  remembered address when the advertisement carries no name. No timeout:
     *  scanning continues until both arms are seen; the phone-side recovery
     *  for a pair that stays invisible is toggling Bluetooth (REMINDER.md). */
    private suspend fun scanForPair(): Pair<BluetoothDevice, BluetoothDevice> {
        val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw IllegalStateException("no bluetooth adapter")
        if (!bt.isEnabled) throw IllegalStateException("bluetooth is off")
        val scanner = bt.bluetoothLeScanner ?: throw IllegalStateException("no LE scanner")
        val (cachedL, cachedR) = cachedAddresses()
        val done = CompletableDeferred<Pair<BluetoothDevice, BluetoothDevice>>()
        var left: BluetoothDevice? = null
        var right: BluetoothDevice? = null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // the ADVERTISED name: device.name needs BLUETOOTH_CONNECT and a
                // cached bond; scanRecord.deviceName rides the advertisement
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val addr = result.device.address
                val isLeft = (name.startsWith(NAME_PREFIX) && LEFT_INFIX in name) || addr.equals(cachedL, true)
                val isRight = (name.startsWith(NAME_PREFIX) && RIGHT_INFIX in name) || addr.equals(cachedR, true)
                if (isLeft && left == null) { left = result.device; Log.i("ble", "found L '$name' $addr") }
                if (isRight && right == null) { right = result.device; Log.i("ble", "found R '$name' $addr") }
                val l = left
                val r = right
                if (l != null && r != null && !done.isCompleted) done.complete(l to r)
            }

            override fun onScanFailed(errorCode: Int) {
                if (!done.isCompleted) done.completeExceptionally(
                    IllegalStateException("BLE scan failed: $errorCode"))
            }
        }
        Log.i("ble", "scanning for the G2 pair (phone-side recovery if it stays invisible: toggle Bluetooth)")
        scanner.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        try {
            return done.await()
        } finally {
            scanner.stopScan(cb)
        }
    }

    companion object {
        const val NAME_PREFIX = "Even G2"
        const val LEFT_INFIX = "_L_"
        const val RIGHT_INFIX = "_R_"

        /** The reference and G2CC both request 512; the glasses answer up to
         *  517 (captures), so the effective MTU is what we ask for. */
        const val REQUESTED_MTU = 512

        /** 8 header + 232 chunk + 2 CRC = 242 B per AA packet, + 3 ATT bytes. */
        const val MIN_MTU = 245

        /** G2CC's tuning for a body-blocked link: 10 × 500 ms. */
        const val CONNECT_RETRIES = 10
        const val CONNECT_RETRY_MS = 500

        const val RSSI_POLL_MS = 10_000L
    }
}
