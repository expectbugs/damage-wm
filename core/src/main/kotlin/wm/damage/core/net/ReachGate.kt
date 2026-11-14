package wm.damage.core.net

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import wm.damage.core.util.Log

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on
 * his own network. This is the gate every one of Damage's listeners puts on a connection it has
 * just accepted (build W; the lead's decision B.4; `REPLAN.md` §15 A1; `CONTRACT.md` §10.9): the
 * connection is answered only when it ARRIVED on loopback, the tailnet or — on the PC — the bus
 * network. [Reach.classify] decides; this reads the facts it needs.
 *
 * - The arriving address is `sock.localAddress`, read once, right after `accept()` returns and
 *   before a byte is read (the setup page's pattern, `desktop/…/SetupServer.kt`). A gate on the
 *   accepted socket, not a bind: an interface that comes up after the start still works.
 * - On the PC, whether that address's interface is point-to-point (a tunnel) is read from
 *   [NetworkInterface] per connection; an interface that cannot be read is a refusal, said.
 * - A refusal is closed by the CALLER before it reads anything ([admit] answers false), and said
 *   through [sink], paced: one line per remote address per minute, with the count held back since
 *   (` (+N more since)`). The pacing map is pruned past [PRUNE_PAST] entries; a pruned entry with a
 *   count still held back is said as it goes, so no refusal goes unsaid.
 * - [open] (the PC's `reachOpen` config key, default false) admits everything: the way back if the
 *   gate misjudges his network. The builder says so at start.
 *
 * The default everywhere is the STRICT phone side: a listener whose builder forgot to pass the PC's
 * gate refuses the bus network loudly; it never opens. The shared token still guards every
 * listener behind this gate; nothing about it changes here. The two known gaps of judging by
 * address and interface are recorded at [Reach].
 *
 * NO TIMEOUTS: nothing here waits; the interface read is one call.
 */
class ReachGate(
    private val side: Side,
    private val open: Boolean = false,
    private val sink: (String) -> Unit = { Log.w(TAG, it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Whether [InetAddress]'s interface is point-to-point; null when it cannot be read. */
    private val pointToPoint: (InetAddress) -> Boolean? = ::pointToPointOf,
    /** The decision; [Reach.classify] — injected only by the gate's own tests. */
    private val classify: (InetAddress, Boolean?, Side) -> Reach.Verdict = { a, p, s -> Reach.classify(a, p, s) },
) {
    private class Said(val at: Long, var held: Int)

    /** Remote address → when its last line was said, and the refusals held back since. */
    private val said = ConcurrentHashMap<String, Said>()

    /**
     * True: serve [sock]. False: the caller closes it at once, unread. Reads only the socket's two
     * addresses; never throws (a failure to judge is a refusal, said).
     */
    fun admit(sock: Socket, listener: String): Boolean {
        if (open) return true
        val local: InetAddress? = sock.localAddress
        val remote = sock.inetAddress?.hostAddress ?: "an unknown peer"
        if (local == null) {
            refused(listener, remote, "an unknown address", "the arriving address could not be read")
            return false
        }
        val verdict = try {
            // the PC alone admits the bus network, so only the PC needs the interface's kind
            classify(local, if (side == Side.PC) pointToPoint(local) else null, side)
        } catch (e: Exception) {
            Reach.Verdict(false, "error", "the arrival could not be judged (${e::class.simpleName})")
        }
        if (verdict.allowed) return true
        refused(listener, remote, local.hostAddress ?: local.toString(), verdict.why)
        return false
    }

    private fun refused(listener: String, remote: String, local: String, why: String) {
        val now = clock()
        var line: String? = null
        said.compute(remote) { _, s ->
            if (s == null || now - s.at >= PACE_MS) {
                line = "reach: $listener refused $remote arriving on $local ($why)" +
                    if (s != null && s.held > 0) " (+${s.held} more since)" else ""
                Said(now, 0)
            } else {
                s.held += 1
                s
            }
        }
        line?.let { say(it) }
        if (said.size > PRUNE_PAST) prune(now)
    }

    private fun prune(now: Long) {
        for ((remote, s) in said.entries.toList()) {
            if (now - s.at < PACE_MS) continue
            if (!said.remove(remote, s)) continue
            if (s.held > 0) say("reach: $remote refused ${s.held} more time(s) in the minute after ${hhmm(s.at)}")
        }
    }

    private fun say(line: String) {
        try {
            sink(line)
        } catch (e: Exception) {
            Log.e(TAG, "the reach line could not be written to its sink: $line", e)
        }
    }

    companion object {
        private const val TAG = "reach"
        /** One refusal line per remote address per this long. */
        const val PACE_MS = 60_000L
        /** The pacing map is pruned of entries older than [PACE_MS] once it holds more than this. */
        const val PRUNE_PAST = 256

        private val HHMM = DateTimeFormatter.ofPattern("HH:mm:ss")
        private fun hhmm(ms: Long): String = HHMM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

        /** Whether [local]'s interface is point-to-point (a tunnel); null when no interface holds it or the
         *  read fails. */
        fun pointToPointOf(local: InetAddress): Boolean? = try {
            NetworkInterface.getByInetAddress(local)?.isPointToPoint
        } catch (e: Exception) {
            null
        }
    }
}
