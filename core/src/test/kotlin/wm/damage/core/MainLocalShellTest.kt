package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.geom.Geometry
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.local.BoundList
import wm.damage.core.local.LocalRules
import wm.damage.core.local.Taps
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg

/**
 * Build C2, stage DI: the real rules answer the real Main, end to end in the firmware model. Context for the reader:
 * Adam's own glasses on his own firmware fork; nothing here contacts a device — the glasses are the firmware model behind
 * [SimTransport] on a bit-10 build (DamageCaps 0x5ff) at the ladder's stage 4, its engine woken every 4 ms. Every gesture
 * is fired through the input hook on both lenses (LEFT first, as the inter-lens answer orders them) and then its stock
 * message reaches the shell through the transport, after the 115 (§6.1, P2) — so the shell's own rules read both.
 *
 * Per `Taps` value: a notch down and a notch up adopted and committed with the whole list moving (every track of the pan
 * changes on RIGHT's panel mid-motion), belief equal to both lenses after each; a tap acted on — under `report` and
 * `glasses` by the tap rule on its 115, the slot's DISCARD riding the window's first frame (under `glasses` the press holds
 * until then), under `phone` by the click as today. Under `report`: a band form changed during a burst is re-staged only
 * after the burst's SETTLE (P9), and a notch onto that stale row is still a COMMIT (decisions X.1, AA.1) with the row's
 * repaint after it; a row form change withdraws the list — the rules' CLEAR, the write, the SETs — and a notch in between
 * goes through as today.
 */
class MainLocalShellTest {

    private class Row(id: String, name: String, var s: DamageWindow.Summary) : DamageWindow(id, name, IconKind.FILES) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(val taps: Taps) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-mainlocal")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x5ff }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val journal = tmp.resolve("journal.jsonl")
        val shell = Shell(CachedText(FakeText()), t, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rows = (0 until 7).map { Row("w$it", "Win $it", DamageWindow.Summary("summary $it", detail = "detail of $it", progress = if (it == 3) 0.25 else null)) }
        /** RIGHT's top-level images, in order. */
        val right = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        @Volatile var onImage: ((ByteArray) -> Unit)? = null

        init {
            for (r in rows) shell.register(r)
            t.devProbe("c1", "4")
            sim.imageObserver = { a, img -> if (a == Arm.RIGHT) { right += img; onImage?.invoke(img) } }
            // the glasses' engine thread: a step due is drawn
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        }

        fun allNotes(): List<Pair<String, String>> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\"" in it }.map { it.substringAfter("\"kind\":\"").substringBefore("\"") to it.substringAfter("{\"t\":").substringBefore(",") + " " + it.substringAfter("\"detail\":\"").substringBefore("\"") }

        fun notes(kind: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }

        /** The journal's time of the first note of [kind] starting with [prefix], or null. */
        fun noteAt(kind: String, prefix: String): Long? = if (!Files.exists(journal)) null else Files.readAllLines(journal)
            .firstOrNull { "\"ev\":\"note\",\"kind\":\"$kind\"" in it && it.substringAfter("\"detail\":\"").startsWith(prefix) }
            ?.substringAfter("{\"t\":")?.substringBefore(",")?.toLong()

        suspend fun until(what: String, maxMs: Long = 30_000, cond: () -> Boolean) =
            waitUntil(maxMs, 5, { "$what — mainstage ${notes("mainstage").takeLast(4)}; local ${notes("local").takeLast(4)}; ${shell.quiescenceReport()}" }, cond)

        suspend fun settle(what: String) = until("$what: settled") { shell.isQuiescent() }

