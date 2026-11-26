package wm.damage.core.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import wm.damage.core.bastion.AlertDelivery
import wm.damage.core.shell.BatteryLatch
import wm.damage.core.shell.HostTag
import wm.damage.core.shell.ShellState
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * R-b stage H2 (`private/build085/RB-lean-spec.md` §4.4, §4.5, §4.8): the phone's half of the health view. At its pace
 * (the app lane's ping's), and whenever the lane brings a `welcome`, the PC's `desc/health` or a change of reach, it
 * reads the phone's four lines — the PC's reach as the lane's paced ping judges it (with the waiting changes, the
 * contract match and this APK's build), the glasses' link word, their battery and the ladder's stage — from volatile
 * fields only (no disk, never the shell's loop), and
 *  - sends them to the PC (`health`) when one changed and on every new connection (a `welcome`): at a new connection
 *    the PC has forgotten the phone's lines (its restart: "not reported") or marked them `lastKnown` (the old
 *    connection's end) while they have not changed here, so a send owed to the connection that is live now is made at
 *    every pass until one has succeeded — without it four lines would read "last known" under "All well" after every
 *    restart, until one of them changed;
 *  - composes them with the PC's description as last heard (its lines `lastKnown` while the PC is out of reach) into
 *    what the surfaces show: the link cell's tag — the first line in alarm with a tag, in the order PC, hub, disk,
 *    copy — and the phone app's sentence, the worst line's, empty while all is well.
 * Nothing is saved and nothing latches: every pass derives every line from its source again; the only kept state is
 * each line's `since` and what was last sent on which connection, both replaced by the next pass that differs.
 */
class PhoneHealth(
    private val wc: WorkingCopy,
    /** One snapshot of the glasses' side per pass (§4.8 (e)), read from volatile fields. */
    private val read: () -> Glasses,
    /** This APK's build, "0.85 (85)". */
    private val apk: String,
    private val scope: CoroutineScope,
    /** What the surfaces show changed (any thread; the phone writes the tag into its shell). */
    private val onView: (View) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** The pace of [start]'s passes; 0 = none (a test calls [step]). */
    private val everyMs: Long = 15_000,
    /** The lines to the PC on the live connection; false when there is none (the next `welcome` sends them). */
    private val send: (List<HealthLine>) -> Boolean = { lines -> wc.client?.health(lines) == true },
    /** The live connection, by identity (its `welcome`); null while there is none. */
    private val connection: () -> Any? = { wc.client?.welcome },
) : WorkingCopy.LaneWatcher {

    /** The glasses' side as one pass reads it: the shell's status word (null: no stack runs), whether the stack draws
     *  the simulator, the glasses' battery, and the ladder (null: a transport without one). */
    data class Glasses(val word: String?, val simulated: Boolean, val batteryPct: Int?, val ladder: CfwTransportBase.Ladder?)

    /** What the surfaces show: the link cell's tag and the phone app's sentence ("" while all is well). */
    data class View(val tag: HostTag, val sentence: String)

    /** A line before its `since` is kept: [since] the source's own instant, when it has one. */
    private class Raw(val id: String, val text: String, val band: HealthBand, val doing: String, val tag: String? = null,
                      val alert: String? = null, val since: Long? = null)

    private val lock = Any()
    // ---- under [lock] ----
    private val own = LinkedHashMap<String, HealthLine>()
    private var sent: List<HealthLine>? = null
    /** The connection [sent] went on: a new connection is owed the lines whatever they hold. */
    private var sentTo: Any? = null
    private var pc: List<HealthLine> = emptyList()
    // ----

    @Volatile var view: View = View(HostTag.NONE, "")
        private set

    private var loop: Job? = null

    fun start() {
        if (everyMs > 0) loop = scope.launch {
            while (isActive) {
                pass()
                delay(everyMs)                // pacing between two reads, not a bound on any work
            }
        }
    }

    fun close() {
        loop?.cancel()
    }

    // ---- the lane's (its reading thread: handed off) ----

    override fun welcome() {
        scope.launch(Dispatchers.IO) { pass() }
    }

    override fun described(scope: String, desc: JsonObject) {
        if (scope != HealthCore.SCOPE) return
        val raw = (desc["lines"] as? JsonArray).orEmpty()
        val lines = raw.mapNotNull { e -> (e as? JsonObject)?.let { o -> HealthLine.parse(o) } }
        if (lines.size != raw.size) Log.w(TAG, "${raw.size - lines.size} of the PC's ${raw.size} health lines do not decode; left out")
        synchronized(lock) { pc = lines }
        this.scope.launch(Dispatchers.IO) { pass() }
    }

    override fun reach(r: AppClient.Reach) {
        scope.launch(Dispatchers.IO) { pass() }
    }

    /** A pass off the caller's thread: one that fails is said and the next reads everything again (a failure never ends
     *  the pacing loop or, on the phone, the process). */
    private fun pass() {
        try {
            step()
        } catch (e: Exception) {
            Log.e(TAG, "a health pass failed (${e::class.simpleName}: ${e.message}); the next pass reads every line again", e)
        }
    }

    /** One pass (any thread): the lines read, sent when one changed or the connection is new, the view composed. */
    fun step(): View {
        val client = wc.client
        val conn = connection()
        val out: List<HealthLine>?
        val v: View
        synchronized(lock) {
            val now = clock()
            val g = read()
            val reach = wc.reach
            val mine = listOf(reachRaw(reach, client), linkRaw(g), batteryRaw(g), stageRaw(g)).map { keep(it, now) }
            val same = sentTo === conn && sent?.let { s -> s.size == mine.size && s.zip(mine).all { (a, b) -> a.sameAs(b) } } == true
            out = mine.takeIf { conn != null && !same }
            val theirs = pc.filter { it.id !in HealthCore.PHONE_IDS }.map { l ->
                if (reach is AppClient.Reach.Up) l else l.copy(state = HealthCore.LAST_KNOWN, reason = "as the PC last said")
            }
            v = compose(mine + theirs, now)
            if (v != view) {
                view = v
                onView(v)                     // a volatile write on the phone: cheap under the lock
            }
        }
        // the write outside the lock: a pass never waits on another's socket
        if (out != null && send(out)) synchronized(lock) { sent = out; sentTo = conn }
        return v
    }

    // ---- the phone's four lines ----

    private fun reachRaw(r: AppClient.Reach, c: AppClient?): Raw {
        val n = wc.queue.waiting
        val wait = if (n == 0) "" else "; $n change${if (n == 1) "" else "s"} waiting"
        val build = "; this phone runs APK $apk"
        val copy = "the glasses run from this phone's copy"
        val differ = (c?.contractState as? Contracts.State.Known)?.matches?.values?.filter { !it.same }.orEmpty()
        return when {
            c != null && c.refused -> Raw(HealthCore.REACH, c.stateLine + wait, HealthBand.ALARM,
                "asked again every ${c.refusedPacingMs / 1000} s; $copy", tag = HostTag.REFUSED)
            r is AppClient.Reach.Down -> Raw(HealthCore.REACH, "not answering$wait", HealthBand.ALARM,
                "the lane connects again every ${(c?.retryPacingMs ?: 2_000) / 1000} s; $copy", tag = PC_TAG,
                alert = AlertDelivery.HOME_THREAD, since = r.since)
            r is AppClient.Reach.Up && differ.isNotEmpty() -> Raw(HealthCore.REACH,
                "answering; different builds for ${differ.joinToString { Windows.title(it.window) }}$wait$build",
                HealthBand.ATTENTION, "said only: each of those windows runs from its own side's copy until both are deployed")
            r is AppClient.Reach.Up -> Raw(HealthCore.REACH, "answering$wait$build", HealthBand.NORMAL,
                "pinged every ${(c?.pingEveryMs ?: 15_000) / 1000} s")
            else -> Raw(HealthCore.REACH, "connecting$wait$build", HealthBand.NORMAL, "the lane connects until the PC answers")
        }
    }

    private fun linkRaw(g: Glasses): Raw = when {
        g.word == null -> Raw(HealthCore.GLASSES_LINK, "no session runs", HealthBand.NORMAL, HealthCore.SHOWN_ONLY)
        g.simulated -> Raw(HealthCore.GLASSES_LINK, "the simulator (target sim): ${g.word}", HealthBand.NORMAL, HealthCore.SHOWN_ONLY)
        g.word == ShellState.OK.word -> Raw(HealthCore.GLASSES_LINK, "ok", HealthBand.NORMAL, KEEPER)
        // on purpose (`DESIGN.md` §4.4): the shell sleeps with the glasses
        g.word == ShellState.SILENT.word -> Raw(HealthCore.GLASSES_LINK, "silent, on purpose", HealthBand.NORMAL, KEEPER)
        else -> Raw(HealthCore.GLASSES_LINK, g.word, HealthBand.ATTENTION, KEEPER)
    }

    private fun batteryRaw(g: Glasses): Raw {
        val p = g.batteryPct
        return when {
            p == null -> Raw(HealthCore.GLASSES_BATTERY, "not read yet", HealthBand.NORMAL, BATTERY_READ)
            // strict "less than", the latch's own threshold (Adam, 2026-09-21: "below 20 %")
            p < BatteryLatch.LOW_PCT -> Raw(HealthCore.GLASSES_BATTERY, "$p %", HealthBand.ATTENTION,
                "the phone's Glasses battery notification says it")
            else -> Raw(HealthCore.GLASSES_BATTERY, "$p %", HealthBand.NORMAL, BATTERY_READ)
        }
    }

    private fun stageRaw(g: Glasses): Raw {
        val l = g.ladder ?: return Raw(HealthCore.GLASSES_STAGE, "no ladder on this transport", HealthBand.NORMAL,
            HealthCore.SHOWN_ONLY)
        return when {
            l.heldBack != null -> Raw(HealthCore.GLASSES_STAGE, "stage ${l.stored} held back, ${l.armed} armed: ${l.heldBack}",
                HealthBand.ATTENTION, "lifted by the next case-in or the first session start on a later day")
            l.armed != l.stored -> Raw(HealthCore.GLASSES_STAGE, "stage ${l.stored}, ${l.armed} armed", HealthBand.NORMAL, ARMS)
            else -> Raw(HealthCore.GLASSES_STAGE, "stage ${l.stored}", HealthBand.NORMAL, ARMS)
        }
    }

    // ---- under [lock] ----

    /** [r] as a line: its `since` kept while its band holds (the source's own instant where it has one), and the line
     *  as last composed kept whole while it shows the same. */
    private fun keep(r: Raw, now: Long): HealthLine {
        val prev = own[r.id]
        val since = r.since ?: if (prev != null && prev.band == r.band) prev.since else now
        val l = HealthLine(r.id, LABELS.getValue(r.id), r.text, r.band, since, now, HealthCore.PHONE, HealthCore.LIVE,
            r.doing, tag = r.tag, alert = r.alert)
        val kept = if (prev != null && prev.sameAs(l)) prev else l
        own[r.id] = kept
        return kept
    }

    private fun compose(lines: List<HealthLine>, now: Long): View {
        val all = lines.sortedBy { l -> ORDER.indexOf(l.id).let { if (it < 0) ORDER.size else it } }
        fun tagged(l: HealthLine) = l.band == HealthBand.ALARM && l.tag != null
        val t = TAG_ORDER.firstNotNullOfOrNull { id -> all.firstOrNull { it.id == id && tagged(it) } } ?: all.firstOrNull(::tagged)
        val tag = t?.let { HostTag(it.tag!!, it.since.takeIf { _ -> it.tag in HostTag.AGED }) } ?: HostTag.NONE
        val worst = all.filter { it.band != HealthBand.NORMAL }.maxByOrNull { it.band.ordinal }
        val sentence = worst?.let { w ->
            "${w.label}: ${w.text} since ${dayTime(w.since, now)} — ${w.doing}" +
                (if (w.state == HealthCore.LAST_KNOWN) " (${w.reason})" else "")
        } ?: ""
        return View(tag, sentence)
    }

    /** "14:02" today, "Nov 22 14:02" on another day. */
    private fun dayTime(ms: Long, now: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return if (t.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) HHMM.format(t) else DAY.format(t)
    }

    companion object {
        private const val TAG = "phone-health"
        /** The tag of the PC out of reach (`HostTag.AGED`). */
        const val PC_TAG = HostTag.PC
        private const val KEEPER = "the keeper starts the session again whenever it ends"
        private const val BATTERY_READ = "read from the glasses a few times an hour"
        private const val ARMS = "armed at each session start"
        private val LABELS: Map<String, String> = HealthCore.LINES.toMap()
        private val ORDER: List<String> = HealthCore.LINES.map { it.first }
        /** The tag's order (§4.5): the PC, the hub, the disk, the nightly copy; any other tagged line after them. */
        private val TAG_ORDER = listOf(HealthCore.REACH, HealthCore.HUB, HealthCore.DISK, HealthCore.BACKUP)
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH)
    }
}
