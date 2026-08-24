package wm.damage.core.shell

import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.util.Log

/**
 * The window manager itself: input grammar (§1), the back stack, focus, the
 * surface stack (Main / windows / switcher / notifications / silent), the flush
 * pump, persistence, and error surfacing. One instance == one shell; it runs
 * identically on the desktop and inside the APK (§10.1).
 *
 * Threading: a single event loop (one coroutine on [scope]); everything enters
 * through [post]. Scheduled UI transitions (grace periods, idle ticks, the
 * minute clock) are exactly that — scheduled UI state changes, which NO
 * TIMEOUTS explicitly permits; no operation is ever time-bounded.
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
    private val chrome = Chrome(text)
    private val journal = Journal(journalPath)
    val notifications = Notifications(text)
    private val switcher = Switcher(text)

    private val windows = ArrayList<DamageWindow>()
    private val recency = ArrayList<DamageWindow>()          // most recent first
    private lateinit var settingsWindow: SettingsWindow
    private val main = MainSurface(text, { mainRows() }, { commitWindow(it) })

    private enum class Mode { MAIN, WINDOW, SILENT }
    private var mode = Mode.MAIN
    private var current: DamageWindow? = null

    // slides for the active list/doc view (above+below band, or the doc region)
    private var slides: List<Slide> = emptyList()
    private var pendingListRepaint = false

    private var inputEcho = ""
    private var opText = "idle"
    private var statusText = "ok"
    private var restGen = 0
    private var graceGen = 0
    private var silentDismissGen = 0
    private var saveGen = 0
    private var previewPainted: String? = null
    @Volatile private var running = false

    /** Host-link status line for the status bar (set by the content client). */
    @Volatile var hostState: String = ""

    private val inflightFlushes = HashMap<Long, Compositor.Assembled>()
    /** FlushDone can beat the submitted-record message into the loop (the
     *  transport completes asynchronously); park it here until the record
     *  arrives. Single-threaded access (the loop). */
    private val earlyDone = HashMap<Long, TransportEvent.FlushDone>()
    private var flushFailStreak = 0
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
        data class FlushSubmitted(val id: Long, val a: Compositor.Assembled, val label: String) : Msg()
        object Pump : Msg()
    }

    val services: ShellServices = object : ShellServices {
        override fun requestRender(window: DamageWindow) { post(Msg.Invalidate(window.id)) }
        override fun setOperation(op: String) { opText = op; post(Msg.Pump) }
        override fun notifyInternal(source: String, body: String, urgent: Boolean) {
            if (!settings.notifyDamage && !urgent) return
            val c = wallClock()
            post(Msg.Notice(Notifications.Notice("DAMAGE · $source", source, body, c.hhmm, urgent)))
        }
    }

    private fun post(m: Msg) {
        queued.incrementAndGet()
        msgs.trySend(m)
    }

    // ------------------------------------------------------------------ set-up
    fun register(w: DamageWindow) {
        windows.add(w)
    }

    private fun mainRows(): List<DamageWindow> {
        // Settings is the LAST entry (§4.2: one scroll up from the top lands on it)
        val ordered = windows.filter { it !== settingsWindow } + settingsWindow
        return ordered
    }

    /** External notice entry (content client, phone bridge, tests). */
    fun postNotice(n: Notifications.Notice) = post(Msg.Notice(n))

    /** External input entry (keyboard harness, touch harness, remote shell). */
    fun postGesture(type: Int, source: Int = wm.damage.core.wire.EvenHubMsg.SRC_RING) =
        post(Msg.In(type, source))

    suspend fun start() {
        running = true
        settingsWindow = SettingsWindow(text, { settings }, { applySettings(it) })
        register(settingsWindow)

        // restore persisted state (§9.1: survives WM restart)
        persistence.load()
        settings = ShellSettings.fromJson(persistence.get("shell.settings"))
        layout = layoutFor(settings)
        for (w in windows) persistence.get("window.${w.id}")?.let {
            try { w.restoreState(it) } catch (e: Exception) {
                Log.e("shell", "restore of ${w.id} failed — window starts fresh", e)
            }
        }
        val shellState = persistence.get("shell.state")
        val restoredId = shellState?.get("current")?.jsonPrimitive?.contentOrNull

        // UNDISPATCHED: the collector subscribes BEFORE transport.start() can
        // emit, or early events (capability, lease) would vanish silently.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            transport.events.collect { post(Msg.Trans(it)) }
        }

        // start the display: capability gate + carrier + lease + warmup splash
        transport.start(splashFrame())

        // initial surface
        current = windows.firstOrNull { it.id == restoredId && it !== settingsWindow }
        if (current != null) {
            mode = Mode.WINDOW
            recency.remove(current)
            recency.add(0, current!!)
            current!!.onActivate(services)
        }
        composeFullSurface()
        comp.requestKeyframe()

        scope.launch { loop() }
        scheduleMinuteTick()
        scope.launch { while (isActive && running) { delay(5_000); post(Msg.IdleTick) } }
        scheduleRest()
        post(Msg.Pump)
    }

    suspend fun stop() {
        running = false
        saveAll()
        transport.stop()
        journal.close()
    }

    // ------------------------------------------------------------------ loop
    private suspend fun loop() {
        for (m in msgs) {
            if (!running) break
            try {
                when (m) {
                    is Msg.In -> handleInput(m.type)
                    is Msg.Trans -> handleTransport(m.ev)
                    Msg.MinuteTick -> handleMinute()
                    Msg.IdleTick -> handleIdle()
                    is Msg.RestTick -> if (m.gen == restGen && mode == Mode.MAIN && !main.resting) {
                        main.resting = true
                        if (settings.presence >= 0) composeContent()
                    }
                    is Msg.GraceTick -> if (m.gen == graceGen && notifications.active && !notifications.focused) {
                        notifications.takeFocus()
                        updatePlanes()
                        paintNotification()
                    }
                    is Msg.SilentTick -> if (m.gen == silentDismissGen && mode == Mode.SILENT &&
                        notifications.active
                    ) {
                        // silent-mode boxes auto-dismiss after 5 s and STAY UNREAD
                        // (§4.5 — deliberate divergence from G2CC's mark-at-display)
                        notifications.dropSilent()
                        val c = wallClock()
                        SilentMode.paintAll(comp.composed, layout, c.hh, c.mm)
                        comp.damageAll()
                        if (notifications.active) {
                            paintNotification()
                            val gen = ++silentDismissGen
                            scope.launch { delay(5_000); if (running) post(Msg.SilentTick(gen)) }
                        }
                    }
                    is Msg.SaveTick -> if (m.gen == saveGen) saveAll()
                    is Msg.Notice -> handleNotice(m.n)
                    is Msg.Invalidate -> if (mode != Mode.SILENT) composeContent()
                    is Msg.FlushSubmitted -> {
                        journal.flushSubmitted(m.id, m.a, m.label)
                        val early = earlyDone.remove(m.id)
                        if (early != null) completeFlush(early, m.a)
                        else inflightFlushes[m.id] = m.a
                    }
                    Msg.Pump -> {}
                }
            } catch (e: Exception) {
                // LOUD AND PROUD: a shell-loop error reaches the status bar, the
                // journal, and the log — and the loop keeps serving input.
                Log.e("shell", "loop error handling $m", e)
                journal.note("error", e.toString())
                statusText = "ERROR ${e.message ?: e::class.simpleName}"
            }
            try {
                pump()
            } catch (e: Exception) {
                Log.e("shell", "pump error", e)
                journal.note("pump-error", e.toString())
                statusText = "ERROR ${e.message ?: e::class.simpleName}"
            }
            queued.decrementAndGet()
        }
    }

    // ------------------------------------------------------------------ input
    private fun handleInput(type: Int) {
        val e = wm.damage.core.wire.EvenHubMsg
        inputEcho = when (type) {
            e.EV_CLICK -> "tap"
            e.EV_DOUBLE_CLICK -> "double"
            e.EV_SCROLL_TOP -> "up"
            e.EV_SCROLL_BOTTOM -> "down"
            e.EV_RING_LONG_PRESS -> "hold"
            e.EV_RING_LONG_PRESS_RELEASE -> "release"
            else -> "ev$type"
        }
        // input restarts the grace and the rest transition (§4.5, §4.2)
        restGen++
        scheduleRest()
        if (main.resting) { main.resting = false; if (mode == Mode.MAIN) composeContent() }
        if (notifications.active && !notifications.focused) scheduleGrace()

        // SILENT: everything swallowed except double-tap (§1.5)
        if (mode == Mode.SILENT) {
            if (type == e.EV_DOUBLE_CLICK) exitSilent()
            return
        }
        // Notification holds focus: its own gesture table (§4.5)
        if (notifications.active && notifications.focused) {
            when (type) {
                e.EV_CLICK -> {
                    val n = notifications.current
                    if (n != null && n.emergency) dismissNotice(markRead = true)
                    else if (n?.appId != null) {
                        dismissNotice(markRead = true)
                        windows.firstOrNull { it.id == n.appId }?.let { commitWindow(it) }
                    } else dismissNotice(markRead = true)
                }
                e.EV_DOUBLE_CLICK -> dismissNotice(markRead = true)
                e.EV_RING_LONG_PRESS -> dismissNotice(markRead = false)
                e.EV_SCROLL_TOP -> { notifications.scrollBody(-1, layout); paintNotification() }
                e.EV_SCROLL_BOTTOM -> { notifications.scrollBody(1, layout); paintNotification() }
            }
            return
        }
        // Switcher open: §1.3 grammar
        if (switcher.open) {
            when (type) {
                e.EV_SCROLL_TOP -> { switcher.scroll(-1); paintSwitcherFrame() }
                e.EV_SCROLL_BOTTOM -> { switcher.scroll(1); paintSwitcherFrame() }
                e.EV_CLICK -> commitSwitcher()
                e.EV_RING_LONG_PRESS, e.EV_DOUBLE_CLICK -> cancelSwitcher()
            }
            return
        }
        when (type) {
            e.EV_RING_LONG_PRESS -> openSwitcher()
            e.EV_SCROLL_TOP -> scrollFocused(-1)
            e.EV_SCROLL_BOTTOM -> scrollFocused(1)
            e.EV_CLICK -> tapFocused()
            e.EV_DOUBLE_CLICK -> backFocused()
            e.EV_RING_LONG_PRESS_RELEASE -> {}      // banked, unused (§1.2)
        }
    }

    private fun scrollFocused(delta: Int) {
        if (mode == Mode.WINDOW && current === settingsWindow && settingsWindow.onScrollAdjust(delta)) {
            applySettings(settings)          // repaint with live value
            composeContent()
            return
        }
        when (val v = focusedView()) {
            is WindowView.ListView -> {
                val n = v.rowCount()
                if (n <= 1) return
                v.model.cursor = (v.model.cursor + delta).mod(n)
                startListSlide(delta)
            }
            is WindowView.DocView -> {
                val lines = kit.visibleLines(layout, v)
                val maxTop = maxOf(0, v.lineCount() - lines)
                val old = v.model.topLine
                val top = (old + delta).coerceIn(0, maxTop)
                if (top == old) return
                v.model.topLine = top
                startDocSlide(v, (top - old) * v.lineHeight)
            }
            is WindowView.CanvasView -> {}
            null -> {}
        }
        scheduleSave()
    }

    private fun tapFocused() {
        if (mode == Mode.WINDOW && current === settingsWindow && settingsWindow.onTapAdjust()) {
            composeContent()
            scheduleSave()
            return
        }
        when (val v = focusedView()) {
            is WindowView.ListView -> v.onCommit(v.model.cursor)
            is WindowView.DocView -> v.onTap()
            else -> {}
        }
        composeContent()
        scheduleSave()
    }

    private fun backFocused() {
        when (mode) {
            Mode.WINDOW -> {
                val w = current ?: return
                if (!w.back()) {
                    w.onDeactivate()
                    mode = Mode.MAIN
                    current = null
                }
                composeContent()
            }
            Mode.MAIN -> enterSilent()
            Mode.SILENT -> exitSilent()
        }
        scheduleSave()
    }

    private fun focusedView(): WindowView? = when (mode) {
        Mode.MAIN -> main.view()
        Mode.WINDOW -> current?.view()
        Mode.SILENT -> null
    }

    // ------------------------------------------------------------------ modes
    private fun commitWindow(w: DamageWindow) {
        if (w === current && mode == Mode.WINDOW) return
        current?.onDeactivate()
        current = w
        mode = Mode.WINDOW
        recency.remove(w)
        recency.add(0, w)
        w.dirty = false
        w.onActivate(services)
        // activation auto-marks that app's notifications read (§4.5) — commit
        // only; preview never does this
        notifications.markAppRead(w.id)
        composeContent()
        scheduleSave()
    }

    private fun enterSilent() {
        mode = Mode.SILENT
        val c = wallClock()
        SilentMode.paintAll(comp.composed, layout, c.hh, c.mm)
        comp.planes = emptyList()
        comp.damageAll()
    }

    private fun exitSilent() {
        mode = Mode.MAIN
        main.resting = false
        composeFullSurface()
    }

    // ------------------------------------------------------------------ switcher
    private fun openSwitcher() {
        switcher.openWith(if (mode == Mode.WINDOW) current else null, recency)
        updatePlanes()
        paintSwitcherFrame()
    }

    private fun commitSwitcher() {
        val sel = switcher.selected() ?: return cancelSwitcher()
        val target = sel.window
        switcher.close()
        if (target == null) {
            current?.onDeactivate()
            current = null
            mode = Mode.MAIN
            composeContent()      // §4.3: commit repaints the panel region — the
        } else {                  // preview already painted the rest
            commitWindow(target)
        }
        updatePlanes()
    }

    private fun cancelSwitcher() {
        switcher.close()
        previewPainted = null
        updatePlanes()
        composeContent()          // restore what we came from (the expensive path)
    }

    private fun paintSwitcherFrame() {
        val panel = switcher.paint(comp.composed, layout)
        comp.damage(panel)
        syncChromeForPreview()
    }

    /** Live preview (§4.3): the window BEHIND the panel becomes the selected
     *  one — a RENDER, never an activation; lowest-priority, runs on settle. */
    private fun settlePreview() {
        if (!switcher.open || switcher.spinning) return
        val sel = switcher.selected() ?: return
        val key = sel.window?.id ?: "@main"
        if (previewPainted == key) return
        previewPainted = key
        paintContentOf(sel.window)          // no lifecycle hooks — render only
        paintSwitcherFrame()
    }

    private fun syncChromeForPreview() {
        // the top bar previews too, snapping rather than animating (§4.3)
        chromeDirty = true
    }

    // ------------------------------------------------------------------ notices
    private fun handleNotice(n: Notifications.Notice) {
        if (mode == Mode.SILENT) {
            notifications.post(n)
            paintNotification()
            val gen = ++silentDismissGen
            scope.launch { delay(5_000); post(Msg.SilentTick(gen)) }
            return
        }
        val hadFocusableBox = notifications.active
        notifications.post(n)
        if (!hadFocusableBox) {
            updatePlanes()
            scheduleGrace()
        }
        paintNotification()
    }

    private fun dismissNotice(markRead: Boolean) {
        notifications.dismiss(markRead)
        // the furl animation runs via pump; planes update when it completes
    }

    private fun paintNotification() {
        val n = notifications.current ?: return
        val box = notifications.paint(comp.composed, layout, silent = mode == Mode.SILENT)
        if (box != null) comp.damage(box)
    }

    // ------------------------------------------------------------------ ticks
    private fun handleMinute() {
        scheduleMinuteTick()
        if (mode == Mode.SILENT) {
            val c = wallClock()
            SilentMode.paintClock(comp.composed, layout, c.hh, c.mm)
            comp.damage(SilentMode.clockRect(layout))
        } else {
            chromeDirty = true
        }
    }

    private fun handleIdle() {
        chromeDirty = true      // chrome-only changes flush on the idle tick (§8.3)
    }

    private var chromeDirty = true

    private fun scheduleMinuteTick() {
        scope.launch {
            val ms = 60_000 - (System.currentTimeMillis() % 60_000)
            delay(ms)
            if (running) post(Msg.MinuteTick)
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

    private fun scheduleSave() {
        val gen = ++saveGen
        scope.launch { delay(2_000); if (running) post(Msg.SaveTick(gen)) }
    }

    // ------------------------------------------------------------------ transport
    private fun handleTransport(ev: TransportEvent) {
        when (ev) {
            is TransportEvent.Input -> handleInput(ev.type)
            is TransportEvent.FlushDone -> {
                val a = inflightFlushes.remove(ev.id)
                if (a == null && ev.id >= 0) {
                    // completion outran the FlushSubmitted record — park it
                    earlyDone[ev.id] = ev
                } else {
                    completeFlush(ev, a)
                }
            }
            is TransportEvent.Lease -> {
                if (!ev.held) {
                    // fail-open fired: stock repainted; a keyframe is required on
                    // reacquire (§5.16) and the failure is surfaced loudly
                    statusText = "LEASE LOST"
                    services.notifyInternal("lease", "framebuffer lease lost — ${ev.detail}", urgent = true)
                    comp.requestKeyframe()
                } else if (statusText == "LEASE LOST") {
                    statusText = "ok"
                }
                chromeDirty = true
            }
            is TransportEvent.Link -> {
                statusText = if (ev.connected) "ok" else "LINK DOWN"
                chromeDirty = true
            }
            is TransportEvent.DiagFlags -> {
                val setFlags = ev.flags.filterValues { it }.keys
                if (setFlags.isNotEmpty()) {
                    // any sticky flag is a HARD error: panic keyframe (§9.2b)
                    statusText = "PANIC ${setFlags.joinToString("/")}"
                    journal.note("panic", setFlags.joinToString())
                    services.notifyInternal("diag", "divergence flags: $setFlags — keyframing", urgent = true)
                    comp.requestKeyframe()
                    scope.launch { transport.clearDiagFlags() }
                }
            }
            is TransportEvent.Fault -> {
                statusText = "${ev.what}!"
                journal.note("fault", "${ev.what}: ${ev.detail}")
            }
        }
    }

    private fun completeFlush(ev: TransportEvent.FlushDone, a: Compositor.Assembled?) {
        journal.flushDone(ev.id, ev.ok, ev.ackMs, ev.bytes, ev.error)
        if (!ev.ok) {
            statusText = "flush failed"
            Log.e("shell", "flush ${ev.id} FAILED: ${ev.error}")
            if (a != null) comp.rollback(a)
            if (++flushFailStreak >= 3) {
                // three consecutive failures: stop re-partitioning the same
                // damage and reset with a keyframe, loudly
                statusText = "PANIC resync"
                journal.note("panic", "flush failure streak — keyframing")
                services.notifyInternal("compositor",
                    "repeated flush failures (${ev.error}) — keyframe resync", urgent = true)
                comp.requestKeyframe()
                flushFailStreak = 0
            }
        } else {
            flushFailStreak = 0
            if (a != null) comp.keyframeDelivered(a)
        }
    }

    // ------------------------------------------------------------------ compose
    private fun layoutFor(s: ShellSettings): Layout =
        if (s.heightMode >= Geometry.PANEL_H) Layout()
        else Layout().withHeightMode(s.heightMode, s.vpos)

    private fun applySettings(s: ShellSettings) {
        val relayout = s.heightMode != settings.heightMode || s.vpos != settings.vpos
        val rescale = s.fontScale != settings.fontScale
        settings = s
        if (rescale) for (w in windows) w.onFontScaleChanged(s.fontScale)
        persistence.put("shell.settings", s.toJson())
        if (relayout) {
            layout = layoutFor(s)
            chrome.invalidate()
            kit.resetRail()
            comp.composed.clear(0)
            composeFullSurface()
            comp.requestKeyframe()   // a size change re-lays out the whole shell (§4.2)
        } else {
            updatePlanes()
            composeContent()
        }
        scheduleSave()
    }

    /** Repaint chrome + content from scratch (mode changes, boot, relayout). */
    private fun composeFullSurface() {
        if (mode == Mode.SILENT) {
            val c = wallClock()
            SilentMode.paintAll(comp.composed, layout, c.hh, c.mm)
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
        if (mode == Mode.SILENT) return
        slides = emptyList()
        paintContentOf(if (mode == Mode.WINDOW) current else null)
        if (switcher.open) paintSwitcherFrame()
        if (notifications.active) paintNotification()
        updatePlanes()
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
            is WindowView.CanvasView -> {
                v.paint(comp.composed, layout.content)
                comp.damage(layout.content)
            }
        }
    }

    private fun updatePlanes() {
        val d = settings.depth
        val planes = ArrayList<Compositor.PlaneRegion>()
        if (d != 0 && mode != Mode.SILENT) {
            planes.add(Compositor.PlaneRegion(layout.content, d))          // content parks far
            val v = focusedView()
            if (v is WindowView.ListView) {
                planes.add(Compositor.PlaneRegion(layout.lens, 0))          // lens comes forward
            }
            if (switcher.open) {
                planes.add(Compositor.PlaneRegion(layout.switcherPanel, d)) // neighbours with content
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
        comp.planes = planes
    }

    // ------------------------------------------------------------------ slides
    private fun startListSlide(delta: Int) {
        val bandAbove = Rect(layout.content.x, layout.content.y + Layout.CONTENT_PAD,
            layout.content.w - Layout.RAIL_W, layout.rowsAbove * Layout.ROW_H)
        val bandBelow = Rect(layout.content.x, layout.lens.bottom,
            layout.content.w - Layout.RAIL_W, layout.rowsBelow * Layout.ROW_H)
        if (slides.size != 2 || slides[0].region != bandAbove) {
            slides = listOf(
                Slide(comp, bandAbove) { g, y0, h -> paintListSlice(g, y0, h, above = true) },
                Slide(comp, bandBelow) { g, y0, h -> paintListSlice(g, y0, h, above = false) },
            )
        }
        for (s in slides) s.retarget(delta * Layout.ROW_H)
        // optimistic lens repaint (§5.11): the model already moved
        val v = focusedView()
        if (v is WindowView.ListView) {
            comp.composed.fillRect(layout.lens, Level.BG)
            comp.composed.fillRect(layout.lens.x, layout.lens.y, layout.lens.w - Layout.RAIL_W, 2, Level.DIM)
            comp.composed.fillRect(layout.lens.x, layout.lens.bottom - 2, layout.lens.w - Layout.RAIL_W, 2, Level.DIM)
            v.paintLens(comp.composed, Rect(layout.lens.x, layout.lens.y, layout.lens.w - Layout.RAIL_W, layout.lens.h), v.model.cursor)
            comp.damage(layout.lens)
            val n = v.rowCount()
            kit.paintRail(comp.composed, layout, if (n > 1) v.model.cursor.toDouble() / (n - 1) else 0.0,
                (layout.content.h * (layout.rowsAbove + layout.rowsBelow + 1) / maxOf(1, n)))
                ?.let { comp.damage(it) }
        }
    }

    /** Paint target-state list rows for a band-relative y-range. */
    private fun paintListSlice(g: Gray8, y0: Int, h: Int, above: Boolean) {
        val v = focusedView() as? WindowView.ListView ?: return
        val n = v.rowCount()
        if (n == 0) return
        val slots = layout.rowsAbove + layout.rowsBelow + 1
        val c = v.model.cursor
        g.fillRect(0, 0, g.w, g.h, Level.BG)
        val bandSlots = if (above) layout.rowsAbove else layout.rowsBelow
        var slot = Math.floorDiv(y0, Layout.ROW_H)
        while (slot * Layout.ROW_H < y0 + h) {
            if (slot in -1..bandSlots) {   // one extra row either side for slide overlap
                val idx = if (above) c - layout.rowsAbove + slot else c + 1 + slot
                val real = if (n > slots) idx.mod(n) else if (idx in 0 until n) idx else null
                if (real != null) {
                    val tmp = Gray8(g.w, Layout.ROW_H)
                    v.paintRow(tmp, real, Rect(0, 0, g.w, Layout.ROW_H), false)
                    g.blit(tmp, Rect(0, 0, g.w, Layout.ROW_H), 0, slot * Layout.ROW_H - y0)
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
            slides = listOf(Slide(comp, region) { g, y0, h -> paintDocSlice(g, v, y0, h) })
        }
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
                v.paintLine(tmp, idx, Rect(0, 0, g.w, v.lineHeight))
                g.blit(tmp, Rect(0, 0, g.w, v.lineHeight), 0, slot * v.lineHeight - y0)
            }
            slot++
        }
    }

    // ------------------------------------------------------------------ pump
    /** After every message: advance animations, ride chrome along, flush. */
    private suspend fun pump() {
        if (!running || !transport.state.value.started) return
        val st = transport.state.value
        val room = st.inFlight < st.window
        if (!room) return         // FlushDone re-pumps; §5.13 coalescing happens here

        // 1. animations first (they are already-committed motion)
        var animated = false
        if (switcher.open && switcher.spinning) {
            switcher.stepSpin()
            paintSwitcherFrame()
            animated = true
        }
        if (notifications.animating) {
            val n0 = notifications.current
            val more = notifications.stepUnfurl()
            if (notifications.current == null) {
                // furl finished with an empty queue: restore what was beneath
                updatePlanes()
                if (mode == Mode.SILENT) {
                    val c = wallClock()
                    SilentMode.paintAll(comp.composed, layout, c.hh, c.mm)
                    comp.damageAll()
                } else composeContent()
            } else {
                if (!more && !notifications.focused && mode != Mode.SILENT) scheduleGrace()
                if (notifications.current !== n0) updatePlanes()
                paintNotification()
            }
            animated = true
        }
        for (s in slides) if (s.active) { s.step(comp.composed); animated = true }

        // 2. chrome rides along whenever anything else is flushing, and alone
        //    on the idle tick (§8.3)
        if (chromeDirty && (comp.hasPending || animated || mode != Mode.SILENT)) {
            if (mode != Mode.SILENT) {
                // One rect per BAR, never one per cell (§2.4 rule 2): union the
                // dirty cells of each bar before damaging.
                val cells = chrome.sync(comp.composed, layout, chromeState())
                val top = cells.filter { it.y < layout.topDivider.bottom }
                val bottom = cells.filter { it.y >= layout.bottomDivider.y }
                if (top.isNotEmpty()) comp.damage(top.reduce(wm.damage.core.geom.Rect::union))
                if (bottom.isNotEmpty()) comp.damage(bottom.reduce(wm.damage.core.geom.Rect::union))
            }
            chromeDirty = false
        }

        // 3. the one atomic flush
        if (comp.hasPending || comp.needsKeyframe) {
            val budget = Geometry.rectBudget(st.window)
            val assembled = try {
                comp.assembleFlush(budget)
            } catch (e: Exception) {
                Log.e("shell", "flush assembly failed", e)
                journal.note("assemble-error", e.toString())
                statusText = "ASSEMBLE ${e.message}"
                null
            }
            if (assembled != null) {
                val label = "$mode${if (switcher.open) "+switcher" else ""}"
                pendingSubmits.incrementAndGet()
                scope.launch {
                    try {
                        val id = transport.submit(FlushRequest(assembled.ops, assembled.epoch, label,
                            wide = assembled.wide || assembled.keyframe))
                        post(Msg.FlushSubmitted(id, assembled, label))
                        post(Msg.Pump)     // keep animations advancing
                    } catch (e: Exception) {
                        Log.e("shell", "submit failed", e)
                        comp.rollback(assembled)
                        post(Msg.Pump)
                    } finally {
                        pendingSubmits.decrementAndGet()
                    }
                }
            }
        } else if (!animated) {
            // 4. nothing pending: the lowest-priority work — preview settle
            settlePreview()
        }
    }

    // ------------------------------------------------------------------ chrome
    private fun chromeState(): Chrome.State {
        val c = wallClock()
        val st = transport.state.value
        val rows = mainRows()
        val at = when (mode) {
            Mode.WINDOW -> rows.indexOf(current).coerceAtLeast(0)
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
            else -> "${rows.size} windows" + (rows.count { it.dirty }.takeIf { it > 0 }?.let { " · $it unread" } ?: "")
        }
        return Chrome.State(
            windowName = name,
            windowIcon = icon,
            context = context,
            clock = c.hhmm,
            clockAmPm = c.amPm,
            glasses = null,           // real batteries arrive with the phone bridge
            ring = null,
            phone = phoneBattery,
            windowCount = rows.size,
            windowAt = at,
            dirtyAt = rows.mapIndexedNotNull { i, w -> if (w.dirty) i else null }.toSet(),
            stackDepth = when (mode) {
                Mode.MAIN -> 1
                Mode.WINDOW -> 1 + (current?.levelDepth() ?: 1)
                Mode.SILENT -> 1
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

    private val pendingSubmits = java.util.concurrent.atomic.AtomicInteger(0)

    /** True when nothing is pending anywhere — test/selfcheck introspection. */
    fun isQuiescent(): Boolean =
        queued.get() == 0 && pendingSubmits.get() == 0 &&
            !comp.hasPending && !comp.needsKeyframe && inflightFlushes.isEmpty() &&
            earlyDone.isEmpty() && !notifications.animating &&
            slides.none { it.active } && !switcher.spinning

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

    private fun saveAll() {
        persistence.put("shell.settings", settings.toJson())
        persistence.put("shell.state", buildJsonObject {
            put("current", current?.id ?: "")
        })
        for (w in windows) {
            try {
                persistence.put("window.${w.id}", w.saveState())
            } catch (e: Exception) {
                Log.e("shell", "saveState of ${w.id} failed", e)
            }
        }
        persistence.save()
    }

    companion object {
        fun systemClock(): LocalClock {
            val now = java.time.LocalTime.now()
            val h12 = if (now.hour % 12 == 0) 12 else now.hour % 12
            return LocalClock(now.hour, now.minute,
                "%d:%02d".format(h12, now.minute), if (now.hour < 12) "AM" else "PM")
        }
    }
}