        fun <T> onLoop(block: () -> T): T {
            val out = java.util.concurrent.CompletableFuture<T>()
            shell.services.runOnShell { out.complete(block()) }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        /** Field 39 with Main's entries live on RIGHT: ids 4 and 5, and the tap (id 0) unless `phone`. */
        val bound39: Int get() = 0x30 or (if (taps == Taps.PHONE) 0 else 1)

        suspend fun bound(what: String) {
            until("$what: Main's list bound on RIGHT") { onLoop { shell.mainBoundForTest().first } != null && sim.bindForTest(Arm.RIGHT).field39() == bound39 }
            settle(what)
        }

        suspend fun up() {
            shell.start(); settle("start")
            shell.updateSettings { it.copy(heightMode = 352, cachedText = "on", taps = taps) }
            until("the atlas goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            bound("up")
        }

        fun list(): BoundList = assertNotNull(onLoop { shell.mainBoundForTest().first })
        fun row(): Int = onLoop { shell.mainBoundForTest().second }

        /** One gesture on the ring: the hook on both lenses, then its stock message through the transport (after the 115,
         *  which the hook sent synchronously). RIGHT's S after the fire. */
        fun fire(eventId: Int): Int {
            val now = System.currentTimeMillis()
            for (a in Arm.entries) sim.conformanceInput(a, CfwModes.DEV_RING, eventId.toLong(), 0, now)
            t.injectInput(BoundList.stockEvent(eventId)!!)
            return sim.bindForTest(Arm.RIGHT).seq
        }

        fun panel(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
            for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        /** Belief equals both lenses' panels, read ON the loop with nothing else pending. */
        suspend fun assertOracle(what: String) {
            var diff: String? = null
            var read = false
            val deadline = System.currentTimeMillis() + 30_000
            while (!read && System.currentTimeMillis() < deadline) {
                if (!shell.isQuiescent()) { delay(5); continue }
                val r = shell.sampleIdle {
                    val out = StringBuilder()
                    for (left in listOf(true, false)) {
                        val b = shell.comp.expectedLens(left); val g = panel(left)
                        loop@ for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W)
                            if (wm.damage.core.gfx.Pack.level(b[x, y]) * 17 != g[x, y]) { out.append("${if (left) "LEFT" else "RIGHT"} belief != glass at ($x,$y); "); break@loop }
                    }
                    out.toString()
                }
                if (r != null) { read = true; diff = r }
            }
            assertTrue(read, "$what: the shell never held still to be sampled — ${shell.quiescenceReport()}")
            assertTrue(diff.isNullOrEmpty(), "$what: $diff")
        }

        fun subs(img: ByteArray): List<ByteArray> {
            if (img[0].toInt() != 8) return listOf(img)
            val out = ArrayList<ByteArray>()
            var i = 2
            repeat(img[1].toInt() and 0xFF) {
                val n = (img[i].toInt() and 0xFF) or ((img[i + 1].toInt() and 0xFF) shl 8)
                out += img.copyOfRange(i + 2, i + 2 + n); i += 2 + n
            }
            return out
        }

        fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        fun isCommit(s: ByteArray, tag: Int) = s[0].toInt() == 26 && s[1].toInt() == 3 && u16(s, 3) == tag
        fun isSub(s: ByteArray, mode: Int, sub: Int) = s[0].toInt() == mode && s[1].toInt() == sub
        /** A drawing sub-message (not the rules' tail, not the present hint). */
        fun drawing(s: ByteArray) = (s[0].toInt() and 0x7F) !in setOf(24, 26, 27)
        fun cacheWrite(img: ByteArray) = (img[0].toInt() and 0x7F) in setOf(12, 19, 25)

        fun close() { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** One notch, adopted and committed: the row moves by its step, the pan of the whole list draws on RIGHT (each of its
     *  tracks changes mid-motion), RIGHT's images carry a COMMIT naming the fire's S, no snap-back is noted, and belief
     *  equals both lenses once the motion has ended. */
    private suspend fun notch(r: Rig, eventId: Int, what: String) {
        r.settle("$what: before")
        val list = r.list()
        val e = assertNotNull(list.entryFor(eventId))
        val row0 = r.row()
        val before = r.panel(false)
        val at = r.right.size
        val snaps = r.notes("snapback").size
        val s = r.fire(eventId)
        delay((e.period * 2 * 1000L) / 1024 + 10)              // about two steps in
        val mid = r.panel(false)
        for (track in e.program!!.tracks) {
            val a = track.areaR
            var moved = false
            for (y in maxOf(0, a.y) until minOf(Geometry.PANEL_H, a.bottom)) { for (x in maxOf(0, a.x) until minOf(Geometry.PANEL_W, a.right)) if (mid[x, y] != before[x, y]) { moved = true; break }; if (moved) break }
            assertTrue(moved, "$what: the pan's track at $a changed on RIGHT mid-motion (the whole list moves)")
        }
        delay(((e.program.T + 1) * e.period * 1000L) / 1024 + LocalRules.Q_MS + 200)   // the motion, then the burst's SETTLE
        r.settle("$what: after")
        assertEquals(row0 + e.step, r.row(), "$what: the row moved by its step")
        assertTrue(r.right.drop(at).any { img -> r.subs(img).any { r.isCommit(it, s) } }, "$what: a COMMIT of S $s")
        assertEquals(snaps, r.notes("snapback").size, "$what: no snap-back: ${r.notes("snapback").takeLast(2)}")
        r.assertOracle(what)
    }

    /** A tap: acted on (the row's window opens); under `report` and `glasses` the slot's DISCARD rides the window's first
     *  frame, and under `glasses` the press is drawn and held until then; under `phone` no entry matches and the click acts
     *  as today. Then back to Main, bound again. */
    private suspend fun tap(r: Rig) {
        r.settle("tap: before")
        val row = r.row()
        val s0 = r.sim.bindForTest(Arm.RIGHT).seq
        val at = r.right.size
        val s = r.fire(CfwModes.EV_TAP)
        r.until("the row's window opens") { r.onLoop { r.shell.currentWindowId() } == "w$row" }
        r.settle("tap: after")
        if (r.taps == Taps.PHONE) {
            assertEquals(s0, s, "phone: no tap entry, no S taken")
        } else {
            assertEquals((s0 + 1) and 0xFFFF, s, "${r.taps.word}: the tap entry fired")
            // the transport's `applied` note: the press drew (its parameters carried) under `glasses`, nothing under `report`
            val applied = r.notes("applied").firstOrNull { it.startsWith("S $s ") }
            assertTrue(applied != null && (if (r.taps == Taps.GLASSES) " drew " in applied else " report-only " in applied),
                "${r.taps.word}: the tap's 115 — $applied")
            val imgs = r.right.drop(at)
            val firstFrame = imgs.indexOfFirst { img -> r.subs(img).any { r.drawing(it) } }
            val firstDiscard = imgs.indexOfFirst { img -> r.subs(img).any { r.isSub(it, 26, 4) } }
            assertTrue(firstFrame >= 0 && firstFrame == firstDiscard,
                "${r.taps.word}: the press held until the window's first frame, its DISCARD riding it (frame at $firstFrame, DISCARD at $firstDiscard)")
            val ss = r.subs(imgs[firstFrame])
            assertTrue(ss.indexOfFirst { r.isSub(it, 26, 4) } > ss.indexOfLast { r.drawing(it) }, "${r.taps.word}: the DISCARD after the frame's drawing subs")
            assertTrue(r.notes("tapdrop").isEmpty(), "${r.taps.word}: no tap dropped: ${r.notes("tapdrop")}")
        }
        r.assertOracle("tap (${r.taps.word})")
        r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)          // a window at its top level: back to Main
        r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
        r.bound("back on Main")
    }

    private fun perTaps(taps: Taps) = runBlocking {
        val r = Rig(taps)
        try {
            r.up()
            r.assertOracle("${taps.word}: bound")
            notch(r, CfwModes.EV_UPROLL, "${taps.word}: a notch down")
            notch(r, CfwModes.EV_UPROLL, "${taps.word}: a second notch down")
            notch(r, CfwModes.EV_DOWNROLL, "${taps.word}: a notch up")
            tap(r)
            notch(r, CfwModes.EV_UPROLL, "${taps.word}: a notch down after the window")
            println("DI-e2e ${taps.word}: local ${r.notes("local").filter { it.startsWith("35=") }.takeLast(1)}")
        } finally { r.close() }
    }

    @Test fun reportNotchesAndATap_DI() = perTaps(Taps.REPORT)
    @Test fun glassesNotchesAndAPressedTap_DI() = perTaps(Taps.GLASSES)
    @Test fun phoneNotchesAndAClickAsToday_DI() = perTaps(Taps.PHONE)

    /**
     * A band form changed while a burst is open is re-staged only after the burst's lone SETTLE (P9: nothing a live list
     * names is rewritten during a burst — the item-table word that switches the form is). A notch onto that row inside the
     * burst draws its staged (older) form: the answer is still a COMMIT, with no snap-back (decisions X.1, AA.1), and the
     * row's repaint follows — belief equals both lenses at the end.
     */
    @Test
    fun aBandFormChangedInABurstWaitsForItsSettleAndItsNotchIsStillCommitted_DI(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            assertEquals(0, r.row())
            val at = r.right.size
            val snaps = r.notes("snapback").size
            r.fire(CfwModes.EV_UPROLL)                              // A: row 1, the burst opens
            delay(20)
            r.rows[2].s = r.rows[2].s.copy(detail = "detail of 2, changed")
            r.shell.services.requestRender(r.rows[2])
            delay(230)
            val tB = System.currentTimeMillis()
            val sB = r.fire(CfwModes.EV_UPROLL)                     // B: onto row 2, whose staged band form is the old one
            r.until("row 2 re-staged", 10_000) { r.notes("mainstage").any { it.startsWith("row 2 re-staged") } }
            delay(300)
            r.settle("after")
            val imgs = r.right.drop(at)
            val commitB = imgs.indexOfFirst { img -> r.subs(img).any { r.isCommit(it, sB) } }
            assertTrue(commitB >= 0, "B onto the stale row is answered with a COMMIT (S $sB)")
            val settle = imgs.indexOfFirst { img -> r.subs(img).any { r.isSub(it, 26, 5) } }
            assertTrue(settle > commitB, "the burst's lone SETTLE after B's COMMIT (COMMIT $commitB, SETTLE $settle)")
            // the re-stage waited for the burst's end: Q after B's report (the lone SETTLE), not between A and B
            val restagedAt = assertNotNull(r.noteAt("mainstage", "row 2 re-staged"))
            assertTrue(restagedAt >= tB + LocalRules.Q_MS / 2, "row 2 re-staged ${restagedAt - tB} ms after B: not before the burst's SETTLE (Q ${LocalRules.Q_MS} ms)")
            // no snap-back of the rules' own: no DISCARD answered A or B. (In the simulator the transport's mirror IS the
            // lens, so the row's repaint that follows the COMMIT meets B's adopted instance while it still draws and the
            // transport CLOSEs it first (§5.6) — RIGHT's stop reads as `burst-end`. On glass the mirror holds no local or
            // adopted instance and sends no such CLOSE: `C2-DI-built.md` §0.)
            assertTrue(imgs.none { img -> r.subs(img).any { r.isSub(it, 26, 4) } }, "no DISCARD answered A or B")
            assertTrue(r.notes("snapback").drop(snaps).all { "cause=burst-end" in it }, "no snap-back but the simulator's CLOSE: ${r.notes("snapback").drop(snaps)}")
            assertEquals(2, r.row())
            r.assertOracle("after the re-stage")
        } finally { r.close() }
    }

    /** A notch adopted while Main rests (its 12 s dashboard rest: dim names, no icons) wakes Main as any input does — the
     *  lens drew the active rows it holds staged, so the phone's truth is the active list too, and the answer's END is
     *  what Main shows. (Found by reading at the join: the adoption moved the row and left Main resting.) */
    @Test
    fun aNotchAdoptedWhileMainRestsWakesMain_DI(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            r.until("Main rests", 20_000) { r.onLoop { r.shell.mainRestingForTest() } }
            r.settle("resting")
            notch(r, CfwModes.EV_UPROLL, "a notch at rest")
            assertTrue(!r.onLoop { r.shell.mainRestingForTest() }, "the adopted notch woke Main")
        } finally { r.close() }
    }

