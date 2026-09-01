package wm.damage.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import wm.damage.core.util.Log

/**
 * A READ-ONLY enumeration of the R1 ring's GATT table over the phone's OWN
 * link to the ring — the 2026-08-31 finding (HANDOFF.md §19.4): the glasses
 * cannot relay ring data. The stock sid-0x91 service accepts only the EVENT
 * registration and never fills RingRawData (openCFW `pb_service_ring.c`,
 * instruction-level recovery of our 2.2.6.10 base; zero rawData frames across
 * both captures; G2CC docs §10 — the official app reads ring battery on the
 * ring's own link). A phone↔ring link coexisting with ring↔glasses is the
 * shipped Faceclaw shape; the ring serves both, and it advertises in app
 * mode (measured during the DFU work: `EVEN R1_35F0B8`).
 *
 * The probe: scan for an `EVEN R1` advertisement, connect WITHOUT pairing,
 * discover services, log the whole table, then read Battery Level (0x2A19)
 * and the Device Information revision strings when present — ONE attempt
 * each, never retried: a read the ring refuses with insufficient
 * authentication is REPORTED, not escalated (a retry is where Android
 * auto-pairs, and the §11.5 lesson says bond state around this ring is not
 * to be disturbed casually). Disconnects and closes when done.
 *
 * The scan window and the overall watchdog are liveness DECISIONS of the
 * SeamProbe class, not work-abandoning timeouts: a probe that reports
 * "nothing answered inside the window" has completed its job — the person
 * taps again when they want another look.
 */
@SuppressLint("MissingPermission")   // the activity gates on runtime grants first
class RingProbe(private val context: Context) {

    data class Report(
        val name: String? = null,
        val address: String? = null,
        val rssi: Int? = null,
        /** Every discovered service uuid, with its characteristics + properties. */
        val services: List<String> = emptyList(),
        val batteryPct: Int? = null,
        val firmwareRev: String? = null,
        val failure: String? = null,
        /** Listen mode: notify frames captured, and strings pulled from them. */
        val framesHeard: Int = 0,
        val strings: List<String> = emptyList(),
    ) {
        val batteryServicePresent: Boolean get() = services.any { it.startsWith("0000180f") }
        fun summary(): String = failure ?: buildString {
            append("$name ($address, ${rssi ?: "?"} dBm): ${services.size} services")
            append(if (batteryServicePresent) " · Battery Service PRESENT" else " · NO standard Battery Service")
            batteryPct?.let { append(" · battery $it%") }
            firmwareRev?.let { append(" · fw $it") }
            if (framesHeard > 0 || strings.isNotEmpty()) {
                append(" · heard $framesHeard frames")
                if (strings.isNotEmpty()) append(" · " + strings.joinToString(" "))
            }
        }
    }

    /** Which action a probe run performs. */
    enum class Mode { ENUMERATE, LISTEN }
    private var mode = Mode.ENUMERATE
    private var listenMs = LISTEN_WINDOW_MS
    private var framesHeard = 0
    private val heardStrings = LinkedHashSet<String>()

    private val handler = Handler(Looper.getMainLooper())
    private val done = AtomicBoolean(false)
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private lateinit var onDone: (Report) -> Unit

    // partial results, filled as stages complete
    private var devName: String? = null
    private var devAddr: String? = null
    private var devRssi: Int? = null
    private var serviceLines: List<String> = emptyList()
    private var batteryPct: Int? = null
    private var firmwareRev: String? = null
    private var readQueue: ArrayDeque<BluetoothGattCharacteristic> = ArrayDeque()
    private var cccdQueue: ArrayDeque<BluetoothGattCharacteristic> = ArrayDeque()

