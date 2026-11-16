package wm.damage.core.windows.torrents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.ActionSpec
import wm.damage.core.app.ActionTable
import wm.damage.core.app.Draft
import wm.damage.core.app.Expect
import wm.damage.core.app.Item
import wm.damage.core.app.Kind
import wm.damage.core.app.Levels
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.Record
import wm.damage.core.app.WindowCore
import wm.damage.core.app.WindowState
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q4 (the spec's §3.1, §3.5; `CONTRACT.md` §12's Torrents row, §3.3, §4, §7; `TORRENTS.md` §3):
 * Torrents' core. Its own records live in the PC record — the search recents (one register, Expect.Any: decisions
 * A.9), the two Notify rows (shared) and the glasses' Size row — and the transfer table, the tracker and the session
 * stay the provider's (source state, C§2.1). The core names every action with its kind and level, builds the window's
 * description from the record and the PC's live source, and raises the done and error notices ONCE, on the PC, into
 * the notice record ([attachNotices]). The glasses' presenter (`TorrentsGlasses`) reads it through its `WindowSource`;
 * the web page reads the same description (`TorrentsScopes`). No pixels here.
 */

/**
 * What the PC holds of Torrents beside the record (`TorrentsScopes` keeps it): the provider's last snapshot and state
 * line, the session's speed samples (memory only, lost at a restart), when the state line stopped being healthy, and
 * the tracker's last answer ("ok", its failure in words, or null before anything asked it).
 */
data class TorrentsLive(
    val snapshot: Snapshot?,
    val stateLine: String,
    val speeds: List<Speed>,
    val qbtSince: Long? = null,
    val tracker: String? = null,
) {
    data class Speed(val at: Long, val dl: Long, val up: Long)
}

/**
 * Torrents' core. [live] is the PC's source (null on the phone, whose presenter reads the table from its own provider
 * over the window channel at today's paces, decisions A.3): the description carries the table only where it is given.
 */
class TorrentsCore(private val live: () -> TorrentsLive? = { null }) : WindowCore {
    override val window: String = WINDOW

    override fun describe(state: WindowState): JsonObject = describe(state, live())

    /** `t:<hash>` names a transfer of the PC's table; nothing else is a level of this core. */
    override fun level(state: WindowState, addr: String): JsonObject? {
        if (!addr.startsWith("t:")) return null
        val t = live()?.snapshot?.transfers?.firstOrNull { it.hash == addr.removePrefix("t:") } ?: return null
        return transferRow(t)
    }

    /** Main's summary: a provider state line replaces line 1 (staleness said with its duration, TORRENTS.md §3.4). */
    data class Sum(val line: String, val detail: String, val more: Boolean = false, val progress: Double? = null)

    // ---- the notices (the spec's §3.1 "Notices", §5 N6) ----

    private val noticeLock = Any()
    /** The last event `seq` of the provider's epoch this core handled (memory: the event ring is per process too). */
    private var handledSeq = 0L
    @Volatile private var attachedTo: TorrentsProvider? = null

    private val events = object : TorrentsProvider.Listener {
        override fun snapshot(s: Snapshot) {}
        override fun state(line: String) {}
        override fun event(e: TorrentEvent) = synchronized(noticeLock) { handle(e) }
    }

    private var notices: NoticeCore? = null

    /**
     * At a PROCESS START, in the PC's two host builders only (never per stack): listens to [provider]'s events and
     * replays its ring ([epoch]'s events from the start) first, because the provider's first poll starts in its own
     * `init` and may have raised its events already. A `done` or `error` event is raised into the notice record by
     * [notices] — the app's Notify row read once on the executor at the raise; the body raw (`done · <name>`), each
     * presenter sanitising it with its own face. Events at or below the last handled `seq` are never raised twice.
     */
    fun attachNotices(provider: TorrentsProvider, epoch: Long, notices: NoticeCore) {
        synchronized(noticeLock) {
            check(attachedTo == null) { "Torrents' notices are attached once per process" }
            attachedTo = provider
            this.notices = notices
            provider.addListener(events)
            for (e in provider.eventsSince(0, epoch).sortedBy { it.seq }) handle(e)
        }
    }

