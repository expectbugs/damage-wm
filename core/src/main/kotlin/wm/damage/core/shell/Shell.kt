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
import wm.damage.core.motion.PopupPrograms
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
import wm.damage.core.transport.bind
import wm.damage.core.transport.effects
import wm.damage.core.transport.transitionRegion
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
    val notifications = Notifications(chromeText).also { n -> n.arrivalFor = { noticeArrival(it) } }
    private val switcher = Switcher(chromeText, { iconSource })
    private val menu = MenuSurface(chromeText)
    /** §4.8 the keyboard (2026-09-01) — routed like the menu while open. */
    private val keyboard = KeyboardSurface(chromeText)

    /** The host's theme-icon source (2026-09-01) — set before start; every
     *  icon call site falls back to the drawn set when null or on a miss. */
    @Volatile var iconSource: wm.damage.core.gfx.IconSource? = null

    /** Test/introspection: is the floating context menu open, and whose? */
    val menuIsOpen: Boolean get() = menu.open
    /** The status cell's word as last derived (`Status.kt`: "ok", "ERROR …", "DIVERGE …") — tests, status lines and the
     *  health view (any thread). */
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
    /** The notice's next timer step, while one is owed ([armGraceStep]); null when none is — cleared by the step's
     *  handling and by every stop ([stopGrace]). Loop-confined. */
    private var graceJob: kotlinx.coroutines.Job? = null
    /** When the running grace started (`System.nanoTime`): step k is due k × 250 ms after it, so a loop that is late
     *  for one step does not push the rest back. */
    private var graceT0Ns = 0L
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

    /** R-b H2: the link cell's tag — the worst health line's, set by the phone's `PhoneHealth` on a change only; its
     *  age is composed at each chrome build ([HostTag.text]), so it moves with the clock cell's minute. */
    @Volatile var hostTag: HostTag = HostTag.NONE

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

    /**
     * Build S1-2 Q3 (the spec's §2.1; `CONTRACT.md` §7): the host's notice mirror (`app/NoticeMirror.kt`), set by the
     * host builder before the first start; null (a tool, a test, a host with no record) leaves every notice as it was.
     * Reading effects go to it from input only: [dismissNotice], the tap-through, an activation from input
     * ([activated]); never from Silent Mode's drop, a requeue or an abandoned furl.
     */
    @Volatile var noticeInput: wm.damage.core.app.NoticeInput? = null

    /** S1-5 S: the record's settings moved — read on the loop ([settingsFromRecord]); held, the latest, while no session
     *  runs (a keyed push), and run again at the next start. */
    private val settingsListener = object : wm.damage.core.app.SourceListener {
        override fun changed(state: wm.damage.core.app.WindowState, keys: Set<String>) =
            services.runOnShell("settings:record") { settingsFromRecord() }
    }

    /**
     * Build S1-5 S (the lean spec's §4.2): Settings' values on the PC's record — on the PC its own record, on the phone
     * the working copy. Set by the host builder before the first start, and to null at its stack's stop (the source
     * outlives the stack on the PC). Null (a tool, a test, `--snapshot`'s own shells without one): the local store
     * alone, as before. With it: the start shows the local copy, then the record's values once the source has heard
     * Settings; [applySettings] sends one op per changed item; a record change made elsewhere is applied here.
     */
    @Volatile var settingsSource: wm.damage.core.app.WindowSource? = null
        set(v) {
            field?.removeListener(settingsListener)
            field = v
            v?.addListener(settingsListener)
        }

    /** S1-5 S: per record item, the values this shell wrote and has not yet seen come back, oldest first — an older
     *  write's change that arrives after a newer write is never read as the record moving (a fast adjust is never
     *  stepped back). Games' guard (`GamesGlasses.echo`). On the loop only. */
    private val settingsInflight = HashMap<String, ArrayDeque<kotlinx.serialization.json.JsonElement>>()

    /** S1-5 S: settings applied because the record moved (a test reads it: an echo of this shell's own write is never
     *  one). */
    @Volatile internal var settingsFromRecordCount = 0
        private set

    /** S1-2 Q3: the presenters' formatters by window ([ShellServices.noticeFormatter]). */
    private val noticeFormatters = java.util.concurrent.ConcurrentHashMap<String, (wm.damage.core.app.NoticeRecord) -> String>()

    /** S1-2 Q3: what the mirror hands this shell. Each goes to the loop under the notice's own key: while no session
     *  runs the latest per notice is held (a withdraw after a pop of the same occurrence leaves nothing to show; a
     *  newer occurrence replaces the older), exactly as keyed pushes are held (§72). */
    val noticeSink: wm.damage.core.app.NoticeSink = object : wm.damage.core.app.NoticeSink {
        override fun pop(rec: wm.damage.core.app.NoticeRecord) = noticeFromRecord(rec)
        override fun withdraw(ref: wm.damage.core.app.NoticeRef, queuedOnly: Boolean) = withdrawNotice(ref, queuedOnly)
    }

    /** S1-2 Q3: a record of the PC's notice record, shown as the box its app's own raise showed: the source and thread
     *  of today's (`DAMAGE · <source>`, the thread after `<source>:`), the body through the app's presenter formatter
     *  when one is registered, the time its raise formatted on this device. Safe from any thread. */
    fun noticeFromRecord(rec: wm.damage.core.app.NoticeRecord) = services.runOnShell("notice:${rec.id}") {
        val body = rec.app?.let { noticeFormatters[it] }?.let { f ->
            try { f(rec) } catch (e: Exception) {
                Log.e("shell", "the ${rec.app} notice formatter failed on ${rec.id}: its body is shown as raised", e)
                rec.body
            }
        } ?: rec.body
        handleNotice(Notifications.Notice("DAMAGE · ${rec.source}", rec.thread, body, recordTime(rec.raisedAt),
            emergency = false, appId = rec.app, target = rec.target,
            gatedBy = if (rec.app == null) rec.gatedBy else null, record = rec.ref))
    }

    /** S1-2 Q3 (§5 N5): [ref] moved on another interface — queued entries leave; the shown box folds away as a
     *  dismissal does (Silent Mode's small form is dropped), unless [queuedOnly]. Safe from any thread. */
    fun withdrawNotice(ref: wm.damage.core.app.NoticeRef, queuedOnly: Boolean) = services.runOnShell("notice:${ref.id}") {
        val shown = notifications.current?.record == ref && !notifications.furlingOut
        if (shown && !queuedOnly && !quiet) settleSlidesForOverlay()     // the furl restores from a settled base
        when (notifications.withdraw(ref, queuedOnly, silent = quiet)) {
            Notifications.Withdrawn.NOTHING -> return@runOnShell
            Notifications.Withdrawn.QUEUED -> if (notifications.active && !notifications.furlingOut) paintNotification()  // its +N
            Notifications.Withdrawn.KEPT -> {}                                     // its timer runs on
            Notifications.Withdrawn.FOLDING -> stopGrace()   // the furl runs via the pump; a box folding away owes no step
            Notifications.Withdrawn.DROPPED -> repaintAfterSilentDrop()
        }
        scheduleSave()
    }

    /** H:MM of a record's raise on this device's clock, as [systemClock] writes the time a notice shows. */
    private fun recordTime(ms: Long): String {
        val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return "%d:%02d".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute)
    }

    val services: ShellServices = object : ShellServices {
        override fun requestRender(window: DamageWindow) { post(Msg.Invalidate(window.id)) }

        /** Stage L: read at a paint, on the loop (the transport's state one read). */
        override fun lanesInForce(): Boolean = lanesInForce(transport.state.value)
        override fun lanesHeld(): Boolean = lanes.open

        override fun noticeFormatter(app: String, format: ((wm.damage.core.app.NoticeRecord) -> String)?) {
            if (format == null) noticeFormatters.remove(app) else noticeFormatters[app] = format
        }

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
            is TransportEvent.DiagFlags, is TransportEvent.Wear,
            is TransportEvent.Applied, is TransportEvent.LocalRead, is TransportEvent.LocalBody -> transEventsDroppedWhileDown.incrementAndGet()
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

    /** Build RA-1: the loop's turns, for the host's liveness watcher ([LoopWatch]); a message is described only when
     *  its turn is stuck (the `slow` note's words). The host sets [LoopTurns.clock] before the start. */
    val turns = LoopTurns { m ->
        when (m) {
            is Msg.Trans -> "transport ${m.ev.javaClass.simpleName}"
            is Msg.Run -> "Run " + m.tag.ifEmpty { "(untagged)" }
            else -> m.javaClass.simpleName
        }
    }

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
    /** S1-5 S (test introspection, `SettingsCoreTest`): the Settings window after a start, and the shell's own Global rows. */
    internal fun settingsWindowForTest(): SettingsWindow = settingsWindow
    internal fun shellRowsForTest(): List<HostSetting> = globalFontRows

    internal fun settingsApps(): List<Pair<String, List<HostSetting>>> {
        // K1-2 stage 3c: the app's `Scroll effect` row only at ladder stage 5, where a window's list is bound (DP2's rule: a
        // row that does nothing is not shown) — left out of the same STABLE set below it, so every row keeps its identity
        val stage5 = transport.state.value.effects
        val own = windows.mapNotNull { w ->
            val rows = w.appSettings() +
                (if (w === settingsWindow) emptyList() else styleRowsFor(w.id).let { s -> if (stage5) s else s.filter { it.name != APP_SCROLL_ROW } })
            rows.takeIf { it.isNotEmpty() }?.let { w.name to it }
        }
        val host = hostCategories.filter { (name, _) -> windows.none { it.name.equals(name, ignoreCase = true) } }
        return own + host
    }

    // ------------------------------------------------------------------ set-up
    fun register(w: DamageWindow) {
        // the sub-record key scheme parses `window.<id>.<subKey>` at the FIRST
        // dot — a dotted id would let one window's removal sweep remove
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
     *  the loop like a Settings row would: persisted, and with a [settingsSource] its changed items go to the record. */
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
                { settingsApps() },
                localInput = { transport.state.value.localInput },
                // §7.9 as ruled (decisions W, D-2): the row lifts the hold-back and keeps the stored stage
                localRearm = { transport.devProbe("local", "rearm") },
                bindBuild = { transport.state.value.damageFeatures and wm.damage.core.wire.DamageMsg.FEATURE_BIND != 0 },
                motionHint = { motionHint() },
                scrollPlays = { scrollPlaysWords() },
                effectsInForce = { transport.state.value.effects })
            register(settingsWindow)
        }
        for (w in windows) w.onRegistered(services)

        // a leftover surface from this instance's PREVIOUS session (the keeper
        // stops and restarts the same Shell on link edges) must not carry a
        // stale under-capture forward — content can change across the gap
        // (a record's change), and the close-restore would blit old pixels
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
        // stay "reported" — saveAll would remove the real record, exactly what
        // the guard exists to prevent
        subReported.clear()
        badPlaneSaid.clear()          // a new session re-reports a bad region once

        // restore persisted state (§9.1: survives WM restart) BEFORE the loop; a session start over a store already
        // loaded keeps what it holds (Persistence.load)
        persistence.load()
        stateLoaded = true
        settings = ShellSettings.fromJson(persistence.get("shell.settings"))
        // S1-5 S (§4.2's (1)): once the source has heard Settings, the record's values over the local copy (and the copy
        // written); until then the local copy shows — never the defaults over his settings
        settingsSource?.takeIf { it.heard() }?.let { src ->
            val r = recordSettingsOver(settings, src.state())
            if (r != settings) { settings = r; persistence.put("shell.settings", r.toJson()) }
        }
        // §73: BEFORE any window restores or wraps — with cached text on, every layout of the
        // session measures by the cache's metric, whether or not its face is uploaded yet
        cachedText?.cacheMetric = settings.cachedText == "on"
        clockWKey = null; statusWKey = null
        layout = layoutFor(settings)
        refreshStyles()   // every window draws through its per-app transform (Style.kt)
        // §73: the same windows live across sessions and keep what they laid out. A typography or
        // Cached text change that reached the store while no session ran (the record's, folded just
        // above) is read just above — and nothing told the windows: a book held from the
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
            // (F2c/d): a later removal may remove them, while keys a build
            // ignores (no sub-record support) or failed to restore stay
            // protected — "didn't load it" is never a removal
            try {
                // an empty blob is never a held record (see saveAll)
                subReported.getOrPut(w.id) { HashSet() }
                    .addAll(w.saveSubState().filterValues { it.isNotEmpty() }.keys)
            } catch (e: Exception) {
                Log.e("shell", "saveSubState of ${w.id} at restore failed", e)
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
        testHold = false                  // the `hold=on` dev probe never crosses a session
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
                activated(current!!, ActivationSource.RESTORE)   // S1-2 Q3: a restore is no input — nothing read
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
            comp.requestKeyframe("session start")
            localSessionStart()                   // build C2 (P1): the start's bind batch rides the keyframe's tail
            noticeReset(); noticeStepsSaid.clear()  // build K1-4a: a fresh lease frees the cache — the notice's programs go again
            lanes.reset("session start"); laneSaid.clear()   // stage L: and the held lanes' areas; every instance ended with the lease
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
        // probe's answer, a change from the record applied, a flush's answer kept for the next session). Returned
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
            turns.begin(m)                 // RA-1: a turn begins (the liveness watcher's measure)
            loopHandling = m::class.simpleName
            loopSince = System.currentTimeMillis()
            // a ring event (or a typed line) is the one message whose flush
            // may take the window's last slot — see pump()
            pumpPriority = m is Msg.In || (m is Msg.Trans && marksInput(m.ev))
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
                turns.end()                // RA-1: this turn ends here
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
                    // his look at 0.81: the save builds every window's state on the loop (up to 86 ms there; the disk write is
                    // off it already) — not inside a burst or while Main's motion may still draw: again in 2 s
                    is Msg.SaveTick -> if (m.gen == saveGen) { if (listBindBurstOpen() || listMotionMayDraw()) scheduleSave() else saveAll() }
                    is Msg.Notice -> handleNotice(m.n)
                    // P6 item 2 (LC-1): a parked window's request composes nothing; its divider tick rides the chrome
                    is Msg.Invalidate -> if (!showsRequestOf(m.windowId)) chromeDirty = true
                        else if (mode == Mode.EXCLUSIVE) paintExclusiveDelta()
                        else if (mode != Mode.SILENT) { syncLayout(); composeContent() }
                    is Msg.Run -> m.action()
                    is Msg.Shutdown -> try {
                        stopGrace()               // the notice's timer owes no step past its session
                        holdPushes()              // §72: keyed pushes are held from here on
                        dropKeyboard()    // the draft goes back to its requester BEFORE the save (review 2026-09-01 K5)
                        // the focused window's session-bound state ends with the
                        // session (a focused poll pace, a pane subscription) —
                        // start() re-activates the restored window (R2-W13)
                        try { current?.onDeactivate() } catch (e: Exception) { Log.e("shell", "onDeactivate at shutdown", e) }
                        saveAll(sync = true)      // the last state must be ON DISK before stop() returns
                    } finally {
                        // P5 part a (review 1b RE-4's sibling): `running` goes false only AFTER the final save. A
                        // message another thread posts meanwhile (a tick, a probe, a transport event) goes to the
                        // drain, which decides it as `post` would have.
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
            turns.end()                    // RA-1: the turn is over; the loop is idle until its next message
            if (!running && m is Msg.Shutdown) { leftThroughShutdown = true; break }
        }
        } finally {
            turns.end()                    // RA-1: a loop that ends mid-turn (its scope cancelled) leaves no open turn
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
        if (testHold) releaseHold("an input event")     // the `hold=on` dev probe never outlives a gesture
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
        gestureBookkeeping(type)

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
            if (type == EvenHubMsg.EV_DOUBLE_CLICK) {
                val before = screenKey(wm.damage.core.motion.Transitions.Tier.LOCK)   // build C3 (DP2): leaving the silent clock is the Lock tier, reversed
                exitSilent()
                transitionAct(before, wm.damage.core.motion.Transitions.Tier.LOCK, reversed = true)
            }
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
                EvenHubMsg.EV_SCROLL_TOP -> { switcher.scroll(-1, spinFrames()); paintSwitcherFrame(); wheelPhoneNotches++ }
                EvenHubMsg.EV_SCROLL_BOTTOM -> { switcher.scroll(1, spinFrames()); paintSwitcherFrame(); wheelPhoneNotches++ }
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

    /** What every gesture does before its own effect (decisions AF.3: one function, called by `handleInput` and by an
     *  adopted 115): the dev probes end, the input echo, the rest and the notice's grace restart, Main wakes, and a scroll
     *  or a tap ends the long-press chord. */
    private fun gestureBookkeeping(type: Int) {
        if (testHold) releaseHold("an input event")     // the `hold=on` dev probe never outlives a gesture
        if (type == EvenHubMsg.EV_SCROLL_TOP || type == EvenHubMsg.EV_SCROLL_BOTTOM || type == EvenHubMsg.EV_CLICK) chordArmedAtMs = 0L
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
    }

    private fun handleNotificationGesture(type: Int) {
        when (type) {
            EvenHubMsg.EV_CLICK -> {
                val n = notifications.current
                if (n != null && !n.emergency && n.appId != null) {
                    // opening the app is not clearing the queue: the next box
                    // keeps its grace (§4.5 rule 1), so the tap meant for the
                    // app just entered does not land on it (round 2, d2-10)
                    dismissNotice(markRead = true, clearing = false, op = wm.damage.core.app.Notices.MARK_READ)
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
        // C3 SC1 (the lead's part CD): a row that applies per notch goes through [applySettings], which composes the
        // content with the new value — the notch's one composition; a row that stages (Size, a host row: applied on the
        // tap) changes only its own text, composed here. Composing after both painted every applied notch twice (his
        // stage-5 session: an adjust notch's handler 56 ms against 28–30 for the acts that paint once, measured)
        if (mode == Mode.WINDOW && current === settingsWindow) {
            val staged = settingsWindow.adjustingStages
            if (settingsWindow.onScrollAdjust(delta)) {
                if (staged) composeContent()
                scheduleSave()
                return
            }
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
        // build C3 (DP2): a tap that changes the screen is a Select. C3 X3 (decisions CD (b)): a tap Settings says changes no
        // screen (a row entering its adjust level, a row with nothing to open) plays no transition, so nothing is recorded
        val before = screenKey(wm.damage.core.motion.Transitions.Tier.SELECT,
            record = !(mode == Mode.WINDOW && current === settingsWindow && !settingsWindow.tapChangesScreen()))
        when (val v = focusedView()) {
            is WindowView.ListView -> v.onCommit(v.model.cursor)
            is WindowView.DocView -> v.onTap()
            is WindowView.CanvasView -> v.onTap?.invoke()
            else -> {}
        }
        composeContent()
        transitionAct(before, wm.damage.core.motion.Transitions.Tier.SELECT, reversed = false)
        scheduleSave()
    }

    /** §16.2: the hand-off caller a root-level back returns to (one level).
     *  Set by openWindow, consumed on use, cleared by explicit navigation. */
    private var backTarget: DamageWindow? = null
    /** One visible alarm per save-failure streak (R3s#11). */
    private var saveFailureRaised = false

    /** Per window: the sub-record keys its saveSubState has REPORTED this
     *  session — the only keys the removal sweep may remove (F2c/d). */
    private val subReported = HashMap<String, MutableSet<String>>()

    /** Sub-keys already reported as wrongly-empty — one loud line each. */
    private val emptySubSaid = HashSet<String>()

    /** Illegal depth regions already reported this session (§3,
     *  `DamageWindow.contentPlanes`) — the complaint is loud once, not once a
     *  frame. */
    private val badPlaneSaid = HashSet<String>()

    private fun backFocused() {
        // build C3 (DP2): leaving a window or a level is a Back (Select reversed in time); entering the shell's silent
        // clock and leaving it are the Lock tier, leaving it reversed. C3 X4T item 3 (`C3-V2T-review.md` 3, decisions
        // AT (3)): no tier in Exclusive mode — no act follows there, so nothing is picked or recorded. C3 X3 (decisions CD
        // (b)): a Back that leaves Settings' adjust level changes one row, not the screen — nothing is recorded for it
        val before = screenKey(when (mode) {
            Mode.WINDOW -> wm.damage.core.motion.Transitions.Tier.BACK
            Mode.MAIN, Mode.SILENT -> wm.damage.core.motion.Transitions.Tier.LOCK
            Mode.EXCLUSIVE -> null
        }, record = !(mode == Mode.WINDOW && current === settingsWindow && settingsWindow.adjustingNow()))
        backFocusedInner()
        when (before.mode) {
            Mode.WINDOW -> transitionAct(before, wm.damage.core.motion.Transitions.Tier.BACK, reversed = true)
            Mode.MAIN -> transitionAct(before, wm.damage.core.motion.Transitions.Tier.LOCK, reversed = false)
            Mode.SILENT -> transitionAct(before, wm.damage.core.motion.Transitions.Tier.LOCK, reversed = true)
            Mode.EXCLUSIVE -> {}
        }
    }

    private fun backFocusedInner() {
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
        exclusiveRepaints++
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
        comp.requestReseed("exclusive mode ended")
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
        activated(w, from)
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
        val margin = switcherMargin()
        retire(switcher.hole(layout, margin))   // the previewed window's rows under the panel (and its margins), the last spin frame's names
        val panel = switcher.paint(comp.composed, layout, margin, stage5 = transport.state.value.effects)
        comp.damage(panel)
        chromeDirty = true        // the top bar previews too, snapping (§4.3)
    }

    /** C3 DW-0 (the DW spec §2.6, decisions BD's O2): at stage 5 ([LinkState.effects]) the black margin the open wheel
     *  clears either side of its panel — the panel's disparity (Global `Depth`, or the content's plane when Global is 0),
     *  so one rect per lens over the panel holds only the wheel; 0 below stage 5 and with no planes (today's hole and plane
     *  map). Read by the hole ([paintSwitcherFrame]) and the plane map ([updatePlanes]) alike, once per compose each. */
    private fun switcherMargin(): Int {
        if (!transport.state.value.effects || quiet) return 0
        return if (settings.depth != 0) settings.depth else contentDepth()
    }

    /** The content's plane: the FOCUSED app's own depth when it set one, else the Global row; Main is always the Global row. */
    private fun contentDepth(): Int = (if (mode == Mode.WINDOW) current?.let { appDepth(it) } else null) ?: settings.depth

    /** Live preview (§4.3): the window BEHIND the panel becomes the selected
     *  one — a RENDER, never an activation; lowest-priority, runs on settle. */
    /** The wheel's preview of the selected window, once per selection when the spin has stopped. True when it painted. */
    private fun settlePreview(): Boolean {
        if (!switcher.open || switcher.spinning) return false
        // C3 DW-1b-1 (the DW spec §4.3): with the wheel bound the spin is on the glasses — the preview waits while its burst
        // is open or its motion may still draw (the held cells' wait), and goes after the burst's SETTLE (lowest priority)
        if (wheelBind.offered != null && (local.burstOpen || listMotionMayDraw())) return false
        val sel = switcher.selected() ?: return false
        val key = sel.window?.id ?: "@main"
        if (previewPainted == key) return false
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
        return true
    }

    // ------------------------------------------------------------------ notices
    private fun handleNotice(n: Notifications.Notice) {
        if (!noticeAllowed(n)) {
            Log.i("shell", "notification from '${n.source}' filtered by settings (§4.5)")
            return
        }
        // silent AND exclusive: the small form, auto-dismissing (verdict 23); behind a transition it waits as in every mode
        // (K1 §9; the branch below queues it)
        if (quiet && transition == null) {
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
        } else if (switcher.open || menu.open || keyboard.open || transition != null) {
            // decision 6: wait behind the wheel — and behind the context menu
            // (§16.11) — queued unshown until the surface closes; build C3 (DP2, K1 §9): behind a transition until its end
            notifications.post(n, layout, show = false)
            return
        }
        val shownBefore = notifications.current
        val replacedRect = notifications.post(n, layout)
        if (replacedRect != null) {
            // same-thread coalesce replaced the visible box: its size may have
            // changed — repaint content under the union, then the new box
            // (its timer runs on: a new body is not an input)
            composeContent()
        } else {
            // a box beginning its unfurl over a mid-flight slide would capture
            // a moving base — motion yields to the overlay (§6.3, round 2 #A4)
            settleSlidesForOverlay()
            // a NEW box: no step is owed for it until its unfurl ends. One queued behind a shown box leaves that
            // box's timer alone — a reset he sees must be one his input made (2026-11-19)
            if (notifications.current !== shownBefore) scheduleGrace()   // a box shown at once starts now (K1-4a)
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
            // MUSIC is NOT gated here: the window owns its six Notify rows
            // (WINDOWS.md §1), and the Global "Notify · Music" row that APKs up
            // to 0.17 carried could have persisted a false that no row could
            // undo — a hidden gate (docs audit 2026-09-02)
            // `Notify · Damage` is the WM's own events (`DESIGN.md` §4.5): a notice that names its app
            // is gated by that app's own rows in its own Settings category, never by a Global row
            // (P7 item 1, OS-1); S1-5 S removed the hidden SMS and MAIL gate (no row set it). An answer to Adam's own gesture
            // (`Notice.answer`, P7 item 2) is not a courtesy event either. A host category's notice
            // (`Notice.gatedBy`, build W: the bus watcher's "Bastion") was gated by that category's own rows. R-b H2 (the
            // lead's ruling, `RB-decisions.md` Part B): a notice that names Global (the health record's) is Damage's own,
            // and Global's row for those is this one.
            "DAMAGE" -> n.appId != null || n.answer || (n.gatedBy != null && n.gatedBy != Notifications.GLOBAL) ||
                settings.notifyDamage
            else -> true
        }
    }

    /** Every caller is a gesture on the box (S1-2 Q3, §5 N4): a record notice sends [op] — `dismiss` for a double-tap,
     *  a long-press or an app-less tap, `read` for the tap-through. */
    private fun dismissNotice(markRead: Boolean, clearing: Boolean = true, op: String = wm.damage.core.app.Notices.DISMISS) {
        settleSlidesForOverlay()      // the furl restores from a snapshot:
        val n = notifications.dismiss(markRead, clearing)  // settle the base
        stopGrace()                   // a box folding away owes no step
        if (n?.fault == true) faults.mute(n.thread)        // a dismissed fault stays dismissed for its episode (§69)
        n?.record?.let { ref ->
            if (op == wm.damage.core.app.Notices.MARK_READ) noticeInput?.read(ref) else noticeInput?.dismiss(ref)
        }
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

    /**
     * One step of the notice's focus timer (§4.5, Adam 2026-11-19): the top rule grows by an eighth and the ink rises
     * a step; the eighth step makes the bar whole and gives the box focus. A step paints into the frame and the pump
     * that follows this message sends it — never in the input's reserved slot (`pumpPriority` is an input's). The
     * timer ends here when there is nothing for it to do: no box, a box with focus, one folding away, a quiet form
     * (rule 4), a box still unfurling (the unfurl's end starts it).
     */
    private fun handleGrace(gen: Int) {
        if (gen != graceGen) return
        graceJob = null
        if (!graceRuns()) return
        if (switcher.open) { scheduleGrace(); return }   // never steal the wheel's gestures: it waits, from empty
        if (notifications.engine) {
            // build K1-4a: the glasses run the steps; the phone's one tick is the arrival's end (the timer's PLAY goes)
            // or the timer's end (focus, the program's END already on the glass)
            if (noticePhase == NoticePhase.ARRIVAL) { noticeTimerStart(); return }
            while (!notifications.advanceGrace()) { /* the bar whole */ }
        }
        if (notifications.advanceGrace()) {
            notifications.takeFocus()
            updatePlanes()        // the box steps FORWARD (§4.5); the plane-map
            paintNotification()   // diff re-renders it and cleans its old seams
        } else {
            paintNotification()   // this step's eighth of the bar and its ink
            armGraceStep()
        }
    }

    /** A box the timer runs for: up, fully unfurled, without focus, not folding away, in the window form. */
    private fun graceRuns(): Boolean = notifications.active && !notifications.focused && !notifications.animating && !quiet

    private fun handleSilentTick(gen: Int) {
        if (gen != silentDismissGen || !quiet || !notifications.active) return
        // silent boxes auto-dismiss after 5 s and STAY UNREAD (§4.5 —
        // deliberate divergence from G2CC's mark-at-display)
        notifications.dropSilent()
        repaintAfterSilentDrop()
    }

    /** The quiet surface after its small box left (the 5 s drop; S1-2 Q3: a withdraw), the next box shown fresh. */
    private fun repaintAfterSilentDrop() {
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

    /**
     * An activation's notice effect (§4.5): the app's notices read — the shown box stays, the queued ones leave. S1-2
     * Q3 (§5 N4): from input only, so a record notice of the app sends `read`; a RESTORE is no input and does neither
     * (a restore that sent `read` would be a write no input made).
     */
    private fun activated(w: DamageWindow, from: ActivationSource) {
        if (from == ActivationSource.RESTORE) return
        noticeInput?.let { input ->
            val all = listOfNotNull(notifications.current) + notifications.queued()
            for (n in all) if (n.appId == w.id && !n.read) n.record?.let(input::read)
        }
        notifications.markAppRead(w.id)
    }

    // ------------------------------------------------------------------ ticks
    private fun handleMinute() {
        noticeInput?.minute()     // S1-2 Q3: held records whose end has passed pop together (on the mirror's executor)
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

    /**
     * The grace starts again (§4.5 rule 1): an input on an unfocused box, a box's unfurl ending, a box shown again
     * after a surface closed. The bar goes back to empty and the ink to its arrival level AT ONCE — painted into the
     * frame being composed, so an input's own flush carries it (his words: "showing the bar reset on inputs") — and
     * the first step is owed 250 ms on. Arms nothing for a box the timer does not run for ([graceRuns]).
     */
    private fun scheduleGrace() {
        stopGrace()
        if (!graceRuns()) return
        // build K1-4a: the glasses' timer where it can run (one PLAY in this frame's flush: an input's own, a start's,
        // the arrival's), the phone's steps (build NT) where it cannot
        val n = notifications.current!!
        val arrival = notifications.takeArrival()
        val style = when (arrival) { Notifications.Arrival.FADE -> PopupPrograms.Style.FADE; Notifications.Arrival.SLIDE -> PopupPrograms.Style.SLIDE
            else -> PopupPrograms.Style.TIMER }
        val prog = noticeProg(style, n) ?: if (style != PopupPrograms.Style.TIMER) noticeProg(PopupPrograms.Style.TIMER, n) else null
        if (prog != null) {
            if (!notifications.engine) { notifications.engine = true; paintNotification() }   // the box as the program's END
            noticePhase = if (prog.style == PopupPrograms.Style.TIMER) NoticePhase.TIMER else NoticePhase.ARRIVAL
            // fix A1: a style is owed, not bytes — the PLAY is built in the pump that sends it, after the input's own
            // act (a window with its own height, a depth change moves the box; the programs are the layout's)
            noticeOwed = prog.style
            noticeArrivalMs = prog.periodMs * prog.f
            graceT0Ns = System.nanoTime()
            armGraceStep()
            return
        }
        // the programs went (a new layout, the slice yielded): the phone's steps — one chain, armed below (fix B3)
        if (notifications.engine) { notifications.engine = false; noticeOwed = null; paintNotification() }
        if (notifications.resetGrace()) paintNotification()
        graceT0Ns = System.nanoTime()
        armGraceStep()
    }

    /** The next step of the running grace, due at its place on the 250 ms grid from [graceT0Ns]. */
    private fun armGraceStep() {
        val gen = graceGen
        if (graceByHand) { graceJob = kotlinx.coroutines.Job(); return }
        val dueMs = when {
            !notifications.engine -> (notifications.graceStep + 1) * Notifications.GRACE_STEP_MS
            noticePhase == NoticePhase.ARRIVAL -> noticeArrivalMs
            else -> Notifications.GRACE_STEPS * Notifications.GRACE_STEP_MS
        }
        val due = graceT0Ns + dueMs * 1_000_000L
        graceJob = scope.launch {
            delay(maxOf(0L, (due - System.nanoTime()) / 1_000_000L))
            if (running) post(Msg.GraceTick(gen))
        }
    }

    /** No step owed: the box was dismissed, the session ends, or a new grace is about to start. */
    private fun stopGrace() {
        graceGen++
        graceJob?.cancel()
        graceJob = null
    }

    // ------------------------------------------------------------------ build K1-4a: the notice's timer on the engine
    /** The timer's phase on the glasses: an arrival drawn from the box's pixels, then the timer (`PopupPrograms`). */
    private enum class NoticePhase { TIMER, ARRIVAL }
    private var noticePhase = NoticePhase.TIMER
    /** The arrival's length on the phone's clock (its steps × its period): when the timer's PLAY goes. */
    private var noticeArrivalMs = 0L

    /** One program the glasses hold for one box (a line count at the layout in force): its record in the notice region
     *  ([CfwModes.noticeRegion]), written and counted by the stage lane as a staged strip is, and its period and steps. */
    private class NoticeProg(val style: PopupPrograms.Style, val lines: Int, val program: wm.damage.core.motion.MotionProgram,
        val period: Int, val f: Int, val rec: StagedRecord) {
        val periodMs: Long get() = period * 1000L / 1024
    }
    private class NoticeStageKey(slot: Int, val style: PopupPrograms.Style) : StageKey(NoticeStageKey::class, slot, 1, 0, 0) {
        override fun toString() = "the notice's ${style.name.lowercase()} program $firstLine"
    }
    private var noticeProgs: List<NoticeProg> = emptyList()
    /** What [noticeProgs] were laid out for (the box's geometry, the depth, the cache, the arrival rows). */
    private var noticeProgsFor: List<Any>? = null
    /** The PLAY owed to the next flush's tail, as the style it plays (fix A1: built from the layout in force when it
     *  goes, after the drawing subs: the shadow holds the box's END picture when the PLAY captures it), the flush that
     *  carried the last one, and that PLAY's tag. */
    private var noticeOwed: PopupPrograms.Style? = null
    private var noticePlayFlush: Long? = null
    private var noticeTag = -1
    private var noticeTags = 0
    /** The box the engine's timer was refused for (a refused PLAY, a failed flush): the phone's steps until it goes. */
    private var noticeEngineOff: Notifications.Notice? = null
    /** Each reason the phone's steps were used where the engine is in force, said once a session. */
    private val noticeStepsSaid = HashSet<String>()

    /** What the programs are laid out for now; null where the notice has no engine timer (no region, ENGINE not in force). */
    private fun noticeLayoutKey(st: LinkState): List<Any>? {
        if (st.flagsInForce and wm.damage.core.wire.DamageMsg.FLAG_ENGINE == 0) return null
        val region = CfwModes.noticeRegion(st.cacheSize, st.bind) ?: return null
        return listOf(region.first, layout.notificationMax, layout.content, notifications.ruleOffset(), notifications.maxLines(layout),
            settings.depth, settings.popupFade, settings.popupSlide, settings.popupLength, settings.popupFrames)
    }

    /** The counted program for [style] and the box of [n], or null — and then why, said once a session. */
    private fun noticeProg(style: PopupPrograms.Style, n: Notifications.Notice): NoticeProg? {
        if (quiet || n.emergency || n === noticeEngineOff) return null
        val st = transport.state.value
        val why = when {
            st.flagsInForce and wm.damage.core.wire.DamageMsg.FLAG_ENGINE == 0 -> return null     // no engine: NT, nothing to say
            CfwModes.noticeRegion(st.cacheSize, st.bind) == null -> "no notice region in a ${st.cacheSize / 1024} KiB cache without BIND"
            // the coordinator's ruling (a): a notice costs Reader nothing — the slice is Page staging's while it pages
            pagingInFront() -> "page staging in front"
            noticeCover(n) > Notifications.BAR_INSET -> "a nearer plane covers ${noticeCover(n)} of the box's edge columns (the bar's run starts ${Notifications.BAR_INSET} in)"
            sliceHeld(CfwModes.noticeRegion(st.cacheSize, st.bind)!!) -> "page staging's strips hold the slice"
            !st.draw2 || !st.leaseHeld || glassesSilent || sleep != null -> return null
            !ledgerReadable(st) -> "its programs cannot be counted on the glasses (Cached text off, or no count on this build)"
            noticeProgsFor != noticeLayoutKey(st) -> "its programs are not laid out for this layout yet"
            else -> null
        }
        val lines = notifications.linesFor(n, layout)
        val p = if (why == null) noticeProgs.firstOrNull { it.style == style && it.lines == lines } else null
        if (p != null && p.rec.state == StagedRecord.State.PROVEN) return p
        if (style == PopupPrograms.Style.TIMER) noticeStepsSay(why ?: "its program is not counted on the glasses yet")
        return null
    }

    private fun noticeStepsSay(why: String) {
        if (noticeStepsSaid.add(why)) journal.note("notice", "the focus timer is the phone's steps — $why")
    }

    /** The PLAY of [p] in slot 2, the box captured into the notice region after the programs. */
    private fun noticePlayOf(p: NoticeProg): ByteArray? {
        val st = transport.state.value
        val region = CfwModes.noticeRegion(st.cacheSize, st.bind) ?: return null
        val tag = NOTICE_TAG + (noticeTags++ and 0xFFF)
        return try {
            wm.damage.core.motion.Motion.play(p.program, PopupPrograms.SLOT, p.period, tag,
                (region.first + CfwModes.NOTICE_PROGRAMS) / 4, IntArray(4)).also { noticeTag = tag }
        } catch (e: wm.damage.core.geom.LintError) { noticeFallback("its PLAY is refused offline (${e.message})"); null }
    }

    /** The arrival's end: the timer's PLAY (it closes the arrival — same slot — and captures the END the arrival wrote). */
    private fun noticeTimerStart() {
        val n = notifications.current ?: return
        val p = noticeProg(PopupPrograms.Style.TIMER, n) ?: run { noticeFallback("its timer program left the glasses during the arrival"); return }
        noticePhase = NoticePhase.TIMER
        noticeOwed = PopupPrograms.Style.TIMER
        graceT0Ns = System.nanoTime()
        armGraceStep()
    }

    /** The engine's timer cannot run for the box up: the phone's steps from the start, said once a session with [why]
     *  (null: said where it was decided). */
    private fun noticeFallback(why: String?) {
        noticeOwed = null
        val n = notifications.current
        if (why != null) { noticeEngineOff = n; noticeStepsSay(why) }
        if (n == null || !notifications.engine) return
        notifications.engine = false
        if (!graceRuns()) return
        stopGrace()
        notifications.resetGrace()
        paintNotification()
        graceT0Ns = System.nanoTime()
        armGraceStep()
    }

    /** The PLAY to put at the end of the batch about to go, or null: one is owed, and the batch leaves nothing of the
     *  frame behind (a split frame would capture half the box). */
    /**
     * Fix A1: the owed PLAY's program, resolved for the box as it is now — after the input's own act — before the frame
     * is assembled (so a fallback's repaint rides this frame): the owed style's program, an arrival's falling to the
     * timer's (its tick re-armed for the timer), or none — then the phone's steps from here, with the reason said.
     */
    private fun noticeResolve(): NoticeProg? {
        val owed = noticeOwed ?: return null
        val n = notifications.current
        if (n == null || !notifications.engine || !graceRuns()) { noticeOwed = null; return null }
        val p = noticeProg(owed, n) ?: if (owed != PopupPrograms.Style.TIMER) noticeProg(PopupPrograms.Style.TIMER, n) else null
        if (p == null) { noticeFallback("its program for the box as it is now is not on the glasses"); return null }
        if (p.style != owed) {                              // the arrival cannot play: the timer at once, its tick for 2.0 s
            noticePhase = NoticePhase.TIMER; noticeOwed = PopupPrograms.Style.TIMER
            stopGrace(); graceT0Ns = System.nanoTime(); armGraceStep()
        }
        return p
    }

    /** The PLAY of the resolved program, or null: none, or the batch leaves part of the frame behind (a split frame, or
     *  cells held behind a local motion — fix B2 — would capture something other than the box). */
    private fun noticeTakePlay(p: NoticeProg?, leavesPending: Boolean): ByteArray? {
        if (p == null || leavesPending) return null
        noticeOwed = null
        return noticePlayOf(p)
    }

    /** RIGHT's motion notify ([detail], the transport's note): a refusal of the box's PLAY ends the engine's timer for it. */
    private fun noticeNotify(detail: String) {
        val n = MotionNotify.parse(detail) ?: return
        if (n.tag != noticeTag) return                      // an older PLAY's (a restart's own PLAY closed it)
        if (n.outcome.startsWith("refused")) { noticeFallback("RIGHT refused its PLAY (${n.outcome.removePrefix("refused: ")})"); return }
        // fix B1: a phone write into the box closed the live timer on the glasses (a scroll's slide frames, a badge, a
        // coalesced body, cached text beside it — the transport's CLOSE, §5.6): the box shows its END, full ink. The
        // phone's steps take the rest of the grace from the step the timer had reached, on the same grid: focus at the
        // same 2.0 s. (A restart's own PLAY closes an older tag; the focus CLOSE comes after the grace.)
        if (n.outcome == "closed" && notifications.engine && graceRuns() && noticeOwed == null) {
            val elapsedMs = (System.nanoTime() - graceT0Ns) / 1_000_000
            stopGrace()
            notifications.engine = false
            if (noticePhase == NoticePhase.ARRIVAL) { graceT0Ns = System.nanoTime(); notifications.graceStepAt(0) }
            else notifications.graceStepAt((elapsedMs / Notifications.GRACE_STEP_MS).toInt())
            paintNotification()
            armGraceStep()
            // fix B-1: the rest of this box's grace stays on the phone's steps — the next input's write would close an
            // engine timer again (a scroll's cached text beside the box on every notch): one hand-over a box at most
            noticeEngineOff = notifications.current
            noticeStepsSay("a write into the box closed its timer (the phone's steps take the rest of the grace)")
        }
    }

    /**
     * The notice's programs (idle lane, after the atlas and before Page staging): laid out again when the box's
     * geometry, the depth, the cache or the arrival rows change, and each written into its slot of the notice region by
     * the stage lane and counted by the ledger as a staged strip is. A record a cleared lane left SENT goes again.
     */
    private suspend fun pumpNoticeStage(st: LinkState): Boolean {
        val key = noticeLayoutKey(st) ?: return false
        val region = CfwModes.noticeRegion(st.cacheSize, st.bind) ?: return false
        if (pagingInFront()) { noticeYield("page staging in front"); return false }
        if (sliceHeld(region)) { noticeYield("page staging's strips hold the slice"); return false }
        if (cacheWritesHeld()) return false     // C3 DM-T (D6), 0.83 integration: the notice's programs wait for a glyph run's end
        if (stageFailed || glassesSilent || !st.draw2 || !st.leaseHeld || !ledgerReadable(st)) return false
        if (key != noticeProgsFor) noticeLayout(st, key)
        for (p in noticeProgs) if (p.rec.state == StagedRecord.State.SENT && stageInFlightRec !== p.rec) p.rec.state = StagedRecord.State.WANTED
        val rec = noticeProgs.firstOrNull { it.rec.state == StagedRecord.State.WANTED }?.rec ?: return false
        return stageOwnRecord(rec, st)
    }

    /** One record of the stage lane's own books ([ownRecs]: the notice's programs, the held lanes' areas) written, after
     *  Page staging's holds, every one (fix A2): the atlas's lane, the count's reading, a re-send owed, one unproven
     *  record at a time. [whole] (Music stage SF2, `MM-S-V2-review.md` 2; the held lanes' areas): the record goes as ONE
     *  message when its entry list fits mode 25's inflate (`FIRMWARE.md` §5.9: 16,384 B) and the transport says a write
     *  submitted now goes deflated — the transitions' rule ([Transport.nextListDeflates]): the transport deflates one
     *  waiting message at a time, so a second piece right behind the first went raw. Otherwise today's pieces. */
    private suspend fun stageOwnRecord(rec: StagedRecord, st: LinkState, whole: Boolean = false): Boolean {
        if (atlasInFlight != null || atlasQueue.isNotEmpty() || atlasLapsedOwed || atlasCheckInFlight) return false
        if (stageInFlight != null || stageCursor != null || ledgerReadInFlight || st.inFlight != 0 || comp.hasPending || comp.needsKeyframe) return false
        if (ledgerBaseOwed(st)) return tryLedgerRead(st)
        if (stage.count(StagedRecord.State.ACKED) > 0 || ownRecs().any { it.state == StagedRecord.State.ACKED }) return false
        if (atlasResendMissing > 0L) return false
        val one = whole && rec.encoded.size + 4 <= CfwModes.ZWRITE_MAX && transport.nextListDeflates()
        stageSubmit(rec, st, chunk = if (one) CfwModes.ZWRITE_MAX - 4 else STAGE_CHUNK)
        return true
    }

    private fun noticeLayout(st: LinkState, key: List<Any>) {
        val region = CfwModes.noticeRegion(st.cacheSize, st.bind) ?: return
        val out = ArrayList<NoticeProg>()
        for (lines in 1..notifications.maxLines(layout)) {
            val geo = PopupPrograms.Geo(notifications.windowBox(lines, layout), notifications.ruleOffset(), Notifications.BAR_INSET, settings.depth)
            val (pa, fa) = ListStaging.timing(geo.areaPx, ListStaging.ScrollMotion(settings.popupLength, settings.popupFrames))
            val hold = (wm.damage.core.motion.Motion.MAX_STEPS - fa - 1).coerceAtLeast(1)
            for (style in PopupPrograms.Style.entries) {
                if (style == PopupPrograms.Style.FADE && !settings.popupFade || style == PopupPrograms.Style.SLIDE && !settings.popupSlide) continue
                val slot = (lines - 1) * PopupPrograms.Style.entries.size + style.ordinal
                val off = region.first + slot * PopupPrograms.SLOT_BYTES
                val prog = try {
                    if (style == PopupPrograms.Style.TIMER) PopupPrograms.timer(off / 4, geo) else PopupPrograms.arrival(off / 4, geo, style, fa, hold)
                } catch (e: wm.damage.core.geom.LintError) { noticeStepsSay("its ${style.name.lowercase()} program cannot be built (${e.message})"); continue }
                val rec = StagedRecord(NoticeStageKey(slot, style), Gray8(1, 1), prog.bytes.copyOf((prog.bytes.size + 3) / 4 * 4)).also { it.off = off }
                out += NoticeProg(style, lines, prog, if (style == PopupPrograms.Style.TIMER) PopupPrograms.TIMER_PERIOD else pa, fa, rec)
            }
        }
        noticeProgs = out
        noticeProgsFor = key
        journal.note("notice", "the notice's ${out.size} program(s) laid out (${out.sumOf { it.rec.encoded.size }} B at ${region.first}, " +
            "the capture at ${region.first + CfwModes.NOTICE_PROGRAMS})")
    }

    /**
     * The coordinator's ruling (2026-11-19, form (a)): a notice costs Reader nothing it has today. Page staging keeps its
     * whole stage reserve; the notice's slice of it is the notice's only while no window that stages pages is in front
     * and none of Page staging's strips lies in it — strips stay in the ring when their window leaves, and are not
     * written over.
     */
    private fun pagingInFront(): Boolean = settings.pageStaging == "on" && stagedDoc() != null

    /**
     * Fix A3: how many of the box's edge columns a nearer plane covers on a lens at the depths in force — a region nearer
     * than the box (disparity below the Global depth it sits at until focus) on its rows, within the depth's reach of its
     * sides: its pieces beside the box render nearer and win over the box's shifted columns (Main's selection band covers
     * 4 at the defaults; a nearer app depth or a window's own plane more). Only the `self` tracks reproduce what is there;
     * the bar's run (8 in from each end) must not reach it.
     */
    private fun noticeCover(n: Notifications.Notice): Int {
        val d = settings.depth
        if (d <= 0) return 0
        val box = notifications.fullRect(n, layout, false)
        var near = d
        // the box's neighbourhood on each side, as far as its shift reaches: every REGION there (fix C-1: the remainder,
        // plane 0 with no region, is the base the compositor copies first and paints every region over — it never
        // covers the box's shifted columns)
        for (strip in listOf(Rect(box.x - d, box.y, d, box.h), Rect(box.right, box.y, d, box.h))) {
            for (p in comp.planes) {
                if (p.rect == box || p.rect.intersect(strip) == null) continue
                near = minOf(near, p.disparity)
            }
        }
        return maxOf(0, d - near)
    }
    private fun sliceHeld(region: IntRange): Boolean =
        stage.ring?.resident.orEmpty().any { it.off >= 0 && it.off <= region.last && it.off + it.size4 > region.first }

    /**
     * The slice goes back to Page staging: the notice's programs leave the books (an owed PLAY naming them with them)
     * before Page staging writes there. A timer still drawing from them ends on the glasses at the first write over the
     * slice (close-on-meet, `FIRMWARE.md` §5.6), the box showing the shadow — its END, full ink — until its focus tick, so
     * the picture never stays dim; a restart then takes NT's steps from the start. Said once each time.
     */
    private fun noticeYield(why: String) {
        if (noticeProgs.isEmpty() && noticeProgsFor == null) return
        noticeProgs = emptyList(); noticeProgsFor = null
        journal.note("notice", "the notice's programs leave the slice — $why")
    }

    /** A new session (a fresh lease frees the cache), a cache another writer touched: every program goes again. */
    private fun noticeReset() {
        noticeProgs = emptyList(); noticeProgsFor = null
        noticeOwed = null; noticePlayFlush = null; noticeEngineOff = null
    }

    /** The arrival a box about to show takes ([Notifications.arrivalFor]): a random pick among the `Popup ·` rows that are
     *  on (none on: at once); the engine's styles only where their program is counted, else today's unfurl. */
    private fun noticeArrival(n: Notifications.Notice): Notifications.Arrival {
        if (quiet || n.emergency) return Notifications.Arrival.UNFURL
        val on = listOfNotNull(Notifications.Arrival.UNFURL.takeIf { settings.popupUnfurl },
            Notifications.Arrival.FADE.takeIf { settings.popupFade }, Notifications.Arrival.SLIDE.takeIf { settings.popupSlide })
        if (on.isEmpty()) return Notifications.Arrival.NONE
        val ok = on.filter { a -> when (a) {
            Notifications.Arrival.FADE -> noticeProg(PopupPrograms.Style.FADE, n) != null
            Notifications.Arrival.SLIDE -> noticeProg(PopupPrograms.Style.SLIDE, n) != null
            else -> true
        } }
        // fix B5: an engine style that cannot run gives what he left on — the unfurl if it is on, else at once
        return ok.randomOrNull() ?: if (settings.popupUnfurl) Notifications.Arrival.UNFURL else Notifications.Arrival.NONE
    }

    // ------------------------------------------------------------------ Music's motion update, stage L: the held lanes
    /** The window in front's held lanes, animated by the glasses in slots 0 and 1 (`WindowLanes.kt`). */
    private val lanes = WindowLanes { journal.note("lane", it) }
    /** Each reason a window's lanes were refused, said once a session. */
    private val laneSaid = HashSet<String>()
    private var laneWakeAt = 0L
    private var laneInForceWas = false

    /** The stage lane's own books beside the registry — the notice's programs and the lanes' areas — for the ledger. */
    private fun ownRecs(): List<StagedRecord> = noticeProgs.map { it.rec } + lanes.records

    /** The session can run held lanes (the look a window paints follows it: [ShellServices.lanesInForce]). */
    private fun lanesInForce(st: LinkState): Boolean = lanes.off == null && st.effects && st.draw2 && st.leaseHeld &&
        CfwModes.musicRegion(st.cacheSize, st.bind) != null && transport.mirror.modelsMotion && ledgerReadable(st)

    private fun laneSay(why: String) { if (laneSaid.add(why)) journal.note("lane", why) }

    /** What the front asks of the lanes now: the window in front's set, or null and why (null where nothing is said). */
    private fun laneWanted(inForce: Boolean): Pair<LaneSpec?, String?> {
        if (!inForce) return null to null
        val w = current ?: return null to "no window in front"
        val why = when {
            glassesSilent || sleep != null -> "Silent Mode"
            mode != Mode.WINDOW && mode != Mode.EXCLUSIVE -> "the window is not in front"
            switcher.open -> "the wheel is open"
            menu.open || keyboard.open -> "an overlay is open"
            transition != null -> "a transition is in hand"
            local.front != null -> "a list is bound in front"
            pagingInFront() -> "page staging in front"
            else -> null
        }
        if (why != null) return null to why
        // the window's area: its content, or in its exclusive mode the safe rect it paints whole (stage S: Music Mode's strip
        // sits in the status band's rows)
        val area = if (mode == Mode.EXCLUSIVE) layout.safe else layout.content
        val spec = try { w.lanes(area) } catch (e: wm.damage.core.geom.LintError) { laneSay("${w.id}'s lanes are refused: ${e.message}"); null }
            ?: return null to "${w.id} wants no lanes now"
        for (r in spec.rects) {
            if (!area.contains(r) || r != r.alignOut() || !comp.laneInOnePlane(r)) {
                laneSay("${w.id}'s lane $r is refused: it must lie on the damage grid, inside the window's area and inside one plane")
                return null to "a lane off the grid"
            }
        }
        // a notice box over a lane: its paint goes there, and its PLAY's close pass would end its own timer (spec §4.1) —
        // the box at its nearer depth and the lanes at theirs, each widened by the largest shift (32 px) either way
        val n = notifications.current
        if (notifications.active && n != null) {
            val box = notifications.fullRect(n, layout, false)
            val wide = Rect(box.x - 32, box.y, box.w + 64, box.h)
            if (spec.rects.any { Rect(it.x - 32, it.y, it.w + 64, it.h).overlaps(wide) }) return null to "a notice box over a lane"
        }
        return spec to null
    }

    /** Before the frame: the slice for the lanes (Page staging's strips there dropped, his answer 6), the chain's step,
     *  and the cells the compositor holds or releases. */
    private fun lanesBefore(st: LinkState) {
        val inForce = lanesInForce(st)
        if (inForce != laneInForceWas) { laneInForceWas = inForce; current?.let { w -> post(Msg.Invalidate(w.id)) } }  // its look follows
        val region = CfwModes.musicRegion(st.cacheSize, st.bind)
        val (spec, why) = laneWanted(inForce)
        if (spec != null && region != null) stagePagesDropUnder(region, "the held lanes take the stage reserve's lower 16 KiB (his answer 6); " +
            "Reader stages them again when it is next in front")
        val d = spec?.let { comp.laneDisparity(it.rects[0]) } ?: 0
        lanes.pump(WindowLanes.Env(System.currentTimeMillis(), spec, why, d, if (inForce) region else null, if (inForce) effectsToken(st) else null,
            written = { (l, r) -> comp.laneWritten(l, r) }) { slot ->
            transport.mirror.motionTagIn(Arm.RIGHT, slot)
        })
        lanes.takeRelease()?.let {
            comp.laneRelease(it)
            // Music stage SF2 (`MM-S-V2-review.md` 1): in Exclusive mode the window paints now, in the pump that takes the
            // release and before its frame — the released cells are written from the place shown now, not from the strip's
            // last once-a-second paint (which went out for one flush at every chain end). The notice's furl paints here too
            if (mode == Mode.EXCLUSIVE) paintExclusiveDelta()
        }
        comp.laneHold = lanes.hold                    // empty whenever a list is in front: the gate ends the chain first
    }

    /** After the frame: the chain's PLAY or PLAY-AFTER alone, and the pump owed when the next stretch is due. */
    private suspend fun lanesAfter() {
        lanes.takeSend()?.let { msg ->
            try { lanes.sent(transport.submit(FlushRequest(emptyList(), comp.epoch, "LANE", tail = listOf(msg)))) }
            catch (e: Exception) { lanes.notSent(e.message ?: e::class.simpleName ?: "the submit failed") }
        }
        val at = lanes.wakeAt
        if (at != null && at != laneWakeAt) { laneWakeAt = at; pumpAfter(maxOf(0L, at - System.currentTimeMillis())) }
    }

    /** RIGHT's motion body: one of a lane's stretches drew, was closed or refused. */
    private fun laneNotify(detail: String) {
        val n = MotionNotify.parse(detail) ?: return
        if (n.tag and 0xF000 != WindowLanes.TAG) return
        lanes.notify(n.tag, n.outcome, n.drawn, n.folded, n.stepUs)
        post(Msg.Pump)
    }

    /** The idle pump's step for the lanes' areas: as the notice's programs are written ([pumpNoticeStage]). */
    private suspend fun pumpLaneStage(st: LinkState): Boolean {
        val rec = lanes.wanted() ?: run {
            for (r in lanes.records) if (r.state == StagedRecord.State.SENT && stageInFlightRec !== r) r.state = StagedRecord.State.WANTED
            lanes.wanted()
        } ?: return false
        if (pagingInFront() || cacheWritesHeld()) return false
        if (stageFailed || glassesSilent || !st.draw2 || !st.leaseHeld || !ledgerReadable(st)) return false
        return stageOwnRecord(rec, st, whole = true)
    }

    /** Tests: the lanes' chain (open, its (slot, tag)s, its areas' states, strikes off). */
    internal fun lanesForTest(): String = "open ${lanes.open} tags ${lanes.openTags.joinToString { "%d:0x%04x".format(it.first, it.second) }} " +
        "areas ${lanes.records.joinToString { "${it.key.firstLine}:${it.state}" }} off ${lanes.off}"
    internal val lanesOpenForTest: Boolean get() = lanes.open

    private fun scheduleSilentDismiss() {
        val gen = ++silentDismissGen
        scope.launch { delay(5_000); if (running) post(Msg.SilentTick(gen)) }
    }

    private fun scheduleSave() {
        val gen = ++saveGen
        scope.launch { delay(2_000); if (running) post(Msg.SaveTick(gen)) }
    }

    // ------------------------------------------------------------------ build C2: local input (`FIRMWARE.md` §6.7)
    /** The phone's rules P1–P11, the tap rule and the table repair (stage DP2). Idle unless BIND is in force at stage 4
     *  on a bit-10 build: below that nothing here sends a byte. Confined to the loop. */
    private val local = wm.damage.core.local.LocalRules({ kind, detail -> journal.note(kind, detail) },
        { why -> journal.note("local", "keyframe: $why"); comp.requestKeyframe("local input: $why"); post(Msg.Pump) },
        { why -> localTelemetryRead(why) })

    /** Decisions AA.2: ONE telemetry read of RIGHT, launched (nothing waits on it); its record reaches the rules as every
     *  record does (`TransportEvent.LocalRead`: a refusal record naming mode 27 sends the table again and keyframes). A
     *  read that ends without an answer is said; the minute's read still comes. */
    private fun localTelemetryRead(why: String) {
        journal.note("local", "telemetry read asked: $why")
        scope.launch {
            val failed = try { if (transport.telemetry("local check") == null) "no record (the session ended, or this build has none)" else null }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: wm.damage.core.transport.ReadingEnded) { e.message ?: "ended" }
                catch (e: Exception) { e.message ?: e.toString() }
            if (failed != null) post(Msg.Run { journal.note("local", "the telemetry read asked at a burst's SETTLE was not answered: $failed") })
        }
    }
    /** The surface that offers a bound list while it is in front and staged: the slot-0 holder's ([slot0]). */
    private fun boundSurface(): wm.damage.core.local.BoundSurface? = slot0()
    /** The list the rules were last told is in front (identity: a re-stage is a new [BoundList]). */
    private var localFrontNow: wm.damage.core.local.BoundList? = null
    /** K1-2 stage 3 (the spec §6's BF row): each recent fire's list by its tag (S), from its 115's field 7 — the last
     *  [REPORT_TAGS_KEPT]; cleared at a session start. A 114 body of that tag is that list's ([localBodyRead]). Loop-only. */
    private val reportListByTag = object : LinkedHashMap<Int, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Int>?): Boolean = size > REPORT_TAGS_KEPT
    }
    /** Tail-only flushes (an answer, the lone SETTLE, a re-send with nothing to draw): a failed one keyframes (§6.11). */
    private val localOnlyFlushes = HashSet<Long>()
    /** Every flush that carried the rules' messages, until its completion: a refused one sends the table again. */
    private val localTailFlushes = HashSet<Long>()
    private var localSettleAt = Long.MAX_VALUE
    /** The last `local` counters' note written (AU.4), cleared at a session start. */
    private var localNoteSaid: String? = null
    /** The held cells' release the loop was last asked back for (AF.7): one wake per motion end. */
    private var deferWakeAt = 0L

    /**
     * K1-2 stage 3 (decisions CK A8, the K1-2 spec §5.1): WHICH LIST HOLDS SLOT 0 — decided here alone, and asked by Main's
     * holder, a window's and (DW-1b) the wheel's: the bound list in front with nothing drawn over it (§6.7). No menu,
     * switcher or keyboard, and no notice up at all — focused, floating, unfurling or furling (decisions AF.1: its box lies
     * over the list's rows); then Main in front, or a window whose list is staged for the view in front ([windowListMatches]:
     * a `ListView` of two rows or more) with Settings not adjusting (the adjust level is a cover: its notch changes a value,
     * not the list). A window's list with no staging of its own is no list in front: its row is not read, as today.
     * C3 DW-1b-1 (`C3-DW1-amendment.md` §4.1; decisions DA (c) 2, (c) 4): the wheel's case first — with the switcher open the
     * wheel's list while it is offered ([wheelBind]), else none: an open wheel is a cover, so neither Main's list nor a
     * window's is in front under it. It returns the surface, the one thing every caller reads (the rules' [BoundSurface]).
     */
    private fun slot0(): wm.damage.core.local.BoundSurface? = when {
        switcher.open -> wheelBind.takeIf { it.offered != null }
        menu.open || keyboard.open || notifications.active || notifications.animating -> null
        mode == Mode.MAIN -> mainList.surface
        mode == Mode.WINDOW && !(current === settingsWindow && settingsWindow.isAdjusting) && windowListMatches() -> windowList.surface
        else -> null
    }

    /** The glasses list [slot0]'s surface binds — by identity with the three holders' surfaces — or −1 (none in front). */
    private fun slot0List(): Int = when (slot0()) { mainList.surface -> mainList.listNo; windowList.surface -> windowList.listNo; wheelBind -> WHEEL_LIST; else -> -1 }

    /** A bound list in front with nothing drawn over it (§6.7): [slot0] has one. */
    private fun localUncovered(): Boolean = slot0() != null

    /** The bound list in front with nothing drawn over it: the surface's own while it is uncovered and offered. */
    private fun localFront(): Pair<wm.damage.core.local.BoundSurface, wm.damage.core.local.BoundList>? {
        if (!localUncovered()) return null
        val sfc = boundSurface() ?: return null
        val b = sfc.bound() ?: return null
        return sfc to b
    }

    /** The row the rules read: the surface's own whenever it is uncovered, offered or not — a withdrawn list's row stays
     *  current, so a DISCARD naming it is right (decisions AF.2) — and −1 under a cover, where it cannot move. */
    private fun localRow(): Int = if (localUncovered()) boundSurface()?.row() ?: -1 else -1

    /** The list in front, told to the rules in the pump that draws its change (a cover's CLEAR, a return's SET). */
    private fun syncLocalFront() {
        if (!local.active) return
        val f = localFront()
        val b = f?.second
        if (b === localFrontNow) return
        val was = localFrontNow
        localFrontNow = b
        local.front(b, f?.first?.row() ?: -1, if (b != null && was != null && was.list == b.list) f.first.shift(was, b) else 0)
    }

    /** What the shell does with one report or stock message (P3, P5, the tap rule). */
    /** [eventId]: the report's (a verdict on a 115), which says an adopted notch's direction. */
    private fun localVerdict(v: wm.damage.core.local.LocalRules.Verdict, eventId: Int? = null) {
        localVerdictsForTest?.add("$mode $v")
        when (v) {
            wm.damage.core.local.LocalRules.Verdict.Nothing -> {}
            is wm.damage.core.local.LocalRules.Verdict.Apply -> { asInput(); edgeAssembleMark = assembleCalls; edgeAtMs = System.currentTimeMillis(); handleInput(v.type, EvenHubMsg.SRC_RING) }
            is wm.damage.core.local.LocalRules.Verdict.Adopt -> {
                // an adopted notch is a gesture as any other (decisions AF.3): the same bookkeeping as `handleInput`'s, the
                // cursor saved to the record; its picture is the lens's own, so no slide and no input slot (its COMMIT
                // needs none)
                val was = boundSurface()?.row() ?: v.row
                if (boundSurface() === wheelBind) wheelLocalNotches++          // C3 DW-1b-1: for the open's `wheel` note
                boundSurface()?.setRow(v.row)
                // the echo's direction is the report's event's (AL.6, R5: a correction may leave the row where it was)
                gestureBookkeeping(eventId?.let { wm.damage.core.local.BoundList.stockEvent(it) }
                    ?: if (v.row < was) EvenHubMsg.EV_SCROLL_TOP else EvenHubMsg.EV_SCROLL_BOTTOM)
                scheduleSave()
                // C3 DW-F item 1: the wheel's adopted notch paints what the phone's own wheel notch paints — the panel, the
                // preview following at its settle — never the window behind it
                if (boundSurface() === wheelBind) paintSwitcherFrame() else composeContent()
            }
            is wm.damage.core.local.LocalRules.Verdict.Act -> {
                asInput()
                val sfc = boundSurface()
                if (sfc != null && sfc.row() != v.row) sfc.setRow(v.row)
                handleInput(EvenHubMsg.EV_CLICK, EvenHubMsg.SRC_RING)
            }
        }
        post(Msg.Pump)
    }

    /** 0.83 integration, tests: each verdict the rules gave, with the mode it met ("MAIN Adopt(row=3)"); null: not kept. */
    @Volatile internal var localVerdictsForTest: MutableList<String>? = null

    /** Decisions AF.8: an act or a gesture applied from a 115 (not from the stock message, which is an input message of
     *  its own) is input — its first flush may take the reserved slot, and the journal marks it so the trial prices it. */
    private fun asInput() { pumpPriority = true; inputFlushPending = true }

    /** P1: a session start — S_heard, the arming uptime, the start's bind batch owed to the first flush (the keyframe's
     *  tail, in stream order after it). */
    private fun localSessionStart() {
        val st = transport.state.value
        wheelBind.offered = null                  // C3 DW-1b-1: the wheel is offered again only once its block is counted again
        val f = localFront()
        localFrontNow = f?.second
        localOnlyFlushes.clear(); localTailFlushes.clear(); for (h in lists) h.listBind.tailsForgotten()
        transitionEnd("a session start"); transitionCloses = emptyList()      // build C3 (DP2): the start's keyframe ends every instance
        glyphHoldUntilMs = 0L                                       // X2T item 1: so nothing is left to land on either lens
        glyphHoldPumpOwed = false
        for (h in lists) { h.stepWindow.clear(); h.periodSamplesSinceChange = 0 }          // decisions BF: the measured window is the session's
        reportListByTag.clear()
        localNoteSaid = null
        localSettleAt = Long.MAX_VALUE
        for (h in lists) {
            h.listBindRefusedFor = null                         // AF.6: a staging that did not fit is tried again at every start
            h.listBindBlockedSaid = null
            h.listBindRefusalSaid = null
        }
        local.sessionStart(st.bind && st.leaseHeld, st.bindSHeard0, st.bindArmUptime, f?.second, f?.first?.row() ?: -1, settings.taps)
        if (st.bind) journal.note("local", "session start: S_heard ${st.bindSHeard0}, armed at uptime ${st.bindArmUptime}, " +
            "list in front ${f?.second?.list ?: "none"}, Taps ${settings.taps.word}")
    }

    /** Build K1-4a: the notice's PLAY alone (an input with nothing else to draw: the restart is its whole flush). */
    private suspend fun submitNoticeOnly(play: ByteArray) {
        try {
            noticePlayFlush = transport.submit(FlushRequest(emptyList(), comp.epoch, "NOTICE", tail = listOf(play)))
        } catch (e: Exception) {
            noticeFallback("its PLAY was not submitted (${e.message ?: e::class.simpleName})")
        }
    }

    /** A flush of the rules' messages alone (nothing to draw): one sub-message as its own image, more as a batch. */
    /** The flush's id, or null when nothing went (an empty tail, or a submit the ended link refused). */
    /**
     * C3 X6T (decisions AW; the 44 of 45, `C3-X5T-compare.md`): the ONE door for a cache write — the atlas's chunks, the
     * stage lane's pieces (page staging, Main's records, its programs-only re-make, the notice's programs) and a transition's records' and lists'
     * writes. Each goes as a flush of its own under the atlas's writer tag and is booked on the count's ledger at its send,
     * which voids a reading on its way; [completeFlush] counts it at its completion (restarting the settle window), and no
     * reading is launched while any is open ([CacheLedger]'s two rules). Throws as [Transport.submit] does.
     */
    private suspend fun submitCacheWrite(op: DisplayOp, label: String, atlas: Boolean): Long {
        require(op is DisplayOp.CacheWrite || op is DisplayOp.CacheWrite2) { "not a cache write: $op" }
        return transport.submit(FlushRequest(listOf(op), comp.epoch, label, writer = atlasTag)).also { cacheLedger.writeSent(it, atlas) }
    }

    private suspend fun submitLocalOnly(tail: List<ByteArray>, label: String): Long? {
        if (tail.isEmpty()) return null
        return try {
            val id = transport.submit(FlushRequest(emptyList(), comp.epoch, label, tail = tail))
            localOnlyFlushes += id
            localTailFlushes += id
            for (h in lists) h.listBind.tailSent(id, h.listBindEntriesSent())
            id
        } catch (e: Exception) {
            // the link ended between the pump's gate and the submit: the next session's start sends the whole table
            journal.note("local", "a flush of local-input messages was not submitted: ${e.message ?: e::class.simpleName}")
            null
        }
    }

    /** The lone SETTLE's moment (P7): one pump posted for it, on the loop's clock. */
    private fun scheduleLocalSettle() {
        val at = local.settleAt() ?: return
        if (at >= localSettleAt && localSettleAt > System.currentTimeMillis()) return
        localSettleAt = at
        scope.launch { delay(maxOf(1L, at - System.currentTimeMillis())); if (running) post(Msg.Pump) }
    }

    // ------------------------------------------------------------------ transport
    /** A stock scroll message or click from the ring while the rules are active (§6.7 P2, P5): read against the echo owes
     *  before it is handled. A gesture made on a screen ([TransportEvent.Input.injected]) is no stock message — no lens fired
     *  for it — so it is handled at once as today, and the rules see the row move ([LocalRules] `observe`: the slot's
     *  answer is a DISCARD naming it). */
    private fun localReads(ev: TransportEvent.Input): Boolean =
        !ev.injected && ev.source == EvenHubMsg.SRC_RING && local.active && wm.damage.core.local.LocalRules.Stock.of(ev.type) != null

    /** Whether a transport event's pump is a gesture's (its flush may take the window's last slot and is journaled as
     *  input): a typed line, or an Input — but not one the rules read, which is input only when their verdict applies or
     *  acts on it (`asInput`; decisions AL.5: a consumed echo marks nothing). */
    internal fun marksInput(ev: TransportEvent): Boolean = ev is TransportEvent.Text || (ev is TransportEvent.Input && !localReads(ev))

    private fun handleTransport(ev: TransportEvent) {
        when (ev) {
            // build C2 (§6.7 P2, P5): a stock scroll message or click is read against the echo owes BEFORE it is handled
            is TransportEvent.Input -> {
                lastInputAtMs = System.currentTimeMillis()
                if (!localReads(ev)) handleInput(ev.type, ev.source)
                else localVerdict(local.stock(wm.damage.core.local.LocalRules.Stock.of(ev.type)!!, localRow(), System.currentTimeMillis()))
            }
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
            // build C2 (stage DP2): the 115 reports in the same ordered flow as the stock messages (P2), the telemetry
            // reads (P1's refusal record, `tapsLive`), RIGHT's 114 bodies while BIND is in force (the counting)
            is TransportEvent.Applied -> {
                lastInputAtMs = System.currentTimeMillis()
                // K1-2 stage 3 (the spec §6's BF row): the list each fire's instance (tag S, `FIRMWARE.md` §6.4) belongs to, by
                // its report's field 7 — its 114 bodies are that list's period samples ([localBodyRead])
                ev.report.s?.let { s -> ev.report.list?.let { reportListByTag[s] = it } }
                // decisions DE (the second review of K1-2, finding 1): a report handled between a change of the list in front
                // and the pump that tells the rules ([syncLocalFront], past the room check) is judged against the list the
                // rules hold — so it is handed no row (`focus`, applied as today to the screen in front), never adopted onto
                // the other list's row: Main and a window list, and since DW-1b-1 the wheel and either
                localVerdict(local.report(ev.report, if (localFrontNow != null && slot0List() != localFrontNow?.list) -1 else localRow(), ev.atMs), ev.report.eventId)
            }
            is TransportEvent.LocalRead -> {
                ev.flagsInForce?.let { local.bindInForce(it and wm.damage.core.wire.DamageMsg.FLAG_BIND != 0) }
                local.telemetry(ev.boundIds, ev.refusal27, ev.batch14, ev.refusal26, ev.atMs)
                // AU.4: the counters' note when a value changes (and once after a session start), not at every read
                if (local.active) local.localNote(ev.fields).let { n -> if (n != localNoteSaid) { localNoteSaid = n; journal.note("local", n, ev.atMs) } }
                post(Msg.Pump)
            }
            is TransportEvent.LocalBody -> { local.body114(ev.slot, ev.tag, ev.outcome, ev.reason); transitionBody(ev); localBodyRead(ev) }
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
                    comp.requestKeyframe("the lease was lost")
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
                    comp.requestKeyframe("the shadow reported divergence flags")
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
                // build C1 (`FIRMWARE.md` §5.6, spec §6.4): RIGHT's END CRC in a motion notify disagrees with the
                // mirror's — the shadow is not what belief holds there; one keyframe reseeds both (it also closes
                // every instance on the glasses, decision A.8)
                if (ev.kind == "divergence" && ev.detail.startsWith("motion")) comp.requestKeyframe("a motion END CRC disagreed (${ev.detail})")
                // build C1's ruling R1.16: the feel session's probe played a test program and its PLAY is acknowledged
                // — END goes into belief as the glasses wrote it into their shadow, so when the instance ends the
                // agreement check finds END on both sides (no divergence, no keyframe)
                if (ev.kind == "motion") wm.damage.core.motion.TestPrograms.playedName(ev.detail)?.let { testProgramEndIntoBelief(it) }
                if (ev.kind == "motion") noticeNotify(ev.detail)          // build K1-4a: a refused notice PLAY
                if (ev.kind == "motion") laneNotify(ev.detail)            // stage L: a held lane's stretch drew, was closed or refused
            }
            is TransportEvent.Presented ->         // FIRMWARE.md §3 (F1.3): a panel transfer timed on the glasses; §4: its path
                journal.present(ev.seq, ev.workerUs, ev.copyUs, ev.transferUs, ev.path, ev.atMs)
        }
    }

    private fun completeFlush(ev: TransportEvent.FlushDone) {
        completionsSeen++                             // P5 item 5: a completion of any kind (content, atlas, stage)
        cacheLedger.writeDone(ev.id, ev.ok, System.currentTimeMillis())   // X6T: a booked cache write counted (or lost) here, whoever wrote it
        if (ev.id == atlasInFlight) { atlasDone(ev); return }
        if (ev.id == stageInFlight) { stageDone(ev); return }
        // build K1-4a: the flush that carried the notice's PLAY failed — the phone's steps for that box
        if (ev.id == noticePlayFlush) { noticePlayFlush = null; if (!ev.ok && !glassesSilent) noticeFallback("the flush carrying its PLAY failed (${ev.error})") }
        if (lanes.flushDone(ev.id, ev.ok, System.currentTimeMillis(), ev.wroteAtMs, ev.ackAtMs)) post(Msg.Pump)   // stage L: a lane's PLAY landed
        transitionFlushDone(ev)                       // build C3 (DP2): the records' write, the out half, the PLAY-AFTER, a frame's
        val a = inflightFlushes.remove(ev.id)
        journal.flushDone(ev.id, ev.ok, ev.ackMs, ev.bytes, ev.error, ev.tookAtMs, ev.wroteAtMs, ev.ackAtMs)
        // build C2 (§6.11): a tail-only flush that failed — an answer, a SETTLE or a re-send lost — is a divergence: keyframe
        if (localOnlyFlushes.remove(ev.id) && !ev.ok && !glassesSilent) {
            journal.note("local", "a flush of local-input messages only failed (${ev.error}) — keyframing (§6.11)")
            comp.requestKeyframe("a local-input flush failed")
            post(Msg.Pump)
        }
        for (h in lists) h.listBind.tailDone(ev.id, ev.ok)      // K1-1: a programs switch's SETs landed on both lenses (or go again)
        // a refused flush that carried the rules' messages: the whole table again (`LocalRules.tableAgain`, marked)
        if (localTailFlushes.remove(ev.id) && !ev.ok && ev.error?.contains("ImgResCmd") == true && !glassesSilent) {
            local.tableAgain("a flush carrying local-input messages was refused (${ev.error})")
            post(Msg.Pump)
        }
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
                comp.requestKeyframe("three flushes failed in a row")
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
        val unjudged = atlasUnproven.toList()   // B1: read before the start's count reset empties the books
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
        if (ct != null && a != null && reset == null) { atlasKeep(ct, a, st, unjudged); return }
        atlasReset()
        if (reset == null) return
        // the transport's gaps ride every reset note too, unless the reason already quotes them
        val facts = if (st.leaseCarry.isEmpty() || reset.contains(st.leaseCarry)) "" else " (${st.leaseCarry})"
        journal.note("atlas", "reset — $reset$facts")
    }

    /** The kept path of [atlasAtSessionStart]: the glasses hold [a]'s acked bytes — but for [unjudged], the messages acked
     *  and not yet judged when the link ended, which may have reached LEFT alone: they go again (B1). */
    private fun atlasKeep(ct: CachedText, a: GlyphAtlas, st: LinkState, unjudged: List<AtlasWrite>) {
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
        val (again, bytes) = atlasResendUnjudged(unjudged)          // B1: after atlasCheckOwed's clears
        journal.note("atlas", "kept across the rebuild (${st.leaseCarry}): ${held.size} font(s) and " +
            "${heldImages.size} icon(s) live, ${a.ackedBytes} B on the glasses, ${a.used - a.ackedBytes} B to go again" +
            (if (again > 0) "; $again message(s) acked and not judged when the link ended, $bytes B, go once more (the keyframe follows their count)" else ""))
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
            // build C1: a build with the riders allocates up to 256 KiB (the ladder's stage 2), so its atlas may be wider
            atlas = GlyphAtlas(ct.base, v2 = st.draw2, capacity = st.atlasCapacity,
                maxCache = if (st.damageFeatures and wm.damage.core.wire.DamageMsg.FEATURE_RIDERS != 0) CfwModes.CACHE2_MAX_RIDERS else CfwModes.CACHE2_MAX)
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
        // allocated must be at least the atlas's capacity plus that reserve (C1-Q: at least, not equal)
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
                    // C1-Q: smaller, not "not equal" — RIGHT may keep a bigger cache carried from an earlier session
                    // while this session lays out over the size it asked (LEFT may hold only that), and takes every write
                    wantSize != null && size.toInt() < wantSize ->
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
                    comp.requestKeyframe("DRAW2 not armed")
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
            comp.requestKeyframe("atlas: " + what.substringBefore(" —"))
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
            comp.requestKeyframe("atlas: the count ended")
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

    /** K1-2 stage 4d (decisions CW): the order of the faces one atlas message extends — face, size, bold, italic, raw:
     *  the faces alone. Why: `GlyphAtlas.specs()` is a hash map's keys and a `FontSpec`'s hash starts from its `Face`
     *  enum's identity hash, which differs between builds — the same session sent its table patches (and, with new
     *  glyphs, placed each face's records) in another order on another build, and a recorded act could not hold its
     *  bytes. The other walks whose order reaches the wire take it too: the repack's kept faces ([atlasEvictFor], ties
     *  in use) and Matrix's face ([rainFace], ties in its count). */
    private val atlasFaceOrder: Comparator<FontSpec> =
        compareBy<FontSpec>({ it.face.ordinal }, { it.sizePx }, { it.bold }, { it.italic }, { it.raw })

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
        for (spec in a.specs().sortedWith(atlasFaceOrder)) {
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
        val residents = a.specs().sortedWith(atlasFaceOrder)       // the kept faces' ties in use are packed in this order
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
        // an icon's key is its bitmap (identity hash): ties in use are packed in the order the layout being replaced held them
        val keepImages = a.imageKeys().filter { clock - ct.imageLastUse(it) <= ATLAS_RECENT_FRAMES }
            .sortedWith(compareByDescending<Any> { ct.imageUsage(it) }.thenBy { a.image(it)?.offset ?: Int.MAX_VALUE })
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
        if (specs != ct.live || images != ct.liveImages) {
            // decisions AU.2: Main's records are pictures (no atlas offset in them; its programs name only its own region)
            // and its END and `Same` run on the phone's own copies, so the change costs Main nothing but rows whose live
            // paint may now differ (a face it draws went live): every row is painted again and compared, and only a row
            // that differs is re-staged, by the paced re-stage path
            stagePagesClear("the live font set changed")
            for (h in lists) h.listBind.recheckAll()
            wheel.liveChanged()             // V1-DW1a F2: its cells are painted again, and only a part that differs is written
        }
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
        if (cacheWritesHeld()) return false               // C3 DM-T (D6): an Encode or Matrix run reads the fonts
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
            submitCacheWrite(write, "ATLAS", atlas = true)
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
        journal.flushDone(ev.id, ev.ok, ev.ackMs, ev.bytes, ev.error, ev.tookAtMs, ev.wroteAtMs, ev.ackAtMs)
        val ct = cachedText ?: return
        if (!ev.ok) {
            // §66: what landed is unknown; the next reading re-bases (booked as lost at [completeFlush], X6T)
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
        // §66: one non-empty mode-12/19 message per atlas flush — counted (and the settle window restarted) at
        // [completeFlush], before this runs (X6T: every cache write's ack in one place)
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
            comp.requestKeyframe("atlas: a re-send went unjudged")
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

    /** One mode-19 message of a record's write: at most [STAGE_CHUNK] bytes of data; [names]: it names the rest (a band
     *  switch's item word), so it goes alone and only after every piece before it was acknowledged. */
    /** [ranges]: the cache bytes it writes (B083, S1: a wrap may play from a record whose written pieces miss its range). */
    private class StagePiece(val op: DisplayOp, val bytes: Int, val names: Boolean, val ranges: List<IntRange> = emptyList())

    /**
     * A record written piece by piece (build SPLIT): one piece per STAGE flush, the next only after the last one's ack,
     * from the idle pump ([pumpStagePiece]) — so a gesture's flush waits behind one piece, never a whole ~20 KB record.
     * The envelope (`FORK.md` §3.8): (a) cleared with the registry ([stageClear]: a session start, a lapse, a reset, a
     * clear), by a failed or unsubmitted piece, by a reading that re-bases the count or calls the interval of an acked
     * piece short, and when its record leaves the registry (a dropped staging, another layout) — then abandoned, never
     * named; (b) a piece's wait is its FlushDone (ok, not ok, or the session's sweep); a piece that cannot go waits on the
     * idle pump's own gates and [mayGo], each read afresh on every pump; (c) keyed on the record object itself (the
     * registry's identity check); (d) the loop only; the image lane's writes stay in stream order, LEFT cannot report; (e)
     * one read of the link state per pump; (f) every record [stageSubmit] sends — Page staging's strips, Main's base
     * records, re-stages and re-made pans — and the ledger's two verdicts that drop records.
     */
    private class StageCursor(val rec: StagedRecord, val pieces: List<StagePiece>, val mayGo: () -> Boolean, val onBooked: (() -> Unit)?) {
        /** The next piece to send. */
        var next = 0
        /** The ledger's mark at the last acked piece's ack (-1: none acked yet). */
        var lastAckedAt = -1L
        /** [onBooked] has run (at the naming piece's submission, else at the last piece's ack). */
        var booked = false
    }
    private var stageCursor: StageCursor? = null
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
        get() = atlasInFlight != null || atlasCheckInFlight || stageInFlight != null || stageCursor != null || ledgerReadInFlight
    /** First-time atlas messages chosen to be written once more after a shortfall, so far (never reset). */
    @Volatile var atlasWritesChosenAgain = 0
        private set
    /** Extra bumps seen this session (a count anomaly to read, never a reason to drop a record). */
    private var stageForeignBumps = 0L
    private var stageFullSaid = false
    /** C3 DW-2: the strip that last found the reserve holding only pinned records ([pumpStage]) — the wheel's hold for the
     *  first strip ends on it ([pageStagingFirst]). Cleared with the registry ([stageClear], [stagePagesClear]). */
    private var stageWaitsFor: StageKey? = null
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
        // Main's staged records (build C2, stage DMS) and the wheel's block (C3 DW-1a) are never drawn by the compositor:
        // not counted as drawable
        stagedProvenCount = stage.records.values.count { it.state == StagedRecord.State.PROVEN && it.key !is RegionStageKey }
        stagedResidentCount = stage.records.size
        stagedProvenLines = stage.records.values.filter { it.state == StagedRecord.State.PROVEN && it.key !is RegionStageKey }
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

    /** Decisions AU.2: Page staging's records go, Main's own staging stays — for a reason that concerns Page staging only.
     *  C3 DW-1a: the wheel's block, the region's other tenant, stays too. */
    private fun stagePagesClear(why: String) {
        val gone = stage.records.values.filter { it.key !is RegionStageKey }
        for (r in gone) stage.remove(r)
        stageWaitsFor = null
        // a write of one in flight completes as any other: its ack is counted, its record is no longer in the registry
        stageClears++                                  // harnesses count a boundary by it
        comp.placements = emptyList()
        stageRecount()
        if (gone.isNotEmpty()) journal.note("stage", "dropped ${gone.size} record(s) — $why")
    }

    /** Every record and placement goes: nothing draws from the reserve until a strip is staged and
     *  proven again. [why] null = a session boundary (the start's own notes say so). */
    private fun stageClear(why: String?) {
        val had = stage.records.size
        stage.clear()
        stageWaitsFor = null
        stageInFlight = null; stageInFlightRec = null
        stageCursor = null                 // build SPLIT: a part-written record goes with the registry, never named
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

    /** What Page staging stages for [v]: its layout's key, the strips' region, line height and step, the lines on screen,
     *  and the strips the next notches expose — or null when [v] stages nothing (no content key; a region wider, or a step
     *  taller, than a record may be). [pumpStage] writes from it and the wheel's hold reads its first strip
     *  ([pageStagingFirst], C3 DW-2): one function, so the hold never waits for a strip the lane would not write. */
    private class StagePlan(val key: Any, val region: Rect, val lineH: Int, val top: Int, val visible: Int, val wanted: List<StageKey>)
    private fun stagePlan(v: WindowView.DocView): StagePlan? {
        val key = v.contentKey() ?: return null
        val region = docRegion(v)
        val lineH = v.lineHeight
        if (lineH <= 0 || region.w <= 0 || region.w > CfwModes.MAX_IMAGE2_W) return null
        val step = v.stepLines().coerceAtLeast(1)
        if (step * lineH > CfwModes.MAX_IMAGE2_H) return null     // a record is at most 2048 rows tall
        val visible = kit.visibleLines(layout, v)
        val top = v.model.topLine
        val count = v.lineCount()
        // the strips the next notches expose: ahead first (reading forward), one behind, then further ahead
        val wanted = listOf(top + visible, top + visible + step, top - step, top + visible + 2 * step)
            .filter { it >= 0 && it < count }
            .map { StageKey(key, it, step, lineH, region.w) }
        return StagePlan(key, region, lineH, top, visible, wanted)
    }

    /**
     * C3 DW-2 (decisions DM 3, DP 9; amending CK A3's order of lanes as DF finding 2 did for a window's list): a document
     * Page staging serves in front whose first wanted strip ([stagePlan]) is not written yet — the wheel's block waits
     * behind it, its place reserved. It ends when that strip's record is in the registry, or at any lasting refusal or
     * failure of Page staging: the row off, staging off for the session, DRAW2, the lease or the cache check not in force,
     * no cached text or its atlas failed or absent, no document served, no content key, a region or a step no record
     * takes, no wanted line, the strip refused for size or waiting on a reserve that holds only pinned records. Not while
     * the switcher is open ([windowListFirst]'s rule: Page staging writes nothing then at stage 5, and the wheel's lane
     * writes what the offer needs). Every transient return of [pumpStage] is also a gate of [pumpWheel] ahead of the
     * placement (the C3 DW-2b report lists each return and what ends the hold for it).
     */
    private fun pageStagingFirst(st: LinkState): Boolean {
        if (switcher.open || settings.pageStaging != "on" || stageFailed || !st.draw2 || !st.leaseHeld || !transport.cacheCheckSupported) return false
        if (cachedText == null || settings.cachedText != "on" || atlasFailed || atlas == null) return false
        val k = stagedDoc()?.let { stagePlan(it) }?.wanted?.firstOrNull() ?: return false
        return !stage.records.containsKey(k) && k !in stageRefused && k != stageWaitsFor
    }

    /** The idle pump's staging step, after the atlas's: one reading launched, or one strip rendered
     *  and written. True when it did something. */
    private suspend fun pumpStage(st: LinkState): Boolean {
        if (settings.pageStaging != "on" || stageFailed || glassesSilent || !st.draw2 || !st.leaseHeld) return false
        if (cacheWritesHeld()) return false     // C3 DM-T: D6's hold (an Encode run's lists lie in the stage reserve)
        // C3 DW-1b-1 (the amendment's §4.5): at stage 5 no write step while the switcher is open — the document behind the
        // wheel is not in front, and a strip would sit on the link ahead of the wheel's answers and void the count reading an
        // unbound open's bind waits for (P9); a strip already in pieces completes ([pumpStagePiece]). Below stage 5, today's
        if (switcher.open && st.effects) return false
        if (!transport.cacheCheckSupported) return false     // no proof possible, so nothing is ever drawn from a record
        val ct = cachedText ?: return false
        if (settings.cachedText != "on" || atlasFailed || atlas == null) return false
        if (atlasInFlight != null || atlasQueue.isNotEmpty() || atlasLapsedOwed || atlasCheckInFlight) return false
        if (stageInFlight != null || stageCursor != null || ledgerReadInFlight) return false
        if (st.inFlight != 0 || comp.hasPending || comp.needsKeyframe) return false
        val v = stagedDoc() ?: return false
        noticeYield("page staging in front")          // build K1-4a (a): before any strip goes where the notice's programs are
        lanes.yieldSlice("page staging in front")     // stage L: and the held lanes' areas (his answer 6: Reader's in front)
        val plan = stagePlan(v) ?: return false
        val region = plan.region
        val gone = stage.retain(plan.key, plan.lineH, region.w)
        if (gone.isNotEmpty()) { stageRecount(); journal.note("stage", "dropped ${gone.size} record(s) of another layout") }
        val ring = stage.ring ?: StageRing(st.cacheSize - CfwModes.stageReserve(st.cacheSize, st.bind), st.cacheSize).also { stage.ring = it }
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
        val wanted = plan.wanted
        val pinned: (StagedRecord) -> Boolean = { r -> r.key in wanted || (r.firstLine < plan.top + plan.visible && r.firstLine + r.lines > plan.top) }
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
                stageWaitsFor = k
                if (!stageFullSaid) { stageFullSaid = true; journal.note("stage", "the reserve holds only pinned records (${ring.bytesHeld} B) — $k waits") }
                continue
            }
            stageFullSaid = false
            stageWaitsFor = null                  // C3 DW-F item 2: placed, so nothing waits on this record any more
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

    /** [rec]'s write, piece by piece (build SPLIT: one piece per flush, the idle pump between them, input first);
     *  [parts] (build C2, stage DM2: a row of Main's re-staged) are its writes at their own offsets — by default the
     *  record whole at its place. [namesLast]: the last part names the rest (a band switch's item word) and goes alone,
     *  after every other piece was acknowledged. [mayGo]: whether a piece after the first may go now (the record's
     *  owner's gate, read afresh on every pump). [onBooked]: what makes the record nameable — run when the naming piece
     *  is submitted, else at the last piece's ack. [chunk]: a message's data at most (Music stage SF2: the held lanes'
     *  area as one message where it may go deflated, [stageOwnRecord]). The first piece goes now. */
    private suspend fun stageSubmit(rec: StagedRecord, st: LinkState, parts: List<Pair<Int, ByteArray>> = listOf(rec.off to rec.encoded),
                                    namesLast: Boolean = false, mayGo: () -> Boolean = { true }, chunk: Int = STAGE_CHUNK,
                                    onBooked: (() -> Unit)? = null) {
        stageCursor = StageCursor(rec, stagePieces(parts, namesLast, st.cacheSize, chunk), mayGo, onBooked)
        stageSendPiece(st)
    }

    /** [parts] as pieces of one mode-19 message each: consecutive data parts packed at their own offsets, split so a
     *  message carries at most [chunk] bytes of data (its entries' 4-byte headers beside them; parts are 4-byte
     *  aligned, so a split keeps every offset aligned); with [namesLast] the last part alone, last. */
    private fun stagePieces(parts: List<Pair<Int, ByteArray>>, namesLast: Boolean, cacheSize: Int, chunk: Int = STAGE_CHUNK): List<StagePiece> {
        val out = ArrayList<StagePiece>()
        var entries = ArrayList<CfwModes.CacheWrite2>()
        var room = chunk + 4                                        // header + data of the message's entries
        fun close(names: Boolean) {
            if (entries.isEmpty()) return
            out += StagePiece(DisplayOp.CacheWrite2(CfwModes.cacheUpdate2(entries, cacheSize)), entries.sumOf { it.data.size }, names,
                entries.map { it.off4 * 4 until it.off4 * 4 + it.data.size })
            entries = ArrayList(); room = chunk + 4
        }
        for ((i, part) in parts.withIndex()) {
            val (off, bytes) = part
            val naming = namesLast && i == parts.size - 1
            if (naming) close(false)
            var pos = 0
            while (pos < bytes.size) {
                if (room < 4 + 4) close(false)
                val len = minOf(room - 4, bytes.size - pos)
                entries += CfwModes.CacheWrite2((off + pos) / 4, bytes.copyOfRange(pos, pos + len))
                room -= 4 + len
                pos += len
            }
            if (naming) close(true)
        }
        close(false)
        return out
    }

    /** The cursor's next piece as one STAGE flush; at the naming piece the record is booked. */
    private suspend fun stageSendPiece(st: LinkState) {
        val c = stageCursor ?: return
        val rec = c.rec
        val p = c.pieces[c.next]
        val id = try {
            submitCacheWrite(p.op, "STAGE", atlas = false)
        } catch (e: Exception) {
            Log.e("shell", "staged record submit failed", e)
            stageCursor = null
            stage.remove(rec)
            stageRecount()
            listRestageEndedUnbooked(c, "could not be submitted")
            if (++stageWriteFailures >= STAGE_FAILURES_MAX) stageOff("$stageWriteFailures writes in a row could not be submitted, the last: ${e.message}")
            else journal.note("stage", "${rec.key} could not be submitted (${e.message}) at piece ${c.next + 1} of ${c.pieces.size} — it goes again later")
            return
        }
        rec.state = StagedRecord.State.SENT
        stageInFlight = id; stageInFlightRec = rec
        journal.flushSubmitted(id, comp.epoch, listOf(p.op), "STAGE", st.transportName,
            Journal.Timing(win = current?.id ?: "", mode = mode.name))     // package G (E4): on every submit record
        if (c.next == 0) journal.note("stage", "${rec.key} (${rec.encoded.size} B at ${rec.off}) submitted as flush $id — piece 1 of ${c.pieces.size} (${p.bytes} B)")
        else journal.note("stage", "${rec.key}: piece ${c.next + 1} of ${c.pieces.size} (${p.bytes} B${if (p.names) ", naming the rest" else ""}) as flush $id")
        if (p.names && !c.booked) { c.booked = true; c.onBooked?.invoke() }
    }

    /** The idle pump's step for a part-written record (build SPLIT): its next piece, once the lane is idle — nothing in
     *  flight, nothing pending (a gesture's flush goes first), no reading on its way — and its owner lets it go. True when
     *  it did something. A record that left the registry meanwhile is abandoned: nothing names it. */
    private suspend fun pumpStagePiece(st: LinkState): Boolean {
        val c = stageCursor ?: return false
        if (!stageOwns(c.rec)) {
            stageCursor = null
            journal.note("stage", "${c.rec.key} left the registry after ${c.next} of ${c.pieces.size} piece(s) — its write ends there, nothing names it")
            listRestageEndedUnbooked(c, "left the registry")
            return false
        }
        if (stageFailed || glassesSilent || !st.draw2 || !st.leaseHeld) return false
        if (cacheWritesHeld()) return false     // C3 DM-T: D6's hold (an Encode run's lists lie in the stage reserve)
        if (atlasInFlight != null || atlasCheckInFlight || stageInFlight != null || ledgerReadInFlight) return false
        if (st.inFlight != 0 || comp.hasPending || comp.needsKeyframe) return false
        if (ledgerBaseOwed(st)) return tryLedgerRead(st)
        if (atlasResendMissing > 0L || !c.mayGo()) return false
        stageSendPiece(st)
        return true
    }

    /** Build SPLIT: a part-written record the count can no longer vouch for (its acked pieces fall in what a reading
     *  re-based or called short) goes — abandoned, never named; its owner writes it again. */
    private fun stagePartialDropped(why: String) {
        val c = stageCursor ?: return
        stageCursor = null
        if (stage.records[c.rec.key] === c.rec) stage.remove(c.rec)
        journal.note("stage", "${c.rec.key} dropped after ${c.next} of ${c.pieces.size} piece(s) — $why; it goes again")
        listRestageEndedUnbooked(c, "was dropped by the count")
    }

    /** The focused read of B082 (F1): a Main re-stage that ends before it is booked — a piece that failed or could not be
     *  submitted, a partial the count dropped, a record that left the registry — may have left bytes on the glasses that
     *  the phone's copy does not hold (it takes the re-stage only at the booking), so a retry that finds the row back at
     *  its old picture would write nothing over them. The staging is dropped and the next one writes whole. */
    private fun listRestageEndedUnbooked(c: StageCursor?, why: String) {
        val k = c?.rec?.key as? ListStageKey ?: return
        // K1-2 stage 3: the holder whose staging the key names (Main's or the window list's)
        val b = lists.firstOrNull { it.listBind.staging === k.content }
        if (c.booked || k.firstLine < ListBinding.PATCH_KEY || b == null) return
        b.listBindDrop("row ${k.firstLine - ListBinding.PATCH_KEY}'s re-stage $why before it was booked (its record may hold bytes the phone's copy does not)")
    }

    /** The stage lane's record [r] is still owned: Page staging's or Main's (the registry), or one of the notice's programs
     *  (build K1-4a: written through this lane, kept in their own books). */
    private fun stageOwns(r: StagedRecord): Boolean = stage.records[r.key] === r || ownRecs().any { it === r }   // stage L: and the lanes' areas

    private fun stageDone(ev: TransportEvent.FlushDone) {
        val rec = stageInFlightRec
        stageInFlight = null; stageInFlightRec = null
        journal.flushDone(ev.id, ev.ok, ev.ackMs, ev.bytes, ev.error, ev.tookAtMs, ev.wroteAtMs, ev.ackAtMs)
        val c = stageCursor?.takeIf { it.rec === rec }
        if (!ev.ok) {
            // booked as lost at [completeFlush] (X6T)
            // a failed piece fails its record: which earlier bytes survived is not known (a refusal for a lapse, a lost
            // ack), so the record goes again whole — its owner's rule, as for a record written in one flush
            if (c != null) stageCursor = null
            if (rec != null) {
                stage.remove(rec)
                stageRecount()
            }
            listRestageEndedUnbooked(c, "failed (${ev.error})")
            if (++stageWriteFailures >= STAGE_FAILURES_MAX) stageOff("$stageWriteFailures writes in a row failed, the last: ${ev.error}")
            else journal.note("stage", "${rec?.key ?: "a record"}'s write ${ev.id}${c?.let { " (piece ${it.next + 1} of ${it.pieces.size})" } ?: ""} failed (${ev.error}) — it goes again later")
            post(Msg.Pump)
            return
        }
        stageWriteFailures = 0
        // counted, and the settle window restarted, at [completeFlush] (X6T)
        var more = false
        if (c != null) {
            c.next++
            c.lastAckedAt = cacheLedger.acked
            if (!stageOwns(c.rec)) { stageCursor = null; listRestageEndedUnbooked(c, "left the registry") }   // abandoned, never named
            else if (c.next < c.pieces.size) more = true                         // the next piece on the idle pump, input first
            else stageCursor = null
        }
        if (!more && rec != null && rec.state == StagedRecord.State.SENT && stageOwns(rec)) {
            rec.state = StagedRecord.State.ACKED
            rec.ackedAt = cacheLedger.acked
            if (c != null && !c.booked) { c.booked = true; c.onBooked?.invoke() }
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
        // X6T (decisions AW): and by the count's own books — every cache write sent has completed, whoever sent it (the
        // transport's in-flight count falls at the ack, before the shell has counted it)
        if (cacheLedger.writesOpen > 0) return false
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
        // B083 (the focused read of 0.82, S4): so is a record in pieces — a reading between two of them judges none of it
        // (it stays SENT to its last ack) and cost a RIGHT round trip per piece, lengthening the re-stage
        if (based && stageCursor?.let { it.next in 1 until it.pieces.size } == true) return false
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
        cacheLedger.readingDropped()
        cacheCountUnjudged = 0L
    }

    /** One reading of the cache generation (op 1, RIGHT) — off the loop, applied on it against the
     *  epoch. The counts it speaks for are captured here, with both lanes still. */
    private fun launchLedgerRead() {
        ledgerReadInFlight = true
        val epoch = ledgerEpoch
        val sendAcked = cacheLedger.acked
        val sendAtlasAcked = cacheLedger.atlasAcked
        val settled = cacheLedger.settled(System.currentTimeMillis())
        cacheLedger.readingSent()                  // X6T: a cache write sent from here to the answer voids it
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
                    cacheLedger.readingDropped()       // X6T: nothing of an unanswered reading is judged
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
                        if (reSendAcked) comp.requestKeyframe("atlas: the count went off")
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
                // fix A2: the notice's programs too — written again, never proven by a count that cannot see them
                for (r in ownRecs()) if (r.state == StagedRecord.State.ACKED) r.state = StagedRecord.State.WANTED   // stage L: the lanes' areas too
                if (unproven.isNotEmpty()) journal.note("stage", "$from re-based the generation count at ${v.gen} — ${unproven.size} acked record(s) go again")
                // build SPLIT: a part-written record's acked pieces cannot be counted either
                if ((stageCursor?.lastAckedAt ?: -1L) >= 0L) stagePartialDropped("$from re-based the count under its acked pieces")
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
                    // B1 (the lead's ruling on FR-083): after a lost write a first-time message among them may be one LEFT
                    // acked and RIGHT never took — nothing would ever call it short — so it goes again as a shortfall's do
                    val (again, bytes) = if (v.first) 0 to 0 else atlasResendUnjudged(dropped)
                    journal.note("atlas", "count re-based — ${dropped.size} atlas message(s) acked before it are not judged (" +
                        (if (v.first) "the session's first reading" else "a write was lost before the reading") + ", generation ${v.gen})" +
                        (if (again > 0) "; $again first-time message(s), $bytes B, go once more (the keyframe follows their count)" else "") +
                        (if (resent) "; a re-send among them: keyframing" else ""))
                    if (resent) comp.requestKeyframe("atlas: the count was re-based")
                }
            }
            is CacheLedger.Verdict.Proven -> {
                var n = 0
                for (r in stage.records.values + ownRecs()) if (r.state == StagedRecord.State.ACKED && r.ackedAt <= v.upTo) { r.state = StagedRecord.State.PROVEN; n++ }
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
                    comp.requestKeyframe("atlas: a re-send was counted")
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
                // fix A2: a notice program of the short interval goes again
                for (r in ownRecs()) if (r.state == StagedRecord.State.ACKED && r.ackedAt > v.fromAcked && r.ackedAt <= v.toAcked)
                    r.state = StagedRecord.State.WANTED          // stage L: the lanes' areas too
                // build SPLIT: a part-written record with a piece acked in the short interval may miss that piece
                if ((stageCursor?.lastAckedAt ?: -1L) > v.fromAcked) stagePartialDropped("$from called the interval of an acked piece short")
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
            // X6T (decisions AW): a cache write went out while this reading was on its way — its generation may or may not
            // hold that write, so nothing of it is used (no base moves, nothing proven, nothing short); the next pump asks
            // again once every write sent has completed ([tryLedgerRead])
            CacheLedger.Verdict.Void ->
                journal.note(if (staged) "stage" else "atlas", (if (staged) "" else "count void — ") +
                    "$from was voided: a cache write went out while it was on its way — asked again")
            is CacheLedger.Verdict.Foreign -> {
                // more bumps than this shell's acked writes: the count is re-based and the acked records
                // stand — an extra bump does not make them bad, and another writer's bytes are the
                // transport's writer tag to catch (§66.1: seen once on glass, cause unread)
                stageForeignBumps += v.extra
                var n = 0
                for (r in stage.records.values + ownRecs()) if (r.state == StagedRecord.State.ACKED && r.ackedAt <= v.toAcked) { r.state = StagedRecord.State.PROVEN; n++ }
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
                        comp.requestKeyframe("atlas: a re-send was counted")
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

    /**
     * B1 (the lead's ruling on FR-083; older than X6T): [dropped] — atlas messages acked and never judged — leave the books
     * at a re-base after a lost write or at a carried rebuild, and one of them may be a message LEFT acked and RIGHT never
     * took: no later reading would call it short, and RIGHT would draw its glyphs from bytes it does not hold. The
     * first-time ones are queued once more through the shortfall's re-send ([atlasShortfall]: a plain copy of the layout
     * that stands, cut into messages of their own, never queued a third time); their count's verdict keyframes as a
     * counted re-send does ([ledgerVerdict], Proven). A message that was itself a re-send is not queued again (the same
     * bound). Returns the messages queued and their bytes.
     */
    private fun atlasResendUnjudged(dropped: List<AtlasWrite>): Pair<Int, Int> {
        val a = atlas ?: return 0 to 0
        val first = dropped.mapNotNull { it.done }.filter { !it.resend }
        if (first.isEmpty()) return 0 to 0
        val bytes = a.resend(first.flatMap { it.writes })
        atlasWritesChosenAgain += first.size
        atlasResendMissing = maxOf(atlasResendMissing, first.size.toLong())      // none known landed: as many as went again
        atlasWantVersion = -1          // the grow's cue, as after a shortfall
        return first.size to bytes
    }

    /** Build C1's ruling R1.16: END of the test program [name] — played by the `motion=NAME` dev probe, its PLAY
     *  acknowledged — painted into belief on both lenses. END's pixels do not depend on where in the cache the
     *  program's records sit, so the program is built for any contract-2 cache. */
    private fun testProgramEndIntoBelief(name: String) {
        val tp = wm.damage.core.motion.TestPrograms.build(name, CfwModes.CACHE2_MAX) ?: return
        comp.paintBelief { left, belief -> tp.paintEnd(left, belief) }
        journal.note("motion", "test program $name: END put into belief on both lenses (its PLAY is acknowledged)")
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
        cacheLedger.writeAcked(1, atlas = true, nowMs = System.currentTimeMillis())   // as a real ack would: the settle window runs from here
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
     *  session REBUILD (`Transport.restartSession`: the session ends — the phone's
     *  radio link is kept since stage BW — the keeper stops this shell and starts
     *  it again from the prelude up, G2CC's relayout path), never a keyframe. The new start reads the
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
        transitionEnd("the shell went to sleep ($why)")             // build C3 (DP2): the session's end or the wake re-grounds
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
     *  push came, §37.0's 22:04:28 — the session is rebuilt over a fresh
     *  connect, at most once per pacing (a ring spun twice must not ask twice). 0.64: the transport's
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
        // stage BWF (`BL-decisions.md` Part G item 1): this rebuild ends the link — kept, frames refused after an
        // answered start would be kept again at every pacing, and only a hand action ended that
        requestRebuild("the glasses say they are awake and refused the last frames ($why)", endLink = true)
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
     *   hold = on | off    the feel session (2026-11-18): no frame is submitted until hold=off or an input event.
     *
     * True when the name is the shell's: the host then does not hand it to the transport. Any thread.
     * The probe is run ON the loop, and only in the session it was taken in ([sessionSerial]): one taken
     * while a start was still on its way, or queued behind a stop, says so in a note and does nothing.
     */
    fun devProbe(name: String, value: String): Boolean {
        if (name == "taps") {
            // build C2 (decisions N.1): `Taps` is a Settings row; the probe goes through the setting's own door
            Log.i("shell", "probe: $name=$value")
            journal.note("probe", "$name=$value")
            val t = wm.damage.core.local.Taps.of(value)
            if (t == null) { journal.note("local", "taps=$value: expected phone | report | glasses — nothing changed"); return true }
            post(Msg.Run { applySettings(settings.copy(taps = t)) })
            return true
        }
        if (name == "hold") {
            Log.i("shell", "probe: $name=$value")
            journal.note("probe", "$name=$value")
            if (!running) {
                journal.note("hold", "hold=$value not taken — no session")
                return true
            }
            val serial = sessionSerial                          // captured once, on the caller's thread
            post(Msg.Run(dropped = { journal.note("hold", "hold=$value not taken — the session ended") }) {
                if (serial != sessionSerial) journal.note("hold", "hold=$value not taken — the session changed")
                else holdProbe(value)
            })
            return true
        }
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

    /** The `hold=on|off` dev probe (the feel session, on Adam's word, 2026-11-18: "keep the blocks rather than going
     *  back to text each time"): while on, [pump] submits no frame, so what a hand-played test program
     *  (`probe motion=NAME`) left on the lenses stays there between plays. The lease, its renewals and the page traffic
     *  go on — they are the transport's own loops — and nothing is sent for the hold itself. It ends at `hold=off`, at
     *  ANY input event (which is then handled as usual) and with the session; the shell then repaints its own picture
     *  over the test's. Loop-confined. */
    private var testHold = false

    private fun holdProbe(value: String) {
        when (value) {
            // decisions AF.3: refused while BIND is in force — a hold would keep the rules' answers and SETTLEs from going
            "on" -> if (transport.state.value.bind) journal.note("hold", "hold=on not taken — BIND is in force (stage 4)")
            else if (testHold) journal.note("hold", "hold=on — already holding") else {
                testHold = true
                journal.note("hold", "holding — no frame is submitted until hold=off, an input event or the session's end")
            }
            "off" -> if (testHold) releaseHold("probe hold=off") else journal.note("hold", "hold=off — not holding")
            else -> journal.note("hold", "hold=$value not taken — on | off")
        }
    }

    private fun releaseHold(why: String) {
        testHold = false
        journal.note("hold", "released — $why; the shell repaints its own picture")
        pumpAfter(0)
    }

    /** Decisions AZ.4: the wrap's PLAY tags (one per PLAY, cycling through 0x1000). */
    private var wrapTags = 0

    /** G4 item 1: assembles made so far (the pump's), the count at the last edge report's verdict, and the assembles
     *  between that verdict and its wrap's PLAY. */
    private var assembleCalls = 0
    private var edgeAssembleMark = 0
    private var lastWrapAssemblesBefore = -1
    private var edgeAtMs = 0L
    /** Tests only (his look at 0.81): run on the loop right after the wrap's flush is submitted — a loaded loop. */
    @Volatile internal var afterWrapSubmitForTest: (() -> Unit)? = null
    /** Tests only (the focused read's L1): the wrap's END paint throws after the LEFT lens's. */
    @Volatile internal var wrapPaintFailsForTest = false

    /**
     * G4 item 1 (his glasses, 0.80: the wrap's slide began a median 555 ms after the edge's report). Its PLAY rode the
     * batch of the pump that drew the wrap's frame, after that frame's assemble — Main's whole list repainted behind the
     * held cells, 100–170 ms of the phone's loop — and was sent after it. Now the PLAY and the rules' answer (the DISCARD
     * naming the wrapped row, the riding LIST, whatever table messages are owed) go first, in a flush of their own, before
     * the frame is assembled: the model takes END and holds the areas as the batch path did. Not when a COMMIT is planned
     * (the batch path applies its END first) or a keyframe is owed (it closes every instance; the row is drawn as today).
     */
    private suspend fun mainWrapFirst(st: LinkState, nowMs: Long): Boolean {
        if (!(local.active && st.bind && st.leaseHeld) || comp.needsKeyframe) return false
        // B082 (the focused read's W2): only once the rules know what is in front — this runs before the pump's
        // `syncLocalFront`, and asking them earlier, in the pump where a cover appears, made a false DISCARD and a
        // `snapback cause=moved` record (their `observe` saw the covered row −1 against the list still in front)
        if (localFront()?.second !== localFrontNow) return false
        if (local.plan(localRow()).isNotEmpty()) return false
        val wrap = mainWrapPlan(nowMs) ?: return false
        // B082 (the focused read's L1): a throw from the END paint is said, keyframes (a half-applied END on the lens
        // painted first is re-grounded by it) and leaves the answer to the batch path, which denies the wrap under the
        // owed keyframe and answers with the DISCARD — so it cannot repeat at every pump
        try { comp.holdEnds(listOf(wrap.end), nowMs) }
        catch (e: Exception) {
            Log.e("shell", "the wrap's END could not be painted", e)
            journal.note("assemble-error", "the wrap's END: $e")
            raiseFault("assemble", "the wrap's END could not be painted: ${e.message ?: e::class.simpleName} — keyframing", urgent = true, source = "compositor")
            comp.requestKeyframe("the wrap's END could not be painted")
            return false
        }
        val tail = listOf(wrap.play) + local.tail(localRow(), nowMs, emptySet(), frame = false, wrapped = true)
        lastWrapAssemblesBefore = assembleCalls - edgeAssembleMark
        val id = submitLocalOnly(tail, "WRAP")
        // the focused read's N8: the note names the flush, so its `done` row prices the wrap's start against the report
        journal.note("local", "the wrap's PLAY went ahead of its frame as flush ${id ?: "(not submitted)"}: ${tail.size} message(s), " +
            "${tail.sumOf { it.size }} B, ${nowMs - edgeAtMs} ms after the edge's report reached the shell")
        wrapSent(wrap)
        afterWrapSubmitForTest?.invoke()
        return true
    }

    /** His look at 0.81: the last ring event or glasses' report, for the period's quiet rule. */
    private var lastInputAtMs = 0L
    private var periodRecheckGen = 0
    private fun periodRecheck(inMs: Long) {
        val gen = ++periodRecheckGen
        scope.launch { delay(inMs); if (running) post(Msg.Run(dropped = {}) { if (gen == periodRecheckGen) post(Msg.Pump) }) }
    }

    private fun localBodyRead(ev: TransportEvent.LocalBody) {
        // B083 (2c; his edge bursts): the wrap drew its last step — what Main holds behind it (the row a burst of edges
        // reached) goes now, not at the end of the wrap's start allowance, unless a later motion holds those cells too.
        // Pacing on a report; a lost body leaves the allowance's end, as before
        if (ev.slot == ListStaging.WRAP_SLOT && ev.tag == wrapTagOut && ev.outcome == wm.damage.core.wire.DamageMsg.OUTCOME_DREW &&
            comp.deferring && comp.deferUntilMs <= wrapHeldUntil) {
            val early = comp.deferUntilMs - System.currentTimeMillis()
            if (comp.releaseDeferred(Long.MAX_VALUE)) {
                journal.note("local", "the wrap's last step came: the cells held behind it go now, $early ms before its allowance ended")
                post(Msg.Pump)
            }
        }
        // K1-2 stage 3 (the spec §6's BF row, pin 14): a body is the list's whose report named its tag (field 7) — a late
        // body of Main's fire after a window's list came in front moves Main's period, never the window list's; the wrap's
        // (slot 1) is Main's; a slot-0 body whose report did not arrive is Main's only while no window's list has been staged
        // this process (below stage 5, as before) — else whose it was is not known, and it is used for nothing (decisions CY,
        // the first review of 3a's finding C: credited to Main, a window list's step raised Main's period)
        val known = reportListByTag[ev.tag]?.takeIf { ev.slot != ListStaging.WRAP_SLOT }
        val b = if (known == null) mainList.takeIf { ev.slot == ListStaging.WRAP_SLOT || windowList.listBind.stagings == 0 } ?: return
            else lists.firstOrNull { it.listNo == known } ?: return
        val ms = b.listBind.staging ?: return
        val list = b.listBind.list ?: return
        if (ev.slot != list.slot && ev.slot != ListStaging.WRAP_SLOT) return
        val steps = (ev.drawn ?: 0L) + (ev.folded ?: 0L)
        val complete = ev.outcome == wm.damage.core.wire.DamageMsg.OUTCOME_DREW || steps >= ms.t
        // G4 item 2: a local instance's own body (no END written) closed by RIGHT — counted, never a keyframe. Every step
        // drawn: at END. Short of them, the partial step on the panel goes at the next present, which the close makes
        // whole (the stale mark): the answer's — the COMMIT writes END and presents, the DISCARD presents — or, with the
        // report lost, the frame of its stock message applied as today; a COMMIT that never lands is a failed flush whose
        // rollback repaints, a session's end a keyframe (B082, the focused read's W3: the repaint here repaired nothing)
        if (ev.outcome == wm.damage.core.wire.DamageMsg.OUTCOME_CLOSED && ev.endCrc == 0L && ev.slot == list.slot) {
            if (steps >= ms.t) local.counts.merge("localClosedAtEnd", 1, Int::plus)
            else {
                local.counts.merge("localClosedShort", 1, Int::plus)
                journal.note("local", "slot ${ev.slot} tag 0x%04x closed on the glasses after $steps of ${ms.t} steps".format(ev.tag))
            }
        }
        val us = ev.stepUs ?: return
        if (!complete || steps != ms.t.toLong() || us <= 0L) return
        b.stepWindow.addLast(us)
        while (b.stepWindow.size > PERIOD_WINDOW) b.stepWindow.removeFirst()
        b.periodSamplesSinceChange++
        if (b.listPeriodWanted() != null) post(Msg.Pump)
    }

    private fun ListBind.listPeriodWanted(): Int? {
        val ms = listBind.staging ?: return null
        val max = stepWindow.maxOrNull() ?: return null
        val need = kotlin.math.ceil(max / 1000.0 * PERIOD_MARGIN_MEASURED * 1.024).toInt()
            .coerceIn(CfwModes.MOTION_PERIODS.first, CfwModes.MOTION_PERIODS.last)
        val p = ms.periodInForce
        return when {
            need > p -> need
            // ruled (W1; his look at 0.81): raised at once whenever the measured step × 1.10 needs more than the period in
            // force; lowered only when idle — [PERIOD_QUIET_MS] with no input, nothing that may still draw, no burst
            // ([listPeriodApply] holds those) — by [PERIOD_LOWER_MIN] ticks or more, never below the staging's own period
            // (the fit with Auto, length over frames with a fixed count). A raise of 1–3 ticks above that floor stays until
            // the next staging: each change is a SET flush landing on the two lenses at different moments
            stepWindow.size >= PERIOD_WINDOW && maxOf(need, ms.period).let { t -> p - t >= PERIOD_LOWER_MIN } -> maxOf(need, ms.period)
            else -> null
        }
    }

    private fun ListBind.listPeriodApply() {
        if (local.burstOpen) return
        val ms = listBind.staging ?: return
        val want = listPeriodWanted() ?: return
        val was = ms.periodInForce
        if (want < was) {
            // a lowering waits for quiet: no motion that may still draw (the burst is held above; the mirror's open slots
            // keep an instance at its end too, so they are not read), and PERIOD_QUIET_MS since the last input (a re-check)
            val quietAt = lastInputAtMs + PERIOD_QUIET_MS
            val now = System.currentTimeMillis()
            if (listMotionMayDraw() || now < quietAt) { periodRecheck(maxOf(quietAt, now + 250) - now); return }
        }
        ms.periodInForce = want
        periodSamplesSinceChange = 0
        listBind.relisted()
        note("period $was → $want: steps measured up to ${"%.1f".format((stepWindow.maxOrNull() ?: 0L) / 1000.0)} ms " +
            "over the last ${stepWindow.size} motion(s) (×${PERIOD_MARGIN_MEASURED}); the entries are SET again, ${ms.t} frames × ${"%.0f".format(want * 1000.0 / 1024)} ms")
    }

    /** Decisions AZ.4: the wrap's PLAY bytes and its END plan for the assemble. */
    private class MainWrap(val play: ByteArray, val end: Compositor.EndPlan, val tag: Int)
    /** B083 (2c): the last wrap PLAY sent — its tag and the end of the wait its held cells were given ([wrapSent]). */
    private var wrapTagOut = -1
    private var wrapHeldUntil = 0L
    private fun wrapSent(w: MainWrap) { wrapTagOut = w.tag; wrapHeldUntil = w.end.untilMs }

    /**
     * Decisions AZ.4: the pan across Main's wrap, when the rules found an edge at an end of the bound list with the row now
     * the other end ([LocalRules.wrapPlan]) and the staging wraps. The same pan as a notch's (the bound entry's program,
     * period and T: the `Animations` rows), its parameters for the row just past the end ([ListStaging.wrapParams]), in
     * [ListStaging.WRAP_SLOT]: END is Main's paint at the wrapped row; the model takes it, and holds what Main repaints
     * inside the areas until the motion has ended — its start a flush after this one, so one allowance
     * ([WRAP_START_MS]) past the steps. The mirror holds the instance, so a later flush that meets it CLOSEs it first.
     * None on a ring staging (stage 5 where Main's paint wraps: no wrap record, [ListStaging.wrapPan] null), and no
     * other staging at stage 5 holds a wrap record, so no wrap PLAY meets a transition (C3 X2M0, decisions AZ (2)).
     */
    private fun mainWrapPlan(nowMs: Long): MainWrap? {
        val w = local.wrapPlan(localRow()) ?: return null
        val ms = mainList.listBind.staging ?: return null
        if (w.list.list != ListStaging.LIST || mainList.listBind.list?.mark != w.list.mark) return null
        val pan = ms.wrapPan(w.step) ?: return null
        val p = ms.wrapParams(pan, w.cPrime) ?: return null
        // B082: the wrap draws its own record, which a re-stage in pieces may be writing: no PLAY while a piece written or
        // on its way meets its read range (§5.6) — until the re-stage is booked the edge goes as today (SPLIT2). K1-2 stage 2
        // (decisions CP): a re-stage while the list is in front writes only inside that range (a band form's move: the
        // spare slots and the item table lie between the wrap's record and its program), and one that writes the strip goes
        // only withdrawn, when the rules hold no list in front and plan no wrap — so no wrap plays during any re-stage
        val c = stageCursor
        if (c != null && c.rec.key is ListStageKey) {
            val range = pan.program.firstRange(p)
            val sent = c.pieces.take(c.next + (if (stageInFlightRec === c.rec) 1 else 0))
            if (sent.any { pc -> pc.ranges.any { it.first <= range.last && range.first <= it.last } }) return null
        }
        val tag = WRAP_TAG + (wrapTags++ and 0xFFF)
        val play = try { wm.damage.core.motion.Motion.play(pan.program, ListStaging.WRAP_SLOT, ms.periodInForce, tag, 0, p) }
            catch (e: wm.damage.core.geom.LintError) { journal.note("local", "the wrap's PLAY is refused offline (${e.message}) — the row is drawn as today"); return null }
        val until = nowMs + (ms.t + 1L) * ms.periodInForce * 1000L / 1024 + WRAP_START_MS
        return MainWrap(play, Compositor.EndPlan(ListStaging.WRAP_SLOT, pan.program.tracks.map { it.areaL to it.areaR },
            { left, g -> if (!left && wrapPaintFailsForTest) throw wm.damage.core.geom.LintError("a test: the wrap's END paint fails"); ms.paintEnd(pan, p, left, g) }, until), tag)
    }
    // ------------------------------------------------------ Main's bound list (build C2, stage DM2)
    /**
     * Main's list staged on the glasses for local input (`C2-spec.md` §6.4; decisions B.4; `FIRMWARE.md` §6.7 P9). While
     * BIND is in force and Main is in front — or behind the shell's clock screen, which wakes to Main (decisions BP: the
     * base staging only, [listBehindClock]) — the idle pump paints Main's staging from Main's own painters ([ListStaging]),
     * places its records — in the shared stage reserve at 160 KiB (the document's strips go; Reader stages again on its
     * return), in Main's own region at 256 ([CfwModes.mainRegion]) — and writes them through the stage lane; once the
     * count (field 17) has proven every one, `MainSurface.bound()` offers the list and the rules SET its entries. A row
     * whose picture changes is painted again and re-staged, paced per row ([ListBinding.RESTAGE_PACE_MS]), only while
     * no burst is open and nothing is in flight, and never under a table that still names what is written (decisions
     * X.2): its band form goes into a free slot and the one item-table word that names it follows in the same flush (the
     * report's P names the slot drawn, before and after); its strip segments go only with the list withdrawn — the rules'
     * CLEAR rides a flush between, since the idle step that writes never runs in a pump that flushes, and the SETs come
     * back after the write. A row older than Main's truth is not a reason to discard (X.1). A full re-stage (the layout,
     * the planes, the rows or the region changed, a row outgrew its slot) withdraws the list the same way. The envelope
     * is [ListBinding]'s.
     */
    private val mainList: ListBind = object : ListBind(main, ListStaging.LIST, ListStaging.ENTRIES) {
        override val stageTag = "mainstage"
        override val who = "Main"
        override val listName = "Main's list"
        override val form = wm.damage.core.motion.ListForm.MAIN_BANDS
        /** Main's region moved (the atlas over it, another cache): its staging lies from the region's start. */
        override fun notKept(st: LinkState, ms: ListStaging): String? =
            if (st.cacheSize != ms.cacheSize || listBindRegion(st).first != ms.regionStart) "the session's cache or Main's region changed" else null
        override fun rows(): Int = mainRows().size
        override fun rowKey(index: Int): Any? = main.rowKey(index)
        override fun rowIdentity(index: Int): String? = main.rowIdentity(index)
        override fun inFront(): Boolean = mode == Mode.MAIN

        /** Main's planes as `updatePlanes` gives them while Main is in front with nothing over it: the list at the Global
         *  `Depth`, the band one notch nearer, never nearer than the screen plane (0 / 0 at Depth 0). Read from the setting,
         *  not the plane map, so a cover that drops the band's plane does not re-stage Main. */
        override fun listBindPlanes(): Pair<Int, Int> = settings.depth.let { d -> if (d == 0) 0 to 0 else d to maxOf(0, d - 4) }

        /** Where Main's records lie this session: its own region on a 256 KiB session with BIND in force — unless the atlas
         *  was laid out over it (an atlas made before BIND was armed: the shared reserve then); else the shared reserve. */
        override fun listBindRegion(st: LinkState): IntRange {
            val own = CfwModes.mainRegion(st.cacheSize, st.bind)
            val a = atlas
            return if (own.last + 1 < st.cacheSize && a != null && a.capacity > own.first) CfwModes.mainRegion(st.cacheSize, false) else own
        }

        /** Decisions BP (his 0.82 start of 11-20 13:26: 42 s on the clock with nothing of Main written, then 12.4 s of notches
         *  drawn by the phone after the wake): the shell's own clock screen wakes to Main — its double tap always lands there
         *  (`exitSilent`, `DESIGN.md` §1.5) — so Main's base staging goes behind it, painted for the layout the wake brings
         *  ([listBindLayout]). The list stays unbound until Main is in front: the rules' list in front needs `Mode.MAIN`, and
         *  the wake's first flush SETs it (the return rule, AF.2). A window's exclusive screen wakes to that window (§4.9):
         *  nothing of Main is staged there. */
        override fun listBehindClock(): Boolean = mode == Mode.SILENT

        /** The layout Main's staging is painted and kept for: the shell's own while Main is in front; elsewhere Main's OWN —
         *  [layoutFor] the settings, the one Main has when it is next in front (behind the clock the one its wake brings,
         *  `exitSilent`'s `syncLayout`: a height set while the clock shows is applied only there, so the staging is not
         *  dropped at the wake). K1-2 stage 3b (decisions CX): the shell's `layout` follows the window in front, and nothing
         *  of Main's staging needs it while Main is not — its region is the cache's, its pictures are its own records and
         *  the atlas's changes clear the registry by themselves — so a window of another height or face in front keeps
         *  Main's staging, and a Global height or face change (Main's own layout) still drops it. */
        override fun listBindLayout(): Layout = if (mode == Mode.MAIN) layout else layoutFor(settings)

        /** Build ANIM (AV, AW.1): the motion the `Animations` rows ask for. */
        override fun motionInForce() = ListStaging.ScrollMotion(settings.scrollLength, settings.frames,
            wm.damage.core.motion.ScrollEffect.byId(settings.scrollEffect) ?: wm.damage.core.motion.ScrollEffect.DEFAULT)
    }

    /**
     * K1-2 stage 0 (`K1-2-spec.md` §7; decisions BC, BD, BQ): one list the glasses hold for local scroll, as the shell keeps
     * it — its staging and binding ([listBind]), the [surface] that offers it, the space its records take this session
     * ([listBindRegion]), its planes, its motion and the key a staging is painted and refused for ([listBindPaintFor],
     * [listBindRefusedFor]) — with the shell's paths over it as its methods, at their places below: the poll
     * ([listBindPoll]), the pump ([pumpListBind]), the staging ([listBindStage]), a row's re-stage ([listBindRow]), the
     * pans' re-make and its poll ([listBindReprogram], [listBindReprogramPoll]), the drop ([listBindDrop]) and the period's
     * apply ([listPeriodApply]). Two instances (K1-2 stage 3): Main ([mainList]) and a window's list ([windowList]); which
     * holds slot 0 is [slot0]'s alone (decisions CK A8). The list's number and its entries' numbers are values (Main: list
     * 0, entries 0–2; the window list list 1 with entries 3–5; the wheel to be list 2 with 6–8), and the space is any range
     * of the cache it is given, from the region's start or below another tenant. What is Main's alone is its instance's: the
     * clock screen it is staged behind, its rows' keys and identities; its staging's form ([ListStaging]: the band forms, the
     * item table, the press, the stage-4 wrap record), the wrap's PLAY and the probe stay outside. Nothing here is new
     * state: the six questions are [ListBinding]'s, the measured period's are BF's ([stepWindow]). Loop-confined.
     */
    private abstract inner class ListBind(val surface: ListSurface, val listNo: Int, entries: IntArray) {
        val listBind = ListBinding(listNo, entries)
        /** K1-2 stage 3: its journal notes' kind (`mainstage`, `liststage`), what they call it ("Main", "the window list")
         *  and its list ("Main's list", "the window list"). */
        abstract val stageTag: String
        abstract val who: String
        abstract val listName: String
        /** Its staging's form: Main's band forms, or a window's strip and pan slots alone ([ListStaging.assembleWindow]). */
        abstract val form: wm.damage.core.motion.ListForm
        /** Why its staging [ms] is no longer kept — the cache, its region, a tenant laid over it, another list in front — or
         *  null: the poll's, beside the layout, planes, rows, motion and form it compares for every instance. */
        abstract fun notKept(st: LinkState, ms: ListStaging): String?
        /** A note under its kind. */
        fun note(text: String) = journal.note(stageTag, text)
        /** What a staging is made for beyond its rows' keys (a window list's view), part of its paint's and refusal's key. */
        open fun stagingOf(): Any? = null
        /** K1-2 stage 3c, for its notes: whose row its effect comes from (a window list: the app's own, or `Animations'` at
         *  "default"); null for Main, whose effect is always `Animations'`. */
        open fun effectFrom(): String? = null
        /** A staging [ms] was laid out and begun. */
        open fun began(ms: ListStaging) {}
        /** A staging was refused ([listBindRefused]); [pt] its paint where every row was painted, else null. */
        open fun refused(pt: ListStaging.Paint?) {}
        /** The rows it lists now. */
        abstract fun rows(): Int
        /** A row's key ([MainSurface.rowKey]: what a compose compares) and identity ([MainSurface.rowIdentity]: what carries
         *  its segment room into the next staging). */
        abstract fun rowKey(index: Int): Any?
        abstract fun rowIdentity(index: Int): String?
        /** Its window is in front (a staging is made and kept current only then, P9). */
        abstract fun inFront(): Boolean
        /** Its records' space this session. */
        abstract fun listBindRegion(st: LinkState): IntRange
        /** Its planes: the list's disparity and the band's. */
        abstract fun listBindPlanes(): Pair<Int, Int>
        /** It is staged behind a screen that wakes to it: the base staging only, the list bound at the wake. */
        abstract fun listBehindClock(): Boolean
        /** The layout its staging is painted and kept for. */
        abstract fun listBindLayout(): Layout
        /** The motion its pans play. */
        abstract fun motionInForce(): ListStaging.ScrollMotion

        /** A staging the region could not hold, by what it was painted for: not painted again until one of those changes. */
        var listBindRefusedFor: String? = null
        /** Stagings refused this process (tests read it: a refused list is not painted again until what it was refused for
         *  changes). */
        var listBindRefusals = 0
        /** The staging being painted a row per loop turn (AF.8), the key it is painted for, and the atlas's versions at its
         *  start (a change means the paint met characters the atlas did not hold: painted again once it does). */
        var listBindPaint: ListStaging.Paint? = null
        var listBindPaintFor: String? = null
        var listBindPaintVersions: Pair<Any?, Any?>? = null
        /** The last reason it was not staged that the journal was told (AF.9), cleared at a session start. */
        var listBindBlockedSaid: String? = null
        /** The last "cannot be staged" note (AF.6), said once per change of its reason; cleared at a session start and when
         *  it is staged (decisions AL.1). */
        var listBindRefusalSaid: String? = null
        var listBindWakeAtMs = 0L
        /** Pumps so far: a strip write goes in a later pump than the withdrawal that let the rules' CLEAR go first. */
        var listBindPumps = 0L
        /** C3 DM-1 (D3): the last "plays its fallback" note ([listBindPlaysNote]). */
        var listBindPlaysSaid: String? = null

        /**
         * Decisions BF (G4): the period follows the MEASURED step. Each complete motion of the bound list (its slot's local
         * instances and the wrap's) gives its largest step time; the last [PERIOD_WINDOW] are kept. The period in force is
         * raised as soon as the window's maximum × [PERIOD_MARGIN_MEASURED] needs more ticks, and lowered — to what it needs,
         * never below the staging's own period (W1) — only after [PERIOD_QUIET_MS] with no input, by [PERIOD_LOWER_MIN] ticks
         * or more, with no instance open and no burst (his look at 0.81, ruled). Clamped to the engine's 16..128. Applied with no burst open: new SETs
         * (the records kept); K1-1: under Auto the pans are then re-made in idle for the steps the new period gives
         * (`listBindReprogram`) — with no spare pan slot their T is kept and a longer period runs the motion longer.
         */
        val stepWindow = ArrayDeque<Long>()
        var periodSamplesSinceChange = 0
    }

    // ------------------------------------------------------ K1-2 stage 3: a window's list, bound (glasses list 1)
    /** What a window list's staging is of (the K1-2 spec §5.1's key, less the effect — decisions CV: an effect change keeps
     *  the staging — and less what the poll compares on the staging itself: its rows, layout, planes and space): the
     *  window, its list level (Settings' `listLevel`: the adjust level is a cover, not a level) and its `ListModel` by
     *  identity (Settings' categories and a category's rows share one). Not its title (decisions CY, the first review of
     *  3a's finding A): a 4-second notice in a window's title (Files, Torrents, Feed, Music, Games) or Music's queue title
     *  at every track dropped and staged an unchanged list twice; a different list under the same key (Files' sibling
     *  folders of one size, a deep link to another category) is the compose compare's ([WindowListBind.composeCompare]:
     *  every row it compares drawing otherwise is a new staging). [title] rides for the notes only. */
    private data class WindowListView(val window: String, val level: Int, val model: ListModel) {
        /** The title when it was read — not part of the key (not in the constructor: no part of equality). */
        var title: String = ""
        /** For the notes: "settings level 2 'global'". */
        fun words(): String = "$window level $level${if (title.isNotEmpty()) " '$title'" else ""}"
    }

    /** A window's list in front: [w]'s `ListView` [v] (one per call) of two rows or more — a list of one row is not bound,
     *  its notch does nothing (the spec §1) — and what it is ([id]). */
    private class WindowFront(val w: DamageWindow, val v: WindowView.ListView, val id: WindowListView)

    /** The window's list in front, or null (Main, the clock, an exclusive screen, any view that is not a list of 2+ rows) —
     *  inside a pump the turn's ([frontOfTurn]), elsewhere (a handler, a test's read) as it is now. */
    private fun windowFront(): WindowFront? = frontOfTurn.let { if (it != null) it.front else windowFrontNow() }

    /** K1-2 stage 4i (item 3; the first review of K1-2's finding D, measured: about 19 evaluations a pump with Settings'
     *  Global in front, each the window's `view()`, `rowCount()` and `title()`, which Settings answers by building its rows
     *  again): the window list in front worked out ONCE a pump, at its start — after the turn's message is handled — and
     *  read by every holder, surface and rule the pump asks. The mode, the window and its list level change only in a
     *  turn's handler, never inside its pump. Null outside a pump. */
    private class FrontOfTurn(val front: WindowFront?)
    private var frontOfTurn: FrontOfTurn? = null

    private fun windowFrontNow(): WindowFront? {
        if (mode != Mode.WINDOW) return null
        val w = current ?: return null
        val v = w.view() as? WindowView.ListView ?: return null
        if (v.rowCount() < 2) return null
        return WindowFront(w, v, WindowListView(w.id, if (w === settingsWindow) settingsWindow.listLevel() else w.levelDepth(), v.model).also { it.title = w.title() })
    }

    /** A window's planes as `updatePlanes` gives them while it is in front with nothing over it: its content at the app's
     *  `Depth` (else Global's), the band one notch nearer, never nearer than the screen plane (0 / 0 at Depth 0). */
    private fun windowPlanes(w: DamageWindow): Pair<Int, Int> = settings.appDepthOf(w.id).let { d -> if (d == 0) 0 to 0 else d to maxOf(0, d - Geometry.X_STEP) }

    /** The window list's staging is of the view in front, as it is now — its key whole (the spec §5.1): the view, its row
     *  count, the layout and the planes. What [WindowListSurface.bound] and [slot0] ask; the poll drops a staging that
     *  stops matching while its view is in front, and keeps it while another screen is (kept across Main). */
    private fun windowListMatches(): Boolean {
        val ms = windowList.listBind.staging ?: return false
        val f = windowList.stagedFront() ?: return false
        return f.v.rowCount() == ms.rows && layout == ms.layout && windowPlanes(f.w) == (ms.listD to ms.bandD)
    }

    /**
     * K1-2 stage 3 (the K1-2 spec §5, §6; decisions BC O2/O4, BD, CK A3/A8/A10, CU, CV): a window's list staged on the glasses
     * and bound as glasses list 1 (entries 3–5, slot 0) in the window form ([ListStaging.assembleWindow]: its strip and two
     * pan slots, no band forms, no item table, a report-only tap) while it is in front at ladder stage 5 on a 256 KiB
     * session — below that nothing of it is sent ([pumpWindowList]). Its space is Main's region from the wheel's block's
     * base ([wheelBase]) down to Main's staging's end: Main wins an overlap, and a list already staged where the block goes
     * yields (`wheelPlace`, the one place a block is laid out). Kept while Main, the clock or a screen that is not a list is in front (Settings →
     * Main → Settings writes nothing); another list in front replaces it; a row count, layout or planes change of its own
     * view makes a new staging (a new mark). Its effect is Global `Animations`' (Crush and Flip play their fallback on the
     * window form, decisions CV); an effect change re-makes its pans alone, as Main's. Its rows' keys are null: a changed row
     * is found by the compose compare ([composeCompare], stage 3b) and the round-robin. Notes under `liststage`.
     */
    private inner class WindowListBind : ListBind(WindowListSurface({ windowList.viewNow()?.v }, { windowListMatches() }), WindowListCheck.LIST, WindowListCheck.ENTRIES) {
        override val stageTag = "liststage"
        override val who = "the window list"
        override val listName = "the window list"
        override val form = wm.damage.core.motion.ListForm.STRIP_WINDOWS
        /** The staging and the view it was made for (set when it is staged; the pair holds only while that staging stands). */
        var stagedFor: Pair<ListStaging, WindowListView>? = null

        override fun stagingOf(): Any? = windowFront()?.id
        override fun began(ms: ListStaging) { stagedFor = windowFront()?.let { ms to it.id }; refusedRows = null; if (stagedFor?.second != allChangedFor) allChangedFor = null }

        /** K1-2 stage 3b (pin 6's content half; decisions AF.6): a staging refused with every row painted — each row form's
         *  hash ([rectHash]), the view and the layout it was painted for. A compose of that view that shows one of those rows
         *  drawing otherwise tries the staging again ([composeCompare]); nothing else does but what its key holds (the
         *  layout, planes, rows, space, motion). Cleared when a staging is begun; replaced at the next refusal. */
        private var refusedRows: Triple<IntArray, WindowListView, Layout>? = null
        override fun refused(pt: ListStaging.Paint?) {
            refusedRows = pt?.let { p -> windowFront()?.id?.let { id -> Triple(IntArray(p.n) { i -> p.rowForms[i]!!.let { g -> rectHash(g, 0, 0, g.w, g.h) } }, id, p.layout) } }
        }

        /** K1-2 stage 3b: the band's picture at the last compose compared — what it was compared for (the staging, or the
         *  refused rows), the row under the band and the band's hash. */
        private var bandSeen: Triple<Any, Int, Int>? = null
        /** Decisions CY: the staging whose every row a compose compared drew otherwise — [notKept] drops it (held by
         *  identity: a new staging is never it). */
        private var allChanged: ListStaging? = null
        /** Decisions DF (the focused read of 3b's fixes, finding 1): the view an every-row-changed drop was made for, kept until
         *  another view is staged ([began]) — a second every-row-changed of that same view is a live list (Torrents' speed
         *  in each downloading row), whose rows go one at a time with the staging and its mark kept. */
        private var allChangedFor: WindowListView? = null

        /**
         * K1-2 stage 3b (the K1-2 spec's O5 and §6's row "the compose compare and the round-robin"; decisions BC O5): at a
         * compose of the window whose list this holds, the rows its paint just made beside the band are compared with the
         * staged copy ([ListStaging.stagedRowIs]) — a row drawing otherwise is painted again in idle and re-staged by
         * withdrawal ([ListBinding.differs]; the round-robin finds the rows off screen) — and the band's picture with the
         * last compose's for the same row under it: the band paints the lens form, not the row form the strip holds, so a
         * change there with the row unmoved is that row's own (an adjusted value) and marks it the same way. A reorder is
         * found as row changes (the spec §1: no identity is known); every row compared drawing otherwise is another list
         * under the same key (decisions CY: the title is no part of it) — the poll drops the staging and the next is a new
         * one ([notKept]) — once per view (decisions DF: a second time it is a live list, kept, its rows one at a time).
         * For a staging the space refused, a row drawing otherwise than its refused paint tries the staging again (AF.6:
         * the key is what the rows draw; a row count the form cannot take is refused before any paint, decisions DE, and
         * keeps no rows to compare). Bytes the paint already
         * made, loop-only; the only state is [bandSeen].
         */
        fun composeCompare() {
            val ms = listBind.staging
            val f = (if (ms != null) stagedFront() else windowFront()) ?: return
            if (contentWindow() !== f.w) return
            val l = layout
            val rr = refusedRows?.takeIf { ms == null && it.second == f.id && it.third == l && it.first.size == f.v.rowCount() }
            val ref: Any = (if (ms != null && ms.layout == l) ms else rr?.first) ?: run { bandSeen = null; return }
            val g = comp.composed
            val c = f.v.model.cursor
            var retry = false
            var compared = 0
            var differ = 0
            kit.visibleRows(l, f.v.rowCount(), c) { row, r ->
                if (ms != null) {
                    val same = ms.stagedRowIs(row, g, r.x, r.y) ?: return@visibleRows
                    compared++
                    if (!same) { differ++; listBind.differs(row) }
                } else if (rr!!.first[row] != rectHash(g, r.x, r.y, r.w, r.h)) retry = true
            }
            // decisions CY (the first review of 3a, finding A): every row compared drawing otherwise is another list under
            // the same key (the title is no part of it) — the poll drops the staging and the next is a new one with a new
            // mark ([notKept]), not a re-stage of a row per 5 s
            // decisions DF: once per view — a second time with no other view staged between, it is a live list: its rows,
            // each marked above, go one at a time and the staging and its mark are kept
            if (ms != null && compared > 0 && differ == compared && allChanged !== ms && f.id != allChangedFor) { allChanged = ms; allChangedFor = f.id; post(Msg.Pump) }
            val band = rectHash(g, l.lens.x, l.lens.y, l.lens.w - Layout.RAIL_W, l.lens.h)
            val b = bandSeen
            if (b != null && b.first === ref && b.second == c && b.third != band) { if (ms != null) listBind.differs(c) else retry = true }
            bandSeen = Triple(ref, c, band)
            if (retry) { listBindRefusedFor = null; refusedRows = null; bandSeen = null; post(Msg.Pump) }
        }

        /** Decisions CY (the review's finding B): the screen in front is no longer the one its staging was made for —
         *  another window, another level, or no list of two rows or more; a cover, the adjust level, a withdrawal, a drop of
         *  the same view or a title change are not. */
        fun screenLeft(): Boolean = windowFront()?.id.let { it == null || it != stagedFor?.second }

        /** The view its staging is of, when that view is in front; null otherwise (another view, none, no staging). */
        fun stagedFront(): WindowFront? {
            val sf = stagedFor ?: return null
            if (sf.first !== listBind.staging) return null
            return windowFront()?.takeIf { it.id == sf.second }
        }

        /** The view in front for a staging to be made, or the staged view in front — null while the staging stands for
         *  another screen (kept: its own rows, layout and planes are then what it was made for). The surface's view: a
         *  staging is never painted again from a view it is not of. */
        fun viewNow(): WindowFront? = if (listBind.staging == null) windowFront() else stagedFront()

        override fun rows(): Int = viewNow()?.v?.rowCount() ?: listBind.staging?.rows ?: 0
        override fun rowKey(index: Int): Any? = null
        override fun rowIdentity(index: Int): String? = null
        override fun inFront(): Boolean = windowFront() != null && !(current === settingsWindow && settingsWindow.isAdjusting)
        override fun listBehindClock(): Boolean = false
        override fun listBindLayout(): Layout = listBind.staging?.takeIf { viewNow() == null }?.layout ?: layout
        override fun listBindPlanes(): Pair<Int, Int> = viewNow()?.let { windowPlanes(it.w) } ?: listBind.staging?.let { it.listD to it.bandD } ?: (0 to 0)
        /** K1-2 stage 3c (the K1-2 spec §4; decisions CV): `Animations`' length and frames, and the effect of the app whose
         *  list it is — its own `Scroll effect` row, `Animations`' at "default"; Settings' own lists play `Animations`'. A
         *  change re-makes the pans alone (the poll, [listBindReprogram]): the staging is kept. */
        override fun motionInForce(): ListStaging.ScrollMotion {
            val g = mainList.motionInForce()
            val id = appId()?.takeIf { it != settingsWindow.id } ?: return g
            return g.copy(effect = settings.scrollEffectOf(id))
        }

        /** The window whose list it is: the view its staging is of (in front or kept), or the list in front before one is
         *  made; null with neither. */
        fun appId(): String? = viewNow()?.w?.id ?: stagedFor?.takeIf { it.first === listBind.staging }?.second?.window

        override fun effectFrom(): String {
            val id = appId()?.takeIf { it != settingsWindow.id } ?: return "Animations'"
            val name = windows.firstOrNull { it.id == id }?.name ?: id
            return if (settings.appStyle(id).scrollEffect == "default") "Animations', the row of $name at default" else "the row of $name"
        }

        /** From the wheel's block's base down to Main's staging's end in Main's own region (decisions CK A10, CU; Main wins,
         *  O4); the region's start where Main has no staging there, its end where no block is placed. Empty with no region
         *  of Main's own (160 KiB, an atlas over it: [pumpWindowList] tries nothing then). */
        override fun listBindRegion(st: LinkState): IntRange {
            val own = mainList.listBindRegion(st)
            if (own.last + 1 >= st.cacheSize) return IntRange.EMPTY
            val floor = mainList.listBind.staging?.takeIf { it.regionStart == own.first && it.regionEnd == own.last + 1 }?.end ?: own.first
            return floor until wheelBase(st)
        }

        override fun notKept(st: LinkState, ms: ListStaging): String? {
            if (st.cacheSize != ms.cacheSize) return "the session's cache changed"
            val own = mainList.listBindRegion(st)
            if (own.last + 1 >= st.cacheSize || ms.start < own.first || ms.end > own.last + 1) return "Main's own region no longer holds it"
            mainList.listBind.staging?.takeIf { it.end > ms.start && it.start < ms.end }?.let { m ->
                return "Main's staging at ${m.start}..${m.end} meets it at ${ms.start}..${ms.end} (Main wins)"
            }
            if (allChanged === ms) return "every row on screen draws otherwise than its staged copy — another list under the same key: a new staging"
            windowFront()?.let { f -> if (stagedFor?.second != f.id) return "another list is in front (${f.id.words()}): its staging replaces this one" }
            return null
        }
    }

    /** K1-2 stage 3b: [g]'s rect ([x], [y], [w], [h]) hashed line by line as `Arrays.hashCode` hashes the same pixels cut
     *  out — a row form painted alone and the same row in a composed frame hash alike. */
    private fun rectHash(g: Gray8, x: Int, y: Int, w: Int, h: Int): Int {
        if (x < 0 || y < 0 || x + w > g.w || y + h > g.h) return 0
        var out = 1
        for (k in 0 until h) { val at = (y + k) * g.w + x; for (i in at until at + w) out = 31 * out + g.pix[i] }
        return out
    }

    private val windowList = WindowListBind()
    /** Both holders, Main's first. */
    private val lists: List<ListBind> = listOf(mainList, windowList)

    /** K1-2 stage 3: the window list's lane in idle, after the wheel's and before Page staging (decisions CK A3) — only at
     *  stage 5 on a 256 KiB session with a region of Main's own (decisions BC O2, O4): below that nothing of it is sent.
     *  Under a transition [pumpLane]'s rule. C3 DW-1b-1 (decisions DA (c) 4): nothing at all while the switcher is open — an
     *  open wheel is a cover (its list is not in front), and a write then would sit ahead of the wheel's answers and void
     *  the count reading an unbound open's bind waits for (P9). */
    private suspend fun pumpWindowList(st: LinkState): Boolean {
        if (!st.effects || switcher.open || st.cacheSize != CfwModes.CACHE2_MAX_RIDERS || mainList.listBindRegion(st).last + 1 >= st.cacheSize) return false
        return windowList.pumpLane(st)
    }

    /** One rule for both lists' lanes while a transition is in hand (K1-2 stage 4a for the window list, 4i for Main: the
     *  K1-2 spec §5.3, `FIRMWARE.md` §7.11; decisions DA (c) 3, DK 2): a list whose staging is not held and counted — a
     *  level a Select brings, Main arriving with none (a session started inside a window, then Back) — is only PAINTED,
     *  a row per loop turn as Main's paint (AF.8), in this lowest-priority step that runs only in a turn with nothing to
     *  send, ahead of [pumpListBind]'s refusals of a flush in flight or pending, which a run's frames meet for much of it.
     *  Its layout and every write (the records, the count's reading, LIST, the SETs) follow the run's end, so it is staged
     *  after the run from rows already painted for its key. A held, counted staging goes as it does with no run: Main's
     *  (its SETs ride the held frame, a changed row re-staged), the window list's nothing (its rows' repaint and re-stage
     *  wait for the run's end). */
    private suspend fun ListBind.pumpLane(st: LinkState): Boolean = when {
        transition == null -> pumpListBind(st)
        !listBind.counted -> listBindPaintUnderRun(st)
        this === mainList -> pumpListBind(st)
        else -> false
    }

    /** K1-2 stage 4a: under a transition, one row of the arriving list's staging painted (loop work only, [pumpLane]) — never
     *  for a list staged already (a laid-out one waits for the run's end, as its writes do), never while
     *  [listBindBlocked] gives a reason or for a key refused, and, as Main's paint, not while the atlas is being written
     *  (§73: the atlas goes first) — [listBindStage] with `paintOnly` stops before the layout, after which the records are
     *  wanted. True when a row was painted. */
    private fun ListBind.listBindPaintUnderRun(st: LinkState): Boolean =
        listBind.staging == null && listBindBlocked(st) == null && atlasInFlight == null && atlasQueue.isEmpty() && !atlasLapsedOwed &&
            !atlasCheckInFlight && listBindStage(st, System.currentTimeMillis(), paintOnly = true)

    /** Test introspection, LOOP-ONLY (read inside [sampleIdle]): Main's staging and its counts; what Main offers and its row. */
    internal val mainBinding: ListBinding get() = mainList.listBind
    internal fun mainBoundForTest(): Pair<wm.damage.core.local.BoundList?, Int> = main.bound() to main.row()
    /** K1-2 stage 3, tests (on the loop): the window list's binding; what it offers for the view in front and that view's
     *  row; which list holds slot 0 ("main", "window" or null: [slot0]); the window list's measured window (BF). */
    internal val windowListBinding: ListBinding get() = windowList.listBind
    internal fun windowBoundForTest(): Pair<wm.damage.core.local.BoundList?, Int> = windowList.surface.bound() to windowList.surface.row()
    internal fun slot0ForTest(): String? = when (slot0List()) { mainList.listNo -> "main"; windowList.listNo -> "window"; WHEEL_LIST -> "wheel"; else -> null }
    /** C3 DW-1b-1, tests (on the loop): what the wheel offers and its row; whether a burst is open and the preview's key. */
    internal fun wheelBoundForTest(): Pair<wm.damage.core.local.BoundList?, Int> = wheelBind.offered to wheelBind.row()
    internal fun previewForTest(): Pair<Boolean, String?> = local.burstOpen to previewPainted
    /** K1-2 stage 4a, tests (on the loop): the window list's paint in hand — rows painted, rows in all, rows painted under a
     *  transition — or null. */
    internal fun windowListPaintForTest(): Triple<Int, Int, Int>? = windowList.listBindPaint?.let { Triple(it.next, it.n, it.underRun) }
    /** K1-2 stage 4i, tests (on the loop): Main's paint in hand, as [windowListPaintForTest]; Main's region on this session. */
    internal fun mainPaintForTest(): Triple<Int, Int, Int>? = mainList.listBindPaint?.let { Triple(it.next, it.n, it.underRun) }
    internal fun mainRegionForTest(): IntRange = mainList.listBindRegion(transport.state.value)
    /** K1-2 stage 3b, tests (on the loop): the window list's refused stagings this process. */
    internal fun windowListRefusalsForTest(): Int = windowList.listBindRefusals
    /** Tests (on the loop): a 114 body read as the transport hands one (the review's finding C pin: a body whose report was
     *  not heard). */
    internal fun localBodyForTest(ev: TransportEvent.LocalBody) = localBodyRead(ev)
    /** Tests (on the loop): the cursor of the list view in front (−1: none). */
    internal fun focusedRowForTest(): Int = (focusedView() as? WindowView.ListView)?.model?.cursor ?: -1
    internal fun stepWindowsForTest(): Pair<List<Long>, List<Long>> = mainList.stepWindow.toList() to windowList.stepWindow.toList()
    /** Tests (on the loop): the window list's lane has nothing left to write — no window list wanted here (below stage 5,
     *  160 KiB, the window's list not in front: none, or Settings at its adjust level, a cover by design), or a staging for
     *  the view in front counted, offered or refused for its key. C3 DW-2 (decisions DP 8): nothing left to write either
     *  while the switcher is open — [pumpWindowList] writes nothing under it (decisions DA (c) 4: an open wheel is a cover);
     *  met by the oracle walk's chord right after a session start with Settings in front, whose list is staged after the
     *  close. */
    internal fun windowListLaneDoneForTest(): Boolean {
        val st = transport.state.value
        if (!st.effects || !st.bind || st.cacheSize != CfwModes.CACHE2_MAX_RIDERS || !windowList.inFront() || switcher.open) return true
        val b = windowList.listBind
        return (b.staging != null && b.counted && windowList.surface.offered != null && windowListMatches()) ||
            (b.staging == null && windowList.listBindRefusedFor != null && windowList.listBindPaint == null)
    }
    /** Test introspection (build ANIM, AW.4): Q in force and the bound pan's motion in ms (T × P), loop-only. */
    internal fun localQForTest(): Long = local.q
    /** K1-1: whether a burst is open (the rules' P7), loop-only. */
    internal fun localBurstOpenForTest(): Boolean = local.burstOpen
    /** The rules' counters (`unreported`, `lateEcho`, …), read on the loop. */
    internal fun localCountsForTest(): Map<String, Int> = local.counts.toMap()
    internal fun motionMsForTest(): Long = mainList.listBind.staging?.let { it.t * it.periodInForce * 1000L / 1024 } ?: 0L
    /** G4 item 1: assembles the shell made between the last edge report's verdict and its wrap's PLAY (0: the PLAY went
     *  first); the period in force (BF); the measured window. Loop-only. */
    internal fun wrapAssemblesBeforeForTest(): Int = lastWrapAssemblesBefore
    internal fun periodInForceForTest(): Int = mainList.listBind.staging?.periodInForce ?: 0
    internal fun mainRestingForTest(): Boolean = main.resting
    internal fun notificationsUpForTest(): Boolean = notifications.active || notifications.animating
    internal fun notificationsFocusedForTest(): Boolean = notifications.active && notifications.focused
    /** A harness's clock for the notice's timer (the tests, the desktop render harness): while set, a step owed is
     *  marked and not launched, and [graceStepByHand] delivers it as the real one is delivered (a `GraceTick` of the
     *  running grace, handled on the loop). Set before the box arrives. */
    @Volatile var graceByHand = false
    fun graceStepByHand() = post(Msg.Run { if (graceJob != null) post(Msg.GraceTick(graceGen)) })
    /** The timer's step and whether a step is owed — LOOP-ONLY (read it inside [sampleIdle]). */
    fun graceState(): Pair<Int, Boolean> = notifications.graceStep to (graceJob != null)
    /** Build K1-4a, tests: the notice's programs as "STYLE lines STATE" (a wait's condition; read off the loop). */
    internal fun noticeProgramsForTest(): List<String> = noticeProgs.map { "${it.style} ${it.lines} ${it.rec.state}" }
    /** 0.83 integration, tests (on the loop): each notice program's cache offset, its bytes as written, its state. */
    internal fun noticeProgramRecordsForTest(): List<Triple<Int, ByteArray, String>> = noticeProgs.map { Triple(it.rec.off, it.rec.encoded.copyOf(), it.rec.state.name) }
    /** Tests: the notice lane has nothing left to write this session — no programs here (no region, no engine), the slice
     *  Page staging's while a window that stages pages is in front (the coordinator's ruling (a): the lane yields by design
     *  until it leaves; K1-2 S4cF, decisions DG), or every one laid out for the box as it is and counted. A rig's start
     *  waits on it: its writes and their count readings are the start's work. */
    internal fun noticeLaneDoneForTest(): Boolean {
        val key = noticeLayoutKey(transport.state.value) ?: return true
        if (pagingInFront()) return true
        return key == noticeProgsFor && noticeProgs.all { it.rec.state == StagedRecord.State.PROVEN }
    }
    /** K1-2 stage 4cF, tests (on the loop): the notice lane's state and each of [pumpNoticeStage]'s gates in its order, the
     *  count's paces against the clock — a stall's report. Diagnostic only. */
    internal fun noticeLaneReportForTest(): String {
        val st = transport.state.value
        val key = noticeLayoutKey(st)
        val region = CfwModes.noticeRegion(st.cacheSize, st.bind)
        val now = System.currentTimeMillis()
        return "key ${when { key == null -> "none"; key == noticeProgsFor -> "laid out"; else -> "$key, laid out for $noticeProgsFor" }}, " +
            "programs ${noticeProgs.map { "${it.style} ${it.lines} ${it.rec.state}" }}; gates: paging in front ${pagingInFront()}, " +
            "slice held ${region?.let { sliceHeld(it) }}, writes held ${cacheWritesHeld()} (glyph hold ${glyphHoldUntilMs - now} ms), " +
            "stage failed $stageFailed, silent $glassesSilent, draw2 ${st.draw2}, lease ${st.leaseHeld}, readable ${ledgerReadable(st)}, " +
            "atlas in flight ${atlasInFlight != null} queue ${atlasQueue.size} lapsed $atlasLapsedOwed check $atlasCheckInFlight, " +
            "stage in flight ${stageInFlight != null} cursor ${stageCursor != null} reading $ledgerReadInFlight, link in flight ${st.inFlight}, " +
            "pending ${comp.hasPending} keyframe ${comp.needsKeyframe}, base owed ${ledgerBaseOwed(st)}, acked ${stage.count(StagedRecord.State.ACKED)}" +
            "/${ownRecs().count { it.state == StagedRecord.State.ACKED }}, resend missing $atlasResendMissing; the count: unjudged ${cacheLedger.unjudged}, " +
            "writes open ${cacheLedger.writesOpen}, re-read in ${ledgerReadNotBeforeMs - now} ms, retry in ${ledgerRetryNotBeforeMs - now} ms; " +
            "mode $mode, window ${current?.id}, view ${focusedView()?.let { it::class.simpleName }}"
    }
    /** Fix A2's pin, on the loop: the first notice program taken as acked now, then a Base or ([shortfall]) a Shortfall
     *  over the interval that holds that ack, judged as the ledger judges it; the program's state after. */
    /** Fix B3's pin: the slice yields (as Page staging's first strip would make it) with no write over the timer yet. */
    internal fun noticeYieldForTest() = post(Msg.Run { noticeYield("test") })
    internal fun noticeVerdictForTest(shortfall: Boolean): String {
        val p = noticeProgs.firstOrNull() ?: return "none"
        p.rec.state = StagedRecord.State.ACKED; p.rec.ackedAt = cacheLedger.acked
        ledgerVerdict(if (shortfall) CacheLedger.Verdict.Shortfall(1, 0, p.rec.ackedAt - 1, p.rec.ackedAt)
            else CacheLedger.Verdict.Base(0, false), "test", null, false)
        return p.rec.state.name
    }
    /** Build K1-4a, tests: Page staging's records in the ring (offset, bytes, state) — the strips a notice must leave alone. */
    internal fun pageStripsForTest(): List<Triple<Int, ByteArray, String>> =
        stage.ring?.resident.orEmpty().filter { it.key !is RegionStageKey }.map { Triple(it.off, it.encoded, it.state.name) }

    /** Why Main's list is not staged now; null when it is wanted. [front] false: every reason but Main being covered
     *  (K1-1's fix stage: the pans' re-make runs then too). */
    private fun ListBind.listBindBlocked(st: LinkState, front: Boolean = true): String? = when {
        !st.bind -> "BIND is not in force"
        !linkUpAndSteady(st) -> "the link is down or the session is rebuilding"
        sleep != null || glassesSilent -> "the glasses are asleep"
        front && !inFront() && !listBehindClock() -> "$who is not in front"
        testHold -> "hold=on is in force"
        stageFailed -> "staging is off for this session"
        !st.draw2 || !st.leaseHeld -> "DRAW2 or the lease is not in force"
        !transport.cacheCheckSupported || !ledgerReadable(st) -> "the cache count cannot be read here"
        else -> null
    }

    /** [ms]'s motion in words ("10 frames × 45 ms"; None's "1 frame × 45 ms"), as the `Scroll frames` row's hint says it. */
    private fun motionWords(ms: ListStaging): String = frameWords(ms.t, ms.periodInForce)
    private fun frameWords(t: Int, p: Int): String = "$t ${if (t == 1) "frame" else "frames"} × ${"%.0f".format(p * 1000.0 / 1024)} ms"

    /** The `Scroll frames` row's hint: the motion in force at Main's height now — the staging's when it was made for these rows,
     *  else what they give at the current layout. C3 AS: in the row's short form, steps × period ("19 × 16 ms"), as the
     *  tiers' `Frames` rows say theirs, so the value fits a row away from the cursor; the journal keeps [frameWords]. */
    private fun motionHint(): String {
        val ms = mainList.listBind.staging
        val (p, t) = if (ms != null && ms.motion == mainList.motionInForce()) ms.periodInForce to ms.t else {
            val (listD, bandD) = mainList.listBindPlanes()
            val m = mainList.motionInForce()
            wm.damage.core.motion.ScrollEffects.timing(m.effect, layout, listD, bandD, m, transport.state.value.effects)
        }
        return "$t × ${"%.0f".format(p * 1000.0 / 1024)} ms"
    }

    /** The relay Main's pictures are painted through (Page staging's): a face the glasses hold blits from the atlas. */
    private fun listBindRelay(): (Gray8, () -> Unit) -> Unit {
        val ct = cachedText
        return { g, paint -> if (ct != null) ct.via(g, 0, 0, NO_RECORDS) { paint() } else paint() }
    }

    /** Whether a burst is open (`FIRMWARE.md` §6.7 P7): a re-stage waits while one is (P9) — the rules' own state, from
     *  a report until the lone SETTLE. */
    private fun listBindBurstOpen(): Boolean = local.burstOpen

    /** B082 (the split on 0.81): a motion of Main's list may still draw on the glasses — the cells held behind an adopted
     *  motion or the wrap's PLAY wait until its end (AF.7, AZ.4: the wrap's wait runs past Q by its start allowance), and
     *  a re-stage's next piece waits with them: a cache write meeting the instance's read range closes it (§5.6). */
    private fun listMotionMayDraw(): Boolean = comp.deferring && comp.deferUntilMs > System.currentTimeMillis()

    /** Each pump, before anything asks: the staging checked against Main as it is now, the re-stages the count has
     *  judged, and what `MainSurface` offers. */
    private fun ListBind.listBindPoll(st: LinkState) {
        listBindPumps++
        val b = listBind
        val ms = b.staging
        if (ms == null) { surface.offered = null; return }
        val gone = b.records.firstOrNull { stage.records[it.key] !== it }
        val lost = notKept(st, ms)
        val why = when {
            gone != null -> "$gone left the stage registry (the stage notes say why)"
            ms.layout != listBindLayout() -> "$who's layout changed"
            (ms.listD to ms.bandD) != listBindPlanes() -> "$who's planes changed (${ms.listD}/${ms.bandD} staged, ${listBindPlanes().let { "${it.first}/${it.second}" }} now)"
            ms.rows != rows() -> "$who's rows changed (${ms.rows} staged, ${rows()} now)"
            lost != null -> lost
            // build ANIM (AV): the `Animations` rows changed — K1-1: the pans alone are re-made into the spare slot
            // (`listBindReprogram`); a staging with none is made again whole, as before; a change in a burst waits for its SETTLE
            ms.progSlots == null && ms.motion != motionInForce() && !listBindBurstOpen() -> "the motion changed (${motionWords(ms)} → ${motionInForce().lengthMs} ms, " +
                "${if (motionInForce().frames == 0) "Auto" else "${motionInForce().frames} frames"})"
            // build C3, stage DM-0: the staging's form follows EFFECTS in force — a ring staging is never bound without it
            // (a LIST-RING would be refused 15), and a stage-4 form staged before EFFECTS landed gives way to the ring. Stage
            // FF: so does its fit, at every height — the fast path's programs never stay bound while fires run C2's path
            // (a hand disarm), nor C2's fit while every fire runs the fast path (a staging made before EFFECTS landed)
            st.bind && ms.stage5 != st.effects -> "the stage-5 form and fit ${if (ms.stage5) "no longer match: EFFECTS is not in force" else "are wanted: EFFECTS is in force"}"
            else -> null
        }
        if (why != null) { listBindDrop(why); return }
        for (p in b.patches.toList()) {
            val r = stage.records[p.record.key]
            if (r !== p.record) {
                // a write failed or the count called it short: what the item table and the slots hold is not known
                listBindDrop("row ${p.row}'s re-stage left the stage registry before it was counted (the stage notes say why)")
                return
            }
            // decisions AF.4: a band form's old slot is released only when no report can still name it — the switch
            // acknowledged AND no burst open (a report adopted before the ack may still be COMMITted from that slot)
            b.releaseIfDue(p, r.state == StagedRecord.State.ACKED || r.state == StagedRecord.State.PROVEN, local.burstOpen)
            if (r.state == StagedRecord.State.PROVEN && p.released) {
                b.patchCounted(p)
                stage.remove(r)
                stageRecount()
            }
        }
        listBindReprogramPoll()
        val offer = if (st.bind && b.counted && b.withdrawnFor == null) b.list else null
        if (offer !== surface.offered) {
            surface.offered = offer
            if (offer != null) note("offered — $who's ${ms.rows} rows counted on the glasses: list ${offer.list} slot ${offer.slot} " +
                "mark ${offer.mark}, ${offer.entries.size} entries (the whole list, P ${ms.periodInForce} T ${ms.t}), items at off4 ${offer.items4}" +
                if (offer.ring) ", a ring list (LIST-RING: the wrap notch fires on the glasses)" else "")
        }
    }

    /** The rules hold the scroll entries of the list Main offers now (their SETs were sent) — or, K1-2 stage 2 (decisions
     *  CM (1), `C3-FR-DM1.md` item 1), none of its scroll entries at all (a cover's CLEAR): either way a programs switch's
     *  old pans can no longer be named by a fire after them in stream order (K1-1). Without the second, a switch made in a
     *  pump with no room, a cover arriving before the next pump, never released its old pans, and a change made under the
     *  cover played only after the return. */
    private fun ListBind.listBindEntriesSent(): Boolean = surface.offered?.let { l ->
        l.entries.filter { !it.isTap }.map { e -> e to local.heldFor(l.list, e.eventId) }.let { es -> es.all { (e, h) -> h?.prog4 == e.prog4 } || es.all { it.second == null } } } == true

    /** The staging goes: its records leave the registry and the list is withdrawn. */
    private fun ListBind.listBindDrop(why: String) {
        val was = listBind.staging
        for (r in listBind.drop()) if (stage.records[r.key] === r) stage.remove(r)
        // build SPLIT: a re-stage still in pieces (not booked) belongs to the staging that goes — K1-2 stage 3: this holder's
        // (its key names the staging), never the other list's
        stageCursor?.rec?.let { r -> if (r.key is ListStageKey && r.key.content === was && stage.records[r.key] === r) stage.remove(r) }
        surface.offered = null
        stageRecount()
        note("dropped — $why; $listName is offered again once it is staged and counted")
        post(Msg.Pump)
    }

    /** At a compose of Main: rows whose key changed are marked for their repaint. */
    private fun ListBind.mainBindKeys() {
        val ms = listBind.staging ?: return
        listBind.keysChanged(List(ms.rows) { rowKey(it) })
    }

    /** The idle pump's step for Main's list, after the atlas's: a staging painted and placed, one record written, a row
     *  painted again, or a row's re-stage written. True when it did something. */
    private suspend fun ListBind.pumpListBind(st: LinkState): Boolean {
        val blocked = listBindBlocked(st)
        // decisions AF.9: why Main is not staged, once per reason, while BIND is in force and Main is in front (or behind
        // the clock, decisions BP)
        if (st.bind && (inFront() || listBehindClock()) && blocked != listBindBlockedSaid) {
            listBindBlockedSaid = blocked
            if (blocked != null) note("not staged — $blocked; $who's scrolls and taps go as today until it is")
        }
        // K1-1's fix stage: with Main covered (Settings, where the scroll rows change) only the pans' re-make runs — it
        // paints nothing of Main and writes a slot nothing names — so his return SETs the new pans directly
        val covered = blocked != null && !inFront() && listBindBlocked(st, front = false) == null
        if (blocked != null && !covered) return false
        if (cacheWritesHeld()) return false               // C3 DM-T (D6): its staging and its re-make wait for the run's end
        if (atlasInFlight != null || atlasQueue.isNotEmpty() || atlasLapsedOwed || atlasCheckInFlight) return false
        if (stageInFlight != null || stageCursor != null || ledgerReadInFlight) return false
        if (st.inFlight != 0 || comp.hasPending || comp.needsKeyframe) return false
        if (ledgerBaseOwed(st)) return tryLedgerRead(st)
        if (atlasResendMissing > 0L) return false
        val b = listBind
        val now = System.currentTimeMillis()
        if (covered) return b.staging?.takeIf { b.counted }?.let { listBindReprogram(st, it) } ?: false
        val ms = b.staging ?: return listBindStage(st, now)
        // the base records, back to back (one reading counts them all; the reading is [pumpLedger]'s)
        val rec = b.records.firstOrNull { it.state == StagedRecord.State.WANTED && stage.records[it.key] === it }
        if (rec != null) { stageSubmit(rec, st); stageRecount(); return true }
        if (!b.counted) return false
        // decisions BP: behind the clock only the base staging goes — no row painted again or re-staged while the list's
        // window is not in front (P9), and no wake is asked for them: the wake's compose compares the rows' keys
        if (listBehindClock()) return false
        b.verifyDue(now)
        b.nextDirty()?.let { row -> return listBindRow(st, ms, row, write = false, now) }
        // a withdrawn list's strip write, in a pump after the one that withdrew it (the rules' CLEAR went between)
        // B082: a re-stage's first piece too waits while a motion of the list may still draw (its later ones: `mayGo`)
        b.withdrawnFor?.let { row -> return if (listBindPumps > b.withdrawnAtPump && !listMotionMayDraw()) listBindRow(st, ms, row, write = true, now) else false }
        if (!listBindBurstOpen() && !listMotionMayDraw()) b.nextOwed(now)?.let { row -> return listBindRow(st, ms, row, write = true, now) }
        if (listBindReprogram(st, ms)) return true
        listOfNotNull(b.dueInMs(now), b.verifyInMs(now)).minOrNull()?.let { wait ->
            if (listBindWakeAtMs <= now || now + wait < listBindWakeAtMs) { listBindWakeAtMs = now + wait; pumpAfter(wait) }
        }
        return false
    }

    /** A staging refused for [what] (AF.6: tried again when it changes); its [note] said once per change of the reason —
     *  the note without its numbers (the sizes move with the rows' content, the reason does not: decisions AL.1). */
    private fun ListBind.listBindRefused(what: String, note: String, pt: ListStaging.Paint? = null) {
        listBindRefusedFor = what
        listBindRefusals++
        refused(pt?.takeIf { it.done })
        val reason = note.replace(Regex("\\d+"), "#")
        if (reason != listBindRefusalSaid) { listBindRefusalSaid = reason; this.note(note) }
    }

    /** Paints Main's staging and puts its records in the registry at their offsets (WANTED). [paintOnly] (K1-2 stage 4a,
     *  under a transition): the rows painted, nothing laid out — the layout and its writes follow after the run, for the
     *  same key. */
    private fun ListBind.listBindStage(st: LinkState, now: Long, paintOnly: Boolean = false): Boolean {
        val region = listBindRegion(st)
        val (listD, bandD) = listBindPlanes()
        val n = rows()
        val lay = listBindLayout()                       // decisions BP: behind the clock, the layout its wake brings
        // decisions AF.6: the rows' content is part of what a refusal is keyed on — a staging that did not fit is tried
        // again when what it was sized for changes (and at every session start)
        // C3 DM-0 and FF: EFFECTS in force as the transport's state gives it (stage 5 on a bit-11 build) — the staging takes
        // its form (the ring where Main's paint wraps, `ListStaging.ringFits`: 8 rows at 288 and 352) and its fit (the fast
        // DRAW path's) from it; the poll drops a staging made under the other value
        val stage5 = st.effects
        // C3 DM-1: the gate's token for Crush's and Flip's version-2 pans, made from the link state now and never kept
        val fx = effectsToken(st)
        val what = "$lay $listD/$bandD $n ${region.first}..${region.last} ${st.cacheSize} ${motionInForce()} stage5=$stage5 ${List(n) { rowKey(it) }.hashCode()}${stagingOf()?.let { " $it" } ?: ""}"
        if (listBindRefusedFor == what) return false
        if (this === mainList && wheelBind.offered != null && mainWaitsForWheel == what) return false     // C3 DW-1b-1 (CK A6), below
        // decisions DE (the second review's finding 2): a row count the window form can never take is refused before any row
        // is painted, and keeps no rows — so no change of what they draw tries it again (that retry is for a list refused for
        // its space, which a change of its content can cure); under a transition too (stage 4a's paint)
        if (form == wm.damage.core.motion.ListForm.STRIP_WINDOWS) ListStaging.windowCountRefusal(lay, n)?.let {
            listBindPaint = null
            listBindRefused(what, "$who's rows cannot be staged: $it — the list stays unbound (its scrolls and taps go as today) until $who's layout or row count changes, or the next session")
            return false
        }
        val ct = cachedText
        // decisions AF.8: one row painted per loop turn (its row form and band form); the staging is laid out once every row
        // is painted for this same key — a change of layout, planes, rows or their content starts the paint again
        val pt = listBindPaint?.takeIf { listBindPaintFor == what && it.n == n } ?: run {
            listBindPaintFor = what; listBindPaintVersions = ct?.wantVersion to ct?.seenVersion
            ListStaging.Paint(lay, surface.view() ?: return false, kit, bands = form == wm.damage.core.motion.ListForm.MAIN_BANDS).also { listBindPaint = it }
        }
        if (!pt.done) {
            val t0 = System.nanoTime()
            try { pt.step(listBindRelay()) } catch (e: wm.damage.core.geom.LintError) {
                listBindPaint = null
                listBindRefused(what, "$who's row ${pt.next} cannot be painted for staging: ${e.message} — the list stays unbound (its scrolls and taps go as today) until $who's layout, planes or rows change")
                return false
            }
            pt.ns += System.nanoTime() - t0
            if (transition != null) pt.underRun++
            post(Msg.Pump)
            return true
        }
        // K1-2 stage 4a: under a run the layout and every write wait for its end; the atlas-first repaint below goes under it
        // too where the atlas may extend now (not under a glyph run, which holds the atlas to its end), so the rows laid out
        // after the run are already painted once the atlas holds what they drew
        if (paintOnly) {
            if (ct == null || listBindPaintVersions == (ct.wantVersion to ct.seenVersion) || cacheWritesHeld() || listBind.paints >= ListBinding.PAINTS_MAX) return false
            listBind.paints++; listBindPaint = null
            note("$who's rows drew characters the atlas does not hold yet — the atlas goes first, then they are painted again (under a transition)")
            post(Msg.Pump)
            return true
        }
        listBindPaint = null
        if (ct != null && listBindPaintVersions != (ct.wantVersion to ct.seenVersion) && ++listBind.paints <= ListBinding.PAINTS_MAX) {
            // §73, Page staging's rule: the atlas goes first, and Main is painted again once it holds what the paint drew
            note("$who's rows drew characters the atlas does not hold yet — the atlas goes first, then they are painted again")
            post(Msg.Pump)
            return true
        }
        // B082: rows whose window changed in an earlier staging start with segment room; K1-2 stage 1 (O12): and those whose
        // band form changed there with the largest band slot (every other row's its own size)
        val room = listBind.roomFor(List(pt.n) { rowIdentity(it) })
        val bandRoom = listBind.bandRoomFor(List(pt.n) { rowIdentity(it) })
        val ms = try {
            // K1-2 stage 3: a window's list in the window form, laid out down from the region's end (the wheel's block's base)
            // and refused below its start (Main's end) — its strip and two pan slots, the spare given up first
            if (form == wm.damage.core.motion.ListForm.STRIP_WINDOWS) ListStaging.assembleWindow(pt, listD, bandD, st.cacheSize, motionInForce(), fx,
                floor = region.first, top = region.last + 1, room = room) else
            // the bound list's pans only (AF.10); one strip at every cache size (K1-2 stage 1, decisions BC O1: a row form's
            // change withdraws the list, at 256 KiB as at 160)
            ListStaging.assemble(pt, listD, bandD, st.cacheSize, region.first, region.last + 1, listOf(ListStaging.WHOLE), motion = motionInForce(), room = room, stage5 = stage5, fx = fx, bandRoom = bandRoom)
        } catch (e: wm.damage.core.geom.LintError) {
            listBindRefused(what, "$who's rows cannot be staged: ${e.message} — the list stays unbound (its scrolls and taps go as today) until $who's layout, planes, rows or their content change, or the next session", pt)
            return false
        }
        // C3 DW-1b-1 (CK A6): a staging of Main's that meets the wheel's block does not start while the wheel is bound — its
        // paint is kept and it starts at the first poll after the close (the unbind is never mid-open; Main wins then)
        if (this === mainList && wheelBind.offered != null) wheel.block?.takeIf { ms.end > it.base }?.let { b ->
            listBindPaint = pt
            if (mainWaitsForWheel != what) { mainWaitsForWheel = what; note("waits — its staging ends at ${ms.end}, past the wheel's block's base ${b.base}, and the wheel is bound: it starts after the switcher closes (Main wins then)") }
            return false
        }
        mainWaitsForWheel = null
        val recs = ms.blobs.mapIndexed { i, blob -> StagedRecord(ListStageKey(ms, i, blob.name, "$who's"), blob.raw ?: Gray8(1, 1), blob.bytes) }
        val shared = region.last + 1 == st.cacheSize
        if (shared) {
            if (stage.records.isNotEmpty()) stageClear("Main's rows take the shared stage reserve (Reader stages again on its return)")
            val ring = stage.ring?.takeIf { it.start == region.first && it.end == st.cacheSize } ?: StageRing(region.first, st.cacheSize).also { stage.ring = it }
            for ((i, r) in recs.withIndex()) {
                if (ring.placeAt(r, ms.blobs[i].off) == null) {
                    for (q in recs) if (stage.records[q.key] === q) stage.remove(q)
                    listBindRefused(what, "Main's ${ms.blobs[i].name} does not fit the reserve at ${ms.blobs[i].off} — not staged")
                    return false
                }
                stage.records[r.key] = r
            }
        } else {
            // AU.1: a Page staging ring laid out before BIND (the 64 KiB reserve) reaches into Main's own region: its
            // records go, and the ring is laid out again over the bound session's 32 KiB reserve at its next strip
            stage.ring?.takeIf { it.start <= region.last }?.let { stagePagesClear("Main's own region takes the bytes below the stage reserve"); stage.ring = null }
            for ((i, r) in recs.withIndex()) { r.off = ms.blobs[i].off; stage.records[r.key] = r }
        }
        listBindRefusedFor = null
        listBind.begin(ms, recs, List(ms.rows) { rowKey(it) }, now, List(ms.rows) { rowIdentity(it) })
        began(ms)
        stepWindow.clear(); periodSamplesSinceChange = 0          // decisions BF: a staging starts from the fit
        listBindRefusalSaid = null
        stageRecount()
        note(if (form == wm.damage.core.motion.ListForm.STRIP_WINDOWS) windowStagedWords(ms, st, region, pt) else "staged — ${ms.bytes} B in ${ms.blobs.size} record(s) at ${ms.start}..${ms.end} of the ${st.cacheSize / 1024} KiB cache " +
            "(${if (shared) "the shared reserve" else "Main's own region"}): the strip ${ms.blobs[0].bytes.size} B, ${ms.rows} band forms " +
            "${ms.blobs.drop(1).take(ms.rows).sumOf { it.bytes.size }} B in ${ms.bands.sumOf { it.slot }} B of slots each its own size " +
            "(two spares of ${ms.free.values.max()} B, the largest; ${ms.bands.count { it.slot == ms.free.values.max() }} row(s) at that size), " +
            "the item table and ${ms.pans.size + 1} programs ${ms.blobs.takeLast(2).sumOf { it.bytes.size }} B; " +
            "the region's free span ${ms.freeSpan} B at ${ms.end}..${ms.regionEnd}; " +
            "planes $listD/$bandD, press to ${ms.press.pressD}; ${listBind.stagings} staging(s) and ${listBind.stagedBytes} B this process; " +
            "${motionWords(ms)}${if (ms.stage5) " (the fast DRAW path's fit: EFFECTS in force)" else ""}" +
            (if (ms.ring) "; the stage-5 form: a ring list (LIST-RING), the strip's margins ${ms.margin} rows of the other end, no wrap record " +
                "— the wrap notch fires on the glasses" else if (ms.wrap == null && ms.rows > ms.layout.rowsAbove + ms.layout.rowsBelow + 1) "; the wrap's own record left out (the region " +
                "does not hold it): the wrap goes as the shell's frames" else "") +
            (if (listBehindClock()) " — behind the clock: the list is bound when Main is in front" else ""))
        listBindPlaysNote(ms)
        post(Msg.Pump)
        return true
    }

    /** K1-2 stage 3: the window list's `staged —` note — what it takes of its space and what the space holds (decisions CT's
     *  reading: each list's real rows against the room for list 1). */
    private fun ListBind.windowStagedWords(ms: ListStaging, st: LinkState, region: IntRange, pt: ListStaging.Paint): String {
        val m = mainList.listBind.staging
        val top = region.last + 1
        return "staged — ${ms.bytes} B in ${ms.blobs.size} record(s) at ${ms.start}..${ms.end} of the ${st.cacheSize / 1024} KiB cache: " +
            "${windowFront()?.id?.words()} — ${ms.rows} rows in the window form, the strip ${ms.blobs[0].bytes.size} B in its slot of ${ms.strip.slot} B, " +
            "${ms.progSlots?.size ?: 1} pan slot(s) of ${ms.progSlot} B${if (ms.progSlots == null) " (no spare: an effect change is a full staging)" else ""}; " +
            "its space ${region.first}..$top (${top - region.first} B, from ${if (m != null && m.end == region.first) "Main's staging's end" else "the region's start"} " +
            "to ${if (wheel.block?.base == top) "the wheel's block's base" else "the region's end"}), ${ms.start - region.first} B of it left below the list; " +
            "planes ${ms.listD}/${ms.bandD}; ${listBind.stagings} staging(s) and ${listBind.stagedBytes} B this process; " +
            "${ms.motion?.effect?.word} (${effectFrom()}), ${motionWords(ms)}" +
            (if (ms.ring) "; a ring list (LIST-RING), the strip's margins ${ms.margin} rows of the other end" else "; a plain list, its staged edge") +
            // K1-2 stage 4a: what its paint cost the loop, and how much of it went while a transition was in hand
            "; its ${pt.n} rows painted in ${"%.1f".format(pt.ns / 1e6)} ms, one a loop turn, ${pt.underRun} under a transition"
    }

    /** C3 DM-1: the gate's token ([wm.damage.core.motion.Motion.effects]) from [st] as it is now — null where version 2 is
     *  not in force (below stage 5, a build without DamageCaps bit 11). Made at each staging and re-make, never kept
     *  (decisions W, D-6). */
    private fun effectsToken(st: LinkState): wm.damage.core.motion.Motion.Effects? = if (!st.effects) null else
        try { wm.damage.core.motion.Motion.effects(wm.damage.core.wire.DamageMsg.Caps(st.damageContract, st.damageFeatures), st.flagsInForce) }
        catch (e: wm.damage.core.geom.LintError) { journal.note("mainstage", "the version-2 gate refuses with EFFECTS in force: ${e.message} — Crush and Flip play their fallbacks"); null }

    /** C3 DM-1 (D3): why the asked effect plays its fallback, said once per change of what plays and why (the row says
     *  what plays: [scrollPlaysWords]). */
    private fun ListBind.listBindPlaysNote(ms: ListStaging) {
        val asked = ms.motion?.effect ?: return
        val words = ms.plays.words(asked)
        val said = if (words.isEmpty()) null else "${asked.word} $words — ${ms.plays.why}"
        if (said != null && said != listBindPlaysSaid) note("Scroll effect $said")
        listBindPlaysSaid = said
    }

    /** C3 DM-1: what the `Scroll effect` row adds — "plays Slide" while Crush's fallback plays, "plays its height turn"
     *  for Flip's — from the staging made for the rows in force, or before one from the link state; "" otherwise. */
    private fun scrollPlaysWords(): String {
        val m = mainList.motionInForce()
        val ms = mainList.listBind.staging
        val p = if (ms != null && ms.motion?.effect == m.effect) ms.plays else wm.damage.core.motion.ScrollEffects.playsAt(m.effect, transport.state.value.effects)
        return p.words(m.effect)
    }

    /**
     * K1-1 (the idle step): the pans re-made alone when the scroll rows ask for others, or under Auto the period in force
     * gives another step count (`ListStaging.remakeOwed`) — written into the free pan slot as one record, counted, then
     * switched by the entries' SETs ([listBindReprogramPoll]); never a strip. Only in idle: no burst open, no motion of the
     * list that may still draw (a cache write meeting an instance's read range closes it, §5.6), no row's re-stage on its
     * way. Pans that outgrow the slot owe a full staging (a new mark), said.
     */
    private suspend fun ListBind.listBindReprogram(st: LinkState, ms: ListStaging): Boolean {
        val b = listBind
        if (b.reprogram != null || b.withdrawnFor != null || b.patches.isNotEmpty()) return false
        if (listBindBurstOpen() || listMotionMayDraw()) return false
        val want = motionInForce()
        if (ms.remakeOwed(want) == null) return false
        val p = when (val r = ms.remake(want, effectsToken(st))) {
            is ListStaging.Remake.Owed -> r.programs
            is ListStaging.Remake.NoRoom -> {
                // Auto's steps alone stay as they are where there is no slot; a change of the rows is a full staging
                if (ms.motion != want) listBindDrop("the motion changed (${ms.motion?.words()} → ${want.words()}) and ${r.why}: a full staging is owed")
                return ms.motion != want
            }
        }
        val rec = StagedRecord(ListStageKey(ms, ListBinding.PROGS_KEY + p.slot, "pans in slot ${p.slot}", "$who's"), Gray8(1, 1), p.bytes).also { it.off = p.off }
        stage.records[rec.key] = rec
        b.reprogramBegun(ListBinding.Reprogram(rec, p))
        stageSubmit(rec, st, mayGo = { !listBindBurstOpen() && !listMotionMayDraw() })
        note("pans re-made — ${p.why}: ${p.motion.effect.word}, ${p.t} frames × ${"%.0f".format(p.periodInForce * 1000.0 / 1024)} ms, " +
            "${p.bytes.size} B into the free slot at ${p.off}; the entries are SET to them once counted (no strip written); " +
            "${b.reprogramCount} re-make(s), ${b.reprogramBytes} B this process" + (effectFrom()?.let { "; the effect from $it" } ?: ""))
        if (rec.state != StagedRecord.State.SENT) b.reprogramLost()
        return true
    }

    /** K1-1, each poll: the re-made pans' record — gone before its switch: owed again; counted, no burst open: switched
     *  (the list made now names them, and the rules SET the entries); its SETs landed with no burst open: the old pans
     *  released. C3 X3 (decisions CA (2)): counted pans the scroll rows no longer ask for are dropped, never switched, and
     *  none are switched while the list is covered — a change in several steps under a cover goes on the return. */
    private fun ListBind.listBindReprogramPoll() {
        val b = listBind
        val r = b.reprogram ?: return
        if (stage.records[r.record.key] !== r.record) {
            if (!r.switched) { b.reprogramLost(); note("the re-made pans' write did not land (the stage notes say why) — re-made again in idle") }
            else listBindDrop("the pans in force left the stage registry (the stage notes say why)")
            return
        }
        val counted = !r.switched && r.record.state == StagedRecord.State.PROVEN
        // C3 X3: the rows changed again while these were written — the free slot is named by nothing, so they are dropped
        // unswitched and the idle step re-makes into it for the motion asked now (one re-make in flight at a time)
        if (counted && r.programs.motion != motionInForce()) {
            stage.remove(r.record); stageRecount(); b.reprogramLost()
            note("re-made pans dropped unswitched — ${r.programs.motion.words()} is no longer asked (${motionInForce().words()}): re-made for it in idle")
            post(Msg.Pump)
            return
        }
        // the switch waits for the burst's SETTLE, as the period's SETs do (`listPeriodApply`: applied with no burst open);
        // C3 X3: and for the list uncovered — a switch under a cover kept the old pans' slot until the return (their release
        // needs the SETs, which go only with the list in front), so a second change made there played only after it. The
        // return's poll runs before its frame: the return's batch SETs these pans (their LIST ahead where the pitch changes, S6)
        if (counted && !local.burstOpen && slot0() === surface) {
            b.reprogramSwitch()
            val ms = b.staging ?: return
            // a new effect draws other areas: its steps are measured afresh from its own fit (decisions BF)
            if (r.programs.reset) { stepWindow.clear(); periodSamplesSinceChange = 0 }
            note("pans switched — ${r.programs.motion.words()}: ${motionWords(ms)}; the entries are SET to them, the old pans kept for reports until the SETs land" +
                (b.list?.previous?.pitch?.takeIf { it != ms.pitch }?.let { was -> "; the list's pitch $was → ${ms.pitch}: its LIST goes ahead of the SETs in their batch, a new mark (S6)" } ?: ""))
            listBindPlaysNote(ms)
        }
        b.reprogramReleaseIfDue(local.burstOpen)?.let { rec -> if (stage.records[rec.key] === rec) stage.remove(rec); stageRecount() }
    }

    /** Row [row] painted again: [write] false — the repaint after its key changed or its round-robin turn (it marks the row
     *  owed or current); true — its re-stage, painted at the write so what goes is what Main shows now. */
    private suspend fun ListBind.listBindRow(st: LinkState, ms: ListStaging, row: Int, write: Boolean, now: Long): Boolean {
        val ct = cachedText
        val want0 = ct?.wantVersion; val seen0 = ct?.seenVersion
        val view = surface.view() ?: return false
        val r = try { ms.restage(row, view, kit, listBindRelay()) }
            catch (e: wm.damage.core.geom.LintError) { listBindDrop("$who's row $row cannot be painted again: ${e.message}"); return true }
        if (ct != null && (ct.wantVersion != want0 || ct.seenVersion != seen0)) {
            // the atlas first: the row stays marked, and is painted again once the atlas holds what it drew
            post(Msg.Pump)
            return true
        }
        when (r) {
            ListStaging.Restage.Same -> { listBind.sameWhileWithdrawn(row); post(Msg.Pump) }
            is ListStaging.Restage.Outgrown -> listBindDrop("a full staging is owed (a new mark): ${r.why}")
            is ListStaging.Restage.Changed -> if (!write) { listBind.changed(row); post(Msg.Pump) }
            // a strip write needs the list withdrawn (decisions Z; at every cache size since K1-2 stage 1, BC O1)
            else if (r.strip.isNotEmpty() && listBind.withdrawnFor != row) {
                // the strip is named only inside the pan programs: its segments are written with no entry live (X.2)
                listBind.withdraw(row, listBindPumps)
                surface.offered = null
                note("withdrawn — row $row's row form changed: its strip segment(s) go once the entries are cleared, and the list is offered again after them")
                post(Msg.Pump)
            } else {
                val key = ListStageKey(ms, ListBinding.PATCH_KEY + row, "row $row's re-stage", "$who's")
                val bytes = ByteArray(r.bytes).also { out -> var o = 0; for (w in r.writes) { w.bytes.copyInto(out, o); o += w.bytes.size } }
                val rec = StagedRecord(key, Gray8(1, 1), bytes).also { it.off = r.writes.first().off }
                stage.records[key] = rec
                // build SPLIT: the writes go in pieces (no piece while a burst is open: an instance's read ranges cover the
                // strip and the spare band slot, §5.6); the books take the re-stage — the staging's pictures, the
                // list that names it, the patch — only when nothing unwritten remains: at the item word's submission (it
                // goes alone, after every other piece was acknowledged), else at the last piece's ack
                var pieces = 0
                stageSubmit(rec, st, r.writes.map { it.off to it.bytes }, namesLast = r.band != null, mayGo = { !listBindBurstOpen() && !listMotionMayDraw() }) {
                    if (listBind.staging !== ms) return@stageSubmit       // the staging went meanwhile (its drop said so)
                    listBind.submitted(r, rec, System.currentTimeMillis())
                    stageRecount()
                    note("row $row re-staged — ${r.bytes} B in $pieces piece(s) (${r.writes.joinToString { "${it.what} ${it.bytes.size} B" }})" +
                        (r.band?.let { m -> "; its band form moved to off4 ${m.off / 4}, the item table switched by one write after the rest was acknowledged, the old slot free once acknowledged" } ?: "") +
                        "; mark kept; ${listBind.restageCount} re-stage(s), ${listBind.restagedBytes} B this process")
                    post(Msg.Pump)
                }
                pieces = stageCursor?.takeIf { it.rec === rec }?.pieces?.size ?: 0
                if (rec.state != StagedRecord.State.SENT) { listBind.notWritten(row); return true }   // not submitted: said there, the row stays owed
            }
        }
        return true
    }

    // ------------------------------------------------------ C3 DW-1a: the wheel's block resident in Main's region
    /**
     * The switcher's wheel's pictures and programs resident in Main's region at ladder stage 5 (`C3-DW1-amendment.md`
     * §3.2–§3.4; decisions BD's O5, CK): its first tenant from the region's end, laid out by `Wheel.block` with a cell per
     * registered window and the item table in the switcher's order (CK A1), staged ahead in idle in the lanes' order (CK A3:
     * Main's, the notice's, then this, then Page staging) and kept current in place while the wheel is closed (§3.4; CK A2:
     * the programs re-made in place at another height). Main wins an overlap. DW-1b-1 binds it as glasses list 2 while the
     * switcher is open ([wheelBind]): the lane then writes nothing; open and not bound, only what the offer needs (CK A5).
     * The books and the envelope: [WheelResident].
     */
    private val wheel = WheelResident()

    /**
     * C3 DW-1b-1 (`C3-DW1-amendment.md` §3.3, §4.1; decisions CK A8, DA (c) 2, (d) 1): the wheel bound on the glasses as list
     * [WHEEL_LIST] in slot 0 — the rules' surface for it. [offered] is the list the poll offers ([wheelOfferPoll]) while the
     * switcher is open and every offer condition holds; [row] the switcher's cursor; [setRow] the cursor set and the drum
     * stopped there with no phone frames (an adoption, the tap rule's correction); shift 0. The list itself is the block's
     * ([Wheel.Block.boundList]: entries 6 and 7, its tap entry the rules' own number for it, 0), kept by identity while
     * the block is, with a new mark where the item table's order or n changed. Loop-confined; cleared at a session start.
     */
    private inner class WheelBind : wm.damage.core.local.BoundSurface {
        var offered: wm.damage.core.local.BoundList? = null
        private var built: wm.damage.core.local.BoundList? = null
        private var builtFrom: wm.damage.core.motion.Wheel.Block? = null
        private var mark = 0
        override fun bound(): wm.damage.core.local.BoundList? = offered
        override fun row(): Int = if (switcher.open) switcher.cursor else -1
        override fun setRow(row: Int) = switcher.setCursor(row)

        /** List 2 for the placed block [b]: the one built for it, or a new one (a new mark where its item table differs). */
        fun listFor(b: wm.damage.core.motion.Wheel.Block): wm.damage.core.local.BoundList {
            built?.takeIf { builtFrom === b }?.let { return it }
            val was = built
            if (was == null || was.items4 != b.itemsOff / 4 || !was.items!!.contentEquals(b.items4)) mark = (mark + 1) and 0xFF
            var image: ByteArray? = null
            return b.boundList(WHEEL_LIST, ListStaging.SLOT, WHEEL_ENTRIES, mark) { image ?: b.cache(CfwModes.CACHE2_MAX_RIDERS).also { image = it } }
                .also { built = it; builtFrom = b }
        }
    }
    private val wheelBind = WheelBind()

    /** The open the `wheel` note is about: the chord's time (0: none open), when it was bound (−1: not yet), why it was not
     *  offered at its first poll, and its notches — adopted from the glasses and drawn by the phone. */
    private var wheelOpenAtMs = 0L
    private var wheelBoundAtMs = -1L
    private var wheelOpenWhy: String? = null
    private var wheelLocalNotches = 0
    private var wheelPhoneNotches = 0
    /** CK A6: the key of the staging of Main's that waits for the wheel's close (its block met, the wheel bound): not laid out
     *  again for it while the wheel stays bound. */
    private var mainWaitsForWheel: String? = null

    /**
     * Why the wheel is not offered now (null: it is) — the amendment's §3.3, read once a poll: stage 5 (EFFECTS in force), a
     * 256 KiB session with Main's own region, Main's lane's conditions (BIND, the link, awake, DRAW2 and the lease, the count
     * readable); the block placed (Main's staging never over it: [wheelPoll]); n ≥ 3 and the programs' shape; every part the
     * lane last built sent and adopted as the block, its item table this open's order and its programs this open's geometry
     * and timing ([WheelKey]); every record counted. Not a cell's picture (CK A4).
     */
    private fun wheelNotOffered(st: LinkState): String? {
        if (!st.effects) return "below stage 5 (EFFECTS is not in force)"
        WheelResident.sessionRefusal(st.cacheSize, mainList.listBindRegion(st).last + 1)?.let { return it }
        mainList.listBindBlocked(st, front = false)?.let { return it }
        val b = wheel.block ?: return "the block is not placed${wheel.notPlacedSaid?.let { " (${it.removePrefix("not placed — ")})" } ?: " yet"}"
        wm.damage.core.motion.Wheel.refusal(b.geo, switcher.entryCount)?.let { return it }
        val k = wheel.wantFor as? WheelKey
        if (k == null || b !== wheel.want || wheel.sent.size != b.cells.size + 3) return "a write of the block is owed"
        val order = switcher.entriesInOrder.map { e -> wheel.regs.indexOfFirst { it === e.window } }
        if (k.order != order) return "the item table is not this open's order"
        if (k.g != wheelGeo(st).toString() || k.motion != wheelMotion()) return "the programs are not this open's geometry and timing"
        if (wheel.record?.state != StagedRecord.State.PROVEN || wheel.patches.isNotEmpty()) return "a write of the block is not counted yet"
        return null
    }

    /** Each poll, after the block's: the offer ([wheelNotOffered]) while the switcher is open, none while it is closed; and the
     *  open's `wheel` note, said once at its close (bound after how long, or why not; its notches by who drew them). */
    private fun wheelOfferPoll(st: LinkState) {
        val now = System.currentTimeMillis()
        if (switcher.open && wheelOpenAtMs == 0L) { wheelOpenAtMs = now; wheelBoundAtMs = -1L; wheelOpenWhy = null; wheelLocalNotches = 0; wheelPhoneNotches = 0 }
        val why = if (switcher.open) wheelNotOffered(st) else null
        val offer = if (switcher.open && why == null) wheelBind.listFor(wheel.block!!) else null
        if (switcher.open && wheelBoundAtMs < 0L && wheelOpenWhy == null) wheelOpenWhy = why
        if (offer != null && wheelBoundAtMs < 0L) wheelBoundAtMs = now
        wheelBind.offered = offer
        if (!switcher.open && wheelOpenAtMs != 0L) {
            journal.note("wheel", "closed — " + (if (wheelBoundAtMs < 0L) "never bound (${wheelOpenWhy ?: "the offer did not hold"})"
                else if (wheelOpenWhy == null) "bound at the open (${wheelBoundAtMs - wheelOpenAtMs} ms after the chord)"
                else "bound ${wheelBoundAtMs - wheelOpenAtMs} ms after the chord, mid-open (at the open: $wheelOpenWhy)") +
                "; $wheelLocalNotches notch(es) drawn by the glasses, $wheelPhoneNotches by the phone; open ${now - wheelOpenAtMs} ms")
            wheelOpenAtMs = 0L
        }
    }

    /** THE BLOCK'S BASE (decisions CK A10: K1-2 stage 3's window list is laid out from here down): the lowest byte of Main's
     *  region the wheel's block holds this session, else the place reserved for it (C3 DW-1b-2, decisions DF finding 2:
     *  [WheelResident.reserved]), else the region's end. */
    private fun wheelBase(st: LinkState): Int = wheel.baseIn(mainList.listBindRegion(st).last + 1)

    /** C3 DW-1b-2 (decisions DF finding 2, amending CK A3's order of lanes): a window's list in front whose lane has its
     *  staging still to lay out, write or count — the wheel's block waits behind it, its place reserved above it. Not
     *  under an open switcher (the window list's lane writes nothing then, decisions DA (c) 4, and the wheel's lane writes
     *  what the offer needs, CK A5), nor for a list refused for space, a list covered or adjusting, or no list in front. */
    private fun windowListFirst(): Boolean {
        if (switcher.open || !windowList.inFront()) return false
        val b = windowList.listBind
        val ms = b.staging
        if (ms == null) return windowList.listBindRefusedFor == null
        return !(b.counted && windowList.stagedFront() != null)
    }

    /** The disparity the wheel would open with next — [switcherMargin]'s; behind the shell's clock Main's, which its wake
     *  brings; on an exclusive screen (where the chord never arms) its window's, which the double tap returns to (V1-DW1a F3). */
    private fun wheelOpenD(st: LinkState): Int = if (!st.effects) 0 else if (settings.depth != 0) settings.depth
        else (if (mode == Mode.WINDOW || mode == Mode.EXCLUSIVE) current?.let { appDepth(it) } else null) ?: settings.depth

    /** What one cell would be painted for now (no paint). */
    private fun wheelCellKey(w: DamageWindow?): WheelResident.CellKey {
        val e = switcher.entryFor(w)
        val names = wm.damage.core.gfx.IconNames.forKind(e.icon)
        return WheelResident.CellKey(w, e.name, e.icon, e.dirty, wm.damage.core.gfx.IconPaint.answer(iconSource, names, 56),
            wm.damage.core.gfx.IconPaint.answer(iconSource, names, 20))
    }

    /** What the block is for now (the amendment's §3.4 keys, read without a paint): the registered windows (Main first), the
     *  layout and geometry the wheel would open at, the cells' keys, the item table's order, the motion, the region's end;
     *  [key] all of them. */
    private class WheelWant(val regs: List<DamageWindow?>, val lay: Layout, val g: wm.damage.core.motion.Wheel.Geo, val keys: List<WheelResident.CellKey>,
                            val order: IntArray, val motion: WheelMotion, val regionEnd: Int) {
        val face: String get() = "${g.w} x ${g.hC} + ${g.hN}"
        val key: WheelKey get() = WheelKey(keys, order.toList(), g.toString(), motion, regionEnd)
        /** C3 DW-1b-1 (CK A4, A5): the same want with the cells' keys [k] — an open keeps a cell painted for an older key. */
        fun withKeys(k: List<WheelResident.CellKey>) = WheelWant(regs, lay, g, k, order, motion, regionEnd)
    }

    /** What a block is built for ([WheelWant.key]): its cells' keys, its item table's order, its programs' geometry and
     *  motion, the region's end. The offer reads the order and the programs' part ([wheelNotOffered]). */
    private data class WheelKey(val cells: List<WheelResident.CellKey>, val order: List<Int>, val g: String, val motion: WheelMotion, val regionEnd: Int)

    /** The wheel's programs' timing: the Scroll tier's length and frames, and `Switcher` = None ([none]: the one-step
     *  programs) — C3 DW-1b-2: a change to or from None is a change of the programs' key, so the offer fails until they are
     *  rewritten ([wheelNotOffered]) and the lane rewrites them in place ([pumpWheel]). */
    private data class WheelMotion(val scroll: ListStaging.ScrollMotion, val none: Boolean)

    private fun wheelWant(st: LinkState): WheelWant {
        val regs = listOf<DamageWindow?>(null) + windows
        val lay = wheelLayout()
        // `openSwitcher`'s order: Main, the current window in a window, the rest by recency
        val order = switcher.order(if (mode == Mode.WINDOW) current else null, recency).map { w -> regs.indexOfFirst { it === w } }.toIntArray()
        return WheelWant(regs, lay, wm.damage.core.motion.Wheel.geo(switcher, lay, wheelOpenD(st)), regs.map { wheelCellKey(it) }, order,
            wheelMotion(), mainList.listBindRegion(st).last + 1)
    }

    /** The layout, geometry and motion the wheel would open with now (its programs' key, [WheelKey]). */
    private fun wheelLayout(): Layout = if (mode == Mode.SILENT) effectiveLayout() else layout
    private fun wheelGeo(st: LinkState) = wm.damage.core.motion.Wheel.geo(switcher, wheelLayout(), wheelOpenD(st))
    private fun wheelMotion() = WheelMotion(ListStaging.ScrollMotion(settings.scrollLength, settings.frames), settings.switcher == "none")

    /** Each pump, after Main's poll: the block's records against the registry, the count, and Main's staging against the
     *  block's bytes (Main wins, decisions BD); then the offer (DW-1b-1). */
    private fun wheelPoll(st: LinkState) {
        wheelPollBlock()
        wheelOfferPoll(st)
    }

    private fun wheelPollBlock() {
        val rec = wheel.record ?: return
        val b = wheel.block ?: return
        if (stage.records[rec.key] !== rec) { wheelDrop("its write left the stage registry (the stage notes say why)", keepCells = false); return }
        for (p in wheel.patches.toList()) {
            val r = p.second
            if (stage.records[r.key] !== r) {
                // what that record's bytes on the glasses hold is not known: it is written again in place
                wheel.patches.remove(p); wheel.sent.remove(p.first)
                journal.note("wheel", "the ${p.first}'s write in place left the stage registry before it was counted (the stage notes say why) — written again")
            } else if (r.state == StagedRecord.State.PROVEN) { wheel.patches.remove(p); stage.remove(r); stageRecount() }
        }
        if (rec.state == StagedRecord.State.PROVEN && !wheel.countedSaid) {
            wheel.countedSaid = true
            journal.note("wheel", "counted — the block's ${rec.encoded.size} B at ${b.base}..${b.end} proven on the glasses (field 17)")
        }
        val ms = mainList.listBind.staging
        if (ms != null && ms.end > b.base)
            wheelDrop("Main's staging ends at ${ms.end}, past the block's base ${b.base} (Main wins)", keepCells = true)
    }

    /** The block leaves the registry and the books ([WheelResident.drop]); a piece of its write still to go is abandoned by
     *  the stage lane (its record is no longer owned). */
    private fun wheelDrop(why: String, keepCells: Boolean) {
        for (r in wheel.drop(keepCells)) if (stage.records[r.key] === r) stage.remove(r)
        stageRecount()
        journal.note("wheel", "dropped — $why; placed again in idle")
        post(Msg.Pump)
    }

    /** Not placed for [why] (said once per change of it); [refusedFor]: the want's key it is not tried again for. */
    private fun wheelNotPlaced(why: String, refusedFor: Any?) {
        wheel.refusedFor = refusedFor
        val note = "not placed — $why; the switcher's wheel stays as today"
        if (note != wheel.notPlacedSaid) { wheel.notPlacedSaid = note; journal.note("wheel", note) }
    }

    /** The idle chain's step for the wheel's block (after Main's lane and the notice's, before Page staging — CK A3): one
     *  cell painted, the block placed with its write begun, or one record rewritten in place. True when it did something. */
    private suspend fun pumpWheel(st: LinkState): Boolean {
        // DW-1b-1: nothing of the block goes while the wheel is bound (the open's entries are a snapshot, the amendment's §3.4);
        // open and not bound, only what the offer needs (CK A5, below); below stage 5 no version-2 record is written, and a
        // block already counted stays (§5)
        if (!st.effects || wheelBind.offered != null || transition != null) return false
        if (mainList.listBindBlocked(st, front = false) != null) return false
        if (mainList.listBindRegion(st).last + 1 >= st.cacheSize) return false     // no region of Main's own (160 KiB; an atlas over it)
        if (cacheWritesHeld()) return false
        if (atlasInFlight != null || atlasQueue.isNotEmpty() || atlasLapsedOwed || atlasCheckInFlight) return false
        if (stageInFlight != null || stageCursor != null || ledgerReadInFlight) return false
        if (st.inFlight != 0 || comp.hasPending || comp.needsKeyframe) return false
        if (ledgerBaseOwed(st)) return tryLedgerRead(st)
        if (atlasResendMissing > 0L) return false
        // CK A3, Main's staging first where Main stages — the atlas, then its records, counted (P9): Main's lane runs before
        // this one, shares every gate above and does something in every pump while it owes a write (a row painted, the
        // staging laid out, a base record written); and while a base record of Main's is written and not yet counted this
        // lane holds (V1-DW1a F1: in the pace after a pending reading the block's pieces went first, and the next reading
        // waited for all of them). A record Main cannot write (a window in front) holds nothing.
        if (mainList.listBind.records.any { it.state == StagedRecord.State.SENT || it.state == StagedRecord.State.ACKED }) return false
        val w0 = wheelWant(st)
        if (w0.face != wheel.face || w0.regs != wheel.regs) {
            if (wheel.block != null) { wheelDrop(if (w0.face != wheel.face) "the cells' face changed (${wheel.face} → ${w0.face})" else "the registered windows changed", keepCells = false); return true }
            wheel.painted.clear(); wheel.paintedAgain = null; wheel.face = w0.face; wheel.regs = w0.regs
        }
        // DW-1b-1 (CK A4, A5): with the switcher open and the wheel not bound the lane writes what the offer needs — the block
        // when none is placed, the item table for this open's order, the programs for its geometry — and a placed block keeps
        // the cells it was last built with (a cell merely older than the truth is written after the close)
        val built = (wheel.wantFor as? WheelKey)?.takeIf { switcher.open && wheel.block != null && wheel.want != null && it.cells.size == w0.keys.size }
        val w = if (built != null) w0.withKeys(built.cells) else w0
        if (wheel.refusedFor == w.key) return false
        // one cell painted a pump (AF.8's pace), through the shell's cached-text relay, the atlas first when it drew
        // characters the atlas does not hold (§73's rule, as Main's staging)
        if (built == null) w.keys.indices.firstOrNull { wheel.painted[it]?.first != w.keys[it] }?.let { i -> return wheelPaint(i, w) }
        val cells = if (built != null) wheel.want!!.cells else w.keys.indices.map { wheel.painted.getValue(it).second }
        val fx = effectsToken(st) ?: return false
        val b = wheel.block ?: return wheelPlace(st, w, cells, fx)
        // current: nothing changed since the last pump that found every record as sent (a lost write takes its record out
        // of [WheelResident.sent], so the comparison runs again)
        if (wheel.wantFor == w.key && b === wheel.want && wheel.sent.size == b.cells.size + 3) return false
        // kept current in place (§3.4): the block for the keys now on the placed block's offsets, record by record
        val want = if (wheel.wantFor == w.key) wheel.want else try { b.rebuilt(w.g, cells, w.order, fx, w.motion.scroll, w.motion.none) }
            catch (e: wm.damage.core.geom.LintError) { wheelDrop("it cannot be kept current: ${e.message}", keepCells = true); return true }
        wheel.wantFor = w.key; wheel.want = want
        if (want == null) {
            val why = cells.indices.firstOrNull { (cells[it].encoded.size + 3) / 4 * 4 > b.cellSlots[it] }
                ?.let { "the cell of '${w.keys[it].name}' (${cells[it].encoded.size} B) passes its slot of ${b.cellSlots[it]} B" }
                ?: "the programs at ${w.g.panel} d ${w.g.d} pass their slot of ${b.progSlot} B"
            wheelDrop("re-placed: $why", keepCells = true)
            return true
        }
        // every part sent: the block is what was built (with the switcher open the poll may offer it now: asked back)
        val part = want.parts().firstOrNull { !it.same(wheel.sent[it.name]) } ?: run { wheel.block = want; if (switcher.open) post(Msg.Pump); return false }
        val rec = StagedRecord(WheelStageKey(wheel, wheel.serial++, part.name), Gray8(1, 1), concat(part.writes)).also { it.off = part.writes.first().first }
        stage.records[rec.key] = rec
        wheel.patches += part.name to rec
        wheel.sent[part.name] = part
        wheel.kept++; wheel.keptBytes += part.bytes
        stageSubmit(rec, st, part.writes)
        stageRecount()
        val what = when {
            part.name == wm.damage.core.motion.Wheel.Block.ITEMS -> "the item table (${want.n} of ${cells.size} entries: ${w.order.joinToString(" ") { w.keys[it].name }})"
            part.name == wm.damage.core.motion.Wheel.Block.PROGRAMS -> "the programs (P ${want.period} T ${want.t}${if (want.none) ", Switcher None" else ""} at ${w.g.panel} d ${w.g.d})"
            part.name.startsWith("cell ") -> "the ${part.name} (${w.keys[part.name.removePrefix("cell ").toInt()]})"
            else -> "the ${part.name}"
        }
        journal.note("wheel", "kept — $what rewritten in place: ${part.bytes} B at ${part.writes.first().first}; ${wheel.kept} in-place write(s), ${wheel.keptBytes} B this process")
        return true
    }

    /** Cell [i] painted for [w]'s key (the switcher's own painters at the rest band, `Wheel.cell`). */
    private fun wheelPaint(i: Int, w: WheelWant): Boolean {
        val ct = cachedText
        val v0 = ct?.wantVersion to ct?.seenVersion
        val cell = try { wm.damage.core.motion.Wheel.cell(switcher, w.lay, w.g, switcher.entryFor(w.regs[i]), listBindRelay()) }
            catch (e: wm.damage.core.geom.LintError) { wheelNotPlaced("the cell of '${w.keys[i].name}' cannot be painted: ${e.message}", w.key); return false }
        if (ct != null && (ct.wantVersion to ct.seenVersion) != v0 && wheel.paintedAgain != w.keys[i]) {
            wheel.paintedAgain = w.keys[i]          // the atlas goes first (its lane on the next pump), then this cell once more
            post(Msg.Pump)
            return true
        }
        wheel.painted[i] = w.keys[i] to cell
        post(Msg.Pump)
        return true
    }

    /** The block laid out as the first tenant from the region's end and its write begun — unless past the cap or met by
     *  Main's staging (said, and tried again when the want or Main's staging changes). */
    private suspend fun wheelPlace(st: LinkState, w: WheelWant, cells: List<wm.damage.core.motion.Wheel.Cell>, fx: wm.damage.core.motion.Motion.Effects): Boolean {
        val size = wheel.sized?.takeIf { it.first == w.key }?.second ?: try {
            wm.damage.core.motion.Wheel.block(0, w.g, cells, fx, w.motion.scroll, w.motion.none, w.order).size.also { wheel.sized = w.key to it }
        } catch (e: wm.damage.core.geom.LintError) { wheelNotPlaced("${e.message}", w.key); return false }
        val ms = mainList.listBind.staging
        WheelResident.refusal(w.regionEnd, size, ms?.end)?.let { why ->
            // past the cap: not tried again until the want changes; met by Main: tried again as Main's staging changes
            wheel.reserved = null
            wheelNotPlaced(why, if (size > WheelResident.CAP) w.key else null)
            return false
        }
        // C3 DW-1b-2 (decisions DF finding 2): the block's place reserved by its size; a window's list in front still to be
        // staged goes first, laid out under that place ([wheelBase]), and the block's write follows its count. C3 DW-2
        // (decisions DM 3): so does a document's first strip, which lies in the stage reserve above Main's region
        wheel.reserved = size
        if (windowListFirst() || pageStagingFirst(st)) return false
        val base = WheelResident.baseFor(w.regionEnd, size)
        // K1-2 stage 3 (decisions BD O5, CV): a window's list staged where the block goes yields — its staging drops (its
        // entries are CLEARed in the flush that follows, before any write of the block's: the idle step never runs in a
        // pump that flushes) and it is staged again below the block; the block is placed in a later pump
        windowList.listBind.staging?.takeIf { it.end > base && it.start < w.regionEnd }?.let { l ->
            windowList.listBindDrop("the wheel's block goes at $base..${w.regionEnd}, over its records at ${l.start}..${l.end}: the list yields, and is staged again below the block")
            return true
        }
        val b = wm.damage.core.motion.Wheel.block(base, w.g, cells, fx, w.motion.scroll, w.motion.none, w.order)
        val parts = b.parts()
        val writes = parts.flatMap { it.writes }
        val rec = StagedRecord(WheelStageKey(wheel, wheel.serial++, "block"), Gray8(1, 1), concat(writes)).also { it.off = base }
        wheel.block = b; wheel.record = rec; wheel.wantFor = w.key; wheel.want = b
        for (p in parts) wheel.sent[p.name] = p
        wheel.notPlacedSaid = null; wheel.refusedFor = null
        wheel.placements++; wheel.placedBytes += rec.encoded.size
        stage.records[rec.key] = rec
        stageSubmit(rec, st, writes)
        stageRecount()
        val from = ms?.end ?: (mainList.listBindRegion(st).first)
        journal.note("wheel", "placed — ${b.describe()}; at $base..${b.end} of Main's region (its end ${w.regionEnd}): a cell per registered window, " +
            "the item table ${w.order.joinToString(" ") { w.keys[it].name }}; ${if (ms != null) "Main's staging ends at ${ms.end}" else "no staging of Main's"}, " +
            "${base - from} B free below the block; ${rec.encoded.size} B written in ${stageCursor?.takeIf { it.rec === rec }?.pieces?.size ?: 1} piece(s); " +
            "${wheel.placements} placement(s), ${wheel.placedBytes} B this process")
        return true
    }

    private fun concat(writes: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArray(writes.sumOf { it.second.size })
        var o = 0
        for ((_, b) in writes) { b.copyInto(out, o); o += b.size }
        return out
    }

    /** C3 DW-1a, tests (on the loop): the block placed (null: none), its records in the registry, and whether its lane has
     *  nothing left to write — no block wanted here (below stage 5, no region of Main's own), or the block placed for the
     *  keys as they are, counted, with no write in place on its way and none owed. */
    internal fun wheelBlockForTest(): wm.damage.core.motion.Wheel.Block? = wheel.block
    internal fun wheelRecordsForTest(): List<StagedRecord> = stage.records.values.filter { it.key is WheelStageKey }
    internal fun wheelBaseForTest(): Int = wheelBase(transport.state.value)
    /** C3 DW-1a, tests (on the loop): Page staging's three drops as a book change (`Staging.retain` for another layout),
     *  an Encode act's lists over the whole cache ([stagePagesDropUnder]) and its own clear ([stagePagesClear]) make them. */
    internal fun pageStagingDropsForTest() {
        if (stage.retain(Any(), 0, 0).isNotEmpty()) stageRecount()
        stagePagesDropUnder(0 until transport.state.value.cacheSize)
        stagePagesClear("a test")
    }
    internal fun wheelLaneDoneForTest(): Boolean {
        val st = transport.state.value
        if (!st.effects || !st.bind || mainList.listBindRegion(st).last + 1 >= st.cacheSize) return true
        val b = wheel.block ?: return wheel.notPlacedSaid != null
        val want = wheel.want ?: return false
        return wheel.record?.state == StagedRecord.State.PROVEN && wheel.patches.isEmpty() && wheel.wantFor == wheelWant(st).key &&
            want.parts().all { it.same(wheel.sent[it.name]) } && b === want
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

    /** The one way out of the sleep (§37.0): the transport ends the session (keeping its radio link where it can,
     *  stage BW, unless [endLink]) and reports it, the keeper stops this shell (state saved) and starts it
     *  again, and the new start adopts the glasses' state — asleep or
     *  painting — from the session's READ. A transport that cannot (nothing
     *  started, or a seam whose far end did not take it) leaves a loud status
     *  and the next tick asks again; the manual recovery is the host's
     *  (Target → SIM → glasses). */
    private fun requestRebuild(why: String, endLink: Boolean = false) {
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
            val ok = try { transport.restartSession(why, endLink) } catch (e: Exception) { Log.e("shell", "session restart", e); false }
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
                // build C1 (spec §6.5): a motion instance's areas, from its PLAY to the later of RIGHT's notify and its
                // nominal end, are the lens's own to draw — not compared (none: every pixel, as before)
                // ...nor the cells the compositor holds behind an adopted motion's end (G1 item 5: a rolled-back sub's
                // pixels sit in belief there, unknown and unsent, until the release repaints them from the truth)
                val skip = m.unknownAreas(arm) + comp.heldRects(arm == Arm.LEFT)
                val first = firstDisagreement(belief, panel, stride, lo, hi, skip) ?: return@readPanel null
                if (report != null) return@readPanel ""
                val diffs = countDisagreements(belief, panel, stride, lo, hi, skip)
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
        comp.requestKeyframe("the mirror disagreed with belief")
    }

    private fun nibble(panel: ByteArray, stride: Int, x: Int, y: Int): Int {
        val b = panel[y * stride + (x shr 1)].toInt() and 0xFF
        return if (x and 1 == 0) b shr 4 else b and 0x0F
    }

    /** The first pixel whose quantised belief differs from the panel, or null
     *  — a cheap early-out scan (a lookup table replaces the per-pixel divide). */
    private fun firstDisagreement(belief: Gray8, panel: ByteArray, stride: Int, lo: Int, hi: Int,
                                  skip: List<Rect> = emptyList()): Pair<Int, Int>? {
        val w = comp.width
        if (skip.isNotEmpty()) {
            // build C2 (C2-decisions F, stage DP1): under an open motion the skipped areas are taken out of each row as
            // spans, once per row, and the rest of the row is compared straight — the per-pixel area test made this scan
            // about ten times the cost of the plain one on every FlushDone while a motion was open (365 ms seen on his
            // phone, 2026-11-18 16:21:12)
            for (y in lo..hi) {
                val n = openSpans(skip, y, w)
                val row = y * stride
                for (k in 0 until n) {
                    val x1 = spanBuf[2 * k + 1]
                    var x = spanBuf[2 * k]
                    while (x < x1) {
                        val b = panel[row + (x shr 1)].toInt() and 0xFF
                        if (LEVEL[belief[x, y]] != (if (x and 1 == 0) b shr 4 else b and 0x0F)) return x to y
                        x++
                    }
                }
            }
            return null
        }
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

    /** Build C2 (stage DP1): the x-spans of row [y] in [0, w) that no rect of [skip] covers, merged and in order, into
     *  [spanBuf] as (start, end) pairs; the count of spans. Loop thread only (the agreement check's own buffer). */
    private fun openSpans(skip: List<Rect>, y: Int, w: Int): Int {
        var m = 0
        for (r in skip) {
            if (y < r.y || y >= r.bottom) continue
            val s = maxOf(0, r.x); val e = minOf(w, r.right)
            if (s >= e) continue
            // insertion by start: a handful of areas (four slots, eight tracks each at most)
            var i = m++
            if (2 * m > coverBuf.size) coverBuf = coverBuf.copyOf(4 * m)
            while (i > 0 && coverBuf[2 * (i - 1)] > s) { coverBuf[2 * i] = coverBuf[2 * (i - 1)]; coverBuf[2 * i + 1] = coverBuf[2 * i - 1]; i-- }
            coverBuf[2 * i] = s; coverBuf[2 * i + 1] = e
        }
        var n = 0
        var at = 0
        fun open(s: Int, e: Int) {
            if (2 * (n + 1) > spanBuf.size) spanBuf = spanBuf.copyOf(4 * (n + 1))
            spanBuf[2 * n] = s; spanBuf[2 * n + 1] = e; n++
        }
        for (i in 0 until m) {
            val s = coverBuf[2 * i]; val e = coverBuf[2 * i + 1]
            if (s > at) open(at, s)
            if (e > at) at = e
        }
        if (at < w) open(at, w)
        return n
    }
    private var spanBuf = IntArray(16)
    private var coverBuf = IntArray(16)

    private fun countDisagreements(belief: Gray8, panel: ByteArray, stride: Int, lo: Int, hi: Int,
                                   skip: List<Rect> = emptyList()): Int {
        var diffs = 0
        val w = comp.width
        for (y in lo..hi) {
            if (skip.isEmpty()) {
                for (x in 0 until w) if (LEVEL[belief[x, y]] != nibble(panel, stride, x, y)) diffs++
                continue
            }
            val n = openSpans(skip, y, w)                     // build C2 (stage DP1): as [firstDisagreement]
            for (k in 0 until n) for (x in spanBuf[2 * k] until spanBuf[2 * k + 1])
                if (LEVEL[belief[x, y]] != nibble(panel, stride, x, y)) diffs++
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
        comp.requestReseed("the height changed")     // a height change re-lays out the whole shell (§4.2); v2: a fill + draws
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
     *  repaints whole here. A settings change from the record can change typography AND height in one apply
     *  (R3s#6): the layout is re-derived BEFORE the full repaint. */
    private fun relayoutEverything() {
        refreshStyles()
        for (w in windows) w.onFontScaleChanged(effScale(w.id))
        if (!syncLayout()) {
            for (w in windows) w.onLayoutChanged()
            comp.composed.clear(0)
            composeFullSurface()
            comp.requestReseed("the font or the style changed")
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
     * [ShellSettings], an item of Settings' record, so the PC holds it too (`Notify · alerts`, `Notify · cleared`).
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
            // K1-2 stage 3c (the K1-2 spec §4; decisions BC O8, CV; MOTION.md §7: one Scroll effect per app, in its own
            // category): what this app's list plays on the glasses — "default" is `Animations`' choice, and where that is
            // Crush or Flip the row says what a window's list plays instead, as Main's row says its fallback. Shown only at
            // ladder stage 5 ([settingsApps]): below it no window list is bound, so the row would do nothing
            HostSetting(APP_SCROLL_ROW, { appScrollOptions() },
                { settings.appStyle(id).scrollEffect.let { v -> if (v == "default") appScrollDefault() else wm.damage.core.motion.ScrollEffect.byId(v)?.word ?: appScrollDefault() } },
                { v -> applySettings(settings.withAppStyle(id) { s ->
                    s.copy(scrollEffect = wm.damage.core.motion.ScrollEffects.WINDOW_EFFECTS.firstOrNull { it.word == v }?.id ?: "default") }) }),
        )
    }

    /** K1-2 stage 3c: the per-app `Scroll effect` row's name, its options (the "default" option first, then what a window's
     *  list plays itself) and its "default" option's words — "default", or "default (plays Slide)" where `Animations`' Crush
     *  or Flip plays its fallback on a window's list (decisions CV). */
    private fun appScrollOptions(): List<String> = listOf(appScrollDefault()) + wm.damage.core.motion.ScrollEffects.WINDOW_EFFECTS.map { it.word }
    private fun appScrollDefault(): String {
        val g = wm.damage.core.motion.ScrollEffect.byId(settings.scrollEffect) ?: wm.damage.core.motion.ScrollEffect.DEFAULT
        val w = wm.damage.core.motion.ScrollEffects.playsFor(g, wm.damage.core.motion.ListForm.STRIP_WINDOWS, null).words(g)
        return if (w.isEmpty()) "default" else "default ($w)"
    }

    /**
     * The one door every settings change goes through. [input] = an input on this device: with a [settingsSource] its
     * changed items go to the record as ops after the field is set (the first flush never waits); false = the record's
     * own change ([settingsFromRecord]), which sends nothing back. Either way the local copy is written.
     */
    private fun applySettings(s: ShellSettings, input: Boolean = true) {
        val old = settings
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
        val retaps = s.taps != settings.taps
        settings = s
        if (input) settingsSource?.let { src -> sendSettings(src, old, s) }
        if (retaps) { local.taps(s.taps); post(Msg.Pump) }     // build C2 (§6.7 Switching): the tap entry's SET or CLEAR only
        if (restage && s.pageStaging != "on") stagePagesClear("the Page staging row is off")     // AU.2: not Main's region
        if (rebright) transport.setBrightness(s.brightnessAuto, s.brightness)
        if (relink) transport.setLinkPriority(s.linkPriority)
        if (recache) {
            // §73: the row decides the layout metric as well as the upload — set first, so the
            // re-derive below (every wrap, the chrome's measured cells) measures by the new one
            cachedText?.cacheMetric = s.cachedText == "on"
            clockWKey = null; statusWKey = null
            if (s.cachedText == "on") atlasEnable(rederive = false) else atlasDisable("the setting is off")
        }
        persistence.put("shell.settings", s.toJson())
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
            // (a silent-clock size from the record, §1.5) — paintAll clears the
            // whole panel, so the old box never lingers
            if (reclock) composeFullSurface()
        } else if (!relayout || !syncLayout()) {
            updatePlanes()
            if (redepth) composeFullSurface() else composeContent()
        }
        scheduleSave()
    }

    /** S1-5 S: one `set` per item [new] changes from [old], stamped with the input's time; each value kept until it
     *  comes back ([settingsEcho]). */
    private fun sendSettings(src: wm.damage.core.app.WindowSource, old: ShellSettings, new: ShellSettings) {
        val at = src.now()
        for (d in SettingsCore.diff(old, new, at)) {
            settingsInflight.getOrPut(d.targets.single()) { ArrayDeque() }.addLast(d.result)
            src.act(d)
        }
    }

    /** S1-5 S (§4.2's (3)), on the loop: every record item that is not this shell's own write coming back and differs
     *  from what is shown is applied — the local copy written, no op sent back. Nothing until the source has heard
     *  Settings: the local copy shows meanwhile. */
    private fun settingsFromRecord() {
        val src = settingsSource ?: return
        if (!src.heard()) return
        val next = recordSettingsOver(settings, src.state())
        if (next == settings) return
        settingsFromRecordCount++
        Log.i("settings", "settings changed on the record")
        applySettings(next, input = false)
    }

    /** [shown] with every item of [st] that moved: absent or removed reads as the default; an echo of this shell's own
     *  write is skipped. */
    private fun recordSettingsOver(shown: ShellSettings, st: wm.damage.core.app.WindowState): ShellSettings {
        val cur = SettingsCore.encode(shown)
        val prefix = wm.damage.core.app.Record.prefix(SettingsCore.WINDOW)
        val names = LinkedHashSet(cur.keys)
        for (k in st.items.keys) if (k.startsWith(prefix)) names += k.removePrefix(prefix)
        val moved = HashMap<String, kotlinx.serialization.json.JsonElement?>()
        for (name in names) {
            if (!SettingsCore.known(name)) continue
            val v = SettingsCore.valueIn(st, name)
            if (v == null || settingsEcho(name, v)) continue
            if (v != (cur[name] ?: SettingsCore.defaultOf(name))) moved[name] = v
        }
        return SettingsCore.with(shown, moved)
    }

    /** True when [v] is this shell's own write of [name] coming back: it and every older write still in flight leave
     *  the list. */
    private fun settingsEcho(name: String, v: kotlinx.serialization.json.JsonElement): Boolean {
        val q = settingsInflight[name] ?: return false
        val i = q.indexOf(v)
        if (i < 0) return false
        repeat(i + 1) { q.removeFirst() }
        if (q.isEmpty()) settingsInflight.remove(name)
        return true
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
            // up to its 5 s early (a silent-clock size from the record, a restyle or the Cached text row
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
        contentComposes++
        slides = emptyList()
        notifications.invalidateUnder()   // the content beneath is repainting
        // while the wheel is open the backdrop belongs to the PREVIEWED entry
        // (§4.3) — an async repaint (an icon resolve, an invalidate) must not
        // swap it back to the current window under a settled preview
        // (review 2026-09-01 F7). Render-only, never an activation.
        paintContentOf(contentWindow())
        if (mode == Mode.MAIN) mainList.mainBindKeys()             // build C2, stage DM2: a row whose key changed is re-staged
        else if (mode == Mode.WINDOW) windowList.composeCompare()  // K1-2 stage 3b: a window list's row drawing otherwise, likewise
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

    /** C3 SC1 (the lead's part CD): the content's compositions this process ([composeContent] past its mode checks) — the
     *  pin that a Settings adjust notch composes the content once reads it. Loop-only. */
    private var contentComposes = 0
    internal fun contentComposesForTest(): Int = contentComposes
    /** Exclusive mode's repaints that changed something ([composeContent] does not run there; stage S's once-a-second pin). */
    private var exclusiveRepaints = 0
    internal fun exclusiveRepaintsForTest(): Int = exclusiveRepaints

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
        noticePlane = null
        // content: the FOCUSED app's own depth when it set one, else the
        // Global row; Main is always the Global row
        val contentD = contentDepth()
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
            // the panel with the neighbours at content depth, the centre band forward (C3 DW-0: at stage 5 the panel's
            // margins and the band as painted, `Switcher.planes`)
            if (switcher.open) planes.addAll(switcher.planes(layout, d, switcherMargin()))
            val n = notifications.current
            if (n != null) {
                val box = notifications.fullRect(n, layout, silent = false)
                val plane = when {
                    n.emergency && notifications.focused -> -4               // crossed: plane +1
                    notifications.focused -> 0
                    else -> d                                                // arrives with content
                }
                planes.add(Compositor.PlaneRegion(box, plane).also { noticePlane = it })
            }
        }
        comp.planes = planes   // a changed map re-renders + seam-cleans itself
    }

    /** The notice box's plane in the map [updatePlanes] last set (null: none) — what Encode's cells leave out (X2T item 5). */
    private var noticePlane: Compositor.PlaneRegion? = null
    /** Tests only (on the loop): the notice box's region in the plane map. */
    internal fun noticePlaneForTest(): Rect? = noticePlane?.rect

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
        frontOfTurn = FrontOfTurn(windowFrontNow())      // K1-2 stage 4i: the turn's front, once ([windowFront])
        try { pumpTurn() } finally { frontOfTurn = null }
    }

    private suspend fun pumpTurn() {
        // C3 X4T item 4: the first pump after a glyph hold's period is the one that lets its writes go
        if (glyphHoldPumpOwed && System.currentTimeMillis() >= glyphHoldUntilMs) glyphHoldPumpOwed = false
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
        // the `hold=on` dev probe: a hand-played test program's picture stays on the lenses until the hold ends
        if (testHold) return
        if (pumpHeldForTest) return             // tests only: a link with no room at all (a transition stays owed)
        // a cache write that was not this shell's landed in the live cache mid-session (the self-test's
        // `live:` writes, 2026-09-15 review): the atlas's bytes on the glasses are no longer what it
        // packed, so nothing draws from them — pixels until the next session or a Cached text toggle
        if (atlas != null && st.cacheWriter.isNotEmpty() && st.cacheWriter != atlasTag && st.cacheWriter != atlasForeignWriter) {
            atlasForeignWriter = st.cacheWriter
            noticeReset()                         // build K1-4a: the notice's programs are cache bytes another writer may have
            lanes.reset("another writer touched the cache")   // stage L: so are the lanes' areas
            journal.note("atlas", "the texture cache was written by '${st.cacheWriter}' mid-session — the atlas is dropped, cached text is pixels until the next session or a Cached text toggle")
            atlasReset()
        }
        // decisions BF (G4): a period the measured steps call for, set with no burst open (before the offer below)
        for (h in lists) h.listPeriodApply()
        // build C2, stage DM2: what Main offers the rules is decided before anything below asks for it — K1-2 stage 3: and
        // what a window's list offers (after Main's: a Main staging laid over it drops it here, before any write of Main's)
        for (h in lists) h.listBindPoll(st)
        wheelPoll(st)                                 // C3 DW-1a: the wheel's block against the registry and Main's staging; DW-1b-1: the offer
        // G4 item 1: the wrap's PLAY goes now, alone and small, ahead of this pump's frame (whose assemble behind the held
        // cells took 100–170 ms on his phone) and whatever the window holds — the transport queues it in stream order
        mainWrapFirst(st, System.currentTimeMillis())
        // One slot is RESERVED for the response to input (2026-09-05,
        // `HANDOFF.md` §32): animation frames, poll-driven repaints and the
        // visualizer may keep at most window-1 flushes in flight, so a
        // gesture's first flush never queues behind three of them (each
        // 150–1,200 ms on the phone's measured curve, §31.1). The pump that
        // follows a ring event may fill the window; every other pump stops one
        // short. An instant transport never sees the difference — nothing
        // stays in flight — and a window of 1 keeps its one slot.
        val cap = if (pumpPriority || yieldReserve) st.window else maxOf(1, st.window - 1)
        val room = st.inFlight < cap && noRoomAfterPollForTest?.invoke() != true
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
                // build K1-4a: the next box of a queue shown at once (no unfurl) starts its timer here
                if (notifications.current !== n0 && !notifications.animating) scheduleGrace()
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
        // build C2 (§6.7): the list in front told to the rules in the pump that draws its change; their tail (answers,
        // table messages, the idle SETTLE) rides this flush — only while BIND is in force and the lease held (P9)
        val frontBefore = localFrontNow
        syncLocalFront()
        val localOn = local.active && st.bind && st.leaseHeld
        if (local.active && !st.bind) local.bindInForce(false)
        val localNow = System.currentTimeMillis()
        // decisions AW.3: a window over the list cuts the adopted motion — the list left the front because another window's
        // frame (which repaints the whole content area: every cell of the slot's areas) is about to go, while a COMMITted
        // instance still draws there. A CLOSE of the slot goes first, alone, in stream order ahead of that frame (§5.6: it
        // presents the shadow, which holds END); the held cells are dropped and the areas made unknown, so the frame
        // repaints them from the truth now instead of after the motion's end. A notice box, the menu, the keyboard and the
        // switcher cover part of the list or keep Main under them: their cells still wait (AF.7). The silent clock repaints
        // every cell as a window does (X2P2: the class of the first review pass's finding 1 — AW.3 left it out). Build C3
        // (DP2): with a transition owed none of this — its out PLAY, the act's first visible image, ends the same instance in
        // its close pass, and `transitionFirst` cuts the held cells there (the review's findings 1 and 3).
        // K1-2 stage 3: the class swept — the list in front changed to ANOTHER list (Main's to a window's kept one, a window's
        // to Main's) is the same case: the arriving screen's frame repaints the whole content area; a window's list leaving
        // for Main too. Below stage 5 only Main's list is ever in front, so this is today's condition there. K1-2 stage 3b
        // (decisions CY, the first review of 3a's finding B): a window's list leaves the front under a cover, the adjust level,
        // a withdrawal or a drop of its own view with the window still in front — those cells wait as Main's do (AF.7); only
        // another window or level in front (or none that is a list) cuts its motion
        if (frontBefore != null && localFrontNow?.list != frontBefore.list &&
            (mode == Mode.WINDOW || mode == Mode.EXCLUSIVE || mode == Mode.SILENT || (mode == Mode.MAIN && frontBefore.list != ListStaging.LIST)) &&
            (mode != Mode.WINDOW || frontBefore.list != windowList.listNo || windowList.screenLeft()) &&
            transition?.phase != TransitionRun.Phase.OWED && st.bind && st.leaseHeld && comp.deferring && comp.deferUntilMs > localNow) {
            submitLocalOnly(listOf(CfwModes.motionClose(frontBefore.slot)), "CLOSE")
            comp.cutDeferral()
            journal.note("local", "${if (mode == Mode.SILENT) "the silent clock" else if (mode == Mode.MAIN) "Main" else "a window"} over the list: slot ${frontBefore.slot}'s adopted motion closed ahead of its frame — its cells go now")
        }
        // build C3, stage DP2 (`FIRMWARE.md` §7.11): a transition's out half goes now, ahead of the arriving frame's assemble —
        // the records' write, then alone the rules' CLEARs, the out PLAY and their answers. Nothing waits behind it: every
        // flush from the out PLAY to the run's end is held by both owners and shows at the in half's next step or at LAND
        transitionFirst(st, localOn, localNow)
        lanesBefore(st)                       // stage L: the held lanes' step — before the frame, whose cells it holds or releases
        // decisions AF.7: the cells held behind a COMMIT go once the adopted motion has ended on the glasses — the loop is
        // asked back then (pacing, never a wait on a send)
        comp.releaseDeferred(localNow)
        if (comp.deferring && comp.deferUntilMs > localNow && comp.deferUntilMs != deferWakeAt) {
            deferWakeAt = comp.deferUntilMs; pumpAfter(comp.deferUntilMs - localNow)
        }
        if (localOn) scheduleLocalSettle()
        if (localOn && local.settleDue(localNow) && !local.owed && !comp.hasPending && !comp.needsKeyframe) {
            submitLocalOnly(local.loneSettle(localRow(), localNow), "SETTLE")       // P7: alone, Q after the burst
        } else if (comp.hasPending || comp.needsKeyframe || (localOn && local.owed) || noticeOwed != null) {
            val noticeProg = noticeResolve()      // fix A1: before the frame is assembled, after the input acted
            comp.placements = stagePlacements()   // §66: what this frame may draw from the reserve
            // build C1's B-N5: room for the transport's CLOSE — asked of the mirror (and its lock) only while ENGINE is
            // in force, the one state in which the transport adds a CLOSE (ruling B-N4); elsewhere, today's firmware
            // included, the assemble path is what it was
            comp.motionOpen = st.flagsInForce and wm.damage.core.wire.DamageMsg.FLAG_ENGINE != 0 && transport.mirror.motionOpen()
            // build C2, stage DI: no delta is merged across the bound list's areas (chrome's ink beside Main's rail thumb)
            comp.keepOut = (if (localOn) local.front?.areas else null) ?: lanes.hold     // stage L: or a held lane's (no list in front)
            val assembleT0 = System.nanoTime()
            // build C2 (P3, P10): each planned COMMIT's END applied to the model before the diff; denied when a drawing
            // sub then meets its slot's areas
            val plans = if (localOn) local.plan(localRow()) else emptyList()
            // decisions AZ.4: an edge at an end of Main's wrapping list — the pan across the wrap, a phone PLAY in a free slot
            // whose END goes into the model as a COMMIT's does, its areas held until it ends
            val wrap = if (localOn) mainWrapPlan(localNow) else null
            assembleCalls++
            var denied: Set<Int> = emptySet()
            val assembled = try {
                if (plans.isEmpty() && wrap == null) comp.assembleFlush(Geometry.rectBudget(st.window))
                else comp.assembleWithEnds(Geometry.rectBudget(st.window), plans.map { c ->
                    wm.damage.core.comp.Compositor.EndPlan(c.slot, c.areas, c.list.paintEnd?.let { pe -> { left: Boolean, g: Gray8 -> pe(left, c.prog4, c.p, g) } }, c.motionEndsMs)
                } + listOfNotNull(wrap?.end), localNow).let { (a, d) -> denied = d; a }
            } catch (e: Exception) {
                Log.e("shell", "flush assembly failed", e)
                journal.note("assemble-error", e.toString())
                raiseFault("assemble", "a flush could not be assembled: ${e.message ?: e::class.simpleName} — keyframing", urgent = true, source = "compositor")
                // a throw mid-assembly can leave shadows seeded with nothing
                // on the wire (R3s#7): a keyframe re-grounds belief — cheap
                // insurance on a path that should never run
                comp.requestKeyframe("a flush could not be assembled")
                null
            }
            // the wrap's PLAY goes ahead of the rules' answers (the DISCARD naming the wrapped row); a keyframe's batch
            // denies it (it closes every instance) and the row is drawn as today
            val wrapPlay = wrap?.takeIf { it.end.slot !in denied }?.also { wrapSent(it) }?.play
            // B083 fix stage (the focused read's 1): no answer waits for the wrap's held cells — a DISCARD held with them let
            // the next notch fire from the wrap's row and be adopted, a notch of an edge burst lost; a tap inside the wait
            // acting on a row the panel does not show yet is the declared residual (his taps come ≥ 696 ms after a scroll
            // message, `FIRMWARE.md` §6.1)
            val localTail = if (localOn) listOfNotNull(wrapPlay) + local.tail(localRow(), localNow, denied, frame = assembled != null, wrapped = wrapPlay != null)
                else emptyList()
            // build K1-4a: the notice's PLAY after every drawing sub (the box's END picture is in the shadow when it is
            // captured) — this frame's, an input's own; never into a batch that leaves part of the frame for the next.
            // (0.83 integration: none is owed while a transition is in hand — the act requeues the box, so
            // [noticeResolve] finds no current box, and a notice arriving under the run waits for its end)
            val noticeTail = noticeTakePlay(noticeProg, leavesPending = (assembled != null && comp.hasPending) || comp.deferring)
            if (assembled == null && localTail.isNotEmpty()) {
                val id = submitLocalOnly(localTail + listOfNotNull(noticeTail), "LOCAL")
                if (noticeTail != null) { if (id != null) noticePlayFlush = id else noticeFallback("its PLAY's flush was not submitted") }
            } else if (assembled == null && noticeTail != null) submitNoticeOnly(noticeTail)
            if (assembled == null) transitionAlone()
            if (assembled != null) {
                val assembleMs = (System.nanoTime() - assembleT0) / 1_000_000
                // the loop stamped loopSince when it took the message this
                // pump follows: everything between is the paints it caused
                val handleMs = System.currentTimeMillis() - loopSince
                val label = "$mode${if (switcher.open) "+switcher" else ""}"
                try {
                    // build C3 (DP2): the arriving frame is held by the out half's owner; the flush that leaves nothing
                    // pending carries the in half's PLAY-AFTER after its drawing subs
                    val tr = transition
                    val playAfter = tr?.takeIf { it.phase == TransitionRun.Phase.FRAME && !transitionHoldInHalfForTest && !assembled.keyframe && !comp.hasPending && !comp.needsKeyframe }?.playAfter
                    val id = transport.submit(FlushRequest(assembled.ops, assembled.epoch, label,
                        wide = assembled.wide, tail = localTail + listOfNotNull(noticeTail) + listOfNotNull(playAfter),
                        held = (tr?.held() ?: emptySet()) + lanes.heldSlots))     // stage L: a held lane's owners stay open
                    if (noticeTail != null) noticePlayFlush = id
                    transitionSubmitted(id, assembled.keyframe, playAfter != null)
                    if (assembled.keyframe) lanes.keyframed()          // stage L: it closed every instance
                    if (localTail.isNotEmpty()) { localTailFlushes += id; for (h in lists) h.listBind.tailSent(id, h.listBindEntriesSent()) }
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
                    if (noticeTail != null) noticeFallback("its PLAY's flush was not submitted (${e.message})")   // fix B4
                } catch (e: Exception) {
                    Log.e("shell", "submit failed", e)
                    journal.note("submit-error", e.toString())
                    raiseFault("submit", "a flush could not be submitted: ${e.message ?: e::class.simpleName}", urgent = true, source = "transport")
                    comp.rollback(assembled)
                    mirrorWhole = true                     // P6 item 5
                    if (noticeTail != null) noticeFallback("its PLAY's flush was not submitted (${e.message})")   // fix B4
                }
            }
        } else {
            // room, and nothing to send: a gesture that met a full window painted nothing after
            // all (or its paint went out with an earlier flush) — the flag is not left standing
            // for whatever flushes next (an idle tick, a push), which no ring event started
            inputAwaitsSlot = false; inputWaitSince = -1L
            transitionAlone()                 // build C3 (DP2): a frame that drew nothing still gets its in half
            // 6. nothing pending: the lowest-priority work — the cache count's reading
            //    (0.64), the atlas's next chunk (§40), then a staged strip (§66), then
            //    the preview settle
            // the wheel's preview over Main before Main's staging step (decisions AL.1): a staging's paint turns are not
            // what the preview waits for
            if (!animated && !pumpLedger(st) && !pumpAtlas(st) && !settlePreview() && !pumpStagePiece(st) && !mainList.pumpLane(st) &&
                !pumpNoticeStage(st) && !pumpLaneStage(st) && !pumpWheel(st) && !pumpWindowList(st)) pumpStage(st)
        }
        lanesAfter()                          // stage L: the chain's PLAY or PLAY-AFTER alone, after the frame

        // animations continue on the next completion or message; make sure one
        // arrives even in instant-transport tests
        if (animated || slides.any { it.active } || switcher.spinning || notifications.animating) {
            post(Msg.Pump)
        }
    }

    // ------------------------------------------------------------------ build C3, stage DP2: transitions
    /** What an act compares before and after: the mode, the window and its list level (Settings' adjust level changes a
     *  row, not the screen). [bottom] is the layout's bottom at the time (not compared). */
    private data class ScreenKey(val mode: Mode, val window: String?, val level: Int) {
        var bottom = 0
        /** C3 X1MX2 item 2 (decisions CB (2)): the screen's content plane at the time — 0 on the silent clock (no plane
         *  map), else the focused app's own Depth or Global ([contentDepth], as [updatePlanes] lays it). Not compared. */
        var plane = 0
        /** C3 X2T item 4: the act's effect, picked before its handler paints ([transitionPick]); null below stage 5 or with
         *  nothing checked. Not compared. */
        var pick: wm.damage.core.motion.Transitions.Effect? = null
        /** The effects that left the pick before it (an Encode this session cannot play), each with its reason. */
        val leftOut = ArrayList<Pair<wm.damage.core.motion.Transitions.Effect, String>>()
        /** C3 DM-T: the screen's cells as it stood before the act (Encode's out half), recorded only when Encode is the
         *  pick; not compared. */
        var cells: wm.damage.core.motion.CellLists.Screen? = null
    }

    /** [tier]: the act this key stands before — its effect is picked now, before the handler paints, and the leaving
     *  screen's cells are recorded only when that pick is Encode (one paint into a scratch, [screenRuns]). C3 X3 (decisions
     *  CD (b)): [record] false for an act known to change no screen (it plays no transition: [transitionAct] finds the same
     *  key after it) — the pick is made as always, so the picks' draws stay in step, and nothing is recorded. */
    private fun screenKey(tier: wm.damage.core.motion.Transitions.Tier? = null, record: Boolean = true): ScreenKey {
        val w = if (mode == Mode.WINDOW) current else null
        return ScreenKey(mode, w?.id, w?.let { if (it === settingsWindow) settingsWindow.listLevel() else it.levelDepth() } ?: 0)
            .also { k ->
                k.bottom = layout.safe.bottom
                k.plane = if (quiet) 0 else contentDepth()
                if (tier != null) transitionPick(tier, k, record)
            }
    }

    /**
     * C3 X2T item 4 (the review's 5, decisions AQ (4)): the act's pick, made before anything is painted — a random pick
     * among the tier's checked effects (`MOTION.md` §7). An Encode this session cannot play ([encodeUnplayable]) leaves the
     * pick with its reason and the pick is made again; when the pick is Encode the leaving screen is recorded, and only
     * then. Nothing at all below stage 5. C3 X4T item 1 (`C3-V2T-review.md` 1, decisions AT (1)): a text run the notice box
     * covers is not one of the leaving screen's cells — the box is on the panel the out half dims, and a cell would draw
     * the hidden text over it for the out half; the box goes dark with the rest of the picture. (The arriving screen keeps
     * its runs under the box: the box is lifted at the act, so the in half shows that text.)
     */
    private fun transitionPick(tier: wm.damage.core.motion.Transitions.Tier, k: ScreenKey, record: Boolean) {
        val st = transport.state.value
        if (st.transitionRegion == null) return                          // below stage 5: no pick, no recording
        val pool = settings.effectsOf(tier).mapNotNull { wm.damage.core.motion.Transitions.Effect.of(it) }.toMutableList()
        while (pool.isNotEmpty()) {
            val e = pool[transitionRandom.nextInt(pool.size)]
            if (e == wm.damage.core.motion.Transitions.Effect.ENCODE) {
                val why = encodeUnplayable(st)
                if (why != null) { pool.remove(e); k.leftOut += e to why; continue }
                // the box's own rect, not its plane: with Global and the window's Depth both 0 there is no plane map
                val box = notifications.current?.let { notifications.fullRect(it, layout, silent = mode == Mode.SILENT) }
                if (record) k.cells = screenCells(screenRuns().filter { box == null || !it.rect.overlaps(box) }, Rect(0, 0, comp.width, layout.safe.bottom))
            }
            k.pick = e
            return
        }
    }

    /** Why this session cannot play Encode now (null: it can): a 256 KiB cache with BIND (the stage reserve holds its
     *  lists, the DM spec's D5), cached text on, no atlas chunk queued or in flight (the fonts it reads could move). Read at
     *  the pick (no recording for an Encode that cannot play) and again at the act, on the act's one read of the state. */
    private fun encodeUnplayable(st: LinkState): String? = when {
        st.cacheSize < CfwModes.CACHE2_MAX_RIDERS || !st.bind ->
            "Encode plays on a 256 KiB session with BIND only (this one: ${st.cacheSize / 1024} KiB${if (st.bind) "" else ", no BIND"})"
        atlas == null || cachedText == null -> "cached text is off"
        atlasInFlight != null || atlasQueue.isNotEmpty() || atlasBusyForTest -> ATLAS_BUSY
        else -> null
    }

    /** The transition in hand (`FIRMWARE.md` §7.11: the out half, the held frame, the in half); null when none. */
    private var transition: TransitionRun? = null
    private var transitionActs = 0
    private var transitionDeadlineGen = 0
    /** Tests only: the pick among a tier's checked effects (a random one at each use, `MOTION.md` §7). */
    @Volatile internal var transitionRandom: java.util.Random = java.util.Random()
    /** Tests only: RIGHT's in-half body is not read (a lost body) — the deadline's CLOSE of both slots ends it. */
    @Volatile internal var transitionBodyLostForTest = false
    /** Tests only: the in half's PLAY-AFTER is held back (the out half plays to done and keeps owning). */
    @Volatile internal var transitionHoldInHalfForTest = false
    /** Tests only: every pump returns at once, as one does when the window has no room (§32) — an act's transition stays
     *  owed while input arrives behind it. */
    @Volatile internal var pumpHeldForTest = false
    /** Tests only (K1-2 stage 2, item 3a's pin): asked in each pump after the poll — true: this pump goes as one with no
     *  room on the link (`pumpHeldForTest` returns before the poll, so it cannot make a switch fall in such a pump). */
    @Volatile internal var noRoomAfterPollForTest: (() -> Boolean)? = null
    internal fun transitionPhaseForTest(): String? = transition?.phase?.name
    /** Transitions that ran whole (RIGHT's in-half body, outcome 0) this process. */
    private var transitionsWhole = 0
    internal fun transitionCountsForTest(): Pair<Int, Int> = transitionActs to transitionsWhole
    /** C3 DM-T: the transitions that ran whole, by effect id (tests: the walk's picks). */
    private val transitionsWholeBy = HashMap<String, Int>()
    internal fun transitionsWholeByEffectForTest(): Map<String, Int> = HashMap(transitionsWholeBy)

    /**
     * An act changed the screen ([before] → now): with ladder stage 5 in force and an effect checked in [tier], the
     * transition is owed — a random pick among the checked, both programs built (refused offline as the glasses would:
     * then the screen lands at once and the note names the half refused), the records laid out in the act's slot of the
     * region (two, taken in turn), a box on screen requeued unread (it returns after). Anywhere else nothing happens: the
     * screen lands at once, as it always has.
     */
    private fun transitionAct(before: ScreenKey, tier: wm.damage.core.motion.Transitions.Tier, reversed: Boolean) {
        val after = screenKey()
        if (after == before || before.mode == Mode.EXCLUSIVE || after.mode == Mode.EXCLUSIVE) return
        val st = transport.state.value                                   // ONE read for the decision
        val region = st.transitionRegion ?: return                       // below stage 5: today's cut, byte for byte
        if (!st.bind || !st.leaseHeld || glassesSilent || sleep != null || switcher.open) return
        val ids = settings.effectsOf(tier)
        if (ids.isEmpty()) return                                        // None: the screen lands at once
        val k = transitionActs++
        val area = Rect(0, 0, comp.width, maxOf(before.bottom, layout.safe.bottom))
        val at4 = (region.first + (k and 1) * CfwModes.TRANSITION_SLOT) / 4
        val tagOut = TransitionRun.TAG_OUT + (k and 0xFFF)
        val tagIn = TransitionRun.TAG_IN + (k and 0xFFF)
        // C3 X1MX2 item 2 (decisions CB (2)): ONE plane for both halves, the nearer of the two screens' (the hand-over
        // between the halves needs one grid) — the silent clock's 0 both ways. C3 X2MX2 item 2 (decisions CJ (3)): and never
        // behind Global `Depth`, the two bars' plane (an app's own row set further back keeps the rain at Global)
        val plane = minOf(before.plane, after.plane, settings.depth)
        // C3 DM-T (`MOTION.md` §7: a random pick among the checked): an Encode or Matrix whose preconditions this act does not
        // meet leaves the pick, said once with its reason, and the pick is made again among the rest; when nothing checked is
        // left, the tier's defaults play — never a refusal he sees
        // X2T item 4: the pick made before the handler goes first ([transitionPick]); what left it then stays out
        val pool = ids.mapNotNull { wm.damage.core.motion.Transitions.Effect.of(it) }.toMutableList()
        val leftOut = ArrayList<String>()
        for ((e, why) in before.leftOut) if (pool.remove(e)) leftOut += "${e.label} ($why)"
        var next = before.pick
        var run: TransitionRun? = null
        var what = ""
        while (run == null) {
            val effect = next?.takeIf { it in pool }
                ?: if (pool.isNotEmpty()) pool[transitionRandom.nextInt(pool.size)] else wm.damage.core.motion.Transitions.defaultsOf(tier).first()
            next = null
            what = "${tier.word} ${effect.label}${if (reversed) " reversed" else ""}"
            try {
                run = transitionRunOf(st, tier, effect, reversed, area, at4, k, tagOut, tagIn, before, plane)
            } catch (e: wm.damage.core.geom.LintError) {
                if (effect.glyphs && pool.remove(effect)) { leftOut += "${effect.label} (${e.message})"; continue }
                if (leftOut.isNotEmpty()) journal.note("transition", "left out of this act's pick: ${leftOut.joinToString("; ")}")
                journal.note("transition", "$what is refused offline (${e.message}) — the screen lands at once, as without stage 5")
                return
            }
        }
        if (leftOut.isNotEmpty()) journal.note("transition", "left out of this act's pick: ${leftOut.joinToString("; ")} — $what plays")
        transition?.let { journal.note("transition", "a new act replaces ${it.tier.word}'s (${it.phase}): its PLAY closes what is left of it") }
        // X2T item 3: an Encode run's lists overwrite only the page-staging strips whose bytes they meet; nothing writes the
        // reserve while the run holds ([cacheWritesHeld])
        run.built.listsRange?.let { lists ->
            stagePagesDropUnder(lists)
            // 0.83 integration (K1-4a's slice meets X2T's lists): the notice's programs and capture lie in the reserve's top
            // 16 KiB; lists that reach them overwrite counted programs, so the programs leave the books (as a page strip's
            // record does above) and the notice lane writes them again once the run lets cache writes go
            CfwModes.noticeRegion(st.cacheSize, st.bind)?.takeIf { it.first <= lists.last && lists.first <= it.last }
                ?.let { noticeYield("an Encode transition's cell lists take bytes ${lists.first}..${lists.last}") }
            // stage L: and the held lanes' areas in the reserve's lower 16 KiB, likewise
            CfwModes.musicRegion(st.cacheSize, st.bind)?.takeIf { it.first <= lists.last && lists.first <= it.last }
                ?.let { lanes.yieldSlice("an Encode transition's cell lists take bytes ${lists.first}..${lists.last}") }
        }
        // K1 §9: a box on screen goes back to the queue unread, and returns after the transition
        if (notifications.active) {
            if (notifications.furlingOut) {
                notifications.restoreUnderFinished(comp.composed)?.let { retire(it); comp.damage(it) }
                notifications.abandonFurl()
            } else {
                liftNotificationBox()
                notifications.requeueCurrent()
            }
            boxLifted = false
            stopGrace()
            updatePlanes()
        }
        transition = run
        transitionDeadlineGen++
        journal.note("transition", "owed: ${run.describe()}")
        post(Msg.Pump)
    }

    /**
     * An act's run for [effect]: both programs built (each refusal names its half), the PLAY and the PLAY-AFTER judged
     * offline. C3 DM-T, Encode and Matrix (the DM spec §3, §4): refused here — and so left out of the act's pick — when an
     * atlas chunk is queued or in flight (the fonts it reads could move), on a session without 256 KiB and BIND (Encode:
     * the stage reserve holds its lists), when a screen's lists do not fit, or when a half passes 80 % of the step budget;
     * their period and frames follow "the length holds, frames give" ([wm.damage.core.motion.Transitions.timingFor]).
     * C3 X1MX2 item 2: [plane] is the act's one plane (the nearer of the two screens' and, since C3 X2MX2, Global `Depth`),
     * which Matrix's rain takes in both halves; the Depth effect keeps Global `Depth` (its push back inside the 16-px budget, bytes as before).
     */
    private fun transitionRunOf(st: LinkState, tier: wm.damage.core.motion.Transitions.Tier, effect: wm.damage.core.motion.Transitions.Effect,
                                reversed: Boolean, area: Rect, at4: Int, k: Int, tagOut: Int, tagIn: Int, before: ScreenKey, plane: Int): TransitionRun {
        val fx = wm.damage.core.motion.Motion.effects(wm.damage.core.wire.DamageMsg.Caps(st.damageContract, st.damageFeatures), st.flagsInForce)
        val glyphs = if (!effect.glyphs) null else {
            if (atlasInFlight != null || atlasQueue.isNotEmpty() || atlasBusyForTest) throw wm.damage.core.geom.LintError(ATLAS_BUSY)
            if (effect == wm.damage.core.motion.Transitions.Effect.MATRIX) wm.damage.core.motion.Transitions.Glyphs(rain = rainFace())
            else {
                encodeUnplayable(st)?.let { throw wm.damage.core.geom.LintError(it) }
                // X2T item 4: recorded only when Encode was the pick made before the handler (the arriving screen is not
                // recorded for an Encode that cannot play)
                val leaving = before.cells ?: throw wm.damage.core.geom.LintError("Encode: the leaving screen was not recorded (Encode was not this act's first pick, " +
                    "or the act was taken for one that changes no screen)")
                val arriving = screenCells(screenRuns(), Rect(0, 0, comp.width, layout.safe.bottom))
                // X2T item 3: the lists go where they meet the fewest bytes of page staging's strips
                val reserve = CfwModes.stageReserve(st.cacheSize, st.bind)
                val res = (st.cacheSize - reserve) until st.cacheSize
                val at = encodeListsAt(res, wm.damage.core.motion.CellLists.bytes(leaving) + wm.damage.core.motion.CellLists.bytes(arriving))
                wm.damage.core.motion.Transitions.Glyphs(leaving = leaving, arriving = arriving, reserveAt4 = at / 4, reserveRoom = res.last + 1 - at)
            }
        }
        val timing = wm.damage.core.motion.Transitions.timingFor(effect, settings.lengthOf(tier), settings.framesOf(tier), area, glyphs?.cells ?: 0)
        val b = wm.damage.core.motion.Transitions.build(fx, effect, reversed, area, timing, at4, CfwModes.TRANSITION_SLOT,
            settings.depth, seed = k and 0xFFFF, glyphs = glyphs, plane = plane)
        if (effect.glyphs && (!b.withinShare || maxOf(b.outCost, b.inCost) * 100 > wm.damage.core.motion.Motion.BUDGET_CAP * transitionShareForTest))
            throw wm.damage.core.geom.LintError("a half costs ${maxOf(b.outCost, b.inCost)} u, past " +
                "${minOf(wm.damage.core.motion.Transitions.SHARE_MAX_PERCENT, transitionShareForTest)} % of the step budget's cap")
        // C3 X3 (decisions CD (a)): both halves' C as the plan keeps them (worked out once an act)
        val out = try { wm.damage.core.motion.Motion.play(b.out, TransitionRun.OUT_SLOT, timing.period, tagOut, 0, IntArray(4), cost = b.outCost) }
            catch (e: wm.damage.core.geom.LintError) { throw wm.damage.core.geom.LintError("the out half's PLAY: ${e.message}") }
        // the step budget (§7.12): the in half waits on the out half, which may still be drawing
        val inn = try {
            wm.damage.core.motion.Motion.playAfter(b.inn, TransitionRun.IN_SLOT, timing.period, tagIn, TransitionRun.OUT_SLOT,
                IntArray(4), listOf(wm.damage.core.motion.Motion.Drawing(b.outCost)), 0, cost = b.inCost)
        } catch (e: wm.damage.core.geom.LintError) { throw wm.damage.core.geom.LintError("the in half's PLAY-AFTER: ${e.message}") }
        // Encode: the out half's lists ride the records' write (one message). X2T item 2 (the review's 2, decisions AQ (2)):
        // the in half's ride it too when the three fit one compressed message (the list within mode 25's inflate) — the
        // transport deflates one waiting message at a time, so a second write right behind it went raw; past that they go
        // after the out PLAY, above the out lists, as before. No new wait either way. C3 X4T item 2 (`C3-V2T-review.md` 2,
        // decisions AT (2)): and only when the transport says a write submitted now would go deflated — ZWRITE in force,
        // nothing waiting to be judged, no cache write queued ahead of it ([Transport.nextListDeflates]); a quick second
        // act sent the three raw before its out PLAY
        val outAndRecords = listOfNotNull(CfwModes.CacheWrite2(at4, b.blob), b.outLists?.let { CfwModes.CacheWrite2(it.at4, it.bytes) })
        val inWrite = b.inLists?.let { CfwModes.CacheWrite2(it.at4, it.bytes) }
        val together = inWrite?.let { CfwModes.cacheUpdate2(outAndRecords + it, st.cacheSize) }
            ?.takeIf { it.size - 1 <= CfwModes.ZWRITE_MAX && transport.nextListDeflates() }
        return TransitionRun(tier, b, together ?: CfwModes.cacheUpdate2(outAndRecords, st.cacheSize), out, inn, tagOut, tagIn, System.currentTimeMillis(),
            listWrite = if (together != null) null else inWrite?.let { CfwModes.cacheUpdate2(listOf(it), st.cacheSize) })
    }

    /** Tests only: the atlas reads as busy (a chunk queued or in flight) at the act. */
    @Volatile internal var atlasBusyForTest = false
    /** Tests only: the share of the step budget an Encode or Matrix half may take, in percent (the rule's: 80). */
    @Volatile internal var transitionShareForTest = wm.damage.core.motion.Transitions.SHARE_MAX_PERCENT

    /** Matrix's face: of the faces the glasses hold, the one that packs the most of A–Z a–z 0–9 (the DM spec §3); a tie
     *  goes to the first in [atlasFaceOrder]. */
    private fun rainFace(): wm.damage.core.motion.CellLists.Face? {
        val ct = cachedText ?: return null
        val a = atlas ?: return null
        return ct.live.sortedWith(atlasFaceOrder).filter { a.isAcked(it) }.mapNotNull { s -> a.entry(s)?.let { s to it } }
            .maxByOrNull { (_, e) -> wm.damage.core.motion.CellLists.ALNUM.count { e.packs(it.toChar()) } }
            ?.let { (s, e) -> wm.damage.core.motion.CellLists.face(s, e) }?.takeIf { it.alphabet.isNotEmpty() }
    }

    /** C3 DM-T, the DM spec's D6: no other cache write while an Encode or Matrix run is in hand — their read range runs from
     *  the atlas's fonts to their records (Encode's to its lists in the stage reserve, above Main's region), and a write
     *  that meets it closes the half on the glasses. The five writers read it ([pumpAtlas], [pumpListBind],
     *  [pumpStagePiece], [pumpStage], and K1-4a's [pumpNoticeStage] since the 0.83 integration); every end of a run goes through [transitionEnd] or a new act, each posting a pump.
     *  C3 X2T item 1 (the review's 1, decisions AQ (1)): and for one in-half period past RIGHT's in-half body of such a run
     *  ([glyphHoldUntilMs]) — LEFT reports nothing, and a write released in the pump that sends the CLOSE could reach LEFT
     *  before its in half has landed there (a close by a cache write, §7.3); the period's end posts a pump. */
    private fun cacheWritesHeld(): Boolean = transition?.holdsCache == true || System.currentTimeMillis() < glyphHoldUntilMs

    /** X2T item 1: the end of the hold's one period after a glyph run's in-half body (0: none); set by [transitionEnd] at
     *  that body, cleared at a session start (its keyframe ends every instance on both lenses). */
    private var glyphHoldUntilMs = 0L
    /** C3 X4T item 4: a glyph hold's period was set and no pump has run since it ended — the held writes have not been
     *  let go yet (harnesses only: [idleApartFromMessages]). */
    private var glyphHoldPumpOwed = false
    /** Tests only (on the loop): the hold as the five writers read it; the run in hand's programs and lists; page staging's
     *  records. */
    internal fun cacheWritesHeldForTest(): Boolean = cacheWritesHeld()
    internal fun transitionBuiltForTest(): wm.damage.core.motion.Transitions.Built? = transition?.built
    internal fun stagedRecordsForTest(): List<StagedRecord> = stage.records.values.toList()

    /** C3 X2T item 3 (the review's 3, decisions AQ (3)): where an Encode act's lists of [need] bytes go in the stage reserve
     *  [res] — the 4-aligned start whose span meets the fewest bytes of page staging's strips (a strip met is counted
     *  whole: it is written again whole), the lowest on a tie; the candidates are the reserve's start and the end of each
     *  strip. */
    private fun encodeListsAt(res: IntRange, need: Int): Int {
        val strips = stage.records.values.filter { it.key !is RegionStageKey && it.off >= 0 && it.off in res }
        fun met(at: Int) = strips.filter { it.off < at + need && at < it.off + it.size4 }.sumOf { it.size4 }
        return (listOf(res.first) + strips.map { it.off + it.size4 }).filter { it % 4 == 0 && it + need <= res.last + 1 }
            .distinct().sorted().minByOrNull { met(it) } ?: res.first
    }

    /** X2T item 3: an Encode act's lists, laid over [lists] (bytes), drop the page-staging strips whose bytes they meet —
     *  staged again when their document is in front and idle; every other strip stays proven and drawable (nothing
     *  writes the reserve while the run holds, [cacheWritesHeld]). */
    private fun stagePagesDropUnder(lists: IntRange, why: String = "an Encode transition's cell lists take bytes ${lists.first}..${lists.last}") {
        val met = stage.records.values.filter { it.key !is RegionStageKey && it.off >= 0 && it.off <= lists.last && lists.first < it.off + it.size4 }
        if (met.isEmpty()) return
        val from = met.minOf { it.off }
        for (r in met) stage.remove(r)
        stage.ring?.rewind(from)                        // written back where they lay once the document is idle in front
        comp.placements = emptyList()                   // rebuilt from the registry at the next assemble
        stageRecount()
        journal.note("stage", "dropped ${met.size} record(s) — $why (${met.joinToString { it.key.toString() }})")
    }

    /** The pump's first step for an owed transition: the records' write (one mode-19 message, this shell's tag), then
     *  alone the rules' CLEARs (the cover), the out half's PLAY and the rest of their tail (the tap's answer). Not with
     *  stage 5, BIND or the lease gone since the act, or a keyframe owed (it would end every instance): then nothing of it
     *  goes and the frame lands as a cut. */
    private suspend fun transitionFirst(st: LinkState, localOn: Boolean, now: Long) {
        if (transitionCloses.isNotEmpty()) {
            val c = transitionCloses; transitionCloses = emptyList()
            val id = submitLocalOnly(c, "TRANSITION")
            journal.note("transition", "${c.size} CLOSE(s) sent alone as flush ${id ?: "(not submitted)"}")
        }
        val tr = transition ?: return
        if (tr.phase != TransitionRun.Phase.OWED) return
        // the review's finding 1 (AW.3's own form): every act is a whole-panel change, so the out PLAY's close pass ends every
        // instance in its area — an adopted motion whose cells the compositor holds included; when the run is not sent, its
        // reason ends the instance itself (a keyframe, a lease release, the CLOSE-all before a disarm, a new session).
        // Nothing is left to wait for, and those cells go with the arriving frame
        val cut = comp.cutDeferral()
        if (!st.effects || !st.bind || !st.leaseHeld || comp.needsKeyframe || st.transitionRegion?.let { r -> tr.built.at4 * 4 in r } != true) {
            transitionEnd("not sent: stage 5, BIND or the lease is no longer in force, a keyframe is owed, or the session changed — the screen lands at once")
            return
        }
        tr.writeId = try {
            submitCacheWrite(DisplayOp.CacheWrite2(tr.write), "TRANSITION", atlas = false)
        } catch (e: Exception) {
            transitionEnd("its records could not be submitted (${e.message}) — the screen lands at once")
            return
        }
        val tail = if (localOn) local.tail(localRow(), now, emptySet(), frame = false) else emptyList()
        val clears = tail.takeWhile { it.size == 3 && it[0].toInt() == CfwModes.BIND_MODE && it[1].toInt() == CfwModes.BIND_CLEAR }
        tr.outId = submitLocalOnly(clears + listOf(tr.playOut) + tail.drop(clears.size), "TRANSITION")
        tr.phase = TransitionRun.Phase.FRAME
        tr.outSentAtMs = now
        // C3 DM-T, Encode: the in half's lists after the out PLAY, above the out lists (they meet no range the out half
        // reads), ahead of the frame and the PLAY-AFTER in stream order
        tr.listWrite?.let { lw ->
            tr.listId = try {
                submitCacheWrite(DisplayOp.CacheWrite2(lw), "TRANSITION", atlas = false)
            } catch (e: Exception) {
                transitionEnd("its in half's lists could not be submitted (${e.message})", close = true)
                return
            }
        }
        tr.outId?.let { id ->
            // the out PLAY is what he sees first: journaled as the gesture's first flush (`in`, the wait for a slot), so
            // `tools/journal_report.py`'s WAIT column prices it; the frame under it no longer counts as the first
            journal.flushSubmitted(id, comp.epoch, emptyList(), "TRANSITION+${tr.tier.word.lowercase()}", st.transportName, Journal.Timing(
                input = (if (pumpPriority || inputAwaitsSlot) 1 else 0).also { inputAwaitsSlot = false },
                inWaitMs = (if (pumpPriority || inputWaitSince >= 0) (if (inputWaitSince >= 0) System.currentTimeMillis() - inputWaitSince else 0L)
                    else -1L).also { inputWaitSince = -1L },
                win = current?.id ?: "", mode = mode.name))
            if (pumpPriority) inputFlushPending = false
        }
        journal.note("transition", "out half sent: the records' write as flush ${tr.writeId} (${tr.write.size} B raw; its size on the wire is noted at its ack), then the out PLAY as flush ${tr.outId} " +
            "(${clears.size} CLEAR(s), the PLAY, ${tail.size - clears.size} answer(s)) — the act's first visible image — ${now - tr.actAtMs} ms after the act" +
            if (cut) "; the cells held behind an adopted motion go with the frame" else "")
    }

    /** A frame flush was submitted while a transition is in hand ([keyframe]: it ends every instance on the glasses;
     *  [carried]: it carried the PLAY-AFTER). A flush in the in half — his input, a repaint, a chrome tick — is held by both
     *  owners and ends nothing: it shows at the in half's next step or at LAND (§7.11). */
    private fun transitionSubmitted(id: Long, keyframe: Boolean, carried: Boolean) {
        val tr = transition ?: return
        when {
            keyframe && tr.phase != TransitionRun.Phase.OWED -> transitionEnd("a keyframe went: it ends every instance, the frame shows whole")
            carried -> { tr.phase = TransitionRun.Phase.IN; tr.inId = id; tr.inSentAtMs = System.currentTimeMillis()
                journal.note("transition", "in half's PLAY-AFTER rides flush $id, ${tr.inSentAtMs - tr.outSentAtMs} ms after the out half's") }
        }
    }

    /** The arriving frame drew nothing more and no flush carried the in half: its PLAY-AFTER goes alone. */
    private suspend fun transitionAlone() {
        val tr = transition ?: return
        if (tr.phase != TransitionRun.Phase.FRAME || transitionHoldInHalfForTest || comp.hasPending || comp.needsKeyframe) return
        val id = submitLocalOnly(listOf(tr.playAfter), "TRANSITION") ?: return transitionEnd("its PLAY-AFTER could not be submitted", close = true)
        transitionSubmitted(id, keyframe = false, carried = true)
    }

    private fun transitionFlushDone(ev: TransportEvent.FlushDone) {
        // the records' and lists' writes are the ledger's whether or not their act is still in hand: booked at their send
        // ([submitCacheWrite]) and counted at [completeFlush] (X6T)
        val tr = transition ?: return
        val inFrame = tr.phase == TransitionRun.Phase.FRAME || tr.phase == TransitionRun.Phase.IN
        when (ev.id) {
            // C3 X4T item 5 (`C3-V2T-review.md` 6): the wire's size beside the raw list's — the transport deflates it as mode
            // 25 when it can, so the submit's note (the raw size) is not what the link carried
            tr.writeId -> if (!ev.ok) transitionEnd("its records' write failed (${ev.error})", close = true)
                else journal.note("transition", "the records' write (flush ${ev.id}) took ${ev.bytes} B on the wire (${tr.write.size} B raw)")
            tr.listId -> if (!ev.ok) transitionEnd("its in half's lists' write failed (${ev.error})", close = true)
                else journal.note("transition", "the in half's lists' write (flush ${ev.id}) took ${ev.bytes} B on the wire (${tr.listWrite?.size} B raw)")
            // the deadline is armed at whichever of the two acks comes second (the review's finding 4: from the out PLAY's
            // ack, not its submit, so a busy link cannot make it fall inside the in half)
            tr.outId -> if (!ev.ok) transitionEnd("its out half's flush failed (${ev.error})", close = true)
                else { tr.outDoneAtMs = System.currentTimeMillis(); transitionDeadlineArm(tr) }
            tr.inId -> if (!ev.ok) transitionEnd("the flush carrying its PLAY-AFTER failed (${ev.error})", close = true)
                else { tr.inDoneAtMs = System.currentTimeMillis(); transitionDeadlineArm(tr) }
            else -> if (!ev.ok && inFrame && ev.id in inflightFlushes) transitionEnd("a flush written under it failed (${ev.error}) — its repaint goes now", close = true)
        }
    }

    /** §7.11: with no in-half body by the in half's nominal end plus its allowance ([TransitionRun.deadlineMs]), the phone
     *  CLOSEs both slots — the one rule that ends a waiter, so no pose is held until he acts. Pacing on the loop, never a
     *  wait on a send. C3 TR1: armed again at each fact that moves the end (each ack, RIGHT's out-half body); each arm
     *  supersedes the one before. */
    private fun transitionDeadlineArm(tr: TransitionRun) {
        val at = tr.deadlineMs() ?: return
        val gen = ++transitionDeadlineGen
        val from = if (tr.outBodyAtMs != 0L) "RIGHT's out-half body plus ${tr.steps} periods and ${TransitionRun.BODY_ALLOWANCE_MS} ms"
            else "its acks plus one period and ${TransitionRun.ACKS_ALLOWANCE_MS} ms (no out-half body yet)"
        scope.launch {
            delay(maxOf(1L, at - System.currentTimeMillis()))
            if (running) post(Msg.Run(dropped = {}) {
                if (transition === tr && gen == transitionDeadlineGen)
                    transitionEnd("no in-half body by its nominal end from $from — both slots are CLOSEd, the frame shows whole", close = true)
            })
        }
    }

    /** RIGHT's notify-114 bodies: the in half's outcome-0 body ends the transition, and the phone CLOSEs the out half's
     *  slot (empty on RIGHT; it ends an owner LEFT kept after losing the in half). A body of an earlier act's in half (a
     *  newer act PLAYed slot 2 since) is not this run's and CLOSEs nothing (§7.11, B'3). */
    private fun transitionBody(ev: TransportEvent.LocalBody) {
        val tr = transition ?: return
        // C3 TR1: RIGHT's out half drew its last step — the in half's nominal end is measured from here (the backstop moves)
        if (ev.slot == TransitionRun.OUT_SLOT && ev.tag == tr.tagOut && ev.outcome == wm.damage.core.wire.DamageMsg.OUTCOME_DREW &&
            tr.outBodyAtMs == 0L) {
            tr.outBodyAtMs = System.currentTimeMillis()
            transitionDeadlineArm(tr)
            return
        }
        if (ev.slot != TransitionRun.IN_SLOT || ev.tag != tr.tagIn || transitionBodyLostForTest) return
        when (ev.outcome) {
            wm.damage.core.wire.DamageMsg.OUTCOME_DREW -> {
                journal.note("transition", "in half's last step drawn on RIGHT (${ev.drawn ?: "?"} drawn, ${ev.folded ?: "?"} folded), " +
                    "${System.currentTimeMillis() - tr.actAtMs} ms after the act")
                transitionsWhole++
                transitionsWholeBy.merge(tr.built.effect.id, 1, Int::plus)
                transitionEnd(null, closeOut = true)
            }
            // the second review pass's finding 2: a refusal whose body comes before its flush's failed ack closes both
            // slots here, as the ack would (the out half, done and owning, holds its far pose until something ends it);
            // a closed in half (outcome 1) met both boxes in its close pass
            else -> transitionEnd("its in half ended on RIGHT with outcome ${ev.outcome}${ev.reason?.let { " (reason $it)" } ?: ""}",
                close = ev.outcome == wm.damage.core.wire.DamageMsg.OUTCOME_REFUSED)
        }
    }

    /** The transition ends: [why] said (null: it ran whole), [close] both slots or [closeOut] the out half's in one flush
     *  of their own, the writes that waited and the queued notices let go. */
    private fun transitionEnd(why: String?, close: Boolean = false, closeOut: Boolean = false) {
        val tr = transition ?: return
        transition = null
        transitionDeadlineGen++
        if (why != null) journal.note("transition", "${tr.tier.word} ${tr.built.effect.label} ended in ${tr.phase}: $why")
        // X2T item 1: RIGHT's in-half body of a glyph run holds the other cache writes one in-half period more (LEFT may
        // land later and reports nothing); its end is asked back as a pump. Every other end releases the run's hold here
        // (a period an earlier body set lapses by itself, its pump already asked).
        if (why == null && tr.holdsCache) {
            val p = Math.ceil(tr.built.timing.period * 1000.0 / 1024).toLong()
            glyphHoldUntilMs = maxOf(glyphHoldUntilMs, System.currentTimeMillis() + p)
            glyphHoldPumpOwed = true
            pumpAfter(p)
        }
        val owners = tr.phase == TransitionRun.Phase.FRAME || tr.phase == TransitionRun.Phase.IN
        val closes = when {
            !owners || glassesSilent -> emptyList()
            close -> listOf(CfwModes.motionClose(TransitionRun.OUT_SLOT), CfwModes.motionClose(TransitionRun.IN_SLOT))
            closeOut -> listOf(CfwModes.motionClose(TransitionRun.OUT_SLOT))
            else -> emptyList()
        }
        transitionCloses = transitionCloses + closes
        // the notices that waited behind it (not behind the wheel, a menu or the keyboard, which keep them)
        if (!switcher.open && !menu.open && !keyboard.open && notifications.showNextIfIdle()) {
            updatePlanes(); paintNotification()
            if (quiet) scheduleSilentDismiss() else scheduleGrace()
        }
        post(Msg.Pump)
    }

    /** CLOSEs a transition's end owes: the next pump sends them alone, ahead of its frame; a session's start drops them
     *  (its keyframe ends every instance). */
    private var transitionCloses: List<ByteArray> = emptyList()

    // ------------------------------------------------------------------ build C3, stage DM-T: Encode's cells
    /**
     * The text runs of the screen as it stands now: painted once into a scratch through [CachedText.survey] — only runs
     * of codes a face the glasses hold packs are recorded (the atlas's own split), into a list of the survey's own, and
     * nothing is counted (X2T item 4: no face seen, no use, no character noted, no icon use — a thumbnail shown once is
     * not packed because a screen was recorded; no record in the frame's list). Content only: the chrome's few strings
     * are not cells (they show with the backdrop). The content painters damage the content area as they always do; the
     * screen being painted is the one on the panel or the one about to be, so that costs no byte. [screenRunCount] counts
     * every call (the one entry of both recordings and the test seam).
     */
    private fun screenRuns(): List<wm.damage.core.comp.TextDraw> {
        val ct = cachedText ?: return emptyList()
        screenRunCount++
        val g = Gray8(comp.width, comp.height)
        return ct.survey(g) {
            if (mode == Mode.SILENT) wallClock().let { c -> SilentMode.paintAll(g, layout, c.hh, c.mm, settings.silentClock, silentSmallPainter(c)) }
            else when (val w = contentWindow()) {
                null -> kit.paintList(g, layout, main.view(), resting = main.resting)
                else -> when (val v = w.view()) {
                    is WindowView.ListView -> kit.paintList(g, layout, v)
                    is WindowView.DocView -> kit.paintDoc(g, layout, v)
                    is WindowView.CanvasView -> v.paint(g, layout.content)
                }
            }
        }
    }

    /** C3 X2T item 4 (decisions AM (1), AQ (4)): the screens recorded this process — 0 below stage 5 and for every effect
     *  but Encode, 2 per Encode act (the leaving screen at the pick, the arriving one at the act). */
    private var screenRunCount = 0
    internal fun screenRunsForTest(): Int = screenRunCount

    /** [runs] as Encode's cells over [area] ([CellLists.screen]): a face counts when the glasses hold it — live and acked
     *  (`GlyphAtlas.isAcked`) — and a run's track offsets are its plane's disparity (the compositor's last region holding
     *  its rect, as `Compositor.disparityAt` reads it), the notice box's region left out (X2T item 5, the review's 4(c):
     *  the box is lifted at the act, so the text under it shows with its own plane's offset, not the box's). */
    private fun screenCells(runs: List<wm.damage.core.comp.TextDraw>, area: Rect): wm.damage.core.motion.CellLists.Screen {
        val ct = cachedText; val a = atlas
        if (ct == null || a == null) return wm.damage.core.motion.CellLists.Screen.NoFit("cached text is off")
        val live = ct.live
        // C3 X4T item 1's class (the arriving screen): by value, not by identity — the compositor keeps its old list when
        // a plane map comes in equal (`Compositor.planes`), so after a second [updatePlanes] with nothing changed the
        // region in the map was not [noticePlane]'s object, the box's plane stayed and the arriving text under the box
        // took the box's offset (8 px off its glyph on each lens, found by the X4T row)
        val box = noticePlane
        val planes = comp.planes.filter { it != box }
        return wm.damage.core.motion.CellLists.screen(runs, { s -> if (s in live && a.isAcked(s)) a.entry(s) else null }, { s -> ct.kernOf(s) },
            { r -> planes.lastOrNull { it.rect.contains(r) }?.disparity ?: 0 }, area)
    }

    /** Tests only: the screen on the panel as Encode's cells (on the loop). */
    internal fun screenCellsForTest(): Pair<List<wm.damage.core.comp.TextDraw>, wm.damage.core.motion.CellLists.Screen> =
        screenRuns().let { it to screenCells(it, Rect(0, 0, comp.width, layout.safe.bottom)) }
    /** Tests only: the atlas's entry of [spec] as the glasses hold it. */
    internal fun atlasEntryForTest(spec: FontSpec): GlyphAtlas.Entry? = atlas?.takeIf { it.isAcked(spec) && spec in cachedFontsLive }?.entry(spec)
    /** Tests only (C3 AH1F): the atlas lane has nothing queued and nothing in flight (on the loop). */
    internal fun atlasLaneIdleForTest(): Boolean = atlasInFlight == null && atlasQueue.isEmpty()
    /** Tests only (C3 AH3): [block] evaluated once ON the loop, answered either way — by the loop's turn, or where a
     *  stopping loop drops it (nothing changes the shell's state there any more); at once with no loop alive, or when
     *  asked from the loop itself. The one door for a test to read what the loop changes in place (`OnLoop.kt`). */
    internal fun <T> readOnLoopForTest(block: () -> T): java.util.concurrent.CompletableFuture<T> {
        val out = java.util.concurrent.CompletableFuture<T>()
        val read: () -> Unit = { try { out.complete(block()) } catch (e: Throwable) { out.completeExceptionally(e) } }
        if (!loopAlive || turns.open()?.thread === Thread.currentThread()) read()
        else post(Msg.Run(dropped = read, tag = "test read", action = read))
        return out
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
            hostState = hostTag.text(System.currentTimeMillis()),
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
        noticePlayFlush?.let { append("noticePlay=$it ") }
        if (notifications.animating) append("notice-anim ")
        if (slides.any { it.active }) append("slides ")
        if (switcher.spinning) append("spin ")
        if (haltedEpoch != null) append("halted ")
        if (glassesSilent) append("glasses-silent ")
        transition?.let { append("transition=${it.phase} ") }
        if (transitionCloses.isNotEmpty()) append("closes=${transitionCloses.size} ")
        lastDivergence?.let { append("diverge[$it] ") }
        append("reports=$divergencesReported status='$statusWord'")
    }

    /** True when nothing is pending anywhere — test/selfcheck introspection. */
    fun isQuiescent(): Boolean = queued.get() == 0 && idleApartFromMessages()

    /** Everything quiescence asks for EXCEPT the message queue. C3 X4T item 4 (`C3-V2T-review.md` 5, decisions AT (5)):
     *  not idle while a glyph hold keeps cache writes back — its extra period ([glyphHoldUntilMs]), then until the pump
     *  after it has run and what it let go has landed (the class: a harness that settled at the period's end acted just
     *  as the held atlas chunk went, and the next act left Encode and Matrix out). Only in a session that played a glyph
     *  run (the stamp is 0 otherwise). Read by the harnesses only (`isQuiescent`, [sampleIdle]); no product decision
     *  reads it. */
    private fun idleApartFromMessages(): Boolean =
        (glassesSilent || (!comp.hasPending && !comp.needsKeyframe && !comp.deferring)) && inflightFlushes.isEmpty() &&
            noticePlayFlush == null &&                     // build K1-4a: the notice's PLAY alone in flight is a flush in flight
            !notifications.animating && slides.none { it.active } && !switcher.spinning && transition == null && transitionCloses.isEmpty() &&
            !glyphHoldKeepsWritesBack()

    /** C3 X4T item 4: a glyph run's hold still keeps cache writes back, or what it let go is still on its way. */
    private fun glyphHoldKeepsWritesBack(): Boolean = glyphHoldUntilMs != 0L &&
        (System.currentTimeMillis() < glyphHoldUntilMs || glyphHoldPumpOwed || atlasQueue.isNotEmpty() || cacheWorkInFlight)

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
    /** [sync] = the shutdown form: the write has landed when this returns.
     *  The periodic form encodes on the loop and writes on the store's own
     *  thread (2026-09-05, §32), reporting a failed write back through the
     *  loop so the notice is raised where every other state mutation is. */
    private fun saveAll(sync: Boolean = false) {
        // settings are NOT re-put here (R2#4): applySettings already wrote
        // every change, and the stored record keeps any field this build does
        // not know
        persistence.put("shell.state", buildJsonObject {
            put("current", current?.id ?: "")
            put("mode", mode.name)
            put("mainCursor", main.model.cursor.toString())
            put("notices", saveNotices())
        })
        for (w in windows) {
            try {
                persistence.put("window.${w.id}", w.saveState())
                // §16.4a sub-records: per-item blobs. A stored sub-key the
                // window no longer holds is removed from the store (S1-5 §4.8).
                // ⚠ Only keys the window ITSELF reported this session may be
                // removed (review 2026-09-01 F2c/d): a failed restore, or a
                // build that does not speak sub-records, must never turn
                // "didn't load it" into a removal of real data.
                // An EMPTY blob is not a record (an older build's store holds
                // it as a removal's mark): a window that returns one is refused
                // loudly and its stored record kept (review 2026-09-03)
                val subs = w.saveSubState().filter { (sk, blob) ->
                    // said ONCE per key per session: saveAll runs every couple
                    // of seconds and a standing defect must not become a stream
                    blob.isNotEmpty().also {
                        if (!it && emptySubSaid.add("${w.id}.$sk"))
                            Log.e("shell", "${w.id}.saveSubState() reported '$sk' as an EMPTY " +
                                "object — that is not a record; refused")
                    }
                }
                val prefix = "window.${w.id}."
                val reported = subReported.getOrPut(w.id) { HashSet() }
                reported.addAll(subs.keys)
                for ((sk, blob) in subs) persistence.put(prefix + sk, blob)
                for (k in persistence.keysWithPrefix(prefix)) {
                    val sk = k.removePrefix(prefix)
                    if (sk !in subs && sk in reported) persistence.remove(k)
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
                // synchronous shutdown save reports for itself)
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
            n.record?.let { put("recordId", it.id); put("recordOcc", it.occ) }     // S1-2 Q3
        })
    }

    private fun restoreNotices(shellState: JsonObject?) {
        val arr = shellState?.get("notices") as? JsonArray ?: return
        for (e in arr) {
            try {
                val o = e.jsonObject
                if (o["read"]?.jsonPrimitive?.booleanOrNull == true) continue
                val recordId = o["recordId"]?.jsonPrimitive?.contentOrNull
                val recordOcc = o["recordOcc"]?.jsonPrimitive?.contentOrNull
                notifications.enqueueRestored(Notifications.Notice(
                    source = o["source"]?.jsonPrimitive?.contentOrNull ?: continue,
                    thread = o["thread"]?.jsonPrimitive?.contentOrNull ?: "",
                    body = o["body"]?.jsonPrimitive?.contentOrNull ?: "",
                    timeHHMM = o["time"]?.jsonPrimitive?.contentOrNull ?: "",
                    emergency = o["emergency"]?.jsonPrimitive?.booleanOrNull ?: false,
                    appId = o["appId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                    target = o["target"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                    gatedBy = o["gatedBy"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                    record = if (recordId != null && recordOcc != null) wm.damage.core.app.NoticeRef(recordId, recordOcc) else null,
                ))
            } catch (ex: Exception) {
                Log.w("shell", "unreadable persisted notice skipped: ${ex.message}")
            }
        }
    }

    companion object {
        /** K1-2 stage 3c: the per-app `Scroll effect` row's name (shown only at ladder stage 5, [settingsApps]). */
        const val APP_SCROLL_ROW = "Scroll effect"
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
        /** Decisions AZ.4: the wrap's PLAY tags, apart from the `motion` probe's (1, 2, 3, …); and the allowance for its start after the flush
         *  that carries it (the answer flushes' acks on his glasses, 2026-11-19: 50–400 ms), added to its steps for how long
         *  the cells inside its areas wait. */
        const val WRAP_TAG = 0xB000
        /** Build K1-4a: the notice's PLAY tags (one per PLAY, cycling through 0x1000), apart from the wrap's — and apart from
         *  a transition's (0.83 integration: its out half plays in the notice's slot 2 from `TransitionRun.TAG_OUT`, 0xC000;
         *  the mirror keeps one END record per (slot, tag) for the session, so a shared range let an act's out PLAY write
         *  over a live notice timer's record and read its closed body as a divergence). */
        const val NOTICE_TAG = 0xE000
        /** Decisions BF (G4): the measured period's window (complete motions), its margin over the largest step, and how
         *  many ticks lower a window must call for before the period comes down. */
        const val PERIOD_WINDOW = 8
        /** K1-2 stage 3: the fires whose list is kept by tag ([reportListByTag]) — more than a burst's motions in flight. */
        const val REPORT_TAGS_KEPT = 64
        /** C3 DW-1b-1 (decisions BD, DA (d) 1): the wheel's glasses list and its two scroll entries' numbers (down, up); its
         *  tap entry is the rules' own number for a list that stages none (`LocalRules.tapEntry`: 0). */
        const val WHEEL_LIST = 2
        val WHEEL_ENTRIES: IntArray get() = intArrayOf(6, 7)
        const val PERIOD_MARGIN_MEASURED = 1.10
        /** His look at 0.81 (ruled): a lowering waits this long with no input, and goes only by this many ticks or more. */
        const val PERIOD_QUIET_MS = 5_000L
        const val PERIOD_LOWER_MIN = 4
        const val WRAP_START_MS = 400L
        /** §66: a staged record's write goes out in messages of this many bytes (a multiple of 4,
         *  under the atlas's 3,072 B messages). */
        const val STAGE_CHUNK = 3064
        /** §66: write failures in a row, readings that did not answer in a row, or shortfalls in the
         *  session, before staging is off for the session. */
        const val STAGE_FAILURES_MAX = 3
        /** §66: a relay keep that records nothing — a strip is painted the way a live paint is,
         *  but its draws are not this frame's. */
        private val NO_RECORDS = Rect(0, 0, 0, 0)
        /** C3 DM-T: why Encode and Matrix leave an act's pick while the atlas is writing. */
        private const val ATLAS_BUSY = "an atlas chunk is queued or in flight — the fonts it reads could move"
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
