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
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.util.Log

/**
 * ⚠ BANKED UNTIL FLASH DAY — the CFW BLE transport. The protocol brain is
 * [CfwTransportBase], the exact code the sim transport runs through the
 * selfcheck daily; this file adds only the GATT glue. It has NEVER touched
 * hardware: the glasses still run stock 2.2.2.20 and stay on G2CC until Adam
 * flashes the CFW. Two guards protect a mistaken activation:
 *   - the Settings target defaults to SIM, and
 *   - the base's capability gate refuses any firmware that does not answer
 *     with an EVENCFW string (stock never does) — it reads settings, then
 *     refuses to paint.
 *
 * Wire lineage: UUIDs + AA framing from G2CC ble/G2Constants.kt +
 * docs/G2_BLE_PROTOCOL.md (Adam's own capture-derived spec); the arm split and
 * window discipline from overview.md §2/§8.1. First-light checklist items
 * (two-arm capture, per-notch scroll, rect budget) live in REMINDER.md.
 */
@SuppressLint("MissingPermission")   // callers gate on runtime permissions first
class BleTransport(
    private val context: Context,
    scope: CoroutineScope,
) : CfwTransportBase(scope, "ble") {

    // UUID base from G2CC G2Constants (survived the 2026-06 firmware drift:
    // service 5450 holds chars 5401 write / 5402 notify)
    private fun uuid(suffix: Int): UUID =
        UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e%04x".format(suffix))

    private val serviceUuid = uuid(0x5450)
    private val writeUuid = uuid(0x5401)
    private val notifyUuid = uuid(0x5402)

    private inner class ArmManager(private val arm: Arm) : BleManager(context) {
        var write: BluetoothGattCharacteristic? = null
        var notify: BluetoothGattCharacteristic? = null

        /** True between this arm's connect completing and its link ending —
         *  a disconnect callback that arrives while it is false belongs to a
         *  connection we no longer count on (a late report from an earlier
         *  one) and must not sweep a session that is being set up (round 6). */
        @Volatile var linkUp = false

        init {
            // An UNEXPECTED disconnect (link loss, the peer ending it, a
            // supervision timeout) must sweep the session: every in-flight ack
            // is lost, the window permits would stay taken and the next start
            // would park forever (review round 3 D1). Our own disconnectLink()
            // runs after running=false and is not a link-down.
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
                    onLinkDown("$arm disconnected: ${reasonName(reason)}")
                }
            })
        }

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val svc = gatt.getService(serviceUuid) ?: return false
            write = svc.getCharacteristic(writeUuid)
            notify = svc.getCharacteristic(notifyUuid)
            return write != null && notify != null
        }

        override fun initialize() {
            setNotificationCallback(notify).with { _, data ->
                data.value?.let { onNotifyPacket(arm, it) }
            }
            // Every step observed: a failed MTU request or CCCD write would
            // otherwise leave notifications dead and the capability gate
            // waiting in total silence (review round 1).
            beginAtomicRequestQueue()
                .add(requestMtu(247)
                    .fail { _, status -> emitFault("ble", "$arm MTU request failed: $status") })
                .add(enableNotifications(notify)
                    .fail { _, status -> emitFault("ble", "$arm notification enable FAILED: $status — acks will never arrive") })
                .done { Log.i("ble", "$arm initialized (MTU + notifications)") }
                .enqueue()
        }

        override fun onServicesInvalidated() {
            write = null
            notify = null
        }

        suspend fun writePacket(bytes: ByteArray) {
            val c = write ?: throw IllegalStateException("$arm write characteristic gone")
            // Write-without-response, one AA packet per write — G2CC's proven
            // path. Nordic serializes its own queue; the base serializes
            // messages above it.
            writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                .suspend()
        }

        /** readRssi() is protected in BleManager; surface it for the link cell. */
        suspend fun rssi(): Int = readRssi().suspend()
    }

    private val managers = mapOf(Arm.LEFT to ArmManager(Arm.LEFT), Arm.RIGHT to ArmManager(Arm.RIGHT))

    private fun reasonName(reason: Int): String = when (reason) {
        ConnectionObserver.REASON_SUCCESS -> "clean"
        ConnectionObserver.REASON_TERMINATE_LOCAL_HOST -> "terminated by phone"
        ConnectionObserver.REASON_TERMINATE_PEER_USER -> "terminated by glasses"
        ConnectionObserver.REASON_LINK_LOSS -> "link loss"
        ConnectionObserver.REASON_NOT_SUPPORTED -> "not supported"
        ConnectionObserver.REASON_CANCELLED -> "cancelled"
        ConnectionObserver.REASON_TIMEOUT -> "supervision timeout"
        else -> "reason $reason"
    }

    override suspend fun connectLink() {
        // a previous session that ended in a link death may have left the
        // surviving arm's linkUp standing (round 7 D2): this session counts
        // only the connections it makes itself
        for (m in managers.values) m.linkUp = false
        val (left, right) = scanForPair()
        Log.i("ble", "connecting L=${left.address} R=${right.address}")
        for ((arm, dev) in listOf(Arm.LEFT to left, Arm.RIGHT to right)) {
            val m = managers.getValue(arm)
            m.connect(dev).retry(3, 300).useAutoConnect(false).suspend()
            m.linkUp = true
            // a drop in the gap between the connect resolving and the mark
            // would be reported as "not in use" and lost (round 7 D3): look
            // once, loudly
            if (!m.isConnected) {
                m.linkUp = false
                throw IllegalStateException("$arm dropped right after connecting")
            }
        }
        Log.i("ble", "both arms connected; MTU negotiated")
        // RSSI for the status-bar link cell, from the RIGHT (command) arm
        scope.launch {
            while (running) {
                delay(10_000)
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

    /** Find both lenses by advertised name — "Even G2_XX_L_YYYY" / "_R_"
     *  (G2CC G2Constants: the pair advertises as two devices). No timeout:
     *  scanning continues until both arms are seen; the user cancels by
     *  toggling the target back to SIM. */
    private suspend fun scanForPair(): Pair<BluetoothDevice, BluetoothDevice> {
        val bt = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw IllegalStateException("no bluetooth adapter")
        if (!bt.isEnabled) throw IllegalStateException("bluetooth is off")
        val scanner = bt.bluetoothLeScanner ?: throw IllegalStateException("no LE scanner")
        val done = CompletableDeferred<Pair<BluetoothDevice, BluetoothDevice>>()
        var left: BluetoothDevice? = null
        var right: BluetoothDevice? = null
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // the ADVERTISED name: device.name needs BLUETOOTH_CONNECT and a
                // cached bond; scanRecord.deviceName rides the advertisement
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (!name.startsWith("Even G2")) return
                if ("_L_" in name && left == null) left = result.device
                if ("_R_" in name && right == null) right = result.device
                val l = left
                val r = right
                if (l != null && r != null && !done.isCompleted) done.complete(l to r)
            }

            override fun onScanFailed(errorCode: Int) {
                if (!done.isCompleted) done.completeExceptionally(
                    IllegalStateException("BLE scan failed: $errorCode"))
            }
        }
        Log.i("ble", "scanning for the G2 pair (phone-side recovery if stuck: toggle Bluetooth)")
        scanner.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        try {
            return done.await()
        } finally {
            scanner.stopScan(cb)
        }
    }
}
