package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Level
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg

/**
 * `HANDOFF.md` §73, the shell's half: a character a live face has not drawn before is the host's
 * pixels on the flush that reveals it, the face then gains its glyph off the gesture path (one
 * small cache write, read back like any upload), and from the promotion on the character ships
 * as a cached draw — belief equal to glass at every step, on the simulator's own cache and
 * tables, on both contracts. And the envelope: an extension owed at a link end, at a lapse, under
 * a setting toggle; the gates that keep a font from going live unproven; the lapse gate.
 *
 * Each pin was watched to fail under the mutation its comment names.
 */
class GlyphSubsetShellTest {

    /** The base choreography over the model on a clock the test owns; the link can be ended from the test. */
    private class LabTransport(val glass: GlassFirmwareSim, scope: CoroutineScope, val clock: () -> Long) :
        CfwTransportBase(scope, "lab") {
        override val instant: Boolean get() = true
        override fun nowMs(): Long = clock()
        /** While set, what the glasses answer waits here: flushes stay in flight. */
        @Volatile var hold = false
        private val held = ArrayList<Pair<Arm, ByteArray>>()

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) {
                    if (hold) synchronized(held) { held.add(arm to packet) } else onNotifyPacket(arm, packet)
                }
                override fun panelChanged(arm: Arm) {}
            })
        }

        /** Everything the glasses said while held, in order. */
        fun release() {
            hold = false
            val l = synchronized(held) { held.toList().also { held.clear() } }
            for ((a, p) in l) onNotifyPacket(a, p)
        }
        /** The link ended with those answers in the air. */
        fun discard() { hold = false; synchronized(held) { held.clear() } }

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() { glass.linkReset() }
        override suspend fun writeArm(arm: Arm, packet: ByteArray) { glass.write(arm, packet, clock()) }
        override fun onMaintenanceTick() {
            glass.tick(clock())
            val held = glass.leaseHeld(Arm.LEFT, clock()) && glass.leaseHeld(Arm.RIGHT, clock())
            if (!held && state.value.started) setLease(false, "lease LOST — stock repaints (fail-open)")
            else if (held) setLease(true, "lease held")
        }
        fun endLink(reason: String) = onLinkDown(reason)
    }

    /** Sees every flush; can turn the completion of chosen ATLAS flushes into "not delivered", drop
     *  them, or hold every ATLAS completion until released; counts read-backs and can hold them. */
    private class Hooks(private val inner: Transport, scope: CoroutineScope) : Transport by inner {
        private val flushes = ArrayList<FlushRequest>()
        /** The transport's word for a lapse, held until the test gives the lease back: the state
         *  reads "not held", the transport's own renewals are not passed on, and the shell is told. */
        @Volatile private var lapse = false
        private val forced = kotlinx.coroutines.flow.MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
        private val shown = kotlinx.coroutines.flow.MutableStateFlow(inner.state.value)
        /** The estimator's terms as the shell is shown them, in place of the transport's own (an
         *  instant link times nothing): the shell's half of the regime is read against these. */
        @Volatile private var link: (wm.damage.core.transport.LinkState) -> wm.damage.core.transport.LinkState = { it }
        private fun present(s: wm.damage.core.transport.LinkState) = link(if (lapse) s.copy(leaseHeld = false) else s)
        fun showLink(f: (wm.damage.core.transport.LinkState) -> wm.damage.core.transport.LinkState) { link = f; shown.value = present(inner.state.value) }
        init { scope.launch { inner.state.collect { s -> shown.value = present(s) } } }
        override val state: kotlinx.coroutines.flow.StateFlow<wm.damage.core.transport.LinkState> get() = shown
        fun loseLease() {
            lapse = true
            shown.value = present(inner.state.value)
            forced.tryEmit(TransportEvent.Lease(false, "test: lease LOST — stock repaints (fail-open)"))
        }
        fun leaseBack() {
            lapse = false
            shown.value = present(inner.state.value)
            forced.tryEmit(TransportEvent.Lease(true, "test: lease held"))
        }
        private val lock = Mutex()
        private val atlasIds = HashSet<Long>()
        private val failIds = HashSet<Long>()
        private var atlasN = 0
        /** 1-based ordinals of ATLAS flushes whose completion the shell sees as not delivered. */
        @Volatile var failAtlas: Set<Int> = emptySet()
        /** While set, an ATLAS flush's completion never reaches the shell (the link ends first). */
        @Volatile var dropAtlasDone = false
        /** While set and not complete, an ATLAS flush's completion (and everything behind it) waits. */
        @Volatile var holdAtlasDone: CompletableDeferred<Unit>? = null
        /** While set and not complete, a read-back waits before it is even asked. */
        @Volatile var holdChecks: CompletableDeferred<Unit>? = null
        val checksAnswered = AtomicInteger(0)
        /** Read-backs asked for so far, held or not. */
        val checksAsked = AtomicInteger(0)

        override val events: Flow<TransportEvent> = kotlinx.coroutines.flow.merge(forced, inner.events.mapNotNull { ev ->
            if (ev is TransportEvent.Lease && lapse) return@mapNotNull null
            if (ev !is TransportEvent.FlushDone) return@mapNotNull ev
            val (atlas, fail) = lock.withLock { (ev.id in atlasIds) to (ev.id in failIds) }   // every collector sees the same answer
            if (!atlas) return@mapNotNull ev
            if (dropAtlasDone) return@mapNotNull null
            holdAtlasDone?.await()
            if (fail) ev.copy(ok = false, error = "test: the ack never came") else ev
        })

        override suspend fun submit(flush: FlushRequest): Long = lock.withLock {
            synchronized(flushes) { flushes.add(flush) }
            val id = inner.submit(flush)
            if (flush.label == "ATLAS") {
                atlasIds.add(id)
                if (++atlasN in failAtlas) failIds.add(id)
            }
            id
        }

        override suspend fun cacheCheck(): DamageMsg.Telemetry? {
            checksAsked.incrementAndGet()
            holdChecks?.await()
            val t = inner.cacheCheck()
            checksAnswered.incrementAndGet()
            return t
        }

        fun all() = synchronized(flushes) { flushes.toList() }
        fun atlasFlushes() = all().count { it.label == "ATLAS" }
        /** Cached string draws shipped so far whose bytes hold [ch] (an adjust byte is 1..31, never a printable code). */
        fun drawsCarrying(ch: Char): Int = all().sumOf { f -> f.ops.count { op ->
            when (op) {
                is DisplayOp.DrawText -> op.text.any { it == ch.code.toByte() }
                is DisplayOp.DrawText2 -> op.text.any { it == ch.code.toByte() }
                else -> false
            } } }
        /** Cached string draws shipped so far whose codes are exactly [text] (kerning adjusts aside). */
        fun drawsOf(text: String): Int = all().sumOf { f -> f.ops.count { op ->
            val b = when (op) { is DisplayOp.DrawText -> op.text; is DisplayOp.DrawText2 -> op.text; else -> null }
            b != null && String(b.filter { it.toInt() and 0xFF >= 32 }.toByteArray(), Charsets.ISO_8859_1) == text } }
        fun drawsShipped(): Int = all().sumOf { f -> f.ops.count { it is DisplayOp.DrawText || it is DisplayOp.DrawText2 } }
    }

    /** Rows in one face whose text the test changes; [extra] draws a second face (heavier by the
     *  pixels it covers when [extraHeavy], so the atlas packs it first). */
    private class Lines(private val tx: TextRasterizer, val font: FontSpec) : DamageWindow("lines", "Lines", IconKind.FILES) {
        private val model = ListModel()
        @Volatile var suffix = "abc"
        /** A second face drawn at the right of every row while set. */
        @Volatile var extra: FontSpec? = null
        /** What ten characters measured each time the shell told this window to lay out again. */
        val relayouts = java.util.concurrent.CopyOnWriteArrayList<Int>()
        /** What they measured at every paint and layout hook since the test last cleared it. */
        val probes = java.util.concurrent.CopyOnWriteArrayList<Int>()
        private fun probe() = tx.measure(PROBE, font).also { probes.add(it) }
        override fun onFontScaleChanged(scale: Double) { relayouts.add(probe()) }
        override fun onLayoutChanged() { probe() }
        override fun view(): WindowView = WindowView.ListView(model, { 40 },
            paintRow = { g, i, r, _ ->
                probe()
                tx.draw(g, r.x + 40, r.y + 4, "Row $i $suffix", font, Level.BODY)
                extra?.let { tx.draw(g, r.x + 400, r.y + 4, "xy", it, Level.BODY) }
            },
            paintLens = { g, r, i -> tx.draw(g, r.x + 44, r.y + 6, "Row $i", font, Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("40 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) { probe() }
        companion object { const val PROBE = "abcd\u00e9afghi" }
    }

    private class Rig(val scope: CoroutineScope, contract: Int, base: TextRasterizer = NoisyText(), cached: Boolean = true,
        val font: FontSpec = FontSpec(Face.MONO, 22)) {
        val tmp = Files.createTempDirectory("damage-subset")
        var clock = 1_000_000L
        val sim = GlassFirmwareSim().also { if (contract > 0) it.damageContract = contract }
        val lab = LabTransport(sim, scope) { clock }
        val spy = Hooks(lab, scope)
        val text = CachedText(base)
        val journalPath = tmp.resolve("journal.jsonl")
        val store = Persistence(tmp.resolve("state.json"))
        val shell = Shell(text, spy, store, journalPath, scope)
        val keeper = ShellKeeper(shell, spy, scope, retryPauseMs = 100)
        val win = Lines(text, font)
        val wantCached = cached

        init { shell.register(win) }

        fun lines(): List<String> = if (!Files.exists(journalPath)) emptyList() else Files.readAllLines(journalPath)
        fun notes(kind: String): List<String> = lines().filter { it.contains("\"kind\":\"$kind\"") }
        fun driving() = keeper.state == ShellKeeper.State.RUNNING && lab.state.value.started

        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10_000) {
                if (shell.isQuiescent()) return
                delay(5)
            }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }

        suspend fun until(what: String, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < 15_000) delay(5)
            assertTrue(cond(), "$what — live=${shell.cachedFontsLive} queued=${shell.atlasExtensionsQueued} promoted=${shell.atlasExtensionsLive} " +
                "atlas notes=${notes("atlas").takeLast(6)} faults=${notes("fault").takeLast(3)}")
        }

        /** The atlas has nothing queued, in flight or unread: the last note says what is live. */
        suspend fun atlasIdle(what: String) {
            until("$what: the test face is live") { font in shell.cachedFontsLive }
            settle(what)
        }

        suspend fun up(enable: Boolean = true) {
            keeper.start()
            until("the keeper drives") { driving() }
            settle("start")
            shell.services.runOnShell { shell.services.openWindow("lines", null) }
            settle("open lines")
            if (wantCached && enable) {
                shell.updateSettings { it.copy(cachedText = "on") }
                atlasIdle("up")
            }
        }

        suspend fun notch(what: String) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); settle(what) }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(640, 480)
            for (y in 0 until 480) for (x in 0 until 640) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        fun assertGlassMatchesBelief(what: String) {
            for (left in booleanArrayOf(true, false)) {
                val e = shell.comp.expectedLens(left); val g = lens(left)
                var diffs = 0; var first: String? = null
                for (y in 0 until 480) for (x in 0 until 640) {
                    val q = minOf(15, (e[x, y] + 8) / 17) * 17
                    if (q != g[x, y]) { diffs++; if (first == null) first = "($x,$y) belief $q glass ${g[x, y]}" }
                }
                assertEquals(0, diffs, "$what: ${if (left) "L" else "R"} belief != glass ($diffs px, first $first)")
            }
        }

        suspend fun close() { keeper.stop(); scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    private fun rig(contract: Int, base: TextRasterizer = NoisyText(), cached: Boolean = true) =
        Rig(CoroutineScope(SupervisorJob() + Dispatchers.Default), contract, base, cached)

    /**
     * The whole path, on both contracts, against a shell that never touches the cache.
     *  - the flush that reveals `Q` ships it as the host's pixels (no draw carries it);
     *  - the face gains `Q` off the gesture path: one ATLAS message, and NOTHING is repainted
     *    or flushed for the promotion itself;
     *  - the next row that shows `Q` ships it in a cached draw;
     *  - belief = glass at each step, the glasses' generation count equals the ATLAS flushes
     *    (one non-empty message each — what the staging ledger counts by), and the content
     *    region equals, pixel for pixel, the same walk with cached text OFF: a wrong table
     *    entry or a tofu drawn on both sides agrees with itself, not with the host.
     * Mutations watched: no promotion in `goLive` (Q never in a draw); the recorder split on
     * the contract's range instead of the entry's (a tofu where the reference has Q); the table
     * spans left out of `takeUpload` (belief != glass after the promotion).
     */
    @Test
    fun aFirstSeenCharacterIsTheHostsPixelsThenACachedDraw(): Unit = runBlocking {
        for (contract in intArrayOf(0, 2)) {
            val r = rig(contract); val ref = rig(contract, cached = false)
            try {
                r.up(); ref.up()
                assertEquals(0, r.spy.drawsCarrying('Q'))
                r.notch("warm"); ref.notch("warm")
                assertTrue(r.spy.drawsShipped() > 0, "contract $contract: the rows ship as cached draws before any of this")
                val extBefore = r.shell.atlasExtensionsLive
                val atlasBefore = r.spy.atlasFlushes()

                r.win.suffix = "abQ"; ref.win.suffix = "abQ"
                r.notch("the notch that reveals Q"); ref.notch("the notch that reveals Q")
                assertEquals(0, r.spy.drawsCarrying('Q'), "contract $contract: Q went out as the host's pixels")
                r.assertGlassMatchesBelief("contract $contract, Q host-drawn")
                // HERE, before anything repaints the row: a hollow box where Q should be agrees with
                // itself on both sides of the link, and the next notch paints over it
                sameContent(r, ref, "contract $contract, on the flush that revealed Q")

                r.until("contract $contract: the face gains Q") { r.shell.atlasExtensionsLive > extBefore }
                r.settle("after the promotion")
                assertEquals(atlasBefore + 1, r.spy.atlasFlushes(), "contract $contract: one cache-write message for the extension")
                assertEquals(0, r.spy.drawsCarrying('Q'), "contract $contract: a promotion repaints nothing")
                assertTrue(r.notes("atlas").any { "gained glyphs" in it && "+\\\"Q\\\"" in it }, "the journal names what was gained: ${r.notes("atlas").takeLast(3)}")
                r.assertGlassMatchesBelief("contract $contract, after the promotion")

                r.notch("the next row with Q"); ref.notch("the next row with Q")
                assertTrue(r.spy.drawsCarrying('Q') >= 1, "contract $contract: the next row ships Q in a cached draw")
                r.assertGlassMatchesBelief("contract $contract, Q cached")
                assertEquals(r.spy.atlasFlushes().toLong(), r.sim.cacheGen(Arm.RIGHT), "contract $contract: one generation bump per ATLAS flush")

                sameContent(r, ref, "contract $contract, with Q cached")
            } finally { r.close(); ref.close() }
        }
    }

    /** The reference: the content region is, pixel for pixel on both lenses, what a shell that never
     *  used the cache put on the glasses after the same gestures. */
    private fun sameContent(r: Rig, ref: Rig, what: String) {
        val c = r.shell.layout.content
        for (left in booleanArrayOf(true, false)) {
            val a = r.lens(left); val b = ref.lens(left)
            var diffs = 0; var first: String? = null
            for (y in c.y until c.bottom) for (x in c.x until c.right) if (a[x, y] != b[x, y]) { diffs++; if (first == null) first = "($x,$y) cached ${a[x, y]} host ${b[x, y]}" }
            assertEquals(0, diffs, "$what, ${if (left) "L" else "R"}: the content differs from the host-only walk ($diffs px, first $first)")
        }
    }

    /** A face whose new glyph the cache already holds (every character of this rasterizer is one
     *  record) gains it with a message that only rewrites table entries: no byte mark moves, and
     *  the entries still go live only after their own read-back. Mutation watched: the gate keyed
     *  on `ackedBytes` alone (no read-back is asked; the count below stands still). */
    @Test
    fun aPatchOnlyExtensionIsReadBackBeforeItIsLive(): Unit = runBlocking {
        val r = rig(2, base = FakeText())
        try {
            r.up()
            r.notch("warm")
            val checks = r.spy.checksAnswered.get()
            assertTrue(checks >= 1, "the first upload was read back")
            val ext = r.shell.atlasExtensionsLive
            r.win.suffix = "abQ"
            r.notch("the notch that reveals Q")
            r.until("the face gains Q") { r.shell.atlasExtensionsLive > ext }
            assertTrue(r.spy.checksAnswered.get() > checks, "the patch-only batch was read back: ${r.spy.checksAnswered.get()} after $checks")
            r.notch("Q cached")
            assertTrue(r.spy.drawsCarrying('Q') >= 1)
            r.assertGlassMatchesBelief("after a patch-only extension")
        } finally { r.close() }
    }

    /** A grow that runs while a read-back is on its way must not light the acked fonts itself —
     *  here a grow with nothing to upload (its cue is a character the face has no glyph for), the
     *  case where the queue is empty and nothing is in flight. Mutation watched: the held path's
     *  direct `atlasLive` (the fonts are live while the answer is still held). */
    @Test
    fun noFontGoesLiveWhileItsReadBackIsOnItsWay(): Unit = runBlocking {
        val r = rig(2, base = NoisyText(uncovered = setOf('é')))
        try {
            r.up(enable = false)
            val gate = CompletableDeferred<Unit>()
            r.spy.holdChecks = gate
            r.shell.updateSettings { it.copy(cachedText = "on") }
            // the read-back is asked only at the batch's last ack with the queue empty: asked = the upload is acked
            r.until("the upload's read-back is asked for") { r.spy.checksAsked.get() >= 1 }
            r.settle("upload acked")
            assertTrue(r.shell.cachedFontsLive.isEmpty(), "nothing is live before the answer")
            val queuedNotes = r.notes("atlas").count { "chunk(s) queued" in it }
            // a new character the face cannot draw: a cue for the grow, nothing to upload, the read-back still out
            r.win.suffix = "abé"
            r.notch("a grow under the read-back")
            r.until("the grow ran: é is known to have no glyph") { r.text.atlas?.missing(r.font, r.text.codesFor(r.font))?.isEmpty == true && r.text.charsOf(r.font).get('é'.code) }
            r.settle("after the grow")
            assertEquals(queuedNotes, r.notes("atlas").count { "chunk(s) queued" in it }, "that grow had nothing to upload: it met an empty queue")
            assertEquals(0, r.spy.checksAnswered.get(), "the read-back is still held")
            assertTrue(r.shell.cachedFontsLive.isEmpty(), "and nothing went live around it: ${r.shell.cachedFontsLive}")
            gate.complete(Unit)
            r.atlasIdle("after the answer")
            assertTrue(r.spy.checksAnswered.get() >= 1)
            r.notch("drawing"); r.assertGlassMatchesBelief("after the held read-back")
        } finally { r.close() }
    }

    /** An extension's message whose ack never came: it goes again from the acked mark — the
     *  appended bytes and the table patch — and is read back before it is promoted. Mutations
     *  watched: no promotion in `goLive`; a rewind that leaves the extension marked handed out.
     *  (The rewind that empties the queue under ACKED fonts is the next pin's.) */
    @Test
    fun anUndeliveredMessageIsSentAgainAndNothingIsLiveUnread(): Unit = runBlocking {
        val r = rig(2)
        try {
            r.up()
            r.notch("warm")
            val ext = r.shell.atlasExtensionsLive
            val n = r.spy.atlasFlushes()
            r.spy.failAtlas = setOf(n + 1)                 // the extension's message: acked on the glasses, lost on the way back
            val checks = r.spy.checksAnswered.get()
            r.win.suffix = "abQ"
            r.notch("the notch that reveals Q")
            r.until("the extension went again and is live") { r.shell.atlasExtensionsLive > ext }
            assertEquals(n + 2, r.spy.atlasFlushes(), "sent twice: the lost one and its repeat")
            assertTrue(r.notes("atlas").any { "not delivered" in it })
            assertTrue(r.spy.checksAnswered.get() > checks, "the repeat was read back before it went live")
            r.notch("Q cached")
            assertTrue(r.spy.drawsCarrying('Q') >= 1)
            r.assertGlassMatchesBelief("after an undelivered extension")
        } finally { r.close() }
    }

    /** The link ends with the extension's message owed; the rebuild keeps the atlas (a renewal on
     *  both arms) and the extension goes again — its appended bytes and its table patch — while the
     *  fonts the glasses held stay live. Mutation watched: a rewind that leaves the extension
     *  marked as handed out (it is never sent again and `Q` never ships in a draw). */
    @Test
    fun anExtensionOwedAtALinkEndGoesAgainUnderTheKeptAtlas(): Unit = runBlocking {
        val r = rig(2)
        try {
            r.up()
            r.notch("warm")
            val live = r.shell.cachedFontsLive
            val n = r.spy.atlasFlushes()
            r.spy.dropAtlasDone = true                     // the extension leaves and its completion never arrives
            r.win.suffix = "abQ"
            r.notch("the notch that reveals Q")
            r.until("the extension's message left") { r.spy.atlasFlushes() == n + 1 }
            assertEquals(0, r.shell.atlasExtensionsLive)
            r.spy.dropAtlasDone = false
            r.clock += 20_000L
            r.lab.endLink("test: supervision timeout")
            r.until("the second session drives") { r.keeper.attempts == 2 && r.driving() }
            r.until("the glasses saw the second connect") { r.sim.preludeAcks == 2 }
            assertTrue(r.notes("atlas").any { "kept across the rebuild" in it }, "kept: ${r.notes("atlas").takeLast(5)}")
            r.until("the extension went again and is live") { r.shell.atlasExtensionsLive >= 1 }
            r.settle("after the kept rebuild")
            assertEquals(n + 2, r.spy.atlasFlushes(), "only the extension went again")
            assertTrue(r.shell.cachedFontsLive.containsAll(live), "what the glasses held stayed live")
            r.notch("Q cached")
            assertTrue(r.spy.drawsCarrying('Q') >= 1)
            r.assertGlassMatchesBelief("after the kept rebuild")
        } finally { r.close() }
    }

    /** The lease lapses with an extension on its way (the upstream build: no rebuild follows). The
     *  extension is the face from then on; while the lease is gone NOTHING is written — a first-seen
     *  character is a cue for the grow now, and a grow there hands the whole layout to a cache that
     *  does not exist; when the lease is back everything goes up again and `Q` draws from it.
     *  Mutations watched: the lapse gate removed from `pumpAtlas` (a cache write leaves under the
     *  lapse); the fold removed from `forgetUpload` (`Q` never ships in a draw). */
    @Test
    fun aLapseHoldsEveryCacheWriteAndFoldsTheExtension(): Unit = runBlocking {
        val r = rig(0)
        try {
            r.up()
            r.notch("warm")
            val n = r.spy.atlasFlushes()
            r.spy.dropAtlasDone = true                     // the extension's completion has not arrived when the lease goes
            r.win.suffix = "abQ"
            r.notch("the notch that reveals Q")
            r.until("the extension's message left") { r.spy.atlasFlushes() == n + 1 && r.shell.atlasExtensionsQueued >= 1 }
            val sent = r.spy.atlasFlushes()
            r.spy.loseLease()
            r.until("the shell saw the lapse") { r.notes("atlas").any { "the lease lapsed" in it } }
            // and the lapse is REAL on the glasses: the model's lease runs out and it frees the cache,
            // so what is drawn afterwards can only come from bytes that went up again
            r.clock += 100_000L
            r.until("the model freed the cache on both lenses") { Arm.entries.none { r.sim.cacheAllocated(it) } }
            r.spy.dropAtlasDone = false
            // a character nothing has drawn yet, under the lapse: a cue, and no write
            r.win.suffix = "abQZ"
            r.notch("a first-seen character under the lapse")
            delay(300)
            assertEquals(sent, r.spy.atlasFlushes(), "no cache write while the lease is gone (was $n before the extension)")
            assertFalse(r.notes("atlas").any { "cached text off" in it }, "and cached text was not switched off: ${r.notes("atlas").takeLast(4)}")
            r.until("the transport holds the lease again (a fresh acquire)") { r.lab.state.value.leaseHeld }
            r.spy.leaseBack()
            r.atlasIdle("after the lease is back")
            assertTrue(Arm.entries.all { r.sim.cacheAllocated(it) }, "the model holds a cache again")
            assertTrue(r.spy.atlasFlushes() > sent, "the atlas went up again")
            r.until("Z joins too") { r.shell.atlasExtensionsLive >= 1 || r.text.atlas?.entry(r.font)?.packs('Z') == true }
            r.settle("after the re-upload")
            r.notch("Q and Z cached")
            assertTrue(r.spy.drawsCarrying('Q') >= 1, "the folded extension draws from the cache")
            r.assertGlassMatchesBelief("after the lapse")
        } finally { r.close() }
    }

    /** `Cached text` goes off and on with messages still queued. The queue is dropped — and so is
     *  the atlas's own record of having handed those messages out, or their bytes never go while
     *  later acks move the acked mark over them. Mutation watched: `atlasDisable` without the
     *  rewind (the face never goes live again). */
    @Test
    fun theSettingOffAndOnWithMessagesQueuedSendsThemAgain(): Unit = runBlocking {
        // a face that takes several messages: every code at a size whose glyphs do not compress
        val everyCode = AtlasRepackTest.EVERY_CODE
        val r = rig(0)
        try {
            r.up(enable = false)
            r.win.suffix = everyCode
            r.notch("every code drawn"); r.notch("twice")
            val gate = CompletableDeferred<Unit>()
            r.spy.holdAtlasDone = gate
            r.shell.updateSettings { it.copy(cachedText = "on") }
            r.until("the first message left with more behind it") { r.spy.atlasFlushes() == 1 && r.notes("atlas").any { "chunk(s) queued" in it } }
            assertTrue(r.notes("atlas").filter { "chunk(s) queued" in it }.maxOf { Regex("(\\d+) chunk\\(s\\) queued").find(it)!!.groupValues[1].toInt() } >= 3, "several messages: ${r.notes("atlas").takeLast(3)}")
            r.shell.updateSettings { it.copy(cachedText = "off") }
            r.until("off") { r.notes("atlas").any { "cached text off" in it } }
            r.shell.updateSettings { it.copy(cachedText = "on") }
            delay(100)
            gate.complete(Unit)
            r.spy.holdAtlasDone = null
            r.atlasIdle("after off and on")
            r.notch("drawing from it")
            assertTrue(r.spy.drawsShipped() > 0, "the face draws from the cache")
            r.assertGlassMatchesBelief("after off and on")
        } finally { r.close() }
    }

    /** A LATE message of the first upload is not delivered: the rewind empties the queue with
     *  faces already acked below the mark. They go live only through a read-back — the next grow
     *  used to light them itself, with the queue empty, nothing in flight and no answer asked for.
     *  Mutation watched: the grow's held path lighting what is acked with messages still queued
     *  and without the gate, which is what it did when it ran before the queue was refilled (the
     *  direct `atlasLive` ALONE does not fail this pin: the queue is no longer empty there). */
    @Test
    fun aRewindThatEmptiesTheQueueLightsNothingWithoutARead(): Unit = runBlocking {
        val r = rig(2)
        try {
            r.up(enable = false)
            r.win.extra = FontSpec(Face.READER, 24)
            r.win.suffix = AtlasRepackTest.EVERY_CODE          // the test face takes several messages; it is the heaviest, so packed first
            r.notch("every code drawn"); r.notch("twice")
            val first = CompletableDeferred<Unit>()
            r.spy.holdAtlasDone = first
            r.shell.updateSettings { it.copy(cachedText = "on") }
            r.until("the first message left with the rest queued") { r.spy.atlasFlushes() == 1 && r.notes("atlas").any { "chunk(s) queued" in it } }
            val n = r.notes("atlas").filter { "chunk(s) queued" in it }.maxOf { Regex("(\\d+) chunk\\(s\\) queued").find(it)!!.groupValues[1].toInt() }
            assertTrue(n >= 4, "several messages: $n")
            r.spy.failAtlas = setOf(n)                         // the LAST one's ack never comes
            val gate = CompletableDeferred<Unit>()
            r.spy.holdChecks = gate
            first.complete(Unit); r.spy.holdAtlasDone = null
            r.until("the last message was not delivered") { r.notes("atlas").any { "not delivered" in it } }
            assertTrue(r.text.atlas!!.specs().any { r.text.atlas!!.isAcked(it) }, "faces are acked below the rewound mark")
            r.until("the rest went again and its read-back is asked for") { r.spy.checksAsked.get() >= 1 }
            r.settle("with the read-back held")
            assertEquals(0, r.spy.checksAnswered.get())
            assertTrue(r.shell.cachedFontsLive.isEmpty(), "nothing is live before a read-back answers: ${r.shell.cachedFontsLive}")
            gate.complete(Unit)
            r.atlasIdle("after the answer")
            r.notch("drawing"); r.assertGlassMatchesBelief("after an undelivered late message")
        } finally { r.close() }
    }

    /** A character first drawn while its face's extension is still on its way waits for that one —
     *  and must not wait for ever: the promotion re-arms the cue (the character is not new to the
     *  face by then, so nothing else would). Mutation watched: the promotion not resetting the
     *  want version (`Z` is never packed). */
    @Test
    fun aCharacterThatWaitedBehindAnExtensionFollowsItsPromotion(): Unit = runBlocking {
        val r = rig(2)
        try {
            r.up()
            r.notch("warm")
            val asked = r.spy.checksAsked.get()
            val gate = CompletableDeferred<Unit>()
            r.spy.holdChecks = gate                            // Q's extension is acked and waits for its read-back
            r.win.suffix = "abQ"
            r.notch("the notch that reveals Q")
            r.until("Q's read-back is asked for") { r.spy.checksAsked.get() > asked }
            r.win.suffix = "abQZ"                              // Z arrives while Q's extension is pending
            r.notch("the notch that reveals Z")
            r.settle("Z drawn by the host")
            assertEquals(0, r.shell.atlasExtensionsLive, "nothing promoted while the read-back is held")
            assertFalse(r.text.atlas!!.entry(r.font)!!.packs('Z'))
            gate.complete(Unit); r.spy.holdChecks = null
            r.until("Q is promoted and Z follows it (${r.shell.atlasExtensionsLive} promoted)") { r.text.atlas?.entry(r.font)?.packs("QZ") == true }
            r.notch("both cached")
            assertTrue(r.spy.drawsCarrying('Z') >= 1)
            r.assertGlassMatchesBelief("after the second extension")
        } finally { r.close() }
    }

    /** A host-drawn character whose ink runs past its advance (an `f`, most of an italic face) used
     *  to cost its whole rect: the run after it had its box blacked in the base, the overhang went
     *  with it, the proof failed and the row shipped as pixels. The first columns of that run are
     *  left to the base now, and the row still ships as draws on the flush that reveals the
     *  character. Mutation watched: `keep` always 0 in the recorder (no draw of the run after `Q`
     *  on that flush; the journal's reason is `proof`). */
    @Test
    fun aHostDrawnCharactersOverhangDoesNotCostTheRowItsDraws(): Unit = runBlocking {
        for (contract in intArrayOf(0, 2)) {
            val r = rig(contract, base = NoisyText(overhang = setOf('Q')))
            try {
                r.up()
                r.notch("warm")
                assertEquals(0, r.spy.drawsOf("cb"))
                r.win.suffix = "abQcb"                     // Q is new: the host's; "cb" after it is packed already
                r.notch("the notch that reveals Q")
                assertEquals(0, r.spy.drawsCarrying('Q'))
                assertTrue(r.spy.drawsOf("cb") >= 1, "contract $contract: the run after the host's Q shipped as a draw on that flush")
                val misses = r.lines().filter { "\"ev\":\"submit\"" in it }.takeLast(6).joinToString(" ") { Regex("\"cacheMiss\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) ?: "" }
                // (the v1 shape's full-width retry leaves "proof=0" behind when it succeeds: a miss is a count above 0)
                assertFalse(Regex("proof=[1-9]").containsMatchIn(misses), "contract $contract: no proof miss on those flushes: $misses")
                r.assertGlassMatchesBelief("contract $contract, Q host-drawn with its overhang")
                r.until("the face gains Q") { r.shell.atlasExtensionsLive >= 1 }
                r.notch("Q cached"); r.assertGlassMatchesBelief("contract $contract, Q cached")
            } finally { r.close() }
        }
    }

    /** A rebuild that keeps the atlas keeps what its faces were last drawn at. The frame clock runs
     *  on across sessions; with the stamps cleared, every resident face read as not drawn for a
     *  window of frames the moment the session started, and the first face that needed room evicted
     *  what was in use (12:06:36 on 2026-11-09: a repack 0.57 s after "kept … 0 B to go again").
     *  Mutation watched: `atlasKeep` clearing the stamps (the face reads as never drawn). */
    @Test
    fun aKeptAtlasKeepsWhatItsFacesWereLastDrawnAt(): Unit = runBlocking {
        val r = rig(2)
        try {
            val other = FontSpec(Face.READER, 24)
            r.win.extra = other
            r.up()
            r.until("the second face is live too") { other in r.shell.cachedFontsLive }
            r.win.extra = null                             // no longer drawn; still resident
            repeat(3) { r.notch("without it $it") }
            val stamp = r.text.lastUseOf(other)
            assertTrue(stamp > 0 && r.text.lastUseOf(r.font) > stamp, "drawn once, then left behind: $stamp against ${r.text.lastUseOf(r.font)}")
            r.clock += 20_000L
            r.lab.endLink("test: supervision timeout")
            r.until("the second session drives") { r.keeper.attempts == 2 && r.driving() }
            r.until("the glasses saw the second connect") { r.sim.preludeAcks == 2 }
            r.settle("after the kept rebuild")
            assertTrue(r.notes("atlas").any { "kept across the rebuild" in it }, "kept: ${r.notes("atlas").takeLast(4)}")
            assertTrue(other in r.shell.cachedFontsLive, "the resident face is live from the first compose")
            assertEquals(stamp, r.text.lastUseOf(other), "and reads as last drawn when it was, not as never drawn")
        } finally { r.close() }
    }

    /** The submit records of the journal since line [from], the atlas's and staging's own aside. */
    private fun submits(r: Rig, from: Int): List<String> =
        r.lines().drop(from).filter { "\"ev\":\"submit\"" in it && "\"label\":\"ATLAS\"" !in it && "\"label\":\"STAGE\"" !in it }
    private fun field(line: String, name: String): Int = Regex("\"$name\":(-?\\d+)").find(line)!!.groupValues[1].toInt()

    /** Everything posted to the shell before this call has been handled, and its pump has run. */
    private suspend fun barrier(r: Rig) {
        val d = CompletableDeferred<Unit>()
        r.shell.services.runOnShell { d.complete(Unit) }
        d.await()
    }

    /**
     * The journal's two §73 fields, read where the report reads them. `unpacked`: the characters
     * the host drew on this flush for want of a glyph. `in`: whether a ring event started the
     * flush — 1 on a gesture's flush, 0 on a repaint nobody asked for, and still 1 when the
     * gesture's pump found the window full and its flush left on the pump after the next ack
     * (the flag was read from the pump's own message, which by then is the ack).
     * Mutations watched: the count left out of the timing; `in` from `pumpPriority` alone.
     */
    @Test
    fun theSubmitLineSaysWhatTheHostDrewAndWhetherARingEventStartedTheFlush(): Unit = runBlocking {
        val r = rig(2)
        try {
            r.up()
            r.notch("warm")
            var from = r.lines().size
            r.win.suffix = "abQ"
            r.notch("the notch that reveals Q")
            var subs = submits(r, from)
            assertTrue(subs.isNotEmpty(), "the notch flushed")
            assertEquals(1, field(subs.first(), "in"), "a gesture's flush says a ring event started it: ${subs.first()}")
            assertTrue(subs.sumOf { field(it, "unpacked") } >= 1, "the host drew Q for want of a glyph: ${subs.map { field(it, "unpacked") }}")
            r.until("the face gains Q") { r.shell.atlasExtensionsLive >= 1 }
            r.settle("promoted")

            // a repaint no ring event asked for
            from = r.lines().size
            r.win.suffix = "abQa"
            r.shell.services.runOnShell { r.shell.services.requestRender(r.win) }
            r.until("the repaint flushed") { submits(r, from).isNotEmpty() }
            r.settle("repaint")
            subs = submits(r, from)
            assertTrue(subs.all { field(it, "in") == 0 }, "no input started these: ${subs.map { field(it, "in") }}")
            assertEquals(0, subs.sumOf { field(it, "unpacked") }, "and every character on them had its glyph")

            // a gesture whose pump finds the window full: its flush leaves on the next ack's pump
            r.lab.hold = true
            val shipped0 = r.spy.all().size
            r.win.suffix = "abQb"; r.shell.services.runOnShell { r.shell.services.requestRender(r.win) }
            r.until("one flush in flight") { r.lab.state.value.inFlight >= 1 }
            r.win.suffix = "abQc"; r.shell.services.runOnShell { r.shell.services.requestRender(r.win) }
            r.until("two in flight: all a repaint may take") { r.lab.state.value.inFlight >= 2 }
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            r.until("the gesture's flush took the reserved slot") { r.lab.state.value.inFlight >= 3 }
            barrier(r)
            from = r.lines().size
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)      // its pump finds no room
            barrier(r)
            assertTrue(submits(r, from).isEmpty(), "nothing left while the window was full (${r.spy.all().size - shipped0} shipped before)")
            r.lab.release()
            r.until("the waiting gesture's flush left") { submits(r, from).isNotEmpty() }
            r.settle("after the release")
            assertEquals(1, field(submits(r, from).first(), "in"), "the flush that waited for a slot is still the gesture's: ${submits(r, from).first()}")
            r.assertGlassMatchesBelief("after the held window")
        } finally { r.close() }
    }

    /**
     * The Cached text row decides the layout metric (`CachedText.cacheMetric`), and every layout is
     * re-derived when it changes: set BEFORE the windows are told to lay out again (they measure
     * in that call), and at a session's start before anything restores or paints — a metric that
     * followed the row late would wrap a page at one width and draw it at another.
     * Mutations watched: the row change that leaves the metric alone; the session start that does.
     */
    @Test
    fun theCachedTextRowSetsTheLayoutMetricBeforeAnythingIsLaidOut(): Unit = runBlocking {
        val r = rig(2, base = FractionalText())
        try {
            r.up(enable = false)
            assertFalse(r.text.cacheMetric, "cached text off: the host's metric")
            assertEquals(74, r.text.measure(Lines.PROBE, r.font))
            var n = r.win.relayouts.size
            r.shell.updateSettings { it.copy(cachedText = "on") }
            r.atlasIdle("on")
            assertTrue(r.text.cacheMetric)
            assertTrue(r.win.relayouts.size > n, "the row change told every window to lay out again")
            assertEquals(80, r.win.relayouts.last(), "and the cache's metric was in force when it did")
            r.assertGlassMatchesBelief("on, laid out by the cache's metric")

            // a session start: the metric is the persisted row's before any window hook runs
            r.text.cacheMetric = false                             // a fresh process's rasterizer
            r.win.probes.clear()
            r.clock += 20_000L
            r.lab.endLink("test: supervision timeout")
            r.until("the second session drives") { r.keeper.attempts == 2 && r.driving() }
            r.atlasIdle("the second session")
            assertTrue(r.text.cacheMetric, "the session start set the metric from the persisted row")
            assertTrue(r.win.probes.isNotEmpty() && r.win.probes.all { it == 80 },
                "every layout and paint of the session measured by the cache's metric: ${r.win.probes.distinct()}")

            n = r.win.relayouts.size
            r.shell.updateSettings { it.copy(cachedText = "off") }
            r.until("the cache goes dark") { !r.shell.cachedTextActive }
            r.settle("off")
            assertFalse(r.text.cacheMetric)
            assertTrue(r.win.relayouts.size > n, "off re-derives every layout too")
            assertEquals(74, r.win.relayouts.last(), "at the host's metric")
            r.assertGlassMatchesBelief("off again")
        } finally { r.close() }
    }

    /**
     * From the review of §73: the flag that says "a ring event's flush is waiting for a slot" was
     * cleared only when a flush was submitted, so a link that ended with it set carried it into
     * the next session and that session's keyframe was journaled as a gesture's first flush.
     * Mutation watched: the clear at a session start removed.
     * (The flag is also cleared when a pump has room and nothing to send. That clear has no pin:
     * in this rig every ring event, a long-press included, leaves a flush of its own behind — the
     * echo in the status cell — and that flush's submit consumes the flag first.)
     */
    @Test
    fun theInputFlagIsNotCarriedIntoTheNextSession(): Unit = runBlocking {
        val r = rig(2)
        try {
            r.up()
            r.notch("warm")
            // three in flight, the third a gesture's; then a second gesture, whose flush waits
            r.lab.hold = true
            r.win.suffix += "x"; r.shell.services.runOnShell { r.shell.services.requestRender(r.win) }
            r.until("one flush in flight") { r.lab.state.value.inFlight >= 1 }
            r.win.suffix += "y"; r.shell.services.runOnShell { r.shell.services.requestRender(r.win) }
            r.until("two in flight") { r.lab.state.value.inFlight >= 2 }
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            r.until("the gesture's flush took the reserved slot") { r.lab.state.value.inFlight >= 3 }
            barrier(r)
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            barrier(r)
            val from = r.lines().size
            r.clock += 20_000L
            r.lab.endLink("test: supervision timeout")
            r.lab.discard()
            r.until("the second session drives") { r.keeper.attempts == 2 && r.driving() }
            r.settle("the second session")
            val first = r.lines().drop(from).dropWhile { "session start" !in it }.first { "\"ev\":\"submit\"" in it && "\"label\":\"ATLAS\"" !in it }
            assertEquals(0, field(first, "in"), "a session's first flush is no gesture's: $first")
        } finally { r.close() }
    }

    /**
     * The shell's half of the estimator change, which no gate read (the review of §73): the regime
     * and its said-once lines start over with a session; an ADOPTED start — a pause and resume,
     * the transport's session and its readings still standing — keeps the measured regime instead
     * of reading "fast" for ten flushes beside its own numbers; and the floor watch takes a term
     * that is still its default for no reading at all. The link's terms are shown to the shell
     * by hand (an instant link times nothing). Mutations watched: the session-start call removed
     * (no second "measured" line); the adopted start reset like any other ("fast regime" beside
     * 90 ms/KB); the watch's unmeasured guard removed ("floor back to 60 ms" from a default); the
     * "measured" line removed.
     */
    @Test
    fun theRegimeStartsOverWithASessionAndStandsOnAnAdoptedOne(): Unit = runBlocking {
        val r = rig(0, cached = false)
        try {
            r.up(enable = false)
            fun link() = r.notes("link")
            fun regimes() = link().filter { "regime (" in it }
            fun measured() = link().count { "measured —" in it }
            r.notch("first")
            assertTrue("fast regime" in regimes().single() && "unmeasured" in regimes().single(), "a session starts on defaults and says so: ${regimes()}")
            assertEquals(0, measured())

            // the link reads slow: 90 ms/KB over five isolated flushes, a floor of 300 ms
            r.spy.showLink { it.copy(transferMsPerKbEma = 90.0, transferSamples = 5, floorMsEma = 300.0, floorSamples = 5) }
            repeat(14) { r.notch("slow $it") }                    // past the ten-flush dwell
            assertTrue("SLOW regime" in regimes().last() && "(n=5)" in regimes().last(), "the regime follows the measured term: ${regimes()}")
            assertEquals(1, measured(), "the first real reading of a session is said once: ${link()}")
            r.shell.postMinuteTick(); r.shell.postMinuteTick(); barrier(r)
            assertTrue(link().any { "floor 300 ms for 2 min" in it }, "two minutes above the line: ${link().takeLast(3)}")

            // a pause and resume: the transport's session stands, its readings with it
            r.settle("before the pause")
            val n = regimes().size
            r.keeper.pause("test")
            r.keeper.resume()
            r.until("resumed") { r.driving() }
            r.settle("resumed")
            r.notch("after the resume")
            assertEquals(n + 1, regimes().size, "the resumed session journals its regime, once: ${regimes()}")
            assertTrue("SLOW regime" in regimes().last(), "an adopted start keeps the regime its standing readings give: ${regimes().last()}")
            assertEquals(2, measured(), "and says what it stands on again")

            // a real session end: everything starts over, and a default is no reading
            r.spy.showLink { it }
            val m = regimes().size
            val attempts = r.keeper.attempts
            r.clock += 20_000L
            r.lab.endLink("test: supervision timeout")
            r.until("the next session drives") { r.keeper.attempts > attempts && r.driving() }
            r.settle("the next session")
            r.notch("on the new link")
            assertTrue(regimes().size == m + 1 && "fast regime" in regimes().last() && "unmeasured" in regimes().last(),
                "the new session starts on defaults: ${regimes().takeLast(2)}")
            val notes = link().size
            r.shell.postMinuteTick(); r.shell.postMinuteTick(); barrier(r)
            assertTrue(link().drop(notes).none { "floor" in it }, "an unmeasured floor is neither slow nor back: ${link().drop(notes)}")
            r.spy.showLink { it.copy(transferMsPerKbEma = 30.0, transferSamples = 4, floorMsEma = 80.0, floorSamples = 4) }
            r.notch("measured again")
            assertEquals(3, measured(), "the new session's first real reading is said: ${link().takeLast(3)}")
            r.shell.postMinuteTick(); barrier(r)
            assertTrue(link().any { "floor back to 80 ms" in it }, "and a measured floor under the line closes the episode: ${link().takeLast(3)}")
        } finally { r.close() }
    }

    /**
     * From the review of §73: a typography or Cached text change that reaches the store while no
     * session runs (a synced record, applied store-direct) is read at the next start — and nothing
     * told the windows, which live across sessions and keep what they laid out. The start now
     * re-derives every layout when the settings a layout depends on differ from the last
     * session's. Mutation watched: the keyed call removed (no relayout is recorded).
     */
    @Test
    fun aLayoutSettingChangedWhileDownIsReDerivedAtTheNextStart(): Unit = runBlocking {
        val r = rig(2, base = FractionalText())
        try {
            r.up(enable = false)
            assertFalse(r.text.cacheMetric)
            val n = r.win.relayouts.size
            r.keeper.stop()
            val on = wm.damage.core.shell.ShellSettings.fromJson(r.store.get("shell.settings")).copy(cachedText = "on").toJson()
            assertTrue(r.store.tryApplyRemote("shell.settings", on, System.currentTimeMillis() + 1_000), "the synced record went into the store")
            r.keeper.start()
            r.until("the next session drives") { r.driving() }
            r.atlasIdle("the next session")
            assertTrue(r.text.cacheMetric, "the start read the row")
            assertTrue(r.win.relayouts.size > n, "and told the windows to lay out again")
            assertEquals(80, r.win.relayouts.last(), "by the cache's metric")
            // the same settings at the start after that: nothing to re-derive
            val k = r.win.relayouts.size
            r.clock += 20_000L
            r.lab.endLink("test: supervision timeout")
            r.until("a third session drives") { r.keeper.attempts >= 2 && r.driving() }
            r.settle("a third session")
            assertEquals(k, r.win.relayouts.size, "an unchanged key re-derives nothing: a link edge does not re-wrap the open book")
        } finally { r.close() }
    }

    /**
     * From the review of §73: the metric's contract follows the ATLAS's, set by the shell where the
     * atlas is built, and the layouts made under the other rule are re-derived there. On a
     * contract-1 session an accent is the host's, with no kern beside it; the metric read "contract
     * 2" until the atlas existed (and again after every reset), so accented text was measured one
     * way when wrapped and another when fitted. On a kerning rasterizer: the probe measures 78 by
     * the contract-2 rule (kern(é, a) = -1 and kern(h, i) = -1) and 79 by contract 1's.
     * Mutations watched: the contract not handed to the metric; the re-derive at the change removed.
     */
    @Test
    fun onAContractOneSessionTheMetricFollowsTheAtlasAndTheLayoutsAreReDerived(): Unit = runBlocking {
        val r = rig(0, base = KernedText())
        try {
            r.up(enable = false)
            assertTrue(r.text.metricV2, "a process starts on the contract-2 rule")
            r.keeper.stop()
            val on = wm.damage.core.shell.ShellSettings.fromJson(r.store.get("shell.settings")).copy(cachedText = "on").toJson()
            assertTrue(r.store.tryApplyRemote("shell.settings", on, System.currentTimeMillis() + 1_000))
            val n = r.win.relayouts.size
            r.keeper.start()
            r.until("the next session drives") { r.driving() }
            r.atlasIdle("the contract-1 atlas is up")
            assertFalse(r.text.metricV2, "the atlas is contract 1, and so is the metric")
            assertTrue(r.win.relayouts.size >= n + 2, "re-derived at the start (the row came on) and again when the atlas's contract was known: ${r.win.relayouts}")
            assertEquals(79, r.win.relayouts.last(), "the last layout is by the contract-1 rule")
            r.win.probes.clear()
            r.notch("a paint after it")
            assertTrue(r.win.probes.isNotEmpty() && r.win.probes.all { it == 79 }, "and every paint measures what that layout did: ${r.win.probes.distinct()}")
            r.assertGlassMatchesBelief("contract 1, kerned")
        } finally { r.close() }
    }
}
