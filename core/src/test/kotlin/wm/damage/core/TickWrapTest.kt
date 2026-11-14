package wm.damage.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.SettingsMsg

/**
 * Build W, package G (the lead's decision H, row E7; G-design §6). Context for the reader: Adam's own glasses report
 * their OS tick (a u32, 1.024 per ms) in each telemetry record; on the present boot it comes round to 0 at about
 * 2026-12-06 20:58. The phone used to read that as a reset — a false `keeper` note, and DRAW2 held back for every
 * later session when an arming had gone before it within 120 s. These pins: a reading one wrap on from the
 * prediction is the wrap, just before it is nothing, and a real reset across the same boundary is still a reset.
 * Watched on the unfixed tree: the wrap was noted as a reset, and the session after it held DRAW2's arming back.
 */
class TickWrapTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun clean() { scope.cancel() }

    private val wrapTicks = 1L shl 32

    /** A RIGHT telemetry record (`DamageMsg.parseTelemetry`'s shape: field 111 with its varints) carrying [ticks]. */
    private fun record(id: Long, ticks: Long) = AaFrame.frame((id and 0xFF).toInt(), SettingsMsg.SID, SettingsMsg.FLAG_RESPONSE,
        Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(DamageMsg.TELEMETRY_FIELD, Pb.cat(Pb.v(1, id), Pb.v(2, ticks)))), AaFrame.TYPE_RESPONSE).single()

    private class Notes(scope: CoroutineScope, t: CfwTransportBase) {
        val list = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        init { scope.launch(start = CoroutineStart.UNDISPATCHED) { t.events.collect { if (it is TransportEvent.Note) list.add(it.kind to it.detail) } } }
        fun keeper() = list.filter { it.first == "keeper" }.map { it.second }
    }

    private suspend fun readings(firstTicks: Long, secondTicks: Long, apartMs: Long): List<String> {
        var clock = 5_000_000L
        val t = StartRig(GlassFirmwareSim(), scope, clock = { clock })
        val notes = Notes(scope, t)
        t.deliver(Arm.RIGHT, record(1, firstTicks))
        clock += apartMs
        t.deliver(Arm.RIGHT, record(2, secondTicks))
        clock += 1_000
        t.deliver(Arm.RIGHT, record(3, secondTicks + 1024))          // one more second in the new frame: nothing
        delay(200)
        return notes.keeper()
    }

    @Test
    fun aWrapIsAWrapAndARealResetAcrossTheSameBoundaryIsStillAReset(): Unit = runBlocking {
        // at the wrap: 2^32 - 2,048 ticks (2,000 ms short of it), then 1,024 ticks (1,000 ms), 3 s apart
        val wrap = readings(wrapTicks - 2_048, 1_024, apartMs = 3_000)
        assertTrue(wrap.none { it.startsWith("the glasses reset") }, "a wrap is no reset: $wrap")
        assertEquals(1, wrap.count { it.startsWith("the glasses' tick wrapped (not a reset)") }, "the wrap is said once: $wrap")
        // just before it: 2^32 - 4,096 then 2^32 - 1,024, 3 s apart — the counter has not come round
        val before = readings(wrapTicks - 4_096, wrapTicks - 1_024, apartMs = 3_000)
        assertTrue(before.isEmpty(), "nothing to say before the wrap: $before")
        // a real reset across the same boundary: the same two readings 300 s apart (the prediction is 298 s past the wrap)
        val reset = readings(wrapTicks - 2_048, 1_024, apartMs = 300_000)
        assertEquals(1, reset.count { it.startsWith("the glasses reset") }, "a real reset is still a reset: $reset")
        assertTrue(reset.none { "wrapped" in it }, "and not called a wrap: $reset")
    }

    /**
     * The hold-back rule across a wrap: DRAW2's flags armed 60 s before the counter comes round, a session rebuilt
     * after it. The model's tick is cut to 32 bits on its way back (the model counts on); the rebuilt session's reset
     * check reads the small value. Unfixed, that read as a reset 66 s after the arming: the hold-back fired and the
     * wish was dropped. Fixed: the wrap is said and the flags are armed again.
     */
    @Test
    fun aWrapAfterAnArmingHoldsNothingBack(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 1 }
        var clock = 1_000_000L
        val upMs = CfwTransportBase.TICK_WRAP_MS - 63_000         // 63 s before the wrap at the first start
        sim.uptimeOffsetMs = clock - upMs
        val t = StartRig(sim, scope, clock = { clock })
        t.onNotify = { arm, p ->
            val rec = StartRig.telemetry(p)
            val ticks = rec?.fields?.get(2)
            if (arm == Arm.RIGHT && ticks != null && ticks >= wrapTicks) StartRig.withRecordField(p, 2, ticks and 0xFFFFFFFFL) else p
        }
        val notes = Notes(scope, t)
        val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
        t.start(full)
        t.devProbe("flags", "probe")
        waitUntil(10_000, 10, { "armed by the probe: ${notes.list}" }) { Arm.entries.all { sim.damageFlags(it) == DamageMsg.FLAG_PROBE } }
        clock += 66_000                                           // past the wrap; the lease lapses on the way
        assertTrue(t.restartSession("test: after the wrap"))
        t.start(full)
        waitUntil(10_000, 10, { "the wrap is said: ${notes.list}" }) { notes.keeper().any { it.startsWith("the glasses' tick wrapped") } }
        waitUntil(10_000, 10, { "the wish is armed again after the start: ${notes.list}" }) {
            notes.keeper().any { "armed bit 15" in it } && Arm.entries.all { sim.damageFlags(it) == DamageMsg.FLAG_PROBE }
        }
        assertTrue(notes.keeper().none { it.startsWith("hold-back") || it.startsWith("the glasses reset") }, "no reset, no hold-back: ${notes.keeper()}")
        t.stop()
    }
}