    /** At a process end (tests): the provider is no longer listened to. */
    fun detachNotices() {
        synchronized(noticeLock) {
            attachedTo?.removeListener(events)
            attachedTo = null
        }
    }

    private fun handle(e: TorrentEvent) {
        if (e.seq <= handledSeq) return
        handledSeq = e.seq
        val n = notices ?: return
        val row = when (e.kind) {
            "done" -> NOTIFY_DONE
            "error" -> NOTIFY_ERRORS
            else -> return
        }
        try {
            n.raise(NOTICE_SOURCE, e.hash, "${e.kind} · ${e.name}", WINDOW, "t:${e.hash}", GATED_BY,
                notifyRow = Record.key(WINDOW, row))
        } catch (ex: Exception) {
            // a source this build would not take (it names none of today's): said, never dropped unsaid
            Log.e(TAG, "the ${e.kind} notice of ${e.hash} could not be raised", ex)
        }
    }

    companion object {
        private const val TAG = "torrents"
        const val WINDOW = "torrents"

        // the record items (C§12: shared rows, the glasses' Size, the search recents)
        const val RECENTS = "recents"
        const val NOTIFY_DONE = "notify.done"
        const val NOTIFY_ERRORS = "notify.errors"
        /** The glasses' Torrents height: 0 = the Global height, else one of the four sizes (Reader's precedent). */
        const val SIZE = "glasses.size"
        const val RECENTS_MAX = 10

        /** The notice's source and category: today's (`DAMAGE · torrent`, gated by Settings → Torrents), so a record
         *  notice and the presenter's own raise of one event coalesce into one box (`Notifications.post`). */
        const val NOTICE_SOURCE = "torrent"
        const val GATED_BY = "Torrents"

        // the actions (the spec's §3.1 table)
        const val START = "start"
        const val STOP = "stop"
        const val RECHECK = "recheck"
        const val DELETE = "delete"
        const val DELETE_FILES = "deleteFiles"
        const val ADD = "add"
        const val ADD_STOPPED = "addStopped"
        const val OPEN_ON_PC = "openOnPc"
        const val OPEN_IN_FILES = "openInFiles"
        const val RECENT = "recent"
        const val SET = "set"
        /** A register's value from before Torrents' conversion, judged by its old stamp (the position kind, as
         *  Reader's `migrate`: the kind whose expectation may be a pre-conversion stamp). */
        const val MIGRATE = "migrate"

        /** The actions that go into an outside program (qBittorrent, the tracker, `xdg-open`): never queued. */
        val PROGRAM: Set<String> = setOf(START, STOP, RECHECK, DELETE, DELETE_FILES, ADD, ADD_STOPPED, OPEN_ON_PC)

        /** The level [action] needs (C§4.2, C§12): start, stop, recheck and delete keeping files control; delete with
         *  files full (it writes files on the PC); the tracker's add and opening on the PC full; a search's recent
         *  full (only a full device can search); a row and a migrated row control. Null: no such action. */
        fun levelOf(action: String): String? = when (action) {
            START, STOP, RECHECK, DELETE, SET, MIGRATE -> Levels.CONTROL
            DELETE_FILES, ADD, ADD_STOPPED, OPEN_ON_PC, RECENT -> Levels.FULL
            OPEN_IN_FILES -> Levels.VIEW
            else -> null
        }

        /** Torrents' record actions as the record judges them (C§2.4); the program actions are not the record's. */
        val ACTIONS = ActionTable { w, a ->
            if (w != WINDOW) null else when (a) {
                RECENT, SET -> ActionSpec(Kind.REGISTER)
                MIGRATE -> ActionSpec(Kind.POSITION)
                else -> null
            }
        }

        /** One action as the description lists it (C§4.1): kind r · i · o · v · register, `into`, `targets` one or
         *  many, `confirm` present when the page asks first (its words are the page's, the spec's table). */
        data class ActionDesc(val id: String, val label: String, val kind: String, val into: String?, val targets: String,
                              val confirm: JsonObject? = null) {
            val level: String get() = levelOf(id) ?: Levels.FULL
        }

        private val ASK = JsonObject(emptyMap())
        private val ASK_TWICE = buildJsonObject { put("double", true) }

        val ACTION_LIST: List<ActionDesc> = listOf(
            ActionDesc(START, "Start", "r", "program", "many"),
            ActionDesc(STOP, "Stop", "r", "program", "many"),
            ActionDesc(RECHECK, "Recheck", "r", "program", "many"),
            ActionDesc(DELETE, "Delete", "i", "program", "many", ASK),
            ActionDesc(DELETE_FILES, "Delete with files", "i", "program", "many", ASK_TWICE),
            ActionDesc(ADD, "Add", "o", "program", "one", ASK),
            ActionDesc(ADD_STOPPED, "Add stopped", "o", "program", "one", ASK),
            ActionDesc(OPEN_ON_PC, "Open on the PC", "r", "program", "one"),
            ActionDesc(OPEN_IN_FILES, "Open in Files", "v", null, "one"),
            ActionDesc(RECENT, "Keep the search", "register", "record", "one"),
            ActionDesc(SET, "Set a row", "register", "record", "one"),
        )

        /** The rows' values when the record holds none (Notify on, Size global). */
        val DEFAULTS: Map<String, JsonElement> = mapOf(
            NOTIFY_DONE to JsonPrimitive(true), NOTIFY_ERRORS to JsonPrimitive(true), SIZE to JsonPrimitive(0),
            RECENTS to JsonArray(emptyList()),
        )

        // ---- the record's items, read ----

        private fun live(state: WindowState, name: String): Item? = state.itemNamed(name)?.takeIf { !it.gone }

        /** The search recents, newest first (at most ten), or null when the record holds none. */
        fun recents(state: WindowState): List<String>? = recentsOf(live(state, RECENTS)?.value)

        /** A recents value as a list of searches (deduped, at most ten), or null when it is not one. */
        fun recentsOf(v: JsonElement?): List<String>? = (v as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() }
        }?.distinct()?.take(RECENTS_MAX)

