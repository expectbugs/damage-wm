package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MainSeqPlan
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg

/**
 * Build C2, stage DMS: the shell's `mainseq` probe — Adam's scroll test on Main's staged rows (six notches up a
 * constant apart, a pause, six down one at a time, per width). Context for the reader: Adam's own glasses on his own
 * firmware fork; nothing here contacts a device — the glasses are the firmware model behind [SimTransport], whose motion
 * engine a ticker here wakes on the wall clock (the model draws a step only when woken, as the glasses' engine thread
 * does every period).
 *
 * The harness: every wait decides on ONE evaluation ([waitUntil]); the journal is read from the file the shell writes
 * synchronously; a sample of the shell's belief and truth against the lenses is taken ON the loop.
 */
class MainSeqTest {

    private class Row(id: String, name: String, private var s: String) : DamageWindow(id, name, IconKind.FILES) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = Summary(s, detail = "detail of $s")
        fun change(to: String) { s = to }
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(val scope: CoroutineScope, stage: Int) {
        val tmp = Files.createTempDirectory("damage-mainseq")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val journal = tmp.resolve("journal.jsonl")
        val shell = Shell(CachedText(FakeText()), t, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rows = (0 until 7).map { Row("w$it", "Win $it", "summary $it") }
        /** The PLAYs RIGHT took, with the wall clock at their arrival. */
        val plays = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, ByteArray>>()

        init {
            for (r in rows) shell.register(r)
            if (stage != 0) t.devProbe("c1", "$stage")
            sim.imageObserver = { arm, img -> if (arm == Arm.RIGHT && img[0].toInt() and 0x7F == CfwModes.MOTION_MODE && img[1].toInt() == 0) plays += System.currentTimeMillis() to img }
            // the glasses' engine thread: a step due is drawn (the model draws only when woken)
            scope.launch { while (isActive) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        }

        fun notes(kind: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }

        suspend fun until(what: String, maxMs: Long = 20_000, cond: () -> Boolean) =
            waitUntil(maxMs, 5, { "$what — mainseq ${notes("mainseq")}; motion ${notes("motion").takeLast(4)}; ${shell.quiescenceReport()}" }, cond)

        suspend fun settle(what: String) = until("$what: settled") { shell.isQuiescent() }

        suspend fun up() {
            shell.start(); settle("start")
            shell.updateSettings { it.copy(heightMode = 352, cachedText = "on") }
            until("the atlas goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            settle("cached text on")
        }

        /** On the loop: belief equals the truth everywhere and each lens shows belief. */
        fun agreesOnLoop(): Boolean {
            val out = java.util.concurrent.CompletableFuture<Boolean>()
            shell.services.runOnShell {
                out.complete(Arm.entries.all { arm ->
                    val left = arm == Arm.LEFT
                    val belief = shell.comp.expectedLens(left)
                    belief.pix.contentEquals(shell.comp.truthForTest(left).pix) && glassIs(arm, belief)
                })
            }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        fun glassIs(arm: Arm, belief: Gray8): Boolean = synchronized(sim) {
            val ctx = if (arm == Arm.LEFT) sim.left else sim.right
            (0 until 480).all { y -> (0 until 640).all { x ->
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                (if (x and 1 == 0) b shr 4 else b and 0x0F) == minOf(15, (belief[x, y] + 8) / 17)
            } }
        }

        fun assertGlassMatchesBelief(what: String) = assertTrue(Arm.entries.all { glassIs(it, shell.comp.expectedLens(it == Arm.LEFT)) }, "$what: a lens differs from belief")

        fun close() { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun s16(b: ByteArray, at: Int) = u16(b, at).toShort().toInt()

    private val pace = MainSeqPlan.Pace(fastMs = 250, pauseMs = 1_500, slowMs = 250, betweenMs = 300, followUpMs = 20)

    /**
     * `mainseq=2` at his height (352, eight rows): Main's records written and counted, then twelve PLAYs — slot 0, P 34,
     * each its own tag, from the last row six up and six back — with the parameters the staging's rules give at each new
     * cursor (P0 / P3 the two strips' start y, P1 / P2 the entering and leaving rows' band forms) and the spacing of the
     * plan. In the pause after the sixth, the shell's belief equals its truth everywhere and both lenses show it: END is
     * in belief, and the follow-up drew the rows past k and the rail. Every instance draws all six steps — no follow-up
     * met one while it drew. At the end Main's cursor is back where it was, and the lenses equal belief.
     */
    @Test
    fun theScrollTestPlaysSixUpAPauseSixDownWithEndInBeliefAtEachStep_DMS(): Unit = runBlocking {
        val r = Rig(CoroutineScope(SupervisorJob() + Dispatchers.Default), stage = 3)
        try {
            r.up()
            assertEquals(0x2c, r.t.state.value.flagsInForce, "ENGINE in force")
            r.shell.mainSeqPace = pace
            r.shell.devProbe("mainseq", "2")
            r.until("six PLAYs, and in the pause belief, truth and both lenses agree") { r.plays.size == 6 && r.agreesOnLoop() }
            r.until("released at the end", 30_000) { r.notes("mainseq").any { it.startsWith("released — the test is done") } }
            r.settle("released")
            r.assertGlassMatchesBelief("after the test")
            assertEquals(0, r.shell.divergencesReported, "no divergence: ${r.shell.lastDivergence}")

            val l = r.shell.layout
            val cursors = listOf(6, 5, 4, 3, 2, 1, 2, 3, 4, 5, 6, 7)
            assertEquals(12, r.plays.size, "twelve PLAYs")
            // where the band forms went (the stage notes: "Main's band i (N B at OFF) …")
            val bandOff = r.notes("stage").mapNotNull { Regex("Main's band (\\d+) \\((\\d+) B at (\\d+)\\)").find(it) }
                .associate { it.groupValues[1].toInt() to it.groupValues[3].toInt() / 4 }
            assertEquals((0..7).toSet(), bandOff.keys, "eight band forms staged")
            var from = 7
            val tags = HashSet<Int>()
            val progs = HashMap<Int, MutableSet<Int>>()
            for ((i, pl) in r.plays.withIndex()) {
                val b = pl.second
                val to = cursors[i]
                val step = to - from
                assertEquals(0, b[2].toInt(), "PLAY ${i + 1}: slot 0")
                assertEquals(34, b[3].toInt(), "PLAY ${i + 1}: period 34")
                tags += u16(b, 6)
                progs.getOrPut(step) { HashSet() } += u16(b, 4)
                assertEquals(listOf(l.lens.y - (from + l.rowsAbove) * l.rowH, bandOff.getValue(to), bandOff.getValue(from), l.lens.bottom - (from + 1 + l.rowsAbove) * l.rowH),
                    (0 until 4).map { s16(b, 10 + 2 * it) }, "PLAY ${i + 1} ($from→$to): P0..P3")
                from = to
            }
            assertEquals(12, tags.size, "a tag per PLAY: $tags")
            assertTrue(tags.all { it in Shell.MAINSEQ_TAG until Shell.MAINSEQ_TAG + 0x1000 }, "the probe's tags: $tags")
            assertEquals(1, progs.getValue(-1).size, "one program up"); assertEquals(1, progs.getValue(1).size, "one program down")
            assertTrue(progs.getValue(-1) != progs.getValue(1), "two programs")
            // the spacing, as the plays reached the glasses
            val gaps = r.plays.zipWithNext { a, b -> b.first - a.first }
            val want = MainSeqPlan.plan(listOf(2), 8, pace).drop(1).map { it.afterMs }
            for ((i, g) in gaps.withIndex()) assertTrue(g >= want[i] - 5 && g < want[i] + 600, "gap ${i + 1}: $g ms, the plan's ${want[i]}")
            // the note: per notch RIGHT's 114 — every instance drew its six steps, none was closed by a follow-up
            val note = r.notes("mainseq").single { it.startsWith("k=2") }
            println("DMS-journal mainseq: ${r.notes("mainseq").joinToString("\n")}")
            assertEquals(12, Regex("114 drew drawn (\\d+) folded (\\d+)").findAll(note).count { it.groupValues[1].toInt() + it.groupValues[2].toInt() == 6 }, note)
            assertTrue(r.notes("mainseq").any { "Main's cursor back at row 0" in it }, "the cursor back where it was: ${r.notes("mainseq")}")
        } finally { r.close() }
    }

    /**
     * A row that changed after it was staged (a live summary: Music's progress, Torrents', Feed's counts): END there is
     * not the truth, so the follow-up would write into the pan's areas while the instance draws — the transport would
     * CLOSE it and cut the motion. The follow-up waits for the instance's end instead, so every instance still draws its
     * six steps; at the end the shell's repaint puts the changed row on the lenses.
     * Mutation watched: [Shell]'s `mainSeqFollowUpMeets` answering false (a CLOSE cuts the instance: RIGHT's 114 says
     * "closed while drawing").
     */
    @Test
    fun aRowChangedSinceStagingNeverCutsAMotionTheFollowUpWaitsForItsEnd_DMS(): Unit = runBlocking {
        val r = Rig(CoroutineScope(SupervisorJob() + Dispatchers.Default), stage = 3)
        try {
            r.up()
            r.shell.mainSeqPace = pace.copy(pauseMs = 300)
            r.shell.devProbe("mainseq", "2")
            r.until("counted") { r.notes("mainseq").any { it.startsWith("counted") } }
            r.rows[5].change("summary 5 changed after staging")
            r.shell.services.requestRender(r.rows[5])
            r.until("released at the end", 30_000) { r.notes("mainseq").any { it.startsWith("released — the test is done") } }
            r.settle("released")
            r.assertGlassMatchesBelief("after the test")
            val note = r.notes("mainseq").single { it.startsWith("k=2") }
            assertTrue("deferred" in note, "a follow-up waited for an instance's end: $note")
            assertEquals(12, Regex("114 drew drawn (\\d+) folded (\\d+)").findAll(note).count(), "every instance drew its last step, none closed: $note")
            assertEquals(0, r.shell.divergencesReported, "no divergence: ${r.shell.lastDivergence}")
        } finally { r.close() }
    }

    /** Any input event ends the test at once: no PLAY after it, the cursor back where Adam had it (and the gesture then
     *  handled as usual), the shell's repaint, the lenses equal to belief. */
    @Test
    fun anInputEventInTheMiddleEndsItAndTheShellRepaints_DMS(): Unit = runBlocking {
        val r = Rig(CoroutineScope(SupervisorJob() + Dispatchers.Default), stage = 3)
        try {
            r.up()
            r.shell.mainSeqPace = pace.copy(pauseMs = 300, slowMs = 400)
            r.shell.devProbe("mainseq", "2")
            r.until("into the down notches") { r.plays.size >= 8 }
            r.sim.injectGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            r.until("released by the input") { r.notes("mainseq").any { it.startsWith("released — an input event") } }
            val after = r.plays.size
            r.settle("released")
            delay(600)
            r.settle("still released")
            assertEquals(after, r.plays.size, "no PLAY after the run ended")
            assertTrue(after < 12, "the run did not finish: $after PLAYs")
            r.assertGlassMatchesBelief("after the input")
            assertEquals(0, r.shell.divergencesReported, "no divergence: ${r.shell.lastDivergence}")
            assertTrue(r.notes("mainseq").any { it.startsWith("k=2") && "notch(es)" in it }, "what it played is journaled: ${r.notes("mainseq")}")
        } finally { r.close() }
    }

    /** Without ENGINE (the ladder's stage 0) nothing is staged or played, and the note says why; so for a value that is
     *  not a width, and for no session. */
    @Test
    fun withoutEngineItSaysSoAndPlaysNothing_DMS(): Unit = runBlocking {
        val r = Rig(CoroutineScope(SupervisorJob() + Dispatchers.Default), stage = 0)
        try {
            assertTrue(r.shell.devProbe("mainseq", "2"), "the name is the shell's even with no session")
            assertEquals(listOf("mainseq=2 not taken — no session"), r.notes("mainseq"))
            r.up()
            r.shell.devProbe("mainseq", "2")
            r.shell.devProbe("mainseq", "sideways")
            r.until("both said") { r.notes("mainseq").size >= 3 }
            r.settle("after")
            val notes = r.notes("mainseq")
            assertTrue(notes.any { it.startsWith("mainseq=2 not taken — ENGINE is not in force") }, "$notes")
            assertTrue(notes.any { it == "mainseq=sideways not taken — 1 | 2 | 3 | all" }, "$notes")
            assertEquals(0, r.plays.size, "no PLAY")
            assertTrue(r.notes("stage").none { "Main's" in it }, "nothing of Main's staged: ${r.notes("stage")}")
        } finally { r.close() }
    }
}
