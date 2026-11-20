package wm.damage.core.shell

import wm.damage.core.motion.Transitions

/**
 * Build C3, stage DP2: one full-screen transition as the shell runs it (`FIRMWARE.md` §7.11; `MOTION.md` §7). Context
 * for the reader: Adam's own glasses on his own firmware fork; display-rendering work. Loop-only state.
 *
 * [Phase.OWED]: the act happened (a Select, a Back, the silent clock entered or left) and the screen was painted; the
 * next pump sends the records' write and then, alone, the rules' CLEARs, the out half's PLAY (slot [OUT_SLOT]) and the
 * rules' answers. [Phase.FRAME]: the arriving frame goes in as many flushes as it takes, each held by the out half's
 * owner ([held]); the flush that leaves nothing pending carries the in half's PLAY-AFTER (slot [IN_SLOT], `after` =
 * [OUT_SLOT]) after its drawing subs. [Phase.IN]: the in half runs on the glasses; a write that is not his input waits
 * for its end. It ends at RIGHT's in-half body (outcome 0: the phone CLOSEs the out half's slot, §7.11), at [deadlineMs]
 * (no body by the in half's nominal end plus one period: both slots CLOSEd), or is cut — a keyframe, a failed flush, his
 * input in [Phase.IN], a newer act, a session's end, the glasses asleep.
 */
internal class TransitionRun(
    val tier: Transitions.Tier,
    val built: Transitions.Built,
    /** The records' mode-19 message, the out half's PLAY and the in half's PLAY-AFTER, encoded at the act. */
    val write: ByteArray, val playOut: ByteArray, val playAfter: ByteArray,
    val tagOut: Int, val tagIn: Int,
    /** True when the out half is §7.11's freeze (the effect's out program was refused offline). */
    val frozen: Boolean,
    val actAtMs: Long,
) {
    enum class Phase { OWED, FRAME, IN, DONE }

    var phase = Phase.OWED
    var writeId: Long? = null
    var outId: Long? = null
    var inId: Long? = null
    var outSentAtMs = 0L
    var inSentAtMs = 0L
    /** When the flush carrying the PLAY-AFTER was acknowledged (0: not yet). */
    var inDoneAtMs = 0L

    val steps: Int get() = built.timing.steps
    private val periodMs: Double get() = built.timing.period * 1000.0 / 1024

    /** The slots whose owner holds a flush assembled now: the out half's, while the arriving frame goes in. */
    fun held(): Set<Int> = if (phase == Phase.FRAME) setOf(OUT_SLOT) else emptySet()

    /** The out half's nominal end: its last step, then the one period its pose shows for (phone clock). */
    fun outEndMs(): Long = outSentAtMs + Math.round(steps * periodMs)

    /** The in half's nominal end — it starts at the later of the PLAY-AFTER's ack and the out half's end — plus one
     *  period: past it with no in-half body, the phone CLOSEs both slots (§7.11). Null before the PLAY-AFTER's ack. */
    fun deadlineMs(): Long? = if (phase != Phase.IN || inDoneAtMs == 0L) null
        else maxOf(inDoneAtMs, outEndMs()) + Math.round((steps + 1) * periodMs)

    fun describe(): String = "${tier.word} ${built.describe()}${if (frozen) " (out half: the one-step freeze)" else ""}; " +
        "tags out 0x%04x in 0x%04x".format(tagOut, tagIn)

    companion object {
        /** K1 §9's slots: 0 the bound list, 1 the wrap's PLAY, 2 a transition's out half, 3 its in half. */
        const val OUT_SLOT = 2
        const val IN_SLOT = 3
        /** One tag per PLAY, cycling through 0x1000 apiece (§7.11: never reused while a PLAY-AFTER names the slot). */
        const val TAG_OUT = 0xC000
        const val TAG_IN = 0xD000
    }
}
