package wm.damage.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.PhyRequest
import no.nordicsemi.android.ble.callback.ConnectionParametersUpdatedCallback
import no.nordicsemi.android.ble.callback.PhyCallback
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.PairFind
import wm.damage.core.transport.PairWatch
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
 *     wait for the screen); unfiltered by name only for a pair never seen.
 *     A remembered lens the scan has not seen and the phone holds a link to
 *     does not advertise: it is taken by its address ([PairWatch], stage BL)
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
    /** Build C1-P: where the C1 ladder's stage is kept across a restart of the process (the app's data directory) —
     *  no default, so the one construction cannot leave it out. */
    c1StageFile: java.nio.file.Path,
    /** Build C1-P R1 §1: the last run ended on an uncaught error — a stored stage steps down to 0 (the first stack
     *  only; no default, as above). */
    c1StageStepDown: Boolean,
) : CfwTransportBase(scope, "ble", c1StageFile = c1StageFile, c1StageStepDown = c1StageStepDown) {

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

        /** Stage BLF: the PHY (tx to rx) this connection last reported — the read at its initialize, and (stage BW)
         *  the answer to a PHY request on it, so a link kept through a restart reads the PHY it is on; null until
         *  the read answers. */
        @Volatile var phyAtConnect: Pair<Int, Int>? = null

        init {
            // EVERY parameter update on this link, ours or the peripheral's
            // (2026-09-05, `HANDOFF.md` §32): the priority request below has
            // no result API, and the production journal's slow regime is this
            // radio path — whether HIGH was granted, and what the glasses
            // renegotiate later (the captures show a 30 → 90 ms move on the
            // official app), is exactly the unknown this answers
            setConnectionParametersListener { _, interval, latency, timeout ->
                noteLinkParams(arm, "updated", interval, latency, timeout)
                onParamsUpdated(arm, interval, latency)
            }
        }

        /** Ask the platform for the configured priority (§47). The request is
         *  best-effort and stands ALONE in the queue: inside an atomic queue a
         *  failed child ends the queue (Nordic 2.7.5) and would drop the MTU
         *  and notification requests behind it. */
        fun requestPriority(arm: Arm, why: String) {
            val target = priorityTarget
            requestConnectionPriority(target)
                .with(ConnectionParametersUpdatedCallback { _, interval, latency, timeout ->
                    noteLinkParams(arm, "granted", interval, latency, timeout)
                })
                .fail { _, status -> Log.w("ble", "$arm connection priority request ($why) status $status (continuing)") }
                .enqueue()
        }

        /** §49 probe (FORK.md M0.4): ask for a PHY on this arm. The controllers
         *  settle it between themselves; the answer, whatever it is, is logged and
         *  journaled — a refusal leaves the link on the PHY it had. */
        fun requestPhy(mask: Int, label: String) {
            setPreferredPhy(mask, mask, PhyRequest.PHY_OPTION_NO_PREFERRED)
                .with(PhyCallback { _, tx, rx ->
                    phyAtConnect = tx to rx          // stage BW: the link's PHY now, for the 2M skip on a kept link
                    notePhy(arm, tx, rx)
                    emitNote("link", "$arm PHY after the $label request: ${phyName(tx)}/${phyName(rx)}")
                })
                .fail { _, status ->
                    Log.w("ble", "$arm PHY request ($label) status $status")
                    emitNote("probe", "$arm PHY request ($label) status $status")
                }
                .enqueue()
        }

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
                    // the record first, running or not: the other arm's end has already stopped the
                    // session, and THIS arm's reason may be the one that names a lens reboot — the
                    // atlas carry decision reads it (2026-09-15, second review)
                    noteArmLinkEnd("$arm disconnected: ${reasonName(reason)}")
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
            // otherwise leave notifications inert and the capability gate
            // waiting in silence. Order per the CFW reference: priority, MTU,
            // notifications. The priority request is best-effort and stands
            // ALONE: inside the atomic queue a failed child ends the queue
            // (Nordic 2.7.5: the child's failure marks the queue finished and
            // `hasMore()` stops it), which would drop the MTU and notification
            // requests behind it.
            requestPriority(arm, "connect")
            beginAtomicRequestQueue()
                .add(requestMtu(REQUESTED_MTU)
                    .with { _, mtu -> negotiatedMtu = mtu; Log.i("ble", "$arm MTU negotiated $mtu") }
                    .fail { _, status -> initFailure = "$arm MTU request failed: status $status" })
                .add(enableNotifications(notify)
                    .fail { _, status -> initFailure = "$arm notification enable failed: status $status — acks would never arrive" })
                .done { Log.i("ble", "$arm initialized (MTU + notifications)") }
                .enqueue()
            // the PHY in use, read once (1M is all the glasses accept — this
            // records it rather than assumes it); best-effort, alone
            readPhy()
                .with(PhyCallback { _, tx, rx -> phyAtConnect = tx to rx; notePhy(arm, tx, rx) })
                .fail { _, status -> Log.w("ble", "$arm PHY read status $status (continuing)") }
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

    /** Per-arm "interval/latency/timeout [phy]" as the platform reported
     *  them, joined into [LinkState.linkParams] so the shell journals each
     *  change and the phone's journal (served at /journal) carries it. */
    private val linkParamsByArm = java.util.concurrent.ConcurrentHashMap<Arm, String>()
    private val phyByArm = java.util.concurrent.ConcurrentHashMap<Arm, String>()

    private fun publishLinkParams() {
        val s = Arm.entries.mapNotNull { a ->
            val p = linkParamsByArm[a] ?: return@mapNotNull null
            "${a.name.first()} $p${phyByArm[a]?.let { " $it" } ?: ""}"
        }.joinToString(" · ")
        // §47: the worst arm as numbers, so the shell's regime reads them at once
        val interval = intervalUnitsByArm.values.maxOrNull()?.let { it * 1.25 } ?: 0.0
        val latency = latencyByArm.values.maxOrNull() ?: 0
        updateState { it.copy(linkParams = s, linkIntervalMs = interval, linkLatency = latency) }
    }

    // ------------------------------------------------------------ §47: the priority
    /** The configured priority (Global `Link`): HIGH by default. */
    @Volatile private var priorityTarget = BluetoothGatt.CONNECTION_PRIORITY_HIGH
    @Volatile private var priorityName = "high"
    private val intervalUnitsByArm = java.util.concurrent.ConcurrentHashMap<Arm, Int>()
    private val latencyByArm = java.util.concurrent.ConcurrentHashMap<Arm, Int>()
    private val reaskJobs = java.util.concurrent.ConcurrentHashMap<Arm, Job>()
    private val lastReaskMs = java.util.concurrent.ConcurrentHashMap<Arm, Long>()
    private val reasks = java.util.concurrent.atomic.AtomicInteger(0)

    /** The Global `Link` row (§47): "high" or "balanced". A change while the
     *  arms are up is asked for at once; the next connect asks for it in
     *  its initialize. */
    override fun setLinkPriority(name: String) {
        val target = when (name) {
            "balanced" -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            "high" -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
            else -> { Log.w("ble", "unknown link priority '$name' ignored (high | balanced)"); return }
        }
        if (target == priorityTarget) return
        priorityTarget = target
        priorityName = name
        Log.i("ble", "link priority -> $name")
        for ((arm, m) in managers) if (m.linkUp) m.requestPriority(arm, "setting")
    }

    /** The interval above which the configured priority counts as lost:
     *  Android grants HIGH at 11.25–15 ms and BALANCED at 30–50 ms
     *  (BluetoothGatt.CONNECTION_PRIORITY_* documentation); the glasses'
     *  own fast set is 15 ms / latency 1 and their idle set 105 ms / 4. */
    private fun maxIntervalMs(): Double =
        if (priorityTarget == BluetoothGatt.CONNECTION_PRIORITY_BALANCED) 60.0 else 30.0

    /**
     * §47 (2026-09-12): a parameter update on a live arm. The glasses move
     * the link to their idle set themselves (105 ms / latency 4 at 12:00:02
     * that day, after a stock-UI excursion; the captures show the same
     * 30 → 90 ms move under the official app) and the platform applies a
     * peripheral's request without asking the app — the only answer is to
     * ask for OUR priority again. Paced per arm ([PRIORITY_REASK_MS], a
     * pacing between requests, not a bound on anything) and only if the
     * link is STILL slow when the pacing is up: the connect sequence passes
     * through 48.75 ms on its way to 15 and must not be answered.
     */
    private fun onParamsUpdated(arm: Arm, intervalUnits: Int, latency: Int) {
        intervalUnitsByArm[arm] = intervalUnits
        latencyByArm[arm] = latency
        publishLinkParams()
        val m = managers.getValue(arm)
        if (!m.linkUp || !running) return
        if (intervalUnits * 1.25 <= maxIntervalMs()) return
        if (reaskJobs[arm]?.isActive == true) return          // one pending re-ask per arm
        reaskJobs[arm] = scope.launch {
            val wait = PRIORITY_REASK_MS - (System.currentTimeMillis() - (lastReaskMs[arm] ?: 0L))
            if (wait > 0) delay(wait)
            val now = System.currentTimeMillis()
            val stillSlow = (intervalUnitsByArm[arm] ?: 0) * 1.25 > maxIntervalMs()
            if (!m.linkUp || !running || !stillSlow) return@launch
            lastReaskMs[arm] = now
            val n = reasks.incrementAndGet()
            val text = "$arm at %.2f ms / latency %d — asking for $priorityName again (#$n this session)"
                .format((intervalUnitsByArm[arm] ?: 0) * 1.25, latencyByArm[arm] ?: 0)
            Log.i("ble", text)
            emitNote("link", text)
            m.requestPriority(arm, "re-ask #$n")
        }
    }

    private fun resetPriorityBookkeeping() {
        for (j in reaskJobs.values) j.cancel()
        reaskJobs.clear()
        lastReaskMs.clear()
        intervalUnitsByArm.clear()
        latencyByArm.clear()
        reasks.set(0)
    }

    /** HCI units: interval × 1.25 ms, latency in intervals, timeout × 10 ms. */
    private fun noteLinkParams(arm: Arm, how: String, interval: Int, latency: Int, timeout: Int) {
        val text = "%.2fms/%d/%dms".format(interval * 1.25, latency, timeout * 10)
        Log.i("ble", "$arm connection parameters $how: interval ${"%.2f".format(interval * 1.25)} ms, " +
            "latency $latency, supervision timeout ${timeout * 10} ms")
        linkParamsByArm[arm] = text
        publishLinkParams()
    }

    private fun phyName(p: Int) = when (p) { PhyCallback.PHY_LE_1M -> "1M"; PhyCallback.PHY_LE_2M -> "2M"; PhyCallback.PHY_LE_CODED -> "coded"; else -> "phy$p" }

    private fun notePhy(arm: Arm, tx: Int, rx: Int) {
        Log.i("ble", "$arm PHY tx ${phyName(tx)} rx ${phyName(rx)}")
        phyByArm[arm] = "phy ${phyName(tx)}/${phyName(rx)}"
        publishLinkParams()
    }

    /** `FIRMWARE.md` §4: a build carrying the link edits offers LE 2M — ask for it on both arms;
     *  the PHY callback journals the answer as a `link` note ("PHY after the link request"). */
    override fun onDamageBuild(caps: wm.damage.core.wire.DamageMsg.Caps) {
        if (!caps.has(wm.damage.core.wire.DamageMsg.FEATURE_LINK)) return
        // a `phy=1m` probe is an A/B the rebuilds must not undo (2026-09-15 review)
        if (phyProbe == "1m") { emitNote("link", "the build offers LE 2M (DamageCaps bit 6), but phy=1m was probed — not requested"); return }
        // name the arms that were actually asked: an arm whose link was not up at this moment is
        // not asked, and saying "both arms" then is a record that is not true — on a surface whose
        // whole theme is the two arms holding different state (2026-09-15, the third review)
        val asked = ArrayList<String>()
        val skipped = ArrayList<String>()
        val onTwoM = ArrayList<String>()
        for ((arm, m) in managers) {
            if (!m.linkUp) { skipped.add(arm.name); continue }
            // stage BLF: a link already on 2M both ways (a link the phone held from the session before) is not asked —
            // the request would change nothing and the arm's queue would wait on the radio to answer a no-change
            // request. The PHY is the one this connection last reported (its initialize's read, or — stage BW, a link
            // kept through a restart — the answer to the last session's request), not one read just before this line.
            val p = m.phyAtConnect
            if (p != null && p.first == PhyCallback.PHY_LE_2M && p.second == PhyCallback.PHY_LE_2M) { onTwoM.add(arm.name); continue }
            m.requestPhy(PhyRequest.PHY_LE_2M_MASK, "link")
            asked.add(arm.name)
        }
        if (onTwoM.isNotEmpty()) Log.i("ble", "2M request skipped on ${onTwoM.joinToString("+")}: the PHY read at its connect already said 2M both ways")
        emitNote("link", "the build offers LE 2M (DamageCaps bit 6): 2M requested on ${if (asked.isEmpty()) "no arm" else asked.joinToString("+")}" +
            (if (skipped.isEmpty()) "" else " (${skipped.joinToString("+")} had no link up)") +
            (if (onTwoM.isEmpty()) "" else "; not requested on ${onTwoM.joinToString("+")}: the PHY read at its connect already said 2M both ways"))
    }

    /** The last `phy=` probe's choice, kept across rebuilds (null until one is probed). */
    @Volatile private var phyProbe: String? = null

    /** §49: the phone's radio adds the PHY probe; diag and logger are the base's. */
    override fun devProbe(name: String, value: String) {
        if (name != "phy") return super.devProbe(name, value)
        Log.i("ble", "probe: $name=$value")
        emitNote("probe", "$name=$value")
        val mask = when (value) {
            "2m" -> PhyRequest.PHY_LE_2M_MASK
            "1m" -> PhyRequest.PHY_LE_1M_MASK
            else -> { Log.w("ble", "probe phy=$value: expected 2m | 1m"); return }
        }
        phyProbe = value
        var asked = 0
        for ((arm, m) in managers) {
            if (!m.linkUp) { Log.w("ble", "probe phy=$value: $arm is not connected"); continue }
            m.requestPhy(mask, value)
            asked++
        }
        if (asked == 0) emitNote("probe", "phy=$value not asked: no arm connected")
    }

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

    /** Stage BW: a deliberate restart keeps the link when both arms are up right now. */
    override fun linkKeepable(): Boolean = managers.values.all { it.linkUp && it.isConnected }

    /** Stage BW: adopt the link kept through the restart — both arms demonstrably up (the session's mark, the
     *  platform's connection and the write and notify characteristics), else the words why not and today's
     *  [connectLink] runs (it ends what is left first). The link's properties are not asked for again where they
     *  are in force: the paced priority re-ask's test once per arm (asked only where the arm's last interval is
     *  above the configured priority's bound), and the 2M request is skipped by [onDamageBuild]'s own rule on a
     *  link that reads 2M. Nothing is reset that describes the live connection (MTU, intervals, PHY); the re-ask
     *  count is the session's and starts again. */
    override suspend fun adoptLink(): String? {
        val down = managers.entries.filter { (_, m) -> !(m.linkUp && m.isConnected && m.write != null && m.notify != null) }
            .map { (arm, m) -> "${arm.name.first()}: " + when {
                !m.linkUp -> "its link ended in the gap"
                !m.isConnected -> "not connected"
                else -> "its characteristics are gone" } }
        if (down.isNotEmpty()) return down.joinToString("; ")
        reasks.set(0)
        val asked = ArrayList<String>()
        for ((arm, m) in managers) {
            val iv = intervalUnitsByArm[arm] ?: continue          // no update reported on this link: nothing says it is slow
            if (iv * 1.25 <= maxIntervalMs()) continue
            asked.add("${arm.name.first()} at %.2f ms".format(iv * 1.25))
            m.requestPriority(arm, "kept link")
        }
        Log.i("ble", "kept link: R+L adopted — no scan, no connect; " +
            (if (asked.isEmpty()) "priority in force on both arms, not asked" else "$priorityName asked again on ${asked.joinToString(", ")}"))
        if (asked.isNotEmpty()) emitNote("link", "kept link: $priorityName asked again on ${asked.joinToString(", ")} (above ${maxIntervalMs().toInt()} ms)")
        return null
    }

    override suspend fun connectLink() {
        resetPriorityBookkeeping()          // §47: per session, like the counters
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
        val found = scanForPair()
        val left = found.left
        val right = found.right
        Log.i("ble", "connecting R=${right.address} then L=${left.address}")
        // stage BL: the arm whose connect or check failed — a lens taken by its
        // address that does not answer gets the hand recovery's words
        var step: Arm? = null
        try {
            // RIGHT first, then LEFT — the CFW reference's order (control + events
            // ride RIGHT; bulk pixels go LEFT)
            for ((arm, dev) in listOf(Arm.RIGHT to right, Arm.LEFT to left)) {
                step = arm
                val m = managers.getValue(arm)
                m.negotiatedMtu = -1
                m.initFailure = null
                m.phyAtConnect = null
                updateState { it.copy(detail = "connecting ${arm.name}" + heldWords(arm, found.via(arm))) }
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
                step = arm
                m.linkUp = false
                throw IllegalStateException("$arm dropped while the pair was being connected")
            }
        } catch (e: Exception) {
            val a = step
            if (e !is CancellationException && a != null && found.via(a) == PairFind.Via.HELD && heldFailed.add(a)) {
                Log.w("ble", "${a.name.first()} was taken by its address and did not connect (${e.message}): the phone " +
                    "holds a link to it that does not answer — turn Bluetooth off and on (the app keeps trying meanwhile)")
            }
            throw e
        }
        heldFailed.clear()
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

    /** Stage BL: the lenses taken by their address whose connect then failed —
     *  words in the status detail only (the hand recovery), until a connect
     *  completes, the scan sees that lens advertise or Bluetooth goes off. */
    private val heldFailed = java.util.concurrent.ConcurrentHashMap.newKeySet<Arm>()

    /** Stage BLF: what the scan's decision reads — the phone's link notices for
     *  the transport's life, one scan's sightings and asks ([PairWatch]). Every
     *  call is made under this object's lock: the notices' receiver (main
     *  thread), the scan's callback (a binder thread) and the asker. */
    private val watch = PairWatch()

    /** Stage BLF: the phone's LE link-up and link-down notices (they see a link
     *  whoever holds it, where the GATT list sees only an app's) and Bluetooth
     *  turning off, for the transport's life — registered and unregistered in
     *  the `init` at the end of this class. */
    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (i.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE) != BluetoothDevice.TRANSPORT_LE) return
                    val dev = deviceOf(i) ?: run { Log.w("ble", "link notice ${i.action} with no device — not recorded"); return }
                    val up = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                    synchronized(watch) { watch.linkNotice(dev.address, up, System.currentTimeMillis()) }
                    val known = remembered()
                    val tag = when { dev.address.equals(known.leftAddress, true) -> "L"; dev.address.equals(known.rightAddress, true) -> "R"; else -> null }
                    if (tag != null) Log.i("ble", "$tag ${dev.address}: link-${if (up) "up" else "down"} notice")
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                        synchronized(watch) { watch.bluetoothOff() }
                        heldFailed.clear()        // the hand recovery's words end with the toggle they asked for
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(i: Intent): BluetoothDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** What the scan settled on: each lens's device and where it came from. */
    private class Found(val left: BluetoothDevice, val right: BluetoothDevice, val decision: PairFind.Decision.Connect) {
        fun via(arm: Arm) = if (arm == Arm.LEFT) decision.left else decision.right
    }

    private fun stateName(s: Int) = when (s) {
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"; BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_CONNECTED -> "connected"; BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        else -> "state $s"
    }

    /** Stage BLF: what the phone showed for one remembered lens, in the words of what was read. */
    private fun signalWords(listed: Boolean?, n: PairWatch.Notice?): String =
        (when (listed) { true -> "the phone lists it as connected"; false -> "the phone does not list it as connected"; null -> "the phone's list could not be read" }) +
            "; " + when {
                n == null -> "no link notice in this process"
                n.up -> "a link-up notice at ${clock(n.atMs)} and no link-down notice since"
                else -> "a link-down notice at ${clock(n.atMs)}"
            }

    private fun clock(ms: Long) = String.format(java.util.Locale.ROOT, "%tT", ms)

    private fun lensLine(arm: Arm, addr: String, seen: Boolean, listed: Boolean?, n: PairWatch.Notice?, own: Int, bond: Int?): String =
        "${arm.name.first()} $addr: ${if (seen) "seen" else "not seen"} by the scan; ${signalWords(listed, n)}; " +
            (if (own != BluetoothProfile.STATE_DISCONNECTED) "this app's own ${arm.name} link ${stateName(own)}; " else "") +
            when (bond) { null -> "bond unknown"; BluetoothDevice.BOND_BONDED -> "bonded"; BluetoothDevice.BOND_BONDING -> "bonding"; else -> "not bonded" }

    /** The status detail's words for a lens taken by its address. */
    private fun heldWords(arm: Arm, via: PairFind.Via): String = when {
        via != PairFind.Via.HELD -> ""
        arm in heldFailed -> " (by its address) — the phone holds a link to it that did not answer last time: turn Bluetooth off and on"
        else -> " (by its address: the phone already held a link to it — another app talking to the glasses would be one cause)"
    }

    /** Find both lenses: by advertised name — "Even G2_XX_L_YYYY" / "_R_"
     *  (G2CC G2Constants: the pair advertises as two devices) — or by a
     *  remembered address when the advertisement carries no name. No timeout:
     *  scanning continues until both arms are found. Stage BL: with a pair
     *  remembered, the phone is asked on a pacing tick whether it holds a link
     *  to each lens (its GATT list, and the link notices' record); a lens with
     *  a link up does not advertise and is taken by its address
     *  ([PairWatch.decide]). The phone-side recovery for a pair that stays
     *  invisible is toggling Bluetooth (REMINDER.md). */
    private suspend fun scanForPair(): Found {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bt = bm.adapter ?: throw IllegalStateException("no bluetooth adapter")
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
        // stage BL: a lens taken by its address that did not answer — the hand recovery, named while it holds
        val failedWords = heldFailed.sortedBy { it.ordinal }.joinToString(" and ") { it.name }
            .let { if (it.isEmpty()) "" else " — $it: the phone holds a link to it that did not answer: turn Bluetooth off and on" }
        updateState { it.copy(detail = (if (filters.isEmpty()) "scanning for the pair (first time: needs the screen on)" else "scanning for the remembered pair") + failedWords) }
        val done = CompletableDeferred<Found>()
        // Bluetooth turning OFF mid-scan does NOT reliably reach onScanFailed
        // — the scan just goes quiet and the await would park forever (G2CC's
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
        // this scan's devices (index = Arm.ordinal for the held ones) — read and written only
        // under `watch`'s lock, with the sightings and the asks: the scan's callback, the asker
        // and the notices' receiver all reach it
        var left: BluetoothDevice? = null
        var right: BluetoothDevice? = null
        val heldDev = arrayOfNulls<BluetoothDevice>(2)
        val lastListed = arrayOfNulls<Boolean>(2)
        synchronized(watch) { watch.scanStarts(cachedL, cachedR) }
        scanNames.clear()
        /** Under `watch`'s lock: the decision ([PairWatch.decide]); a connect completes the wait. */
        fun decideLocked() {
            if (done.isCompleted) return
            val d = watch.decide()
            if (d !is PairFind.Decision.Connect) return
            val l = if (d.left == PairFind.Via.SCAN) left else heldDev[0]
            val r = if (d.right == PairFind.Via.SCAN) right else heldDev[1]
            if (l == null || r == null) { Log.e("ble", "pair decision $d without a device (L ${l != null}, R ${r != null}) — still waiting"); return }
            if (!done.complete(Found(l, r, d))) return      // the wait ended another way first: nothing was taken
            for (arm in Arm.entries) {
                if ((if (arm == Arm.LEFT) d.left else d.right) != PairFind.Via.HELD) continue
                val i = arm.ordinal
                Log.i("ble", "${arm.name.first()} taken by its remembered address ${heldDev[i]?.address} on ${watch.heldAsks(arm)} " +
                    "asks in a row (every ${HELD_ASK_MS / 1000} s): ${signalWords(lastListed[i], watch.notice(arm))}; the scan has not " +
                    "seen it — a lens with a link up does not advertise. The phone already held a link to it; another app talking " +
                    "to the glasses would be one cause (if one does, both now talk to them)")
                // a lens taken by its address keeps the name remembered for it (the scan's name filter)
                (if (arm == Arm.LEFT) known.leftName else known.rightName)?.let { scanNames[arm] = it }
            }
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // the ADVERTISED name: device.name needs BLUETOOTH_CONNECT and a
                // cached bond; scanRecord.deviceName rides the advertisement
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val addr = result.device.address
                val isLeft = (name.startsWith(NAME_PREFIX) && LEFT_INFIX in name) || addr.equals(cachedL, true)
                val isRight = (name.startsWith(NAME_PREFIX) && RIGHT_INFIX in name) || addr.equals(cachedR, true)
                synchronized(watch) {
                    if (isLeft && left == null) { left = result.device; if (name.isNotEmpty()) scanNames[Arm.LEFT] = name; Log.i("ble", "found L '$name' $addr"); heldFailed.remove(Arm.LEFT); watch.seen(Arm.LEFT) }
                    if (isRight && right == null) { right = result.device; if (name.isNotEmpty()) scanNames[Arm.RIGHT] = name; Log.i("ble", "found R '$name' $addr"); heldFailed.remove(Arm.RIGHT); watch.seen(Arm.RIGHT) }
                    decideLocked()
                }
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
        // Stage BL/BLF: with a pair remembered, ask on a pacing tick whether the
        // phone holds a link to each lens — a lens with a link up does not
        // advertise, and the scan would wait for it without end (2026-11-25
        // 15:02). Two signals: the phone's GATT list (read here) and its link
        // notices (the receiver's record); [PairWatch] counts and decides. Each
        // lens's line is said once per change; a read that cannot be made is
        // said once per kind and decides nothing. Pacing between asks, not a
        // bound: the wait stays endless.
        val asker = if (cachedL == null || cachedR == null) null else scope.launch {
            val said = arrayOfNulls<String>(2)
            val failSaid = HashSet<String>()
            fun sayOnce(what: String, e: Exception) {
                if (failSaid.add(what + e.javaClass.name)) Log.w("ble", "could not read $what (${e.javaClass.simpleName}: ${e.message}) — this decides nothing")
            }
            while (isActive && !done.isCompleted) {
                delay(HELD_ASK_MS)
                if (done.isCompleted) break
                val devs = try { listOf(bt.getRemoteDevice(cachedL), bt.getRemoteDevice(cachedR)) }
                    catch (e: Exception) { sayOnce("the remembered addresses", e); continue }
                val listedDevs = try { bm.getConnectedDevices(BluetoothProfile.GATT) }
                    catch (e: Exception) { sayOnce("the phone's GATT list", e); null }
                val bonds = devs.map { d -> try { d.bondState } catch (e: Exception) { sayOnce("the bond state", e); null } }
                val own = Arm.entries.map { managers.getValue(it).connectionState }
                synchronized(watch) {
                    for (arm in Arm.entries) {
                        val i = arm.ordinal
                        val listed = listedDevs?.any { it.address.equals(devs[i].address, true) }
                        lastListed[i] = listed
                        heldDev[i] = devs[i]
                        watch.ask(arm, listed, own[i] != BluetoothProfile.STATE_DISCONNECTED)
                        val line = lensLine(arm, devs[i].address, (if (arm == Arm.LEFT) left else right) != null, listed, watch.notice(arm), own[i], bonds[i])
                        if (line != said[i]) { said[i] = line; Log.i("ble", line) }
                    }
                    decideLocked()
                }
            }
        }
        try {
            return done.await()
        } finally {
            pacer.cancel()
            asker?.cancel()
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

        /** §47: pacing between two priority re-asks on one arm. A ping-pong
         *  with a firmware that keeps asking for its idle set costs one
         *  small request per pacing, each journaled with its count. */
        const val PRIORITY_REASK_MS = 5_000L

        /** Re-issue a still-hunting scan on this pacing — under the ~30 min
         *  point where Android silently downgrades a long scan, and far under
         *  the 5-starts-per-30-s throttle. */
        const val SCAN_REISSUE_MS = 20L * 60 * 1000

        /** Stage BL: the pacing of the asks while the scan waits with a pair
         *  remembered. After a rebuild the scan found each lens in 0.2–1.4 s
         *  (his log, 2026-11-24 and 11-25), so a lens about to advertise is
         *  normally seen before the first ask; with [PairFind.HELD_ASKS] asks in
         *  a row a held lens is taken about 10 s into the scan, where it waited
         *  for a hand toggle before. An ask is one list call and two bond reads
         *  (reads in the phone's Bluetooth service), nothing on air. */
        const val HELD_ASK_MS = 5_000L
    }

    // Stage BLF: the link notices' receiver for the transport's life — last in the class, so every field it reaches is
    // set before a notice can arrive. Unregistered when the stack's scope ends (`ShellService.stopStack` cancels the
    // scope the transport was built with). A register that fails leaves the record empty: unknown counts as not held.
    init {
        try {
            context.registerReceiver(linkReceiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            })
            val job = scope.coroutineContext[Job]
            if (job == null) Log.w("ble", "link notices: the transport's scope has no job — the receiver stays for the process")
            else job.invokeOnCompletion {
                try { context.unregisterReceiver(linkReceiver) } catch (e: Exception) { Log.w("ble", "link notices receiver: ${e.message}") }
            }
        } catch (e: Exception) {
            Log.w("ble", "link notices not registered (${e.javaClass.simpleName}: ${e.message}) — a lens counts as held only on the phone's GATT list")
        }
    }
}
