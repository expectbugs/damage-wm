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
                r.calmCache("before restart ${i + 1}")             // no cache write lands inside the count (one evaluation decides)
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
            // fix stage 2 (review 2, item 1): the notice's faces go live in the atlas BEFORE the strips are staged. A face's
            // first use changes the live set, and that drops Page staging's strips (`atlasLive`, §66 — older than this
            // build); with the faces live first, "kept" is measured on the strips staged once, never dropped
            r.shell.postNotice(Notifications.Notice("SMS · WARM", "t0", "the faces go live", "12:00"))
            r.settle("the first notice")
            repeat(Notifications.GRACE_STEPS) { if (!r.sample("focus") { r.shell.notifications.focused }) { r.shell.graceStepByHand(); r.settle("a step") } }
            assertTrue(r.sample("focus") { r.shell.notifications.focused }, "the first notice took focus")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)        // focused: dismissed
            r.settle("dismissed")
            r.until("the box's faces live and the atlas calm") {
                !r.shell.notifications.active && BODY_FACE in r.shell.cachedFontsLive &&
                    !r.shell.cacheWorkInFlight && r.shell.cacheCountUnjudged == 0L
            }
            r.shell.services.runOnShell { r.shell.services.openWindow("doc", null) }
            r.settle("the doc")
            repeat(2) { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); r.settle("a notch") }
            r.until("$strips strip(s) staged") { r.strips().count { it.third == "PROVEN" } >= strips }
            r.calmCache("staged")
            // the strips, and the clears so far, read in one evaluation on the loop: from here on nothing drops them
            val (proven, clears) = r.sample("staged") { r.strips().filter { it.third == "PROVEN" } to r.shell.stageClears }
            println("NoticeEngineTest: step $step strips ${proven.map { "${it.first}+${it.second.size}" }}")
            assertTrue(proven.size >= strips, "$strips strip(s): ${proven.map { it.first }}")
            assertTrue(proven.maxOf { it.second.size } >= largest, "a strip of at least $largest B: ${proven.map { it.second.size }}")
            val region = CfwModes.noticeRegion(256 * 1024, true)!!
            assertTrue(proven.any { (off, b, _) -> off <= region.last && off + b.size > region.first }, "a strip lies in the notice's slice: $proven")
            val kept = proven.map { it.first }
            suspend fun assertKept(what: String) {
                val (ring, c) = r.sample(what) { r.strips().map { "${it.first}+${it.second.size}:${it.third}" } to r.shell.stageClears }
                val lost = kept.filter { off -> ring.none { it.startsWith("$off+") && it.endsWith(":PROVEN") } }
                if (c != clears || lost.isNotEmpty()) {
                    val notes = r.journalNotes("stage", "atlas", "notice", "submit-STAGE", "done")
                    println("NoticeEngineTest DIAG step $step ($what): ring $ring clears $clears -> $c; ${r.shell.quiescenceReport()}\n" + notes.joinToString("\n"))
                }
                assertEquals(clears, c, "$what: Page staging's strips were dropped (a clear)")
                assertTrue(lost.isEmpty(), "$what: the strips at $lost are not in the ring, proven: $ring")
                r.assertStripsIntact(what)
            }
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "over the page", "12:00"))
            r.settle("a notice over the page"); r.calmCache("a notice over the page")
            assertFalse(r.snap("over the page").engine, "with page staging in front the timer is the phone's steps")
            assertKept("the notice over the page")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)        // the box unfocused: the doc's back, to Main
            r.settle("back")
            r.calmCache("idle on Main")                              // the programs' writes and their reading, if any, are over
            assertKept("back on Main, idle")
            // and the other way round: the notice holds no program a strip has written over — while the strips are in the
            // slice its programs are not counted there, and a notice on Main takes the phone's steps (no PLAY of them)
            val progs = r.sample("the programs") { r.shell.noticeProgramsForTest() }
            assertTrue(progs.none { it.endsWith("PROVEN") }, "a notice program counted under a strip: $progs")
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t2", "on Main after reading", "12:00"))
            r.settle("a notice on Main"); r.calmCache("a notice on Main")
            assertFalse(r.snap("on Main").engine, "while the strips hold the slice the timer is the phone's steps")
            assertKept("the notice on Main")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ fix stage 1 (review pass 1)

    /** A1: a gesture that moves the box (a window with its own height) — the PLAY is built for the box where it is now. */
    @Test
    fun aGestureThatMovesTheBoxPlaysTheBoxWhereItIs_A1(): Unit = runBlocking {
        val r = Rig()
        try {
            r.shell.register(Rows("tall", height = 416))
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box"); assertTrue(r.snap("the box").engine)
            val before = assertNotNull(r.snap("the timer").open, "the timer plays").first
            r.shell.postGesture(EvenHubMsg.EV_CLICK)                 // unfocused: Main's tap opens the 416-high window
            r.settle("the window"); r.calmCache("the window")
            assertEquals("tall", r.shell.currentWindowId())
            // the restart's PLAY is resolved for the box at 416, whose programs are not laid out yet: none goes (the phone's
            // steps) — a PLAY of the program laid out for 480 would play the box's old place
            val after = r.noticeTags().filter { it > before }
            assertTrue(after.isEmpty(), "a PLAY after the gesture for programs of the old layout: tags ${after.map { "%04x".format(it) }}")
            r.assertShadowIsBelief("after the window's height moved the box")
            r.assertTimerAlive("after the window's height moved the box")
        } finally { r.close() }
    }

    /** B1: an input that DRAWS (a scroll under the box, the list drawn through the shell's cached text as a real window's
     *  is) — the lens band's cached glyph runs beside the box close the timer on the first notch; the grace goes to the
     *  phone's steps and stays there (fix B-1): no PLAY after the hand-over, the engine's flag down on every notch. */
    @Test
    fun aScrollUnderTheBoxLeavesItsTimerAlive_B1(): Unit = runBlocking {
        val r = Rig()
        try {
            r.shell.register(Rows("list", tx = r.text))
            r.up()
            r.shell.services.runOnShell { r.shell.services.openWindow("list", null) }; r.settle("the list"); r.calmCache("the list")
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box"); r.calmCache("the box"); assertTrue(r.snap("the box").engine, "the box's timer on the glasses")
            var tagsAtHandOver: Set<Int>? = null
            repeat(3) { i ->
                r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); r.settle("notch ${i + 1}"); r.calmCache("notch ${i + 1}")
                r.assertTimerAlive("notch ${i + 1}")
                assertFalse(r.snap("notch ${i + 1}").engine, "notch ${i + 1}: the box on the phone's steps after the write into it")
                if (i == 0) tagsAtHandOver = r.noticeTags().toSet()
            }
            assertTrue(r.journalNotes("notice").any { "a write into the box closed its timer" in it },
                "the scroll's write closed the timer and the grace was handed over: ${r.journalNotes("notice")}")
            val later = r.noticeTags().filter { it !in tagsAtHandOver!! }
            assertTrue(later.isEmpty(), "a PLAY after the hand-over (the box back on the engine): tags ${later.map { "%04x".format(it) }}")
            r.assertShadowIsBelief("after the notches")
        } finally { r.close() }
    }

    /** B1: a write that is no input (a second notice's badge) closes the timer — the phone's steps take over. */
    @Test
    fun aBadgeWrittenIntoTheBoxLeavesItsTimerAlive_B1(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · ONE", "t1", "the first", "12:00"))
            r.settle("the box"); assertTrue(r.snap("the box").engine)
            r.shell.postNotice(Notifications.Notice("SMS · TWO", "t2", "queued behind it", "12:00"))
            r.settle("the badge"); r.calmCache("the badge")
            r.assertTimerAlive("after the badge")
            r.assertShadowIsBelief("after the badge")
        } finally { r.close() }
    }

    /** A3: a nearer plane over more of the box's edge than the bar's inset (Global Depth 16, a list app at 8: its lens
     *  band one notch nearer, at 4, covers 12 columns) — the phone's steps for that notice, nothing drawn over the band. */
    @Test
    fun aNearerPlaneOverTheBarsRunTakesThePhonesSteps_A3(): Unit = runBlocking {
        val r = Rig(ShellSettings(cachedText = "on", depth = 16, appStyles = mapOf("near" to ShellSettings.AppStyle(depth = 8))))
        try {
            r.shell.register(Rows("near", tx = r.text))
            r.up()
            r.shell.services.runOnShell { r.shell.services.openWindow("near", null) }; r.settle("the window"); r.calmCache("the window")
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box"); r.calmCache("the box")
            assertFalse(r.snap("the box").engine, "a 12-column cover: the phone's steps")
            assertTrue(r.noticeTags().isEmpty(), "no PLAY under the cover: ${r.noticeTags()}")
            r.assertShadowIsBelief("the box over the nearer band")
        } finally { r.close() }
    }

    /** C-1: the remainder (plane 0, an app at Depth 0) is the base the compositor paints every region over — it covers
     *  nothing, so the box beside it keeps the engine's timer, and the picture is the compositor's. */
    @Test
    fun theRemainderCoversNothingTheTimerRuns_C1(): Unit = runBlocking {
        val r = Rig(ShellSettings(cachedText = "on", depth = 16, appStyles = mapOf("far" to ShellSettings.AppStyle(depth = 0))))
        try {
            r.shell.register(Rows("far", tx = r.text))
            r.up()
            r.shell.services.runOnShell { r.shell.services.openWindow("far", null) }; r.settle("the window"); r.calmCache("the window")
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box"); r.calmCache("the box")
            assertTrue(r.snap("the box").engine, "nothing nearer beside the box: the engine's timer")
            r.assertTimerAlive("the box over the window at 0")
            r.assertShadowIsBelief("the box over the window at 0")
        } finally { r.close() }
    }

    /** A2: a Base or a Shortfall over a program's write sends it again; it is never taken as counted. */
    @Test
    fun aBaseOrAShortfallSendsTheProgramAgain_A2(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            assertEquals("WANTED", r.sample("a Base") { r.shell.noticeVerdictForTest(shortfall = false) }, "a Base")
            r.calmCache("written again")
            assertEquals("WANTED", r.sample("a Shortfall") { r.shell.noticeVerdictForTest(shortfall = true) }, "a Shortfall over its write")
        } finally { r.close() }
    }

    /** B3: the fallback from inside a restart (the programs left the slice) arms ONE chain: focus 2.0 s after the input. */
    @Test
    fun theFallbackInsideARestartKeepsTheTwoSeconds_B3(): Unit = runBlocking {
        val r = Rig(byHand = false)
        try {
            r.shell.register(Rows("list"))
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box"); assertTrue(r.snap("the box").engine)
            // the slice yields (a paging window came to the front) and an input follows before any write over the timer
            // has been answered: the engine's flag is still up and its programs are not counted, so the restart falls back
            // inside the restart (both messages queued back to back, in this order)
            val t0 = System.currentTimeMillis()
            r.shell.noticeYieldForTest()
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)           // a restart that falls back to the phone's steps
            val t1 = r.firstTime("focus") { r.shell.notifications.focused }
            println("NoticeEngineTest: focus ${t1 - t0} ms after the restart")
            assertTrue(t1 - t0 >= 1_900, "focus came ${t1 - t0} ms after the restart — before the 2.0 s grace")
        } finally { r.close() }
    }

    /** B4: a PLAY whose flush is not submitted — that notice takes the phone's steps, said. */
    @Test
    fun aPlayWhoseFlushIsNotSubmittedTakesThePhonesSteps_B4(): Unit = runBlocking {
        val r = Rig()
        try {
            r.shell.register(Rows("list"))
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box"); assertTrue(r.snap("the box").engine); r.calmCache("the box")
            r.flaky.failPlay = true
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)           // a restart in a flush that draws (Main's cursor moves)
            r.settle("the failed submit"); r.calmCache("the failed submit")
            r.assertTimerAlive("after the failed submit")
        } finally { r.close() }
    }

    /** B5: Unfurl off, only an engine style on, and it cannot run (stage 3: no slice) — at once, never the unfurl. */
    @Test
    fun anEngineStyleThatCannotRunAppearsAtOnce_B5(): Unit = runBlocking {
        val r = Rig(ShellSettings(cachedText = "on", popupUnfurl = false, popupFade = true), stage = "3")
        try {
            r.up(programs = false)
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box")
            assertEquals(Notifications.Arrival.NONE, r.sample("arrival") { r.shell.notifications.arrival }, "at once (the tier's None)")
        } finally { r.close() }
    }

    /** C-2: Unfurl off and no other row on, on a build whose engine is not armed (stage 2) — at once, never the unfurl. */
    @Test
    fun unfurlOffWithoutTheEngineAppearsAtOnce_C2(): Unit = runBlocking {
        val r = Rig(ShellSettings(cachedText = "on", popupUnfurl = false), stage = "2")
        try {
            r.up(programs = false)
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.settle("the box")
            assertEquals(Notifications.Arrival.NONE, r.sample("arrival") { r.shell.notifications.arrival }, "at once (the tier's None)")
        } finally { r.close() }
    }

    /** The phone's own timer arithmetic, on the real clock: a Fade's end sends the timer's PLAY, focus 2.0 s after it. */
    @Test
    fun onTheRealClockTheArrivalThenTheTimer(): Unit = runBlocking {
        val r = Rig(ShellSettings(cachedText = "on", popupUnfurl = false, popupFade = true), byHand = false)
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            var arrivalTag = -1
            val tA = r.firstTime("the arrival plays") { r.sim.motionOpenIn(Arm.RIGHT, PopupPrograms.SLOT)?.first?.also { arrivalTag = it } != null }
            val tT = r.firstTime("the timer plays") { r.sim.motionOpenIn(Arm.RIGHT, PopupPrograms.SLOT)?.first.let { it != null && it != arrivalTag } }
            val tF = r.firstTime("focus") { r.shell.notifications.focused }
            println("NoticeEngineTest: the arrival ${tT - tA} ms, focus ${tF - tT} ms after the timer's PLAY")
            assertTrue(tT - tA in 150..1_500, "the timer's PLAY ${tT - tA} ms after the arrival's")
            assertTrue(tF - tT in 1_850..4_000, "focus ${tF - tT} ms after the timer's PLAY")
        } finally { r.close() }
    }

    /** The box's body face (`Notifications.fBody`): the one face the box draws that nothing earlier in the rig does. */
    private val BODY_FACE = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 17)

    /** A list window; [tx] the rasterizer it draws with (a real window draws with the host's, the shell's cached text). */
    private class Rows(id: String, private val height: Int? = null, private val tx: wm.damage.core.text.TextRasterizer = FakeText()) :
        wm.damage.core.shell.DamageWindow(id, id.replaceFirstChar { it.uppercase() }, wm.damage.core.gfx.IconKind.FILES) {
        private val model = wm.damage.core.shell.ListModel()
        private val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
        override val preferredHeight: Int? get() = height
        override fun view(): wm.damage.core.shell.WindowView = wm.damage.core.shell.WindowView.ListView(model, { 30 },
            paintRow = { g, i, rr, _ -> tx.draw(g, rr.x + 4, rr.y + 5, "Row $i of the list under the box", f, wm.damage.core.gfx.Level.BODY) },
            paintLens = { g, rr, i -> tx.draw(g, rr.x + 4, rr.y + 8, "Row $i", f, wm.damage.core.gfx.Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("30 rows")
        override fun saveState(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {}
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
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

    /** Fix B4's knob: the next submit carrying a mode-26 message throws, as a link ending between the pump and the send. */
    private class Flaky(private val inner: wm.damage.core.transport.Transport) : wm.damage.core.transport.Transport by inner {
        @Volatile var failPlay = false
        override suspend fun submit(flush: wm.damage.core.transport.FlushRequest): Long {
            if (failPlay && flush.tail.any { it.isNotEmpty() && it[0].toInt() == 26 }) { failPlay = false; throw IllegalStateException("test: the link ended") }
            return inner.submit(flush)
        }
    }

    private class Snap(val engine: Boolean, val focused: Boolean, val animating: Boolean, val open: Pair<Int, Int>?,
        val capture: ByteArray, val shadowR: ByteArray, val sourceLevel: Int)

    private class Rig(settings: ShellSettings = ShellSettings(cachedText = "on"), stage: String = "4", byHand: Boolean = true) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp: java.nio.file.Path = Files.createTempDirectory("damage-nengine")
        val store = Persistence(tmp.resolve("state.json"))
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x5ff }
        // stage 4: a stored ladder record at stage 4 with a 256 KiB last session (decisions AD.1), so the first start asks
        // 256 KiB (`MainLocalShellTest`'s `kib256`); below it the probe sets the stage on a 160 KiB start
        val transport: wm.damage.core.transport.CfwTransportBase = if (stage == "4")
            StartRig(sim, scope, c1StageFile = tmp.resolve("c1-stage").also { f -> Files.writeString(f, wm.damage.core.transport.LadderRecord(stage = 4, sessionKib = 256).encode()) })
            else SimTransport(sim, scope, SimTransport.Timing(instant = true)).also { it.devProbe("c1", stage) }
        val flaky = Flaky(transport)
        /** The shell's journal (a diagnostic of the test: the stage lane's and the count's notes for a failure's message). */
        val journal: java.nio.file.Path = tmp.resolve("journal.jsonl")
        /** The shell's text, which a window may draw with too (as the hosts hand their windows the one rasterizer). */
        val text = wm.damage.core.comp.CachedText(FakeText())
        val shell = Shell(text, flaky, store, journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
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
            shell.graceByHand = byHand
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

        suspend fun <T : Any> trySample(block: () -> T): T? = if (shell.isQuiescent()) shell.sampleIdle(block) else null

        /** Waits until [cond] holds, each evaluation on an idle loop (one evaluation decides). */
        suspend fun until(what: String, cond: () -> Boolean) {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 30_000) { if (trySample(cond) == true) return; delay(10) }
            throw AssertionError("$what: never held — ${shell.quiescenceReport()}")
        }

        /** When [cond] first held on an idle loop (polled every 2 ms; the wall clock of the test). */
        suspend fun firstTime(what: String, cond: () -> Boolean): Long {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 15_000) { if (trySample(cond) == true) return System.currentTimeMillis(); delay(2) }
            throw AssertionError("$what never held — ${shell.quiescenceReport()}")
        }

        /** Up, the atlas live, and (with [programs]) the notice's programs counted on the glasses. */
        suspend fun up(programs: Boolean = true) {
            shell.start(); settle("start")
            waitUntil(20_000, 5, { "the atlas never came up" }) { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            settle("atlas")
            if (programs) waitUntil(30_000, 10, { "the notice's programs were never counted: ${shell.noticeProgramsForTest()}" }) {
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

        /** The journal's lines of these kinds (a `note`'s kind, or the stage lane's submits and every completion). */
        fun journalNotes(vararg kinds: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal).filter { l ->
            kinds.any { k -> when (k) {
                "submit-STAGE" -> "\"ev\":\"submit\"" in l && "\"label\":\"STAGE\"" in l
                "done" -> "\"ev\":\"done\"" in l
                else -> "\"kind\":\"$k\"" in l
            } }
        }.map { it.take(400) }

        /** The notice PLAYs' tags RIGHT reported bodies for (slot 2). */
        fun noticeTags(): List<Int> = notes.filter { it.first == "motion" }.mapNotNull {
            Regex("""slot ${PopupPrograms.SLOT} tag 0x([0-9a-f]{4})""").find(it.second)?.groupValues?.get(1)?.toInt(16) }

        /** Settled with no cache work on its way or unjudged (the harness rule: one evaluation decides). */
        suspend fun calmCache(what: String) {
            repeat(2_000) { if (sample(what) { !shell.cacheWorkInFlight && shell.cacheCountUnjudged == 0L }) return; delay(10) }
            throw AssertionError("$what: cache work never ended — ${shell.quiescenceReport()}")
        }

        /** The box after a write or an input: its timer running on the glasses (slot 2 open), or the phone's steps owed —
         *  never the engine's END with no timer (fix B1's state). */
        suspend fun assertTimerAlive(what: String) {
            val (engine, open, owed) = sample(what) { Triple(shell.notifications.engine, sim.motionOpenIn(Arm.RIGHT, PopupPrograms.SLOT), shell.graceState().second) }
            assertTrue(owed, "$what: a step is owed")
            assertTrue(!engine || open != null, "$what: the box at full ink with no timer on the glasses and the engine's flag up")
        }

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
