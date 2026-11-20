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
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.local.BoundList
import wm.damage.core.local.LocalRules
import wm.damage.core.local.Taps
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MainStaging
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
    private companion object {
        /** 20 notches, the chrome riding each COMMIT: 21 flushes measured with the keep-out, 58 without it (the model,
         *  an instant link; decisions AQ.2). */
        const val FLUSHES_FOR_20 = 30
    }


    private class Row(id: String, name: String, var s: DamageWindow.Summary) : DamageWindow(id, name, IconKind.FILES) {
        /** A playing track's position (decisions AL.1): the progress read from the clock at every read, as Music's. */
        @Volatile var progress: (() -> Double)? = null
        /** A first view that is not a list (decisions AQ.1): a canvas with a few marks across its whole width. */
        @Volatile var canvas = false
        override fun view(): WindowView = if (canvas) WindowView.CanvasView({ g, r ->
            g.fillRect(r, 0)
            for (k in 0 until 6) g.fillRect(r.x + 8 + k * (r.w - 24) / 5, r.y + 6 + k * (r.h - 20) / 5, 8, 8, (6 + k) * 17)
        }) else WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = progress?.let { s.copy(progress = it()) } ?: s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /** [kib256]: the transport made over a stored ladder record that holds stage 4 and a 256 KiB last session (decisions
     *  AD.1, `LadderRecord.sessionKib`), so its first start asks 256 KiB and Main stages into its own region; the start
     *  rig's mirror is its own model of the phone's bytes, as on glass (not the lens, as `SimTransport`'s is). */
    private class Rig(val taps: Taps, val stage: Int = 4, nRows: Int = 7, val kib256: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-mainlocal")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x5ff }
        val t: wm.damage.core.transport.CfwTransportBase = if (!kib256) SimTransport(sim, scope, SimTransport.Timing(instant = true))
            else StartRig(sim, scope, c1StageFile = tmp.resolve("c1-stage").also { f ->
                Files.writeString(f, wm.damage.core.transport.LadderRecord(stage = 4, sessionKib = 256).encode()) })
        val journal = tmp.resolve("journal.jsonl")
        val shell = Shell(CachedText(FakeText()), t, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rows = (0 until nRows).map { Row("w$it", "Win $it", DamageWindow.Summary("summary $it", detail = "detail of $it", progress = if (it == 3) 0.25 else null)) }
        /** RIGHT's top-level images, in order. */
        val right = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        @Volatile var onImage: ((ByteArray) -> Unit)? = null
        /** Every top-level image either lens is handed, before it is handled (the first arm to see one: neither has it yet,
         *  and both have every image before it). */
        @Volatile var onAnyImage: ((Arm, ByteArray) -> Unit)? = null

        init {
            for (r in rows) shell.register(r)
            if (!kib256) t.devProbe("c1", "$stage")
            sim.imageObserver = { a, img -> onAnyImage?.invoke(a, img); if (a == Arm.RIGHT) { right += img; onImage?.invoke(img) } }
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

        /** Build ANIM (AW.4): Q in force and the bound pan's length (T × P) — the tests space notches by them. */
        fun q(): Long = onLoop { shell.localQForTest() }
        fun motionMs(): Long = onLoop { shell.motionMsForTest() }

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
        delay(((e.program.T + 1) * e.period * 1000L) / 1024 + r.q() + 200)   // the motion, then the burst's SETTLE
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
            delay(r.motionMs() + 30)                               // B after A's motion (AW.4), still inside its burst (Q)
            val tB = System.currentTimeMillis()
            val sB = r.fire(CfwModes.EV_UPROLL)                     // B: onto row 2, whose staged band form is the old one
            r.until("row 2 re-staged", 10_000) { r.notes("mainstage").any { it.startsWith("row 2 re-staged") } }
            // the re-stage waited for the burst's end: Q after B's report (the lone SETTLE), not between A and B
            val restagedAt = assertNotNull(r.noteAt("mainstage", "row 2 re-staged"))
            assertTrue(restagedAt >= tB + r.q() / 2, "row 2 re-staged ${restagedAt - tB} ms after B: not before the burst's SETTLE (Q ${r.q()} ms)")
            delay(r.q() + 300)
            r.settle("after")
            val imgs = r.right.drop(at)
            val commitB = imgs.indexOfFirst { img -> r.subs(img).any { r.isCommit(it, sB) } }
            assertTrue(commitB >= 0, "B onto the stale row is answered with a COMMIT (S $sB)")
            val settle = imgs.indexOfFirst { img -> r.subs(img).any { r.isSub(it, 26, 5) } }
            assertTrue(settle > commitB, "the burst's lone SETTLE after B's COMMIT (COMMIT $commitB, SETTLE $settle)")
            // no snap-back of the rules' own: no DISCARD answered A or B. (In the simulator the transport's mirror IS the
            // lens, so the row's repaint that follows the COMMIT meets B's adopted instance while it still draws and the
            // transport CLOSEs it first (§5.6) — RIGHT's stop reads as `burst-end`. On glass the mirror holds no local or
            // adopted instance and sends no such CLOSE: `C2-DI-built.md` §0.)
            assertTrue(imgs.none { img -> r.subs(img).any { r.isSub(it, 26, 4) } }, "no DISCARD answered A or B")
            // decisions AF.7: the stale row's held cells went once B's motion had ended — no motion was cut by them (in the
            // simulator the transport's mirror is the lens, so a write meeting a running instance CLOSEs it first: a cut
            // shows as "closed while drawing"; a CLOSE of the finished instance cuts nothing)
            assertTrue(r.notes("motion").none { "closed while drawing" in it }, "a held repaint cut a motion: ${r.notes("motion").filter { "closed while drawing" in it }}")
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
            val at = r.right.size
            val s = r.fire(CfwModes.EV_UPROLL)
            r.until("the notch adopted", 5_000) { r.row() == 1 }
            assertTrue(!r.onLoop { r.shell.mainRestingForTest() }, "the adopted notch woke Main")
            delay(r.q() + 400); r.settle("after the notch")
            assertTrue(r.right.drop(at).any { img -> r.subs(img).any { r.isCommit(it, s) } }, "a COMMIT of S $s")
            r.assertOracle("a notch at rest")
        } finally { r.close() }
    }

    /**
     * The brief's item 4, a measurement (the simulator on this PC's JVM — M for the PC, I for the phone; not a gate: it
     * runs only with `DI_LOOP=<notches>`): the shell loop's time per notch from the journal's `loop` note (handler time
     * per message class, the pumps, the mirror scans) with Main bound and the rules on (stage 4: the 115 adopted, the
     * echo consumed, the COMMIT planned with END painted into the model) against the same notches unbound (stage 3: the
     * stock message applied as today). Each rig warms up first; the minute tick writes and clears the note.
     */
    @Test
    fun loopTimePerNotch_DI(): Unit = runBlocking {
        val n = System.getenv("DI_LOOP")?.toInt() ?: return@runBlocking
        for (stage in listOf(4, 3)) {
            val r = Rig(Taps.REPORT, stage)
            try {
                if (stage == 4) r.up() else {
                    r.shell.start(); r.settle("start")
                    r.shell.updateSettings { it.copy(heightMode = 352, cachedText = "on", taps = Taps.REPORT) }
                    r.until("the atlas goes live") { r.shell.cachedTextActive && r.shell.cachedFontsLive.isNotEmpty() }
                    r.settle("up")
                }
                suspend fun notches(k: Int) = repeat(k) { i ->
                    val id = if ((i / 3) % 2 == 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL
                    if (stage == 4) r.fire(id) else r.t.injectInput(BoundList.stockEvent(id)!!)
                    delay(r.motionMs() + 50); r.settle("notch")
                }
                notches(10)                                              // warm-up
                delay(r.q() + 100); r.settle("warm")
                r.shell.postMinuteTick(); r.settle("minute")
                val before = r.notes("loop").size
                notches(n)
                delay(r.q() + 100); r.settle("measured")
                r.shell.postMinuteTick(); r.settle("minute")
                println("DI-loop stage $stage, $n notches: ${r.notes("loop").drop(before).joinToString(" || ")}")
                println("DI-loop stage $stage: snapbacks ${r.notes("snapback").size} ${r.notes("snapback").groupingBy { it.substringBefore(" S=") }.eachCount()}; " +
                    "CLOSEs ${r.notes("motion").count { it.startsWith("a flush meets open instance") }}; COMMITs ${r.right.count { img -> r.subs(img).any { it[0].toInt() == 26 && it[1].toInt() == 3 } }}; " +
                    "DISCARDs ${r.right.count { img -> r.subs(img).any { it[0].toInt() == 26 && it[1].toInt() == 4 } }}")
                // the assemble's own ms per submitted flush in the measured window (the journal's `submit` lines)
                val sub = Files.readAllLines(r.journal).filter { "\"ev\":\"submit\"" in it }.takeLast(200)
                    .map { it.substringAfter("\"assembleMs\":").substringBefore(",").toInt() }.sorted()
                println("DI-loop stage $stage: assembleMs over ${sub.size} flushes: median ${sub.getOrNull(sub.size / 2)} p90 ${sub.getOrNull(sub.size * 9 / 10)} max ${sub.lastOrNull()}")
            } finally { r.close() }
        }
    }

    /**
     * Stage DI, the chrome overlap settled: at the four heights, every program of Main's bound list (its areas are fixed
     * per program — the cursor picks pictures, never areas) lies inside Main's content clip on each lens (at the depths
     * Main's content is drawn at: the list's, the band's, the press's) and meets no chrome cell, bar or divider on either
     * lens (each at its own plane's disparity).
     */
    @Test
    fun theProgramsAreasLieInsideMainsContentAndMeetNoChrome_DI(): Unit = runBlocking {
        for (h in listOf(288, 352, 416, 480)) {
            val r = Rig(Taps.GLASSES)
            try {
                r.shell.start(); r.settle("start")
                r.shell.updateSettings { it.copy(heightMode = h, cachedText = "on", taps = Taps.GLASSES) }
                r.until("the atlas goes live") { r.shell.cachedTextActive && r.shell.cachedFontsLive.isNotEmpty() }
                r.bound("h=$h")
                val (l, planes) = r.onLoop { r.shell.layout to r.shell.comp.planes.toList() }
                fun dAt(x: Rect) = planes.lastOrNull { it.rect.contains(x) }?.disparity ?: 0
                val chrome = l.chromeCells().values + listOf(l.topBar, l.topDivider, l.bottomDivider, l.statusBar)
                // the content's planes, and the press's (the band row one depth notch nearer, never past the screen plane:
                // `MainStaging`, DM2 §0.3) — the band row drawn at that depth is still Main's content
                val listDs = planes.filter { l.content.contains(it.rect) }.map { it.disparity }.toSet()
                val contentDs = listDs + maxOf(0, listDs.min() - Geometry.X_STEP)
                for (e in r.list().entries) for (t in e.program!!.tracks) for (left in listOf(true, false)) {
                    val a = t.area(left)
                    val clip = contentDs.map { d -> l.content.translate(if (left) -d else d, 0) }.reduce(Rect::union)
                    assertTrue(clip.contains(a), "h=$h entry ${e.entry}: ${if (left) "L" else "R"} area $a inside Main's content clip $clip")
                    for (c in chrome) {
                        val d = dAt(c)
                        val lens = c.translate(if (left) -d else d, 0)
                        assertTrue(!lens.overlaps(a), "h=$h entry ${e.entry}: ${if (left) "L" else "R"} area $a meets chrome $lens")
                    }
                }
            } finally { r.close() }
        }
    }

    /** Stage DI's keep-out, measured on what it does since AF.7 (decisions AQ.2): a fast series of notches at 352 (one
     *  every 300 ms) — the chrome's position mark and the rail thumb beside the list's areas ride each notch's COMMIT
     *  flush (no merge across the areas), so the series takes about one flush per notch; merged with the areas they
     *  would be held whole until the motion's end and go in a flush of their own. No snap-back; the oracle holds. */
    @Test
    fun aFastSeriesOfNotchesTakesAboutOneFlushEach_AQ2(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            val f0 = r.onLoop { r.shell.flushesSubmitted }
            // five down from the top, five back: the rail's thumb moves deep enough that its change reaches the pan's
            // top track's rows (from row 3 on at 352), where the divider's mark beside it was merged with it
            repeat(20) { i ->
                r.fire(if ((i / 5) % 2 == 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)
                delay(r.motionMs() + 50); r.settle("notch $i")
            }
            delay(r.q() + 200); r.settle("after")
            val flushes = r.onLoop { r.shell.flushesSubmitted } - f0
            assertTrue(flushes <= FLUSHES_FOR_20, "20 notches took $flushes flushes (at most $FLUSHES_FOR_20: the chrome rides the COMMITs)")
            assertTrue(r.notes("snapback").isEmpty(), "no snap-back: ${r.notes("snapback").take(3)}")
            r.assertOracle("after the series")
        } finally { r.close() }
    }

    /** Decisions AF.1: a notice up — floating, not focused — is a cover: the list's entries are CLEARed while its box is
     *  on screen, and a notch then goes as today (no S taken, the row moves). A first notice puts its face in the atlas
     *  (which clears the stage registry and so withdraws the list by itself) and is dismissed; the second one, with
     *  nothing new to draw, is the cover alone. */
    @Test
    fun aNoticeUpIsACover_AF1(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            fun notice() = r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("TEST · a", "t", "a notice over Main", "12:00"))
            notice()
            r.until("the first notice focused", 10_000) { r.onLoop { r.shell.notificationsFocusedForTest() } }
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                 // dismissed
            r.until("the first notice gone", 10_000) { !r.onLoop { r.shell.notificationsUpForTest() } }
            r.bound("after the first notice")
            val staged = r.notes("mainstage").count { it.startsWith("staged") }
            val t0 = System.currentTimeMillis()
            notice()
            // while it floats, unfocused: well inside its 2.0 s grace (a focused notice was a cover before AF.1 too)
            r.until("the entries CLEARed while the notice floats", 1_500) { r.sim.bindForTest(Arm.RIGHT).field39() == 0 }
            assertTrue(!r.onLoop { r.shell.notificationsFocusedForTest() } && System.currentTimeMillis() - t0 < 2_400, "CLEARed before the notice took focus")
            assertEquals(staged, r.notes("mainstage").count { it.startsWith("staged") }, "no staging went: the cover, not a withdrawal, cleared the entries")
            val s0 = r.sim.bindForTest(Arm.RIGHT).seq
            val s = r.fire(CfwModes.EV_UPROLL)
            assertEquals(s0, s, "no entry matched under the notice")
            r.until("the notch applied as today") { r.row() == 1 }
            r.settle("as today")
            r.assertOracle("under the notice")
        } finally { r.close() }
    }

    /** Decisions AF.3: an adopted notch does a gesture's bookkeeping — Main's cursor reaches the stored record; and the
     *  `hold` probe is refused while BIND is in force. */
    @Test
    fun anAdoptedNotchIsAGestureAndHoldIsRefusedAtStage4_AF3(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            // the start's own settings changes scheduled a save (2 s after the last): let it go first, so the only save
            // that can carry the notch's row is one the notch itself asks for
            val state = r.tmp.resolve("state.json")
            fun storedCursor(): String? = if (!Files.exists(state)) null else Regex("""mainCursor\W+(\d+)""").find(Files.readString(state))?.groupValues?.get(1)
            delay(2_600); r.settle("no save pending")
            assertEquals("0", storedCursor(), "the stored record before the notch")
            r.fire(CfwModes.EV_UPROLL)
            r.until("adopted") { r.row() == 1 }
            r.until("Main's cursor in the stored record after the adopted notch", 4_000) { storedCursor() == "1" }
            r.shell.devProbe("hold", "on")
            r.until("hold refused") { r.notes("hold").any { it.startsWith("hold=on not taken — BIND is in force") } }
        } finally { r.close() }
    }

    /** Decisions AF.8: a tap acted on from its 115 is input — the window's first flush is journaled as the gesture's. */
    @Test
    fun anActFromAReportIsInput_AF8(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            val lines0 = Files.readAllLines(r.journal).size
            r.fire(CfwModes.EV_TAP)
            r.until("the window opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
            r.settle("opened")
            val first = Files.readAllLines(r.journal).drop(lines0).first { "\"ev\":\"submit\"" in it && "\"label\":\"WINDOW" in it }
            assertTrue("\"in\":1" in first, "the act's first flush marked as input: ${first.take(300)}")
        } finally { r.close() }
    }

    /** Decisions AU.1 (b): at 256 KiB, rows fuller than two strips' room (eighteen dense rows) stage with ONE strip —
     *  never "cannot be staged" while one fits — the list bound; a row form's change then withdraws and returns, as at
     *  160 KiB. (Two strips when they fit: `aRowFormChangeAt256KiBSwitchesStripsWithNoWithdrawal_AD2`.) */
    @Test
    fun fullerRowsStageWithOneStripAt256KiB_AU1(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, nRows = 18, kib256 = true)
        try {
            for (w in r.rows) w.s = w.s.copy(line = "W".repeat(80), detail = "W".repeat(80))
            r.up()
            assertTrue(r.notes("mainstage").none { it.startsWith("Main's rows cannot be staged") }, "${r.notes("mainstage")}")
            assertTrue(r.notes("mainstage").any { it.startsWith("two strips do not fit Main's region") }, "one strip staged: ${r.notes("mainstage")}")
            assertTrue(r.onLoop { r.shell.mainBinding.staging!!.stripB == null }, "no second strip")
            r.rows[3].s = r.rows[3].s.copy(line = "short 3")
            r.shell.services.requestRender(r.rows[3])
            r.until("withdrawn for the row form's change", 15_000) { r.notes("mainstage").any { it.startsWith("withdrawn") } }
            r.bound("bound again after the strip's write")
            r.assertOracle("after the withdrawal")
        } finally { r.close() }
    }

    /** Decisions AU.2: a face's first use while another window is in front (Settings' faces go live) leaves Main's own
     *  region intact — no record dropped, no staging byte sent there — and back on Main the list is bound with no staging
     *  again (rows whose live paint did not change are not re-staged). */
    @Test
    fun aFontSetChangeInAnotherWindowLeavesMainsRegionIntact_AU2(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            val staging0 = r.onLoop { r.shell.mainBinding.staging }
            val staged0 = r.notes("mainstage").count { it.startsWith("staged") }
            val live0 = r.onLoop { r.shell.cachedFontsLive.toSet() }
            val lines0 = Files.readAllLines(r.journal).size
            r.onLoop { r.shell.services.openWindow("settings", null) }
            r.until("Settings open") { r.onLoop { r.shell.currentWindowId() } == "settings" }
            r.until("a face went live in Settings", 15_000) { r.onLoop { r.shell.cachedFontsLive.toSet() } != live0 }
            r.settle("in Settings")
            val inSettings = Files.readAllLines(r.journal).drop(lines0)
            assertTrue(inSettings.none { "\"ev\":\"submit\"" in it && "\"label\":\"STAGE\"" in it }, "no staging byte while Settings is in front")
            assertTrue(r.onLoop { r.shell.mainBinding.staging } === staging0, "Main's staging kept")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            r.bound("bound on Main again")
            assertEquals(staged0, r.notes("mainstage").count { it.startsWith("staged") }, "no staging again: ${r.notes("mainstage").takeLast(4)}")
            assertTrue(r.notes("mainstage").none { it.startsWith("dropped") }, "Main's staging never dropped: ${r.notes("mainstage").takeLast(4)}")
            r.assertOracle("back on Main")
        } finally { r.close() }
    }

    /** Decisions AU.4: the `local` counters' note is written when a value changes (and once after a session start), not
     *  at every telemetry read — no two in a row the same. */
    @Test
    fun theCountersNoteIsWrittenOnlyWhenAValueChanges_AU4(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            repeat(3) { r.fire(CfwModes.EV_UPROLL); delay(r.motionMs() + 100); r.settle("notch $it") }
            delay(r.q() + 200); r.settle("after")
            val ns = r.notes("local").filter { it.startsWith("35=") }
            assertTrue(ns.isNotEmpty(), "at least one counters' note")
            assertTrue(ns.zipWithNext().none { (a, b) -> a == b }, "no counters' note repeated: ${ns.size} notes, ${ns.toSet().size} distinct")
        } finally { r.close() }
    }

    /** Build ANIM (AV): a change of the `Animations` rows makes the bound list's pans again — here by a re-staging (no
     *  switch carries a programs record) — once no burst is open; the list is bound again with the new motion, and a
     *  notch after it is COMMITted with it (its entry's T and P on RIGHT). */
    @Test
    fun anAnimationsChangeMakesThePansAgainAndANotchUsesThem_ANIM(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            val before = r.list().entries.first { it.eventId == CfwModes.EV_UPROLL }
            assertEquals(46 to 6, before.period to before.program!!.T, "the defaults at 352 (300 ms, decisions AZ.7)")
            val staged0 = r.notes("mainstage").count { it.startsWith("staged") }
            r.shell.updateSettings { it.copy(scrollLength = 450, frames = 0) }
            r.until("staged again with the new motion", 15_000) { r.notes("mainstage").count { it.startsWith("staged") } > staged0 }
            r.bound("bound with the new motion")
            val after = r.list().entries.first { it.eventId == CfwModes.EV_UPROLL }
            assertEquals(46 to 10, after.period to after.program!!.T, "450 ms Auto at 352: T 10 at P 46")
            assertEquals(46, r.sim.bindForTest(Arm.RIGHT).entries.filterNotNull().first { it.eventId == CfwModes.EV_UPROLL }.period, "RIGHT's entry")
            assertTrue(r.notes("mainstage").any { it.startsWith("dropped — the motion changed") }, "${r.notes("mainstage").takeLast(4)}")
            notch(r, CfwModes.EV_UPROLL, "a notch with the new motion")
        } finally { r.close() }
    }

    /** Decisions AW.3: a tap that opens a window 100 ms after an adopted notch (its motion ≈ 449 ms at the defaults): a
     *  CLOSE of the slot goes ahead of the window's first frame, the held cells are not held — the window's cells inside
     *  the list's areas go with that frame — the lens's instance is closed, and belief equals both lenses after. */
    @Test
    fun aWindowOpenedInsideTheWaitCutsTheMotion_AW3(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            val at = r.right.size
            val s = r.fire(CfwModes.EV_UPROLL)
            r.until("adopted") { r.row() == 1 }
            assertTrue(r.onLoop { r.shell.comp.deferring }, "the held cells wait for the motion's end")
            delay(100)
            r.fire(CfwModes.EV_TAP)
            r.until("the window opens") { r.onLoop { r.shell.currentWindowId() } == "w1" }
            assertTrue(!r.onLoop { r.shell.comp.deferring }, "nothing waits for the motion once the window is up")
            assertTrue(r.right.drop(at).any { img -> r.subs(img).any { r.isSub(it, 26, 2) } }, "a CLOSE went to RIGHT")
            assertTrue(r.right.drop(at).any { img -> r.subs(img).any { r.isCommit(it, s) } }, "the notch was COMMITted first")
            assertTrue(r.notes("local").any { "adopted motion closed ahead of its frame" in it }, "${r.notes("local").takeLast(3)}")
            r.settle("the window painted")
            assertTrue(r.sim.motionForTest(Arm.RIGHT).slots[0] == null, "RIGHT's slot 0 empty: ${r.sim.motionForTest(Arm.RIGHT).slots[0]}")
            r.assertOracle("after the cut")
        } finally { r.close() }
    }

    /** Decisions AW.3: a notice box (a partial cover) inside the wait cuts nothing — the held cells wait for the motion's
     *  end, the shell sends no CLOSE of its own, and belief equals both lenses after. (The simulator's transport, whose
     *  mirror is the lens itself, still closes an instance a flush meets — on glass the mirror holds no local instance.) */
    @Test
    fun aNoticeInsideTheWaitStillWaits_AW3(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            val at = r.right.size
            r.fire(CfwModes.EV_UPROLL)
            r.until("adopted") { r.row() == 1 }
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("TEST · a", "t", "a notice over Main", "12:00"))
            r.until("the notice up") { r.onLoop { r.shell.notificationsUpForTest() } }
            assertTrue(r.onLoop { r.shell.comp.deferring }, "the held cells still wait under a notice")
            r.settle("the motion's end")
            assertTrue(r.notes("local").none { "adopted motion closed ahead of its frame" in it }, "the shell cut nothing: ${r.notes("local").takeLast(3)}")
            r.assertOracle("after the notice")
        } finally { r.close() }
    }

    /** Decisions AL.5: a ring Input the rules read is no gesture of its own pump (its echo consumed marks nothing; a verdict
     *  that applies or acts on it marks its own); one they do not read (the temples, a typed line) is. */
    @Test
    fun anInputTheRulesReadDoesNotMarkItsPump_AL5(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            val ring = wm.damage.core.transport.TransportEvent.Input(EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.SRC_RING)
            val temple = wm.damage.core.transport.TransportEvent.Input(EvenHubMsg.EV_SCROLL_BOTTOM, EvenHubMsg.SRC_GLASSES_R)
            assertEquals(false, r.onLoop { r.shell.marksInput(ring) }, "a ring scroll the rules read")
            assertEquals(true, r.onLoop { r.shell.marksInput(temple) }, "a temple scroll")
            assertEquals(true, r.onLoop { r.shell.marksInput(wm.damage.core.transport.TransportEvent.Text("x")) }, "a typed line")
        } finally { r.close() }
    }

    /** Decisions AF.10: the bound staging holds the whole list's pans only (the probe's test widths are the probe's). */
    @Test
    fun theBoundStagingHoldsOnlyTheWholeListsPans_AF10(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            val ks = r.onLoop { r.shell.mainBinding.staging!!.pans.map { it.k } }
            assertTrue(ks.isNotEmpty() && ks.all { it == wm.damage.core.shell.MainStaging.WHOLE }, "pans of widths $ks")
        } finally { r.close() }
    }

    /** Decisions AF.6: a staging that did not fit (fourteen dense rows past the 64 KiB reserve) is tried again when the
     *  content it was sized for changes — the rows short again: staged and bound, with no restart. */
    @Test
    fun aStagingThatDidNotFitIsTriedAgainWhenItsContentChanges_AF6(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, nRows = 14)
        try {
            r.up()
            val dense = "W".repeat(80)
            for (w in r.rows) { w.s = w.s.copy(line = dense, detail = dense); r.shell.services.requestRender(w) }
            r.until("refused", 20_000) { r.notes("mainstage").any { it.startsWith("Main's rows cannot be staged") } }
            // decisions AL.1: the dense rows with a bar each (other sizes) are refused again for the same reason, said once
            for (w in r.rows) { w.s = w.s.copy(progress = 0.75); r.shell.services.requestRender(w) }
            r.settle("refused again"); delay(300); r.settle("refused again")
            assertEquals(1, r.notes("mainstage").count { it.startsWith("Main's rows cannot be staged") },
                "the refusal said once for one reason: ${r.notes("mainstage").filter { it.startsWith("Main's rows cannot") }}")
            for ((i, w) in r.rows.withIndex()) { w.s = DamageWindow.Summary("s $i", detail = "d $i"); r.shell.services.requestRender(w) }
            r.until("staged again", 20_000) { r.notes("mainstage").count { it.startsWith("staged") } >= 2 }
            r.bound("bound again")
        } finally { r.close() }
    }

    /** Decisions AQ.1 in the shell: an adopted notch, then — inside the held cells' wait — a tap that opens the row's
     *  window: one whose first view is not a list (the band plane goes) and one with its own Depth (12). The shell stays
     *  live (the loop answers), the window paints, no assemble error, and the oracle holds once the wait is over. */
    @Test
    fun aTapInsideTheWaitOpensAWindowAndTheShellStaysLive_AQ1(): Unit = runBlocking {
        for (kind in listOf("canvas", "depth 12")) {
            val r = Rig(Taps.REPORT)
            try {
                if (kind == "canvas") r.rows[1].canvas = true
                r.up()
                if (kind == "depth 12") {
                    r.shell.updateSettings { it.copy(appStyles = mapOf("w1" to wm.damage.core.shell.ShellSettings.AppStyle(depth = 12))) }
                    r.settle("$kind: the per-app depth")
                }
                r.fire(CfwModes.EV_UPROLL)
                r.until("$kind: adopted") { r.row() == 1 }
                assertTrue(r.onLoop { r.shell.isQuiescent() }.not(), "$kind: inside the wait")
                r.fire(CfwModes.EV_TAP)
                r.until("$kind: the window opens", 10_000) { r.onLoop { r.shell.currentWindowId() } == "w1" }
                r.settle("$kind: the window painted, the wait over")
                r.assertOracle("$kind: after the wait")
                assertTrue(r.notes("assemble-error").isEmpty(), "$kind: no assemble error (the oracle held): ${r.notes("assemble-error")}")
            } finally { r.close() }
        }
    }

    /** Decisions AL.6 (R1): the shell is not quiescent while the held cells wait for an adopted motion's end ((T + 1) × P
     *  after the report, ≈ 250 ms here): a harness settles after the release, with the repaint sent. */
    @Test
    fun theShellIsNotQuiescentWhileHeldCellsWait_AL6(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            r.fire(CfwModes.EV_UPROLL)
            r.until("adopted") { r.row() == 1 }
            val samples = ArrayList<Boolean>()
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 100) { samples += r.shell.isQuiescent(); delay(5) }
            r.settle("the motion ended")
            assertTrue(samples.none { it }, "not quiescent during the wait: ${samples.count { it }} of ${samples.size} samples quiescent")
        } finally { r.close() }
    }

    /** Decisions AL.1: Main's row key carries what Main draws — with a track playing (row 3's progress moving at every
     *  read, as `MusicWindow.summary` gives it) Main is staged and bound within seconds, and the loop rests between the
     *  bar's block changes (here one per 22.5 s). V2 reader B's probe, inverted. */
    @Test
    fun aPlayingTracksProgressLetsMainBeStagedAndTheLoopRest_AL1(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            val t0 = System.nanoTime()
            r.rows[3].progress = { ((System.nanoTime() - t0) / 1e6 % 180_000) / 180_000.0 }
            r.up()                                                    // bound, within up()'s wait
            var samples = 0; var quiet = 0
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 2_000) { samples++; if (r.shell.isQuiescent()) quiet++; delay(40) }
            assertTrue(quiet > samples / 2, "the loop rests while the track plays: quiescent in $quiet of $samples samples")
        } finally { r.close() }
    }

    /**
     * Decisions AD.2 in the shell (follow-up FC), on a 256 KiB session with BIND in force (the stored record's last size,
     * AD.1): Main's own region holds a second strip, and a row form's change writes the new strip there and SETs the
     * entries to the pans that name it — no CLEAR of the entries, no unbound moment. A notch before the switch, one fired
     * between the write and the SETs (when the first lens is handed the batch that SETs the new pans: both lenses then hold
     * the new strip and the old entries), and one after: each adopted and COMMITted, belief equal to both lenses after
     * each, the staging's active strip the other one, RIGHT's entries naming its pans. (The same at 160 KiB withdraws and
     * returns on the right row: `aRowFormChangeWithdrawsTheListAndANotchInBetweenGoesAsToday_DI`.)
     */
    @Test
    fun aRowFormChangeAt256KiBSwitchesStripsWithNoWithdrawal_AD2(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            assertEquals(256 * 1024, r.t.state.value.cacheSize, "a 256 KiB session from the stored record")
            assertTrue(r.onLoop { r.shell.mainBinding.staging?.stripB != null }, "Main's own region holds a second strip")
            val list0 = r.list()
            val oldProgs = list0.entries.filter { !it.isTap }.map { it.prog4 }.toSet()
            notch(r, CfwModes.EV_UPROLL, "the notch before the switch")
            delay(r.q() + 200); r.settle("the burst closed")
            val at = r.right.size
            var between: Int? = null
            r.onAnyImage = { _, img ->
                if (between == null && r.subs(img).any { r.isSub(it, 27, 0) && r.u16(it, 9) !in oldProgs && r.u16(it, 9) != 0xFFFF })
                    between = r.fire(CfwModes.EV_UPROLL)
            }
            r.rows[3].s = r.rows[3].s.copy(line = "summary 33")
            r.shell.services.requestRender(r.rows[3])
            r.until("a notch fired between the strip's write and the SETs", 15_000) { between != null }
            r.onAnyImage = null
            val sB = between!!
            delay(((list0.entries.first { it.eventId == CfwModes.EV_UPROLL }.let { (it.program!!.T + 1) * it.period }) * 1000L) / 1024 + r.q() + 200)
            r.settle("after the notch between")
            assertEquals(2, r.row(), "the notch between was adopted")
            assertTrue(r.right.drop(at).any { img -> r.subs(img).any { r.isCommit(it, sB) } }, "the notch between was COMMITted (S $sB): " +
                "applied ${r.notes("applied").takeLast(2)}; snapback ${r.notes("snapback").takeLast(2)}; mainstage ${r.notes("mainstage").takeLast(3)}; " +
                "answers ${r.right.drop(at).flatMap { img -> r.subs(img).filter { it[0].toInt() == 26 || it[0].toInt() == 27 }.map { x -> x.joinToString("") { "%02x".format(it) } } }.take(12)}")
            r.assertOracle("after the notch between the write and the SETs")
            notch(r, CfwModes.EV_UPROLL, "the notch after the switch")
            val list1 = r.list()
            val newDown = list1.entries.first { it.eventId == CfwModes.EV_UPROLL }.prog4
            assertTrue(newDown !in oldProgs, "the offered list names the other strip's pans")
            assertEquals(1, r.onLoop { r.shell.mainBinding.staging!!.activeStrip }, "the strip switched")
            assertEquals(newDown, r.sim.bindForTest(Arm.RIGHT).entries.filterNotNull().first { it.eventId == CfwModes.EV_UPROLL }.prog, "RIGHT's entry names the new pan")
            assertTrue(r.notes("applied").last().contains("prog $newDown"), "the last notch drew the new strip's pan: ${r.notes("applied").last()}")
            assertTrue(r.right.drop(at).none { img -> r.subs(img).any { r.isSub(it, 27, 1) } }, "no CLEAR of the entries: no unbound moment")
            assertTrue(r.notes("mainstage").none { it.startsWith("withdrawn") }, "no withdrawal: ${r.notes("mainstage").filter { it.startsWith("withdrawn") }}")
            assertTrue(r.notes("snapback").isEmpty() && r.notes("repair").isEmpty(), "no snap-back and no table repair: " +
                "${r.notes("snapback")} ${r.notes("repair")}")
            // AL.4: the second strip slot went with the base records, so the switch's flush carries only the changed row
            val staged = r.notes("mainstage").first { it.startsWith("staged") }
            val restaged = r.notes("mainstage").first { it.startsWith("row 3 re-staged") }
            val stripB = Regex("""its second slot (\d+) B""").find(staged)?.groupValues?.get(1)?.toInt()
            val switchB = Regex("""re-staged — (\d+) B""").find(restaged)!!.groupValues[1].toInt()
            assertTrue(stripB != null && switchB < stripB / 2, "the second slot staged with the base records ($staged); the switch's flush small ($restaged)")
        } finally { r.close() }
    }

    /** Fact finding for the chrome overlap (run with `DI_FACTS=1`): at the four heights, Main's content clip, the rows
     *  Main paints, the rail, the dividers and the chrome cells, the planes, each program's tracks per lens; then one
     *  adopted notch — which pixels changed in the nominal frame, by element, and the flushes that carried them. */
    @Test
    fun chromeOverlapFacts_DI(): Unit = runBlocking {
        System.getenv("DI_FACTS") ?: return@runBlocking
        for (h in listOf(288, 352, 416, 480)) {
            val r = Rig(Taps.REPORT)
            try {
                r.shell.start(); r.settle("start")
                r.shell.updateSettings { it.copy(heightMode = h, cachedText = "on", taps = Taps.REPORT) }
                r.until("the atlas goes live") { r.shell.cachedTextActive && r.shell.cachedFontsLive.isNotEmpty() }
                r.bound("h=$h")
                val l = r.onLoop { r.shell.layout }
                val above = (0 until l.rowsAbove).map { Rect(l.content.x, l.lens.y - (l.rowsAbove - it) * l.rowH, l.content.w - wm.damage.core.geom.Layout.RAIL_W, l.rowH) }
                val below = (0 until l.rowsBelow).map { Rect(l.content.x, l.lens.bottom + it * l.rowH, l.content.w - wm.damage.core.geom.Layout.RAIL_W, l.rowH) }
                println("FACT h=$h safe ${l.safe} topBar ${l.topBar} topDivider ${l.topDivider} content ${l.content} rail ${l.rail} lens ${l.lens} rowH ${l.rowH} " +
                    "rows above ${l.rowsAbove} (first ${above.firstOrNull()}) below ${l.rowsBelow} (last ${below.lastOrNull()}) bottomDivider ${l.bottomDivider} statusBar ${l.statusBar}")
                println("FACT h=$h chrome cells ${l.chromeCells()}")
                println("FACT h=$h planes ${r.onLoop { r.shell.comp.planes.joinToString { p -> "${p.rect}@${p.disparity}" } }}")
                val list = r.list()
                for (e in list.entries) e.program?.let { p -> println("FACT h=$h entry ${e.entry} id ${e.eventId}: tracks " + p.tracks.joinToString { "L ${it.areaL} R ${it.areaR}" }) }
                // one adopted notch: the nominal frame before and after, and the flushes
                val before = r.onLoop { r.shell.comp.composed.pix.copyOf() }
                val lines0 = Files.readAllLines(r.journal).size
                r.fire(CfwModes.EV_UPROLL)
                delay(700); r.settle("after")
                val after = r.onLoop { r.shell.comp.composed.pix.copyOf() }
                val regions = linkedMapOf("topDivider" to l.topDivider, "pad above" to Rect(l.content.x, l.content.y, l.content.w - wm.damage.core.geom.Layout.RAIL_W, above.first().y - l.content.y),
                    "rail" to l.rail, "topBar" to l.topBar, "statusBar+div" to Rect(l.statusBar.x, l.bottomDivider.y, l.statusBar.w, l.statusBar.bottom - l.bottomDivider.y))
                val w = Geometry.PANEL_W
                for ((name, rr) in regions) {
                    var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
                    for (y in rr.y until rr.bottom) for (x in rr.x until rr.right) if (before[y * w + x] != after[y * w + x]) { x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) }
                    println("FACT h=$h notch: $name $rr changed " + (if (x1 < 0) "nothing" else "ink (${x0},${y0})-(${x1},${y1})"))
                }
                println("FACT h=$h flushes after the notch:\n" + Files.readAllLines(r.journal).drop(lines0).filter { "\"ev\":\"submit\"" in it || "open instance" in it }
                    .joinToString("\n") { "  " + (if ("submit" in it) it.substringAfter("\"ops\":") else it.substringAfter("\"detail\":\"").substringBefore("\"")) })
            } finally { r.close() }
        }
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
            // decisions AF.2: the return named the phone's current row — RIGHT's list cursor is where the frame is
            assertEquals(r.row(), r.sim.bindForTest(Arm.RIGHT).lists[0].cursor, "the return named the current row on RIGHT")
            r.assertOracle("bound again")
            notch(r, CfwModes.EV_UPROLL, "a notch after the re-bind")
        } finally { r.close() }
    }

    /** G1 item 5 (his glasses, 0.79, 2026-11-19 14:46:56): `Scroll length` 600 (T 13 at P 46), a notch to the last row
     *  and 6 ms later a notch past the edge (the wrap) — the as-on-glass mirror (the start rig's own model) must agree
     *  with belief throughout: no `divergence` note, belief equal to both lenses after. */
    @Test
    fun aWrapSixMsAfterANotchToTheLastRowKeepsBeliefAndMirrorInAgreement_G1_5(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            val staged0 = r.notes("mainstage").count { it.startsWith("staged") }
            r.shell.updateSettings { it.copy(scrollLength = 600, frames = 0) }
            r.until("staged again with the new motion", 15_000) { r.notes("mainstage").count { it.startsWith("staged") } > staged0 }
            r.bound("bound at 600 ms")
            val e = r.list().entries.first { it.eventId == CfwModes.EV_UPROLL }
            assertEquals(46 to 13, e.period to e.program!!.T, "600 ms Auto at 352: T 13 at P 46")
            val count = r.list().count
            while (r.row() < count - 2) { r.fire(CfwModes.EV_UPROLL); delay(r.motionMs() + r.q() + 200); r.settle("toward the end") }
            assertEquals(count - 2, r.row(), "one row above the last")
            r.fire(CfwModes.EV_UPROLL)
            delay(6)
            r.fire(CfwModes.EV_UPROLL)                               // past the edge: the wrap
            for (k in 0 until 40) { delay(50); assertTrue(r.notes("divergence").isEmpty(), "at ${k * 50} ms after the wrap: ${r.notes("divergence")}; " +
                "applied ${r.notes("applied").takeLast(2)}; snapback ${r.notes("snapback").takeLast(2)}") }
            delay(r.motionMs() + r.q() + 300); r.settle("after the wrap")
            assertTrue(r.notes("divergence").isEmpty(), "${r.notes("divergence")}")
            assertEquals(0, r.row(), "the wrap landed on the first row")
            r.assertOracle("after the wrap")
        } finally { r.close() }
    }
    // ---------------------------------------------------------------------------------------------- AZ.4: the wrap
    /** Notches to [to], each one's motion and burst let out. */
    private suspend fun walkTo(r: Rig, to: Int) {
        while (r.row() != to) {
            r.fire(if (to > r.row()) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)
            delay(r.motionMs() + r.q() + 200); r.settle("toward row $to")
        }
    }

    private fun isWrapPlay(s: ByteArray) = s[0].toInt() == 26 && s[1].toInt() == 0 && s[2].toInt() == MainStaging.WRAP_SLOT

    /** RIGHT's first image since [at] that carries the wrap's PLAY, and that image's sub-messages. */
    private fun wrapBatch(r: Rig, at: Int): List<ByteArray>? = r.right.drop(at).map { r.subs(it) }.firstOrNull { subs -> subs.any { isWrapPlay(it) } }

    /** No `divergence` note for [ms], read every 50 ms. */
    private suspend fun noDivergenceFor(r: Rig, ms: Long, what: String) {
        var t = 0L
        while (t < ms) { delay(50); t += 50; assertTrue(r.notes("divergence").isEmpty(), "$what, $t ms in: ${r.notes("divergence")}") }
    }

    /** The wrap down from the last row: [direction] +1 (UPROLL from the last row) or −1 (DOWNROLL from row 0). Returns the
     *  batch that carried the PLAY. Asserts: the PLAY in slot 1 is the bound entry's program at its period, ahead of the
     *  DISCARD naming list 0 at the wrapped row; the pan's areas move on RIGHT; at rest the row is the other end, RIGHT's
     *  cursor on it, the instance drew its last step, no divergence, belief equal to both lenses. */
    private suspend fun wrap(r: Rig, direction: Int, what: String): List<ByteArray> {
        val list = r.list()
        val count = list.count
        val from = if (direction > 0) count - 1 else 0
        val to = if (direction > 0) 0 else count - 1
        assertEquals(from, r.row(), "$what: at the end")
        val ev = if (direction > 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL
        val e = assertNotNull(list.entryFor(ev))
        val before = r.panel(false)
        val at = r.right.size
        r.fire(ev)
        r.until("$what: the wrap's PLAY on RIGHT") { wrapBatch(r, at) != null }
        val batch = wrapBatch(r, at)!!
        val play = batch.first { isWrapPlay(it) }
        assertEquals(e.period, play[3].toInt() and 0xFF, "$what: the PLAY's period is the entry's (the Animations rows)")
        assertEquals(e.prog4, r.u16(play, 4), "$what: the PLAY's program is the entry's pan")
        val iPlay = batch.indexOfFirst { isWrapPlay(it) }
        val iDiscard = batch.indexOfFirst { r.isSub(it, 26, 4) }
        assertTrue(iDiscard > iPlay, "$what: the DISCARD follows the PLAY in its batch")
        assertEquals(0, batch[iDiscard][5].toInt(), "$what: the DISCARD names list 0")
        assertEquals(to, r.u16(batch[iDiscard], 6), "$what: ...at the wrapped row")
        assertTrue(batch.none { r.isSub(it, 26, 3) }, "$what: no COMMIT beside it")
        delay((e.period * 2 * 1000L) / 1024 + 10)
        val mid = r.panel(false)
        for (track in e.program!!.tracks) {
            val a = track.areaR
            var moved = false
            for (y in maxOf(0, a.y) until minOf(Geometry.PANEL_H, a.bottom)) { for (x in maxOf(0, a.x) until minOf(Geometry.PANEL_W, a.right)) if (mid[x, y] != before[x, y]) { moved = true; break }; if (moved) break }
            assertTrue(moved, "$what: the pan's track at $a changed on RIGHT mid-motion")
        }
        // the instance's last step, before the held cells go (they wait past its end): what the lens shows in the areas then
        // must be what it shows at rest — END is Main's paint at the wrapped row, and the release has nothing to send there
        r.until("$what: the wrap's last step on RIGHT") { r.sim.motionForTest(Arm.RIGHT).slots[MainStaging.WRAP_SLOT]?.drawing != true }
        val atEnd = r.panel(false)
        assertTrue(r.notes("divergence").isEmpty(), "$what: ${r.notes("divergence")}")
        delay(r.q() + 200); r.settle("$what: after")
        val rest = r.panel(false)
        for (track in e.program.tracks) {
            val a = track.areaR
            var off = 0
            for (y in maxOf(0, a.y) until minOf(Geometry.PANEL_H, a.bottom)) for (x in maxOf(0, a.x) until minOf(Geometry.PANEL_W, a.right)) if (atEnd[x, y] != rest[x, y]) off++
            assertEquals(0, off, "$what: px of the pan's track at $a where the last step is not Main's paint at the wrapped row")
        }
        assertEquals(to, r.row(), "$what: the row is the other end")
        assertEquals(to, r.sim.bindForTest(Arm.RIGHT).lists[0].cursor, "$what: RIGHT's cursor is on it")
        assertTrue(r.notes("motion").any { "slot ${MainStaging.WRAP_SLOT} " in it && "drew its last step" in it }, "$what: the wrap's instance drew its last step: ${r.notes("motion").takeLast(3)}")
        assertTrue(r.notes("snapback").last().endsWith("wrap=play"), "$what: ${r.notes("snapback").last()}")
        assertTrue(r.notes("divergence").isEmpty(), "$what: ${r.notes("divergence")}")
        r.assertOracle(what)
        return batch
    }

    /** Decisions AZ.4: Main wraps at 352 with its rows (the strip's margins hold one more row of the other end each): a
     *  notch past the last row slides one row to the first, started by the phone; and back up past row 0 to the last. */
    @Test
    fun theWrapSlidesOneRowBothWays_AZ4(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            assertTrue(r.onLoop { r.shell.mainBinding.staging!!.wraps }, "Main wraps at 352 with its rows")
            walkTo(r, r.list().count - 1)
            wrap(r, +1, "down past the last row")
            wrap(r, -1, "up past row 0")
            notch(r, CfwModes.EV_DOWNROLL, "a notch after the wraps is an ordinary fire")
        } finally { r.close() }
    }

    /** Decisions AZ.4: a list that does not wrap (its rows all fit: blanks past the ends) keeps today's edge — no PLAY. */
    @Test
    fun aListThatDoesNotWrapKeepsTodaysEdge_AZ4(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, nRows = 3, kib256 = true)
        try {
            r.up()
            assertTrue(!r.onLoop { r.shell.mainBinding.staging!!.wraps }, "Main's ${r.list().count} rows fit at 352")
            walkTo(r, r.list().count - 1)
            val at = r.right.size
            r.fire(CfwModes.EV_UPROLL)
            delay(r.motionMs() + r.q() + 300); r.settle("after the edge")
            assertTrue(wrapBatch(r, at) == null, "no wrap PLAY")
            assertTrue(r.notes("local").none { "wrap's PLAY is refused" in it }, "none was even made: ${r.notes("local").takeLast(2)}")
            assertTrue(r.notes("snapback").last().let { "cause=edge" in it && "wrap=play" !in it }, r.notes("snapback").last())
            r.assertOracle("after the edge")
        } finally { r.close() }
    }

    /** Decisions AZ.4: a notch while the wrap's motion draws — the lens's fire in slot 0 closes the phone's instance in
     *  slot 1 (their areas meet) and draws from the wrapped row; adopted, COMMITted; belief equal to both lenses. And the
     *  same with the wrap at the end of a quick burst (the notches inside Q). */
    @Test
    fun aNotchDuringTheWrapClosesItAndIsAnOrdinaryFire_AZ4(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            val count = r.list().count
            // a burst: every notch inside Q of the last, down to the end, then the wrap, then one more
            while (r.row() < count - 1) { r.fire(CfwModes.EV_UPROLL); delay(r.motionMs() + 30) }
            r.until("at the last row") { r.row() == count - 1 }
            val at = r.right.size
            r.fire(CfwModes.EV_UPROLL)
            r.until("the wrap's PLAY on RIGHT") { wrapBatch(r, at) != null }
            delay(60)
            val s = r.fire(CfwModes.EV_UPROLL)
            r.until("the wrap's instance closed on RIGHT") { r.sim.motionForTest(Arm.RIGHT).slots[MainStaging.WRAP_SLOT]?.drawing != true }
            // P9: in a slot of its own, the fire's close pass ends it with RIGHT's outcome-1 body, so the mirror hears of it (in
            // the bound slot the fire would end it silently)
            r.until("RIGHT's body for the closed wrap") { r.notes("motion").any { "slot ${MainStaging.WRAP_SLOT} " in it && "closed while drawing" in it } }
            noDivergenceFor(r, r.motionMs() + 300, "after the notch")
            delay(r.q() + 300); r.settle("after the notch")
            assertEquals(1, r.row(), "the notch from the wrapped row was adopted")
            assertTrue(r.right.drop(at).any { img -> r.subs(img).any { r.isCommit(it, s) } }, "a COMMIT of S $s")
            assertTrue(r.notes("divergence").isEmpty(), "${r.notes("divergence")}")
            r.assertOracle("after the notch")
        } finally { r.close() }
    }

    /** Decisions AZ.4: a tap while the wrap's motion draws acts on the wrapped row (its window opens; the window's frame
     *  meets the instance, so the transport CLOSEs it first); and a window opened over the list during the motion closes it
     *  the same way. Belief equals both lenses after each. */
    @Test
    fun aTapOrAWindowDuringTheWrapClosesIt_AZ4(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            walkTo(r, r.list().count - 1)
            var at = r.right.size
            r.fire(CfwModes.EV_UPROLL)
            r.until("the wrap's PLAY on RIGHT") { wrapBatch(r, at) != null }
            delay(40)
            r.fire(CfwModes.EV_TAP)
            r.until("the first row's window opened") { r.onLoop { r.shell.currentWindowId() } == r.rows[0].id }
            r.until("the wrap's instance closed on RIGHT") { r.sim.motionForTest(Arm.RIGHT).slots[MainStaging.WRAP_SLOT]?.drawing != true }
            r.settle("in the window")
            assertTrue(r.notes("divergence").isEmpty(), "${r.notes("divergence")}")
            r.assertOracle("in the row's window")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            r.bound("bound again")
            walkTo(r, r.list().count - 1)
            at = r.right.size
            r.fire(CfwModes.EV_UPROLL)
            r.until("the second wrap's PLAY on RIGHT") { wrapBatch(r, at) != null }
            delay(40)
            r.onLoop { r.shell.services.openWindow("settings", null) }
            r.until("Settings open") { r.onLoop { r.shell.currentWindowId() } == "settings" }
            r.until("the wrap's instance closed on RIGHT by the window") { r.sim.motionForTest(Arm.RIGHT).slots[MainStaging.WRAP_SLOT]?.drawing != true }
            r.settle("in Settings")
            assertTrue(r.notes("divergence").isEmpty(), "${r.notes("divergence")}")
            r.assertOracle("in Settings")
        } finally { r.close() }
    }

    /** Decisions AZ.4 with the Animations rows: at Scroll length 600 the wrap's instance on RIGHT has the bound pan's T (13
     *  at P 46), and the lone SETTLE comes Q after the edge's report, once the wrap's motion has ended. */
    @Test
    fun theWrapTakesTheAnimationsRowsAndTheSettleComesAfterIt_AZ4(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            val staged0 = r.notes("mainstage").count { it.startsWith("staged") }
            r.shell.updateSettings { it.copy(scrollLength = 600, frames = 0) }
            r.until("staged again with the new motion", 15_000) { r.notes("mainstage").count { it.startsWith("staged") } > staged0 }
            r.bound("bound at 600 ms")
            walkTo(r, r.list().count - 1)
            val at = r.right.size
            val firedAt = System.currentTimeMillis()
            r.fire(CfwModes.EV_UPROLL)
            r.until("the wrap's PLAY on RIGHT") { wrapBatch(r, at) != null }
            val inst = assertNotNull(r.sim.motionForTest(Arm.RIGHT).slots[MainStaging.WRAP_SLOT])
            assertEquals(46 to 13, inst.period to inst.T, "the wrap's instance: the bound pan's period and T at 600 ms")
            var settleAt: Long? = null
            r.onImage = { img -> if (settleAt == null && r.subs(img).any { r.isSub(it, 26, 5) }) settleAt = System.currentTimeMillis() }
            r.until("the lone SETTLE", 10_000) { settleAt != null }
            r.onImage = null
            assertTrue(settleAt!! - firedAt >= r.q(), "the SETTLE came ${settleAt!! - firedAt} ms after the edge, Q ${r.q()}")
            delay(r.motionMs()); r.settle("after")
            assertTrue(r.notes("motion").any { "slot ${MainStaging.WRAP_SLOT} " in it && "drew its last step" in it }, "the wrap ran to its last step: ${r.notes("motion").takeLast(3)}")
            r.assertOracle("after the wrap at 600 ms")
        } finally { r.close() }
    }
    // ---------------------------------------------------------------------------------------------- AZ.3: S_heard's seed
    /** The kib256 rig's glasses before its first start: S at [s] on both lenses and the lease live for [leaseMs] more ms
     *  (0: none) — a process that ended while the lease ran (an app update), with no lease on the new one's record. */
    private fun lensKept(r: Rig, s: Int, leaseMs: Long) {
        for (a in Arm.entries) {
            r.sim.bindForTest(a).seqForTest(s)
            if (leaseMs > 0) r.sim.forceLease(a, System.currentTimeMillis() + leaseMs)
        }
    }

    /** The first notch of the session after [lensKept]: adopted, COMMITted, and its report took S [s] with no gap (so no
     *  echo owes and P3's bound one row). */
    private suspend fun firstNotchHasNoGap(r: Rig, s: Int, what: String) {
        val got = r.fire(CfwModes.EV_UPROLL)
        assertEquals(s, got, "$what: RIGHT's S at the first notch")
        delay(r.motionMs() + r.q() + 300); r.settle("$what: after the first notch")
        assertEquals(1, r.row(), "$what: the first notch was adopted")
        assertTrue(r.right.any { img -> r.subs(img).any { r.isCommit(it, s) } }, "$what: a COMMIT of S $s")
        val counters = r.notes("local").filter { it.startsWith("35=") }
        assertTrue(counters.none { "gap=" in it || "owesAtSettle=" in it || "stale=" in it }, "$what: ${counters.lastOrNull()}")
    }

    /** Decisions AZ.3, the update case of 14:40:56: the lens kept S 37 and its lease (40 s left); the phone's lease record
     *  is empty. S_heard is seeded 37 from RIGHT's read, and the first report, S 38, shows no gap. */
    @Test
    fun aStartWhoseLensKeptItsLeaseSeedsSHeardFromRightsRead_AZ3(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            lensKept(r, 37, 40_000)
            r.up()
            assertEquals(37, r.t.state.value.bindSHeard0, "S_heard seeded from field 36")
            firstNotchHasNoGap(r, 38, "the lens kept S 37")
        } finally { r.close() }
    }

    /** Decisions AZ.3: a lens that restarted holds no lease and S 0 — S_heard 0, the first report S 1 with no gap. */
    @Test
    fun aStartAfterALensRestartSeedsZero_AZ3(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            lensKept(r, 0, 0)
            r.up()
            assertEquals(0, r.t.state.value.bindSHeard0, "S_heard 0")
            firstNotchHasNoGap(r, 1, "a restarted lens")
        } finally { r.close() }
    }

    /** Decisions AZ.3: RIGHT's read shows the lease with 1.5 s left, under the 2 s spare, and it lapses before the acquire
     *  lands (a release point on the lens: S := 0). The seed is not used — S_heard 0, not ahead of the lens's S (a seed
     *  of 37 would make the phone drop the next 37 reports as stale, P8). The rig's session after a lapse is a 64 KiB one
     *  (the cache-size rule), so no notch follows here; the fuzz's invariant (4) reads the same order at every start. */
    @Test
    fun aLeaseThatLapsesBeforeTheAcquireIsNotSeededFrom_AZ3(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            lensKept(r, 37, 1_500)
            val lapsed = java.util.concurrent.ConcurrentHashMap.newKeySet<Arm>()
            (r.t as StartRig).onWrite = { arm, p ->
                if (StartRig.damageOp(p) == wm.damage.core.wire.SettingsMsg.OP_FB_ACQUIRE && lapsed.add(arm))
                    r.sim.forceLease(arm, System.currentTimeMillis() - 1)       // this arm's first acquire: lapsed on its way
                p
            }
            r.shell.start(); r.settle("start")
            (r.t as StartRig).onWrite = null
            assertEquals(2, lapsed.size, "both arms' acquires met a lapsed lease")
            assertEquals(0, r.sim.bindForTest(Arm.RIGHT).seq, "the lens's release point reset S")
            assertEquals(0, r.t.state.value.bindSHeard0, "the seed is not used: S_heard is not ahead of the lens's S")
        } finally { r.close() }
    }

    /** Decisions AZ.3: the read shows 1.5 s left, under the spare, and the lease does NOT lapse (the lens renews and keeps
     *  S 37): the seed is still not used — the spare is the rule, the old wide gap the cost on this side. */
    @Test
    fun aLeaseUnderTheSpareIsNotSeededFrom_AZ3(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            lensKept(r, 37, 1_500)
            r.up()
            assertEquals(0, r.t.state.value.bindSHeard0, "the seed is not used under the spare")
        } finally { r.close() }
    }
    // ---------------------------------------------------------------------------------------------- G4
    /** G4 item 1 (his glasses, 0.80: the wrap's slide began a median 555 ms after the edge's report): the wrap's PLAY goes
     *  before the shell assembles the wrap's frame — no assemble between the edge's verdict and the PLAY — in a flush of
     *  its own with the rules' answer and no drawing sub; the wrap still lands whole (belief equal to both lenses). */
    @Test
    fun theWrapsPlayGoesBeforeItsFramesAssemble_G4(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            walkTo(r, r.list().count - 1)
            val at = r.right.size
            r.fire(CfwModes.EV_UPROLL)
            r.until("the wrap's PLAY on RIGHT") { wrapBatch(r, at) != null }
            val batch = wrapBatch(r, at)!!
            assertTrue(batch.none { r.drawing(it) && !isWrapPlay(it) }, "the PLAY's flush draws nothing: ${batch.map { "${it[0]}/${it[1]}" }}")
            assertEquals(0, r.onLoop { r.shell.wrapAssemblesBeforeForTest() }, "no frame was assembled before the wrap's PLAY")
            delay(r.motionMs() + r.q() + 300); r.settle("after the wrap")
            assertEquals(0, r.row())
            assertTrue(r.notes("divergence").isEmpty(), "${r.notes("divergence")}")
            r.assertOracle("after the wrap")
        } finally { r.close() }
    }

    private fun motionIn(p: ByteArray): wm.damage.core.wire.DamageMsg.Motion? {
        val f = wm.damage.core.wire.AaFrame.Reassembler { }.offer(p) ?: return null
        val b = wm.damage.core.wire.Pb.bytesField(f.payload, wm.damage.core.wire.DamageMsg.MOTION_FIELD) ?: return null
        return wm.damage.core.wire.DamageMsg.parseMotion(wm.damage.core.wire.Pb.l(wm.damage.core.wire.DamageMsg.MOTION_FIELD, b))
    }

    /** G4 item 2 (his glasses, 18:53:34, five keyframes in ten minutes): a re-stage's write closes a notch's local
     *  instance on the lens (§5.6: a cache write meeting its read range) before the notch's COMMIT lands, and RIGHT's
     *  "closed while drawing" body — END CRC 0, no END written (§6.4) — reaches the phone after the COMMIT went. That is
     *  no divergence: no keyframe; the closed instance's areas are repainted; belief equals both lenses after. */
    @Test
    fun aLocalClosedBeforeItsCommitLandsIsNoDivergence_G4(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            val rig = r.t as StartRig
            val regionStart = CfwModes.mainRegion(256 * 1024, true).first
            fun firstOff(img: ByteArray): Int = when (img[0].toInt() and 0x7F) {
                19 -> r.u16(img, 1) * 4
                25 -> java.util.zip.Inflater().run { setInput(img, 1, img.size - 1); val out = ByteArray(4); inflate(out); end(); r.u16(out, 0) * 4 }
                else -> -1
            }
            var s: Int? = null
            val held = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
            // RIGHT's closed body for the notch's instance is held until the notch's COMMIT has been written (the link's
            // order on his glasses: the body came after the COMMIT went)
            rig.onNotify = { arm, p -> val m = motionIn(p)
                if (arm == Arm.RIGHT && s != null && m != null && m.slot == 0 && m.tag == s && m.outcome == wm.damage.core.wire.DamageMsg.OUTCOME_CLOSED) { held += p; null } else p }
            r.onAnyImage = { a, img ->
                if (a == Arm.RIGHT && s == null && r.cacheWrite(img) && firstOff(img) >= regionStart) s = r.fire(CfwModes.EV_UPROLL)
                if (a == Arm.RIGHT && s != null && held.isNotEmpty() && r.subs(img).any { r.isCommit(it, s!!) })
                    r.scope.launch { delay(20); for (p in held) rig.deliver(Arm.RIGHT, p); held.clear() }
            }
            r.rows[3].s = r.rows[3].s.copy(line = "summary 33")
            r.shell.services.requestRender(r.rows[3])
            r.until("a notch fired as the re-stage's write went out", 15_000) { s != null }
            r.until("the closed body delivered after the COMMIT", 15_000) { r.notes("motion").any { "tag 0x%04x".format(s) in it && "closed while drawing" in it } }
            r.onAnyImage = null; rig.onNotify = null
            delay(r.motionMs() + r.q() + 300); r.settle("after")
            assertTrue(r.notes("divergence").isEmpty(), "no divergence: ${r.notes("divergence")}")
            assertTrue(r.notes("motion").any { "local instance's own body" in it }, "the body was read as a local's own: ${r.notes("motion").takeLast(4)}")
            r.assertOracle("after the closed local and its COMMIT")
        } finally { r.close() }
    }

    /** Decisions BF (G4): steps measured at 48 ms against P 46 (44.9 ms; the model folds one step a motion then) raise the
     *  period within a few motions — SETs alone, with no burst open — and the folds stop; a run of fast steps (30 ms) brings
     *  it down only after a whole window of eight motions since the change. */
    @Test
    fun thePeriodFollowsTheMeasuredSteps_BF(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            assertEquals(46, r.onLoop { r.shell.periodInForceForTest() }, "the fit at 352")
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 48_000L
            val at = r.right.size
            var notches = 0
            while (r.onLoop { r.shell.periodInForceForTest() } == 46 && notches < 4) {
                r.fire(if (r.row() < r.list().count - 2) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL); notches++
                delay(r.motionMs() + r.q() + 300); r.settle("slow notch $notches")
            }
            assertEquals(55, r.onLoop { r.shell.periodInForceForTest() }, "48 ms × 1.10 → 55 ticks, after $notches notch(es)")
            assertTrue(notches <= 2, "within a few motions: $notches")
            assertTrue(r.notes("mainstage").any { it.startsWith("period 46 → 55") }, "${r.notes("mainstage").takeLast(2)}")
            // the change's flush: SETs alone (no cache write, no LIST), after the burst's SETTLE
            val imgs = r.right.drop(at)
            val setAt = imgs.indexOfFirst { img -> r.subs(img).any { r.isSub(it, 27, 0) && (it[8].toInt() and 0xFF) == 55 } }
            assertTrue(setAt >= 0, "the entries SET with P 55")
            val setSubs = r.subs(imgs[setAt]).filter { r.isSub(it, 27, 0) }
            println("BF-sets: ${setSubs.size} SET(s), ${setSubs.sumOf { it.size }} B")
            assertTrue(imgs.none { r.cacheWrite(it) }, "no re-stage")
            assertTrue(imgs.take(setAt).any { img -> r.subs(img).any { r.isSub(it, 26, 5) } }, "the SETs only once the burst's lone SETTLE went")
            assertTrue(r.subs(imgs[setAt]).none { r.isSub(it, 26, 3) }, "not in a COMMIT's batch (a burst's answer)")
            // the next notches: no fold at P 55
            val bodies0 = r.notes("motion").size
            repeat(2) { r.fire(CfwModes.EV_DOWNROLL); delay(r.motionMs() + r.q() + 300); r.settle("notch at P 55") }
            val after = r.notes("motion").drop(bodies0).filter { "committed" in it }
            assertTrue(after.isNotEmpty() && after.all { "folded 0" in it }, "no fold at P 55: $after")
            // fast steps: down only after eight motions since the change
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 30_000L
            var n = 0
            while (r.onLoop { r.shell.periodInForceForTest() } == 55 && n < 12) {
                r.fire(if (n % 2 == 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL); n++
                delay(r.motionMs() + r.q() + 300); r.settle("fast notch $n")
            }
            assertEquals(34, r.onLoop { r.shell.periodInForceForTest() }, "30 ms × 1.10 → 34 ticks")
            assertTrue(n + 2 >= Shell.PERIOD_WINDOW, "not before a whole window since the change: ${n + 2} motions")
            r.assertOracle("after the period came down")
        } finally { r.close() }
    }
}
