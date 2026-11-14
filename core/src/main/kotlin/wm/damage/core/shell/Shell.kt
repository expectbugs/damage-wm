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
import wm.damage.core.transport.atlasCapacity
import wm.damage.core.transport.floorMeasured
import wm.damage.core.transport.transferMeasured
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.TextureCache
import wm.damage.core.transport.draw2
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
    /** R2 (RF-1): read from the store at construction — both hosts load it before they build the shell, an
     *  unloaded store answers null and so the defaults — because the phone's error sink and `onUrgent` read
     *  `Phone notifications` between the construction and the first start (it was the defaults until
     *  `startLocked` read the store). `startLocked` reads it again at every start, so an unreadable record is
     *  logged at ERROR twice (here, then there). Written on the loop and the start path, read from the
     *  host's threads: `@Volatile`. */
    @Volatile var settings: ShellSettings = ShellSettings.fromJson(persistence.get("shell.settings"))
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

    /** The status cell's width for the CURRENT chrome face, cached the way the
     *  clock cell's is: the widest state word beside the widest input echo
     *  (`Chrome.statusCellWidth`, `HANDOFF.md` §69). */
    private var statusWKey: Triple<String, Double, String>? = null
    private var statusWValue = Layout.STATUS_W
    private fun statusCellW(): Int {
        val key = Triple(settings.fontFace, settings.fontScale, settings.fontStyle)
        if (statusWKey == key) return statusWValue
        statusWValue = chrome.statusCellWidth()
        statusWKey = key
        return statusWValue
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
    /** The status cell's word as last derived (`Status.kt`) — tests and status lines. */
    val statusLine: String get() = statusWord
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
    /** The status cell's STATE (`Status.kt`, `HANDOFF.md` §69): derived on every pump from
     *  conditions that each have their own lifecycle ([refreshStatusWord]) — there is no status
     *  string to set, so nothing to clear and no exit that can clear another state's word. */
    @Volatile private var statusWord = ShellState.OK.word
    /** One notice per fault class per episode ([raiseFault]); cleared at every session start.
     *  [faultQuietMs] is the quiet period that closes an episode — a pacing knob, like [silentPacingMs]. */
    @Volatile var faultQuietMs = FaultEpisodes.QUIET_MS
    private val faults = FaultEpisodes { faultQuietMs }
    private var faultCloseGen = 0
    /** The atlas check found DRAW2 not in force on the glasses while the link state says it is:
     *  the compositor emits v1 shapes until the next session start (§69). Loop-only. */
    private var v2RefusedThisSession = false
    /** Stamps the minute/idle maintenance loops to ONE shell session. The
     *  keeper stops and restarts this shell all day (WiFi edges, handovers);
     *  a loop that only checked `running` would come back from its sleep to
     *  find the NEXT session running and keep going — every restart added a
     *  loop, accumulating wakeups forever on the all-day driver. A stale
     *  generation exits instead. P5 item 3: bumped at a start's first lines (with `running`), so the loops of
     *  the session before stop through a start in progress; also the identity [requestRebuild]'s coroutine
     *  carries. Written on the start path under `lifecycle`; read by the loops and that coroutine. */
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
    /** R2 (RH-6): the running session's loop, joined by [stopLocked] after its `done` so that a stop
     *  returns only once the loop's drain has run. Written at the launch and read and cleared by the
     *  stop, both under [lifecycle]. */
    private var loopJob: kotlinx.coroutines.Job? = null
    private var collectorLaunched = false

    /** Host-link status line for the status bar (set by the content client). */
    @Volatile var hostState: String = ""

    /** The host's build, as the journal records it at every session start
     *  (§41): the APK's version or the desktop's git stamp — a journal read
     *  a week later says which tree wrote it. Set by the host before start. */
    @Volatile var buildTag: String = ""

    /** Rows the host appends to Settings (the display target); set before start. */
    @Volatile var hostSettings: List<HostSetting> = emptyList()

    /** Build W (R6 P11, option A): Settings categories the HOST supplies for a feature that has no window yet — the
     *  phone's "Bastion" (the bus alert watcher, `bastion/BastionRows.kt`). Set before start. Listed after the
     *  windows' categories unless a registered window has the same name (case-insensitive): the window wins, so a
     *  Bastion window built later returns the same rows and nothing migrates ([settingsApps]). Each category's
     *  rows must be ONE stable list: Settings matches a staged row by identity. */
    @Volatile var hostCategories: List<Pair<String, List<HostSetting>>> = emptyList()

    /** §9.3: the phone is the out-of-band error channel — urgent internal
     *  notices also fire this hook (the APK raises a real phone notification). */
    @Volatile var onUrgent: ((source: String, body: String) -> Unit)? = null

    /** 0.64 (`HANDOFF.md` §75.4 calls 1-3; `DESIGN.md` §9.3): the host's own alert path for the low-battery
     *  latch — `(true, pct)` when the latch closes (below 20 %, not charging, once per discharge),
     *  `(false, pct)` when it opens again (the host withdraws what it showed). NOT [onUrgent]: it is not
     *  behind Global `Phone notifications`, and it needs no link and no session. Null = this host has no
     *  such path (the PC). Called on the shell's loop, or on the thread that delivers a transport event
     *  while no session runs — the hook must be safe on either; a throw is logged and journaled. It answers
     *  whether the host SHOWED it (0.64 R2, the review's RG-3): a phone with Damage's notifications or the
     *  channel off shows nothing and says no error, and the `lowbatt` note then does not claim the phone.
     *  The answer to `(false, pct)` is not read. */
    @Volatile var onGlassesBattery: ((low: Boolean, pct: Int) -> Boolean)? = null
    private val batteryLatch = BatteryLatch()
    /** A glasses battery reading was handled by THIS session's loop (the P4 review's finding 13): the
     *  reading held while no session ran is posted behind whatever the start itself queued, so once a
     *  live reading has been handled the held one is the older of the two and is dropped. Loop-only;
     *  reset on the start path before the loop launches, like the session flags beside `glassesSilent`. */
    private var batterySeenThisSession = false

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
        data class Run(val dropped: (() -> Unit)? = null,
            /** Build W, package G (E4): where it was posted from, for the `slow` note ("" untagged). */
            val tag: String = "", val action: () -> Unit) : Msg()
        data class Shutdown(val done: CompletableDeferred<Unit>) : Msg()
        object Pump : Msg()
    }

    /**
     * Build W (R6 P11, option A): a notice from a HOST feature that has a Settings category ([hostCategories]) but no
     * window — the phone's bus alert watcher (`bastion/AlertDelivery.kt`, `bastion/HomeWatch.kt`). That category's
     * own rows gated it before it got here, and [gatedBy] names the category, so Global `Notify · Damage` does not
     * gate it ([noticeAllowed]). It names no app: a tap only dismisses it. Never the host's urgent hook. Safe from
     * any thread (posted to the loop). A `Shell` member, not a [ShellServices] one, so the windows' contract and
     * its test fakes keep their shape.
     */
    fun notifyFromHost(source: String, body: String, thread: String, gatedBy: String) {
        val c = wallClock()
        post(Msg.Notice(Notifications.Notice("DAMAGE · $source", thread.ifEmpty { source }, body, c.hhmm,
            emergency = false, gatedBy = gatedBy)))
    }

    val services: ShellServices = object : ShellServices {
        override fun requestRender(window: DamageWindow) { post(Msg.Invalidate(window.id)) }

        override fun setOperation(op: String) {
            post(Msg.Run { if (opText != op) { opText = op; chromeDirty = true } })
        }

        override fun notifyInternal(source: String, body: String, urgent: Boolean,
            appId: String?, thread: String, target: String?, answer: Boolean) {
            // P5 item 6 (wait-no-escape finding 4, its sibling fix only): the host's hook under the same try as
            // `raiseFault`'s. Called on the start path of a session whose READ says Silent Mode (`enterSleep` →
            // `saySilentOnce`), a throw left the start past `transport.start()` and outside both its catches,
            // `running` set. The notice below still goes up; the log holds the hook's failure.
            if (urgent) try { onUrgent?.invoke(source, body) } catch (t: Throwable) {
                Log.e("shell", "the host's urgent hook failed for '$source' — the notice still goes up", t)
            }
            val c = wallClock()
            post(Msg.Notice(Notifications.Notice("DAMAGE · $source", thread.ifEmpty { source },
                body, c.hhmm, emergency = false, appId = appId, target = target, answer = answer)))
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
                    notifications.restoreUnderFinished(comp.composed)?.let { retire(it); comp.damage(it) }
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
                    notifications.restoreUnderFinished(comp.composed)?.let { retire(it); comp.damage(it) }
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

        override fun runOnShell(action: () -> Unit) = post(Msg.Run(tag = "runOnShell", action = action))

        // §72: while no session is live, the latest action per key is HELD (an older one
        // replaced, moved to the end so the flush keeps the order of the last updates) and
        // posted by releaseHeldPushes(); a live session posts it at once
        override fun runOnShell(key: String, action: () -> Unit) {
            synchronized(heldLock) {
                if (!windowsLive) {
                    holdLatestLocked(key, action)
                    return
                }
                // posted under the lock: a push can never overtake the held ones that
                // releaseHeldPushes is posting (the second review's ordering finding)
                post(Msg.Run(tag = key, action = action))
            }
        }

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
        // §72 (2026-11-08): a transport event with no session running is about a session that
        // is gone or never came up, and the keeper retrying a start that keeps failing made them
        // by the thousand. Each kind is decided by [keepWhileDown].
        if (m is Msg.Trans && !running && !keepWhileDown(m)) return
        // P5 item 4 (boundary-clear F5): §72's rule for the third state. `running` is true from a start's first line,
        // and a ring event that came while the start was still on its way (a touch on the lens view or a replica while
        // the phone scans for the glasses; the inputs after the one that woke a rest) was replayed into the new
        // session. Dropped, counted, said in the start's `queue` note; `Msg.In` (the harness entry) is not touched.
        if (m is Msg.Trans && m.ev is TransportEvent.Input && starting) {
            inputDroppedDuringStart.incrementAndGet()
            Log.i("shell", "a ring event (type ${m.ev.type}) came during a start in progress — dropped")
            return
        }
        enqueue(m)
    }

    /** [post] without the decision: for a message [keepWhileDown] has ALREADY kept (R2, RH-N1) — it is
     *  asked once per message, so its side effects (a battery reading judged, a note journaled, a count)
     *  are never run twice. */
    private fun enqueue(m: Msg) {
        queued.incrementAndGet()
        if (msgs.trySend(m).isFailure) {
            queued.decrementAndGet()
            Log.e("shell", "the loop refused $m — the shell is no longer serving messages")
        }
    }

    // ---------------------------------------------------------------- §72: the queue while down
    /** True from just before a session's loop launches until that session ends. While it is
     *  false, keyed window pushes ([ShellServices.runOnShell] with a key) are held, the latest
     *  per key. Written under [heldLock] with [held]; read by the services' keyed post.
     *  [holdPushes] clears it, [releaseHeldPushes] sets it. */
    @Volatile private var windowsLive = false
    private val heldLock = Any()
    private val held = LinkedHashMap<String, () -> Unit>()
    /** Pushes a newer push of the same key replaced while down (under [heldLock]). */
    private var supersededWhileDown = 0
    private val transEventsDroppedWhileDown = java.util.concurrent.atomic.AtomicInteger(0)
    /** P5 item 4: a start is on its way — true from its first line (with `running`) until it has its session, at the
     *  [sessionSerial] bump, so "during a start" means here what it means to a dev probe; false again on both failure
     *  paths. Written on the start path (under `lifecycle`), read by [post] on the collector's thread. */
    @Volatile private var starting = false
    /** Ring events dropped by [post] while [starting]; said and zeroed by the next start's `queue` note. */
    private val inputDroppedDuringStart = java.util.concurrent.atomic.AtomicInteger(0)

    /** The session is up: keyed pushes run as they come, and what was held while it was down is
     *  posted now — one action per key, in the order of their last update. Called on the start
     *  path just before the loop launches, so the loop runs them after the restore and the first
     *  compose, as it ran every queued push before §72. */
    private fun releaseHeldPushes(unanswered: List<Long> = emptyList()) {
        val n: Int
        val superseded: Int
        synchronized(heldLock) {
            windowsLive = true
            n = held.size
            // posted UNDER the lock (non-blocking: the channel is unlimited), so a keyed push
            // arriving now lands behind every held one, never ahead of an older value
            for ((k, a) in held) post(Msg.Run(tag = k, action = a))
            held.clear()
            superseded = supersededWhileDown
            supersededWhileDown = 0
        }
        val trans = transEventsDroppedWhileDown.getAndSet(0)
        if (superseded > 0 || trans > 0 || n > 0)
            journal.note("queue", "while down: $n held push(es) run now, $superseded superseded by a newer one, " +
                "$trans stale transport event(s) dropped")
        // P5 item 4: counted until the start had its session ([starting] false); a failed start's are said here too
        val inputs = inputDroppedDuringStart.getAndSet(0)
        if (inputs > 0) journal.note("queue", "$inputs ring event(s) that came during the start dropped")
        // P5 item 1: the flushes of the last session with no answer handled — an answer kept while down is one of
        // them (it waits in the queue behind this start), a late one from a lane is another
        if (unanswered.isNotEmpty())
            journal.note("queue", "${unanswered.size} flush(es) of the last session not answered when this session started " +
                "(id ${unanswered.joinToString(",")}): an answer that comes is journaled, not judged")
    }

    /** Under [heldLock]: keep [action] as the latest for [key], moved to the end. */
    private fun holdLatestLocked(key: String, action: () -> Unit) {
        if (held.remove(key) != null) supersededWhileDown++
        held[key] = action
    }

    /** §72: a transport event that arrives with no session running, by kind. True = queue it for
     *  the next session, as before. Everything else is handled here and NOT queued:
     *  - FlushDone stays queued: it completes the in-flight, atlas and page-staging bookkeeping,
     *    which would otherwise never empty (the second review's finding).
     *  - Text stays queued: a typed line must never vanish (handleTypedText).
     *  - Battery: the low-battery latch judges the reading NOW, at its arrival (0.64 — the phone's
     *    half needs no link and no session), and only the chrome's part is held, the latest only:
     *    the next session's chrome wants the newest level. The latch is not replayed: a held
     *    reading is posted BEHIND the readings the next start itself queues, so a replayed
     *    "19 %, not charging" could follow a live "19 %, charging" and say it a second time.
     *  - Note, Presented, Link and Fault are facts for the journal, journaled now. Package G (E2): each carries the
     *    time the transport built it (`atMs`), which the line's `t` is — during a start the events wait for the
     *    loop or for a failure's drain, up to the whole start (500 s on 2026-11-12), so "now" was not their time.
     *  - Input, Lease, SilentMode, DiagFlags and Wear describe a session that is gone. The
     *    next start re-reads the transport's state. Delivered late, a stale `Lease(held=false)`
     *    raised "lease lost" and requested a rebuild in the new session, and a stale
     *    `SilentMode(false)` woke it; a late "not worn" must not act on the next session either.
     *    Dropped, counted, said at the next start.
     *  Every kind is named: a new event kind does not compile until it is decided here. */
    private fun keepWhileDown(m: Msg.Trans): Boolean {
        when (val ev = m.ev) {
            is TransportEvent.FlushDone, is TransportEvent.Text -> return true
            is TransportEvent.Battery -> {
                // no session runs, so nothing can be painted: the host's own path is the only one
                val said = batteryReading(ev, canPaint = false)
                if (said.verdict == BatteryLatch.Verdict.SAY)
                    journal.note("lowbatt", "said at ${ev.glassesPct}% — " + lowbattWhere(said, canPaint = false, asleep = false, down = true), ev.atMs)
                synchronized(heldLock) {
                    // a session went live meanwhile: queue it as usual. `handleTransport` judges it a
                    // second time, which is NONE for the latch on the same reading — nothing repeats
                    if (windowsLive) return true
                    holdLatestLocked("shell.battery") { if (!batterySeenThisSession) applyBatteryToChrome(ev) }
                }
            }
            // package G (E2): each with the time the transport built it — the line is written now, and says `late`
            is TransportEvent.Note -> journal.note(ev.kind, ev.detail, ev.atMs)
            is TransportEvent.Presented -> journal.present(ev.seq, ev.workerUs, ev.copyUs, ev.transferUs, ev.path, ev.atMs)
            is TransportEvent.Link -> journal.note("link", if (ev.connected) "up: ${ev.detail}" else "DOWN: ${ev.detail}", ev.atMs)
            is TransportEvent.Fault -> journal.note("fault", "${ev.what}: ${ev.detail} (no session running)", ev.atMs)
            is TransportEvent.Input, is TransportEvent.Lease, is TransportEvent.SilentMode,
            is TransportEvent.DiagFlags, is TransportEvent.Wear -> transEventsDroppedWhileDown.incrementAndGet()
        }
        return false
    }

    // ---------------------------------------------------------------- 0.64: the low-battery latch
    /** What one battery reading did to the latch, whether a host hook was set, whether it ran, and whether it
     *  answered that the host showed it. */
    private class BatterySaid(val verdict: BatteryLatch.Verdict, val hookSet: Boolean, val hookRan: Boolean, val shown: Boolean)

    /** One glasses battery reading through the latch (`BatteryLatch`), from any thread. The hook is read
     *  ONCE: that one read decides whether anybody can be told (`canTell`), whether the hook is called,
     *  and what the caller's note may say. On SAY the hook hears `(true, pct)`; on REOPENED `(false, pct)`
     *  and the journal says it. A ring reading (no glasses level) is not a glasses reading.
     *
     *  Two threads can judge at once — the loop and the event collector, or a failed start's drain beside
     *  the collector (`dropTransportEventsOfFailedStart`) — so an older "not charging" can be judged after a
     *  newer "charging" and say it once more; the next reading (a minute on) opens the latch again and the
     *  host withdraws what it showed. The latch itself is locked; the ORDER of two readings is not kept. */
    private fun batteryReading(ev: TransportEvent.Battery, canPaint: Boolean): BatterySaid {
        val pct = ev.glassesPct ?: return BatterySaid(BatteryLatch.Verdict.NONE, hookSet = false, hookRan = false, shown = false)
        val hook = onGlassesBattery
        val charging = ev.glassesCharging == true
        val v = batteryLatch.reading(pct, charging, canTell = hook != null || canPaint)
        var ran = false
        var shown = false
        if (v != BatteryLatch.Verdict.NONE && hook != null) {
            // a host's hook that throws must not end the loop's message or, with no session running, the
            // collector that delivers every transport event
            try { shown = hook(v == BatteryLatch.Verdict.SAY, pct); ran = true } catch (t: Throwable) {
                Log.e("shell", "the host's glasses-battery hook failed at $pct%", t)
                journal.note("lowbatt", "the host's hook failed at $pct%: ${t.message ?: t::class.simpleName}")
            }
        }
        if (v == BatteryLatch.Verdict.REOPENED)
            journal.note("lowbatt", "open again — " + (if (charging) "a charging reading" else "$pct%"))
        return BatterySaid(v, hookSet = hook != null, hookRan = ran, shown = shown)
    }

    /** Who was told, for the `lowbatt` note of a SAY — from what the caller already read, nothing re-read. */
    private fun lowbattWhere(said: BatterySaid, canPaint: Boolean, asleep: Boolean, down: Boolean): String {
        val why = when { down -> "no session running"; asleep -> "asleep"; else -> "the link is not steady" }
        // R2 (RG-3): the phone is claimed only when the hook answered that it showed it
        val wontShow = "the phone will not show it — notifications are off for Damage or for its Glasses battery channel"
        return when {
            said.hookRan && said.shown && canPaint -> "the phone and the glasses"
            said.hookRan && said.shown -> "the phone only ($why)"
            said.hookRan && canPaint -> "the glasses only ($wontShow)"
            said.hookRan -> "nowhere ($wontShow; $why)"
            canPaint && said.hookSet -> "the glasses only (the phone's hook failed)"
            canPaint -> "the glasses only (this host has no phone path)"
            // reachable only through a hook that threw: with no hook and no paint the latch does not say
            // (a hook that answered "not shown" is the `nowhere` above: the latch counted the hook as a teller)
            else -> "nowhere (the phone's hook failed; $why)"
        }
    }

    /** The chrome's part of a battery reading — chrome never justifies its own flush (§8.3): the new
     *  value rides the next content flush or the idle chrome tick. LOOP-ONLY. `ev.ringPct` is ignored:
     *  the ring battery has no source and no cell (`CLAIMS.md`); the relay listener stays only to log
     *  a frame if a future CFW ever pushes one. */
    private fun applyBatteryToChrome(ev: TransportEvent.Battery) {
        ev.glassesPct?.let { glassesBattery = Chrome.Battery(it) }
        chromeDirty = true
    }

    /** The session is over (or never started): keyed pushes are held from now on. Every place a
     *  session ends reaches this, each with `running` already false — so it is also where the `power`
     *  note says `down` (0.64), AFTER the lock: the journal takes its own. */
    private fun holdPushes() {
        synchronized(heldLock) { windowsLive = false }
        // P5 item 3 (review 1b RH-5, its fix 2 — the root): a sleep's release coroutine and its pacing loop are keyed
        // on `silentGen`; bumped where every session ends, one that runs late fails its guard instead of turning the
        // lease off under the next start (or writing a real RELEASE into the next session). No pin from outside: it
        // needs a coroutine held between its launch and its first line.
        silentGen++
        notePower()
    }

    // ---------------------------------------------------------------- 0.64: the `power` notes
    private val powerLock = Any()
    /** The state the last `power` note named; guarded by [powerLock]. */
    private var lastPower = "down"

    /**
     * 0.64 (`HANDOFF.md` §75.4): one `power` note whenever the shell's power state CHANGES, so
     * `tools/journal_report.py` can charge each percent of the glasses' battery to the state it was
     * lost in: `lit` (painting), `shell-silent` (the shell's own silent clock screen), `rest`,
     * `fw-silent` (asleep for the firmware's Silent Mode, a refusal streak included) and `down` (no
     * session). A live state carries the glasses' level when one has been read (` 61%`); a session's
     * first note may have none — the start's call runs before the loop has handled a reading.
     *
     * ONE derived state, read where it can change: after every loop message, on the start path just
     * before the loop launches, and at every session end ([holdPushes]) — so the `mode =` sites and
     * the sleep's transitions need no hook each. The envelope: (a) `down` at every session end,
     * re-derived at every start; a process that ends without writing one is closed in the report by
     * the next process start's notes. (b) No wait. (c) The token itself; nothing is keyed on an older
     * value. (d) The loop, the start path and the stop paths: the compare, the set and the write are
     * under one lock, so the notes are in the order of the changes; `mode` is read only while a
     * session runs, which is the loop's or the start path's own thread. (e) `running` and `sleep`
     * are each read once. (f) Every `mode =` site and every sleep transition — the per-message call;
     * every session end — [holdPushes].
     */
    private fun notePower() {
        val live = running
        val s = sleep                                           // one read (it is `@Volatile`)
        val now = when {
            !live -> "down"
            s == SleepReason.REST -> "rest"
            s == SleepReason.SILENT -> "fw-silent"
            mode == Mode.SILENT -> "shell-silent"
            else -> "lit"
        }
        synchronized(powerLock) {
            if (now == lastPower) return
            lastPower = now
            journal.note("power", if (live) now + (glassesBattery?.let { " ${it.pct}%" } ?: "") else now)
        }
    }

    /** §72: a start that failed before this session's loop launched. Its transport events go
     *  through [keepWhileDown], the same decision as one arriving with no session; every other
     *  message stays queued, in its order, for the next session as before (a completion, a
     *  notice). The loop is not running, so nothing else drains the channel meanwhile. A post
     *  from another thread during the drain can land ahead of the re-posted ones (accepted:
     *  each window's completions carry their own sequence guards). */
    private fun dropTransportEventsOfFailedStart() {
        val keep = ArrayList<Msg>()
        while (true) {
            val m = msgs.tryReceive().getOrNull() ?: break
            queued.decrementAndGet()
            if (m !is Msg.Trans || keepWhileDown(m)) keep.add(m)
        }
        for (m in keep) enqueue(m)   // decided above: a kept Trans is not judged a second time (R2, RH-N1)
    }

    /** Loop liveness, for [quiescenceReport]: a stall that says WHICH — the
     *  loop ended, or it is parked inside a handler — is a stall someone can
     *  act on (review §31: three tests reported `queued=1` with no thread
     *  inside our code and no way to tell those two apart). */
    @Volatile private var loopAlive = false
    @Volatile private var loopHandling: String? = null
    @Volatile private var loopSince = 0L

    /** The word the status cell shows, from the pump's ONE link-state snapshot; a change marks
     *  the chrome dirty and rides the next flush or the idle tick like any other cell (§8.3). */
    private fun refreshStatusWord(st: LinkState) {
        val w = ShellState.of(
            connected = st.connected, silent = glassesSilent, rebuilding = restartRequested,
            leaseHeld = st.leaseHeld, halted = haltedEpoch != null,
            faultOpen = faults.anyOpen(System.currentTimeMillis()) || lastDivergence != null,
            linkSlow = linkParamsSlowShown).word
        if (w != statusWord) { statusWord = w; chromeDirty = true }
    }

    /**
     * An EVENT — a fault of class [cls] — becomes a notice, never a status-bar line (Adam,
     * 2026-09-16; `HANDOFF.md` §66.2, §69). LOOP-ONLY. One notice per class per episode
     * ([FaultEpisodes]): a repeat updates the one box's count at ×2, ×4, ×8 … — unless Adam
     * dismissed it ([dismissNotice] mutes the episode), and then it stays dismissed until the
     * class has been quiet for a minute; the phone hears an [urgent] class once per episode. A
     * fault notice ignores Global `Notify · Damage` (that row gates the WM's own courtesy notices;
     * an app's notices are gated by its own rows, `noticeAllowed`). It is POSTED,
     * never painted from here: this runs inside the loop's own catch blocks, and whatever the
     * box's paint does it does as an ordinary message, under the same catch — so it cannot
     * throw out of one either. The journal and the log are the caller's, as before.
     */
    private fun raiseFault(cls: String, body: String, urgent: Boolean = false, source: String = cls) {
        try {
            val v = faults.raise(cls, urgent, System.currentTimeMillis())
            if (v.ring) onUrgent?.invoke(source, body)
            val src = "DAMAGE · $source"
            val text = when (v.action) {
                FaultEpisodes.Action.POST -> body
                FaultEpisodes.Action.UPDATE -> "$body (×${v.count})"
                FaultEpisodes.Action.NONE -> null
            }
            if (text != null) post(Msg.Notice(Notifications.Notice(src, cls, text, wallClock().hhmm, fault = true)))
            if (v.action == FaultEpisodes.Action.POST) scheduleFaultClose()
        } catch (t: Throwable) {
            Log.e("shell", "raising the '$cls' fault notice failed — the log and the journal hold the fault itself", t)
        }
    }

    /** Whether a fault of the link's own traffic is news: with the link down, the session not
     *  started or a rebuild asked for, the cell's word already says so, and the fault belongs to
     *  a session that is ending — as a notice it would only wait in the queue as a stale box. */
    private fun linkUpAndSteady(): Boolean = linkUpAndSteady(transport.state.value)

    /** The same rule from a link state the caller already read (ONE read per decision). */
    private fun linkUpAndSteady(st: LinkState): Boolean = st.connected && st.started && !restartRequested

    /** The `fault` word clears itself: look again once the oldest open episode may have closed. */
    private fun scheduleFaultClose() {
        val at = faults.nextCloseAt() ?: return
        val gen = ++faultCloseGen
        scope.launch {
            delay(maxOf(50L, at - System.currentTimeMillis() + 50L))
            if (running) post(Msg.Run { if (gen == faultCloseGen && faults.anyOpen(System.currentTimeMillis())) scheduleFaultClose() })
        }
    }

    /**
     * Settings' per-app categories, read on every render. Per-app (2026-08-31): every registered window gets one —
     * its own rows plus the shell's font/size/style/depth rows (Settings itself renders the global style, so it
     * contributes only its own rows — none). Then each HOST category ([hostCategories], build W) whose name equals
     * no registered window's name, case-insensitive — the window wins.
     */
    internal fun settingsApps(): List<Pair<String, List<HostSetting>>> {
        val own = windows.mapNotNull { w ->
            val rows = w.appSettings() +
                (if (w === settingsWindow) emptyList() else styleRowsFor(w.id))
            rows.takeIf { it.isNotEmpty() }?.let { w.name to it }
        }
        val host = hostCategories.filter { (name, _) -> windows.none { it.name.equals(name, ignoreCase = true) } }
        return own + host
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

    /** A host's failure report as a fault of class [cls] (P7 item 2: the phone's line for the last run's
     *  uncaught error, `ShellService.onCreate`): shown whatever Global `Notify · Damage` says, not urgent —
     *  the host's own log sink decides the phone's half, as before. Any thread; posted, and raised on the loop
     *  (`raiseFault` is LOOP-ONLY). Posted before a session runs it waits for the first one; a stop that drains
     *  it first says so in the log (the host's log line and the journal hold the report itself). */
    fun raiseHostFault(cls: String, body: String) = post(Msg.Run(dropped = {
        Log.w("shell", "the host's '$cls' fault was not shown: the session ended before the loop reached it — the log holds it")
    }) { raiseFault(cls, body) })

    /** External input entry (keyboard harness, touch harness, remote shell). */
    fun postGesture(type: Int, source: Int = EvenHubMsg.SRC_RING) =
        post(Msg.In(type, source))

    /** The idle tick, on demand — tests of what may flush alone (§8.3). */
    fun postIdleTick() = post(Msg.IdleTick)

    /** The minute tick, on demand — tests of what runs on the wall-clock minute (the floor watch). */
    fun postMinuteTick() = post(Msg.MinuteTick)

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
    /** Rects shipped as staged draws so far (§66 — the self-check's gate reads it). */
    @Volatile var stagedRectsShipped = 0L
        private set

    /** The throughput readout as the bars show it (tests: §8.3's telemetry rule). */
    val chromeThru: String? get() = chrome.paintedState?.thru
    /** The status cell as last painted: the state word, the input echo, and how often its
     *  paint fell back to the fit path (zero in a correct build, `HANDOFF.md` §69). */
    val chromeStatus: String? get() = chrome.paintedState?.status
    val chromeEcho: String? get() = chrome.paintedState?.inputEcho
    val chromeStatusFitRuns: Int get() = chrome.statusFitRuns

    /** start/stop are serialized (round 3, phone D1): a stop arriving while
     *  start is still choreographing the display would otherwise save state
     *  that was never loaded, and leave two drivers on one transport. */
    private val lifecycle = Mutex()
    private var stateLoaded = false

    suspend fun start() = lifecycle.withLock { startLocked() }

    private suspend fun startLocked() {
        check(!running) { "shell already started" }
        running = true
        // P5 item 3 (boundary-clear F4): the session stamps move HERE, with `running`. Moved only after
        // `transport.start()` returned, the last session's idle and minute loops (keyed on `tickGen`) and its sleep's
        // release and pacing (keyed on `silentGen`) read their guard as true through a start in progress — a long
        // scan — and posted into a queue no loop drains. A failed start leaves them bumped, which is right.
        tickGen++
        silentGen++
        starting = true                   // P5 item 4: until the start has its session (the `sessionSerial` bump)
        // R4 item 3 (review 3A open finding (c)): the failed start's rollback below covers the prelude too. A throw
        // here (a window's `onRegistered`, the load, a restore) left `running` and `starting` set: every later start
        // read "shell already started" and `post` dropped every ring event as a start's.
        val adopted: Boolean
        val stampsAtLoad: Map<String, Long>
        val restoredId: String?
        val restoredMode: String?
        try {
        // registration happens ONCE per instance: a retried start after a
        // refused transport must not add a second Settings row or a second
        // event collector (round 4, shell #2)
        if (!::settingsWindow.isInitialized) {
            settingsWindow = SettingsWindow(chromeText, { settings }, { applySettings(it) },
                // the shell's own global font rows lead the host's rows
                { globalFontRows + hostSettings },
                { settingsApps() })
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
        stampsAtLoad = persistence.stamps()
        stateLoaded = true
        settings = ShellSettings.fromJson(persistence.get("shell.settings"))
        // §73: BEFORE any window restores or wraps — with cached text on, every layout of the
        // session measures by the cache's metric, whether or not its face is uploaded yet
        cachedText?.cacheMetric = settings.cachedText == "on"
        clockWKey = null; statusWKey = null
        settingsLocallyEdited = false   // live settings now MIRROR the store
        if (persistence.get("shell.settings") == null) {
            persistence.putBaseline("shell.settings", settings.toJson())  // R2#16
        }
        layout = layoutFor(settings)
        refreshStyles()   // every window draws through its per-app transform (Style.kt)
        // §73: the same windows live across sessions and keep what they laid out. A typography or
        // Cached text change that reached the store while no session ran (a synced record applied
        // store-direct) is read just above — and nothing told the windows: a book held from the
        // last session stayed wrapped by the old metric, its long lines cut. Keyed on what a layout
        // depends on: an unconditional call would re-wrap the open book at every link edge.
        val styleKey = styleKeyOf(settings)
        if (styleKeyAtLastStart != null && styleKeyAtLastStart != styleKey) {
            for (w in windows) {
                try { w.onFontScaleChanged(effScale(w.id)); w.onLayoutChanged() } catch (e: Exception) {
                    Log.e("shell", "re-layout of ${w.id} at the session start failed", e)
                }
            }
        }
        styleKeyAtLastStart = styleKey
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
        restoredId = shellState?.get("current")?.jsonPrimitive?.contentOrNull
        restoredMode = shellState?.get("mode")?.jsonPrimitive?.contentOrNull
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
        adopted = transport.state.value.started
        // §47: the radio's priority is a setting — told to the transport before
        // the connect so the first request already asks for it, and again on
        // an adopted session so a handback re-asserts it
        transport.setLinkPriority(settings.linkPriority)
        if (adopted) {
            Log.i("shell", "adopting the transport's live session (a takeover/handback) — no re-choreography, one keyframe")
        } else {
            transport.start(splashFrame())
        }
        } catch (e: Exception) {
            // refuse-to-start (capability gate, link failure): leave the shell
            // stopped, not half-running with no loop (review round 2 #B1)
            running = false
            starting = false              // P5 item 4: with no session running, `keepWhileDown` decides again
            holdPushes()
            // §72: this attempt's transport events would wait for the next session; a
            // keeper retrying a start that keeps failing must not pile them up
            dropTransportEventsOfFailedStart()
            journal.close()
            throw e
        }
        var loopStartedThisSession = false
        // R4 item 3: the rollback after `transport.start()` covers this stretch too (P5 item 6 fixed one throw in it)
        try {
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
        // 0.64: the same holds for a REST — it never crosses a session (a link end during one starts
        // an awake session, upstream's trap), and a probe taken before this line is not this session's.
        sleep = null
        silentGen++
        sessionSerial++
        starting = false                  // P5 item 4: the start has its session — a ring event from here on is this session's
        restWakeWanted = false
        restAskNotTakenSaid = false
        restartRequested = false
        refusalStreak = 0
        // P5 item 2 (boundary-clear F1): the other two counts and the halt are per session too — a count of 1 or 2
        // stood at the next start after a link end, and its first failure could make the third of a streak
        flushFailStreak = 0; keyframeFailStreak = 0; haltedEpoch = null
        reserveHeldAt = -1L; reserveYield = false   // P5 item 5: the reserve's ticks are per session
        silentNoticeShown = false
        rebuildRefusalShown = false
        faults.clear(); faultCloseGen++       // a new session's faults are news again (§69)
        // P7 item 2 (OS-2's latch): a failed Shutdown save set it and its notice went with that session's queue
        // (the drain): kept, the next session said nothing for as long as the streak lasted
        saveFailureRaised = false
        batterySeenThisSession = false        // 0.64: this session's loop has handled no battery reading yet
        v2RefusedThisSession = false          // the start arms DRAW2 afresh, and the atlas check reads it again
        val atStart = transport.state.value   // ONE read for the two decisions below
        // Build W, package G (G-design §1): a start whose READ said Silent Mode completes with no carrier
        // (`LinkState.carrierUp` false) — asleep here even when a push off has cleared `glassesSilent` since the
        // start rested and before this read: that push's `SilentMode(false)` is queued behind this and wakes the
        // shell into a rebuild. NOT for an ADOPTED session (its events while no shell ran were dropped, so no wake is
        // queued): with no carrier it holds no lease either, and the branch below rebuilds it at once.
        // Fix round 2 (R2.2): a start that RESTED (no carrier) says no notice — on glass a start in the case never
        // reached this line before package G, and with it every start in the case (the install, an app restart, a
        // reconnect) would post the urgent Silent Mode notice. An awake start that reads Silent Mode here (a push ON
        // in the start's tail) still says it, as a push ON in a live session does.
        if (atStart.glassesSilent || (!atStart.carrierUp && !adopted))
            enterSleep(SleepReason.SILENT, "the glasses say Silent Mode is on (the READ at session start)", notice = atStart.carrierUp)
        else if (adopted && atStart.started && !atStart.leaseHeld) {
            // 0.64 (the P4 review's finding 4): this start ADOPTED a live session (a keeper resume, a
            // handback) whose lease is not held and whose glasses do not say Silent Mode — the session of a
            // driver that was asleep: a rest, or a refusal-streak sleep. That driver's release stopped the
            // renewals, the keepalive and the carrier refresh and stood the watchdog down, and the
            // transport's `leaseWanted` returns to true only inside `transport.start()`, which an adopted
            // start does not call. This shell starts awake (`sleep = null` above), so it would paint into
            // no lease until the link happened to end. The rebuild is the same one a wake is: the next
            // start is a fresh one and takes the lease. (A lease that LAPSED under the driver before reads
            // the same here and gets the same answer — on a Damage build its flags went with it, which is
            // what a lapse under a running shell rebuilds for.)
            // Keyed on THIS shell's own adoption, not on the state alone: a fresh `transport.start()`
            // returns with the lease held, and a state that says otherwise for an instant must not end a
            // session nobody released. NOT covered: a driver that claims such a session through the seam —
            // there the adoption is the far end's (`RemoteTransportServer`), this flag is false, and the
            // seam carries no word of it (P4's Q9: the seam is left as it is); that driver paints into no
            // lease until it leaves, and the owner's own shell rebuilds on the hand-back, here.
            journal.note("keeper", "adopted a session whose lease is not held and whose glasses do not say Silent Mode " +
                "(released by a driver that was asleep, or lapsed) — rebuilding the session")
            Log.w("shell", "adopted a session with no lease and no Silent Mode: rebuilding it")
            requestRebuild("adopted a session whose lease was not held (a driver that was asleep released it, or it lapsed)")
        }

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
            // §40: a new session, a fresh atlas — unless the glasses still
            // hold the old one (§54, the bounded skip: the rebuild's acquire
            // was a renewal on both arms). Decided BEFORE the first compose,
            // so the fonts that compose draws are the ones packed first — or
            // the ones already on the glasses; uploaded AFTER the keyframe
            // below, chunk by chunk, only while nothing else is pending, so
            // the first paint and every gesture come first.
            journal.note("build", buildTag.ifEmpty { "unstamped host" } + " · session start via ${transport.state.value.transportName}")
            linkRegimeSessionStart(adopted)
            inputAwaitsSlot = false; inputFlushPending = false     // a gesture of the last session flags nothing here
            inputWaitSince = -1L                                  // package G (E3), with the flag
            // P5 item 1 (twice-read F1 = boundary-clear F2): a completion carries no session, only its id. What the
            // last session left in flight is cleared here — its answers, kept while down or late from a lane, are
            // journaled when they come and judged by nobody (`completeFlush`); every start composes the whole
            // surface and keyframes below, so nothing of an older flush needs a rollback. Said in the start's
            // `queue` note. The map is the loop's, and no loop runs here (the old one was joined by the stop).
            val unanswered = inflightFlushes.keys.sorted()
            inflightFlushes.clear()
            mirrorSpanLo = Int.MAX_VALUE; mirrorSpanHi = -1; mirrorWhole = true    // P6 item 5: the first check is whole
            atlasAtSessionStart(adopted)
            composeFullSurface()
            comp.requestKeyframe()
            // fix round 2 (R2.4): only an AWAKE start enables the atlas. A start that rests has no flags in force
            // (`draw2` false): it built a v1 atlas and moved the layout metric, every window re-wrapped while asleep,
            // and the wake's session moved it back and re-wrapped again before its first flush. Every wake is a
            // session start (`wakeGlasses`, `wakeFromRest`), and that start enables it
            if (settings.cachedText == "on" && sleep == null) post(Msg.Run { atlasEnable() })
            if (notifications.showNextIfIdle()) {
                if (quiet) scheduleSilentDismiss() else scheduleGrace()
                updatePlanes()   // the restored box enters the plane map now (round 3 S3)
            }

            // §72: the session is up and its loop about to run — window pushes run as they
            // come, and the ones held while it was down (the latest per key) run now. Not any
            // earlier: a start that fails before this line keeps them held, bounded by key.
            notePower()                   // 0.64: the session's state as it starts (`lit`, or asleep from its first frame)
            releaseHeldPushes(unanswered)
            loopJob = scope.launch { loop() }
            loopLaunched = true
            loopStartedThisSession = true
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
            val gen = tickGen             // P5 item 3: bumped at the start's first lines (a rebuild asked above captured it)
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
            starting = false              // R4 item 3: a throw before the session's `sessionSerial` bump left it set
            holdPushes()
            if (!loopStartedThisSession) dropTransportEventsOfFailedStart()   // §72, as above
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
            holdPushes()
            // never write defaults over a state never read. A save that does not land raises the `state` fault
            // HERE, off the loop (`raiseFault` is LOOP-ONLY; P7 item 2): no loop exists to share `faults`,
            // `lifecycle` is held, the notice waits in the channel for the next session, and `startLocked`
            // clears the episodes' table first
            if (stateLoaded) saveAll(sync = true)
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
                    holdPushes()          // §72: every place the session ends holds pushes
                }
            }
        }
        // R2 (RH-6): `done` is completed INSIDE the Shutdown's arm; the loop still has its drain to run (a
        // probe's answer, a synced record stored, a flush's answer kept for the next session). Returned
        // before it, a stop let a start that followed run beside the old loop's drain. Nothing the drain
        // does waits on this stopper or takes `lifecycle`; no caller of `stop` runs on the loop.
        loopJob?.join()
        loopJob = null
        if (stopTransport) transport.stop()
        if (cachedText != null && wm.damage.core.gfx.IconPaint.recorder === cachedText) wm.damage.core.gfx.IconPaint.recorder = null
        journal.close()
    }

    // ------------------------------------------------------------------ loop
    private suspend fun loop() {
        loopAlive = true
        var leftThroughShutdown = false    // P5 item 1: the drain re-posts what it keeps only then
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
            val slowFrom = SlowFrom(loopClock, current?.id, mode)       // package G (E4): read only if the handler is slow
            val handlerT0 = System.nanoTime()
            if (!running && m !is Msg.Shutdown) {
                // R2 (RE-4's class): an answer that throws must not end the loop with this message counted
                if (m is Msg.Run && m.dropped != null)
                    try { m.dropped.invoke() } catch (e: Exception) { Log.e("shell", "a dropped message's answer failed", e) }
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
                        reserveCheck()              // P5 item 5: the input reserve's way out
                    }
                    is Msg.RestTick -> if (m.gen == restGen && mode == Mode.MAIN && !main.resting) {
                        main.resting = true
                        composeContent()
                    }
                    is Msg.GraceTick -> handleGrace(m.gen)
                    is Msg.SilentTick -> handleSilentTick(m.gen)
                    is Msg.SaveTick -> if (m.gen == saveGen) saveAll()
                    is Msg.Notice -> handleNotice(m.n)
                    // P6 item 2 (LC-1): a parked window's request composes nothing; its divider tick rides the chrome
                    is Msg.Invalidate -> if (!showsRequestOf(m.windowId)) chromeDirty = true
                        else if (mode == Mode.EXCLUSIVE) paintExclusiveDelta()
                        else if (mode != Mode.SILENT) { syncLayout(); composeContent() }
                    is Msg.Run -> m.action()
                    is Msg.Shutdown -> try {
                        holdPushes()              // §72: keyed pushes are held from here on
                        dropKeyboard()    // the draft goes back to its requester BEFORE the save (review 2026-09-01 K5)
                        // the focused window's session-bound state ends with the
                        // session (a focused poll pace, a pane subscription) —
                        // start() re-activates the restored window (R2-W13)
                        try { current?.onDeactivate() } catch (e: Exception) { Log.e("shell", "onDeactivate at shutdown", e) }
                        saveAll(sync = true)      // the last state must be ON DISK before stop() returns
                    } finally {
                        // P5 part a (review 1b RE-4's sibling): `running` goes false only AFTER the final save. Set
                        // before it, a synced record that came during the save met `postSync` false, was stored
                        // directly by the host's applier, and the save then put the live state over it under a fresh
                        // stamp (pushed back to the peer). Now it is posted behind this message and the drain's
                        // `dropped` arm stores it after the save. A message another thread posts meanwhile (a tick, a
                        // probe, a transport event) goes to the drain, which decides it as `post` would have.
                        // R4 item 2 (review 3A-3): on EVERY exit from the steps above. A throw there (an `Error`, an
                        // encode) left `running` true and `done` open: the loop served on with the pushes held and
                        // `stop()` did not return. The throw still reaches the catch below and its fault.
                        running = false
                        m.done.complete(Unit)
                    }
                    Msg.Pump -> {}
                }
            } catch (e: Exception) {
                // LOUD AND PROUD: a shell-loop error reaches a notice (and the
                // phone), the journal, and the log — and the loop keeps serving input.
                Log.e("shell", "loop error handling $m", e)
                journal.note("error", e.toString())
                raiseFault("loop", "error handling ${m::class.simpleName}: ${e.message ?: e::class.simpleName}", urgent = true, source = "shell")
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
                raiseFault("loop", "ERROR handling ${m::class.simpleName}: ${e.message ?: e::class.simpleName} — restart when convenient", urgent = true, source = "shell")
            }
            handlerNsThisMsg = System.nanoTime() - handlerT0
            if (handlerNsThisMsg >= SLOW_HANDLER_NS) noteSlow(m, handlerNsThisMsg, slowFrom)
            // P6 item 1 (LC-7): every handled message's time, whether or not its turn submits
            loopCost.getOrPut(if (m is Msg.Trans) m.ev.javaClass else m.javaClass) { LoopCost() }.add(handlerNsThisMsg)
            val pumpT0 = System.nanoTime()
            val pumped = running
            try {
                if (pumped) pump()
            } catch (e: Exception) {
                Log.e("shell", "pump error", e)
                journal.note("pump-error", e.toString())
                raiseFault("pump", "pump error: ${e.message ?: e::class.simpleName}", urgent = true, source = "shell")
            } catch (e: Error) {
                Log.e("shell", "pump ERROR — the loop keeps serving; restart when convenient", e)
                journal.note("pump-error", e.toString())
                raiseFault("pump", "pump ERROR: ${e.message ?: e::class.simpleName} — restart when convenient", urgent = true, source = "shell")
            }
            if (pumped) pumpCost.add(System.nanoTime() - pumpT0)
            notePower()                   // 0.64: a state change this message made (a string compare, no layout)
            queued.decrementAndGet()
            loopHandling = null
            if (!running && m is Msg.Shutdown) { leftThroughShutdown = true; break }
        }
        } finally {
            loopAlive = false
            // whatever is still in the channel will never be handled: the
            // count must not outlive the loop that would have cleared it.
            // 0.64 (the P4 review's finding 9): a `Run` among them is TOLD — its `dropped` runs here as
            // it does for one that reaches a stopped loop above. Without it a caller's promise "an
            // answer either way" ([Msg.Run], `sampleIdle`) did not hold behind a Shutdown: the probe
            // `rest=…` queued there left its `probe` note and nothing else.
            // R2 (RH-N1): a transport event is decided by [keepWhileDown], as a post with no session is: a
            // flush's answer or a typed line queued behind the Shutdown is kept for the next session (it was
            // counted here and gone — the flush's in-flight entry then stayed for the life of the process:
            // no quiescence, no mirror check), the rest journaled or counted there. The kept ones go back
            // in the channel after the drain, decided once.
            // P5 item 1 (twice-read F1's corrected drain): kept ones go back only when the loop left through the
            // Shutdown. This `finally` also runs when the loop's scope is cancelled, with no loop left to take a
            // re-posted message: counted in `queued` there, it would read as work forever. Then they are counted
            // with the rest, and the journal names the kinds (the log line was the only trace).
            val left = LinkedHashMap<String, Int>()
            val keep = ArrayList<Msg.Trans>()
            while (true) {
                val m = msgs.tryReceive().getOrNull() ?: break
                queued.decrementAndGet()
                if (m is Msg.Trans && leftThroughShutdown) { if (keepWhileDown(m)) keep.add(m); continue }
                val kind = if (m is Msg.Trans) "Trans.${m.ev::class.simpleName}" else m::class.simpleName ?: "?"
                left[kind] = (left[kind] ?: 0) + 1
                if (m is Msg.Run && m.dropped != null) {
                    try { m.dropped.invoke() } catch (e: Exception) { Log.e("shell", "a dropped message's answer failed", e) }
                }
            }
            if (left.isNotEmpty()) {
                val kinds = left.entries.joinToString(", ") { "${it.key}×${it.value}" }
                Log.w("shell", "the loop ended with ${left.values.sum()} message(s) unhandled: $kinds")
                journal.note("queue", "the loop ended with ${left.values.sum()} message(s) unhandled" +
                    (if (leftThroughShutdown) "" else " (its scope ended, not a stop)") + ": $kinds")
            }
            if (keep.isNotEmpty()) Log.i("shell", "the loop ended with ${keep.size} transport event(s) kept for the next session")
            for (m in keep) enqueue(m)
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
                "typed line not delivered — $where (focus a window that types, like Tmux)", answer = true)
            return
        }
        composeContent()
        scheduleSave()
    }

    private fun handleInput(type: Int, source: Int) {
        // a ring event while the glasses are silent: the person is there —
        // check now rather than at the next pacing tick (§36, §37.0); the
        // input itself is handled normally, the surface stays current.
        // 0.64: an input event during a REST is its WAKE, and the waking event is CONSUMED (the P4
        // review's finding 2) — the panel is dark, so a ring tap would otherwise commit whatever the
        // cursor sits on, unseen (`DESIGN.md` §1.7's stray tap). Any input wakes: the ring, a temple, a
        // replica's key — tested here, before the ring-only filter. SILENT's path is as it was.
        when (sleep) {
            SleepReason.SILENT -> silentTick("input")
            SleepReason.REST -> {
                wakeFromRest("an input event (type $type, source $source) — the event is not delivered")
                return
            }
            null -> {}
        }
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
            // a FIXED vocabulary (`InputEcho`): the status cell is measured for it
            EvenHubMsg.EV_CLICK -> InputEcho.TAP
            EvenHubMsg.EV_DOUBLE_CLICK -> InputEcho.DOUBLE
            EvenHubMsg.EV_SCROLL_TOP -> InputEcho.UP
            EvenHubMsg.EV_SCROLL_BOTTOM -> InputEcho.DOWN
            EvenHubMsg.EV_RING_LONG_PRESS -> InputEcho.HOLD
            // a bare release means "a touch ended" and follows almost every
            // swipe (HANDOFF.md §11): echoing it would permanently overwrite
            // the last real gesture, so it echoes only when a chord is armed
            EvenHubMsg.EV_RING_LONG_PRESS_RELEASE ->
                if (chordArmedAtMs != 0L) InputEcho.RELEASE else inputEcho
            else -> InputEcho.unknown(type)
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
                            "that notice's app (${n.appId}) is not available on this device", answer = true)
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
        // the box's strings of this frame go with its pixels: a lift is followed by a slide that
        // moves the records under it, or by a requeue that never paints this rect again
        retire(lifted)
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
        // an entry from a menu commit or a deep link paints the whole panel twice in one frame
        // (the handler enters the mode, then the commit path composes the content)
        retire(Rect(0, 0, comp.width, comp.height))
        comp.composed.fillRect(Rect(0, 0, comp.width, comp.height), Level.BG)
        try {
            w.paintExclusive(comp.composed, layout.safe, full = true)
        } catch (e: Exception) {
            Log.e("shell", "paintExclusive of ${w.id} failed", e)
            raiseFault("window", "${w.id}: the exclusive paint failed: ${e.message ?: e::class.simpleName}", urgent = true)
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
        // 0.64 R1: what this frame has recorded so far. The window fills the bands it repaints and
        // only says which afterwards, so the drop that every other painter here makes BEFORE its
        // fill ([retire]) is made after this one, on the records that were there before it
        val rec = cachedText
        val olderDraws = rec?.frameDraws() ?: emptyList()
        val olderImages = rec?.frameImageDraws() ?: emptyList()
        val rects = try {
            w.paintExclusive(comp.composed, layout.safe, full = false)
        } catch (e: Exception) {
            Log.e("shell", "paintExclusive of ${w.id} failed", e)
            raiseFault("window", "${w.id}: the exclusive paint failed: ${e.message ?: e::class.simpleName}", urgent = true)
            emptyList()
        }
        if (rects.isEmpty()) return
        // with the window full two repaints of one band share a frame (modeled: the visualizer's
        // eight a second against a flush of 70 ms and up): the earlier paint's records under a rect this
        // paint returned are stale — a string that changed fails the band's proof, one that did not
        // ships twice
        rec?.dropOlderRecords(olderDraws, olderImages, rects)
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
        comp.requestReseed()
        if (notifications.showNextIfIdle()) {
            updatePlanes()
            paintNotification()
            scheduleGrace()
        }
        scheduleSave()
    }

    // ------------------------------------------------------------------ menu
    /**
     * [r] of the composed surface is about to be painted over whole — an overlay's box, a region's
     * repaint, a restore from an under-snapshot. What this frame recorded there no longer describes
     * the surface, and a record that only reaches into it goes too: a cached draw cannot be clipped.
     * `Slide.paintInto` has done this for a strip since §41; until 0.64 every other painter left its
     * predecessor's records behind, and a frame that painted a region twice either refused the rect
     * (the context menu's box: pixels on 52 of 52 first paints on glass, `HANDOFF.md` §75.3) or
     * shipped each string twice. The rule for a painter in this file: a fill of a region that is
     * then painted again has a `retire` of that region before it.
     *
     * On the shell's own recorder ([cachedText], there whenever the host wrapped its rasterizer in
     * one), not `comp.cachedText`, which is null while no font is live. A null [r] is a surface
     * that is not up: nothing to drop.
     */
    private fun retire(r: Rect?) { if (r != null) cachedText?.dropRecords(r) }

    private fun paintMenu() {
        retire(menu.rect(layout))          // the rows under the box, and the menu's own earlier paint of this frame
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
            // the content's pixels are back; the menu's strings of this frame are not on them
            if (r != null) { retire(r); comp.damage(r) } else composeContent()
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
            raiseFault("menu", "action failed: ${e.message}")      // P7 item 2: a failure, shown whatever the row says
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
        retire(keyboard.rect(layout))
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
            if (r != null) { retire(r); comp.damage(r) } else composeContent()
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
                    raiseFault("keyboard", "key failed: ${e.message}")   // P7 item 2, as `commitMenu`
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
            raiseFault("keyboard", "typed text failed: ${e.message}")   // P7 item 2, as `commitMenu`
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
            services.notifyInternal(w.id, "couldn't open the requested item ($target) — it may be gone", answer = true)
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
        retire(Rect(0, 0, comp.width, comp.height))
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
                notifications.restoreUnderFinished(comp.composed)?.let { retire(it); comp.damage(it) }
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
        retire(layout.switcherPanel)       // the previewed window's rows under the panel, the last spin frame's names
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
        if (n.emergency || n.fault) return true
        val kind = n.source.substringBefore('·').trim().uppercase()
        return when (kind) {
            "SMS", "MMS" -> settings.notifySms
            "MAIL" -> settings.notifyMail
            // MUSIC is NOT gated here: the window owns its six Notify rows
            // (WINDOWS.md §1), and the Global "Notify · Music" row that APKs up
            // to 0.17 carried could have persisted a false that no row could
            // undo — a hidden gate (docs audit 2026-09-02)
            // `Notify · Damage` is the WM's own events (`DESIGN.md` §4.5): a notice that names its app
            // is gated by that app's own rows in its own Settings category, never by a Global row
            // (P7 item 1, OS-1). Inside this arm, not ahead of the `when`: an `appId` on an SMS or MAIL
            // notice does not get it past `Notify · SMS` / `Notify · Mail`. An answer to Adam's own gesture
            // (`Notice.answer`, P7 item 2) is not a courtesy event either. A host category's notice
            // (`Notice.gatedBy`, build W: the bus watcher's "Bastion") was gated by that category's own rows.
            "DAMAGE" -> n.appId != null || n.answer || n.gatedBy != null || settings.notifyDamage
            else -> true
        }
    }

    private fun dismissNotice(markRead: Boolean, clearing: Boolean = true) {
        settleSlidesForOverlay()      // the furl restores from a snapshot:
        val n = notifications.dismiss(markRead, clearing)  // settle the base
        if (n?.fault == true) faults.mute(n.thread)        // a dismissed fault stays dismissed for its episode (§69)
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
        retire(notifications.boxRect(layout, silent))      // the box at its current unfurl step: what `paint` fills
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
        retire(Rect(0, 0, comp.width, comp.height))
        SilentMode.paintAll(comp.composed, layout, c.hh, c.mm, settings.silentClock, silentSmallPainter(c))
        comp.damageAll()
        if (notifications.active) {
            paintNotification()
            scheduleSilentDismiss()
        }
    }

    // ------------------------------------------------------------------ ticks
    private fun handleMinute() {
        noteLoopCost()            // P6 item 1 (LC-7): the minute's loop time, before any early return below
        noteLinkRegime()          // §47: a parameter change while idle shows within the minute
        watchFloor()
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
                applyBatteryToChrome(ev)
                if (ev.glassesPct != null) batterySeenThisSession = true    // the held reading is older than this one
                // 0.64, the low-battery latch (`HANDOFF.md` §75.4 calls 1-3). The glasses' half needs a
                // shell that can paint: awake (§36 — never an image into a pair that refuses them) and a
                // link that is up and steady. One read of each.
                val asleep = glassesSilent
                val canPaint = !asleep && linkUpAndSteady()
                val said = batteryReading(ev, canPaint)
                if (said.verdict == BatteryLatch.Verdict.SAY) {
                    val pct = ev.glassesPct!!
                    // Posted directly, not through `raiseFault`: no fault episode opens and the status
                    // cell does not say `fault`. `fault = true` is used for its one effect — the notice
                    // is shown whatever Global `Notify · Damage` says (`noticeAllowed`). It is NOT
                    // withdrawn when the latch opens again (the phone's notification is): it stays in
                    // the queue as the record that the level was crossed.
                    if (canPaint) post(Msg.Notice(Notifications.Notice("DAMAGE · glasses", "battery",
                        "glasses battery $pct% — time to charge", wallClock().hhmm, fault = true)))
                    journal.note("lowbatt", "said at $pct% — " + lowbattWhere(said, canPaint, asleep, down = false), ev.atMs)
                }
            }
            // 0.64 build 1 of rest mode READS only (`DESIGN.md` §0): a wear report acts on nothing here —
            // the transport's `wear` note is the record, and `LinkState.worn` the state
            is TransportEvent.Wear -> {}
            is TransportEvent.SilentMode ->
                if (ev.on) enterSleep(SleepReason.SILENT, "the firmware's Silent Mode is on")
                else when (sleep) {                                 // one read
                    SleepReason.SILENT -> wakeGlasses("the firmware's Silent Mode is off")
                    // not reachable through the transport today (it emits CHANGES only, and an ON would have
                    // changed the reason first); the guard keeps a stray OFF from waking a rest if it ever is
                    SleepReason.REST -> journal.note("rest", "the firmware's Silent Mode went off while resting — the rest goes on")
                    null -> {}
                }
            is TransportEvent.Lease -> {
                if (!ev.held && glassesSilent) {
                    // released on purpose (§36): not a loss, no keyframe, no notice
                    chromeDirty = true
                } else if (!ev.held) {
                    // fail-open fired: stock repainted; a keyframe is required on
                    // reacquire (§5.16) and the failure is surfaced loudly
                    raiseFault("lease", "framebuffer lease lost — ${ev.detail}", urgent = true)
                    comp.requestKeyframe()
                    atlasLapsed()                // §40: the cache went with the lease
                    // a Damage build cleared the session's flags and the cache's size with the lease
                    // (`FIRMWARE.md` §3, §4): the renewal that follows is a fresh acquire nothing re-arms,
                    // so the session is rebuilt — the start takes the size, arms DRAW2 and the wanted
                    // flags again and the shell adopts the glasses as they are (2026-09-15 review)
                    if (transport.state.value.damageContract > 0 && !restartRequested)
                        requestRebuild("the framebuffer lease lapsed on a Damage build — its flags went with it")
                } else {
                    // §40: the cache is allocatable again. Not gated on the status TEXT any more
                    // (2026-09-16 review): any fault between the lapse and the re-acquire
                    // overwrote it — and a fault is near certain there, since the atlas chunk in
                    // flight at the lapse is refused — and the upload then never restarted, so
                    // cached text stayed dark for the rest of the session.
                    atlasLeaseBack()
                }
                chromeDirty = true
            }
            is TransportEvent.Link -> {
                // the cell's `no link` is derived from the link state itself ([refreshStatusWord])
                // §41: the arm and the reason reach the journal — thirteen
                // alternating-arm session rebuilds in one day (2026-09-06)
                // had left no trace but a "characteristic gone" fault
                journal.note("link", if (ev.connected) "up: ${ev.detail}" else "DOWN: ${ev.detail}", ev.atMs)
                chromeDirty = true
            }
            is TransportEvent.DiagFlags -> {
                val setFlags = ev.flags.filterValues { it }.keys
                if (setFlags.isNotEmpty()) {
                    // any sticky flag is a HARD error: panic keyframe (§9.2b)
                    journal.note("panic", setFlags.joinToString())
                    raiseFault("diag", "divergence flags: $setFlags — keyframing", urgent = true)
                    comp.requestKeyframe()
                    scope.launch { transport.clearDiagFlags() }
                }
            }
            is TransportEvent.Fault -> {
                if (glassesSilent && ev.what in FAULTS_EXPECTED_ASLEEP) {
                    // a refusal that lands while the glasses are silent (a
                    // frame in flight at the sleep) is the expected answer,
                    // not a fault to shout about (§36). 0.64 (the P4 review's finding 3): the firmware
                    // MODEL's own refusal joins `imgres` — a v2 op, a cached draw or a cache write
                    // decoded after the release has no lease, which the simulator reports as `decode`
                    // and a hardware transport's mirror as `mirror/decode`; a later fragment of an image
                    // already on its way at the release is how one gets there. Raised as a fault it was
                    // a notice waiting in the queue for the wake. The kind follows the sleep's reason
                    // (finding 11): a rest writes no `silent` note.
                    journal.note(sleepKind(), "refused while asleep (${ev.what}): ${ev.detail}", ev.atMs)
                } else {
                    journal.note("fault", "${ev.what}: ${ev.detail}", ev.atMs)
                    // with the link down or the session rebuilding the cell already says so, and a
                    // fault of the ending session would only wait in the queue as a stale box; an
                    // `imgres` is a refused image — never answered with a box's images (§36), its
                    // streak puts the glasses to sleep and THAT is said once
                    if (ev.what != "imgres" && linkUpAndSteady())
                        raiseFault("transport/${ev.what}", "${ev.what}: ${ev.detail}",
                            urgent = ev.what in URGENT_TRANSPORT_FAULTS, source = "transport")
                }
            }
            is TransportEvent.Note -> {
                journal.note(ev.kind, ev.detail, ev.atMs)   // a fact: the journal only (E2: with its own time)
                if (ev.kind == "link") noteLinkRegime()   // §47: the transport's own parameter facts
                // the one dev probe that is the shell's (the ledger lives here): the transport journals
                // every probe as this note and sends nothing for this one
                if (ev.kind == "probe" && ev.detail == "cache=miscount") probeCacheMiscount()
            }
            is TransportEvent.Presented ->         // FIRMWARE.md §3 (F1.3): a panel transfer timed on the glasses; §4: its path
                journal.present(ev.seq, ev.workerUs, ev.copyUs, ev.transferUs, ev.path, ev.atMs)
        }
    }

    private fun completeFlush(ev: TransportEvent.FlushDone) {
        completionsSeen++                             // P5 item 5: a completion of any kind (content, atlas, stage)
        if (ev.id == atlasInFlight) { atlasDone(ev); return }
        if (ev.id == stageInFlight) { stageDone(ev); return }
        val a = inflightFlushes.remove(ev.id)
        journal.flushDone(ev.id, ev.ok, ev.ackMs, ev.bytes, ev.error)
        // P5 item 1: an id this session did not send (the start cleared what the last one left; ids never repeat) —
        // its `done` line above is its record; it is no failure, success or link reading of this session's
        if (a == null) return
        if (!ev.ok) {
            mirrorWhole = true            // P6 item 5: a failed flush (each arm below rolls back) — the next check is whole
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
                comp.rollback(a)
                return
            }
            if (refused && refusalStreak >= REFUSALS_TO_SLEEP) {
                Log.e("shell", "flush ${ev.id} refused: ${ev.error}")
                comp.rollback(a)
                enterSleep(SleepReason.SILENT, "the glasses refused $refusalStreak frames in a row")
                return
            }
            Log.e("shell", "flush ${ev.id} FAILED: ${ev.error}")
            // a flush the session's own end swept is the link's story, told by the cell's word; and
            // a REFUSAL never raises a box — its paint would answer a refused image with more
            // images (§36): the streak above is that path, and the journal holds each one
            val steady = linkUpAndSteady()             // P5 item 2: ONE read for the notice and the two arms below
            if (!refused && steady) raiseFault("flush", "a flush failed: ${ev.error}")
            comp.rollback(a)
            // P5 item 2 (boundary-clear F1): the `halt` and `resync` arms follow the notice's rule — a failure the
            // session's end swept counts toward no streak (three of them rang the phone and saved a `resync` notice
            // for the next session). The log line and the journal's `done` above are its record.
            if (!refused && !steady) return
            // a KEYFRAME that fails three times in a row cannot be helped by
            // another keyframe (the frame itself is undisplayable — past the
            // firmware's caps): halt until the content changes, with ONE loud
            // notice, instead of a failure loop that repeats at the phone (round 6)
            keyframeFailStreak = if (a.keyframe) keyframeFailStreak + 1 else 0
            if (keyframeFailStreak >= 3) {
                haltedEpoch = comp.epoch
                keyframeFailStreak = 0
                flushFailStreak = 0
                journal.note("halt", "keyframe failed repeatedly (${ev.error}) — waiting for content to change")
                raiseFault("halt", "this frame cannot be displayed (${ev.error}) — the display resumes when the content changes",
                    urgent = true, source = "compositor")
                return
            }
            if (++flushFailStreak >= 3) {
                // three consecutive failures: stop re-partitioning the same
                // damage and reset with a keyframe, loudly
                journal.note("panic", "flush failure streak — keyframing")
                raiseFault("resync", "repeated flush failures (${ev.error}) — keyframe resync", urgent = true, source = "compositor")
                comp.requestKeyframe()
                flushFailStreak = 0
            }
        } else {
            flushFailStreak = 0
            keyframeFailStreak = 0
            refusalStreak = 0
            noteLinkRegime()
            // P6 item 5: the rows this flush touched join the shell's span until a check agrees
            for (t in a.touched) if (t.rect.h > 0 && t.rect.w > 0) {
                mirrorSpanLo = minOf(mirrorSpanLo, t.rect.y); mirrorSpanHi = maxOf(mirrorSpanHi, t.rect.bottom - 1)
            }
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
    /** Chunks waiting to go, each with the atlas layout generation it was taken from ([GlyphAtlas.uploadGen]). */
    private val atlasQueue = ArrayDeque<Pair<ByteArray, Int>>()
    private var atlasInFlight: Long? = null
    private var atlasInFlightGen = 0
    /** The fonts the chunks in flight and queued complete: live on the last ack. */
    private var atlasPendingSpecs = LinkedHashSet<FontSpec>()
    private var atlasSeenVersion = -1
    /** §73: the recorder's `wantVersion` at the last grow — a face drew a character it had not
     *  drawn before, and a resident face may be able to gain its glyph. Set back to -1 wherever
     *  [atlasSeenVersion] is, and at a promotion (the characters that queued behind it). */
    private var atlasWantVersion = -1
    /** §73: the faces whose refused extension the journal has already said (one line per face per
     *  atlas; a face that gains its glyphs, or a new atlas, clears it). Journal pacing only. */
    private val atlasExtRefusedSaid = HashSet<FontSpec>()
    /** §73: the "stays pixels" and "not now" lines already written for this atlas, by face and
     *  reason — one each until a repack or a new atlas. Journal pacing only: nothing reads it to decide. */
    private val atlasSaid = HashSet<String>()
    private var atlasBytesSent = 0L
    private var atlasFailed = false
    /** §54: chunks that failed without a refusal in a row (a link end, a sweep) — reset
     *  by an ack; at [ATLAS_UNDELIVERED_LIMIT] the feature is switched off like a refusal. */
    private var atlasUndelivered = 0
    /** §47: when the last repack ran (pacing between rebuilds), and whether a
     *  face is still pixels for want of room after one — the status cell. */
    private var lastAtlasRepackMs = 0L
    private var atlasFullShown = false
    /** §47: the frame at which a refused face is tried again (the faces it
     *  needs room from may be stale by then); -1 = no retry pending. */
    private var atlasRetryAtFrame = -1L

    /** Fonts on the glasses right now (tests, status). */
    val cachedFontsLive: Set<FontSpec> get() = cachedText?.live ?: emptySet()
    val cachedTextActive: Boolean get() = comp.cachedText != null

    /** This shell's mark on every cache write it submits ([FlushRequest.writer]):
     *  the transport remembers the last writer, and a kept cache is trusted only
     *  when that was this shell (§54). One per instance: the keeper restarts the
     *  same instance, a new process starts with no atlas anyway. */
    private val atlasTag = "shell-" + java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() and 0xFFFFFFFFL)
    /** The last cache writer the atlas has already accounted for — on record at the session's start or a
     *  fresh atlas, or one it dropped the atlas for — so only a NEW foreign writer drops it. */
    private var atlasForeignWriter = ""

    /**
     * §54 (2026-09-15), Adam's ruling of `HANDOFF.md` §51.8 — the bounded atlas
     * skip. Every session start used to throw the atlas away and upload it again:
     * 16 KB on the silent clock and up to 63 KB in a window, ~20 s of link, a
     * dozen times a day (§42.4). The glasses keep the texture cache across a
     * rebuild whenever the new acquire is a RENEWAL on both lenses — written
     * inside the previous lease's 90 s (`CLAIMS.md`: freed on expiry, FB_RELEASE,
     * a fresh acquire and mode 11, never on a renewal) — so when the transport
     * says its first acquire was one ([LinkState.leaseCarried]) and the last
     * cache write through it was this shell's, the atlas stays as it is: the
     * fonts the glasses hold are live from the first compose, the chunk that was
     * in flight at the link end goes again, the keyframe follows as always. Every
     * other case resets: an adopted session (another shell may have uploaded into
     * the same lease), cached text off, no atlas yet, a release (the Silent-Mode
     * wake included — the shell dropped the lease on purpose), a gap past the
     * window, or another writer. An `atlas` note goes to the journal either way
     * with the transport's gaps, so the saving is measured and a wrong keep is
     * traceable. No firmware flag is involved: CACHE_KEEP stays unarmed, only
     * RIGHT could report it, and the renewal rule holds for both lenses by
     * construction.
     */
    private fun atlasAtSessionStart(adopted: Boolean) {
        stageSessionStart()             // §66: staged records never cross a session boundary
        val ct = cachedText
        val a = atlas
        val st = transport.state.value
        atlasForeignWriter = st.cacheWriter      // a writer already on record is this decision's; only a later one drops the atlas
        val reset: String? = when {
            ct == null -> null                                  // no cache path on this host: nothing to say
            a == null -> "no atlas from a previous session"
            adopted -> "an adopted session (a takeover or handback): another shell may have written the cache"
            settings.cachedText != "on" -> "cached text is off"
            !st.leaseCarried -> "the acquire was not a renewal on both arms — " +
                st.leaseCarry.ifEmpty { "the transport reports no carry-over" }
            a.v2 != st.draw2 || a.capacity != st.atlasCapacity ->
                "the session's contract differs (atlas ${if (a.v2) "v2" else "v1"} ${a.capacity / 1024} KiB, session ${if (st.draw2) "v2" else "v1"} ${st.atlasCapacity / 1024} KiB)"
            st.cacheWriter != atlasTag -> "the last cache write through the transport was not this shell's" +
                (if (st.cacheWriter.isEmpty()) "" else " (${st.cacheWriter})")
            else -> null
        }
        if (ct != null && a != null && reset == null) { atlasKeep(ct, a, st); return }
        atlasReset()
        if (reset == null) return
        // the transport's gaps ride every reset note too, unless the reason already quotes them
        val facts = if (st.leaseCarry.isEmpty() || reset.contains(st.leaseCarry)) "" else " (${st.leaseCarry})"
        journal.note("atlas", "reset — $reset$facts")
    }

    /** The kept path of [atlasAtSessionStart]: the glasses hold [a]'s acked bytes. */
    private fun atlasKeep(ct: CachedText, a: GlyphAtlas, st: LinkState) {
        ct.target = comp.composed
        wm.damage.core.gfx.IconPaint.recorder = ct
        // §73: the frame stamps and the icons stay — cleared, every resident face and icon read
        // as stale at once (the frame clock runs on across sessions) and the first face that
        // needed room after a kept rebuild evicted what was in use
        ct.clearSeen(keepUse = true)
        ct.endFrame()
        atlasQueue.clear()
        atlasInFlight = null
        // a new session over a cache carried from the last one: the mark the previous session's
        // answer left says nothing about this session, so the next upload — the rewound chunk, a
        // font appended later — asks for its own round trip instead of matching that mark
        // (2026-09-16 review). Asking HERE, with nothing to upload, was tried and taken back out:
        // the carry decision already reads RIGHT's cache presence and generation at the acquire,
        // and the read-back reads the same lens, so a check there could not be made to fail.
        atlasCheckOwed("a session carried across the rebuild")      // the count was reset by the start: nothing to drop here
        atlasLapsedOwed = false
        atlasPendingSpecs.clear()
        atlasSeenVersion = -1
        atlasWantVersion = -1
        atlasBytesSent = 0L
        atlasFailed = false            // a link end, not a refusal, took the last chunk's ack
        atlasUndelivered = 0
        atlasRetryAtFrame = -1L
        atlasFullShown = false
        a.rewindToAcked()              // what was in flight at the link end goes again — an extension's table patch too (§73)
        val held = a.specs().filter { a.isAcked(it) }.toSet()
        val heldImages = a.imageKeys().filter { a.isImageAcked(it) }.toSet()
        for (spec in a.specs()) if (spec !in held) atlasPendingSpecs.add(spec)
        ct.live = held
        ct.liveImages = heldImages
        comp.cachedText = if (held.isEmpty() && heldImages.isEmpty()) null else ct
        journal.note("atlas", "kept across the rebuild (${st.leaseCarry}): ${held.size} font(s) and " +
            "${heldImages.size} icon(s) live, ${a.ackedBytes} B on the glasses, ${a.used - a.ackedBytes} B to go again")
    }

    private fun atlasReset() {
        stageClear("the atlas was reset")
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
        atlasCheckOwed("the layout was replaced")      // a fresh atlas owes its own read-back; mid-session, the count re-bases
        atlasLapsedOwed = false
        atlasPendingSpecs.clear()
        atlasSeenVersion = -1
        atlasWantVersion = -1
        atlasExtRefusedSaid.clear(); atlasSaid.clear()
        atlasBytesSent = 0L
        atlasFailed = false
        atlasUndelivered = 0
        lastAtlasRepackMs = 0L
        atlasRetryAtFrame = -1L
        atlasFullShown = false
    }

    /** [rederive]: false when the caller re-derives every layout itself right after (the row's change). */
    private fun atlasEnable(rederive: Boolean = true) {
        val ct = cachedText
        if (ct == null) {
            journal.note("atlas", "cached text asked for, but this host's rasterizer is not a CachedText — pixels")
            return
        }
        if (atlas == null) {
            // contract 2 (`FIRMWARE.md` §4): with DRAW2 in force the atlas is a v2 layout over the
            // session's cache (mode 19, 224-entry tables, per-lens draws); otherwise the v1 one
            val st = transport.state.value
            atlasForeignWriter = st.cacheWriter  // a fresh atlas writes over whatever is there
            atlas = GlyphAtlas(ct.base, v2 = st.draw2, capacity = st.atlasCapacity)
            ct.atlas = atlas
            // §73: the layout metric lays out for the atlas's contract (which codes a run may
            // hold, so which pairs kern). It moves only when a session's contract differs from
            // the last atlas's — never on the daily path: a start that rests (Silent Mode, no
            // flags in force) builds no atlas (fix round 2, R2.4; the wake's session does) — and
            // every layout made under the other rule is re-derived: accented text re-measured in
            // place was cut by its own fit.
            if (ct.metricV2 != st.draw2) {
                ct.metricV2 = st.draw2
                if (rederive && ct.cacheMetric) {
                    clockWKey = null; statusWKey = null
                    relayoutEverything()
                }
            }
            journal.note("atlas", "built for ${if (st.draw2) "contract 2 (per-lens draws, ${st.atlasCapacity / 1024} KiB)" else "the v1 cache (64 KiB, flat draws)"}")
        }
        atlasGrow()
    }

    /** The upload's last chunk is acked: on a build with telemetry, read the cache back before the
     *  fonts go live — the size the glasses allocated, the flags in force and the last image-lane
     *  refusal — and keep cached text off for the session if the bytes are not there. The read is
     *  one control round trip off the shell loop; the fonts go live on the loop either way, so a
     *  build that cannot answer behaves exactly as before. RIGHT answers; the left lens cannot, so
     *  this proves one lens (`CLAIMS.md`, the senders' lens rule).
     *
     *  The check is owed by BYTES, not by a layout number (2026-09-15, the third review): every
     *  `GlyphAtlas` starts at upload generation 0, so keying it on that number read the second
     *  atlas of a process as already checked — and the second atlas is the one a rebuild after a
     *  lens reset builds, which is exactly the session §59 lost. It is owed again whenever bytes
     *  are acked past the watermark the last answer covered, so a font appended mid-session is
     *  proven too. One check is in flight at a time; a batch that lands under it waits for its
     *  own, since an answer taken before its bytes were acked says nothing about them.
     *
     *  A byte count alone is not enough either (2026-09-16 review). [atlasCheckEpoch] is bumped by
     *  every event after which an answer, or the mark an answer left, says nothing about what the
     *  glasses hold: a repack (the SAME `GlyphAtlas`, every record at a new offset, `ackedBytes`
     *  back at the guard — so the old mark covered a byte axis that no longer exists), a lapse, a
     *  session carried across a rebuild, a fresh atlas. An answer whose epoch is stale is dropped
     *  rather than applied, which is also what releases the in-flight flag those events used to
     *  leave standing. */
    private fun atlasCheckThenLive() {
        // 0.64 R1 (review 1a, RC-6): nothing is asked of a sleeping pair and nothing goes live there. The
        // release at a sleep frees the glasses' cache on purpose and RIGHT still answers op 4 — with no
        // cache to report, which read as "the glasses hold no texture cache": cached text off and a
        // fault notice for a sleep that was asked for. [enterSleep] already drops a read-back that was
        // out (its epoch) and a chunk in flight; this is the read-back ASKED while asleep — a grow with
        // nothing to upload (the Cached text row off and on during the sleep). Either reason. The
        // wake is a session start: [atlasAtSessionStart] decides keep or reset afresh.
        if (glassesSilent) return
        val a = atlas ?: return goLive()
        val st = transport.state.value
        // an upstream build has nothing to answer with, and an upload with nothing acked yet has
        // nothing to prove (a repack drops the queue: the old layout's ack lands on the new one)
        if (st.damageContract <= 0 || a.ackedBytes <= a.uploadStart) return goLive()
        // §73: owed by MESSAGES as well as by bytes — a message that only rewrites table entries
        // (a face gained a glyph the cache already held) acks without moving the byte mark, and
        // its entries must not go live on a read-back taken before it
        if (a === atlasCheckedAtlas && atlasCheckedEpoch == atlasCheckEpoch &&
            a.ackedBytes <= atlasCheckedBytes && a.ackedMsgs <= atlasCheckedMsgs) return goLive()
        if (atlasCheckInFlight) { atlasCheckWanted = true; return }
        atlasCheckInFlight = true
        atlasCheckWanted = false
        val target = a
        val epoch = atlasCheckEpoch
        val covered = a.ackedBytes
        val coveredMsgs = a.ackedMsgs
        // §66: the atlas lays out over the cache less the staging reserve, so the size the glasses
        // allocated must be the atlas's capacity plus that reserve
        val wantSize = if (a.v2) a.capacity + CfwModes.STAGE_RESERVE else null
        val wantDraw2 = a.v2
        scope.launch {
            var why0: String? = null
            val t = try { transport.cacheCheck() } catch (e: Exception) { why0 = e.message ?: e.toString(); null }
            post(Msg.Run {
                val cur = atlas
                // identity, not the generation: two atlases of one process share generation 0.
                // The epoch covers what identity cannot: a repack keeps the object and moves
                // every record, and a lapse or a carried session changes what the glasses hold
                // (2026-09-16 review — applying this answer then would vouch for the wrong bytes,
                // or report a lapse the shell had already handled as a refusal)
                if (cur !== target || epoch != atlasCheckEpoch || !running) return@Run
                // the test FIRST, the flag after (0.64 R1, review 1a RC-5 — the order the count's reader
                // has): an answer clears only its OWN flag. Every event that makes it stale has released
                // the flag itself ([atlasCheckOwed]: a reset, a repack, a lapse, a kept start, a sleep),
                // so a newer read-back may be out when a stale answer lands; cleared up here, the stale
                // one released both cache-write lanes under the newer one and spent its "asked again" mark.
                atlasCheckInFlight = false
                val again = atlasCheckWanted
                atlasCheckWanted = false
                // cached text failed under this read-back (a refused chunk, an undeliverable one): nothing
                // goes live on its answer — and not on NO answer either, which is why this stands above
                // the branch below (it used to follow it: `goLive()` there put the fonts back)
                if (atlasFailed) return@Run
                if (t == null) {
                    // three different facts used to arrive as one silent null. Say which: the
                    // fonts go live on bytes nothing vouched for either way, and the next batch
                    // asks again (2026-09-15, the third review)
                    journal.note("atlas", if (!transport.cacheCheckSupported)
                        "this transport cannot read the cache back — $covered B go live unproven"
                    // and WHY it did not answer, where there is a reason to give: a silent null
                    // on the one path whose job is to make a silent refusal loud is what the third
                    // review split into three messages; this is the third of them, and it was the
                    // only one that still said nothing about its cause (2026-09-16 review)
                    else "the cache read-back did not answer" + (why0?.let { " ($it)" } ?: "") +
                        " — $covered B go live unproven")
                    goLive(); return@Run
                }
                // 0.64: this answer no longer carries the generation count into the ledger. The count
                // has ONE reader ([pumpLedger], op 1), launched under one lane rule and one pace; this
                // read-back was launched from the ack's own handler (never settled) and from the
                // grow's held path (possibly with a STAGE flush in flight), and two settled short
                // readings could arrive inside one pace. It judges presence, size and flags only.
                // (The `atlasFailed` test that stood here is above the "no answer" branch now.)
                val size = t.cacheSize
                // the two conditions that are THIS upload's, and not sticky: the cache's presence and
                // size, and the flag every mode-19 write needs. The refusal record (fields 23-25) is
                // sticky until the next refusal or a mode-7 sub-0 — a vector run's own refusal would
                // read as this upload's — so it is journaled beside the record, never acted on
                val why = when {
                    size == null -> "the glasses hold no texture cache — every write was refused"
                    wantSize != null && size.toInt() != wantSize ->
                        "the glasses' cache is ${size / 1024} KiB, the atlas is laid out over ${wantSize / 1024} KiB"
                    wantDraw2 && (t.flags ?: 0L) and wm.damage.core.wire.DamageMsg.FLAG_DRAW2.toLong() == 0L ->
                        "the v2 ops are not armed on the glasses (flags 0x${(t.flags ?: 0L).toString(16)}), so every mode-19 write was refused"
                    else -> null
                }
                journal.note("glass", "atlas check: ${t.describe()}" +
                    (t.refusal?.takeIf { (it.mode and 0x7F) == 12 || (it.mode and 0x7F) == 19 }
                        ?.let { " — the record still names a cache write: ${it.describe()} (sticky; not this upload's unless the size or the flags above say so)" } ?: ""))
                if (why == null) {
                    atlasCheckedAtlas = cur
                    atlasCheckedEpoch = epoch
                    atlasCheckedBytes = covered
                    atlasCheckedMsgs = coveredMsgs
                    // bytes or messages acked while the answer was on its way are not covered by it
                    if (again || cur.ackedBytes > covered || cur.ackedMsgs > coveredMsgs) atlasCheckThenLive() else goLive()
                    return@Run
                }
                atlasFailed = true
                atlasDisable(why)
                if (wantDraw2 && (t.flags ?: 0L) and wm.damage.core.wire.DamageMsg.FLAG_DRAW2.toLong() == 0L) {
                    // the glasses say DRAW2 is not in force while the link state says it is (a fresh
                    // acquire the phone could not see, a lens that restarted): EVERY v2 op is being
                    // refused there, not only the cache writes — a fill, a per-lens draw, a hint. The
                    // ack precedes the decode, so nothing else will say so. v1 shapes for the rest of
                    // this session, and a keyframe for whatever a refused fill already left behind
                    // (`HANDOFF.md` §69: found when a fault notice's own hole was such a fill).
                    v2RefusedThisSession = true
                    journal.note("glass", "DRAW2 is not armed on the glasses — v1 shapes until the next session; keyframing")
                    comp.requestKeyframe()
                }
                raiseFault("atlas", "cached text is off for this session — the atlas check failed: $why")
                Log.e("shell", "atlas check failed: $why")
            })
        }
    }

    /** The atlas's acked fonts go live (the upload's own step, split out for [atlasCheckThenLive]). */
    private fun goLive() {
        // the setting can go off while a chunk is in flight; its ack must not put cached text back
        // (2026-09-15, the third review — `pumpAtlas` and `atlasGrow` already check it)
        if (settings.cachedText != "on") return
        val ct = cachedText ?: return
        val a = atlas
        // §73: the faces whose new glyphs the glasses now hold take their new entries FIRST, so a
        // repaint below draws with them. "Hold" is as proven as a font's first upload and no more:
        // the message was acked and the read-back found the cache present, the right size and
        // DRAW2 armed. Neither says the glasses APPLIED that message (the ack precedes the decode);
        // one they dropped leaves the table naming the tofu, drawn with no refusal — an accepted
        // exposure, graded in `CLAIMS.md`. Since 0.64 it is bounded in time and said, still with no
        // gate here (Adam's ruling, `HANDOFF.md` §75.1): the count is read after every batch
        // ([pumpLedger]), and a message it does not show is a notice and ONE re-send ([atlasShortfall]).
        // Nothing is repainted or dropped for a promotion alone:
        // the host-drawn character went out as pixels, so belief already equals glass; a record
        // or a staged strip made before it is proven against the truth when it is used; the next
        // paint of that text blits the cached glyph and the diff carries any difference.
        val promoted = a?.promote() ?: emptyList()
        if (promoted.isNotEmpty()) {
            atlasExtensionsLive += promoted.size
            atlasWantVersion = -1          // the characters that waited behind these get their turn on the next idle pump
            journal.note("atlas", "${promoted.size} face(s) gained glyphs — " + promoted.joinToString(", ") {
                "${specName(it.spec)} +\"${it.codes}\" (${it.bytes} B, ${it.entry.packedCount} packed)" })
            post(Msg.Pump)
        }
        val held = a?.specs()?.filter { a.isAcked(it) }?.toSet() ?: emptySet()
        val heldImages = a?.imageKeys()?.filter { a.isImageAcked(it) }?.toSet() ?: emptySet()
        atlasPendingSpecs.removeAll(held)
        if (held != ct.live || heldImages != ct.liveImages) atlasLive(held, heldImages, "uploaded")
    }

    /** Extensions promoted so far by this shell — never reset at a session start (harnesses and the self-check count them). */
    @Volatile var atlasExtensionsLive = 0
        private set
    /** Extensions queued so far by this shell. */
    @Volatile var atlasExtensionsQueued = 0
        private set

    private fun specName(s: FontSpec): String =
        "${s.face} ${s.sizePx}" + (if (s.bold) " bold" else "") + (if (s.italic) " italic" else "") + (if (s.raw) " raw" else "")

    /** The atlas whose upload was last read back, the epoch that answer belonged to, and the
     *  acked watermark it covered: a fresh atlas, a new epoch, or bytes acked past that mark all
     *  owe their own round trip. Identity, not a generation number — every `GlyphAtlas` starts at
     *  generation 0. */
    private var atlasCheckedAtlas: wm.damage.core.comp.GlyphAtlas? = null
    private var atlasCheckedEpoch = -1
    private var atlasCheckedBytes = -1
    private var atlasCheckedMsgs = -1
    /** Bumped by every event after which an answer in flight, or the mark one left, no longer says
     *  anything about what the glasses hold: a repack, a lapse, a reset, a session carried across
     *  a rebuild (2026-09-16 review). */
    private var atlasCheckEpoch = 0
    /** A read-back is on its way; a batch that lands under it asks for its own when it returns. */
    private var atlasCheckInFlight = false
    private var atlasCheckWanted = false

    /** Everything an answer could have vouched for is now in doubt: the gate is owed again, and an
     *  answer already on its way is dropped when it lands (its epoch will not match).
     *
     *  0.64, the count's half: the atlas messages acked and not yet covered by an agreed reading
     *  leave the books here — their offsets belong to a layout that is going or their ranges can no
     *  longer be sent again — and the next reading re-bases ([CacheLedger.rebaseNext]), so an
     *  interval never spans two layouts and a shortfall is never answered by sending a whole
     *  repacked layout again for a message of the one before it. Dropped unjudged, one of them may
     *  not have landed while a draw already used it: belief then equals the truth and a repaint
     *  sends no difference (the compositor diffs shadow against truth), so the lens is put right
     *  by a keyframe, and the journal says so. [why] is the note's reason. At a session start and
     *  a lapse [ledgerReset] ran first and this half is a no-op.
     *
     *  [keyframe] false is the sleep's entry ([enterSleep], 0.64 P4): the messages leave the books
     *  the same way, but no keyframe is asked for — nothing may be sent into a sleeping pair (§36;
     *  a rest has released the lease), the pump sends nothing while asleep, and every wake is a
     *  session rebuild whose start keyframes by itself. The note says so instead of "keyframing". */
    private fun atlasCheckOwed(why: String, keyframe: Boolean = true) {
        atlasCheckEpoch++
        atlasCheckedAtlas = null
        atlasCheckedEpoch = -1
        atlasCheckedBytes = -1
        atlasCheckedMsgs = -1
        atlasCheckWanted = false
        atlasCheckInFlight = false
        val dropped = atlasUnproven.size
        val resendOwed = atlasResendMissing > 0L        // read BEFORE the clears: queued, in the air, or acked and unjudged
        atlasUnproven.clear()
        atlasResendMissing = 0L
        atlasLastAcked = null
        if (cacheLedger.unjudged > 0) cacheLedger.rebaseNext()
        // 0.64 R1 (review 1a, RC-1): a shortfall re-bases the count and empties the books, so between it
        // and its re-send's ack nothing is "acked and unjudged" — and a repack or a mid-session reset in
        // that window dropped the re-send (`repack` / `forgetUpload` clear the atlas's own list, a reset
        // drops the atlas) with no note and no keyframe, while the row drawn from the entry the glasses
        // never held stayed as it was. The re-send owed is the reason too, said in words of its own.
        if (!resendOwed && !(dropped > 0 && cacheLedger.unjudged > 0)) return
        // R2 (`R1-verify.md` §5.2 g): at a sleep the layout has not left — the wake's session start keeps or resets it
        val what = if (dropped > 0) "count re-based — $dropped atlas message(s) acked before it are not judged ($why)"
            else if (!keyframe) "re-send unjudged — the re-send owed after a shortfall is not judged before the sleep; the wake's session start keeps or resets its layout ($why)"
            else "re-send unjudged — the re-send owed after a shortfall left with its layout ($why)"
        if (!keyframe) journal.note("atlas", "$what; no keyframe: nothing is sent while asleep, and the wake's session start keyframes")
        else {
            journal.note("atlas", "$what; keyframing")
            comp.requestKeyframe()
        }
    }

    private fun atlasDisable(why: String) {
        stageClear("cached text went off ($why)")
        val ct = cachedText ?: return
        comp.cachedText = null
        ct.live = emptySet()
        ct.liveImages = emptySet()
        atlasQueue.clear()
        // §73: the queued messages are dropped, so the atlas's own hand-out record goes back to
        // the acked mark with them — it kept their chunk ends and the sent mark, and a re-enable
        // then never sent those bytes while the acks of later messages moved the acked mark over
        // them. A chunk still in flight belongs to the old generation: its ack moves nothing and
        // its bytes go again.
        atlas?.rewindToAcked()
        atlasSeenVersion = -1
        atlasWantVersion = -1
        atlasPendingSpecs.clear()
        journal.note("atlas", "cached text off — $why")
        // 0.64 R1 (RC-1's sibling): switched off for the SESSION ([atlasFailed] — a refused or undeliverable
        // chunk, a read-back that found no cache), nothing reads the count again ([ledgerReadable]), so the
        // messages acked and unjudged and a re-send still owed are never judged: one of them may not have
        // landed while a draw used it, belief equals the truth there, and only a keyframe puts the lens
        // right. Said once: the books are emptied here. With the row merely off they stay, and are judged
        // when it is back.
        if (atlasFailed && (atlasResendMissing > 0L || atlasUnproven.isNotEmpty())) {
            val n = atlasUnproven.size
            val resend = atlasResendMissing > 0L
            atlasUnproven.clear(); atlasResendMissing = 0L
            journal.note("atlas", "count ended — cached text is off for this session with $n atlas message(s) acked and not judged" +
                (if (resend) " and a re-send owed" else "") + "; keyframing")
            comp.requestKeyframe()
        }
    }

    /** The lease lapsed: the firmware freed the cache. Every font is pixels
     *  again until the whole atlas has gone up once more — which waits for
     *  the lease to be held again (a mode-12 write needs it; one refused
     *  under a lapse would switch the feature off for the session). */
    private fun atlasLapsed() {
        stageClear("the lease lapsed — the cache is gone")
        ledgerReset()                   // the count starts over with the cache; a reading on its way is dropped
        val ct = cachedText ?: return
        val a = atlas ?: return
        comp.cachedText = null
        ct.live = emptySet()
        ct.liveImages = emptySet()
        atlasQueue.clear()
        atlasInFlight = null            // its ack would read as this layout's upload finishing (2026-09-15, the third
        atlasCheckOwed("the lease lapsed") // review): the check would then report the lapse as a refusal — and one
                                        // already in flight would do the same when it landed (2026-09-16)
        atlasPendingSpecs.clear()
        // NOT `atlasSeenVersion = -1` here: that would have `pumpAtlas` call `atlasGrow` on the
        // next frame and write mode-19 chunks into a lease that is gone — refused (3), and a
        // refused chunk turns cached text off for the session. The restart belongs to
        // `atlasLeaseBack`, which the lease-held event now reaches unconditionally.
        atlasLapsedOwed = true
        a.forgetUpload()
        journal.note("atlas", "the lease lapsed — the cache is gone; ${a.used} B go up again once the lease is back")
    }

    /** The lease is held again after a lapse: the atlas goes up again. Guarded by the lapse
     *  itself rather than by the status line, and a no-op when no lapse is owed. */
    private fun atlasLeaseBack() {
        if (!atlasLapsedOwed) return
        atlasLapsedOwed = false
        val a = atlas ?: return
        if (settings.cachedText == "on" && a.sentBytes <= a.uploadStart) atlasGrow()
    }

    /** A lapse took the glasses' cache and nothing has put it back yet. */
    private var atlasLapsedOwed = false

    /** The frame at which `pumpAtlas` last looked for a face in use that the atlas does not serve
     *  (a frame number only: nothing to clear at any boundary, the next frame looks again). */
    private var atlasInUseLookedAt = -1L

    /** Add every font seen since the last look — the ones drawn on plane 0
     *  first, since only those can ship as draws and the cache is 64 KiB —
     *  and queue the bytes they need. */
    private fun atlasGrow() {
        val ct = cachedText ?: return
        val a = atlas ?: return
        if (atlasFailed || settings.cachedText != "on") return
        val growT0 = System.nanoTime()
        val growNow = System.currentTimeMillis()
        atlasSeenVersion = ct.seenVersion
        atlasWantVersion = ct.wantVersion
        var added = 0
        val addedNames = ArrayList<String>()
        // the heaviest faces first (§41): every plane is served now, so the
        // cue is the pixels a face draws, not whether it sat on plane 0 — a
        // 64 KiB cache holds about a dozen faces, and the rows', the lens's
        // and the bars' are where a notch's bytes are
        val wanted = ct.seenSpecs().sortedByDescending { ct.usageOf(it) }
        var stillFull = false
        var repacked = false
        val clock = ct.useClock
        for (spec in wanted) {
            if (a.has(spec) || a.isRefused(spec)) continue
            // §73: a face is packed with the characters it has drawn, never its whole table
            val codes = ct.codesFor(spec)
            // §47: a face nobody has drawn for a window of frames takes no
            // room from one that is being drawn — it is packed only into
            // room that is free anyway, never after the repack that just
            // made room for the current window's faces
            val recent = clock - ct.lastUseOf(spec) <= ATLAS_RECENT_FRAMES
            // §73: "room that is free anyway" has a second claimant now — the faces in use grow
            // into it — so a face nobody is drawing is not packed inside a repack's pace window
            // (it would take straight back the room its own eviction made, and the next new
            // characters would repack again: measured on the simulator before this line), and
            // never into the last [ATLAS_GROW_HEADROOM] of the cache
            if (!recent && (repacked || (lastAtlasRepackMs != 0L && growNow - lastAtlasRepackMs < ATLAS_REPACK_PACE_MS) ||
                    a.price(spec, codes) > a.free - ATLAS_GROW_HEADROOM)) continue
            val named = { addedNames += "${specName(spec)} ${a.entry(spec)?.packedCount ?: 0} codes ${a.bytesOf(spec)} B" }
            if (a.add(spec, codes)) { added++; named(); continue }
            // full — evict the faces the current window is not drawing and
            // try once more, or say why not (deferred, nothing stale)
            if (recent && atlasEvictFor(spec, a, ct) && a.add(spec, codes)) { added++; named(); repacked = true; continue }
            if (atlasSaid.add("$spec|full")) journal.note("atlas", "font $spec stays pixels — the cache is full (${a.used} B)")
            stillFull = true
        }
        // §73: a resident face gains the glyphs it has drawn since it was packed — appended and
        // patched into its table in place, one extension per face at a time (the rest wait for
        // its promotion, which re-arms this cue). When the cache has no room, a face the window
        // is drawing gets what a NEW face gets (§47): the faces nobody has drawn for a window of
        // frames are evicted and the cache repacked, under the same pace and the same "only if
        // it frees enough" rule — a face left with half its glyphs ships every line as fragments
        // around the host's pixels, which is worse than either all draws or all pixels (the
        // self-check on a full cache: 43 refused extensions, `too-many-draws` and `proof` misses
        // where there had been none). A face nobody is drawing waits, and says so once.
        var extended = 0
        val extendedNames = ArrayList<String>()
        for (spec in a.specs()) {
            if (a.pendingExt(spec) != null) continue
            val want = a.missing(spec, ct.codesFor(spec))
            if (want.isEmpty) continue
            var x = a.extend(spec, want)
            val inUse = clock - ct.lastUseOf(spec) <= ATLAS_RECENT_FRAMES
            // need > 0: a face whose glyphs could not be RENDERED answers Refused with need 0, and
            // an eviction "for 0 bytes" would repack for nothing
            if (x is GlyphAtlas.Extended.Refused && x.first && x.need > 0 && !repacked && inUse &&
                atlasEvictFor(spec, a, ct, need = x.need)) {
                repacked = true
                // the repack folded every extension that was on its way: the characters that
                // waited behind one have no promotion coming to re-arm the cue, so it is re-armed here
                atlasWantVersion = -1
                x = a.extend(spec, a.missing(spec, ct.codesFor(spec)))
            }
            // a face IN USE that is still refused gets another try a window of frames on, whatever
            // made this one fail (a repack already ran in this grow, the eviction was deferred or
            // freed too little, the face was away when it was first refused): the only booking used
            // to be inside the eviction attempt, and a refusal that never reached one was never
            // looked at again — the face shipped every line as fragments for the life of the atlas.
            // A face nobody is drawing books nothing (`pumpAtlas` books for it when it is drawn again).
            if (x is GlyphAtlas.Extended.Refused && x.need != 0 && inUse && atlasRetryAtFrame < 0)
                atlasRetryAtFrame = clock + ATLAS_RECENT_FRAMES + 1
            when (x) {
                is GlyphAtlas.Extended.Queued -> {
                    extended++; atlasExtensionsQueued++
                    atlasExtRefusedSaid.remove(spec)
                    extendedNames += "${specName(spec)} +\"${x.ext.codes}\" ${x.ext.bytes} B"
                }
                is GlyphAtlas.Extended.Live -> {
                    atlasExtRefusedSaid.remove(spec)
                    journal.note("atlas", "${specName(spec)} gained \"${x.codes}\" with nothing to send — the cache already held those records where its table names them")
                }
                // said once per face until it gains them or the atlas is rebuilt: the retry a window
                // of frames on forgets the refusal and asks again, and a full cache then wrote this
                // line for every waiting face every thirteen frames (215 in one self-check pass)
                is GlyphAtlas.Extended.Refused -> if (x.first && atlasExtRefusedSaid.add(spec))
                    journal.note("atlas", if (x.need > 0)
                        "${specName(spec)} cannot gain \"${x.codes}\" — ${x.need} B needed, ${x.free} B free; they stay the host's pixels until the cache has room"
                    else "${specName(spec)} cannot gain \"${x.codes}\" — its glyphs could not be rendered (the log says why); they stay the host's pixels")
                GlyphAtlas.Extended.Busy, GlyphAtlas.Extended.None -> {}
            }
        }
        // a full atlas is a latency condition, not a fault, and on today's cache it is an everyday
        // one (`HANDOFF.md` §66: the day's faces do not fit): the journal says it once per episode,
        // and neither the cell nor a box does (§69) — a box here interrupted several times a day
        // The episode lasts while ANY seen face stands refused, not only one refused in this grow:
        // a grow cued by a first-seen character skips a refused face at the top of the loop, read
        // "not full" and closed the episode, and the next retry opened it again with a new line.
        if (!stillFull) stillFull = wanted.any { a.isRefused(it) }
        if (stillFull && !atlasFullShown) {
            atlasFullShown = true
            journal.note("atlas", "full — the faces that did not fit stay pixels until it repacks")
        } else if (!stillFull && atlasFullShown) atlasFullShown = false
        // §41: icons — drawn at least twice, heaviest first, into what the
        // fonts leave (a quarter of the cache at most, `GlyphAtlas.IMAGE_BUDGET`)
        var icons = 0
        val paced = lastAtlasRepackMs != 0L && growNow - lastAtlasRepackMs < ATLAS_REPACK_PACE_MS
        for (key in ct.seenImageKeys().filter { ct.imageUses(it) >= CachedText.PACK_AFTER_USES }
            .sortedByDescending { ct.imageUsage(it) }) {
            if (a.hasImage(key) || a.isImageRefused(key)) continue
            val img = ct.imageOf(key) ?: continue
            // §73, the fonts' rule for their sibling: an icon nobody is drawing is not packed
            // straight back by the grow whose repack evicted it, nor inside the pace window, nor
            // into the headroom (`pumpAtlas` books the grow when it is drawn again)
            if (clock - ct.imageLastUse(key) > ATLAS_RECENT_FRAMES &&
                (repacked || paced || img.encode().size > a.free - ATLAS_GROW_HEADROOM)) continue
            if (a.addImage(key, img)) icons++
            // once per icon until the atlas is repacked or rebuilt, like the fonts' lines: the
            // retry forgets icon refusals too, and this line was then written every thirteen frames
            else if (atlasSaid.add("icon|$key")) journal.note("atlas", "icon ${img.w}x${img.h} stays pixels — icons hold ${a.imageBytes} B, ${a.used} B used")
        }
        // §41: fonts the glasses already HOLD go live at once. The off→on
        // flip used to find nothing to upload and return before re-attaching
        // the compositor, so the setting stayed dark until a new font came
        // along (2026-09-06, 18:01–18:08 on glass: two flips, no draws).
        val held = a.specs().filter { a.isAcked(it) }.toSet()
        val heldImages = a.imageKeys().filter { a.isImageAcked(it) }.toSet()
        atlasPendingSpecs.clear()
        for (spec in a.specs()) if (spec !in held) atlasPendingSpecs.add(spec)
        val chunks = a.takeUpload()
        for (c in chunks) atlasQueue.addLast(c to a.uploadGen)
        // held fonts go live here only when no upload is running: mid-upload
        // the last chunk's ack does it once (0.38 on glass: each grow during
        // the first upload re-lit a few fonts and repainted the surface).
        // §73: and only THROUGH the read-back's gate — this used to light them directly, which
        // put acked bytes live while their read-back was still on its way, or after an
        // undelivered chunk's rewind had emptied the queue, with no read-back at all. The gate
        // goes live at once when the last answer already covers every acked byte and message.
        if (atlasQueue.isEmpty() && atlasInFlight == null &&
            (held != ct.live || heldImages != ct.liveImages)) atlasCheckThenLive()
        if (chunks.isEmpty()) return
        journal.note("atlas", "$added font(s) and $icons icon(s) packed, ${a.used} B in the atlas (${a.imageBytes} B icons), ${chunks.size} chunk(s) queued" +
            (if (extended > 0) "; $extended face(s) gaining glyphs" else "") +
            " — ${(System.nanoTime() - growT0) / 1_000_000} ms" +
            (if (addedNames.isEmpty() && extendedNames.isEmpty()) "" else " [" + (addedNames + extendedNames).joinToString(", ") + "]"))
        post(Msg.Pump)
    }

    /**
     * §47 (2026-09-12): the cache is full and [spec] needs room. Evict every
     * resident face not drawn in the last [ATLAS_RECENT_FRAMES] frames — if
     * together they free [spec]'s price — then repack the survivors
     * (heaviest first, as they were packed) and the icons drawn in the same
     * window of frames. True when the caller may try `add` again. The measured case: ten faces and 23 icons filled the
     * 64 KiB by 12:59 on 2026-09-12 and the tmux window's mono faces
     * stayed pixels for the afternoon, with only a journal line to say so.
     *
     * What makes it safe: every font and icon comes off the live sets
     * BEFORE the rewrite (a mode-14 draw against a half-written table would
     * draw the wrong glyphs), so the glasses draw pixels until the whole new
     * content is acked — the lapse path's discipline (§40). Paced: at most
     * one repack per [ATLAS_REPACK_PACE_MS] (a repack is up to 64 KiB of
     * link), never while a chunk is in flight (its ack would move the
     * watermark of a cache that no longer exists — deferred to the next
     * grow instead). A face drawn within the last [ATLAS_RECENT_FRAMES] flushes is never a
     * victim. That is not "every face on screen": a frame that repaints only chrome cells
     * advances the clock too, so after about a minute idle the page's own faces read as stale.
     * The eviction's own whole repaint stamps everything on screen, and the grow that follows
     * packs those faces again with the rest (same upload, same go-live) — a second batch only
     * on the new-face path, once per full-cache repack.
     */
    private fun atlasEvictFor(spec: FontSpec, a: GlyphAtlas, ct: CachedText, need: Int? = null): Boolean {
        val now = System.currentTimeMillis()
        val clock = ct.useClock
        // every "not now" answer books a retry a window of frames ahead
        // (pumpAtlas honours it): by then a chunk has landed, the pacing is
        // up, or the faces around the refused one have gone stale
        atlasRetryAtFrame = clock + ATLAS_RECENT_FRAMES + 1
        // each "not now" is said once per face and reason until the atlas is repacked or rebuilt
        // (§73): the retry runs every thirteen frames while a face waits, and the phone's journal
        // is never rotated
        if (atlasInFlight != null) {
            if (atlasSaid.add("$spec|in-flight")) journal.note("atlas", "repack for $spec deferred — a chunk is in flight")
            return false
        }
        if (lastAtlasRepackMs != 0L && now - lastAtlasRepackMs < ATLAS_REPACK_PACE_MS) {
            if (atlasSaid.add("$spec|paced")) journal.note("atlas", "repack for $spec deferred — one ran ${(now - lastAtlasRepackMs) / 1000} s ago")
            return false
        }
        val residents = a.specs()
        val stale = residents.filter { clock - ct.lastUseOf(it) > ATLAS_RECENT_FRAMES }.sortedBy { ct.lastUseOf(it) }
        if (stale.isEmpty()) {
            if (atlasSaid.add("$spec|none-stale")) journal.note("atlas", "nothing to evict for $spec — every resident face was drawn in the last $ATLAS_RECENT_FRAMES frames")
            return false
        }
        // [need]: what a resident face's new glyphs take (§73); else the whole face's price
        @Suppress("NAME_SHADOWING") val need = need ?: a.price(spec, ct.codesFor(spec))
        // EVERY stale face goes, not just enough for this one: a repack costs
        // the same rewrite however many leave, the window in use is about to
        // ask for its other faces too, and the pacing would hold those for
        // 45 s if this repack left them no room
        val evict = stale
        val freed = evict.sumOf { a.bytesOf(it) }
        if (freed < need) {
            if (atlasSaid.add("$spec|too-little")) journal.note("atlas", "evicting ${evict.size} stale font(s) would free $freed B; $spec needs $need B — stays pixels")
            return false
        }
        atlasRetryAtFrame = -1L
        atlasSaid.clear(); atlasExtRefusedSaid.clear()      // a repacked atlas: every refusal is a new one
        val keep = residents.filter { it !in evict }.sortedByDescending { ct.usageOf(it) }
        val keepImages = a.imageKeys().filter { clock - ct.imageLastUse(it) <= ATLAS_RECENT_FRAMES }.sortedByDescending { ct.imageUsage(it) }
        val droppedIcons = a.imageKeys().size - keepImages.size
        // off the glasses first: pixels everywhere until the new content is acked
        atlasLive(emptySet(), emptySet(), "repacking — ${evict.size} font(s) not drawn in $ATLAS_RECENT_FRAMES frames ($freed B) and $droppedIcons icon(s) evicted for $spec")
        // chunks of the layout being replaced carry bytes for offsets that are about to move: they
        // are dropped rather than sent, and the generation tag stops a late ack of one from moving
        // the new layout's watermark (2026-09-15, the second review)
        atlasQueue.clear()
        val evictedNames = evict.joinToString(", ") { "${specName(it)} ${a.bytesOf(it)} B" }
        val repackT0 = System.nanoTime()
        val used = a.repack(keep, keepImages)
        // the SAME GlyphAtlas, every record at a new offset and `ackedBytes` back at the guard:
        // the mark the last answer left covered a byte axis that no longer exists, so keying the
        // gate on identity + bytes alone skipped the whole repacked layout (2026-09-16 review)
        atlasCheckOwed("the layout was replaced")
        lastAtlasRepackMs = now
        journal.note("atlas", "repacked: ${keep.size} font(s) and ${keepImages.size} icon(s) kept, $used B in use, ${a.free} B free for $spec (needs $need B)" +
            " — ${(System.nanoTime() - repackT0) / 1_000_000} ms; evicted [$evictedNames]")
        return true
    }

    /** [specs] and [images] are on the glasses: the recorder blits from them
     *  from now on and the compositor may ship them as draws. The surface
     *  repaints once so belief holds the atlas's pixels — what the glasses
     *  draw from here. */
    private fun atlasLive(specs: Set<FontSpec>, images: Set<Any>, how: String) {
        val ct = cachedText ?: return
        // §66: a record's pixels were painted under the previous live set; a live repaint now draws
        // them differently, so the records go (they are re-staged in a moment)
        if (specs != ct.live || images != ct.liveImages) stageClear("the live font set changed")
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
        // §73: nothing grows and nothing is sent while the lease is gone. The lapse path leaves
        // `atlasSeenVersion` alone on purpose (a grow there hands out the whole forgotten layout
        // and its first chunk is refused, which switches cached text off for the session) — and
        // that held only while nothing else called the grow; a first-seen character is a cue too
        // now, and a lapse's own notice draws some. [atlasLeaseBack] is the one restart.
        if (atlasLapsedOwed || !st.leaseHeld) return false
        // §73: a face (or an icon) in use that no cue will ever speak for. A face whose extension was refused
        // while nobody drew it books nothing; a face a repack evicted, or one passed over as stale
        // (the pace window, the headroom), is simply not in the atlas — and coming back to either
        // draws no NEW character and no new font, so nothing cues a grow: the window's text stayed
        // pixels until some other face happened to. The retry is booked here, for this pump, when
        // such a face is being drawn; the grow below then packs it, makes room, or books the next
        // try a window of frames on (`atlasEvictFor`, the extension pass). Looked at once per frame.
        // A refused ICON is not looked for: nothing books its next try, so the look would fire on
        // every frame it is drawn — it goes again with any retry (`unrefuseAll`), as it always has.
        if (atlasRetryAtFrame < 0 && ct.useClock != atlasInUseLookedAt) {
            atlasInUseLookedAt = ct.useClock
            val a = atlas
            if (a != null && (ct.specsInUse(ATLAS_RECENT_FRAMES).any { !a.has(it) || a.isExtRefused(it) } ||
                    ct.imagesInUse(ATLAS_RECENT_FRAMES).any { !a.hasImage(it) && !a.isImageRefused(it) })) atlasRetryAtFrame = ct.useClock
        }
        if (atlasRetryAtFrame >= 0 && ct.useClock >= atlasRetryAtFrame) {
            // §47: a refused face gets its retry — the refusals are
            // forgotten so `add` prices it again against a cache whose
            // stale faces may now be evictable
            atlasRetryAtFrame = -1L
            atlas?.unrefuseAll()
            atlasGrow()
        } else if (ct.seenVersion != atlasSeenVersion || ct.wantVersion != atlasWantVersion) atlasGrow()
        if (atlasInFlight != null || atlasQueue.isEmpty()) return false
        if (st.inFlight != 0 || comp.hasPending || comp.needsKeyframe) return false
        // §66: a generation reading on its way speaks for the writes acked at its send and no
        // other — nothing else is written until it lands (the reading re-asks on the pacing tick
        // and a session end fails it, so this holds nothing for ever). Nor under a STAGE flush:
        // the count's reader judges the lanes still by the shell's own books.
        if (atlasCheckInFlight || ledgerReadInFlight || stageInFlight != null) return false
        // 0.64: the count's base is read BEFORE a cache write whenever it is owed — a session's
        // first chunk (so the first batch is judged like every other), after a lost write, after a
        // repack or a mid-session reset, the row switched on mid-session. One round trip.
        if (ledgerBaseOwed(st)) return tryLedgerRead(st)
        val (chunk, gen) = atlasQueue.removeFirst()
        val write: DisplayOp = if (atlas?.v2 == true) DisplayOp.CacheWrite2(chunk) else DisplayOp.CacheWrite(chunk)
        val id = try {
            transport.submit(FlushRequest(listOf(write), comp.epoch, "ATLAS", writer = atlasTag))
        } catch (e: Exception) {
            Log.e("shell", "atlas chunk submit failed", e)
            atlasFailed = true
            atlasDisable("a chunk could not be submitted: ${e.message}")
            return false
        }
        atlasInFlight = id
        atlasInFlightGen = gen
        atlasBytesSent += chunk.size
        // §42: the chunk is a flush like any other in the journal — with its label
        // and transport — so the report prices the upload where it happened
        journal.flushSubmitted(id, comp.epoch, listOf(write), "ATLAS", st.transportName,
            Journal.Timing(win = current?.id ?: "", mode = mode.name))     // package G (E4): on every submit record
        journal.note("atlas", "chunk of ${chunk.size} B submitted as flush $id (${atlasQueue.size} to go)")
        return true
    }

    private fun atlasDone(ev: TransportEvent.FlushDone) {
        atlasInFlight = null
        journal.flushDone(ev.id, ev.ok, ev.ackMs, ev.bytes, ev.error)
        val ct = cachedText ?: return
        if (!ev.ok) {
            cacheLedger.writeLost()        // §66: what landed is unknown; the next reading re-bases
            if (ev.error?.contains("ImgResCmd") != true && ++atlasUndelivered < ATLAS_UNDELIVERED_LIMIT) {
                // §54: not the glasses' answer but the link's end or the session's
                // sweep (the ack never came): the chunk may or may not have landed,
                // so it goes again from the acked mark — in this session if the
                // link is still up, or in the next under the kept atlas. Bounded:
                // a third such failure in a row is treated like a refusal below
                // (no unbounded re-send of a chunk the transport cannot carry)
                atlas?.rewindToAcked()
                atlasQueue.clear()
                atlasSeenVersion = -1
                atlasWantVersion = -1
                journal.note("atlas", "chunk ${ev.id} not delivered (${ev.error}) — it goes again from ${atlas?.ackedBytes ?: 0} B")
                return
            }
            atlasFailed = true
            if (ev.error?.contains("ImgResCmd") == true) {
                atlasDisable("the glasses refused a cache write: ${ev.error}")
                raiseFault("atlas", "cached text is off for this session — the glasses refused a cache write: ${ev.error}")
            } else {
                atlasDisable("$atlasUndelivered chunks in a row were not delivered, the last: ${ev.error}")
                raiseFault("atlas", "cached text is off for this session — $atlasUndelivered atlas chunks in a row were not delivered, the last: ${ev.error}")
            }
            return
        }
        atlasUndelivered = 0
        cacheLedger.writeAcked(1, atlas = true)  // §66: one non-empty mode-12/19 message per atlas flush
        cacheWriteLastAckMs = System.currentTimeMillis()
        // null: a chunk of a layout a repack replaced, or of a cut since rewound — it moves no
        // watermark; it is counted all the same, and there is nothing of it to send again
        val done = atlas?.acked(atlasInFlightGen)
        if (done != null) atlasLastAcked = done
        val st = transport.state.value           // ONE read for the two decisions below
        // 0.64: remembered until a reading of the count covers it — what a shortfall sends once more
        if (ledgerJudges(st)) atlasUnproven.addLast(AtlasWrite(cacheLedger.acked, done))
        // 0.64 R1 (RC-1's second sibling): a shortfall's re-send acked where nothing judges it any more
        // (the count went off with the re-send still queued). The keyframe belongs HERE, at its ack: one
        // asked for when the count went off would go out before the re-send's bytes had landed.
        // R2 (item 31; `R1-verify.md` §5.2 a): at the LAST message of the re-send only — one keyframe for a re-send
        // of N messages, after all of their bytes have landed — and the owed re-send the count-off kept ends here
        if (done?.resend == true && !ledgerJudges(st) && (atlas?.resendsQueued ?: 0) == 0) {
            atlasResendMissing = 0L
            journal.note("atlas", "re-send unjudged — a re-send was acked with the count off for this session: nothing judges it; keyframing")
            comp.requestKeyframe()
        }
        countStateChanged(st)
        if (atlasQueue.isEmpty()) {
            // the last chunk of this batch: every font whose bytes are acked
            // is on the glasses (the acked watermark, §41 — never the queued
            // one, which runs ahead of the radio). The ack says the message
            // ARRIVED, not that the firmware took it (the ack precedes the
            // decode), so on a build that can answer, the cache is read back
            // before anything is drawn from it (2026-09-15, second review).
            atlasCheckThenLive()
        }
        post(Msg.Pump)
    }

    // ------------------------------------------------------ page staging (§66)
    /**
     * Page staging (`HANDOFF.md` §66, Adam's decisions of 2026-09-16): on a contract-2 session with
     * cached text on, the focused document's next and previous strips — one notch's lines each — are
     * painted ahead through the recorder's own path, written as v2 image records into the cache's
     * reserve (`CfwModes.STAGE_RESERVE`, above the atlas's layout) on the idle pump after the atlas's
     * chunks, proven by the generation count ([CacheLedger]), and drawn by a notch as one mode-17
     * pair under a clip (`Compositor.emitStaged`). The registry and the ring are `Staging.kt`.
     *
     * The envelope (`FORK.md` §3.8): (a) every record goes at a session start, a lapse, a reset, a
     * disable, a live-set change, Silent Mode, the row going off, a relayout or another document
     * (`stage.retain`); the ring lies outside the atlas's capacity, so a repack never reaches it.
     * (b) A write's wait is its FlushDone — ok, not ok, or the session's sweep; a failed write goes
     * again, bounded by [STAGE_FAILURES_MAX]; a reading that does not answer is asked again a
     * pace later (`CacheLedger.UNANSWERED_PACE_MS`, 0.64 R1), bounded the same way; an unproven record is never drawn. (c) A record is keyed
     * on the layout's identity and its lines, on the glasses by the ledger's count
     * ([StagedRecord.ackedAt]); placements are rebuilt from the registry before every assemble. (d) Everything
     * runs on the loop; the lanes are held while a reading is on its way; LEFT cannot report — the
     * atlas's accepted blindness. (e) One read of the link state per pump. (f) The v1 path stages
     * nothing; the atlas's messages and the staged strips are judged by one ledger with ONE reader
     * of the count (0.64: [pumpLedger] — the reading is the cache's, not staging's, and a stage
     * clear no longer drops it); both lenses through the pair forms.
     */
    private val stage = Staging()
    private val cacheLedger = CacheLedger()
    private var stageInFlight: Long? = null
    private var stageInFlightRec: StagedRecord? = null
    private var stageInFlightWrites = 0
    private var stageFailed = false
    /** Writes that could not be submitted or came back not ok, in a row (a success resets it). */
    private var stageWriteFailures = 0
    /** Shortfalls this session whose interval held a staged strip's write — a write the glasses
     *  acked and did not keep is never a run of luck. An atlas-only shortfall does not count here
     *  (0.64: with the count read everywhere it is the usual kind, and three of them at Main must
     *  not switch Page staging off). */
    private var stageShortfalls = 0
    /** Strips too large for the reserve this session — not re-rendered on every pump. */
    private val stageRefused = HashSet<StageKey>()
    /** When the last cache write (atlas or stage) was acked: a generation reading sent within
     *  `CacheLedger.SETTLE_MS` of it may be short for the decode still on its way (§66.1). */
    private var cacheWriteLastAckMs = 0L

    // ---- the count's one reader (0.64). The envelope, `FORK.md` §3.8: (a) [ledgerReset] at a session
    // start and a lapse; [ledgerDropReading] at a sleep; [atlasCheckOwed] at a repack and a mid-session
    // reset (the unjudged messages leave the books, the next reading re-bases). (b) A reading's wait is
    // the transport's read: asked again every 2 s until the answer or the session's end; one that is not
    // answered is asked again a pace later ([ledgerRetryNotBeforeMs], on [pumpAfter]'s pump); three without
    // an answer end the asking for the session ([ledgerOff]) and nothing waits on the count after that.
    // (c) A reading is keyed on [ledgerEpoch], checked BEFORE its flag is touched, so an answer clears
    // only its own flag; an unjudged message on [CacheLedger.acked] at its ack. (d) Loop only; RIGHT
    // answers, LEFT cannot. (e) One read of the link state per pump and per handler. (f) Three launch
    // sites — [pumpLedger], and the base backstops in [pumpAtlas] and [pumpStage] — all through
    // [tryLedgerRead]; both write lanes hold on [ledgerReadInFlight].
    /** A reading of the cache generation count is on its way: nothing is written to the cache under it. */
    private var ledgerReadInFlight = false
    /** Bumped wherever a reading on its way stops meaning anything: a session start, a lapse, a sleep. */
    private var ledgerEpoch = 0
    /** Readings that did not answer, in a row. */
    private var ledgerReadFailures = 0
    /** The count cannot be read this session (three readings without an answer): nothing more is asked. */
    private var ledgerOff = false
    /** The `cache/off` notice has been raised this session (0.64 R1: said once per session). Cleared
     *  at a session start only ([stageSessionStart]) — a lapse's [ledgerReset] re-arms the count, not
     *  the notice. Loop-only. */
    private var cacheOffSaid = false
    /** No reading before this after a pending or short one (`CacheLedger.REREAD_PACE_MS`, `SETTLE_MS`). */
    private var ledgerReadNotBeforeMs = 0L
    /** No reading at all before this after one that was not answered (`CacheLedger.UNANSWERED_PACE_MS`,
     *  0.64 R1). A pace of its own, tested in [tryLedgerRead] so a BASE waits behind it too — the
     *  pending / short pace above is [pumpLedger]'s alone, and a base ignores that one on purpose.
     *  Cleared by [ledgerReset]; loop-only. */
    private var ledgerRetryNotBeforeMs = 0L
    /** One atlas message acked and not yet covered by a reading that agreed: the ledger's count
     *  right after its ack, and what it wrote (null: a message of a cut since replaced). */
    private class AtlasWrite(val ledgerAt: Long, val done: GlyphAtlas.Acked?)
    /** Oldest first. At most the messages between two verdicts: emptied up to the judged mark by
     *  every verdict but pending / short, whole by [atlasCheckOwed] and [ledgerReset]; nothing is
     *  appended while the count cannot judge. */
    private val atlasUnproven = ArrayDeque<AtlasWrite>()
    /** How many writes the shortfall that queued the standing re-send was missing: a later reading
     *  with at least that many extra bumps says the first sends landed late (no keyframe); fewer,
     *  and one of them was absent until the re-send wrote it. Cleared with [atlasUnproven].
     *  While it stands a re-send is owed or unjudged: [pumpStage] takes no new strip (0.64 R1), and
     *  [atlasCheckOwed] reads it before its clears. */
    private var atlasResendMissing = 0L
    /** The last atlas message acked on the layout that stands (the `cache=miscount` dev probe's subject). */
    private var atlasLastAcked: GlyphAtlas.Acked? = null
    /** Cache writes acked that a reading of the count will still judge — 0 where nothing will be
     *  read (harnesses wait on it: "nothing unread" includes the count). */
    @Volatile var cacheCountUnjudged = 0L
        private set
    /**
     * Test introspection, LOOP-ONLY: a cache write, a read-back or a count reading is between its
     * launch and the run that applies its result. Each of the four flags is set in the loop turn that
     * sends its work and cleared by the handler that applies the answer (or by the event that makes
     * the answer stale), so read ON the loop with nothing queued behind — inside [sampleIdle]'s block,
     * its only reader — "false" means no cache work is anywhere on its way. None of this is part of
     * quiescence, and a harness that counts answers at the transport cannot see the stall between
     * the transport's return and the run that applies it (0.64 R1; review 1a, RB-7). Not `@Volatile`
     * and not for another thread: it reads four plain fields of the loop.
     */
    val cacheWorkInFlight: Boolean
        get() = atlasInFlight != null || atlasCheckInFlight || stageInFlight != null || ledgerReadInFlight
    /** First-time atlas messages chosen to be written once more after a shortfall, so far (never reset). */
    @Volatile var atlasWritesChosenAgain = 0
        private set
    /** Extra bumps seen this session (a count anomaly to read, never a reason to drop a record). */
    private var stageForeignBumps = 0L
    private var stageFullSaid = false
    /** Records the compositor may draw from right now (harnesses read it across threads). */
    @Volatile var stagedProvenCount = 0
        private set
    /** Records the ring holds, whatever their state. */
    @Volatile var stagedResidentCount = 0
        private set
    /** The line ranges the drawable records cover (harnesses wait on the one the next notch needs). */
    @Volatile var stagedProvenLines: List<IntRange> = emptyList()
        private set
    /** Clears so far this process (harnesses count a boundary by it). */
    @Volatile var stageClears = 0
        private set

    private fun stageRecount() {
        stagedProvenCount = stage.count(StagedRecord.State.PROVEN)
        stagedResidentCount = stage.records.size
        stagedProvenLines = stage.records.values.filter { it.state == StagedRecord.State.PROVEN }
            .map { it.firstLine until it.firstLine + it.lines }
    }

    private fun stageSessionStart() {
        stageClear(null)
        ledgerReset()
        stageFailed = false; stageWriteFailures = 0; stageShortfalls = 0
        cacheOffSaid = false
        stageRefused.clear(); stageFullSaid = false
        stageForeignBumps = 0L
        stage.ring = null                  // sized from the session's cache at the first strip
    }

    /** Every record and placement goes: nothing draws from the reserve until a strip is staged and
     *  proven again. [why] null = a session boundary (the start's own notes say so). */
    private fun stageClear(why: String?) {
        val had = stage.records.size
        stage.clear()
        stageInFlight = null; stageInFlightRec = null; stageInFlightWrites = 0
        // 0.64: NOT the count's reading. It is the cache's, about every acked write, and a clear of
        // the registry (the row off, a live-set change, a reset) leaves it valid; dropping its flag
        // here released both write lanes under a reading still on its way.
        stageClears++
        comp.placements = emptyList()
        stageRecount()
        if (why != null && had > 0) journal.note("stage", "dropped $had record(s) — $why")
    }

    private fun stageOff(why: String) {
        stageFailed = true
        stageClear(why)
        journal.note("stage", "off for the session — $why")
    }

    /** The rows a DocView's lines occupy — the region `startDocSlide` slides. */
    private fun docRegion(v: WindowView.DocView): Rect {
        val lines = kit.visibleLines(layout, v)
        return Rect(layout.content.x, layout.content.y + Layout.CONTENT_PAD, layout.content.w - Layout.RAIL_W, lines * v.lineHeight)
    }

    /** The document staging serves: the focused window's DocView, in WINDOW mode, glasses awake. */
    private fun stagedDoc(): WindowView.DocView? {
        if (mode != Mode.WINDOW || glassesSilent) return null
        return focusedView() as? WindowView.DocView
    }

    /** The idle pump's staging step, after the atlas's: one reading launched, or one strip rendered
     *  and written. True when it did something. */
    private suspend fun pumpStage(st: LinkState): Boolean {
        if (settings.pageStaging != "on" || stageFailed || glassesSilent || !st.draw2 || !st.leaseHeld) return false
        if (!transport.cacheCheckSupported) return false     // no proof possible, so nothing is ever drawn from a record
        val ct = cachedText ?: return false
        if (settings.cachedText != "on" || atlasFailed || atlas == null) return false
        if (atlasInFlight != null || atlasQueue.isNotEmpty() || atlasLapsedOwed || atlasCheckInFlight) return false
        if (stageInFlight != null || ledgerReadInFlight) return false
        if (st.inFlight != 0 || comp.hasPending || comp.needsKeyframe) return false
        val v = stagedDoc() ?: return false
        val key = v.contentKey() ?: return false
        val region = docRegion(v)
        val lineH = v.lineHeight
        if (lineH <= 0 || region.w <= 0 || region.w > CfwModes.MAX_IMAGE2_W) return false
        val step = v.stepLines().coerceAtLeast(1)
        if (step * lineH > CfwModes.MAX_IMAGE2_H) return false     // a record is at most 2048 rows tall
        val gone = stage.retain(key, lineH, region.w)
        if (gone.isNotEmpty()) { stageRecount(); journal.note("stage", "dropped ${gone.size} record(s) of another layout") }
        val ring = stage.ring ?: StageRing(st.cacheSize - CfwModes.STAGE_RESERVE, st.cacheSize).also { stage.ring = it }
        // the count's base is owed before anything goes out (the backstop, as in `pumpAtlas`); the
        // reading of acked strips is [pumpLedger]'s, and staging writes nothing while a strip is
        // unproven — an unproven record is never drawn
        if (ledgerBaseOwed(st)) return tryLedgerRead(st)
        if (stage.count(StagedRecord.State.ACKED) > 0) return false
        // 0.64 R1 (review 1a, RC-3): no new strip while a shortfall's re-send is owed or unjudged. The
        // lanes above hold it while the re-send is queued or in flight; this is the stretch after its
        // ack, while its reading is pending or short (the bump lands up to a second after the ack on
        // glass). A strip written there is read in ONE interval with the re-send, and a strip the
        // glasses dropped was then said as "short again after a re-send" (`cache/lost`) about a
        // re-send that had been counted. A pace, never a latch — read afresh on every pump; every
        // verdict of the re-send clears it (counted, late, short again, a re-base), and so do the
        // count going off (with the re-send still queued: at its last message's ack, R2 item 31), a
        // repack, a reset, a sleep, a lapse and a session start ([atlasResendMissing]'s own clears).
        // The pump that follows the verdict brings staging back.
        if (atlasResendMissing > 0L) return false
        val visible = kit.visibleLines(layout, v)
        val top = v.model.topLine
        val count = v.lineCount()
        // the strips the next notches expose: ahead first (reading forward), one behind, then further ahead
        val wanted = listOf(top + visible, top + visible + step, top - step, top + visible + 2 * step)
            .filter { it >= 0 && it < count }
            .map { StageKey(key, it, step, lineH, region.w) }
        val pinned: (StagedRecord) -> Boolean = { r -> r.key in wanted || (r.firstLine < top + visible && r.firstLine + r.lines > top) }
        for (k in wanted) {
            if (stage.records.containsKey(k) || k in stageRefused) continue
            val want0 = ct.wantVersion; val seen0 = ct.seenVersion
            val rec = renderStrip(v, k, region, ct)
            if (ct.wantVersion != want0 || ct.seenVersion != seen0) {
                // §73: this render showed a face characters it had not drawn (or a face, an icon,
                // new to the session). The render is the atlas's cue, and the atlas goes first —
                // `pumpAtlas` on the next pump, staging held while its bytes are on their way — then
                // the strip is rendered again with the glyphs packed. Written now, with the host's
                // pixels for them, the record stops matching the page the moment they are promoted:
                // the notch falls back, the stale record stays resident, its cache writes were for
                // nothing (three ~14 KB strips per promotion of the book's face on the simulator).
                // Bounded by what is new: a character the atlas cannot give is not new on the
                // second render, so the strip is written then — one extra render, never a wait.
                post(Msg.Pump)
                return true
            }
            if (rec.size4 > ring.capacity) {
                stageRefused += k
                journal.note("stage", "$k is ${rec.encoded.size} B — larger than the ${ring.capacity / 1024} KiB reserve, stays pixels")
                continue
            }
            val evicted = ring.place(rec, pinned)
            if (evicted == null) {
                if (!stageFullSaid) { stageFullSaid = true; journal.note("stage", "the reserve holds only pinned records (${ring.bytesHeld} B) — $k waits") }
                continue
            }
            stageFullSaid = false
            for (e in evicted) stage.records.remove(e.key)
            stage.records[k] = rec
            stageSubmit(rec, st)
            stageRecount()
            return true
        }
        return false
    }

    /** Paint the strip's lines the way a live repaint paints them — through the recorder's relay with
     *  nothing recorded, so a font the glasses hold blits from the atlas here too — and encode the
     *  record. On the loop: a strip is a few lines, one notch's own strip's worth. */
    private fun renderStrip(v: WindowView.DocView, key: StageKey, region: Rect, ct: CachedText): StagedRecord {
        val g = Gray8(region.w, key.lines * key.lineH)
        val count = v.lineCount()
        for (i in 0 until key.lines) {
            val idx = key.firstLine + i
            if (idx !in 0 until count) continue
            val tmp = Gray8(region.w, key.lineH)
            ct.via(tmp, 0, 0, NO_RECORDS) { v.paintLine(tmp, idx, Rect(0, 0, region.w, key.lineH)) }
            g.blit(tmp, Rect(0, 0, region.w, key.lineH), 0, i * key.lineH)
        }
        val levels = ByteArray(g.pix.size) { Pack.level(g.pix[it].toInt() and 0xFF).toByte() }
        val enc = TextureCache.Image2(g.w, g.h, levels).encode()
        // padded to its 4-byte boundary with explicit zeros (§65: the live cache is not zeroed)
        val padded = if (enc.size % 4 == 0) enc else enc.copyOf((enc.size + 3) / 4 * 4)
        return StagedRecord(key, g, padded)
    }

    private suspend fun stageSubmit(rec: StagedRecord, st: LinkState) {
        val ops = ArrayList<DisplayOp>()
        var pos = 0
        while (pos < rec.encoded.size) {
            val len = minOf(STAGE_CHUNK, rec.encoded.size - pos)
            val part = rec.encoded.copyOfRange(pos, pos + len)
            ops.add(DisplayOp.CacheWrite2(CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2((rec.off + pos) / 4, part)), st.cacheSize)))
            pos += len
        }
        val id = try {
            transport.submit(FlushRequest(ops, comp.epoch, "STAGE", writer = atlasTag))
        } catch (e: Exception) {
            Log.e("shell", "staged record submit failed", e)
            stage.remove(rec)
            stageRecount()
            if (++stageWriteFailures >= STAGE_FAILURES_MAX) stageOff("$stageWriteFailures writes in a row could not be submitted, the last: ${e.message}")
            else journal.note("stage", "${rec.key} could not be submitted (${e.message}) — it goes again later")
            return
        }
        rec.state = StagedRecord.State.SENT
        stageInFlight = id; stageInFlightRec = rec; stageInFlightWrites = ops.size
        journal.flushSubmitted(id, comp.epoch, ops, "STAGE", st.transportName,
            Journal.Timing(win = current?.id ?: "", mode = mode.name))     // package G (E4): on every submit record
        journal.note("stage", "${rec.key} (${rec.encoded.size} B at ${rec.off}) submitted as flush $id in ${ops.size} write(s)")
    }

    private fun stageDone(ev: TransportEvent.FlushDone) {
        val rec = stageInFlightRec
        val writes = stageInFlightWrites
        stageInFlight = null; stageInFlightRec = null; stageInFlightWrites = 0
        journal.flushDone(ev.id, ev.ok, ev.ackMs, ev.bytes, ev.error)
        if (!ev.ok) {
            cacheLedger.writeLost()
            if (rec != null) {
                stage.remove(rec)
                stageRecount()
            }
            if (++stageWriteFailures >= STAGE_FAILURES_MAX) stageOff("$stageWriteFailures writes in a row failed, the last: ${ev.error}")
            else journal.note("stage", "${rec?.key ?: "a record"}'s write ${ev.id} failed (${ev.error}) — it goes again later")
            post(Msg.Pump)
            return
        }
        stageWriteFailures = 0
        cacheLedger.writeAcked(writes, atlas = false)
        cacheWriteLastAckMs = System.currentTimeMillis()
        if (rec != null && rec.state == StagedRecord.State.SENT && stage.records[rec.key] === rec) {
            rec.state = StagedRecord.State.ACKED
            rec.ackedAt = cacheLedger.acked
        }
        stageRecount()
        countStateChanged(transport.state.value)
        post(Msg.Pump)                     // the reading follows on the idle pump
    }

    // ------------------------------------------------------ the cache count's one reader (0.64)
    /** The count can judge cache writes at all this session: a Damage build, a transport with a
     *  radio of its own, and readings that answer. */
    private fun ledgerJudges(st: LinkState) = !ledgerOff && st.damageContract > 0 && transport.cacheCheckSupported
    /** A reading may be asked now: cached text on and up, the glasses awake, the cache there. */
    private fun ledgerReadable(st: LinkState) = cachedText != null && settings.cachedText == "on" && !atlasFailed &&
        !glassesSilent && !atlasLapsedOwed && st.leaseHeld && ledgerJudges(st)
    /** No base to count from (a new session, a lost write, a replaced layout): one is read before the next cache write. */
    private fun ledgerBaseOwed(st: LinkState) = ledgerReadable(st) && (cacheLedger.baseGen == null || cacheLedger.unsure)

    /** Launch one reading if both write lanes are still by the shell's OWN books. True when launched.
     *  (The atlas read-back, op 4, may be out at the same time: it no longer touches the count, and
     *  each read has its own request id and waiter.) */
    private fun tryLedgerRead(st: LinkState): Boolean {
        if (!ledgerReadable(st) || ledgerReadInFlight) return false
        // 0.64 R1 (review 1a, RC-2): an unanswered reading is asked again one pace later, not on the
        // next pump — the loop pumps after every message, the unanswered reading's own included, and
        // three in a row (the bound that ends the asking for the session) were then one event
        if (System.currentTimeMillis() < ledgerRetryNotBeforeMs) return false
        if (atlasInFlight != null || stageInFlight != null || st.inFlight != 0) return false
        launchLedgerRead()
        return true
    }

    /** The count's reading wherever the shell is — at Main, in a list, in the Reader: cache writes are
     *  acked past the last agreed reading, the batch has ended, nothing else is waiting to go, and
     *  the pace of a pending or short reading has passed. An interval is the batches since the last
     *  agreed reading (a grow cued at a batch's last ack refills the queue before this runs). With
     *  no base and writes already acked it asks the base itself; with nothing acked yet the base
     *  is the writing lane's to ask ([pumpAtlas], [pumpStage]). */
    private fun pumpLedger(st: LinkState): Boolean {
        countStateChanged(st)
        if (cacheLedger.unjudged == 0L) return false                          // nothing acked past the last agreed reading
        val based = cacheLedger.baseGen != null && !cacheLedger.unsure
        if (based && atlasQueue.isNotEmpty()) return false                    // a batch is read at its end
        if (comp.hasPending || comp.needsKeyframe) return false               // yields to content, as a chunk does
        // the pace; a base counts nothing short and ignores it
        if (based && System.currentTimeMillis() < ledgerReadNotBeforeMs) return false
        return tryLedgerRead(st)
    }

    /** What harnesses wait on: the writes a reading will still judge (0 where none will be asked). */
    private fun countStateChanged(st: LinkState) {
        cacheCountUnjudged = if (ledgerReadable(st)) cacheLedger.unjudged else 0L
    }

    /** The count starts over: a session start, a lapse. A reading on its way is dropped when it lands. */
    private fun ledgerReset() {
        cacheLedger.reset()
        ledgerEpoch++
        ledgerReadInFlight = false
        ledgerReadNotBeforeMs = 0L
        ledgerRetryNotBeforeMs = 0L
        ledgerReadFailures = 0
        ledgerOff = false
        atlasUnproven.clear()
        atlasResendMissing = 0L
        atlasLastAcked = null
        cacheWriteLastAckMs = 0L
        cacheCountUnjudged = 0L
    }

    /** The shell goes to sleep (the firmware's Silent Mode, or a rest): a reading on its way is dropped
     *  when it lands and holds no lane meanwhile. Nothing is asked while asleep ([ledgerReadable]); what
     *  was unjudged is never read — the atlas's messages leave the books at the same entry
     *  ([atlasCheckOwed] with no keyframe, 0.64 P4), a staged strip's with its record ([stageClear]) —
     *  and the wake's session start resets the count. ONE owner of this drop: [enterSleep], the one
     *  entry of every sleep. */
    private fun ledgerDropReading() {
        ledgerEpoch++
        ledgerReadInFlight = false
        cacheCountUnjudged = 0L
    }

    /** One reading of the cache generation (op 1, RIGHT) — off the loop, applied on it against the
     *  epoch. The counts it speaks for are captured here, with both lanes still. */
    private fun launchLedgerRead() {
        ledgerReadInFlight = true
        val epoch = ledgerEpoch
        val sendAcked = cacheLedger.acked
        val sendAtlasAcked = cacheLedger.atlasAcked
        val settled = System.currentTimeMillis() - cacheWriteLastAckMs >= CacheLedger.SETTLE_MS
        // the journal's subject is true to what the reading is for: a strip acked and unproven
        // makes it the stage check (its notes are kind `stage`); otherwise it is the cache count's
        val staged = stage.count(StagedRecord.State.ACKED) > 0
        val label = if (staged) "stage check" else "cache count"
        scope.launch {
            var why: String? = null
            // R3 (review 2, 2A-2): the transport's word for a reading it ended — not asked (its first write
            // failed), or asked this many times and not answered. Null: no answer and no cause given.
            var ended: wm.damage.core.transport.ReadingEnded? = null
            val t = try { transport.telemetry(label) }
                catch (e: wm.damage.core.transport.ReadingEnded) { ended = e; why = e.message; null }
                catch (e: Exception) { why = e.message ?: e.toString(); null }
            post(Msg.Run {
                // the epoch FIRST: an answer clears only its own flag (a stale one landing after a
                // newer reading was launched used to clear the newer one's, and the lanes were then
                // not held under it); every epoch bump clears the flag itself
                if (epoch != ledgerEpoch || !running) return@Run
                ledgerReadInFlight = false
                val st = transport.state.value
                val gen = t?.cacheGen
                if (gen == null) {
                    val because = why?.let { " ($it)" } ?: ""
                    if (++ledgerReadFailures >= STAGE_FAILURES_MAX) {
                        ledgerOff = true
                        // 0.64 R1 (RC-1's class): a re-send ACKED and still unjudged leaves the books here
                        // with the rest — as at the re-base that swallows one ([ledgerVerdict], Base), the
                        // lens is put right by a keyframe. One still QUEUED is not this: its keyframe
                        // comes at its own ack ([atlasDone]), after its bytes have landed.
                        val reSendAcked = atlasUnproven.any { it.done?.resend == true }
                        atlasUnproven.clear()
                        // R2 (item 31, `R1-verify.md` §5.1): a re-send still QUEUED keeps the field until its last
                        // message's ack ([atlasDone]) — or until what drops it ([atlasDisable] after a refusal or
                        // three undelivered, [atlasCheckOwed] at a repack, a reset, a sleep) says so and keyframes.
                        // Cleared here, a re-send that was then never acked left with no note and no keyframe.
                        if ((atlas?.resendsQueued ?: 0) == 0) atlasResendMissing = 0L
                        // a build whose capabilities carry no telemetry bit has no count to read: the
                        // transport answers null without asking. A capability, not a failure
                        val hasCount = st.damageFeatures and wm.damage.core.wire.DamageMsg.FEATURE_TELEMETRY != 0
                        journal.note("atlas", "count off — the cache generation read did not answer $ledgerReadFailures times$because; " +
                            "cache writes are not judged by the count for the rest of this session" +
                            (if (hasCount) "" else " (this build's capabilities carry no telemetry: there is no count to read)") +
                            (if (reSendAcked) "; a re-send was acked and not yet judged: keyframing" else ""))
                        if (reSendAcked) comp.requestKeyframe()
                        if (!stageFailed) stageOff("the cache generation read did not answer $ledgerReadFailures times$because")
                        // 0.64 R1 (the lead's ruling on review 1a, RC-2): SAID, once per session. With the
                        // count off Page staging is off and the one check that says a cache write was not
                        // applied is gone — a state the journal alone held. Only on a link that is up and
                        // steady by THIS handler's one read of it (a session that is ending answers null
                        // three times by itself; its box would wait in the queue about a session that is
                        // over — the `flush` notice's rule), and only on a build that has a count.
                        if (hasCount && !cacheOffSaid && linkUpAndSteady(st)) {
                            cacheOffSaid = true
                            // R3 (review 2, 2A-2): the body says the cause the last reading had, and names Page
                            // staging only where its row is on (with the row off there was nothing to switch off)
                            val e = ended
                            val cause = when {
                                e != null && e.asks == 0 -> "the cache count could not be asked ($why)"
                                e != null -> "the glasses did not answer the cache count (asked ${e.asks} times)"
                                why != null -> "the cache count could not be read ($why)"
                                else -> "the glasses did not answer the cache count"
                            }
                            raiseFault("cache/off", "$cause — cache writes go unchecked" +
                                (if (settings.pageStaging == "on") " and Page staging is off until the next session" else ""),
                                source = "cache")
                        }
                    } else {
                        // the note, THEN the clock for the pace, THEN the pump at its end — in that
                        // order, so the pace is never read before the note's own stamp (RD-4's order)
                        journal.note(if (staged) "stage" else "atlas",
                            (if (staged) "" else "count unread — ") + "the cache generation read did not answer$because — asked again in ${CacheLedger.UNANSWERED_PACE_MS} ms")
                        ledgerRetryNotBeforeMs = System.currentTimeMillis() + CacheLedger.UNANSWERED_PACE_MS
                        pumpAfter(CacheLedger.UNANSWERED_PACE_MS)
                    }
                    countStateChanged(st)
                    return@Run
                }
                ledgerReadFailures = 0
                ledgerVerdict(cacheLedger.answer(gen, sendAcked, sendAtlasAcked, settled), "the $label", t, staged)
                countStateChanged(st)
                post(Msg.Pump)
            })
        }
    }

    /** What a generation reading means for the staged records and for the atlas (`CacheLedger`).
     *  [staged]: a strip was acked and unproven at the reading's launch — its pending, short and
     *  over-count notes are staging's (kind `stage`); otherwise they are the count's own (kind `atlas`). */
    private fun ledgerVerdict(v: CacheLedger.Verdict, from: String, t: wm.damage.core.wire.DamageMsg.Telemetry?, staged: Boolean) {
        val gen = cacheLedger.baseGen
        when (v) {
            is CacheLedger.Verdict.Base -> {
                // nothing before this reading can be proven by counting: an acked record goes again
                val unproven = stage.records.values.filter { it.state == StagedRecord.State.ACKED }
                for (r in unproven) stage.remove(r)
                if (unproven.isNotEmpty()) journal.note("stage", "$from re-based the generation count at ${v.gen} — ${unproven.size} acked record(s) go again")
                // the atlas's half: the messages acked before it leave the books unjudged; a re-send
                // among them can no longer be read, so the lens is put right by a keyframe instead
                val dropped = atlasUnproven.toList()
                atlasUnproven.clear()
                // R3 (review 2, item 31's sibling): a re-send still QUEUED keeps the field — under the new base it
                // is judged like any write (its verdict clears it), or what drops it says so and keyframes
                // ([atlasDisable], [atlasCheckOwed]). Cleared here, a re-send then refused left unnamed.
                if ((atlas?.resendsQueued ?: 0) == 0) atlasResendMissing = 0L
                if (dropped.isNotEmpty()) {
                    val resent = dropped.any { it.done?.resend == true }
                    journal.note("atlas", "count re-based — ${dropped.size} atlas message(s) acked before it are not judged (" +
                        (if (v.first) "the session's first reading" else "a write was lost before the reading") + ", generation ${v.gen})" +
                        (if (resent) "; a re-send among them: keyframing" else ""))
                    if (resent) comp.requestKeyframe()
                }
            }
            is CacheLedger.Verdict.Proven -> {
                var n = 0
                for (r in stage.records.values) if (r.state == StagedRecord.State.ACKED && r.ackedAt <= v.upTo) { r.state = StagedRecord.State.PROVEN; n++ }
                if (n > 0) journal.note("stage", "$from proved $n record(s) — ${stage.count(StagedRecord.State.PROVEN)} drawable, ${stage.ring?.bytesHeld ?: 0} B in the reserve")
                val counted = atlasUnproven.filter { it.ledgerAt <= v.upTo }
                atlasUnproven.removeAll { it.ledgerAt <= v.upTo }
                val again = counted.count { it.done?.resend == true }
                if (counted.size > again) journal.note("atlas", "count ok — ${counted.size - again} atlas message(s) counted by RIGHT, generation $gen")
                if (again > 0) {
                    // the re-send was counted: with an interval of one message the first send had not
                    // landed (the note claims no more than the count shows). Draws made meanwhile used
                    // bytes the glasses did not hold, and belief already equals the truth there — a
                    // repaint would send nothing, so the lens is put right by one keyframe
                    atlasResendMissing = 0L
                    journal.note("atlas", "re-send counted — $again atlas message(s) sent a second time were counted by RIGHT; keyframing")
                    comp.requestKeyframe()
                }
            }
            is CacheLedger.Verdict.Pending -> {
                // the acked writes have not had their settle window: counts for nothing, asked again paced.
                // The note FIRST, then the clock for the pace, then the pump at its end (0.64 R1, review 1a
                // RD-4): with the clock read before the note, the note's stamp could come after it and a
                // re-read launched by some other pump was then less than the pace after the NOTE — the
                // one fact a journal reader (and `StagingTest`'s bound) has. The pump stays last: launched
                // before the pace it would arrive early after a stall, and the re-read would wait for the
                // idle tick.
                if (staged) journal.note("stage", "$from read the generation ${v.missing} short inside the settle window — pending, asked again in ${CacheLedger.REREAD_PACE_MS} ms")
                else journal.note("atlas", "count pending — $from read the generation ${v.missing} short inside the settle window, asked again in ${CacheLedger.REREAD_PACE_MS} ms")
                ledgerReadNotBeforeMs = System.currentTimeMillis() + CacheLedger.REREAD_PACE_MS
                pumpAfter(CacheLedger.REREAD_PACE_MS)
            }
            is CacheLedger.Verdict.Short -> {
                // §73: paced at the settle window, not the re-read pace — the second settled reading
                // is the one that makes a shortfall, and until the pump below existed it came with
                // the 5 s idle tick; at 500 ms a decode 2.5 s behind its ack would have been called lost.
                // The note, then the clock, then the pump — the order of the branch above, for its reason.
                if (staged) journal.note("stage", "$from read the generation ${v.missing} short after the settle window (settled reading ${v.reads}) — asked again in ${CacheLedger.SETTLE_MS} ms")
                else journal.note("atlas", "count short (settled reading ${v.reads}) — $from read the generation ${v.missing} short after the settle window, asked again in ${CacheLedger.SETTLE_MS} ms")
                ledgerReadNotBeforeMs = System.currentTimeMillis() + CacheLedger.SETTLE_MS
                pumpAfter(CacheLedger.SETTLE_MS)
            }
            is CacheLedger.Verdict.Shortfall -> {
                val lost = stage.records.values.filter { it.state == StagedRecord.State.ACKED && it.ackedAt > v.fromAcked && it.ackedAt <= v.toAcked }
                for (r in lost) stage.remove(r)
                // the atlas is NOT switched off on a shortfall (2026-09-16 21:xx, §66.1): the 0.57 ledger took
                // cached text down for the evening on three lagged readings forty minutes apart. 0.64 (Adam's
                // ruling, §75.1): no gate either — a shortfall is SAID, the atlas messages of its interval are
                // written once more, and staging keeps its own rule (the strips dropped and staged again)
                val stageWrites = (v.toAcked - v.fromAcked) - v.atlasWrites
                if (stageWrites > 0) journal.note("stage", "$from: the generation says ${v.missing} of the cache writes since the last reading never landed — " +
                    "${lost.size} record(s) dropped" + (if (v.atlasInvolved) "; atlas messages were among the writes (the atlas note says what goes again)" else ""))
                // staging's three count only a shortfall whose interval held a stage write — BEFORE the
                // notice is worded, which says "staging is off" in place of "staged again" when this fired
                var stagingOff = false
                if (stageWrites > 0 && ++stageShortfalls >= STAGE_FAILURES_MAX) {
                    stageOff("$stageShortfalls shortfalls this session")
                    stagingOff = true
                }
                atlasShortfall(v, lost.size, stagingOff,
                    t?.refusal?.let { "; the last refusal on record: ${it.describe()} (sticky)" } ?: "; no refusal on record")
            }
            is CacheLedger.Verdict.Foreign -> {
                // more bumps than this shell's acked writes: the count is re-based and the acked records
                // stand — an extra bump does not make them bad, and another writer's bytes are the
                // transport's writer tag to catch (§66.1: seen once on glass, cause unread)
                stageForeignBumps += v.extra
                var n = 0
                for (r in stage.records.values) if (r.state == StagedRecord.State.ACKED && r.ackedAt <= v.toAcked) { r.state = StagedRecord.State.PROVEN; n++ }
                // with no strip acked at the reading's launch this is the count's own note (kind `atlas`,
                // like its pending and short notes): a `stage` note means a strip was involved
                if (staged) journal.note("stage", "$from: ${v.extra} more generation bump(s) than this shell's writes since the last reading ($stageForeignBumps this session) — re-based; $n acked record(s) taken as landed")
                else journal.note("atlas", "count over — $from read ${v.extra} more generation bump(s) than this shell's writes since the last agreed reading " +
                    "($stageForeignBumps this session); re-based, nothing is called lost")
                val counted = atlasUnproven.filter { it.ledgerAt <= v.toAcked }
                atlasUnproven.removeAll { it.ledgerAt <= v.toAcked }
                val again = counted.count { it.done?.resend == true }
                if (again > 0) {
                    val missing = atlasResendMissing
                    atlasResendMissing = 0L
                    if (v.extra >= missing) {
                        // every write the shortfall missed has bumped since: the first sends were applied
                        // after all (a decode slower than two settle windows); the image lane decodes in
                        // order, so nothing was drawn ahead of them — no keyframe
                        journal.note("atlas", "re-send landed late — ${v.extra} more bump(s) than writes after a re-send: the write(s) called short were counted after all; nothing was lost")
                    } else {
                        // fewer late bumps than the shortfall missed: one write was absent until the
                        // re-send wrote it, and the lens keeps what was drawn meanwhile — the keyframe
                        journal.note("atlas", "re-send counted — $again atlas message(s) sent a second time were counted by RIGHT, " +
                            "and ${v.extra} of the $missing write(s) called short bumped late; keyframing")
                        comp.requestKeyframe()
                    }
                }
            }
        }
        stageRecount()
    }

    /**
     * 0.64 (Adam's ruling, `HANDOFF.md` §75.1 — no proof gate; the re-read everywhere, a shortfall
     * notice and ONE re-send): the count is short by `v.missing` of the `k` messages acked since
     * the last agreed reading. It cannot say which, so every first-time atlas message of the
     * interval is written once more — a cache write is a plain copy, and one that did land is
     * rewritten with its own bytes — through the existing queue, with the fonts left live. Nothing
     * else happens: no font leaves the live set, no promotion is taken back, cached text stays on.
     *
     * The bound is on the message's own record ([GlyphAtlas.Acked.resend]): a message that was
     * itself a re-send is never queued again. When the re-sent messages alone can account for what
     * is missing they are taken as the ones not counted, and the first-time messages read with
     * them (the notice's own faces gaining glyphs, typically) are not sent again; that is stated as
     * fact only when the interval held nothing else.
     */
    private fun atlasShortfall(v: CacheLedger.Verdict.Shortfall, strips: Int, stagingOff: Boolean, ref: String) {
        val k = v.toAcked - v.fromAcked
        val gen = cacheLedger.baseGen
        val cand = atlasUnproven.filter { it.ledgerAt > v.fromAcked && it.ledgerAt <= v.toAcked }
        atlasUnproven.removeAll { it.ledgerAt <= v.toAcked }
        val a = atlas                                                     // ONE read
        val mine = cand.mapNotNull { it.done }                            // null: a cut since replaced — its bytes went again already
        val again = mine.filter { it.resend }
        val first = if (again.isNotEmpty() && v.missing <= again.size) emptyList() else mine.filter { !it.resend }
        atlasResendMissing = 0L
        val parts = ArrayList<String>()
        if (first.isNotEmpty() && a != null) {
            val bytes = a.resend(first.flatMap { it.writes })
            atlasWritesChosenAgain += first.size
            atlasResendMissing = v.missing
            atlasWantVersion = -1          // the grow's cue, as after a rewind: takeUpload -> atlasQueue -> pumpAtlas
            journal.note("atlas", "count short — RIGHT's generation is $gen, ${v.missing} short of $k cache write(s) acked since the last agreed reading " +
                "(${v.atlasWrites} of the atlas); ${first.size} atlas message(s), $bytes B, go once more$ref")
            parts += "${first.size} sent once more"
        } else if (v.atlasWrites > 0 && again.isEmpty())
            journal.note("atlas", "count short — RIGHT's generation is $gen, ${v.missing} short of $k cache write(s); ${v.atlasWrites} atlas message(s) " +
                "in the interval, none of the layout that stands — nothing to send again$ref")
        val alone = k == again.size.toLong()                             // the interval held the re-sent messages and nothing else
        if (again.isNotEmpty()) journal.note("atlas", if (alone)
            "count short again — ${again.size} atlas message(s) sent twice were not counted either time; nothing more is sent for them and the atlas stays live$ref"
        else "count short again — the count is ${v.missing} short after a re-send (${again.size} re-sent, ${k - again.size} other write(s) read with it); " +
            "nothing more is sent for the re-sent message(s) and the atlas stays live$ref")
        if (!linkUpAndSteady()) return                                    // a session that is ending: the journal has it
        if (stagingOff) parts += (if (strips > 0) "$strips staged strip(s) not staged again: " else "") + "staging is off for this session"
        else if (strips > 0) parts += "$strips staged strip(s) staged again"
        // 0.64 R1 (review 1a, RC-3): the verdict that switches staging off is raised under a class of its
        // own, IN PLACE of `cache/short` — one box per verdict. Three strip shortfalls a few seconds
        // apart are one episode of `cache/short` (posted, updated at the second, and the third — not a
        // power of two — shown nowhere; a dismissed box mutes the rest), so the body that says
        // "staging is off for this session" was only ever in the journal. Staging goes off once per
        // session, so this class is raised at most once per session.
        if (first.isNotEmpty() || strips > 0 || stagingOff || again.isEmpty())
            raiseFault(if (stagingOff) "cache/staging" else "cache/short",
                "the glasses acknowledged $k cache write(s) and counted ${maxOf(0L, k - v.missing)} — " +
                (if (parts.isEmpty()) "nothing to send again" else parts.joinToString(", ")), source = "cache")
        // "for the re-sent message(s)": the same verdict's other box may say "N sent once more" for the
        // first-time messages read with them — the two must not read as contradicting each other
        if (again.isNotEmpty())
            raiseFault("cache/lost", if (alone)
                "a cache write sent twice was not counted either time — it is not sent again; text from the cache may draw wrong until the next session"
            else "the count is short again after a re-send (${again.size} re-sent, ${k - again.size} other write(s) read with it) — nothing more is sent for the re-sent message(s)",
                source = "cache")
    }

    /** The `cache=miscount` dev probe (sent only on Adam's word, `tools/glassdrive.py probe:cache=miscount`):
     *  one atlas ack the glasses never saw is added to the ledger, with the last acked message as
     *  what it wrote. The count then reads one short and the real path runs end to end on glass —
     *  the short readings, the notice, a re-send of bytes the glasses already hold (a plain copy of
     *  identical bytes), the agreed reading, one keyframe. Nothing new goes on the wire. */
    private fun probeCacheMiscount() {
        val st = transport.state.value
        val last = atlasLastAcked
        val why = when {
            !ledgerReadable(st) -> "the count is not being read (cached text off or not up, the glasses asleep, or a build or transport that cannot answer)"
            cacheLedger.baseGen == null || cacheLedger.unsure -> "the count has no base yet"
            last == null -> "no atlas message has been acked on this layout this session (draw a character a face has not drawn, then send it again)"
            else -> null
        }
        if (why != null || last == null) { journal.note("probe", "cache=miscount not run — $why"); return }
        cacheLedger.writeAcked(1, atlas = true)
        cacheWriteLastAckMs = System.currentTimeMillis()      // as a real ack would: the settle window runs from here
        atlasUnproven.addLast(AtlasWrite(cacheLedger.acked, GlyphAtlas.Acked(last.n, last.writes, resend = false)))
        countStateChanged(st)
        journal.note("probe", "cache=miscount — one atlas ack the glasses never saw is on the ledger (the last acked message, ${last.bytes} B): the count reads one short from here")
        post(Msg.Pump)
    }

    /**
     * §73: one pump when a paced re-read comes due. The verdicts above only set
     * [ledgerReadNotBeforeMs]; nothing ran when it passed, so on a quiet link the re-read waited
     * for the 5 s idle tick and a strip sat acked and not drawable that long — on glass the
     * first reading after a write is normally short (the bump lands up to a second after the
     * ack). A pace, not a bound: nothing is given up when the delay ends, and a pump that
     * arrives in any other state (silent, a session later) finds nothing to do; a stopped shell is
     * sent none.
     */
    private fun pumpAfter(ms: Long) {
        scope.launch {
            delay(ms + 20)
            // only to a running shell: a counted message must be delivered, and a stopped shell's
            // queue has no reader (staged records never cross a session, so there is no re-read to make)
            if (running) post(Msg.Pump)
        }
    }

    /** The placements the next assemble may draw from: every PROVEN record of the focused document at
     *  its nominal position under the document's current displacement (`Slide.offsetPx`), clipped to
     *  the region. Rebuilt on every pump that assembles, never carried across frames. */
    private fun stagePlacements(): List<Compositor.StagedPlacement> {
        if (stage.records.isEmpty() || stageFailed || settings.pageStaging != "on") return emptyList()
        val v = stagedDoc() ?: return emptyList()
        val key = v.contentKey() ?: return emptyList()
        val region = docRegion(v)
        val lineH = v.lineHeight
        val top = v.model.topLine
        val offset = slides.firstOrNull { it.region == region }?.offsetPx ?: 0
        val out = ArrayList<Compositor.StagedPlacement>()
        for (r in stage.records.values) {
            if (r.state != StagedRecord.State.PROVEN || r.off < 0) continue
            if (r.key.content !== key || r.key.lineH != lineH || r.key.w != region.w) continue
            val y0 = region.y + (r.firstLine - top) * lineH + offset
            val vis = Rect(region.x, y0, r.w, r.h).intersect(region) ?: continue
            if (vis.w <= 0 || vis.h <= 0) continue
            out.add(Compositor.StagedPlacement(vis, r.off / 4, r.w, r.h, region.x, y0, r.pixels))
        }
        return out
    }

    // ------------------------------------------------------ the shell asleep (§36, §37.0; 0.64: the reason)
    /** Why the shell is asleep (`HANDOFF.md` §75.4 call 6). SILENT: the firmware's Silent Mode — a push,
     *  the READ at a session start, or three refusals in a row. REST: the shell's own; in 0.64 (build 1 of
     *  rest mode) only the dev probe `rest=on` starts one — nothing rests by itself. Neither is
     *  `Mode.SILENT` (the shell's own silent clock screen) nor `main.resting` (Main's 12 s dashboard rest). */
    private enum class SleepReason { SILENT, REST }

    /** The glasses are in the firmware's Silent Mode: they refuse every image
     *  until the both-temple long-press wakes them — and LEAVING the mode ends
     *  the firmware's EvenHub page, so nothing paints again until a CREATE
     *  (measured on glass 2026-09-05 22:04, `HANDOFF.md` §37.0). The shell
     *  keeps composing and stops SENDING, drops the lease so stock owns the
     *  display and the temples, tells the phone once — and the WAKE is a
     *  session REBUILD (`Transport.restartSession`: the link ends, the keeper
     *  stops this shell and starts it again from the prelude up — G2CC's
     *  reconnect-and-relayout path), never a keyframe. The new start reads the
     *  glasses' state before its first frame ([startLocked]).
     *
     *  0.64: a REST is the same sleep under the shell's own reason — frames stop, the lease is
     *  released, the page traffic and the transport's watchdog sleep with it (`pageTrafficWanted`),
     *  and its wake is the same session rebuild. It posts no notice and never rings the phone.
     *
     *  The envelope (`FORK.md` §3.8) of [sleep] and [silentGen]: (a) `sleep = null` and `silentGen++`
     *  at every session start; a lease lapse does not touch them (a sleep has no lease to lapse).
     *  A start that ADOPTS a live session calls no `transport.start()`, and that is the only place
     *  the transport's `leaseWanted` returns to true — so an adopted session can be one whose lease
     *  the last driver released for its own sleep: [startLocked] rebuilds the session this shell
     *  itself adopts (the P4 review's finding 4), so a sleep is never inherited and its released
     *  lease does not outlive the hand-back. (b) The release write is
     *  awaited inside the transport and ends on the write, its failure or the scope's end; the wake
     *  is a rebuild asked again on the pacing while the transport refuses it. (c) An episode is
     *  [silentGen]'s: the release coroutine and the pacing loop are keyed on it; a REST that becomes
     *  SILENT keeps the generation on purpose (one episode, one release, one loop). (d) Written on
     *  the loop and the start path, read off the loop by those two coroutines and by status readers
     *  — hence `@Volatile`. (e) [enterSleep] and [restProbe] read [sleep] once. (f) Every reader of
     *  [glassesSilent] means "asleep for either reason"; the sites that differ by reason are the
     *  paced check, the input path, the `SilentMode(false)` event, the notice and the note kinds. */
    @Volatile private var sleep: SleepReason? = null
    /** Asleep, for either reason: frames stop, the lease is released, the page traffic sleeps. */
    private val glassesSilent: Boolean get() = sleep != null
    @Volatile private var silentGen = 0
    private var silentNoticeShown = false
    /** A refused session rebuild was said (§69); per session, like [silentNoticeShown]. */
    private var rebuildRefusalShown = false
    /** Checks made during this sleep (§42) — journaled by count, not one each. */
    private var silentChecks = 0
    /** The rebuild has been asked for: this session is over the moment the
     *  transport reports the link down, and nothing more is sent or asked. */
    private var restartRequested = false
    private var lastRebuildAskMs = 0L
    /** A rest's wake was asked for and the transport has not taken it yet: the paced check asks again
     *  (as [silentTick] does for a refusal streak). Cleared at a session start and when the reason
     *  becomes SILENT; loop-only. */
    private var restWakeWanted = false
    /** An input event that asked for a wake and was not taken (one already on its way, or refused inside
     *  the pacing) has been journaled: the next ones of the same wake are consumed without a line.
     *  Cleared at a session start and by every ask that IS taken; loop-only; journal pacing only —
     *  nothing reads it to decide. */
    private var restAskNotTakenSaid = false
    /** Bumped at every session start — the identity a dev probe carries from the thread that took it
     *  to the loop message that runs it ([devProbe]): a probe taken while a start was still on its
     *  way, or kept in the queue across a start that failed, is not run in the session that follows.
     *  (`tickGen` cannot serve: it moves at a start's first line, before the start has its session — a probe
     *  taken during a start in progress would carry the new value.) */
    @Volatile private var sessionSerial = 0

    /** How the glasses currently read to this shell — for status lines and tests. */
    val glassesAsleep: Boolean get() = glassesSilent
    /** Asleep for the shell's own reason (a rest) — for status lines and tests. */
    val resting: Boolean get() = sleep == SleepReason.REST

    /** The asleep shell's check pacing (a paced loop, never a bound on work):
     *  each tick either waits on — the glasses say they are still silent, or the
     *  shell rests — or asks for the rebuild a refusal streak or a refused wake
     *  calls for. Tests shorten it. */
    @Volatile var silentPacingMs = SILENT_PACING_MS

    /** The journal kind of a note about the sleep itself: `rest` under a rest, `silent` otherwise —
     *  `REMINDER.md` reads Adam's Silent Mode presses from the `silent` notes, so a rest writes none. */
    private fun sleepKind(): String = if (sleep == SleepReason.REST) "rest" else "silent"

    /** The once-per-session notice that the firmware's Silent Mode is on (and the phone's ring). */
    private fun saySilentOnce(why: String) {
        if (silentNoticeShown) return
        silentNoticeShown = true
        services.notifyInternal("glasses",
            "the glasses are in Silent Mode ($why) — long-press both temples to wake them; the display rebuilds by itself",
            urgent = true)
    }

    /** The shell goes to sleep for [reason]. LOOP-ONLY (and the start path, before the loop launches).
     *  Already asleep for the same reason: nothing. A rest asked for under the firmware's Silent Mode:
     *  a note, nothing else — that mode is the stronger sleep and its exit ends the firmware's page.
     *  Silent Mode coming on during a rest: the SAME sleep under the stronger reason — nothing is
     *  released twice, this episode's pacing loop goes on under the Silent rule, the Silent notice is
     *  said; the rest is over as a rest (the push OFF wakes into a painting session).
     *  [notice] false: the Silent notice is not said (fix round 2, R2.2: a start that rested). */
    private fun enterSleep(reason: SleepReason, why: String, notice: Boolean = true) {
        val was = sleep                                         // ONE read: every branch decides from it
        if (was == reason) return
        if (was == SleepReason.SILENT) {
            journal.note("rest", "not taken — asleep for the firmware's Silent Mode already ($why)")
            return
        }
        sleep = reason
        if (was == SleepReason.REST) {
            restWakeWanted = false
            chromeDirty = true
            journal.note("silent", "resting, and the firmware's Silent Mode came on — $why; its push off or the READ is the wake now")
            Log.w("shell", "resting, and the glasses are silent ($why): the same sleep, the firmware's wake")
            saySilentOnce(why)
            return                                              // the lease is released already and this episode's pacing runs
        }
        stageClear(if (reason == SleepReason.SILENT) "the glasses are silent — the lease goes, and the wake rebuilds the session"
            else "the shell rests — the lease goes, and the wake rebuilds the session")
        ledgerDropReading()            // a reading on its way is not applied while asleep (no verdict, no notice, no lane held)
        // 0.64 (P4 Q7), as [atlasLapsed] does: the release frees the glasses' cache ON PURPOSE, so a
        // cache write still in flight and a read-back still on its way must not be judged against it —
        // the write's ack read as "this layout's upload finished" and its read-back then found no
        // cache ("the glasses hold no texture cache"), or the firmware's Silent Mode refused the
        // chunk ("the glasses refused a cache write"): a fault notice and cached text off for a sleep
        // that was asked for. The completion now takes the plain path ([completeFlush]: asleep, a
        // refusal is rolled back and otherwise ignored), the read-back's answer is dropped by its
        // epoch, and the wake's session start resets everything this touches ([atlasAtSessionStart]).
        // NO keyframe is asked for here, unlike every other caller: nothing may be sent into a
        // sleeping pair (§36 — a refused image is never answered with more images; a rest has no
        // lease to paint into), and every wake is a session rebuild whose start keyframes by itself.
        atlasInFlight = null
        atlasCheckOwed("the shell went to sleep", keyframe = false)
        silentChecks = 0
        val gen = ++silentGen
        keyframeFailStreak = 0; flushFailStreak = 0; haltedEpoch = null
        chromeDirty = true
        if (reason == SleepReason.SILENT) {
            journal.note("silent", "glasses asleep — $why; no frames until they wake")
            Log.w("shell", "the glasses are silent ($why): frames stop, the lease is released; a check every ${silentPacingMs / 1000} s")
            if (notice) saySilentOnce(why)
        } else {
            // a rest paints nothing by definition and posts no notice: a box posted here could only
            // be seen after the wake, when it is no longer true
            journal.note("rest", "resting — $why; frames stop, the lease is released, the page traffic sleeps")
            Log.w("shell", "resting ($why): frames stop, the lease is released; a check every ${silentPacingMs / 1000} s")
        }
        scope.launch {
            // the wake may have rebuilt the session before this ran: a release written into the NEW
            // session frees the glasses' texture cache, clears DRAW2 and stops the renewal loop —
            // one spurious rebuild and a whole atlas again (2026-09-15, the third review). The
            // generation this episode owns is the guard, as the pacing loop below uses it.
            if (gen != silentGen || sleep == null) return@launch
            try { transport.setLeaseWanted(false) } catch (e: Exception) { Log.e("shell", "lease release while asleep", e) }
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
     *  READ, `LinkState.glassesSilent`) there is nothing to do: the push OFF
     *  is the wake — the 60 s poll's READ wakes too, but only on an explicit
     *  field-14 zero, and whether the glasses send one is unread
     *  (`SettingsMsg.parseSilentRestored`). When they say it is off and the
     *  shell sleeps all the same — a refusal streak: the page ended and no
     *  push came, §37.0's 22:04:28 — the session is rebuilt, at most once per
     *  pacing (a ring spun twice must not ask twice). 0.64: the transport's
     *  watchdog stands down for that sleep too (`pageTrafficWanted`), so this
     *  check — or an input event — is its one way back. A REST has its own
     *  check ([restCheck]). */
    private fun silentTick(why: String) {
        val s = sleep                                           // one read
        if (s == null || restartRequested) return
        // R2 (RG-6): a link that is down, or a session not started, says nothing about the glasses — the
        // flags a link end clears read "awake" — and the keeper is already on its way (it polls `started`
        // and stops and starts the shell; the new session starts awake). Asked here, the transport refused
        // the rebuild ("no session is started") and a `rebuild` notice followed about the link being down.
        val st = transport.state.value                          // one snapshot for this check
        if (!st.connected || !st.started) return
        if (s == SleepReason.REST) { restCheck(); return }
        if (st.glassesSilent) {
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

    /** A rest's paced check. Build 1 READS only: one wear query (op 7) per check, never waited on — no
     *  reply is a legal answer, and an answer is the transport's `wear` note; it acts on nothing here
     *  (`DESIGN.md` §0). A wake the transport refused is asked again instead, at most once per pacing. */
    private fun restCheck() {
        silentChecks++
        if (restWakeWanted) {
            val now = System.currentTimeMillis()
            if (now - lastRebuildAskMs >= silentPacingMs) { lastRebuildAskMs = now; requestRebuild("the rest's wake, asked again") }
            return
        }
        val asked = transport.queryWear()                       // false = nothing went out; the note says which
        if (silentChecks == 1 || silentChecks % SILENT_CHECKS_PER_NOTE == 0)
            journal.note("rest", "still resting (check $silentChecks) — " +
                (if (asked) "wear query sent" else "no wear query sent: this transport has no session of its own to ask with"))
    }

    private fun wakeGlasses(why: String) {
        if (sleep != SleepReason.SILENT || restartRequested) return
        journal.note("silent", "glasses awake after $silentChecks check(s) — $why; rebuilding the session (leaving Silent Mode ends the firmware's page)")
        Log.i("shell", "the glasses are awake ($why): session rebuild")
        lastRebuildAskMs = System.currentTimeMillis()
        requestRebuild(why)
    }

    /** A rest ends: the same session rebuild the Silent Mode wake is (the release took the flags and the
     *  glasses' cache with it). A second ask while the first is on its way (a repeated `rest=off`, a ring
     *  spun twice), or inside the pacing after a refused one (a ring spun twice asks once: the paced check
     *  asks next), asks nothing more and SAYS so — the P4 review's finding 9. A [probe] is answered every
     *  time; of the input events of one wake (a ring spun on a dark panel) only the first is, so the
     *  notes are bounded by the asks, not by the ring. */
    private fun wakeFromRest(why: String, probe: Boolean = false) {
        if (sleep != SleepReason.REST) return
        val now = System.currentTimeMillis()
        val notTaken = when {
            restartRequested -> "the wake is on its way already — not asked again"
            restWakeWanted && now - lastRebuildAskMs < silentPacingMs ->
                "the wake was refused a moment ago — the paced check asks again, not this one"
            else -> null
        }
        if (notTaken != null) {
            if (probe || !restAskNotTakenSaid) journal.note("rest", "$notTaken ($why)")
            if (!probe) restAskNotTakenSaid = true
            return
        }
        restWakeWanted = true
        restAskNotTakenSaid = false
        journal.note("rest", "awake after $silentChecks check(s) — $why; rebuilding the session (the release took the flags and the cache)")
        Log.i("shell", "the rest ends ($why): session rebuild")
        lastRebuildAskMs = now
        requestRebuild(why)
    }

    /**
     * A dev probe for the SHELL. The host's probe route offers every probe here BEFORE its transport
     * (`ReplicaServer`'s token-gated `probe` message — `tools/glassdrive.py probe:rest=on|off`; the
     * transport's own probes are [wm.damage.core.transport.Transport.devProbe]):
     *
     *   rest = on | off    0.64, build 1 of rest mode (`HANDOFF.md` §75.4): start or end a rest by hand.
     *                      `on` while awake on a steady link: frames stop, the lease is released on
     *                      purpose, the renewals, the keepalive, the carrier refresh, the poll's
     *                      telemetry read and the transport's watchdog stop with it; the 60 s device-info
     *                      READ goes on and each paced check sends one wear query (op 7). `off` — or ANY
     *                      input event, which is then consumed — rebuilds the session, as the Silent
     *                      Mode wake does. Every other case is a `rest` note saying why nothing was done.
     *                      Sent to glass only on Adam's word.
     *
     * True when the name is the shell's: the host then does not hand it to the transport. Any thread.
     * The probe is run ON the loop, and only in the session it was taken in ([sessionSerial]): one taken
     * while a start was still on its way, or queued behind a stop, says so in a note and does nothing.
     */
    fun devProbe(name: String, value: String): Boolean {
        if (name != "rest") return false
        Log.i("shell", "probe: $name=$value")
        journal.note("probe", "$name=$value")
        if (!running) {
            journal.note("rest", "rest=$value not taken — no session")
            return true
        }
        val serial = sessionSerial                              // captured once, on the caller's thread
        post(Msg.Run(dropped = { journal.note("rest", "rest=$value not taken — the session ended") }) {
            if (serial != sessionSerial) journal.note("rest", "rest=$value not taken — the session changed")
            else restProbe(value)
        })
        return true
    }

    private fun restProbe(value: String) {
        val s = sleep                                           // one read
        when (value) {
            "on" -> when {
                s == SleepReason.REST -> journal.note("rest", "rest=on — already resting")
                s == SleepReason.SILENT -> journal.note("rest", "rest=on not taken — asleep for the firmware's Silent Mode")
                !linkUpAndSteady() -> journal.note("rest", "rest=on not taken — the link is down or the session is rebuilding")
                // R2 (RH-7): a rest says "the lease is released" and its `power` note charges the hour to `rest`;
                // through the seam's client the release is a log line and the owner keeps the lease lit
                !transport.leaseOnDemand -> journal.note("rest",
                    "rest=on not taken — this host's transport cannot release the lease (the seam): rest from the host that owns the radio")
                else -> enterSleep(SleepReason.REST, "probe rest=on")
            }
            "off" -> when (s) {
                SleepReason.REST -> wakeFromRest("probe rest=off", probe = true)
                SleepReason.SILENT -> journal.note("rest", "rest=off — not resting: asleep for the firmware's Silent Mode, its push off is the wake")
                null -> journal.note("rest", "rest=off — not resting")
            }
            else -> journal.note("rest", "rest=$value — expected on | off")
        }
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
        chromeDirty = true
        // P5 item 3, RH-5's second site: the coroutine ran with no guard of the session it was asked in — run after
        // that session ended and the next had started, it ended the new one. Keyed on `tickGen`, which moves only at
        // a start's first lines; captured here, on the loop or on the start path after that bump.
        val gen = tickGen
        scope.launch {
            if (gen != tickGen || !running) {
                journal.note("keeper", "a session rebuild asked in a session that has ended is not run ($why)")
                Log.w("shell", "session rebuild of an ended session not run: $why")
                return@launch
            }
            val ok = try { transport.restartSession(why) } catch (e: Exception) { Log.e("shell", "session restart", e); false }
            if (!ok) post(Msg.Run {
                restartRequested = false
                // The kind follows the sleep's reason (the P4 review's finding 11): a rest writes no
                // `silent` note. Awake — a lapse on a Damage build, an adopted session with no lease —
                // no paced check runs, so the words do not promise a second ask; and an awake shell
                // writes no `silent` note either (the P4 verify pass, the same class: `REMINDER.md`
                // reads the firmware's Silent Mode from that kind) — it is a `keeper` note, the kind
                // of the adopted-session note beside it and one `tools/journal_report.py` lists.
                val s = sleep                                   // one read for the kind and both texts
                val again = if (s != null) "still asleep; asking again on the next check"
                    else "not asleep, so no check asks again: the session goes on as it is"
                val kind = when (s) { SleepReason.REST -> "rest"; SleepReason.SILENT -> "silent"; null -> "keeper" }
                journal.note(kind, "session rebuild refused by the transport ($why) — $again")
                // asked again every pacing tick, and the pacing IS the episodes' quiet period: said
                // once per sleep (the journal takes each refusal), or a dismissed box came back every minute
                if (!rebuildRefusalShown) {
                    rebuildRefusalShown = true
                    raiseFault("rebuild", "the transport refused a session rebuild ($why)" +
                        (if (s != null) " — asking again on every check" else ""), source = "transport")
                }
            })
        }
    }

    /**
     * The link is SLOW when the transport's measured transfer term says so
     * (2026-09-05, `HANDOFF.md` §32): ~20 ms/KB on PC-direct BlueZ against
     * ~125 ms/KB through the phone (the production journal, §31.6). The wheel
     * spins in fewer frames on a slow link; nothing else changes, and nothing
     * changes at all until three isolated flushes of 1 KB or more have been timed
     * against a measured floor (`LinkEstimator`, §73), or the connection parameters say so.
     */
    /** §41: the regime decision has HYSTERESIS and a dwell — the EMA swung
     *  36–68 ms/KB second to second on 2026-09-06 and the wheel changed its
     *  frame count 22 times in 70 s. Slow above 1.3× the line, fast again
     *  below 0.7×, and never twice within [REGIME_DWELL_FLUSHES] flushes. */
    private var linkSlowState = false
    private var linkSlowFlipAt = 0L
    /** [st] is the caller's ONE read of the link state (`HANDOFF.md` §73): the note that follows a
     *  decision prints the numbers the decision used. */
    private fun linkSlow(st: LinkState = transport.state.value): Boolean {
        // §47 (2026-09-12): the connection parameters decide AT ONCE, no dwell —
        // 105 ms / latency 4 is ~525 ms before a packet can even leave, and the
        // EMA took ten flushes to say so on glass
        if (paramsSlow(st)) return true
        val ema = st.transferMsPerKbEma
        val want = if (linkSlowState) ema > SLOW_LINK_MS_PER_KB * 0.7 else ema > SLOW_LINK_MS_PER_KB * 1.3
        if (want != linkSlowState && flushesSubmitted - linkSlowFlipAt >= REGIME_DWELL_FLUSHES) {
            linkSlowState = want
            linkSlowFlipAt = flushesSubmitted
        }
        return linkSlowState
    }

    /** The worst wait the parameters allow before the peripheral must listen:
     *  interval × (latency + 1). 15 ms / 1 = 30, 48.75 / 0 = 49, 105 / 4 = 525. */
    private fun paramsSlow(st: LinkState): Boolean =
        st.linkIntervalMs > 0 && st.linkIntervalMs * (st.linkLatency + 1) >= SLOW_PARAMS_MS

    private fun spinFrames(slow: Boolean = linkSlow()): Int = if (slow) 2 else 4

    /** §73: a session start measures its own link. The transport's estimator starts over there;
     *  the regime decided on the last session's readings goes with them, and the dwell counts
     *  from here (the flush counter itself is the process's — harnesses read it). An ADOPTED
     *  start (a pause and resume, a takeover) is the exception: the transport did not start,
     *  its readings stand, and the regime starts from them — reset to "fast" behind the dwell,
     *  a link measured slow read fast for ten flushes beside its own numbers. */
    private fun linkRegimeSessionStart(adopted: Boolean) {
        val st = transport.state.value
        linkSlowState = adopted && st.transferMeasured && st.transferMsPerKbEma > SLOW_LINK_MS_PER_KB
        linkSlowFlipAt = flushesSubmitted
        lastLinkSlow = null
        lastLinkParams = ""
        slowFloorMinutes = 0
        linkMeasuredSaid = false
    }
    private var linkMeasuredSaid = false

    private var lastLinkSlow: Boolean? = null
    private var lastLinkParams = ""
    /** The parameters-slow episode the status cell and the notice track (§47). */
    private var linkParamsSlowShown = false
    /** Consecutive minute ticks with the ack floor above [FLOOR_ALERT_MS] (§47). */
    private var slowFloorMinutes = 0
    private var floorAlerted = false

    /** Journal the link regime when it flips and the connection parameters
     *  when the platform reports them — so the phone's journal carries the
     *  answer to "was the priority request granted" (no adb needed). Since
     *  §47 also the status cell and one notice per episode: slow parameters
     *  are a state the wearer should see, not a journal line to find later. */
    private fun noteLinkRegime() {
        val st = transport.state.value
        val slow = linkSlow(st)
        if (slow != lastLinkSlow) {
            lastLinkSlow = slow
            // §73: a term that is still its default says so — "fast" on a link nothing has timed
            // yet is not a measurement, and a journal that cannot tell the two apart misled a read
            fun n(samples: Int, measured: Boolean) = if (samples < 0) "" else if (measured) " (n=$samples)" else " (unmeasured, n=$samples)"
            journal.note("link", "transfer %.0f ms/KB%s, floor %.0f ms%s — %s regime (wheel spins in %d frames)%s"
                .format(st.transferMsPerKbEma, n(st.transferSamples, st.transferMeasured), st.floorMsEma, n(st.floorSamples, st.floorMeasured),
                    if (slow) "SLOW" else "fast", spinFrames(slow),
                    if (st.linkParams.isEmpty()) "" else " · ${st.linkParams}"))
        }
        // §73: a session whose link is fast flips nothing, so its only regime line was the first
        // one, "unmeasured" — said once when the transfer term first stands on real readings
        if (!linkMeasuredSaid && st.transferSamples >= wm.damage.core.transport.LinkEstimator.MIN_SAMPLES) {
            linkMeasuredSaid = true
            journal.note("link", "measured — transfer %.0f ms/KB (n=%d), floor %.0f ms (n=%d): %s"
                .format(st.transferMsPerKbEma, st.transferSamples, st.floorMsEma, st.floorSamples, if (slow) "SLOW" else "fast"))
        }
        if (st.linkParams != lastLinkParams) {
            lastLinkParams = st.linkParams
            if (st.linkParams.isNotEmpty()) journal.note("link", "connection parameters: ${st.linkParams}")
        }
        val ps = paramsSlow(st)
        if (ps && !linkParamsSlowShown) {
            linkParamsSlowShown = true
            services.notifyInternal("link", "the glasses slowed the link to %.0f ms / latency %d — asking for the %s parameters again"
                .format(st.linkIntervalMs, st.linkLatency, settings.linkPriority))
        } else if (!ps && linkParamsSlowShown) {
            linkParamsSlowShown = false
            services.notifyInternal("link", "link parameters back to %.0f ms / latency %d".format(st.linkIntervalMs, st.linkLatency))
        }
    }

    /** §47: the ack floor watched on the minute — two ticks above
     *  [FLOOR_ALERT_MS] raise one notice, the fall below it another. The floor
     *  is the median of the last (up to nine) isolated small flushes, so it moves
     *  only with those: an idle link keeps its reading, and the notice clears
     *  once the median reads under the line — about five of nine. A pacing decision on a
     *  measurement, never a timeout: nothing is cancelled. */
    private fun watchFloor() {
        val st = transport.state.value
        if (!st.started) { slowFloorMinutes = 0; return }
        // §73: the floor starts over with each session and reads its 60 ms default until three
        // small flushes have been timed — a default is neither "slow" nor "back"
        if (!st.floorMeasured) return
        if (st.floorMsEma > FLOOR_ALERT_MS) {
            slowFloorMinutes++
            if (slowFloorMinutes >= FLOOR_ALERT_MINUTES && !floorAlerted) {
                floorAlerted = true
                journal.note("link", "floor %.0f ms for %d min (parameters %s)".format(st.floorMsEma, slowFloorMinutes, st.linkParams.ifEmpty { "unreported" }))
                services.notifyInternal("link", "every flush is waiting ~%.0f ms on the radio (%d min) — parameters %s"
                    .format(st.floorMsEma, slowFloorMinutes, st.linkParams.ifEmpty { "unreported" }))
            }
        } else {
            slowFloorMinutes = 0
            if (floorAlerted) {
                floorAlerted = false
                journal.note("link", "floor back to %.0f ms".format(st.floorMsEma))
                services.notifyInternal("link", "radio floor back to %.0f ms".format(st.floorMsEma))
            }
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
    /** §73: the recorder's count of host-drawn, not-yet-packed characters at the last submitted flush. */
    private var unpackedAtLastFlush = 0L
    /** §73: a ring event's pump found the window full; the next flush submitted is that gesture's
     *  (the journal's `in`). Set only there — a gesture that painted nothing leaves no mark. */
    private var inputAwaitsSlot = false
    /** Build W, package G (E3): the loop's entry time of the ring event whose pump first found the window full
     *  (-1: none waits). Set with [inputAwaitsSlot] — only the first such pump of a gesture — and cleared wherever it
     *  is; the gesture's flush carries `inWaitMs` = its submit less this. Loop-only; journal only. */
    private var inputWaitSince = -1L
    private var slidesNs = 0L
    private var chromeNs = 0L
    private var overlaysNs = 0L

    /** Build W, package G (the lead's decision H, row E4; G-design §4): the host's clocks for the `slow` note; null
     *  = none (a tool's shell), and the note writes -1 for each figure, said once. Set by the host before start. */
    @Volatile var loopClock: LoopClock? = null
    /** The once-per-process line that this host's clocks cannot answer (loop-only). */
    private var slowClockSaid = false

    /** What a loop message started from, for the `slow` note: the thread, its CPU and the collector's time, the face
     *  renders so far, the window and the mode. A few reads per message (`clock_gettime` and counters); the note is
     *  built only for a handler of [SLOW_HANDLER_NS] or more. */
    private class SlowFrom(val clock: LoopClock?, val win: String?, val mode: Mode) {
        val thread: Thread = Thread.currentThread()
        val cpuNs = clock?.threadCpuNs() ?: -1L
        val gcMs = clock?.gcMs() ?: -1L
        val faces = wm.damage.core.text.TextProfile.faceRenders.get()
        val facesNs = wm.damage.core.text.TextProfile.faceRenderNs.get()
    }

    /** E4 (2026-11-12 21:27:52: one ring event held the loop 1,140 ms, and ten handlers of 382-1,241 ms in a day; the
     *  journal named no window): one `slow` note for a message that held the loop [SLOW_HANDLER_NS] or more — what it
     *  was (a transport event's kind, a `Run`'s tag), the window and mode before → after, the thread's CPU time
     *  against the wall (CPU close to wall = computation; far under = waiting), the collector's time during it, and
     *  the faces rendered. Journal only; no fix rides on it. LOOP-ONLY. */
    private fun noteSlow(m: Msg, ns: Long, from: SlowFrom) {
        val ms = ns / 1_000_000
        val sameThread = Thread.currentThread() === from.thread
        val cpuNow = from.clock?.threadCpuNs() ?: -1L
        val cpuMs = if (from.cpuNs >= 0 && cpuNow >= 0 && sameThread) (cpuNow - from.cpuNs) / 1_000_000 else -1L
        val gcNow = from.clock?.gcMs() ?: -1L
        val gcMs = if (from.gcMs >= 0 && gcNow >= 0) gcNow - from.gcMs else -1L
        if ((from.cpuNs < 0 || gcNow < 0) && !slowClockSaid) {
            slowClockSaid = true
            journal.note("slow", "this host's clocks do not answer (" + (if (from.clock == null) "none set" else
                listOfNotNull("thread CPU".takeIf { from.cpuNs < 0 }, "collector".takeIf { gcNow < 0 }).joinToString(" and ")) +
                "): the figure reads -1 in every slow note of this process")
        }
        val what = when (m) {
            is Msg.Trans -> "transport ${m.ev.javaClass.simpleName}"
            is Msg.Run -> "Run " + m.tag.ifEmpty { "(untagged)" }
            else -> m.javaClass.simpleName
        }
        val faces = wm.damage.core.text.TextProfile.faceRenders.get() - from.faces
        val facesMs = (wm.damage.core.text.TextProfile.faceRenderNs.get() - from.facesNs) / 1_000_000
        journal.note("slow", "handler $ms ms: $what · window ${from.win ?: "-"} → ${current?.id ?: "-"} · mode ${from.mode} → $mode · " +
            "thread CPU $cpuMs ms of $ms wall" + (if (!sameThread) " (the handler moved threads: no CPU figure)" else "") +
            " · collector $gcMs ms · faces rendered $faces in $facesMs ms")
    }

    /** APK 0.65, P6 item 1 (the loop-cost sweep's LC-7): a count, a total and a maximum, in ns. */
    private class LoopCost {
        var n = 0; var totalNs = 0L; var maxNs = 0L
        fun add(ns: Long) { n++; totalNs += ns; if (ns > maxNs) maxNs = ns }
        override fun toString() = "n=$n total=${ms1(totalNs)} max=${ms1(maxNs)}"
        private fun ms1(ns: Long) = "${ns / 1_000_000}.${(ns / 100_000) % 10}"     // tenths of a ms, no locale
    }
    /** P6 item 1 (LC-7): the loop's time per message class since the last `loop` note — the handler's time,
     *  keyed by the message's class (a transport event by its own); the pumps and the mirror scans apart. A
     *  message that submits nothing reached no row before: the `submit` line's split carries the time of the
     *  turn that flushed, and the scan an ack at rest runs is in a turn that never submits. Loop thread only:
     *  [loop] adds, [noteLoopCost] (from [handleMinute]) writes one line and clears. */
    private val loopCost = LinkedHashMap<Class<*>, LoopCost>()
    private var pumpCost = LoopCost()
    private var mirrorCost = LoopCost()
    private var loopCostSince = System.currentTimeMillis()
    /** Set by [checkMirrorAgreementInner] when it reached the compare — the early returns are not scans. */
    private var mirrorCompared = false
    /** P6 item 5: rows the scans compared since the last `loop` note, both lenses summed (a whole pair is 960). */
    private var mirrorRowsCompared = 0L
    /** P6 item 5 (the loop-cost sweep's LC-2, the challenge's corrected fix): the rows the completed flushes touched
     *  since the last agreeing check (lo > hi: none) — [completeFlush] widens it, an agreeing check clears it.
     *  [mirrorWhole]: the next check compares every row — the session's first, after a failed or rolled-back flush.
     *  [mirrorWholeAtNs]: when the last whole compare ran; one a minute is kept, the one check that does not lean on
     *  this bookkeeping. Loop thread only; a session start resets the span and asks for a whole compare. */
    private var mirrorSpanLo = Int.MAX_VALUE
    private var mirrorSpanHi = -1
    private var mirrorWhole = true
    private var mirrorWholeAtNs = 0L

    private fun checkMirrorAgreement() {
        val t0 = System.nanoTime()
        mirrorCompared = false
        try { checkMirrorAgreementInner() } finally {
            val ns = System.nanoTime() - t0
            mirrorNsThisMsg += ns
            if (mirrorCompared) mirrorCost.add(ns)
        }
    }

    /** P6 item 1 (LC-7): one `loop` note when anything ran since the last one. A local journal line, no page
     *  traffic. The `done` line is not moved behind the scan: the report's WAIT column is `done.t − submit.t`. */
    private fun noteLoopCost() {
        if (loopCost.isEmpty() && pumpCost.n == 0) return
        val now = System.currentTimeMillis()
        val parts = loopCost.entries.sortedByDescending { it.value.totalNs }.joinToString("; ") { (k, c) ->
            (if (TransportEvent::class.java.isAssignableFrom(k)) "Trans." else "") + k.simpleName + " " + c
        }
        journal.note("loop", "over ${(now - loopCostSince) / 1000} s: $parts; pump $pumpCost; mirror $mirrorCost " +
            "rows=$mirrorRowsCompared (ms; handler time per message class, the mirror scan inside its FlushDone; rows " +
            "compared, both lenses)")
        loopCost.clear(); pumpCost = LoopCost(); mirrorCost = LoopCost(); loopCostSince = now; mirrorRowsCompared = 0
    }

    private fun checkMirrorAgreementInner() {
        val m = transport.mirror
        if (!m.exact) return
        // §37.0: with the lease released on purpose the mirror shows the
        // stock pattern — nothing of ours is on the glass to agree with, and
        // the report it raised at 22:04:02 was a false alarm that stuck
        if (glassesSilent) return
        if (inflightFlushes.isNotEmpty() || comp.hasPending || comp.needsKeyframe) return
        mirrorCompared = true                  // P6 item 1: a scan the `loop` note counts
        val stride = m.stride
        var report: String? = null
        // P6 item 5 (LC-2): since the last agreeing check only the rows the model's panel was written in and the rows
        // our completed flushes touched can differ; those are compared, read in place under the model's monitor. Every
        // row: the session's first check, after a failed or rolled-back flush, while a disagreement stands, and once
        // a minute. Each arm's span is taken at every compare, so the next starts from here.
        val now = System.nanoTime()
        val whole = mirrorWhole || lastDivergence != null || now - mirrorWholeAtNs >= MIRROR_WHOLE_EVERY_NS
        for (arm in Arm.entries) {
            val belief = comp.expectedLens(arm == Arm.LEFT)
            val model = m.takeChangedRows(arm)
            val lo = if (whole) 0 else maxOf(0, minOf(if (model.isEmpty()) Int.MAX_VALUE else model.first, mirrorSpanLo))
            val hi = if (whole) comp.height - 1 else minOf(comp.height - 1, maxOf(if (model.isEmpty()) -1 else model.last, mirrorSpanHi))
            if (lo > hi) continue
            mirrorRowsCompared += hi - lo + 1
            val said = m.readPanel(arm) { panel ->
                val first = firstDisagreement(belief, panel, stride, lo, hi) ?: return@readPanel null
                if (report != null) return@readPanel ""
                val diffs = countDisagreements(belief, panel, stride, lo, hi)
                "$arm: $diffs px differ, first at (${first.first},${first.second}) belief " +
                    "${belief[first.first, first.second]} (level ${LEVEL[belief[first.first, first.second]]}) mirror level ${nibble(panel, stride, first.first, first.second)}"
            }
            if (said != null && report == null) report = said
        }
        if (report == null) {
            mirrorSpanLo = Int.MAX_VALUE; mirrorSpanHi = -1; mirrorWhole = false
            if (whole) mirrorWholeAtNs = now
            if (lastDivergence != null) {
                journal.note("divergence", "agreement restored")
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
            // recurring: no more keyframes or notices until agreement has held for a
            // while — the journal counts the episodes, and the cell says `fault` for
            // as long as the two disagree ([refreshStatusWord] reads lastDivergence)
            return
        }
        // the flood guard above IS this class's episode rule (Adam's ruling 3, §66.2): its
        // notices coalesce on one thread and skip [FaultEpisodes]' quiet minute
        onUrgent?.invoke("mirror", "belief and mirror disagree — $report")
        post(Msg.Notice(Notifications.Notice("DAMAGE · mirror", "mirror",
            "belief and mirror disagree — $report — keyframing" +
                (if (divergenceRun == DIVERGE_EPISODES_MAX) " (last notice: further episodes go to the journal until agreement holds)" else ""),
            wallClock().hhmm, fault = true)))
        comp.requestKeyframe()
    }

    private fun nibble(panel: ByteArray, stride: Int, x: Int, y: Int): Int {
        val b = panel[y * stride + (x shr 1)].toInt() and 0xFF
        return if (x and 1 == 0) b shr 4 else b and 0x0F
    }

    /** The first pixel whose quantised belief differs from the panel, or null
     *  — a cheap early-out scan (a lookup table replaces the per-pixel divide). */
    private fun firstDisagreement(belief: Gray8, panel: ByteArray, stride: Int, lo: Int, hi: Int): Pair<Int, Int>? {
        val w = comp.width
        for (y in lo..hi) {
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

    private fun countDisagreements(belief: Gray8, panel: ByteArray, stride: Int, lo: Int, hi: Int): Int {
        var diffs = 0
        for (y in lo..hi) {
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
        val base = Layout(rowH = rowH, lensH = lensH, clockW = clockCellW(), statusW = statusCellW())
        return if (s.heightMode >= Geometry.PANEL_H) base
        else base.withHeightMode(s.heightMode, wm.damage.core.geom.VPos.TOP)
    }

    /** §2 per-app height (archive/REFINEMENT.md, 2026-08-31): the layout the shell
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
        val base = Layout(rowH = rowH, lensH = lensH, clockW = clockCellW(), statusW = statusCellW())
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
        comp.requestReseed()     // a height change re-lays out the whole shell (§4.2); v2: a fill + draws
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

    /** Everything a window's layout depends on that a setting can change (the fields `restyle`
     *  compares in [applySettings], and the Cached text row: it picks the layout metric). */
    private fun styleKeyOf(s: ShellSettings): String =
        "${s.cachedText == "on"}|${s.fontScale}|${s.fontFace}|${s.fontStyle}|" +
            s.appStyles.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.face}:${it.value.scale}:${it.value.style}" }
    /** [styleKeyOf] at this shell's last session start; null before the first. */
    private var styleKeyAtLastStart: String? = null

    /** Re-derive every layout and repaint whole: a typography change, or the layout metric's
     *  (the Cached text row, the contract the metric follows). Styles first (review §29): the
     *  list rhythm is measured through them, so a face or scale change re-derives the row pitch
     *  and the lens band in `syncLayout` — which re-lays out and repaints whole by itself when
     *  the height or the rhythm moved, and is a no-op otherwise, in which case the restyle
     *  repaints whole here. A synced record can change typography AND height in one apply
     *  (R3s#6): the layout is re-derived BEFORE the full repaint. */
    private fun relayoutEverything() {
        refreshStyles()
        for (w in windows) w.onFontScaleChanged(effScale(w.id))
        if (!syncLayout()) {
            for (w in windows) w.onLayoutChanged()
            comp.composed.clear(0)
            composeFullSurface()
            comp.requestReseed()
        }
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
            // §66 (2026-09-16, Adam): the focused document's next and previous strips
            // staged in the cache's reserve — a notch is one draw. On by default; off
            // for the A/B
            HostSetting("Page staging", { ShellSettings.PAGE_STAGING },
                { settings.pageStaging },
                { v -> applySettings(settings.copy(pageStaging = v)) }),
            // §47 (2026-09-12): the radio's connection priority — high (the
            // 15 ms regime every fast flush was measured on) or balanced (the
            // on-glass experiment for the ~50-minute arm rebuilds). The phone
            // asks for it at connect and again, paced, whenever the glasses
            // move the link to slower parameters.
            HostSetting("Link", { ShellSettings.LINK_PRIORITIES },
                { settings.linkPriority },
                { v -> applySettings(settings.copy(linkPriority = v)) }),
        )
    }

    /**
     * Build W (dec. B.3): a row for a HOST category ([hostCategories]) whose value is a SHARED setting — a field of
     * [ShellSettings], synced as `shell.settings`, so the PC holds it too (`Notify · alerts`, `Notify · cleared`).
     * The Settings window calls [HostSetting.apply] on the loop (its tap), and the change goes through
     * [applySettings] like every Global row's. Build each row ONCE and keep it: Settings matches a staged row by
     * identity.
     */
    fun sharedRow(name: String, options: List<String>, current: (ShellSettings) -> String,
        set: (ShellSettings, String) -> ShellSettings): HostSetting =
        HostSetting(name, options, { current(settings) }, { v -> applySettings(set(settings, v)) })

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
        val restage = s.pageStaging != settings.pageStaging
        val relink = s.linkPriority != settings.linkPriority
        settings = s
        if (restage && s.pageStaging != "on") stageClear("the Page staging row is off")
        if (rebright) transport.setBrightness(s.brightnessAuto, s.brightness)
        if (relink) transport.setLinkPriority(s.linkPriority)
        if (recache) {
            // §73: the row decides the layout metric as well as the upload — set first, so the
            // re-derive below (every wrap, the chrome's measured cells) measures by the new one
            cachedText?.cacheMetric = s.cachedText == "on"
            clockWKey = null; statusWKey = null
            if (s.cachedText == "on") atlasEnable(rederive = false) else atlasDisable("the setting is off")
        }
        // liveApplySync passes persist=false: the store already holds the
        // EXACT synced record; putting our clamped re-encoding would re-stamp
        // it and, across versions with different ladders, ping-pong restyles
        // forever (review 2026-09-01 F5). The flag carries the same fact to
        // freshenSyncKey and saveAll (R2#4): only a LOCAL edit may ever
        // re-encode the record.
        settingsLocallyEdited = persist
        if (persist) persistence.put("shell.settings", s.toJson())
        // the Cached text row moves every string's measure (§73), so it re-derives what a
        // typography change does: every wrap, the row pitch, the chrome's cells, a whole repaint
        if (restyle || (recache && cachedText != null)) {
            relayoutEverything()
            styleKeyAtLastStart = styleKeyOf(s)        // the windows are current with THIS key
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
            retire(Rect(0, 0, comp.width, comp.height))
            SilentMode.paintAll(comp.composed, layout, c.hh, c.mm, settings.silentClock, silentSmallPainter(c))
            comp.planes = emptyList()
            comp.damageAll()
            // 0.64 R1: the clear took a shown notice with it, and the pump repaints a box only while
            // it animates or after a lift — a shown one is neither, so it was gone from the glass
            // up to its 5 s early (a synced silent-clock size, a restyle or the Cached text row
            // landing inside them). As every sibling of this arm does after its clear
            // ([enterSilent], [handleSilentTick], the quiet furl's end, [paintExclusiveFull]).
            if (notifications.active) { notifications.invalidateUnder(); paintNotification() }
            return
        }
        retire(Rect(0, 0, comp.width, comp.height))      // the bars' cells and the content: all of it is painted again
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
        paintContentOf(contentWindow())
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

    /** The window whose content [composeContent] paints — null for Main's rows. One definition for the compose and
     *  for [showsRequestOf], so the two cannot disagree. R4 item 4 (review 3A-2): while the wheel is open it is the
     *  window the area SHOWS ([previewPainted], set by [settlePreview] before its paint), not the cursor's — a notch
     *  moves the cursor at once, and a push from the window still shown was judged parked and dropped; a notch back
     *  inside the spin then met an unchanged key and the preview stayed stale until the wheel closed. */
    private fun contentWindow(): DamageWindow? =
        if (switcher.open && previewPainted != null) windows.firstOrNull { it.id == previewPainted }   // "@main": null
        else if (mode == Mode.WINDOW) current else null

    /** P6 item 2 (the loop-cost sweep's LC-1): whether a render request from window [id] is for what the content
     *  area shows. Tmux at its session list asks on every status push (2.8 s from the PC), and the arm composed
     *  whatever was on screen for it — Now Playing recomposed and flushed about 1,550 times in four hours (APK
     *  0.63's journal), a running slide ended, an open menu repainted. `CLAUDE.md`: a live window may keep the link
     *  busy only while ACTIVE. True: no id; in EXCLUSIVE the current window; Main's rows (every window's summary);
     *  the window shown (the wheel's previewed entry while one is painted); Settings (it reads every window's
     *  `appSettings()` on each render). A parked window is composed whole when it is next focused. */
    private fun showsRequestOf(id: String?): Boolean {
        if (id == null) return true
        if (mode == Mode.EXCLUSIVE) return current?.id == id
        val shown = contentWindow() ?: return true
        return shown.id == id || shown === settingsWindow
    }

    /** Paint [w]'s (or Main's) content into the content area — used both for
     *  the live surface and for switcher preview (render, not activation). */
    private fun paintContentOf(w: DamageWindow?) {
        kit.resetRail()
        // one drop for the three content painters (a list, a document, a canvas each fill the
        // content first): a tap that opens a window, a menu or the keyboard paints the content
        // twice in its frame, and so does every commit handler that composes by itself
        retire(layout.content)
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
        retire(r)              // `applyCanvasFill` above may have painted this canvas a moment ago
        v.paint(comp.composed, r)
        comp.damage(r)
        // the floor is a block worth one copy op; below it the diff is cheaper
        // than the copy plus the repairs behind it
        val exposed = declareTranslation(prev, Rect(0, 0, r.w, r.h), r) ?: return
        // 0.64 R1: nothing splits while a surface floats on the content that the owed fill's run
        // cannot repaint. A canvas that moves by itself under an open menu, the keyboard or the
        // wheel (a lyric line, a terminal's output; the box in `prev` breaks the moved block, so
        // the exposed strip holds rows of the box) sent that strip blank, the surface captured the
        // blank rows as what it covers, and the fill then painted the whole canvas over the surface
        // with nothing to paint it again: a menu that takes input and is not on the glass. Under a
        // FURLING notice the fill left the box's snapshot standing (a furl is not lifted) and the
        // furl put the blank rows back over the fresh canvas. The strip lands whole instead, in the
        // flush that carries the surface.
        if (!surfaceFloats() && splitFills() && exposed.w * exposed.h >= Slide.SPLIT_FILL_PX) {
            // §40: copy first, fill second. The strip goes out BLANK with the
            // translation (a uniform run, a few bytes — the glass moves ~70 ms
            // after the notch) and its content one message on. A tmux history
            // notch measured 352–645 ms to its first visible change as one
            // 2.4–4.3 KB flush.
            retire(exposed)    // the canvas has just drawn the strip's strings; they are not on the blank that ships
            comp.composed.fillRect(exposed.x, exposed.y, exposed.w, exposed.h, Level.BG)
            canvasFillOwed = true
            post(Msg.Run {
                if (!canvasFillOwed) return@Run                      // a later paint of the canvas landed it
                if (surfaceFloats()) {
                    // a surface opened, or a furl began, off a message queued ahead of this one: the
                    // content, then whatever floats on it, each with a fresh capture. Not from
                    // inside `applyCanvasFill` — `paintCanvasOf` calls that from a compose. The
                    // flag is cleared first, so the compose finds the block where it already is
                    // (no second move against the half-drawn frame) and starts no second split.
                    canvasFillOwed = false
                    composeContent()
                } else {
                    liftNotificationBox()
                    applyCanvasFill()
                }
            })
        }
    }

    /** A surface floats on the content that a bare repaint of the content would paint over and
     *  nothing would paint again: a menu, the keyboard, the wheel, or a notice mid-furl (a shown or
     *  unfurling notice is lifted and repainted by the pump; a furl owns its snapshot strip by
     *  strip). Read on the loop, once per decision. */
    private fun surfaceFloats(): Boolean = menu.open || keyboard.open || switcher.open || notifications.furlingOut

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
        retire(layout.content)     // the paint that left the strip owed may be in this frame still
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
            retire(layout.lens)    // with the window full, the lens repaints of several notches share a frame
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
        val key = if (stage.records.isEmpty()) null else v.contentKey()
        var slot = Math.floorDiv(y0, v.lineHeight)
        while (slot * v.lineHeight < y0 + h) {
            val idx = v.model.topLine + slot
            if (idx in 0 until v.lineCount()) {
                // §66: a line a PROVEN record holds is blitted from it — the pixels a paint produced
                // through the same path under the same live set — and the rect the compositor then
                // ships as one draw. §73: "the same" is of the SET of faces; a face may have gained a
                // glyph since the strip was staged, and the record then holds that character as the
                // host drew it where a fresh paint would blit it (a level's rounding apart at its
                // edges). The record is what goes on the glass and belief is taken from it, so the
                // two agree; a later full repaint of the line draws the cached glyph and the diff
                // carries the few cells that differ, once.
                val rec = stage.recordFor(idx, key, v.lineHeight, g.w)?.takeIf { it.state == StagedRecord.State.PROVEN }
                if (rec != null) {
                    g.blit(rec.pixels, Rect(0, (idx - rec.firstLine) * v.lineHeight, g.w, v.lineHeight), 0, slot * v.lineHeight - y0)
                } else {
                    val tmp = Gray8(g.w, v.lineHeight)
                    val paint = { v.paintLine(tmp, idx, Rect(0, 0, g.w, v.lineHeight)) }
                    cachedText?.viaInto(g, tmp, 0, slot * v.lineHeight - y0, paint) ?: paint()
                    g.blit(tmp, Rect(0, 0, g.w, v.lineHeight), 0, slot * v.lineHeight - y0)
                }
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

    /** P5 item 5 (wait-no-escape finding 2, its corrected condition): the input reserve's way out. Every pump that
     *  does not follow a ring event stops one short of the window; with that many image messages un-acked and
     *  nothing acked after them, no such pump has room, and the earlier messages can be released only by a later
     *  image ack (§34), whose only source is such a pump — frames stop for everything but input, with no word,
     *  until the msgId counter comes round or the link ends. Keyed on what the room check itself reads
     *  (`inFlight`), not on the display flushes in flight: a staged strip's writes hold slots too.
     *  [completionsSeen]: every completion the loop handles. [reserveHeldAt]: that count at the last idle tick
     *  that found the reserve holding (-1: the last tick did not). [reserveYield]: the next pump may take the
     *  whole window. [reserveSaidAt]: the count at the last `link` note — one note per held episode. Loop-only;
     *  a session start clears the tick state; asleep, nothing is held (nothing is sent). */
    private var completionsSeen = 0L
    private var reserveHeldAt = -1L
    private var reserveYield = false
    private var reserveSaidAt = -1L

    /** On the idle tick: the reserve holding on two ticks in a row with no completion of any kind between them →
     *  this tick's pump takes the whole window. A send, decided by pacing; the ack decides the rest. */
    private fun reserveCheck() {
        val st = transport.state.value                                  // ONE read for the decision
        val held = !glassesSilent && st.started && st.window > 1 &&
            st.inFlight >= st.window - 1 && st.inFlight < st.window
        if (!held) { reserveHeldAt = -1L; return }
        if (reserveHeldAt != completionsSeen) { reserveHeldAt = completionsSeen; return }   // the first such tick
        reserveHeldAt = -1L                                             // two in a row: yield, then count afresh
        reserveYield = true
        if (reserveSaidAt != completionsSeen) {
            reserveSaidAt = completionsSeen
            journal.note("link", "the input reserve yields: ${st.inFlight} image message(s) un-acked over two idle ticks " +
                "with no completion between them — this pump may take the whole window; its ack releases the earlier ones")
        }
    }

    private suspend fun pump() {
        val yieldReserve = reserveYield                 // P5 item 5: decided by this message's idle tick only
        reserveYield = false
        // ONE read (2026-09-16 review — the §62.3 item 9 / §63.3 item 8 class, in the hottest
        // path): a lapse landing between two of the three reads this used to take left
        // `comp.v2` true while `st.draw2` was already false, and the frame was assembled with
        // fills, per-lens draws and a hint for lenses that no longer had DRAW2 in force.
        val st = transport.state.value
        comp.v2 = st.draw2 && !v2RefusedThisSession   // contract 2 (`FIRMWARE.md` §4): fills, the reseed, the hint
        // package G (G-design §1): a session with no carrier (a start that rested in Silent Mode) takes no frame
        if (!running || !st.started || !st.carrierUp) return
        // a cache write that was not this shell's landed in the live cache mid-session (the self-test's
        // `live:` writes, 2026-09-15 review): the atlas's bytes on the glasses are no longer what it
        // packed, so nothing draws from them — pixels until the next session or a Cached text toggle
        if (atlas != null && st.cacheWriter.isNotEmpty() && st.cacheWriter != atlasTag && st.cacheWriter != atlasForeignWriter) {
            atlasForeignWriter = st.cacheWriter
            journal.note("atlas", "the texture cache was written by '${st.cacheWriter}' mid-session — the atlas is dropped, cached text is pixels until the next session or a Cached text toggle")
            atlasReset()
        }
        // One slot is RESERVED for the response to input (2026-09-05,
        // `HANDOFF.md` §32): animation frames, poll-driven repaints and the
        // visualizer may keep at most window-1 flushes in flight, so a
        // gesture's first flush never queues behind three of them (each
        // 150–1,200 ms on the phone's measured curve, §31.1). The pump that
        // follows a ring event may fill the window; every other pump stops one
        // short. An instant transport never sees the difference — nothing
        // stays in flight — and a window of 1 keeps its one slot.
        val cap = if (pumpPriority || yieldReserve) st.window else maxOf(1, st.window - 1)
        val room = st.inFlight < cap
        if (!room) {              // FlushDone re-pumps; §5.13 coalescing happens here
            if (pumpPriority) {
                inputAwaitsSlot = true      // §73: the flush that follows is still this gesture's
                if (inputWaitSince < 0) inputWaitSince = loopSince     // package G (E3): when the gesture began to wait
            }
            return
        }

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
                } else retire(strip)       // the box's lines of this frame that stood in the strip
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
                        retire(Rect(0, 0, comp.width, comp.height))
                        SilentMode.paintAll(comp.composed, layout, c.hh, c.mm, settings.silentClock, silentSmallPainter(c))
                        comp.damageAll()
                    }
                } else {
                    val restored = notifications.restoreUnderFinished(comp.composed)
                    if (restored != null) { retire(restored); comp.damage(restored) } else composeContent()
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
        refreshStatusWord(st)
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
            inputAwaitsSlot = false; inputWaitSince = -1L
            if (animated || slides.any { it.active } || switcher.spinning || notifications.animating) post(Msg.Pump)
            return
        }
        if (haltedEpoch != null) {
            if (haltedEpoch == comp.epoch) return       // undisplayable frame, unchanged
            haltedEpoch = null
        }
        if (comp.hasPending || comp.needsKeyframe) {
            comp.placements = stagePlacements()   // §66: what this frame may draw from the reserve
            val assembleT0 = System.nanoTime()
            val assembled = try {
                comp.assembleFlush(Geometry.rectBudget(st.window))
            } catch (e: Exception) {
                Log.e("shell", "flush assembly failed", e)
                journal.note("assemble-error", e.toString())
                raiseFault("assemble", "a flush could not be assembled: ${e.message ?: e::class.simpleName} — keyframing", urgent = true, source = "compositor")
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
                    stagedRectsShipped += comp.stagedRectsThisAssemble
                    if (pumpPriority) inputFlushPending = false
                    journal.flushSubmitted(id, assembled, label, st.transportName, Journal.Timing(
                        handleMs = handleMs, handlerMs = handlerNsThisMsg / 1_000_000, mirrorMs = mirrorNsThisMsg / 1_000_000,
                        assembleMs = assembleMs, truthMs = comp.lastTruthNs / 1_000_000,
                        compressMs = comp.lastCompressNs / 1_000_000, compressN = comp.lastCompressN,
                        cached = comp.cachedRectsThisAssemble, cacheMiss = comp.cacheMissSummary(),
                        staged = comp.stagedRectsThisAssemble,
                        unpacked = (cachedText?.unpackedDrawn ?: 0L).let { now -> (now - unpackedAtLastFlush).also { unpackedAtLastFlush = now } },
                        input = (if (pumpPriority || inputAwaitsSlot) 1 else 0).also { inputAwaitsSlot = false },
                        // package G (E3, E4): the gesture's wait in the shell for a slot, and what was on screen
                        inWaitMs = (if (pumpPriority || inputWaitSince >= 0) (if (inputWaitSince >= 0) System.currentTimeMillis() - inputWaitSince else 0L)
                            else -1L).also { inputWaitSince = -1L },
                        win = current?.id ?: "", mode = mode.name,
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
                    mirrorWhole = true                     // P6 item 5: a rolled-back flush — the next check is whole
                } catch (e: Exception) {
                    Log.e("shell", "submit failed", e)
                    journal.note("submit-error", e.toString())
                    raiseFault("submit", "a flush could not be submitted: ${e.message ?: e::class.simpleName}", urgent = true, source = "transport")
                    comp.rollback(assembled)
                    mirrorWhole = true                     // P6 item 5
                }
            }
        } else {
            // room, and nothing to send: a gesture that met a full window painted nothing after
            // all (or its paint went out with an earlier flush) — the flag is not left standing
            // for whatever flushes next (an idle tick, a push), which no ring event started
            inputAwaitsSlot = false; inputWaitSince = -1L
            // 6. nothing pending: the lowest-priority work — the cache count's reading
            //    (0.64), the atlas's next chunk (§40), then a staged strip (§66), then
            //    the preview settle
            if (!animated && !pumpLedger(st) && !pumpAtlas(st) && !pumpStage(st)) settlePreview()
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
            status = statusWord,
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
    @Volatile private var glassesBattery: Chrome.Battery? = null
    /** The glasses' level as the chrome will next paint it (null until the wire reports) — status lines and tests. */
    val glassesBatteryPct: Int? get() = glassesBattery?.pct

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
        append("reports=$divergencesReported status='$statusWord'")
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
     * the loop: a record the store already HOLDS (the same value, a stamp not
     * newer by more than the tolerance — HANDOFF §75.1) ends here, before any
     * work is done for it. Otherwise FRESHEN the key first (last-write-wins
     * must compare against the state the user sees now, not the last debounced
     * save), then apply, then live-apply so the change is visible without a
     * restart.
     *
     * The held-record test is the Run's FIRST statement and runs on the loop,
     * not where the record is read off the wire (0.64 P3, review finding 1):
     * records for one key are applied in the order they were posted, and a
     * test made on the reader's thread compares against a store the queued
     * ones have not reached yet (K = v1; the peer sends v2, then v1 again
     * inside the tolerance: the second would be dropped against the old v1 and
     * this side would end on v2).
     *
     * What skipping the freshen costs: the freshen also puts live state that
     * no save has captured yet, and a save tick follows INPUT only, so state
     * that changed with no ring input (a track change in Music) is not put by
     * this record any more. It reaches the store and the peer at the next
     * input-driven save, the next record for the key that is not held, or an
     * orderly stop. Before this, a handshake's 77 records each ran the
     * freshen and a whole-store save on the loop, every other handshake.
     */
    fun postSync(key: String, value: JsonObject, stamp: Long): Boolean {
        if (!running) return false
        // R2 (RE-4): `running` is read before the post, so a record can land behind the stop's Shutdown; the
        // loop's drain then runs `dropped` — the store-direct apply the caller makes when this returns false.
        // Inside its own `try`: the loop's `!running` arm calls `dropped` too.
        post(Msg.Run(dropped = {
            try {
                if (persistence.tryApplyRemote(key, value, stamp))
                    Log.i("sync", "'$key' from the peer met a stopped shell — stored; the next start shows it")
            } catch (e: Exception) { Log.e("sync", "storing synced '$key' failed", e) }
        }) {
            try {
                if (persistence.holdsSame(key, value, stamp)) return@Run
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
     *  disk must not learn at the next restart that nothing persisted.
     *  P7 item 2 (OS-2): a FAULT of class `state`, shown whatever Global `Notify · Damage` says (it went
     *  through the WM's courtesy door and was dropped with that row off). LOOP-ONLY like [raiseFault],
     *  with one exception said at its call: `stopLocked`'s no-loop arm. The streak's latch is per
     *  session — cleared at [startLocked] beside the episodes' table. */
    private fun noteSaveLanded(landed: Boolean) {
        if (!landed && !saveFailureRaised) {
            saveFailureRaised = true
            raiseFault("state", "state save FAILED — changes will not survive a restart", urgent = true)
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
            put("gatedBy", n.gatedBy ?: "")
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
                    gatedBy = o["gatedBy"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
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
        /** §47: interval × (latency + 1) at or above this is the slow regime
         *  at once. The fast set is 15 ms / 1 (30), Android's balanced grant
         *  30–50 ms / 0; the glasses' idle set 105 ms / 4 (525). */
        const val SLOW_PARAMS_MS = 100.0
        /** §47: an ack floor above this for [FLOOR_ALERT_MINUTES] minute
         *  ticks raises the notice — the fast floor measures ~60 ms, the slow
         *  parameters ~530. */
        const val FLOOR_ALERT_MS = 250.0
        const val FLOOR_ALERT_MINUTES = 2
        /** §47: a face not drawn in this many frames may be evicted from a
         *  full atlas; a repack runs at most once per [ATLAS_REPACK_PACE_MS].
         *  Derived, not measured: a window switch composes a handful of
         *  frames, so the previous window's faces are stale a few notches
         *  in, while the chrome's are drawn every frame and never qualify. */
        /** §54: undelivered atlas chunks in a row before cached text is switched off. */
        const val ATLAS_UNDELIVERED_LIMIT = 3
        /** §66: a staged record's write goes out in messages of this many bytes (a multiple of 4,
         *  under the atlas's 3,072 B messages). */
        const val STAGE_CHUNK = 3064
        /** §66: write failures in a row, readings that did not answer in a row, or shortfalls in the
         *  session, before staging is off for the session. */
        const val STAGE_FAILURES_MAX = 3
        /** §66: a relay keep that records nothing — a strip is painted the way a live paint is,
         *  but its draws are not this frame's. */
        private val NO_RECORDS = Rect(0, 0, 0, 0)
        const val ATLAS_RECENT_FRAMES = 12L
        const val ATLAS_REPACK_PACE_MS = 45_000L
        /** §73: the last of the cache a face nobody is drawing may not be packed into — the
         *  faces in use gain glyphs there (a body face gains ~0.1 KB per new character, a
         *  first screen of a new window a few KB). */
        const val ATLAS_GROW_HEADROOM = 4 * 1024
        /** Consecutive ImgResCmd refusals that put the glasses to sleep (§36). */
        const val REFUSALS_TO_SLEEP = 3
        /** The asleep shell's check pacing (§37.0): the glasses' own state is
         *  read by the 60 s device-info poll, so a check between polls would
         *  learn nothing new. */
        const val SILENT_PACING_MS = 60_000L
        /** Package G (E4): a loop message whose handler held the loop this long or more gets a `slow` note. */
        const val SLOW_HANDLER_NS = 250_000_000L
        /** One `silent` note per this many pacing checks while asleep (§42): an
         *  hour at the 60 s pacing. */
        const val SILENT_CHECKS_PER_NOTE = 60
        const val DIVERGE_EPISODES_MAX = 3
        const val DIVERGE_QUIET_CHECKS = 10
        /** P6 item 5: the agreement check compares every row at least this often (the decision: one a minute). */
        const val MIRROR_WHOLE_EVERY_NS = 60_000_000_000L

        /** How many depth regions a window may ask for inside the content
         *  area (§3, `DamageWindow.contentPlanes`). Every region splits the
         *  panel into more pieces, and the piece count is what the rect budget
         *  is spent on — a window that wants a dozen has misunderstood depth. */
        const val MAX_WINDOW_PLANES = 4

        /** Chrome never shrinks below the design size to fit a bar — the bar
         *  is already sized for it at 100 %; the cap only limits growth. */
        const val CHROME_SCALE_FLOOR = 1.0
        /** The transport faults the phone hears (Adam's ruling 5, `HANDOFF.md` §69): a feature
         *  held back after a reset, a build without the required capabilities, a stalled window.
         *  Every other transport fault is a notice on the glasses only. */
        val URGENT_TRANSPORT_FAULTS = setOf("holdback", "capability", "stall")
        /** Transport faults that are the EXPECTED answer to a frame that lands after the shell went to
         *  sleep — a journal note, never a notice (§36; 0.64, the P4 review's finding 3): the firmware's
         *  own refusal of an image (`imgres`), and the model's refusal of a v2 op, a cached draw or a
         *  cache write decoded with no lease — `decode` from the simulator's transport
         *  (`SimTransport`), `mirror/decode` from a hardware transport's mirror (`CfwTransportBase`). */
        val FAULTS_EXPECTED_ASLEEP = setOf("imgres", "decode", "mirror/decode")
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
