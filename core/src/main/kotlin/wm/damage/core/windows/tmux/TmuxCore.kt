package wm.damage.core.windows.tmux

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import wm.damage.core.app.Kind
import wm.damage.core.app.Levels
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.Record
import wm.damage.core.app.WindowCore
import wm.damage.core.app.WindowState
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, pieces B8 and B9 (`CONTRACT.md` §2.1, §3.4, §4, §7, §12's Tmux row; `TMUX.md`): Tmux's core. Its record is
 * four kinds of item — `alerts` (shared, absent = on), `mute.<host>/<name>` (bool), `glasses.size` and
 * `glasses.context` — and nothing else: the sessions, windows, each host's health and the configured keys are the
 * provider's (source state, served by the PC); frames, the control-mode stream and the history are live or one-shot
 * (`TmuxScopes`). The core names every action with its kind and level (all full: the window's minimum is full), builds
 * the description from the record and the PC's live source, and raises a waiting session's alert ONCE, on the PC, into
 * the notice record ([attachAlerts]), today's body and thread, so the box on the glasses is today's. No pixels here.
 */

/** What the PC holds of Tmux beside the record (`TmuxScopes` keeps it): the last status, each host's health, the
 *  provider's state line, and when the PC last heard a status ([asOf], its clock). */
data class TmuxLive(
    val sessions: List<TmuxSessionInfo>,
    val cfg: TmuxConfig,
    val hosts: List<TmuxHostHealth>,
    val stateLine: String,
    val asOf: Long,
)

/** Tmux's core. [live] is the PC's source (null on the phone, whose presenter reads its provider as today). */
class TmuxCore(private val live: () -> TmuxLive? = { null }) : WindowCore {
    override val window: String = WINDOW

    override fun describe(state: WindowState): JsonObject = describe(state, live())

    /** `session:<host>:<name>` names a session of the PC's last status. */
    override fun level(state: WindowState, addr: String): JsonObject? {
        val (host, name) = sessionOf(addr) ?: return null
        val s = live()?.sessions?.firstOrNull { it.host == host && it.name == name } ?: return null
        return sessionRow(s, muted(state, host, name))
    }

    // ---- the alerts (B9 "Alerts, once") ----

    private val alertLock = Any()
    @Volatile private var attachedTo: TmuxProvider? = null
    /** The hosts the last status listed sessions on: today's rule for the body's ` · host` (more than one). */
    @Volatile private var hostsSeen: Set<String> = emptySet()

    private val alerts = object : TmuxProvider.Listener {
        override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) {
            hostsSeen = sessions.mapTo(HashSet()) { it.host }
        }
        override fun frame(target: TmuxTarget, frame: PaneFrame) {}
        override fun state(state: String) {}
        override fun alert(session: TmuxSessionInfo) = raise(session)
    }

    private var notices: NoticeCore? = null
    private var stateOf: () -> WindowState = { WindowState.empty(WINDOW) }

    /**
     * At a PROCESS START, in the PC's two host builders only (never per stack): a waiting edge of [provider]'s becomes
     * one notice in the record — the mute read once from [state] (the PC record), Alerts read once by [notices] at the
     * raise (absent = on). Nothing clears it: it stays until dismissed, and the next waiting edge is the next occurrence.
     */
    fun attachAlerts(provider: TmuxProvider, notices: NoticeCore, state: () -> WindowState) {
        synchronized(alertLock) {
            check(attachedTo == null) { "Tmux's alerts are attached once per process" }
            attachedTo = provider
            this.notices = notices
            stateOf = state
            provider.addListener(alerts)
        }
    }

    /** At a process end (tests): the provider is no longer listened to. */
    fun detachAlerts() {
        synchronized(alertLock) {
            attachedTo?.removeListener(alerts)
            attachedTo = null
        }
    }

    private fun raise(s: TmuxSessionInfo) {
        val n = notices ?: return
        try {
            if (muted(stateOf(), s.host, s.name)) {
                Log.i(TAG, "${s.host}/${s.name} waits for input; muted, so no alert")
                return
            }
            n.raise(NOTICE_SOURCE, threadOf(s.host, s.name), alertBody(s, hostsSeen.size > 1), WINDOW,
                sessionTarget(s.host, s.name), GATED_BY, notifyRow = Record.key(WINDOW, ALERTS))
        } catch (e: Exception) {
            // said, never dropped unsaid
            Log.e(TAG, "the alert of ${s.host}/${s.name} could not be raised", e)
        }
    }

    companion object {
        private const val TAG = "tmux"
        const val WINDOW = "tmux"

        // the record items (B8)
        const val ALERTS = "alerts"
        const val MUTE = "mute."
        /** The glasses' Tmux height: 0 = the Global height, else one of the four sizes; absent = 480 (TMUX.md §3.3). */
        const val SIZE = "glasses.size"
        const val CONTEXT = "glasses.context"
        const val SIZE_DEFAULT = 480

        /** The notice's source and category: today's (`DAMAGE · tmux`, gated by Settings → Tmux). */
        const val NOTICE_SOURCE = "tmux"
        const val GATED_BY = "Tmux"

        // the actions (B9, B16): the program ops into tmux, never queued, and the record's
        const val KEY = "key"
        const val SNIPPET = "snippet"
        const val LINE = "line"
        const val RAW = "raw"
        const val NEW = "new"
        const val RENAME = "rename"
        const val END = "end"
        const val SELECT = "select"
        const val MUTE_ACTION = "mute"
        const val SET = "set"
        /** A register's value from before Tmux's conversion, stamped with its old time (B15). */
        const val MIGRATE = "migrate"

        val PROGRAM: Set<String> = setOf(KEY, SNIPPET, LINE, RAW, NEW, RENAME, END, SELECT)

        /** Every action at level full (the window's minimum is full; part G (1): every device at full may type). */
        fun levelOf(action: String): String? = if (action in PROGRAM || action == MUTE_ACTION || action == SET || action == MIGRATE)
            Levels.FULL else null

        val ACTIONS = ActionTable { w, a ->
            if (w != WINDOW) null else when (a) {
                MUTE_ACTION, SET -> ActionSpec(Kind.REGISTER)
                MIGRATE -> ActionSpec(Kind.POSITION)
                else -> null
            }
        }

        data class ActionDesc(val id: String, val label: String, val kind: String, val into: String, val targets: String,
                              val confirm: Boolean = false)

        val ACTION_LIST: List<ActionDesc> = listOf(
            ActionDesc(KEY, "Send a key", "r", "program", "one"),
            ActionDesc(SNIPPET, "Send a snippet", "r", "program", "one"),
            ActionDesc(LINE, "Run it (sends + Enter)", "o", "program", "one", confirm = true),
            ActionDesc(RAW, "Type", "r", "program", "one"),
            ActionDesc(NEW, "New session", "o", "program", "one"),
            ActionDesc(RENAME, "Rename", "o", "program", "one"),
            ActionDesc(END, "End session", "i", "program", "one", confirm = true),
            ActionDesc(SELECT, "Select window", "r", "program", "one"),
            ActionDesc(MUTE_ACTION, "Mute alerts", "register", "record", "one"),
            ActionDesc(SET, "Set a row", "register", "record", "one"),
        )

        // ---- names ----

        fun muteName(host: String, name: String) = "$MUTE$host/$name"
        fun threadOf(host: String, name: String) = "$host/$name"
        fun sessionTarget(host: String, name: String) = "session:$host:$name"

        /** `session:<host>:<name>` → (host, name); a tmux session name holds no `:`, so the LAST colon splits it. */
        fun sessionOf(addr: String): Pair<String, String>? {
            val spec = addr.removePrefix("session:").takeIf { it != addr } ?: return null
            val cut = spec.lastIndexOf(':')
            if (cut <= 0 || cut == spec.length - 1) return null
            return spec.substring(0, cut) to spec.substring(cut + 1)
        }

        /** Today's alert body (`TmuxWindow`'s raise): the pane's whole line; the box wraps and scrolls it. */
        fun alertBody(s: TmuxSessionInfo, manyHosts: Boolean): String =
            "${s.name}${if (manyHosts) " · ${s.host}" else ""} wants input: ${s.lastLine}"

        // ---- the record's items, read ----

        private fun item(state: WindowState, name: String) = state.itemNamed(name)?.takeIf { !it.gone }

        fun bool(v: JsonElement?): Boolean? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        /** Alerts: on unless the record holds false (TMUX.md verdict 3: on for all, with per-session mutes). */
        fun alertsOn(state: WindowState): Boolean = bool(item(state, ALERTS)?.value) ?: true

        fun muted(state: WindowState, host: String, name: String): Boolean = bool(item(state, muteName(host, name))?.value) ?: false

        /** Every muted session's `host/name`. */
        fun mutedSet(state: WindowState): Set<String> {
            val p = Record.key(WINDOW, MUTE)
            return state.items.filter { (k, v) -> k.startsWith(p) && !v.gone && bool(v.value) == true }
                .keys.mapTo(LinkedHashSet()) { it.removePrefix(p) }
        }

        fun context(state: WindowState): Boolean = bool(item(state, CONTEXT)?.value) ?: true

        /** The glasses' height: 0 (global) or one of the four sizes; absent = 480. */
        fun size(state: WindowState): Int = sizeOf(item(state, SIZE)?.value) ?: SIZE_DEFAULT

        fun sizeOf(v: JsonElement?): Int? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?.takeIf { it == 0 || it in wm.damage.core.shell.ShellSettings.HEIGHTS }

        // ---- the drafts a presenter sends (C§2.4: the result; the latest input wins) ----

        fun muteDraft(host: String, name: String, on: Boolean) = Draft(MUTE_ACTION, listOf(muteName(host, name)), JsonPrimitive(on))

        fun setDraft(row: String, value: JsonElement) = Draft(SET, listOf(row), value)

        // ---- the summary (moved from `TmuxWindow.summary()`: Main's row and the web's card share its words) ----

        data class Sum(val line: String, val detail: String, val more: Boolean)

        fun summary(sessions: List<TmuxSessionInfo>, stateLine: String): Sum {
            val waiting = sessions.filter { it.waiting }
            return Sum(
                line = when {
                    sessions.isEmpty() -> if (stateLine.isEmpty()) "no sessions" else stateLine
                    waiting.isEmpty() -> "${sessions.size} session${if (sessions.size == 1) "" else "s"}"
                    else -> "${waiting.joinToString(", ") { it.name }} waiting"
                },
                detail = if (stateLine.isEmpty()) "" else "! $stateLine",
                more = waiting.isNotEmpty(),
            )
        }

        // ---- the description (B9; C§3.1, C§3.3: typed fields in their units) ----

        fun sessionRow(s: TmuxSessionInfo, muted: Boolean): JsonObject = buildJsonObject {
            put("host", s.host)
            put("name", s.name)
            put("windows", s.windows)
            put("attached", s.attached)
            put("activity", s.activity * 1_000)       // the PC's ms (tmux gives unix seconds)
            put("waiting", s.waiting)
            put("lastLine", s.lastLine)
            put("muted", muted)
        }

        fun describe(state: WindowState, live: TmuxLive?): JsonObject = buildJsonObject {
            put("id", WINDOW)
            put("name", "Tmux")
            put("icon", "terminal")
            put("min", Levels.FULL)
            put("title", "sessions")
            if (live == null) { put("availability", "away"); put("reason", "the sessions are the PC's") }
            else put("availability", "live")
            live?.let { put("asOf", it.asOf) }
            val sum = summary(live?.sessions.orEmpty(), live?.stateLine ?: "")
            put("summary", buildJsonObject { put("line", sum.line); put("detail", sum.detail); put("more", sum.more) })
            put("sessions", buildJsonArray {
                for (s in live?.sessions.orEmpty()) add(sessionRow(s, muted(state, s.host, s.name)))
            })
            put("hosts", buildJsonArray {
                for (h in live?.hosts.orEmpty()) add(buildJsonObject {
                    put("name", h.name)
                    put("health", buildJsonObject {
                        put("state", h.state)
                        h.error?.let { put("error", it) }
                        put("lastOkMs", h.lastOkMs)
                    })
                })
            })
            val cfg = live?.cfg
            put("keys", buildJsonObject {
                put("quick", JsonArray(cfg?.quickKeys.orEmpty().map { JsonPrimitive(it) }))
                put("snippets", JsonArray(cfg?.snippets.orEmpty().map { JsonPrimitive(it) }))
            })
            put("actions", buildJsonArray {
                for (a in ACTION_LIST) add(buildJsonObject {
                    put("id", a.id); put("label", a.label); put("kind", a.kind); put("level", levelOf(a.id) ?: Levels.FULL)
                    put("into", a.into); put("targets", a.targets)
                    if (a.confirm) put("confirm", JsonObject(emptyMap()))
                })
            })
            put("settings", settings(state))
        }

        /** The rows (C§6): Alerts shared; Context rows and Size the glasses'. The glasses' Update row stays theirs. */
        fun settings(state: WindowState): JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("id", ALERTS); put("label", "Alerts"); put("scope", "shared"); put("type", "bool")
                put("value", alertsOn(state)); put("level", Levels.FULL)
            })
            add(buildJsonObject {
                put("id", CONTEXT); put("label", "Context rows"); put("scope", "glasses"); put("type", "bool")
                put("value", context(state)); put("level", Levels.FULL)
            })
            add(buildJsonObject {
                put("id", SIZE); put("label", "Size"); put("scope", "glasses"); put("type", "choice")
                put("options", buildJsonArray {
                    add(buildJsonObject { put("id", 0); put("label", "global") })
                    for (h in wm.damage.core.shell.ShellSettings.HEIGHTS) add(buildJsonObject { put("id", h); put("label", "$h") })
                })
                put("value", size(state)); put("level", Levels.FULL)
            })
        }

        /** Main's card: the summary the glasses' Main row shows. */
        fun card(live: TmuxLive?): JsonObject = buildJsonObject {
            put("window", WINDOW)
            put("name", "Tmux")
            put("icon", "terminal")
            put("min", Levels.FULL)
            put("availability", if (live == null) "away" else "live")
            val sum = summary(live?.sessions.orEmpty(), live?.stateLine ?: "")
            put("summary", buildJsonObject { put("line", sum.line); put("detail", sum.detail); put("more", sum.more) })
        }
    }
}
