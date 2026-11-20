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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.geom.Geometry
import wm.damage.core.gfx.IconKind
import wm.damage.core.local.Taps
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/**
 * Build SPLIT: a staged record goes as pieces of one mode-19 message each (at most `Shell.STAGE_CHUNK` bytes), one per
 * STAGE flush, the next only after the last one's ack and from the idle pump — so a gesture's flush waits behind one
 * piece, never a whole ~20 KB strip. Main's base staging at stage 4 is the subject (its strip is the largest record).
 *
 * Context for the reader: Adam's own glasses on his own display firmware; the phone's shell against the simulator's
 * model of the lenses; nothing contacts a device.
 */
class StagePiecesTest {
    private class Row(id: String, name: String, var s: DamageWindow.Summary) : DamageWindow(id, name, IconKind.FILES) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /** One flush as the shell submitted it: its id, label and the first cache write's offset (STAGE only, else -1). */
    private class Sent(val id: Long, val label: String, val off: Int, val bytes: Int)

    /** The shell's transport, seen: every flush in order; [onStage] before a STAGE flush goes on (its ordinal, 1-based);
     *  [failStage]: STAGE ordinals whose completion the shell sees as not ok. */
    private class Spy(private val inner: Transport) : Transport by inner {
        val sent = java.util.concurrent.CopyOnWriteArrayList<Sent>()
        @Volatile var onStage: ((Int) -> Unit)? = null
        /** Before the shell sees a STAGE flush's completion (its ordinal, 1-based), on the shell's collector. */
        @Volatile var onStageDone: (suspend (Int) -> Unit)? = null
        @Volatile var failStage: Set<Int> = emptySet()
        /** The next STAGE flush whose first write lies at or past this offset is seen as not ok (−1: none). */
        @Volatile var failNextAtOrPast: Int = -1
        private val failIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        private val ordinalOf = java.util.concurrent.ConcurrentHashMap<Long, Int>()
        private var stageN = 0
        override val events: kotlinx.coroutines.flow.Flow<TransportEvent> = inner.events.map { ev ->
            if (ev is TransportEvent.FlushDone) ordinalOf[ev.id]?.let { n -> onStageDone?.invoke(n) }
            if (ev is TransportEvent.FlushDone && ev.id in failIds) ev.copy(ok = false, error = "test: the piece's ack never came") else ev
        }
        /** B083 (S4): every reading the shell asks, in the same order as [sent] (label "READ <its label>"). */
        override suspend fun telemetry(label: String): wm.damage.core.wire.DamageMsg.Telemetry? {
            sent += Sent(-1, "READ $label", -1, 0)
            return inner.telemetry(label)
        }
        override suspend fun submit(flush: FlushRequest): Long {
            val n = if (flush.label == "STAGE") synchronized(this) { ++stageN } else 0
            if (n > 0) onStage?.invoke(n)
            val id = inner.submit(flush)
            if (n > 0) ordinalOf[id] = n
            if (n in failStage) failIds += id
            val w = flush.ops.firstOrNull() as? DisplayOp.CacheWrite2
            val off = if (w == null) -1 else ((w.payload[1].toInt() and 0xFF) or ((w.payload[2].toInt() and 0xFF) shl 8)) * 4
            if (n > 0 && failNextAtOrPast >= 0 && off >= failNextAtOrPast) { failIds += id; failNextAtOrPast = -1 }
            sent += Sent(id, flush.label, off, if (w == null) 0 else w.payload.size)
            return id
        }
    }

