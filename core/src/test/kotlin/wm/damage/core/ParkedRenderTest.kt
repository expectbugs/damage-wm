package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import wm.damage.core.geom.Rect
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.DocModel
import wm.damage.core.shell.HostSetting
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.LinkState
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.wire.EvenHubMsg

/**
 * APK 0.65, P6 item 2 (the loop-cost sweep's LC-1, `scratchpad/sweeps/loop-cost-verified.md`): a render request
 * from a window the content area does not show recomposed the window that it does. Tmux at its session list asks on
 * every status push (every 2.8 s from the PC), so Now Playing was recomposed and flushed about 1,550 times in four
 * hours for nothing it asked for; a running slide was ended by the compose (`slides = emptyList()`), and an open menu
 * or keyboard was repainted. Fixed: the `Invalidate` arm composes only for the window shown (the wheel's previewed
 * entry while one is painted, else the current window), for Main (its rows are every window's summary), for
 * Settings (it reads every window's `appSettings()` on each render), and for a request that names no window.
 */
class ParkedRenderTest {
    private val tmp: Path = Files.createTempDirectory("parked-render")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private class Spy(private val inner: Transport) : Transport by inner {
        private val flushes = ArrayList<FlushRequest>()
        override suspend fun submit(flush: FlushRequest): Long {
            synchronized(flushes) { flushes.add(flush) }
            return inner.submit(flush)
        }
        fun all() = synchronized(flushes) { flushes.toList() }

        /** While on, the shell's pump reads every window slot as taken (a link whose acks are still on their way):
         *  no pump steps a slide or flushes. A snapshot of the state at the call; off, the link's own state again. */
        @Volatile private var full: StateFlow<LinkState>? = null
        fun windowFull(on: Boolean) {
            full = if (on) MutableStateFlow(inner.state.value.let { it.copy(inFlight = it.window) }) else null
        }
        override val state: StateFlow<LinkState> get() = full ?: inner.state
    }

