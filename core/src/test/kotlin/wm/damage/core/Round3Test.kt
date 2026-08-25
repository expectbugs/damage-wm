package wm.damage.core

import java.nio.file.Files
import wm.damage.core.transport.Arm
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent

/** Review round 3 regressions: each test is a defect a reviewer traced. */
class Round3Test {

    private fun fidCount(ops: List<DisplayOp>) =
        ops.count { it is DisplayOp.Delta || it is DisplayOp.StereoPair }

    /** D2: a flush must never span the 0xFFFE -> 1 fid wrap. Seeded three fids
     *  short of the wrap, a 3-rect flush and everything after it must still
     *  complete — before the fix every later flush failed FID001 forever. */
    @Test
    fun fidWrapInsideAFlushDoesNotWedgeTheTransport(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            t.seedFidsForTest(Geometry.FID_MAX - 2)
            val done = ArrayList<TransportEvent.FlushDone>()
            val faults = ArrayList<TransportEvent.Fault>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    if (it is TransportEvent.FlushDone) synchronized(done) { done.add(it) }
                    if (it is TransportEvent.Fault && it.what != "warmup") synchronized(faults) { faults.add(it) }
                }
            }
            val g = Gray8(640, 480)
            val full = Zl.encodeCfw(Pack.rect(g, Rect(0, 0, 640, 480)))
            t.start(full)
            t.submit(FlushRequest(listOf(DisplayOp.Keyframe(full)), 1, wide = true))
            g.fillRect(100, 100, 40, 20, 8 * 17)
            val p = Zl.encodeCfw(Pack.rect(g, Rect(100, 100, 40, 20)))
            // 3 rects with 3 fids left: the whole flush must move past the wrap
            t.submit(FlushRequest(listOf(
                DisplayOp.Delta(Rect(100, 100, 40, 20), p),
                DisplayOp.Delta(Rect(200, 100, 40, 20), p),
                DisplayOp.Delta(Rect(300, 100, 40, 20), p),
            ), 2))
            // and life goes on after the wrap
            t.submit(FlushRequest(listOf(DisplayOp.Delta(Rect(100, 200, 40, 20), p)), 3))
            t.submit(FlushRequest(listOf(DisplayOp.Delta(Rect(200, 200, 40, 20), p)), 4))
            val t0 = System.currentTimeMillis()
            while (synchronized(done) { done.size } < 4 && System.currentTimeMillis() - t0 < 10_000) delay(10)
            val all = synchronized(done) { done.toList() }
            assertEquals(4, all.size, "not every flush completed: $all")
            assertTrue(all.all { it.ok }, "a flush failed after the fid wrap: ${all.filter { !it.ok }}")
            assertEquals(emptyList(), synchronized(faults) { faults.toList() })
            for (arm in Arm.entries) {
                assertTrue(sim.flags(arm).none { it.value }, "sticky flags on $arm after the wrap: ${sim.flags(arm)}")
            }
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** C1: a keyframe of a busy plane map needs more per-lens ops than the
     *  16-fid ring holds. It must complete over several flushes, never emit a
     *  flush over the ring, and end with nothing pending. */
    @Test
    fun keyframeOfABusyPlaneMapDrainsWithinTheFidRing() {
        val comp = Compositor()
        val l = Layout()
        comp.composed.fillRect(l.content, 6 * 17)
        val box = Rect(l.notificationMax.x, l.notificationMax.y, l.notificationMax.w, 104)
        // the shell's busiest map, plus rows of small islands at DIFFERENT
        // depths: deltas only merge within one disparity and every far island
        // owes its own seam pair, so the per-lens work provably exceeds one
        // wide flush — the residual path must carry the rest
        val islands = (0 until 14).map { i ->
            val d = intArrayOf(4, 12, 16, 0)[i % 4]
            Compositor.PlaneRegion(Rect(l.content.x + 24 + (i % 7) * 80, l.content.y + 40 + (i / 7) * 60, 40, 20), d)
        }
        comp.planes = listOf(
            Compositor.PlaneRegion(l.content, 8),
            Compositor.PlaneRegion(l.lens, 0),
            Compositor.PlaneRegion(l.switcherPanel, 8),
            Compositor.PlaneRegion(Rect(l.switcherPanel.x, l.switcherPanel.y + 44, l.switcherPanel.w, 88), 0),
            Compositor.PlaneRegion(box, 8),
        ) + islands
        comp.requestKeyframe()
        var flushes = 0
        var fidTotal = 0
        var sawKeyframe = false
        while (comp.hasPending || comp.needsKeyframe) {
            val a = assertNotNull(comp.assembleFlush(Geometry.rectBudget(3)), "pending work assembled to nothing")
            flushes++
            if (a.ops.any { it is DisplayOp.Keyframe }) sawKeyframe = true
            val fids = fidCount(a.ops)
            assertTrue(fids <= Geometry.CFW_FID_RING, "flush $flushes carries $fids fids (> ring)")
            // wide exactly when the window must drain: a keyframe, leftover
            // dirt, or more rects than the pipelined budget (§8.2 #4)
            assertTrue(a.wide || fids <= Geometry.rectBudget(3),
                "flush $flushes carries $fids fids past the pipelined budget but is not wide")
            fidTotal += fids
            assertTrue(flushes < 10, "keyframe did not drain")
        }
        assertTrue(sawKeyframe)
        assertTrue(flushes >= 2, "this map was chosen to overflow one flush (got $flushes)")
        assertTrue(fidTotal > Geometry.CFW_FID_RING, "the per-lens work should exceed the ring, was $fidTotal")
    }

    /** C2: a region whose PLANE changes re-renders even though not one nominal
     *  byte moved — the live depth preview depends on it. */
    @Test
    fun planeChangeWithoutPixelChangeStillRenders() {
        val comp = Compositor()
        val l = Layout()
        comp.composed.fillRect(l.content, 5 * 17)
        comp.planes = listOf(Compositor.PlaneRegion(l.content, 8), Compositor.PlaneRegion(l.lens, 0))
        comp.requestKeyframe()
        while (comp.hasPending || comp.needsKeyframe) assertNotNull(comp.assembleFlush(5))
        assertEquals(null, comp.assembleFlush(5), "clean after the keyframe")

        // depth notch: same pixels, new disparity for the content plane
        comp.planes = listOf(Compositor.PlaneRegion(l.content, 12), Compositor.PlaneRegion(l.lens, 0))
        assertTrue(comp.hasPending, "a plane change must schedule work")
        val deltas = ArrayList<DisplayOp.Delta>()
        val pairs = ArrayList<DisplayOp.StereoPair>()
        while (comp.hasPending) {
            val a = assertNotNull(comp.assembleFlush(5))
            for (op in a.ops) when (op) {
                is DisplayOp.Delta -> deltas.add(op)
                is DisplayOp.StereoPair -> pairs.add(op)
                else -> {}
            }
        }
        assertTrue(deltas.any { it.disparity == 12 }, "content must re-render at the new disparity: $deltas")
        assertTrue(deltas.none { it.disparity == 8 }, "nothing may still render at the old disparity")
        assertTrue(pairs.isNotEmpty(), "the old render's ghost columns need seam cleanup")
        // the stale columns [612,616) on L / [24,28) on R lie inside a black
        // pair (the whole seam strip is painted; black over black is a no-op)
        assertTrue(pairs.any { it.left.x <= 612 && it.left.right >= 616 && it.left.y <= 34 },
            "left ghost columns not cleaned: $pairs")
        assertTrue(pairs.any { it.right.x <= 24 && it.right.right >= 28 && it.right.y <= 34 },
            "right ghost columns not cleaned: $pairs")
        assertTrue(pairs.all { it.left.w <= 16 && it.right.w <= 16 }, "a seam strip wider than the shift budget: $pairs")
    }

    /** Phone D1: a stop() that arrives while start() is still choreographing
     *  the display must NOT save default state over a file it never read. */
    @Test
    fun stopDuringStartPreservesPersistedState(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-r3")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = tmp.resolve("s.json")
            val seed = Persistence(file)
            seed.load()
            seed.put("test.marker", buildJsonObject { put("v", 1) })
            seed.save()

            val sim = GlassFirmwareSim()
            // modeled timing: the warmup + ack choreography takes real time,
            // which is the window the defect lived in
            val transport = SimTransport(sim, scope, SimTransport.Timing())
            val shell = Shell(FakeText(), transport, Persistence(file), null, scope) {
                Shell.LocalClock(12, 0, "12:00", "PM")
            }
            val starter = scope.launch { shell.start() }
            delay(20)            // start is now inside transport.start()
            shell.stop()         // must serialize behind it, then save properly
            starter.join()

            val check = Persistence(file)
            check.load()
            assertNotNull(check.get("test.marker"), "stop-during-start overwrote the state file")
            assertNotNull(check.get("shell.state"), "the orderly stop should have saved shell state")
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /** D1/D8 (transport): the SAME transport instance survives stop() and
     *  start() — the takeover path — with flushes completing in the new
     *  session and nothing from the old one leaking into it. */
    @Test
    fun transportRestartsCleanlyOnTheSameInstance(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val done = ArrayList<TransportEvent.FlushDone>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is TransportEvent.FlushDone) synchronized(done) { done.add(it) } }
            }
            val g = Gray8(640, 480)
            val full = Zl.encodeCfw(Pack.rect(g, Rect(0, 0, 640, 480)))
            repeat(2) { session ->
                t.start(full)
                val id = t.submit(FlushRequest(listOf(DisplayOp.Keyframe(full)), 1L + session, wide = true))
                val t0 = System.currentTimeMillis()
                while (synchronized(done) { done.none { it.id == id } } && System.currentTimeMillis() - t0 < 10_000) delay(10)
                val d = synchronized(done) { done.firstOrNull { it.id == id } }
                assertNotNull(d, "session $session: flush never completed")
                assertTrue(d.ok, "session $session: flush failed: ${d.error}")
                t.stop()
            }
        } finally {
            scope.cancel()
        }
    }
}
