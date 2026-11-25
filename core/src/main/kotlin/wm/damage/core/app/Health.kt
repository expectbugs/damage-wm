package wm.damage.core.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * R-b stage H1 (`private/build085/RB-lean-spec.md` §4.1, §4.2, §4.8): the health record — one description on the PC,
 * `desc/health`, that every surface and the later agents read. Each line is `CONTRACT.md` §3.2's value with `since`
 * (when its state began), `doing` (what the system does about it, in plain words; "shown only" where nothing is
 * done), `tag` (the glasses' word, alarm lines only) and `alert` (the id of the line's own existing alert). The PC's
 * readers (`desktop/HealthParts.kt`) and, from H2, the phone's report set the lines. The description is published again
 * only when what a line shows changes — never per read, so a hub answering every 10 s publishes nothing — and a line in
 * alarm for [NOTICE_AFTER_MS] with no alert of its own raises one notice per episode. Nothing here is saved: a restart
 * reads every line again from its source; the only kept state, a notice episode, ends when its line leaves alarm.
 */

enum class HealthBand(val wire: String) { NORMAL("normal"), ATTENTION("attention"), ALARM("alarm") }

/** One line of the health record (§4.1). [since]: when its state began — the source's own instant when it has one,
 *  else when its band last changed. [asOf]: when what it shows last changed (a line is read again at its pace and
 *  published only on a change; `state` live says those reads succeed). */
