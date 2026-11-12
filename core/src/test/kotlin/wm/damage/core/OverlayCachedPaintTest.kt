package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.comp.CanvasShift
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Level
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.DocModel
import wm.damage.core.shell.KeyboardSurface
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MenuSurface
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.Slide
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.LinkState
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg

/**
 * APK 0.64, P1 part b — a shell painter that clears a region of the composed surface and paints
 * it again drops what the frame recorded under that region first (`Shell.retire`, `HANDOFF.md`
 * §75.3 cause B).
 *
 * On 0.63 the context menu's box went out as pixels on every open (52 of 52 first paints at
 * ~2.6 KB on glass): a tap runs the window's handler, which opens and paints the menu, and then
 * the shell repaints the content and the menu again in the same frame, so the content's records
 * lay under the box and the box's own records were there twice. A record that no longer
 * describes the surface makes the compositor's proof refuse the rect (pixels), and one that is
 * there twice ships twice. The same shape was read in the keyboard, the notice box, the wheel,
 * a window opened from Main, the optimistic lens repaint, exclusive mode's entry, a canvas's
 * split fill, and every restore from an under-snapshot.
 *
 * What a pin reads: a GROUP is one delta and the cached draws that follow it up to the next op
 * that is not a draw — what one cached rect ships. "Once" is asserted per group: two planned
 * rects that are each only partly settled may still grow over one string (`planOps`' order is
 * not changed by this build), so a flush-wide "once" is not what `retire` guarantees. A failed
 * proof reads `proof` or `two-planes` (`Compositor.proofMiss`); a stale record that straddles two
 * planes is the second word, so a pin that watches a retire reads the group, or refuses both.
 *
 * Main's lens (the last test) is the same exit read from the other side: a band that could not
 * ship as draws because of what was painted over its text, cured in the painter.
 *
 * Decided by frames, never by a link's timing: where two paints must share a frame the glasses'
 * answers are held ([HeldTransport]), so the flushes in flight fill the window and the pump
 * assembles nothing until the test lets the answers through. Every pin ends with belief equal
 * to glass on both lenses. Each was watched to fail with the one `retire` its comment names
 * removed (`research/mutations-75-p1.json`, the `P1b-` entries).
 */
class OverlayCachedPaintTest {

