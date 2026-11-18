package wm.damage.core.windows.tmux

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Change
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.ProgramHandler
import wm.damage.core.app.ProgramResult
import wm.damage.core.app.Record
import wm.damage.core.app.WindowState

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, pieces B9–B11 (`CONTRACT.md` §3.4, §8, §9.2, §12's Tmux row): Tmux's PC-served parts on the `app` lane,
 * made once per process by the PC's two host builders over the PC's provider. Every scope, source and op at level full.
 * - [table] (its own describe executor): `desc/tmux` — the description — `tmux/session:<host>:<name>` (the pane's size
 *   and screen, the session's windows) and `tmux/history:<host>:<name>` (the 1,000-line capture as colour runs).
 * - [live]: `live/tmux/frame/<host>/<name>` — the glasses' own capture, its lines as colour runs, while a viewer holds it
 *   (one more provider listener; one capture pace per PC, the glasses' Update row) — and `live/tmux/stream/<host>/<name>`
 *   — the control-mode stream ([TmuxStream]) for a desk's terminal.
 * - [program]: the keys, snippets, typed lines, raw bytes and session actions into tmux, never queued, on ONE thread
 *   ([pool]), so a connection's keys run in arrival order (the latest real input wins: tmux orders input by arrival).
 */
class TmuxScopes(
    private val host: AppHost,
    private val provider: TmuxProvider,
    /** The control-mode stream (null: this host serves no terminal stream). */
    private val stream: TmuxStream? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    tableOn: Executor? = null,
) : AutoCloseable {
    /** Tmux's core over this PC's live source. */
    val core = TmuxCore { live() }

    private val ownPools = ArrayList<ExecutorService>()
    private val tableEx: Executor = tableOn ?: pool("tmux-describe")
    /** The one thread every Tmux program op runs on (B9: keys in arrival order). */
    val pool: ExecutorService = pool("program-tmux")

    @Volatile private var sessions: List<TmuxSessionInfo> = emptyList()
    @Volatile private var cfg = TmuxConfig()
    @Volatile private var stateLine = ""
    @Volatile private var asOf = 0L
    private val tableListeners = CopyOnWriteArrayList<() -> Unit>()

    private fun pool(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }.also { ownPools += it }

    /** The PC's live source as the core reads it. */
    fun live(): TmuxLive = TmuxLive(sessions, cfg, provider.health(), stateLine, asOf)

    private val listener = object : TmuxProvider.Listener {
        override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) {
            asOf = clock()
            val moved = sessions != this@TmuxScopes.sessions || cfg != this@TmuxScopes.cfg
            this@TmuxScopes.sessions = sessions
            this@TmuxScopes.cfg = cfg
            if (moved) for (l in tableListeners) l()
        }

        override fun state(state: String) {
            if (state == stateLine) return
            stateLine = state
            for (l in tableListeners) l()
        }

        override fun frame(target: TmuxTarget, frame: PaneFrame) {}
        override fun alert(session: TmuxSessionInfo) {}
    }

    /** At a process start (both PC host builders): listens to the provider's status and state line. */
    fun start() {
        provider.addListener(listener)
    }

    override fun close() {
        provider.removeListener(listener)
        for (k in frames.keys.toList()) frames.remove(k)?.let { stop(it) }
        stream?.close()
        for (p in ownPools) p.shutdown()
    }

    private fun state(): WindowState {
        val (_, _, e) = host.read(TmuxCore.WINDOW)
        return WindowState(TmuxCore.WINDOW, e.items)
    }

    // ---- the described scopes ----

    val table: AppNet.Described = object : AppNet.Described {
        override fun gate(scope: String): AppNet.Gate? =
            if (scope == DESC || scope.startsWith("${TmuxCore.WINDOW}/")) AppNet.Gate(TmuxCore.WINDOW, Levels.FULL) else null

        override fun describe(scope: String): JsonObject? = when {
            scope == DESC -> core.describe(state())
            scope.startsWith(SESSION) -> targetOf(scope.removePrefix(SESSION))?.let { canvas(it) }
            scope.startsWith(HISTORY) -> targetOf(scope.removePrefix(HISTORY))?.let { history(it) }
            else -> null
        }

        /** The description follows the status, the state line and the record's Tmux rows; a session's canvas and its
         *  history are one answer each (a page asks again). */
        override fun follows(scope: String, c: Change?): Boolean =
            scope == DESC && (c == null || c.effect.items.keys.any { it.startsWith(Record.prefix(TmuxCore.WINDOW)) })

        override fun addListener(l: () -> Unit) {
            tableListeners += l
        }

        override val describeOn: Executor get() = tableEx
    }

    private fun canvas(t: TmuxTarget): JsonObject {
        val f = provider.capture(t)
        return buildJsonObject {
            put("cols", f.cols)
            put("rows", f.rows)
            put("alternate", f.alternate)
            put("windows", buildJsonArray {
                for (w in provider.windows(t)) add(buildJsonObject {
                    put("idx", w.idx); put("name", w.name); put("active", w.active); put("bell", w.bell)
                })
            })
        }
    }

    private fun history(t: TmuxTarget): JsonObject {
        val lines = provider.history(t, TmuxGlasses.HISTORY_LINES)
        return buildJsonObject {
            put("capturedAt", clock())
            put("lines", buildJsonArray { for (l in lines) add(runs(l)) })
        }
    }

    // ---- program ops (B9 "Keys as ops") ----

    /** Each op into tmux on [pool]: Sent when tmux took it, Refused in tmux's own words when it did not. */
    val program = ProgramHandler { op, _ -> act(op) }

    private fun act(op: Op): ProgramResult {
        val one = op.targets.singleOrNull() ?: return ProgramResult.Refused("${op.action} names one target")
        if (op.action == TmuxCore.NEW) {
            val name = provider.newSession(one)
            return ProgramResult.Sent(buildJsonObject { put("name", name) })
        }
        val (h, n) = TmuxCore.sessionOf(one) ?: return ProgramResult.Refused("${op.action} names a session as session:<host>:<name>")
        val t = TmuxTarget(h, n)
        fun text(k: String): String? = (op.params[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun need(k: String) = ProgramResult.Refused("${op.action} carries its $k in params.$k")
        when (op.action) {
            TmuxCore.KEY -> provider.sendKeys(t, listOf(text("key") ?: return need("key")))
            TmuxCore.SNIPPET, TmuxCore.LINE -> {
                val s = text(if (op.action == TmuxCore.SNIPPET) "snippet" else "text")
                    ?: return need(if (op.action == TmuxCore.SNIPPET) "snippet" else "text")
                provider.sendLiteral(t, s)
                provider.sendKeys(t, listOf("Enter"))             // always runs (G2CC, Adam 2026-06-18)
            }
            TmuxCore.RAW -> provider.sendRaw(t, text("data") ?: return need("data"))
            TmuxCore.RENAME -> {
                val typed = text("text") ?: return need("text")
                provider.renameSession(t, typed)
                val to = TmuxNames.clean(typed) ?: typed
                return ProgramResult.Sent(buildJsonObject {
                    put("renamed", buildJsonObject { put("from", one); put("to", TmuxCore.sessionTarget(h, to)) })
                })
            }
            TmuxCore.END -> provider.killSession(t)
            TmuxCore.SELECT -> provider.selectWindow(t, (op.params["window"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: return need("window"))
            else -> return ProgramResult.Refused("Tmux has no program action '${op.action}'", wm.damage.core.app.Answer.UNSUPPORTED)
        }
        return ProgramResult.Sent()
    }

    /** Main's card (the registry's running half; `AppNet.serve`). */
    fun card(): JsonObject = TmuxCore.card(live())

    // ---- the live sources (B10, B11) ----

    /** One frame viewer: a provider listener of its own on one target while it holds focus; its first frame is the
     *  `init` (a subscriber is pushed a frame at once), the rest are `frame`s. */
    private class FrameViewer(val target: TmuxTarget, val send: (JsonObject) -> Unit) : TmuxProvider.Listener {
        private var started = false
        override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) {}
        override fun state(state: String) {}
        override fun alert(session: TmuxSessionInfo) {}
        override fun frame(target: TmuxTarget, frame: PaneFrame) {
            if (target != this.target) return
            val kind = synchronized(this) { (if (started) "frame" else "init").also { started = true } }
            send(buildJsonObject { put(kind, frameJson(frame)) })
        }
    }

    private val frames = ConcurrentHashMap<Pair<Long, String>, FrameViewer>()

    private fun stop(v: FrameViewer) {
        provider.subscribe(v, null)
        provider.removeListener(v)
    }

    val live: AppNet.Live = object : AppNet.Live {
        override fun gate(scope: String): AppNet.Gate? =
            if (sourceOf(scope) != null) AppNet.Gate(TmuxCore.WINDOW, Levels.FULL) else null

        override fun attach(scope: String, conn: Long, send: (JsonObject) -> Unit) {
            val (kind, t) = sourceOf(scope) ?: throw IllegalArgumentException("$scope names no Tmux source")
            if (kind == FRAME) {
                val v = FrameViewer(t, send)
                frames.put(conn to scope, v)?.let { stop(it) }
                provider.addListener(v)
                provider.subscribe(v, t)
            } else {
                val s = stream ?: throw IllegalStateException("this host runs no terminal stream")
                s.attach(t.host, t.session, conn, send)
            }
        }

        override fun detach(scope: String, conn: Long) {
            val (kind, t) = sourceOf(scope) ?: return
            if (kind == FRAME) frames.remove(conn to scope)?.let { stop(it) }
            else stream?.detach(t.host, t.session, conn)
        }
    }

    companion object {
        const val DESC = "desc/tmux"
        const val SESSION = "tmux/session:"
        const val HISTORY = "tmux/history:"
        const val FRAME = "frame"
        const val STREAM = "stream"
        private const val LIVE_TMUX = "${AppWire.LIVE}tmux/"

        /** `<host>:<name>` (the last colon splits it) as a target. */
        fun targetOf(spec: String): TmuxTarget? = TmuxCore.sessionOf("session:$spec")?.let { (h, n) -> TmuxTarget(h, n) }

        /** `live/tmux/<frame|stream>/<host>/<name>` → (kind, target); a host label holds no `/`. */
        fun sourceOf(scope: String): Pair<String, TmuxTarget>? {
            val rest = scope.takeIf { it.startsWith(LIVE_TMUX) }?.removePrefix(LIVE_TMUX) ?: return null
            val kind = rest.substringBefore('/')
            if (kind != FRAME && kind != STREAM) return null
            val hn = rest.substringAfter('/', "")
            val cut = hn.indexOf('/')
            if (cut <= 0 || cut == hn.length - 1) return null
            return kind to TmuxTarget(hn.substring(0, cut), hn.substring(cut + 1))
        }

        /** A frame as the page draws it: its lines as colour runs (a normal pane's logical, a full-screen one's
         *  row-exact), the pane's size, the cursor, and when the PC took it. */
        fun frameJson(f: PaneFrame): JsonObject = buildJsonObject {
            put("lines", buildJsonArray { for (l in f.lines) add(runs(l)) })
            put("cols", f.cols)
            put("rows", f.rows)
            put("cursor", buildJsonObject { put("x", f.cursorX); put("y", f.cursorY); put("visible", f.cursorVisible) })
            put("alternate", f.alternate)
            put("capturedAt", f.capturedAtMs)
        }

        /** One captured line as runs `{t, fg?, bg?, fl?}`: a colour an xterm index or `#rrggbb`, absent = default. */
        fun runs(line: String): JsonArray = buildJsonArray {
            for (r in Sgr.colourRuns(line)) add(buildJsonObject {
                put("t", r.text)
                colour(r.fg)?.let { put("fg", it) }
                colour(r.bg)?.let { put("bg", it) }
                if (r.flags != 0) put("fl", r.flags)
            })
        }

        private fun colour(c: Int): JsonPrimitive? = when {
            c == Sgr.DEFAULT_COLOUR -> null
            c and Sgr.RGB != 0 -> JsonPrimitive("#%06x".format(c and 0xFFFFFF))
            else -> JsonPrimitive(c)
        }
    }
}