data class HealthLine(
    val id: String,
    val label: String,
    val text: String,
    val band: HealthBand,
    val since: Long,
    val asOf: Long,
    val source: String,
    val state: String,
    val doing: String,
    val reason: String? = null,
    val tag: String? = null,
    val alert: String? = null,
) {
    /** True when [o] shows the same as this: everything but the instant of reading. */
    fun sameAs(o: HealthLine): Boolean = copy(asOf = 0) == o.copy(asOf = 0)

    fun json(): JsonObject = buildJsonObject {
        put("id", id)
        put("label", label)
        put("text", text)
        put("band", band.wire)
        put("since", since)
        put("asOf", asOf)
        put("source", source)
        put("state", state)
        reason?.let { put("reason", it) }
        put("doing", doing)
        tag?.let { put("tag", it) }
        alert?.let { put("alert", it) }
    }

    companion object {
        /** A line as [json] wrote it (R-b H2: the phone's `health` message and the phone's copy of `desc/health`), or
         *  null when its id, band or `since` does not decode (the caller says so). */
        fun parse(o: JsonObject): HealthLine? {
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun n(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            val band = HealthBand.entries.firstOrNull { it.wire == s("band") } ?: return null
            return HealthLine(s("id") ?: return null, s("label") ?: "", s("text") ?: "", band, n("since") ?: return null,
                n("asOf") ?: 0L, s("source") ?: "", s("state") ?: "", s("doing") ?: "", s("reason"), s("tag"), s("alert"))
        }
    }
}

/**
 * The record on the PC's core executor. [set] and [failed] take a PC reader's result, [phone] and [phoneGone] the
 * phone's report (H2 wires them to the lane); all of them from any thread. Served as `desc/health` at level view.
 */
class HealthCore(
    private val host: AppHost,
    private val notices: NoticeCore,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : AppNet.Described {
    /** What a PC reader read: the words, the band, what is done about it; [since] the source's own instant for this
     *  state when it has one; [stale] why the reading is not current (a request stuck), null when it is. */
    data class Reading(
        val text: String,
        val band: HealthBand,
        val doing: String,
        val tag: String? = null,
        val alert: String? = null,
        val since: Long? = null,
        val stale: String? = null,
    )

    val startedAt: Long = host.clock()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // ---- executor only ----
    private val lines = LinkedHashMap<String, HealthLine>()
    /** Per line: when its band last changed (the notice rule's clock and the default `since`). */
    private val bandAt = HashMap<String, Long>()
    /** The lines whose current alarm episode has raised its notice. */
    private val noticed = HashSet<String>()
    /** The lines whose reads are failing now: said once per run of failures. */
    private val failing = HashSet<String>()
    // ----

    /** The lines as last changed, for [describe] on the lane's describer. */
    @Volatile private var view: List<HealthLine>

    init {
        val start = "the service's start at ${hhmm(startedAt)}"
        for ((id, label) in LINES) {
            val phone = id in PHONE_IDS
            lines[id] = if (phone) HealthLine(id, label, "not reported", HealthBand.NORMAL, startedAt, startedAt, PHONE,
                LAST_KNOWN, "the phone reports its lines when they change and when it connects",
                reason = "the phone has not reported since $start")
            else HealthLine(id, label, "not read yet", HealthBand.NORMAL, startedAt, startedAt, PC, STALE,
                "read from its source at the service's start, then at its pace", reason = "not read since $start")
            bandAt[id] = startedAt
        }
        view = lines.values.toList()
        host.addTickListener(::tick)
    }

    /** [id]'s reading from a PC reader (any thread; applied on the executor). */
    fun set(id: String, r: Reading) = onExecutor("health $id") {
        val old = pcLine(id) ?: return@onExecutor
        if (failing.remove(id)) Log.i(TAG, "health line $id is read again")
        val now = host.clock()
        val at = if (r.band != old.band) now else bandAt.getValue(id)
        put(old.copy(text = r.text, band = r.band, since = r.since ?: at, state = if (r.stale == null) LIVE else STALE,
            doing = r.doing, reason = r.stale, tag = r.tag, alert = r.alert), now)
    }

    /** [id]'s read failed with [why]: its last reading stays, `stale` with the words; said once per run of failures. */
    fun failed(id: String, why: String) = onExecutor("health $id failed") {
        val old = pcLine(id) ?: return@onExecutor
        if (failing.add(id)) Log.w(TAG, "health line $id: the read failed ($why); its last reading is kept, marked stale")
        put(old.copy(state = STALE, reason = "the read failed: $why"), host.clock())
    }

    /** The phone's own lines as it reported them, arrived [at] on the PC's clock (any thread). A line of another id is
     *  said and left out. */
    fun phone(reported: List<HealthLine>, at: Long) = onExecutor("health phone") {
        for (l in reported) {
            val old = lines[l.id]
            if (old == null || old.source != PHONE) {
                Log.w(TAG, "the phone reported a health line '${l.id}' that is not one of its own: left out")
                continue
            }
            put(l.copy(label = old.label, source = PHONE), at)
        }
    }

    /** The phone's connection ended at [at]: its lines are `lastKnown` until it reports again (any thread). */
    fun phoneGone(at: Long) = onExecutor("health phone gone") {
        for (id in PHONE_IDS) {
            val old = lines.getValue(id)
            if (old.state == LAST_KNOWN) continue
            put(old.copy(state = LAST_KNOWN, reason = "phone not connected since ${hhmm(at)}"), host.clock())
        }
    }

    /** A copy of the lines as they stand (tests; any thread). */
    fun lines(): List<HealthLine> = view

    override fun gate(scope: String): AppNet.Gate? = if (scope == SCOPE) AppNet.Gate(null, Levels.VIEW) else null

    /** Described again only when a line changes ([addListener]); never by a record change. */
    override fun follows(scope: String, c: Change?): Boolean = c == null && scope == SCOPE

    override fun addListener(l: () -> Unit) {
        listeners += l
    }

    override fun describe(scope: String): JsonObject? {
        if (scope != SCOPE) return null
        val ls = view
        val worst = ls.maxByOrNull { it.band.ordinal }?.takeIf { it.band != HealthBand.NORMAL }
        return buildJsonObject {
            put("id", "health")
            put("name", "Health")
            put("min", Levels.VIEW)
            put("availability", "live")
            put("started", startedAt)
            put("worst", buildJsonObject {
                worst?.let { put("id", it.id) }
                put("band", (worst?.band ?: HealthBand.NORMAL).wire)
            })
            put("summary", buildJsonObject { put("line", worst?.let { "${it.label}: ${it.text}" } ?: "All well") })
            put("lines", buildJsonArray { for (l in ls) add(l.json()) })
        }
    }

    /** The notice rule (§4.2), on the record's tick: a line in alarm for [NOTICE_AFTER_MS] with no alert of its own
     *  raises one notice; its episode ends when the line leaves alarm. The PC cannot withdraw a notice: the line's
     *  return is said on the surfaces. */
    private fun tick() {
        val now = host.clock()
        for (l in lines.values) {
            if (l.band != HealthBand.ALARM || l.alert != null || l.id in noticed) continue
            if (now - bandAt.getValue(l.id) < NOTICE_AFTER_MS) continue
            noticed += l.id
            val body = "${l.label}: ${l.text} since ${dayTime(l.since, now)}. ${l.doing.replaceFirstChar { it.uppercase() }}."
            Log.i(TAG, "health line ${l.id} in alarm ${(now - bandAt.getValue(l.id)) / 60_000} min: one notice")
            notices.raise(SOURCE, l.id, body, null, null, GATED_BY)
        }
    }

    private fun pcLine(id: String): HealthLine? {
        val old = lines[id]
        if (old == null || old.source != PC) {
            Log.e(TAG, "a PC reader set the health line '$id', which is not one of the PC's: left out")
            return null
        }
        return old
    }

    /** Executor only: [l] replaces its line when it shows something else; the description is then published. */
    private fun put(l: HealthLine, now: Long) {
        val old = lines.getValue(l.id)
        if (old.sameAs(l)) return
        if (l.band != old.band) {
            bandAt[l.id] = now
            if (l.band != HealthBand.ALARM) noticed -= l.id
        }
        lines[l.id] = l.copy(asOf = now)
        view = lines.values.toList()
        for (x in listeners) x()
    }

    private fun onExecutor(what: String, f: () -> Unit) {
        if (host.executor.isCurrent()) f() else host.executor.execute(what, f)
    }

    private fun hhmm(ms: Long): String = HHMM.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** "14:02" today, "Nov 22 14:02" on another day. */
    private fun dayTime(ms: Long, now: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return if (t.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) HHMM.format(t) else DAY.format(t)
    }

    companion object {
        private const val TAG = "health"
        const val SCOPE = "desc/health"
        /** His question Q1 (open; `RB-lean-spec.md` §1, recommended 5 min — the hub's silence alert's length, so every
         *  health notice waits the same): how long a line is in alarm, with no alert of its own, before its one notice.
         *  His answer changes this constant and nothing else. */
        const val NOTICE_AFTER_MS = 5 * 60_000L
        /** The notices' source and the Settings category that names them: Global, whose `Notify · Damage` gates them on
         *  the glasses (R-b H2; the shell's one gate, `Shell.noticeAllowed`). */
        const val SOURCE = "health"
        const val GATED_BY = wm.damage.core.shell.Notifications.GLOBAL

        const val PC = "pc"
        const val PHONE = "phone"
        const val LIVE = "live"
        const val STALE = "stale"
        const val LAST_KNOWN = "lastKnown"
        /** What a line says where nothing is done about it. */
        const val SHOWN_ONLY = "shown only"

        const val SERVICE = "service"
        const val REACH = "reach"
        const val PHONE_LINE = "phone"
        const val HUB = "hub"
        const val BACKUP = "backup"
        const val CAP = "cap"
        const val GLASSES_LINK = "glasses.link"
        const val GLASSES_BATTERY = "glasses.battery"
        const val GLASSES_STAGE = "glasses.stage"
        const val BUILDS = "builds"
        const val DISK = "disk"
        const val ERROR = "error"
        const val SOURCES = "sources"

        /** The lines in the spec's §3.2 order, with their labels. */
        val LINES: List<Pair<String, String>> = listOf(
            SERVICE to "Service", REACH to "PC reach", PHONE_LINE to "Phone", HUB to "Home hub", BACKUP to "Nightly copy",
            CAP to "Memory cap", GLASSES_LINK to "Glasses link", GLASSES_BATTERY to "Glasses battery",
            GLASSES_STAGE to "Glasses stage", BUILDS to "Builds", DISK to "Disk", ERROR to "Last error", SOURCES to "Sources",
        )
        /** The lines the phone reports (H2); every other line is the PC's. */
        val PHONE_IDS: Set<String> = setOf(REACH, GLASSES_LINK, GLASSES_BATTERY, GLASSES_STAGE)

        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH)
    }
}
