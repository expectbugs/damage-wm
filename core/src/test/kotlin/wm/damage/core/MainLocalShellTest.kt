package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import wm.damage.core.motion.MotionProgram
import wm.damage.core.motion.ScrollEffect
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.ListStaging
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
    private class Rig(val taps: Taps, val stage: Int = 4, nRows: Int = 7, val kib256: Boolean = false, val link: Pair<Long, Long>? = null,
                      val linkLatency: Long = 0, /** C3 DM-0: 0xdff for a bit-11 build (stage 5 arms EFFECTS). */ features: Int = 0x5ff,
                      /** C3 DM-2aF: the rig's face ([FakeText] unless a pin needs one that lacks a glyph). */
                      text: wm.damage.core.text.TextRasterizer = FakeText()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-mainlocal")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features }
        private fun c1(): java.nio.file.Path = tmp.resolve("c1-stage").also { f ->
            Files.writeString(f, wm.damage.core.transport.LadderRecord(stage = stage, sessionKib = 256).encode()) }
        val t: wm.damage.core.transport.CfwTransportBase = when {
            link != null -> LinkRig(sim, scope, c1(), link.first, link.second, linkLatency)
            !kib256 -> SimTransport(sim, scope, SimTransport.Timing(instant = true))
            else -> StartRig(sim, scope, c1StageFile = c1())
        }
        val journal = tmp.resolve("journal.jsonl")
        val persistence = Persistence(tmp.resolve("state.json"))
        val shell = Shell(CachedText(text), t, persistence, journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rows = (0 until nRows).map { Row("w$it", "Win $it", DamageWindow.Summary("summary $it", detail = "detail of $it", progress = if (it == 3) 0.25 else null)) }
        /** RIGHT's top-level images, in order. */
        val right = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        @Volatile var onImage: ((ByteArray) -> Unit)? = null
        /** Every top-level image either lens is handed, before it is handled (the first arm to see one: neither has it yet,
         *  and both have every image before it). */
        @Volatile var onAnyImage: ((Arm, ByteArray) -> Unit)? = null

        init {
            for (r in rows) shell.register(r)
            if (!kib256 && link == null) t.devProbe("c1", "$stage")
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
            // build K1-4a: the notice's programs are written and counted at a stage-4 start too; a test begins after
            // them (else a program's write lands in a test's window, or a count reading writes the counters' note
            // after its first notch) — one evaluation on the loop deciding
            // C3 DW-1a: and the wheel's block placed and counted at stage 5 on 256 KiB
            until("the notice's programs counted") { onLoop { shell.noticeLaneDoneForTest() && shell.wheelLaneDoneForTest() && !shell.cacheWorkInFlight } }
        }

        /** B083-ms (decisions BP): a first session started where the stored state leaves the shell — [mode] (`SILENT`: the
         *  shell's own clock screen; `EXCLUSIVE` with [current]: that window's exclusive screen) with [up]'s settings
         *  already stored, as a phone restart finds them. */
        suspend fun upOn(mode: String, current: String? = null) {
            persistence.put("shell.settings", wm.damage.core.shell.ShellSettings().copy(heightMode = 352, cachedText = "on", taps = taps).toJson())
            persistence.put("shell.state", JsonObject(buildMap {
                put("mode", kotlinx.serialization.json.JsonPrimitive(mode))
                if (current != null) put("current", kotlinx.serialization.json.JsonPrimitive(current))
            }))
            shell.start(); settle("start on $mode")
        }

        /** The shell's power state is its own clock screen (the last `power` note). */
        fun onClock(): Boolean = notes("power").lastOrNull()?.startsWith("shell-silent") == true

        /** One flush as the journal holds it: its submit (time, label, the shell's mode) and its completion (the shell's
         *  time for it, 0 until done; the transport's `took` and `wrote`). */
        data class Flush(val id: Long, val t: Long, val label: String, val mode: String, val doneT: Long, val took: Long, val wrote: Long, val bytes: Long)

        fun flushes(): List<Flush> {
            if (!Files.exists(journal)) return emptyList()
            val lines = Files.readAllLines(journal)
            fun num(l: String, k: String) = Regex("\"$k\":(-?\\d+)").find(l)?.groupValues?.get(1)?.toLong() ?: 0L
            fun str(l: String, k: String) = Regex("\"$k\":\"([^\"]*)\"").find(l)?.groupValues?.get(1) ?: ""
            val done = lines.filter { "\"ev\":\"done\"" in it }.associateBy { num(it, "id") }
            return lines.filter { "\"ev\":\"submit\"" in it }.map { l ->
                val d = done[num(l, "id")]
                Flush(num(l, "id"), num(l, "t"), str(l, "label"), str(l, "mode"), d?.let { num(it, "t") } ?: 0L, d?.let { num(it, "took") } ?: 0L,
                    d?.let { num(it, "wrote") } ?: 0L, Regex("\"bytes\":(\\d+)").findAll(l).sumOf { it.groupValues[1].toLong() })
            }
        }

        /** Nothing pending and nothing submitted for [quietMs]. */
        suspend fun idle(what: String, quietMs: Long = 1_000, maxMs: Long = 60_000) =
            until("$what: idle for $quietMs ms", maxMs) { shell.isQuiescent() && (flushes().lastOrNull()?.t ?: 0L) < System.currentTimeMillis() - quietMs }

        fun list(): BoundList = assertNotNull(onLoop { shell.mainBoundForTest().first })
        fun row(): Int = onLoop { shell.mainBoundForTest().second }

        /** One gesture on the ring: the hook on both lenses, then its stock message through the transport (after the 115,
         *  which the hook sent synchronously). RIGHT's S after the fire. */
        fun fire(eventId: Int): Int {
            val now = System.currentTimeMillis()
            for (a in Arm.entries) sim.conformanceInput(a, CfwModes.DEV_RING, eventId.toLong(), 0, now)
            val stock = BoundList.stockEvent(eventId)!!
            // a link that takes time: the stock message rides the uplink behind the 115 the hook just sent (§6.1)
            (t as? LinkRig)?.later { t.ringStockForTest(stock) } ?: t.ringStockForTest(stock)
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

        private val TIMER_DRAWING = "the notice's timer is drawing"

        /** Belief equals both lenses' panels, read ON the loop with nothing else pending. */
        suspend fun assertOracle(what: String) {
            var diff: String? = null
            var read = false
            val deadline = System.currentTimeMillis() + 30_000
            while (!read && System.currentTimeMillis() < deadline) {
                if (!shell.isQuiescent()) { delay(5); continue }
                val r = shell.sampleIdle {
                    // build K1-4a: the notice's timer still drawing on the glasses (a fault's box: the L1 throw raises one)
                    // shows its step there and its END in belief — the panels are read once it has drawn its last step
                    if (synchronized(sim) { listOf(sim.left, sim.right).any { it.motion.slots[wm.damage.core.motion.PopupPrograms.SLOT]?.drawing == true } })
                        return@sampleIdle TIMER_DRAWING
                    val out = StringBuilder()
                    for (left in listOf(true, false)) {
                        val b = shell.comp.expectedLens(left); val g = panel(left)
                        loop@ for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W)
                            if (wm.damage.core.gfx.Pack.level(b[x, y]) * 17 != g[x, y]) { out.append("${if (left) "LEFT" else "RIGHT"} belief != glass at ($x,$y); "); break@loop }
                    }
                    out.toString()
                }
                if (r == TIMER_DRAWING) delay(5) else if (r != null) { read = true; diff = r }
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
     *  equals both lenses once the motion has ended. [steps]: the steps RIGHT is sampled at mid-motion, from the entry's
     *  program (step 2 by default; C3 DM-2b: Scramble's letters change in place, each cell at its own steps, so every
     *  step is sampled and a track's area must change at one of them — the shell rig's rows share most letters). [inPlace]
     *  (Scramble): a track's area whose picture the notch leaves as it was may show nothing mid-motion — the rig's box face
     *  draws every letter and every noise glyph as the same box, so the band's letters changing in place show nothing. */
    private suspend fun notch(r: Rig, eventId: Int, what: String, inPlace: Boolean = false, steps: (MotionProgram) -> List<Int> = { listOf(2) }) {
        r.settle("$what: before")
        val list = r.list()
        val e = assertNotNull(list.entryFor(eventId))
        val row0 = r.row()
        val before = r.panel(false)
        val at = r.right.size
        val snaps = r.notes("snapback").size
        val t0 = System.nanoTime()
        val s = r.fire(eventId)
        val ks = steps(e.program!!)
        val mids = ks.map { k ->
            val due = t0 / 1_000_000 + (e.period * k * 1000L) / 1024 + 10    // about k steps in
            delay(maxOf(0L, due - System.nanoTime() / 1_000_000))
            r.panel(false)
        }
        val diffs = e.program.tracks.map { track ->
            val a = track.areaR
            a to mids.map { mid -> var n = 0
                for (y in maxOf(0, a.y) until minOf(Geometry.PANEL_H, a.bottom)) for (x in maxOf(0, a.x) until minOf(Geometry.PANEL_W, a.right)) if (mid[x, y] != before[x, y]) n++
                n }
        }
        delay(((e.program.T + 1) * e.period * 1000L) / 1024 + r.q() + 200)   // the motion, then the burst's SETTLE
        r.settle("$what: after")
        val after = r.panel(false)
        fun same(a: Rect) = (maxOf(0, a.y) until minOf(Geometry.PANEL_H, a.bottom)).all { y -> (maxOf(0, a.x) until minOf(Geometry.PANEL_W, a.right)).all { x -> after[x, y] == before[x, y] } }
        for ((i, d) in diffs.withIndex()) assertTrue(d.second.any { it > 0 } || (inPlace && same(d.first)), "$what: the pan's track ${i + 1} at ${d.first} changed on RIGHT mid-motion " +
            "(the whole list moves): px changed at steps $ks — ${diffs.joinToString("; ") { "${it.first}: ${it.second}" }} (T ${e.program.T}, P ${e.period}); " +
            "notes ${r.notes("local").takeLast(4)}")
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
     *
     * MR-083 (`burst-gate`): not one piece of the re-stage goes while the burst is open — the first piece included (build
     * SPLIT: an instance's read ranges cover the strip and the spare band slots, §5.6). Between A's motion's end
     * and B the motion gate is open and only the burst gate holds the first piece (traced: with it removed the first
     * piece went 12 ms before B, the later pieces and the books still after the SETTLE by their own `mayGo`). The burst
     * closes no earlier than Q after B's report, so every STAGE flush since the change is submitted at tB + Q or later.
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
            val changedAt = System.currentTimeMillis()
            r.rows[2].s = r.rows[2].s.copy(detail = "detail of 2, changed")
            r.shell.services.requestRender(r.rows[2])
            delay(r.motionMs() + 30)                               // B after A's motion (AW.4), still inside its burst (Q)
            val tB = System.currentTimeMillis()
            val sB = r.fire(CfwModes.EV_UPROLL)                     // B: onto row 2, whose staged band form is the old one
            r.until("row 2 re-staged", 10_000) { r.notes("mainstage").any { it.startsWith("row 2 re-staged") } }
            // the re-stage waited for the burst's end: Q after B's report (the lone SETTLE), not between A and B — its
            // books (the last piece) and its first piece alike
            val restagedAt = assertNotNull(r.noteAt("mainstage", "row 2 re-staged"))
            assertTrue(restagedAt >= tB + r.q(), "row 2 re-staged ${restagedAt - tB} ms after B: not before the burst's SETTLE (Q ${r.q()} ms)")
            val pieces = r.flushes().filter { it.label == "STAGE" && it.t >= changedAt }
            assertTrue(pieces.isNotEmpty() && pieces.all { it.t >= tB + r.q() },
                "no piece of the re-stage while the burst is open: STAGE flushes at ${pieces.map { it.t - tB }} ms after B (Q ${r.q()} ms)")
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
                // `ListStaging`, DM2 §0.3) — the band row drawn at that depth is still Main's content
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

    /** Decisions AU.1 (b): at 256 KiB, rows fuller than two strips' room (eighteen dense rows) stage — never "cannot be
     *  staged" while one strip fits (since K1-2 stage 1 one strip at every size, decisions BC O1) — the list bound; a row
     *  form's change then withdraws and returns, as at 160 KiB. */
    @Test
    fun fullerRowsStageWithOneStripAt256KiB_AU1(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, nRows = 18, kib256 = true)
        try {
            for (w in r.rows) w.s = w.s.copy(line = "W".repeat(80), detail = "W".repeat(80))
            r.up()
            assertTrue(r.notes("mainstage").none { it.startsWith("Main's rows cannot be staged") }, "${r.notes("mainstage")}")
            assertTrue(r.notes("mainstage").any { it.startsWith("staged") && "Main's own region" in it }, "staged in Main's own region: ${r.notes("mainstage")}")
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

    /** Build ANIM (AV), K1-1: a change of the `Animations` rows makes the bound list's pans again — the pans alone, into
     *  the free slot, then SETs — once no burst is open; the list is offered again with the new motion (the same staging,
     *  no strip written), and a notch after it is COMMITted with it (its entry's T and P on RIGHT). */
    @Test
    fun anAnimationsChangeMakesThePansAgainAndANotchUsesThem_ANIM(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            val before = r.list().entries.first { it.eventId == CfwModes.EV_UPROLL }
            assertEquals(46 to 6, before.period to before.program!!.T, "the defaults at 352 (300 ms, decisions AZ.7)")
            val m = marks(r)
            r.shell.updateSettings { it.copy(scrollLength = 450, frames = 0) }
            remade(r, "450 ms", m)
            val after = r.list().entries.first { it.eventId == CfwModes.EV_UPROLL }
            assertEquals(46 to 10, after.period to after.program!!.T, "450 ms Auto at 352: T 10 at P 46")
            assertEquals(46, r.sim.bindForTest(Arm.RIGHT).entries.filterNotNull().first { it.eventId == CfwModes.EV_UPROLL }.period, "RIGHT's entry")
            notch(r, CfwModes.EV_UPROLL, "a notch with the new motion")
        } finally { r.close() }
    }

    /** K1-1: the counts of stagings, drops and programs switches so far, taken before what should re-make the pans. */
    private class Marks(val staged: Int, val dropped: Int, val switched: Int)
    private fun marks(r: Rig) = r.notes("mainstage").let { n -> Marks(n.count { it.startsWith("staged") }, n.count { it.startsWith("dropped") }, n.count { it.startsWith("pans switched") }) }

    /** K1-1: waits for the pans re-made and switched since [m], and for RIGHT to hold the entries that name them; the
     *  staging is the one there was (nothing staged again, nothing dropped since [m]). */
    private suspend fun remade(r: Rig, what: String, m: Marks) {
        val staged0 = m.staged
        val switched0 = m.switched
        r.until("$what: the pans switched", 15_000) { r.notes("mainstage").count { it.startsWith("pans switched") } > switched0 }
        r.bound("$what: bound with the re-made pans")
        r.until("$what: RIGHT holds the re-made pans") { val e = r.list().entryFor(CfwModes.EV_UPROLL)!!
            r.sim.bindForTest(Arm.RIGHT).entries.filterNotNull().firstOrNull { it.eventId == CfwModes.EV_UPROLL }?.prog == e.prog4 }
        assertEquals(staged0, r.notes("mainstage").count { it.startsWith("staged") }, "$what: no staging: ${r.notes("mainstage").takeLast(4)}")
        assertEquals(m.dropped, r.notes("mainstage").count { it.startsWith("dropped") }, "$what: no drop: ${r.notes("mainstage").takeLast(4)}")
    }

    /** The byte ranges a cache-write image writes (mode 19, or 25 deflated), each [from, to). */
    private fun writeRanges(img: ByteArray): List<IntRange> {
        val body = when (img[0].toInt() and 0x7F) {
            19 -> img.copyOfRange(1, img.size)
            25 -> java.util.zip.Inflater().run { setInput(img, 1, img.size - 1); val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
                while (!finished()) { val n = inflate(buf); if (n == 0 && (needsInput() || needsDictionary())) break; out.write(buf, 0, n) }; end(); out.toByteArray() }
            else -> return emptyList()
        }
        val out = ArrayList<IntRange>()
        var i = 0
        while (i + 4 <= body.size) {
            val off = ((body[i].toInt() and 0xFF) or ((body[i + 1].toInt() and 0xFF) shl 8)) * 4
            val len = (body[i + 2].toInt() and 0xFF) or ((body[i + 3].toInt() and 0xFF) shl 8)
            out += off until off + len; i += 4 + len
        }
        return out
    }

    /**
     * K1-1 item 4, the brief's pin "a programs-only change never writes a strip": a change of `Scroll effect` on a counted
     * staging writes only into the free pan slot (every cache write since the change lies there — no strip, band form,
     * wrap record, item table or press byte), the SETs naming the re-made pans go only after that write (counted first),
     * the staging stays the same object, and a notch then draws the new effect (four tracks) and is committed, belief
     * equal to both lenses. On both caches (160 KiB: the shared reserve, one strip; 256: Main's own region, two).
     */
    @Test
    fun aScrollRowChangeWritesThePansAloneNeverAStrip_K11(): Unit = runBlocking {
        for (big in listOf(false, true)) {
            val r = Rig(Taps.REPORT, kib256 = big)
            try {
                r.up()
                val ms = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
                val slots = assertNotNull(ms.progSlots, "a spare pan slot (${if (big) 256 else 160} KiB)")
                val free = slots[1 - ms.activeProgs] until slots[1 - ms.activeProgs] + ms.progSlot
                val at = r.right.size
                val m = marks(r)
                r.shell.updateSettings { it.copy(scrollEffect = "slide") }
                remade(r, "Slide", m)
                assertTrue(r.onLoop { r.shell.mainBinding.staging } === ms, "the same staging")
                val imgs = r.right.drop(at)
                val writes = imgs.withIndex().filter { r.cacheWrite(it.value) }
                assertTrue(writes.isNotEmpty(), "the re-made pans were written")
                for (w in writes) for (rg in writeRanges(w.value)) assertTrue(rg.first >= free.first && rg.last <= free.last,
                    "a cache write at ${rg.first}..${rg.last + 1} outside the free pan slot ${free.first}..${free.last + 1}")
                val prog = r.list().entryFor(CfwModes.EV_UPROLL)!!.prog4
                val setAt = imgs.indexOfFirst { img -> r.subs(img).any { r.isSub(it, 27, 0) && r.u16(it, 9) == prog } }
                assertTrue(setAt > writes.last().index, "the SETs naming the re-made pans after their write (set at $setAt, write at ${writes.last().index})")
                // ... and only once the write was COUNTED (field 17): the stage's "proved" note comes between the write's
                // submit and the switch that makes the list naming the re-made pans (the SETs' source)
                val ns = r.allNotes()
                val sub = ns.indexOfFirst { (k, d) -> k == "stage" && "Main's pans in slot" in d && "submitted" in d }
                val sw = ns.indexOfFirst { (k, d) -> k == "mainstage" && " pans switched" in d }
                val proved = ns.withIndex().indexOfFirst { (i, n) -> i > sub && n.first == "stage" && " proved " in n.second }
                assertTrue(sub >= 0 && proved in (sub + 1) until sw, "the re-made pans were counted before the switch (submit $sub, proved $proved, switched $sw): ${ns.drop(maxOf(0, sub)).take(8)}")
                println("K11-row-change ${if (big) 256 else 160} KiB: ${writes.sumOf { it.value.size }} B of cache writes in ${writes.size} flush(es), " +
                    "SETs ${r.subs(imgs[setAt]).filter { r.isSub(it, 27, 0) }.sumOf { it.size }} B; the staging is ${ms.bytes} B")
                assertEquals(4, r.list().entryFor(CfwModes.EV_UPROLL)!!.program!!.tracks.size, "Slide's pans")
                notch(r, CfwModes.EV_UPROLL, "a notch with Slide")
                r.assertOracle("after Slide")
            } finally { r.close() }
        }
    }

    /**
     * C3 DM-2a, the records rule on the shell (decisions DW 2; the DM-2 spec §8): Depth → Matrix → Depth on Main at stage 5
     * on 256 KiB — each change a full staging (the staging replaced, one drop and one staging more, no pans switched, a new
     * mark), never pans re-made beside records that are not there or left behind; Matrix's staging holds its records once,
     * last, and a notch plays its five tracks and is committed, belief equal to both lenses; Depth's staging after it is
     * the first one byte for byte.
     */
    @Test
    fun aChangeIntoOrOutOfMatrixIsAFullStagingEachWay_DM2a(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            val d1 = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
            suspend fun change(what: String, to: String, from: ListStaging): ListStaging {
                val m = marks(r)
                val mark0 = r.list().mark
                r.shell.updateSettings { it.copy(scrollEffect = to) }
                r.until("$what: staged again", 30_000) { r.onLoop { r.shell.mainBinding.staging }.let { it != null && it !== from } }
                r.bound("$what: bound")
                val ms = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
                val m1 = marks(r)
                assertEquals(Triple(m.staged + 1, m.dropped + 1, m.switched), Triple(m1.staged, m1.dropped, m1.switched), "$what: one drop and one staging, no pans switched")
                assertTrue(r.list().mark != mark0, "$what: a new mark")
                assertTrue(r.notes("mainstage").any { it.startsWith("dropped") && "Matrix's records are staging records" in it }, "$what: the drop says why: ${r.notes("mainstage").takeLast(3)}")
                return ms
            }
            val mx = change("Depth → Matrix", "matrix", d1)
            assertTrue(mx.recordEffect == wm.damage.core.motion.ScrollEffect.MATRIX && mx.plays.effect == wm.damage.core.motion.ScrollEffect.MATRIX, "Matrix plays, its records staged: ${mx.plays}")
            assertEquals(1, mx.blobs.count { it.name == "Matrix's records" }, "the records once")
            assertEquals("Matrix's records", mx.blobs.last().name, "the records last")
            assertEquals(5, r.list().entryFor(CfwModes.EV_UPROLL)!!.program!!.tracks.size, "Matrix's pans")
            notch(r, CfwModes.EV_UPROLL, "a notch with Matrix")
            r.assertOracle("after Matrix")
            val d2 = change("Matrix → Depth", "depth", mx)
            assertNull(d2.recordEffect, "no records after Matrix")
            assertEquals(d1.blobs.map { it.name to it.off }, d2.blobs.map { it.name to it.off }, "Depth's staging as the first: its records' places")
            assertTrue(d1.blobs.zip(d2.blobs).all { (a, b) -> a.bytes.contentEquals(b.bytes) }, "Depth's staging as the first, byte for byte")
        } finally { r.close() }
    }

    /**
     * C3 DM-2b, the records rule on the shell for Scramble (decisions DW 2, EP 2; the DM-2 spec §8): Depth → Scramble →
     * Depth on Main at stage 5 on 256 KiB, Main drawn through its atlas — each change a full staging (one drop and one
     * staging more, no pans switched, a new mark, the drop saying why); Scramble's staging holds its records once, last, its
     * cells made from the runs the staging's paint recorded, and a notch plays its seven tracks and is committed, belief
     * equal to both lenses; Depth's staging after it is the first one byte for byte.
     */
    @Test
    fun aChangeIntoOrOutOfScrambleIsAFullStagingEachWay_DM2b(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            val d1 = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
            suspend fun change(what: String, to: String, from: ListStaging): ListStaging {
                val m = marks(r)
                val mark0 = r.list().mark
                r.shell.updateSettings { it.copy(scrollEffect = to) }
                r.until("$what: staged again", 30_000) { r.onLoop { r.shell.mainBinding.staging }.let { it != null && it !== from } }
                r.bound("$what: bound")
                val ms = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
                val m1 = marks(r)
                assertEquals(Triple(m.staged + 1, m.dropped + 1, m.switched), Triple(m1.staged, m1.dropped, m1.switched), "$what: one drop and one staging, no pans switched")
                assertTrue(r.list().mark != mark0, "$what: a new mark")
                assertTrue(r.notes("mainstage").any { it.startsWith("dropped") && "Scramble's records are staging records" in it }, "$what: the drop says why: ${r.notes("mainstage").takeLast(3)}")
                return ms
            }
            val sc = change("Depth → Scramble", "scramble", d1)
            assertTrue(sc.recordEffect == wm.damage.core.motion.ScrollEffect.SCRAMBLE && sc.plays.effect == wm.damage.core.motion.ScrollEffect.SCRAMBLE, "Scramble plays, its records staged: ${sc.plays}")
            assertEquals(1, sc.blobs.count { it.name == "Scramble's records" }, "the records once")
            assertEquals("Scramble's records", sc.blobs.last().name, "the records last")
            assertEquals(7, r.list().entryFor(CfwModes.EV_UPROLL)!!.program!!.tracks.size, "Scramble's pans")
            notch(r, CfwModes.EV_UPROLL, "a notch with Scramble", inPlace = true) { p -> (1..p.T).toList() }
            r.assertOracle("after Scramble")
            val d2 = change("Scramble → Depth", "depth", sc)
            assertNull(d2.recordEffect, "no records after Scramble")
            assertEquals(d1.blobs.map { it.name to it.off }, d2.blobs.map { it.name to it.off }, "Depth's staging as the first: its records' places")
            assertTrue(d1.blobs.zip(d2.blobs).all { (a, b) -> a.bytes.contentEquals(b.bytes) }, "Depth's staging as the first, byte for byte")
        } finally { r.close() }
    }

    /**
     * C3 DM-2bF (decisions EZ 2; `C3-DM2b-V1-a.md` §2, its probe A): Scramble at 450 ms (stage 5, 256 KiB) on a glass whose
     * steps take 83.5 ms — about 2.6 times the fit — so the period in force rises and Auto asks fewer steps than the noise
     * needs (T' − N < 1). The re-make is floored at N + 1: Scramble's own pans re-made and switched, its records kept, the
     * staging the same object (no drop, no staging), the period note and the re-make's note saying the floor and the
     * notch's length against the 450 ms asked; a notch after it plays the new pans on both lenses, the oracle holding.
     * Before the cure the re-make was refused, unsaid, and laid out again at every idle pump (93 times in 5 s, measured in
     * this rig on the PC) while T stayed at the raised period.
     */
    @Test
    fun aSlowGlassFloorsScramblesAutoStepsAtItsNoisePlusOne_DM2bF(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            val d0 = r.onLoop { r.shell.mainBinding.staging }
            r.shell.updateSettings { it.copy(scrollEffect = "scramble", scrollLength = 450) }
            r.until("Scramble staged at 450 ms") { r.onLoop { r.shell.mainBinding.staging }.let { it != null && it !== d0 &&
                it.recordEffect == wm.damage.core.motion.ScrollEffect.SCRAMBLE && it.motion?.lengthMs == 450 } }
            r.bound("Scramble bound"); r.settle("staged")
            val ms = r.onLoop { r.shell.mainBinding.staging!! }
            val t0 = ms.t; val p0 = ms.period
            val n = maxOf(1, t0 / 3)
            val m = marks(r)
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 83_500L
            r.fire(CfwModes.EV_UPROLL); delay(r.motionMs() + r.q() + 300)
            r.until("Scramble's pans re-made at N + 1 and switched") { r.onLoop { r.shell.mainBinding.staging?.t } == n + 1 && marks(r).switched > m.switched }
            r.settle("after the re-make")
            val notes = r.notes("mainstage")
            val pif = r.onLoop { r.shell.periodInForceForTest() }
            println("DM2bF floor (the PC rig, modeled): staged P $p0 T $t0 N $n; at 83.5 ms a step the period in force $pif, T ${ms.t}: " +
                "${notes.filter { it.startsWith("period ") || it.startsWith("pans re-made") }.takeLast(2).map { it.take(260) }}")
            val m2 = marks(r)
            assertTrue(r.onLoop { r.shell.mainBinding.staging } === ms && m2.staged == m.staged && m2.dropped == m.dropped, "the same staging: no drop, no staging")
            assertTrue(ms.plays.effect == wm.damage.core.motion.ScrollEffect.SCRAMBLE && ms.recordEffect == wm.damage.core.motion.ScrollEffect.SCRAMBLE, "Scramble's own pans, its records kept: ${ms.plays}")
            assertTrue(notes.any { it.startsWith("period ") && "floored at Scramble's noise + 1 = ${n + 1} steps" in it }, "the period note says the floor: ${notes.filter { it.startsWith("period") }}")
            assertTrue(notes.any { it.startsWith("pans re-made — the period in force") && "floored at Scramble's noise + 1 = ${n + 1} steps" in it && "against the 450 ms asked" in it },
                "the re-make's note says the floor and the notch's length: ${notes.filter { it.startsWith("pans re-made") }}")
            val pan = r.onLoop { r.shell.mainBinding.staging!!.pan(ListStaging.WHOLE, 1) }
            r.fire(CfwModes.EV_UPROLL)
            for (a in Arm.entries) r.until("$a plays the re-made pan") { synchronized(r.sim) { r.sim.motionForTest(a).slots[ListStaging.SLOT]?.prog4 } == pan.prog4 }
            delay(r.motionMs() + r.q() + 300); r.settle("after a notch at N + 1")
            r.assertOracle("after a notch at N + 1")
        } finally { r.close() }
    }

    /** C3 DM-2bG: Scramble staged at 450 ms Auto (stage 5, 256 KiB) with Main in front, bound and settled; its staging. */
    private suspend fun scrambleStagedAt450(r: Rig): ListStaging {
        r.up()
        val d0 = r.onLoop { r.shell.mainBinding.staging }
        r.shell.updateSettings { it.copy(scrollEffect = "scramble", scrollLength = 450) }
        r.until("Scramble staged at 450 ms") { r.onLoop { r.shell.mainBinding.staging }.let { it != null && it !== d0 &&
            it.recordEffect == ScrollEffect.SCRAMBLE && it.motion?.lengthMs == 450 } }
        r.bound("Scramble bound"); r.settle("staged")
        return r.onLoop { r.shell.mainBinding.staging!! }
    }

    /**
     * C3 DM-2bG (decisions FH, FI; `C3-DM2b-V2-a.md` finding 1): Scramble's noise follows the fit. With Main in front, his
     * `Scroll length` change from 450 to 300 ms under Scramble's records gives another noise (in this rig, modeled: T 14,
     * N 4 → T 9, N 3), so it is staged again whole: exactly one drop, said, and one full staging; afterwards the pans' T
     * and the staged noise satisfy N = max(1, ⌊T/3⌋). Before the cure the pans alone were re-made over the old records (T 9
     * over N 4: the same settings showed two looks until the next full staging).
     */
    @Test
    fun hisLengthChangeThatChangesScramblesNoiseStagesItAgainWhole_DM2bG(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            val ms = scrambleStagedAt450(r)
            val n = assertNotNull(ms.scrambleNoise, "Scramble's records hold a noise")
            val m = marks(r)
            r.shell.updateSettings { it.copy(scrollLength = 300) }
            r.until("Main's pans asked for 300 ms") { r.onLoop { r.shell.mainBinding.staging }?.motion?.lengthMs == 300 }
            r.bound("bound at 300 ms"); r.settle("at 300 ms")
            val ms2 = r.onLoop { r.shell.mainBinding.staging!! }
            val m2 = marks(r)
            val notes = r.notes("mainstage")
            println("DM2bG length 450 → 300 (the PC rig, modeled): staged T ${ms.t} N $n; after it T ${ms2.t} N ${ms2.scrambleNoise}, " +
                "${m2.staged - m.staged} staging(s), ${m2.dropped - m.dropped} drop(s), ${m2.switched - m.switched} switch(es); " +
                "${notes.filter { it.startsWith("dropped") || it.startsWith("pans re-made") }.takeLast(2).map { it.take(240) }}")
            assertEquals(1 to 1, (m2.staged - m.staged) to (m2.dropped - m.dropped), "his length change that changes the noise: one drop and one full staging")
            assertTrue(notes.any { it.startsWith("dropped") && "Scramble's noise" in it }, "the drop says the noise: ${notes.filter { it.startsWith("dropped") }.takeLast(2)}")
            assertTrue(ms2 !== ms && ms2.recordEffect == ScrollEffect.SCRAMBLE && ms2.plays.effect == ScrollEffect.SCRAMBLE, "Scramble staged again with its records: ${ms2.plays}")
            val n2 = assertNotNull(ms2.scrambleNoise, "the new staging's records hold a noise")
            assertEquals(maxOf(1, ms2.t / 3), n2, "the pans' T ${ms2.t} and the staged noise: N = max(1, ⌊T/3⌋)")
            assertTrue(n2 != n, "precondition: the fit gives 300 ms another noise than 450 ms ($n → $n2)")
            r.assertOracle("at 300 ms")
        } finally { r.close() }
    }

    /**
     * C3 DM-2bG (decisions FI; `C3-DM2b-V2-f.md` F1, its probe 2's shape): `Scroll frames` stepped under the Settings cover
     * with Scramble's records staged at 450 ms Auto (N 4 or more here). Auto → 4 → 6: neither count keeps the noise, so
     * the staging stays under the cover — no drop, no staging — and his return stages once, for the 6 he left the row on.
     * Auto → 4 → Auto: no drop and no staging at all. Before the cure 4 dropped the staging at once, under the cover.
     */
    @Test
    fun framesThroughFourOnToSixUnderACoverStagesOnceOnHisReturn_DM2bG(): Unit = runBlocking { framesUnderACover(listOf(4, 6)) }

    @Test
    fun framesThroughFourBackToAutoUnderACoverStagesNothing_DM2bG(): Unit = runBlocking { framesUnderACover(listOf(4, 0)) }

    private suspend fun framesUnderACover(steps: List<Int>) {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            val d1 = scrambleStagedAt450(r)
            val n = assertNotNull(d1.scrambleNoise, "Scramble's records hold a noise")
            assertTrue(n >= 3, "precondition: the staged noise $n (T ${d1.t}) is one that 4 and 6 frames (N 1 and 2) change")
            r.onLoop { r.shell.services.openWindow("settings", null) }
            r.until("Settings open") { r.onLoop { r.shell.currentWindowId() } == "settings" }
            r.until("Settings' transition landed") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("in Settings")
            val m = marks(r)
            for (f in steps) { r.shell.updateSettings { it.copy(frames = f) }; delay(300) }
            delay(2_000)
            r.settle("after the steps")
            val m1 = marks(r)
            val what = "frames Auto → ${steps.joinToString(" → ") { if (it == 0) "Auto" else "$it" }}"
            println("DM2bG $what under the cover (the PC rig, modeled): staged T ${d1.t} N $n; ${m1.staged - m.staged} staging(s), " +
                "${m1.dropped - m.dropped} drop(s); ${r.notes("mainstage").takeLast(2).map { it.take(240) }}")
            assertEquals(0 to 0, (m1.staged - m.staged) to (m1.dropped - m.dropped), "$what under the cover: no drop and no staging")
            assertTrue(r.onLoop { r.shell.mainBinding.staging } === d1, "$what: the staging stays under the cover")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            if (steps.last() == 0) {
                r.bound("bound again"); r.settle("back on Main"); delay(2_000); r.settle("back on Main, after 2 s")
                val m2 = marks(r)
                println("DM2bG $what after the return: ${m2.staged - m1.staged} staging(s), ${m2.dropped - m1.dropped} drop(s), ${m2.switched - m1.switched} switch(es)")
                assertEquals(0 to 0, (m2.staged - m1.staged) to (m2.dropped - m1.dropped), "$what: back on its own motion, nothing at his return")
                assertTrue(r.onLoop { r.shell.mainBinding.staging } === d1, "$what: the same staging")
            } else {
                r.until("Main's pans asked for ${steps.last()} frames", 60_000) { r.onLoop { r.shell.mainBinding.staging }?.motion?.frames == steps.last() }
                r.bound("bound again"); r.settle("back on Main")
                val m2 = marks(r)
                val ms = r.onLoop { r.shell.mainBinding.staging!! }
                println("DM2bG $what after the return: ${m2.staged - m1.staged} staging(s), ${m2.dropped - m1.dropped} drop(s), ${m2.switched - m1.switched} switch(es); " +
                    "T ${ms.t} N ${ms.scrambleNoise}, plays ${ms.plays.effect.word}")
                assertEquals(1 to 1, (m2.staged - m1.staged) to (m2.dropped - m1.dropped), "$what: one full staging on his return")
                assertTrue(ms !== d1 && ms.recordEffect == ScrollEffect.SCRAMBLE && ms.plays.effect == ScrollEffect.SCRAMBLE && ms.t == steps.last(),
                    "$what: Scramble staged for the ${steps.last()} he left the row on: T ${ms.t}, ${ms.plays}")
                assertEquals(maxOf(1, ms.t / 3), ms.scrambleNoise, "$what: the pans' T ${ms.t} and the staged noise: N = max(1, ⌊T/3⌋)")
            }
            r.assertOracle("after his return")
        } finally { r.close() }
    }

    /**
     * C3 DM-2bG (decisions FH; the control of the two pins above): a change of his that keeps Scramble's noise — `Scroll
     * frames` Auto → the count F of the row with max(1, ⌊F/3⌋) = the staged N (12 for N 4 in this rig) — is a programs-only
     * re-make with no staging: no drop, Scramble's own pans at T F switched over the same records.
     */
    @Test
    fun hisChangeThatKeepsScramblesNoiseReMakesThePansAlone_DM2bG(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            val ms = scrambleStagedAt450(r)
            val n = assertNotNull(ms.scrambleNoise, "Scramble's records hold a noise")
            val f = assertNotNull(listOf(4, 6, 8, 10, 12, 16).firstOrNull { maxOf(1, it / 3) == n && it != ms.t },
                "precondition: a count of the Frames row keeps the staged noise $n (T ${ms.t})")
            val m = marks(r)
            r.shell.updateSettings { it.copy(frames = f) }
            r.until("Main's pans asked for $f frames") { r.onLoop { r.shell.mainBinding.staging }?.motion?.frames == f }
            r.bound("bound at $f frames"); r.settle("at $f frames")
            val m2 = marks(r)
            val notes = r.notes("mainstage")
            println("DM2bG frames Auto → $f (the PC rig, modeled): staged T ${ms.t} N $n; ${m2.staged - m.staged} staging(s), ${m2.dropped - m.dropped} drop(s), " +
                "${m2.switched - m.switched} switch(es); ${notes.filter { it.startsWith("pans re-made") }.takeLast(1).map { it.take(200) }}")
            assertEquals(0 to 0, (m2.staged - m.staged) to (m2.dropped - m.dropped), "a change that keeps the noise: no drop and no staging")
            assertTrue(r.onLoop { r.shell.mainBinding.staging } === ms && ms.t == f && ms.scrambleNoise == n &&
                ms.plays.effect == ScrollEffect.SCRAMBLE && ms.recordEffect == ScrollEffect.SCRAMBLE, "the same staging, Scramble's own pans at T $f over its records: T ${ms.t}, ${ms.plays}")
            assertTrue(notes.any { it.startsWith("pans re-made — the motion changed") }, "a programs-only re-make, said")
            r.assertOracle("at $f frames")
        } finally { r.close() }
    }

    /** K1-1, the brief's pin "no change in a burst": a change of the scroll rows inside a burst writes nothing and SETs
     *  nothing until the burst's lone SETTLE has gone; then the pans are re-made. */
    @Test
    fun aScrollRowChangeInABurstWaitsForItsSettle_K11(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            var settleAt = -1
            var firstWrite = -1
            var firstSet = -1
            val prog0 = r.list().entryFor(CfwModes.EV_UPROLL)!!.prog4
            val n0 = r.right.size
            r.onImage = { img ->
                val i = r.right.size - 1
                if (settleAt < 0 && r.subs(img).any { r.isSub(it, 26, 5) }) settleAt = i
                if (firstWrite < 0 && r.cacheWrite(img)) firstWrite = i
                if (firstSet < 0 && r.subs(img).any { r.isSub(it, 27, 0) && r.u16(it, 9) != prog0 && it[4].toInt() == CfwModes.EV_UPROLL }) firstSet = i
            }
            val m = marks(r)
            r.fire(CfwModes.EV_UPROLL)
            r.until("the burst opens") { r.onLoop { r.shell.localBurstOpenForTest() } }
            r.shell.updateSettings { it.copy(scrollEffect = "fall") }
            assertTrue(r.onLoop { r.shell.localBurstOpenForTest() }, "the change reached the loop while the burst was open")
            remade(r, "Fall", m)
            r.onImage = null
            assertTrue(settleAt >= n0, "the burst's SETTLE went")
            assertTrue(firstWrite > settleAt, "the re-make's write after the SETTLE (write $firstWrite, settle $settleAt)")
            assertTrue(firstSet > settleAt, "the SETs after the SETTLE (set $firstSet, settle $settleAt)")
            r.assertOracle("after Fall")
        } finally { r.close() }
    }

    /** K1-1's fix stage (the review's finding 1): a change of `Scroll effect` made with Settings in front is re-made while
     *  Main is covered (the spare slot is read by nothing), so back on Main the FIRST notch plays the new effect's
     *  program on both lenses — the return batch SETs the new pans directly. */
    @Test
    fun aChangeMadeInSettingsPlaysOnTheFirstNotchBackOnMain_K11(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            val m = marks(r)
            r.onLoop { r.shell.services.openWindow("settings", null) }
            r.until("Settings open") { r.onLoop { r.shell.currentWindowId() } == "settings" }
            r.shell.updateSettings { it.copy(scrollEffect = "slide") }
            // a while in Settings, as he would spend there (not asserted here: the notch below says it) — C3 X3: until the
            // re-made pans are counted (switched there before X3, on the return since)
            val until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until && r.notes("mainstage").count { it.startsWith("pans switched") } == m.switched &&
                r.onLoop { r.shell.mainBinding.reprogram?.record?.state } != wm.damage.core.shell.StagedRecord.State.PROVEN) delay(20)
            r.settle("in Settings")
            assertEquals("settings", r.onLoop { r.shell.currentWindowId() }, "still in Settings")
            val at = r.right.size
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back on Main and its list bound on RIGHT") { r.onLoop { r.shell.currentWindowId() } == null && r.sim.bindForTest(Arm.RIGHT).field39() == r.bound39 }
            // C3 X3 (decisions CA (2)): the re-made pans are switched by the return's poll (none is switched under a cover),
            // so Slide's pan is the staging's from the return on
            val slide = r.onLoop { r.shell.mainBinding.staging!!.let { ms -> ms.motion?.effect to ms.pan(ListStaging.WHOLE, 1).prog4 } }
            r.fire(CfwModes.EV_UPROLL)                                      // the first notch, at once
            for (a in Arm.entries) r.until("$a plays the first notch") { r.sim.motionForTest(a).slots[ListStaging.SLOT] != null }
            for (a in Arm.entries) assertEquals(wm.damage.core.motion.ScrollEffect.SLIDE to slide.second, slide.first to r.sim.motionForTest(a).slots[ListStaging.SLOT]!!.prog4,
                "$a: the first notch back on Main plays Slide's pan")
            val sets = r.right.drop(at).flatMap { r.subs(it) }.filter { r.isSub(it, 27, 0) && it[4].toInt() == CfwModes.EV_UPROLL }
            assertTrue(sets.isNotEmpty() && sets.all { r.u16(it, 9) == slide.second }, "the return SETs Slide's pans directly: ${sets.map { r.u16(it, 9) }}")
            delay(r.motionMs() + r.q() + 300); r.settle("after the notch")
            assertEquals(m.staged, r.notes("mainstage").count { it.startsWith("staged") }, "no staging")
            r.assertOracle("after the first notch")
        } finally { r.close() }
    }

    /**
     * C3 X3 (decisions CA (2), `C3-V2DM1-review.md` finding 2): `Scroll effect` stepped through several values with Main
     * covered by Settings (the row adjusts live per notch) — Depth → Slide → Crush → Flip → Crush at stage 5 (256 KiB, a
     * ring list at 352), [gapMs] between the steps (each re-make written and counted before the next on the instant rig)
     * or none; then back on Main, the return's transition landed, a notch:
     * - ONE flush since the first step carries scroll SETs — the return's — with the list's LIST-RING at Crush's pitch (+rowH)
     *   ahead of them: nothing was switched under the cover, and nothing after the return;
     * - those SETs name the pans in force, Crush's (version 2, five tracks), whose records both lenses hold byte for byte;
     * - the notch plays that pan on both lenses, moves one row, is COMMITted with no snap-back; END Main's paint (belief
     *   equals both lenses);
     * - the journal names ONE switch since the first step, to Crush; the steps between were dropped unswitched.
     * [steps] of one ("crush") is the one-step path, which passed before this fix too.
     */
    private suspend fun stepsUnderACover(steps: List<String>, gapMs: Long) {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            val rowH = r.onLoop { r.shell.mainBinding.staging!!.layout.rowH }
            assertTrue(r.list().ring && r.list().pitch == -rowH, "Depth at stage 5: a ring list at pitch −rowH")
            r.onLoop { r.shell.services.openWindow("settings", null) }
            r.until("Settings open") { r.onLoop { r.shell.currentWindowId() } == "settings" }
            r.until("Settings' transition landed") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("in Settings")
            val n0 = r.allNotes().size
            val at = r.right.size
            for (e in steps) { r.shell.updateSettings { it.copy(scrollEffect = e) }; if (gapMs > 0) delay(gapMs) }
            delay(2_000)                                                   // a while in Settings, as he would spend there
            r.settle("after the steps")
            assertEquals("settings", r.onLoop { r.shell.currentWindowId() }, "still in Settings")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back on Main and its list bound on RIGHT") { r.onLoop { r.shell.currentWindowId() } == null && r.sim.bindForTest(Arm.RIGHT).field39() == r.bound39 }
            r.until("the return's transition landed") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("back on Main")
            val (ms, pan) = r.onLoop { r.shell.mainBinding.staging!!.let { it to it.pan(ListStaging.WHOLE, 1) } }
            assertEquals(wm.damage.core.motion.ScrollEffect.CRUSH, ms.motion?.effect, "the pans in force are asked for Crush")
            assertTrue(ms.plays.effect == wm.damage.core.motion.ScrollEffect.CRUSH && ms.plays.v2 && ms.pitch == rowH && pan.program.tracks.size == 5,
                "Crush plays (version 2, pitch +rowH, five tracks): ${ms.plays.effect} v2=${ms.plays.v2} pitch ${ms.pitch} ${pan.program.tracks.size} tracks")
            fun isList(sub: ByteArray) = r.isSub(sub, 27, CfwModes.BIND_LIST_RING) || r.isSub(sub, 27, 2)
            val sets = r.right.drop(at).map { r.subs(it) }.filter { ss -> ss.any { r.isSub(it, 27, 0) && it[4].toInt() == CfwModes.EV_UPROLL && it[5].toInt() == 0 } }
            assertEquals(1, sets.size, "ONE flush since the first step carries scroll SETs (the return's), not ${sets.size}: " +
                "${sets.map { ss -> ss.filter { r.isSub(it, 27, 0) && it[4].toInt() == CfwModes.EV_UPROLL && it[5].toInt() == 0 }.map { r.u16(it, 9) } }}")
            val ss = sets.single()
            val li = ss.indexOfFirst { isList(it) }
            assertTrue(li >= 0 && r.isSub(ss[li], 27, CfwModes.BIND_LIST_RING) && r.u16(ss[li], 9).toShort().toInt() == rowH,
                "the return carries the list's LIST-RING at Crush's pitch +$rowH: ${if (li >= 0) r.u16(ss[li], 9).toShort().toInt() else "none"}")
            val up = ss.withIndex().first { (_, x) -> r.isSub(x, 27, 0) && x[4].toInt() == CfwModes.EV_UPROLL && x[5].toInt() == 0 }
            assertTrue(up.index > li && r.u16(up.value, 9) == pan.prog4, "the return's SET names Crush's pan ${pan.prog4} after the LIST-RING: ${r.u16(up.value, 9)}")
            for (a in Arm.entries) {
                assertEquals(pan.prog4, r.sim.bindForTest(a).entries.filterNotNull().first { it.eventId == CfwModes.EV_UPROLL }.prog, "$a holds the SET naming Crush's pan")
                assertTrue(r.sim.bindForTest(a).lists[0].let { it.pitch == rowH && it.ring }, "$a's list at Crush's pitch, ring")
                for ((off4, bytes) in pan.program.records) {
                    val held = synchronized(r.sim) { (if (a == Arm.LEFT) r.sim.left else r.sim.right).textureCache!!.copyOfRange(off4 * 4, off4 * 4 + bytes.size) }
                    assertTrue(held.contentEquals(bytes), "$a holds Crush's record at off4 $off4 byte for byte")
                }
            }
            val row0 = r.row()
            val snaps = r.notes("snapback").size
            val at2 = r.right.size
            val s = r.fire(CfwModes.EV_UPROLL)
            for (a in Arm.entries) r.until("$a plays the notch") { synchronized(r.sim) { r.sim.motionForTest(a).slots[ListStaging.SLOT]?.prog4 } == pan.prog4 }
            delay(r.motionMs() + r.q() + 300); r.settle("after the notch")
            assertEquals((row0 + 1).mod(r.list().count), r.row(), "the notch moved one row")
            assertTrue(r.right.drop(at2).any { img -> r.subs(img).any { r.isCommit(it, s) } }, "a COMMIT of S $s")
            assertEquals(snaps, r.notes("snapback").size, "no snap-back: ${r.notes("snapback").takeLast(2)}")
            r.assertOracle("after the notch")
            val ns = r.allNotes().drop(n0).filter { it.first == "mainstage" }.map { it.second.substringAfter(' ') }
            val switched = ns.filter { it.startsWith("pans switched") }
            assertTrue(switched.size == 1 && switched.single().startsWith("pans switched — Crush "), "ONE switch since the first step, to Crush: $switched")
            println("X3 ${steps.joinToString("→")} gap $gapMs: ${ns.filter { it.startsWith("pans") || it.startsWith("re-made") }.map { it.take(70) }}")
        } finally { r.close() }
    }

    @Test fun stepsUnderACoverSwitchOnceToTheLastOnTheReturn_X3(): Unit = runBlocking { stepsUnderACover(listOf("slide", "crush", "flip", "crush"), 300) }
    @Test fun stepsUnderACoverAtOnceSwitchOnceToTheLastOnTheReturn_X3(): Unit = runBlocking { stepsUnderACover(listOf("slide", "crush", "flip", "crush"), 0) }
    @Test fun oneStepUnderACoverStillSwitchesOnTheReturn_X3(): Unit = runBlocking { stepsUnderACover(listOf("crush"), 300) }

    /**
     * C3 DM-2aF (decisions EV 2; `C3-DM2a-V1-a.md` F2, `C3-DM2a-V1-b.md` finding 1 — its probe's shape): `Scroll effect` set
     * through Matrix with Main covered by Settings (stage 5, 256 KiB, [gapMs] between the steps). Matrix's records rule
     * (a change into or out of a records effect is a full staging, decisions DV 2) is left standing under the cover and
     * acted on with Main in front:
     * - under the cover, whatever the steps: no drop and no staging — Main's staging is the one there was;
     * - through Matrix on to another effect (the last step not "matrix"): back on Main no staging, the pans in force are
     *   the last effect's (the programs-only re-make, as before DM-2a), the return's SETs name them, and the first notch
     *   plays them on both lenses, COMMITted, the oracle holding;
     * - Matrix kept: back on Main one drop (said: Matrix's records) and ONE full staging, Matrix's records in it; no SET
     *   since the return names the old pans, and no notch on the glasses plays them — a notch at once is drawn as today
     *   (the list is not bound until Matrix's staging is counted); once bound, a notch plays Matrix's pan on both lenses.
     */
    private suspend fun throughMatrixUnderACover(steps: List<String>, gapMs: Long = 300) {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            val d0 = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
            val old = d0.pans.map { it.prog4 }.toSet()
            r.onLoop { r.shell.services.openWindow("settings", null) }
            r.until("Settings open") { r.onLoop { r.shell.currentWindowId() } == "settings" }
            r.until("Settings' transition landed") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("in Settings")
            val m = marks(r)
            for (e in steps) { r.shell.updateSettings { it.copy(scrollEffect = e) }; delay(gapMs) }
            delay(2_000)                                                   // a while in Settings, as he would spend there
            r.settle("after the steps")
            assertEquals("settings", r.onLoop { r.shell.currentWindowId() }, "still in Settings")
            val m1 = marks(r)
            val what = steps.joinToString("→")
            assertEquals(m.staged to m.dropped, m1.staged to m1.dropped, "$what under the cover: no staging and no drop: ${r.notes("mainstage").takeLast(4)}")
            assertTrue(r.onLoop { r.shell.mainBinding.staging } === d0, "$what under the cover: Main's staging is the one there was")
            // each scroll SET from here on, with the program record RIGHT holds at the off4 it names as the SET goes (before
            // the lens handles it): its version and track count — Matrix's staging lays its pans where Depth's lay, so the
            // bytes, not the offset, say whose pans a SET names
            val named = java.util.concurrent.CopyOnWriteArrayList<String>()
            r.onImage = { img -> for (sub in r.subs(img)) if (r.isSub(sub, 27, 0) && sub[4].toInt() == CfwModes.EV_UPROLL && sub[5].toInt() == 0) {
                val o = r.u16(sub, 9) * 4
                val head = synchronized(r.sim) { r.sim.right.textureCache?.copyOfRange(o, o + 2) }
                named += if (head == null) "no cache" else "v${head[0]} ${head[1]} tracks" } }
            val at = r.right.size
            val s0 = r.sim.bindForTest(Arm.RIGHT).seq
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
            fun setsSince(): List<Int> = r.right.drop(at).flatMap { r.subs(it) }.filter { r.isSub(it, 27, 0) && it[4].toInt() == CfwModes.EV_UPROLL && it[5].toInt() == 0 }.map { r.u16(it, 9) }
            if (steps.last() != "matrix") {
                r.until("back on Main and its list bound on RIGHT") { r.sim.bindForTest(Arm.RIGHT).field39() == r.bound39 }
                r.until("the return's transition landed") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
                r.settle("back on Main")
                val (ms, pan) = r.onLoop { r.shell.mainBinding.staging!!.let { it to it.pan(ListStaging.WHOLE, 1) } }
                assertTrue(ms === d0, "$what: no staging on the return")
                assertEquals(steps.last(), ms.motion?.effect?.id, "$what: the pans in force are asked for ${steps.last()}")
                val m2 = marks(r)
                assertEquals(Triple(m.staged, m.dropped, m.switched + 1), Triple(m2.staged, m2.dropped, m2.switched), "$what: one switch, no staging, no drop")
                assertTrue(setsSince().isNotEmpty() && setsSince().all { it == pan.prog4 }, "$what: the return's SETs name ${steps.last()}'s pan ${pan.prog4}: ${setsSince()}")
                val s = r.fire(CfwModes.EV_UPROLL)
                for (a in Arm.entries) r.until("$a plays the notch") { synchronized(r.sim) { r.sim.motionForTest(a).slots[ListStaging.SLOT]?.prog4 } == pan.prog4 }
                delay(r.motionMs() + r.q() + 300); r.settle("after the notch")
                assertTrue(r.right.drop(at).any { img -> r.subs(img).any { r.isCommit(it, s) } }, "$what: a COMMIT of S $s")
                r.assertOracle("$what: after the notch")
            } else {
                // a notch at once, the return's transition still in hand: a lens that matched it (its S moved on) plays a
                // program of five tracks — Matrix's — never the old pans' three
                val s1 = r.fire(CfwModes.EV_UPROLL)
                val drew = if (s1 == s0) null else Arm.entries.map { a -> synchronized(r.sim) { r.sim.motionForTest(a).slots[ListStaging.SLOT]?.takeIf { it.tag == s1 }?.prog?.tracks?.size } }
                r.until("$what: staged again on the return", 30_000) { r.onLoop { r.shell.mainBinding.staging }.let { it != null && it !== d0 } }
                r.bound("$what: Matrix bound")
                val (ms, pan) = r.onLoop { r.shell.mainBinding.staging!!.let { it to it.pan(ListStaging.WHOLE, 1) } }
                val m2 = marks(r)
                assertEquals(m1.staged + 1 to m1.dropped + 1, m2.staged to m2.dropped, "$what: one drop and ONE full staging on the return")
                assertTrue(r.notes("mainstage").any { it.startsWith("dropped") && "Matrix's records are staging records" in it }, "$what: the drop says why: ${r.notes("mainstage").takeLast(3)}")
                assertTrue(ms.recordEffect == wm.damage.core.motion.ScrollEffect.MATRIX && ms.plays.effect == wm.damage.core.motion.ScrollEffect.MATRIX, "$what: Matrix plays, its records staged: ${ms.plays}")
                assertTrue(named.isNotEmpty() && named.all { it == "v2 5 tracks" }, "$what: every scroll SET since the return names Matrix's pans (version 2, five tracks), none the old ones: $named (${setsSince()}, old $old)")
                assertTrue(drew == null || drew.all { it == null || it == 5 }, "$what: the notch at once drew no old pan on either lens (tracks per lens: $drew)")
                delay(r.motionMs() + r.q() + 300); r.settle("after the first notch")
                val s = r.fire(CfwModes.EV_UPROLL)
                for (a in Arm.entries) r.until("$a plays Matrix's notch") { synchronized(r.sim) { r.sim.motionForTest(a).slots[ListStaging.SLOT]?.prog4 } == pan.prog4 }
                assertEquals(5, pan.program.tracks.size, "Matrix's pan")
                delay(r.motionMs() + r.q() + 300); r.settle("after Matrix's notch")
                assertTrue(r.right.drop(at).any { img -> r.subs(img).any { r.isCommit(it, s) } }, "$what: a COMMIT of S $s")
                r.assertOracle("$what: after Matrix's notch")
            }
            println("DM2aF $what gap $gapMs: ${r.notes("mainstage").drop(0).filter { "pans" in it || it.startsWith("dropped") || it.startsWith("staged") }.takeLast(6).map { it.take(90) }}")
        } finally { r.close() }
    }

    @Test fun throughMatrixOnToNoneUnderACoverKeepsTheStaging_DM2aF(): Unit = runBlocking { throughMatrixUnderACover(listOf("crush", "matrix", "none")) }
    @Test fun throughMatrixOnToCrushUnderACoverKeepsTheStaging_DM2aF(): Unit = runBlocking { throughMatrixUnderACover(listOf("none", "matrix", "crush")) }
    @Test fun matrixKeptUnderACoverIsOneFullStagingOnTheReturn_DM2aF(): Unit = runBlocking { throughMatrixUnderACover(listOf("matrix")) }

    /**
     * C3 DM-2aF (`C3-DM2b-V1-a.md` §3, decisions EP 2): a staging that gave Matrix's records up stays on its fallback's
     * pans through Auto's re-makes. The rig's face cannot draw 'F' (one of the noise set's codes), so Matrix's records
     * cannot be made: Matrix asked at stage 5, 256 KiB, plays Depth, said. Then the glasses' steps are measured at 30 ms
     * against the fit's 8.8 ms (modeled, 352), so the period in force rises and Auto owes another step count: over six
     * slow notches and 3 s of quiet after them no staging is made again and none is dropped — Depth's pans are re-made
     * alone ("pans re-made — the period in force …"), the staging the same object, the oracle holding. The counts are
     * printed either way (measured in the rig). C3 DM-2bF (the lead's ruling on DM-2aF's last point): his own change of
     * `Scroll length` then tries the records once more — ONE drop and ONE staging, which gives them up again, said — and
     * three slow notches and quiet after it stage nothing more ([givenUpStaysOnItsFallback]).
     */
    @Test
    fun aStagingThatGaveMatrixsRecordsUpStaysOnItsFallbackThroughAutosRemakes_DM2aF(): Unit = runBlocking {
        givenUpStaysOnItsFallback("matrix", wm.damage.core.motion.ScrollEffect.MATRIX)
    }

    /** C3 DM-2bF (decisions EZ 3): Scramble's side of [aStagingThatGaveMatrixsRecordsUpStaysOnItsFallbackThroughAutosRemakes_DM2aF]
     *  — the face that cannot draw 'F' cannot draw Scramble's noise set either, so its records are given up (Depth, said),
     *  and the same predicate keeps the staging through Auto's re-makes and tries the records again on his length change. */
    @Test
    fun aStagingThatGaveScramblesRecordsUpStaysOnItsFallbackThroughAutosRemakes_DM2bF(): Unit = runBlocking {
        givenUpStaysOnItsFallback("scramble", wm.damage.core.motion.ScrollEffect.SCRAMBLE)
    }

    private suspend fun givenUpStaysOnItsFallback(id: String, effect: wm.damage.core.motion.ScrollEffect) {
        val noF = object : wm.damage.core.text.TextRasterizer by FakeText() {
            override fun covers(text: String, font: wm.damage.core.text.FontSpec) = text != "F"
        }
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff, text = noF)
        try {
            r.up()
            val d0 = r.onLoop { r.shell.mainBinding.staging }
            r.shell.updateSettings { it.copy(scrollEffect = id) }
            r.until("${effect.word} asked and staged without its records") { r.onLoop { r.shell.mainBinding.staging }.let { it != null && it !== d0 &&
                it.motion?.effect == effect } }
            r.bound("${effect.word} asked, its records given up")
            r.settle("staged")
            val ms = r.onLoop { r.shell.mainBinding.staging!! }
            assertTrue(ms.recordEffect == null && ms.plays.effect == wm.damage.core.motion.ScrollEffect.DEPTH &&
                "cannot draw 'F'" in (ms.plays.why ?: ""), "the records given up, said: ${ms.plays}")
            val m = marks(r)
            val p0 = r.onLoop { r.shell.periodInForceForTest() }
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 30_000L
            repeat(6) { n -> r.fire(if (n % 2 == 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL); delay(r.motionMs() + r.q() + 300) }
            delay(3_000)
            val m2 = marks(r)
            val p1 = r.onLoop { r.shell.periodInForceForTest() }
            val remade = r.notes("mainstage").count { it.startsWith("pans re-made — the period in force") }
            println("DM2aF given-up (${effect.word}): ${m2.staged - m.staged} staging(s) and ${m2.dropped - m.dropped} drop(s) over 6 slow notches and 3 s; " +
                "period in force $p0 → $p1; $remade Auto re-make(s); drops: ${r.notes("mainstage").filter { it.startsWith("dropped") }.takeLast(3).map { it.take(160) }}")
            assertEquals(0 to 0, (m2.staged - m.staged) to (m2.dropped - m.dropped), "no staging again and no drop while ${effect.word}'s records stay given up")
            assertTrue(p1 > p0 && remade > 0, "the period rose ($p0 → $p1) and Depth's pans were re-made alone for it ($remade)")
            r.settle("after the notches")
            assertTrue(r.onLoop { r.shell.mainBinding.staging } === ms, "the same staging")
            r.assertOracle("after the notches")
            // C3 DM-2bF: his own change of Scroll length tries the records once more — one full staging, given up again, said
            r.shell.updateSettings { it.copy(scrollLength = 450) }
            r.until("staged again for his length") { r.onLoop { r.shell.mainBinding.staging }.let { it != null && it !== ms && it.motion?.lengthMs == 450 } }
            r.bound("staged again for his length"); r.settle("staged again for his length")
            val ms2 = r.onLoop { r.shell.mainBinding.staging!! }
            val m3 = marks(r)
            assertEquals(1 to 1, (m3.staged - m2.staged) to (m3.dropped - m2.dropped), "his length change: one drop and one staging")
            assertTrue(r.notes("mainstage").any { it.startsWith("dropped") && "${effect.word}'s records are staging records" in it }, "the drop says why: ${r.notes("mainstage").takeLast(3)}")
            assertTrue(ms2.recordEffect == null && "cannot draw 'F'" in (ms2.plays.why ?: ""), "the records tried and given up again, said: ${ms2.plays}")
            repeat(3) { n -> r.fire(if (n % 2 == 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL); delay(r.motionMs() + r.q() + 300) }
            delay(3_000)
            val m4 = marks(r)
            // C3 DM-2bG (decisions FI; `C3-DM2b-V2-b.md` 2): anchored as the first half is — an Auto re-make was tried after
            // his change (the slow glass raises the new staging's period), so the count below is not passed empty
            val remade2 = r.notes("mainstage").count { it.startsWith("pans re-made — the period in force") }
            println("DM2bG given-up (${effect.word}): after his change ${m4.staged - m3.staged} staging(s), ${m4.dropped - m3.dropped} drop(s); " +
                "Auto re-make(s) $remade → $remade2")
            assertEquals(0 to 0, (m4.staged - m3.staged) to (m4.dropped - m3.dropped), "Auto's re-makes after it stage nothing")
            assertTrue(remade2 > remade, "an Auto re-make was tried after his change ($remade → $remade2)")
            r.settle("after his change"); r.assertOracle("after his change")
        } finally { r.close() }
    }

    /**
     * C3 DM-2aF (`C3-DM2a-V1-b.md` §2: its probe, kept as a directed test; the fuzz and the stage-5 walk stay as they are):
     * Depth → Matrix with Main in front on a link that takes time (20 ms a packet down, 30 up), four notches 400 ms apart
     * from 4 ms after the drop — the first can race RIGHT's unbind. Every notch is counted (the row moves one a notch,
     * whoever drew it), Matrix is staged and bound, and the oracle holds after its staging and after a Matrix notch.
     * C3 DM-2aV: the notches the glasses drew (both lenses' S moved and each lens's slot holds that S's instance) are
     * counted and printed, and the leg out of Matrix runs the same. Measured over twenty runs each (DM-2aV's report):
     * into Matrix the glasses drew one notch in 18 and none in 2, so it is printed only; out of Matrix one in all 20, so
     * at least one is asserted there.
     */
    @Test
    fun notchesWhileMatrixIsStagedOnALinkThatTakesTimeAreEachCounted_DM2aF(): Unit = runBlocking {
        notchesDuringTheReStaging(ScrollEffect.DEPTH, ScrollEffect.MATRIX, glassesAtLeast = 0)
    }

    @Test
    fun notchesWhileMatrixIsLeftOnALinkThatTakesTimeAreEachCounted_DM2aV(): Unit = runBlocking {
        notchesDuringTheReStaging(ScrollEffect.MATRIX, ScrollEffect.DEPTH, glassesAtLeast = 1)
    }

    private suspend fun notchesDuringTheReStaging(from: ScrollEffect, to: ScrollEffect, glassesAtLeast: Int) {
        val r = Rig(Taps.REPORT, stage = 5, link = 20L to 30L, features = 0xdff)
        try {
            r.up()
            fun plays(e: ScrollEffect, not: ListStaging?) = r.onLoop { r.shell.mainBinding.staging }.let { it != null && it !== not &&
                it.plays.effect == e && (e != ScrollEffect.MATRIX || it.recordEffect == ScrollEffect.MATRIX) } &&
                r.onLoop { r.shell.mainBoundForTest().first } != null && r.sim.bindForTest(Arm.RIGHT).field39() == r.bound39
            if (from != ScrollEffect.DEFAULT) {
                r.shell.updateSettings { it.copy(scrollEffect = from.id) }
                r.until("${from.word} staged and bound", 60_000) { plays(from, null) }
                r.settle("after ${from.word}'s staging")
            }
            val d0 = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
            assertEquals(from, d0.plays.effect, "the leg starts on ${from.word}")
            val n = r.list().count
            val row0 = r.row()
            r.shell.updateSettings { it.copy(scrollEffect = to.id) }
            r.until("${from.word}'s staging dropped") { r.onLoop { r.shell.mainBinding.staging } !== d0 }
            delay(4)
            val glasses = ArrayList<Int>()
            repeat(4) { k ->
                val s0 = Arm.entries.map { a -> synchronized(r.sim) { r.sim.bindForTest(a).seq } }
                r.fire(CfwModes.EV_UPROLL)
                if (Arm.entries.withIndex().all { (i, a) -> synchronized(r.sim) { r.sim.bindForTest(a).seq.let { sa ->
                        sa != s0[i] && r.sim.motionForTest(a).slots[ListStaging.SLOT]?.tag == sa } } }) glasses += k + 1
                delay(400)
            }
            r.until("${to.word} staged and bound", 60_000) { plays(to, d0) }
            println("DM2aV ${from.word} → ${to.word}: ${glasses.size} of 4 notches during the re-staging drawn by the glasses (notches $glasses)")
            assertTrue(glasses.size >= glassesAtLeast, "${from.word} → ${to.word}: the glasses drew ${glasses.size} of the 4 notches (at least $glassesAtLeast)")
            r.settle("after ${to.word}'s staging")
            assertEquals((row0 + 4).mod(n), r.row(), "every notch counted: four rows on from $row0 (of $n)")
            r.assertOracle("after ${to.word}'s staging")
            val pan = r.onLoop { r.shell.mainBinding.staging!!.pan(ListStaging.WHOLE, 1) }
            r.fire(CfwModes.EV_UPROLL)
            for (a in Arm.entries) r.until("$a plays ${to.word}'s notch") { synchronized(r.sim) { r.sim.motionForTest(a).slots[ListStaging.SLOT]?.prog4 } == pan.prog4 }
            delay(r.motionMs() + r.q() + 300); r.settle("after ${to.word}'s notch")
            assertEquals((row0 + 5).mod(n), r.row(), "${to.word}'s notch counted")
            r.assertOracle("after ${to.word}'s notch")
        } finally { r.close() }
    }

    /**
     * K1-2 stage 2, item 3a (decisions CM (1); `C3-FR-DM1.md` item 1, the narrow path of the covered switch): a programs
     * switch made with the list uncovered (Depth → Slide from another surface) falls in a pump with no room on the link, and
     * a cover (Settings) arrives before the next pump — so the switch's SETs never go; the cover's CLEAR goes instead. Then
     * `Scroll effect` → Crush under the cover, and back on Main a notch. With the cure the CLEAR's flush counts for the tail
     * books (the rules hold no scroll entry of the list after it), the old pans are released once it lands, Crush is re-made
     * and counted under the cover and switched by the return's poll: ONE flush since the switch carries scroll SETs — the
     * return's — and it names Crush's pan; the first notch back plays Crush on both lenses. Without it the slot stayed held,
     * the return SET Slide's pans (the record's symptom: the first notches back play the previous effect) and Crush was
     * re-made and SET only after them — the same end state a little later, which only the return's own batch shows.
     */
    @Test
    fun aSwitchWhoseSetsACoverOvertookLetsTheNextChangePlayOnTheReturn_CM1(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            val hit = java.util.concurrent.atomic.AtomicBoolean(false)
            val at = java.util.concurrent.atomic.AtomicInteger(-1)
            r.shell.noRoomAfterPollForTest = {
                // the pump whose poll switched: no room (no SETs), and the cover handled before a pump with room
                when {
                    !hit.get() && r.shell.mainBinding.reprogram?.switched == true -> {
                        hit.set(true)
                        at.set(r.right.size)
                        r.shell.services.runOnShell { r.shell.services.openWindow("settings", null) }
                        true
                    }
                    hit.get() -> r.shell.currentWindowId() != "settings"
                    else -> false
                }
            }
            r.shell.updateSettings { it.copy(scrollEffect = "slide") }
            r.until("the switch fell in a pump with no room, the cover behind it") { hit.get() }
            r.until("Settings open") { r.onLoop { r.shell.currentWindowId() } == "settings" }
            r.until("Settings' transition landed") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("in Settings")
            r.shell.noRoomAfterPollForTest = null
            r.shell.updateSettings { it.copy(scrollEffect = "crush") }
            delay(2_000)                                                   // a while in Settings
            r.settle("after the change under the cover")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back on Main and its list bound on RIGHT") { r.onLoop { r.shell.currentWindowId() } == null && r.sim.bindForTest(Arm.RIGHT).field39() == r.bound39 }
            r.until("the return's transition landed") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("back on Main")
            val (ms, pan) = r.onLoop { r.shell.mainBinding.staging!!.let { it to it.pan(ListStaging.WHOLE, 1) } }
            assertTrue(ms.motion?.effect == wm.damage.core.motion.ScrollEffect.CRUSH && ms.plays.effect == wm.damage.core.motion.ScrollEffect.CRUSH,
                "the pans in force are Crush's at the return: asked ${ms.motion?.effect}, plays ${ms.plays.effect}")
            val sets = r.right.drop(at.get()).map { r.subs(it) }.filter { ss -> ss.any { r.isSub(it, 27, 0) && it[4].toInt() == CfwModes.EV_UPROLL && it[5].toInt() == 0 } }
                .map { ss -> ss.filter { r.isSub(it, 27, 0) && it[4].toInt() == CfwModes.EV_UPROLL && it[5].toInt() == 0 }.map { r.u16(it, 9) } }
            println("CM1 the flushes carrying scroll SETs since the switch: $sets (Crush's pan ${pan.prog4}); mainstage " +
                r.notes("mainstage").filter { it.startsWith("pans") || it.startsWith("re-made") }.map { it.take(60) })
            assertEquals(listOf(listOf(pan.prog4)), sets, "ONE flush since the switch carries scroll SETs — the return's — naming Crush's pan ${pan.prog4}")
            for (a in Arm.entries) assertEquals(pan.prog4, r.sim.bindForTest(a).entries.filterNotNull().first { it.eventId == CfwModes.EV_UPROLL }.prog,
                "$a holds the SET naming Crush's pan")
            r.fire(CfwModes.EV_UPROLL)
            for (a in Arm.entries) r.until("$a plays the first notch back") { synchronized(r.sim) { r.sim.motionForTest(a).slots[ListStaging.SLOT]?.prog4 } != null }
            for (a in Arm.entries) assertEquals(pan.prog4, synchronized(r.sim) { r.sim.motionForTest(a).slots[ListStaging.SLOT]!!.prog4 }, "$a: the first notch back plays Crush's pan")
            delay(r.motionMs() + r.q() + 300); r.settle("after the notch")
            r.assertOracle("after the notch")
        } finally { r.shell.noRoomAfterPollForTest = null; r.close() }
    }

    /** K1-1 item 4: under Auto the steps follow the period in force — steps measured at 48 ms raise it 46 → 55 (decisions
     *  BF), and the pans are then re-made alone with T 5 (300 ms at 55 ticks), in idle; the staging's own period stays the
     *  floor, and five seconds with no input at fast steps bring both back (46, T 6). */
    @Test
    fun autosStepsFollowThePeriodInForce_K11(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            val m = marks(r)
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 48_000L
            var notches = 0
            while (r.onLoop { r.shell.periodInForceForTest() } == 46 && notches < 4) {
                r.fire(if (r.row() < r.list().count - 2) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL); notches++
                delay(r.motionMs() + r.q() + 300); r.settle("slow notch $notches")
            }
            assertEquals(55, r.onLoop { r.shell.periodInForceForTest() })
            remade(r, "T at P 55", m)
            val e = r.list().entryFor(CfwModes.EV_UPROLL)!!
            assertEquals(55 to 5, e.period to e.program!!.T, "300 ms at P 55: T 5")
            assertTrue(r.notes("mainstage").any { it.startsWith("pans re-made — the period in force 55 gives Auto 5 steps") }, "${r.notes("mainstage").takeLast(3)}")
            notch(r, CfwModes.EV_DOWNROLL, "a notch at T 5")
            val m2 = marks(r)
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 30_000L
            repeat(Shell.PERIOD_WINDOW + 1) { n ->
                r.fire(if (n % 2 == 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)
                delay(r.motionMs() + r.q() + 300); r.settle("fast notch $n")
            }
            delay(Shell.PERIOD_QUIET_MS + 1_000); r.settle("5 s with no input")
            assertEquals(46, r.onLoop { r.shell.periodInForceForTest() })
            remade(r, "T back at P 46", m2)
            assertEquals(46 to 6, r.list().entryFor(CfwModes.EV_UPROLL)!!.let { it.period to it.program!!.T })
            r.assertOracle("after")
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
            assertTrue(ks.isNotEmpty() && ks.all { it == wm.damage.core.shell.ListStaging.WHOLE }, "pans of widths $ks")
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
     * K1-2 stage 1 (decisions BC O1): on a 256 KiB session with BIND in force (the stored record's last size, AD.1) Main is
     * staged on ONE strip in its own region, and a row form's change withdraws the list as at 160 KiB — the rules' CLEAR
     * reaches RIGHT before the strip's write, the LIST and the SETs after it, naming the same pans (no strip switch). A
     * notch fired while the write is on its way matches no entry (no S taken) and goes as today; the return names the
     * phone's row on RIGHT; once bound again a notch is adopted and committed. Prints the path's flushes and bytes (the
     * report's count; the rig's link is the start rig's, so the times are the model's).
     */
    @Test
    fun aRowFormChangeAt256KiBWithdrawsTheListAndReturnsOnItsRow_O1(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            assertEquals(256 * 1024, r.t.state.value.cacheSize, "a 256 KiB session from the stored record")
            val staged = r.notes("mainstage").first { it.startsWith("staged") }
            assertTrue("Main's own region" in staged && "second slot" !in staged, "one strip in Main's own region: $staged")
            // the region's free span as the note says it: the region less what is staged, from Main's end to the region's
            val span = Regex("""the region's free span (\d+) B at (\d+)\.\.(\d+)""").find(staged)?.groupValues?.drop(1)?.map { it.toInt() }
            val (end, regionEnd, start) = r.onLoop { r.shell.mainBinding.staging!!.let { Triple(it.end, it.regionEnd, it.start) } }
            assertEquals(listOf(regionEnd - end, end, regionEnd), span, "the free span said: $staged")
            assertEquals(CfwModes.mainRegion(256 * 1024, true).let { it.last + 1 - it.first } - (end - start), span!![0], "the region less the staging")
            assertEquals(0, r.row())
            val list0 = r.list()
            val progs0 = list0.entries.filter { !it.isTap }.map { it.prog4 }.toSet()
            val at = r.right.size
            val t0 = System.currentTimeMillis()
            var between: Pair<Int, Int>? = null                 // RIGHT's S before and after the notch in between
            r.onImage = { img ->
                if (between == null && r.cacheWrite(img) && r.sim.bindForTest(Arm.RIGHT).field39() == 0) {
                    val s0 = r.sim.bindForTest(Arm.RIGHT).seq
                    between = s0 to r.fire(CfwModes.EV_UPROLL)
                }
            }
            r.rows[3].s = r.rows[3].s.copy(line = "summary 33")
            r.shell.services.requestRender(r.rows[3])
            r.until("withdrawn", 15_000) { r.notes("mainstage").any { it.startsWith("withdrawn — row 3") } }
            r.until("re-staged", 15_000) { r.notes("mainstage").any { it.startsWith("row 3 re-staged") } }
            r.bound("offered again")
            r.onImage = null
            val b = assertNotNull(between, "a notch was fired while the strip's write was on its way, with no entry on RIGHT")
            assertEquals(b.first, b.second, "the notch in between matched no entry (no S taken)")
            assertEquals(1, r.row(), "the notch in between moved the row as today")
            // decisions AF.2: the return named the phone's current row — RIGHT's list cursor is where the frame is
            assertEquals(r.row(), r.sim.bindForTest(Arm.RIGHT).lists[0].cursor, "the return named the current row on RIGHT")
            // the path on RIGHT: the CLEAR of Main's entries first, then the strip's write, then the SETs (the same pans)
            val imgs = r.right.drop(at)
            val clearAt = imgs.indexOfFirst { img -> r.subs(img).any { r.isSub(it, 27, CfwModes.BIND_CLEAR) } }
            val writeAt = imgs.indexOfFirst { r.cacheWrite(it) }
            val setAt = imgs.indices.firstOrNull { it > writeAt && r.subs(imgs[it]).any { s -> r.isSub(s, 27, CfwModes.BIND_SET) } } ?: -1
            assertTrue(clearAt in 0 until writeAt && setAt > writeAt, "CLEAR at $clearAt, the write at $writeAt, the SETs at $setAt of RIGHT's images")
            val list1 = r.list()
            assertEquals(progs0, list1.entries.filter { !it.isTap }.map { it.prog4 }.toSet(), "the same pans: no strip switch")
            assertEquals(list0.mark, list1.mark, "the mark kept (P9: a row re-staged keeps it)")
            val restaged = r.notes("mainstage").first { it.startsWith("row 3 re-staged") }
            val bytes = Regex("""re-staged — (\d+) B in (\d+) piece""").find(restaged)!!.groupValues
            println("O1 FACT: a row form's change at 256 KiB: RIGHT's images from the CLEAR to the SETs ${setAt - clearAt + 1} " +
                "(the CLEAR's ${clearAt - 0}, the write's ${writeAt}, the SETs' ${setAt} after the change), the re-stage ${bytes[1]} B in ${bytes[2]} piece(s), " +
                "${System.currentTimeMillis() - t0} ms on the rig to the re-offer; the notch in between drawn by the phone; $restaged")
            r.assertOracle("bound again")
            notch(r, CfwModes.EV_UPROLL, "a notch after the re-bind")
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
            val m = marks(r)
            r.shell.updateSettings { it.copy(scrollLength = 600, frames = 0) }
            remade(r, "600 ms", m)
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

    private fun isWrapPlay(s: ByteArray) = s[0].toInt() == 26 && s[1].toInt() == 0 && s[2].toInt() == ListStaging.WRAP_SLOT

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
        assertEquals(r.onLoop { r.shell.mainBinding.staging!!.wrapPan(e.step)!!.prog4 }, r.u16(play, 4), "$what: the PLAY's program is the wrap's own pan (B082)")
        assertEquals(e.program!!.T, r.onLoop { r.shell.mainBinding.staging!!.wrapPan(e.step)!!.program.T }, "$what: with the entry's T")
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
        r.until("$what: the wrap's last step on RIGHT") { r.sim.motionForTest(Arm.RIGHT).slots[ListStaging.WRAP_SLOT]?.drawing != true }
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
        assertTrue(r.notes("motion").any { "slot ${ListStaging.WRAP_SLOT} " in it && "drew its last step" in it }, "$what: the wrap's instance drew its last step: ${r.notes("motion").takeLast(3)}")
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

    /** C3 DM-0 (the DM spec §2, S4, S5): at stage 5 on a bit-11 build Main's eight rows at 352 are a ring list — the notch
     *  past the last row fires on the glasses like any notch: adopted (no snap-back), COMMITted, no PLAY in slot 1, the
     *  row the other end, both lenses' lists still ringing after the answers, every LIST LIST-RING; and back up past row 0.
     *  Belief equals both lenses at rest. */
    @Test
    fun aWrapNotchOnARingListFiresOnTheGlassesAndIsCommitted_DM0(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            assertTrue(r.onLoop { r.shell.mainBinding.staging!!.ring } && r.list().ring, "the ring form at 352 with eight rows at stage 5")
            val count = r.list().count
            walkTo(r, count - 1)
            for (dir in listOf(+1, -1)) {
                val what = if (dir > 0) "down past the last row" else "up past row 0"
                val to = if (dir > 0) 0 else count - 1
                val at = r.right.size
                val snaps = r.notes("snapback").size
                val s = r.fire(if (dir > 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)
                delay(r.motionMs() + r.q() + 300); r.settle("$what: after")
                assertEquals(to, r.row(), "$what: the row is the other end")
                assertTrue(r.right.drop(at).any { img -> r.subs(img).any { r.isCommit(it, s) } }, "$what: a COMMIT of S $s")
                assertTrue(wrapBatch(r, at) == null, "$what: no wrap PLAY in slot ${ListStaging.WRAP_SLOT}")
                assertEquals(snaps, r.notes("snapback").size, "$what: no snap-back: ${r.notes("snapback").takeLast(2)}")
                val lists = r.right.drop(at).flatMap { r.subs(it) }.filter { r.isSub(it, 27, 2) || r.isSub(it, 27, CfwModes.BIND_LIST_RING) }
                assertTrue(lists.isNotEmpty() && lists.all { r.isSub(it, 27, CfwModes.BIND_LIST_RING) }, "$what: every LIST is LIST-RING (S5)")
                for (a in Arm.entries) assertTrue(r.sim.bindForTest(a).lists[0].ring && r.sim.bindForTest(a).lists[0].cursor == to, "$what: $a's list rings, its cursor at $to")
                r.assertOracle(what)
            }
        } finally { r.close() }
    }

    /**
     * C3 DM-1, the brief's item 5 (decisions AD's S6) on the SHELL path: at stage 5 on a bit-11 build (256 KiB, his cache;
     * Main's eight rows at 352: a ring list), a Settings change Depth → Crush → Depth with Main in front and notches around
     * each change:
     * - each change is K1-1's re-make: every cache write since the change lies in the free pan slot (no strip, band form,
     *   item table or press byte), and ONE flush then carries the list's LIST-RING with the new pitch (+rowH for Crush,
     *   −rowH back) AHEAD of the three SETs naming the re-made pans (with the slot's DISCARD naming the row);
     * - a notch fired on the lenses just before the change, its report held on the way until the switch's batch has landed
     *   on both, is NOT adopted against the new form — refused, `restaged`, by the switch's fresh mark — applied as today (one
     *   row), and belief equals both lenses. (C3 X2DM1a, decisions CA (6): the mark gives no protection `names` does not —
     *   `names` judges each list with its own entry and pitch, so a report from a lens holding the new pitch with the old
     *   entry matches neither list and `names` refuses it; what the mark changes is that the held report, which `names`
     *   would adopt against the previous list it was drawn from — right too — is applied as today instead);
     * - the next notch plays the new effect's pan on both lenses (slot 0's program) and is COMMITted with no snap-back,
     *   belief equal to both lenses.
     */
    @Test
    fun aPitchSwitchGoesAsOneBatchOnTheShellPath_DM1(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            val rig = r.t as StartRig
            val rowH = r.onLoop { r.shell.mainBinding.staging!!.layout.rowH }
            assertTrue(r.list().ring && r.list().pitch == -rowH, "Depth at stage 5: a ring list at pitch −rowH")
            notch(r, CfwModes.EV_UPROLL, "a notch on Depth")
            for ((effect, pitch) in listOf("crush" to rowH, "depth" to -rowH)) {
                val ms = assertNotNull(r.onLoop { r.shell.mainBinding.staging })
                val slots = assertNotNull(ms.progSlots, "$effect: a spare pan slot at 256 KiB")
                val free = slots[1 - ms.activeProgs] until slots[1 - ms.activeProgs] + ms.progSlot
                r.settle("$effect: before")
                val row0 = r.row()
                // a notch on the lenses whose report is held on the way (the hook sends its 115 inside the fire)
                val held = java.util.concurrent.CopyOnWriteArrayList<Pair<Arm, ByteArray>>()
                val holding = java.util.concurrent.atomic.AtomicBoolean(true)
                rig.onNotify = { a, p -> if (holding.get()) { held += a to p; null } else p }
                val now = System.currentTimeMillis()
                for (a in Arm.entries) r.sim.conformanceInput(a, CfwModes.DEV_RING, CfwModes.EV_UPROLL.toLong(), 0, now)
                holding.set(false)
                val s = r.sim.bindForTest(Arm.RIGHT).seq
                assertTrue(held.isNotEmpty(), "$effect: the held notch's report")
                val at = r.right.size
                val m = marks(r)
                r.shell.updateSettings { it.copy(scrollEffect = effect) }
                remade(r, effect, m)
                val imgs = r.right.drop(at)
                for (w in imgs.filter { r.cacheWrite(it) }) for (rg in writeRanges(w)) assertTrue(rg.first >= free.first && rg.last <= free.last,
                    "$effect: a cache write at ${rg.first}..${rg.last + 1} outside the free pan slot ${free.first}..${free.last + 1} (a strip or record write)")
                val list = r.list()
                assertEquals(pitch, list.pitch, "$effect: the list's pitch")
                val progs = list.entries.filter { !it.isTap }.map { it.prog4 }.toSet()
                fun isList(sub: ByteArray) = r.isSub(sub, 27, CfwModes.BIND_LIST_RING) || r.isSub(sub, 27, 2)
                val switch = imgs.map { r.subs(it) }.filter { ss -> ss.any { r.isSub(it, 27, 0) && r.u16(it, 9) in progs } }
                assertEquals(1, switch.size, "$effect: ONE flush carries the SETs naming the re-made pans")
                val ss = switch.single()
                val li = ss.indexOfFirst { isList(it) }
                assertTrue(li >= 0 && r.isSub(ss[li], 27, CfwModes.BIND_LIST_RING), "$effect: the switch's flush carries the list's LIST-RING")
                assertEquals(pitch, r.u16(ss[li], 9).toShort().toInt(), "$effect: the LIST-RING carries the new pitch")
                val sets = ss.withIndex().filter { (_, x) -> r.isSub(x, 27, 0) && x[4].toInt() != CfwModes.EV_TAP }
                assertTrue(sets.size == 2 && sets.all { it.index > li && r.u16(it.value, 9) in progs }, "$effect: the LIST ahead of the two scroll SETs naming the re-made pans: ${ss.map { "${it[0]}/${it[1]}" }}")
                for (a in Arm.entries) r.sim.bindForTest(a).lists[0].let { l -> assertTrue(l.pitch == pitch && l.ring, "$effect: $a's list at pitch ${l.pitch}, ring ${l.ring}") }
                // the held report, now: refused by its mark, applied as today
                for ((a, p) in held) rig.deliver(a, p)
                r.t.ringStockForTest(BoundList.stockEvent(CfwModes.EV_UPROLL)!!)
                delay(r.motionMs() + r.q() + 300); r.settle("$effect: the held report answered")
                assertTrue(r.notes("snapback").any { "cause=restaged S=$s " in it }, "$effect: the report from before the switch not adopted (restaged): ${r.notes("snapback").takeLast(3)}")
                assertEquals((row0 + 1).mod(list.count), r.row(), "$effect: the held notch applied as today, one row")
                r.assertOracle("$effect: after the held report")
                // the next notch plays the new pan on both lenses and is committed
                val snaps = r.notes("snapback").size
                val at2 = r.right.size
                val row1 = r.row()
                val s2 = r.fire(CfwModes.EV_UPROLL)
                val pan = list.entryFor(CfwModes.EV_UPROLL)!!.prog4
                for (a in Arm.entries) r.until("$effect: $a plays the notch") { synchronized(r.sim) { r.sim.motionForTest(a).slots[ListStaging.SLOT]?.prog4 } == pan }
                delay(r.motionMs() + r.q() + 300); r.settle("$effect: after the notch")
                assertEquals((row1 + 1).mod(list.count), r.row(), "$effect: the notch moved one row")
                assertTrue(r.right.drop(at2).any { img -> r.subs(img).any { r.isCommit(it, s2) } }, "$effect: a COMMIT of S $s2")
                assertEquals(snaps, r.notes("snapback").size, "$effect: no snap-back: ${r.notes("snapback").takeLast(2)}")
                r.assertOracle("$effect: after the notch")
                rig.onNotify = null
            }
        } finally { r.close() }
    }

    /** C3 DM-0 (the staging's form follows EFFECTS in force): a hand disarm of EFFECTS mid-session drops the ring staging
     *  and Main is staged again in today's form — its wrap record, a plain LIST, never a LIST-RING after it — so no ring
     *  list is ever bound without EFFECTS (a LIST-RING would be refused 15, and so would every fire on a ring list). */
    @Test
    fun aHandDisarmOfEffectsStagesMainAgainInTodaysForm_DM0(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            assertTrue(r.onLoop { r.shell.mainBinding.staging!!.ring }, "the ring form first")
            r.t.devProbe("flags", "0x3c")                     // stage 4's flags: EFFECTS taken away by hand
            r.until("staged again in today's form and bound") { r.onLoop { r.shell.mainBinding.staging?.let { !it.ring && it.wrap != null } == true &&
                r.shell.mainBoundForTest().first?.ring == false } }
            r.settle("re-bound")
            val at = r.right.size
            assertTrue(r.notes("mainstage").any { "dropped" in it && "stage-5 form" in it }, "the drop said why: ${r.notes("mainstage").takeLast(3)}")
            walkTo(r, r.list().count - 1)
            r.fire(CfwModes.EV_UPROLL)
            r.until("the wrap's PLAY (today's)") { wrapBatch(r, at) != null }
            delay(r.motionMs() + r.q() + 300); r.settle("after the wrap")
            assertTrue(r.right.drop(at).flatMap { r.subs(it) }.none { r.isSub(it, 27, CfwModes.BIND_LIST_RING) }, "no LIST-RING after the disarm")
            for (a in Arm.entries) assertTrue(!r.sim.bindForTest(a).lists[0].ring, "$a's list is plain")
            r.assertOracle("after the wrap")
        } finally { r.close() }
    }

    /** C3 FF (the fit follows the fact the ring form follows): at 480, where Main's paint does not wrap and the form is
     *  today's at both stages, a hand disarm of EFFECTS drops the staging made on the fast DRAW path's fit (Depth P 16 T 19)
     *  and Main is staged again on C2's (P 59 T 5) — the fast fit's programs never stay bound while fires run C2's path. A
     *  notch on each lands with belief equal to both lenses. (A bit armed by hand is believed only when a start arms it,
     *  decisions AG.2: a hand re-arm changes nothing on the phone until the next start, which stages the fast fit.) */
    @Test
    fun aHandDisarmOfEffectsAt480RestagesOnC2sFit_FF(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            r.shell.updateSettings { it.copy(heightMode = 480) }
            fun staged(stage5: Boolean, pt: Pair<Int, Int>) = r.onLoop { r.shell.mainBinding.staging?.let { it.layout.rowsAbove == 5 && it.stage5 == stage5 &&
                !it.ring && it.period to it.t == pt && it.periodInForce == pt.first } == true && r.shell.mainBoundForTest().first?.entryFor(CfwModes.EV_UPROLL)?.period == pt.first }
            r.until("staged at 480 on the fast fit and bound") { staged(true, 16 to 19) }
            r.bound("at 480, stage 5")
            notch(r, CfwModes.EV_UPROLL, "a notch on the fast fit")
            val drops = r.notes("mainstage").count { it.startsWith("dropped") }
            r.t.devProbe("flags", "0x3c")                     // stage 4's flags: EFFECTS taken away by hand
            r.until("staged again on C2's fit and bound") { staged(false, 59 to 5) }
            r.bound("re-bound on C2's fit")
            assertTrue(r.notes("mainstage").filter { it.startsWith("dropped") }.drop(drops).any { "stage-5 form and fit no longer match" in it },
                "the drop said why: ${r.notes("mainstage").takeLast(3)}")
            notch(r, CfwModes.EV_DOWNROLL, "a notch on C2's fit")
            r.assertOracle("after")
        } finally { r.close() }
    }

    /**
     * C3 FF item 3 (decisions BF at stage 5): the measured period raises and lowers the fast fit's period as it does C2's.
     * With the glasses' steps measured at 30 ms (against the fit's modeled 8.8 ms at 352), the first motion that ends whole
     * (one sample in the window) raises P 16 → 34 (30 × 1.10 × 1.024, rounded up) and Auto's T follows through the programs-only re-make: 300 ms at
     * P 34, T 9; steps measured at 8 ms over the window and 5 s of quiet bring it back to the fit's 16 (the staging's own
     * period is the floor) and T 19. Belief equals both lenses after.
     */
    @Test
    fun theMeasuredPeriodRaisesAndLowersTheFastFitAndAutosStepsFollow_FF(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, stage = 5, kib256 = true, features = 0xdff)
        try {
            r.up()
            assertEquals(listOf(true, 16, 19), r.onLoop { r.shell.mainBinding.staging!!.let { listOf(it.stage5, it.period, it.t) } }, "the fast fit at 352")
            val m = marks(r)
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 30_000L
            var notches = 0
            while (r.onLoop { r.shell.periodInForceForTest() } == 16 && notches < 4) {
                r.fire(if (r.row() < r.list().count - 2) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL); notches++
                delay(r.motionMs() + r.q() + 300); r.settle("slow notch $notches")
            }
            assertEquals(34, r.onLoop { r.shell.periodInForceForTest() })
            // the raise is taken from the first motion that ended whole (one sample in the window); which notch the loop
            // reads it after depends on when that motion's body arrived (at its close), so the window, not the loop, says it
            assertTrue(r.notes("mainstage").any { it.startsWith("period 16 → 34") && "over the last 1 motion(s)" in it },
                "the first motion that ends whole raises the period: ${r.notes("mainstage").filter { it.startsWith("period") }}")
            remade(r, "T at P 34", m)
            val e = r.list().entryFor(CfwModes.EV_UPROLL)!!
            assertEquals(34 to 9, e.period to e.program!!.T, "300 ms at P 34: T 9")
            assertTrue(r.notes("mainstage").any { it.startsWith("pans re-made — the period in force 34 gives Auto 9 steps") }, "${r.notes("mainstage").takeLast(3)}")
            notch(r, CfwModes.EV_DOWNROLL, "a notch at T 9")
            val m2 = marks(r)
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 8_000L
            repeat(Shell.PERIOD_WINDOW + 1) { n ->
                r.fire(if (n % 2 == 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)
                delay(r.motionMs() + r.q() + 300); r.settle("fast notch $n")
            }
            delay(Shell.PERIOD_QUIET_MS + 1_000); r.settle("5 s with no input")
            assertEquals(16, r.onLoop { r.shell.periodInForceForTest() })
            remade(r, "T back at P 16", m2)
            assertEquals(16 to 19, r.list().entryFor(CfwModes.EV_UPROLL)!!.let { it.period to it.program!!.T })
            r.assertOracle("after")
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
            r.until("the wrap's instance closed on RIGHT") { r.sim.motionForTest(Arm.RIGHT).slots[ListStaging.WRAP_SLOT]?.drawing != true }
            // P9: in a slot of its own, the fire's close pass ends it with RIGHT's outcome-1 body, so the mirror hears of it (in
            // the bound slot the fire would end it silently)
            r.until("RIGHT's body for the closed wrap") { r.notes("motion").any { "slot ${ListStaging.WRAP_SLOT} " in it && "closed while drawing" in it } }
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
            r.until("the wrap's instance closed on RIGHT") { r.sim.motionForTest(Arm.RIGHT).slots[ListStaging.WRAP_SLOT]?.drawing != true }
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
            r.until("the wrap's instance closed on RIGHT by the window") { r.sim.motionForTest(Arm.RIGHT).slots[ListStaging.WRAP_SLOT]?.drawing != true }
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
            val m = marks(r)
            r.shell.updateSettings { it.copy(scrollLength = 600, frames = 0) }
            remade(r, "600 ms", m)
            walkTo(r, r.list().count - 1)
            val at = r.right.size
            val firedAt = System.currentTimeMillis()
            r.fire(CfwModes.EV_UPROLL)
            r.until("the wrap's PLAY on RIGHT") { wrapBatch(r, at) != null }
            val inst = assertNotNull(r.sim.motionForTest(Arm.RIGHT).slots[ListStaging.WRAP_SLOT])
            assertEquals(46 to 13, inst.period to inst.T, "the wrap's instance: the bound pan's period and T at 600 ms")
            var settleAt: Long? = null
            r.onImage = { img -> if (settleAt == null && r.subs(img).any { r.isSub(it, 26, 5) }) settleAt = System.currentTimeMillis() }
            r.until("the lone SETTLE", 10_000) { settleAt != null }
            r.onImage = null
            assertTrue(settleAt!! - firedAt >= r.q(), "the SETTLE came ${settleAt!! - firedAt} ms after the edge, Q ${r.q()}")
            delay(r.motionMs()); r.settle("after")
            assertTrue(r.notes("motion").any { "slot ${ListStaging.WRAP_SLOT} " in it && "drew its last step" in it }, "the wrap ran to its last step: ${r.notes("motion").takeLast(3)}")
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
        // a gap is a value above 0: `LocalRules.report` writes `gap` at every report, 0 included (`count("gap", g)`), so the
        // key's presence alone failed whenever any counters' note followed the first report; `owesAtSettle` and `stale`
        // are written only when one occurs, so their presence stays the test
        fun gapOf(c: String) = Regex("""\bgap=(\d+)""").find(c)?.groupValues?.get(1)?.toInt() ?: 0
        assertTrue(counters.none { gapOf(it) > 0 || "owesAtSettle=" in it || "stale=" in it }, "$what: ${counters.lastOrNull()}")
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
     *  no divergence: no keyframe; the closed instance's areas are repainted; belief equals both lenses after. K1-2 stage 1:
     *  the re-stage is a band form's move (a row form's change withdraws the list at 256 KiB too, so no notch is local
     *  while its write goes); a spare band slot lies inside the pan's read range. */
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
            r.rows[3].s = r.rows[3].s.copy(detail = "the detail of 3, longer now")
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
            assertTrue(imgs.take(setAt + 1).none { r.cacheWrite(it) }, "no cache write before or with the SETs (K1-1: Auto's T follows in idle after them)")
            assertTrue(imgs.take(setAt).any { img -> r.subs(img).any { r.isSub(it, 26, 5) } }, "the SETs only once the burst's lone SETTLE went")
            assertTrue(r.subs(imgs[setAt]).none { r.isSub(it, 26, 3) }, "not in a COMMIT's batch (a burst's answer)")
            // the next notches: no fold at P 55
            val bodies0 = r.notes("motion").size
            repeat(2) { r.fire(CfwModes.EV_DOWNROLL); delay(r.motionMs() + r.q() + 300); r.settle("notch at P 55") }
            val after = r.notes("motion").drop(bodies0).filter { "committed" in it }
            assertTrue(after.isNotEmpty() && after.all { "folded 0" in it }, "no fold at P 55: $after")
            // steps a little faster (47 ms: 53 ticks, 2 under 55) for a whole window, then quiet: no change — under 4 ticks
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 47_000L
            repeat(Shell.PERIOD_WINDOW + 1) { i ->
                r.fire(if (i % 2 == 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)
                delay(r.motionMs() + r.q() + 300); r.settle("47 ms notch $i")
            }
            delay(Shell.PERIOD_QUIET_MS + 600); r.settle("quiet after 47 ms notches")
            assertEquals(55, r.onLoop { r.shell.periodInForceForTest() }, "a two-tick gain does not bring it down (under ${Shell.PERIOD_LOWER_MIN})")
            // fast steps (his look at 0.81, ruled): not lowered while notches keep coming, only after 5 s with no input
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 30_000L
            repeat(Shell.PERIOD_WINDOW + 1) { n ->
                r.fire(if (n % 2 == 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)
                delay(r.motionMs() + r.q() + 300); r.settle("fast notch $n")
            }
            assertEquals(55, r.onLoop { r.shell.periodInForceForTest() }, "not lowered while the notches keep coming")
            delay(Shell.PERIOD_QUIET_MS + 1_000); r.settle("5 s with no input")
            assertEquals(46, r.onLoop { r.shell.periodInForceForTest() }, "30 ms would need 34 ticks; the floor is the staging's own period, 46 (W1)")
            r.assertOracle("after the period came down")
        } finally { r.close() }
    }

    /** Build SPLIT: a notch fired as a re-stage's first piece reaches the lens (Main bound at 256 KiB, a band form
     *  changed — K1-2 stage 1: a row form's change withdraws the list there too, so no notch is local while its pieces
     *  go) opens a burst; the re-stage's later pieces wait for its lone SETTLE — an instance's read ranges cover the
     *  strip and the spare band slots (§5.6) — so the notch is adopted, COMMITted and drawn whole, with no snap-back,
     *  and the oracle holds. */
    @Test
    fun aNotchBetweenARestagesPiecesHoldsTheRestUntilItsBurstEnds_SPLIT(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            val at = r.right.size
            var fired: Int? = null
            // the re-stage's first piece: a cache write into Main's own region (the atlas lies below it) — a mode 19 or its
            // deflated form, mode 25 (ZWRITE is armed at stage 4)
            val regionStart = CfwModes.mainRegion(256 * 1024, true).first
            fun firstOff(img: ByteArray): Int = when (img[0].toInt() and 0x7F) {
                19 -> r.u16(img, 1) * 4
                25 -> java.util.zip.Inflater().run { setInput(img, 1, img.size - 1); val out = ByteArray(4); inflate(out); end(); r.u16(out, 0) * 4 }
                else -> -1
            }
            r.onAnyImage = { a, img -> if (a == Arm.RIGHT && fired == null && r.cacheWrite(img) && firstOff(img) >= regionStart) fired = r.fire(CfwModes.EV_UPROLL) }
            r.rows[3].s = r.rows[3].s.copy(detail = "the detail of 3, longer now")
            r.shell.services.requestRender(r.rows[3])
            r.until("a notch fired as the re-stage's first piece went out", 15_000) { fired != null }
            r.onAnyImage = null
            r.until("the re-stage booked", 15_000) { r.notes("mainstage").any { it.startsWith("row 3 re-staged") } }
            r.settle("the re-stage written")
            val imgs = r.right.drop(at)
            val writes = imgs.withIndex().filter { (_, img) -> r.cacheWrite(img) && firstOff(img) >= regionStart }.map { it.index }
            assertTrue(writes.size >= 2, "the re-stage went in pieces: ${writes.size}")
            val settleAt = imgs.withIndex().firstOrNull { (i, img) -> i > writes[0] && r.subs(img).any { r.isSub(it, 26, 5) } }?.index ?: -1
            assertTrue(settleAt in writes[0] until writes[1], "the second piece after the burst's SETTLE: SETTLE at $settleAt, pieces at $writes")
            assertTrue(imgs.any { img -> r.subs(img).any { r.isCommit(it, fired!!) } }, "the notch COMMITted")
            assertTrue(r.notes("snapback").isEmpty(), "no snap-back: ${r.notes("snapback")}")
            r.assertOracle("after the re-stage")
        } finally { r.close() }
    }

    /** The cache byte ranges a mode-19 (or deflated mode-25) image writes; empty for any other image. */
    private fun writeRanges(r: Rig, img: ByteArray): List<IntRange> {
        val body = when (img[0].toInt() and 0x7F) {
            19 -> img.copyOfRange(1, img.size)
            25 -> java.util.zip.Inflater().run { setInput(img, 1, img.size - 1); val o = java.io.ByteArrayOutputStream(); val b = ByteArray(4096)
                while (!finished()) { val n = inflate(b); if (n == 0 && (needsInput() || needsDictionary())) break; o.write(b, 0, n) }; end(); o.toByteArray() }
            else -> return emptyList()
        }
        val out = ArrayList<IntRange>(); var i = 0
        while (i + 4 <= body.size) { val off = r.u16(body, i) * 4; val n = r.u16(body, i + 2); out += off until off + n; i += 4 + n }
        return out
    }

    /** The first read range (§5.6) of the wrap's pan down from the last row, as Main's staging now plans it. */
    private fun wrapRange(r: Rig): IntRange = r.onLoop {
        val ms = r.shell.mainBinding.staging!!
        val pan = ms.wrapPan(+1)!!
        pan.program.firstRange(ms.wrapParams(pan, ms.rows)!!)
    }

    /** Build SPLIT on 0.81, B082 (the wrap's own record): a wrap whose edge report comes as a 256 KiB re-stage's piece that
     *  meets the wrap's read range goes out sends no PLAY (what it reads may be part-written): the edge goes as today, the
     *  row moves to the first, the re-stage's later pieces wait for the burst and it is booked after. At rest belief
     *  equals both lenses. K1-2 stage 1: the re-stage is a band form's move (a row form's change withdraws the list at
     *  256 KiB too, so no wrap fires while its pieces go); its pieces — a spare band slot and the item word — lie inside
     *  the wrap's read range (from the wrap's record to its program), as every band move's do. */
    @Test
    fun aWrapBetweenARestagesPiecesDrawsTheWholeStrip_SPLIT2(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            walkTo(r, r.list().count - 1)
            val regionStart = CfwModes.mainRegion(256 * 1024, true).first
            val range = wrapRange(r)
            val at = r.right.size
            var fired = false
            r.onAnyImage = { a, img -> if (a == Arm.RIGHT && !fired && r.cacheWrite(img) && writeRanges(r, img).let { w -> w.isNotEmpty() && w.first().first >= regionStart &&
                w.any { it.first <= range.last && range.first <= it.last } }) { fired = true; r.fire(CfwModes.EV_UPROLL) } }
            r.rows[3].s = r.rows[3].s.copy(detail = "the detail of 3, longer now")
            r.shell.services.requestRender(r.rows[3])
            r.until("the wrap fired as a re-stage piece meeting its range went out", 15_000) { fired }
            r.onAnyImage = null
            r.until("the re-stage booked", 15_000) { r.notes("mainstage").any { it.startsWith("row 3 re-staged") } }
            assertTrue(wrapBatch(r, at) == null, "no wrap PLAY while the re-stage was in pieces")
            assertTrue(r.notes("snapback").last().let { "cause=edge" in it && "wrap=play" !in it }, r.notes("snapback").last())
            delay(r.q() + 200); r.settle("after")
            assertEquals(0, r.row(), "the wrap landed on the first row")
            assertTrue(r.notes("divergence").isEmpty(), "${r.notes("divergence")}")
            r.assertOracle("after the wrap and the re-stage")
        } finally { r.close() }
    }
    // ---------------------------------------------------------------------------------------------- the focused read of G4
    /** The focused read's L1: a throw from the wrap's END paint (after the LEFT lens's) is said once and keyframes — the
     *  half-applied END on LEFT is re-grounded — and the batch path answers with the DISCARD: no PLAY, no repeat at every
     *  pump, the row at the first, belief equal to both lenses. */
    @Test
    fun aThrowFromTheWrapsEndPaintKeyframesOnce_L1(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            walkTo(r, r.list().count - 1)
            val at = r.right.size
            r.onLoop { r.shell.wrapPaintFailsForTest = true }
            r.fire(CfwModes.EV_UPROLL)
            r.until("the keyframe") { r.right.drop(at).any { (it[0].toInt() and 0x7F) == 6 || r.subs(it).any { s -> (s[0].toInt() and 0x7F) == 6 } } }
            delay(r.motionMs() + r.q() + 300)
            r.onLoop { r.shell.wrapPaintFailsForTest = false }
            r.settle("after")
            assertEquals(1, r.notes("assemble-error").count { "the wrap's END" in it }, "said once: ${r.notes("assemble-error")}")
            assertTrue(wrapBatch(r, at) == null, "no wrap PLAY")
            assertEquals(0, r.row(), "the edge moved the row as today")
            r.assertOracle("after the failed wrap")
        } finally { r.close() }
    }

    /** The focused read's W2: a cover over Main (a notice) asks the rules nothing before they know the front — no
     *  `snapback` record, no DISCARD in the cover's frame. */
    @Test
    fun aCoverOverMainWritesNoSnapbackRecord_W2(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            notch(r, CfwModes.EV_UPROLL, "a notch first")
            delay(r.q() + 300); r.settle("the burst closed")
            val at = r.right.size
            val snaps = r.notes("snapback").size
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("TEST · a", "t", "a notice over Main", "12:00"))
            r.until("the notice up") { r.onLoop { r.shell.notificationsUpForTest() } }
            delay(300); r.settle("under the notice")
            assertEquals(snaps, r.notes("snapback").size, "no snap-back record: ${r.notes("snapback").takeLast(2)}")
            assertTrue(r.right.drop(at).none { img -> r.subs(img).any { r.isSub(it, 26, 4) } }, "no DISCARD in the cover's frames")
        } finally { r.close() }
    }

    /** The focused read's W1 (ruled): with `Frames` fixed, the measured period never goes below the staging's own — 600 ms
     *  over 10 frames is P 62 — however fast the steps measure. */
    @Test
    fun aFixedFramesPeriodIsNeverLoweredBelowItsOwn_W1(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            val m = marks(r)
            r.shell.updateSettings { it.copy(scrollLength = 600, frames = 10) }
            remade(r, "600 ms, 10 frames", m)
            assertEquals(62, r.onLoop { r.shell.periodInForceForTest() }, "614 ticks over 10 frames")
            for (a in Arm.entries) r.sim.motionForTest(a).stepUsForTest = 30_000L
            repeat(Shell.PERIOD_WINDOW + 2) { i ->
                r.fire(if (i % 2 == 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)
                delay(r.motionMs() + r.q() + 300); r.settle("fast notch $i")
            }
            delay(Shell.PERIOD_QUIET_MS + 1_000); r.settle("5 s with no input")
            assertEquals(62, r.onLoop { r.shell.periodInForceForTest() }, "never below the period his length and frames gave")
        } finally { r.close() }
    }

    /** B082 (the split on 0.81): a re-stage that comes due while the wrap's held wait still runs — past the burst's lone
     *  SETTLE — sends its first piece only once that wait has ended: a cache write meeting a drawing instance's read range
     *  closes it (§5.6). B083 fix stage (item 7; the focused read's area 6): a pin that bites. Over the per-packet link rig
     *  the wrap's PLAY leaves behind a stall of [stall] ms on the downlink (a frame of several KB in flight when the edge's
     *  report comes; his 0.81 journal: the wrap started 332–442 ms after the edge's report, measured), so the wrap draws
     *  after the burst's SETTLE; a band-only row change is owed meanwhile. The first piece must reach RIGHT with the wrap's
     *  instance no longer drawing; without the gate it goes at the SETTLE, once the PLAY is acknowledged, and meets it. */
    @Test
    fun aRestageWaitsForTheWrapsMotionNotJustItsBurst_B082(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, link = 20L to 30L)
        val stall = 400L
        try {
            r.up()
            walkTo(r, r.list().count - 1)
            val regionStart = CfwModes.mainRegion(256 * 1024, true).first
            fun firstOff(img: ByteArray): Int = when (img[0].toInt() and 0x7F) {
                19 -> r.u16(img, 1) * 4
                25 -> java.util.zip.Inflater().run { setInput(img, 1, img.size - 1); val out = ByteArray(4); inflate(out); end(); r.u16(out, 0) * 4 }
                else -> -1
            }
            val t0 = System.currentTimeMillis()
            var playAt = 0L; var pieceAt = 0L; var pieceBytes = 0
            var wrapDrawing: String? = null
            r.onAnyImage = { a, img -> if (a == Arm.RIGHT) {
                if (playAt == 0L && r.subs(img).any { isWrapPlay(it) }) playAt = System.currentTimeMillis()
                if (pieceAt == 0L && r.cacheWrite(img) && firstOff(img) >= regionStart) {
                    pieceAt = System.currentTimeMillis(); pieceBytes = img.size
                    r.sim.motionForTest(Arm.RIGHT).slots[ListStaging.WRAP_SLOT]?.takeIf { it.drawing && it.d < it.T }?.let { wrapDrawing = "step ${it.d} of ${it.T}" } } } }
            (r.t as LinkRig).stallNextMs = stall
            r.fire(CfwModes.EV_UPROLL)                                       // the wrap
            r.until("the wrap's PLAY decided") { r.notes("local").any { "went ahead of its frame" in it } }
            r.rows[3].s = r.rows[3].s.copy(line = "summary 33")                // a band-only row change owed now
            r.shell.services.requestRender(r.rows[3])
            r.until("the re-stage's first piece", 15_000) { pieceAt != 0L }
            r.onAnyImage = null
            println("B082-pin: stall $stall ms; the PLAY on RIGHT +${playAt - t0} ms, the first piece ($pieceBytes B) +${pieceAt - t0} ms; " +
                "Q ${r.q()} ms, the motion ${r.motionMs()} ms; ${wrapDrawing ?: "the wrap no longer drawing"}")
            assertTrue(playAt - t0 >= stall, "the shape: the PLAY reached RIGHT behind the stall (+${playAt - t0} ms)")
            assertTrue(wrapDrawing == null, "the first piece reached RIGHT while the wrap drew ($wrapDrawing)")
            delay(r.q() + 300); r.settle("after")
            r.assertOracle("after the wrap and the re-stage")
        } finally { r.close() }
    }
    /** His look at 0.81 (the wrap's flush seemed to leave 180–590 ms after it was decided): the wrap's flush leaves the
     *  lane at once however busy the loop is after it — a 300 ms load on the loop right after the submit — measured by the
     *  transport's own times in the `done` row (`took`, `wrote`), not by the row's `t`, which is when the loop got to it. */
    @Test
    fun theWrapsFlushLeavesWhileTheLoopIsBusy_BI(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, kib256 = true)
        try {
            r.up()
            walkTo(r, r.list().count - 1)
            r.onLoop { r.shell.afterWrapSubmitForTest = { Thread.sleep(300) } }
            r.fire(CfwModes.EV_UPROLL)
            delay(r.motionMs() + r.q() + 600)
            r.onLoop { r.shell.afterWrapSubmitForTest = null }
            r.settle("after")
            val lines = Files.readAllLines(r.journal).map { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
            fun JsonObjectLong(o: kotlinx.serialization.json.JsonObject, k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            val note = lines.lastOrNull { (it["detail"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.contains("went ahead of its frame as flush") == true }
            assertTrue(note != null, "the wrap went ahead: ${r.notes("local").takeLast(3)}")
            val detail = (note!!["detail"] as kotlinx.serialization.json.JsonPrimitive).content
            val id = Regex("as flush (\\d+)").find(detail)!!.groupValues[1].toLong()
            val done = lines.last { (it["ev"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "done" && JsonObjectLong(it, "id") == id }
            val decided = JsonObjectLong(note, "t")!!
            val took = JsonObjectLong(done, "took")!!; val wrote = JsonObjectLong(done, "wrote")!!; val handled = JsonObjectLong(done, "t")!!
            println("BI-wrap-lane: took +${took - decided} ms, wrote +${wrote - decided} ms, done row +${handled - decided} ms after the decision")
            assertTrue(took - decided < 60 && wrote - decided < 80, "the lane took it at once: took +${took - decided}, wrote +${wrote - decided}")
            assertTrue(handled - decided >= 250, "the done row waited for the loaded loop (+${handled - decided}): the row's t is no send time")
        } finally { r.close() }
    }
    /** B083 fix stage (item 2; the focused read's 1, shown in this shell): over the slow link, k notches past the end 60 ms
     *  apart — all edges, the wrap's DISCARD has not landed — and one notch more once the later edges' DISCARD has landed,
     *  or 740 ms after the first at the latest. The DISCARD now goes when the link has room (its frame still waits behind
     *  the wrap; it lands about +600 ms here), so that notch fires from the burst's row: the row at rest is the ring's, k.
     *  With the DISCARD held for the wrap's wait it went with the frame and landed about +860 ms, the notch fired from the
     *  wrap's row 0 and was adopted at the burst's row: k = 2 ended on row 1, k = 3 on row 1 (a forward notch moved the
     *  row back). */
    @Test
    fun aNotchInsideTheWrapsWaitAfterAnEdgeBurst_B083F2(): Unit = runBlocking {
        for (k in listOf(2, 3)) {
            val r = Rig(Taps.REPORT, link = 20L to 30L, linkLatency = 250)
            try {
                r.up()
                walkTo(r, r.list().count - 1)
                val img0 = r.right.size
                val t0 = System.currentTimeMillis()
                repeat(k) { r.fire(CfwModes.EV_UPROLL); delay(60) }
                while (System.currentTimeMillis() < t0 + 740 && r.sim.bindForTest(Arm.RIGHT).lists[0].cursor != k - 1) delay(4)
                val firedAt = System.currentTimeMillis() - t0
                val lensCursor = r.sim.bindForTest(Arm.RIGHT).lists[0].cursor
                val rowBefore = r.row()
                val wrapDrawing = r.sim.motionForTest(Arm.RIGHT).slots[ListStaging.WRAP_SLOT]?.drawing == true
                r.fire(CfwModes.EV_UPROLL)
                delay(r.motionMs() + r.q() + 1500); r.settle("after k=$k")
                val told = r.right.drop(img0).flatMap { r.subs(it) }.filter { it[0].toInt() == 26 && it[1].toInt() in 3..5 && it.size >= 8 }
                    .map { (if (it[1].toInt() == 3) "C" else if (it[1].toInt() == 4) "D" else "S") + ((it[6].toInt() and 0xFF) or ((it[7].toInt() and 0xFF) shl 8)) }
                println("B083F2 k=$k: the extra notch at +$firedAt ms, the lens's cursor $lensCursor, the phone's row $rowBefore, the wrap drawing $wrapDrawing; " +
                    "at rest row ${r.row()} (the ring says $k); told $told; the motion ${r.motionMs()} ms")
                assertEquals(k, r.row(), "k=$k: the row at rest is the ring's")
                r.assertOracle("after k=$k and the extra notch")
            } finally { r.close() }
        }
    }

    /** B083 fix stage (item 3; the focused read's 3): a drag on the phone's mirror of Main — three notches 25 ms apart
     *  through `injectInput`, no lens firing — is no ring event: each is handled at once as today, nothing counts in u, no
     *  table repair goes. Read as the ring's, each was an unreported scroll message — counted in u with a `lost-report`
     *  snap-back — and the table repair re-sent the scroll SETs. */
    @Test
    fun aDragOnTheMirrorIsHandledAtOnce_B083F3(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.up()
            walkTo(r, 1)
            val repairs0 = r.notes("repair").size
            repeat(3) { r.t.injectInput(EvenHubMsg.EV_SCROLL_BOTTOM); delay(25) }
            r.until("the drag applied at once, inside Q (${r.q()} ms)", 350) { r.row() == 4 }
            delay(r.q() + 300); r.settle("after the drag")
            assertEquals(4, r.row(), "three notches, three rows")
            val c = r.onLoop { r.shell.localCountsForTest() }
            assertEquals(null, c["unreported"], "nothing counted in u: $c")
            assertEquals(repairs0, r.notes("repair").size, "no table repair: ${r.notes("repair").drop(repairs0)}")
            r.assertOracle("after the drag")
        } finally { r.close() }
    }

    /** B083 fix stage (item 3): under `Taps · glasses`, with RIGHT known to hold the tap entry (a ring tap first), a tap on
     *  the phone's screen acts — before, it was read as a click whose 115 was lost and dropped (`tapdrop no-report`). */
    @Test
    fun aScreenTapActsUnderTapsGlasses_B083F3(): Unit = runBlocking {
        val r = Rig(Taps.GLASSES)
        try {
            r.up()
            tap(r)                                                         // a ring tap: tapsLive, then back on Main
            walkTo(r, 2)
            r.t.injectInput(EvenHubMsg.EV_CLICK)
            r.until("the screen tap opens row 2's window", 5_000) { r.onLoop { r.shell.currentWindowId() } == "w2" }
            assertTrue(r.notes("tapdrop").isEmpty(), "no tap dropped: ${r.notes("tapdrop")}")
        } finally { r.close() }
    }

    // ---------------------------------------------------------------------------------------------- B083-ms (decisions BP)
    /** Decisions BP, pin 1 (his 0.82 start of 13:26:43: the session came up on the shell's own clock screen and nothing of
     *  Main went for 42 s): after a start on the clock, with no input, Main's atlas and its records are written and counted
     *  while the clock shows — and the list is NOT bound: no SET on RIGHT, no entry held there. */
    @Test
    fun aStartOnTheClockStagesMainBehindItAndBindsNothing_BP(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.upOn("SILENT")
            assertTrue(r.onClock(), "the session started on the clock: ${r.notes("power")}")
            r.until("Main's list counted while the clock shows") { r.notes("mainstage").any { it.startsWith("offered") } }
            r.idle("counted behind the clock")
            assertTrue(r.onClock(), "still on the clock: ${r.notes("power")}")
            val fl = r.flushes()
            println("BP-1: ${fl.groupBy { "${it.label}/${it.mode}" }.mapValues { it.value.size }}; mainstage ${r.notes("mainstage").map { it.take(90) }}")
            assertTrue(fl.any { it.label == "ATLAS" && it.mode == "SILENT" }, "atlas chunks while the clock shows: ${fl.map { "${it.label}/${it.mode}" }}")
            val stage = fl.filter { it.label == "STAGE" }
            assertTrue(stage.size >= 12 && stage.all { it.mode == "SILENT" }, "Main's twelve records written while the clock shows: ${stage.map { it.mode }}")
            assertTrue(r.notes("mainstage").any { it.startsWith("staged") && "behind the clock" in it }, "the staging says where: ${r.notes("mainstage")}")
            assertTrue(r.notes("stage").any { "proved" in it }, "a reading of the count proved them: ${r.notes("stage").takeLast(4)}")
            assertEquals(0, r.sim.bindForTest(Arm.RIGHT).field39(), "no entry held on RIGHT while the clock shows")
            assertTrue(r.right.none { img -> r.subs(img).any { r.isSub(it, CfwModes.BIND_MODE, CfwModes.BIND_SET) } }, "no SET sent while the clock shows")
        } finally { r.close() }
    }

    /** Decisions BP, pin 2: the wake (a double tap on the clock) opens Main, and the flush that draws Main binds its list
     *  (the return rule, AF.2: the entries are held on RIGHT once that flush is acknowledged); the first notch after it is a
     *  local fire, adopted and committed — no notch drawn by the phone first, nothing staged again. Before decisions BP the
     *  same sequence starts Main's staging at the wake (the message says when the first STAGE flush went). */
    @Test
    fun theWakeBindsMainInItsFirstFlushAndTheFirstNotchIsLocal_BP(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.upOn("SILENT")
            r.idle("behind the clock")                       // BP: Main staged and counted; before it: the clock's atlas only
            assertTrue(r.onClock(), "on the clock: ${r.notes("power")}")
            val n0 = r.flushes().size
            val tap = System.currentTimeMillis()
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("Main's first flush after the wake acknowledged") { r.flushes().drop(n0).firstOrNull { it.label == "MAIN" }?.let { it.doneT > 0 } == true }
            val held = r.sim.bindForTest(Arm.RIGHT).field39()
            val firstStage = r.flushes().firstOrNull { it.label == "STAGE" }
            assertEquals(r.bound39, held, "the wake's first flush bound Main's list (field 39 at its ack) — the first STAGE flush went " +
                (firstStage?.let { "${it.t - tap} ms from the wake (${it.mode})" } ?: "never"))
            notch(r, CfwModes.EV_UPROLL, "the first notch after the wake")
            val after = r.flushes().drop(n0)
            assertTrue(after.none { it.label == "STAGE" }, "nothing staged after the wake: ${after.map { it.label }}")
            assertEquals(1, r.notes("mainstage").count { it.startsWith("staged") }, "one staging: ${r.notes("mainstage")}")
            assertTrue(r.notes("mainstage").none { it.startsWith("dropped") || it.startsWith("withdrawn") || " re-staged" in it }, "${r.notes("mainstage")}")
        } finally { r.close() }
    }

    /** Decisions BP, pin 3, over a link that takes time ([LinkRig]: 20 ms a packet): the wake comes while one of Main's
     *  pieces is in flight behind the clock. Its frame is submitted with no piece between, and waits in the lane at most
     *  that piece's write; the staging then carries on (the rest of its pieces go after the wake) and is not started again. */
    @Test
    fun aWakeDuringAPieceWaitsAtMostThatPieceAndTheStagingCarriesOn_BP(): Unit = runBlocking {
        val r = Rig(Taps.REPORT, link = 20L to 30L)
        try {
            r.upOn("SILENT")
            // the strip's first piece (a whole STAGE_CHUNK) in flight behind the clock
            r.until("Main's first piece submitted behind the clock", 60_000) { r.flushes().count { it.label == "STAGE" } >= 1 }
            val n0 = r.flushes().size
            val tap = System.currentTimeMillis()
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.bound("after the wake")
            val fl = r.flushes()
            val piece = fl.take(n0).last { it.label == "STAGE" }
            val wake = fl.drop(n0).first { it.label == "MAIN" }
            val pieceMs = piece.wrote - piece.took
            val waited = wake.took - wake.t
            println("BP-3: the wake's frame (${wake.bytes} B) submitted ${wake.t - tap} ms after the tap, taken by the lane ${waited} ms later; the piece " +
                "in flight (${piece.bytes} B) written in $pieceMs ms (done ${piece.doneT - tap} ms after the tap); STAGE after the wake ${fl.drop(n0).count { it.label == "STAGE" }}")
            assertTrue(piece.doneT > tap, "the piece was in flight at the tap (done ${piece.doneT - tap} ms after it)")
            // only the flushes after `n0`: the piece in flight at the tap is among the first `n0`, and its submit can be
            // journaled in the very millisecond `tap` is read (the 0.83 integration: red 1 in 12 runs, that piece at tap + 0 ms)
            assertTrue(fl.drop(n0).none { it.label == "STAGE" && it.t in tap..wake.t }, "no piece submitted between the tap and the wake's frame")
            assertTrue(waited <= pieceMs + 50, "the wake's frame waited $waited ms in the lane; one piece is written in $pieceMs ms")
            assertTrue(fl.drop(n0).any { it.label == "STAGE" && it.mode == "MAIN" }, "the rest of Main's pieces went after the wake")
            assertEquals(1, r.notes("mainstage").count { it.startsWith("staged") }, "carried on, not staged again: ${r.notes("mainstage")}")
            assertTrue(r.notes("mainstage").none { it.startsWith("dropped") }, "${r.notes("mainstage")}")
        } finally { r.close() }
    }

    /** Decisions BP, pin 4: where the wake returns to another window — a window's exclusive screen, whose double tap goes
     *  back to that window (`DESIGN.md` §4.9) — nothing of Main is staged, there or back in the window. */
    @Test
    fun aWakeThatReturnsToAWindowStagesNothingOfMain_BP(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.upOn("EXCLUSIVE", current = "w1")
            assertTrue(r.shell.exclusiveMode, "restored into w1's exclusive screen")
            r.idle("in the exclusive screen", quietMs = 1_500)
            assertTrue(r.flushes().none { it.label == "STAGE" } && r.notes("mainstage").none { it.startsWith("staged") },
                "nothing of Main staged behind the exclusive screen: ${r.notes("mainstage")}")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back in w1") { !r.shell.exclusiveMode && r.onLoop { r.shell.currentWindowId() } == "w1" }
            r.idle("in w1", quietMs = 1_500)
            assertTrue(r.flushes().none { it.label == "STAGE" } && r.notes("mainstage").none { it.startsWith("staged") },
                "nothing of Main staged in w1: ${r.notes("mainstage")}")
        } finally { r.close() }
    }

    /** Decisions BP, pin 5: the firmware's Silent Mode, or a rest, begun while Main is staged behind the clock — no piece
     *  (and no atlas chunk) is submitted after the sleep began. */
    @Test
    fun aSleepBegunWhileStagingBehindTheClockSendsNoFurtherPiece_BP(): Unit = runBlocking {
        for (how in listOf("silent", "rest")) {
            val r = Rig(Taps.REPORT, link = 20L to 30L)
            try {
                r.upOn("SILENT")
                r.until("$how: Main's second piece submitted behind the clock", 60_000) { r.flushes().count { it.label == "STAGE" } >= 2 }
                if (how == "silent") r.sim.setSilent(true) else assertTrue(r.shell.devProbe("rest", "on"), "the probe is the shell's")
                // the journal's own order decides (one writer, in order): the note the sleep writes as it begins, then no
                // STAGE or ATLAS submit after it
                val begun: (String) -> Boolean = if (how == "silent") { l -> "\"kind\":\"silent\"" in l && "\"detail\":\"glasses asleep" in l }
                    else { l -> "\"kind\":\"rest\"" in l && "\"detail\":\"resting" in l }
                r.until("$how: asleep") { r.shell.glassesAsleep && Files.readAllLines(r.journal).any(begun) }
                delay(2_000)
                val lines = Files.readAllLines(r.journal)
                val at = lines.indexOfFirst(begun)
                val staged = lines.withIndex().filter { (_, l) -> "\"ev\":\"submit\"" in l && ("\"label\":\"STAGE\"" in l || "\"label\":\"ATLAS\"" in l) }.map { it.index }
                println("BP-5 $how: ${staged.size} STAGE/ATLAS submit(s), ${staged.count { it < at }} before the sleep's note, ${staged.count { it > at }} after it")
                assertTrue(staged.any { it < at && "STAGE" in lines[it] }, "$how: the sleep began while Main was being staged")
                assertTrue(staged.none { it > at }, "$how: nothing staged after the sleep began: ${staged.filter { it > at }.map { lines[it].take(120) }}")
            } finally { r.close() }
        }
    }

    /** Decisions BP, pin 6: one row's text changed three times while the clock shows, after the staging is counted. Behind
     *  the clock nothing is sent for it — not at the change, not at that row's round-robin turn ([ListBinding.VERIFY_PACE_MS]
     *  after the staging); after the wake the row is re-staged ONCE (its latest text; P9's pace) and Main is not staged
     *  again. */
    @Test
    fun aRowChangedBehindTheClockIsRestagedOnceAfterTheWake_BP(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.upOn("SILENT")
            r.until("Main's list counted while the clock shows") { r.notes("mainstage").any { it.startsWith("offered") } }
            r.idle("counted behind the clock")
            val stagedAt = assertNotNull(r.noteAt("mainstage", "staged"))
            val n0 = r.flushes().size
            for (k in 1..3) {
                r.rows[0].s = r.rows[0].s.copy(line = "summary 0, take $k")
                r.shell.services.requestRender(r.rows[0])
                r.settle("change $k"); delay(300)
            }
            val turn = stagedAt + wm.damage.core.shell.ListBinding.VERIFY_PACE_MS + 1_500 - System.currentTimeMillis()
            if (turn > 0) delay(turn)                         // past row 0's round-robin turn
            r.settle("past row 0's turn")
            assertTrue(r.onClock(), "still on the clock: ${r.notes("power")}")
            val behind = r.flushes().drop(n0).filter { it.label == "STAGE" || it.label == "ATLAS" }
            assertTrue(behind.isEmpty(), "three changes of row 0 behind the clock sent nothing: $behind; ${r.notes("mainstage")}")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("row 0 re-staged after the wake", 15_000) { r.notes("mainstage").any { it.startsWith("row 0 re-staged") } }
            r.bound("after row 0's re-stage")
            delay(wm.damage.core.shell.ListBinding.RESTAGE_PACE_MS + 500); r.settle("a pace later")
            println("BP-6: ${r.notes("mainstage").filter { it.startsWith("row ") || it.startsWith("withdrawn") || it.startsWith("staged") }.map { it.take(100) }}")
            assertEquals(1, r.notes("mainstage").count { it.startsWith("row 0 re-staged") }, "row 0 re-staged once: ${r.notes("mainstage")}")
            assertTrue(r.notes("mainstage").none { it.startsWith("row ") && " re-staged" in it && !it.startsWith("row 0 ") }, "no other row: ${r.notes("mainstage")}")
            assertEquals(1, r.notes("mainstage").count { it.startsWith("staged") }, "no full staging: ${r.notes("mainstage")}")
        } finally { r.close() }
    }

    /** Decisions BP, the second site the reader named: a height set while the clock shows (applied to the shell's layout
     *  only at the wake) — Main is staged again behind the clock for the height the wake brings, and the wake keeps that
     *  staging: nothing dropped, the first flush binds the list. */
    @Test
    fun aHeightSetBehindTheClockIsStagedForAndKeptAtTheWake_BP(): Unit = runBlocking {
        val r = Rig(Taps.REPORT)
        try {
            r.upOn("SILENT")
            r.until("Main's list counted while the clock shows") { r.notes("mainstage").any { it.startsWith("offered") } }
            r.idle("counted behind the clock")
            r.shell.updateSettings { it.copy(heightMode = 416) }
            r.until("staged again behind the clock for 416") { r.notes("mainstage").count { it.startsWith("offered") } == 2 }
            r.idle("counted again behind the clock")
            assertTrue(r.onClock(), "still on the clock: ${r.notes("power")}")
            assertTrue(r.notes("mainstage").any { it.startsWith("dropped — Main's layout changed") }, "${r.notes("mainstage")}")
            val m0 = r.notes("mainstage").size
            val n0 = r.flushes().size
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("Main's first flush after the wake acknowledged") { r.flushes().drop(n0).firstOrNull { it.label == "MAIN" }?.let { it.doneT > 0 } == true }
            assertEquals(r.bound39, r.sim.bindForTest(Arm.RIGHT).field39(), "the wake's first flush bound the list: ${r.notes("mainstage").drop(m0)}")
            r.settle("after the wake")
            assertTrue(r.notes("mainstage").drop(m0).none { it.startsWith("dropped") || it.startsWith("staged") }, "kept at the wake: ${r.notes("mainstage").drop(m0)}")
            r.assertOracle("Main at 416 after the wake")
        } finally { r.close() }
    }

    /** B083-ms: what decisions BP must not change, as bytes — every top-level image either lens is handed, by its hash, in
     *  order: (a) a session whose first screen is Main at stage 4, up to the list bound; (b) a start on the clock at stage 3;
     *  (c) the drawing images of a start on the clock at stage 4 (the clock's own picture). Written to `B083MS_DUMP` when it
     *  is set (run on two trees and compared); passes when unset. */
    @Test
    fun theBytesBPMustNotChange_dump(): Unit = runBlocking {
        val out = System.getenv("B083MS_DUMP")?.let { java.nio.file.Paths.get(it) } ?: return@runBlocking
        fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).take(6).joinToString("") { "%02x".format(it) }
        val lines = ArrayList<String>()
        suspend fun take(name: String, stage: Int, block: suspend (Rig) -> Unit, keep: (ByteArray) -> Boolean = { true }) {
            val r = Rig(Taps.REPORT, stage = stage)
            val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
            r.onAnyImage = { a, img -> if (keep(img)) seen += "$a ${img[0].toInt() and 0xFF} ${img.size} ${sha(img)}" }
            try { block(r) } finally { r.close() }
            lines += "== $name ${seen.size}"; lines += seen
        }
        take("main-first stage 4", 4, { r -> r.up() })
        take("clock-first stage 3", 3, { r -> r.upOn("SILENT"); r.idle("stage 3 on the clock", quietMs = 1_500) })
        take("clock-first stage 4, drawing images", 4, { r -> r.upOn("SILENT"); r.idle("stage 4 on the clock", quietMs = 1_500) },
            keep = { img -> (img[0].toInt() and 0x7F) !in setOf(12, 19, 25) })
        Files.write(out, lines)
    }

    /** 2c (his look at 0.81: edge reports in a burst at Main's wrap): k notches past the end 60 ms apart over a link that
     *  takes time ([LinkRig]: 20 ms a packet down, 30 ms up), so the later notches meet the lens before the wrap's PLAY
     *  does, as on his glasses. Recorded per k (printed `2c-k`; strips of RIGHT's frames under `B083_RENDERS` when set):
     *  the wrap instance's outcome, what closed it, the cursors the lens was told, when the burst's row was first shown. */
    @Test
    fun edgesInABurstPastTheEndOverALink_2c(): Unit = runBlocking { edgeBursts(0, "fast") }

    /** 2c over a slow link (250 ms one-way down besides each packet's 20 ms, his measured wrap start of about 330 ms after
     *  the edge): every later notch of the burst meets the lens before the wrap's PLAY, so all are edges — the wrap draws
     *  whole (the later edges' DISCARDs stop nothing in its slot, their frames wait behind it, AF.7) and the cells held
     *  behind it — the burst's row — go at its last-step report, not at the end of its start allowance. */
    @Test
    fun edgesInABurstPastTheEndOverASlowLink_2c(): Unit = runBlocking { edgeBursts(250, "slow") }

    private suspend fun edgeBursts(latency: Long, name: String) {
        val out = System.getenv("B083_RENDERS")?.let { java.nio.file.Paths.get(it) }
        for (k in listOf(2, 3, 5)) {
            val r = Rig(Taps.REPORT, link = 20L to 30L, linkLatency = latency)
            try {
                try { r.up() } catch (e: Throwable) { println("2c-$k: up failed — stage ${r.notes("stage").takeLast(8)}; faults ${r.allNotes().filter { it.first in setOf("fault", "atlas", "count") }.takeLast(8)}"); throw e }
                walkTo(r, r.list().count - 1)
                val m0 = r.notes("motion").size; val s0 = r.notes("snapback").size; val img0 = r.right.size; val l0 = r.notes("local").size
                val frames = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Gray8>>()
                val t0 = System.currentTimeMillis()
                val sampler = r.scope.launch {
                    var last: Gray8? = null
                    while (true) {
                        val g = r.panel(false)
                        if (last == null || !g.pix.contentEquals(last.pix)) { frames += (System.currentTimeMillis() - t0) to g; last = g }
                        delay(8)
                    }
                }
                repeat(k) { r.fire(CfwModes.EV_UPROLL); delay(60) }
                delay(r.motionMs() + r.q() + 1500); r.settle("after $k")
                sampler.cancel()
                val final = r.panel(false)
                val shownAt = frames.indexOfLast { !it.second.pix.contentEquals(final.pix) }.let { i -> frames.getOrNull(i + 1)?.first }
                val told = r.right.drop(img0).flatMap { r.subs(it) }.filter { it[0].toInt() == 26 && it[1].toInt() in 3..5 && it.size >= 8 }
                    .map { (if (it[1].toInt() == 3) "C" else if (it[1].toInt() == 4) "D" else "S") + ((it[6].toInt() and 0xFF) or ((it[7].toInt() and 0xFF) shl 8)) }
                val wrapLines = r.notes("motion").drop(m0).filter { "slot 1 " in it || "meets open" in it }.map { it.substringBefore(" stepUs").substringAfter("motion ") }
                val early = r.notes("local").drop(l0).firstOrNull { it.startsWith("the wrap's last step came") }
                println("2c-$name-$k: row ${r.row()} (wanted ${k - 1}); the burst's row first shown +${shownAt} ms; told ${told}; wrap ${wrapLines}; " +
                    "snapbacks ${r.notes("snapback").drop(s0).map { it.substringBefore(" mark") }}; RIGHT frames ${frames.size}; ${early ?: "held cells at the allowance's end"}")
                if (out != null) writeStrip(out.resolve("2c-$name-$k-${System.getenv("B083_TAG") ?: "run"}.png"), frames)
                assertEquals(k - 1, r.row(), "the burst's row")
                if (latency > 0) {
                    assertTrue(wrapLines.any { "slot 1" in it && "drew its last step" in it } && wrapLines.none { "slot 1" in it && "closed while drawing" in it },
                        "every later notch an edge: the wrap drew whole — $wrapLines; told $told")
                    assertTrue(early != null, "the held cells went at the wrap's last step: ${r.notes("local").drop(l0).takeLast(4)}")
                }
                r.assertOracle("after $k notches past the end")
            } finally { r.close() }
        }
    }

    /** RIGHT's frames, the list's part (rows 32..351) at half size, one under the other, each with its time in ms as a
     *  run of ticks along its left edge (one tick per 20 ms) — for a reader to see the motion, not for a check. */
    private fun writeStrip(path: java.nio.file.Path, frames: List<Pair<Long, Gray8>>) {
        val y0 = 32; val h = 320; val fw = 320; val fh = h / 2; val gap = 4
        val img = java.awt.image.BufferedImage(fw + 24, frames.size * (fh + gap), java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
        for ((n, f) in frames.withIndex()) {
            val (t, g) = f
            val oy = n * (fh + gap)
            for (y in 0 until fh) for (x in 0 until fw) img.raster.setSample(24 + x, oy + y, 0, g[x * 2, y0 + y * 2])
            for (k in 0 until minOf(fh / 3, (t / 20).toInt())) img.raster.setSample(2 + (k % 4) * 5, oy + (k / 4) * 3, 0, 255)
        }
        java.nio.file.Files.createDirectories(path.parent)
        javax.imageio.ImageIO.write(img, "png", path.toFile())
    }
}

/** B083 (2c, 2d): a start rig whose link takes time — every packet written waits [downMs] before it reaches the glasses
 *  (one packet's air time: ~232 B in 20 ms is about 11.6 KB/s, the order of the phone's measured rate), and every notify
 *  from the glasses — and, through [later], the ring's stock message behind its 115 — reaches the transport [upMs] later,
 *  in order (one queue). The glasses' clock and the engine are the rig's wall clock, as `StartRig`'s. */
private class LinkRig(glass: GlassFirmwareSim, scope: CoroutineScope, c1: java.nio.file.Path, val downMs: Long, val upMs: Long,
                      /** A one-way latency on the way down besides each packet's air time (a pipe: packets in flight together). */
                      val latencyMs: Long = 0) :
    StartRig(glass, scope, instantPacing = false, c1StageFile = c1) {
    private val up = kotlinx.coroutines.channels.Channel<Pair<Long, () -> Unit>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val down = kotlinx.coroutines.channels.Channel<Pair<Long, () -> Unit>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    init {
        onNotify = { arm, p -> later { deliver(arm, p) }; null }
        for (ch in listOf(up, down)) scope.launch { for ((due, f) in ch) { val w = due - System.currentTimeMillis(); if (w > 0) delay(w); f() } }
    }
    fun later(f: () -> Unit) { up.trySend(System.currentTimeMillis() + upMs to f) }
    /** B083 fix stage (item 7): the next packet written to LEFT (the image lane) waits this long first, and every later one
     *  behind it (a frame of several KB in flight ahead of it, in one ordered lane). */
    @Volatile var stallNextMs = 0L
    override suspend fun writeArm(arm: Arm, packet: ByteArray) {
        val stall = stallNextMs
        if (arm == Arm.LEFT && stall > 0) { stallNextMs = 0; delay(stall) }
        delay(downMs)
        if (latencyMs <= 0) { glass.write(arm, packet, nowMs()); return }
        down.trySend(System.currentTimeMillis() + latencyMs to { glass.write(arm, packet, nowMs()) })
    }
}
