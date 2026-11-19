package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Notifications.Companion.GRACE_STEPS
import wm.damage.core.shell.Notifications.Companion.graceInk
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/**
 * The notice's focus timer made visible (`DESIGN.md` §4.5, Adam 2026-11-19): "a progress bar at the top of it, going
 * left to right, giving it focus when the bar is all the way full (let's make that 2s flat), and showing the bar reset
 * on inputs … The pop-up can also increase in brightness from like 30% to 90% gradually as the timer goes".
 *
 * The box's top rule is the bar (lit at the rule's level to the timer's share, level 1 beyond), its ink rises with the
 * steps ([Notifications.graceInk]), an input sets both back at once, and the box takes focus on the step that makes the
 * bar whole. The shell's steps run on the harness's clock ([Shell.graceByHand]) so each one is read alone, and the
 * truth oracle (belief = glass = truth, both lenses) is taken at every step; one test keeps the real clock.
 */
class NoticeTimerTest {

    // ------------------------------------------------------------------ the box alone

    /** His "30% to 90%", on the 16 levels: the table `DESIGN.md` §4.5 states, never level 0, never falling. */
    @Test
    fun theInkRisesFromAThirdToNineTenthsOnTheSixteenLevels() {
        val table = mapOf(
            Level.HEAD to listOf(4, 5, 6, 7, 8, 9, 10, 11),
            Level.BODY to listOf(2, 3, 4, 4, 5, 6, 7, 7),
            Level.DIM to listOf(1, 1, 1, 2, 2, 2, 2, 3),
            Level.FAINT to listOf(1, 1, 1, 1, 1, 1, 1, 1),
        )
        for ((base, want) in table) {
            val got = (0 until GRACE_STEPS).map { graceInk(base, it) / 17 }
            assertEquals(want, got, "the ink of a level-${base / 17} mark over the steps")
        }
        for (lvl in 1..15) {
            val steps = (0 until GRACE_STEPS).map { graceInk(Level.of(lvl), it) / 17 }
            assertTrue(steps.all { it >= 1 }, "a level-$lvl mark never goes to level 0: $steps")
            assertTrue(steps.zipWithNext().all { (a, b) -> b >= a }, "a level-$lvl mark never dims as the timer runs: $steps")
            assertTrue(kotlin.math.abs(steps.first() - lvl * 0.30) <= 0.5 || steps.first() == 1, "about 30 % at arrival: $lvl → ${steps.first()}")
            assertTrue(kotlin.math.abs(steps.last() - lvl * 0.90) <= 0.5, "about 90 % at the last step: $lvl → ${steps.last()}")
        }
        assertEquals(0, graceInk(0, 3), "no ink stays no ink")
        assertEquals(Level.BODY, Notifications.BAR_LIT, "the bar's lit part is the body's reading level, level 8 (2026-11-19)")
    }

    /** Each step: the rule lit from the left edge to an eighth more of the box, the rest level 1; the source, the time,
     *  the badge and the body at the step's level; at focus the rule whole and every mark at its own level. */
    @Test
    fun theTopRuleFillsLeftToRightAndTheInkFollowsTheSteps() {
        val n = Notifications(FakeText())
        val l = Layout()
        n.post(Notifications.Notice("SMS · MOM", "t1", "on my way", "14:32"), l)
        n.post(Notifications.Notice("SMS · DAD", "t2", "another", "14:33"), l)          // the +1 badge
        repeat(6) { n.stepUnfurl(l, silent = false) }
        var lastLit = -1
        for (s in 0..GRACE_STEPS) {
            if (s == GRACE_STEPS) n.takeFocus() else if (s > 0) assertFalse(n.advanceGrace(), "step $s is not the last")
            val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
            val box = n.paint(g, l, silent = false)!!
            val full = n.fullRect(n.current!!, l, silent = false)
            assertEquals(full, box)
            val m = measure(g, full)
            val focus = s == GRACE_STEPS
            val want = if (focus) full.w else (full.w - 2 * Notifications.BAR_INSET) * s / GRACE_STEPS / 4 * 4
            assertEquals(want, m.lit, "step $s: the bar's lit width")
            assertTrue(m.lit > lastLit, "step $s: the bar grew (from $lastLit to ${m.lit})")
            lastLit = m.lit
            assertEquals(if (focus) Level.HEAD else graceInk(Level.HEAD, s), m.source, "step $s: the source line")
            assertEquals(if (focus) Level.BODY else graceInk(Level.BODY, s), m.body, "step $s: the body")
            assertEquals(if (focus) Level.DIM else graceInk(Level.DIM, s), m.meta, "step $s: the time and the badge")
        }
        assertTrue(n.focused)
    }

