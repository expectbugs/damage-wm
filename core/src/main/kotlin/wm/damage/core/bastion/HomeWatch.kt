package wm.damage.core.bastion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wm.damage.core.util.Log

/**
 * The phone's own network, as the "home unreachable" judge needs it (R6 §4, P10). Implemented by the APK
 * (`NetState`, package D: a validated non-VPN network, its blocked status, when it came up, a VPN present); a
 * stand-in in the tests. Read once per tick; written by the platform's callback thread.
 */
interface NetFacts {
    /** A validated network with internet is up (not a VPN). */
    val validated: Boolean
    /** That network is blocked for this app (Doze without the exemption, data saver): counts as offline. */
    val blocked: Boolean
    /** When the phone last came online (wall-clock ms; 0 = never seen up in this process). */
    val validatedSince: Long
    /** A VPN is up; null = not known. Only the notice's text reads it. */
    val vpnPresent: Boolean?
}

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * "Home unreachable" (build W, R6 §4, P9; the lead's decisions A.5, B.2): the phone says so when the PC at home has
 * sent nothing on the bus watcher's channel for the phone-owned `Home unreachable` row's minutes WHILE the phone
 * itself has internet. It names no cause — an inverter stop, the uplink, the PC off or stalled, the service stopped,
 * either tailnet end — because it cannot tell them apart.
 *
 * The judge, once per [tick] (15 s pacing, [start]):
 * - `down = now − max(lastHeard, validatedSince, startMs)`: the clock runs only while the phone is online and since
 *   this stack started, so coming online after hours offline does not raise at once;
 * - raise ONCE per episode when validated, not blocked, the row not off, and `down ≥ threshold`;
 * - clear when the channel has heard a frame after the raise ("home answers again after N min");
 * - offline: silent; a raised notice stays until home answers. The row turned off withdraws it silently.
 * - quiet hours (normal): a raise inside the window is held in memory and released once if home is still down at
 *   its end; a clear of a raise never said is dropped. A restart forgets the hold (the clock restarts too).
 *
 * Nothing here is a timeout: the tick is pacing, and every clock is read, never waited on.
 */
class HomeWatch(
    private val lastHeard: () -> Long,
    private val net: NetFacts,
    /** The phone-owned row: minutes, 0 = off. Read once per tick. */
    private val thresholdMin: () -> Int,
    private val delivery: AlertDelivery,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val startMs: Long = clock(),
) {
    // the tick's own state: one coroutine (or the test's thread) only
    private var raisedAt = 0L
    private var downFrom = 0L
    private var said = false
    private var heldSince = 0L
    private var vpnOff = false

    /** Raised now (said or held). */
    val raised: Boolean get() = raisedAt != 0L

    /** Held by quiet hours, not said yet. */
    val held: Boolean get() = heldSince != 0L

    /** The tick loop: withdraws a notification a previous stack left, then judges every [periodMs]. */
    fun start(scope: CoroutineScope, periodMs: Long = TICK_MS): Job {
        delivery.homeWithdraw()
        return scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { tick() } catch (e: Exception) { Log.e(TAG, "the home-unreachable judge failed this tick", e) }
                delay(periodMs)                      // pacing, not a timeout
            }
        }
    }

    fun tick() {
        val now = clock()
        val heard = lastHeard()
        val validated = net.validated
        val blocked = net.blocked
        val since = net.validatedSince
        val vpn = net.vpnPresent
        val thr = thresholdMin()

        if (raisedAt != 0L) {
            if (heard > raisedAt) {
                val min = (heard - downFrom) / 60_000
                Log.i(TAG, "home answers again after $min min")
                delivery.homeClear("home answers again after $min min", raiseSaid = said)
                reset()
            } else if (thr <= 0) {
                Log.i(TAG, "the Home unreachable row is off: the raised notice is withdrawn")
                delivery.homeWithdraw()
                reset()
            }
        }

        if (raisedAt == 0L && thr > 0 && validated && !blocked) {
            val from = maxOf(heard, since, startMs)
            val down = now - from
            if (down >= thr * 60_000L) {
                raisedAt = now
                downFrom = from
                vpnOff = vpn == false
                val text = "home has not answered for ${down / 60_000} min (the phone has internet)" + vpnClause()
                Log.w(TAG, text)
                when (delivery.homeRaise(text, release = false)) {
                    AlertDelivery.Outcome.DELIVERED -> said = true
                    AlertDelivery.Outcome.HELD -> heldSince = now
                }
            }
        }

        if (heldSince != 0L && !delivery.quietNow()) {
            heldSince = 0L
            said = true
            val text = "home has not answered since ${delivery.hhmm(downFrom)} (the phone has internet)" + vpnClause()
            Log.i(TAG, "quiet hours over: $text")
            delivery.homeRaise(text, release = true)
        }
    }

    private fun vpnClause() = if (vpnOff) " · the phone's VPN is off" else ""

    private fun reset() {
        raisedAt = 0L
        downFrom = 0L
        said = false
        heldSince = 0L
        vpnOff = false
    }

    companion object {
        private const val TAG = "home-watch"
        const val TICK_MS = 15_000L
    }
}
