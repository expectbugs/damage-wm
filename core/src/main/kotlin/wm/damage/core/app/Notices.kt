package wm.damage.core.app

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.shell.QuietHours
import wm.damage.core.comp.Journal
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q3 (the spec's §2.1, §5 rows N1–N3; `CONTRACT.md` §7; decisions A.1, A.2; S1-2 R0 §2): the PC's
 * notice record. Every notice is an item of the pseudo-window `notices` in the PC record, `rec.notices.<source>:<thread>`,
 * its value {occ, raisedAt, app, source, body, urgency, gatedBy, target, state, heldUntil?}. `occ` is both the occurrence and the unit of each interface's notice cursor, and it is written as TEXT:
 * [Notices.RULE] reads an occurrence only as text, so a number would make every read and dismiss change nothing,
 * unsaid (R0 §2). A core raises on the PC only
 * ([NoticeCore]); reads and dismisses come from input on any interface; a state never moves backwards ([Notices.RULE]).
 * A RAISED record raised inside Quiet hours carries `heldUntil` (the PC's clock, ms): every interface reads it, none
 * decides the hold itself.
 */

/** A notice of the record as a presenter names it: its id and the occurrence it saw. */
data class NoticeRef(val id: String, val occ: String)

/** One notice as the record holds it, read from its value; [value] is that whole value (a read or a dismiss carries it
 *  back with its state changed: the result, never a recipe). [thread] is the id's part after `<source>:`. */
data class NoticeRecord(
    val id: String,
    val occ: String,
    val occN: Long,
    val raisedAt: Long,
    val app: String?,
    val source: String,
    val thread: String,
    val body: String,
    val urgency: String,
    val gatedBy: String?,
    val target: String?,
    val state: String,
    val heldUntil: Long?,
    val value: JsonObject,
) {
    val ref: NoticeRef get() = NoticeRef(id, occ)

    /** In the live table (C§7.2 item 4): raised or read. */
    val live: Boolean get() = state == Notices.RAISED || state == Notices.READ

    /** Held by Quiet hours at device time [now] (R0 §2: the field the record carries, never a device's own call). */
    fun heldAt(now: Long): Boolean = state == Notices.RAISED && heldUntil != null && heldUntil > now
}

/** The notice record's window: its actions and levels, its apply-time rule, its parsing and its drafts. Its
 *  description serves any host (the PC's record, the phone's working copy). */
object Notices : WindowCore {
    const val WINDOW = "notices"

    // the actions (C§7.1; the spec's §2.1)
    const val RAISE = "raise"
    const val MARK_READ = "read"
    const val DISMISS = "dismiss"

    // the states, upper case as C§7.1 writes them
    const val RAISED = "RAISED"
    const val READ = "READ"
    const val DISMISSED = "DISMISSED"

    const val NORMAL = "NORMAL"
    const val URGENT = "URGENT"

    /** The PC raises as this device, in this stream; no other device may (refused at apply time). */
    const val PC = "pc"
    const val STREAM = "notices"

    /** The state each action writes. */
    val STATE_OF: Map<String, String> = mapOf(RAISE to RAISED, MARK_READ to READ, DISMISS to DISMISSED)

    /** A state's place in a notice's life: an op whose state ranks below the standing one of the same occurrence
     *  would move it backwards (R0 §2) and changes nothing. */
    private val RANK = mapOf(RAISED to 0, READ to 1, DISMISSED to 2)

    /** Every action is a register (C§2.4), ordered by [RULE]: a new raise supersedes the shown occurrence; `read` and
     *  `dismiss` act on the occurrence their value carries. */
    val ACTIONS = ActionTable { w, a -> if (w == WINDOW && a in STATE_OF) ActionSpec(Kind.REGISTER) else null }

    /** The level [action] needs (C§4.2): reading effects are view; `raise` full, and the PC's own. */
    fun levelOf(action: String): String? = when (action) {
        MARK_READ, DISMISS -> Levels.VIEW
        RAISE -> Levels.FULL
        else -> null
    }

    /** The record's apply-time rule for this window (`AppHost`, in the merge rule's place: a notice is ordered by its
     *  occurrence, not by device clocks): a raise only from [PC], always written; every op carries its whole value (its
     *  occurrence as text, its action's state); a reading effect is written only on the occurrence it carries and
     *  never moves a state backwards — a `read` after a dismiss of the same occurrence, or a dismiss of an occurrence a
     *  newer raise replaced, changes nothing (never said). */
    val RULE = ApplyRule { rec, op -> admit(rec, op) }

    private fun admit(rec: RecordView, op: Op): Admit? {
        val want = STATE_OF[op.action] ?: return null
        val own = op.action == RAISE
        if (own && op.device != PC)
            return Admit.Refuse(Answer.Refused("a notice's ${op.action} is the PC's own, not device ${op.device}'s", Answer.LEVEL))
        val r = op.result as? JsonObject
        val occ = r?.let { text(it, "occ") }
        if (r == null || occ == null || text(r, "state") != want || op.targets.size != 1)
            return Admit.Refuse(Answer.Refused("a notice's ${op.action} carries one target and its whole value: occ as " +
                "text and state $want", Answer.INVALID))
        if (own) return Admit.Apply
        val standing = rec.item(Record.key(WINDOW, op.targets[0]))?.takeIf { !it.gone }?.value as? JsonObject
            ?: return Admit.Keep                                           // no notice to act on
        if (text(standing, "occ") != occ) return Admit.Keep               // another occurrence
        if ((RANK[want] ?: 0) < (RANK[text(standing, "state")] ?: 0)) return Admit.Keep
        return Admit.Apply
    }

    /** [state]'s notices by id: every value that reads as one (only [NoticeCore] writes them, its occurrence as text). */
    fun records(state: WindowState): Map<String, NoticeRecord> {
        val out = HashMap<String, NoticeRecord>()
        val p = Record.prefix(WINDOW)
        for ((k, item) in state.items) {
            if (item.gone || !k.startsWith(p)) continue
            val id = k.removePrefix(p)
            parse(id, item.value)?.let { out[id] = it }
        }
        return out
    }

    /** One value as a [NoticeRecord], or null when it carries no occurrence as text. */
    fun parse(id: String, v: kotlinx.serialization.json.JsonElement): NoticeRecord? {
        val o = v as? JsonObject ?: return null
        val occ = text(o, "occ") ?: return null
        val n = occ.toLongOrNull() ?: return null
        return NoticeRecord(id, occ, n, num(o, "raisedAt") ?: 0L, text(o, "app"), text(o, "source") ?: id.substringBefore(':'),
            id.substringAfter(':', id), text(o, "body") ?: "", text(o, "urgency") ?: NORMAL, text(o, "gatedBy"),
            text(o, "target"), text(o, "state") ?: RAISED, num(o, "heldUntil"), o)
    }

    /** A reading effect on [r] (`read` or `dismiss`): its whole value, its occurrence included, with the
     *  action's state (C§2.4: the result, never a recipe). */
    fun draft(r: NoticeRecord, action: String): Draft {
        val state = STATE_OF[action] ?: throw IllegalArgumentException("no notice action $action")
        return Draft(action, listOf(r.id), JsonObject(r.value + ("state" to JsonPrimitive(state))))
    }

    fun readDraft(r: NoticeRecord) = draft(r, MARK_READ)
    fun dismissDraft(r: NoticeRecord) = draft(r, DISMISS)

    // ---- the description (C§3.1): the live table ----

    override val window: String = WINDOW

    override fun describe(state: WindowState): JsonObject = buildJsonObject {
        put("id", WINDOW)
        put("name", "Notices")
        put("min", Levels.VIEW)
        put("live", buildJsonArray {
            for (r in records(state).values.filter { it.live }.sortedBy { it.occN }) add(r.value + ("id" to JsonPrimitive(r.id)))
        })
    }

    /** A live notice by its id, or null. */
    override fun level(state: WindowState, addr: String): JsonObject? =
        records(state)[addr]?.takeIf { it.live }?.let { JsonObject(it.value + ("id" to JsonPrimitive(it.id))) }

    private operator fun JsonObject.plus(e: Pair<String, JsonPrimitive>): JsonObject = JsonObject(this.toMap() + e)

    internal fun text(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun num(o: JsonObject, k: String): Long? = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}

/**
 * The PC's raiser (the spec's `NoticeCore`): a core's event becomes a record of the `notices` window, raised once on
 * the PC as device [Notices.PC] in stream [Notices.STREAM] (a [HostBackend]). Built once per process by the PC's two
 * host builders ([atProcessStart]); its description is [Notices]'.
 *
 * [quietHours] reads the one shared Quiet hours row (its preset id) once per raise; [clock] and [zone] are the PC's.
 * [note] writes a line in the shell journal (none in host-only). Every state field is the core executor's.
 */
class NoticeCore(
    private val host: AppHost,
    private val quietHours: () -> String = { QuietHours.OFF },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val note: (String) -> Unit = {},
) : WindowCore by Notices {

    /** One raise, as the core asked for it; kept whole so a raise the log did not take is minted again. */
    data class Raise(
        val source: String,
        val thread: String,
        val body: String,
        val app: String?,
        val target: String?,
        val gatedBy: String,
        val urgency: String,
        val raisedAt: Long,
    ) {
        val id: String get() = "$source:$thread"
    }

    private val backend = HostBackend(host, Notices.PC, Notices.STREAM)
    /** Raises handed to the record and not yet answered, by the occurrence they carry. */
    private val inFlight = HashMap<String, Raise>()
    /** §5 N1 (b): raises answered `retry` (the log did not take them), the latest per id, sent again at the
     *  record's pacing tick and never dropped. */
    private val kept = LinkedHashMap<String, Raise>()
    /** The largest occurrence this core minted: a raise not yet applied still holds its number. */
    private var lastOcc = 0L

    private val listener = object : SourceListener {
        override fun answered(op: Op, answer: Answer) = answeredNow(op, answer)
    }

    init {
        host.executor.call { backend.watch(Notices.WINDOW, listener) }
    }

    /**
     * Raise the notice ([source], [thread]) with [body] (fixed at the raise, never cut), naming the window [app] that
     * a tap opens at [target], gated by the Settings category [gatedBy]. Any thread: handed to the executor. There,
     * [notifyRow] (a record key of the app's Notify row; absent = on) is read once and a row that is off raises
     * nothing; then the occurrence is minted and, for a NORMAL notice inside Quiet hours, `heldUntil` set.
     */
    fun raise(source: String, thread: String, body: String, app: String?, target: String?, gatedBy: String,
              urgency: String = Notices.NORMAL, notifyRow: String? = null) {
        require(':' !in source && source.isNotBlank()) { "a notice's source names no ':' and is not blank: \"$source\"" }
        val r = Raise(source, thread, body, app, target, gatedBy, urgency, clock())
        host.executor.execute("notice raise ${r.id}") {
            if (notifyRow != null && !rowOn(notifyRow)) {
                Log.i(TAG, "${r.id} not raised: its Notify row ($notifyRow) is off")
                return@execute
            }
            submit(r)
        }
    }

    /** The record's pacing tick (on the executor): kept raises are minted and sent again. */
    fun tick() {
        if (kept.isEmpty()) return
        val again = kept.values.toList()
        kept.clear()
        for (r in again) submit(r)
    }

    /** True unless [key]'s item is a boolean false (absent, removed or not a boolean = on: the rows' default). */
    private fun rowOn(key: String): Boolean {
        val i = host.read(HostBackend.windowOf(key)).third.items[key]
        if (i == null || i.gone) return true
        return (i.value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: true
    }

    private fun submit(r: Raise) {
        val top = host.read(Notices.WINDOW).third.items.values.maxOfOrNull { occOf(it.value) } ?: 0L
        lastOcc = maxOf(lastOcc, top) + 1
        val occ = lastOcc.toString()
        val held = if (r.urgency == Notices.NORMAL)
            QuietHours.endAfter(quietHours(), Instant.ofEpochMilli(r.raisedAt).atZone(zone()))?.toInstant()?.toEpochMilli()
        else null
        val value = buildJsonObject {
            put("occ", occ)                                   // text: R0 §2
            put("raisedAt", r.raisedAt)
            put("app", r.app?.let { JsonPrimitive(it) } ?: JsonNull)
            put("source", r.source)
            put("body", r.body)
            put("urgency", r.urgency)
            put("gatedBy", r.gatedBy)
            put("target", r.target?.let { JsonPrimitive(it) } ?: JsonNull)
            put("state", Notices.RAISED)
            if (held != null) put("heldUntil", held)
        }
        inFlight[occ] = r
        backend.submit(Notices.WINDOW, Draft(Notices.RAISE, listOf(r.id), value))
    }

    private fun answeredNow(op: Op, a: Answer) {
        if (op.action != Notices.RAISE) return
        val occ = (op.result as? JsonObject)?.let { Notices.text(it, "occ") } ?: return
        val r = inFlight.remove(occ) ?: return
        when {
            a is Answer.Refused && a.code == Answer.RETRY -> {
                // a newer raise of the same id, in flight or kept, supersedes this one
                if (inFlight.values.any { it.id == r.id } || r.id in kept) return
                if (kept.isEmpty()) Log.w(TAG, "the record did not take ${r.id} (${a.reason}): sent again at its pacing tick")
                kept[r.id] = r
            }
            a is Answer.Refused -> {
                val line = "${r.id} was not raised: ${a.reason}"
                Log.w(TAG, line)
                note(line)
            }
            else -> {}
        }
    }

    companion object {
        private const val TAG = "notices"

        /** A value's occurrence as a number, or 0 when it carries none as text. */
        fun occOf(v: kotlinx.serialization.json.JsonElement): Long =
            (v as? JsonObject)?.let { Notices.text(it, "occ") }?.toLongOrNull() ?: 0L

        /**
         * At a PROCESS START, in the PC's two host builders only (`runShell`, `hostOnly`; `tools/check_apk_wiring.py`
         * holds the list): the raiser over [host], its kept raises sent again on the record's pacing tick. [journal]:
         * the shell journal (null in host-only, where the log alone hears it).
         */
        fun atProcessStart(host: AppHost, quietHours: () -> String, journal: Path?): NoticeCore {
            val core = NoticeCore(host, quietHours, note = { line -> journal?.let { Journal.appendNote(it, "notices", line) } })
            host.addTickListener(core::tick)
            return core
        }
    }
}
