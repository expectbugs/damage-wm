package wm.damage.desktop

import wm.damage.core.transport.SeamProbe
import wm.damage.core.util.Log

/**
 * The PC standby's two decisions (`HANDOFF.md` §19.2, §47, §72), pure so a table of cases pins
 * them (`StandbyPolicyTest`).
 *
 * 2026-11-06: Adam set the APK's Target to Sim to read on the phone without the glasses. The
 * standby read "the APK says Target≠glasses" as its cue and claimed. beardos's adapter is
 * powered off, so its scan could not look, and the scan's first failure had turned the
 * arms-advertising check off for the life of the process. It then retried a start that could
 * not succeed every 2–4 s until the heap ran out (HANDOFF §72).
 */
internal object StandbyPolicy {

    /** What the phone's seam answered on one probe. */
    enum class Phone {
        /** Reachable and wants the radio (Target=glasses), or an APK too old to be asked. */
        DRIVES,
        /** Reachable and does NOT want the radio: Target is not glasses. A choice. */
        CHOSE_NOT_TO,
        /** No answer: the phone, its app or the path to it is gone. */
        UNREACHABLE,
    }

    fun phoneOf(r: SeamProbe.Result): Phone = when (r) {
        // null = an APK too old to ask: the conservative reading, as before
        is SeamProbe.Result.Reachable -> if (r.wantsRadio != false) Phone.DRIVES else Phone.CHOSE_NOT_TO
        else -> Phone.UNREACHABLE
    }

    /** Adam's ruling D1 (2026-11-07): claim ONLY when the phone has been UNREACHABLE for
     *  [debounce] probes in a row AND this adapter SEES both arms advertising (a phone that holds
     *  them keeps them connected, and a connected arm does not advertise). A reachable phone on
     *  Target≠glasses chose to leave the glasses alone, and an adapter that cannot scan
     *  ([armsAdvertising] null) sees nothing: neither one ever claims. */
    fun shouldClaim(phone: Phone, absentStreak: Int, debounce: Int, armsAdvertising: Boolean?): Boolean =
        phone == Phone.UNREACHABLE && absentStreak >= debounce && armsAdvertising == true

    /** A standby claim whose starts have failed [limit] times in a row goes back to standby,
     *  which claims again only on sight. So a claim never retries, for the life of the process, a
     *  start that cannot succeed (fix 2). Counted in attempts (the keeper's own pacing), never
     *  in time. */
    fun shouldGiveBack(failedStartsInARow: Int, limit: Int): Boolean = failedStartsInARow >= limit

    /** The probes a claim waits for after [giveBacksInARow] give-backs: [base], doubled per
     *  give-back, never past [cap]. Arms that advertise while every start fails (glasses at the
     *  edge of range, a refusal) would otherwise cycle claim → six failures → give-back about
     *  once a minute. Pacing in probes, not a timeout. The count resets when the phone is back
     *  or a claim reaches a running session (the second review's finding). */
    fun claimDebounce(giveBacksInARow: Int, base: Int, cap: Int): Int =
        minOf(base.toLong() shl minOf(giveBacksInARow, 16), cap.toLong()).toInt()
}

/**
 * Whether both arms advertise to THIS adapter, which means the phone has let go of them (a
 * connected peripheral does not advertise; a phone that is merely unreachable over the network
 * keeps its links). Discovery runs only while the phone is absent, and stops the moment it is
 * back or the PC claims (the transport runs its own scan).
 *
 * Null when this probe cannot scan: the adapter is powered off (beardos's hci0 is rfkill-
 * blocked), BlueZ is unreachable, or it refused. **Null never claims ([StandbyPolicy.shouldClaim]),
 * and it is not sticky (§72, 2026-11-08).** The first failure used to set a flag for the life of
 * the process, after which claims went by the debounce alone. Now the cause is said once, every
 * probe looks again, and an adapter switched back on scans again without a restart. A link that
 * failed is dropped and a fresh one made on the next probe (bluetoothd may have restarted). A
 * powered-off adapter is read from its `Powered` property, not by failing a discovery start.
 */
internal class StandbyScan(
    private val cfg: Config,
    private val newLink: () -> BlueZLink = { BlueZDbus() },
) {
    private var link: BlueZLink? = null
    private var scanning = false
    private var cannotScanSaid: String? = null

    fun armsAdvertising(): Boolean? {
        val l = link ?: try {
            newLink().also { link = it }
        } catch (e: Exception) {
            return cannotScan("BlueZ is not reachable (${e.message})")
        }
        try {
            val a = l.adapter()
            if (!a.powered) {
                scanning = false
                return cannotScan("the adapter ${a.name} is powered off")
            }
            if (!scanning) {
                l.startDiscovery()
                scanning = true
                if (cannotScanSaid != null) {
                    Log.i("damage", "standby: the adapter scans again (was: $cannotScanSaid)")
                    cannotScanSaid = null
                }
                Log.i("damage", "standby: scanning for the arms (BlueZ discovery on) — a claim needs both to advertise")
                return false            // give the scan a probe interval before judging
            }
            if (!l.discovering()) {
                scanning = false        // ended on its own (adapter off, bluetoothd restarted): restart next probe
                Log.w("damage", "standby: the discovery ended on its own — restarting it on the next probe")
                return false
            }
            val peers = l.peers()
            fun seen(p: BlueZLink.Peer) = p.rssi != null && !p.connected
            fun isLeft(p: BlueZLink.Peer) = seen(p) && (p.address.equals(cfg.leftAddress, true) && cfg.leftAddress.isNotEmpty() ||
                p.name.startsWith(BlueZTransport.NAME_PREFIX) && BlueZTransport.LEFT_INFIX in p.name)
            fun isRight(p: BlueZLink.Peer) = seen(p) && (p.address.equals(cfg.rightAddress, true) && cfg.rightAddress.isNotEmpty() ||
                p.name.startsWith(BlueZTransport.NAME_PREFIX) && BlueZTransport.RIGHT_INFIX in p.name)
            return peers.any(::isLeft) && peers.any(::isRight)
        } catch (e: Exception) {
            scanning = false
            try { l.close() } catch (c: Exception) { Log.w("damage", "standby: closing the failed BlueZ link: ${c.message}") }
            link = null
            return cannotScan("BlueZ refused (${e.message})")
        }
    }

    /** The probe cannot scan: say why once per cause, and answer null, which never claims. */
    private fun cannotScan(why: String): Boolean? {
        if (cannotScanSaid != why) {
            cannotScanSaid = why
            Log.e("damage", "standby: cannot scan for the arms — $why; no claim until this adapter can see them (§72)")
        }
        return null
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        try { link?.stopDiscovery() } catch (e: Exception) { Log.w("damage", "standby: stopDiscovery: ${e.message}") }
    }
}
