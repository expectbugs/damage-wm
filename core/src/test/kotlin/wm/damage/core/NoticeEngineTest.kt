package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Geometry
import wm.damage.core.gfx.Gray8
import wm.damage.core.motion.PopupPrograms
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.transport.bind
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg

/**
 * Build K1-4a: the notice box's focus timer as one program on the glasses' engine (`PopupPrograms`, `FIRMWARE.md` §5),
 * on the glasses' model at the ladder's stage 4 (a bit-10 build, 256 KiB, BIND and ENGINE in force) with Cached text on
 * (the programs are counted by the cache ledger). The model's shadow is exact: it holds each PLAY's END, and each PLAY's
 * capture lands in its cache at `cap` — so "a restart does not dim the box" is read on the bytes the next capture takes.
 */
class NoticeEngineTest {

    @Test
    fun theTimerIsOnePlayAndRestartsCaptureTheSamePicture(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box")
            val first = r.snap("the first PLAY")
            assertTrue(first.engine, "the box's timer runs on the glasses")
            val tag0 = assertNotNull(first.open, "slot ${PopupPrograms.SLOT} holds the timer").first
            r.assertShadowIsBelief("the first PLAY")
            assertEquals(12, first.sourceLevel, "the shadow holds the box at full ink (the END every PLAY captures)")
            var tag = tag0
            repeat(5) { i ->
                val f0 = r.flushes.get()
                r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, 0)        // an input with nothing else to draw
                r.settle("restart ${i + 1}")
                val s = r.snap("restart ${i + 1}")
                val t = assertNotNull(s.open, "restart ${i + 1}: slot ${PopupPrograms.SLOT} holds a timer").first
                assertTrue(t != tag, "restart ${i + 1}: a new PLAY (tag ${"%04x".format(t)} after ${"%04x".format(tag)})")
                tag = t
                assertEquals(1, r.flushes.get() - f0, "restart ${i + 1}: the PLAY went in the input's one flush")
                assertContentEquals(first.capture, s.capture, "restart ${i + 1}: the capture is the same picture — nothing dims or brightens")
                assertContentEquals(first.shadowR, s.shadowR, "restart ${i + 1}: the shadow holds the same END")
                r.assertShadowIsBelief("restart ${i + 1}")
            }
            r.shell.graceStepByHand()                                                  // the 2.0 s tick: focus
            r.settle("focus")
            val f = r.snap("focus")
            assertTrue(f.focused, "the box took focus at the timer's end")
            assertNull(f.open, "the focus repaint closed the timer (the transport's CLOSE, §5.6)")
            r.assertShadowIsBelief("focused")
            r.assertPanelIsBelief("focused")
            // after G4 (0.81): the five restarts closed a drawing timer each (the new PLAY's close pass), the focus repaint
            // closed the last (the transport's CLOSE, a write) — every body compared
            val closed = r.noticeBodies("restarts and focus", "closed while drawing")
            assertEquals(6, closed.size, "five timers closed by a restart and one by the focus repaint: $closed")
        } finally { r.close() }
    }

    /** An arrival the glasses draw is its own PLAY (one period an instance, §5.3); its end sends the timer's PLAY, which
     *  closes it and captures the END it wrote — the same picture a timer started at once captures. */
    @Test
    fun aFadeArrivalHandsOverToTheTimer(): Unit = runBlocking {
        val r = Rig(ShellSettings(cachedText = "on", popupUnfurl = false, popupFade = true))
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the arrival")
            val a = r.snap("the arrival")
            assertTrue(a.engine && !a.animating, "shown at once, drawn by the glasses")
            val arrivalTag = assertNotNull(a.open, "the arrival plays").first
            r.shell.graceStepByHand()                                                  // the arrival's end
            r.settle("the timer")
            val t = r.snap("the timer")
            val timerTag = assertNotNull(t.open, "the timer plays").first
            assertTrue(timerTag != arrivalTag, "a second PLAY")
            assertContentEquals(a.shadowR, t.shadowR, "the arrival and the timer write the same END")
            r.assertShadowIsBelief("the timer after the arrival")
            // the arrival closed by the timer's PLAY: its body compared (after G4)
            assertTrue(r.noticeBodies("the arrival", "closed while drawing").any { "tag 0x%04x".format(arrivalTag) in it }, "the arrival's body: ${r.notes}")
        } finally { r.close() }
    }

    /** No `Popup ·` row on: the box appears at once and its timer starts in the same flush. */
    @Test
    fun noStyleOnTheBoxAppearsAtOnce(): Unit = runBlocking {
        val r = Rig(ShellSettings(cachedText = "on", popupUnfurl = false))
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box")
            val s = r.snap("the box")
            assertTrue(s.engine && !s.animating, "at once")
            assertNotNull(s.open, "its timer plays")
            r.assertShadowIsBelief("at once")
        } finally { r.close() }
    }

    /** Below stage 4 (no BIND: no notice region in the cache) the timer is the phone's steps, as in build NT. */
    @Test
    fun withoutTheRegionTheTimerIsThePhonesSteps(): Unit = runBlocking {
        val r = Rig(stage = "3")
        try {
            r.up(programs = false)
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box")
            val s = r.snap("the box")
            assertFalse(s.engine, "the phone's steps")
            assertNull(s.open, "no PLAY")
            assertEquals(0 to true, r.sample("grace") { r.shell.graceState() }, "NT's first step is owed")
        } finally { r.close() }
    }

    /** What the notice's slice costs Page staging: its ring (the stage reserve below the slice) at each cache layout. Only
     *  a 256 KiB session with BIND in force has the slice — its ring 16 KiB of the 32 it had; 160 KiB, and 256 KiB
     *  without BIND, keep the 64 KiB ring byte for byte. */
    @Test
    fun theSliceCostsPageStagingOnlyAtStage4() {
        fun ring(cache: Int, bind: Boolean) = CfwModes.stageRingEnd(cache, bind) - (cache - CfwModes.stageReserve(cache, bind))
        assertEquals(16 * 1024, ring(256 * 1024, true), "stage 4 at 256 KiB: the ring is 16 KiB (it was 32)")
        assertEquals(64 * 1024, ring(256 * 1024, false), "256 KiB without BIND: the 64 KiB ring as before")
        assertEquals(64 * 1024, ring(160 * 1024, true), "160 KiB: the 64 KiB ring as before")
        assertEquals(64 * 1024, ring(160 * 1024, false), "160 KiB: the 64 KiB ring as before")
        assertNull(CfwModes.noticeRegion(160 * 1024, true)); assertNull(CfwModes.noticeRegion(256 * 1024, false))
        val r = assertNotNull(CfwModes.noticeRegion(256 * 1024, true))
        assertEquals(256 * 1024 - CfwModes.NOTICE_REGION, r.first, "the slice is the top 16 KiB")
        assertTrue(CfwModes.mainRegion(256 * 1024, true).last < CfwModes.stageRingEnd(256 * 1024, true), "Main's own region is below the ring")
    }

    // ------------------------------------------------------------------ the rig

    private class Snap(val engine: Boolean, val focused: Boolean, val animating: Boolean, val open: Pair<Int, Int>?,
        val capture: ByteArray, val shadowR: ByteArray, val sourceLevel: Int)

    private class Rig(settings: ShellSettings = ShellSettings(cachedText = "on"), stage: String = "4") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp: java.nio.file.Path = Files.createTempDirectory("damage-nengine")
        val store = Persistence(tmp.resolve("state.json"))
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x5ff }
        // stage 4: a stored ladder record at stage 4 with a 256 KiB last session (decisions AD.1), so the first start asks
        // 256 KiB (`MainLocalShellTest`'s `kib256`); below it the probe sets the stage on a 160 KiB start
        val transport: wm.damage.core.transport.CfwTransportBase = if (stage == "4")
            StartRig(sim, scope, c1StageFile = tmp.resolve("c1-stage").also { f -> Files.writeString(f, wm.damage.core.transport.LadderRecord(stage = 4, sessionKib = 256).encode()) })
            else SimTransport(sim, scope, SimTransport.Timing(instant = true)).also { it.devProbe("c1", stage) }
        val shell = Shell(wm.damage.core.comp.CachedText(FakeText()), transport, store, null, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val flushes = AtomicInteger(0)
        private val failures = ArrayList<String>()
        /** The transport's notes (kind, detail): RIGHT's motion bodies and what the transport made of them. */
        val notes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

        /** G4's rule (0.81, `CfwTransportBase.onMotionNotify`): a body with END CRC 0 under BIND is a local instance's own
         *  and not compared. Every body of the notice's slot came with an END CRC other than 0, the transport compared it
         *  (no "not compared" note names the slot) and found no divergence; returns the bodies of [outcome] ("closed while
         *  drawing", "drew its last step"). */
        fun noticeBodies(what: String, outcome: String): List<String> {
            val slot = "slot ${PopupPrograms.SLOT} "
            val bodies = notes.filter { (k, d) -> k == "motion" && d.contains(slot) && d.contains("endCrc") }.map { it.second }
            for (b in bodies) assertFalse(b.endsWith("endCrc 00000000"), "$what: a notice body with END CRC 0 — G4's rule would not compare it: $b")
            assertTrue(notes.none { (k, d) -> k == "motion" && d.contains(slot.trim()) && d.contains("not compared") }, "$what: a notice body was not compared: $notes")
            assertTrue(notes.none { (k, _) -> k == "divergence" }, "$what: a divergence: ${notes.filter { it.first == "divergence" }}")
            return bodies.filter { outcome in it }
        }

        init {
            store.put("shell.settings", settings.toJson())
            shell.graceByHand = true
            // the glasses' engine thread: a step due is drawn (as OracleWalkTest's stage-4 rig)
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.events.collect { e ->
                    if (e is TransportEvent.FlushDone) { flushes.incrementAndGet(); if (!e.ok) synchronized(failures) { failures += "${e.id}: ${e.error}" } }
                    if (e is TransportEvent.Note) notes += e.kind to e.detail
                }
            }
        }

        suspend fun settle(what: String) =
            waitUntil(20_000, 5, { "$what: did not settle — ${shell.quiescenceReport()}" }) { shell.isQuiescent() }

        suspend fun <T : Any> sample(what: String, block: () -> T): T {
            repeat(2_000) { settle(what); shell.sampleIdle(block)?.let { return it } }
            throw AssertionError("$what: never idle — ${shell.quiescenceReport()}")
        }

        /** Up, the atlas live, and (with [programs]) the notice's programs counted on the glasses. */
        suspend fun up(programs: Boolean = true) {
            shell.start(); settle("start")
            waitUntil(20_000, 5, { "the atlas never came up" }) { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            settle("atlas")
            if (programs) waitUntil(30_000, 10, { "the notice's programs were never counted: ${shell.noticeProgramsForTest()} — ${shell.noticeDiagForTest()}" }) {
                shell.noticeProgramsForTest().let { p -> p.isNotEmpty() && p.all { it.endsWith("PROVEN") } }
            }
            settle("programs")
        }

        suspend fun snap(what: String): Snap = sample(what) {
            val n = shell.notifications
            val st = transport.state.value
            val region = CfwModes.noticeRegion(st.cacheSize, st.bind)
            val cache = sim.right.textureCache
            val cap = if (region == null || cache == null) ByteArray(0)
                else cache.copyOfRange(region.first + CfwModes.NOTICE_PROGRAMS, minOf(cache.size, region.last + 1))
            // the brightest nibble of the box's source line in RIGHT's shadow (the box at +depth on the right lens)
            val box = n.boxRect(shell.layout, false)
            var src = 0
            if (box != null) for (y in box.y until box.y + 16) for (x in box.x + 8 + shell.settings.depth until box.x + box.w / 2) {
                val b = sim.right.shadow[y * ((Geometry.PANEL_W + 1) / 2) + (x shr 1)].toInt() and 0xFF
                src = maxOf(src, if (x and 1 == 0) b shr 4 else b and 0x0F)
            }
            Snap(n.engine, n.active && n.focused, n.animating, sim.motionOpenIn(Arm.RIGHT, PopupPrograms.SLOT), cap, sim.right.shadow.copyOf(), src)
        }

        private fun decode(packed: ByteArray): Gray8 {
            val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
            val stride = (Geometry.PANEL_W + 1) / 2
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                val b = packed[y * stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        /** The model's shadow on both lenses equals the compositor's belief (every PLAY's END is in both, exactly). */
        suspend fun assertShadowIsBelief(what: String) {
            synchronized(failures) { assertTrue(failures.isEmpty(), "$what: failed flushes $failures") }
            val s = sample(what) {
                listOf(OracleWalkTest.quantised(shell.comp.expectedLens(true)), OracleWalkTest.quantised(shell.comp.expectedLens(false)),
                    decode(sim.left.shadow.copyOf()), decode(sim.right.shadow.copyOf()))
            }
            OracleWalkTest.firstDiff(s[0], s[2])?.let { throw AssertionError("$what: LEFT belief != shadow at $it") }
            OracleWalkTest.firstDiff(s[1], s[3])?.let { throw AssertionError("$what: RIGHT belief != shadow at $it") }
        }

        /** With no instance open, the panels equal belief too (the truth oracle's glass half). */
        suspend fun assertPanelIsBelief(what: String) {
            val s = sample(what) {
                listOf(OracleWalkTest.quantised(shell.comp.expectedLens(true)), OracleWalkTest.quantised(shell.comp.expectedLens(false)),
                    decode(sim.left.panel.copyOf()), decode(sim.right.panel.copyOf()))
            }
            OracleWalkTest.firstDiff(s[0], s[2])?.let { throw AssertionError("$what: LEFT belief != glass at $it") }
            OracleWalkTest.firstDiff(s[1], s[3])?.let { throw AssertionError("$what: RIGHT belief != glass at $it") }
        }

        suspend fun close() {
            try { shell.stop() } finally { scope.cancel(); tmp.toFile().deleteRecursively() }
        }
    }
}
