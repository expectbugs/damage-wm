package wm.damage.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 *   scan — FILTERED on the remembered pair (address and advertised name)
 *     whenever a pair is remembered, because Android suspends unfiltered
 *     scans while the screen is off (a pocket-time link loss would otherwise
 *     wait for the screen); unfiltered by name only for a pair never seen
 *   → connect RIGHT then LEFT (retry(10, 500), no autoConnect — the session
 *     keeper owns reconnects)
 *   → per arm in initialize(): MTU 512 requested and the negotiated value
 *     checked (>= 245 carries a 242 B AA packet), connection priority HIGH,
 *     notifications on 5402 with any failure surfaced
 *   → the base settles ~800 ms and runs the sid-0x01 prelude, capability
 *     gate, carrier CREATE, FB lease on both arms, warmup
 *
 * LIVE on hardware since 2026-08-31 (HANDOFF.md §13.3b/DAILY.md: phone first
 * light passed on the first try; this glue owns the radio all day now). The
 * guard that still matters: the base's capability gate refuses any firmware
 * that does not answer with an EVENCFW string, so a mistaken switch against
 * stock glasses reads settings and refuses to paint.
 *
 * Wire lineage: UUIDs from G2CC ble/G2Constants.kt + docs/G2_BLE_PROTOCOL.md
 * §1.3 (post-drift: service 5450 holds chars 5401 write / 5402 notify); the
 * arm split and window discipline from overview.md §2/§8.1.
 */
