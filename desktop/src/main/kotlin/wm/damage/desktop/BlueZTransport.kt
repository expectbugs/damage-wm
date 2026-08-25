package wm.damage.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.util.Log

/**
 * PC-direct BLE (HANDOFF.md §8.2): the same protocol brain as the phone
 * ([CfwTransportBase]) over a [BlueZLink]. Sequence, from the CFW reference
 * and G2CC's driver: discover the pair by advertised name or cached address →
 * connect RIGHT then LEFT → services resolved → service 5450 / chars 5401 +
 * 5402 → MTU checked ≥ 245 → notifications on → the base runs the prelude,
 * capability gate, carrier, lease and warmup. A device's `Connected=false`
 * ends the session (`onLinkDown`); the session keeper starts a new one.
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
    @Volatile private var listening = false

    private fun onEvent(e: BlueZLink.Event) {
        when (e) {
            is BlueZLink.Event.Notification -> synchronized(notifyToArm) { notifyToArm[e.charPath] }
                ?.let { onNotifyPacket(it, e.value) }
            is BlueZLink.Event.Connected -> if (!e.connected) {
                val arm = synchronized(deviceToArm) { deviceToArm[e.devicePath] } ?: return
                if (running) onLinkDown("$arm disconnected (BlueZ Connected=false)")
            }
        }
    }

    override suspend fun connectLink(): Unit = withContext(Dispatchers.IO) {
        val a = link.adapter()
        if (!a.powered) throw IllegalStateException("adapter ${a.name} (${a.address}) is not powered")
        if (!listening) { link.listen(::onEvent); listening = true }
        synchronized(deviceToArm) { deviceToArm.clear() }
        synchronized(notifyToArm) { notifyToArm.clear() }

        val (left, right) = discoverPair()
        Log.i("ble", "connecting R=${right.address} then L=${left.address} via ${a.name}")
        for ((arm, peer) in listOf(Arm.RIGHT to right, Arm.LEFT to left)) {
            link.connect(peer.path)
            link.awaitServicesResolved(peer.path)
            val chars = link.characteristics(peer.path)
            val mtu = link.mtu(chars.notifyPath) ?: link.mtu(chars.writePath)
            if (mtu != null && mtu < MIN_MTU)
                throw IllegalStateException("$arm MTU $mtu < $MIN_MTU: a 242 B AA packet would not fit")
            if (mtu == null) Log.w("ble", "$arm MTU not exposed by this BlueZ — proceeding unchecked")
            else Log.i("ble", "$arm MTU $mtu")
            link.startNotify(chars.notifyPath)
            synchronized(notifyToArm) { notifyToArm[chars.notifyPath] = arm }
            synchronized(deviceToArm) { deviceToArm[peer.path] = arm }
            devicePath[arm] = peer.path
            writePath[arm] = chars.writePath
        }
        rememberAddresses(left.address, right.address)
        Log.i("ble", "both arms connected")
        scope.launch {
            while (running) {
                delay(RSSI_POLL_MS)
                if (!running) break
                val r = devicePath[Arm.RIGHT]?.let { p -> withContext(Dispatchers.IO) { link.rssi(p) } }
                updateState { it.copy(rssiDbm = r) }
            }
        }
        Unit
    }

    /** Scan until both arms are seen — by name ("Even G2_.._L_.." / "_R_") or
     *  by a remembered address. No deadline; the keeper's stop cancels it. */
    private suspend fun discoverPair(): Pair<BlueZLink.Peer, BlueZLink.Peer> {
        val (cachedL, cachedR) = cachedAddresses()
        fun isLeft(p: BlueZLink.Peer) = (p.name.startsWith(NAME_PREFIX) && LEFT_INFIX in p.name) || p.address.equals(cachedL, true)
        fun isRight(p: BlueZLink.Peer) = (p.name.startsWith(NAME_PREFIX) && RIGHT_INFIX in p.name) || p.address.equals(cachedR, true)
        link.startDiscovery()
        Log.i("ble", "scanning for the G2 pair")
        try {
            while (true) {
                val peers = link.peers()
                val l = peers.firstOrNull { isLeft(it) }
                val r = peers.firstOrNull { isRight(it) }
                if (l != null && r != null) {
                    Log.i("ble", "found L '${l.name}' ${l.address}, R '${r.name}' ${r.address}")
                    return l to r
                }
                delay(SCAN_POLL_MS)
            }
        } finally {
            link.stopDiscovery()
        }
    }

    override suspend fun disconnectLink(): Unit = withContext(Dispatchers.IO) {
        for ((arm, p) in devicePath) {
            synchronized(deviceToArm) { deviceToArm.remove(p) }
            try { link.disconnect(p) } catch (e: Exception) { Log.w("ble", "$arm disconnect: ${e.message}") }
        }
        devicePath.clear()
        writePath.clear()
        synchronized(notifyToArm) { notifyToArm.clear() }
    }

    override suspend fun writeArm(arm: Arm, packet: ByteArray) {
        val p = writePath[arm] ?: throw IllegalStateException("$arm write characteristic not resolved")
        withContext(Dispatchers.IO) { link.write(p, packet) }
    }

    companion object {
        const val NAME_PREFIX = "Even G2"
        const val LEFT_INFIX = "_L_"
        const val RIGHT_INFIX = "_R_"

        /** 8 header + 232 chunk + 2 CRC = 242 B per AA packet, + 3 ATT bytes. */
        const val MIN_MTU = 245
        const val SCAN_POLL_MS = 500L
        const val RSSI_POLL_MS = 10_000L
    }
}
