package wm.damage.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.util.Log

/**
 * The CFW choreography ([CfwTransportBase]) over the byte-exact firmware model
 * — the development transport. Link timing is modeled from the measured
 * numbers (176 ms ack, ~11 KB/s) so the scheduler and telemetry behave
 * realistically; [Timing.instant] turns modeling off for tests.
 */
class SimTransport(
    private val sim: GlassFirmwareSim,
    scope: CoroutineScope,
    private val timing: Timing = Timing(),
    private val clock: () -> Long = System::currentTimeMillis,
) : CfwTransportBase(scope, "sim", mirrorSim = sim) {

    data class Timing(
        val ackMs: Long = 176,
        val bytesPerSec: Double = 11_000.0,
        val instant: Boolean = false,
    )

    override val instant: Boolean get() = timing.instant

    /** Test hook: return false to drop a packet the sim is sending back
     *  (acks included). Null passes everything. */
    @Volatile var notifyFilter: ((Arm, ByteArray) -> Boolean)? = null

    override fun nowMs(): Long = clock()

    init {
        sim.attachListener(object : GlassFirmwareSim.SimDiag {
            override fun event(kind: String, detail: String) {
                Log.w("sim/$kind", detail)
                if (kind in setOf("decode", "fid", "session", "msgid", "compressmode", "abort"))
                    emitFault(kind, detail)
            }

            override fun notify(arm: Arm, packet: ByteArray) {
                // a test may LOSE a packet on the way back (an ack that never
                // arrives is the phone path's commonest fault, §33.4)
                if (notifyFilter?.invoke(arm, packet) == false) return
                onNotifyPacket(arm, packet)
            }

            override fun panelChanged(arm: Arm) {}
        })
    }

    override suspend fun connectLink() {
        // the sim is always in range; nothing to negotiate
    }

    override suspend fun disconnectLink() {
        sim.linkReset()        // a new session sends a new prelude and CREATE
    }

    override suspend fun writeArm(arm: Arm, packet: ByteArray) {
        if (!timing.instant) {
            val ms = (packet.size / timing.bytesPerSec * 1000).toLong()
            if (ms > 0) delay(ms)
        }
        sim.write(arm, packet, clock())
    }

    /** Model the ack round trip per image message, then surface the sim's
     *  sticky diagnostic flags — programmatic access the hardware only offers
     *  visually (mode-7 overlay) or via the untested logger sid. */
    override suspend fun onImageDelivered() {
        if (!timing.instant) delay(timing.ackMs)
        val l = sim.flags(Arm.LEFT)
        val r = sim.flags(Arm.RIGHT)
        emitFlags((l.keys + r.keys).associateWith { (l[it] ?: false) || (r[it] ?: false) })
    }

    /** Drive the sim's clock (lease fail-open) and mirror lease state. */
    override fun onMaintenanceTick() {
        sim.tick(clock())
        val held = sim.leaseHeld(Arm.LEFT, clock()) &&
            sim.leaseHeld(Arm.RIGHT, clock())
        if (!held && state.value.started) {
            setLease(false, "lease LOST — stock repaints (fail-open)")
        } else if (held) {
            setLease(true, "lease held")
        }
    }

}
