package wm.damage.core.bastion

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import wm.damage.core.shell.QuietHours
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.util.Log

/**
 * The phone's own notification shade, as the bus watcher uses it (build W; the lead's decision A.4: the channels
 * "Bus alerts", "Bus alerts · urgent" and "Home unreachable" sit beside "Glasses battery", none behind Global
 * `Phone notifications`). Implemented by the APK (package D); a stand-in in the tests. Every call may come from
 * any thread. One notification per alert id; one for "home unreachable".
 */
interface PhoneAlerts {
    /** Post or update the notification of [rec]. [silent] = update the shade without a sound or a heads-up (an
     *  adopted, acknowledged or re-posted alert). An acknowledged record carries no Acknowledge button. */
    fun show(rec: AlertRecord, kind: AlertDelivery.Kind, body: String, silent: Boolean)

    /** Withdraw the notification of alert [id], if one is shown. */
    fun cancel(id: String)

    /** The alert ids whose notifications are in the shade now (ours only). */
    fun shownIds(): Set<String>

    fun homeShow(text: String, silent: Boolean)
    fun homeCancel()
}

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build W adds a reader on his PC that only reads his own home hub and raises alerts; this is the phone's ONE place
 * that decides, for each alert change, whether the glasses get a notice, whether the phone's shade gets one, and
 * whether quiet hours hold it (R6 §3, P8; the lead's decisions B.1–B.3; the spec's §2(a)).
 *
 * - The glasses: `Notify · alerts` (a raise, a repeat, a release) and `Notify · cleared` — shared rows of the host
 *   category "Bastion" in [ShellSettings] — through [glasses] (`Shell.notifyFromHost(…, gatedBy = "Bastion")`).
 * - The phone's shade: the phone-owned `Phone · alerts` row ([phoneOn], read once per decision) through [phone].
 *   Never `Shell.onUrgent`, never Global `Phone notifications`.
 * - Quiet hours (dec. B.2): Global's one shared row. Inside the window a NORMAL raise or repeat is HELD (nothing
 *   delivered; the caller keeps the mark and asks for a [Kind.RELEASE] at the end, if the alert is still raised)
 *   and a NORMAL clear is dropped (the shade's notification is still withdrawn: it shows state). URGENT passes.
 *   The clock is the device's wall clock read at each decision — pacing on a clock, never a deadline.
 *
 * A body is never cut: the notice box scrolls, and `Draw.dynamic` shows a glyph the face lacks as `?`.
 */
class AlertDelivery(
    private val glasses: (source: String, body: String, thread: String, gatedBy: String) -> Unit,
    private val settings: () -> ShellSettings,
    private val phone: PhoneAlerts?,
    private val phoneOn: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** The phone's form (the spec's C3): the glasses through [shell]'s host entry, the rows from its settings. */
    constructor(shell: Shell, phone: PhoneAlerts?, phoneOn: () -> Boolean) : this(
        { s, b, t, g -> shell.notifyFromHost(s, b, t, g) }, { shell.settings }, phone, phoneOn)

    /** What happened to an alert, as delivery sees it. [ADOPT] = an alert met at first contact or carried over to a
     *  new epoch (the shade only, silently); [ACKED] = acknowledged (the shade only, silently). */
    enum class Kind { RAISED, REPEAT, RELEASE, CLEARED, ADOPT, ACKED }

    enum class Outcome { DELIVERED, HELD }

    data class Rows(val notifyAlerts: Boolean, val notifyCleared: Boolean, val phoneAlerts: Boolean)

    /** [hold] = quiet hours keep it (nothing delivered); [glasses] / [phone] = the paths that deliver it. For
     *  [Kind.CLEARED], [phone] is false: the shade's notification is withdrawn whatever the rows say. */
    data class Verdict(val hold: Boolean, val glasses: Boolean, val phone: Boolean)

    fun rows(): Rows = settings().let { Rows(it.notifyAlerts, it.notifyCleared, phoneOn()) }

    /** Inside Global `Quiet hours` now, on the device's wall clock (one read). */
    fun quietNow(): Boolean = QuietHours.inside(settings().quietHours, localTime())

    private fun localTime(): LocalTime = LocalTime.ofInstant(Instant.ofEpochMilli(clock()), zone())

    /** HH:MM of [ms] in the device's zone now (24-hour). */
    fun hhmm(ms: Long): String = HHMM.format(Instant.ofEpochMilli(ms).atZone(zone()))

    /** A raise, a repeat, or the release of a held raise at the end of quiet hours. */
    fun alert(rec: AlertRecord, kind: Kind): Outcome {
        require(kind == Kind.RAISED || kind == Kind.REPEAT || kind == Kind.RELEASE) { "alert(): $kind" }
        val v = decide(kind, rec.severity, rows(), quietNow())
        if (v.hold) return Outcome.HELD
        val body = body(rec, kind)
        if (v.glasses) glassesSay(body, rec.id)
        if (v.phone) phoneShow(rec, kind, body, silent = false)
        return Outcome.DELIVERED
    }

    /**
     * An alert met at first contact or carried over to a new epoch (the spec's §2(b)): no glasses notice
     * (`CONTRACT.md` §7.3); the shade gets it silently. Inside quiet hours a NORMAL raised record that has no
     * notification in the shade ([shown]) is HELD instead of posted (§2(a), "first contact inside").
     */
    fun adopt(rec: AlertRecord, shown: Set<String>): Outcome {
        if (rec.state == AlertState.RAISED && rec.id !in shown &&
            decide(Kind.ADOPT, rec.severity, rows(), quietNow()).hold) return Outcome.HELD
        if (phoneOn()) phoneShow(rec, Kind.ADOPT, body(rec, Kind.ADOPT), silent = true)
        return Outcome.DELIVERED
    }

    /** Acknowledged: the shade's notification is updated silently, without its Acknowledge button. */
    fun acked(rec: AlertRecord) {
        if (phoneOn()) phoneShow(rec, Kind.ACKED, body(rec, Kind.ACKED), silent = true)
    }

    /** Cleared at [at]: the shade's notification is withdrawn; the glasses say "cleared" when `Notify · cleared` is
     *  on, the raise was said ([raiseSaid]) and quiet hours do not drop it. */
    fun cleared(rec: AlertRecord, at: Long, raiseSaid: Boolean) {
        cancel(rec.id)
        val v = decide(Kind.CLEARED, rec.severity, rows(), quietNow())
        if (v.glasses && raiseSaid) glassesSay(clearedBody(rec, at), rec.id)
    }

    /** Withdraw alert [id]'s notification from the shade, silently (it has no record any more). */
    fun cancel(id: String) {
        val p = phone ?: return
        try { p.cancel(id) } catch (e: Exception) { Log.e(TAG, "the phone's notification of $id was not withdrawn", e) }
    }

    /**
     * The `Phone · alerts` row was changed to [on] (fix round 1): the shade is reconciled at once. Off: every bus
     * alert's notification is withdrawn, and "home unreachable"'s too (the row gates both). On: every alert RAISED
     * now ([raised], the copy's) is posted silently — each was said or held already, so turning the row on says
     * nothing again; "home unreachable" is posted again at its next raise.
     */
    fun phoneRowChanged(on: Boolean, raised: List<AlertRecord>) {
        if (!on) {
            for (id in shownIds()) cancel(id)
            homeWithdraw()
            return
        }
        for (r in raised) phoneShow(r, Kind.ADOPT, body(r, Kind.ADOPT), silent = true)
    }

    /** The ids whose notifications are in the shade; empty with no shade, said when it cannot be read. */
    fun shownIds(): Set<String> {
        val p = phone ?: return emptySet()
        return try { p.shownIds() } catch (e: Exception) {
            Log.e(TAG, "the phone's shade could not be read; reconciled as if it were empty", e)
            emptySet()
        }
    }

    // ---- "home unreachable" (R6 §4; the same rows and quiet-hours rule, severity normal) -------------------------

    /** "Home unreachable" raised ([release] = false) or released at the end of quiet hours ([release] = true). */
    fun homeRaise(text: String, release: Boolean): Outcome {
        val v = decide(if (release) Kind.RELEASE else Kind.RAISED, Severity.NORMAL, rows(), quietNow())
        if (v.hold) return Outcome.HELD
        if (v.glasses) glassesSay(text, HOME_THREAD)
        if (v.phone) phone?.let { p ->
            try { p.homeShow(text, silent = false) } catch (e: Exception) {
                Log.e(TAG, "the phone's \"home unreachable\" notification was not posted (the glasses still say it)", e)
            }
        }
        return Outcome.DELIVERED
    }

    /** Home answers again: the shade's notification is withdrawn; the glasses say [text] when the raise was said,
     *  `Notify · cleared` is on and quiet hours do not drop it. */
    fun homeClear(text: String, raiseSaid: Boolean) {
        homeWithdraw()
        val v = decide(Kind.CLEARED, Severity.NORMAL, rows(), quietNow())
        if (v.glasses && raiseSaid) glassesSay(text, HOME_THREAD)
    }

    /** Withdraw the "home unreachable" notification silently (a stack start, the row turned off). */
    fun homeWithdraw() {
        val p = phone ?: return
        try { p.homeCancel() } catch (e: Exception) { Log.e(TAG, "the phone's \"home unreachable\" notification was not withdrawn", e) }
    }

    // ---- bodies ---------------------------------------------------------------------------------------------------

    /** `[acknowledged · ][urgent · ]<text>[ · <value>] · HH:MM` — `since HH:MM` on a release; HH:MM = the raise. */
    fun body(rec: AlertRecord, kind: Kind): String {
        val parts = ArrayList<String>()
        if (rec.acknowledged) parts += "acknowledged"
        if (rec.severity == Severity.URGENT) parts += "urgent"
        parts += rec.text
        if (rec.value.isNotEmpty()) parts += rec.value
        parts += if (kind == Kind.RELEASE) "since ${hhmm(rec.raisedAt)}" else hhmm(rec.raisedAt)
        return parts.joinToString(" · ")
    }

    fun clearedBody(rec: AlertRecord, at: Long): String = "cleared · ${rec.text} · ${hhmm(at)}"

    private fun glassesSay(body: String, thread: String) {
        try { glasses(SOURCE, body, thread, CATEGORY) } catch (e: Exception) {
            Log.e(TAG, "the glasses notice for $thread was not posted: $body", e)
        }
    }

    private fun phoneShow(rec: AlertRecord, kind: Kind, body: String, silent: Boolean) {
        val p = phone ?: return
        try { p.show(rec, kind, body, silent) } catch (e: Exception) {
            Log.e(TAG, "the phone's notification of ${rec.id} (${kind.name.lowercase()}) was not posted: $body", e)
        }
    }

    companion object {
        private const val TAG = "bastion"
        /** The notices' source (`DAMAGE · bastion`) and the host category that gates them (`Notice.gatedBy`). */
        const val SOURCE = "bastion"
        const val CATEGORY = "Bastion"
        /** The coalescing thread of the "home unreachable" notice: in the watcher's reserved prefix, so no rule id
         *  can take it. */
        const val HOME_THREAD = AlertRule.BUILT_IN_PREFIX + "home"
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * The one decision, pure. Quiet hours hold a NORMAL raise, repeat or adoption and drop a NORMAL clear; a
         * release is never held (it runs only outside the window); URGENT always passes. The glasses follow
         * `Notify · alerts` (`Notify · cleared` for a clear); the shade follows `Phone · alerts` and nothing else.
         */
        fun decide(kind: Kind, severity: Severity, rows: Rows, quiet: Boolean): Verdict {
            val held = quiet && severity == Severity.NORMAL
            return when (kind) {
                Kind.RAISED, Kind.REPEAT ->
                    if (held) Verdict(true, false, false) else Verdict(false, rows.notifyAlerts, rows.phoneAlerts)
                Kind.RELEASE -> Verdict(false, rows.notifyAlerts, rows.phoneAlerts)
                Kind.ADOPT -> if (held) Verdict(true, false, false) else Verdict(false, false, rows.phoneAlerts)
                Kind.ACKED -> Verdict(false, false, rows.phoneAlerts)
                Kind.CLEARED -> Verdict(false, !held && rows.notifyCleared, false)
            }
        }
    }
}