    /**
     * A row form change withdraws the list as a cover does: the rules' CLEAR reaches RIGHT, then the strip's write, then
     * the SETs. A notch fired while the write is on its way matches no entry (no S taken) and goes through as today (the
     * row moves by one); once bound again, a notch is adopted and committed.
     */
    @Test
    fun aRowFormChangeWithdrawsTheListAndANotchInBetweenGoesAsToday_DI(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            assertEquals(0, r.row())
            var between: Pair<Int, Int>? = null                 // RIGHT's S before and after the notch in between
            r.onImage = { img ->
                if (between == null && r.cacheWrite(img) && r.sim.bindForTest(Arm.RIGHT).field39() == 0) {
                    val s0 = r.sim.bindForTest(Arm.RIGHT).seq
                    between = s0 to r.fire(CfwModes.EV_UPROLL)
                }
            }
            r.rows[3].s = r.rows[3].s.copy(line = "summary 33")
            r.shell.services.requestRender(r.rows[3])
            r.until("withdrawn", 10_000) { r.notes("mainstage").any { it.startsWith("withdrawn — row 3") } }
            r.until("re-staged", 10_000) { r.notes("mainstage").any { it.startsWith("row 3 re-staged") } }
            r.bound("offered again")
            r.onImage = null
            val b = assertNotNull(between, "a notch was fired while the strip's write was on its way, with no entry on RIGHT")
            assertEquals(b.first, b.second, "the notch in between matched no entry (no S taken)")
            assertEquals(1, r.row(), "the notch in between moved the row as today")
            r.assertOracle("bound again")
            notch(r, CfwModes.EV_UPROLL, "a notch after the re-bind")
        } finally { r.close() }
    }
}
