package wm.damage.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.LinkState
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.transport.atlasCapacity
import wm.damage.core.transport.draw2
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg

/**
 * APK 0.65, P5 item 7 (the twice-read sweep's F2, its corrected fix): a contract-2 start decides DRAW2 ONCE, from
 * the cache size the session got. A v2 atlas lays out over the size less the strips' reserve, so a session whose
 * cache is under 128 KiB armed DRAW2 with an atlas capacity of 0 — the shell's atlas builder refuses that and the
 * loop faults. Four ways a session gets 64 KiB: the cache allocated already at 64 (status 4), a size off the wire
 * outside 64..160 (the fallback), op 5 refused, and a hand probe that lifts the hold-back while the start waits on
 * its size. Each pin is watched to fail on the tree before the fix; the build report's pins table has each one.
 */
class Draw2SessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun clean() { scope.cancel() }

    private val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
    private val notes = ArrayList<Pair<String, String>>()
    private fun has(kind: String, text: String) = synchronized(notes) { notes.any { it.first == kind && text in it.second } }
    private fun collect(t: CfwTransportBase) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            t.events.collect { if (it is TransportEvent.Note) synchronized(notes) { notes.add(it.kind to it.detail) } }
        }
    }

    /** The implication every pin reads: DRAW2 in force only over a cache a v2 atlas can lay out on. */
    private fun assertAtlasFits(st: LinkState, what: String) {
        assertTrue(!st.draw2 || st.atlasCapacity in CfwModes.TEXTURE_CACHE_SIZE..CfwModes.CACHE2_MAX,
            "$what: DRAW2 in force (flags 0x${st.flagsInForce.toString(16)}) over a ${st.cacheSize / 1024} KiB cache — " +
                "atlas capacity ${st.atlasCapacity}; notes: ${synchronized(notes) { notes.filter { it.first in setOf("keeper", "glass") } }}")
    }

    /** The cache allocated at 64 KiB in the session before (a size asked by hand, then one write), the link ends, and
     *  the next start — inside the lease, so the cache is kept — asks for 160 and is told "allocated, 64 KiB". */
    @Test
    fun aCacheAllocatedAt64KiBIsNotArmedForDraw2(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2 }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        collect(t)
        t.start(full)
        assertTrue(t.state.value.draw2, "the first session arms DRAW2 at 160 KiB")
        // the poll's telemetry read is off on an instant transport, so the only field-112 writes now are the probe's two
        val writes = sim.damageControlWritesSeen
        t.devProbe("cachesize", "64")
        waitUntil(10_000, message = { "the hand-asked size reached both lenses: ${sim.damageControlWritesSeen - writes} writes" }) {
            sim.damageControlWritesSeen >= writes + 2
        }
        assertEquals(DamageMsg.STATUS_OK, sim.damageStatus(Arm.LEFT), "the model took the hand-asked size")
        val rec = wm.damage.core.wire.TextureCache.Image2(8, 4, ByteArray(32) { 15 }).encode()
        val msg = CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(1, rec)), 64 * 1024)
        t.submit(FlushRequest(listOf(DisplayOp.CacheWrite2(msg)), 0, "ATLAS"))
        waitUntil(10_000, message = { "the write allocated the cache at 64 KiB: ${sim.cacheSize(Arm.RIGHT)}" }) {
            Arm.entries.all { sim.cacheSize(it) == 64 * 1024 }
        }
        t.endLink("test: the link ends inside the lease")
        t.start(full)
        assertTrue(has("glass", "status 4, 64 KiB this session (allocated already)"), "the second start was told the cache is up at 64 KiB: $notes")
        assertAtlasFits(t.state.value, "status 4 at 64 KiB")
        assertTrue(has("keeper", "DRAW2 not armed: this session's cache is 64 KiB"), "the branch that does not arm says why: $notes")
        t.stop()
    }

    /** Field 18 off the wire outside 64..160 KiB: the start falls back to the v1 64 KiB — and must not arm DRAW2 over it. */
    @Test
    fun aSizeOutsideTheBudgetFallsBackWithoutDraw2(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2 }
        val t = StartRig(sim, scope)
        collect(t)
        val op5Out = java.util.concurrent.atomic.AtomicBoolean(false)
        t.onWrite = { _, p -> if (StartRig.damageOp(p) == DamageMsg.OP_CACHE_SIZE) op5Out.set(true); p }
        // op 5's reply (id 0, its own status) carries a field 18 of 48 KiB
        t.onNotify = { _, p ->
            val r = StartRig.telemetry(p)
            if (r != null && r.requestId == 0L && op5Out.getAndSet(false)) { StartRig.withRecordField(p, 18, 48L * 1024) } else p
        }
        t.start(full)
        assertTrue(has("glass", "outside 64..160 KiB — this session uses the v1 64 KiB"), "the fallback ran: $notes")
        assertAtlasFits(t.state.value, "the out-of-range fallback")
        assertTrue(has("keeper", "DRAW2 not armed: this session's cache is 64 KiB"), "the branch that does not arm says why: $notes")
        t.stop()
    }

    /** Op 5 refused (status 5, outside the budget): the first write allocates the v1 64 KiB, so no DRAW2. The
     *  request's size is moved past 160 on its way to both arms, so the model itself refuses it. */
    @Test
    fun aRefusedCacheSizeIsNotArmedForDraw2(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2 }
        val t = StartRig(sim, scope)
        collect(t)
        t.onWrite = { _, p ->
            if (StartRig.damageOp(p) != DamageMsg.OP_CACHE_SIZE) p
            else {
                val b = StartRig.body(p)
                val i = StartRig.controlAt(b)
                b[i + 4] = 200.toByte(); b[i + 5] = 0                    // 200 KiB: status 5 on both lenses
                StartRig.reframe(p, b)
            }
        }
        t.start(full)
        assertTrue(has("glass", "cache size: asked 160 KiB, status 5, 64 KiB this session"), "the model refused the size and the start took 64 KiB: $notes")
        assertAtlasFits(t.state.value, "a refused op 5")
        assertTrue(has("keeper", "DRAW2 not armed: this session's cache is 64 KiB"), "the branch that does not arm says why: $notes")
        t.stop()
    }

    /** The race (the finding's own pin): a reset held DRAW2 back, so the next start asks for 64 KiB; while it waits on
     *  that answer a hand probe asks for bit 2 (which lifts the hold-back and puts bit 2 in the wish). The start read
     *  the hold-back three times across the wait: the size for a held-back session, then a DRAW2 arming over it. */
    @Test
    fun aProbeDuringTheSizesWaitDoesNotArmDraw2OverASmallCache(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2 }
        var clock = 1_000_000L
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
        collect(t)
        t.start(full)
        assertTrue(t.state.value.draw2, "the first session arms DRAW2")
        // the glasses reset 10 s after the arming, the lease lapses, the session is rebuilt: held back
        clock += 10_000L
        sim.uptimeOffsetMs = clock
        clock += 200_000L
        assertTrue(t.restartSession("test: after a reset"))
        t.start(full)
        waitUntil(10_000, message = { "the hold-back: $notes" }) { has("keeper", "hold-back: the glasses reset 10 s after flags 0x4 were armed") }
        assertTrue(!t.state.value.draw2, "the held-back session is not DRAW2")
        assertTrue(t.restartSession("test: the next start"))
        // the next start's op 5 answer is lost once: the start waits 2 s for the re-ask's
        val dropped = java.util.concurrent.atomic.AtomicBoolean(false)
        t.notifyFilter = { _, p ->
            val r = StartRig.telemetry(p)
            !(r != null && r.requestId == 0L && dropped.compareAndSet(false, true))
        }
        val starting = async { t.start(full) }
        waitUntil(10_000, message = { "the size's first answer lost" }) { dropped.get() }
        t.devProbe("flags", "0x4")
        starting.await()
        assertTrue(has("glass", "cache size: asked 64 KiB"), "the held-back start asked for 64 KiB: $notes")
        // the start's one read of the hold-back says what it decided on, the probe's lift notwithstanding
        assertTrue(has("keeper", "DRAW2 held back since a reset followed its arming"), "the held-back start said so: $notes")
        assertAtlasFits(t.state.value, "a probe inside the size's wait")
        t.stop()
    }
}
