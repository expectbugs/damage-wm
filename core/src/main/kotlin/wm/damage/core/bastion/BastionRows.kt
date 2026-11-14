package wm.damage.core.bastion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import wm.damage.core.shell.HostSetting
import wm.damage.core.shell.Shell
import wm.damage.core.util.Log

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * The Settings category "Bastion" (build W, R6 P11 option A; the lead's decision B.3; the spec's §2(d)): the bus
 * watcher has rows before it has a window, so the phone supplies them as a HOST category (`Shell.hostCategories`),
 * and a Bastion window built later returns the same rows (the window wins by name; nothing migrates).
 *
 * Rows, in order: `Watcher` — display-only, the PC watcher's state line ([watcherRow]); `Notify · alerts` and
 * `Notify · cleared` — SHARED ([Shell.sharedRow], in `ShellSettings`, synced); then the phone's own rows
 * ([category]'s `phoneRows`: `Phone · alerts`, `Home unreachable`, kept in the phone's preferences, never synced);
 * then `Acknowledge`.
 */
object BastionRows {

    const val CATEGORY = AlertDelivery.CATEGORY
    /** The Acknowledge row's resting value: there is nothing to keep, it is an action. */
    const val NONE = "—"
    const val WATCHER = "Watcher"

    /**
     * The category, built ONCE: Settings matches a staged row by identity, so the host keeps this pair and sets
     * `Shell.hostCategories` to it. [scope] runs the acknowledgements (a blocking channel request, never on the loop).
     */
    fun category(shell: Shell, phoneRows: List<HostSetting>, rb: RemoteBastion, scope: CoroutineScope): Pair<String, List<HostSetting>> {
        val rows = listOf(
            watcherRow(rb),
            shell.sharedRow("Notify · alerts", ON_OFF, { onOff(it.notifyAlerts) }, { s, v -> s.copy(notifyAlerts = v == "on") }),
            shell.sharedRow("Notify · cleared", ON_OFF, { onOff(it.notifyCleared) }, { s, v -> s.copy(notifyCleared = v == "on") }),
        ) + phoneRows + acknowledgeRow(shell, rb, scope)
        return CATEGORY to rows
    }

    /**
     * Display-only (fix round 1, R1.2(b)): the PC watcher's state line ([RemoteBastion.watcherLine]: "home not
     * answering" while the channel is down), so the watcher's state — an entity that cannot be read, the hub not
     * answering, the watcher off and why — is visible on the glasses. At rest the whole line (fitted to the value
     * cell like every host row's answer); while adjusting, scroll walks it part by part (its " · " parts) so each
     * part can be read whole. A tap keeps nothing: there is no value to set.
     */
    fun watcherRow(rb: RemoteBastion): HostSetting =
        HostSetting(WATCHER,
            { val line = rb.watcherLine(); val parts = line.split(" · "); if (parts.size > 1) listOf(line) + parts else parts },
            { rb.watcherLine() },
            { /* display-only: a tap on any part keeps nothing (nothing is set, nothing to report) */ })

    /**
     * A row that is really an action — listed for step S1-5 with the other such rows (dec. B.3: acknowledge is an
     * action on the alert; until the glasses' notices carry actions, this row presents the same `ack {id, episode}`
     * the phone's notification button calls). Options `— · all raised (N)` from [RemoteBastion.raised] at adjust
     * time; it always rests at `—`. A tap on `all raised (N)` hands the alerts raised at that moment to a background
     * coroutine that acknowledges each; the answer is a notice to Adam's own gesture. Out of reach it says
     * "not acknowledged — home not answering": nothing is queued and nothing is retried.
     */
    fun acknowledgeRow(shell: Shell, rb: RemoteBastion, scope: CoroutineScope): HostSetting =
        HostSetting("Acknowledge",
            { val n = rb.raised().size; if (n == 0) listOf(NONE) else listOf(NONE, "all raised ($n)") },
            { NONE },
            { v ->
                if (v != NONE) {
                    val targets = rb.raised()               // the alerts raised at the tap, read on the loop
                    scope.launch(Dispatchers.IO) {
                        val text = acknowledgeAll(rb, targets)
                        Log.i(TAG, "Acknowledge row: $text")
                        shell.services.notifyInternal(AlertDelivery.SOURCE, text, answer = true)
                    }
                }
            })

    /** Acknowledges each of [targets] in turn (blocking) and says what happened, in plain words. */
    internal fun acknowledgeAll(rb: RemoteBastion, targets: List<AlertRecord>): String {
        var ok = 0
        var ended = 0
        for (r in targets) {
            val a = try { rb.ack(r.id, r.episode) } catch (e: Exception) {
                Log.w(TAG, "acknowledging ${r.id} episode ${r.episode} did not reach home: ${e.message}")
                return if (ok == 0 && ended == 0) "not acknowledged — home not answering"
                else "acknowledged $ok, the rest not — home not answering"
            }
            if (a.ok) ok++ else ended++
        }
        return when {
            targets.isEmpty() -> "nothing to acknowledge"
            ended == 0 -> "acknowledged $ok"
            else -> "acknowledged $ok · $ended had already ended"
        }
    }

    private val ON_OFF = listOf("on", "off")
    private fun onOff(b: Boolean) = if (b) "on" else "off"
    private const val TAG = "bastion"
}
