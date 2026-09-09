package wm.damage.core.shell

import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.comp.CachedText
import wm.damage.core.comp.CanvasShift
import wm.damage.core.comp.GlyphAtlas
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.LinkState
import wm.damage.core.comp.Compositor
import wm.damage.core.comp.Journal
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Level
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.Arm
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.util.Log
import wm.damage.core.wire.EvenHubMsg

/**
 * The window manager itself: input grammar (§1), the back stack, focus, the
 * surface stack (Main / windows / switcher / notifications / silent), the flush
 * pump, persistence, and error surfacing. One instance == one shell; it runs
 * identically on the desktop and inside the APK (§10.1).
 *
 * Threading: a single event loop (one coroutine on [scope]); everything enters
 * through [post] — including background completions, via
 * [ShellServices.runOnShell]. Compositor state is loop-confined; transport
 * completions arrive as messages. Scheduled UI transitions (grace periods,
 * idle ticks, the minute clock) are exactly that — scheduled UI state changes,
 * which NO TIMEOUTS explicitly permits; no operation is ever time-bounded.
 */
class Shell(
    private val text: TextRasterizer,
    private val transport: Transport,
    private val persistence: Persistence,
    journalPath: Path?,
    private val scope: CoroutineScope,
    private val wallClock: () -> LocalClock = { systemClock() },
) {
    data class LocalClock(val hh: Int, val mm: Int, val hhmm: String, val amPm: String)

    // ------------------------------------------------------------------ state
    var settings: ShellSettings = ShellSettings()
        private set
    var layout = Layout()
        private set
    val comp = Compositor()
    private val kit = ContentKit(comp)

    /** Chrome + Main + the shell overlays draw through the GLOBAL style
     *  (Style.kt, 2026-08-31 — Adam's reversal of the fixed-system-face
     *  rule): face swap for SYSTEM specs, the global scale, the global style
     *  force. Windows carry their own per-app transform instead. */
    private val chromeText = wm.damage.core.text.StyledText(text) { spec ->
        wm.damage.core.text.StyleTransform(
            face = wm.damage.core.text.Faces.byLabel(settings.fontFace),
            systemOnly = true,
            scale = chromeScale(),
            bold = when (settings.fontStyle) { "bold" -> true; "regular", "italic" -> false; else -> null },
            italic = when (settings.fontStyle) { "italic" -> true; "regular", "bold" -> false; else -> null },
        ).apply(spec)
    }
    /**
     * The global font scale AS FAR AS THE BARS CAN TAKE IT.
     *
     * §4.2's scale ladder reaches 130 % and scales chrome too (Adam's ask,
     * 2026-08-31) — but §2.3's bars are a fixed 32 px and 28 px, and a chrome
     * line whose MEASURED ink is taller than its bar draws outside the only
     * rect that paint damages: at 130 % the title ran into the divider, and
     * at a reduced height the status line's descenders landed below the safe
     * rect where nothing ever repaints them (review 2026-09-05). So the
     * chrome grows until its bar is full and then stops; CONTENT keeps the
     * full ladder. Measured against the real rasterizer and the chosen face,
     * cached per (face, scale).
     */
    private var chromeScaleKey: Pair<String, Double>? = null
    private var chromeScaleValue = 1.0
    private fun chromeScale(): Double {
        val key = settings.fontFace to settings.fontScale
        if (chromeScaleKey == key) return chromeScaleValue
        val face = wm.damage.core.text.Faces.byLabel(settings.fontFace) ?: Face.SYSTEM
        // the tallest chrome line is the 16 px title/status face; the shortest
        // bar is the status bar
        val room = Layout.STATUS_H
        var s = settings.fontScale
        while (s > CHROME_SCALE_FLOOR) {
            val px = maxOf(6, Math.round(16 * s).toInt())
            val m = text.metrics(FontSpec(face, px, bold = true))
            if (m.ascent + m.descent <= room) break
            s -= 0.05
        }
        // the step walk can undershoot by one step in floating point; the
        // chrome never renders below its design size
        chromeScaleKey = key
        chromeScaleValue = s.coerceIn(CHROME_SCALE_FLOOR, maxOf(CHROME_SCALE_FLOOR, settings.fontScale))
        return chromeScaleValue
    }

    private val chrome = Chrome(chromeText, { iconSource })

    /**
     * The clock cell's width for the CURRENT chrome face, cached the way
     * [chromeScale] is (review §30). §2.3's 80 px is the floor and holds the
     * widest `h:mm` plus its AM/PM marker at 100 % exactly; a step up the
     * ladder needs more, and without it the time ran into the marker.
     */
    private var clockWKey: Triple<String, Double, String>? = null
    private var clockWValue = Layout.CLOCK_W
    private fun clockCellW(): Int {
        val key = Triple(settings.fontFace, settings.fontScale, settings.fontStyle)
        if (clockWKey == key) return clockWValue
        clockWValue = chrome.clockCellWidth()
        clockWKey = key
        return clockWValue
    }

    /** Silent-mode paint plumbing (§1.5 sizes, 2026-09-01): the "small" size
     *  is the title bar clock's own text at its own cell, drawn by chrome. */
    private fun silentSmallPainter(c: LocalClock): (wm.damage.core.gfx.Gray8, wm.damage.core.geom.Rect) -> Unit =
        { g, r -> chrome.paintClockText(g, r, c.hhmm, c.amPm) }
    private val journal = Journal(journalPath)

    /** A fact for this host's journal from outside the loop — the keeper's
     *  transitions (§42: a start that fails before its `build` note, a link
     *  end, the pacing between attempts, the reason each carried). The
     *  journal reopens on write, so a stopped shell's keeper still lands. */
    fun journalNote(kind: String, detail: String) = journal.note(kind, detail)
    val notifications = Notifications(chromeText)
    private val switcher = Switcher(chromeText, { iconSource })
    private val menu = MenuSurface(chromeText)
    /** §4.8 the keyboard (2026-09-01) — routed like the menu while open. */
    private val keyboard = KeyboardSurface(chromeText)

    /** The host's theme-icon source (2026-09-01) — set before start; every
     *  icon call site falls back to the drawn set when null or on a miss. */
    @Volatile var iconSource: wm.damage.core.gfx.IconSource? = null

    /** Test/introspection: is the floating context menu open, and whose? */
    val menuIsOpen: Boolean get() = menu.open
    /** Test/introspection: the status cell's text ("ok", "ERROR …", "DIVERGE …"). */
    val statusLine: String get() = statusText
    val switcherIsOpen: Boolean get() = switcher.open
    /** How many rows the wheel is showing — a harness reads it so a pin that
     *  needs a real spin cannot silently run against a one-entry drum. */
    val switcherEntryCount: Int get() = switcher.entryCount
    val menuTitle: String? get() = menu.current()?.title

    /** The open menu's row labels, and where its cursor rests — test/harness
     *  reach so a script can select a row BY NAME instead of counting notches
     *  (2026-09-03: adding one row silently broke five tests that counted). */
    val menuLabels: List<String> get() = menu.current()?.items?.map { it.label } ?: emptyList()
    /** The labels of the menu rows that can actually DO something — a harness
     *  asking "does this row work" must not have to read pixels (review §30). */
    val menuEnabled: List<String> get() =
        menu.current()?.items?.filter { it.enabled }?.map { it.label } ?: emptyList()
    /** The open menu's DETAIL column — a row's warning often lives here rather
     *  than in its label (the Games refill confirm, review pass 3). */
    val menuDetails: List<String> get() = menu.current()?.items?.map { it.detail } ?: emptyList()
    val menuCursor: Int get() = menu.cursor
    val keyboardIsOpen: Boolean get() = keyboard.open
    val keyboardTitle: String? get() = keyboard.current()?.title
    fun keyboardDraft(): String = keyboard.draft

    /** An async icon resolve completed: repaint so the theme bitmap replaces
     *  the drawn fallback (posted by the host's IconSource hook). */
    fun requestRepaint() {
        post(Msg.Run {
            chrome.invalidate()
            chromeDirty = true
            if (mode == Mode.EXCLUSIVE) paintExclusiveDelta()     // the window decides what an icon changed
            else if (mode != Mode.SILENT) composeContent()
        })
    }

    private val windows = ArrayList<DamageWindow>()
    private val recency = ArrayList<DamageWindow>()          // most recent first
    private lateinit var settingsWindow: SettingsWindow
    private val main = MainSurface(chromeText, { mainRows() }, { commitWindow(it, ActivationSource.MAIN) }, { settings.presence }, { iconSource })

    /** EXCLUSIVE (§4.9, 2026-09-02 — Music Mode): the focused window paints the
     *  whole panel; input is swallowed except double-tap (silent's path). */
    private enum class Mode { MAIN, WINDOW, SILENT, EXCLUSIVE }
    /** The two quiet modes share the notification form and the input law. */
    private val quiet: Boolean get() = mode == Mode.SILENT || mode == Mode.EXCLUSIVE
    private var mode = Mode.MAIN
    private var current: DamageWindow? = null

    private var slides: List<Slide> = emptyList()

    private var inputEcho = ""
    private var opText = "idle"
    private var statusText = "ok"
    /** Stamps the minute/idle maintenance loops to ONE shell session. The
     *  keeper stops and restarts this shell all day (WiFi edges, handovers);
     *  a loop that only checked `running` would come back from its sleep to
     *  find the NEXT session running and keep going — every restart added a
     *  loop, accumulating wakeups forever on the all-day driver. A stale
     *  generation exits instead. */
    @Volatile private var tickGen = 0
    private var restGen = 0
    private var graceGen = 0
    private var silentDismissGen = 0
    /** §1.3 chord (long-press OFF): event 9 arms this wall-clock instant,
     *  event 10 refreshes it, a double-tap inside [chordWindowMs] opens the
     *  switcher; 0 = not armed. */
    private var chordArmedAtMs = 0L
    /** The chord's sequence window — a §4.5-grace-style UI timing, not a
     *  deadline on any wait. First-light item 18 checks it feels right. */
    private val chordWindowMs = 800L
    private var saveGen = 0
    private var previewPainted: String? = null
    @Volatile private var running = false
    @Volatile private var loopLaunched = false
    private var collectorLaunched = false

    /** Host-link status line for the status bar (set by the content client). */
    @Volatile var hostState: String = ""

    /** The host's build, as the journal records it at every session start
     *  (§41): the APK's version or the desktop's git stamp — a journal read
     *  a week later says which tree wrote it. Set by the host before start. */
    @Volatile var buildTag: String = ""

    /** Rows the host appends to Settings (the display target); set before start. */
    @Volatile var hostSettings: List<HostSetting> = emptyList()

    /** §9.3: the phone is the out-of-band error channel — urgent internal
     *  notices also fire this hook (the APK raises a real phone notification). */
    @Volatile var onUrgent: ((source: String, body: String) -> Unit)? = null

    private val inflightFlushes = HashMap<Long, Compositor.Assembled>()
    private var flushFailStreak = 0
    private var chromeDirty = true
    private var chromeIdleFlush = false
    private val msgs = Channel<Msg>(Channel.UNLIMITED)
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)

    private sealed class Msg {
        data class In(val type: Int, val source: Int) : Msg()
        data class Trans(val ev: TransportEvent) : Msg()
        object MinuteTick : Msg()
        object IdleTick : Msg()
        data class RestTick(val gen: Int) : Msg()
        data class GraceTick(val gen: Int) : Msg()
        data class SilentTick(val gen: Int) : Msg()
        data class SaveTick(val gen: Int) : Msg()
        data class Notice(val n: Notifications.Notice) : Msg()
        data class Invalidate(val windowId: String?) : Msg()
        /** [dropped] runs INSTEAD of [action] when the message reaches a
         *  stopped loop: a caller awaiting an answer must get one either way,
         *  or it waits for ever (review §30 — `sampleIdle`'s own hazard). */
        data class Run(val dropped: (() -> Unit)? = null, val action: () -> Unit) : Msg()
        data class Shutdown(val done: CompletableDeferred<Unit>) : Msg()
        object Pump : Msg()
    }

    val services: ShellServices = object : ShellServices {
        override fun requestRender(window: DamageWindow) { post(Msg.Invalidate(window.id)) }

        override fun setOperation(op: String) {
            post(Msg.Run { if (opText != op) { opText = op; chromeDirty = true } })
        }

        override fun notifyInternal(source: String, body: String, urgent: Boolean,
            appId: String?, thread: String, target: String?) {
            if (urgent) onUrgent?.invoke(source, body)
            val c = wallClock()
            post(Msg.Notice(Notifications.Notice("DAMAGE · $source", thread.ifEmpty { source },
                body, c.hhmm, emergency = false, appId = appId, target = target)))
        }

        override fun openMenu(spec: MenuSurface.Spec, owner: DamageWindow?): Boolean {
            // LOOP-ONLY (§16.11): callers are commit handlers, already on the loop
            if (mode != Mode.WINDOW || switcher.open) {
                Log.w("shell", "openMenu refused: mode=$mode switcher=${switcher.open}")
                return false
            }
            // an async completion may land after the user moved to another
            // window: its menu must not open over someone else's content
            // (R2#8) — the false return tells the caller to say it another way
            if (owner != null && owner !== current) {
                Log.i("shell", "openMenu refused: '${owner.id}' is not the focused window")
                return false
            }
            // a menu already open (an async Stats landing over a re-opened
            // entry menu) is closed PROPERLY first — restore + onClose — or
            // its pixels become the new menu's "under" and get painted back
            // on cancel (review 2026-09-01 F1)
            // the keyboard is a modal input surface (§4.8): an async menu
            // landing over it would disturb typing — refuse, the caller says
            // it as a notice. A requester's own menu (Files' rename confirm)
            // runs from the keyboard's commit, AFTER it closed
            if (keyboard.open) {
                Log.w("shell", "openMenu refused: the keyboard is open")
                return false
            }
            if (menu.open) cancelMenu()
            settleSlidesForOverlay()
            // decision-6 semantics: the menu owns the screen like the wheel —
            // a box on screen goes back to the queue unread and returns after
            if (notifications.active) {
                if (notifications.furlingOut) {
                    notifications.restoreUnderFinished(comp.composed)?.let { comp.damage(it) }
                    notifications.abandonFurl()
                } else {
                    liftNotificationBox()
                    notifications.requeueCurrent()
                }
                boxLifted = false
            }
            menu.openWith(spec)
            updatePlanes()
            paintMenu()
            return true
        }

        override fun openKeyboard(spec: KeyboardSurface.Spec, owner: DamageWindow?): Boolean {
            // LOOP-ONLY (§4.8): the same refusal rules as openMenu
            if (mode != Mode.WINDOW || switcher.open) {
                Log.w("shell", "openKeyboard refused: mode=$mode switcher=${switcher.open}")
                return false
            }
            if (owner != null && owner !== current) {
                Log.i("shell", "openKeyboard refused: '${owner.id}' is not the focused window")
                return false
            }
            if (menu.open) {
                // a menu commit that wants the keyboard runs after the menu
                // closed; anything else asking under an open menu is an async
                // completion that must not take the screen
                Log.w("shell", "openKeyboard refused: a menu is open")
                return false
            }
            // a spec the surface refuses (too many live keys) is a caller
            // defect: say it, return false, touch nothing (R2-K2)
            if (spec.extra.size > KeyboardSurface.MAX_EXTRA) {
                Log.e("shell", "openKeyboard refused: ${spec.extra.size} live keys, the keyboard holds ${KeyboardSurface.MAX_EXTRA}")
                return false
            }
            if (keyboard.open) cancelKeyboard()
            settleSlidesForOverlay()
            if (notifications.active) {
                if (notifications.furlingOut) {
                    notifications.restoreUnderFinished(comp.composed)?.let { comp.damage(it) }
                    notifications.abandonFurl()
                } else {
                    liftNotificationBox()
                    notifications.requeueCurrent()
                }
                boxLifted = false
            }
            keyboard.openWith(spec, settings.keyboardLayout)
            updatePlanes()
            paintKeyboard()
            return true
        }

        override fun icons(): wm.damage.core.gfx.IconSource? = iconSource

        override fun enterExclusive(window: DamageWindow): Boolean = enterExclusiveMode(window)
        override fun exitExclusive() = exitExclusiveMode()

        override fun openWindow(id: String, target: String?): Boolean {
            // LOOP-ONLY by contract (§16.2): callers are gesture/commit
            // handlers, which already run on the loop
            val w = windows.firstOrNull { it.id == id }
            if (w == null) {
                Log.w("shell", "openWindow('$id'): no such window — hand-off refused")
                return false
            }
            // §16.2's "double-tap returns to the caller": remember who handed
            // off (one level — B→C after A→B returns to B, then Main). Cleared
            // by any EXPLICIT navigation (Main commit, switcher commit).
            val caller = if (mode == Mode.WINDOW) current else null
            commitWindow(w, ActivationSource.DEEP_LINK)
            if (w !== caller) backTarget = caller?.takeIf { it !== w }
            if (target != null) tryOpenTarget(w, target)
            return true
        }

        override fun runOnShell(action: () -> Unit) = post(Msg.Run(action = action))

        override fun docContentWidth(): Int = layout.contentInner.w - 32
        // the same height ContentKit.visibleLines divides by lineHeight
        override fun docContentHeight(): Int = layout.content.h - 2 * wm.damage.core.geom.Layout.CONTENT_PAD
    }

    /**
     * 🔴 A refused post UNDOES its own count and says so. `queued` is what
     * `isQuiescent()` reads, so a message counted but never delivered leaves
     * the shell permanently "busy" to every harness and every gate — a silent
     * failure in the counter whose whole job is to be trusted (review §31).
     */
    private fun post(m: Msg) {
        queued.incrementAndGet()
        if (msgs.trySend(m).isFailure) {
            queued.decrementAndGet()
            Log.e("shell", "the loop refused $m — the shell is no longer serving messages")
        }
    }

    /** Loop liveness, for [quiescenceReport]: a stall that says WHICH — the
     *  loop ended, or it is parked inside a handler — is a stall someone can
     *  act on (review §31: three tests reported `queued=1` with no thread
     *  inside our code and no way to tell those two apart). */
    @Volatile private var loopAlive = false
    @Volatile private var loopHandling: String? = null
    @Volatile private var loopSince = 0L

    private fun setStatus(s: String) {
        if (statusText != s) {
            statusText = s
            chromeDirty = true
        }
    }

    // ------------------------------------------------------------------ set-up
    fun register(w: DamageWindow) {
        // the sub-record key scheme parses `window.<id>.<subKey>` at the FIRST
        // dot — a dotted id would let one window's tombstone sweep overwrite
        // another's records (review 2026-09-01 F9). Enforced, not assumed.
        require('.' !in w.id) { "window id '${w.id}' must not contain '.'" }
        require(windows.none { it.id == w.id }) { "window id '${w.id}' already registered" }
        windows.add(w)
    }

    private fun mainRows(): List<DamageWindow> =
        windows.filter { it !== settingsWindow } + settingsWindow

    /** External notice entry (content client, phone bridge, tests). */
    fun postNotice(n: Notifications.Notice) = post(Msg.Notice(n))

    /** External input entry (keyboard harness, touch harness, remote shell). */
    fun postGesture(type: Int, source: Int = EvenHubMsg.SRC_RING) =
        post(Msg.In(type, source))

    /** The idle tick, on demand — tests of what may flush alone (§8.3). */
    fun postIdleTick() = post(Msg.IdleTick)

    /** Change the settings from outside the loop (hosts, tests): applied ON
     *  the loop like a Settings row would, persisted and synced. */
    fun updateSettings(f: (ShellSettings) -> ShellSettings) = post(Msg.Run { applySettings(f(settings)) })

    /** Flushes this shell has submitted, counted ON the loop — a harness
     *  that reads it after a settle sees every flush the settle covered
     *  (a collector on the transport's events can lag the loop). */
    @Volatile var flushesSubmitted = 0L
    /** Rects shipped as cached draws so far (§41 — harnesses read it). */
    @Volatile var cachedRectsShipped = 0L
        private set

    /** The throughput readout as the bars show it (tests: §8.3's telemetry rule). */
    val chromeThru: String? get() = chrome.paintedState?.thru

    /** start/stop are serialized (round 3, phone D1): a stop arriving while
     *  start is still choreographing the display would otherwise save state
     *  that was never loaded, and leave two drivers on one transport. */
    private val lifecycle = Mutex()
    private var stateLoaded = false

    suspend fun start() = lifecycle.withLock { startLocked() }

    private suspend fun startLocked() {
        check(!running) { "shell already started" }
        running = true
        // registration happens ONCE per instance: a retried start after a
        // refused transport must not add a second Settings row or a second
        // event collector (round 4, shell #2)
        if (!::settingsWindow.isInitialized) {
            settingsWindow = SettingsWindow(chromeText, { settings }, { applySettings(it) },
                // the shell's own global font rows lead the host's rows
                { globalFontRows + hostSettings },
                // per-app categories (2026-08-31): every registered window gets
                // one — its own rows plus the shell's font/size/style/depth
                // rows (Settings itself renders the global style, so it
                // contributes only its own rows — none)
                { windows.mapNotNull { w ->
                    val rows = w.appSettings() +
                        (if (w === settingsWindow) emptyList() else styleRowsFor(w.id))
                    rows.takeIf { it.isNotEmpty() }?.let { w.name to it }
                } })
            register(settingsWindow)
        }
        for (w in windows) w.onRegistered(services)

        // a leftover surface from this instance's PREVIOUS session (the keeper
        // stops and restarts the same Shell on link edges) must not carry a
        // stale under-capture forward — content can change across the gap
        // (a sync apply), and the close-restore would blit old pixels
        // (review 2026-09-01 R2#19). Drop it; reopening costs one tap.
        if (menu.open) {
            val leftover = closeMenuSurface(restore = false)
            try { leftover?.onClose?.invoke() } catch (e: Exception) {
                Log.e("shell", "menu close handler at session start failed", e)
            }
        }
        dropKeyboard()                    // the same rule for the keyboard (§4.8), draft handed back
        // reported sub-keys are per SESSION (review 2026-09-01 R2#5): a key
        // restored in session N whose restore THROWS in session N+1 must not
        // stay "reported" — saveAll would tombstone the real record and sync
        // the removal fleet-wide, exactly what the guard exists to prevent
        subReported.clear()
        badPlaneSaid.clear()          // a new session re-reports a bad region once

        // restore persisted state (§9.1: survives WM restart) BEFORE the loop
        persistence.load()
        // the stamp map AT load — the §19.4 reconciliation baseline: any
        // syncable key whose stamp advances before the loop's first pass was
        // applied store-direct and must be LIVE-applied before the first save
        // can out-stamp it with stale live state (2026-09-01)
        val stampsAtLoad = persistence.stamps()
        stateLoaded = true
        settings = ShellSettings.fromJson(persistence.get("shell.settings"))
        settingsLocallyEdited = false   // live settings now MIRROR the store
        if (persistence.get("shell.settings") == null) {
            persistence.putBaseline("shell.settings", settings.toJson())  // R2#16
        }
        layout = layoutFor(settings)
        refreshStyles()   // every window draws through its per-app transform (Style.kt)
        for (w in windows) {
            // sub-records FIRST (§16.4a): per-item state (reading positions,
            // saves) is in place before the main restore launches any async
            // open that reads it
            val prefix = "window.${w.id}."
            for (k in persistence.keysWithPrefix(prefix)) {
                persistence.get(k)?.let {
                    try { w.restoreSubState(k.removePrefix(prefix), it) } catch (e: Exception) {
                        Log.e("shell", "sub-restore of $k failed", e)
                    }
                }
            }
            persistence.get("window.${w.id}")?.let {
                try { w.restoreState(it) } catch (e: Exception) {
                    Log.e("shell", "restore of ${w.id} failed — window starts fresh", e)
                }
            }
            // keys the window actually HOLDS after restore count as reported
            // (F2c/d): a later removal may tombstone them, while keys a build
            // ignores (no sub-record support) or failed to restore stay
            // protected — "didn't load it" is never a removal
            try {
                // an empty blob is the tombstone, never a held record (see saveAll)
                subReported.getOrPut(w.id) { HashSet() }
                    .addAll(w.saveSubState().filterValues { it.isNotEmpty() }.keys)
            } catch (e: Exception) {
                Log.e("shell", "saveSubState of ${w.id} at restore failed", e)
            }
            // a window with NO stored record gets a stamp-0 BASELINE of its
            // defaults (R2#16): persisted for offline restarts, but any real
            // fleet record beats it — the first value-changing save after
            // actual use re-stamps for real
            if (persistence.get("window.${w.id}") == null) {
                try { persistence.putBaseline("window.${w.id}", w.saveState()) } catch (e: Exception) {
                    Log.e("shell", "baseline of ${w.id} failed", e)
                }
            }
        }
        val shellState = persistence.get("shell.state")
        val restoredId = shellState?.get("current")?.jsonPrimitive?.contentOrNull
        val restoredMode = shellState?.get("mode")?.jsonPrimitive?.contentOrNull
        main.model.cursor = shellState?.get("mainCursor")?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull() ?: 0
        // same-instance restart: the persisted notices ARE the in-memory ones
        // — drop the live set first or every keeper restart duplicates the
        // whole unread queue (R3d note, the third per-session-family member)
        notifications.resetForRestore()
        restoreNotices(shellState)
        // divergence bookkeeping is per session: a restarted shell reports its
        // first disagreement afresh, and a stale report does not linger on the
        // hosts' status lines (round 2, d2-3; the count too — round 3, a3-7)
        lastDivergence = null
        divergencesReported = 0
        divergenceRun = 0
        agreeingChecks = 0

        // UNDISPATCHED: the collector subscribes BEFORE transport.start() can
        // emit, or early events (capability, lease) would vanish silently.
        if (!collectorLaunched) {
            collectorLaunched = true
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                transport.events.collect { post(Msg.Trans(it)) }
            }
        }

        // Start the display — capability gate + carrier + lease + warmup — OR
        // ADOPT a session the transport already runs ("the session outlives
        // the driver", 2026-08-31): a takeover or handback skips the whole
        // choreography, and the composeFullSurface + requestKeyframe below
        // rebaseline the glasses from OUR truth in one wide flush. This is
        // what turns a driver change from a multi-second teardown (Adam's two
        // blinks per WiFi edge) into a repaint — the G2CC decoupling.
        val adopted = transport.state.value.started
        try {
            if (adopted) {
                Log.i("shell", "adopting the transport's live session (a takeover/handback) — no re-choreography, one keyframe")
            } else {
                transport.start(splashFrame())
            }
        } catch (e: Exception) {
            // refuse-to-start (capability gate, link failure): leave the shell
            // stopped, not half-running with no loop (review round 2 #B1)
            running = false
            journal.close()
            throw e
        }
        // the firmware restores ITS OWN brightness across reboots: push the
        // configured value once per session so the setting always wins
        // (faceclaw pushes force=true on a fresh connection for the same
        // reason). Fire-and-forget on the control lane.
        transport.setBrightness(settings.brightnessAuto, settings.brightness)

        // §37.0: a same-instance restart carries no sleep over — the keeper
        // restarts this shell on every link end, the wake from Silent Mode
        // included, and the glasses' state is what THIS session's READ said
        // (the transport parses field 4.14 before the capability gate
        // answers, so it is known here). Asleep from before the first frame
        // if they are silent — no keyframe goes out to be refused; otherwise
        // this session paints. The SilentMode event the READ may also have
        // raised is queued behind this and finds the shell already there.
        glassesSilent = false
        silentGen++
        restartRequested = false
        refusalStreak = 0
        silentNoticeShown = false
        if (transport.state.value.glassesSilent)
            enterSilentGlasses("the glasses say Silent Mode is on (the READ at session start)")

        try {
            // initial surface — mode AND window restore (§9.1 rule 1). A SILENT
            // restore must not carry an activated window underneath the clock
            // (review round 2 #A8): silent has no focused window by definition.
            current = if (restoredMode == Mode.SILENT.name) null
            else windows.firstOrNull { it.id == restoredId }
            if (current != null) {
                mode = Mode.WINDOW
                recency.remove(current)
                recency.add(0, current!!)
                current!!.onActivate(services, ActivationSource.RESTORE)
            }
            if (restoredMode == Mode.SILENT.name) mode = Mode.SILENT
            // an EXCLUSIVE restore needs its window registered on this host
            // (a driver swap to a host without it lands in the window, or Main)
            if (restoredMode == Mode.EXCLUSIVE.name && current != null) {
                mode = Mode.EXCLUSIVE
                try { current!!.onExclusive(true) } catch (e: Exception) { Log.e("shell", "onExclusive at restore", e) }
            }
            syncLayout()   // §2: a restored window brings its preferred height
            // §40: a new session, a fresh atlas — the glasses hold nothing
            // of the old one (the cache went with the lease). Reset BEFORE the
            // first compose, so the fonts that compose draws are the ones
            // packed first; uploaded AFTER the keyframe below, chunk by
            // chunk, only while nothing else is pending, so the first paint
            // and every gesture come first.
            journal.note("build", buildTag.ifEmpty { "unstamped host" } + " · session start via ${transport.state.value.transportName}")
            atlasReset()
            composeFullSurface()
            comp.requestKeyframe()
            if (settings.cachedText == "on") post(Msg.Run { atlasEnable() })
            if (notifications.showNextIfIdle()) {
                if (quiet) scheduleSilentDismiss() else scheduleGrace()
                updatePlanes()   // the restored box enters the plane map now (round 3 S3)
            }

            scope.launch { loop() }
            loopLaunched = true
            // §19.4 post-start reconciliation (2026-09-01): the FIRST loop
            // message — any syncable record that advanced past the load
            // baseline (a peer's push racing this start) is live-applied now,
            // so the first save writes the same value and cannot out-stamp it
            post(Msg.Run {
                for ((k, t) in persistence.stamps()) {
                    if (!Persistence.syncable(k) || t <= (stampsAtLoad[k] ?: 0L)) continue
                    Log.i("sync", "post-start reconciliation: '$k' advanced during startup — live-applying")
                    persistence.get(k)?.let { v ->
                        try { liveApplySync(k, v) } catch (e: Exception) {
                            Log.e("sync", "reconciliation of '$k' failed", e)
                        }
                    }
                }
            })
            val gen = ++tickGen
            scheduleMinuteTick(gen)
            scope.launch {
                while (isActive && running && gen == tickGen) {
                    delay(5_000)
                    if (running && gen == tickGen) post(Msg.IdleTick)
                }
            }
            // scheduleRest mutates restGen, which the LOOP also mutates on
            // every input — and the loop is already draining queued messages
            // by this point in the start tail. Post it, so every generation
            // counter stays loop-serial (review 2026-09-01 F6).
            post(Msg.Run { scheduleRest() })
            post(Msg.Pump)
        } catch (e: Exception) {
            // the display is up but the shell could not finish assembling
            // itself (a window's activation or first paint refused): leave
            // nothing running or leased behind (round 3 S4) — unless the
            // session was ADOPTED, in which case it belongs to the transport's
            // owner and another driver may still claim it
            running = false
            if (!adopted) {
                try { transport.stop() } catch (s: Exception) {
                    Log.e("shell", "transport stop after a failed start", s)
                }
            }
            journal.close()
            throw e
        }
    }

    /** Orderly shutdown THROUGH the loop, so the final save cannot race a
     *  concurrent SaveTick (review round 1) and no state mutates cross-thread.
     *  If the loop never launched (start() threw first) there is nothing to
     *  post to — awaiting the Shutdown message would hang forever (round 2
     *  #B1); clean up directly instead.
     *
     *  [stopTransport] = false is the YIELD form ("the session outlives the
     *  driver", 2026-08-31): the shell stops and saves, but the transport's
     *  glasses session — lease renewal included — keeps running for the next
     *  driver to adopt. Only the transport's OWNING host ever passes true
     *  while another driver might still want the session. */
    suspend fun stop(stopTransport: Boolean = true) = lifecycle.withLock { stopLocked(stopTransport) }

    private suspend fun stopLocked(stopTransport: Boolean) {
        if (!running) return
        if (!loopLaunched) {
            running = false
            if (stateLoaded) saveAll(sync = true)   // never write defaults over a state never read
            if (stopTransport) transport.stop()
            journal.close()              // reopens by itself on the next write
            return
        }
        val done = CompletableDeferred<Unit>()
        post(Msg.Shutdown(done))
        // the loop completes `done`; if the scope that runs the loop has been
        // cancelled out from under us, its job's completion is the other way
        // out — never park a caller forever (round 4)
        val job = scope.coroutineContext[kotlinx.coroutines.Job]
        if (job == null) {
            done.await()
        } else {
            kotlinx.coroutines.selects.select<Unit> {
                done.onAwait { }
                job.onJoin {
                    Log.e("shell", "shell scope ended before the orderly shutdown ran — final save skipped")
                    running = false
                }
            }
        }
        if (stopTransport) transport.stop()
        if (cachedText != null && wm.damage.core.gfx.IconPaint.recorder === cachedText) wm.damage.core.gfx.IconPaint.recorder = null
        journal.close()
    }

    // ------------------------------------------------------------------ loop
    private suspend fun loop() {
        loopAlive = true
        try {
        for (m in msgs) {
            loopHandling = m::class.simpleName
            loopSince = System.currentTimeMillis()
            // a ring event (or a typed line) is the one message whose flush
            // may take the window's last slot — see pump()
            pumpPriority = m is Msg.In ||
                (m is Msg.Trans && (m.ev is TransportEvent.Input || m.ev is TransportEvent.Text))
            if (pumpPriority) inputFlushPending = true
            mirrorNsThisMsg = 0L
            textNsAtMsgStart = wm.damage.core.text.TextProfile.drawNs.get()
            val handlerT0 = System.nanoTime()
            if (!running && m !is Msg.Shutdown) {
                if (m is Msg.Run) m.dropped?.invoke()
                queued.decrementAndGet()
                continue
            }
            try {
                when (m) {
                    is Msg.In -> handleInput(m.type, m.source)
                    is Msg.Trans -> handleTransport(m.ev)
                    Msg.MinuteTick -> handleMinute()
                    Msg.IdleTick -> {
                        chromeDirty = true
                        chromeIdleFlush = true      // §8.3: chrome-only changes flush HERE
                    }
                    is Msg.RestTick -> if (m.gen == restGen && mode == Mode.MAIN && !main.resting) {
                        main.resting = true
                        composeContent()
                    }
                    is Msg.GraceTick -> handleGrace(m.gen)
                    is Msg.SilentTick -> handleSilentTick(m.gen)
                    is Msg.SaveTick -> if (m.gen == saveGen) saveAll()
                    is Msg.Notice -> handleNotice(m.n)
                    is Msg.Invalidate -> if (mode == Mode.EXCLUSIVE) paintExclusiveDelta()
                        else if (mode != Mode.SILENT) { syncLayout(); composeContent() }
                    is Msg.Run -> m.action()
                    is Msg.Shutdown -> {
                        running = false
                        dropKeyboard()    // the draft goes back to its requester BEFORE the save (review 2026-09-01 K5)
                        // the focused window's session-bound state ends with the
                        // session (a focused poll pace, a pane subscription) —
                        // start() re-activates the restored window (R2-W13)
                        try { current?.onDeactivate() } catch (e: Exception) { Log.e("shell", "onDeactivate at shutdown", e) }
                        saveAll(sync = true)      // the last state must be ON DISK before stop() returns
                        m.done.complete(Unit)
                    }
                    Msg.Pump -> {}
                }
            } catch (e: Exception) {
                // LOUD AND PROUD: a shell-loop error reaches the status bar, the
                // journal, and the log — and the loop keeps serving input.
                Log.e("shell", "loop error handling $m", e)
                journal.note("error", e.toString())
                setStatus("ERROR ${e.message ?: e::class.simpleName}")
            } catch (e: Error) {
                // An ERROR too (review §29, the live walk): a NoClassDefFoundError
                // out of a handler ended this loop, and the shell sat frozen on
                // its last frame while the transport kept the lease renewed and
                // the keeper's status read "running" — the exact silent failure
                // this project bans. The loop keeps serving and the status says
                // it; whether the process is still healthy after a VM error is
                // for the person to decide, with the display alive to tell them.
                Log.e("shell", "loop ERROR handling $m — the loop keeps serving; restart when convenient", e)
                journal.note("error", e.toString())
                setStatus("ERROR ${e.message ?: e::class.simpleName}")
            }
            handlerNsThisMsg = System.nanoTime() - handlerT0
            try {
                if (running) pump()
            } catch (e: Exception) {
                Log.e("shell", "pump error", e)
                journal.note("pump-error", e.toString())
                setStatus("ERROR ${e.message ?: e::class.simpleName}")
            } catch (e: Error) {
                Log.e("shell", "pump ERROR — the loop keeps serving; restart when convenient", e)
                journal.note("pump-error", e.toString())
                setStatus("ERROR ${e.message ?: e::class.simpleName}")
            }
            queued.decrementAndGet()
            loopHandling = null
            if (!running && m is Msg.Shutdown) break
        }
        } finally {
            loopAlive = false
            // whatever is still in the channel will never be handled: the
            // count must not outlive the loop that would have cleared it
            var left = 0
            while (msgs.tryReceive().isSuccess) { queued.decrementAndGet(); left++ }
            if (left > 0) Log.w("shell", "the loop ended with $left message(s) unhandled")
        }
    }

    // ------------------------------------------------------------------ input
    /** A typed line from a replica (Transport.injectText). Offered to the
     *  FOCUSED window only; a window that accepts stages it behind its own
     *  confirm. Anything else is refused LOUDLY — a typed line must never
     *  vanish or park invisibly (the G2CC F10 lesson). */
    private fun handleTypedText(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        // the menu owns the screen like the wheel does — a typed line landing
        // under it would open confirm surfaces OVER it (review 2026-09-01 F3)
        // the keyboard is open (§4.8): the replica's line IS the draft — a real
        // keyboard beat the ring to it — and it commits through the same path
        if (keyboard.open) {
            keyboard.setDraft(trimmed)
            commitKeyboard(trimmed)
            return
        }
        val w = if (mode == Mode.WINDOW && !switcher.open && !menu.open) current else null
        val accepted = try {
            w?.onTypedText(trimmed) ?: false
        } catch (e: Exception) {
            Log.e("shell", "typed-text handler of ${w?.id} failed", e)
            false
        }
        if (!accepted) {
            val where = when {
                mode == Mode.SILENT -> "the shell is in silent mode"
                mode == Mode.EXCLUSIVE -> "${current?.name ?: "a window"} owns the screen (double-tap leaves it)"
                switcher.open -> "the switcher is open"
                menu.open -> "a menu is open — close it first"
                w == null -> "no window is focused"
                else -> "${w.name} does not accept typed text"
            }
            services.notifyInternal("type",
                "typed line not delivered — $where (focus a window that types, like Tmux)")
            return
        }
        composeContent()
        scheduleSave()
    }

    private fun handleInput(type: Int, source: Int) {
        // a ring event while the glasses are silent: the person is there —
        // check now rather than at the next pacing tick (§36, §37.0); the
        // input itself is handled normally, the surface stays current
        if (glassesSilent) silentTick("input")
        // §1: the R1 ring is the ONLY input device — a temple brush must not
        // select or scroll. Text-region scroll events carry no source and
        // arrive as SRC_RING from the transport (G2_BLE_PROTOCOL.md §6.6).
        // 🔴 EXCEPT events 9/10: `Sys_ItemEvent.EventSource` is ABSENT for
        // them by firmware design (verified at instruction level — CLAUDE.md
        // "a long-press is UNATTRIBUTED"), so they always arrive source 0 and
        // this filter was discarding every real long-press: the switcher was
        // unreachable by either route while LongPressTest passed, because the
        // test harness supplied them with the flattering SRC_RING default
        // (found live 2026-08-31 — Adam: "I have yet to see the switcher at
        // all"). The bare-long-press-is-a-no-op default (§1.2) is what keeps
        // the temple, the second unattributed source, harmless here.
        val unattributed = type == EvenHubMsg.EV_RING_LONG_PRESS ||
            type == EvenHubMsg.EV_RING_LONG_PRESS_RELEASE
        if (!unattributed && source != EvenHubMsg.SRC_RING) {
            Log.i("shell", "non-ring gesture $type from source $source ignored (§1)")
            return
        }
        val newEcho = when (type) {
            EvenHubMsg.EV_CLICK -> "tap"
            EvenHubMsg.EV_DOUBLE_CLICK -> "double"
            EvenHubMsg.EV_SCROLL_TOP -> "up"
            EvenHubMsg.EV_SCROLL_BOTTOM -> "down"
            EvenHubMsg.EV_RING_LONG_PRESS -> "hold"
            // a bare release means "a touch ended" and follows almost every
            // swipe (HANDOFF.md §11): echoing it would permanently overwrite
            // the last real gesture, so it echoes only when a chord is armed
            EvenHubMsg.EV_RING_LONG_PRESS_RELEASE ->
                if (chordArmedAtMs != 0L) "release" else inputEcho
            else -> "ev$type"
        }
        if (newEcho != inputEcho) {
            inputEcho = newEcho          // input echo rides the next flush (§9.2)
            chromeDirty = true
        }
        // input restarts the grace and the rest transition (§4.5, §4.2)
        restGen++
        scheduleRest()
        if (main.resting) {
            main.resting = false
            if (mode == Mode.MAIN) composeContent()
        }
        if (notifications.active && !notifications.focused && !quiet) scheduleGrace()

        // EXCLUSIVE (§4.9): the same law as silent — everything swallowed
        // except double-tap, which returns to the window; the chord never arms
        if (mode == Mode.EXCLUSIVE) {
            chordArmedAtMs = 0L
            if (type == EvenHubMsg.EV_DOUBLE_CLICK) exitExclusiveMode()
            return
        }
        // SILENT: everything swallowed except double-tap (§1.5 — the gloves
        // fix). A long-press never arms the chord here (Adam, 2026-08-30):
        // gloves-on is exactly where accidental presses are the most common,
        // and double-tap must always mean wake.
        if (mode == Mode.SILENT) {
            chordArmedAtMs = 0L
            if (type == EvenHubMsg.EV_DOUBLE_CLICK) exitSilent()
            return
        }
        // §1.2/§1.3 revised 2026-08-30: with long-press OFF (the default) a
        // bare long-press is a no-op everywhere — it only ARMS the chord, the
        // release refreshes the window, and a double-tap inside it opens the
        // switcher. The ARMING event is the rare one, so no common gesture is
        // delayed or re-meant; a mistimed chord degrades to plain back.
        // Inside the open wheel §1.3's own grammar applies below (long-press
        // still cancels), so the chord never re-arms from the cancel.
        if (settings.longPress == ShellSettings.LongPress.OFF && !switcher.open) {
            when (type) {
                EvenHubMsg.EV_RING_LONG_PRESS -> {
                    chordArmedAtMs = System.currentTimeMillis()
                    return
                }
                EvenHubMsg.EV_RING_LONG_PRESS_RELEASE -> {
                    // the window runs from letting go, when the release arrives
                    if (chordArmedAtMs != 0L) chordArmedAtMs = System.currentTimeMillis()
                    return
                }
                EvenHubMsg.EV_DOUBLE_CLICK -> {
                    val armed = chordArmedAtMs != 0L &&
                        System.currentTimeMillis() - chordArmedAtMs <= chordWindowMs
                    chordArmedAtMs = 0L
                    if (armed) {
                        openSwitcher()
                        return
                    }
                    // not armed: an ordinary double-tap, routed below
                }
                else -> chordArmedAtMs = 0L    // any other gesture ends the chord
            }
        }
        // Notification holds focus: its own gesture table (§4.5)
        if (notifications.active && notifications.focused) {
            handleNotificationGesture(type)
            return
        }
        // Floating context menu open (§16.11): scroll/tap/cancel — the chord
        // block above still runs first, so the wheel opens OVER a menu (which
        // cancels) and a bare long-press stays a no-op
        if (menu.open) {
            when (type) {
                EvenHubMsg.EV_SCROLL_TOP -> { menu.scroll(-1); paintMenu() }
                EvenHubMsg.EV_SCROLL_BOTTOM -> { menu.scroll(1); paintMenu() }
                EvenHubMsg.EV_CLICK -> commitMenu()
                EvenHubMsg.EV_DOUBLE_CLICK, EvenHubMsg.EV_RING_LONG_PRESS -> cancelMenu()
            }
            return
        }
        // The keyboard open (§4.8): row/key stages — the chord block above
        // still runs first, so the wheel opens OVER it (a cancel, draft kept)
        if (keyboard.open) {
            when (type) {
                EvenHubMsg.EV_SCROLL_TOP -> { keyboard.scroll(-1); paintKeyboard() }
                EvenHubMsg.EV_SCROLL_BOTTOM -> { keyboard.scroll(1); paintKeyboard() }
                EvenHubMsg.EV_CLICK -> tapKeyboard()
                EvenHubMsg.EV_DOUBLE_CLICK -> if (keyboard.back()) paintKeyboard() else cancelKeyboard()
                // reachable only with long-press ENABLED (the chord block ran
                // first): the menu's meaning, a cancel — the draft is kept
                EvenHubMsg.EV_RING_LONG_PRESS -> cancelKeyboard()
            }
            return
        }
        // Switcher open: §1.3 grammar
        if (switcher.open) {
            when (type) {
                EvenHubMsg.EV_SCROLL_TOP -> { switcher.scroll(-1, spinFrames()); paintSwitcherFrame() }
                EvenHubMsg.EV_SCROLL_BOTTOM -> { switcher.scroll(1, spinFrames()); paintSwitcherFrame() }
                EvenHubMsg.EV_CLICK -> commitSwitcher()
                EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_DOUBLE_CLICK -> cancelSwitcher()
            }
            return
        }
        when (type) {
            // reachable only with long-press ENABLED; the default path to the
            // wheel is the chord block above (§1.3, 2026-08-30)
            EvenHubMsg.EV_RING_LONG_PRESS -> openSwitcher()
            EvenHubMsg.EV_SCROLL_TOP -> scrollFocused(-1)
            EvenHubMsg.EV_SCROLL_BOTTOM -> scrollFocused(1)
            EvenHubMsg.EV_CLICK -> tapFocused()
            EvenHubMsg.EV_DOUBLE_CLICK -> backFocused()
            EvenHubMsg.EV_RING_LONG_PRESS_RELEASE -> {}      // banked, unused (§1.2)
        }
    }

    private fun handleNotificationGesture(type: Int) {
        when (type) {
            EvenHubMsg.EV_CLICK -> {
                val n = notifications.current
                if (n != null && !n.emergency && n.appId != null) {
                    // opening the app is not clearing the queue: the next box
                    // keeps its grace (§4.5 rule 1), so the tap meant for the
                    // app just entered does not land on it (round 2, d2-10)
                    dismissNotice(markRead = true, clearing = false)
                    val w = windows.firstOrNull { it.id == n.appId }
                    if (w == null) {
                        // NO SILENT FAILURES: the notice is gone (read) — say
                        // why nothing opened instead of swallowing the tap
                        Log.w("shell", "notice tap: window '${n.appId}' is not registered here")
                        services.notifyInternal("shell",
                            "that notice's app (${n.appId}) is not available on this device")
                    } else {
                        commitWindow(w, ActivationSource.DEEP_LINK)
                        // §16.1: tap = commit + activate + open AT the item
                        n.target?.let { tryOpenTarget(w, it) }
                    }
                } else {
                    // emergencies and app-less notices: tap = dismiss (§4.5 —
                    // "there's no app to switch to for those")
                    dismissNotice(markRead = true)
                }
            }
            EvenHubMsg.EV_DOUBLE_CLICK -> dismissNotice(markRead = true)
            // reachable only with long-press ENABLED (§1.2 revised 2026-08-30):
            // by default the chord block consumed the event, and "park it
            // unread" is the chord (the wheel requeues the box, decision 6)
            EvenHubMsg.EV_RING_LONG_PRESS -> dismissNotice(markRead = false)
            EvenHubMsg.EV_SCROLL_TOP -> { notifications.scrollBody(-1, layout); paintNotification() }
            EvenHubMsg.EV_SCROLL_BOTTOM -> { notifications.scrollBody(1, layout); paintNotification() }
        }
    }

    private fun scrollFocused(delta: Int) {
        if (mode == Mode.WINDOW && current === settingsWindow && settingsWindow.onScrollAdjust(delta)) {
            composeContent()
            scheduleSave()
            return
        }
        when (val v = focusedView()) {
            is WindowView.ListView -> {
                val n = v.rowCount()
                if (n <= 1) return
                v.model.cursor = (v.model.cursor + delta).mod(n)
                liftNotificationBox()
                startListSlide(delta)
            }
            is WindowView.DocView -> {
                val lines = kit.visibleLines(layout, v)
                val maxTop = maxOf(0, v.lineCount() - lines)
                val old = v.model.topLine
                val step = v.stepLines().coerceAtLeast(1) * docAccelFactor(v, delta)
                val top = (old + delta * step).coerceIn(0, maxTop)
                if (top == old) return
                v.model.topLine = top
                liftNotificationBox()
                startDocSlide(v, (top - old) * v.lineHeight)
            }
            is WindowView.CanvasView -> {
                val h = v.onScroll ?: return
                liftNotificationBox()
                h(delta)
                composeContent()
            }
            null -> {}
        }
        scheduleSave()
    }

    // §3b acceleration (2026-08-31, reversing §0's earlier exclusion after
    // Adam read a book on glass): fast successive notches in ONE direction
    // ramp a multiplier — ≤250 ms apart raises it (to 6), 250–500 ms holds
    // it, a longer pause or a direction change resets it. Time is an input
    // here, not a bound: nothing waits and nothing is abandoned, so the
    // NO-TIMEOUTS rule is untouched.
    private var docNotchMs = 0L
    private var docNotchDir = 0
    private var docNotchMult = 1
    private fun docAccelFactor(v: WindowView.DocView, dir: Int): Int {
        if (!v.accel()) return 1
        val now = System.currentTimeMillis()
        val gap = now - docNotchMs
        docNotchMult = when {
            dir != docNotchDir -> 1
            gap <= 250 -> minOf(docNotchMult + 1, 6)
            gap > 500 -> 1
            else -> docNotchMult
        }
        docNotchMs = now
        docNotchDir = dir
        return docNotchMult
    }

    /** A fully-shown notification box floats ON the sliding band: putting the
     *  under snapshot back FIRST means the slide's blit-shift cannot smear box
     *  pixels through the band, and the furl cannot later restore pre-scroll
     *  content (review round 2 #A4). The box repaints on top after the step
     *  (pump step 3), re-capturing the freshly slid content. A null snapshot
     *  means the box is not currently painted over a captured base — nothing
     *  to lift. */
    private fun liftNotificationBox() {
        // an UNFURLING box is lifted like a shown one (round 3 S1) — only a
        // furl owns its snapshot strip by strip and must not be disturbed
        if (!notifications.active || notifications.furlingOut) return
        val lifted = notifications.restoreUnderFinished(comp.composed) ?: return
        comp.damage(lifted)
        boxLifted = true
    }

    /** Set by a lift, cleared once the box is painted again: a slide that
     *  nets to zero before the pump gets room would otherwise leave the box
     *  lifted with nothing to repaint it (round 3 S2). */
    private var boxLifted = false

    private fun tapFocused() {
        if (mode == Mode.WINDOW && current === settingsWindow && settingsWindow.onTapAdjust()) {
            composeContent()
            scheduleSave()
            return
        }
        when (val v = focusedView()) {
            is WindowView.ListView -> v.onCommit(v.model.cursor)
            is WindowView.DocView -> v.onTap()
            is WindowView.CanvasView -> v.onTap?.invoke()
            else -> {}
        }
        composeContent()
        scheduleSave()
    }

    /** §16.2: the hand-off caller a root-level back returns to (one level).
     *  Set by openWindow, consumed on use, cleared by explicit navigation. */
    private var backTarget: DamageWindow? = null
    /** Live settings hold a value the stored record did not produce — i.e. a
     *  local edit happened. Gates every re-encoding put (R2#4). */
    private var settingsLocallyEdited = false
    /** One visible alarm per save-failure streak (R3s#11). */
    private var saveFailureRaised = false

    /** Per window: the sub-record keys its saveSubState has REPORTED this
     *  session — the only keys the tombstone sweep may remove (F2c/d). */
    private val subReported = HashMap<String, MutableSet<String>>()

    /** Sub-keys already reported as wrongly-empty — one loud line each. */
    private val emptySubSaid = HashSet<String>()

    /** Illegal depth regions already reported this session (§3,
     *  `DamageWindow.contentPlanes`) — the complaint is loud once, not once a
     *  frame. */
    private val badPlaneSaid = HashSet<String>()

    private fun backFocused() {
        when (mode) {
            Mode.WINDOW -> {
                val w = current ?: return
                if (!w.back()) {
                    val ret = backTarget?.takeIf { it !== w && it in windows }
                    backTarget = null
                    if (ret != null) {
                        // the §16.2 promise: back from a handed-off window's
                        // root returns to the CALLER (Files → Reader → back
                        // lands in Files), not Main — a RESUME of the caller,
                        // which is sitting exactly where it handed off
                        commitWindow(ret, ActivationSource.SWITCHER)
                        return
                    }
                    w.onDeactivate()
                    mode = Mode.MAIN
                    current = null
                    syncLayout()  // §2: back to the global height with Main
                }
                composeContent()
            }
            Mode.MAIN -> enterSilent()
            Mode.SILENT -> exitSilent()
            Mode.EXCLUSIVE -> exitExclusiveMode()
        }
        scheduleSave()
    }

    private fun focusedView(): WindowView? = when (mode) {
        Mode.MAIN -> main.view()
        Mode.WINDOW -> current?.view()
        Mode.SILENT, Mode.EXCLUSIVE -> null
    }

    // ------------------------------------------------------------------ exclusive (§4.9)
    /** The focused window paints the WHOLE panel; one flush of everything. */
    private fun paintExclusiveFull() {
        val w = current ?: return
        comp.composed.fillRect(Rect(0, 0, comp.width, comp.height), Level.BG)
        try {
            w.paintExclusive(comp.composed, layout.safe, full = true)
        } catch (e: Exception) {
            Log.e("shell", "paintExclusive of ${w.id} failed", e)
            setStatus("ERROR ${e.message ?: e::class.simpleName}")
        }
        comp.planes = emptyList()
        comp.damageAll()
        if (notifications.active) { notifications.invalidateUnder(); paintNotification() }
    }

    /** Only the surfaces that moved — one rect each (the fid budget). */
    private fun paintExclusiveDelta() {
        val w = current ?: return
        // the frame this one replaces, for the same translation rule the
        // canvas path takes (§31): exclusive mode is the OTHER surface that
        // owns its damage, and a band that scrolls there — Music Mode's synced
        // lyrics advancing a line — is a translation like any other. One
        // buffer, one copy per frame; the detector only looks at bands big
        // enough for a copy to beat the diff.
        val safe = layout.safe
        val prev = exclusivePrev?.takeIf { it.w == safe.w && it.h == safe.h }
            ?: Gray8(safe.w, safe.h).also { exclusivePrev = it }
        prev.blit(comp.composed, safe, 0, 0)
        val rects = try {
            w.paintExclusive(comp.composed, layout.safe, full = false)
        } catch (e: Exception) {
            Log.e("shell", "paintExclusive of ${w.id} failed", e)
            setStatus("ERROR ${e.message ?: e::class.simpleName}")
            emptyList()
        }
        if (rects.isEmpty()) return
        for (r in rects) comp.damage(r)
        for (r in rects) {
            if (r.h < 64 || !safe.contains(r)) continue
            declareTranslation(prev, Rect(r.x - safe.x, r.y - safe.y, r.w, r.h), r)
        }
        // the small notice box rides on top; its under-snapshot is only ever
        // used by a furl, which the quiet modes follow with a full repaint
        if (notifications.active) paintNotification()
    }

    private fun enterExclusiveMode(w: DamageWindow): Boolean {
        if (mode != Mode.WINDOW || switcher.open || current !== w) {
            Log.w("shell", "enterExclusive('${w.id}') refused: mode=$mode switcher=${switcher.open} focused=${current?.id}")
            return false
        }
        dropKeyboard()
        if (menu.open) {
            val s = menu.close()
            menu.invalidateUnder()
            try { s?.onClose?.invoke() } catch (e: Exception) { Log.e("shell", "menu close on exclusive", e) }
        }
        settleSlidesForOverlay()
        slides = emptyList()
        notifications.requeueCurrent()   // the small form shows it again, auto-dismissing (§1.5)
        mode = Mode.EXCLUSIVE
        try { w.onExclusive(true) } catch (e: Exception) { Log.e("shell", "onExclusive(true) of ${w.id}", e) }
        paintExclusiveFull()
        if (notifications.showNextIfIdle()) {
            paintNotification()
            scheduleSilentDismiss()
        }
        scheduleSave()
        return true
    }

    private fun exitExclusiveMode() {
        if (mode != Mode.EXCLUSIVE) return
        mode = Mode.WINDOW
        current?.let { w -> try { w.onExclusive(false) } catch (e: Exception) { Log.e("shell", "onExclusive(false) of ${w.id}", e) } }
        notifications.requeueCurrent()
        composeFullSurface()
        comp.requestKeyframe()
        if (notifications.showNextIfIdle()) {
            updatePlanes()
            paintNotification()
            scheduleGrace()
        }
        scheduleSave()
    }

    // ------------------------------------------------------------------ menu
    private fun paintMenu() {
        menu.paint(comp.composed, layout)?.let { comp.damage(it) }
        chromeDirty = true
    }

    /** Close + restore what the menu covered; a box that waited behind it
     *  shows with its grace (the decision-6 shape, shared with the wheel). */
    private fun closeMenuSurface(restore: Boolean): MenuSurface.Spec? {
        if (!menu.open) return null
        val s = menu.close()
        if (restore) {
            val r = menu.restoreUnderFinished(comp.composed)
            if (r != null) comp.damage(r) else composeContent()
        } else {
            menu.invalidateUnder()
        }
        updatePlanes()
        if (notifications.showNextIfIdle()) {
            updatePlanes()
            paintNotification()
            if (!quiet) scheduleGrace()
        }
        chromeDirty = true
        return s
    }

    private fun commitMenu() {
        val s = menu.current() ?: return
        val idx = menu.selected()
        if (s.items.getOrNull(idx)?.enabled != true) return   // a dim row is a visible no-op
        closeMenuSurface(restore = true)
        try {
            s.onCommit(idx)
        } catch (e: Exception) {
            Log.e("shell", "menu commit failed", e)
            services.notifyInternal("menu", "action failed: ${e.message}")
        }
        composeContent()
        scheduleSave()
    }

    private fun cancelMenu() {
        val s = closeMenuSurface(restore = true) ?: return
        try {
            s.onClose?.invoke()
        } catch (e: Exception) {
            Log.e("shell", "menu close handler failed", e)
        }
        scheduleSave()
    }

    // ------------------------------------------------------------------ keyboard (§4.8)
    private fun paintKeyboard() {
        keyboard.paint(comp.composed, layout)?.let { comp.damage(it) }
        chromeDirty = true
    }

    /** Close + restore what the keyboard covered; a box that waited behind it
     *  shows with its grace (the menu's decision-6 shape). */
    private fun closeKeyboardSurface(restore: Boolean): KeyboardSurface.Spec? {
        if (!keyboard.open) return null
        val s = keyboard.close()
        if (restore) {
            val r = keyboard.restoreUnderFinished(comp.composed)
            if (r != null) comp.damage(r) else composeContent()
        } else {
            keyboard.invalidateUnder()
        }
        updatePlanes()
        if (notifications.showNextIfIdle()) {
            updatePlanes()
            paintNotification()
            if (!quiet) scheduleGrace()
        }
        chromeDirty = true
        return s
    }

    private fun tapKeyboard() {
        when (val t = keyboard.tap()) {
            is KeyboardSurface.Tap.Commit -> commitKeyboard(t.text)
            is KeyboardSurface.Tap.Extra -> {
                val s = keyboard.current()
                try {
                    s?.onExtra?.invoke(t.id)
                } catch (e: Exception) {
                    Log.e("shell", "keyboard live key failed", e)
                    services.notifyInternal("keyboard", "key failed: ${e.message}")
                }
                paintKeyboard()
            }
            KeyboardSurface.Tap.None -> paintKeyboard()
        }
        scheduleSave()
    }

    private fun commitKeyboard(text: String) {
        val s = closeKeyboardSurface(restore = true) ?: return
        try {
            s.onCommit(text)
        } catch (e: Exception) {
            Log.e("shell", "keyboard commit failed", e)
            services.notifyInternal("keyboard", "typed text failed: ${e.message}")
        }
        composeContent()
        scheduleSave()
    }

    /** The cancel path (double-tap at ROW, the wheel, an emergency): the
     *  draft goes back to the requester — kept, per Adam's verdict. */
    private fun cancelKeyboard() {
        val draft = keyboard.draft
        val s = closeKeyboardSurface(restore = true) ?: return
        try {
            s.onCancel?.invoke(draft)
        } catch (e: Exception) {
            Log.e("shell", "keyboard cancel handler failed", e)
        }
        scheduleSave()
    }

    /** Drop the keyboard WITHOUT restoring (the whole surface repaints —
     *  silent entry, a relayout): the draft still goes back. */
    private fun dropKeyboard() {
        if (!keyboard.open) return
        val draft = keyboard.draft
        val s = keyboard.close()
        keyboard.invalidateUnder()
        try {
            s?.onCancel?.invoke(draft)
        } catch (e: Exception) {
            Log.e("shell", "keyboard cancel handler failed", e)
        }
    }

    /** §16.1: run [w].open(target) on the commit path; unresolvable or
     *  unsupported targets are reported LOUDLY, never swallowed. */
    private fun tryOpenTarget(w: DamageWindow, target: String) {
        val ok = try {
            w.open(target)
        } catch (e: Exception) {
            Log.e("shell", "open('$target') in ${w.id} failed", e)
            false
        }
        if (!ok) {
            services.notifyInternal(w.id, "couldn't open the requested item ($target) — it may be gone")
        }
        composeContent()
        scheduleSave()
    }

    // ------------------------------------------------------------------ modes
    /** Focus [w]. [from] carries WHY (§HOLDEM.md §3, Adam 2026-09-04): the
     *  switcher and a hand-off RESUME, Main asks for the window's root list. */
    private fun commitWindow(w: DamageWindow, from: ActivationSource) {
        // an EXPLICIT navigation ends any pending hand-off return (§16.2);
        // openWindow re-sets it right after when IT is the caller
        backTarget = null
        if (mode == Mode.EXCLUSIVE) {
            // a commit from under exclusive mode (a synced hand-off): leave it first
            mode = Mode.WINDOW
            current?.let { c -> try { c.onExclusive(false) } catch (e: Exception) { Log.e("shell", "onExclusive(false)", e) } }
        }
        if (switcher.open) {
            switcher.close()
            previewPainted = null
        }
        if (menu.open) {
            // a window change repaints the whole content — no under-restore
            closeMenuSurface(restore = false)
        }
        dropKeyboard()                    // never carried into another window (§4.8)
        if (w === current && mode == Mode.WINDOW) {
            // already focused: no re-activation (§4.3) — but Main still MEANS
            // "show me the root list", so the navigation happens either way
            if (from == ActivationSource.MAIN) w.onActivate(services, from)
            composeContent()      // e.g. committing the switcher to the current
            return                // window must still erase the panel
        }
        current?.onDeactivate()
        current = w
        mode = Mode.WINDOW
        recency.remove(w)
        recency.add(0, w)
        w.dirty = false
        w.onActivate(services, from)
        // activation auto-marks that app's notifications read (§4.5) — commit
        // only; preview never does this
        notifications.markAppRead(w.id)
        syncLayout()              // §2: the window's preferred height, on FOCUS
        composeContent()
        scheduleSave()
    }

    private fun enterSilent() {
        mode = Mode.SILENT
        dropKeyboard()                    // silent repaints everything itself (§4.8)
        if (menu.open) {
            val s = menu.close()          // silent repaints everything itself
            menu.invalidateUnder()
            try { s?.onClose?.invoke() } catch (e: Exception) { Log.e("shell", "menu close on silent", e) }
        }
        settleSlidesForOverlay()
        slides = emptyList()
        // an on-screen box goes back to the queue UNREAD; silent shows its own
        // smaller form, one at a time, auto-dismissing (§1.5/§4.5)
        notifications.requeueCurrent()
        val c = wallClock()
        SilentMode.paintAll(comp.composed, layout, c.hh, c.mm, settings.silentClock, silentSmallPainter(c))
        comp.planes = emptyList()
        comp.damageAll()
        if (notifications.showNextIfIdle()) {
            paintNotification()
            scheduleSilentDismiss()
        }
        scheduleSave()
    }

    private fun exitSilent() {
        mode = Mode.MAIN
        main.resting = false
        syncLayout()              // §2: silent kept the old window's layout
        composeFullSurface()
        if (notifications.active && !notifications.focused) scheduleGrace()
        scheduleSave()
    }

    // ------------------------------------------------------------------ switcher
    private fun openSwitcher() {
        if (menu.open) cancelMenu()       // the wheel displaces the menu (§16.11)
        if (keyboard.open) cancelKeyboard()   // … and the keyboard (§4.8, draft kept)
        settleSlidesForOverlay()
        // decision 6 (HANDOFF.md §8.1): the wheel owns the screen. A box on
        // screen goes back to the queue unread, unshown until the wheel closes;
        // a box mid-furl finishes at once and stays dismissed.
        if (notifications.active) {
            if (notifications.furlingOut) {
                notifications.restoreUnderFinished(comp.composed)?.let { comp.damage(it) }
                notifications.abandonFurl()
            } else {
                liftNotificationBox()
                notifications.requeueCurrent()
            }
            boxLifted = false
        }
        switcher.openWith(if (mode == Mode.WINDOW) current else null, recency)
        previewPainted = null
        updatePlanes()
        paintSwitcherFrame()
    }

    private fun commitSwitcher() {
        val sel = switcher.selected() ?: return cancelSwitcher()
        val target = sel.window
        switcher.close()
        previewPainted = null
        if (target == null) {
            backTarget = null     // explicit navigation ends a pending hand-off
            current?.onDeactivate()
            current = null
            mode = Mode.MAIN
            syncLayout()          // §2: back to the global height with Main
            composeContent()      // §4.3: the preview already painted the rest;
        } else {                  // this erases the panel region
            commitWindow(target, ActivationSource.SWITCHER)  // marks notices read (§4.5) BEFORE
        }                         // the queue is shown: a box for the app just
        // entered is not shown as new (round 1, f6)
        val shown = notifications.showNextIfIdle()   // a box that waited behind the wheel
        if (shown) {
            updatePlanes()
            paintNotification()
            scheduleGrace()
        } else {
            updatePlanes()
        }
        scheduleSave()
    }

    private fun cancelSwitcher() {
        switcher.close()
        previewPainted = null
        val shown = notifications.showNextIfIdle()   // a box that waited behind the wheel
        updatePlanes()
        composeContent()          // restore what we came from (the expensive path)
        if (shown) scheduleGrace()
    }

    private fun paintSwitcherFrame() {
        val panel = switcher.paint(comp.composed, layout)
        comp.damage(panel)
        chromeDirty = true        // the top bar previews too, snapping (§4.3)
    }

    /** Live preview (§4.3): the window BEHIND the panel becomes the selected
     *  one — a RENDER, never an activation; lowest-priority, runs on settle. */
    private fun settlePreview() {
        if (!switcher.open || switcher.spinning) return
        val sel = switcher.selected() ?: return
        val key = sel.window?.id ?: "@main"
        if (previewPainted == key) return
        previewPainted = key
        notifications.invalidateUnder()     // preview repaints beneath the box
        paintContentOf(sel.window)          // no lifecycle hooks — render only
        paintSwitcherFrame()
        // the box (grace holds while the wheel is open) goes back on top with
        // a fresh capture of the previewed content (review round 2 #A5)
        if (notifications.active) paintNotification()
        // the settle runs in the pump's NOTHING-PENDING branch, so its damage
        // has no flush behind it — without this pump the preview waits for
        // the next incidental message, up to the 5 s idle tick (R3s#5)
        post(Msg.Pump)
    }

    // ------------------------------------------------------------------ notices
    private fun handleNotice(n: Notifications.Notice) {
        if (!noticeAllowed(n)) {
            Log.i("shell", "notification from '${n.source}' filtered by settings (§4.5)")
            return
        }
        if (quiet) {   // silent AND exclusive: the small form, auto-dismissing (verdict 23)
            val shown = notifications.current
            notifications.post(n, layout)
            notifications.showNextIfIdle()
            if (notifications.active) {
                paintNotification()      // repaint covers the queue badge too
                // a notice QUEUING behind the shown box must not reset its 5 s
                // clock — only a new or replaced box does (review round 2 #A9)
                if (notifications.current !== shown) scheduleSilentDismiss()
            }
            return
        }
        if ((menu.open || switcher.open || keyboard.open) && n.emergency) {
            // §4.5: "an emergency alert cancels any pending confirm rather
            // than stacking on it" — a menu can sit open indefinitely, so an
            // emergency must not wait behind it (review 2026-09-01 F8); the
            // WHEEL has no auto-close either, so the same rationale covers it
            // (R3s#3). Losing a surface is safe, a missed alert is not.
            if (switcher.open) cancelSwitcher()
            if (menu.open) cancelMenu()
            if (keyboard.open) cancelKeyboard()   // losing a draft's surface is safe; it comes back pre-filled
            // and the close must not seat a QUEUED ordinary box ahead of the
            // alert — park it back unread; post() seats emergencies at the
            // queue HEAD (R2#3), so the alert really does show next
            if (notifications.active && notifications.current?.emergency == false) {
                liftNotificationBox()
                notifications.requeueCurrent()
                boxLifted = false
            }
        } else if (switcher.open || menu.open || keyboard.open) {
            // decision 6: wait behind the wheel — and behind the context menu
            // (§16.11) — queued unshown until the surface closes
            notifications.post(n, layout, show = false)
            return
        }
        val replacedRect = notifications.post(n, layout)
        if (replacedRect != null) {
            // same-thread coalesce replaced the visible box: its size may have
            // changed — repaint content under the union, then the new box
            composeContent()
        } else {
            // a box beginning its unfurl over a mid-flight slide would capture
            // a moving base — motion yields to the overlay (§6.3, round 2 #A4)
            settleSlidesForOverlay()
            if (notifications.active && !notifications.focused) scheduleGrace()
            updatePlanes()
            paintNotification()
        }
    }

    /** §4.5: the source filter is load-bearing, not hygiene — every allowed
     *  notification interrupts, so the filter is what buys the box its focus. */
    private fun noticeAllowed(n: Notifications.Notice): Boolean {
        if (n.emergency) return true
        val kind = n.source.substringBefore('·').trim().uppercase()
        return when (kind) {
            "SMS", "MMS" -> settings.notifySms
            "MAIL" -> settings.notifyMail
            // MUSIC is NOT gated here: the window owns its six Notify rows
            // (WINDOWS.md §1), and the Global "Notify · Music" row that APKs up
            // to 0.17 carried could have persisted a false that no row could
            // undo — a hidden gate (docs audit 2026-09-02)
            "DAMAGE" -> settings.notifyDamage
            else -> true
        }
    }

    private fun dismissNotice(markRead: Boolean, clearing: Boolean = true) {
        settleSlidesForOverlay()      // the furl restores from a snapshot:
        notifications.dismiss(markRead, clearing)          // settle the base
        scheduleSave()
        // the furl animation runs via pump, restoring from the under snapshot
    }

    /** An overlay animation (unfurl/furl) is about to own the frame while a
     *  slide is mid-flight: lift the painted box off the band, snap the bands
     *  to target, and let the overlay recapture a settled base (§6.3). Lifting
     *  FIRST matters — snapping over a painted box would bake box pixels into
     *  the band while the stale snapshot kept restoring pre-snap rows. */
    private fun settleSlidesForOverlay() {
        if (slides.none { it.active }) return
        liftNotificationBox()
        snapSlides()
    }

    /** Paint the current box; captures the under-content first so the furl can
     *  restore it strip by strip (§4.5 "furl in reverse ... then restore"). */
    private fun paintNotification() {
        val n = notifications.current ?: return
        val silent = quiet
        val full = notifications.fullRect(n, layout, silent)
        notifications.captureUnder(comp.composed, full)
        val box = notifications.paint(comp.composed, layout, silent)
        if (box != null) comp.damage(box)
    }

    private fun handleGrace(gen: Int) {
        if (gen != graceGen) return
        if (quiet) return                                // §4.5 rule 4
        if (switcher.open) { scheduleGrace(); return }   // never steal the wheel's gestures
        if (notifications.active && !notifications.focused) {
            notifications.takeFocus()
            updatePlanes()        // the box steps FORWARD (§4.5); the plane-map
            paintNotification()   // diff re-renders it and cleans its old seams
        }
    }

    private fun handleSilentTick(gen: Int) {
        if (gen != silentDismissGen || !quiet || !notifications.active) return
        // silent boxes auto-dismiss after 5 s and STAY UNREAD (§4.5 —
        // deliberate divergence from G2CC's mark-at-display)
        notifications.dropSilent()
        if (mode == Mode.EXCLUSIVE) { paintExclusiveFull(); if (notifications.active) scheduleSilentDismiss(); return }
        val c = wallClock()
        SilentMode.paintAll(comp.composed, layout, c.hh, c.mm, settings.silentClock, silentSmallPainter(c))
        comp.damageAll()
        if (notifications.active) {
            paintNotification()
            scheduleSilentDismiss()
        }
    }

    // ------------------------------------------------------------------ ticks
    private fun handleMinute() {
        if (mode == Mode.EXCLUSIVE) { paintExclusiveDelta(); return }   // the window's own clock surface
        if (mode == Mode.SILENT) {
            val c = wallClock()
            SilentMode.paintClock(comp.composed, layout, c.hh, c.mm, settings.silentClock, silentSmallPainter(c))
            comp.damage(SilentMode.clockRect(layout, settings.silentClock))    // the 60-per-hour flush
        } else {
            chromeDirty = true                           // rides or waits for idle
        }
    }

    /** One loop per session (not a self-rescheduling chain — see [tickGen]):
     *  fire on each minute boundary, then step 1 s past it so the next wait is
     *  a full minute even when the timer wakes a hair early. */
    private fun scheduleMinuteTick(gen: Int) {
        scope.launch {
            while (isActive && running && gen == tickGen) {
                delay(60_000 - (System.currentTimeMillis() % 60_000))
                if (running && gen == tickGen) post(Msg.MinuteTick)
                delay(1_000)
            }
        }
    }

    private fun scheduleRest() {
        val gen = ++restGen
        scope.launch { delay(12_000); if (running) post(Msg.RestTick(gen)) }
    }

    private fun scheduleGrace() {
        val gen = ++graceGen
        scope.launch { delay(2_500); if (running) post(Msg.GraceTick(gen)) }
    }

    private fun scheduleSilentDismiss() {
        val gen = ++silentDismissGen
        scope.launch { delay(5_000); if (running) post(Msg.SilentTick(gen)) }
    }

    private fun scheduleSave() {
        val gen = ++saveGen
        scope.launch { delay(2_000); if (running) post(Msg.SaveTick(gen)) }
    }

    // ------------------------------------------------------------------ transport
    private fun handleTransport(ev: TransportEvent) {
        when (ev) {
            is TransportEvent.Input -> handleInput(ev.type, ev.source)
            is TransportEvent.Text -> handleTypedText(ev.line)
            is TransportEvent.FlushDone -> completeFlush(ev)
            is TransportEvent.Battery -> {
                // chrome never justifies its own flush (§8.3): the new value
                // rides the next content flush or the idle chrome tick
                ev.glassesPct?.let { glassesBattery = Chrome.Battery(it) }
                // ev.ringPct is ignored: ring battery has no source and no cell
                // (CLAIMS.md); the relay listener stays only to log a frame if a
                // future CFW ever pushes one
                chromeDirty = true
            }
            is TransportEvent.SilentMode ->
                if (ev.on) enterSilentGlasses("the firmware's Silent Mode is on")
                else wakeGlasses("the firmware's Silent Mode is off")
            is TransportEvent.Lease -> {
                if (!ev.held && glassesSilent) {
                    // released on purpose (§36): not a loss, no keyframe, no notice
                    chromeDirty = true
                } else if (!ev.held) {
                    // fail-open fired: stock repainted; a keyframe is required on
                    // reacquire (§5.16) and the failure is surfaced loudly
                    setStatus("LEASE LOST")
                    services.notifyInternal("lease", "framebuffer lease lost — ${ev.detail}", urgent = true)
                    comp.requestKeyframe()
                    atlasLapsed()                // §40: the cache went with the lease
                } else if (statusText == "LEASE LOST") {
                    setStatus("ok")
                    atlasLeaseBack()             // §40: the cache is allocatable again
                }
                chromeDirty = true
            }
            is TransportEvent.Link -> {
                setStatus(if (ev.connected) "ok" else "LINK DOWN")
                // §41: the arm and the reason reach the journal — thirteen
                // alternating-arm session rebuilds in one day (2026-09-06)
                // had left no trace but a "characteristic gone" fault
                journal.note("link", if (ev.connected) "up: ${ev.detail}" else "DOWN: ${ev.detail}")
                chromeDirty = true
            }
            is TransportEvent.DiagFlags -> {
                val setFlags = ev.flags.filterValues { it }.keys
                if (setFlags.isNotEmpty()) {
                    // any sticky flag is a HARD error: panic keyframe (§9.2b)
                    setStatus("PANIC ${setFlags.joinToString("/")}")
                    journal.note("panic", setFlags.joinToString())
                    services.notifyInternal("diag", "divergence flags: $setFlags — keyframing", urgent = true)
                    comp.requestKeyframe()
                    scope.launch { transport.clearDiagFlags() }
                }
            }
            is TransportEvent.Fault -> {
                if (glassesSilent && ev.what == "imgres") {
                    // a refusal that lands while the glasses are silent (a
                    // frame in flight at the sleep) is the expected answer,
                    // not a fault to shout about (§36)
                    journal.note("silent", "refused while asleep: ${ev.detail}")
                } else {
                    setStatus("${ev.what}!")
                    journal.note("fault", "${ev.what}: ${ev.detail}")
                }
            }
            is TransportEvent.Note -> journal.note(ev.kind, ev.detail)   // a fact: the journal only
        }
    }

    private fun completeFlush(ev: TransportEvent.FlushDone) {
        if (ev.id == atlasInFlight) { atlasDone(ev); return }
        val a = inflightFlushes.remove(ev.id)
        journal.flushDone(ev.id, ev.ok, ev.ackMs, ev.bytes, ev.error)
        if (!ev.ok) {
            // 🔴 A REFUSAL is not a failure to recover from by sending more
            // (2026-09-05, `HANDOFF.md` §36): the firmware in its Silent Mode
            // answered a 37-byte delta and every keyframe after it with
            // ImgResCmd status 5, and the panic/keyframe loop below sent a
            // 20 KB keyframe every 3.5 s for a quarter of an hour, which is
            // most likely why the both-temple gesture could not get through.
            // Three refusals in a row put the glasses to sleep here — the
            // push is the primary signal (handleTransport), this is the
            // fallback for a missed one — and a refusal that lands after
            // that is rolled back and otherwise ignored.
            val refused = ev.error?.contains("ImgResCmd") == true
            refusalStreak = if (refused) refusalStreak + 1 else 0
            if (glassesSilent) {
                if (a != null) comp.rollback(a)
                return
            }
            if (refused && refusalStreak >= REFUSALS_TO_SLEEP) {
                Log.e("shell", "flush ${ev.id} refused: ${ev.error}")
                if (a != null) comp.rollback(a)
                enterSilentGlasses("the glasses refused $refusalStreak frames in a row")
                return
            }
            setStatus("flush failed")
            Log.e("shell", "flush ${ev.id} FAILED: ${ev.error}")
            if (a != null) comp.rollback(a)
            // a KEYFRAME that fails three times in a row cannot be helped by
            // another keyframe (the frame itself is undisplayable — past the
            // firmware's caps): halt until the content changes, with ONE loud
            // notice, instead of a failure loop that repeats at the phone (round 6)
            keyframeFailStreak = if (a?.keyframe == true) keyframeFailStreak + 1 else 0
            if (keyframeFailStreak >= 3) {
                haltedEpoch = comp.epoch
                keyframeFailStreak = 0
                flushFailStreak = 0
                setStatus("HALT undisplayable")
                journal.note("halt", "keyframe failed repeatedly (${ev.error}) — waiting for content to change")
                services.notifyInternal("compositor",
                    "this frame cannot be displayed (${ev.error}) — the display resumes when the content changes",
                    urgent = true)
                return
            }
            if (++flushFailStreak >= 3) {
                // three consecutive failures: stop re-partitioning the same
                // damage and reset with a keyframe, loudly
                setStatus("PANIC resync")
                journal.note("panic", "flush failure streak — keyframing")
                services.notifyInternal("compositor",
                    "repeated flush failures (${ev.error}) — keyframe resync", urgent = true)
                comp.requestKeyframe()
                flushFailStreak = 0
            }
        } else {
            flushFailStreak = 0
            keyframeFailStreak = 0
            refusalStreak = 0
            noteLinkRegime()
            checkMirrorAgreement()
        }
    }

    private var keyframeFailStreak = 0
    private var refusalStreak = 0

    // ------------------------------------------------------------ cached text (§40)
    /** The recording rasterizer, when the host wrapped its own in one — the
     *  texture-cache path exists only then (`CachedText`). */
    private val cachedText: CachedText? = text as? CachedText
    private var atlas: GlyphAtlas? = null
    /** Mode-12 chunks waiting to go, oldest first; one flush in flight at a
     *  time, and only when nothing else is pending — an upload yields to
     *  content and to every gesture (`pumpAtlas`). */
    private val atlasQueue = ArrayDeque<ByteArray>()
    private var atlasInFlight: Long? = null
    /** The fonts the chunks in flight and queued complete: live on the last ack. */
    private var atlasPendingSpecs = LinkedHashSet<FontSpec>()
    private var atlasSeenVersion = -1
    private var atlasBytesSent = 0L
    private var atlasFailed = false

    /** Fonts on the glasses right now (tests, status). */
    val cachedFontsLive: Set<FontSpec> get() = cachedText?.live ?: emptySet()
    val cachedTextActive: Boolean get() = comp.cachedText != null

    private fun atlasReset() {
        val ct = cachedText ?: return
        ct.target = comp.composed
        ct.live = emptySet()
        ct.liveImages = emptySet()
        ct.atlas = null
        wm.damage.core.gfx.IconPaint.recorder = ct     // §41: icons through the cache too
        ct.clearSeen()
        ct.endFrame()
        comp.cachedText = null
        atlas = null
        atlasQueue.clear()
        atlasInFlight = null
        atlasPendingSpecs.clear()
        atlasSeenVersion = -1
        atlasBytesSent = 0L
        atlasFailed = false
    }

    private fun atlasEnable() {
        val ct = cachedText
        if (ct == null) {
            journal.note("atlas", "cached text asked for, but this host's rasterizer is not a CachedText — pixels")
            return
        }
        if (atlas == null) {
            atlas = GlyphAtlas(ct.base)
            ct.atlas = atlas
        }
        atlasGrow()
    }

    private fun atlasDisable(why: String) {
        val ct = cachedText ?: return
        comp.cachedText = null
        ct.live = emptySet()
        ct.liveImages = emptySet()
        atlasQueue.clear()
        atlasPendingSpecs.clear()
        journal.note("atlas", "cached text off — $why")
    }

    /** The lease lapsed: the firmware freed the cache. Every font is pixels
     *  again until the whole atlas has gone up once more — which waits for
     *  the lease to be held again (a mode-12 write needs it; one refused
     *  under a lapse would switch the feature off for the session). */
    private fun atlasLapsed() {
        val ct = cachedText ?: return
        val a = atlas ?: return
        comp.cachedText = null
        ct.live = emptySet()
        ct.liveImages = emptySet()
        atlasQueue.clear()
        atlasPendingSpecs.clear()
        a.forgetUpload()
        journal.note("atlas", "the lease lapsed — the cache is gone; ${a.used} B go up again once the lease is back")
    }

    /** The lease is held again after a lapse: the atlas goes up again. */
    private fun atlasLeaseBack() {
        val a = atlas ?: return
        if (settings.cachedText == "on" && a.sentBytes <= wm.damage.core.wire.TextureCache.GUARD) atlasGrow()
    }

    /** Add every font seen since the last look — the ones drawn on plane 0
     *  first, since only those can ship as draws and the cache is 64 KiB —
     *  and queue the bytes they need. */
    private fun atlasGrow() {
        val ct = cachedText ?: return
        val a = atlas ?: return
        if (atlasFailed || settings.cachedText != "on") return
        atlasSeenVersion = ct.seenVersion
        var added = 0
        // the heaviest faces first (§41): every plane is served now, so the
        // cue is the pixels a face draws, not whether it sat on plane 0 — a
        // 64 KiB cache holds about a dozen faces, and the rows', the lens's
        // and the bars' are where a notch's bytes are
        val wanted = ct.seenSpecs().sortedByDescending { ct.usageOf(it) }
        for (spec in wanted) {
            if (a.has(spec) || a.isRefused(spec)) continue
            if (a.add(spec)) added++
            else journal.note("atlas", "font $spec stays pixels — the cache is full (${a.used} B)")
        }
        // §41: icons — drawn at least twice, heaviest first, into what the
        // fonts leave (a quarter of the cache at most, `GlyphAtlas.IMAGE_BUDGET`)
        var icons = 0
        for (key in ct.seenImageKeys().filter { ct.imageUses(it) >= CachedText.PACK_AFTER_USES }
            .sortedByDescending { ct.imageUsage(it) }) {
            if (a.hasImage(key) || a.isImageRefused(key)) continue
            val img = ct.imageOf(key) ?: continue
            if (a.addImage(key, img)) icons++
            else journal.note("atlas", "icon ${img.w}x${img.h} stays pixels — icons hold ${a.imageBytes} B, ${a.used} B used")
        }
        // §41: fonts the glasses already HOLD go live at once. The off→on
        // flip used to find nothing to upload and return before re-attaching
        // the compositor, so the setting stayed dark until a new font came
        // along (2026-09-06, 18:01–18:08 on glass: two flips, no draws).
        val held = a.specs().filter { a.isAcked(it) }.toSet()
        val heldImages = a.imageKeys().filter { a.isImageAcked(it) }.toSet()
        atlasPendingSpecs.clear()
        for (spec in a.specs()) if (spec !in held) atlasPendingSpecs.add(spec)
        // held fonts go live here only when no upload is running: mid-upload
        // the last chunk's ack does it once (0.38 on glass: each grow during
        // the first upload re-lit a few fonts and repainted the surface)
        if (atlasQueue.isEmpty() && atlasInFlight == null &&
            (held != ct.live || heldImages != ct.liveImages)) atlasLive(held, heldImages, "held")
        val chunks = a.takeUpload()
        if (chunks.isEmpty()) return
        atlasQueue.addAll(chunks)
        journal.note("atlas", "$added font(s) and $icons icon(s) packed, ${a.used} B in the atlas (${a.imageBytes} B icons), ${chunks.size} chunk(s) queued")
        post(Msg.Pump)
    }

    /** [specs] and [images] are on the glasses: the recorder blits from them
     *  from now on and the compositor may ship them as draws. The surface
     *  repaints once so belief holds the atlas's pixels — what the glasses
     *  draw from here. */
    private fun atlasLive(specs: Set<FontSpec>, images: Set<Any>, how: String) {
        val ct = cachedText ?: return
        ct.live = specs
        ct.liveImages = images
        comp.cachedText = if (specs.isEmpty() && images.isEmpty()) null else ct
        journal.note("atlas", "$how — ${specs.size} font(s) and ${images.size} icon(s) live, $atlasBytesSent B sent this session")
        if (mode != Mode.SILENT) composeContent()
        chrome.invalidate(); chromeDirty = true
        post(Msg.Pump)
    }

    /** One chunk, when the link is otherwise idle. True when one was sent.
     *  The submit happens ON the loop, like every other flush's, so its id
     *  is known before its completion can possibly arrive. */
    private suspend fun pumpAtlas(st: LinkState): Boolean {
        val ct = cachedText ?: return false
        if (settings.cachedText != "on" || atlasFailed || glassesSilent) return false
        if (ct.seenVersion != atlasSeenVersion) atlasGrow()
        if (atlasInFlight != null || atlasQueue.isEmpty()) return false
        if (st.inFlight != 0 || comp.hasPending || comp.needsKeyframe) return false
        val chunk = atlasQueue.removeFirst()
        val id = try {
            transport.submit(FlushRequest(listOf(DisplayOp.CacheWrite(chunk)), comp.epoch, "ATLAS"))
        } catch (e: Exception) {
            Log.e("shell", "atlas chunk submit failed", e)
            atlasFailed = true
            atlasDisable("a chunk could not be submitted: ${e.message}")
            return false
        }
        atlasInFlight = id
        atlasBytesSent += chunk.size
        // §42: the chunk is a flush like any other in the journal — with its label
        // and transport — so the report prices the upload where it happened
        journal.flushSubmitted(id, comp.epoch, listOf(DisplayOp.CacheWrite(chunk)), "ATLAS", st.transportName)
        journal.note("atlas", "chunk of ${chunk.size} B submitted as flush $id (${atlasQueue.size} to go)")
        return true
    }

    private fun atlasDone(ev: TransportEvent.FlushDone) {
        atlasInFlight = null
        journal.flushDone(ev.id, ev.ok, ev.ackMs, ev.bytes, ev.error)
        val ct = cachedText ?: return
        if (!ev.ok) {
            atlasFailed = true
            atlasDisable("the glasses refused a cache write: ${ev.error}")
            setStatus("atlas refused")
            return
        }
        atlas?.acked()
        if (atlasQueue.isEmpty()) {
            // the last chunk of this batch: every font whose bytes are acked
            // is on the glasses (the acked watermark, §41 — never the queued
            // one, which runs ahead of the radio)
            val a = atlas
            val held = a?.specs()?.filter { a.isAcked(it) }?.toSet() ?: emptySet()
            val heldImages = a?.imageKeys()?.filter { a.isImageAcked(it) }?.toSet() ?: emptySet()
            atlasPendingSpecs.removeAll(held)
            if (held != ct.live || heldImages != ct.liveImages) atlasLive(held, heldImages, "uploaded")
        }
        post(Msg.Pump)
    }

    // ------------------------------------------------------ silent glasses (§36, §37.0)
    /** The glasses are in the firmware's Silent Mode: they refuse every image
     *  until the both-temple long-press wakes them — and LEAVING the mode ends
     *  the firmware's EvenHub page, so nothing paints again until a CREATE
     *  (measured on glass 2026-09-05 22:04, `HANDOFF.md` §37.0). The shell
     *  keeps composing and stops SENDING, drops the lease so stock owns the
     *  display and the temples, tells the phone once — and the WAKE is a
     *  session REBUILD (`Transport.restartSession`: the link ends, the keeper
     *  stops this shell and starts it again from the prelude up — G2CC's
     *  reconnect-and-relayout path), never a keyframe. The new start reads the
     *  glasses' state before its first frame ([startLocked]). */
    private var glassesSilent = false
    private var silentGen = 0
    private var silentNoticeShown = false
    /** Checks made during this sleep (§42) — journaled by count, not one each. */
    private var silentChecks = 0
    /** The rebuild has been asked for: this session is over the moment the
     *  transport reports the link down, and nothing more is sent or asked. */
    private var restartRequested = false
    private var lastRebuildAskMs = 0L

    /** How the glasses currently read to this shell — for status lines and tests. */
    val glassesAsleep: Boolean get() = glassesSilent

    /** The asleep shell's check pacing (a paced loop, never a bound on work):
     *  each tick either waits on — the glasses say they are still silent — or
     *  asks for the rebuild a refusal streak calls for. Tests shorten it. */
    @Volatile var silentPacingMs = SILENT_PACING_MS

    private fun enterSilentGlasses(why: String) {
        if (glassesSilent) return
        glassesSilent = true
        silentChecks = 0
        val gen = ++silentGen
        keyframeFailStreak = 0; flushFailStreak = 0; haltedEpoch = null
        setStatus("glasses silent")
        chromeDirty = true
        journal.note("silent", "glasses asleep — $why; no frames until they wake")
        Log.w("shell", "the glasses are silent ($why): frames stop, the lease is released; a check every ${silentPacingMs / 1000} s")
        if (!silentNoticeShown) {
            silentNoticeShown = true
            services.notifyInternal("glasses",
                "the glasses are in Silent Mode ($why) — long-press both temples to wake them; the display rebuilds by itself",
                urgent = true)
        }
        scope.launch {
            try { transport.setLeaseWanted(false) } catch (e: Exception) { Log.e("shell", "lease release while silent", e) }
        }
        scope.launch {
            while (running && gen == silentGen) {
                delay(silentPacingMs)
                if (running && gen == silentGen) post(Msg.Run { silentTick("pacing") })
            }
        }
    }

    /** The asleep shell's check, on the pacing tick and on a ring event.
     *  While the glasses THEMSELVES say Silent Mode is on (the last push or
     *  READ, `LinkState.glassesSilent`) there is nothing to do: the push OFF,
     *  or the 60 s poll's READ, is the wake. When they say it is off and the
     *  shell sleeps all the same — a refusal streak: the page ended and no
     *  push came, §37.0's 22:04:28 — the session is rebuilt, at most once per
     *  pacing (a ring spun twice must not ask twice). */
    private fun silentTick(why: String) {
        if (!glassesSilent || restartRequested) return
        if (transport.state.value.glassesSilent) {
            // §42: the pacing tick is counted, not journaled — ten hours asleep
            // wrote 600 identical lines. The first check, a ring event and
            // every hour's worth still land; the wake note carries the count.
            silentChecks++
            if (why != "pacing" || silentChecks == 1 || silentChecks % SILENT_CHECKS_PER_NOTE == 0)
                journal.note("silent", "still silent per the glasses ($why, check $silentChecks) — waiting for the push or the READ")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastRebuildAskMs < silentPacingMs) return
        lastRebuildAskMs = now
        requestRebuild("the glasses say they are awake and refused the last frames ($why)")
    }

    private fun wakeGlasses(why: String) {
        if (!glassesSilent || restartRequested) return
        journal.note("silent", "glasses awake after $silentChecks check(s) — $why; rebuilding the session (leaving Silent Mode ends the firmware's page)")
        Log.i("shell", "the glasses are awake ($why): session rebuild")
        lastRebuildAskMs = System.currentTimeMillis()
        requestRebuild(why)
    }

    /** The one way out of the sleep (§37.0): the transport ends its link and
     *  reports it, the keeper stops this shell (state saved) and starts it
     *  again, and the new start adopts the glasses' state — asleep or
     *  painting — from the session's READ. A transport that cannot (nothing
     *  started, or a seam whose far end did not take it) leaves a loud status
     *  and the next tick asks again; the manual recovery is the host's
     *  (Target → SIM → glasses). */
    private fun requestRebuild(why: String) {
        restartRequested = true
        setStatus("rebuilding session")
        chromeDirty = true
        scope.launch {
            val ok = try { transport.restartSession(why) } catch (e: Exception) { Log.e("shell", "session restart", e); false }
            if (!ok) post(Msg.Run {
                restartRequested = false
                journal.note("silent", "session rebuild refused by the transport ($why) — still asleep; asking again on the next check")
                setStatus("REBUILD NEEDED")
            })
        }
    }

    /**
     * The link is SLOW when the transport's measured transfer term says so
     * (2026-09-05, `HANDOFF.md` §32): ~20 ms/KB on PC-direct BlueZ against
     * ~125 ms/KB through the phone (the production journal, §31.6). The wheel
     * spins in fewer frames on a slow link; nothing else changes, and nothing
     * changes at all until a flush of 1 KB or more has actually been timed.
     */
    /** §41: the regime decision has HYSTERESIS and a dwell — the EMA swung
     *  36–68 ms/KB second to second on 2026-09-06 and the wheel changed its
     *  frame count 22 times in 70 s. Slow above 1.3× the line, fast again
     *  below 0.7×, and never twice within [REGIME_DWELL_FLUSHES] flushes. */
    private var linkSlowState = false
    private var linkSlowFlipAt = 0L
    private fun linkSlow(): Boolean {
        val ema = transport.state.value.transferMsPerKbEma
        val want = if (linkSlowState) ema > SLOW_LINK_MS_PER_KB * 0.7 else ema > SLOW_LINK_MS_PER_KB * 1.3
        if (want != linkSlowState && flushesSubmitted - linkSlowFlipAt >= REGIME_DWELL_FLUSHES) {
            linkSlowState = want
            linkSlowFlipAt = flushesSubmitted
        }
        return linkSlowState
    }

    private fun spinFrames(): Int = if (linkSlow()) 2 else 4

    private var lastLinkSlow: Boolean? = null
    private var lastLinkParams = ""

    /** Journal the link regime when it flips and the connection parameters
     *  when the platform reports them — so the phone's journal carries the
     *  answer to "was the priority request granted" (no adb needed). */
    private fun noteLinkRegime() {
        val st = transport.state.value
        val slow = linkSlow()
        if (slow != lastLinkSlow) {
            lastLinkSlow = slow
            journal.note("link", "transfer %.0f ms/KB, floor %.0f ms — %s regime (wheel spins in %d frames)"
                .format(st.transferMsPerKbEma, st.floorMsEma, if (slow) "SLOW" else "fast", spinFrames()))
        }
        if (st.linkParams != lastLinkParams) {
            lastLinkParams = st.linkParams
            if (st.linkParams.isNotEmpty()) journal.note("link", "connection parameters: ${st.linkParams}")
        }
    }

    /** The last belief/mirror disagreement reported (hosts and tests read it;
     *  null = none, or agreement restored after a keyframe). */
    @Volatile var lastDivergence: String? = null
        private set

    /** How many disagreement episodes were reported this session. */
    @Volatile var divergencesReported = 0
        private set

    /** Episodes in a row without a settled stretch of agreement between them:
     *  after [DIVERGE_EPISODES_MAX] the report stays sticky and no further
     *  keyframes or notices are issued until agreement holds for
     *  [DIVERGE_QUIET_CHECKS] checks (round 1, f2 — no repeat loops). */
    private var divergenceRun = 0
    private var agreeingChecks = 0

    /**
     * HANDOFF.md §8.2 "Divergence check": at rest — nothing in flight, nothing
     * pending, no keyframe owed — the compositor's belief about each lens must
     * equal the transport's mirror (the firmware model fed our exact bytes).
     * A disagreement means the compositor and the model disagree about what
     * our own traffic produced: reported once per EPISODE — on the transition
     * from agreement to disagreement (status, journal, urgent notice) — and
     * answered with one keyframe, which reseeds both. A disagreement that
     * survives its keyframe is left standing (the report stays visible) until
     * agreement returns; keyframing again would only repeat it. Episodes that
     * keep recurring stop issuing notices and keyframes after a few (the
     * report stays sticky) until agreement has held for a while. The belief
     * is compared through the emitter's quantiser (Pack.level): the shadow
     * keeps 8-bit levels, the glass holds nibbles. The mirror is read as a
     * SNAPSHOT under its own lock (a torn read of the live buffer would raise
     * a false alarm). Only exact (local) mirrors are read; a seam-fed mirror
     * lags.
     */
    /** Per-message timers for the journal's split (§34): the handler's own
     *  time and the time inside [checkMirrorAgreement], both reset by the loop. */
    private var handlerNsThisMsg = 0L
    private var mirrorNsThisMsg = 0L
    private var textNsAtMsgStart = 0L
    private var slidesNs = 0L
    private var chromeNs = 0L
    private var overlaysNs = 0L

    private fun checkMirrorAgreement() {
        val t0 = System.nanoTime()
        try { checkMirrorAgreementInner() } finally { mirrorNsThisMsg += System.nanoTime() - t0 }
    }

    private fun checkMirrorAgreementInner() {
        val m = transport.mirror
        if (!m.exact) return
        // §37.0: with the lease released on purpose the mirror shows the
        // stock pattern — nothing of ours is on the glass to agree with, and
        // the report it raised at 22:04:02 was a false alarm that stuck
        if (glassesSilent) return
        if (inflightFlushes.isNotEmpty() || comp.hasPending || comp.needsKeyframe) return
        val stride = m.stride
        var report: String? = null
        for (arm in Arm.entries) {
            val belief = comp.expectedLens(arm == Arm.LEFT)
            val panel = m.snapshot(arm)
            val first = firstDisagreement(belief, panel, stride) ?: continue
            if (report == null) {
                val diffs = countDisagreements(belief, panel, stride)
                report = "$arm: $diffs px differ, first at (${first.first},${first.second}) belief " +
                    "${belief[first.first, first.second]} (level ${LEVEL[belief[first.first, first.second]]}) mirror level ${nibble(panel, stride, first.first, first.second)}"
            }
        }
        if (report == null) {
            if (lastDivergence != null) {
                journal.note("divergence", "agreement restored")
                if (statusText.startsWith("DIVERGE")) setStatus("ok")
            }
            lastDivergence = null
            if (++agreeingChecks >= DIVERGE_QUIET_CHECKS) divergenceRun = 0
            return
        }
        agreeingChecks = 0
        val newEpisode = lastDivergence == null
        lastDivergence = report
        if (!newEpisode) return          // still the same episode: one report, one keyframe
        divergencesReported++
        divergenceRun++
        journal.note("divergence", "episode $divergenceRun: $report")
        if (divergenceRun > DIVERGE_EPISODES_MAX) {
            // recurring: the report stays on the status bar; no more keyframes
            // or notices until agreement has held for a while
            setStatus("DIVERGE x$divergenceRun")
            return
        }
        setStatus("DIVERGE")
        services.notifyInternal("mirror", "belief and mirror disagree — $report — keyframing" +
            (if (divergenceRun == DIVERGE_EPISODES_MAX) " (last notice: further episodes stay on the status bar)" else ""),
            urgent = true)
        comp.requestKeyframe()
    }

    private fun nibble(panel: ByteArray, stride: Int, x: Int, y: Int): Int {
        val b = panel[y * stride + (x shr 1)].toInt() and 0xFF
        return if (x and 1 == 0) b shr 4 else b and 0x0F
    }

    /** The first pixel whose quantised belief differs from the panel, or null
     *  — a cheap early-out scan (a lookup table replaces the per-pixel divide). */
    private fun firstDisagreement(belief: Gray8, panel: ByteArray, stride: Int): Pair<Int, Int>? {
        val w = comp.width
        for (y in 0 until comp.height) {
            val row = y * stride
            var x = 0
            while (x < w) {
                val b = panel[row + (x shr 1)].toInt() and 0xFF
                if (LEVEL[belief[x, y]] != (b shr 4)) return x to y
                if (LEVEL[belief[x + 1, y]] != (b and 0x0F)) return (x + 1) to y
                x += 2
            }
        }
        return null
    }

    private fun countDisagreements(belief: Gray8, panel: ByteArray, stride: Int): Int {
        var diffs = 0
        for (y in 0 until comp.height) {
            val row = y * stride
            for (x in 0 until comp.width) {
                if (LEVEL[belief[x, y]] != nibble(panel, stride, x, y)) diffs++
            }
        }
        return diffs
    }
    /** Set when the current frame proved undisplayable: assembly pauses until
     *  the compositor epoch moves (any damage or plane change). */
    private var haltedEpoch: Long? = null

    // ------------------------------------------------------------------ compose
    // Always TOP-aligned since 2026-08-31 (Adam: the top is always visible;
    // the bottom is what occlusion takes) — the vertical-position setting is
    // retired and every reduced band sits at the top of the panel.
    private fun layoutFor(s: ShellSettings): Layout {
        val (rowH, lensH) = listRhythm(chromeText)          // Main is what boots
        val base = Layout(rowH = rowH, lensH = lensH, clockW = clockCellW())
        return if (s.heightMode >= Geometry.PANEL_H) base
        else base.withHeightMode(s.heightMode, wm.damage.core.geom.VPos.TOP)
    }

    /** §2 per-app height (REFINEMENT.md, 2026-08-31): the layout the shell
     *  should be in RIGHT NOW — the focused window's preferred height when it
     *  declares one, else the global Size setting. */
    private fun effectiveLayout(): Layout {
        val focused = if (mode == Mode.WINDOW || mode == Mode.EXCLUSIVE) current else null
        val h = focused?.preferredHeight ?: settings.heightMode
        // the list rhythm follows the face ON SCREEN: the focused window's
        // per-app transform, or the chrome transform under Main (review §29)
        val (rowH, lensH) = listRhythm(focused?.let { w ->
            wm.damage.core.text.StyledText(text) { appTransform(w.id).apply(it) }
        } ?: chromeText)
        val base = Layout(rowH = rowH, lensH = lensH, clockW = clockCellW())
        return if (h >= Geometry.PANEL_H) base
        else base.withHeightMode(h, wm.damage.core.geom.VPos.TOP)
    }

    /**
     * The list row pitch and lens band for a rasterizer view (review §29).
     *
     * A rect a paint returns is a promise, and the design's 32 px row held
     * exactly Clear Sans 18's 27 px of ink under the rows' 5 px offset — one
     * step up the ladder (32 px at 115 %) and the row directly above the lens
     * lost its descenders: the rows above are painted first and the lens
     * band then clears itself over them. The row grows with the MEASURED ink
     * of the row face (regular and bold, whichever inks taller) and the lens
     * with two of them; the design numbers are the floors, so 100 % is
     * pixel-identical. Windows place their lens lines from the same ink
     * (`Draw.lineBelow`), never at a constant.
     */
    private fun listRhythm(tx: TextRasterizer): Pair<Int, Int> {
        val ink = maxOf(
            tx.metrics(FontSpec(Face.SYSTEM, ROW_FACE_PX)).let { it.ascent + it.descent },
            tx.metrics(FontSpec(Face.SYSTEM, ROW_FACE_PX, bold = true)).let { it.ascent + it.descent })
        val rowH = maxOf(Layout.ROW_H, (ROW_TEXT_Y + ink + 1) / 2 * 2)
        val lensH = maxOf(Layout.LENS_H, (2 * ink + LENS_SLACK + 1) / 2 * 2)
        return rowH to lensH
    }

    /** Swap to [effectiveLayout] if it differs: the full §4.2 size-change
     *  path (re-wrap, resurface, keyframe). Called on every focus commit and
     *  on Invalidate; cheap when nothing changed. Returns true on a swap. */
    private fun syncLayout(): Boolean {
        val want = effectiveLayout()
        if (want == layout) return false
        layout = want
        chrome.invalidate()
        kit.resetRail()
        slides = emptyList()
        dropKeyboard()                    // the geometry under it changed (§4.8)
        if (menu.open) {
            val s = menu.close()          // the geometry under it changed
            menu.invalidateUnder()
            try { s?.onClose?.invoke() } catch (e: Exception) { Log.e("shell", "menu close on relayout", e) }
        }
        for (w in windows) w.onLayoutChanged()
        comp.composed.clear(0)
        composeFullSurface()
        comp.requestKeyframe()   // a height change re-lays out the whole shell (§4.2)
        return true
    }

    // ---------------------------------------------------- typography (Style.kt)
    /** The per-app transform windows draw through — face/scale/style from
     *  Settings → <app>, over the global scale. */
    private fun appTransform(id: String): wm.damage.core.text.StyleTransform {
        val a = settings.appStyle(id)
        return wm.damage.core.text.StyleTransform(
            face = if (a.face == "default") null else wm.damage.core.text.Faces.byLabel(a.face),
            systemOnly = false,
            scale = if (a.scale == 0.0) settings.fontScale else a.scale,
            bold = when (a.style) { "bold" -> true; "regular", "italic" -> false; else -> null },
            italic = when (a.style) { "italic" -> true; "regular", "bold" -> false; else -> null },
        )
    }

    private fun refreshStyles() {
        for (w in windows) w.styleTransform = { spec -> appTransform(w.id).apply(spec) }
    }

    private fun effScale(id: String): Double =
        settings.appStyle(id).scale.takeIf { it != 0.0 } ?: settings.fontScale

    /** Content depth for the FOCUSED app (default 8 — in front of the
     *  global-depth chrome); Main and the bars stay on the global setting
     *  (Adam, 2026-08-31). */
    private fun appDepth(w: DamageWindow): Int = settings.appDepthOf(w.id)

    private val fontSizeLabels = ShellSettings.SCALES.associateBy { ShellSettings.scaleLabel(it) }

    /** The Global category's font rows (staged like Size — a face change is a
     *  full relayout, not a per-notch step), each option previewed in ITS OWN
     *  font (raw specs — the transforms must not restyle a candidate). */
    private val globalFontRows: List<HostSetting> by lazy {
        listOf(
            HostSetting("Font", { wm.damage.core.text.Faces.LABELS },
                { settings.fontFace },
                { v -> applySettings(settings.copy(fontFace = v)) },
                optionFont = { opt -> wm.damage.core.text.Faces.byLabel(opt)
                    ?.let { wm.damage.core.text.FontSpec(it, 18, raw = true) } }),
            HostSetting("Font size", { fontSizeLabels.keys.toList() },
                { ShellSettings.scaleLabel(settings.fontScale) },
                { v -> fontSizeLabels[v]?.let { applySettings(settings.copy(fontScale = it)) } },
                optionFont = { opt -> fontSizeLabels[opt]?.let {
                    wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM,
                        maxOf(6, Math.round(18 * it).toInt()), raw = true) } }),
            HostSetting("Font style", { ShellSettings.STYLES },
                { settings.fontStyle },
                { v -> applySettings(settings.copy(fontStyle = v)) },
                optionFont = { opt -> wm.damage.core.text.FontSpec(
                    wm.damage.core.text.Face.SYSTEM, 18,
                    bold = opt == "bold", italic = opt == "italic", raw = true) }),
            // §1.5 silent-clock size (2026-09-01 Adam): large = the original
            // seven-segment box, medium = a smaller one, small = the title
            // bar clock's exact size and position
            HostSetting("Silent clock", { ShellSettings.SILENT_CLOCKS },
                { settings.silentClock },
                { v -> applySettings(settings.copy(silentClock = v)) }),
            // §40 (2026-09-06, Adam): frames per notch for list and document
            // slides — off · 2 · 4 · auto · 8 · 12, auto = today's halving rule
            HostSetting("Slide frames", { ShellSettings.SLIDE_FRAMES },
                { settings.slideFrames },
                { v -> applySettings(settings.copy(slideFrames = v)) }),
            // §41.9 (2026-09-06, Adam: "Auto"): a big strip blank-then-fill
            // (split), whole in the first flush (whole), or split only while
            // the cache is off (auto)
            HostSetting("Slide fill", { ShellSettings.SLIDE_FILLS },
                { settings.slideFill },
                { v -> applySettings(settings.copy(slideFill = v)) }),
            // §40 (2026-09-06): text through the firmware's texture cache —
            // off until it has been seen on glass; on = fonts uploaded once
            // per lease, plane-0 strings as mode-14 draws
            HostSetting("Cached text", { ShellSettings.CACHED_TEXT },
                { settings.cachedText },
                { v -> applySettings(settings.copy(cachedText = v)) }),
        )
    }

    /** Settings → <app>: font/size/style + depth rows, one STABLE set per
     *  window id (the Settings window matches staged rows by identity). */
    private val appStyleRows = HashMap<String, List<HostSetting>>()
    private fun styleRowsFor(id: String): List<HostSetting> = appStyleRows.getOrPut(id) {
        listOf(
            HostSetting("Font", { listOf("default") + wm.damage.core.text.Faces.LABELS },
                { settings.appStyle(id).face },
                { v -> applySettings(settings.withAppStyle(id) { it.copy(face = v) }) },
                optionFont = { opt -> wm.damage.core.text.Faces.byLabel(opt)
                    ?.let { wm.damage.core.text.FontSpec(it, 18, raw = true) } }),
            HostSetting("Font size", { listOf("default") + fontSizeLabels.keys },
                { settings.appStyle(id).scale.takeIf { it != 0.0 }?.let { ShellSettings.scaleLabel(it) } ?: "default" },
                { v -> applySettings(settings.withAppStyle(id) {
                    it.copy(scale = fontSizeLabels[v] ?: 0.0) }) },
                optionFont = { opt -> fontSizeLabels[opt]?.let {
                    wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM,
                        maxOf(6, Math.round(18 * it).toInt()), raw = true) } }),
            HostSetting("Font style", { ShellSettings.STYLES },
                { settings.appStyle(id).style },
                { v -> applySettings(settings.withAppStyle(id) { it.copy(style = v) }) },
                optionFont = { opt -> wm.damage.core.text.FontSpec(
                    wm.damage.core.text.Face.SYSTEM, 18,
                    bold = opt == "bold", italic = opt == "italic", raw = true) }),
            // §3.1 (2026-09-06): "global" follows the Global row (the default);
            // a value moves only this app's content — the bars stay global
            HostSetting("Depth", { listOf("global") + ShellSettings.DEPTHS.map { "$it" } },
                { settings.appStyle(id).depth.let { if (it < 0) "global" else "$it" } },
                { v -> applySettings(settings.withAppStyle(id) {
                    it.copy(depth = v.toIntOrNull() ?: ShellSettings.GLOBAL_DEPTH) }) }),
        )
    }

    private fun applySettings(s: ShellSettings, persist: Boolean = true) {
        val relayout = s.heightMode != settings.heightMode
        // any typography change — global face/scale/style or an app's —
        // re-derives every wrap and repaints whole (the §Type reversal)
        val restyle = s.fontScale != settings.fontScale || s.fontFace != settings.fontFace ||
            s.fontStyle != settings.fontStyle ||
            s.appStyles.any { (id, a) ->
                val o = settings.appStyle(id)
                a.face != o.face || a.scale != o.scale || a.style != o.style
            }
        val redepth = s.appStyles.any { (id, a) -> a.depth != settings.appStyle(id).depth }
        // §4.2's live preview, made real for Brightness (2026-08-31): every
        // step pushes the sid-0x09 write, so the panel changes as you scroll
        val rebright = s.brightness != settings.brightness || s.brightnessAuto != settings.brightnessAuto
        val reclock = s.silentClock != settings.silentClock
        val recache = s.cachedText != settings.cachedText
        settings = s
        if (rebright) transport.setBrightness(s.brightnessAuto, s.brightness)
        if (recache) { if (s.cachedText == "on") atlasEnable() else atlasDisable("the setting is off") }
        // liveApplySync passes persist=false: the store already holds the
        // EXACT synced record; putting our clamped re-encoding would re-stamp
        // it and, across versions with different ladders, ping-pong restyles
        // forever (review 2026-09-01 F5). The flag carries the same fact to
        // freshenSyncKey and saveAll (R2#4): only a LOCAL edit may ever
        // re-encode the record.
        settingsLocallyEdited = persist
        if (persist) persistence.put("shell.settings", s.toJson())
        if (restyle) {
            // a synced record can change typography AND height in one apply
            // (R3s#6): re-derive the layout BEFORE the full repaint, or the
            // restyle renders at the stale height until the next invalidate.
            // Styles first (review §29): the list rhythm is measured through
            // them, so a face or scale change re-derives the row pitch and
            // the lens band in syncLayout — which re-lays out and repaints
            // whole by itself when the height or the rhythm moved, and is a
            // no-op otherwise, in which case the restyle repaints whole here
            refreshStyles()
            for (w in windows) w.onFontScaleChanged(effScale(w.id))
            if (!syncLayout()) {
                for (w in windows) w.onLayoutChanged()
                comp.composed.clear(0)
                composeFullSurface()
                comp.requestKeyframe()
            }
            scheduleSave()
            return
        }
        // a global size change under a window that pins its own height changes
        // nothing visible — syncLayout says so and the cheap path runs instead
        if (mode == Mode.SILENT) {
            // the silent surface repaints only when ITS one element changed
            // (a live-synced silent-clock size, §1.5) — paintAll clears the
            // whole panel, so the old box never lingers
            if (reclock) composeFullSurface()
        } else if (!relayout || !syncLayout()) {
            updatePlanes()
            if (redepth) composeFullSurface() else composeContent()
        }
        scheduleSave()
    }

    /** Repaint chrome + content from scratch (mode changes, boot, relayout). */
    private fun composeFullSurface() {
        if (mode == Mode.EXCLUSIVE) { paintExclusiveFull(); return }
        if (mode == Mode.SILENT) {
            val c = wallClock()
            SilentMode.paintAll(comp.composed, layout, c.hh, c.mm, settings.silentClock, silentSmallPainter(c))
            comp.planes = emptyList()
            comp.damageAll()
            return
        }
        comp.composed.fillRect(Rect(0, 0, comp.width, comp.height), Level.BG)
        chrome.invalidate()
        chromeDirty = true
        composeContent()
    }

    /** Repaint the content area for the current mode + overlays. */
    private fun composeContent() {
        if (mode == Mode.EXCLUSIVE) { paintExclusiveFull(); return }
        if (mode == Mode.SILENT) return
        slides = emptyList()
        notifications.invalidateUnder()   // the content beneath is repainting
        // while the wheel is open the backdrop belongs to the PREVIEWED entry
        // (§4.3) — an async repaint (an icon resolve, an invalidate) must not
        // swap it back to the current window under a settled preview
        // (review 2026-09-01 F7). Render-only, never an activation.
        val behind = if (switcher.open && previewPainted != null) switcher.selected()?.window
        else if (mode == Mode.WINDOW) current else null
        paintContentOf(behind)
        if (switcher.open) paintSwitcherFrame()
        if (menu.open) {
            menu.invalidateUnder()        // recapture over the fresh content
            paintMenu()
        }
        if (keyboard.open) {
            keyboard.invalidateUnder()
            paintKeyboard()
        }
        updatePlanes()
        if (notifications.active) paintNotification()
        chromeDirty = true
    }

    /** Paint [w]'s (or Main's) content into the content area — used both for
     *  the live surface and for switcher preview (render, not activation). */
    private fun paintContentOf(w: DamageWindow?) {
        kit.resetRail()
        if (w == null) {
            kit.paintList(comp.composed, layout, main.view(), resting = main.resting)
        } else when (val v = w.view()) {
            is WindowView.ListView -> kit.paintList(comp.composed, layout, v)
            is WindowView.DocView -> kit.paintDoc(comp.composed, layout, v)
            is WindowView.CanvasView -> paintCanvasOf(v)
        }
    }

    /** The previous canvas frame, for [paintCanvasOf]'s shift detection — one
     *  buffer, reused, sized to the content area. */
    private var canvasPrev: Gray8? = null

    /** The same, for exclusive mode's own damage path. */
    private var exclusivePrev: Gray8? = null

    /**
     * 🔴 A canvas repaint that TRANSLATED its content is transmitted as the
     * translation (`CLAUDE.md`'s endless-scroll rule: mode 8 { mode 9 rect-copy
     * + mode 3 fill }), not as the whole content area.
     *
     * A `CanvasView` owns its damage, and this arm used to be `paint();
     * damage(content)`. Every row moves in a scroll, so the truth diff
     * correctly found the whole area changed and shipped it: MEASURED, a tmux
     * scroll cost 7.4–10.8 KB, and 6–12 KB measures a **median 1193 ms** on the
     * glasses against 65 ms under 500 B (the production journal, isolated
     * flushes; ~6.9 KB/s on the wire). Lists and documents never paid it —
     * their slides have always declared the shift.
     *
     * Detected rather than declared by the window (`CanvasShift` says why), so
     * every canvas gets it: the tmux pane Adam scrolls, the same pane when the
     * terminal itself scrolls a line in, the Hold'em table, and whatever is
     * written next. The detector verifies the block byte for byte, and
     * `declareShift` replays the copy onto the per-lens shadows before the diff
     * runs — so the worst a wrong answer could do is cost bytes, and a verified
     * one cannot even do that.
     */
    private fun paintCanvasOf(v: WindowView.CanvasView) {
        val r = layout.content
        applyCanvasFill()      // a split's second half still owed lands before this frame is read as "previous"
        val prev = canvasPrev?.takeIf { it.w == r.w && it.h == r.h }
            ?: Gray8(r.w, r.h).also { canvasPrev = it }
        prev.blit(comp.composed, r, 0, 0)
        v.paint(comp.composed, r)
        comp.damage(r)
        // the floor is a block worth one copy op; below it the diff is cheaper
        // than the copy plus the repairs behind it
        val exposed = declareTranslation(prev, Rect(0, 0, r.w, r.h), r) ?: return
        if (splitFills() && exposed.w * exposed.h >= Slide.SPLIT_FILL_PX) {
            // §40: copy first, fill second. The strip goes out BLANK with the
            // translation (a uniform run, a few bytes — the glass moves ~70 ms
            // after the notch) and its content one message on. A tmux history
            // notch measured 352–645 ms to its first visible change as one
            // 2.4–4.3 KB flush.
            comp.composed.fillRect(exposed.x, exposed.y, exposed.w, exposed.h, Level.BG)
            canvasFillOwed = true
            post(Msg.Run { liftNotificationBox(); applyCanvasFill() })
        }
    }

    /** A canvas strip sent blank by [paintCanvasOf] whose content is still
     *  owed (§40). */
    private var canvasFillOwed = false

    /** The `Slide fill` row's answer for the next strip (§41.9): split only
     *  while the cache is not serving — a cached strip is ~200 B of draws
     *  and rides the translation's flush; a pixel strip is 1–4 KB and the
     *  glass would wait for it. */
    private fun splitFills(): Boolean = when (settings.slideFill) {
        "split" -> true
        "whole" -> false
        else -> !cachedTextActive
    }

    /** Land the owed canvas content: the focused canvas repaints (its memo
     *  makes an unchanged frame cheap) and the whole content is damaged —
     *  the diff finds the strip, and anything else the window changed since,
     *  so belief never runs ahead of the shadow. */
    private fun applyCanvasFill() {
        if (!canvasFillOwed) return
        canvasFillOwed = false
        if (mode != Mode.WINDOW) return                          // the surface moved on and painted itself
        val cv = focusedView() as? WindowView.CanvasView ?: return
        cv.paint(comp.composed, layout.content)
        comp.damage(layout.content)
    }

    /**
     * Declare the translation between [prev] (read at [was]) and the freshly
     * repainted `composed` (read at [now]), when this repaint was one, and
     * return the strip it EXPOSED (§40), or null when the repaint was not a
     * translation.
     *
     * The floor is a block worth one copy op — below it the diff is cheaper
     * than the copy plus the repairs behind it.
     */
    private fun declareTranslation(prev: Gray8, was: Rect, now: Rect): Rect? {
        val (src, dst) = CanvasShift.detect(prev, was, comp.composed, now, minRun = maxOf(32, now.h / 8))
            ?: return null
        comp.declareShift(src, dst, movePending = false)
        // the strip the block does not cover — the larger one when it sits
        // mid-region; the rest is diffed as before
        val top = if (dst.y > now.y) Rect(now.x, now.y, now.w, dst.y - now.y) else null
        val bottom = if (dst.bottom < now.bottom) Rect(now.x, dst.bottom, now.w, now.bottom - dst.bottom) else null
        return listOfNotNull(top, bottom).maxByOrNull { it.h }
    }

    private fun updatePlanes() {
        val d = settings.depth
        val planes = ArrayList<Compositor.PlaneRegion>()
        // content: the FOCUSED app's own depth when it set one, else the
        // Global row; Main is always the Global row
        val contentD = (if (mode == Mode.WINDOW) current?.let { appDepth(it) } else null) ?: d
        if ((d != 0 || contentD != 0) && !quiet) {
            // §3.1 revised 2026-09-06 (Adam, on glass): the Global `Depth`
            // moves EVERYTHING — both bars with their dividers, Main, and every
            // app's content unless the app's own row says otherwise — and the
            // selection bar (the lens) sits ONE notch nearer than the plane it
            // selects on, never nearer than the screen plane. Before, the bars
            // rode one step behind content capped at 16 and app content parked
            // at 8 whatever the row said, so 8/12/16 moved only the bars and 16
            // changed nothing at all. Both bands carry bar + divider as one
            // region; a plane at 0 is the remainder and needs no region.
            if (d != 0) {
                planes.add(Compositor.PlaneRegion(
                    Rect(layout.topBar.x, layout.topBar.y, layout.topBar.w,
                        Layout.TOP_H + Layout.DIV_H), d))
                planes.add(Compositor.PlaneRegion(
                    Rect(layout.statusBar.x, layout.bottomDivider.y, layout.statusBar.w,
                        Layout.DIV_H + Layout.STATUS_H), d))
            }
            if (contentD != 0) planes.add(Compositor.PlaneRegion(layout.content, contentD))
            // The wheel owns the depth story while it is open (§4.3, corrected
            // 2026-08-31): the window behind it is a PREVIEW, so its lens row
            // is not the focus — and this full-content-width band runs
            // through the wheel's rows, which dragged the whole width forward,
            // background included (Adam's report).
            val lensD = maxOf(0, contentD - 4)
            if (lensD != contentD && !switcher.open && !menu.open && !keyboard.open && focusedView() is WindowView.ListView) {
                planes.add(Compositor.PlaneRegion(layout.lens, lensD))      // the selection, one notch nearer
            }
            // a window's own depth regions (HOLDEM.md §9.2: the hole cards
            // come forward). Validated here, not trusted: an unaligned or
            // escaping box is rejected by the firmware in SILENCE
            if (mode == Mode.WINDOW && !switcher.open && !menu.open && !keyboard.open) {
                current?.let { w ->
                    val want = w.contentPlanes(layout.content)
                    if (want.size > MAX_WINDOW_PLANES && badPlaneSaid.add("${w.id}#${want.size}")) {
                        Log.e("shell", "window ${w.id} asked for ${want.size} depth regions; " +
                            "the budget is $MAX_WINDOW_PLANES — the rest are dropped")
                    }
                    for ((r, plane) in want.take(MAX_WINDOW_PLANES)) {
                        val errs = wm.damage.core.geom.Geometry.checkRect(r, "${w.id} depth region")
                        if (errs.isNotEmpty() || !layout.content.contains(r)) {
                            // LOUD, but not once per frame: updatePlanes runs on
                            // every compose, and an unbounded repeat of the same
                            // line is its own failure mode (review pass 3)
                            if (badPlaneSaid.add("${w.id}@$r")) {
                                Log.e("shell", "window ${w.id} asked for an illegal depth region $r " +
                                    "(content ${layout.content}): ${errs.joinToString("; ")}")
                            }
                            continue
                        }
                        planes.add(Compositor.PlaneRegion(r, ((plane / 4).coerceIn(0, 4)) * 4))
                    }
                }
            }
            if (menu.open) {
                // the menu owns the depth story while open (the §4.3 wheel
                // lesson applied): its box is the only plane-0 region
                menu.rect(layout)?.let { planes.add(Compositor.PlaneRegion(it, 0)) }
            }
            if (keyboard.open) {
                // the keyboard owns the depth story while open (§4.8): plane 0
                keyboard.rect(layout)?.let { planes.add(Compositor.PlaneRegion(it, 0)) }
            }
            if (switcher.open) {
                if (d != 0) planes.add(Compositor.PlaneRegion(layout.switcherPanel, d)) // neighbours with content
                val centre = Rect(layout.switcherPanel.x, layout.switcherPanel.y + 44,
                    layout.switcherPanel.w, 88)
                planes.add(Compositor.PlaneRegion(centre, 0))               // centre band forward
            }
            val n = notifications.current
            if (n != null) {
                val box = notifications.fullRect(n, layout, silent = false)
                val plane = when {
                    n.emergency && notifications.focused -> -4               // crossed: plane +1
                    notifications.focused -> 0
                    else -> d                                                // arrives with content
                }
                planes.add(Compositor.PlaneRegion(box, plane))
            }
        }
        comp.planes = planes   // a changed map re-renders + seam-cleans itself
    }

    // ------------------------------------------------------------------ slides
    private fun snapSlides() {
        for (s in slides) s.snap(comp.composed)
    }

    private fun startListSlide(delta: Int) {
        // the band above hangs from the lens, like ContentKit's rows (review §29)
        val bandAbove = Rect(layout.content.x, layout.lens.y - layout.rowsAbove * layout.rowH,
            layout.content.w - Layout.RAIL_W, layout.rowsAbove * layout.rowH)
        val bandBelow = Rect(layout.content.x, layout.lens.bottom,
            layout.content.w - Layout.RAIL_W, layout.rowsBelow * layout.rowH)
        if (slides.size != 2 || slides[0].region != bandAbove) {
            slides = listOf(
                Slide(comp, bandAbove, cachedText) { g, y0, h -> paintListSlice(g, y0, h, above = true) },
                Slide(comp, bandBelow, cachedText) { g, y0, h -> paintListSlice(g, y0, h, above = false) },
            )
        }
        for (s in slides) { s.frames = settings.slideFrameCount(); s.splitFills = splitFills(); s.retarget(delta * layout.rowH) }
        // §40: the optimistic lens repaint (icon, bold title, detail — 3–6 KB
        // measured, the heavy part of a notch) used to ride the FIRST flush
        // with the first slide step, so nothing moved on the glass for
        // 830–860 ms. It is posted one message on: the first flush is the two
        // band copies and a half-row strip (< 500 B, ~70 ms), the lens
        // follows with the second frame. The Run lands before any later
        // gesture (it is queued ahead of them) and paints from the model as
        // it is then, so a fast spin repaints the newest cursor.
        post(Msg.Run { paintOptimisticLens() })
    }

    /** The lens band for the focused row of the focused list, plus the rail
     *  (§5.11: the model already moved). */
    private fun paintOptimisticLens() {
        if (menu.open || keyboard.open || switcher.open) return   // an overlay took the surface; it painted itself
        liftNotificationBox()
        val v = focusedView()
        if (v is WindowView.ListView) {
            comp.composed.fillRect(layout.lens, Level.BG)
            comp.composed.fillRect(layout.lens.x, layout.lens.y, layout.lens.w - Layout.RAIL_W, 2, Level.DIM)
            comp.composed.fillRect(layout.lens.x, layout.lens.bottom - 2, layout.lens.w - Layout.RAIL_W, 2, Level.DIM)
            v.paintLens(comp.composed, Rect(layout.lens.x, layout.lens.y,
                layout.lens.w - Layout.RAIL_W, layout.lens.h), v.model.cursor)
            comp.damage(layout.lens)
            val n = v.rowCount()
            kit.paintRail(comp.composed, layout,
                if (n > 1) v.model.cursor.toDouble() / (n - 1) else 0.0,
                layout.content.h * (layout.rowsAbove + layout.rowsBelow + 1) / maxOf(1, n))
                ?.let { comp.damage(it) }
        }
    }

    /** Paint target-state list rows for a band-relative y-range. */
    private fun paintListSlice(g: Gray8, y0: Int, h: Int, above: Boolean) {
        val v = focusedView() as? WindowView.ListView ?: return
        val n = v.rowCount()
        g.fillRect(0, 0, g.w, g.h, Level.BG)
        if (n == 0) return
        val slots = layout.rowsAbove + layout.rowsBelow + 1
        val c = v.model.cursor
        val bandSlots = if (above) layout.rowsAbove else layout.rowsBelow
        val rowH = layout.rowH
        var slot = Math.floorDiv(y0, rowH)
        while (slot * rowH < y0 + h) {
            if (slot in -1..bandSlots) {   // one extra row either side for slide overlap
                val idx = if (above) c - layout.rowsAbove + slot else c + 1 + slot
                val real = if (n > slots) idx.mod(n) else if (idx in 0 until n) idx else null
                if (real != null) {
                    val tmp = Gray8(g.w, rowH)
                    // §41: the row temp lands in the strip temp, which lands on
                    // the target — the recorder composes the offsets
                    val paint = { v.paintRow(tmp, real, Rect(0, 0, g.w, rowH), false) }
                    cachedText?.viaInto(g, tmp, 0, slot * rowH - y0, paint) ?: paint()
                    g.blit(tmp, Rect(0, 0, g.w, rowH), 0, slot * rowH - y0)
                }
            }
            slot++
        }
    }

    private fun startDocSlide(v: WindowView.DocView, dyPx: Int) {
        val lines = kit.visibleLines(layout, v)
        val region = Rect(layout.content.x, layout.content.y + Layout.CONTENT_PAD,
            layout.content.w - Layout.RAIL_W, lines * v.lineHeight)
        if (slides.size != 1 || slides[0].region != region) {
            slides = listOf(Slide(comp, region, cachedText) { g, y0, h -> paintDocSlice(g, v, y0, h) })
        }
        slides[0].frames = settings.slideFrameCount()
        slides[0].splitFills = splitFills()
        slides[0].retarget(dyPx)
        val maxTop = maxOf(1, v.lineCount() - lines)
        kit.paintRail(comp.composed, layout, v.model.topLine.toDouble() / maxTop,
            (layout.content.h.toLong() * lines / maxOf(1, v.lineCount())).toInt())
            ?.let { comp.damage(it) }
    }

    private fun paintDocSlice(g: Gray8, v: WindowView.DocView, y0: Int, h: Int) {
        g.fillRect(0, 0, g.w, g.h, Level.BG)
        var slot = Math.floorDiv(y0, v.lineHeight)
        while (slot * v.lineHeight < y0 + h) {
            val idx = v.model.topLine + slot
            if (idx in 0 until v.lineCount()) {
                val tmp = Gray8(g.w, v.lineHeight)
                val paint = { v.paintLine(tmp, idx, Rect(0, 0, g.w, v.lineHeight)) }
                cachedText?.viaInto(g, tmp, 0, slot * v.lineHeight - y0, paint) ?: paint()
                g.blit(tmp, Rect(0, 0, g.w, v.lineHeight), 0, slot * v.lineHeight - y0)
            }
            slot++
        }
    }

    // ------------------------------------------------------------------ pump
    /** After every message: animations first (slides under, overlays over),
     *  chrome rides along, then the one atomic flush; preview settles last. */
    /** Set by the loop for the message the coming pump follows: true for a
     *  ring event or a typed line, false for everything else (ticks, pushes,
     *  animation continuations, completions). */
    private var pumpPriority = false

    /** True from a gesture until its first flush goes out (§41): that flush
     *  never carries telemetry — its bytes decide what the gesture feels
     *  like, and the readout measured 40–230 B of a 184–470 B first flush. */
    private var inputFlushPending = false

    private suspend fun pump() {
        if (!running || !transport.state.value.started) return
        val st = transport.state.value
        // One slot is RESERVED for the response to input (2026-09-05,
        // `HANDOFF.md` §32): animation frames, poll-driven repaints and the
        // visualizer may keep at most window-1 flushes in flight, so a
        // gesture's first flush never queues behind three of them (each
        // 150–1,200 ms on the phone's measured curve, §31.1). The pump that
        // follows a ring event may fill the window; every other pump stops one
        // short. An instant transport never sees the difference — nothing
        // stays in flight — and a window of 1 keeps its one slot.
        val cap = if (pumpPriority) st.window else maxOf(1, st.window - 1)
        val room = st.inFlight < cap
        if (!room) return         // FlushDone re-pumps; §5.13 coalescing happens here

        var animated = false
        slidesNs = 0L; chromeNs = 0L; overlaysNs = 0L
        var phaseT0 = System.nanoTime()

        // 1. slides move the content UNDER everything — lifting a floating
        //    notification box out of the band first (#A4; step 3 repaints it)
        if (slides.any { it.active }) liftNotificationBox()
        for (s in slides) if (s.active) {
            // §40: a strip the last frame left blank lands first, then the
            // next translation — the split's second half rides this flush
            s.fillDeferred(comp.composed)
            if (s.offsetPx != 0) s.step(comp.composed)
            animated = true
        }
        slidesNs = System.nanoTime() - phaseT0; phaseT0 = System.nanoTime()

        // 2. switcher spin, painted OVER the slid content
        if (switcher.open) {
            if (switcher.spinning) {
                switcher.stepSpin()
                animated = true
            }
            if (animated) paintSwitcherFrame()
        }

        // 3. notification unfurl/furl, always on top; the furl restores the
        //    content beneath from the under snapshot, strip by strip
        if (notifications.animating) {
            val silent = quiet
            val n0 = notifications.current
            if (n0 != null) {
                val full = notifications.fullRect(n0, layout, silent)
                notifications.captureUnder(comp.composed, full)
            }
            val more = notifications.stepUnfurl(layout, silent)
            notifications.lastVacated?.let { strip ->
                if (!notifications.restoreUnder(comp.composed, strip)) {
                    // snapshot invalidated (content repainted beneath): repaint
                    // the whole surface under it once, loudly correct
                    composeContent()
                }
                comp.damage(strip)
            }
            if (notifications.consumeFurlFinished()) {
                // the old box is gone: put back what it covered BEFORE any next
                // box paints (queue-advance included); the plane-map diff in
                // updatePlanes re-renders its region and cleans its old seams
                if (quiet) {
                    notifications.invalidateUnder()
                    if (mode == Mode.EXCLUSIVE) paintExclusiveFull()
                    else {
                        val c = wallClock()
                        SilentMode.paintAll(comp.composed, layout, c.hh, c.mm, settings.silentClock, silentSmallPainter(c))
                        comp.damageAll()
                    }
                } else {
                    val restored = notifications.restoreUnderFinished(comp.composed)
                    if (restored != null) comp.damage(restored) else composeContent()
                }
                updatePlanes()
            }
            if (notifications.current != null) {
                if (!more && !notifications.focused && !quiet) scheduleGrace()
                if (notifications.current !== n0) {
                    updatePlanes()
                    if (quiet) scheduleSilentDismiss()
                }
                paintNotification()
            }
            boxLifted = false
            animated = true
        } else if ((animated || boxLifted) && notifications.active) {
            paintNotification()   // overlays stay on top of slide frames
            boxLifted = false
        }

        overlaysNs = System.nanoTime() - phaseT0; phaseT0 = System.nanoTime()

        // 4. chrome rides along with content, or flushes alone on the idle tick.
        //    Telemetry (the throughput readout, the link cell) only on a
        //    gesture's own flush, an animation frame or the idle tick — never
        //    on a content-neutral repaint (§8.3, `HANDOFF.md` §40)
        // §41: never on a gesture's FIRST flush — a later frame of the same
        // gesture, or the idle tick, carries the readout instead
        val firstOfGesture = pumpPriority && inputFlushPending
        val allowTelemetry = !firstOfGesture && (pumpPriority || animated || chromeIdleFlush)
        if ((chromeDirty || allowTelemetry) && !quiet && (comp.hasPending || animated || chromeIdleFlush)) {
            // One rect per BAR, never one per cell (§2.4 rule 2). A sync with
            // nothing dirty paints only the cells that changed — the readout
            // on the flush it may ride, a repeated gesture's included.
            val cells = chrome.sync(comp.composed, layout, chromeState(), allowTelemetry)
            val top = cells.filter { it.y < layout.topDivider.bottom }
            val bottom = cells.filter { it.y >= layout.bottomDivider.y }
            if (top.isNotEmpty()) comp.damage(top.reduce(Rect::union))
            if (bottom.isNotEmpty()) comp.damage(bottom.reduce(Rect::union))
            chromeDirty = false
            chromeIdleFlush = false
        }
        chromeNs = System.nanoTime() - phaseT0

        // 5. the one atomic flush per frame — the project's thesis.
        //    §36: while the glasses are silent the surface above stays
        //    current (slides settle, the notice unfurls, chrome syncs) and
        //    NOTHING is sent; the wake keyframe carries all of it.
        if (glassesSilent) {
            if (animated || slides.any { it.active } || switcher.spinning || notifications.animating) post(Msg.Pump)
            return
        }
        if (haltedEpoch != null) {
            if (haltedEpoch == comp.epoch) return       // undisplayable frame, unchanged
            haltedEpoch = null
            setStatus("ok")
        }
        if (comp.hasPending || comp.needsKeyframe) {
            val assembleT0 = System.nanoTime()
            val assembled = try {
                comp.assembleFlush(Geometry.rectBudget(st.window))
            } catch (e: Exception) {
                Log.e("shell", "flush assembly failed", e)
                journal.note("assemble-error", e.toString())
                setStatus("ASSEMBLE ${e.message}")
                // a throw mid-assembly can leave shadows seeded with nothing
                // on the wire (R3s#7): a keyframe re-grounds belief — cheap
                // insurance on a path that should never run
                comp.requestKeyframe()
                null
            }
            if (assembled != null) {
                val assembleMs = (System.nanoTime() - assembleT0) / 1_000_000
                // the loop stamped loopSince when it took the message this
                // pump follows: everything between is the paints it caused
                val handleMs = System.currentTimeMillis() - loopSince
                val label = "$mode${if (switcher.open) "+switcher" else ""}"
                try {
                    val id = transport.submit(FlushRequest(assembled.ops, assembled.epoch, label,
                        wide = assembled.wide))
                    inflightFlushes[id] = assembled
                    flushesSubmitted++
                    cachedRectsShipped += comp.cachedRectsThisAssemble
                    if (pumpPriority) inputFlushPending = false
                    journal.flushSubmitted(id, assembled, label, st.transportName, Journal.Timing(
                        handleMs = handleMs, handlerMs = handlerNsThisMsg / 1_000_000, mirrorMs = mirrorNsThisMsg / 1_000_000,
                        assembleMs = assembleMs, truthMs = comp.lastTruthNs / 1_000_000,
                        compressMs = comp.lastCompressNs / 1_000_000, compressN = comp.lastCompressN,
                        cached = comp.cachedRectsThisAssemble, cacheMiss = comp.cacheMissSummary(),
                        slidesMs = slidesNs / 1_000_000, chromeMs = chromeNs / 1_000_000, overlaysMs = overlaysNs / 1_000_000,
                        textMs = (wm.damage.core.text.TextProfile.drawNs.get() - textNsAtMsgStart) / 1_000_000))
                } catch (e: IllegalStateException) {
                    // the link ended between the pump's gate and the submit
                    // (§41: seen once on 2026-09-06 as `submit before
                    // start()`, nine seconds before an arm rebuild): the flush
                    // rolls back and the keeper's new session keyframes — a
                    // note, not an error
                    journal.note("link", "flush dropped: ${e.message} — the session is restarting")
                    comp.rollback(assembled)
                } catch (e: Exception) {
                    Log.e("shell", "submit failed", e)
                    journal.note("submit-error", e.toString())
                    setStatus("SUBMIT ${e.message}")
                    comp.rollback(assembled)
                }
            }
        } else if (!animated) {
            // 6. nothing pending: the lowest-priority work — the atlas's
            //    next chunk (§40), then the preview settle
            if (!pumpAtlas(st)) settlePreview()
        }

        // animations continue on the next completion or message; make sure one
        // arrives even in instant-transport tests
        if (animated || slides.any { it.active } || switcher.spinning || notifications.animating) {
            post(Msg.Pump)
        }
    }

    // ------------------------------------------------------------------ chrome
    private fun chromeState(): Chrome.State {
        val c = wallClock()
        val st = transport.state.value
        val rows = mainRows()
        val at = when (mode) {
            Mode.WINDOW, Mode.EXCLUSIVE -> rows.indexOf(current).coerceAtLeast(0)
            else -> rows.indexOf(main.focusedWindow()).coerceAtLeast(0)
        }
        val previewTarget = if (switcher.open) switcher.selected() else null
        val name = when {
            previewTarget != null -> previewTarget.name.uppercase()
            mode == Mode.WINDOW -> current?.name?.uppercase() ?: "MAIN"
            else -> "MAIN"
        }
        val icon = when {
            previewTarget != null -> previewTarget.icon
            mode == Mode.WINDOW -> current?.icon ?: IconKind.SETTINGS
            else -> IconKind.SETTINGS
        }
        val context = when {
            previewTarget != null -> "preview"
            mode == Mode.WINDOW -> current?.title() ?: ""
            else -> "${rows.size} windows" +
                (rows.count { it.dirty }.takeIf { it > 0 }?.let { " · $it unread" } ?: "")
        }
        return Chrome.State(
            windowName = name,
            windowIcon = icon,
            context = context,
            clock = c.hhmm,
            clockAmPm = c.amPm,
            glasses = glassesBattery, // sid-0x09 device-info response, f4.12 (2026-08-31)
            phone = phoneBattery,
            windowCount = rows.size,
            windowAt = at,
            dirtyAt = rows.mapIndexedNotNull { i, w -> if (w.dirty) i else null }.toSet(),
            stackDepth = when (mode) {
                Mode.MAIN -> 1
                Mode.WINDOW -> 1 + (current?.levelDepth() ?: 1) + (if (menu.open || keyboard.open) 1 else 0)
                Mode.SILENT, Mode.EXCLUSIVE -> 1
            },
            op = opText,
            status = statusText,
            inputEcho = inputEcho,
            thru = "${(st.bytesPerSecEma / 1000).toInt()}K/s · ${st.ackMsEma.toInt()}ms",
            compass = null,
            linkBars = if (st.connected) (if (st.leaseHeld) 4 else 2) else 0,
            linkDbm = st.rssiDbm,
            hostState = hostState,
        )
    }

    @Volatile var phoneBattery: Chrome.Battery? = null

    /** Wire-fed batteries: glasses from the settings READ response (start +
     *  the 60 s poll). Blank until the wire reports — never invented. The ring
     *  cell has no working source: the glasses CANNOT relay ring battery (the
     *  stock sid-0x91 service accepts only the EVENT registration and never
     *  fills RingRawData — openCFW pb_service_ring.c on our 2.2.6.10 base +
     *  both captures), the ring has no standard Battery Service / no adv
     *  battery, and its vendor link is request/response with a custom
     *  checksum. The only source is the closed Even SDK. Not pursued
     *  (cosmetic) — `CLAIMS.md`. The relay listener stays wired in the
     *  transport in case a future CFW ever implements the push. */
    private var glassesBattery: Chrome.Battery? = null

    /** The active window's id, or null at Main/silent — test introspection. */
    fun currentWindowId(): String? = if (mode == Mode.WINDOW || mode == Mode.EXCLUSIVE) current?.id else null
    /** Test introspection: is the shell in exclusive mode (§4.9)? */
    val exclusiveMode: Boolean get() = mode == Mode.EXCLUSIVE

    /** Which quiescence conditions are unmet — for a failed settle's message
     *  and the replica status line. Empty = quiescent. */
    fun quiescenceReport(): String = buildString {
        if (queued.get() != 0) append("queued=${queued.get()} ")
        if (!loopAlive) append("LOOP-ENDED ")
        loopHandling?.let { append("in=$it/${System.currentTimeMillis() - loopSince}ms ") }
        if (comp.hasPending) append("pending ")
        if (comp.needsKeyframe) append("keyframe ")
        if (inflightFlushes.isNotEmpty()) append("inflight=${inflightFlushes.size} ")
        if (notifications.animating) append("notice-anim ")
        if (slides.any { it.active }) append("slides ")
        if (switcher.spinning) append("spin ")
        if (haltedEpoch != null) append("halted ")
        if (glassesSilent) append("glasses-silent ")
        lastDivergence?.let { append("diverge[$it] ") }
        append("reports=$divergencesReported status='$statusText'")
    }

    /** True when nothing is pending anywhere — test/selfcheck introspection. */
    fun isQuiescent(): Boolean = queued.get() == 0 && idleApartFromMessages()

    /** Everything quiescence asks for EXCEPT the message queue. */
    private fun idleApartFromMessages(): Boolean =
        (glassesSilent || (!comp.hasPending && !comp.needsKeyframe)) && inflightFlushes.isEmpty() &&
            !notifications.animating && slides.none { it.active } && !switcher.spinning

    /**
     * 🔴 An ATOMIC settled sample, for a harness that compares the shell's
     * state to the glass.
     *
     * `isQuiescent()` answers about ONE instant, from another thread, and the
     * caller then reads `comp.composed`, `comp.planes` and the simulator's
     * panels one after the other — three reads across a window in which the
     * shell can start and finish a whole repaint. That is how the standing
     * `--selfcheck` oracle failed about one run in ten with a whole-surface
     * difference that a second look agreed with (review §30, measured:
     * 16,963 px at 'scale130-reader-in', and the plane map printed with the
     * failure was one region short of the map the glass had been drawn under).
     * The scan is not wrong; the sample was torn.
     *
     * [block] runs ON the shell loop with NO other message queued and nothing
     * else pending, so nothing can move under it. Returns null when the shell
     * turned out not to be idle — the caller settles again and re-asks.
     */
    suspend fun <T : Any> sampleIdle(block: () -> T): T? {
        val out = kotlinx.coroutines.CompletableDeferred<T?>()
        post(Msg.Run(
            // a shell stopped between the ask and the loop answers "not idle"
            // rather than leaving the caller suspended for ever
            dropped = { out.complete(null) },
            // `queued == 1` is THIS message and nothing behind it
            action = { out.complete(if (queued.get() == 1 && idleApartFromMessages()) block() else null) },
        ))
        return out.await()
    }

    // ------------------------------------------------------------------ misc
    private fun splashFrame(): ByteArray {
        val g = Gray8(comp.width, comp.height)
        val f = FontSpec(Face.SYSTEM, 44, bold = true)
        val label = "DAMAGE"
        val w = text.measure(label, f)
        text.draw(g, Geometry.snapX((g.w - w) / 2), Geometry.snapY(g.h / 2 - 30), label, f, Level.HEAD)
        val f2 = FontSpec(Face.SYSTEM, 16)
        val sub = "damage-wm · first light pending"
        text.draw(g, Geometry.snapX((g.w - text.measure(sub, f2)) / 2), Geometry.snapY(g.h / 2 + 24), sub, f2, Level.DIM)
        return Zl.encodeCfw(Pack.rect(g, Rect(0, 0, g.w, g.h)))
    }

    // ------------------------------------------------------------------ sync
    /**
     * A sync record from the peer (HANDOFF.md §19.2). Returns false when the
     * shell is not running — the caller applies it to the store directly. On
     * the loop: FRESHEN the key first (last-write-wins must compare against
     * the state the user sees now, not the last debounced save), then apply
     * under LWW, then live-apply so the change is visible without a restart.
     */
    fun postSync(key: String, value: JsonObject, stamp: Long): Boolean {
        if (!running) return false
        post(Msg.Run {
            try {
                freshenSyncKey(key)
                if (persistence.tryApplyRemote(key, value, stamp)) liveApplySync(key, value)
            } catch (e: Exception) {
                Log.e("sync", "applying synced '$key' failed", e)
            }
        })
        return true
    }

    /** `window.<id>` → (window, null); `window.<id>.<subKey>` → (window, sub).
     *  Window ids carry no dots, so the first segment is always the id. */
    private fun windowForKey(key: String): Pair<DamageWindow, String?>? {
        if (!key.startsWith("window.")) return null
        val rest = key.removePrefix("window.")
        val id = rest.substringBefore('.')
        val sub = rest.substringAfter('.', "").ifEmpty { null }
        val w = windows.firstOrNull { it.id == id } ?: return null
        return w to sub
    }

    private fun freshenSyncKey(key: String) {
        // FRESHEN ONLY WHAT THIS DEVICE EVER HELD (review 2026-09-01 F2): a
        // virgin or unreadable-and-cleared store must not stamp its DEFAULTS over the
        // fleet's real records — if we never had the key, the peer's record
        // simply wins. Local edits made this session put the key first, so a
        // genuinely-used device still freshens against its live state.
        if (persistence.get(key) == null) return
        when {
            // re-encode ONLY after a local edit (R2#4): when live settings came
            // from the stored record itself (boot restore or a peer apply), a
            // re-encoding differs only by version shape — putting it would
            // re-stamp the peer's record on every handshake, the F5 ping-pong
            // through the freshen door
            key == "shell.settings" -> if (settingsLocallyEdited) persistence.put(key, settings.toJson())
            key.startsWith("window.") -> windowForKey(key)?.let { (w, sub) ->
                try {
                    if (sub == null) {
                        persistence.put(key, w.saveState())
                    } else {
                        // freshen only what the window HOLDS: an absent item is
                        // either never-had (the remote record should apply) or
                        // removed (its tombstone already carries a fresh stamp
                        // from the removal's save) — LWW decides both correctly
                        w.saveSubState()[sub]?.let { persistence.put(key, it) }
                    }
                } catch (e: Exception) {
                    Log.e("sync", "freshen of $key failed", e)
                }
            }
        }
    }

    private fun liveApplySync(key: String, value: JsonObject) {
        when {
            key == "shell.settings" -> {
                Log.i("sync", "settings updated from the peer")
                applySettings(ShellSettings.fromJson(value), persist = false)
            }
            key.startsWith("window.") -> {
                val (w, sub) = windowForKey(key) ?: return
                if (sub == null) {
                    Log.i("sync", "state of '${w.id}' updated from the peer")
                    w.restoreStateLive(value)
                    // per-ITEM last-write-wins on top of the main record
                    // (R3d#1): an old peer's whole-map record can win the
                    // MAIN key while this side holds NEWER sub-records — each
                    // newer sub wins its item back, and restoreSubState's own
                    // re-seat keeps the open item's screen position honest
                    val mainStamp = persistence.stamp(key)
                    for (k2 in persistence.keysWithPrefix("$key.")) {
                        if (persistence.stamp(k2) > mainStamp) {
                            persistence.get(k2)?.let { v2 ->
                                try { w.restoreSubState(k2.removePrefix("$key."), v2) }
                                catch (e: Exception) { Log.e("sync", "sub re-apply of $k2 failed", e) }
                            }
                        }
                    }
                } else {
                    Log.i("sync", "item '$sub' of '${w.id}' updated from the peer")
                    w.restoreSubState(sub, value)
                    // the window now HOLDS (or just removed) this item: it
                    // counts as reported (R3s#4) — without this, a user
                    // removal made before the next saveAll never tombstones
                    // and the peer's record resurrects it at the next restart
                    subReported.getOrPut(w.id) { HashSet() }.add(sub)
                }
                // repaint only when the change is on screen; the wheel owns the
                // screen while open (§4.3) and a parked window paints on focus
                if ((mode == Mode.WINDOW || mode == Mode.EXCLUSIVE) && current === w && !switcher.open) {
                    syncLayout()
                    composeFullSurface()
                }
            }
        }
    }

    /** [sync] = the shutdown form: the write has landed when this returns.
     *  The periodic form encodes on the loop and writes on the store's own
     *  thread (2026-09-05, §32), reporting a failed write back through the
     *  loop so the notice is raised where every other state mutation is. */
    private fun saveAll(sync: Boolean = false) {
        // settings are NOT re-put here (R2#4): applySettings(persist=true)
        // already persisted every local edit, and a blind re-encoding of a
        // peer-applied record would re-stamp it on the next save tick — the
        // exact echo the F5 fix suppressed at the apply site
        persistence.put("shell.state", buildJsonObject {
            put("current", current?.id ?: "")
            put("mode", mode.name)
            put("mainCursor", main.model.cursor.toString())
            put("notices", saveNotices())
        })
        for (w in windows) {
            try {
                persistence.put("window.${w.id}", w.saveState())
                // §16.4a sub-records: per-item blobs, individually stamped so
                // sync converges per ITEM. A stored sub-key the window no
                // longer holds gets the removal TOMBSTONE (an empty object) —
                // value-changed, so it re-stamps once and the removal syncs;
                // re-putting an existing tombstone is value-equal and free.
                // ⚠ Only keys the window ITSELF reported this session may be
                // tombstoned (review 2026-09-01 F2c/d): a failed restore, or a
                // build that does not speak sub-records, must never turn
                // "didn't load it" into a fresh-stamped removal of real data.
                // an EMPTY blob IS the tombstone: a window that returns one is
                // reporting a removal it did not mean, and the key is syncable
                // — refuse it loudly rather than fresh-stamp a deletion of the
                // peer's real record (review 2026-09-03, the Music mirror)
                val subs = w.saveSubState().filter { (sk, blob) ->
                    // said ONCE per key per session: saveAll runs every couple
                    // of seconds and a standing defect must not become a stream
                    blob.isNotEmpty().also {
                        if (!it && emptySubSaid.add("${w.id}.$sk"))
                            Log.e("shell", "${w.id}.saveSubState() reported '$sk' as an EMPTY " +
                                "object — that is the removal tombstone, not a record; refused")
                    }
                }
                val prefix = "window.${w.id}."
                val reported = subReported.getOrPut(w.id) { HashSet() }
                reported.addAll(subs.keys)
                for ((sk, blob) in subs) persistence.put(prefix + sk, blob)
                for (k in persistence.keysWithPrefix(prefix)) {
                    val sk = k.removePrefix(prefix)
                    if (sk !in subs && sk in reported) persistence.put(k, JsonObject(emptyMap()))
                }
            } catch (e: Exception) {
                Log.e("shell", "saveState of ${w.id} failed", e)
            }
        }
        if (sync) {
            noteSaveLanded(persistence.save())
        } else {
            persistence.saveAsync { landed ->
                // back onto the loop; a shell already stopped drops it (the
                // sync shutdown save reports for itself)
                if (running) post(Msg.Run(dropped = {}) { noteSaveLanded(landed) })
            }
        }
    }

    /** Raise once per failure streak (R3s#11): an all-day driver on a full
     *  disk must not learn at the next restart that nothing persisted. */
    private fun noteSaveLanded(landed: Boolean) {
        if (!landed && !saveFailureRaised) {
            saveFailureRaised = true
            services.notifyInternal("state", "state save FAILED — changes will not survive a restart", urgent = true)
        } else if (landed) saveFailureRaised = false
    }

    /** Unread notifications the user could still see must survive a restart
     *  (§9.1) — current box first, then the queue. */
    private fun saveNotices(): JsonArray = buildJsonArray {
        val all = ArrayList<Notifications.Notice>()
        notifications.current?.let { all.add(it) }
        all.addAll(notifications.queued())
        for (n in all) add(buildJsonObject {
            put("source", n.source)
            put("thread", n.thread)
            put("body", n.body)
            put("time", n.timeHHMM)
            put("emergency", n.emergency)
            put("appId", n.appId ?: "")
            put("target", n.target ?: "")
            put("read", n.read)
        })
    }

    private fun restoreNotices(shellState: JsonObject?) {
        val arr = shellState?.get("notices") as? JsonArray ?: return
        for (e in arr) {
            try {
                val o = e.jsonObject
                if (o["read"]?.jsonPrimitive?.booleanOrNull == true) continue
                notifications.enqueueRestored(Notifications.Notice(
                    source = o["source"]?.jsonPrimitive?.contentOrNull ?: continue,
                    thread = o["thread"]?.jsonPrimitive?.contentOrNull ?: "",
                    body = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
                    timeHHMM = o["time"]?.jsonPrimitive?.contentOrNull ?: "",
                    emergency = o["emergency"]?.jsonPrimitive?.booleanOrNull ?: false,
                    appId = o["appId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                    target = o["target"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                ))
            } catch (ex: Exception) {
                Log.w("shell", "unreadable persisted notice skipped: ${ex.message}")
            }
        }
    }

    companion object {
        /** Pack.level for every 8-bit value — the emitter's quantiser, tabled. */
        private val LEVEL = IntArray(256) { Pack.level(it) }
        /** Above this measured transfer term the link counts as slow (§32):
         *  the two regimes measured 20 and 125 ms/KB; 50 ≈ 20 KB/s. */
        const val SLOW_LINK_MS_PER_KB = 50.0
        /** Flushes between two regime flips, at least (§41). */
        const val REGIME_DWELL_FLUSHES = 10L
        /** Consecutive ImgResCmd refusals that put the glasses to sleep (§36). */
        const val REFUSALS_TO_SLEEP = 3
        /** The asleep shell's check pacing (§37.0): the glasses' own state is
         *  read by the 60 s device-info poll, so a check between polls would
         *  learn nothing new. */
        const val SILENT_PACING_MS = 60_000L
        /** One `silent` note per this many pacing checks while asleep (§42): an
         *  hour at the 60 s pacing. */
        const val SILENT_CHECKS_PER_NOTE = 60
        const val DIVERGE_EPISODES_MAX = 3
        const val DIVERGE_QUIET_CHECKS = 10

        /** How many depth regions a window may ask for inside the content
         *  area (§3, `DamageWindow.contentPlanes`). Every region splits the
         *  panel into more pieces, and the piece count is what the rect budget
         *  is spent on — a window that wants a dozen has misunderstood depth. */
        const val MAX_WINDOW_PLANES = 4

        /** Chrome never shrinks below the design size to fit a bar — the bar
         *  is already sized for it at 100 %; the cap only limits growth. */
        const val CHROME_SCALE_FLOOR = 1.0
        /** The list rhythm's inputs (review §29): the row face every list
         *  draws its main text in (Clear Sans 18 through the window's own
         *  transform — a Fira or Mono list inks a little less at the same
         *  scale and gets that much extra leading), the rows' text offset,
         *  and the lens's slack around two lines — 5 + 27 = 32 and
         *  2 × 27 + 10 = 64 at 100 %. */
        const val ROW_FACE_PX = 18
        const val ROW_TEXT_Y = 5
        const val LENS_SLACK = 10

        fun systemClock(): LocalClock {
            val now = java.time.LocalTime.now()
            val h12 = if (now.hour % 12 == 0) 12 else now.hour % 12
            return LocalClock(now.hour, now.minute,
                "%d:%02d".format(h12, now.minute), if (now.hour < 12) "AM" else "PM")
        }
    }
}
