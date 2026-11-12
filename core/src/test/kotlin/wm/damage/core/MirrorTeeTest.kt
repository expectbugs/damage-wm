package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
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
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/**
 * HANDOFF.md §8.2 "Mirror": a hardware-style transport keeps a private
 * firmware model fed the exact bytes it writes. Here the "glasses" are a
 * SEPARATE sim behind the wire seam; after every flush the transport's mirror
 * must hold exactly what that sim holds, on both lenses, byte for byte.
 */
class MirrorTeeTest {

    /** A transport whose wire ends in its own GlassFirmwareSim (standing in for
     *  the real pair); the base class tees into a second, private mirror. */
    private class TeeTransport(val glass: GlassFirmwareSim, scope: CoroutineScope) :
        CfwTransportBase(scope, "tee") {
        override val instant: Boolean get() = true

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { onNotifyPacket(arm, packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() {}
        override suspend fun writeArm(arm: Arm, packet: ByteArray) { glass.write(arm, packet, nowMs()) }
        override fun onMaintenanceTick() { glass.tick(nowMs()) }
    }

    /** P5 item 16's rig: the tee transport with a hold in LEFT's write. While [armed], LEFT's image fragments are
     *  counted (the first packet of each EvenHub image message); the [holdSerial]th packet of fragment [holdFragment]
     *  (0-based) waits on [gate] before it reaches the far end. [packetsOfHeld] counts that fragment's packets that
     *  reached the far end; [totalOfHeld] is its AA packet count (the header's pktTot). */
    private class GatedTee(val glass: GlassFirmwareSim, scope: CoroutineScope, val holdFragment: Int, val holdSerial: Int) :
        CfwTransportBase(scope, "tee") {
        override val instant: Boolean get() = true
        @Volatile var armed = false
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        @Volatile var parked = false
        private var fragment = -1
        val packetsOfHeld = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var totalOfHeld = 0

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { onNotifyPacket(arm, packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() {}
        override suspend fun writeArm(arm: Arm, packet: ByteArray) {
            if (armed && arm == Arm.LEFT) {
                if (StartRig.firstOfImage(packet)) fragment++
                if (fragment == holdFragment) {
                    if (packet[5].toInt() == 1) totalOfHeld = packet[4].toInt() and 0xFF
                    if (packet[5].toInt() == holdSerial) { parked = true; gate.await() }
                    packetsOfHeld.incrementAndGet()
                }
            }
            glass.write(arm, packet, nowMs())
        }
        override fun onMaintenanceTick() { glass.tick(nowMs()) }
        fun endLink(reason: String) = onLinkDown(reason)
    }

    /** A keyframe whose image is [fragments] EvenHub fragments: black with a block of noise sized to need them. */
    private fun noisyKeyframe(fragments: Int, seed: Long): ByteArray {
        val rnd = java.util.Random(seed)
        val g = Gray8(640, 480)
        val side = if (fragments == 2) 100 else 125
        for (y in 0 until side) for (x in 0 until side) g[40 + x, 40 + y] = rnd.nextInt(16) * 17
        val image = Zl.encodeCfw(Pack.rect(g, Rect(0, 0, 640, 480)))
        assertEquals(fragments, (image.size + Geometry.MAX_IMAGE_FRAGMENT - 1) / Geometry.MAX_IMAGE_FRAGMENT,
            "the keyframe is ${image.size} B")
        return image
    }

    private class TeeSeen {
        val faults = java.util.concurrent.CopyOnWriteArrayList<String>()
        val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val links = java.util.concurrent.CopyOnWriteArrayList<TransportEvent.Link>()
        val done = java.util.concurrent.CopyOnWriteArrayList<TransportEvent.FlushDone>()
        fun mirrorFaults(about: String) = faults.filter { it.startsWith("mirror/") && about in it }
    }

    private fun watch(t: CfwTransportBase, scope: CoroutineScope): TeeSeen {
        val seen = TeeSeen()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            t.events.collect {
                when (it) {
                    is TransportEvent.Fault -> seen.faults.add("${it.what}: ${it.detail}")
                    is TransportEvent.Note -> if (it.kind == "control") seen.notes.add(it.detail)
                    is TransportEvent.Link -> seen.links.add(it)
                    is TransportEvent.FlushDone -> seen.done.add(it)
                    else -> {}
                }
            }
        }
        return seen
    }

    /** Ships a keyframe whose fragment [GatedTee.holdFragment] is held part-way; the link ends while it is held; the
     *  gate opens; the fragment's tail is written (the wire is unchanged), then the keeper's stop. Returns the number
     *  of packets written after the end. */
    private suspend fun linkEndsInsideAFragment(t: GatedTee, seen: TeeSeen, fragments: Int) {
        t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
        t.armed = true
        t.submit(FlushRequest(listOf(wm.damage.core.transport.DisplayOp.Keyframe(noisyKeyframe(fragments, 7))), 1, wide = true))
        waitUntil(10_000, message = { "the write did not reach the held packet" }) { t.parked }
        t.endLink("test: the link ends inside an image fragment")
        t.gate.complete(Unit)
        waitUntil(10_000, message = { "the held fragment's tail did not reach the far end: ${t.packetsOfHeld.get()} of ${t.totalOfHeld}" }) {
            t.totalOfHeld > 0 && t.packetsOfHeld.get() == t.totalOfHeld
        }
        waitUntil(10_000, message = { "the flush was not answered: ${seen.done}" }) { seen.done.isNotEmpty() }
        t.armed = false
        t.stop()                                        // the keeper's stop after a link end
        waitUntil(10_000, message = { "the stop's link event: ${seen.links}" }) { seen.links.any { !it.connected && "stopped" in it.detail } }
    }

    /** P5 item 16 (the identity-guard sweep's IG-2), pin 1: the link ends while a keyframe's second fragment of three is
     *  part-way out. The tail is still written whole on the wire; the mirror, reset at the end, takes none of it — so
     *  no "fragment 1 opens session S" abort, which would mark S broken for the rest of the process. */
    @Test
    fun aFragmentsTailAfterTheLinkEndsIsNotAppliedToTheMirror(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = GatedTee(GlassFirmwareSim(), scope, holdFragment = 1, holdSerial = 3)
            val seen = watch(t, scope)
            linkEndsInsideAFragment(t, seen, fragments = 3)
            assertEquals(emptyList(), seen.mirrorFaults("opens session"), "the mirror took the tail of a fragment whose stream its reset dropped")
            val tail = t.totalOfHeld - t.holdSerial + 1
            assertEquals(listOf("$tail packet(s) of the ended session left after its end and were not applied to the mirror (at the stop)"),
                seen.notes.filter { "not applied to the mirror" in it }, "one note with the count")
        } finally {
            scope.cancel()
        }
    }

    /** Pin 2: the same with the hold inside the image's LAST fragment, then a new start. Unfixed, the tail completes a
     *  fragment that opens nothing, the mirror marks the session broken, and the next session's warmup (the next id,
     *  adjacent) is refused by the mirror at once — the short form of the lasting result. */
    @Test
    fun aLastFragmentsTailLeavesTheNextSessionsWarmupAlone(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = GatedTee(GlassFirmwareSim(), scope, holdFragment = 1, holdSerial = 3)
            val seen = watch(t, scope)
            linkEndsInsideAFragment(t, seen, fragments = 2)
            val before = seen.faults.size
            t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
            val k = t.submit(FlushRequest(listOf(wm.damage.core.transport.DisplayOp.Keyframe(noisyKeyframe(2, 11))), 2, wide = true))
            waitUntil(10_000, message = { "the new session's keyframe: ${seen.done}" }) { seen.done.any { it.id == k } }
            assertEquals(emptyList(), seen.faults.drop(before).filter { it.startsWith("mirror/") }, "the new session's images are the mirror's")
            for (arm in Arm.entries) assertContentEquals(t.glass.panel(arm), t.mirror.panel(arm), "mirror != glass on $arm after the new session's keyframe")
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** Pin 3, the long form: after pin 1's link end, a new session ships single-fragment flushes until its image
     *  session ids (250 of them, `nextSessionLocked`) have come round past the broken one and its neighbours: no
     *  `mirror/session` refusal on the way, and the mirror equals the glass at the end (what the shell's divergence
     *  check compares). Unfixed, the round comes back to the broken id about every 8 minutes of active use (the
     *  challenge's count from the saved tails), each time with refusals and, where pixels changed, a divergence. */
    @Test
    fun aSessionAfterALinkEndInsideAFragmentKeepsTheMirrorEqualToTheGlass(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = GatedTee(GlassFirmwareSim(), scope, holdFragment = 1, holdSerial = 3)
            val seen = watch(t, scope)
            linkEndsInsideAFragment(t, seen, fragments = 3)
            val before = seen.faults.size
            val g = Gray8(640, 480)
            t.start(Zl.encodeCfw(Pack.rect(g, Rect(0, 0, 640, 480))))
            val ids = ArrayList<Long>()
            ids += t.submit(FlushRequest(listOf(wm.damage.core.transport.DisplayOp.Keyframe(Zl.encodeCfw(Pack.rect(g, Rect(0, 0, 640, 480))))), 2, wide = true))
            for (i in 0 until 260) {
                val r = Rect(60 + (i % 10) * 50, 300 + (i / 10 % 5) * 30, 40, 20)
                g.fillRect(r.x, r.y, r.w, r.h, (i % 12 + 3) * 17)
                ids += t.submit(FlushRequest(listOf(wm.damage.core.transport.DisplayOp.Delta(r, Zl.encodeCfw(Pack.rect(g, r)))), 3L + i))
            }
            waitUntil(10_000, message = { "the new session's flushes: ${seen.done}" }) { ids.all { id -> seen.done.any { it.id == id } } }
            assertEquals(emptyList(), seen.faults.drop(before).filter { it.startsWith("mirror/") }, "no mirror refusal in the new session")
            synchronized(t.glass) {
                for (arm in Arm.entries) assertContentEquals(t.glass.panel(arm), t.mirror.panel(arm), "mirror != glass on $arm")
            }
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    private fun paintScene(g: Gray8, l: Layout, seed: Int) {
        g.clear(0)
        g.fillRect(l.topBar, 3 * 17)
        var y = l.content.y
        var k = seed
        while (y < l.content.bottom) {
            g.fillRect(l.content.x, y, l.content.w, minOf(16, l.content.bottom - y), ((k % 14) + 1) * 17)
            y += 16; k++
        }
    }

    @Test
    fun mirrorEqualsTheGlassAfterEveryFlush(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val glass = GlassFirmwareSim()
            val t = TeeTransport(glass, scope)
            val done = HashSet<Long>()
            val failed = ArrayList<String>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    if (it is TransportEvent.FlushDone) synchronized(done) {
                        if (!it.ok) failed.add("flush ${it.id}: ${it.error}")
                        done.add(it.id)
                    }
                    if (it is TransportEvent.Fault) failed.add("fault ${it.what}: ${it.detail}")
                }
            }
            t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
            assertNotSame(glass, t.mirror, "the mirror is a private model, not the wire's far end")
            assertTrue(t.mirror.exact, "a local mirror is exact")

            val comp = Compositor()
            val l = Layout()
            suspend fun ship(a: Compositor.Assembled) {
                val id = t.submit(FlushRequest(a.ops, a.epoch, "", wide = a.wide))
                val t0 = System.currentTimeMillis()
                while (synchronized(done) { id !in done } && System.currentTimeMillis() - t0 < 10_000) delay(5)
                assertTrue(synchronized(done) { id in done }, "flush $id never completed")
            }
            suspend fun drain(label: String) {
                var n = 0
                while (comp.hasPending || comp.needsKeyframe) {
                    val a = comp.assembleFlush(Geometry.rectBudget(3)) ?: break
                    ship(a)
                    assertTrue(++n < 40, "$label: did not converge")
                }
                for (arm in Arm.entries) {
                    assertContentEquals(glass.panel(arm), t.mirror.panel(arm), "$label: mirror != glass on $arm")
                }
                assertTrue(glass.panel(Arm.LEFT).any { it.toInt() != 0 }, "$label: the glass is blank")
            }

            // keyframe, a delta, a declared shift (copy), a stereo plane map
            paintScene(comp.composed, l, 1)
            comp.requestKeyframe()
            drain("keyframe")
            comp.composed.fillRect(l.lens, 12 * 17)
            comp.damage(l.lens)
            drain("delta")
            val band = Rect(l.content.x, l.lens.bottom, l.content.w - Layout.RAIL_W, l.rowsBelow * Layout.ROW_H)
            val src = Rect(band.x, band.y + 32, band.w, band.h - 32)
            val dst = Rect(band.x, band.y, band.w, band.h - 32)
            val tmp = Gray8(src.w, src.h); tmp.blit(comp.composed, src, 0, 0)
            comp.composed.blit(tmp, Rect(0, 0, src.w, src.h), dst.x, dst.y)
            comp.composed.fillRect(band.x, band.bottom - 32, band.w, 32, 5 * 17)
            comp.declareShift(src, dst)
            comp.damage(Rect(band.x, band.bottom - 32, band.w, 32))
            drain("shift")
            comp.planes = listOf(Compositor.PlaneRegion(l.content, 8), Compositor.PlaneRegion(l.lens, 0))
            drain("stereo")

            assertEquals(emptyList(), failed, "faults or failed flushes during the tee run")
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun injectedInputReachesTheEventFlow(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = TeeTransport(GlassFirmwareSim(), scope)
            val seen = ArrayList<TransportEvent.Input>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is TransportEvent.Input) synchronized(seen) { seen.add(it) } }
            }
            t.injectInput(EvenHubMsg.EV_CLICK)
            t.injectInput(EvenHubMsg.EV_SCROLL_BOTTOM)
            val t0 = System.currentTimeMillis()
            while (synchronized(seen) { seen.size } < 2 && System.currentTimeMillis() - t0 < 5_000) delay(5)
            val got = synchronized(seen) { seen.toList() }
            assertEquals(listOf(EvenHubMsg.EV_CLICK, EvenHubMsg.EV_SCROLL_BOTTOM), got.map { it.type })
            assertTrue(got.all { it.source == EvenHubMsg.SRC_RING }, "injected gestures are ring gestures")
        } finally {
            scope.cancel()
        }
    }
}
