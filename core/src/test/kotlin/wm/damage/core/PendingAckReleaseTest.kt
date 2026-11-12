package wm.damage.core

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
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.EvenHubMsg

/**
 * `HANDOFF.md` §34: a later image fragment's ack releases every earlier
 * image pending as LOST — promptly, with its window slot, and the flush it
 * belonged to fails with a named reason so the compositor re-sends from the
 * truth. Before this, a lost ack held its slot for a whole msgId cycle (249
 * messages) and the phone path measured 49 of them in five days (§33.4).
 */
class PendingAckReleaseTest {

    private class Rig(scope: CoroutineScope) {
        val sim = GlassFirmwareSim()
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val comp = Compositor()
        val done = HashMap<Long, TransportEvent.FlushDone>()
        val faults = ArrayList<String>()
        val notes = java.util.concurrent.CopyOnWriteArrayList<String>()

        init {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    when (it) {
                        is TransportEvent.FlushDone -> synchronized(done) { done[it.id] = it }
                        is TransportEvent.Fault -> synchronized(faults) { faults.add("${it.what}: ${it.detail}") }
                        is TransportEvent.Note -> notes.add("${it.kind}: ${it.detail}")
                        else -> {}
                    }
                }
            }
        }

        suspend fun start() = t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))

        suspend fun await(id: Long, label: String): TransportEvent.FlushDone {
            val t0 = System.currentTimeMillis()
            while (synchronized(done) { id !in done } && System.currentTimeMillis() - t0 < 10_000) delay(5)
            return assertNotNull(synchronized(done) { done[id] }, "$label: flush $id never completed")
        }

        suspend fun ship(a: Compositor.Assembled): TransportEvent.FlushDone =
            await(t.submit(FlushRequest(a.ops, a.epoch, "", wide = a.wide)), "ship")

        suspend fun drain(label: String): Int {
            var n = 0
            while (comp.hasPending || comp.needsKeyframe) {
                val a = comp.assembleFlush(Geometry.rectBudget(3)) ?: break
                n++
                assertTrue(ship(a).ok, "$label: flush failed")
                assertTrue(n <= 40, "$label: did not converge")
            }
            return n
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

        fun assertBeliefMatchesGlass(label: String) {
            for (left in booleanArrayOf(true, false)) {
                val e = comp.expectedLens(left); val g = lens(left)
                for (y in 0 until 480) for (x in 0 until 640)
                    assertEquals(e[x, y], g[x, y], "$label: ${if (left) "L" else "R"} ($x,$y) belief != glass")
            }
        }
    }

    /** An image ack: an AA response frame on the EvenHub sid with the ack flag. */
    private fun isImageAck(p: ByteArray): Boolean =
        p.size >= AaFrame.HEADER && (p[1].toInt() and 0xFF) == AaFrame.TYPE_RESPONSE &&
            (p[6].toInt() and 0xFF) == EvenHubMsg.SID && (p[7].toInt() and 0xFF) == EvenHubMsg.FLAG_ACK

    @Test
    fun aLaterAckReleasesAnEarlierLostOneAndTheFlushIsRepaired(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            val l = Layout()
            val comp = rig.comp
            comp.composed.fillRect(l.content, 3 * 17)
            comp.requestKeyframe()
            rig.drain("keyframe")

            // lose exactly ONE image ack: the next one the glasses send back
            var dropped = 0
            rig.t.notifyFilter = { _: Arm, p: ByteArray ->
                if (dropped == 0 && isImageAck(p)) { dropped++; false } else true
            }
            val rowA = Rect(l.content.x, l.content.y + 40, l.content.w - Layout.RAIL_W, 16)
            comp.composed.fillRect(rowA, 14 * 17); comp.damage(rowA)
            val a = assertNotNull(comp.assembleFlush(5))
            val idA = rig.t.submit(FlushRequest(a.ops, a.epoch, "A", wide = a.wide))
            val rowB = Rect(l.content.x, l.content.y + 120, l.content.w - Layout.RAIL_W, 16)
            comp.composed.fillRect(rowB, 9 * 17); comp.damage(rowB)
            val b = assertNotNull(comp.assembleFlush(5))
            val idB = rig.t.submit(FlushRequest(b.ops, b.epoch, "B", wide = b.wide))

            val t0 = System.currentTimeMillis()
            val doneA = rig.await(idA, "A"); val doneB = rig.await(idB, "B")
            val waited = System.currentTimeMillis() - t0
            assertEquals(1, dropped, "the filter must have dropped exactly one ack")
            assertTrue(!doneA.ok, "A's ack was lost: A must FAIL, not complete as if acked (got $doneA)")
            assertTrue(doneA.error?.contains("lost") == true, "A's failure names the lost ack: ${doneA.error}")
            assertTrue(doneB.ok, "B, whose ack arrived, completes normally: $doneB")
            assertTrue(waited < 5_000, "A failed promptly on B's ack, not after a counter cycle ($waited ms)")
            assertEquals(0, rig.t.state.value.inFlight, "A's window slot came back")
            val f = synchronized(rig.faults) { rig.faults.toList() }
            assertTrue(f.any { it.startsWith("ack:") && "lost" in it }, "the loss is a named fault: $f")
            assertTrue(f.none { "counter cycle" in it || it.startsWith("stall") }, "no cycle wait, no stall: $f")

            // the compositor's lost-flush path repairs the pixels from the truth
            comp.rollback(a)
            rig.t.notifyFilter = null
            rig.drain("repair")
            rig.assertBeliefMatchesGlass("after the repair")
        } finally {
            scope.cancel()
        }
    }

    /**
     * P5 item 10 (the wait-no-escape sweep's finding 1, with its three corrections): a WIDE flush drains the pipeline
     * before it goes (depth 1), and the drain waited for every slot — one held by a message whose ack was lost, which
     * only a later image ack could release, and the drain itself was what kept every later image from being written.
     * The window stayed full until the msgId counter came round (~14 min on the phone, modeled) with the stall reported
     * and nothing done. Fixed: the stall tick nudges a drain parked that long; the lane writes the wide flush at depth 2,
     * and its ack releases the earlier one as lost (§34). Unfixed: A fails as "never arrived (counter cycled)" when the
     * instant rig's control traffic brings the counter round, or B never completes inside the bound.
     */
    @Test
    fun aWideFlushBehindALostAckIsWrittenAtDepthTwoAndReleasesIt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            val l = Layout()
            val comp = rig.comp
            comp.composed.fillRect(l.content, 3 * 17)
            comp.requestKeyframe()
            rig.drain("keyframe")

            // lose exactly ONE image ack: A's
            var dropped = 0
            rig.t.notifyFilter = { _: Arm, p: ByteArray ->
                if (dropped == 0 && isImageAck(p)) { dropped++; false } else true
            }
            val rowA = Rect(l.content.x, l.content.y + 40, l.content.w - Layout.RAIL_W, 16)
            comp.composed.fillRect(rowA, 14 * 17); comp.damage(rowA)
            val a = assertNotNull(comp.assembleFlush(5))
            assertTrue(!a.wide, "A is a row delta")
            val idA = rig.t.submit(FlushRequest(a.ops, a.epoch, "A", wide = a.wide))
            // B is a keyframe: wide, so it drains first
            comp.composed.fillRect(l.content, 5 * 17)
            comp.requestKeyframe()
            val b = assertNotNull(comp.assembleFlush(5))
            assertTrue(b.wide, "B is wide")
            val idB = rig.t.submit(FlushRequest(b.ops, b.epoch, "B", wide = b.wide))

            val doneB = rig.await(idB, "B"); val doneA = rig.await(idA, "A")
            assertEquals(1, dropped, "the filter dropped exactly one ack")
            assertTrue(doneB.ok, "B, written behind the lost ack, completes: $doneB")
            assertTrue(!doneA.ok && doneA.error?.contains("acked first") == true, "A is released by B's ack: $doneA")
            val f = synchronized(rig.faults) { rig.faults.toList() }
            assertTrue(f.none { "counter cycle" in it }, "no counter-cycle wait: $f")
            assertTrue(rig.notes.any { it.startsWith("stall: ") && "wide flush written at depth 2" in it }, "the journal note: ${rig.notes.filter { it.startsWith("stall") }}")
            assertEquals(0, rig.t.state.value.inFlight, "every slot came back")

            comp.rollback(a)
            rig.t.notifyFilter = null
            rig.drain("repair")
            rig.assertBeliefMatchesGlass("after the depth-2 write")
        } finally {
            scope.cancel()
        }
    }

    /**
     * P5 item 10 with GG-2's pointer: the emit states its budget at the depth the flush goes out at. A wide flush of nine
     * delta rects (wide for its count, not a keyframe) behind a lost ack is nudged and goes out at depth 2, where the
     * duplicate ring holds eight per flush: the emit refuses it (BUD001, nothing written, the shell's streak then
     * keyframes). Stated at depth 1 it was written past the ring's rule.
     */
    @Test
    fun aNudgedWideFlushIsBudgetedAtTheDepthItGoesOutAt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            val l = Layout()
            val comp = rig.comp
            comp.composed.fillRect(l.content, 3 * 17)
            comp.requestKeyframe()
            rig.drain("keyframe")
            var dropped = 0
            rig.t.notifyFilter = { _: Arm, p: ByteArray ->
                if (dropped == 0 && isImageAck(p)) { dropped++; false } else true
            }
            val rowA = Rect(l.content.x, l.content.y + 40, l.content.w - Layout.RAIL_W, 16)
            comp.composed.fillRect(rowA, 14 * 17); comp.damage(rowA)
            val a = assertNotNull(comp.assembleFlush(5))
            val idA = rig.t.submit(FlushRequest(a.ops, a.epoch, "A", wide = a.wide))
            val g = Gray8(640, 480).also { it.fillRect(Rect(0, 0, 640, 480), 6 * 17) }
            val nine = (0 until 9).map { i -> Rect(64, 64 + 16 * i, 64, 16) }.map { DisplayOp.Delta(it, Zl.encodeCfw(Pack.rect(g, it))) }
            val idB = rig.t.submit(FlushRequest(nine, a.epoch, "B", wide = true))
            val doneB = rig.await(idB, "B")
            assertTrue(!doneB.ok && doneB.error?.contains("BUD001 9 mode-3 rects with a 2-deep pipeline") == true,
                "the nudged wide flush is budgeted at depth 2: $doneB (A ${synchronized(rig.done) { rig.done[idA] }})")
        } finally {
            scope.cancel()
        }
    }

    /**
     * P5 verify, item 10's other branch — the one every keyframe takes on the phone, where an image ack comes back
     * ~70 ms or more after its write: a WIDE flush submitted while an earlier message's ack is still on its way parks
     * in the drain, and when that ack comes the drain ends as it always did — the flush goes out at depth 1, after the
     * ack, with no `stall` note. The model acks inside its own write, so no other rig here ever parks a drain that then
     * drains: this one holds A's ack by hand and hands it back late, on the product's paced bounds (the stall tick's
     * 10 s, far past the hold). Watched to fail with the drained branch's early return removed: a "wide flush written
     * at depth 1 after 0 s without an image ack" note for a drain nothing was wrong with.
     */
    @Test
    fun aWideFlushWaitingForAnAckOnItsWayGoesAtDepthOneAndSaysNothing(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = StartRig(GlassFirmwareSim(), scope, instantPacing = false)
            val done = java.util.concurrent.ConcurrentHashMap<Long, TransportEvent.FlushDone>()
            val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    when (it) {
                        is TransportEvent.FlushDone -> done[it.id] = it
                        is TransportEvent.Note -> notes.add("${it.kind}: ${it.detail}")
                        is TransportEvent.Fault -> notes.add("fault ${it.what}: ${it.detail}")
                        else -> {}
                    }
                }
            }
            val images = java.util.concurrent.atomic.AtomicInteger()
            t.onWrite = { _, p -> if (StartRig.firstOfImage(p)) images.incrementAndGet(); p }
            t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
            suspend fun await(id: Long, label: String): TransportEvent.FlushDone {
                waitUntil(10_000, message = { "$label: flush $id never completed; notes ${notes.toList()}" }) { done.containsKey(id) }
                return done.getValue(id)
            }
            val l = Layout()
            val comp = Compositor()
            comp.composed.fillRect(l.content, 3 * 17)
            comp.requestKeyframe()
            val k = assertNotNull(comp.assembleFlush(5))
            assertTrue(await(t.submit(FlushRequest(k.ops, k.epoch, "K", wide = k.wide)), "K").ok, "the first keyframe")

            // A's ack is held on its way back
            val held = java.util.concurrent.atomic.AtomicReference<Pair<Arm, ByteArray>?>(null)
            t.onNotify = { arm, p -> if (isImageAck(p) && held.compareAndSet(null, arm to p)) null else p }
            val rowA = Rect(l.content.x, l.content.y + 40, l.content.w - Layout.RAIL_W, 16)
            comp.composed.fillRect(rowA, 14 * 17); comp.damage(rowA)
            val a = assertNotNull(comp.assembleFlush(5))
            assertTrue(!a.wide, "A is a row delta")
            val idA = t.submit(FlushRequest(a.ops, a.epoch, "A", wide = false))
            waitUntil(10_000, message = { "A's ack is on its way: held=${held.get() != null}" }) { held.get() != null }
            // B is a keyframe: wide, so it drains first — behind A's slot
            comp.composed.fillRect(l.content, 5 * 17)
            comp.requestKeyframe()
            val b = assertNotNull(comp.assembleFlush(5))
            assertTrue(b.wide, "B is wide")
            val imagesBeforeB = images.get()
            val idB = t.submit(FlushRequest(b.ops, b.epoch, "B", wide = true))
            delay(300)                       // B reaches the lane and parks in the drain (one evaluation below)
            assertEquals(imagesBeforeB, images.get(), "B waits for A's slot: nothing of it is written while A's ack is held")
            t.onNotify = null
            val (arm, p) = held.get()!!
            t.deliver(arm, p)
            val doneB = await(idB, "B"); val doneA = await(idA, "A")
            assertTrue(doneA.ok && doneB.ok, "A's late ack completes A, and B goes out after it: A $doneA, B $doneB")
            assertTrue(notes.none { it.startsWith("stall") || it.startsWith("fault ") },
                "a drain that ended on the ack says nothing: ${notes.filter { it.startsWith("stall") || it.startsWith("fault ") }}")
            assertEquals(0, t.state.value.inFlight, "every slot came back")
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** Sanity for the rule's precondition: with nothing lost, nothing is
     *  released — every ack matches its own pending. */
    @Test
    fun withNoLossNothingIsReleased(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            val l = Layout()
            rig.comp.composed.fillRect(l.content, 3 * 17)
            rig.comp.requestKeyframe()
            rig.drain("keyframe")
            repeat(6) { i ->
                val row = Rect(l.content.x, l.content.y + 20 * i, l.content.w - Layout.RAIL_W, 16)
                rig.comp.composed.fillRect(row, ((i % 4) + 5) * 17); rig.comp.damage(row)   // never the background level
                assertTrue(rig.ship(assertNotNull(rig.comp.assembleFlush(5))).ok)
            }
            val f = synchronized(rig.faults) { rig.faults.toList() }
            assertTrue(f.none { it.startsWith("ack:") }, "no ack faults without a loss: $f")
            assertEquals(0, rig.t.state.value.inFlight)
            rig.assertBeliefMatchesGlass("steady")
        } finally {
            scope.cancel()
        }
    }
}