    /** The quiet forms never take focus and have no timer: what the small box draws does not follow the step. */
    @Test
    fun theQuietFormHasNoBarAndNoFade() {
        val n = Notifications(FakeText())
        val l = Layout()
        n.post(Notifications.Notice("SMS · MOM", "t1", "on my way", "14:32"), l)
        repeat(6) { n.stepUnfurl(l, silent = true) }
        val g0 = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
        n.paint(g0, l, silent = true)
        repeat(5) { n.advanceGrace() }
        val g5 = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
        n.paint(g5, l, silent = true)
        assertTrue(g0.pix.contentEquals(g5.pix), "the silent box drew the same at step 0 and step 5")
        val full = n.fullRect(n.current!!, l, silent = true)
        val rule = (full.y until full.bottom).first { y -> (full.x until full.right).all { g0[it, y] != 0 } }
        assertEquals(setOf((Level.DIM * 0.6).toInt()), (full.x until full.right).map { g0[it, rule] }.toSet(),
            "the silent box's top rule is whole at the one dim level it always had")
    }

    /** The same grace, so the same timer on the emergency band (§4.5 rule 3): its outer top rule is the bar. */
    @Test
    fun theEmergencyBandCarriesTheBarOnItsOuterRule() {
        val n = Notifications(FakeText())
        val l = Layout()
        n.post(Notifications.Notice("ALERT", "e1", "tornado warning", "14:32", emergency = true), l)
        repeat(6) { n.stepUnfurl(l, silent = false) }
        repeat(3) { n.advanceGrace() }
        val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
        n.paint(g, l, silent = false)
        val full = n.fullRect(n.current!!, l, silent = false)
        val inset = Notifications.BAND_INSET
        val share = (full.w - 2 * inset) * 3 / GRACE_STEPS / 4 * 4
        assertEquals(setOf(Level.MID), (full.x until full.x + inset).map { g[it, full.y] }.toSet(), "dim before the text column")
        assertEquals(setOf(Level.HOT), (full.x + inset until full.x + inset + share).map { g[it, full.y] }.toSet(), "lit to the timer's share")
        assertEquals(setOf(Level.MID), (full.x + inset + share until full.right).map { g[it, full.y] }.toSet(), "dim beyond it")
        assertEquals(setOf(Level.HOT), (full.x until full.right).map { g[it, full.y + 4] }.toSet(), "the inner rule as before")
        n.takeFocus()
        n.paint(g, l, silent = false)
        assertEquals(setOf(Level.HOT), (full.x until full.right).map { g[it, full.y] }.toSet(), "whole at focus")
    }

    // ------------------------------------------------------------------ the shell's steps

