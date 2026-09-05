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

        init {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    when (it) {
                        is TransportEvent.FlushDone -> synchronized(done) { done[it.id] = it }
                        is TransportEvent.Fault -> synchronized(faults) { faults.add("${it.what}: ${it.detail}") }
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
