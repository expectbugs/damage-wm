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

    /** The physical link ends out from under the session, as BlueZ's `Connected=false` does: the
     *  seam a test uses to make the keeper rebuild. `ClockedTransport` in the tests has had the
     *  same one-liner; this puts it where every sim-backed rig can reach it. */
    fun endLink(reason: String) = onLinkDown(reason)

    /** 0.64: `sim = …` moves the firmware MODEL's knobs — it exists so the low-battery notice and every
     *  new wear / case note can be driven on the SIM target (the phone's included) with nothing on a
     *  radio. Every other probe is the base's.
     *
     *    sim = battery=N (0..100) | charging=on|off | worn=yes|no | wearswitch=on|off | case=docked|out
     *
     *  A knob the device-info READ reports (battery, charging, wearswitch) queues ONE READ at once (the
     *  P4 review's finding 17): on the modeled-timing simulator the next one is the poll's, up to a
     *  minute away. `worn` and `case` are pushes of the model's own, when it has one to send. */
    override fun devProbe(name: String, value: String) {
        if (name != "sim") return super.devProbe(name, value)
        Log.i("sim", "probe: $name=$value")
        emitNote("probe", "$name=$value")
        val knob = value.substringBefore('=')
        val arg = value.substringAfter('=', "")
        fun onOff(yes: String, no: String): Boolean? = when (arg) { yes -> true; no -> false; else -> null }
        var read = false
        val said: String? = when (knob) {
            "battery" -> arg.toIntOrNull()?.takeIf { it in 0..100 }?.let { sim.batteryPct = it; read = true; "the model's battery reads $it%" }
            "charging" -> onOff("on", "off")?.let { sim.charging = it; read = true; "the model reads ${if (it) "charging" else "not charging"}" }
            "wearswitch" -> onOff("on", "off")?.let { sim.setWearDetectionSwitch(it); read = true; "the model's wear-detection switch is ${if (it) "on" else "off"}" }
            "worn" -> onOff("yes", "no")?.let { sim.sensorReportsWear(it)
                "the model's sensor reports ${if (it) "worn" else "not worn"} (an event follows only while its switch is on)" }
            "case" -> when (arg) {
                // two frames, as a docking reads upstream: the in-case status alone, then the case's own level
                "docked" -> { sim.pushCase(inCase = 1); sim.pushCase(soc = 78, lid = 1, inCase = 1); "the model pushed two case frames (docked)" }
                "out" -> { sim.pushCase(); "the model pushed one case frame with no status (out)" }
                else -> null
            }
            else -> null
        }
        if (said == null) {
            Log.w("sim", "probe sim=$value: expected battery=0..100 | charging=on|off | worn=yes|no | wearswitch=on|off | case=docked|out")
            emitNote("probe", "sim=$value not taken — expected battery=0..100 | charging=on|off | worn=yes|no | wearswitch=on|off | case=docked|out")
            return
        }
        val readNote = if (!read) "" else if (readDeviceInfoNow()) "; one device-info READ queued" else "; no session — the next start's READ reports it"
        emitNote("probe", "sim: $said$readNote")
    }

    override fun nowMs(): Long = clock()

    init {
        sim.attachListener(object : GlassFirmwareSim.SimDiag {
            override fun event(kind: String, detail: String) {
                Log.w("sim/$kind", detail)
                // the mirror tee's seven kinds (CfwTransportBase's listener); `image` (P5 item 18, the one-sibling
                // sweep's OS-7) is the model's word for interleaved fragments and an image with no layout
                if (kind in setOf("decode", "fid", "session", "msgid", "compressmode", "abort", "image"))
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