        fun notifyDone(state: WindowState): Boolean? = bool(live(state, NOTIFY_DONE)?.value)
        fun notifyErrors(state: WindowState): Boolean? = bool(live(state, NOTIFY_ERRORS)?.value)

        /** The glasses' height: 0 (global) or one of the four sizes; null when the record holds none. */
        fun size(state: WindowState): Int? = sizeOf(live(state, SIZE)?.value)

        fun sizeOf(v: JsonElement?): Int? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?.takeIf { it == 0 || it in wm.damage.core.shell.ShellSettings.HEIGHTS }

        fun bool(v: JsonElement?): Boolean? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        // ---- the drafts a presenter sends (C§2.4: the result, and what the device expected to find) ----

        /** The expectation for a row a device last saw as [seen] (Reader's rule). */
        fun expectSeen(seen: Item?): Expect = when {
            seen == null -> Expect.Value(JsonNull)
            seen.gone -> Expect.Any
            else -> Expect.Value(seen.value)
        }

        /** The recents after a search: the whole list (decisions A.9: one register, Expect.Any). */
        fun recentDraft(list: List<String>) =
            Draft(RECENT, listOf(RECENTS), JsonArray(list.take(RECENTS_MAX).map { JsonPrimitive(it) }), Expect.Any)

        fun setDraft(row: String, value: JsonElement, expect: Expect) = Draft(SET, listOf(row), value, expect)

        // ---- the summary (moved from `TorrentsWindow.summary()`/`counts()`: Main's row and the web's card share it) ----

        fun summary(s: Snapshot?, stateLine: String): Sum {
            if (stateLine.isNotEmpty()) return Sum(stateLine, counts(s))
            if (s == null) return Sum("connecting", "")
            val dl = s.transfers.filter { it.downloading && !it.stopped }
            val seeding = s.transfers.count { it.finished }
            val line = when {
                dl.isNotEmpty() -> "${dl.size} downloading · ${Fmt.speed(s.session.dlSpeed)}"
                seeding > 0 -> "$seeding seeding · ${Fmt.speed(s.session.upSpeed)} up"
                s.transfers.isEmpty() -> "idle"
                else -> "${s.transfers.size} transfers"
            }
            val lead = dl.maxByOrNull { it.progress }
            return Sum(line, counts(s), more = s.transfers.isNotEmpty(), progress = lead?.progress)
        }

