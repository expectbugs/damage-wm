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
 * still on its way to RIGHT when a read was answered is not dropped) and for at least [SPAN_FLOOR_MS] after it was
 * written (the lead's ruling A1 on the focused read FR-083: the cache count asks again every 500 ms while RIGHT catches
 * up, and RIGHT handles a batch up to about a second after LEFT's ack, so two reads alone can pass before RIGHT has
 * handled it; the span is trimmed at a read, on the transport's clock — no timer). A record naming 26 with no such
 * message in that span was one of the lone ones.
 *
 * The rule against repeating, for every repair a refusal record asks (the lead's ruling on B083-rr: a record naming 26,
 * and one naming 27 — P1's own repair, which the gate decides too): one repair, then none while each read shows a new
 * refusal of either mode (the engine word left held, ENGINE gone on RIGHT alone, a table message the phone builds
 * wrongly: the repair's own batch is refused again); a read that shows nothing refused since (the record unchanged) or
 * a session start re-arms it. ONE latch for the two modes: the repair re-sends the whole table either way, and a condition that refuses a 26
 * refuses a 27 too (§6.3's checks hold §5.5's), so a 27 at the read after a 26's repair is that repair's own table
 * refused — standing. BIND, a lease gone and Silent Mode stop it before this: the rules act only while BIND is in force,
 * and the flags clear at every release point. When the refusal's own motion body already asked a keyframe (build C1's
 * ruling B-S2: a PLAY or COMMIT the mirror took, a slot the mirror holds open), a 26's repair re-sends the table and asks
 * none of its own; a refused 27 sends no body, so its repair always keyframes.
 *
 * State (`FORK.md` §3.8): (a) cleared at each session start, with the record's baseline; (b) nothing waits; (c) keyed on
 * the read count and the time each message was written ([clock], the transport's); (d) written by the image writer and
 * the notify thread, read by the notify thread — one lock; (e) each read decides once, on one snapshot; (f) RIGHT's
 * record only — LEFT cannot report.
 */
class EngineRefusals(private val clock: () -> Long) {
    sealed class Decision { abstract val ref: DamageMsg.Refusal }
    /** The shell re-sends the whole table and, when [keyframe], asks ONE keyframe. [sent]: what was written, for the notes
     *  (a 26's span; empty for a 27). */
    data class Repair(override val ref: DamageMsg.Refusal, val sent: String, val keyframe: Boolean) : Decision()
    /** A new refusal naming 26 or 27 at a read after one was repaired, with none clean between: not repaired again. */
    data class Standing(override val ref: DamageMsg.Refusal, val sent: String) : Decision()
    /** Nothing written in the span had a sub-message behind a mode 26: no repair. */
    data class Alone(override val ref: DamageMsg.Refusal) : Decision()

    /** One "SUB slot n" in the span: messages written while it stayed there, when the newest was written, and the reads
     *  since then. The newest is the one that stays longest, so one entry per key decides as one per message would. */
    private class Booked(var n: Int, var atMs: Long, var reads: Int)
    /** Each mode-26 sub with something after it in its batch, as "SUB slot n" (at most a few dozen keys). */
    private val booked = LinkedHashMap<String, Booked>()
    private var latched = false
    private var bodyKeyframed = false

    /** Every image the phone writes ([wm.damage.core.transport.CfwTransportBase]'s one write path). */
    @Synchronized fun wrote(image: ByteArray) {
        val nowMs = clock()
        for (k in ahead(image)) booked.getOrPut(k) { Booked(0, nowMs, 0) }.let { it.n++; it.atMs = nowMs; it.reads = 0 }
    }

    /** Each outcome-2 motion body from RIGHT: whether it asked a keyframe of its own (B-S2). The newest one stands. */
    @Synchronized fun refusalBody(keyframed: Boolean) { bodyKeyframed = keyframed }

    @Synchronized fun sessionStart() { booked.clear(); latched = false; bodyKeyframed = false }

    /** One RIGHT telemetry read: [ref] its record, [fresh] new since the last read and not the session's baseline. A
     *  message leaves the span once two reads have passed since it was written AND [SPAN_FLOOR_MS]. */
    @Synchronized fun read(ref: DamageMsg.Refusal?, fresh: Boolean): Decision? {
        val nowMs = clock()
        booked.values.removeIf { it.reads >= 2 && nowMs - it.atMs >= SPAN_FLOOR_MS }
        val span = LinkedHashMap<String, Int>().also { m -> for ((k, b) in booked) m[k] = b.n }
        for (b in booked.values) b.reads++
        val keyframed = bodyKeyframed
        bodyKeyframed = false
        if (!fresh) { latched = false; return null }
        if (ref == null) return null
        val mode = ref.mode and 0x7F
        if (mode != CfwModes.MOTION_MODE && mode != CfwModes.BIND_MODE) return null
        // a record naming 27 (P1's own repair) has no span to read and always keyframes; it takes the same latch
        val m26 = mode == CfwModes.MOTION_MODE
        if (m26 && span.isEmpty()) return Alone(ref)
        val sent = if (m26) span.entries.joinToString { (k, n) -> "$k ×$n" } else ""
        if (latched) return Standing(ref, sent)
        latched = true
        return Repair(ref, sent, keyframe = !m26 || !keyframed)
    }

    companion object {
        /** A1: how long a written message stays in the span at the least — 2 × the cache count's settle window, which is
         *  sized on RIGHT's measured lag behind LEFT's ack (up to about a second, `CacheLedger.SETTLE_MS`). The batch-14
         *  re-send's span ([LocalRules.telemetry]) keeps the same floor. */
        const val SPAN_FLOOR_MS = 2 * wm.damage.core.shell.CacheLedger.SETTLE_MS
        private val SUBS =mapOf(0 to "PLAY", 1 to "RETARGET", 2 to "CLOSE", 3 to "COMMIT", 4 to "DISCARD", 5 to "SETTLE")

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
