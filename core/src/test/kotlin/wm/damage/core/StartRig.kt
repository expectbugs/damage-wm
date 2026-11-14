package wm.damage.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.Pb

/**
 * APK 0.65, P5 part b: the base choreography over the firmware model with a script on each side of the wire —
 * the rig of the start-gate pins (`Draw2SessionTest`, `PreludeTest`) and the restart-window pin. The pacing is the
 * product's instant pacing; nothing here shortens a wait.
 *
 *  - [onWrite] sees each packet on its way to an arm: it returns the packet (or a replacement) to write, null to
 *    swallow it (the glasses never see it), or throws to fail the write as a characteristic that is gone does.
 *  - [onNotify] sees each packet the model sends back: the packet (or a replacement), or null to lose it.
 *  - [disconnectGate]: while set, `disconnectLink()` waits on it — the phone's two disconnects in turn.
 */
internal open class StartRig(val glass: GlassFirmwareSim, scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis,
                        private val instantPacing: Boolean = true) :
    CfwTransportBase(scope, "start-rig") {
    /** P5 verify: false gives the product's paced bounds (the stall tick's 10 s) to a pin that holds an ack by hand. */
    override val instant: Boolean get() = instantPacing
    @Volatile var onWrite: ((Arm, ByteArray) -> ByteArray?)? = null
    @Volatile var onNotify: ((Arm, ByteArray) -> ByteArray?)? = null
    @Volatile var disconnectGate: CompletableDeferred<Unit>? = null
    @Volatile var disconnectParked = false
    /** Packets that reached an arm while [counting] was set. */
    val counted = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var counting = false

    init {
        glass.attachListener(object : GlassFirmwareSim.SimDiag {
            override fun event(kind: String, detail: String) {}
            override fun notify(arm: Arm, packet: ByteArray) {
                val f = onNotify
                val p = if (f == null) packet else (f(arm, packet) ?: return)
                onNotifyPacket(arm, p)
            }
            override fun panelChanged(arm: Arm) {}
        })
    }

    override fun nowMs(): Long = clock()
    override suspend fun connectLink() {}
    override suspend fun disconnectLink() {
        val g = disconnectGate
        if (g != null) { disconnectParked = true; g.await() }
        glass.linkReset()
    }
    override suspend fun writeArm(arm: Arm, packet: ByteArray) {
        val f = onWrite
        val p = if (f == null) packet else (f(arm, packet) ?: return)
        if (counting) counted.incrementAndGet()
        glass.write(arm, p, nowMs())
    }
    override fun onMaintenanceTick() { glass.tick(nowMs()) }
    fun endLink(reason: String) = onLinkDown(reason)
    /** Hand a packet [onNotify] held back to the transport late, as a link whose answer is still on its way. */
    fun deliver(arm: Arm, packet: ByteArray) = onNotifyPacket(arm, packet)

    companion object {
        /** The sid of an AA packet (`AaFrame`: [AA][type][seq][len][pktTot][pktSer][sid][flag]...). */
        fun sid(p: ByteArray): Int = p[6].toInt() and 0xFF
        /** The message body of a single-packet AA frame (the CRC's two bytes off the end). */
        fun body(p: ByteArray): ByteArray = p.copyOfRange(AaFrame.HEADER, p.size - AaFrame.CRC)
        /** The same frame with a new body: `AaFrame.frame` with the packet's own seq, sid, flag and type. */
        fun reframe(p: ByteArray, body: ByteArray): ByteArray =
            AaFrame.frame(p[2].toInt() and 0xFF, sid(p), p[7].toInt() and 0xFF, body, p[1].toInt() and 0xFF).single()
        /** Where a DamageControl body (`DamageMsg.control`: 'D' 'M' 1 op argLo argHi) starts in [b], or -1. */
        fun controlAt(b: ByteArray): Int {
            for (i in 0..b.size - 6) if (b[i] == 'D'.code.toByte() && b[i + 1] == 'M'.code.toByte() && b[i + 2].toInt() == 1) return i
            return -1
        }
        /** The DamageControl op a sid-0x09 packet carries, or -1. */
        fun damageOp(p: ByteArray): Int {
            if (sid(p) != wm.damage.core.wire.SettingsMsg.SID || p.size < AaFrame.HEADER + AaFrame.CRC) return -1
            val b = body(p)
            val i = controlAt(b)
            return if (i < 0) -1 else b[i + 3].toInt() and 0xFF
        }
        /** The telemetry record (field 111) a sid-0x09 packet from the glasses carries, or null. */
        fun telemetry(p: ByteArray): DamageMsg.Telemetry? =
            if (sid(p) != wm.damage.core.wire.SettingsMsg.SID || p.size < AaFrame.HEADER + AaFrame.CRC) null
            else DamageMsg.parseTelemetry(body(p))
        /** The same reply with record field [field] set to [value] — the model's own shape
         *  (`GlassFirmwareSim.damageControl`: {1:3, 2:0, 111:{varints}}), reframed. */
        fun withRecordField(p: ByteArray, field: Int, value: Long): ByteArray {
            val t = telemetry(p) ?: return p
            val fields = LinkedHashMap(t.fields).also { it[field] = value }
            val record = Pb.cat(fields.entries.sortedBy { it.key }.map { Pb.v(it.key, it.value) })
            return reframe(p, Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(DamageMsg.TELEMETRY_FIELD, record)))
        }
        /** The first AA packet (serial 1) of an EvenHub image message: `08 03 …` after the header
         *  (`EvenHubMsg.imageFragment`, G2CC docs/G2_BLE_PROTOCOL.md §6.5). */
        fun firstOfImage(p: ByteArray): Boolean = p.size > AaFrame.HEADER + 1 && p[5].toInt() == 1 &&
            sid(p) == EvenHubMsg.SID && p[AaFrame.HEADER].toInt() == 0x08 && p[AaFrame.HEADER + 1].toInt() == EvenHubMsg.CMD_IMAGE
    }
}