        fun counts(s: Snapshot?): String {
            s ?: return ""
            val parts = ArrayList<String>()
            val seeding = s.transfers.count { it.finished }
            val stopped = s.transfers.count { it.stopped }
            val errors = s.transfers.count { it.error }
            val week = s.transfers.count { it.underAWeek }
            if (seeding > 0) parts.add("$seeding seeding")
            if (stopped > 0) parts.add("$stopped stopped")
            if (errors > 0) parts.add("$errors errors")
            if (week > 0) parts.add("$week under a week")
            return parts.joinToString(" · ")
        }

        // ---- the description (the spec's §3.1; C§3.1, C§3.3: typed fields in their units) ----

        /** A transfer's state word as the glasses' row says it (`TorrentsWindow.stateWord`, the filter aside). */
        fun stateWord(t: Transfer): String = when {
            t.error -> "error"
            t.checking -> "checking"
            t.stopped -> "stopped"
            t.state == "metaDL" || t.state == "forcedMetaDL" -> "metadata"
            t.state == "queuedDL" || t.state == "queuedUP" -> "queued"
            t.state == "stalledDL" -> "stalled"
            t.downloading -> Fmt.speed(t.dlSpeed)
            t.finished -> "seeding"
            else -> t.state
        }

        /** The band (C§3.2): an error needs attention now, a stalled download needs a look. */
        fun band(t: Transfer): String = when {
            t.error -> "alarm"
            t.state == "stalledDL" -> "attention"
            else -> "normal"
        }

        /** qBittorrent's "no estimate": none, ≤ 0, or its infinite 8,640,000 s (`Fmt.eta`'s rule). */
        private fun etaMs(s: Long): Long? = if (s <= 0 || s >= 8_640_000) null else s * 1_000

        /** One transfer as the description's item: typed fields in C§3.3's units (ms, B/s, bytes, 0..1), its state
         *  with qBittorrent's own word beside the glasses' (`raw`, S1-2 R0 §3: the page's filters read it). */
        fun transferRow(t: Transfer): JsonObject = buildJsonObject {
            put("id", t.hash)
            put("kind", "transfer")
            put("title", t.name)
            put("fields", buildJsonObject {
                put("state", buildJsonObject { put("word", stateWord(t)); put("band", band(t)); put("raw", t.state) })
                put("progress", t.progress)
                put("size", t.size)
                put("downloaded", t.downloaded)
                put("uploaded", t.uploaded)
                put("dlSpeed", t.dlSpeed)
                put("upSpeed", t.upSpeed)
                val eta = etaMs(t.eta)
                if (eta == null) put("eta", JsonNull) else put("eta", eta)
                put("ratio", t.ratio)
                put("seeds", t.seeds)
                put("peers", t.peers)
                put("added", t.addedOn * 1_000)
                if (t.completedOn > 0) put("completed", t.completedOn * 1_000) else put("completed", JsonNull)
                put("seedingTime", t.seedingTime * 1_000)
                put("savePath", t.savePath)
                put("contentPath", t.contentPath)
                put("category", t.category)
                put("tags", t.tags)
                put("tracker", t.tracker)
            })
        }