    /** The CFW choreography over the firmware model, with no link timing; what the glasses answer
     *  can be held back, so the flushes sent meanwhile stay in flight. */
    private class HeldTransport(private val glass: GlassFirmwareSim, scope: CoroutineScope) :
        CfwTransportBase(scope, "held", mirrorSim = glass) {
        override val instant: Boolean get() = true
        /** True while the glasses' answers are kept back. Read and written under [held]'s lock only. */
        private var holding = false
        private val held = ArrayList<Pair<Arm, ByteArray>>()

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {
                    if (kind in setOf("decode", "fid", "session", "msgid", "compressmode", "abort")) emitFault(kind, detail)
                }
                override fun notify(arm: Arm, packet: ByteArray) {
                    // decided and queued in ONE step: an answer that read "held" a moment before a
                    // release must not join the list after the release has emptied it
                    synchronized(held) { if (holding) { held.add(arm to packet); return } }
                    onNotifyPacket(arm, packet)
                }
                override fun panelChanged(arm: Arm) {}
            })
        }

        /** From here on what the glasses answer is kept back, until [release]. */
        fun hold() { synchronized(held) { holding = true } }

        /**
         * Everything the glasses said while held, in the order they said it — and nothing they say
         * meanwhile AHEAD of it: answers stay held until the last kept one has been handed over, so
         * the ack of a flush the shell sends on the first completion queues behind the acks still
         * waiting here.
         *
         * Until the P1 verify pass (2026-11-11) the hold was dropped first and the list delivered
         * after. The shell answers the first completion with a new flush, the model acks it at
         * once, and that ack overtook the two still in the list: the transport read the earlier
         * ack as lost — which is what it is for — failed that flush, re-sent it and raised its
         * notice, and the frame under read held a notice box and the rects beside it. Measured:
         * `notchesInOneFrameLeaveOneLens` repeated in one warm JVM missed 33 runs of 150 (in the 30
         * where the transport's lost-ack line was counted it stood in exactly the 5 runs that
         * missed); the class from a cold JVM missed 2 runs of 20. Delivered in order: 0 of 170
         * warm, 20 class runs of 20.
         */
        fun release() {
            while (true) {
                val next = synchronized(held) { if (held.isEmpty()) { holding = false; null } else held.removeAt(0) } ?: return
                onNotifyPacket(next.first, next.second)
            }
        }

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() { glass.linkReset() }
        override suspend fun writeArm(arm: Arm, packet: ByteArray) { glass.write(arm, packet, nowMs()) }
        override suspend fun onImageDelivered() {
            val l = glass.flags(Arm.LEFT); val r = glass.flags(Arm.RIGHT)
            emitFlags((l.keys + r.keys).associateWith { (l[it] ?: false) || (r[it] ?: false) })
        }
        override fun onMaintenanceTick() {
            glass.tick(nowMs())
            val ok = glass.leaseHeld(Arm.LEFT, nowMs()) && glass.leaseHeld(Arm.RIGHT, nowMs())
            if (!ok && state.value.started) setLease(false, "lease LOST — stock repaints (fail-open)")
            else if (ok) setLease(true, "lease held")
        }
    }

    /**
     * Sees every flush and whether a cache read-back is on its way — and shows the shell a count
     * of flushes in flight that is exact at every pump. The transport takes a slot for a flush on
     * its own coroutine, a moment after `submit` returns; a pump that follows within that moment
     * reads a window with room that is in fact full, which is harmless on a link and makes "these
     * paints share a frame" a matter of thread timing in a test. Here the count rises inside
     * `submit` and falls as the completion passes to the shell.
     *
     * What the shell is shown has three writers on three threads — the link's state as it
     * changes, a completion, a submit — and each one reads the count and the link's state and
     * writes the result INSIDE one lock ([refresh]). As two unguarded steps (until the P1 verify
     * pass, 2026-11-11) a writer that read before another's change and wrote after it could leave
     * the shell a count that was no longer true, with nothing behind it to put it right. Measured
     * with the gap between one writer's read and its write widened by a 30 ms sleep: a fourth
     * flush left while the answers were held in 4 runs of 60, and in 0 of 60 with the same sleep
     * inside the lock. Inferred from one instance as built (1 run of 83, not reproduced): a run
     * that stopped with damage pending, nothing in flight and no pump coming ("did not settle —
     * pending"), which a count left too high explains.
     */
    private class Spy(private val inner: Transport, scope: CoroutineScope) : Transport by inner {
        private val flushes = ArrayList<FlushRequest>()
        private val pending = AtomicInteger(0)
        private val shown = MutableStateFlow(inner.state.value)
        private val showing = Any()
        private fun refresh() = synchronized(showing) { shown.value = inner.state.value.copy(inFlight = pending.get()) }
        init { scope.launch { inner.state.collect { refresh() } } }
        override val state: StateFlow<LinkState> get() = shown
        override val events: Flow<TransportEvent> = inner.events.onEach { ev ->
            if (ev is TransportEvent.FlushDone) { pending.decrementAndGet(); refresh() }
        }
        val checksAsked = AtomicInteger(0)
        val checksAnswered = AtomicInteger(0)
        /** Readings of the cache count (op 1, `Shell.launchLedgerRead`) asked for and answered. P2 gave
         *  the shell this second read; it holds both cache-write lanes as a read-back does, and until
         *  0.64 R1 this rig did not see it (review 1a, RD-N1). */
        val countsAsked = AtomicInteger(0)
        val countsAnswered = AtomicInteger(0)
        /** While set and not complete, a count reading waits before it is even asked (a pin's knob). */
        @Volatile var holdCounts: CompletableDeferred<Unit>? = null
        /** While set and not complete, a read-back the transport HAS answered (and this spy has
         *  counted as answered) waits before the shell's coroutine gets it: the stall between the
         *  transport's return and the run that applies the answer, held open by a pin (RB-7). */
        @Volatile var stallChecks: CompletableDeferred<Unit>? = null
        override suspend fun submit(flush: FlushRequest): Long {
            synchronized(flushes) { flushes.add(flush) }
            val id = inner.submit(flush)
            pending.incrementAndGet()
            refresh()
            return id
        }
        override suspend fun cacheCheck(): DamageMsg.Telemetry? {
            checksAsked.incrementAndGet()
            val t = try { inner.cacheCheck() } finally { checksAnswered.incrementAndGet() }
            stallChecks?.await()
            return t
        }
        override suspend fun telemetry(label: String): DamageMsg.Telemetry? {
            countsAsked.incrementAndGet()
            try { holdCounts?.await(); return inner.telemetry(label) } finally { countsAnswered.incrementAndGet() }
        }
        fun all() = synchronized(flushes) { flushes.toList() }
    }

    /** A list window. [wide] rows pass under a centred overlay; [tag]ged rows also carry a short
     *  string at the left edge that reaches under none. What a tap does is the test's. */
    private class Rows(private val tx: TextRasterizer, private val wide: Boolean, id: String = "rows",
        private val tag: String? = null) : DamageWindow(id, id.replaceFirstChar { it.uppercase() }, IconKind.FILES) {
        private val model = ListModel()
        private val f = FontSpec(Face.SYSTEM, 18)
        private val fB = FontSpec(Face.SYSTEM, 18, bold = true)
        var services: ShellServices? = null
            private set
        var onTap: (ShellServices) -> Unit = {}
        override fun onRegistered(ctx: ShellServices) { services = ctx }
        override fun view(): WindowView = WindowView.ListView(model, { 30 },
            paintRow = { g, i, r, _ ->
                if (tag != null) tx.draw(g, r.x + 4, r.y + 5, "$tag$i", f, Level.BODY)
                if (wide) tx.draw(g, r.x + 40, r.y + 5, "Row $i: To open the door, use the key", f, Level.BODY)
                else if (tag == null) tx.draw(g, r.x + 4, r.y + 5, "R$i", f, Level.BODY)
            },
            paintLens = { g, r, i ->
                if (wide) {
                    tx.draw(g, r.x + 44, r.y + 8, "Row $i", fB, Level.HEAD)
                    tx.draw(g, r.x + 44, r.y + 34, "the detail line of row $i", f, Level.BODY)
                } else tx.draw(g, r.x + 4, r.y + 8, "R$i", fB, Level.HEAD)
            },
            onCommit = { services?.let(onTap) })
        override fun summary() = Summary("30 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /** A document of lines. */
    private class Doc(private val tx: TextRasterizer) : DamageWindow("doc", "Doc", IconKind.FILES) {
        private val model = DocModel()
        private val f = FontSpec(Face.SYSTEM, 18)
        override fun view(): WindowView = WindowView.DocView(model, { 200 }, 24,
            paintLine = { g, i, r -> tx.draw(g, r.x + 8, r.y + 4, "Line $i of the document", f, Level.BODY) },
            onTap = {})
        override fun summary() = Summary("200 lines")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /** A canvas: a header that stays and names the top line, and lines under it that a notch
     *  moves by two (a translation, 48 px: its strip is past `Slide.SPLIT_FILL_PX`). */
    private class Canvas(private val tx: TextRasterizer, private val varied: Boolean = false) : DamageWindow("canvas", "Canvas", IconKind.FILES) {
        @Volatile var top = 100
        private val f = FontSpec(Face.SYSTEM, 18)
        /** [varied]: every line ends in words of its own (seven of them, so a two-line move never puts
         *  a line on one that reads the same). The plain lines differ by a digit, and with a box as tall
         *  as the wheel's panel in the previous frame "no move" then out-votes a two-line move. */
        private fun line(n: Int) = "Line $n: " + (if (varied) TAILS[n % TAILS.size] else TAILS[0])
        override fun view(): WindowView = WindowView.CanvasView(
            paint = { g, r ->
                g.fillRect(r, Level.BG)
                tx.draw(g, r.x + 8, r.y + 4, "top $top", f, Level.HEAD)
                for (i in 0 until (r.h - 32) / 24) tx.draw(g, r.x + 8, r.y + 32 + i * 24, line(top + i), f, Level.BODY)
            },
            onScroll = { d -> top += 2 * d })
        override fun summary() = Summary("a canvas")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
        companion object {
            val TAILS = listOf("a row of the canvas", "by the old quay", "cut from fig wood", "dusk on jet haze",
                "elm and vex mop", "far zinc glow", "gypsum which knob")
        }
    }

    /** A window with a whole-panel mode of three strings, entered from its menu (Music Mode's shape). */
    private class Stage(private val tx: TextRasterizer) : DamageWindow("stage", "Stage", IconKind.FILES) {
        private val model = ListModel()
        private val f = FontSpec(Face.SYSTEM, 18)
        private val fB = FontSpec(Face.SYSTEM, 18, bold = true)
        private var services: ShellServices? = null
        override fun onRegistered(ctx: ShellServices) { services = ctx }
        override fun view(): WindowView = WindowView.ListView(model, { 3 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 4, r.y + 5, "S$i", f, Level.BODY) },
            paintLens = { g, r, i -> tx.draw(g, r.x + 4, r.y + 8, "S$i", fB, Level.HEAD) },
            onCommit = {
                services?.openMenu(MenuSurface.Spec("", listOf(MenuSurface.Item("Whole panel"), MenuSurface.Item("Nothing")),
                    onCommit = { idx -> if (idx == 0) services?.enterExclusive(this) }), this)
            })
        override fun paintExclusive(g: Gray8, safe: Rect, full: Boolean): List<Rect> {
            if (!full) return emptyList()
            for ((i, s) in STRINGS.withIndex()) tx.draw(g, safe.x + 40, safe.y + 80 + i * 60, s, if (i == 0) fB else f, Level.HEAD)
            return listOf(safe)
        }
        override fun summary() = Summary("a stage")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
        companion object { val STRINGS = listOf("NOW SHOWING", "Blackstar", "David Bowie") }
    }

    /** A window with a whole-panel mode whose own repaint is ONE band: a label that stays and a
     *  word that changes at every paint (Music Mode's times line, which reads the position to the
     *  second). A tap enters the mode. */
    private class Ticker(private val tx: TextRasterizer) : DamageWindow("ticker", "Ticker", IconKind.FILES) {
        private val model = ListModel()
        private val f = FontSpec(Face.SYSTEM, 18)
        private var services: ShellServices? = null
        /** Paints of the band so far, and the word the last one drew (written on the loop). */
        @Volatile var paints = 0
        @Volatile var word = ""
        override fun onRegistered(ctx: ShellServices) { services = ctx }
        override fun view(): WindowView = WindowView.ListView(model, { 3 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 4, r.y + 5, "K$i", f, Level.BODY) },
            paintLens = { g, r, i -> tx.draw(g, r.x + 4, r.y + 8, "K$i", f, Level.HEAD) },
            onCommit = { services?.enterExclusive(this) })
        fun band(safe: Rect) = Rect(safe.x + 40, safe.y + 120, 240, 28)
        override fun paintExclusive(g: Gray8, safe: Rect, full: Boolean): List<Rect> {
            val b = band(safe)
            if (full) tx.draw(g, safe.x + 40, safe.y + 60, "THE BAND BELOW", f, Level.HEAD) else g.fillRect(b, Level.BG)
            word = WORDS[paints++ % WORDS.size]
            tx.draw(g, b.x, b.y + 2, LABEL, f, Level.BODY)
            tx.draw(g, b.x + 80, b.y + 2, word, f, Level.HEAD)
            return listOf(if (full) safe else b)
        }
        override fun summary() = Summary("a ticker")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
        companion object {
            const val LABEL = "now at"
            val WORDS = listOf("alpha", "bravo", "charlie", "delta", "echo")
        }
    }

    /** A list window in a face of its own under the title `Rows`: opened from the `rows` window it
     *  draws a face the atlas does not hold yet and NO character new to a face it does hold — a
     *  glyph gained by a live face is a queued extension, which the rig's rest has always seen. */
    private class Odd(private val tx: TextRasterizer, id: String, val font: FontSpec) : DamageWindow(id, "Rows", IconKind.FILES) {
        private val model = ListModel()
        override fun view(): WindowView = WindowView.ListView(model, { 3 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 4, r.y + 5, "odd $i", font, Level.BODY) },
            paintLens = { g, r, i -> tx.draw(g, r.x + 4, r.y + 8, "odd $i", font, Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("three rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /** A window that is only its line on Main. */
    private class Card(id: String, private val s: Summary) : DamageWindow(id, id.replaceFirstChar { it.uppercase() }, IconKind.FILES) {
        private val model = ListModel()
        override fun view(): WindowView = WindowView.ListView(model, { 1 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = s
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /** One delta and the cached draws that follow it: what one cached rect ships. */
    private class Group(val delta: DisplayOp.Delta, val draws: List<DisplayOp>) {
        val texts: List<String> get() = draws.mapNotNull(::textOf)
        /** The draws that occur more than once in this group, as text. */
        fun repeats(): List<String> = draws.groupBy { key(it) }.filter { it.value.size > 1 }.keys.toList()
    }

    /** Both lenses as the shell believes them and as the model's panels hold them: copies, taken together. */
    private class Lenses(val beliefL: Gray8, val beliefR: Gray8, val glassL: Gray8, val glassR: Gray8)

    /** What the flush of one step shipped, with its journal lines. */
    private class Read(val flushes: List<FlushRequest>, val journal: List<String>) {
        val groups: List<Group> get() = flushes.flatMap(::groupsOf)
        val misses: List<String> get() = journal.mapNotNull { Regex("\"cacheMiss\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
        fun shapes() = flushes.map(::shape)
        /** No failed proof of either word (`Compositor.proofMiss`). */
        fun noFailedProof() = misses.none { Regex("(proof|two-planes)=[1-9]").containsMatchIn(it) }
        /** What shipped, in one line of the test's output: the numbers the build's record quotes. */
        fun say(what: String) = println("OverlayCachedPaintTest: $what: ${shapes()} misses $misses")
    }

    private class Rig(val scope: CoroutineScope, contract: Int, base: TextRasterizer) {
        val tmp = Files.createTempDirectory("damage-overlay")
        val sim = GlassFirmwareSim().also { if (contract > 0) it.damageContract = contract }
        val link = HeldTransport(sim, scope)
        val spy = Spy(link, scope)
        val text = CachedText(base)
        val journalPath = tmp.resolve("journal.jsonl")
        val shell = Shell(text, spy, Persistence(tmp.resolve("state.json")), journalPath, scope)

        fun all() = spy.all()
        fun submits(): List<String> = if (!Files.exists(journalPath)) emptyList()
            else Files.readAllLines(journalPath).filter { "\"ev\":\"submit\"" in it }

        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 15_000) {
                if (shell.isQuiescent()) return
                delay(5)
            }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }

        /** A value read ON the loop with nothing queued behind it. */
        suspend fun <T : Any> sample(what: String, block: () -> T): T {
            repeat(2_000) {
                settle(what)
                shell.sampleIdle(block)?.let { return it }
            }
            throw AssertionError("$what: the shell never sat idle for a sample — ${shell.quiescenceReport()}")
        }

        /** What the atlas has done so far: its writes, the faces live, the glyphs gained. */
        private fun atlasMark() = listOf(all().count { it.label == "ATLAS" }, shell.cachedFontsLive.size, shell.atlasExtensionsLive, shell.atlasExtensionsQueued)

        /**
         * Settled, with no cache work on its way and nothing the atlas still owes: two idle samples
         * a moment apart that agree, and between them nothing asked of the glasses' cache.
         *
         * What "on its way" covers (0.64 R1; review 1a, RD-N1 and RB-7), none of it part of the
         * shell's quiescence:
         *  - a read-back (op 4) and a reading of the cache count (op 1) inside the transport — the
         *    spy's asked / answered counts. P2 added the count's reading, which holds both
         *    cache-write lanes as a read-back does; without its counts this returned with a cache
         *    write queued behind one (a face new to the atlas moves no mark here);
         *  - the same two between the transport's return and the run that applies the answer, and a
         *    cache write between its submit and its ack — `Shell.cacheWorkInFlight`, read ON the
         *    loop in the second sample: a count kept in the spy sits on the wrong side of that call;
         *  - a reading the shell will still ask for at the end of a pace (`cacheCountUnjudged`).
         */
        suspend fun calm(what: String) {
            var last = ""
            repeat(1_000) {
                settle(what)
                val a = sample(what) { atlasMark() + all().size }
                delay(15)
                settle(what)
                val asked = spy.checksAsked.get(); val counts = spy.countsAsked.get()
                val (b, working) = sample(what) { (atlasMark() + all().size) to shell.cacheWorkInFlight }
                val answered = spy.checksAnswered.get(); val counted = spy.countsAnswered.get()
                val unjudged = shell.cacheCountUnjudged
                if (a == b && !working &&
                    asked == answered && asked == spy.checksAsked.get() &&
                    counts == counted && counts == spy.countsAsked.get() && unjudged == 0L &&
                    shell.atlasExtensionsLive == shell.atlasExtensionsQueued) return
                last = "marks $a then $b, cache work in flight $working, read-backs $asked asked $answered answered, " +
                    "count readings $counts asked $counted answered, $unjudged unjudged"
            }
            throw AssertionError("$what: the atlas never came to rest — $last — ${shell.quiescenceReport()}")
        }

        suspend fun up(open: String? = "rows") {
            shell.start(); settle("start")
            if (open != null) { shell.services.runOnShell { shell.services.openWindow(open, null) }; settle("open $open") }
            shell.updateSettings { it.copy(cachedText = "on") }
            // decided on the answer that ended the wait ([waitUntil]; 0.64 R1, review 1a RD-7) — as every wait below
            waitUntil(15_000, 5, { "the atlas uploads and the fonts go live" }) { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            calm("atlas up")
        }

        suspend fun gesture(type: Int, what: String) { shell.postGesture(type); calm(what) }

        /** [act], settled, as the flushes and journal lines it added. */
        suspend fun step(what: String, act: suspend () -> Unit): Read {
            val n0 = all().size; val j0 = submits().size
            act(); calm(what)
            return Read(all().drop(n0), submits().drop(j0))
        }

        /**
         * The read of [open]: [open] then [close], over and over, until one whole pass ships no
         * cache write and leaves the faces as they were (a face gains the characters it draws
         * through small cache writes, and until then they are the host's pixels); the NEXT
         * [open] is the read. At most [limit] passes.
         */
        suspend fun read(what: String, limit: Int = 6, close: suspend () -> Unit, open: suspend () -> Unit): Read {
            repeat(limit) {
                val before = sample(what) { atlasMark() }
                open(); calm("$what: packing"); close(); calm("$what: packing, closed")
                if (sample(what) { atlasMark() } == before) return step(what, open)
            }
            fail("$what: the faces were still gaining glyphs after $limit passes")
        }

        /**
         * The paints of several messages in ONE frame, decided by the test: the glasses' answers
         * are held, so the first [inFlight] flushes fill the window and everything handled after
         * them is assembled together by the first slot that comes free. Each of [steps] runs on
         * the loop (it posts gestures, a repaint); the loop is drained after each one, so the runs
         * a handler posts behind itself (a lens repaint, a canvas's owed fill) are handled before
         * the next step. Returns what shipped after the [inFlight] flushes that filled the window.
         */
        suspend fun inOneFrame(what: String, inFlight: Int, vararg steps: () -> Unit): Read {
            // The two marks and the hold are taken in ONE turn of the loop, with nothing queued and no
            // cache work on its way (0.64 R1; review 1a, RB-7): read one after the other from the test
            // thread, an idle tick's chrome flush that fell between the first mark and the hold was
            // sent and acked, and the window then held one flush more than the pin's.
            var at: Pair<Int, Int>? = null
            var tries = 0
            while (at == null && tries++ < 1_000) {
                calm("$what: before")
                at = sample("$what: the hold") {
                    if (shell.cacheWorkInFlight) NOT_HELD else { link.hold(); all().size to submits().size }
                }.takeIf { it !== NOT_HELD }
            }
            val (n0, j0) = at ?: throw AssertionError("$what: cache work was on its way at every try to take the hold — ${shell.quiescenceReport()}")
            for (step in steps) {
                shell.services.runOnShell(step)
                repeat(4) {                                   // a handler's run, the run that run posts, ...
                    val handled = CompletableDeferred<Unit>()
                    shell.services.runOnShell { handled.complete(Unit) }
                    handled.await()
                }
            }
            val sent = all().drop(n0)
            link.release()
            assertEquals(inFlight, sent.size, "$what: $inFlight flushes filled the window and nothing else was sent while the answers were held: ${sent.map(::shape)}")
            calm("$what: released")
            return Read(all().drop(n0 + inFlight), submits().drop(j0 + inFlight))
        }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(640, 480)
            for (y in 0 until 480) for (x in 0 until 640) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        /**
         * Belief against glass, both lenses, from ONE turn of the loop (0.64 R1; review 1a, RD-5).
         * `Compositor.expectedLens` hands out the live shadow, not a copy; walked from the test
         * thread against a panel copied a moment earlier, a flush assembled meanwhile (an idle
         * tick's readout, a notice taking focus after its grace) moved the shadow under the walk,
         * and the difference found was not on the glass. Both shadows and both panels are copied
         * inside one idle sample and compared out here (`OracleWalkTest.assertOracle`'s way).
         */
        suspend fun assertGlassMatchesBelief(what: String) {
            calm(what)
            val s = sample(what) { Lenses(copyOf(shell.comp.expectedLens(true)), copyOf(shell.comp.expectedLens(false)), lens(true), lens(false)) }
            for (left in booleanArrayOf(true, false)) {
                val e = if (left) s.beliefL else s.beliefR; val g = if (left) s.glassL else s.glassR
                var diffs = 0; var first: String? = null
                for (y in 0 until 480) for (x in 0 until 640) {
                    val q = minOf(15, (e[x, y] + 8) / 17) * 17
                    if (q != g[x, y]) { diffs++; if (first == null) first = "($x,$y) belief $q glass ${g[x, y]}" }
                }
                assertEquals(0, diffs, "$what: ${if (left) "L" else "R"} belief != glass ($diffs px, first $first)")
            }
        }

        /** The box of the plane-0 overlay that is up (the menu's, the keyboard's). */
        suspend fun overlayBox(what: String): Rect =
            sample(what) { shell.comp.planes.lastOrNull { it.disparity == 0 }?.rect ?: Rect(0, 0, 0, 0) }
                .also { assertTrue(it.w > 0, "$what: an overlay is up on plane 0") }

        suspend fun close() {
            try { shell.stop() } finally { scope.cancel(); tmp.toFile().deleteRecursively() }
        }
    }

    private fun rig(contract: Int = 2, base: TextRasterizer = Contract2Test.KernText()) =
        Rig(CoroutineScope(SupervisorJob() + Dispatchers.Default), contract, base)

    private fun menuSpec(title: String) = MenuSurface.Spec(title, listOf(
        MenuSurface.Item("Play now"), MenuSurface.Item("Queue next", "3 queued"), MenuSurface.Item("Add to list"),
        MenuSurface.Item("Go to album"), MenuSurface.Item("Go to artist"), MenuSurface.Item("Lyrics"),
        MenuSurface.Item("Rate"), MenuSurface.Item("Remove")), onCommit = {})

    /** The menu's own group in [r]: the one delta whose box is the menu's, with its draws. */
    private fun menuGroup(r: Read, box: Rect, what: String): Group {
        val own = r.groups.filter { it.delta.box == box }
        assertEquals(1, own.size, "$what: the menu's box $box ships as one delta: ${r.shapes()} misses ${r.misses}")
        return own.single()
    }

    // ------------------------------------------------------------------ the menu

    /**
     * The traced case (`HANDOFF.md` §75.3): a tap opens the menu over rows of text, with a title.
     * Both causes at once — the title's box holds its own rule (part a's rule), and the tap's
     * second content paint leaves the rows' records under the box (`paintMenu`'s retire).
     * 0.63 on the simulator: the box as one 301 B delta, no draw, `proof=2`.
     */
    @Test
    fun aMenuOpenedByATapShipsAsOneBaseAndItsDrawsOnce(): Unit = runBlocking { tapOpenedMenu(contract = 2) }

    @Test
    fun aMenuOpenedByATapShipsAsOneBaseAndItsDrawsOnceOnContractOne(): Unit = runBlocking { tapOpenedMenu(contract = 0) }

    private suspend fun tapOpenedMenu(contract: Int) {
        val r = rig(contract)
        try {
            val rows = Rows(r.text, wide = true).also { w -> w.onTap = { it.openMenu(menuSpec("actions"), w) } }
            r.shell.register(rows)
            r.up()
            val open = r.read("the tap that opens the menu", close = { r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel") }) { r.gesture(EvenHubMsg.EV_CLICK, "tap") }
            assertTrue(r.shell.menuIsOpen, "the menu is up")
            open.say("contract $contract, the menu opened by a tap")
            val g = menuGroup(open, r.overlayBox("the menu"), "contract $contract")
            // the title, eight labels, one detail
            assertEquals(10, g.draws.size, "contract $contract: the menu's ten strings ship as draws behind its base: ${open.shapes()} misses ${open.misses}")
            assertTrue(g.repeats().isEmpty(), "contract $contract: no draw twice in the menu's group: ${g.repeats()}")
            if (contract == 2) assertTrue(g.draws.all { it is DisplayOp.DrawText2 && it.xL == it.xR }, "the menu sits on plane 0: one x for both lenses: ${open.shapes()}")
            else assertTrue(g.draws.all { it is DisplayOp.DrawText }, "contract 1 draws flat: ${open.shapes()}")
            assertTrue(g.delta.payload.size < 150, "the base is the box less its text — under half of the 301 B the pixels were: ${g.delta.payload.size} B")
            assertTrue(open.misses.none { Regex("proof=[1-9]").containsMatchIn(it) }, "contract $contract: no proof refused: ${open.misses}")
            r.assertGlassMatchesBelief("contract $contract: the menu, opened by a tap")
            r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel")
            r.assertGlassMatchesBelief("contract $contract: the menu, closed")
        } finally { r.close() }
    }

    /** The title's rule alone (part a's rule, read at the shell): a menu opened off a gesture —
     *  one paint, no content repaint — whose title sits on its rule. 0.63: 301 B, `proof=1`. */
    @Test
    fun aMenuOpenedOffAGestureShipsAsDraws(): Unit = runBlocking {
        val r = rig()
        try {
            val rows = Rows(r.text, wide = true)
            r.shell.register(rows)
            r.up()
            val open = r.read("the menu, opened off a gesture", close = { r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel") }) {
                r.shell.services.runOnShell { r.shell.services.openMenu(menuSpec("actions"), null) }; r.calm("open")
            }
            val g = menuGroup(open, r.overlayBox("the menu"), "off a gesture")
            assertEquals(10, g.draws.size, "the title on its rule, eight labels and a detail ship as draws: ${open.shapes()} misses ${open.misses}")
            assertTrue(open.noFailedProof(), "no proof refused: ${open.misses}")
            r.assertGlassMatchesBelief("the menu, opened off a gesture")
        } finally { r.close() }
    }

    /** The records under the box alone: no title (so no rule in a string's box), a tap, rows of
     *  text under the box. Holds `paintMenu`'s retire by itself. 0.63: 278 B, `proof=2`. */
    @Test
    fun aTapOpenedMenuWithNoTitleOverRowsOfTextShipsAsDraws(): Unit = runBlocking {
        val r = rig()
        try {
            val rows = Rows(r.text, wide = true).also { w -> w.onTap = { it.openMenu(menuSpec(""), w) } }
            r.shell.register(rows)
            r.up()
            val open = r.read("the tap that opens the menu", close = { r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel") }) { r.gesture(EvenHubMsg.EV_CLICK, "tap") }
            val g = menuGroup(open, r.overlayBox("the menu"), "no title")
            assertEquals(9, g.draws.size, "eight labels and a detail ship as draws: ${open.shapes()} misses ${open.misses}")
            assertTrue(g.repeats().isEmpty(), "no draw twice in the menu's group: ${g.repeats()}")
            r.assertGlassMatchesBelief("the menu with no title, opened by a tap")
        } finally { r.close() }
    }

    /** The menu painted twice with nothing under it: no title, rows that stop short of the box.
     *  0.63 shipped each of the nine strings twice. Either of two retires gives "once" here (the
     *  first paint's records lie inside the content AND inside the box), so this pin holds
     *  neither alone: `paintMenu`'s is held by the test above, `paintContentOf`'s by
     *  [aWindowOpenedFromMainDrawsEachStringOnce]. */
    @Test
    fun aTapOpenedMenuOverShortRowsDrawsEachStringOnce(): Unit = runBlocking {
        val r = rig()
        try {
            val rows = Rows(r.text, wide = false).also { w -> w.onTap = { it.openMenu(menuSpec(""), w) } }
            r.shell.register(rows)
            r.up()
            val open = r.read("the tap that opens the menu", close = { r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel") }) { r.gesture(EvenHubMsg.EV_CLICK, "tap") }
            val g = menuGroup(open, r.overlayBox("the menu"), "short rows")
            assertEquals(9, g.draws.size, "eight labels and a detail, each once: ${open.shapes()}")
            assertTrue(g.repeats().isEmpty(), "no draw twice in the menu's group: ${g.repeats()}")
            r.assertGlassMatchesBelief("the menu over short rows")
        } finally { r.close() }
    }

    /**
     * A restore leaves the overlay's own records behind (`closeMenuSurface`'s retire): the menu
     * is scrolled and cancelled in one frame — the daily shape whenever the window is full — so
     * the frame holds the menu's strings over the content the cancel put back. With the records
     * left there the rects under them read `proof` or `two-planes` in the journal for a
     * disagreement that is not the cache's.
     */
    @Test
    fun aMenuScrolledAndCancelledInOneFrameLeavesNoFailedProof(): Unit = runBlocking {
        val r = rig()
        try {
            val rows = Rows(r.text, wide = true).also { w -> w.onTap = { it.openMenu(menuSpec("actions"), w) } }
            r.shell.register(rows)
            r.up()
            r.read("the tap that opens the menu", close = { r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel") }) { r.gesture(EvenHubMsg.EV_CLICK, "tap") }
            assertTrue(r.shell.menuIsOpen, "the menu is up")
            // three scrolls take the three slots; the fourth's paint and the cancel's restore share a frame
            val frame = r.inOneFrame("scroll and cancel", inFlight = 3, {
                repeat(4) { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            })
            assertTrue(!r.shell.menuIsOpen, "the menu is closed")
            assertTrue(frame.flushes.isNotEmpty(), "the cancel's frame shipped")
            assertTrue(frame.noFailedProof(), "nothing under the closed menu was tried against the menu's own strings: ${frame.misses} ${frame.shapes()}")
            r.assertGlassMatchesBelief("the menu, scrolled and cancelled in one frame")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ the keyboard

    private fun keyboardSpec() = KeyboardSurface.Spec("name", onCommit = {})

    /** The keyboard opened by a tap over rows of text (`paintKeyboard`'s retire): its labels ship
     *  as draws, each once in its group. On 0.63 the rows' records lay under the box. */
    @Test
    fun aKeyboardOpenedByATapShipsItsLabelsAsDrawsOnce(): Unit = runBlocking {
        val r = rig()
        try {
            val rows = Rows(r.text, wide = true).also { w -> w.onTap = { it.openKeyboard(keyboardSpec(), w) } }
            r.shell.register(rows)
            r.up()
            val open = r.read("the tap that opens the keyboard", close = { r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel") }) { r.gesture(EvenHubMsg.EV_CLICK, "tap") }
            assertTrue(r.shell.keyboardIsOpen, "the keyboard is up")
            open.say("the keyboard opened by a tap")
            val box = r.overlayBox("the keyboard")
            val own = open.groups.filter { box.contains(it.delta.box) }
            assertTrue(own.isNotEmpty(), "the keyboard's box ships: ${open.shapes()}")
            assertTrue(own.all { it.draws.isNotEmpty() }, "every rect of the keyboard ships as a base and draws: ${open.shapes()} misses ${open.misses}")
            assertTrue(own.sumOf { it.draws.size } >= 26, "the key labels ship as draws: ${own.sumOf { it.draws.size }} in ${open.shapes()}")
            assertTrue(own.all { it.repeats().isEmpty() }, "no draw twice in a group: ${own.map { it.repeats() }}")
            assertTrue(open.noFailedProof(), "no proof refused: ${open.misses}")
            r.assertGlassMatchesBelief("the keyboard, opened by a tap")
        } finally { r.close() }
    }

    /** `closeKeyboardSurface`'s retire: a row move and a cancel in one frame. */
    @Test
    fun aKeyboardMovedAndCancelledInOneFrameLeavesNoFailedProof(): Unit = runBlocking {
        val r = rig()
        try {
            val rows = Rows(r.text, wide = true).also { w -> w.onTap = { it.openKeyboard(keyboardSpec(), w) } }
            r.shell.register(rows)
            r.up()
            r.read("the tap that opens the keyboard", close = { r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel") }) { r.gesture(EvenHubMsg.EV_CLICK, "tap") }
            assertTrue(r.shell.keyboardIsOpen, "the keyboard is up")
            val frame = r.inOneFrame("move and cancel", inFlight = 3, {
                repeat(4) { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            })
            assertTrue(!r.shell.keyboardIsOpen, "the keyboard is closed")
            assertTrue(frame.flushes.isNotEmpty(), "the cancel's frame shipped")
            assertTrue(frame.noFailedProof(), "nothing under the closed keyboard was tried against its labels: ${frame.misses} ${frame.shapes()}")
            r.assertGlassMatchesBelief("the keyboard, moved and cancelled in one frame")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ the notice box

    /**
     * A notice replaced by one of its own thread (`paintNotification`'s retire): the shell
     * repaints the content and then the box in one message, so the rows' records lie under the
     * box. Rows of text DO lie under it here — without them the box proves by part a's rule alone.
     */
    @Test
    fun aReplacedNoticeRepaintsItsBoxAsDraws(): Unit = runBlocking {
        val r = rig()
        try {
            r.shell.register(Rows(r.text, wide = true))
            r.up()
            val bodies = listOf("the first of two bodies", "another line of words")
            var k = 0
            fun notice() = Notifications.Notice("SMS · TEST", "t1", bodies[k++ % 2], "12:00")
            r.shell.postNotice(notice())
            waitUntil(15_000, 10, { "the box took focus after its grace" }) { r.shell.notifications.focused }
            r.calm("the box, focused")
            val replaced = r.read("a notice of the same thread", close = {}) { r.shell.postNotice(notice()); r.calm("replaced") }
            val body = bodies[(k - 1) % 2]
            replaced.say("a replaced notice")
            val box = r.sample("the box") { r.shell.notifications.boxRect(r.shell.layout, false) ?: Rect(0, 0, 0, 0) }
            assertTrue(box.w > 0, "the box is up")
            val own = replaced.groups.filter { box.contains(it.delta.box) }
            assertTrue(own.any { body in it.texts }, "the new body ships as a draw behind a base inside the box $box: ${replaced.shapes()} misses ${replaced.misses}")
            assertTrue(replaced.noFailedProof(), "no proof refused: ${replaced.misses}")
            r.assertGlassMatchesBelief("the replaced notice")
        } finally { r.close() }
    }

    /**
     * A notice painted and then displaced in one frame (`liftNotificationBox`'s retire): the box
     * is repainted for a new message of its thread, and a menu opening in the same frame lifts it
     * off the surface and sends it back to the queue — nothing paints that rect again, and the
     * menu's own box is smaller. Without the drop at the lift the box's source line stays
     * recorded over the content the lift put back.
     */
    @Test
    fun aNoticeRepaintedAndDisplacedByAMenuInOneFrameLeavesNoFailedProof(): Unit = runBlocking {
        val r = rig()
        try {
            r.shell.register(Rows(r.text, wide = false))
            r.up()
            val one = MenuSurface.Spec("", listOf(MenuSurface.Item("Dismiss")), onCommit = {})
            repeat(2) {                                               // the menu's face has drawn its label
                r.shell.services.runOnShell { r.shell.services.openMenu(one, null) }; r.calm("the menu, warm")
                r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel")
            }
            // nine lines of body, three in sight: a notch of the focused box moves them
            fun notice() = Notifications.Notice("SMS · TEST", "t1", (1..9).joinToString(" ") { "part $it of a long message body" }, "12:00")
            r.shell.postNotice(notice())
            waitUntil(15_000, 10, { "the box took focus after its grace" }) { r.shell.notifications.focused }
            r.calm("the box, focused")
            repeat(4) { r.gesture(EvenHubMsg.EV_SCROLL_BOTTOM, "warm") }
            r.shell.postNotice(notice()); r.calm("warm: replaced")
            val frame = r.inOneFrame("a replaced notice, then a menu", inFlight = 3,
                { repeat(3) { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) } },     // three notches of the box: the three slots
                { r.shell.postNotice(notice()) },                                       // the box, painted again for its thread
                { r.shell.services.openMenu(one, null) })                               // lifts it and sends it back to the queue
            assertTrue(r.shell.menuIsOpen && !r.shell.notifications.active, "the menu is up and the box is back in the queue")
            assertTrue(frame.flushes.isNotEmpty(), "the frame shipped")
            assertTrue(frame.noFailedProof(), "nothing where the box stood is tried against the box's own strings: ${frame.misses} ${frame.shapes()}")
            r.assertGlassMatchesBelief("a notice displaced by a menu in the frame that painted it")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ the wheel

    /**
     * A preview behind the wheel (`paintSwitcherFrame`'s retire): the previewed window is painted
     * whole and the wheel's panel over it, so on 0.63 its rows' records lay under the panel and
     * every rect that touched one was refused. A row's short string beside the panel still
     * ships as a draw; no proof is refused for a stale record.
     */
    @Test
    fun aPreviewBehindTheWheelLeavesNoProofMiss(): Unit = runBlocking {
        val r = rig()
        try {
            r.shell.register(Rows(r.text, wide = true, id = "rows", tag = "Ra"))
            r.shell.register(Rows(r.text, wide = true, id = "other", tag = "Ob"))
            r.up("other")
            r.shell.services.runOnShell { r.shell.services.openWindow("rows", null) }
            r.calm("open rows")
            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)       // the chord opens the wheel (§1.3)
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.calm("the wheel")
            assertTrue(r.shell.switcherIsOpen, "the wheel is up")
            // each notch previews the next entry (Main, rows, other): three different pictures, so
            // the faces are at rest only after a whole turn
            val preview = r.read("a notch of the wheel", limit = 9, close = {}) { r.gesture(EvenHubMsg.EV_SCROLL_BOTTOM, "notch") }
            val panel = r.shell.layout.switcherPanel
            assertTrue(preview.misses.none { Regex("proof=[1-9]").containsMatchIn(it) }, "no rect was tried against a record the panel covers: ${preview.misses} ${preview.shapes()}")
            val beside = preview.flushes.flatMap { it.ops }.filterIsInstance<DisplayOp.DrawText2>()
                .filter { it.y >= panel.y && it.y < panel.bottom - 16 && maxOf(it.xL, it.xR) < panel.x - 100 }
            assertTrue(beside.isNotEmpty(), "a short string beside the panel $panel ships as a draw: ${preview.shapes()} misses ${preview.misses}")
            r.assertGlassMatchesBelief("the preview behind the wheel")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ a content repaint

    /**
     * A window opened from Main (`paintContentOf`'s retire): the tap's handler commits the window,
     * which paints it, and the shell paints the content again — on 0.63 every string of the open
     * went out twice (2026-11-10 18:33:15 on glass: eight strings twice behind a 1,970 B base).
     * A list, a document and a canvas.
     */
    @Test
    fun aWindowOpenedFromMainDrawsEachStringOnce(): Unit = runBlocking {
        for (kind in listOf("rows", "doc", "canvas")) {
            val r = rig()
            try {
                r.shell.register(when (kind) { "rows" -> Rows(r.text, wide = true); "doc" -> Doc(r.text); else -> Canvas(r.text) })
                r.up(open = null)                                     // Main, its cursor on the one window
                val open = r.read("$kind opened from Main", close = { r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "back to Main") }) { r.gesture(EvenHubMsg.EV_CLICK, "tap") }
                assertEquals(kind, r.shell.currentWindowId(), "the window is open")
                open.say("$kind opened from Main")
                val drawn = open.groups.filter { it.draws.isNotEmpty() }
                assertTrue(drawn.sumOf { it.texts.size } >= 6, "$kind: the window's strings ship as draws: ${open.shapes()} misses ${open.misses}")
                assertTrue(drawn.all { it.repeats().isEmpty() }, "$kind: no draw twice in a group: ${drawn.map { it.repeats() }.filter { it.isNotEmpty() }}")
                r.assertGlassMatchesBelief("$kind, opened from Main")
            } finally { r.close() }
        }
    }

    /**
     * Notches in one frame (`paintOptimisticLens`'s retire): the lens repaint is posted one
     * message behind its notch, and with the window full several land before anything is
     * assembled — the slow link's ordinary case. On 0.63 the lens band then held every repaint's
     * strings.
     */
    @Test
    fun notchesInOneFrameLeaveOneLens(): Unit = runBlocking {
        val r = rig()
        try {
            r.shell.register(Rows(r.text, wide = true))
            r.up()
            repeat(3) { r.gesture(EvenHubMsg.EV_SCROLL_BOTTOM, "warm"); r.gesture(EvenHubMsg.EV_SCROLL_TOP, "warm back") }
            // three notches take the three slots; their three lens repaints wait in one frame
            val frame = r.inOneFrame("three notches", inFlight = 3, { repeat(3) { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) } })
            val lens = r.shell.layout.lens
            val own = frame.groups.filter { it.delta.box.overlaps(lens) && it.delta.box.y >= lens.y && it.delta.box.bottom <= lens.bottom }
            assertTrue(own.any { g -> g.texts.any { it.startsWith("Row 3") } }, "the lens band ships as a base and the draws of the row it stopped on: ${frame.shapes()} misses ${frame.misses}")
            assertTrue(own.all { it.repeats().isEmpty() }, "no draw twice in the lens's group: ${own.map { it.repeats() }.filter { it.isNotEmpty() }} in ${frame.shapes()}")
            assertTrue(frame.noFailedProof(), "no proof refused: ${frame.misses} ${frame.shapes()}")
            r.assertGlassMatchesBelief("three notches in one frame")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ whole-surface paints

    /**
     * Exclusive mode entered from a menu commit, Music Mode's shape (`paintExclusiveFull`'s
     * retire): the commit's handler enters the mode, which paints the whole panel, and the shell's
     * commit path then composes the content — which in that mode is the whole panel again. On
     * 0.63 the entry shipped each string twice.
     */
    @Test
    fun anExclusiveEntryFromAMenuCommitDrawsEachStringOnce(): Unit = runBlocking {
        val r = rig()
        try {
            r.shell.register(Stage(r.text))
            r.up("stage")
            val entry = r.read("the commit that enters the whole-panel mode",
                close = { r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "leave") }) {
                r.gesture(EvenHubMsg.EV_CLICK, "the menu"); r.gesture(EvenHubMsg.EV_CLICK, "commit")
            }
            assertTrue(r.shell.exclusiveMode, "the mode is on")
            entry.say("the whole-panel mode entered from a menu")
            val whole = entry.flushes.filter { it.label == "EXCLUSIVE" }
            val drawn = whole.flatMap(::groupsOf)
            for (s in Stage.STRINGS) assertEquals(1, drawn.sumOf { g -> g.texts.count { it == s } }, "'$s' ships as one draw: ${whole.map(::shape)} misses ${entry.misses}")
            assertTrue(drawn.all { it.repeats().isEmpty() }, "no draw twice in a group: ${drawn.map { it.repeats() }.filter { it.isNotEmpty() }}")
            r.assertGlassMatchesBelief("the whole-panel mode, entered from a menu")
        } finally { r.close() }
    }

    /**
     * Silent mode entered in the frame that painted Main (`enterSilent`'s retire — one of the four
     * `SilentMode.paintAll` sites): the silent surface is black but for its clock, and every rect
     * of it would be tried against the strings Main had just recorded there.
     */
    @Test
    fun silentEnteredInTheFrameThatPaintedMainLeavesNoFailedProof(): Unit = runBlocking {
        val r = rig()
        try {
            r.shell.register(Rows(r.text, wide = true))
            r.up(open = null)                                         // Main
            repeat(2) { r.gesture(EvenHubMsg.EV_SCROLL_BOTTOM, "warm"); r.gesture(EvenHubMsg.EV_SCROLL_TOP, "warm back") }
            val frame = r.inOneFrame("a repaint of Main, then silent", inFlight = 3, {
                repeat(3) { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }      // the three slots
                r.shell.requestRepaint()                              // Main painted whole: its strings are this frame's records
                r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // back from Main is silent mode
            })
            val silent = frame.flushes.filter { it.label == "SILENT" }
            assertTrue(silent.isNotEmpty(), "the silent surface shipped: ${frame.shapes()}")
            assertTrue(silent.none { f -> f.ops.any(::isDraw) }, "nothing of Main is drawn onto the silent surface: ${silent.map(::shape)}")
            assertTrue(frame.noFailedProof(), "the silent surface is not tried against Main's strings: ${frame.misses} ${frame.shapes()}")
            r.assertGlassMatchesBelief("silent, entered in the frame that painted Main")
        } finally { r.close() }
    }

    /**
     * RB-3: two repaints of one band of the whole-panel mode in ONE frame (`paintExclusiveDelta`).
     * The window fills the band and draws it again; nothing dropped what the earlier paint of the
     * same frame recorded there, so the band held both paints' records: a string that changed
     * left its old ink in the proof's surface where the truth is black (`proof`, for a
     * disagreement that is not the cache's, and the band as pixels), and one that did not change
     * shipped twice. The shell now drops, after the window's paint, the OLDER records under each
     * rect the paint returned.
     * A repaint that is not a gesture's may hold two of the three slots: the first two repaints take
     * them, the third and fourth share the frame. In an anti-aliased face (`SoftText`): in the
     * rig's solid-glyph face one word's ink can lie wholly inside the next one's and prove by
     * accident. Mutation watched: the drop removed.
     */
    @Test
    fun twoRepaintsOfAWholePanelModesBandInOneFrameLeaveOneSetOfRecords(): Unit = runBlocking {
        val r = rig(base = SoftText())
        try {
            val w = Ticker(r.text)
            r.shell.register(w)
            r.up("ticker")
            r.gesture(EvenHubMsg.EV_CLICK, "enter the mode")
            assertTrue(r.shell.exclusiveMode, "the mode is on")
            // every word has been drawn, and the faces have what they draw
            repeat(2 * Ticker.WORDS.size) { r.shell.services.requestRender(w); r.calm("warm") }
            val one = r.step("one repaint") { r.shell.services.requestRender(w) }
            val band = w.band(r.shell.layout.safe)
            assertTrue(one.groups.any { it.delta.box.overlaps(band) && w.word in it.texts }, "the shape guard: a repaint of the band ships its word as a draw: ${one.shapes()} misses ${one.misses}")
            val p0 = w.paints
            val frame = r.inOneFrame("four repaints of the band", inFlight = 2, { repeat(4) { r.shell.services.requestRender(w) } })
            assertEquals(p0 + 4, w.paints, "the band was painted four times")
            frame.say("two repaints of one band in one frame")
            assertTrue(frame.flushes.isNotEmpty(), "the frame shipped")
            assertTrue(frame.noFailedProof(), "the band is not tried against the earlier paint's strings: ${frame.misses} ${frame.shapes()}")
            val own = frame.groups.filter { it.delta.box.overlaps(band) }
            // (the label did not change, so its cells are not sent at all: what ships is the word's rect)
            val words = own.flatMap { it.texts }.filter { it in Ticker.WORDS }
            assertEquals(listOf(w.word), words, "the band ships the last paint's word as one draw, and no earlier paint's: ${frame.shapes()}")
            assertTrue(own.all { it.repeats().isEmpty() }, "no draw twice in a group: ${own.map { it.repeats() }.filter { it.isNotEmpty() }} in ${frame.shapes()}")
            r.assertGlassMatchesBelief("two repaints of one band in one frame")
        } finally { r.close() }
    }

    /**
     * RB-6 (0.64 R1): silent mode with its small notice shown, and a setting that repaints the
     * silent surface arrives (a synced record: the silent clock's size; a restyle and the Cached
     * text row end in the same arm of `composeFullSurface`). That arm cleared the whole panel,
     * painted the clock and returned: the shown box was cleared with the rest and nothing painted
     * it again — it is neither animating nor lifted, which are the pump's two reasons to — so it
     * was gone from the glass up to its 5 s early. Every sibling of the arm (`enterSilent`, the 5 s
     * tick, the quiet furl's end, the whole-panel mode's repaint) paints the box after its clear.
     *
     * One loop sample reads "a notice is up" and "its box is on the surface" together. A run that
     * took longer than the notice's 5 s finds no notice and CANNOT decide: it says so and shows
     * another notice (three times at most), so a slow run does not fail for the tick's reason.
     * Mutation watched: the repaint removed from the arm.
     */
    @Test
    fun aSettingThatRepaintsTheSilentSurfaceLeavesItsShownNoticeUp(): Unit = runBlocking {
        val r = rig()
        try {
            r.shell.register(Rows(r.text, wide = true))
            r.up(open = null)                                         // Main
            val entered = r.step("back from Main is silent mode") { r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }
            assertTrue(entered.flushes.any { it.label == "SILENT" }, "the silent surface is up: ${entered.shapes()}")
            var size = "large"                                        // the default; each attempt moves it
            var decided = false
            for (attempt in 1..3) {
                r.shell.postNotice(Notifications.Notice("SMS · TEST", "t$attempt", "a body", "12:00"))
                r.calm("the small box, shown")
                fun read(): List<Int> {
                    val box = r.shell.notifications.boxRect(r.shell.layout, true) ?: return listOf(0, 0)
                    val g = r.shell.comp.composed
                    var lit = 0
                    for (y in box.y until box.bottom) for (x in box.x until box.right) if (g[x, y] != 0) lit++
                    return listOf(1, lit)
                }
                val before = r.sample("the box before the setting") { read() }
                size = if (size == "medium") "large" else "medium"
                val to = size
                r.shell.updateSettings { it.copy(silentClock = to) }
                r.calm("the silent clock's size changed")
                val after = r.sample("the box after the setting") { read() }
                if (before[0] == 0 || after[0] == 0) {
                    println("OverlayCachedPaintTest: attempt $attempt: the harness outran the notice's 5 s (up before ${before[0]}, after ${after[0]}) — undecided, again")
                    continue
                }
                assertTrue(before[1] > 0, "the shape guard: the small box is on the surface before the setting arrives")
                assertTrue(after[1] > 0, "the notice is still up and its box is still on the surface after the silent surface was repainted (${before[1]} px of it lit before, ${after[1]} after)")
                decided = true
                break
            }
            assertTrue(decided, "undecided, which is not a verdict on the repaint: the harness outran the notice's 5 s three times")
            r.assertGlassMatchesBelief("the silent surface repainted under a shown notice")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ a canvas's split fill

    private suspend fun canvasRig(contract: Int, varied: Boolean = false): Pair<Rig, Canvas> {
        val r = rig(contract)
        val c = Canvas(r.text, varied)
        r.shell.register(c)
        r.up("canvas")
        r.shell.updateSettings { it.copy(slideFill = "split") }
        r.calm("split fills")
        // the header's digits and the lines' are drawn before anything is read
        repeat(4) { r.gesture(EvenHubMsg.EV_SCROLL_BOTTOM, "warm") }
        repeat(4) { r.gesture(EvenHubMsg.EV_SCROLL_TOP, "warm back") }
        return r to c
    }

    /**
     * The strip a canvas's translation exposes goes out blank and is filled a message later
     * (`Slide fill = split`); the canvas has just painted it, so its strings are recorded over
     * black (the `retire(exposed)` in `paintCanvasOf`): the blank ships as a small delta, and the
     * cached path would be tried against the strip's records and refused. Both contracts.
     */
    @Test
    fun aCanvasNotchUnderSplitFillLeavesNoFailedProof(): Unit = runBlocking {
        for (contract in intArrayOf(0, 2)) {
            val (r, c) = canvasRig(contract)
            try {
                val top = c.top
                val notch = r.step("a notch") { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                assertEquals(top + 2, c.top, "the canvas moved two lines")
                assertTrue(notch.flushes.size >= 2 && notch.flushes.first().ops.any { it is DisplayOp.Copy || it is DisplayOp.CopyPair },
                    "contract $contract: the notch is a translation and its strip follows in a second flush: ${notch.shapes()}")
                assertTrue(notch.noFailedProof(), "contract $contract: the blank strip is not tried against the strings the canvas drew there: ${notch.misses} ${notch.shapes()}")
                r.assertGlassMatchesBelief("contract $contract: a canvas notch, split")
            } finally { r.close() }
        }
    }

    /** Two notches back to back under a split fill (the `retire` before a canvas's paint in
     *  `paintCanvasOf`): the second notch first lands the strip the first one owes — a whole
     *  canvas paint — and then paints the canvas again. */
    @Test
    fun twoCanvasNotchesUnderSplitFillDrawEachStringOnce(): Unit = runBlocking {
        val (r, c) = canvasRig(contract = 2)
        try {
            val top = c.top
            val both = r.step("two notches") {
                r.shell.services.runOnShell { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            }
            assertEquals(top + 4, c.top, "the canvas moved four lines")
            val drawn = both.groups.filter { it.draws.isNotEmpty() }
            assertTrue(drawn.isNotEmpty(), "the notches ship draws: ${both.shapes()} misses ${both.misses}")
            assertTrue(drawn.all { it.repeats().isEmpty() }, "no draw twice in a group: ${drawn.map { it.repeats() }.filter { it.isNotEmpty() }} in ${both.shapes()}")
            r.assertGlassMatchesBelief("two canvas notches, split")
        } finally { r.close() }
    }

    /**
     * A notch and the fill it owes in one frame (`applyCanvasFill`'s retire): the notch paints the
     * canvas and leaves its strip owed, and the owed fill — a message behind — paints the whole
     * canvas again before anything is assembled. One notch at a time, each with its fill behind
     * it: the first notch, its fill and the second notch take the three slots; the second's fill,
     * the third notch and the third's fill then wait in one frame.
     */
    @Test
    fun aCanvasNotchAndItsOwedFillInOneFrameDrawEachStringOnce(): Unit = runBlocking {
        val (r, c) = canvasRig(contract = 2)
        try {
            val top = c.top
            val notch = { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            val frame = r.inOneFrame("a notch and its fill", inFlight = 3, notch, notch, notch)
            assertEquals(top + 6, c.top, "the canvas moved six lines")
            val drawn = frame.groups.filter { it.draws.isNotEmpty() }
            assertTrue(drawn.any { g -> g.texts.any { it == "top ${c.top}" } }, "the frame ships the header as a draw: ${frame.shapes()} misses ${frame.misses}")
            assertTrue(drawn.all { it.repeats().isEmpty() }, "no draw twice in a group: ${drawn.map { it.repeats() }.filter { it.isNotEmpty() }} in ${frame.shapes()}")
            r.assertGlassMatchesBelief("a canvas notch and its fill in one frame")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ a canvas that moves under a floating surface (0.64 R1, RB-2 and RB-N1)

    /**
     * THE SHAPE GUARD of the three pins below: does a move of [lines] lines split at all? Read on
     * the loop, the way `paintCanvasOf` reads it: the content as the shell holds it now — the canvas
     * and whatever floats on it — against the canvas [lines] lines on, through the shell's own
     * detector and its floor. Returns the strip the move exposes (the larger one the moved block
     * does not cover, in panel coordinates), or an empty rect when no move is elected. The rig's
     * lines differ by a digit and a floating box breaks the block in two, so a scene that elects no
     * move is possible (a one-line move under a three-line box, modeled) and would make its pin pass
     * with the fix removed.
     */
    private suspend fun stripAMoveExposes(r: Rig, c: Canvas, lines: Int, what: String): Rect = r.sample(what) {
        val content = r.shell.layout.content
        val region = Rect(0, 0, content.w, content.h)
        val prev = Gray8(content.w, content.h).also { it.blit(r.shell.comp.composed, content, 0, 0) }
        val next = Gray8(content.w, content.h)
        val top = c.top
        c.top = top + lines
        try { (c.view() as WindowView.CanvasView).paint(next, region) } finally { c.top = top }
        val moved = CanvasShift.detect(prev, region, next, region, minRun = maxOf(32, content.h / 8))
        if (moved == null) Rect(0, 0, 0, 0) else {
            val dst = moved.second
            val above = Rect(content.x, content.y, content.w, dst.y)
            val below = Rect(content.x, content.y + dst.bottom, content.w, content.h - dst.bottom)
            if (above.h >= below.h) above else below
        }
    }

    private fun assertTheSceneSplits(strip: Rect, box: Rect, what: String) {
        assertTrue(strip.w * strip.h >= Slide.SPLIT_FILL_PX && strip.overlaps(box),
            "$what — the shape guard: the move is elected as a translation and the strip it exposes ($strip) is one the shell would send blank, with rows of the box $box in it")
    }

    private val oneRow = MenuSurface.Spec("", listOf(MenuSurface.Item("Dismiss")), onCommit = {})

    /** The menu is open and its top rule stands whole on the composed surface (one loop sample). */
    private suspend fun assertTheMenuStandsOnTheCanvas(r: Rig, box: Rect, what: String) {
        val seen = r.sample(what) {
            val g = r.shell.comp.composed
            listOf(if (r.shell.menuIsOpen) 1 else 0, (box.x until box.right).count { x -> g[x, box.y] != Level.DIM },
                (box.x until box.right).firstOrNull { x -> g[x, box.y] != Level.DIM } ?: -1)
        }
        assertEquals(1, seen[0], "$what: the menu is open")
        assertEquals(0, seen[1], "$what: the menu's top rule (row ${box.y}, x ${box.x}..${box.right - 1}) is on the surface; ${seen[1]} of its pixels are not the rule's, the first at x=${seen[2]}")
    }

    /**
     * RB-2: a canvas moves by itself (a lyric line, a terminal's output) while a menu is open over
     * it, under a split fill. The box in the previous frame breaks the moved block, the strip the
     * move exposes holds rows of the box, and that strip went out blank with its content owed: the
     * owed fill then painted the whole canvas over the open menu and nothing painted the menu again
     * — a menu that takes input and is not on the glass, and whose under-snapshot holds the blank
     * rows. Nothing splits while a surface floats on the content (`paintCanvasOf`).
     * Mutations watched: a menu not counted as a floating surface (the rule is gone from the
     * surface); the split left in `paintCanvasOf` alone (the one-flush count — the run behind the
     * split composes and puts the frame right a flush later).
     */
    @Test
    fun aCanvasThatMovesUnderAnOpenMenuLeavesTheMenuOnTheGlass(): Unit = runBlocking {
        val (r, c) = canvasRig(contract = 2)
        try {
            r.shell.services.runOnShell { r.shell.services.openMenu(oneRow, null) }; r.calm("the menu")
            assertTrue(r.shell.menuIsOpen, "the menu is up")
            val box = r.overlayBox("the menu")
            assertTheSceneSplits(stripAMoveExposes(r, c, 2, "the scene"), box, "a two-line move under a one-row menu")
            val top = c.top
            val moved = r.step("the canvas moved under the menu") { r.shell.services.runOnShell { c.top += 2; r.shell.services.requestRender(c) } }
            assertEquals(top + 2, c.top, "the canvas moved two lines")
            moved.say("a canvas that moved two lines under an open menu")
            assertTheMenuStandsOnTheCanvas(r, box, "the canvas moved two lines under the open menu")
            // the strip was not sent blank first: the move is ONE flush of the content. With the split
            // left in `paintCanvasOf` the run behind it still puts the frame right (it composes), and
            // only this count says the strip went out blank under the menu a flush earlier
            val content = r.shell.layout.content
            val ofContent = moved.flushes.filter { f -> f.ops.any { it is DisplayOp.Delta && it.box.overlaps(content) } }
            assertEquals(1, ofContent.size, "the move under the menu is one flush of the content, its strip not sent blank first: ${moved.shapes()}")
            r.assertGlassMatchesBelief("a canvas that moved under an open menu")
            // ...and what the menu covers is the canvas as it is now: a cancel puts back no blank row
            r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel")
            assertEquals(0, canvasDiffers(r, c, box, "the menu, cancelled"), "the cancel put the canvas back under the menu's box $box")
            r.assertGlassMatchesBelief("the menu cancelled over the moved canvas")
        } finally { r.close() }
    }

    /**
     * RB-2's other way in: the canvas moves and splits with nothing floating, and a menu opens off
     * a message that was queued AHEAD of the owed fill's own (an async completion; the wheel's
     * chord). The fill then painted the canvas over the menu. The posted run composes instead when
     * a surface floats by then: the content, then whatever floats on it.
     * Mutations watched: the run always taking the bare fill; a menu not counted as a floating
     * surface.
     */
    @Test
    fun aMenuOpenedBetweenACanvasMoveAndItsOwedFillStaysOnTheGlass(): Unit = runBlocking {
        val (r, c) = canvasRig(contract = 2)
        try {
            repeat(2) {                                               // the menu's face has drawn its label
                r.shell.services.runOnShell { r.shell.services.openMenu(oneRow, null) }; r.calm("the menu, warm")
                r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel")
            }
            val top = c.top
            val n0 = r.all().size
            // in one message, so the order behind it is the test's: the repaint, then the menu, and the
            // owed fill's run — which the repaint posts — third
            r.shell.services.runOnShell {
                c.top += 2
                r.shell.services.requestRender(c)
                r.shell.services.runOnShell { r.shell.services.openMenu(oneRow, null) }
            }
            r.calm("a move, then a menu, then the owed fill")
            assertEquals(top + 2, c.top, "the canvas moved two lines")
            assertTrue(r.all().drop(n0).any { f -> f.ops.any { it is DisplayOp.Copy || it is DisplayOp.CopyPair } },
                "the shape guard: the move went out as a translation: ${r.all().drop(n0).map(::shape)}")
            val box = r.overlayBox("the menu")
            assertTheMenuStandsOnTheCanvas(r, box, "a menu opened ahead of the owed fill")
            r.assertGlassMatchesBelief("a menu opened between a canvas move and its owed fill")
            r.gesture(EvenHubMsg.EV_DOUBLE_CLICK, "cancel")
            assertEquals(0, canvasDiffers(r, c, box, "the menu, cancelled"), "the cancel put the canvas back under the menu's box $box")
        } finally { r.close() }
    }

    /** How many pixels of the composed surface over [over] are not the canvas's own, painted fresh
     *  into a scratch surface — one loop sample. */
    private suspend fun canvasDiffers(r: Rig, c: Canvas, over: Rect, what: String): Int = r.sample(what) {
        val content = r.shell.layout.content
        val fresh = Gray8(content.w, content.h)
        (c.view() as WindowView.CanvasView).paint(fresh, Rect(0, 0, content.w, content.h))
        val g = r.shell.comp.composed
        var n = 0
        for (y in over.y until over.bottom) for (x in over.x until over.right) if (g[x, y] != fresh[x - content.x, y - content.y]) n++
        n
    }

    /**
     * RB-N1: the same root under a notice that is FURLING. The canvas moves inside the furl; the
     * strip went out blank; the box then captured its under-snapshot with the blank rows in it; the
     * owed fill painted the canvas and left that snapshot standing (a furl owns its snapshot strip
     * by strip and is not lifted) — and the furl put black back over the fresh canvas, strip by
     * strip. Belief, glass and truth agreed on the black patch, so no check reported it.
     * The answers are held so the furl cannot step before the move: the three notches of the box
     * take the three slots, the dismissal starts the furl, the canvas moves.
     * Mutation watched: a furling notice not counted as a floating surface.
     */
    @Test
    fun aCanvasThatMovesUnderAFurlingNoticeIsNotLeftWithABlackPatch(): Unit = runBlocking {
        val (r, c) = canvasRig(contract = 2)
        try {
            fun notice() = Notifications.Notice("SMS · TEST", "t1", (1..9).joinToString(" ") { "part $it of a long message body" }, "12:00")
            r.shell.postNotice(notice())
            waitUntil(15_000, 10, { "the box took focus after its grace" }) { r.shell.notifications.focused }
            r.calm("the box, focused")
            repeat(4) { r.gesture(EvenHubMsg.EV_SCROLL_BOTTOM, "warm") }
            repeat(4) { r.gesture(EvenHubMsg.EV_SCROLL_TOP, "warm back") }
            val box = r.sample("the box") { r.shell.notifications.boxRect(r.shell.layout, false) ?: Rect(0, 0, 0, 0) }
            assertTrue(box.w > 0, "the box is up")
            assertTheSceneSplits(stripAMoveExposes(r, c, 2, "the scene"), box, "a two-line move under a three-line notice")
            val top = c.top
            r.inOneFrame("a dismissal, then a canvas move inside its furl", inFlight = 3,
                { repeat(3) { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) } },      // three notches of the box: the three slots
                { r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) },                     // dismissed: the furl begins and cannot step
                { c.top += 2; r.shell.services.requestRender(c) })                       // the canvas moves by itself
            assertEquals(top + 2, c.top, "the canvas moved two lines")
            assertTrue(!r.sample("the notice") { r.shell.notifications.active }, "the notice is gone")
            assertEquals(0, canvasDiffers(r, c, box, "where the notice stood"), "where the notice stood ($box) the surface is the canvas, with no row the furl put back from a blank strip")
            r.assertGlassMatchesBelief("a canvas that moved under a furling notice")
        } finally { r.close() }
    }

    /**
     * RB-2's wheel term (the R1 verify stage; the build named it unpinned). The wheel is open and
     * the entry it previews is the canvas itself; the canvas then moves by itself. The panel in the
     * previous frame breaks the moved block exactly as a menu's box does, the strip went out blank,
     * and the owed fill painted the FOCUSED canvas over the content — the wheel's panel included,
     * with nothing to paint the panel again while the wheel still took input. Nothing splits while
     * the wheel floats on the content (`surfaceFloats`).
     * One notch of the wheel moves its cursor from Main to the canvas (the rig holds one window, so
     * the wheel opens on Main), and the preview settles on it. The canvas's lines end in words of
     * their own here: with the plain lines (a digit apart) and a panel 176 rows tall in the previous
     * frame, "no move" out-votes a two-line move and nothing splits on either tree (watched: the
     * shape guard below failed with an empty strip).
     * THE SHAPE GUARDS: the canvas is what stands beside the panel before the move, and the move
     * is one the shell would split ([stripAMoveExposes]).
     * Mutation watched: the wheel not counted as a floating surface (the panel's pixels are the canvas's).
     */
    @Test
    fun aCanvasThatMovesUnderTheWheelLeavesTheWheelOnTheGlass(): Unit = runBlocking {
        val (r, c) = canvasRig(contract = 2, varied = true)
        try {
            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)       // the chord opens the wheel (§1.3)
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.calm("the wheel")
            assertTrue(r.shell.switcherIsOpen, "the wheel is up")
            r.gesture(EvenHubMsg.EV_SCROLL_BOTTOM, "the wheel's cursor on the canvas")
            val panel = r.shell.layout.switcherPanel
            val content = r.shell.layout.content
            val beside = Rect(content.x, content.y, panel.x - content.x - 8, content.h)
            assertEquals(0, canvasDiffers(r, c, beside, "the preview"), "the shape guard: the wheel previews the canvas — beside the panel the surface is the canvas's")
            assertTheSceneSplits(stripAMoveExposes(r, c, 2, "the scene"), panel, "a two-line move under the wheel")
            fun panelPixels(): List<Int> {
                val g = r.shell.comp.composed
                val out = ArrayList<Int>(panel.w * panel.h + 1)
                out.add(if (r.shell.switcherIsOpen) 1 else 0)
                for (y in panel.y until panel.bottom) for (x in panel.x until panel.right) out.add(g[x, y])
                return out
            }
            val before = r.sample("the wheel's panel before the move") { panelPixels() }
            val top = c.top
            val moved = r.step("the canvas moved under the wheel") { r.shell.services.runOnShell { c.top += 2; r.shell.services.requestRender(c) } }
            assertEquals(top + 2, c.top, "the canvas moved two lines")
            moved.say("a canvas that moved two lines under the wheel")
            val after = r.sample("the wheel's panel after the move") { panelPixels() }
            assertEquals(1, after[0], "the wheel is still open")
            val changed = (1 until before.size).count { before[it] != after[it] }
            assertEquals(0, changed, "the wheel's panel $panel stands on the surface as it did before the canvas moved under it ($changed of its pixels changed)")
            assertEquals(0, canvasDiffers(r, c, beside, "beside the panel, after"), "and beside it the canvas is the moved one")
            r.assertGlassMatchesBelief("a canvas that moved under the wheel")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ the rig's own rest

    /**
     * The rig's rest sees a count reading (0.64 R1; review 1a, RD-N1). A reading of the cache count
     * (op 1) is out and has not answered; a window then draws a face the atlas does not hold, and
     * its cache write waits behind the reading (`Shell.pumpAtlas` holds both write lanes under
     * one). The shell reads idle throughout — a reading is no part of quiescence — and the atlas's
     * marks do not move for a face that is only queued. Before this round [Rig.calm] answered "at
     * rest" here, with a cache write owed, and [Rig.read] then took its read a pass early.
     * Decided without a clock in the passing direction: while the reading is held the spy's two
     * counts differ, so `calm` cannot return at all; the 200 ms are only how long the tree before
     * the fix is given to return (it did, inside them).
     */
    @Test
    fun theRigIsNotAtRestWhileACountReadingIsOut(): Unit = runBlocking {
        val r = rig()
        try {
            val one = Odd(r.text, "odd", FontSpec(Face.MONO, 20)); val two = Odd(r.text, "odder", FontSpec(Face.MONO, 24))
            r.shell.register(Rows(r.text, wide = true)); r.shell.register(one); r.shell.register(two)
            r.up()
            val gate = CompletableDeferred<Unit>()
            r.spy.holdCounts = gate
            r.shell.services.runOnShell { r.shell.services.openWindow("odd", null) }
            // the first face's cache write is acked and its reading is asked: held, so this stands once seen
            waitUntil(15_000, 5, { "a count reading is asked after the first face's cache write and has not answered — ${r.shell.quiescenceReport()}" }) {
                r.spy.countsAsked.get() > r.spy.countsAnswered.get() }
            val writes = r.all().count { it.label == "ATLAS" }
            r.shell.services.runOnShell { r.shell.services.openWindow("odder", null) }
            r.settle("the second face, drawn by the host")
            val rested = CompletableDeferred<Unit>()
            val waiting = launch { r.calm("a count reading out"); rested.complete(Unit) }
            delay(200)
            val early = rested.isCompleted
            // the scene itself: nothing was written to the cache under the reading, and the second face is still the host's
            val heldBack = r.all().count { it.label == "ATLAS" } == writes && two.font !in r.shell.cachedFontsLive
            gate.complete(Unit)
            waiting.join()
            assertTrue(heldBack, "the second face's cache write waited behind the reading that had not answered")
            assertTrue(!early, "the rig did not call itself at rest with a count reading out and a cache write waiting behind it")
            assertTrue(two.font in r.shell.cachedFontsLive && r.all().count { it.label == "ATLAS" } > writes,
                "once the reading answered the owed write went out and the second face is live: ${r.shell.cachedFontsLive}")
            r.assertGlassMatchesBelief("after the held reading")
        } finally { r.close() }
    }

    /**
     * The rig's rest sees a read-back the shell has not applied yet (0.64 R1; review 1a, RB-7). The
     * transport has answered the cache read-back — the spy's two counts agree — and the shell's
     * coroutine has not yet posted the run that puts the new face live: a stall of the pool thread
     * between `cacheCheck()` returning and `post`. Nothing the spy can count sits on the shell's
     * side of that call, so the rest reads the shell's own flags, on the loop
     * (`Shell.cacheWorkInFlight`). Before it `calm` returned here with a `goLive` still to come,
     * and the next held frame counted a flush that was not the pin's.
     * Mutations watched (`research/mutations-76-r1.json`, R1c-01 and -02): the getter answering
     * false, and its read-back term removed.
     */
    @Test
    fun theRigIsNotAtRestWhileAReadBackIsNotYetApplied(): Unit = runBlocking {
        val r = rig()
        try {
            val one = Odd(r.text, "odd", FontSpec(Face.MONO, 20))
            r.shell.register(Rows(r.text, wide = true)); r.shell.register(one)
            r.up()
            val gate = CompletableDeferred<Unit>()
            val answered = r.spy.checksAnswered.get()
            r.spy.stallChecks = gate
            r.shell.services.runOnShell { r.shell.services.openWindow("odd", null) }
            // the face's cache write is acked and its read-back answered by the transport: stalled, so this stands once seen
            waitUntil(15_000, 5, { "the read-back of the new face's cache write was answered by the transport — ${r.shell.quiescenceReport()}" }) {
                r.spy.checksAnswered.get() > answered }
            assertTrue(one.font !in r.shell.cachedFontsLive, "its answer is not applied: the face is not live yet")
            val rested = CompletableDeferred<Unit>()
            val waiting = launch { r.calm("a read-back not yet applied"); rested.complete(Unit) }
            delay(200)
            val early = rested.isCompleted
            gate.complete(Unit)
            waiting.join()
            assertTrue(!early, "the rig did not call itself at rest between a read-back's answer and the run that applies it")
            assertTrue(one.font in r.shell.cachedFontsLive, "applied, the face is live: ${r.shell.cachedFontsLive}")
            r.assertGlassMatchesBelief("after the stalled read-back")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------ Main's lens

    /**
     * Main's lens on a row with a progress and a detail too long for the band (the design pass's
     * question 4; `MainSurface.paintLens`). Until 0.64 the detail ran 12 px into the first
     * progress block, which is filled after it, with its continuation mark under that block: the
     * glyphs under the block keep the band out of the cache (2,362 B of pixels here; two 2.2 KB
     * paints of Main on glass on 2026-11-10 are read as this, inferred) and the cut had no mark.
     * And with no progress the detail ran to the band's edge with its mark in the rail's
     * columns, where the next full repaint cleared it.
     *
     * In an anti-aliased face (`SoftText`): a block over solid glyphs of its own level proves by
     * accident. The band ships as a base and its draws, the detail among them; the mark stands
     * whole between the detail and the blocks; and on the row without a progress it stands
     * inside the band and is still there after a full repaint.
     */
    @Test
    fun mainsLensWithALongDetailShipsAsDrawsAndKeepsItsMarkInSight(): Unit = runBlocking {
        val r = rig(base = SoftText())
        try {
            val detail = "Blackstar - remastered edition with the long subtitle that keeps going - q 3/14 - shuffle"
            r.shell.register(Card("music", DamageWindow.Summary("playing", detail = detail, more = true, progress = 0.40)))
            r.shell.register(Card("tmux", DamageWindow.Summary("2 sessions",
                detail = "damage: a pane whose last line is far too long for the band it is shown in, by a good many characters")))
            r.up(open = null)                                         // Main, the lens on the first row
            r.gesture(EvenHubMsg.EV_SCROLL_BOTTOM, "off the row")
            val back = r.read("the lens back on the row with a progress", close = { r.gesture(EvenHubMsg.EV_SCROLL_BOTTOM, "off the row") }) {
                r.gesture(EvenHubMsg.EV_SCROLL_TOP, "back on the row")
            }
            back.say("Main's lens back on the row with a progress and a long detail")
            val l = r.shell.layout
            val band = Rect(l.lens.x, l.lens.y, l.lens.w - Layout.RAIL_W, l.lens.h)
            val own = back.groups.filter { l.lens.contains(it.delta.box) }
            assertTrue(own.any { g -> g.texts.any { it.length >= 20 && detail.startsWith(it) } },
                "the lens band ships as a base and its draws, the detail's fitted part among them: ${back.shapes()} misses ${back.misses}")
            assertTrue(back.noFailedProof(), "no proof refused: ${back.misses}")
            assertMarkStandsClear(r, "the row with a progress", band, edge = band.right - 212)
            r.assertGlassMatchesBelief("Main's lens, a progress and a long detail")

            r.gesture(EvenHubMsg.EV_SCROLL_BOTTOM, "the row with no progress")
            r.shell.requestRepaint(); r.calm("a full repaint")       // the rail is painted after the lens
            assertMarkStandsClear(r, "the row with no progress, after a full repaint", band, edge = band.right)
            r.assertGlassMatchesBelief("Main's lens, a long detail and no progress")
        } finally { r.close() }
    }

    /** The continuation mark of the lens's second line: whole (its nine rows of 2..6..2 px), 16 px
     *  left of [edge], with nothing else lit from its first column to [edge] and four clear
     *  columns between it and the text. Read on the composed surface, on the loop. */
    private suspend fun assertMarkStandsClear(r: Rig, what: String, band: Rect, edge: Int) {
        val markX = edge - 16
        val rows = (band.y + 30) until (band.bottom - 2)              // under line one, inside the band's rules
        val seen = r.sample(what) {
            val g = r.shell.comp.composed
            val top = rows.firstOrNull { y -> g[markX, y] != 0 } ?: -1
            val widths = if (top < 0) emptyList() else (0 until 9).map { i -> (markX until edge).count { x -> g[x, top + i] != 0 } }
            val lead = (markX - 4 until markX).count { x -> rows.any { y -> g[x, y] != 0 } }
            widths + lead
        }
        assertEquals(listOf(2, 3, 4, 5, 6, 5, 4, 3, 2), seen.dropLast(1), "$what: the mark stands whole at x=$markX with nothing else lit up to x=$edge")
        assertEquals(0, seen.last(), "$what: the detail ends four columns before its mark")
    }

    private companion object {
        /** [Rig.inOneFrame]'s "not taken": the sample found cache work on its way. Compared by identity. */
        val NOT_HELD = Pair(-1, -1)

        fun copyOf(g: Gray8) = Gray8(g.w, g.h).also { System.arraycopy(g.pix, 0, it.pix, 0, g.pix.size) }

        fun isDraw(op: DisplayOp) = op is DisplayOp.DrawText || op is DisplayOp.DrawText2 || op is DisplayOp.DrawImage || op is DisplayOp.DrawImage2

        /** The codes of a cached string draw (a kerning adjust byte is 1..31, never a printable code). */
        fun textOf(op: DisplayOp): String? {
            val b = when (op) { is DisplayOp.DrawText -> op.text; is DisplayOp.DrawText2 -> op.text; else -> return null }
            return String(b.filter { (it.toInt() and 0xFF) >= 32 }.toByteArray(), Charsets.ISO_8859_1)
        }

        fun key(op: DisplayOp): String = when (op) {
            is DisplayOp.DrawText -> "T(${op.x},${op.y} '${textOf(op)}')"
            is DisplayOp.DrawText2 -> "T2(${op.xL}/${op.xR},${op.y} '${textOf(op)}')"
            is DisplayOp.DrawImage -> "I(${op.cacheOffset} ${op.x},${op.y})"
            is DisplayOp.DrawImage2 -> "I2(${op.off4} ${op.xL}/${op.xR},${op.y})"
            else -> op.toString()
        }

        /** Every delta of [f] with the draws that follow it, up to the next op that is not a draw. */
        fun groupsOf(f: FlushRequest): List<Group> {
            val out = ArrayList<Group>()
            var i = 0
            while (i < f.ops.size) {
                val op = f.ops[i]
                if (op is DisplayOp.Delta) {
                    var j = i + 1
                    while (j < f.ops.size && isDraw(f.ops[j])) j++
                    out.add(Group(op, f.ops.subList(i + 1, j).toList()))
                    i = j
                } else i++
            }
            return out
        }

        fun shape(f: FlushRequest) = "[${f.label}] " + f.ops.joinToString(" ") {
            when (it) {
                is DisplayOp.Keyframe -> "KF(${it.payload.size})"
                is DisplayOp.Delta -> "D${it.box}(${it.payload.size},d${it.disparity})"
                is DisplayOp.StereoPair -> "SP(${it.payload.size})"
                is DisplayOp.Copy -> "C"
                is DisplayOp.CopyPair -> "CP"
                is DisplayOp.DrawText -> "T(${it.x},${it.y} '${textOf(it)}')"
                is DisplayOp.DrawImage -> "I"
                is DisplayOp.CacheWrite -> "CW"
                is DisplayOp.DrawText2 -> "T2(${it.xL}/${it.xR},${it.y} '${textOf(it)}')"
                is DisplayOp.DrawImage2 -> "I2(${it.xL}/${it.xR},${it.y})"
                is DisplayOp.Fill -> "F"
                is DisplayOp.Clip -> "CL"
                is DisplayOp.PresentHint -> "H${it.y0}-${it.y1}"
                is DisplayOp.CacheWrite2 -> "CW2"
            }
        }
    }
}
