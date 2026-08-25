package wm.damage.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.util.Log

/**
 * PC-direct BLE (HANDOFF.md §8.2): the same protocol brain as the phone
 * ([CfwTransportBase]) over a [BlueZLink]. Sequence, from the CFW reference
 * and G2CC's driver: discover the pair by advertised name or cached address
 * (only devices the CURRENT scan sees — BlueZ keeps previously connected
 * devices listed by name even when they are in the case) → connect RIGHT
 * then LEFT → services resolved → service 5450 / chars 5401 + 5402 → MTU
 * checked ≥ 245 → notifications on → the base runs the prelude, capability
 * gate, carrier, lease and warmup. A device's `Connected=false` ends the
 * session (`onLinkDown`); the session keeper starts a new one.
 *
 * Every blocking link call runs in an interruptible IO context, so a
 * cancelled attempt (a lost arbitration, a keeper pause) ends at the next
 * call rather than after the whole sequence; an arm is registered the moment
 * its connect returns, so the rollback disconnects it whatever fails next.
 *
 * ⚠ Never run on a radio during the build (decision 2): the real link is
 * exercised at first light; here the glue is verified over a fake link.
 */
class BlueZTransport(
    private val link: BlueZLink,
    scope: CoroutineScope,
    private val cachedAddresses: () -> Pair<String?, String?> = { null to null },
    private val rememberAddresses: (left: String, right: String) -> Unit = { _, _ -> },
) : CfwTransportBase(scope, "ble") {

    private val devicePath = HashMap<Arm, String>()
    private val writePath = HashMap<Arm, String>()
    private val notifyToArm = HashMap<String, Arm>()
    private val deviceToArm = HashMap<String, Arm>()
    /** Devices that reported `Connected=false` while a connect was in progress. */
    private val droppedDuringConnect = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var listening = false
    private var ticks = 0

    private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO, block)

    private fun onEvent(e: BlueZLink.Event) {
        when (e) {
            is BlueZLink.Event.Notification -> synchronized(notifyToArm) { notifyToArm[e.charPath] }
                ?.let { onNotifyPacket(it, e.value) }
            is BlueZLink.Event.Connected -> if (!e.connected) {
                val arm = synchronized(deviceToArm) { deviceToArm[e.devicePath] } ?: return
                if (running) onLinkDown("$arm disconnected (BlueZ Connected=false)")
                else droppedDuringConnect.add(e.devicePath)
            }
            is BlueZLink.Event.Failure -> emitFault("ble", e.detail)
        }
    }

    override suspend fun connectLink() {
        val a = io { link.adapter() }
        if (!a.powered) throw IllegalStateException("adapter ${a.name} (${a.address}) is not powered")
        if (!listening) { io { link.listen(::onEvent) }; listening = true }
        synchronized(deviceToArm) { deviceToArm.clear() }
        synchronized(notifyToArm) { notifyToArm.clear() }
        droppedDuringConnect.clear()

        val (left, right) = discoverPair()
        Log.i("ble", "connecting R=${right.address} then L=${left.address} via ${a.name}")
        updateState { it.copy(detail = "connecting RIGHT") }
        // RIGHT first, then LEFT — the reference's order (control + events ride
        // RIGHT; bulk pixels go LEFT)
        for ((arm, peer) in listOf(Arm.RIGHT to right, Arm.LEFT to left)) {
            currentCoroutineContext().ensureActive()
            updateState { it.copy(detail = "connecting ${arm.name}") }
            io { link.connect(peer.path) }
            // registered NOW: whatever fails below, the rollback's
            // disconnectLink() finds this arm and releases it
            devicePath[arm] = peer.path
            synchronized(deviceToArm) { deviceToArm[peer.path] = arm }
            awaitResolved(arm, peer.path)
            val chars = io { link.characteristics(peer.path) }
            val mtu = io { link.mtu(chars.notifyPath) } ?: io { link.mtu(chars.writePath) }
                ?: throw IllegalStateException("$arm MTU not readable — refusing: the ${MIN_MTU}-byte check needs the value")
            if (mtu < MIN_MTU)
                throw IllegalStateException("$arm MTU $mtu < $MIN_MTU: a 242 B AA packet would not fit")
            Log.i("ble", "$arm MTU $mtu")
            io { link.startNotify(chars.notifyPath) }
            synchronized(notifyToArm) { notifyToArm[chars.notifyPath] = arm }
            writePath[arm] = chars.writePath
            if (peer.path in droppedDuringConnect)
                throw IllegalStateException("$arm disconnected while the pair was being connected")
        }
        rememberAddresses(left.address, right.address)
        Log.i("ble", "both arms connected")
    }

    /** Poll the device's link state until services are resolved; a link that
     *  ends first is an error. No deadline. */
    private suspend fun awaitResolved(arm: Arm, path: String) {
        while (true) {
            val p = io { link.probe(path) }
            if (p.servicesResolved) return
            if (!p.connected) throw IllegalStateException("$arm disconnected before its services resolved")
            delay(RESOLVE_POLL_MS)
        }
    }

    /** Scan until both arms are seen by THIS scan — by name ("Even G2_.._L_.."
     *  / "_R_") or by a remembered address, and only devices that are
     *  advertising now (RSSI present) or already connected. No deadline; the
     *  keeper's stop or a lost arbitration cancels it. */
    private suspend fun discoverPair(): Pair<BlueZLink.Peer, BlueZLink.Peer> {
        val (cachedL, cachedR) = cachedAddresses()
        fun seenNow(p: BlueZLink.Peer) = p.rssi != null || p.connected
        fun isLeft(p: BlueZLink.Peer) = seenNow(p) &&
            ((p.name.startsWith(NAME_PREFIX) && LEFT_INFIX in p.name) || p.address.equals(cachedL, true))
        fun isRight(p: BlueZLink.Peer) = seenNow(p) &&
            ((p.name.startsWith(NAME_PREFIX) && RIGHT_INFIX in p.name) || p.address.equals(cachedR, true))
        io { link.startDiscovery() }
        Log.i("ble", "scanning for the G2 pair")
        updateState { it.copy(detail = "scanning for the pair") }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!io { link.discovering() })
                    throw IllegalStateException("discovery ended on its own (adapter off, or bluetoothd restarted)")
                val peers = io { link.peers() }
                val l = peers.firstOrNull { isLeft(it) }
                val r = peers.firstOrNull { isRight(it) }
                if (l != null && r != null) {
                    Log.i("ble", "found L '${l.name}' ${l.address}, R '${r.name}' ${r.address}")
                    return l to r
                }
                delay(SCAN_POLL_MS)
            }
        } finally {
            withContext(NonCancellable) { io { link.stopDiscovery() } }
        }
    }

    /** Runs even from a cancelled coroutine (the base's rollback after a lost
     *  arbitration): the links must not outlive the attempt. */
    override suspend fun disconnectLink(): Unit = withContext(NonCancellable) {
        for ((arm, p) in devicePath) {
            synchronized(deviceToArm) { deviceToArm.remove(p) }
            try { io { link.disconnect(p) } } catch (e: Exception) { Log.w("ble", "$arm disconnect: ${e.message}") }
        }
        devicePath.clear()
        writePath.clear()
        synchronized(notifyToArm) { notifyToArm.clear() }
    }

    override suspend fun writeArm(arm: Arm, packet: ByteArray) {
        val p = writePath[arm] ?: throw IllegalStateException("$arm write characteristic not resolved")
        io { link.write(p, packet) }
    }

    /** RSSI for the link cell every 10th tick, from the RIGHT (command) arm —
     *  on the maintenance coroutine, bound to the session like everything else. */
    override fun onMaintenanceTick() {
        if (++ticks % RSSI_EVERY_TICKS != 0) return
        val p = devicePath[Arm.RIGHT] ?: return
        val r = try { link.rssi(p) } catch (e: Exception) { null }
        updateState { it.copy(rssiDbm = r) }
    }

    companion object {
        const val NAME_PREFIX = "Even G2"
        const val LEFT_INFIX = "_L_"
        const val RIGHT_INFIX = "_R_"

        /** 8 header + 232 chunk + 2 CRC = 242 B per AA packet, + 3 ATT bytes. */
        const val MIN_MTU = 245
        const val SCAN_POLL_MS = 500L
        const val RESOLVE_POLL_MS = 100L
        const val RSSI_EVERY_TICKS = 10
    }
}