        /** The description from [state] and the PC's [live] source (absent on the phone: the record's rows only). */
        fun describe(state: WindowState, live: TorrentsLive?): JsonObject = buildJsonObject {
            val s = live?.snapshot
            val line = live?.stateLine ?: ""
            put("id", WINDOW)
            put("name", "Torrents")
            put("icon", "torrents")
            put("min", Levels.VIEW)
            put("title", "transfers")
            when {
                live == null -> { put("availability", "away"); put("reason", "the transfer table is the PC's") }
                s == null -> { put("availability", "waiting"); put("reason", line.ifEmpty { "no answer from qBittorrent yet" }) }
                else -> put("availability", "live")
            }
            put("health", buildJsonObject {
                put("qbt", buildJsonObject {
                    put("state", if (line.isEmpty()) "ok" else line.removePrefix("qBittorrent "))
                    live?.qbtSince?.let { put("since", it) }
                })
                live?.tracker?.let { t -> put("tracker", buildJsonObject { put("state", t) }) }
            })
            val sum = summary(s, line)
            put("summary", buildJsonObject {
                put("line", sum.line)
                put("detail", sum.detail)
                sum.progress?.let { put("progress", it) }
                put("counts", buildJsonObject {
                    val ts = s?.transfers.orEmpty()
                    put("transfers", ts.size)
                    put("downloading", ts.count { it.downloading && !it.stopped })
                    put("seeding", ts.count { it.finished && !it.stopped })
                    put("stopped", ts.count { it.stopped })
                    put("errors", ts.count { it.error })
                    put("week", ts.count { it.underAWeek })
                })
            })
            put("epoch", s?.epoch ?: 0L)
            put("version", s?.version ?: 0L)
            put("atMs", s?.atMs ?: 0L)
            if (s != null) put("session", buildJsonObject {
                put("dlSpeed", s.session.dlSpeed)
                put("upSpeed", s.session.upSpeed)
                put("dlTotal", s.session.dlSession)
                put("upTotal", s.session.upSession)
                put("ratio", s.session.ratio)
                put("free", s.session.freeSpace)
                put("peers", s.session.peers)
                put("connection", s.session.status)
                put("version", s.session.version)
            })
            put("speeds", buildJsonArray {
                for (p in live?.speeds.orEmpty()) add(buildJsonObject { put("at", p.at); put("dl", p.dl); put("up", p.up) })
            })
            put("transfers", buildJsonArray { for (t in s?.transfers.orEmpty()) add(transferRow(t)) })
            put("actions", buildJsonArray {
                for (a in ACTION_LIST) add(buildJsonObject {
                    put("id", a.id); put("label", a.label); put("kind", a.kind); put("level", a.level)
                    if (a.into == null) put("into", JsonNull) else put("into", a.into)
                    put("targets", a.targets)
                    a.confirm?.let { put("confirm", it) }
                })
            })
            put("settings", settings(state))
        }

        /** The rows (C§6, the spec's §3.5): the Notify rows and Size from the record, Poll the device's own. */
        fun settings(state: WindowState): JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("id", NOTIFY_DONE); put("label", "Notify · done"); put("scope", "shared"); put("type", "bool")
                put("value", notifyDone(state) ?: true); put("level", Levels.CONTROL)
            })
            add(buildJsonObject {
                put("id", NOTIFY_ERRORS); put("label", "Notify · errors"); put("scope", "shared"); put("type", "bool")
                put("value", notifyErrors(state) ?: true); put("level", Levels.CONTROL)
            })
            add(buildJsonObject {
                put("id", "poll"); put("label", "Poll"); put("scope", "device"); put("type", "choice")
                put("options", buildJsonArray {
                    for ((label, ms) in TorrentsPaces.PACES) add(buildJsonObject { put("id", ms); put("label", label) })
                })
                put("value", TorrentsPaces.DEFAULT_MS); put("level", Levels.VIEW)
            })
            add(buildJsonObject {
                put("id", SIZE); put("label", "Size (glasses)"); put("scope", "glasses"); put("type", "choice")
                put("options", buildJsonArray {
                    add(buildJsonObject { put("id", 0); put("label", "global") })
                    for (h in wm.damage.core.shell.ShellSettings.HEIGHTS) add(buildJsonObject { put("id", h); put("label", "$h") })
                })
                put("value", size(state) ?: 0); put("level", Levels.CONTROL)
            })
        }

        /** Main's card (C§3.1 per window; the page's home): the summary the glasses' Main row shows. */
        fun card(live: TorrentsLive?): JsonObject = buildJsonObject {
            val s = live?.snapshot
            put("window", WINDOW)
            put("name", "Torrents")
            put("icon", "torrents")
            put("min", Levels.VIEW)
            put("availability", if (s == null) "waiting" else "live")
            if (s == null) put("reason", live?.stateLine?.ifEmpty { null } ?: "no answer from qBittorrent yet")
            val sum = summary(s, live?.stateLine ?: "")
            put("summary", buildJsonObject {
                put("line", sum.line)
                put("detail", sum.detail)
                sum.progress?.let { put("progress", it) }
            })
        }
    }
}

/** The glasses' and the page's Poll choices (TORRENTS.md §3.3: 1 s / 2 s / 5 s while focused; idle is always 15 s). */
object TorrentsPaces {
    val PACES: Map<String, Long> = linkedMapOf("1 s" to 1_000L, "2 s" to 2_000L, "5 s" to 5_000L)
    const val DEFAULT_MS = 2_000L
}