@SuppressLint("MissingPermission")   // callers gate on runtime permissions first
class BleTransport(
    private val context: Context,
    scope: CoroutineScope,
    /** The remembered pair (Prefs): addresses and advertised names from the
     *  last successful connect — the scan filter, and accepted next to the
     *  name match. */
    private val remembered: () -> Remembered = { Remembered() },
    private val remember: (Remembered) -> Unit = { },
) : CfwTransportBase(scope, "ble") {

    data class Remembered(
        val leftAddress: String? = null, val leftName: String? = null,
        val rightAddress: String? = null, val rightName: String? = null,
    ) {
        val complete: Boolean get() = leftAddress != null && rightAddress != null
    }

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

        /** The manager's own view of the MTU (23 until negotiated). */
        val currentMtu: Int get() = mtu

        /** Non-blocking RSSI read for the link cell; the result lands in the state. */
        fun pollRssi() {
            readRssi()
                .with { _, rssi -> updateState { it.copy(rssiDbm = rssi) } }
                .fail { _, status -> Log.w("ble", "$arm rssi read status $status") }
                .enqueue()
        }

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
            // notifications. The priority request is best-effort and stands
            // ALONE: inside the atomic queue a failed child ends the queue
            // (Nordic 2.7.5: the child's failure marks the queue finished and
            // `hasMore()` stops it), which would drop the MTU and notification
            // requests behind it.
            requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                .fail { _, status -> Log.w("ble", "$arm connection priority request status $status (continuing)") }
                .enqueue()
            beginAtomicRequestQueue()
                .add(requestMtu(REQUESTED_MTU)
                    .with { _, mtu -> negotiatedMtu = mtu; Log.i("ble", "$arm MTU negotiated $mtu") }
                    .fail { _, status -> initFailure = "$arm MTU request failed: status $status" })
                .add(enableNotifications(notify)
                    .fail { _, status -> initFailure = "$arm notification enable failed: status $status — acks would never arrive" })
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
            // path and the reference's WRITE_TYPE. Nordic serialises its own
            // queue; the base serialises messages above it.
            writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                .suspend()
        }

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
        // connections it makes itself. A GATT connection left up by an
        // earlier attempt (a cancelled start) is ended first, or the scan
        // would wait for lenses that are connected to us.
        for ((arm, m) in managers) {
            m.linkUp = false
            if (m.isConnected) {
                Log.w("ble", "$arm still connected from an earlier attempt — ending it first")
                try { m.disconnect().suspend() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w("ble", "$arm disconnect: ${e.message}") }
            }
        }
        val (left, right) = scanForPair()
        Log.i("ble", "connecting R=${right.address} then L=${left.address}")
        // RIGHT first, then LEFT — the CFW reference's order (control + events
        // ride RIGHT; bulk pixels go LEFT)
        for ((arm, dev) in listOf(Arm.RIGHT to right, Arm.LEFT to left)) {
            val m = managers.getValue(arm)
            m.negotiatedMtu = -1
            m.initFailure = null
            updateState { it.copy(detail = "connecting ${arm.name}") }
            m.connect(dev).retry(CONNECT_RETRIES, CONNECT_RETRY_MS).useAutoConnect(false).suspend()
            m.initFailure?.let { throw IllegalStateException(it) }
            if (m.negotiatedMtu < 0) {
                // the request reported nothing: the manager's own value decides
                // (23 until negotiated — a loud refusal, never an assumption)
                m.negotiatedMtu = m.currentMtu
                Log.w("ble", "$arm MTU request gave no callback — manager reports ${m.negotiatedMtu}")
            }
            if (m.negotiatedMtu < MIN_MTU) {
                throw IllegalStateException("$arm MTU ${m.negotiatedMtu} < $MIN_MTU: a 242 B AA packet would not fit")
            }
            m.linkUp = true
            // a drop in the gap between the connect resolving and the mark
            // would be reported as "not in use" and lost: look once, loudly
            if (!m.isConnected) {
                m.linkUp = false
                throw IllegalStateException("$arm dropped right after connecting")
            }
        }
        // a drop of the first arm while the second was connecting is reported
        // before `running` is set (the observer ignores it then): look once
        for ((arm, m) in managers) if (!m.isConnected) {
            m.linkUp = false
            throw IllegalStateException("$arm dropped while the pair was being connected")
        }
        remember(Remembered(left.address, scanNames[Arm.LEFT], right.address, scanNames[Arm.RIGHT]))
        updateState { it.copy(detail = "") }
        Log.i("ble", "both arms connected")
    }

    /** Runs even from a cancelled coroutine (the base's rollback after a lost
     *  arbitration or a keeper pause): the GATT links must not outlive the
     *  attempt — a cancelled continuation would otherwise drop the disconnect
     *  request before it was enqueued (review round 1, b2). */
    override suspend fun disconnectLink(): Unit = withContext(NonCancellable) {
        for (m in managers.values) {
            m.linkUp = false
            try {
                m.disconnect().suspend()
            } catch (e: Exception) {
                Log.w("ble", "disconnect: ${e.message}")
            }
        }
    }

    private var ticks = 0

    /** RSSI for the status-bar link cell every 10th tick, from the RIGHT
     *  (command) arm — bound to the session like every other maintenance. */
    override fun onMaintenanceTick() {
        if (++ticks % RSSI_EVERY_TICKS != 0) return
        val m = managers.getValue(Arm.RIGHT)
        if (m.linkUp) m.pollRssi()
    }

    /** The advertised names seen for each arm during the last scan. */
    private val scanNames = java.util.concurrent.ConcurrentHashMap<Arm, String>()

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
        val known = remembered()
        val cachedL = known.leftAddress
        val cachedR = known.rightAddress
        // FILTERED on a remembered pair: the one kind of scan Android keeps
        // running with the screen off (a pocket-time loss recovers on its own)
        val filters = ArrayList<ScanFilter>()
        if (known.complete) {
            for (addr in listOfNotNull(cachedL, cachedR)) {
                try { filters += ScanFilter.Builder().setDeviceAddress(addr).build() } catch (e: IllegalArgumentException) {
                    Log.w("ble", "remembered address '$addr' is not a filterable address: ${e.message}")
                }
            }
            for (n in listOfNotNull(known.leftName, known.rightName)) filters += ScanFilter.Builder().setDeviceName(n).build()
        }
        updateState { it.copy(detail = if (filters.isEmpty()) "scanning for the pair (first time: needs the screen on)" else "scanning for the remembered pair") }
        val done = CompletableDeferred<Pair<BluetoothDevice, BluetoothDevice>>()
        // Bluetooth turning OFF mid-scan does NOT reliably reach onScanFailed
        // — the scan just goes dead and the await would park forever (G2CC's
        // "scanning forever" class, their ConnectionService BT-state receiver).
        // Toggling phone Bluetooth is also the documented at-work recovery
        // for a stale ACL, so this path WILL be exercised: fail the scan
        // loudly and the keeper's retry loop rides the ON edge back in.
        val offReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                    if (!done.isCompleted) done.completeExceptionally(
                        IllegalStateException("bluetooth turned off during the scan"))
                }
            }
        }
        context.registerReceiver(offReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        var left: BluetoothDevice? = null
        var right: BluetoothDevice? = null
        scanNames.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // the ADVERTISED name: device.name needs BLUETOOTH_CONNECT and a
                // cached bond; scanRecord.deviceName rides the advertisement
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val addr = result.device.address
                val isLeft = (name.startsWith(NAME_PREFIX) && LEFT_INFIX in name) || addr.equals(cachedL, true)
                val isRight = (name.startsWith(NAME_PREFIX) && RIGHT_INFIX in name) || addr.equals(cachedR, true)
                if (isLeft && left == null) { left = result.device; if (name.isNotEmpty()) scanNames[Arm.LEFT] = name; Log.i("ble", "found L '$name' $addr") }
                if (isRight && right == null) { right = result.device; if (name.isNotEmpty()) scanNames[Arm.RIGHT] = name; Log.i("ble", "found R '$name' $addr") }
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
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filterList = if (filters.isEmpty()) null else filters
        scanner.startScan(filterList, settings, cb)
        // Android quiets a scan that runs past ~30 min (downgraded to
        // opportunistic, silently) — a pair left in its case would then never
        // be found until the app restarted. Re-issue the same scan on a
        // pacing tick, the CfwTransportBase re-ask idiom: pacing between
        // attempts, not a bound on the wait, which stays endless.
        val pacer = scope.launch {
            while (isActive && !done.isCompleted) {
                delay(SCAN_REISSUE_MS)
                if (done.isCompleted) break
                Log.i("ble", "scan re-issued (Android quiets a scan after ~30 min)")
                try {
                    scanner.stopScan(cb)
                    scanner.startScan(filterList, settings, cb)
                } catch (e: Exception) {
                    if (!done.isCompleted) done.completeExceptionally(
                        IllegalStateException("scan re-issue failed: ${e.message}"))
                }
            }
        }
        try {
            return done.await()
        } finally {
            pacer.cancel()
            try { context.unregisterReceiver(offReceiver) } catch (e: Exception) { Log.w("ble", "state receiver: ${e.message}") }
            try { scanner.stopScan(cb) } catch (e: Exception) { Log.w("ble", "stopScan: ${e.message}") }
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

        const val RSSI_EVERY_TICKS = 10

        /** Re-issue a still-hunting scan on this pacing — under the ~30 min
         *  point where Android silently downgrades a long scan, and far under
         *  the 5-starts-per-30-s throttle. */
        const val SCAN_REISSUE_MS = 20L * 60 * 1000
    }
}
