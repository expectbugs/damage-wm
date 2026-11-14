package wm.damage.desktop

import wm.damage.core.transport.SeamProbe

/**
 * The PC's `auto` mode, one probe at a time (`HANDOFF.md` §19.2, §47, §72; build W's rider, R6 §5 and the lead's
 * decision A.9): pure, so a table of cases pins it (`StandbyPolicyTest`).
 *
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Since build W the PC never takes the glasses on its own: **PC-direct Bluetooth is a manual choice** (`REPLAN.md`
 * §14: "never automatic") — `--transport ble`, the replica probe `drive=ble`, or the PC shell's `Target` row. In
 * `auto` the PC is the data host: it probes the phone's seam on pacing for the status line and for "the phone is
 * back", and nothing else. [Action] has no member that starts a stack, so no probe answer can.
 *
 * History: on 2026-11-06 the automatic claim took the glasses from a phone set to Target=Sim, on an adapter that
 * could not scan, and retried a start that could not succeed until the heap ran out (§72); the claim, its scan
 * (`StandbyScan`), its debounce and its give-back went with this change.
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

    /** What the loop keeps between probes. */
    data class State(val absentStreak: Int = 0, val choseNotToSaid: Boolean = false)

    /** What one probe asks of the host. [status] is the status line; [said] goes to the log and the journal once. */
    sealed class Action {
        abstract val status: String
        abstract val said: List<String>

        /** Say how things are. */
        data class Narrate(override val status: String, override val said: List<String> = emptyList()) : Action()

        /** A PC stack is up while the mode is `auto` (a manual stack whose stop was dropped by a switch already in
         *  progress, `swapStack`): stop it. `auto` never drives. [stopping] is said when the host does. */
        data class Handback(override val status: String, override val said: List<String> = emptyList(),
            val stopping: String = STOPPING) : Action()
    }

    class Step(val state: State, val action: Action)

    /** The line said when a [Action.Handback] is acted on. */
    const val STOPPING = "standby: a PC stack is up in auto (its stop was dropped by a switch in progress) — stopping it; auto never drives"

    /** The status line while the content port is held by another process (no probe is made). */
    const val PORT_BUSY = "data host · content port busy — another instance?"

    /**
     * One probe. [standby] = the mode is `auto`; false = a manual stack (`--transport sim|ble|remote`, `drive=ble`, the
     * `Target` row), which no probe touches — the loop then passes no answer ([phone] null) and none is made.
     * [stackHeld] = a PC stack is up. [detail] = the phone's own status line. [probeMs] prices the absence.
     */
    fun step(phone: Phone?, s: State, standby: Boolean, stackHeld: Boolean, detail: String = "", probeMs: Long): Step {
        if (!standby) return Step(State(), Action.Narrate("manual: the PC stack runs until drive=auto"))
        if (phone == null) return Step(s, Action.Narrate("data host · probing the phone"))
        val said = ArrayList<String>()
        val (next, status) = when (phone) {
            Phone.DRIVES -> {
                if (s.absentStreak > 0)
                    said += "standby: the phone is back after ${s.absentStreak} missed probe(s) (~${s.absentStreak * probeMs / 1000} s)"
                State() to ("data host · phone drives" + (detail.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))
            }
            Phone.CHOSE_NOT_TO -> {
                // §72, Adam's ruling D1 (2026-11-07): a reachable APK on Target≠glasses chose to leave the glasses alone
                if (!s.choseNotToSaid) said += "standby: the APK is reachable and says Target≠glasses — the glasses are left alone"
                State(choseNotToSaid = true) to "data host · the APK chose Target≠glasses"
            }
            Phone.UNREACHABLE -> {
                val streak = s.absentStreak + 1
                State(absentStreak = streak) to
                    "data host · phone unreachable ${span(streak * probeMs)} · PC-direct BLE is manual (drive=ble)"
            }
        }
        if (stackHeld) return Step(next, Action.Handback("$status · stopping the PC stack (auto never drives)", said))
        return Step(next, Action.Narrate(status, said))
    }

    /** The replica probe `drive=ble|auto` (build W): the mode it asks for, or null for any other value. */
    fun driveOf(value: String): String? = value.takeIf { it == "ble" || it == "auto" }

    /** An absence's length for the status line: seconds under a minute, then minutes, then hours and minutes. */
    fun span(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            else -> "${s / 3600}h${(s % 3600) / 60}m"
        }
    }
}