    /** On screen: a canvas that counts its paints and draws a different band each time. */
    private class Counting : DamageWindow("counting", "Counting", IconKind.FILES) {
        val paints = AtomicInteger()
        var band = Rect(0, 0, 0, 0)
        override fun view(): WindowView = WindowView.CanvasView({ g, r ->
            val n = paints.incrementAndGet()
            band = Rect(r.x + 8, r.y + 8, 300, 16)
            g.fillRect(r.x, r.y, r.w, r.h, 0)
            g.fillRect(r.x + 8, r.y + 8, 40 + (n % 20) * 12, 16, 15 * 17)
        })
        override fun summary() = Summary("counting")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /** Parked: counts what Main (its summary) and Settings (its rows) read of it. */
    private class Parked : DamageWindow("parked", "Parked", IconKind.FILES) {
        val summaries = AtomicInteger()
        val settingsReads = AtomicInteger()
        override fun view(): WindowView = WindowView.CanvasView({ _, _ -> })
        override fun summary() = Summary("parked ${summaries.incrementAndGet()}")
        override fun appSettings(): List<HostSetting> {
            settingsReads.incrementAndGet()
            return listOf(HostSetting("Pace", listOf("slow", "fast"), { "slow" }, {}))
        }
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private class Doc : DamageWindow("doc", "Doc", IconKind.READER) {
        private val model = DocModel()
        override fun view(): WindowView = WindowView.DocView(model, { 400 }, 24,
            { g, i, r -> g.fillRect(r.x + 4, r.y + 4, 60 + (i * 37) % 400, 14, ((i % 10) + 3) * 17) }, {}, stepLines = { 6 })
        override fun summary() = Summary("doc")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(scope: CoroutineScope, tmp: Path, timing: SimTransport.Timing, vararg windows: DamageWindow) {
        val spy = Spy(SimTransport(GlassFirmwareSim(), scope, timing))
        val shell = Shell(FakeText(), spy, Persistence(tmp.resolve("state.json")), tmp.resolve("journal.jsonl"), scope)
        init { for (w in windows) shell.register(w) }
        suspend fun settle(what: String) =
            waitUntil(30_000, message = { "$what: the shell settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        suspend fun open(id: String) {
            shell.services.runOnShell { shell.services.openWindow(id, null) }
            settle("open $id")
        }
        /** Five render requests from [w] (each posts its `Invalidate` at once, as a window's push does from the
         *  loop), then one message behind them that reads [probe] on the loop after the five were handled. */
        suspend fun <T> fiveRequestsFrom(w: DamageWindow, probe: () -> T): T {
            repeat(5) { shell.services.requestRender(w) }
            val seen = CompletableDeferred<T>()
            shell.services.runOnShell { seen.complete(probe()) }
            return seen.await()
        }
    }

    private val instant = SimTransport.Timing(instant = true)

    /** The finding's pin: five requests from a parked window paint nothing on screen and flush nothing there.
     *  Unfixed: the window on screen is painted five times and its band flushed. */
    @Test
    fun aParkedWindowsRenderRequestComposesNothing(): Unit = runBlocking {
        val a = Counting(); val b = Parked()
        val rig = Rig(scope, tmp, instant, a, b)
        rig.shell.start(); rig.settle("start")
        rig.open("counting")
        val paints = a.paints.get(); val flushes = rig.spy.all().size
        rig.fiveRequestsFrom(b) {}
        rig.settle("after the requests")
        val band = a.band
        val touching = rig.spy.all().drop(flushes).count { f -> f.ops.any { op -> when (op) {
            is DisplayOp.Delta -> op.box.overlaps(band)
            is DisplayOp.StereoPair -> op.left.overlaps(band) || op.right.overlaps(band)
            is DisplayOp.Keyframe -> true
            else -> false
        } } }
        assertEquals(paints, a.paints.get(), "the window on screen was not painted for a parked window's request")
        assertEquals(0, touching, "no flush carried the band of the window on screen")
        rig.shell.stop()
    }

    /** The challenge's addition: the same five while a document slide is running leave the slide to finish.
     *  Unfixed: the first request's compose empties `slides` and repaints the content at its final position.
     *  The window reads full from before the scroll, so the slide starts and no pump can step it: only a compose
     *  can end it, and both readings are taken on the loop. (0.65's final battery: the first form waited off the
     *  loop for the slide and then sent the requests. The simulated link frees a slot the moment its model answers,
     *  whatever `ackMs` says, so the slide's seven steps ran out within the pumps the requests themselves bring;
     *  the pin passed only when its poll caught the first steps — 2 misses in 40 runs of the class, 3 of 3 with
     *  100 ms added before the requests.) */
    @Test
    fun aParkedWindowsRenderRequestLeavesARunningSlideToFinish(): Unit = runBlocking {
        val d = Doc(); val b = Parked()
        val rig = Rig(scope, tmp, instant, d, b)
        rig.shell.start(); rig.settle("start")
        rig.shell.updateSettings { it.copy(slideFrames = "12") }
        rig.open("doc")
        rig.spy.windowFull(true)
        rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        val started = CompletableDeferred<Boolean>()
        rig.shell.services.runOnShell { started.complete("slides " in rig.shell.quiescenceReport()) }
        val running = rig.fiveRequestsFrom(b) { "slides " in rig.shell.quiescenceReport() }
        assertTrue(started.await(), "the scroll starts a slide, held by the full window")
        assertTrue(running, "the slide is still running after the five requests")
        rig.spy.windowFull(false)
        rig.shell.postIdleTick()          // a message with a pump behind it: the slide runs on
        rig.settle("the slide ends")
        rig.shell.stop()
    }

    /** The whole-panel window (Music Mode's form): a parked window's requests do not repaint it; its own still do.
     *  Unfixed: every request ran the exclusive window's paint. */
    @Test
    fun inExclusiveModeOnlyTheWindowsOwnRequestRepaintsIt(): Unit = runBlocking {
        val e = object : DamageWindow("whole", "Whole", IconKind.FILES) {
            val paints = AtomicInteger()
            override fun view(): WindowView = WindowView.CanvasView({ _, _ -> })
            override fun paintExclusive(g: wm.damage.core.gfx.Gray8, safe: Rect, full: Boolean): List<Rect> {
                val n = paints.incrementAndGet()
                val r = Rect(safe.x + 8, safe.y + 8, 40 + (n % 20) * 12, 16)
                g.fillRect(safe.x + 8, safe.y + 8, 300, 16, 0); g.fillRect(r.x, r.y, r.w, r.h, 15 * 17)
                return listOf(Rect(safe.x + 8, safe.y + 8, 300, 16))
            }
            override fun summary() = Summary("whole")
            override fun saveState(): JsonObject = buildJsonObject {}
            override fun restoreState(state: JsonObject) {}
        }
        val b = Parked()
        val rig = Rig(scope, tmp, instant, e, b)
        rig.shell.start(); rig.settle("start")
        rig.open("whole")
        val entered = CompletableDeferred<Boolean>()
        rig.shell.services.runOnShell { entered.complete(rig.shell.services.enterExclusive(e)) }
        assertTrue(entered.await(), "the window took the whole panel")
        rig.settle("exclusive")
        val before = e.paints.get()
        val afterParked = rig.fiveRequestsFrom(b) { e.paints.get() }
        assertEquals(before, afterParked, "a parked window's requests did not repaint the whole-panel window")
        val afterOwn = rig.fiveRequestsFrom(e) { e.paints.get() }
        assertTrue(afterOwn > afterParked, "its own requests repaint it: $afterParked then $afterOwn")
        rig.shell.stop()
    }

    /** Main's rows are every window's summary: a request from any window recomposes Main. */
    @Test
    fun onMainARequestFromAnyWindowRepaintsMain(): Unit = runBlocking {
        val a = Counting(); val b = Parked()
        val rig = Rig(scope, tmp, instant, a, b)
        rig.shell.start(); rig.settle("start")
        assertEquals(null, rig.shell.currentWindowId(), "the shell starts on Main")
        val before = b.summaries.get()
        val after = rig.fiveRequestsFrom(b) { b.summaries.get() }
        assertTrue(after > before, "Main was recomposed (it read the window's summary): $before then $after")
        rig.shell.stop()
    }

    /** Settings reads every window's `appSettings()` on each render: a parked window's request recomposes it. */
    @Test
    fun withSettingsOnScreenAParkedWindowsRequestRepaintsSettings(): Unit = runBlocking {
        val a = Counting(); val b = Parked()
        val rig = Rig(scope, tmp, instant, a, b)
        rig.shell.start(); rig.settle("start")
        rig.open("settings")
        assertEquals("settings", rig.shell.currentWindowId())
        val before = b.settingsReads.get()
        val after = rig.fiveRequestsFrom(b) { b.settingsReads.get() }
        assertTrue(after > before, "Settings was recomposed (it read the window's rows): $before then $after")
        rig.shell.stop()
    }

    /** A canvas whose picture is its state: a band whose width is [v]'s — a status push changes it. */
    private class Pushed : DamageWindow("pushed", "Pushed", IconKind.FILES) {
        @Volatile var v = 1
        override fun view(): WindowView = WindowView.CanvasView({ g, r ->
            g.fillRect(r.x, r.y, r.w, r.h, 0)
            g.fillRect(r.x + 8, r.y + 8, 40 + v * 60, 16, 15 * 17)
        })
        override fun summary() = Summary("pushed")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /**
     * R4 item 4 (review 3A-2): `contentWindow()` named the wheel's CURSOR, not what the area shows. The wheel
     * settled on X; a notch to Y moved the cursor at once while the area still showed X, so X's status push was
     * judged a parked window's request and composed nothing; a notch back to X inside the spin, and `settlePreview`
     * found its key unchanged and returned — X's preview stayed stale until the wheel closed. Fixed: the preview is
     * resolved from `previewPainted`. The three messages are posted from the loop itself so they are queued in order
     * behind one another and no settle can run between them (the spin holds it; `Review30Test`'s shape). Unfixed:
     * the band of X's old state stands outside the wheel's panel.
     */
    @Test
    fun aPushToTheWheelsPreviewInsideASpinOutAndBackRepaintsIt(): Unit = runBlocking {
        val x = Pushed(); val y = Parked()
        val rig = Rig(scope, tmp, instant, x, y)
        rig.shell.start(); rig.settle("start")
        rig.open("parked"); rig.open("pushed")
        rig.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)
        rig.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS_RELEASE)
        rig.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)              // §1.3's chord (long-press OFF, the default)
        rig.settle("the wheel is open (on the most recent inactive: Parked)")
        assertTrue(rig.shell.switcherIsOpen, "the chord opens the wheel")
        rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
        rig.settle("the wheel settled on Pushed")
        rig.shell.services.runOnShell {
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // a notch to Parked: the cursor moves at once
            x.v = 2
            rig.shell.services.requestRender(x)                   // Pushed's push, while the area still shows it
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)        // and back, inside the spin
        }
        rig.settle("the wheel settled back on Pushed")
        var stale: Int? = null                                     // one loop sample with nothing queued behind it
        var tries = 0
        while (stale == null && tries++ < 2_000) {
            rig.settle("sample")
            stale = rig.shell.sampleIdle {
                val content = rig.shell.layout.content
                val p = rig.shell.layout.switcherPanel
                val fresh = wm.damage.core.gfx.Gray8(content.w, content.h)
                (x.view() as WindowView.CanvasView).paint(fresh, Rect(0, 0, content.w, content.h))
                val g = rig.shell.comp.composed
                var n = 0
                for (yy in content.y until content.bottom) for (xx in content.x until content.right) {
                    val inPanel = xx >= p.x && xx < p.right && yy >= p.y && yy < p.bottom
                    if (!inPanel && g[xx, yy] != fresh[xx - content.x, yy - content.y]) n++
                }
                n
            }
        }
        assertTrue(rig.shell.switcherIsOpen, "the wheel is still open")
        assertEquals(0, stale, "the content area outside the wheel's panel is a fresh paint of the previewed window")
        rig.shell.stop()
    }
}