    /** Enable notifications on the next queued characteristic (one GATT op at a
     *  time); when the queue drains, open the listen window. */
    private fun enableNext(g: BluetoothGatt) {
        if (cccdQueue.isEmpty()) {
            Log.i("ringprobe", "all subscriptions set — listening ${listenMs / 1000} s " +
                "(TOUCH THE RING — tap/swipe it — to generate frames; a charger on/off forces a battery frame)")
            handler.postDelayed({ if (!done.get()) finish(partial(null)) }, listenMs)
            return
        }
        val c = cccdQueue.removeFirst()
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD)
        if (cccd == null) { enableNext(g); return }
        @Suppress("DEPRECATION")
        cccd.value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        @Suppress("DEPRECATION")
        if (!g.writeDescriptor(cccd)) {
            Log.w("ringprobe", "CCCD write on ${c.uuid.toString().take(8)} refused to start")
            enableNext(g)
        }
    }

    /** Read-only Battery Level / Device Information targets. */
    private fun std(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))
    private val batteryLevelUuid = std(0x2A19)
    private val firmwareRevUuid = std(0x2A26)
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * LISTEN mode: subscribe to every notify characteristic (CCCD enable only
     * — no data write to the ring) and record what streams for [seconds].
     * Confirms whether the ring pushes unsolicited, pulls firmware/serial
     * strings out of the proprietary frames (the §10.13 ring-version check),
     * and captures raw frames so a battery byte can be isolated — G2CC's note
     * is that battery is pushed on a CHANGE, so a charger on/off during the
     * window is what forces a battery frame to appear.
     */
    fun listen(seconds: Int, address: String?, onDone: (Report) -> Unit) {
        mode = Mode.LISTEN
        listenMs = seconds * 1000L
        this.onDone = onDone
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { finish(Report(failure = "Bluetooth is off")); return }
        handler.postDelayed({ if (!done.get()) finish(partial(null)) }, listenMs + CONNECT_SLACK_MS)
        if (address != null) {
            devAddr = address; devName = "EVEN R1 (remembered)"
            connect(adapter.getRemoteDevice(address))
        } else {
            startScan(adapter, connectOnFind = true)
        }
    }

    fun run(onDone: (Report) -> Unit) {
        this.onDone = onDone
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            finish(Report(failure = "Bluetooth is off"))
            return
        }
        // the overall watchdog: whatever stage stalls, the person gets a report
        handler.postDelayed({
            if (!done.get()) {
                Log.w("ringprobe", "probe did not finish inside ${WATCHDOG_MS / 1000} s — reporting what it has")
                finish(partial("stalled after ${WATCHDOG_MS / 1000} s (see logcat for the last stage)"))
            }
        }, WATCHDOG_MS)
        startScan(adapter, connectOnFind = true)
    }

    private fun startScan(adapter: android.bluetooth.BluetoothAdapter, connectOnFind: Boolean) {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { finish(Report(failure = "no LE scanner (Bluetooth off?)")); return }
        Log.i("ringprobe", "scanning for an EVEN R1 advertisement (${SCAN_WINDOW_MS / 1000} s window)")
        scanning = true
        scanner.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        handler.postDelayed({
            if (scanning) {
                stopScan()
                finish(Report(failure = "ring not seen advertising in ${SCAN_WINDOW_MS / 1000} s — " +
                    "is another app holding its phone slot (Even app / G2CC bridge)?"))
            }
        }, SCAN_WINDOW_MS)
    }

    private fun partial(failure: String?) = Report(devName, devAddr, devRssi, serviceLines,
        batteryPct, firmwareRev, failure, framesHeard, heardStrings.toList())

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (!name.startsWith("EVEN R1")) return
            if (!scanning) return
            stopScan()
            devName = name
            devAddr = result.device.address
            devRssi = result.rssi
            Log.i("ringprobe", "found $name at ${result.device.address} (${result.rssi} dBm) — connecting (no pairing)")
            // dump the raw advertisement FIRST — some rings broadcast battery
            // in manufacturer/service data, readable with no connection at all
            dumpAdvertisement(result)
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            if (!scanning) return
            stopScan()
            finish(partial("scan failed (code $errorCode)"))
        }
    }

    private fun stopScan() {
        scanning = false
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (e: Exception) {
            Log.w("ringprobe", "stopScan: ${e.message}")
        }
    }

    /** Connect directly by a known address — the re-read path (no scan). */
    fun runAt(address: String, onDone: (Report) -> Unit) {
        this.onDone = onDone
        devAddr = address
        devName = "EVEN R1 (remembered)"
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            finish(Report(failure = "Bluetooth is off"))
            return
        }
        handler.postDelayed({
            if (!done.get()) finish(partial("stalled after ${WATCHDOG_MS / 1000} s"))
        }, WATCHDOG_MS)
        connect(adapter.getRemoteDevice(address))
    }

    private fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    /** Log the whole advertisement — manufacturer-specific and service data —
     *  so a broadcast battery byte (if any) is visible without connecting. */
    private fun dumpAdvertisement(result: ScanResult) {
        val rec = result.scanRecord
        if (rec == null) { Log.i("ringprobe", "adv: (no scan record)"); return }
        rec.bytes?.let { Log.i("ringprobe", "adv raw: ${it.joinToString("") { b -> "%02x".format(b) }}") }
        val msd = rec.manufacturerSpecificData
        for (i in 0 until (msd?.size() ?: 0)) {
            val id = msd!!.keyAt(i)
            Log.i("ringprobe", "adv mfr 0x%04x: %s".format(id, msd.valueAt(i).joinToString("") { "%02x".format(it) }))
        }
        rec.serviceData?.forEach { (uuid, data) ->
            Log.i("ringprobe", "adv svcdata $uuid: ${data.joinToString("") { "%02x".format(it) }}")
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i("ringprobe", "connection state $newState (status $status)")
            when {
                newState == BluetoothGatt.STATE_CONNECTED -> {
                    if (!g.discoverServices()) finish(partial("discoverServices() refused to start"))
                }
                newState == BluetoothGatt.STATE_DISCONNECTED && !done.get() ->
                    finish(partial("ring disconnected during the probe (status $status)"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(partial("service discovery status $status"))
                return
            }
            val lines = ArrayList<String>()
            for (s in g.services) {
                val chars = s.characteristics.joinToString(" ") {
                    "${it.uuid.toString().take(8)}(p${it.properties})"
                }
                lines.add("${s.uuid} :: $chars")
                Log.i("ringprobe", "service ${s.uuid} — $chars")
            }
            serviceLines = lines
            if (mode == Mode.LISTEN) {
                // subscribe to every notify characteristic — a CCCD enable only,
                // never a data write to the ring. Android runs ONE GATT op at a
                // time: fire the descriptor writes SERIALLY, one per
                // onDescriptorWrite (firing them together drops all but the
                // first — the v2 zero-frames bug). VENDOR notify chars first, so
                // the useful subscriptions land even if a later one stalls.
                val notifyBit = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE
                val all = g.services.flatMap { it.characteristics }
                    .filter { it.properties and notifyBit != 0 && it.getDescriptor(CCCD) != null }
                val vendorFirst = all.sortedByDescending { it.uuid.toString().startsWith("bae8") }
                cccdQueue = ArrayDeque(vendorFirst)
                if (cccdQueue.isEmpty()) { finish(partial("no notify characteristics to listen on")); return }
                enableNext(g)
                return
            }
            // ENUMERATE: queue the read-only targets that exist; nothing else is touched
            readQueue = ArrayDeque()
            for (s in g.services) for (c in s.characteristics) {
                if ((c.uuid == batteryLevelUuid || c.uuid == firmwareRevUuid) &&
                    c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    readQueue.add(c)
                }
            }
            if (readQueue.isEmpty()) finish(partial(null)) else nextRead(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val cu = d.characteristic.uuid.toString().take(8)
            if (status == BluetoothGatt.GATT_SUCCESS) Log.i("ringprobe", "notify enabled on $cu")
            else Log.w("ringprobe", "CCCD write on $cu failed (status $status)")
            enableNext(g)      // next enable, or start the listen window
        }

        @Deprecated("the (gatt, characteristic) form — the API-31 floor")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            framesHeard++
            val hex = v.joinToString("") { "%02x".format(it) }
            Log.i("ringprobe", "frame #$framesHeard ${c.uuid.toString().take(8)} len=${v.size} $hex")
            // pull printable runs (firmware "2.2.6.0009", serial, MAC-as-ascii)
            val run = StringBuilder()
            for (b in v) {
                val ch = b.toInt() and 0xFF
                if (ch in 0x20..0x7e) run.append(ch.toChar())
                else { if (run.length >= 4) heardStrings.add(run.toString()); run.setLength(0) }
            }
            if (run.length >= 4) heardStrings.add(run.toString())
        }

        private fun nextRead(g: BluetoothGatt) {
            val c = readQueue.removeFirstOrNull() ?: run { finish(partial(null)); return }
            Log.i("ringprobe", "reading ${c.uuid} (one attempt)")
            if (!g.readCharacteristic(c)) {
                Log.w("ringprobe", "read of ${c.uuid} refused to start")
                nextRead(g)
            }
        }

        @Deprecated("the (gatt, characteristic, status) form — the API-31 floor")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val v = c.value ?: ByteArray(0)
                when (c.uuid) {
                    batteryLevelUuid -> {
                        batteryPct = (v.getOrNull(0)?.toInt() ?: -1).and(0xFF).takeIf { it in 0..100 }
                        Log.i("ringprobe", "battery level read: $batteryPct% (raw ${v.joinToString("") { "%02x".format(it) }})")
                    }
                    firmwareRevUuid -> {
                        firmwareRev = v.toString(Charsets.UTF_8).trimEnd(' ')
                        Log.i("ringprobe", "firmware revision read: '$firmwareRev'")
                    }
                }
            } else {
                // 0x05/0x0F = insufficient authentication/encryption: REPORT it,
                // never retry — the retry is where the stack starts pairing
                Log.w("ringprobe", "read of ${c.uuid} failed with status $status — not retried")
            }
            nextRead(g)
        }
    }

    private fun finish(report: Report) {
        if (!done.compareAndSet(false, true)) return
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.w("ringprobe", "close: ${e.message}")
        }
        gatt = null
        Log.i("ringprobe", "probe done: ${report.summary()}")
        onDone(report)
    }

    companion object {
        /** SeamProbe-class decision windows (pacing, not work abandonment). */
        const val SCAN_WINDOW_MS = 15_000L
        const val WATCHDOG_MS = 30_000L
        const val LISTEN_WINDOW_MS = 45_000L
        /** Slack past the listen window for connect + discovery before the
         *  watchdog reports whatever was heard. */
        const val CONNECT_SLACK_MS = 20_000L
    }
}