    private class Rig(kib256: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-pieces")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x5ff }
        val t: wm.damage.core.transport.CfwTransportBase = if (!kib256) SimTransport(sim, scope, SimTransport.Timing(instant = true))
            else StartRig(sim, scope, c1StageFile = tmp.resolve("c1-stage").also { f ->
                Files.writeString(f, wm.damage.core.transport.LadderRecord(stage = 4, sessionKib = 256).encode()) })
        val spy = Spy(t)
        val rows = (0 until 7).map { Row("w$it", "Win $it", DamageWindow.Summary("summary $it", detail = "detail of $it")) }
        val journal = tmp.resolve("journal.jsonl")
        val shell = Shell(CachedText(FakeText()), spy, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val keeper = ShellKeeper(shell, spy, scope, retryPauseMs = 100)

        init {
            for (row in rows) shell.register(row)
            t.devProbe("c1", "4")
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        }

        fun lines(): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
        fun notes(kind: String): List<String> = lines()
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }
        suspend fun until(what: String, maxMs: Long = 30_000, cond: () -> Boolean) =
            waitUntil(maxMs, 5, { "$what — stage ${notes("stage").takeLast(4)}; mainstage ${notes("mainstage").takeLast(3)}; ${shell.quiescenceReport()}" }, cond)
        suspend fun settle(what: String) = until("$what: settled") { shell.isQuiescent() }
        fun <T> onLoop(block: () -> T): T {
            val out = java.util.concurrent.CompletableFuture<T>()
            shell.services.runOnShell { out.complete(block()) }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        suspend fun bound(what: String) {
            until("$what: Main's list bound on RIGHT") { onLoop { shell.mainBoundForTest().first } != null && sim.bindForTest(Arm.RIGHT).field39() == 0x31 }
            settle(what)
        }
        /** The session up and Main's base staging under way (the atlas live, cached text on, 352, `report`). */
        suspend fun start() {
            keeper.start()
            until("the session drives") { keeper.state == ShellKeeper.State.RUNNING && t.state.value.started }
            settle("start")
            shell.updateSettings { it.copy(heightMode = 352, cachedText = "on", taps = Taps.REPORT) }
        }
        /** Belief equals both lenses' panels, read ON the loop with nothing else pending. */
        suspend fun assertOracle(what: String) {
            var diff: String? = null
            val deadline = System.currentTimeMillis() + 30_000
            while (diff == null && System.currentTimeMillis() < deadline) {
                if (!shell.isQuiescent()) { delay(5); continue }
                diff = shell.sampleIdle {
                    val out = StringBuilder()
                    for (left in listOf(true, false)) {
                        val b = shell.comp.expectedLens(left); val ctx = if (left) sim.left else sim.right
                        loop@ for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                            val p = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                            if (wm.damage.core.gfx.Pack.level(b[x, y]) != (if (x and 1 == 0) p shr 4 else p and 0x0F)) {
                                out.append("${if (left) "LEFT" else "RIGHT"} belief != glass at ($x,$y); "); break@loop }
                        }
                    }
                    out.toString()
                }
            }
            assertEquals("", diff, "$what: belief equals both lenses (null: never held still — ${shell.quiescenceReport()})")
        }
        suspend fun close() { keeper.stop(); scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** Main's strip (the first base record) and its pieces: the STAGE flushes that write inside its slot. */
    private fun stripFlushes(r: Rig): List<Sent> {
        val (off, slot) = r.onLoop { r.shell.mainBinding.staging!!.strip.let { it.off to it.slot } }
        return r.spy.sent.filter { it.label == "STAGE" && it.off in off until off + slot }
    }

    /** A gesture during the base staging: its flush is submitted between two pieces of the strip (the ring scroll
     *  injected as the strip's first piece goes out); every piece is at most one chunk's message. */
    @Test
    fun aGesturesFlushGoesBetweenTwoPiecesOfAStrip_SPLIT(): Unit = runBlocking {
        val r = Rig()
        try {
            r.spy.onStage = { n -> if (n == 1) r.t.injectInput(EvenHubMsg.EV_SCROLL_BOTTOM) }
            r.start()
            r.bound("staged and bound")
            val strip = stripFlushes(r)
            assertTrue(strip.size >= 2, "the strip went in pieces: ${strip.map { it.bytes }}")
            assertTrue(r.spy.sent.filter { it.label == "STAGE" }.all { it.bytes <= Shell.STAGE_CHUNK + 5 }, "every piece one chunk's message at most")
            val i0 = r.spy.sent.indexOf(strip[0]); val i1 = r.spy.sent.indexOf(strip[1])
            val between = r.spy.sent.subList(i0 + 1, i1).filter { it.label != "STAGE" }
            val inputFlushes = r.lines().filter { "\"ev\":\"submit\"" in it && "\"in\":1" in it }.map { it.substringAfter("\"id\":").substringBefore(",").toLong() }.toSet()
            assertTrue(between.any { it.id in inputFlushes }, "the gesture's flush between the strip's first two pieces: between ${between.map { "${it.label}#${it.id}" }}, input flushes $inputFlushes")
            r.assertOracle("after the staging")
        } finally { r.close() }
    }

    /** B083 (the focused read of 0.82, S4): a record in pieces is read by the cache count at its end, as an atlas batch is
     *  — no reading between the strip's first and last piece (each was a RIGHT round trip lengthening the staging), and a
     *  reading after its last before the list is offered. */
    @Test
    fun theCountIsReadAtARecordsEndNotBetweenItsPieces_S4(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            r.bound("staged and bound")
            val strip = stripFlushes(r)
            assertTrue(strip.size >= 2, "the strip went in pieces: ${strip.map { it.bytes }}")
            val log = r.spy.sent.toList()
            val i0 = log.indexOf(strip.first()); val i1 = log.indexOf(strip.last())
            fun counting(s: Sent) = s.label == "READ stage check" || s.label == "READ cache count"
            val between = log.subList(i0, i1).filter { counting(it) }
            assertTrue(between.isEmpty(), "${between.size} reading(s) between the strip's ${strip.size} pieces: ${log.subList(i0, i1 + 1).map { it.label }}")
            assertTrue(log.drop(i1 + 1).any { counting(it) }, "a reading after its last piece: ${log.drop(i1 + 1).map { it.label }}")
            r.assertOracle("after the staging")
        } finally { r.close() }
    }

    /** A record is not named before its last piece's ack: Main's list is offered only after every base record's last
     *  piece was acknowledged and counted; the journal's order says so. */
    @Test
    fun theListIsOfferedOnlyAfterEveryPieceIsAcked_SPLIT(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            r.bound("staged and bound")
            val ls = r.lines()
            val offered = ls.indexOfFirst { "\"kind\":\"mainstage\"" in it && "\"detail\":\"offered" in it }
            val stageIds = r.spy.sent.filter { it.label == "STAGE" }.map { it.id }.toSet()
            val lastDone = ls.indexOfLast { l -> "\"ev\":\"done\"" in l && stageIds.any { "\"id\":$it," in l } }
            assertTrue(offered > lastDone && lastDone >= 0, "offered (line $offered) after the last piece's ack (line $lastDone)")
            // and no record is proven (drawable, countable towards the offer) while it is part-written: no proof between
            // the strip's first piece and its last
            val stage = r.notes("stage")
            val first = stage.indexOfFirst { it.startsWith("Main's strip") && "submitted as flush" in it }
            val n = Regex("""piece 1 of (\d+)""").find(stage[first])!!.groupValues[1].toInt()
            val last = stage.indexOfFirst { it.startsWith("Main's strip: piece $n of $n") }
            assertTrue(n >= 2 && last > first && stage.subList(first, last).none { "proved" in it }, "no proof while the strip was part-written: ${stage.subList(first, maxOf(first, last) + 1)}")
        } finally { r.close() }
    }

    /** A failed piece fails its record: the strip's second piece is not acknowledged — the staging goes, is staged
     *  again from its first piece, and only then offered; nothing named the half record; the oracle holds. */
    @Test
    fun aFailedPieceStagesAgainBeforeAnyOffer_SPLIT(): Unit = runBlocking {
        val r = Rig()
        try {
            r.spy.failStage = setOf(2)
            r.start()
            r.bound("staged again and bound")
            val ms = r.notes("mainstage")
            val firstOffer = ms.indexOfFirst { it.startsWith("offered") }
            assertTrue(r.notes("stage").any { "(piece 2 of" in it && "failed" in it }, "the second piece failed: ${r.notes("stage").take(6)}")
            assertTrue(ms.take(firstOffer).count { it.startsWith("staged") } >= 2 && ms.take(firstOffer).any { it.startsWith("dropped") },
                "the staging went and was staged again before the first offer: $ms")
            r.assertOracle("after the repair")
        } finally { r.close() }
    }

    /** A session end between two pieces leaves no half record: the session ends as the strip's second piece is
     *  acknowledged (its third not yet sent); the next session stages the strip again from its first piece, the old
     *  record's third piece never goes, the list is bound and the oracle holds. */
    @Test
    fun aSessionEndBetweenPiecesLeavesNoHalfRecord_SPLIT(): Unit = runBlocking {
        val r = Rig()
        try {
            val ended = java.util.concurrent.atomic.AtomicBoolean(false)
            r.spy.onStageDone = { n -> if (n == 2 && ended.compareAndSet(false, true)) r.t.restartSession("test: between two pieces") }
            r.start()
            r.until("the session ended between pieces") { ended.get() }
            r.until("a new session drives") { r.keeper.attempts >= 2 && r.keeper.state == ShellKeeper.State.RUNNING && r.t.state.value.started }
            r.bound("staged in the new session and bound")
            // Decided on what the glasses hold, read once. A pump that runs between piece 2's ack and the shell's handling
            // of the session's end may still try the old record's piece 3: the transport's door refuses it ("submit before
            // start()"), or takes it into the ending session's epoch, where the lane drops it as stale or writes it like
            // any flush in flight at a session's end. The 0.82 battery's one red counted that attempt's note. What must
            // hold: the new session writes the strip again from its first piece to its last, and both lenses then hold
            // the phone's copy of every record — no half record.
            val (stripOff, stripSlot) = r.onLoop { r.shell.mainBinding.staging!!.strip.let { it.off to it.slot } }
            val sent = r.spy.sent.filter { it.label == "STAGE" }
            val strip = r.notes("stage").filter { it.startsWith("Main's strip") }
            val lastFirst = sent.indexOfLast { it.off == stripOff }
            val after = sent.drop(lastFirst).filter { it.off in stripOff until stripOff + stripSlot }.map { (it.off - stripOff) / Shell.STAGE_CHUNK }
            assertTrue(sent.count { it.off == stripOff } >= 2 && after == listOf(0, 1, 2), "the strip written again from its first piece to its last: $after; $strip")
            val stale = r.onLoop {
                val ms = r.shell.mainBinding.staging!!
                val out = ArrayList<String>()
                for (arm in Arm.entries) { val cache = (if (arm == Arm.LEFT) r.sim.left else r.sim.right).textureCache ?: continue
                    for (p in listOfNotNull(ms.strip, ms.stripB, ms.wrap) + ms.bands)
                        if (!java.util.Arrays.equals(cache, p.off, p.off + p.encoded.size, p.encoded, 0, p.encoded.size)) out += "$arm ${p.name}" }
                out
            }
            assertTrue(stale.isEmpty(), "both lenses hold the phone's copy of every record: $stale")
            val oldThird = sent.take(lastFirst).filter { it.off == stripOff + 2 * Shell.STAGE_CHUNK }.map { it.id }.toSet()
            val doneOld = r.lines().filter { l -> "\"ev\":\"done\"" in l && oldThird.any { "\"id\":$it," in l } }.map { if ("\"ok\":true" in it) "acked" else "failed" }
            println("SPLIT-session-end: the old record's piece 3 — refused at the door ${strip.count { "could not be submitted" in it }}, taken ${oldThird.size} (${doneOld.joinToString()})")
            r.assertOracle("after the new session's staging")
        } finally { r.close() }
    }
    /** The focused read of B082 (F1): a Main re-stage's piece fails before it is booked — its bytes landed (a lost ack) —
     *  and the row goes back to its old picture before the retry. Nothing names the half-written record: the staging is
     *  dropped and staged again whole, so whenever the list is offered, every record the glasses hold for it equals the
     *  phone's copy, on both lenses (160 KiB: the one strip in place; 256 KiB: the inactive strip and the wrap record). */
    @Test
    fun aFailedRestagePieceWithTheRowBackLeavesNoStaleBytes_F1(): Unit = runBlocking {
        for (kib256 in listOf(false, true)) {
            val r = Rig(kib256)
            try {
                r.start()
                r.bound("staged and bound (${if (kib256) 256 else 160} KiB)")
                val old = r.rows[2].s
                val baseEnd = r.onLoop { r.shell.mainBinding.staging!!.strip.off }
                r.spy.failNextAtOrPast = baseEnd
                r.spy.onStageDone = { _ -> if (r.spy.failNextAtOrPast < 0 && r.rows[2].s !== old) { r.rows[2].s = old; r.shell.services.requestRender(r.rows[2]) } }
                r.rows[2].s = old.copy(line = "summary 2 changed")
                r.shell.services.requestRender(r.rows[2])
                r.until("a re-stage piece failed", 20_000) { r.notes("stage").any { "re-stage" in it && "failed" in it } }
                r.spy.onStageDone = null
                r.until("offered again", 30_000) { r.onLoop { r.shell.mainBoundForTest().first } != null }
                r.settle("after")
                val stale = r.onLoop {
                    val ms = r.shell.mainBinding.staging!!
                    val out = ArrayList<String>()
                    val pics = listOfNotNull(ms.strip, ms.stripB, ms.wrap) + ms.bands
                    for (arm in Arm.entries) { val cache = (if (arm == Arm.LEFT) r.sim.left else r.sim.right).textureCache ?: continue
                        for (p in pics) if (!java.util.Arrays.equals(cache, p.off, p.off + p.encoded.size, p.encoded, 0, p.encoded.size)) out += "$arm ${p.name}" }
                    out
                }
                assertTrue(stale.isEmpty(), "${if (kib256) 256 else 160} KiB: offered with records the phone's copy does not hold: $stale; mainstage ${r.notes("mainstage").takeLast(4)}")
                assertTrue(r.notes("mainstage").any { it.startsWith("dropped — row 2's re-stage failed") }, "the staging dropped for it: ${r.notes("mainstage").takeLast(4)}")
            } finally { r.close() }
        }
    }
}