    /** Arrival at step 0 with a step owed, seven steps that grow the bar and raise the ink with the box at the content
     *  plane, and focus on the eighth with the bar whole, the box at plane 0 and nothing owed. The oracle at each. */
    @Test
    fun theTimerStepsAndTheBoxTakesFocusWhenTheBarIsWhole(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            var b = r.box("arrived")
            r.assertUnfocusedAt(b, 0, "arrived")
            r.oracle("arrived")
            for (k in 1 until GRACE_STEPS) {
                val f0 = r.flushes.get()
                r.step("step $k")
                b = r.box("step $k")
                r.assertUnfocusedAt(b, k, "step $k")
                assertTrue(r.flushes.get() > f0, "step $k: its paint was sent")
                r.oracle("step $k")
            }
            r.step("step $GRACE_STEPS")
            b = r.box("focused")
            r.assertFocused(b, "focused")
            r.oracle("focused")
            r.step("a ninth step asked for")
            r.assertFocused(r.box("after a ninth"), "nothing more happens")
        } finally { r.close() }
    }

    /** An input on an unfocused box (one with no other effect: a bare release) sets the bar to empty and the ink to its
     *  arrival level in ONE flush — the input's own — and the timer runs again from there. A scroll goes to the window
     *  under the box, as before, and resets it too. */
    @Test
    fun anInputResetsTheBarAndTheInkAtOnce(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.box("arrived")
            repeat(5) { r.step("step ${it + 1}") }
            r.assertUnfocusedAt(r.box("step 5"), 5, "step 5")
            val f0 = r.flushes.get()
            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, 0)
            r.settle("the release")
            val b = r.box("after the release")
            r.assertUnfocusedAt(b, 0, "after the release")
            assertEquals(1, r.flushes.get() - f0, "the reset went in the input's one flush")
            r.oracle("reset")
            repeat(3) { r.step("again ${it + 1}") }
            r.assertUnfocusedAt(r.box("again 3"), 3, "again 3")
            val row0 = r.sample("Main's cursor") { r.shell.mainBoundForTest().second }
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            r.settle("a notch under the box")
            r.assertUnfocusedAt(r.box("after the notch"), 0, "after the notch")
            assertTrue(r.sample("Main's cursor") { r.shell.mainBoundForTest().second } != row0, "the notch went to Main under the box")
            repeat(GRACE_STEPS) { r.step("to focus ${it + 1}") }
            r.assertFocused(r.box("focused"), "focused after a full run")
            r.oracle("focused")
        } finally { r.close() }
    }

    /** §4.5 rule 2: the next box of a queue being cleared arrives focused — the bar whole, full ink, no step owed. */
    @Test
    fun theNextBoxOfAQueueBeingClearedArrivesWithTheBarWhole(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · ONE", "t1", "the first", "12:00"))
            r.shell.postNotice(Notifications.Notice("SMS · TWO", "t2", "the second", "12:00"))
            r.box("arrived")
            repeat(GRACE_STEPS) { r.step("step ${it + 1}") }
            r.assertFocused(r.box("the first, focused"), "the first, focused")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)          // dismiss + read: clearing the queue
            r.settle("the next")
            val b = r.box("the next")
            assertEquals("SMS · TWO", r.sample("source") { r.shell.notifications.current?.source ?: "" })
            r.assertFocused(b, "the next box of the queue")
            r.oracle("the next box")
        } finally { r.close() }
    }

    /** A notice queued behind the shown box leaves its timer where it was (a reset he sees is one his input made), and a
     *  new body for the shown thread does too. */
    @Test
    fun anArrivalBehindTheShownBoxLeavesItsTimerAlone(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · ONE", "t1", "the first", "12:00"))
            r.box("arrived")
            repeat(3) { r.step("step ${it + 1}") }
            r.shell.postNotice(Notifications.Notice("SMS · TWO", "t2", "queued behind", "12:00"))
            r.settle("queued")
            r.assertUnfocusedAt(r.box("queued behind"), 3, "a notice queued behind it")
            r.shell.postNotice(Notifications.Notice("SMS · ONE", "t1", "a new body for the shown thread", "12:00"))
            r.settle("coalesced")
            r.assertUnfocusedAt(r.box("coalesced"), 3, "a new body for its thread")
            r.oracle("after both arrivals")
        } finally { r.close() }
    }

    /** §4.5 rule 4: the silent box has no timer — no step owed, nothing it draws follows a step; back on Main the box
     *  is in the window form with its timer from empty. */
    @Test
    fun theSilentBoxHasNoTimerAndLeavingSilentStartsItFromEmpty(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.box("arrived")
            repeat(3) { r.step("step ${it + 1}") }
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)           // unfocused: Main's back, into silent (as before)
            r.settle("silent")
            r.step("the step owed before silent")
            val s0 = r.box("silent", silent = true)
            assertTrue(s0.full.w > 0, "the small box is up")
            assertFalse(s0.owed, "no step owed in silent")
            assertFalse(s0.focused)
            repeat(3) { r.step("silent ${it + 1}") }
            val s3 = r.box("silent, later", silent = true)
            assertTrue(s0.g.pix.contentEquals(s3.g.pix), "the silent box drew nothing new")
            r.oracle("silent")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)           // out of silent
            r.settle("out of silent")
            r.assertUnfocusedAt(r.box("out of silent"), 0, "back on Main")
            repeat(GRACE_STEPS) { r.step("to focus ${it + 1}") }
            r.assertFocused(r.box("focused"), "focused")
        } finally { r.close() }
    }

    /** The wheel parks the box (decision 6): the step owed then ends with nothing to draw, and the box shown again when
     *  the wheel closes starts from empty. */
    @Test
    fun theWheelParksTheBoxAndItsTimerStartsAgainFromEmpty(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            r.box("arrived")
            repeat(4) { r.step("step ${it + 1}") }
            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS, 0)    // the chord: the wheel
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.settle("the wheel")
            assertTrue(r.shell.switcherIsOpen, "the wheel is open")
            r.step("the step owed")
            assertEquals(false to false, r.sample("parked") { r.shell.notifications.active to r.shell.graceState().second },
                "the box is parked and no step is owed")
            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS, 0)    // cancel the wheel
            r.settle("the wheel closed")
            r.assertUnfocusedAt(r.box("shown again"), 0, "shown again")
            r.oracle("shown again")
        } finally { r.close() }
    }

    /** No step is owed once the box folds away (a dismissal's furl, a withdrawal from another interface) or the session
     *  ends with a box up. */
    @Test
    fun noStepIsOwedAfterAFurlAWithdrawalOrASessionEnd(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "dismissed", "12:00"))
            r.box("arrived")
            repeat(GRACE_STEPS) { r.step("step ${it + 1}") }
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.settle("furled")
            assertEquals(false to false, r.sample("furled") { r.shell.notifications.active to r.shell.graceState().second })

            val ref = wm.damage.core.app.NoticeRef("rec.notices.test:a", "1")
            r.shell.postNotice(Notifications.Notice("DAMAGE · test", "a", "withdrawn elsewhere", "12:00", record = ref))
            r.box("a record notice")
            repeat(2) { r.step("record step ${it + 1}") }
            r.assertUnfocusedAt(r.box("record step 2"), 2, "record step 2")
            r.shell.withdrawNotice(ref, queuedOnly = false)
            r.settle("withdrawn")
            assertEquals(false to false, r.sample("withdrawn") { r.shell.notifications.active to r.shell.graceState().second })

            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t2", "up at the end", "12:00"))
            r.box("up at the end")
            r.step("end step 1")
            assertTrue(r.box("end step 1").owed)
            r.shell.stop()
            // the loop has ended and been joined: its fields are read here after it
            assertFalse(r.shell.graceState().second, "no step owed past the session")
        } finally { r.close() }
    }

    /** The real clock: focus comes 2.0 s after the unfurl ends — not before — with the bar whole. */
    @Test
    fun onTheRealClockFocusComesTwoSecondsAfterTheUnfurl(): Unit = runBlocking {
        val r = Rig(byHand = false)
        try {
            r.up()
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "one line of body", "12:00"))
            suspend fun firstTime(what: String, cond: () -> Boolean): Long {
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 10_000) {
                    if (r.trySample(cond) == true) return System.currentTimeMillis()
                    delay(2)
                }
                throw AssertionError("$what — ${r.shell.quiescenceReport()}")
            }
            val t0 = firstTime("the box never finished its unfurl") { r.shell.graceState().second && !r.shell.notifications.animating }
            val t1 = firstTime("the box never took focus") { r.shell.notifications.focused }
            val ms = t1 - t0
            println("NoticeTimerTest: focus ${ms} ms after the unfurl was seen ended")
            assertTrue(ms >= 1_750, "focus came ${ms} ms after the unfurl — before the 2.0 s grace")
            assertTrue(ms <= 6_000, "focus came ${ms} ms after the unfurl")
            r.assertFocused(r.box("focused"), "focused on the real clock")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ the rig

    /** The top rule's lit width and the brightest ink of the source, the time+badge cell and the body. */
    private class Measure(val lit: Int, val source: Int, val meta: Int, val body: Int)

    private companion object {
        /** FakeText's band: the source line's 16 px, then the 2 px rule. */
        fun ruleRow(g: Gray8, full: Rect): Int =
            (full.y until full.bottom).first { y -> (full.x until full.right).all { g[it, y] == Level.DIM || g[it, y] == Level.FAINT || g[it, y] == Notifications.BAR_LIT } }

        /** The rule's lit width: the whole rule when every pixel is at the rule's level 3 (focus); else a run of
         *  [Notifications.BAR_LIT] that starts [Notifications.BAR_INSET] in, level 1 on both sides of it. */
        fun measure(g: Gray8, full: Rect): Measure {
            val y = ruleRow(g, full)
            var lit = -1
            for (yy in y until y + 2) {
                val row = (full.x until full.right).map { g[it, yy] }
                val l = if (row.all { it == Level.DIM }) row.size else {
                    val inset = Notifications.BAR_INSET
                    assertTrue(row.take(inset).all { it == Level.FAINT }, "the rule before the text column is level 1: $row")
                    val run = row.drop(inset).takeWhile { it == Notifications.BAR_LIT }.size
                    assertTrue(row.drop(inset + run).all { it == Level.FAINT }, "the rule beyond the lit part is level 1: $row")
                    run
                }
                if (lit >= 0) assertEquals(lit, l, "both rows of the rule agree")
                lit = l
            }
            fun max(x0: Int, x1: Int, y0: Int, y1: Int): Int {
                var m = 0
                for (yy in y0 until y1) for (x in x0 until x1) m = maxOf(m, g[x, yy])
                return m
            }
            return Measure(lit,
                source = max(full.x, full.x + full.w / 2 - 16, full.y, y),
                meta = max(full.x + full.w / 2 - 16, full.right, full.y, y),
                body = max(full.x, full.right, y + 2, full.bottom - 2))
        }
    }

    private class Rig(byHand: Boolean = true) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp: java.nio.file.Path = Files.createTempDirectory("damage-nt")
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope) {
            Shell.LocalClock(12, 0, "12:00", "PM")
        }
        val flushes = AtomicInteger(0)
        private val failures = ArrayList<String>()

        init {
            shell.graceByHand = byHand
            // Main needs a row besides Settings for a notch under the box to move its cursor
            shell.register(wm.damage.core.windows.games.GamesWindow(FakeText(), scope) { 1_757_000_000_000L })
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.events.collect { e ->
                    if (e is TransportEvent.FlushDone) {
                        flushes.incrementAndGet()
                        if (!e.ok) synchronized(failures) { failures.add("${e.id}: ${e.error}") }
                    }
                }
            }
        }

        suspend fun up() { shell.start(); settle("start") }

        suspend fun settle(what: String) =
            waitUntil(15_000, 5, { "$what: the shell did not settle — ${shell.quiescenceReport()}" }) { shell.isQuiescent() }

        suspend fun <T : Any> sample(what: String, block: () -> T): T {
            repeat(2_000) {
                settle(what)
                shell.sampleIdle(block)?.let { return it }
            }
            throw AssertionError("$what: the shell never sat idle for a sample — ${shell.quiescenceReport()}")
        }

        suspend fun <T : Any> trySample(block: () -> T): T? = if (shell.isQuiescent()) shell.sampleIdle(block) else null

        /** Deliver the step owed, as its tick would, and let its flush land. */
        suspend fun step(what: String) { shell.graceStepByHand(); settle(what) }

        class Box(val step: Int, val owed: Boolean, val focused: Boolean, val full: Rect, val plane: Int?, val g: Gray8)

        /** The box as the loop holds it, read in one idle turn; [silent] = the shell is in silent mode (the small form). */
        suspend fun box(what: String, silent: Boolean = false): Box = sample(what) {
            val (step, owed) = shell.graceState()
            val full = shell.notifications.boxRect(shell.layout, silent) ?: Rect(0, 0, 0, 0)
            Box(step, owed, shell.notifications.active && shell.notifications.focused, full,
                shell.comp.planes.lastOrNull { it.rect == full }?.disparity, shell.comp.composed.copy())
        }

        fun assertUnfocusedAt(b: Box, k: Int, what: String) {
            assertEquals(k, b.step, "$what: the step")
            assertTrue(b.owed, "$what: a step is owed")
            assertFalse(b.focused, "$what: not focused")
            assertEquals(8, b.plane, "$what: the box sits at the content plane until focus")
            val m = measure(b.g, b.full)
            assertEquals((b.full.w - 2 * Notifications.BAR_INSET) * k / GRACE_STEPS / 4 * 4, m.lit, "$what: the bar's lit width")
            assertEquals(graceInk(Level.HEAD, k), m.source, "$what: the source line's level")
            assertEquals(graceInk(Level.BODY, k), m.body, "$what: the body's level")
        }

        fun assertFocused(b: Box, what: String) {
            assertTrue(b.focused, "$what: focused")
            assertEquals(GRACE_STEPS to false, b.step to b.owed, "$what: the bar whole, no step owed")
            assertEquals(0, b.plane, "$what: the box stepped forward to plane 0")
            val m = measure(b.g, b.full)
            assertEquals(b.full.w, m.lit, "$what: the bar is whole")
            assertEquals(Level.HEAD, m.source, "$what: the source at its own level")
            assertEquals(Level.BODY, m.body, "$what: the body at its own level")
        }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        /** The truth oracle, both lenses, from one turn of the loop: belief = glass, and glass = the truth of the
         *  composed frame under the plane map; no sticky flag; no failed flush. */
        suspend fun oracle(what: String) {
            synchronized(failures) { assertTrue(failures.isEmpty(), "$what: failed flushes $failures") }
            val s = sample(what) {
                listOf(OracleWalkTest.quantised(shell.comp.expectedLens(true)), OracleWalkTest.quantised(shell.comp.expectedLens(false)),
                    OracleWalkTest.quantised(OracleWalkTest.truthOf(shell.comp.composed, shell.comp.planes, true)),
                    OracleWalkTest.quantised(OracleWalkTest.truthOf(shell.comp.composed, shell.comp.planes, false)),
                    lens(true), lens(false))
            }
            for ((i, arm) in listOf("LEFT", "RIGHT").withIndex()) {
                OracleWalkTest.firstDiff(s[i], s[4 + i])?.let { throw AssertionError("$what: $arm belief != glass at $it") }
                OracleWalkTest.firstDiff(s[2 + i], s[4 + i])?.let { throw AssertionError("$what: $arm glass != truth at $it") }
            }
            val flags = sim.flags(Arm.LEFT) + sim.flags(Arm.RIGHT)
            assertTrue(flags.none { it.value }, "$what: sticky diagnostic flags ${flags.filterValues { it }}")
        }

        suspend fun close() {
            try { shell.stop() } finally { scope.cancel(); tmp.toFile().deleteRecursively() }
        }
    }
}
