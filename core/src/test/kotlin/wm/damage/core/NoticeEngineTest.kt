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

    /**
     * The coordinator's ruling (2026-11-19): a notice costs Reader nothing it has today. Page staging keeps its whole 32 KiB
     * reserve at stage 4: with a paging window in front its strips are staged there (two of 12 KB, or one over 16 KiB),
     * one of them in the notice's slice, and a notice takes the phone's steps; back on Main, while the strips are in the
     * ring, the notice's programs are not written over them — every strip's bytes on the glasses are still its record's.
     */
    @Test
    fun theSliceCostsPageStagingNothingTwoStripsKept(): Unit = runBlocking { pagesKept(step = 9, strips = 2, largest = 0) }

    @Test
    fun theSliceCostsPageStagingNothingAStripOver16KiBKept(): Unit = runBlocking { pagesKept(step = 13, strips = 1, largest = 16 * 1024 + 1) }

    private suspend fun pagesKept(step: Int, strips: Int, largest: Int) {
        val r = Rig()
        try {
            val doc = Doc(step); r.shell.register(doc)
            r.up()
            r.shell.services.runOnShell { r.shell.services.openWindow("doc", null) }
            r.settle("the doc")
            repeat(2) { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); r.settle("a notch") }
            waitUntil(30_000, 10, { "$strips strip(s) never staged: ${r.strips().map { it.third }}" }) { r.strips().count { it.third == "PROVEN" } >= strips }
            r.settle("staged")
            val proven = r.strips().filter { it.third == "PROVEN" }
            println("NoticeEngineTest: step $step strips ${proven.map { "${it.first}+${it.second.size}" }}")
            assertTrue(proven.maxOf { it.second.size } >= largest, "a strip of at least $largest B: ${proven.map { it.second.size }}")
            val region = CfwModes.noticeRegion(256 * 1024, true)!!
            assertTrue(proven.any { (off, b, _) -> off <= region.last && off + b.size > region.first }, "a strip lies in the notice's slice: $proven")
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "over the page", "12:00"))
            r.settle("a notice over the page")
            assertFalse(r.snap("over the page").engine, "with page staging in front the timer is the phone's steps")
            r.assertStripsIntact("the notice over the page")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)        // the box unfocused: the doc's back, to Main
            r.settle("back")
            delay(500); r.settle("idle")
            r.assertStripsIntact("back on Main, idle")
            assertTrue(r.strips().count { it.third == "PROVEN" } >= strips, "the strips are still in the ring: ${r.strips().map { it.third }}")
            // and the other way round: the notice holds no program a strip has written over — while the strips are in the
            // slice its programs are not counted there, and a notice on Main takes the phone's steps (no PLAY of them)
            assertTrue(r.shell.noticeProgramsForTest().none { it.endsWith("PROVEN") }, "a notice program counted under a strip: ${r.shell.noticeProgramsForTest()}")
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t2", "on Main after reading", "12:00"))
            r.settle("a notice on Main")
            assertFalse(r.snap("on Main").engine, "while the strips hold the slice the timer is the phone's steps")
            r.assertStripsIntact("the notice on Main")
        } finally { r.close() }
    }

    private class Doc(private val step: Int) : wm.damage.core.shell.DamageWindow("doc", "Doc", wm.damage.core.gfx.IconKind.READER) {
        val model = wm.damage.core.shell.DocModel()
        private val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
        private val tx = FakeText()
        override fun view() = wm.damage.core.shell.WindowView.DocView(model, { 400 }, 24,
            { g, i, rr -> tx.draw(g, rr.x + 4, rr.y + 4, "line $i of a page that fills the width " + "abcdefghij".repeat(6).substring(i % 9), f, ((i % 10) + 3) * 17) },
            {}, stepLines = { step }, contentKey = { model })
        override fun summary() = Summary("doc")
        override fun saveState(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {}
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
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

        fun strips() = shell.pageStripsForTest()

        /** Every proven strip's bytes in RIGHT's cache are its record's (no notice program or capture over them). */
        suspend fun assertStripsIntact(what: String) {
            val bad = sample(what) {
                val cache = sim.right.textureCache ?: ByteArray(0)
                strips().filter { it.third == "PROVEN" }.filter { (off, b, _) -> off + b.size > cache.size || !cache.copyOfRange(off, off + b.size).contentEquals(b) }
                    .map { "${it.first}+${it.second.size}" }
            }
            assertTrue(bad.isEmpty(), "$what: strips overwritten on the glasses: $bad")
        }

        suspend fun close() {
            try { shell.stop() } finally { scope.cancel(); tmp.toFile().deleteRecursively() }
        }
    }
}
