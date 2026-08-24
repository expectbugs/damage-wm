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
    private val chrome = Chrome(text)
    private val journal = Journal(journalPath)
    val notifications = Notifications(text)
    private val switcher = Switcher(text)

    private val windows = ArrayList<DamageWindow>()
    private val recency = ArrayList<DamageWindow>()          // most recent first
    private lateinit var settingsWindow: SettingsWindow
    private val main = MainSurface(text, { mainRows() }, { commitWindow(it) }, { settings.presence })

    private enum class Mode { MAIN, WINDOW, SILENT }
    private var mode = Mode.MAIN
    private var current: DamageWindow? = null

    private var slides: List<Slide> = emptyList()

    private var inputEcho = ""
    private var opText = "idle"
    private var statusText = "ok"
    private var restGen = 0
    private var graceGen = 0
    private var silentDismissGen = 0
    private var saveGen = 0
    private var previewPainted: String? = null
    @Volatile private var running = false
    @Volatile private var loopLaunched = false

    /** Host-link status line for the status bar (set by the content client). */
    @Volatile var hostState: String = ""

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
        data class Run(val action: () -> Unit) : Msg()
        data class Shutdown(val done: CompletableDeferred<Unit>) : Msg()
        object Pump : Msg()
    }

    val services: ShellServices = object : ShellServices {
        override fun requestRender(window: DamageWindow) { post(Msg.Invalidate(window.id)) }

        override fun setOperation(op: String) {
            post(Msg.Run { if (opText != op) { opText = op; chromeDirty = true } })
        }

        override fun notifyInternal(source: String, body: String, urgent: Boolean) {
            if (urgent) onUrgent?.invoke(source, body)
            val c = wallClock()
            post(Msg.Notice(Notifications.Notice("DAMAGE · $source", source, body, c.hhmm,
                emergency = false)))
        }

        override fun runOnShell(action: () -> Unit) = post(Msg.Run(action))

        override fun docContentWidth(): Int = layout.contentInner.w - 32
    }

    private fun post(m: Msg) {
        queued.incrementAndGet()
        msgs.trySend(m)
    }

    private fun setStatus(s: String) {
        if (statusText != s) {
            statusText = s
            chromeDirty = true
        }
    }

    // ------------------------------------------------------------------ set-up
    fun register(w: DamageWindow) {
        windows.add(w)
    }

    private fun mainRows(): List<DamageWindow> =
        windows.filter { it !== settingsWindow } + settingsWindow

    /** External notice entry (content client, phone bridge, tests). */
    fun postNotice(n: Notifications.Notice) = post(Msg.Notice(n))

    /** External input entry (keyboard harness, touch harness, remote shell). */
    fun postGesture(type: Int, source: Int = EvenHubMsg.SRC_RING) =
        post(Msg.In(type, source))

    /** start/stop are serialized (round 3, phone D1): a stop arriving while
     *  start is still choreographing the display would otherwise save state
     *  that was never loaded, and leave two drivers on one transport. */
    private val lifecycle = Mutex()
    private var stateLoaded = false

    suspend fun start() = lifecycle.withLock { startLocked() }

    private suspend fun startLocked() {
        running = true
        settingsWindow = SettingsWindow(text, { settings }, { applySettings(it) })
        register(settingsWindow)
        for (w in windows) w.onRegistered(services)

        // restore persisted state (§9.1: survives WM restart) BEFORE the loop
        persistence.load()
        stateLoaded = true
        settings = ShellSettings.fromJson(persistence.get("shell.settings"))
        layout = layoutFor(settings)
        for (w in windows) persistence.get("window.${w.id}")?.let {
            try { w.restoreState(it) } catch (e: Exception) {
                Log.e("shell", "restore of ${w.id} failed — window starts fresh", e)
            }
        }
        val shellState = persistence.get("shell.state")
        val restoredId = shellState?.get("current")?.jsonPrimitive?.contentOrNull
        val restoredMode = shellState?.get("mode")?.jsonPrimitive?.contentOrNull
        main.model.cursor = shellState?.get("mainCursor")?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull() ?: 0
        restoreNotices(shellState)

        // UNDISPATCHED: the collector subscribes BEFORE transport.start() can
        // emit, or early events (capability, lease) would vanish silently.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            transport.events.collect { post(Msg.Trans(it)) }
        }

        // start the display: capability gate + carrier + lease + warmup splash
        try {
            transport.start(splashFrame())
        } catch (e: Exception) {
            // refuse-to-start (capability gate, link failure): leave the shell
            // stopped, not half-running with no loop (review round 2 #B1)
            running = false
            journal.close()
            throw e
        }

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
                current!!.onActivate(services)
            }
            if (restoredMode == Mode.SILENT.name) mode = Mode.SILENT
            composeFullSurface()
            comp.requestKeyframe()
            if (notifications.showNextIfIdle()) {
                if (mode == Mode.SILENT) scheduleSilentDismiss() else scheduleGrace()
                updatePlanes()   // the restored box enters the plane map now (round 3 S3)
            }

            scope.launch { loop() }
            loopLaunched = true
            scheduleMinuteTick()
            scope.launch { while (isActive && running) { delay(5_000); post(Msg.IdleTick) } }
            scheduleRest()
            post(Msg.Pump)
        } catch (e: Exception) {
            // the display is up but the shell could not finish assembling
            // itself (a window's activation or first paint refused): leave
            // nothing running or leased behind (round 3 S4)
            running = false
            try { transport.stop() } catch (s: Exception) {
                Log.e("shell", "transport stop after a failed start", s)
            }
            journal.close()
            throw e
        }
    }

    /** Orderly shutdown THROUGH the loop, so the final save cannot race a
     *  concurrent SaveTick (review round 1) and no state mutates cross-thread.
     *  If the loop never launched (start() threw first) there is nothing to
     *  post to — awaiting the Shutdown message would hang forever (round 2
     *  #B1); clean up directly instead. */
    suspend fun stop() = lifecycle.withLock { stopLocked() }

    private suspend fun stopLocked() {
        if (!running) return
        if (!loopLaunched) {
            running = false
            if (stateLoaded) saveAll()   // never write defaults over a state never read
            transport.stop()
            journal.close()
            return
        }
        val done = CompletableDeferred<Unit>()
        post(Msg.Shutdown(done))
        done.await()
        transport.stop()
        journal.close()
    }

    // ------------------------------------------------------------------ loop
    private suspend fun loop() {
        for (m in msgs) {
            if (!running && m !is Msg.Shutdown) { queued.decrementAndGet(); continue }
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
                    is Msg.Invalidate -> if (mode != Mode.SILENT) composeContent()
                    is Msg.Run -> m.action()
                    is Msg.Shutdown -> {
                        running = false
                        saveAll()
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
            }
            try {
                if (running) pump()
            } catch (e: Exception) {
                Log.e("shell", "pump error", e)
                journal.note("pump-error", e.toString())
                setStatus("ERROR ${e.message ?: e::class.simpleName}")
            }
            queued.decrementAndGet()
            if (!running && m is Msg.Shutdown) break
        }
    }

    // ------------------------------------------------------------------ input
    private fun handleInput(type: Int, source: Int) {
        // §1: the R1 ring is the ONLY input device — a temple brush must not
        // select or scroll. Text-region scroll events carry no source and
        // arrive as SRC_RING from the transport (G2_BLE_PROTOCOL.md §6.6).
        if (source != EvenHubMsg.SRC_RING) {
            Log.d("shell", "non-ring gesture $type from source $source ignored (§1)")
            return
        }
        val newEcho = when (type) {
            EvenHubMsg.EV_CLICK -> "tap"
            EvenHubMsg.EV_DOUBLE_CLICK -> "double"
            EvenHubMsg.EV_SCROLL_TOP -> "up"
            EvenHubMsg.EV_SCROLL_BOTTOM -> "down"
            EvenHubMsg.EV_RING_LONG_PRESS -> "hold"
            EvenHubMsg.EV_RING_LONG_PRESS_RELEASE -> "release"
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
        if (notifications.active && !notifications.focused && mode != Mode.SILENT) scheduleGrace()

        // SILENT: everything swallowed except double-tap (§1.5 — the gloves fix)
        if (mode == Mode.SILENT) {
            if (type == EvenHubMsg.EV_DOUBLE_CLICK) exitSilent()
            return
        }
        // Notification holds focus: its own gesture table (§4.5)
        if (notifications.active && notifications.focused) {
            handleNotificationGesture(type)
            return
        }
        // Switcher open: §1.3 grammar
        if (switcher.open) {
            when (type) {
                EvenHubMsg.EV_SCROLL_TOP -> { switcher.scroll(-1); paintSwitcherFrame() }
                EvenHubMsg.EV_SCROLL_BOTTOM -> { switcher.scroll(1); paintSwitcherFrame() }
                EvenHubMsg.EV_CLICK -> commitSwitcher()
                EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_DOUBLE_CLICK -> cancelSwitcher()
            }
            return
        }
        when (type) {
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
                    dismissNotice(markRead = true)
                    windows.firstOrNull { it.id == n.appId }?.let { commitWindow(it) }
                } else {
                    // emergencies and app-less notices: tap = dismiss (§4.5 —
                    // "there's no app to switch to for those")
                    dismissNotice(markRead = true)
                }
            }
            EvenHubMsg.EV_DOUBLE_CLICK -> dismissNotice(markRead = true)
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
                val top = (old + delta).coerceIn(0, maxTop)
                if (top == old) return
                v.model.topLine = top
                liftNotificationBox()
                startDocSlide(v, (top - old) * v.lineHeight)
            }
            is WindowView.CanvasView -> {}
            null -> {}
        }
        scheduleSave()
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
        if (switcher.open) {
            switcher.close()
            previewPainted = null
        }
        if (w === current && mode == Mode.WINDOW) {
            composeContent()      // e.g. committing the switcher to the current
            return                // window must still erase the panel
        }
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
        settleSlidesForOverlay()
        slides = emptyList()
        // an on-screen box goes back to the queue UNREAD; silent shows its own
        // smaller form, one at a time, auto-dismissing (§1.5/§4.5)
        notifications.requeueCurrent()
        val c = wallClock()
        SilentMode.paintAll(comp.composed, layout, c.hh, c.mm)
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
        composeFullSurface()
        if (notifications.active && !notifications.focused) scheduleGrace()
        scheduleSave()
    }

    // ------------------------------------------------------------------ switcher
    private fun openSwitcher() {
        settleSlidesForOverlay()
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
            current?.onDeactivate()
            current = null
            mode = Mode.MAIN
            composeContent()      // §4.3: the preview already painted the rest;
        } else {                  // this erases the panel region
            commitWindow(target)
        }
        updatePlanes()
        scheduleSave()
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
    }

    // ------------------------------------------------------------------ notices
    private fun handleNotice(n: Notifications.Notice) {
        if (!noticeAllowed(n)) {
            Log.i("shell", "notification from '${n.source}' filtered by settings (§4.5)")
            return
        }
        if (mode == Mode.SILENT) {
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
            "MUSIC" -> settings.notifyMusic
            "DAMAGE" -> settings.notifyDamage
            else -> true
        }
    }

    private fun dismissNotice(markRead: Boolean) {
        settleSlidesForOverlay()      // the furl restores from a snapshot:
        notifications.dismiss(markRead)                    // settle the base
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
        val silent = mode == Mode.SILENT
        val full = notifications.fullRect(n, layout, silent)
        notifications.captureUnder(comp.composed, full)
        val box = notifications.paint(comp.composed, layout, silent)
        if (box != null) comp.damage(box)
    }

    private fun handleGrace(gen: Int) {
        if (gen != graceGen) return
        if (mode == Mode.SILENT) return                  // §4.5 rule 4
        if (switcher.open) { scheduleGrace(); return }   // never steal the wheel's gestures
        if (notifications.active && !notifications.focused) {
            notifications.takeFocus()
            updatePlanes()        // the box steps FORWARD (§4.5); the plane-map
            paintNotification()   // diff re-renders it and cleans its old seams
        }
    }

    private fun handleSilentTick(gen: Int) {
        if (gen != silentDismissGen || mode != Mode.SILENT || !notifications.active) return
        // silent boxes auto-dismiss after 5 s and STAY UNREAD (§4.5 —
        // deliberate divergence from G2CC's mark-at-display)
        notifications.dropSilent()
        val c = wallClock()
        SilentMode.paintAll(comp.composed, layout, c.hh, c.mm)
        comp.damageAll()
        if (notifications.active) {
            paintNotification()
            scheduleSilentDismiss()
        }
    }

    // ------------------------------------------------------------------ ticks
    private fun handleMinute() {
        scheduleMinuteTick()
        if (mode == Mode.SILENT) {
            val c = wallClock()
            SilentMode.paintClock(comp.composed, layout, c.hh, c.mm)
            comp.damage(SilentMode.clockRect(layout))    // the 60-per-hour flush
        } else {
            chromeDirty = true                           // rides or waits for idle
        }
    }

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
            is TransportEvent.FlushDone -> completeFlush(ev)
            is TransportEvent.Lease -> {
                if (!ev.held) {
                    // fail-open fired: stock repainted; a keyframe is required on
                    // reacquire (§5.16) and the failure is surfaced loudly
                    setStatus("LEASE LOST")
                    services.notifyInternal("lease", "framebuffer lease lost — ${ev.detail}", urgent = true)
                    comp.requestKeyframe()
                } else if (statusText == "LEASE LOST") {
                    setStatus("ok")
                }
                chromeDirty = true
            }
            is TransportEvent.Link -> {
                setStatus(if (ev.connected) "ok" else "LINK DOWN")
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
                setStatus("${ev.what}!")
                journal.note("fault", "${ev.what}: ${ev.detail}")
            }
        }
    }

    private fun completeFlush(ev: TransportEvent.FlushDone) {
        val a = inflightFlushes.remove(ev.id)
        journal.flushDone(ev.id, ev.ok, ev.ackMs, ev.bytes, ev.error)
        if (!ev.ok) {
            setStatus("flush failed")
            Log.e("shell", "flush ${ev.id} FAILED: ${ev.error}")
            if (a != null) comp.rollback(a)
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
        persistence.put("shell.settings", s.toJson())
        if (rescale) for (w in windows) w.onFontScaleChanged(s.fontScale)
        if (relayout) {
            layout = layoutFor(s)
            chrome.invalidate()
            kit.resetRail()
            slides = emptyList()
            for (w in windows) w.onLayoutChanged()
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
        notifications.invalidateUnder()   // the content beneath is repainting
        paintContentOf(if (mode == Mode.WINDOW) current else null)
        if (switcher.open) paintSwitcherFrame()
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
            if (focusedView() is WindowView.ListView) {
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
        comp.planes = planes   // a changed map re-renders + seam-cleans itself
    }

    // ------------------------------------------------------------------ slides
    private fun snapSlides() {
        for (s in slides) s.snap(comp.composed)
    }

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
    /** After every message: animations first (slides under, overlays over),
     *  chrome rides along, then the one atomic flush; preview settles last. */
    private suspend fun pump() {
        if (!running || !transport.state.value.started) return
        val st = transport.state.value
        val room = st.inFlight < st.window
        if (!room) return         // FlushDone re-pumps; §5.13 coalescing happens here

        var animated = false

        // 1. slides move the content UNDER everything — lifting a floating
        //    notification box out of the band first (#A4; step 3 repaints it)
        if (slides.any { it.active }) liftNotificationBox()
        for (s in slides) if (s.active) { s.step(comp.composed); animated = true }

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
            val silent = mode == Mode.SILENT
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
                if (mode == Mode.SILENT) {
                    notifications.invalidateUnder()
                    val c = wallClock()
                    SilentMode.paintAll(comp.composed, layout, c.hh, c.mm)
                    comp.damageAll()
                } else {
                    val restored = notifications.restoreUnderFinished(comp.composed)
                    if (restored != null) comp.damage(restored) else composeContent()
                }
                updatePlanes()
            }
            if (notifications.current != null) {
                if (!more && !notifications.focused && mode != Mode.SILENT) scheduleGrace()
                if (notifications.current !== n0) {
                    updatePlanes()
                    if (mode == Mode.SILENT) scheduleSilentDismiss()
                }
                paintNotification()
            }
            boxLifted = false
            animated = true
        } else if ((animated || boxLifted) && notifications.active) {
            paintNotification()   // overlays stay on top of slide frames
            boxLifted = false
        }

        // 4. chrome rides along with content, or flushes alone on the idle tick
        if (chromeDirty && mode != Mode.SILENT && (comp.hasPending || animated || chromeIdleFlush)) {
            // One rect per BAR, never one per cell (§2.4 rule 2)
            val cells = chrome.sync(comp.composed, layout, chromeState())
            val top = cells.filter { it.y < layout.topDivider.bottom }
            val bottom = cells.filter { it.y >= layout.bottomDivider.y }
            if (top.isNotEmpty()) comp.damage(top.reduce(Rect::union))
            if (bottom.isNotEmpty()) comp.damage(bottom.reduce(Rect::union))
            chromeDirty = false
            chromeIdleFlush = false
        }

        // 5. the one atomic flush per frame — the project's thesis
        if (comp.hasPending || comp.needsKeyframe) {
            val assembled = try {
                comp.assembleFlush(Geometry.rectBudget(st.window))
            } catch (e: Exception) {
                Log.e("shell", "flush assembly failed", e)
                journal.note("assemble-error", e.toString())
                setStatus("ASSEMBLE ${e.message}")
                null
            }
            if (assembled != null) {
                val label = "$mode${if (switcher.open) "+switcher" else ""}"
                try {
                    val id = transport.submit(FlushRequest(assembled.ops, assembled.epoch, label,
                        wide = assembled.wide))
                    inflightFlushes[id] = assembled
                    journal.flushSubmitted(id, assembled, label)
                } catch (e: Exception) {
                    Log.e("shell", "submit failed", e)
                    journal.note("submit-error", e.toString())
                    setStatus("SUBMIT ${e.message}")
                    comp.rollback(assembled)
                }
            }
        } else if (!animated) {
            // 6. nothing pending: the lowest-priority work — preview settle
            settlePreview()
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
            else -> "${rows.size} windows" +
                (rows.count { it.dirty }.takeIf { it > 0 }?.let { " · $it unread" } ?: "")
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

    /** The active window's id, or null at Main/silent — test introspection. */
    fun currentWindowId(): String? = if (mode == Mode.WINDOW) current?.id else null

    /** True when nothing is pending anywhere — test/selfcheck introspection. */
    fun isQuiescent(): Boolean =
        queued.get() == 0 && !comp.hasPending && !comp.needsKeyframe &&
            inflightFlushes.isEmpty() && !notifications.animating &&
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
            put("mode", mode.name)
            put("mainCursor", main.model.cursor.toString())
            put("notices", saveNotices())
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
                ))
            } catch (ex: Exception) {
                Log.w("shell", "unreadable persisted notice skipped: ${ex.message}")
            }
        }
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
