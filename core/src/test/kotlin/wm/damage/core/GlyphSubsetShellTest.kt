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
import wm.damage.core.transport.draw2
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
        /** While set, what the glasses answer waits here: flushes stay in flight. A test sets it
         *  directly; it is read and cleared under [held]'s lock. */
        @Volatile var hold = false
        private val held = ArrayList<Pair<Arm, ByteArray>>()

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) {
                    // decided and queued in ONE step: an answer that read "held" a moment before a
                    // release must not join the list after the release has emptied it
                    synchronized(held) { if (hold) { held.add(arm to packet); return } }
                    onNotifyPacket(arm, packet)
                }
                override fun panelChanged(arm: Arm) {}
            })
        }

        /**
         * Everything the glasses said while held, in the order they said it — and nothing they say
         * meanwhile ahead of it: answers stay held until the last kept one has been handed over.
         * The class sweep (0.64 R1; review 1a, RB-8) of the defect the P1 verify pass measured and
         * fixed at `OverlayCachedPaintTest.HeldTransport.release` (which carries the numbers): with
         * the hold dropped first, the ack of a flush the shell sends on the first completion passed
         * the acks still in the list, and the transport read the earlier ones as lost. Nothing on
         * this tree was seen to fail for it here (0 of 60 warm runs of the two pins that hold three
         * answers, the P1 verify pass's measure).
         */
        fun release() {
            while (true) {
                val next = synchronized(held) { if (held.isEmpty()) { hold = false; null } else held.removeAt(0) } ?: return
                onNotifyPacket(next.first, next.second)
            }
        }
        /** The link ended with those answers in the air. */
        fun discard() { synchronized(held) { hold = false; held.clear() } }

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() { glass.linkReset() }
        /** R3 (review 2, 2A-2): while set, every op-1 (TELEMETRY) request is taken by the link and never
         *  reaches the glasses — its write succeeds and nothing answers it. Each one is kept here, in order. */
        @Volatile var muteCounts = false
        val mutedCounts = java.util.concurrent.CopyOnWriteArrayList<Pair<Arm, ByteArray>>()
        /** R3: while set, every op-1 request's write fails — the reading cannot be asked at all. */
        @Volatile var failCounts = false
        override suspend fun writeArm(arm: Arm, packet: ByteArray) {
            if (failCounts && telemetryRequestId(packet) != null) throw java.io.IOException("test: the link refuses the ask")
            if (muteCounts && telemetryRequestId(packet) != null) { mutedCounts.add(arm to packet); return }
            glass.write(arm, packet, clock())
        }
        /** Hand a muted request to the glasses now: they answer it, late, under the id it was asked with. */
        fun deliverLate(arm: Arm, packet: ByteArray) = glass.write(arm, packet, clock())
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
        @Volatile private var atlasN = 0
        /** 1-based ordinals of ATLAS flushes whose completion the shell sees as not delivered. */
        @Volatile var failAtlas: Set<Int> = emptySet()
        /** While set, the NEXT ATLAS flush's completion is seen as not delivered, whichever it is (then cleared). */
        @Volatile var failNextAtlas = false
        /** While set, an ATLAS flush's completion never reaches the shell (the link ends first). */
        @Volatile var dropAtlasDone = false
        /** While set and not complete, an ATLAS flush's completion (and everything behind it) waits. */
        @Volatile var holdAtlasDone: CompletableDeferred<Unit>? = null
        /** While set and not complete, a read-back waits before it is even asked. */
        @Volatile var holdChecks: CompletableDeferred<Unit>? = null
        val checksAnswered = AtomicInteger(0)
        /** Read-backs asked for so far, held or not. */
        val checksAsked = AtomicInteger(0)

        // ---- 0.64: the count's readings (op 1) and what an ATLAS flush wrote
        /** While set, the shell is shown a mirror that is not exact, so its belief-against-mirror check
         *  stands down. On glass the phone's mirror APPLIES a well-formed cache write the glasses
         *  acked and dropped, so that check cannot see the failure and only the count can; this rig
         *  breaks a write by making it malformed, which its mirror refuses like the glasses do —
         *  it would raise the `mirror` notice and keyframe at the first draw, which no phone would. */
        @Volatile var blindMirror = false
        override val mirror: wm.damage.core.transport.LensPanels get() = inner.mirror.let { m ->
            if (!blindMirror) m else object : wm.damage.core.transport.LensPanels by m { override val exact: Boolean get() = false } }
        /** Readings of the count asked for so far, held or not, and answered. */
        val countsAsked = AtomicInteger(0)
        val countsAnswered = AtomicInteger(0)
        /** While set and not complete, a count reading waits before it is even asked. */
        @Volatile var holdCounts: CompletableDeferred<Unit>? = null
        /** While set and not complete, a count reading's ANSWER — already taken from the glasses — waits. */
        @Volatile var holdCountAnswers: CompletableDeferred<Unit>? = null
        /** While set, every reading (op 1 and op 4) reports the cache generation one short: a decode
         *  that lags its ack for as long as the test says (the model bumps at once). */
        @Volatile var lagReads = false
        /** Readings report one short while no more than this many ATLAS flushes have been submitted:
         *  a decode that lags until the next cache write goes out (no race with the test's own polling). */
        @Volatile var lagThroughAtlas = -1
        /** Count readings whose answer has been taken from the glasses (held on its way back or not). */
        val countsTaken = AtomicInteger(0)
        /** 1-based ordinals of ATLAS flushes whose first write's offset is rewritten past the cache
         *  (`payload[1..2] = 0xFFFF` on both contracts): the model acks the message — the ack precedes
         *  the decode — refuses it whole and bumps nothing. The silent failure the count exists to catch.
         *  The flush is kept here as the shell submitted it. */
        @Volatile var breakAtlas: Set<Int> = emptySet()
        /** Every ATLAS flush carrying this write list (a message and each re-send of it) is broken the same way. */
        @Volatile var breakCarrying: List<Pair<Int, Int>>? = null
        /** The completion of an ATLAS flush carrying this write list never reaches the shell. */
        @Volatile var dropDoneCarrying: List<Pair<Int, Int>>? = null
        private val dropIds = HashSet<Long>()
        // ---- 0.64 R1: what a re-send's completion says, and a count reading that is not answered
        /** The completion of the NEXT ATLAS flush carrying this write list is seen as not delivered (then cleared). */
        @Volatile var failDoneCarrying: List<Pair<Int, Int>>? = null
        /** The completion of the NEXT ATLAS flush carrying this write list is seen as REFUSED by the glasses
         *  (the image lane's own answer, as the shell reads it: an `ImgResCmd` error), then cleared. */
        @Volatile var refuseDoneCarrying: List<Pair<Int, Int>>? = null
        private val refuseIds = HashSet<Long>()
        /** R2 (item 31): armed with a write list, [refuseDoneCarrying] is set to it the moment a completion is
         *  turned into "not delivered" (as [nullCountsOnFailedDone] is) — so the send AFTER the lost one is the
         *  refused one, with no race against the test. */
        val refuseOnFailedDone = java.util.concurrent.atomic.AtomicReference<List<Pair<Int, Int>>?>(null)
        /** This many count readings from now on are not answered: `telemetry()` returns null at once, as the
         *  transport does when its first write fails or the session is ending (`CfwTransportBase.telemetry`). */
        val nullCounts = AtomicInteger(0)
        /** Armed here, [nullCounts] is set to this the moment a completion is turned into "not delivered" by
         *  [failAtlas] / [failNextAtlas] / [failDoneCarrying] — before the shell has seen it, so the base
         *  reading that follows a lost write is the first one not answered, with no race against the test. */
        val nullCountsOnFailedDone = AtomicInteger(0)
        /** Armed with a write list and a number: the moment the NEXT ATLAS flush carrying that list is
         *  submitted, [nullCounts] is set to the number (then cleared) — the readings that follow that
         *  message's ack are the ones not answered, with no race against the test. */
        @Volatile var nullCountsOnSubmitCarrying: Pair<List<Pair<Int, Int>>, Int>? = null
        /** When each count reading was asked for, on this JVM's clock, in order. */
        val countAskedAtMs = java.util.concurrent.CopyOnWriteArrayList<Long>()
        /** While set, the shell's request for a session rebuild is refused (`restartSession` answers false):
         *  the shell journals the refusal and stays in the session it has. */
        @Volatile var refuseRestart = false
        val restartsRefused = AtomicInteger(0)
        override suspend fun restartSession(reason: String): Boolean =
            if (refuseRestart) { restartsRefused.incrementAndGet(); false } else inner.restartSession(reason)
        /** "count" when a count reading is answered, "atlas" when an ATLAS flush is submitted — in order. */
        private val traceLog = java.util.concurrent.CopyOnWriteArrayList<String>()
        fun trace(): List<String> = traceLog.toList()

        private fun lagged(t: DamageMsg.Telemetry?): DamageMsg.Telemetry? {
            val gen = t?.cacheGen
            return if (!(lagReads || atlasN <= lagThroughAtlas) || t == null || gen == null) t else DamageMsg.Telemetry(t.fields + (17 to gen - 1))
        }

        override suspend fun telemetry(label: String): DamageMsg.Telemetry? {
            countsAsked.incrementAndGet()
            countAskedAtMs.add(System.currentTimeMillis())
            holdCounts?.await()
            if (nullCounts.getAndUpdate { maxOf(0, it - 1) } > 0) { traceLog += "count-null"; return null }
            val t = lagged(inner.telemetry(label))
            countsTaken.incrementAndGet()
            holdCountAnswers?.await()
            traceLog += "count"
            countsAnswered.incrementAndGet()
            return t
        }

        /** The (offset in bytes, length) of every write of one cache-write op, from the bytes on the wire. */
        private fun writesOf(op: DisplayOp): List<Pair<Int, Int>> {
            val (p, unit) = when (op) {
                is DisplayOp.CacheWrite -> op.payload to 1
                is DisplayOp.CacheWrite2 -> op.payload to 4
                else -> return emptyList()
            }
            val out = ArrayList<Pair<Int, Int>>()
            var pos = 1
            while (pos + 4 <= p.size) {
                val off = (p[pos].toInt() and 0xFF) or ((p[pos + 1].toInt() and 0xFF) shl 8)
                val len = (p[pos + 2].toInt() and 0xFF) or ((p[pos + 3].toInt() and 0xFF) shl 8)
                out.add(off * unit to len)
                pos += 4 + len
            }
            return out
        }
        /** The write list of each ATLAS flush so far, as the shell submitted it, in order. */
        fun atlasWrites(): List<List<Pair<Int, Int>>> = all().filter { it.label == "ATLAS" }.map { f -> f.ops.flatMap(::writesOf) }
        /** ATLAS flushes carrying every write of [list] (containment: a re-send of two messages shares one message). */
        fun sends(list: List<Pair<Int, Int>>): Int = atlasWrites().count { it.containsAll(list) }
        /** Flushes with a keyframe so far. */
        fun keyframes(): Int = all().count { f -> f.ops.any { it is DisplayOp.Keyframe } }

        private fun broken(flush: FlushRequest): FlushRequest = flush.copy(ops = flush.ops.map { op ->
            fun past(p: ByteArray) = p.copyOf().also { it[1] = 0xFF.toByte(); it[2] = 0xFF.toByte() }
            when (op) {
                is DisplayOp.CacheWrite -> DisplayOp.CacheWrite(past(op.payload))       // byte 65535 + its length: past the 64 KiB
                is DisplayOp.CacheWrite2 -> DisplayOp.CacheWrite2(past(op.payload))     // unit 65535: 256 KiB, past the 160 KiB cache
                else -> op
            }
        })

        override val events: Flow<TransportEvent> = kotlinx.coroutines.flow.merge(forced, inner.events.mapNotNull { ev ->
            if (ev is TransportEvent.Lease && lapse) return@mapNotNull null
            // the mirror's own word about a write it refused (contract 1's decoder says so): see [blindMirror]
            if (blindMirror && ev is TransportEvent.Fault && ev.what.startsWith("mirror/")) return@mapNotNull null
            if (ev !is TransportEvent.FlushDone) return@mapNotNull ev
            val (atlas, fail) = lock.withLock { (ev.id in atlasIds) to (ev.id in failIds) }   // every collector sees the same answer
            if (!atlas) return@mapNotNull ev
            if (dropAtlasDone || lock.withLock { ev.id in dropIds }) return@mapNotNull null
            holdAtlasDone?.await()
            if (fail) nullCountsOnFailedDone.getAndSet(0).let { if (it > 0) nullCounts.set(it) }
            if (fail) refuseOnFailedDone.getAndSet(null)?.let { refuseDoneCarrying = it }
            if (lock.withLock { ev.id in refuseIds }) ev.copy(ok = false, error = "test: ImgResCmd UPGRADE_IMAGE_RAW_DATA_FAILED (5)")
            else if (fail) ev.copy(ok = false, error = "test: the ack never came") else ev
        })

        override suspend fun submit(flush: FlushRequest): Long = lock.withLock {
            synchronized(flushes) { flushes.add(flush) }
            if (flush.label != "ATLAS") return@withLock inner.submit(flush)
            val n = ++atlasN
            val writes = flush.ops.flatMap(::writesOf)
            val carried = breakCarrying
            traceLog += "atlas"
            val id = inner.submit(if (n in breakAtlas || (carried != null && writes.containsAll(carried))) broken(flush) else flush)
            atlasIds.add(id)
            if (n in failAtlas || failNextAtlas.also { failNextAtlas = false }) failIds.add(id)
            dropDoneCarrying?.let { if (writes.containsAll(it)) dropIds.add(id) }
            failDoneCarrying?.let { if (writes.containsAll(it)) { failIds.add(id); failDoneCarrying = null } }
            nullCountsOnSubmitCarrying?.let { (list, k) -> if (writes.containsAll(list)) { nullCounts.set(k); nullCountsOnSubmitCarrying = null } }
            refuseDoneCarrying?.let { if (writes.containsAll(it)) { refuseIds.add(id); refuseDoneCarrying = null } }
            id
        }

        override suspend fun cacheCheck(): DamageMsg.Telemetry? {
            checksAsked.incrementAndGet()
            holdChecks?.await()
            val t = lagged(inner.cacheCheck())
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
        /** What the second face draws. */
        @Volatile var extraText = "xy"
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
                extra?.let { tx.draw(g, r.x + 400, r.y + 4, extraText, it, Level.BODY) }
            },
            paintLens = { g, r, i -> tx.draw(g, r.x + 44, r.y + 6, "Row $i", font, Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("40 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) { probe() }
        companion object { const val PROBE = "abcd\u00e9afghi" }
    }

    private class Rig(val scope: CoroutineScope, contract: Int, base: TextRasterizer = NoisyText(), cached: Boolean = true,
        val font: FontSpec = FontSpec(Face.MONO, 22), pinClock: Boolean = false) {
        val tmp = Files.createTempDirectory("damage-subset")
        var clock = 1_000_000L
        val sim = GlassFirmwareSim().also { if (contract > 0) it.damageContract = contract }
        val lab = LabTransport(sim, scope) { clock }
        val spy = Hooks(lab, scope)
        val text = CachedText(base)
        val journalPath = tmp.resolve("journal.jsonl")
        val store = Persistence(tmp.resolve("state.json"))
        // 0.64: the pins that wait real settle windows (four to ten seconds) pin the wall clock — a
        // minute digit new to the chrome's face would cue a grow and put a second message in the
        // interval under test (the §73 lesson: a pin that waits lets an unrelated cue in)
        val shell = if (pinClock) Shell(text, spy, store, journalPath, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
            else Shell(text, spy, store, journalPath, scope)
        val keeper = ShellKeeper(shell, spy, scope, retryPauseMs = 100)
        init { if (pinClock) spy.blindMirror = true }      // the count's pins: the mirror cannot see on glass what it sees here
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

        /** Decided on the answer that ended the wait ([waitUntil]; 0.64 R1, review 1a RD-7). */
        suspend fun until(what: String, cond: () -> Boolean) = until({ what }, cond)
        /** The same, with a text that holds a live value: [what] is read when the wait gives up. */
        suspend fun until(what: () -> String, cond: () -> Boolean) = waitUntil(15_000, 5, {
            "${what()} — live=${shell.cachedFontsLive} queued=${shell.atlasExtensionsQueued} promoted=${shell.atlasExtensionsLive} " +
                "atlas notes=${notes("atlas").takeLast(6)} faults=${notes("fault").takeLast(3)}" }, cond)

        /** The atlas has nothing queued, in flight or unread — the count included (0.64): the last
         *  note says what is live, and every acked cache write has been judged by a reading. */
        suspend fun atlasIdle(what: String) {
            until("$what: the test face is live") { font in shell.cachedFontsLive }
            settle(what)
            until({ "$what: the count has judged every acked write (${shell.cacheCountUnjudged} unread)" }) { shell.cacheCountUnjudged == 0L }
            settle(what)
        }

        /** The journal's count notes (kind `atlas`): J1 `count ok`, J2 `count short —`, J3 `count short again`,
         *  J5 `re-send counted`, J6 `re-send landed late`, J7 `count re-based`. */
        fun count(head: String): List<String> = notes("atlas").filter { "\"detail\":\"$head" in it }
        fun cacheNotices(): List<wm.damage.core.shell.Notifications.Notice> =
            (listOfNotNull(shell.notifications.current) + shell.notifications.queued()).filter { it.source == "DAMAGE · cache" }
        /**
         * Both lenses as the shell believes them and as the model's panels hold them — copies, taken
         * in ONE turn of the loop with nothing queued behind it (0.64 R1; review 1a, RD-5).
         * `Compositor.expectedLens` hands out the live shadow: walked from the test thread against a
         * panel copied a moment earlier, a flush assembled meanwhile (an idle tick's readout, a
         * notice taking focus, the keyframe a count verdict asks for) moved the shadow under the
         * walk, and the difference found was not on the glass — which FAILS the equality below
         * and SATISFIES `glassDiffersFromBelief() > 0`. `Shell.sampleIdle` answers null when the
         * shell is not idle, so the sample settles and asks again (a count reading is no part of
         * idleness, and the states these pins read stand for seconds).
         */
        private class Lenses(val beliefL: Gray8, val beliefR: Gray8, val glassL: Gray8, val glassR: Gray8)
        private fun copyOf(g: Gray8) = Gray8(g.w, g.h).also { System.arraycopy(g.pix, 0, it.pix, 0, g.pix.size) }
        private suspend fun lenses(what: String): Lenses {
            repeat(2_000) {
                settle(what)
                shell.sampleIdle { Lenses(copyOf(shell.comp.expectedLens(true)), copyOf(shell.comp.expectedLens(false)), lens(true), lens(false)) }
                    ?.let { return it }
            }
            throw AssertionError("$what: the shell never sat idle for a sample — ${shell.quiescenceReport()}")
        }
        /** Pixels, over both lenses, where the glass is not what the shell believes (one loop sample). */
        suspend fun glassDiffersFromBelief(): Int {
            val s = lenses("belief against glass")
            var diffs = 0
            for (left in booleanArrayOf(true, false)) {
                val e = if (left) s.beliefL else s.beliefR; val g = if (left) s.glassL else s.glassR
                for (y in 0 until 480) for (x in 0 until 640) if (minOf(15, (e[x, y] + 8) / 17) * 17 != g[x, y]) diffs++
            }
            return diffs
        }
        /** Dismiss whatever box is up (a tap on a focused box), and the ones queued behind it. */
        suspend fun dismissNotices() {
            val t0 = System.currentTimeMillis()
            // decided on the read that ended the loop (RD-7's class): a box raised after it is not this wait's
            var up = shell.notifications.current
            while (up != null && System.currentTimeMillis() - t0 < 20_000) {
                if (shell.notifications.focused && !shell.notifications.animating) shell.postGesture(EvenHubMsg.EV_CLICK)
                delay(60)
                up = shell.notifications.current
            }
            assertEquals(null, up, "the boxes were dismissed")
            settle("after the dismissal")
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

        /** Down-notches so far (a reference rig is walked the same number). */
        var notches = 0
        suspend fun notch(what: String) { notches++; shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); settle(what) }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(640, 480)
            for (y in 0 until 480) for (x in 0 until 640) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        /** Belief equals glass on both lenses, from one loop sample ([lenses]). */
        suspend fun assertGlassMatchesBelief(what: String) {
            val s = lenses(what)
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

        suspend fun close() { keeper.stop(); scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    private fun rig(contract: Int, base: TextRasterizer = NoisyText(), cached: Boolean = true, pinClock: Boolean = false) =
        Rig(CoroutineScope(SupervisorJob() + Dispatchers.Default), contract, base, cached, pinClock = pinClock)

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
            r.until({ "Q is promoted and Z follows it (${r.shell.atlasExtensionsLive} promoted)" }) { r.text.atlas?.entry(r.font)?.packs("QZ") == true }
            r.notch("both cached")
            assertTrue(r.spy.drawsCarrying('Z') >= 1)
            r.assertGlassMatchesBelief("after the second extension")
        } finally { r.close() }
    }

    /** A host-drawn character whose ink runs past its advance (an `f`, most of an italic face) used
     *  to cost its whole rect: the run after it had its box blacked in the base, the overhang went
     *  with it, the proof failed and the row shipped as pixels. The first columns of that run are
     *  left to the base now, and the row still ships as draws on the flush that reveals the
     *  character. Mutation watched in §73: `keep` always 0 in the recorder (no draw of the run
     *  after `Q` on that flush; the journal's reason was `proof`).
     *
     *  Since 0.64 the base is black only where a draw writes, so the overhang rides it whatever
     *  `keep` says: this test holds by that rule and NO LONGER fails with `keep` at 0
     *  (mutations-73 M15, watched 2026-11-10 — the row is then priced and still ships as draws,
     *  being the smaller message). `keep` is pinned where it is recorded
     *  (`GlyphSubsetTest.theRecorderSplitsOnTheEntryAndNotesWhatTheFaceDrew`) and where it is read
     *  (`BaseMaskTest.aRunBesideAHostDrawnCharacterIsNotPriced`). */
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
                // (0.64: a failed proof is counted under one of two words, `Compositor.proofMiss`; both are refused here)
                assertFalse(Regex("(proof|two-planes)=[1-9]").containsMatchIn(misses), "contract $contract: no proof miss on those flushes: $misses")
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

    // ------------------------------------------------------------------------------------------------
    // 0.64 — a cache write the glasses acknowledged and did not count (Adam's ruling, `HANDOFF.md` §75.1:
    // no proof gate; the count read wherever the shell is, a shortfall notice and ONE re-send).
    // These pins wait real settle windows (2 s, twice per shortfall): their rigs pin the wall clock.
    // A re-send is identified by its WRITE LIST (the offsets and lengths the shell submitted for the
    // first send), never by counting ATLAS flushes: the notice's own text is new to its faces and
    // uploads glyphs of its own while the pin waits.

    /** Up in the list window, one warm notch, every acked write judged by the count. The ATLAS flushes so far. */
    private suspend fun warm(r: Rig): Int {
        r.up()
        // several notches, until a notch packs nothing new: a face first drawn by the second notch
        // (the rows' faces as the list moves) would put its own messages in the interval under test
        var n = -1
        var quiet = 0
        while (quiet < 2) {
            r.notch("warm"); r.atlasIdle("warm")
            val now = r.spy.atlasFlushes()
            if (now == n) quiet++ else { quiet = 0; n = now }
        }
        return n
    }

    /** Reveal a suffix with ONE character the test face has not drawn, [n] ATLAS flushes having gone
     *  before: the face gains it at once (nothing is gated on the count). The extension message's write list. */
    private suspend fun reveal(r: Rig, suffix: String, n: Int): List<Pair<Int, Int>> {
        val ext = r.shell.atlasExtensionsLive
        r.win.suffix = suffix
        r.notch("the notch that reveals $suffix")
        r.until("the extension's message left") { r.spy.atlasFlushes() > n }
        r.until("the face gains it — nothing waits for the count") { r.shell.atlasExtensionsLive > ext }
        return r.spy.atlasWrites()[n]
    }

    /**
     * L1. Outside the Reader — a list window, on both contracts — an acked cache write is followed
     * by a reading of the count: short inside the settle window it is pending and asked again on its
     * own pump, and when the count agrees the journal says so and nothing is left unread. Until 0.64
     * the count was read only with a document staged in the Reader (`telemetry()` was never called
     * here). And nothing is written to the cache under a reading on its way, a base or not.
     * Mutations watched: the reader narrowed to "a strip is acked" (no reading is asked in a list
     * window); `pumpAtlas`'s hold on the reading removed (a cache write leaves under the held reading);
     * the reader launching with a reading already out (one per pump, and the atlas's lane never runs).
     */
    @Test
    fun aCacheWriteOutsideTheReaderIsCountedByItsOwnReading(): Unit = runBlocking {
        for (contract in intArrayOf(1, 2)) {
            val r = rig(contract, pinClock = true)
            try {
                val n = warm(r)
                assertTrue(r.count("count ok").isNotEmpty(), "contract $contract: the first upload was judged by the count: ${r.notes("atlas").takeLast(4)}")
                val asked = r.spy.countsAsked.get()
                val ok = r.count("count ok").size
                r.spy.lagReads = true                          // after the base: a lagged base would read every later count one over
                reveal(r, "abQ", n)
                r.until("contract $contract: the extension's ack is followed by a reading of the count") { r.spy.countsAsked.get() > asked }
                r.until("contract $contract: short inside the settle window is pending, and the note says whose reading it is") {
                    r.count("count pending — the cache count read the generation 1 short").isNotEmpty() }
                r.spy.lagReads = false                         // the decode landed, well inside the settle window
                r.until("contract $contract: the count agrees and says so") { r.count("count ok — 1 atlas message(s) counted by RIGHT").size > 0 && r.count("count ok").size > ok }
                r.until("contract $contract: nothing is left unread") { r.shell.cacheCountUnjudged == 0L }
                assertTrue(r.count("count short").isEmpty(), "contract $contract: a pending reading counts for nothing: ${r.count("count short")}")
                assertTrue(r.cacheNotices().isEmpty(), "contract $contract: and raises nothing")

                // a reading that is NOT a base, held on its way: no cache write leaves under it
                val gate = CompletableDeferred<Unit>()
                r.spy.holdCounts = gate
                val asked2 = r.spy.countsAsked.get()
                reveal(r, "abQZ", r.spy.atlasFlushes())
                r.until("contract $contract: Z's reading is asked for (and held)") { r.spy.countsAsked.get() > asked2 }
                r.settle("Z acked, its reading out")
                assertEquals(asked2 + 1, r.spy.countsAsked.get(), "contract $contract: one reading of the count at a time — none is launched under the one that is out")
                val sent = r.spy.atlasFlushes()
                val queued = r.shell.atlasExtensionsQueued
                r.win.suffix = "abQZX"
                r.notch("a third character under the held reading")
                r.until("contract $contract: X's extension is cut and waits") { r.shell.atlasExtensionsQueued > queued }
                barrier(r); delay(300); barrier(r)
                assertEquals(sent, r.spy.atlasFlushes(), "contract $contract: no cache write under a reading on its way")
                gate.complete(Unit); r.spy.holdCounts = null
                r.until("contract $contract: X's message follows the reading") { r.spy.atlasFlushes() > sent }
                r.atlasIdle("after the release")
                r.notch("all three cached"); r.assertGlassMatchesBelief("contract $contract, after the held reading")
            } finally { r.close() }
        }
    }

    /**
     * L2. The count's base is read BEFORE a session's first cache write, so the first batch is judged
     * like every other (until 0.64 the first reading came after the first batch and could only
     * re-base: the first upload of every session was never judged), and nothing is written while
     * that reading is on its way. In a list window, so the base is `pumpAtlas`'s to ask.
     * Mutations watched: the base backstop removed from `pumpAtlas` (the chunks go at once); the
     * "a batch is read at its end" line removed from `pumpLedger` (a reading after every message).
     */
    @Test
    fun theCountsBaseIsReadBeforeTheFirstCacheWriteAndNothingIsWrittenUnderAReading(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            r.up(enable = false)
            val gate = CompletableDeferred<Unit>()
            r.spy.holdCounts = gate
            r.shell.updateSettings { it.copy(cachedText = "on") }
            r.until("the base is asked for before anything is written") { r.spy.countsAsked.get() >= 1 }
            r.settle("with the base held")
            barrier(r); delay(300); barrier(r)
            assertEquals(0, r.spy.atlasFlushes(), "no cache write before the count's base: ${r.notes("atlas").takeLast(3)}")
            assertTrue(r.notes("atlas").any { "chunk(s) queued" in it }, "the upload is cut and waits for it")
            gate.complete(Unit); r.spy.holdCounts = null
            r.atlasIdle("after the base")
            val t = r.spy.trace()
            assertEquals("count", t.first(), "the base's answer came before the first ATLAS flush: ${t.take(6)}")
            val first = r.count("count ok").firstOrNull() ?: throw AssertionError("the first batch was not judged: ${r.notes("atlas").takeLast(5)}")
            val judged = Regex("count ok — (\\d+) atlas message").find(first)!!.groupValues[1].toInt()
            assertEquals(t.drop(1).takeWhile { it == "atlas" }.size, judged, "the session's first reading after the base judges the whole first batch: $first, trace ${t.take(8)}")
            // a batch is read at its END: the first upload is several messages, and no reading of the
            // count is taken between two of them (each would hold the lane for its round trip)
            val queued = Regex("(\\d+) chunk\\(s\\) queued").find(r.notes("atlas").first { "chunk(s) queued" in it })!!.groupValues[1].toInt()
            assertTrue(queued >= 2, "the first upload is a batch of several messages: $queued")
            assertTrue(t.drop(1).takeWhile { it == "atlas" }.size >= queued, "no reading of the count between the first batch's $queued messages: ${t.take(queued + 3)}")
            assertTrue(r.count("count re-based").isEmpty(), "nothing was acked before the base: ${r.count("count re-based")}")
        } finally { r.close() }
    }

    /**
     * L3. The ruling's case, on both contracts, against a shell that never touches the cache: the
     * extension for `Q` is acked and not counted (refused whole on the glasses). Nothing is gated —
     * the face gains `Q` at once and the next row draws it from an entry the glasses do not hold
     * (belief != glass there, the observable of the missing write). Two settled short readings
     * later the journal and a notice say it, the message's ranges are written ONCE more, the count
     * agrees, one keyframe puts the lens right, and nothing else changed: the face is live, cached
     * text is on, no third send, no second notice.
     * Mutations watched: no `resend()` call (the message is not sent again); the keyframe after a
     * counted re-send removed (no keyframe; belief != glass stands); the shortfall switching cached text off.
     */
    @Test
    fun aWriteTheGlassesDidNotCountIsSaidAndSentOnceMore(): Unit = runBlocking {
        for (contract in intArrayOf(1, 2)) {
            val r = rig(contract, pinClock = true); val ref = rig(contract, cached = false, pinClock = true)
            try {
                val n = warm(r)
                ref.up(); repeat(r.notches) { ref.notch("warm, in step") }
                r.spy.breakAtlas = setOf(n + 1)                // the extension for Q: acked, refused whole, never counted
                ref.win.suffix = "abQ"; ref.notch("the notch that reveals Q")
                val msg = reveal(r, "abQ", n)
                assertTrue(r.count("count short —").isEmpty(), "contract $contract: the promotion did not wait for the count")
                r.notch("a row that draws Q from the cache"); ref.notch("a row that draws Q")
                assertTrue(r.spy.drawsCarrying('Q') > 0, "contract $contract: Q ships in a cached draw before the count has spoken")
                assertTrue(r.glassDiffersFromBelief() > 0, "contract $contract: the glasses do not hold Q's entry — belief != glass on that row")
                val kf = r.spy.keyframes()

                r.until("contract $contract: the shortfall is in the journal") { r.count("count short — RIGHT's generation is").isNotEmpty() }
                assertTrue(r.count("count short —").single().let { "1 short of 1 cache write(s)" in it && "1 atlas message(s)" in it && "go once more" in it }, r.count("count short —").toString())
                r.until("contract $contract: and it is a notice") { r.cacheNotices().any { it.thread == "cache/short" } }
                val box = r.cacheNotices().first { it.thread == "cache/short" }
                assertEquals("DAMAGE · cache", box.source)
                assertEquals("the glasses acknowledged 1 cache write(s) and counted 0 — 1 sent once more", box.body)
                r.until("contract $contract: the message's ranges went once more") { r.spy.sends(msg) == 2 }
                r.until("contract $contract: the re-send was counted") { r.count("re-send counted — 1 atlas message(s)").isNotEmpty() }
                r.atlasIdle("after the re-send")
                r.dismissNotices()
                // the lens first: without the keyframe the row drawn from the missing entry stays wrong
                // (belief equals the truth there, so no repaint sends a difference)
                r.assertGlassMatchesBelief("contract $contract, after the re-send's keyframe")
                sameContent(r, ref, "contract $contract, after the re-send")
                assertEquals(kf + 1, r.spy.keyframes(), "contract $contract: one keyframe, not more")
                assertTrue(r.font in r.shell.cachedFontsLive && r.shell.cachedTextActive, "contract $contract: a shortfall never takes the fonts down")
                assertFalse(r.notes("atlas").any { "cached text off" in it }, "contract $contract: ${r.notes("atlas").takeLast(4)}")

                delay(2 * wm.damage.core.shell.CacheLedger.SETTLE_MS + 600)
                r.settle("two settle windows on")
                assertEquals(2, r.spy.sends(msg), "contract $contract: sent twice, never a third time")
                assertTrue(r.count("count short again").isEmpty() && r.cacheNotices().isEmpty(), "contract $contract: and no second notice: ${r.cacheNotices()}")
                assertEquals(1, r.count("count short —").size, "contract $contract: one shortfall")
            } finally { r.close(); ref.close() }
        }
    }

    /**
     * L4. The re-send is not counted either (every flush carrying the message's write list is
     * refused on the glasses): a second journal note and a second notice (`cache/lost`) say so, and
     * NOTHING more is sent for it — the bound is one re-send per message. The first-time messages
     * read in the same interval (the first notice's own faces gaining glyphs) are not sent again:
     * the re-sent message alone accounts for what is missing. The fonts stay live.
     * Mutations watched: an ack never marked a re-send (a third send, and no second note); the
     * suspects rule removed (the notice's own messages go again: a second `count short —`); the
     * second notice's wording not following the interval (stated as fact with other writes read
     * beside the re-send, or as the count with none).
     */
    @Test
    fun aReSendTheGlassesDidNotCountEitherIsSaidAndNothingMoreIsSent(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            r.spy.breakAtlas = setOf(n + 1)
            val msg = reveal(r, "abQ", n)
            r.spy.breakCarrying = msg                          // and every re-send of it
            r.until("the shortfall") { r.count("count short — RIGHT's generation is").isNotEmpty() }
            r.until("the message went once more") { r.spy.sends(msg) == 2 }
            r.until("the re-send was not counted either, and the journal says so") { r.count("count short again").isNotEmpty() }
            r.until("a second notice") { r.cacheNotices().any { it.thread == "cache/lost" } }
            val lost = r.cacheNotices().first { it.thread == "cache/lost" }
            // stated as fact only when the interval that judged the re-send held nothing else; otherwise
            // worded as the count. The interval is the ATLAS messages submitted between the two verdicts'
            // notes (the first verdict re-based the count with both lanes still): the re-send alone, or
            // the re-send and the first notice's own glyph uploads, whichever this run's order made it
            val an = r.notes("atlas")
            val j2 = an.indexOfFirst { "\"detail\":\"count short — RIGHT's generation is" in it }
            val j3 = an.indexOfFirst { "\"detail\":\"count short again" in it }
            val k = an.subList(j2, j3).count { "\"detail\":\"chunk of " in it }
            assertTrue(k >= 1, "the re-send is in the interval: ${an.subList(j2, j3 + 1)}")
            if (k == 1) {
                assertTrue("count short again — 1 atlas message(s) sent twice were not counted either time" in an[j3], an[j3])
                assertEquals("a cache write sent twice was not counted either time — it is not sent again; text from the cache may draw wrong until the next session", lost.body)
            } else {
                assertTrue("count short again — the count is 1 short after a re-send (1 re-sent, ${k - 1} other write(s) read with it)" in an[j3], an[j3])
                assertEquals("the count is short again after a re-send (1 re-sent, ${k - 1} other write(s) read with it) — nothing more is sent for the re-sent message(s)", lost.body)
            }
            delay(2 * wm.damage.core.shell.CacheLedger.SETTLE_MS + 600)
            r.settle("two settle windows on")
            assertEquals(2, r.spy.sends(msg), "sent twice, never a third time")
            assertEquals(1, r.count("count short —").size, "round two sent nothing again: ${r.count("count short")}")
            assertEquals(1, r.count("count short again").size)
            val lists = r.spy.atlasWrites()
            for (l in lists.distinct()) if (!l.containsAll(msg)) assertEquals(1, lists.count { it == l }, "no other write list went twice: $l")
            assertTrue(r.count("re-send counted").isEmpty())
            assertTrue(r.font in r.shell.cachedFontsLive && r.shell.cachedTextActive, "the atlas stays live")
            assertFalse(r.notes("atlas").any { "cached text off" in it })
        } finally { r.close() }
    }

    /**
     * L5. A decode slower than two settle windows: nothing is refused, the readings lag one behind
     * until the re-send's batch goes out. The shortfall is said and the message sent once more —
     * and the next reading is one OVER: the first send had landed after all. That is said as
     * "landed late", with no keyframe (nothing was drawn ahead of the bytes) and no second notice.
     * Mutation watched: the over-count after a re-send handled as a counted re-send (a keyframe, and the wrong note).
     */
    @Test
    fun aBumpThatLandsAfterTheReSendIsSaidAsLateNotAsLost(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            r.spy.lagThroughAtlas = n + 1                      // every reading one short until the next cache write after Q's
            val msg = reveal(r, "abQ", n)
            r.until("two settled readings one short: a shortfall") { r.count("count short — RIGHT's generation is").isNotEmpty() }
            val kf = r.spy.keyframes()
            r.until("the message went once more") { r.spy.sends(msg) == 2 }
            r.until("the count reads one over: said as late") { r.count("re-send landed late — 1 more bump(s)").isNotEmpty() }
            r.atlasIdle("after the late bump")
            assertTrue(r.count("re-send counted").isEmpty(), "not as a counted re-send: ${r.count("re-send")}")
            assertEquals(kf, r.spy.keyframes(), "no keyframe: nothing was lost")
            assertEquals(listOf("cache/short"), r.cacheNotices().map { it.thread }, "one notice, no second")
            r.dismissNotices()
            r.notch("Q cached"); r.assertGlassMatchesBelief("after a late bump")
        } finally { r.close() }
    }

    /**
     * L6. The link ends with the re-send's ack owed. The rebuild keeps the atlas, the re-send goes
     * again (still marked a re-send: it is never queued a third time on its own account), the new
     * session's count agrees and the keyframe follows.
     * Mutation watched: a rewind that leaves a re-send marked handed out (it never goes again).
     */
    @Test
    fun aReSendOwedAtALinkEndGoesAgainUnderTheKeptAtlas(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            r.spy.breakAtlas = setOf(n + 1)
            val msg = reveal(r, "abQ", n)
            r.spy.dropDoneCarrying = msg                       // the re-send leaves and its completion never arrives
            r.until("the shortfall") { r.count("count short — RIGHT's generation is").isNotEmpty() }
            r.until("the re-send left") { r.spy.sends(msg) == 2 }
            r.spy.dropDoneCarrying = null
            r.clock += 20_000L
            r.lab.endLink("test: supervision timeout")
            r.until("the second session drives") { r.keeper.attempts == 2 && r.driving() }
            r.until("the glasses saw the second connect") { r.sim.preludeAcks == 2 }
            assertTrue(r.notes("atlas").any { "kept across the rebuild" in it }, "kept: ${r.notes("atlas").takeLast(5)}")
            r.until("the re-send goes again under the kept atlas") { r.spy.sends(msg) == 3 }
            r.until("and the new session's count agrees") { r.count("re-send counted").isNotEmpty() }
            r.atlasIdle("after the kept rebuild")
            assertEquals(3, r.spy.sends(msg))
            r.notch("Q cached")
            assertTrue(r.spy.drawsCarrying('Q') >= 1)
            r.assertGlassMatchesBelief("after the kept rebuild's re-send")
        } finally { r.close() }
    }

    /**
     * L7. A reading answered in the last session and landing in this one is dropped: it clears no
     * flag but its own (the new session's base is still out, on a gate of its own) and is applied
     * to nothing. Two gates, so the window between the stale answer and the new one is the test's.
     * Mutations watched: the epoch check removed (the old counts re-base the new session and a
     * cache write leaves under its base); the flag cleared before the epoch check (a second
     * reading is launched under the first).
     */
    @Test
    fun aCountsReadingFromTheLastSessionIsDropped(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            val old = CompletableDeferred<Unit>()
            r.spy.holdCountAnswers = old                       // Q's reading: answered by the glasses, held on its way back
            val taken = r.spy.countsTaken.get()
            reveal(r, "abQ", n)
            r.until("the reading's answer is taken and held") { r.spy.countsTaken.get() > taken }
            r.spy.holdCountAnswers = null
            val fresh = CompletableDeferred<Unit>()
            r.spy.holdCounts = fresh                           // the next session's readings wait on their own gate
            r.clock += 20_000L
            r.lab.endLink("test: supervision timeout")
            r.until("the second session drives") { r.keeper.attempts == 2 && r.driving() }
            r.until("the glasses saw the second connect") { r.sim.preludeAcks == 2 }
            r.settle("the second session")
            val asked = r.spy.countsAsked.get()
            val queued = r.shell.atlasExtensionsQueued
            r.win.suffix = "abQZ"
            r.notch("a character new to the kept atlas")
            r.until("its extension is cut") { r.shell.atlasExtensionsQueued > queued }
            r.until("the new session's base is asked for (and held)") { r.spy.countsAsked.get() == asked + 1 }
            r.settle("the base out")
            val sent = r.spy.atlasFlushes()
            val notes = r.notes("atlas").size
            old.complete(Unit)                                 // the old session's answer lands in the new one
            barrier(r); delay(300); barrier(r)
            assertEquals(sent, r.spy.atlasFlushes(), "no cache write: the new session's base is still out")
            assertEquals(asked + 1, r.spy.countsAsked.get(), "and no second reading was launched under it")
            assertEquals(notes, r.notes("atlas").size, "the stale answer was applied to nothing: ${r.notes("atlas").drop(notes)}")
            fresh.complete(Unit); r.spy.holdCounts = null
            r.until("Z's message follows the new session's own base") { r.spy.atlasFlushes() > sent }
            r.atlasIdle("after the base")
            r.notch("Z cached"); r.assertGlassMatchesBelief("after the dropped reading")
        } finally { r.close() }
    }

    /**
     * L8. Silent Mode and a lapse end the question. (a) Asleep between two readings: nothing is read
     * and nothing written while asleep; the wake's session uploads afresh, its first batch is
     * judged, and the shortfall of the session before is never said. (b) Asleep with the deciding
     * reading already answered and on its way back: it is NOT applied while asleep — no verdict
     * note, no notice (one saved there would be restored by the wake's start about a session that
     * is gone). (c) The lease lapses with the count open: the rebuild that follows a lapse on a
     * Damage build starts the count over.
     * Mutations watched: (a) the asleep term removed from the reader (a reading is asked while
     * asleep); (b) the drop removed from `enterSilentGlasses` (the journal and a notice say the shortfall).
     * (c) is end to end only: a session start follows the lapse on every build whose count is
     * read, and resets the same state — the lapse's own reset has no pin of its own.
     */
    @Test
    fun silentModeAndALapseEndTheQuestion(): Unit = runBlocking {
        for (variant in listOf("asleep between readings", "asleep under the deciding reading", "a lapse")) {
            val r = rig(2, pinClock = true)
            try {
                val n = warm(r)
                r.spy.breakAtlas = setOf(n + 1)
                reveal(r, "abQ", n)
                r.until("$variant: the first settled reading is short") { r.count("count short (settled reading 1)").isNotEmpty() }
                val ok = r.count("count ok").size
                when (variant) {
                    "asleep between readings" -> {
                        r.sim.setSilent(true)
                        r.until("asleep") { r.shell.glassesAsleep }
                        barrier(r)
                        val asked = r.spy.countsAsked.get(); val sent = r.spy.atlasFlushes()
                        delay(wm.damage.core.shell.CacheLedger.SETTLE_MS + 800)      // the deciding reading would be due inside this
                        assertEquals(asked, r.spy.countsAsked.get(), "no reading while asleep")
                        assertEquals(sent, r.spy.atlasFlushes(), "and no cache write")
                    }
                    "asleep under the deciding reading" -> {
                        val gate = CompletableDeferred<Unit>()
                        r.spy.holdCountAnswers = gate
                        val taken = r.spy.countsTaken.get()
                        r.until("the deciding reading's answer is taken and held") { r.spy.countsTaken.get() > taken }
                        r.sim.setSilent(true)
                        r.until("asleep") { r.shell.glassesAsleep }
                        r.spy.holdCountAnswers = null; gate.complete(Unit)
                        barrier(r); delay(300); barrier(r)
                    }
                    else -> {
                        r.spy.loseLease()
                        r.until("the shell saw the lapse") { r.notes("atlas").any { "the lease lapsed" in it } }
                    }
                }
                assertTrue(r.count("count short —").isEmpty(), "$variant: no shortfall is said: ${r.count("count short")}")
                assertTrue(r.cacheNotices().isEmpty(), "$variant: and no notice: ${r.cacheNotices()}")
                // the wake / the rebuild: a new session, a fresh upload, its first batch judged
                val attempts = r.keeper.attempts
                if (variant == "a lapse") {
                    r.until("the rebuild that follows a lapse on a Damage build") { r.keeper.attempts > attempts && r.driving() }
                    r.spy.leaseBack()
                } else {
                    r.sim.setSilent(false)
                    r.until("awake in a new session") { r.keeper.attempts > attempts && r.driving() && !r.shell.glassesAsleep }
                }
                r.atlasIdle("$variant: the new session")
                r.until("$variant: the new session's upload is judged") { r.count("count ok").size > ok }
                delay(2 * wm.damage.core.shell.CacheLedger.SETTLE_MS + 600)
                assertTrue(r.count("count short —").isEmpty(), "$variant: the question ended with the session: ${r.count("count short")}")
                assertTrue(r.cacheNotices().isEmpty(), "$variant: ${r.cacheNotices()}")
                r.notch("drawing"); r.assertGlassMatchesBelief("$variant, in the new session")
            } finally { r.close() }
        }
    }

    /**
     * L9. With `Cached text` off nothing is read (and nothing sent); the writes acked before it
     * stay on the books and the question is answered when the row is back on: the shortfall is
     * said, the message goes once more and is counted. (The number of messages in the interval is
     * not asserted: the row going off rewinds the hand-out, and what goes again with it depends on
     * what was still on its way.)
     * Mutation watched: the row's term removed from the reader (readings go on while it is off).
     */
    @Test
    fun withTheRowOffNothingIsReadAndTheQuestionIsAnsweredWhenItIsBack(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            r.spy.breakAtlas = setOf(n + 1)
            val msg = reveal(r, "abQ", n)                      // after the promotion: the extension is the face
            r.shell.updateSettings { it.copy(cachedText = "off") }
            r.until("off") { r.notes("atlas").any { "cached text off" in it } }
            r.settle("off"); delay(700); barrier(r)            // a reading already on its way lands
            val asked = r.spy.countsAsked.get(); val sent = r.spy.atlasFlushes()
            delay(2 * wm.damage.core.shell.CacheLedger.SETTLE_MS + 500)
            assertEquals(asked, r.spy.countsAsked.get(), "no reading of the count while the row is off")
            assertEquals(sent, r.spy.atlasFlushes(), "and no cache write")
            assertTrue(r.count("count short —").isEmpty() && r.cacheNotices().isEmpty(), "nothing is said meanwhile")
            r.shell.updateSettings { it.copy(cachedText = "on") }
            r.until("the row is back: the shortfall is said") { r.count("count short — RIGHT's generation is").isNotEmpty() }
            r.until("the message goes once more") { r.spy.sends(msg) >= 2 }
            r.until("and is counted") { r.count("re-send counted").isNotEmpty() }
            r.atlasIdle("after the row came back")
            r.dismissNotices()
            r.notch("Q cached"); r.assertGlassMatchesBelief("after off and on")
        } finally { r.close() }
    }

    /**
     * L10. A mid-session reset (another writer took the cache) with a message acked and not yet
     * judged: the message leaves the books unjudged — the journal says so — and a keyframe puts
     * right whatever a draw already took from it; the count re-bases and no shortfall is said for a
     * layout that is gone. The repack's twin is `AtlasRepackTest.aRepackIsACleanBoundaryForTheCount`.
     * Mutations watched: the keyframe removed from `atlasCheckOwed` (belief != glass stands); the
     * clear and the re-base removed (a shortfall is said for the dropped atlas).
     */
    @Test
    fun aMidSessionResetDropsTheUnjudgedMessagesAndKeyframes(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            r.spy.breakAtlas = setOf(n + 1)
            val gate = CompletableDeferred<Unit>()
            r.spy.holdCounts = gate                            // Q's message stays unjudged for as long as the test says
            reveal(r, "abQ", n)
            r.notch("a row that draws Q from the cache")
            assertTrue(r.glassDiffersFromBelief() > 0, "the glasses do not hold Q's entry: belief != glass on that row")
            val kf = r.spy.keyframes()
            // a cache write that is not this shell's, into the live cache (the self-test's `live:` step):
            // one mode-19 write of four zero bytes far above the atlas
            val foreign = wm.damage.core.wire.CfwModes.cacheUpdate2(listOf(wm.damage.core.wire.CfwModes.CacheWrite2(20_000, ByteArray(4))), 160 * 1024)
            r.lab.devProbe("selftest", "live:" + foreign.joinToString("") { "%02x".format(it) })
            r.until("the atlas is dropped") { r.notes("atlas").any { "mid-session — the atlas is dropped" in it } }
            r.until("the unjudged message leaves the books, said") {
                r.count("count re-based — 1 atlas message(s) acked before it are not judged (the layout was replaced); keyframing").isNotEmpty() }
            r.until("a keyframe") { r.spy.keyframes() > kf }
            r.settle("after the reset")
            r.assertGlassMatchesBelief("after the reset's keyframe")
            gate.complete(Unit); r.spy.holdCounts = null
            barrier(r)
            delay(2 * wm.damage.core.shell.CacheLedger.SETTLE_MS + 800)
            assertTrue(r.count("count short").isEmpty(), "the count re-based: nothing is called short for a layout that is gone: ${r.count("count short")}")
            assertTrue(r.cacheNotices().isEmpty(), "and no notice: ${r.cacheNotices()}")
            assertEquals(0L, r.shell.cacheCountUnjudged)
        } finally { r.close() }
    }

    /**
     * L11. A shortfall whose verdict lands on a link that is ending is in the journal and queues its
     * re-send, and raises no notice: the cell's word already says the link is going, and the box
     * would wait in the queue about a session that is over.
     * Mutation watched: the notice raised without the link check.
     */
    @Test
    fun aShortfallOnALinkThatIsEndingIsJournaledAndNotANotice(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            r.spy.breakAtlas = setOf(n + 1)
            reveal(r, "abQ", n)
            r.until("the first settled reading is short") { r.count("count short (settled reading 1)").isNotEmpty() }
            val gate = CompletableDeferred<Unit>()
            r.spy.holdCountAnswers = gate
            val taken = r.spy.countsTaken.get()
            r.until("the deciding reading's answer is taken and held") { r.spy.countsTaken.get() > taken }
            r.spy.showLink { it.copy(connected = false) }      // the link is ending when the verdict lands
            r.spy.holdCountAnswers = null; gate.complete(Unit)
            r.until("the journal has the shortfall") { r.count("count short — RIGHT's generation is").isNotEmpty() }
            barrier(r)
            assertTrue(r.cacheNotices().isEmpty(), "no notice about a session that is ending: ${r.cacheNotices()}")
            r.spy.showLink { it }
        } finally { r.close() }
    }

    /**
     * L12. The `cache=miscount` dev probe (never sent to a radio by a test): one atlas ack the
     * glasses never saw goes on the ledger, and the real path runs end to end on the simulator —
     * the short readings, the journal note, the notice, a re-send of the last acked message's
     * ranges, the count agreeing, one keyframe. Nothing new on the wire: the re-send is the bytes
     * the glasses already hold.
     * Mutation watched: the probe's route removed from the shell's note handler (nothing happens).
     */
    @Test
    fun theMiscountProbeDrivesTheWholePathOnTheSimulator(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            val msg = reveal(r, "abQ", n)
            r.atlasIdle("Q counted")
            val kf = r.spy.keyframes()
            r.lab.devProbe("cache", "miscount")
            r.until("the probe is on the ledger") { r.notes("probe").any { "cache=miscount — one atlas ack the glasses never saw" in it } }
            r.until("the shortfall is in the journal") { r.count("count short — RIGHT's generation is").isNotEmpty() }
            r.until("and a notice") { r.cacheNotices().any { it.thread == "cache/short" && it.body.endsWith("1 sent once more") } }
            r.until("the last acked message's ranges go once more") { r.spy.sends(msg) == 2 }
            r.until("the re-send is counted") { r.count("re-send counted").isNotEmpty() }
            r.until("one keyframe") { r.spy.keyframes() > kf }
            r.atlasIdle("after the probe")
            assertEquals(kf + 1, r.spy.keyframes())
            r.dismissNotices()
            r.notch("Q cached"); r.assertGlassMatchesBelief("after the probe")
        } finally { r.close() }
    }

    /**
     * L13. A late bump that does not cover the shortfall still gets its keyframe. Two messages in
     * one interval: the first is refused on the glasses, the second lands and its bump is read
     * late. The count is two short of two; both go once more; the next reading is one over. One of
     * the two was absent until the re-send wrote it, and the lens kept what was drawn from it
     * meanwhile — so this is said as a counted re-send, with the keyframe, not as "landed late".
     * Mutation watched: any over-count after a re-send read as "landed late" (no keyframe; belief != glass).
     */
    @Test
    fun aLateBumpThatDoesNotCoverTheShortfallStillKeyframes(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            r.spy.breakAtlas = setOf(n + 1)                    // Q's extension: refused whole
            r.spy.lagThroughAtlas = n + 2                      // and every reading one short until the re-sends go out
            val q = reveal(r, "abQ", n)
            val z = reveal(r, "abQZ", n + 1)                   // Z's extension lands; its bump is the one read late
            r.notch("a row that draws Q from the cache")
            assertTrue(r.glassDiffersFromBelief() > 0, "the glasses do not hold Q's entry")
            val kf = r.spy.keyframes()
            r.until("two short of two") { r.count("count short — RIGHT's generation is").any { "2 short of 2 cache write(s)" in it && "2 atlas message(s)" in it } }
            r.until("both go once more") { r.spy.sends(q) == 2 && r.spy.sends(z) == 2 }
            r.until("one over, and one of the two was absent until the re-send: counted, with the keyframe") {
                // (the two messages' ranges ride one re-send message: the note counts re-send messages)
                r.count("re-send counted — ").any { "sent a second time were counted by RIGHT, and 1 of the 2 write(s) called short bumped late; keyframing" in it } }
            r.until("the keyframe") { r.spy.keyframes() > kf }
            r.atlasIdle("after the re-sends")
            assertTrue(r.count("re-send landed late").isEmpty(), "not said as late: ${r.count("re-send")}")
            r.dismissNotices()
            r.assertGlassMatchesBelief("after the keyframe")
        } finally { r.close() }
    }

    /**
     * L14 (the verify stage; the review's "no pin at all" list). A re-send acked and not yet judged,
     * and then a cache write whose ack never came: what landed is unknown, the next reading can only
     * re-base, and the re-send's outcome can no longer be read. The messages leave the books
     * unjudged, the journal says a re-send was among them, and ONE keyframe puts right the row that
     * was drawn from the entry the glasses did not hold (belief equals the truth there, so no
     * repaint would send a difference). No verdict is made up for the re-send: neither "counted"
     * nor "short again".
     * Mutation watched: the keyframe removed from the re-base that swallowed a re-send.
     */
    @Test
    fun aReBaseThatSwallowsAReSendKeyframes(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            r.spy.breakAtlas = setOf(n + 1)                    // Q's extension: acked, refused whole, never counted
            val msg = reveal(r, "abQ", n)
            r.notch("a row that draws Q from the cache")
            assertTrue(r.glassDiffersFromBelief() > 0, "the glasses do not hold Q's entry: belief != glass on that row")
            // every ATLAS completion waits from here, so the re-send's ack is the test's to release
            // and what follows is set up with nothing racing it
            val acks = CompletableDeferred<Unit>()
            r.spy.holdAtlasDone = acks
            r.until("the shortfall") { r.count("count short — RIGHT's generation is").isNotEmpty() }
            r.until("the re-send left") { r.spy.sends(msg) == 2 }
            val kf = r.spy.keyframes()
            val pending = r.count("count pending").size
            r.spy.lagReads = true                              // its reading stays one short: pending, and the lane is open between readings
            r.spy.holdAtlasDone = null; acks.complete(Unit)
            r.until("the re-send is acked and its reading is pending") { r.count("count pending").size > pending }
            r.spy.failNextAtlas = true                         // the next cache write's ack never comes
            r.win.suffix = "abQZ"
            r.notch("a character whose message is lost")
            r.until("the write is lost") { r.notes("atlas").any { "not delivered" in it } }
            r.until("the re-base says a re-send was among the unjudged") {
                r.count("count re-based — ").any { "a write was lost before the reading" in it && "a re-send among them: keyframing" in it } }
            r.until("a keyframe") { r.spy.keyframes() > kf }
            r.atlasIdle("after the re-base")
            assertEquals(kf + 1, r.spy.keyframes(), "one keyframe")
            assertTrue(r.count("re-send counted").isEmpty() && r.count("count short again").isEmpty(),
                "no verdict is made up for the re-send: ${r.count("re-send") + r.count("count short again")}")
            assertEquals(1, r.count("count short —").size, "one shortfall")
            r.dismissNotices()
            r.assertGlassMatchesBelief("after the re-base's keyframe")
        } finally { r.close() }
    }

    /**
     * L15 (the verify stage; F12's rule swept over its class). An extra bump with no strip involved
     * is the count's own note. The base is read one low here (a lagged reading: a decode of the
     * session before still on its way), so the first batch reads one over: re-based, nothing called
     * lost, no notice — and the note is kind `atlas` under the head the report keys on. A `stage`
     * note means a strip was involved; until this pass the over-count was the one verdict still
     * written there from a list window.
     * Mutation watched: the over-count always written as staging's note.
     */
    @Test
    fun anExtraBumpWithNoStripInvolvedIsTheCountsOwnNote(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            r.spy.lagThroughAtlas = 0                          // the base alone reads one short; every reading after the first cache write is true
            val n = warm(r)
            r.until("the first batch reads one over, said as the count's own note") {
                r.count("count over — the cache count read 1 more generation bump(s) than this shell's writes").isNotEmpty() }
            assertTrue(r.notes("stage").none { "more generation bump" in it }, "no strip is involved: not staging's note: ${r.notes("stage")}")
            assertTrue(r.count("count short").isEmpty() && r.cacheNotices().isEmpty(), "an extra bump calls nothing lost: ${r.count("count short")} ${r.cacheNotices()}")
            reveal(r, "abQ", n)
            r.atlasIdle("after the over-count")
            assertTrue(r.count("count ok — 1 atlas message(s) counted by RIGHT").isNotEmpty(), "the count goes on from the new base: ${r.notes("atlas").takeLast(4)}")
            assertEquals(1, r.count("count over").size, "one over-count, said once")
            r.notch("Q cached"); r.assertGlassMatchesBelief("after an over-count")
        } finally { r.close() }
    }

    /**
     * L16 (the verify stage; F7's first statement, its other branch). With nothing else read in the
     * interval that judges it, a re-send that was not counted either is stated as FACT: the same
     * write, sent twice, counted neither time. The first shortfall's box is dismissed here, which
     * mutes its class for the episode, so the second shortfall paints nothing and uploads no glyphs
     * of its own — the re-send is alone in its interval. (`aReSendTheGlassesDidNotCountEither…`
     * holds the wording to the interval whichever shape its run has; this pins the shape.)
     * Mutation watched: the second notice never stated as fact.
     */
    @Test
    fun aReSendAloneInItsIntervalAndNotCountedIsStatedAsFact(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val n = warm(r)
            // round one: Q's extension is not counted and its re-send is; the box is dismissed
            r.spy.breakAtlas = setOf(n + 1)
            reveal(r, "abQ", n)
            r.until("round one: the re-send is counted") { r.count("re-send counted").isNotEmpty() }
            r.atlasIdle("round one")
            r.dismissNotices()                                 // mutes `cache/short` for the episode: the next shortfall paints no box
            r.atlasIdle("after the dismissal")
            // round two: Z's extension and its re-send are both refused on the glasses
            val m = r.spy.atlasFlushes()
            r.spy.breakAtlas = setOf(m + 1)
            val msg = reveal(r, "abQZ", m)
            r.spy.breakCarrying = msg                          // and every re-send of it
            r.until("round two: the re-send was not counted either") { r.count("count short again").isNotEmpty() }
            val an = r.notes("atlas")
            val j2 = an.indexOfLast { "\"detail\":\"count short — RIGHT's generation is" in it }
            val j3 = an.indexOfFirst { "\"detail\":\"count short again" in it }
            assertTrue(j2 in 0 until j3, "the second shortfall precedes it: ${an.takeLast(6)}")
            assertEquals(1, an.subList(j2, j3).count { "\"detail\":\"chunk of " in it }, "the re-send is alone in its interval: ${an.subList(j2, j3 + 1)}")
            assertTrue("count short again — 1 atlas message(s) sent twice were not counted either time" in an[j3], an[j3])
            r.until("the second notice") { r.cacheNotices().any { it.thread == "cache/lost" } }
            assertEquals("a cache write sent twice was not counted either time — it is not sent again; text from the cache may draw wrong until the next session",
                r.cacheNotices().first { it.thread == "cache/lost" }.body)
            assertEquals(2, r.spy.sends(msg), "sent twice, never a third time")
            assertTrue(r.font in r.shell.cachedFontsLive && r.shell.cachedTextActive, "the atlas stays live")
        } finally { r.close() }
    }

    // ------------------------------------------------------------------------------------------------
    // 0.64 R1 (review 1a, dimension C): what a shortfall's re-send leaves behind when it cannot be judged.

    /** The scene the three pins below share: `Q`'s extension is acked and not counted, a row draws `Q`
     *  from the entry the glasses do not hold (belief != glass: the observable), and the shortfall queues
     *  its ONE re-send. Returns the extension's write list and the keyframes so far. */
    private suspend fun aRowDrawnFromAnEntryTheGlassesDoNotHold(r: Rig, arm: (List<Pair<Int, Int>>) -> Unit): Pair<List<Pair<Int, Int>>, Int> {
        val n = warm(r)
        r.spy.breakAtlas = setOf(n + 1)                    // Q's extension: acked, refused whole, never counted
        val msg = reveal(r, "abQ", n)
        r.notch("a row that draws Q from the cache")
        assertTrue(r.glassDiffersFromBelief() > 0, "the glasses do not hold Q's entry: belief != glass on that row")
        val kf = r.spy.keyframes()
        arm(msg)                                           // what becomes of the re-send's completion: read at its submit
        r.until("the shortfall") { r.count("count short — RIGHT's generation is").isNotEmpty() }
        r.until("the re-send left") { r.spy.sends(msg) == 2 }
        return msg to kf
    }

    /**
     * RC-1, L10's twin. A re-send that is OWED — here still in the air, its completion never arriving —
     * when the layout it belongs to is replaced mid-session (another writer took the cache). The
     * shortfall re-based the count and emptied the books, so nothing is "acked and unjudged" at the
     * reset; until this round the reset then said nothing and asked for no keyframe, and the row drawn
     * from the entry the glasses never held stayed as it was (belief equals the truth there: no repaint
     * sends a difference). Now the journal says the re-send left with its layout, and a keyframe follows.
     * The guard in the middle is against a rescue: were any message acked and unjudged at the reset
     * (the notice's own glyph upload going out ahead of the re-send), the older clause would keyframe
     * and this pin would pass for the wrong reason — it fails as a rig fault instead.
     * The repack's form is the same line (`atlasCheckOwed`); it is not reachable cheaply in a rig (the
     * re-send must still be unsubmitted when the repack runs).
     * Mutation watched: the re-send's term removed from `atlasCheckOwed` (no keyframe follows).
     */
    @Test
    fun aReSendOwedAtAMidSessionResetIsSaidAndKeyframes(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val (_, kf) = aRowDrawnFromAnEntryTheGlassesDoNotHold(r) { r.spy.dropDoneCarrying = it }   // L6's hook: only the re-send's completion is lost
            barrier(r)
            assertEquals(0L, r.shell.cacheCountUnjudged, "rig: nothing is acked and unjudged at the reset — the older clause must not be what keyframes: ${r.notes("atlas").takeLast(5)}")
            assertTrue(r.count("count re-based").isEmpty(), "rig: nothing has left the books yet: ${r.count("count re-based")}")
            assertEquals(kf, r.spy.keyframes(), "rig: no keyframe before the reset")
            val foreign = wm.damage.core.wire.CfwModes.cacheUpdate2(listOf(wm.damage.core.wire.CfwModes.CacheWrite2(20_000, ByteArray(4))), 160 * 1024)
            r.lab.devProbe("selftest", "live:" + foreign.joinToString("") { "%02x".format(it) })
            r.until("the atlas is dropped") { r.notes("atlas").any { "mid-session — the atlas is dropped" in it } }
            r.until("a keyframe") { r.spy.keyframes() > kf }
            assertEquals(1, r.count("re-send unjudged — the re-send owed after a shortfall left with its layout (the layout was replaced); keyframing").size,
                "and the journal says what left: ${r.notes("atlas").takeLast(5)}")
            assertTrue(r.count("count re-based").isEmpty(), "no message was acked and unjudged: this is not the older note: ${r.count("count re-based")}")
            r.settle("after the reset")
            r.dismissNotices()
            r.assertGlassMatchesBelief("after the reset's keyframe")
            assertEquals(kf + 1, r.spy.keyframes(), "one keyframe")
        } finally { r.close() }
    }

    /**
     * RC-1's first sibling: the glasses REFUSE the re-send (the image lane's own answer), cached text
     * goes off for the session, and nothing will read the count again — so the re-send that was owed is
     * never judged, and the row drawn from the missing entry is put right by a keyframe at that
     * moment (`atlasDisable` under `atlasFailed`). The row merely switched off is not this: its messages
     * stay on the books and are judged when it is back (L9).
     * Mutation watched: the keyframe removed from `atlasDisable`.
     */
    @Test
    fun aReSendTheGlassesRefuseSwitchesCachedTextOffAndKeyframes(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val (_, kf) = aRowDrawnFromAnEntryTheGlassesDoNotHold(r) { r.spy.refuseDoneCarrying = it }
            r.until("the refusal switches cached text off") { r.notes("atlas").any { "cached text off — the glasses refused a cache write" in it } }
            r.until("a keyframe") { r.spy.keyframes() > kf }
            assertEquals(1, r.count("count ended — cached text is off for this session with ").size, "said once: ${r.notes("atlas").takeLast(5)}")
            r.settle("after the refusal")
            r.dismissNotices()
            r.assertGlassMatchesBelief("after the keyframe")
            assertEquals(kf + 1, r.spy.keyframes(), "one keyframe")
            // said ONCE and keyframed once: the books were emptied where it was said, so a second
            // switch-off under the same failure — the row going off — finds nothing to say (the R1
            // verify stage; watched with the two clears removed: a second note)
            r.shell.updateSettings { it.copy(cachedText = "off") }
            r.until("the row is off") { r.notes("atlas").any { "cached text off — the setting is off" in it } }
            barrier(r); r.settle("the row off")
            assertEquals(1, r.count("count ended — cached text is off for this session with ").size, "said once, whatever switches it off next: ${r.notes("atlas").takeLast(5)}")
        } finally { r.close() }
    }

    /**
     * RC-1's second sibling: the count stops being read (three readings without an answer) while the
     * re-send is still owed — here its first completion never came, so the count's base was owed
     * before it could go again, and that base is what did not answer. The re-send then goes (the
     * atlas's lane does not ask the count) and is acked with nothing left to judge it: the keyframe
     * comes at THAT ack — one asked for when the count went off would go out before the re-send's
     * bytes had landed.
     * Mutation watched: the keyframe removed from the re-send's ack in `atlasDone`.
     */
    @Test
    fun aReSendAckedAfterTheCountWentOffKeyframesAtItsAck(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val (msg, kf) = aRowDrawnFromAnEntryTheGlassesDoNotHold(r) {
                r.spy.failDoneCarrying = it                    // the re-send's ack never comes: a lost write, the base is owed
                r.spy.nullCountsOnFailedDone.set(3)            // and that base is not answered, three times
            }
            r.until("the re-send was not delivered") { r.notes("atlas").any { "not delivered" in it } }
            r.until("the count is off for the session") { r.count("count off — ").isNotEmpty() }
            assertEquals(kf, r.spy.keyframes(), "no keyframe when the count goes off: the re-send has not landed yet")
            r.until("the re-send goes again") { r.spy.sends(msg) == 3 }
            r.until("a keyframe at its ack") { r.spy.keyframes() > kf }
            assertEquals(1, r.count("re-send unjudged — a re-send was acked with the count off for this session").size, "said once: ${r.notes("atlas").takeLast(5)}")
            r.settle("after the re-send")
            r.dismissNotices()
            r.assertGlassMatchesBelief("after the keyframe")
            assertEquals(kf + 1, r.spy.keyframes(), "one keyframe")
            // R2 verify (item 31's field lifetime): the re-send owed ENDS at that ack. Kept past it, the staging
            // pace stayed shut for the session and the next reader of the field (the sleep's `atlasCheckOwed`)
            // said the re-send a second time. Watched with the clear at the ack removed: the sleep said it.
            r.sim.setSilent(true)
            r.until("asleep for the firmware's Silent Mode") { r.shell.glassesAsleep }
            barrier(r)
            assertTrue(r.count("re-send unjudged — the re-send owed after a shortfall").isEmpty(),
                "the re-send owed ended at its ack — the sleep finds nothing owed: ${r.notes("atlas").takeLast(5)}")
        } finally { r.close() }
    }

    /**
     * R2 item 31 (`R1-verify.md` §5.1): the route of the pin above to its last step, and then the re-send's
     * third send is REFUSED by the glasses — its ack never comes. The count went off with the re-send still
     * queued, so its keyframe was left to that ack; the refusal switches cached text off for the session
     * (`atlasFailed` → `atlasDisable`), and with the re-send owed cleared at the count-off that path found
     * nothing owed: no note, no keyframe, and the row drawn from the entry the glasses never held stayed.
     * Mutation watched: the count-off branch clears the re-send owed while its messages are still queued.
     */
    @Test
    fun aReSendAfterTheCountWentOffThatTheGlassesRefuseKeyframes(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val (msg, kf) = aRowDrawnFromAnEntryTheGlassesDoNotHold(r) {
                r.spy.failDoneCarrying = it                    // the re-send's ack never comes: a lost write, the base is owed
                r.spy.nullCountsOnFailedDone.set(3)            // and that base is not answered, three times
                r.spy.refuseOnFailedDone.set(it)               // and the re-send's next send is refused by the glasses
            }
            r.until("the re-send was not delivered") { r.notes("atlas").any { "not delivered" in it } }
            r.until("the count is off for the session") { r.count("count off — ").isNotEmpty() }
            assertEquals(kf, r.spy.keyframes(), "no keyframe when the count goes off: the re-send has not landed yet")
            r.until("the re-send goes again") { r.spy.sends(msg) == 3 }
            r.until("the glasses refused it: cached text is off") { r.notes("atlas").any { "cached text off — the glasses refused a cache write" in it } }
            r.until("a keyframe: the re-send owed will never be acked") { r.spy.keyframes() > kf }
            assertEquals(1, r.count("count ended — cached text is off for this session with ").count { "and a re-send owed" in it },
                "said once, naming the re-send owed: ${r.notes("atlas").takeLast(5)}")
            assertTrue(r.count("re-send unjudged — ").isEmpty(), "no ack of the re-send was seen: ${r.notes("atlas").takeLast(5)}")
            r.settle("after the refusal")
            r.dismissNotices()
            r.assertGlassMatchesBelief("after the keyframe")
            assertEquals(kf + 1, r.spy.keyframes(), "one keyframe")
        } finally { r.close() }
    }

    /**
     * R3 item 4 (review 2, A "Smaller", first line): item 31's sibling at the RE-BASE. The route of the pin
     * above, but the base that the lost write owes IS answered: the count re-bases with the re-send still
     * queued (its first send was not delivered, so it goes again under the new base). Until R3 the
     * re-base cleared the re-send owed there; the re-send's next send is then REFUSED (`atlasFailed` →
     * `atlasDisable`), and that path did not know a re-send was owed. Now the re-base keeps it while the
     * re-send is queued, as the count-off does (R2 item 31), and the disable says it.
     * What this scene can show: the rewind after the lost send re-cuts two messages ahead of the re-send,
     * acked under the new base and unjudged, so the keyframe comes either way; the field is read in the
     * note. Watched on d10f612: "count ended — … with 2 atlas message(s) acked and not judged; keyframing",
     * without "and a re-send owed". (The consequence review 2 named — a strip read in the re-send's
     * interval through `pumpStage`'s hold — is not reached by this rig.)
     */
    @Test
    fun aReSendStillQueuedAtARebaseThatTheGlassesThenRefuseKeyframes(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val (msg, kf) = aRowDrawnFromAnEntryTheGlassesDoNotHold(r) {
                r.spy.failDoneCarrying = it                    // the re-send's ack never comes: a lost write, the base is owed
                r.spy.refuseOnFailedDone.set(it)               // and the re-send's next send is refused by the glasses
            }
            r.until("the re-send was not delivered") { r.notes("atlas").any { "not delivered" in it } }
            r.until("the re-send goes again") { r.spy.sends(msg) == 3 }
            r.until("the glasses refused it: cached text is off") { r.notes("atlas").any { "cached text off — the glasses refused a cache write" in it } }
            assertTrue(r.count("count off — ").isEmpty(), "the count was answered throughout: ${r.notes("atlas").takeLast(5)}")
            r.until("a keyframe: the re-send owed will never be acked") { r.spy.keyframes() > kf }
            assertEquals(1, r.count("count ended — cached text is off for this session with ").count { "and a re-send owed" in it },
                "said once, naming the re-send owed: ${r.notes("atlas").takeLast(5)}")
            r.settle("after the refusal")
            r.dismissNotices()
            r.assertGlassMatchesBelief("after the keyframe")
            assertEquals(kf + 1, r.spy.keyframes(), "one keyframe")
        } finally { r.close() }
    }

    /**
     * RC-1's class, a third site found by the sweep: the re-send is ACKED and its reading is what does
     * not answer — three times, so the count goes off for the session with the re-send on the books
     * and unjudged. The books are emptied there; like the re-base that swallows a re-send (L14), the
     * row drawn from the entry the glasses did not hold is put right by a keyframe, and the note says why.
     * Mutation watched: the keyframe removed from the count-off branch.
     */
    @Test
    fun aReSendAckedAndUnjudgedWhenTheCountGoesOffKeyframes(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            val (_, kf) = aRowDrawnFromAnEntryTheGlassesDoNotHold(r) { r.spy.nullCountsOnSubmitCarrying = it to 3 }
            r.until("the count is off for the session") { r.count("count off — ").isNotEmpty() }
            r.until("a keyframe") { r.spy.keyframes() > kf }
            assertTrue("a re-send was acked and not yet judged: keyframing" in r.count("count off — ").single(), r.count("count off — ").toString())
            r.settle("after the count went off")
            r.dismissNotices()
            r.assertGlassMatchesBelief("after the keyframe")
            assertEquals(kf + 1, r.spy.keyframes(), "one keyframe")
        } finally { r.close() }
    }

    /**
     * RC-2, the notice (the lead's ruling of review 1a). Three readings of the count without an answer
     * end the asking for the session — and take Page staging with them, and the one check that says a
     * cache write was not applied. Until this round only the journal said so. Now, once per session:
     * a notice on thread `cache/off`, raised only when one read of the link state says the link is up
     * and steady (a session that is ending answers null three times by itself, and the box would wait
     * in the queue about a session that is over) and only when this session's capabilities say the
     * build answers the count at all (a build without the telemetry bit has no count: a capability,
     * not a failure — the journal line says which).
     * The three readings are a pace apart (`CacheLedger.UNANSWERED_PACE_MS`), so each case takes
     * about four seconds. The absences are read behind two marks through the shell's own loop: the
     * notice is posted by the handler that writes the `count off` note, and the second mark is
     * queued behind anything that handler posted.
     * Mutations watched: no notice; the notice without the link's test; without the capability's.
     */
    @Test
    fun aCountThatGoesOffIsANoticeOnASteadyLinkOfABuildThatHasOne(): Unit = runBlocking {
        for (variant in listOf("a steady link", "a link that is ending", "a build without the telemetry bit")) {
            val r = rig(2, pinClock = true)
            try {
                r.up(enable = false)
                if (variant == "a build without the telemetry bit")
                    r.spy.showLink { it.copy(damageFeatures = it.damageFeatures and DamageMsg.FEATURE_TELEMETRY.inv()) }
                r.spy.nullCounts.set(3)                        // the count's base, asked before the first cache write: not answered
                r.shell.updateSettings { it.copy(cachedText = "on") }
                if (variant == "a link that is ending") {
                    r.until("$variant: two readings were not answered") { r.count("count unread — ").size == 2 }
                    val gate = CompletableDeferred<Unit>()
                    r.spy.holdCounts = gate                    // the third is asked a pace later, and waits here
                    val asked = r.spy.countsAsked.get()
                    r.until("$variant: the third reading is asked (and held)") { r.spy.countsAsked.get() > asked }
                    r.spy.showLink { it.copy(connected = false) }   // the link is ending when its null lands
                    r.spy.holdCounts = null; gate.complete(Unit)
                }
                r.until("$variant: the count is off for the session") { r.count("count off — ").isNotEmpty() }
                barrier(r); barrier(r)
                assertEquals(2, r.count("count unread — ").size, "$variant: two unanswered readings before the third: ${r.count("count")}")
                assertTrue(r.notes("stage").any { "off for the session — the cache generation read did not answer 3 times" in it }, "$variant: Page staging went with it: ${r.notes("stage")}")
                when (variant) {
                    "a steady link" -> {
                        val boxes = r.cacheNotices().filter { it.thread == "cache/off" }
                        assertEquals(1, boxes.size, "$variant: one notice: ${r.cacheNotices()}")
                        assertEquals("DAMAGE · cache", boxes[0].source)
                        assertEquals("the glasses did not answer the cache count — cache writes go unchecked and Page staging is off until the next session", boxes[0].body)
                        r.until("$variant: the upload goes on without the count") { r.font in r.shell.cachedFontsLive }
                        // ...and once per SESSION, not once for the life of the process (the R1 verify
                        // stage; watched with the latch's clear removed from the session start: no box).
                        // The box is dismissed first — the next session's is then a box of its own to
                        // count, whatever the queue does with two of one thread — and a character new to
                        // the face gives the second session a cache write, so its base is asked for.
                        r.settle("$variant: the first session, said")
                        r.dismissNotices()
                        assertTrue(r.cacheNotices().isEmpty(), "$variant: the first session's box was dismissed: ${r.cacheNotices()}")
                        r.spy.nullCounts.set(3)
                        r.clock += 20_000L
                        r.lab.endLink("test: supervision timeout")
                        r.until("$variant: the second session drives") { r.keeper.attempts == 2 && r.driving() }
                        r.win.suffix = "abQ"
                        r.notch("$variant: a character new to the face, in the second session")
                        r.until("$variant: the second session's count is off too") { r.count("count off — ").size == 2 }
                        barrier(r); barrier(r)
                        assertEquals(1, r.cacheNotices().count { it.thread == "cache/off" },
                            "$variant: said again in the session that follows — once per session: ${r.cacheNotices()}")
                    }
                    "a link that is ending" -> {
                        assertTrue(r.cacheNotices().isEmpty(), "$variant: no notice about a session that is ending: ${r.cacheNotices()}")
                        r.spy.showLink { it }
                    }
                    else -> {
                        assertTrue(r.cacheNotices().isEmpty(), "$variant: a capability, not a failure — no notice: ${r.cacheNotices()}")
                        assertTrue("this build's capabilities carry no telemetry" in r.count("count off — ").single(), "$variant: and the journal says which: ${r.count("count off — ")}")
                    }
                }
            } finally { r.close() }
        }
    }

    /**
     * R3 (review 2, 2A-2). A count reading the glasses NEVER answer while every write succeeds: the op-1
     * asks leave and nothing comes back. Until R3 the transport asked again every 2 s for the whole
     * session, the reading held both cache-write lanes all that time, the atlas never went up, and the
     * only trace was one journal line. Now a reading ends at its third unanswered ask (a bound by asks,
     * no clock is added), the shell's three-in-a-row bound switches the count off, `cache/off` says the
     * cause it had, and the cache goes on unchecked as 0.63's did: the atlas goes up and draws from it.
     * Three readings of three asks 2 s apart, two paces between: about 22 s.
     * Watched on d10f612 (no bound): the wait for the notice gives up.
     */
    @Test
    fun aCountTheGlassesNeverAnswerEndsInTheNoticeAndTheCacheGoesOnUnchecked(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            r.up(enable = false)
            assertTrue(r.spy.state.value.draw2, "DRAW2 is in force before the asks are muted (the start's own reads were answered)")
            r.lab.muteCounts = true                       // from here every op-1 ask is written and never answered
            r.shell.updateSettings { it.copy(cachedText = "on") }
            // R4 item 8 (review 3B open finding (e)): the transport's third "not answered" note reaches the journal
            // through the events collector, the ended reading reaches the shell through the ledger's `Run` — no order
            // between them, so the notice can be up before the note is journaled. ONE evaluation reads both, and the
            // asserts read the list that evaluation took (it read the notes a second time and missed, 2 of 3).
            var ended: List<String> = emptyList()
            waitUntil(40_000, 5, { "the count goes off and says so — count notes=${r.notes("atlas").filter { "count" in it }.takeLast(4)} " +
                "glass=${r.notes("glass").filter { "cache count" in it }.takeLast(3)} notices=${r.cacheNotices()}" }) {
                ended = r.notes("glass").filter { "not answered after 3 asks" in it }
                ended.size >= 3 && r.cacheNotices().any { it.thread == "cache/off" } }
            val box = r.cacheNotices().single { it.thread == "cache/off" }
            assertEquals("the glasses did not answer the cache count (asked 3 times) — cache writes go unchecked " +
                "and Page staging is off until the next session", box.body)
            assertEquals(3, ended.size, "each reading ends at its third ask, and the transport says so: $ended")
            assertTrue(ended.all { "cache count" in it }, "the note names the label: $ended")
            assertEquals(2, r.count("count unread — ").size, "two unanswered readings before the third: ${r.count("count")}")
            assertEquals(18, r.lab.mutedCounts.size, "three readings of three asks, each to both arms — no fourth ask")
            r.until("the atlas goes up without the count") { r.font in r.shell.cachedFontsLive }
            r.settle("live")
            r.notch("drawing from it")
            r.until("a cached draw follows") { r.spy.drawsShipped() > 0 }
            assertEquals(18, r.lab.mutedCounts.size, "and nothing more is asked of the count this session")
        } finally { r.close() }
    }

    /**
     * R3 (review 2, 2A-2): `cache/off`'s body says the cause the readings had, and names Page staging only
     * where its row is on. The two cases the bound's pin does not reach: a reading that could not be ASKED
     * (its first write fails; the body carries the transport's reason), and a reading the transport ended
     * with null and no cause, with the Page staging row off (there was nothing to switch off).
     * Watched: the not-asked arm removed (the body said the glasses did not answer); the row's test
     * removed (the body named Page staging with its row off).
     */
    @Test
    fun theCacheOffNoticeSaysItsCauseAndNamesPageStagingOnlyWhereItsRowIsOn(): Unit = runBlocking {
        for (variant in listOf("the asks cannot be written", "the Page staging row is off")) {
            val r = rig(2, pinClock = true)
            try {
                r.up(enable = false)
                if (variant == "the asks cannot be written") r.lab.failCounts = true
                else { r.shell.updateSettings { it.copy(pageStaging = "off") }; r.spy.nullCounts.set(3) }
                r.shell.updateSettings { it.copy(cachedText = "on") }
                r.until("$variant: the count goes off and says so") { r.cacheNotices().any { it.thread == "cache/off" } }
                val body = r.cacheNotices().single { it.thread == "cache/off" }.body
                if (variant == "the asks cannot be written") {
                    assertTrue(body.startsWith("the cache count could not be asked (cache count ") &&
                        "not written: " in body && "test: the link refuses the ask)" in body, "$variant: $body")
                    assertTrue(body.endsWith(") — cache writes go unchecked and Page staging is off until the next session"), "$variant: $body")
                } else {
                    assertEquals("the glasses did not answer the cache count — cache writes go unchecked", body, variant)
                }
            } finally { r.close() }
        }
    }

    /**
     * R3 (review 2, 2A-2), the bound's other half: an answer to a reading that ended at its bound and
     * arrives later is dropped by its id — the next reading, asked a pace later under a new id, does
     * not take it for its own. The first reading's last ask to RIGHT is handed to the glasses while the
     * second reading is out (and muted too): the glasses answer it, late, under the first reading's id.
     * The second reading still ends unanswered: two `count unread` notes, no reading answered, no cache
     * write. Watched: with the reply handler handing an answer whose reading has ended to a reading still
     * out, the second reading took it (one reading answered, and the atlas went up under that base).
     */
    @Test
    fun anAnswerThatArrivesAfterTheBoundIsNotTakenByTheNextReading(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            r.up(enable = false)
            r.lab.muteCounts = true
            r.shell.updateSettings { it.copy(cachedText = "on") }
            r.until("the first reading ended unanswered") { r.count("count unread — ").size == 1 }
            val first = r.lab.mutedCounts.filter { it.first == Arm.RIGHT }
            assertEquals(3, first.size, "the first reading: three asks to RIGHT")
            val lateId = telemetryRequestId(first.last().second)!!
            r.until("the second reading is out") { r.lab.mutedCounts.count { it.first == Arm.RIGHT } > 3 }
            val secondId = telemetryRequestId(r.lab.mutedCounts.last { it.first == Arm.RIGHT }.second)!!
            assertTrue(secondId != lateId, "the second reading has an id of its own ($lateId, $secondId)")
            r.lab.deliverLate(Arm.RIGHT, first.last().second)
            r.until("the late answer reached the transport") { r.notes("glass").any { "id=$lateId " in it } }
            r.until("the second reading ended unanswered too") { r.count("count unread — ").size == 2 }
            // what a reading that took the late answer changes: it returns a record (the hooks count it), the
            // count has its base, and both cache-write lanes are free — the atlas goes up under that base
            assertEquals(0, r.spy.countsAnswered.get(), "no reading took the late answer for its own: ${r.notes("atlas").takeLast(4)}")
            assertEquals(0, r.spy.atlasFlushes(), "and nothing was written to the cache under it")
            assertEquals(2, r.notes("glass").count { "not answered after 3 asks" in it }, "both readings ended at their bound")
        } finally { r.close() }
    }

    /**
     * RC-5 (review 1a), L7's twin for the READ-BACK (op 4). A read-back asked in the last session and
     * answered in this one is dropped by its epoch — and must clear no flag but its own: this session's
     * read-back is still out, and the cache-write lanes are held under it. The count's reader was given
     * that order in 0.64 (L7); the read-back's handler still cleared the in-flight flag and spent the
     * "asked again" mark BEFORE its epoch test, so a stale answer released the lanes under the read-back
     * of the session that followed. A kept rebuild makes the two overlap: the same atlas object, a new
     * epoch. Two gates, so the window between the stale answer and the new one is the test's; the new
     * face's chunks are the cache write that must not leave.
     * (The second order settled with it — an answer that finds cached text already failed is dropped
     * before the "no answer" branch can put fonts live — has no pin of its own: it is reachable only
     * through the first.)
     * Mutation watched: the flag cleared before the epoch test (a cache write leaves under the new read-back).
     */
    @Test
    fun aReadBackFromTheLastSessionClearsNoFlagButItsOwn(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            warm(r)
            val old = CompletableDeferred<Unit>()
            r.spy.holdChecks = old                             // Q's read-back (A): asked, and held before it reaches the glasses
            val asked = r.spy.checksAsked.get()
            r.win.suffix = "abQ"
            r.notch("the notch that reveals Q")
            r.until("Q's extension is acked and its read-back is asked for (and held)") { r.spy.checksAsked.get() == asked + 1 }
            val fresh = CompletableDeferred<Unit>()
            r.spy.holdChecks = fresh                           // the gate is read at the call: the next session's read-backs wait on their own
            r.clock += 20_000L
            r.lab.endLink("test: supervision timeout")
            r.until("the second session drives") { r.keeper.attempts == 2 && r.driving() }
            r.until("the glasses saw the second connect") { r.sim.preludeAcks == 2 }
            assertTrue(r.notes("atlas").any { "kept across the rebuild" in it }, "the same atlas, a new epoch: ${r.notes("atlas").takeLast(5)}")
            // the unpromoted extension goes again under the kept atlas, and ITS read-back (B) is this session's
            r.until("the rewound extension is acked again and its read-back is asked for (and held)") { r.spy.checksAsked.get() == asked + 2 }
            r.settle("the new session's read-back out")
            assertEquals(0, r.shell.atlasExtensionsLive, "nothing is promoted: both read-backs are held")
            // a second face: its chunks are cut and wait under B (a new character of the test face would
            // only wait behind Q's unpromoted extension and queue nothing)
            val queuedNotes = r.notes("atlas").count { "chunk(s) queued" in it }
            r.win.extra = FontSpec(Face.READER, 24)
            r.notch("a second face under the read-back")
            r.until("the second face is packed and its chunks are queued") { r.notes("atlas").count { "chunk(s) queued" in it } > queuedNotes }
            barrier(r)
            val sent = r.spy.atlasFlushes()
            val answered = r.spy.checksAnswered.get()
            old.complete(Unit)                                 // the old session's read-back is answered now, in the new one
            r.until("the stale read-back's answer came back") { r.spy.checksAnswered.get() > answered }
            barrier(r); delay(300); barrier(r)
            assertEquals(sent, r.spy.atlasFlushes(), "no cache write leaves: this session's read-back is still out — ${r.notes("atlas").takeLast(4)}")
            assertEquals(asked + 2, r.spy.checksAsked.get(), "and no third read-back was launched under it")
            fresh.complete(Unit); r.spy.holdChecks = null
            r.until("the second face's chunks follow the read-back") { r.spy.atlasFlushes() > sent }
            r.atlasIdle("after the read-back")
            r.notch("drawing"); r.assertGlassMatchesBelief("after the dropped read-back")
        } finally { r.close() }
    }

    /**
     * RC-6 (review 1a): what 0.64 P4's sleep entry left open for the read-back. The release at a sleep
     * frees the glasses' cache ON PURPOSE, and RIGHT still answers the read-back (op 4) asleep — with
     * no cache to report. An answer applied then reads as "the glasses hold no texture cache": cached
     * text off and a fault notice, for a sleep that was asked for.
     *  (a) A read-back already OUT at the sleep (the firmware's Silent Mode here): dropped by its
     *      epoch, which the sleep's entry bumps (`enterSleep` -> `atlasCheckOwed`, P4). P4's own pin
     *      has a chunk in flight, not a read-back: this is the held-answer form. It passes on P4's
     *      tree; watched to fail with the epoch left alone at a sleep.
     *  (b) A read-back ASKED while asleep: during a rest the Cached text row is switched off and on
     *      (a synced record, the phone's settings). The grow that follows finds every face acked,
     *      none live and nothing to upload, and asked the freed cache to vouch for them. Nothing is
     *      asked of a sleeping pair now and nothing goes live there: the wake is a session start,
     *      which decides keep or reset afresh. (A rest, because it posts no notice, and with the
     *      status cell's word already in its face: a grow with something to queue asks nothing —
     *      watched: under Silent Mode the notice's new glyphs, and under a rest the `t` of the cell's
     *      `silent`, each gave that grow a chunk, and the same toggle then asked no read-back.)
     * Absences, each read behind marks through the shell's own loop after the event that would have
     * produced the answer (the gate's release, the row's change) plus 300 ms for a round trip on a
     * link that answers within milliseconds — a bounded wait, said as one.
     * Mutations watched: (a) the read-back's epoch not bumped at a sleep; (b) the asleep test removed
     * from `atlasCheckThenLive`.
     */
    @Test
    fun aReadBackIsNeitherAppliedNorAskedWhileTheGlassesSleep(): Unit = runBlocking {
        for (variant in listOf("a read-back out at the sleep", "the row off and on during a rest")) {
            val held = variant == "a read-back out at the sleep"
            val r = rig(2, pinClock = true)
            try {
                r.up()
                r.notch("warm")
                val gate = CompletableDeferred<Unit>()
                if (held) {
                    r.spy.holdChecks = gate                    // Q's read-back: asked, and held before it reaches the glasses
                    val asked = r.spy.checksAsked.get()
                    r.win.suffix = "abQ"
                    r.notch("the notch that reveals Q")
                    r.until("$variant: Q's read-back is asked for (and held)") { r.spy.checksAsked.get() == asked + 1 }
                    r.sim.setSilent(true)
                } else {
                    // the status cell says `silent` while the shell sleeps: its face holds that word's
                    // glyphs already here (a row draws it), so the sleep itself gives the grow nothing
                    // to queue — the scene of any session whose chrome has shown those letters before
                    r.win.extraText = "silent"; r.win.extra = FontSpec(Face.SYSTEM, 16)
                    r.notch("the status cell's face draws the word")
                    r.atlasIdle("$variant: everything acked, read back and live")
                    assertTrue(r.shell.devProbe("rest", "on"), "the probe is the shell's")
                }
                r.until("$variant: asleep") { r.shell.glassesAsleep }
                r.until("$variant: the release freed RIGHT's cache") { !r.sim.cacheAllocated(Arm.RIGHT) }
                barrier(r)
                val asked = r.spy.checksAsked.get()
                val answered = r.spy.checksAnswered.get()
                if (held) {
                    r.spy.holdChecks = null; gate.complete(Unit)
                    r.until("$variant: the held read-back's answer came back, from a pair with no cache") { r.spy.checksAnswered.get() > answered }
                } else {
                    r.shell.updateSettings { it.copy(cachedText = "off") }
                    r.until("$variant: off") { r.notes("atlas").any { "cached text off — the setting is off" in it } }
                    r.shell.updateSettings { it.copy(cachedText = "on") }
                }
                barrier(r); delay(300); barrier(r)
                if (!held) assertEquals(asked, r.spy.checksAsked.get(), "$variant: no read-back is asked of a sleeping pair — ${r.notes("atlas").takeLast(3)}")
                assertTrue(r.notes("atlas").none { "cached text off — the glasses" in it }, "$variant: nothing is judged against the freed cache: ${r.notes("atlas").takeLast(4)}")
                val boxes = listOfNotNull(r.shell.notifications.current) + r.shell.notifications.queued()
                assertTrue(boxes.none { it.thread == "atlas" }, "$variant: and no notice waits for the wake: $boxes")
                // the wake: a new session, the atlas up again, cached text as it was asked for
                val attempts = r.keeper.attempts
                if (held) r.sim.setSilent(false) else r.shell.devProbe("rest", "off")
                r.until("$variant: awake in a new session") { r.keeper.attempts > attempts && r.driving() && !r.shell.glassesAsleep }
                r.atlasIdle("$variant: the new session")
                r.notch("drawing"); r.assertGlassMatchesBelief("$variant, after the wake")
            } finally { r.close() }
        }
    }

    /**
     * RC-4 (review 1a): the read-back's EPOCH test, pinned where nothing else can drop the answer. A
     * read-back is out when the lease lapses; the lapse bumps the epoch (`atlasLapsed` ->
     * `atlasCheckOwed`) and the answer, when it lands, is dropped — applied, it would report the lapse
     * the shell has already handled as "the glasses hold no texture cache": cached text off for the
     * session and a fault notice.
     * On a Damage build a lapse is followed by a session rebuild, and then the answer is dropped by
     * other means (the transport's `started` latch answers null; a new session's fresh atlas fails the
     * identity test) — which is why no pin could fail for the epoch before: the same atlas object,
     * a running session and a non-null answer are needed together. The rebuild is REFUSED here
     * (`Hooks.refuseRestart`: the shell journals it and stays in the lapsed session), the model's
     * lease runs out and frees the cache, and only then is the held read-back let through.
     * Passes on the tree as built; mutation watched: the epoch term removed from the answer's test.
     */
    @Test
    fun aReadBackHeldAcrossALapseIsDroppedByItsEpoch(): Unit = runBlocking {
        val r = rig(2, pinClock = true)
        try {
            warm(r)
            val gate = CompletableDeferred<Unit>()
            r.spy.holdChecks = gate                            // Q's read-back: asked, and held before it reaches the glasses
            val asked = r.spy.checksAsked.get()
            r.win.suffix = "abQ"
            r.notch("the notch that reveals Q")
            r.until("Q's extension is acked and its read-back is asked for (and held)") { r.spy.checksAsked.get() == asked + 1 }
            r.spy.refuseRestart = true
            r.spy.loseLease()
            r.until("the shell saw the lapse") { r.notes("atlas").any { "the lease lapsed" in it } }
            r.until("the rebuild a lapse asks for on a Damage build was refused, and said") {
                r.spy.restartsRefused.get() >= 1 && r.notes("keeper").any { "session rebuild refused by the transport" in it } }
            // and the lapse is REAL on the glasses: the model's lease runs out and it frees the cache
            r.clock += 100_000L
            r.until("the model freed the cache on both lenses") { Arm.entries.none { r.sim.cacheAllocated(it) } }
            val answered = r.spy.checksAnswered.get()
            r.spy.holdChecks = null; gate.complete(Unit)
            r.until("the held read-back's answer came back, from a pair with no cache") { r.spy.checksAnswered.get() > answered }
            barrier(r); barrier(r)
            assertEquals(1, r.keeper.attempts, "the same session: nothing but the epoch stands between this answer and the atlas")
            assertTrue(r.notes("atlas").none { "cached text off" in it }, "the stale answer is not applied: ${r.notes("atlas").takeLast(4)}")
            val boxes = listOfNotNull(r.shell.notifications.current) + r.shell.notifications.queued()
            assertTrue(boxes.none { it.thread == "atlas" }, "and raises no notice: $boxes")
        } finally { r.close() }
    }
}

/** The request id of an op-1 (TELEMETRY) request in one settings packet, or null when [packet] is not one:
 *  field 112's body is `D M 1 op lo hi` (`DamageMsg.control`; `FIRMWARE.md` §3). */
private fun telemetryRequestId(packet: ByteArray): Int? {
    if (packet.size < 13 || (packet[6].toInt() and 0xFF) != wm.damage.core.wire.SettingsMsg.SID) return null
    for (i in 0 until packet.size - 5) {
        if (packet[i] == 'D'.code.toByte() && packet[i + 1] == 'M'.code.toByte() && packet[i + 2].toInt() == 1 &&
            packet[i + 3].toInt() == DamageMsg.OP_TELEMETRY)
            return (packet[i + 4].toInt() and 0xFF) or ((packet[i + 5].toInt() and 0xFF) shl 8)
    }
    return null
}
