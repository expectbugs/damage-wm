package wm.damage.core.local

import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg

/**
 * Decisions BS.3 (B083-rr): which of RIGHT's refusal records naming mode 26 get the repair P1 gives one naming mode 27
 * (`FIRMWARE.md` §6.7: the whole table again and ONE keyframe, [LocalRules.telemetry]). A refused sub-message ends its
 * batch on that lens (§4), so a mode 26 refused with something after it in its batch leaves RIGHT without the rest — a
 * CLOSE the transport put first (§5.6's close-first rule) with the frame, the table messages and the answer behind it,
 * the wrap's PLAY with the rules' tail behind it, a COMMIT or DISCARD with a SETTLE behind it. One alone in its message,
 * or last in its batch, loses only itself and the batch's present, and the next present goes whole (§4): no repair.
 *
 * The record names the mode, the reason and the present count, not the message, so the phone keeps what it wrote: each
 * message that carried a mode 26 with a sub-message after it, since the read before the last (two reads, so a message
 * still on its way to RIGHT when a read was answered is not dropped). A record naming 26 with no such message written
 * in that span was one of the lone ones.
 *
 * The rule against repeating: one repair, then none while each read shows a new mode-26 refusal (the engine word left
 * held, ENGINE gone on RIGHT alone: the repair's own batch is refused again); a read that shows nothing refused since
 * (the record unchanged) or a session start re-arms it. BIND, a lease gone and Silent Mode stop it before this: the
 * rules act only while BIND is in force, and the flags clear at every release point. When the refusal's own motion body
 * already asked a keyframe (build C1's ruling B-S2: a PLAY or COMMIT the mirror took, a slot the mirror holds open), the
 * repair re-sends the table and asks none of its own.
 *
 * State (`FORK.md` §3.8): (a) cleared at each session start, with the record's baseline; (b) nothing waits; (c) keyed on
 * the read count alone; (d) written by the image writer and the notify thread, read by the notify thread — one lock;
 * (e) each read decides once, on one snapshot; (f) RIGHT's record only — LEFT cannot report.
 */
class EngineRefusals {
    sealed class Decision
    /** The shell re-sends the whole table and, when [keyframe], asks ONE keyframe. [sent]: what was written, for the notes. */
    data class Repair(val ref: DamageMsg.Refusal, val sent: String, val keyframe: Boolean) : Decision()
    /** A new mode-26 refusal at a read after one was repaired, with none clean between: not repaired again. */
    data class Standing(val ref: DamageMsg.Refusal, val sent: String) : Decision()
    /** Nothing written in the span had a sub-message behind a mode 26: no repair. */
    data class Alone(val ref: DamageMsg.Refusal) : Decision()

    /** Per read span: each mode-26 sub with something after it in its batch, as "SUB slot n", and how many messages. */
    private var now = LinkedHashMap<String, Int>()
    private var before: Map<String, Int> = emptyMap()
    private var latched = false
    private var bodyKeyframed = false

    /** Every image the phone writes ([wm.damage.core.transport.CfwTransportBase]'s one write path). */
    @Synchronized fun wrote(image: ByteArray) { for (k in ahead(image)) now[k] = (now[k] ?: 0) + 1 }

    /** Each outcome-2 motion body from RIGHT: whether it asked a keyframe of its own (B-S2). The newest one stands. */
    @Synchronized fun refusalBody(keyframed: Boolean) { bodyKeyframed = keyframed }

    @Synchronized fun sessionStart() { now = LinkedHashMap(); before = emptyMap(); latched = false; bodyKeyframed = false }

    /** One RIGHT telemetry read: [ref] its record, [fresh] new since the last read and not the session's baseline. */
    @Synchronized fun read(ref: DamageMsg.Refusal?, fresh: Boolean): Decision? {
        val span = LinkedHashMap(before).also { m -> for ((k, n) in now) m[k] = (m[k] ?: 0) + n }
        val keyframed = bodyKeyframed
        before = now; now = LinkedHashMap(); bodyKeyframed = false
        if (!fresh) { latched = false; return null }
        if (ref == null || ref.mode and 0x7F != CfwModes.MOTION_MODE) return null
        if (span.isEmpty()) return Alone(ref)
        val sent = span.entries.joinToString { (k, n) -> "$k ×$n" }
        if (latched) return Standing(ref, sent)
        latched = true
        return Repair(ref, sent, keyframe = !keyframed)
    }

    companion object {
        private val SUBS = mapOf(0 to "PLAY", 1 to "RETARGET", 2 to "CLOSE", 3 to "COMMIT", 4 to "DISCARD", 5 to "SETTLE")

        /** The mode-26 sub-messages of a mode-8 batch [image] that have another sub-message after them. */
        fun ahead(image: ByteArray): List<String> {
            if (image.size < 2 || image[0].toInt() != 8) return emptyList()
            val n = image[1].toInt() and 0xFF
            val out = ArrayList<String>()
            var i = 2
            for (k in 0 until n) {
                if (i + 2 > image.size) break
                val len = (image[i].toInt() and 0xFF) or ((image[i + 1].toInt() and 0xFF) shl 8)
                val at = i + 2
                if (k < n - 1 && len >= 3 && at + 3 <= image.size && image[at].toInt() and 0x7F == CfwModes.MOTION_MODE) {
                    val sub = image[at + 1].toInt() and 0xFF
                    val slot = image[at + 2].toInt() and 0xFF
                    out += "${SUBS[sub] ?: "sub $sub"} slot ${if (slot == CfwModes.MOTION_ALL) "all" else slot}"
                }
                i = at + len
            }
            return out
        }
    }
}
