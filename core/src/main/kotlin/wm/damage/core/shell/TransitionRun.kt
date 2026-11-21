package wm.damage.core.shell

import wm.damage.core.motion.Transitions

/**
 * Build C3, stage DP2: one full-screen transition as the shell runs it (`FIRMWARE.md` §7.11; `MOTION.md` §7). Context
 * for the reader: Adam's own glasses on his own firmware fork; display-rendering work. Loop-only state.
 *
 * [Phase.OWED]: the act happened (a Select, a Back, the silent clock entered or left) and the screen was painted; the
 * next pump sends the records' write and then, alone, the rules' CLEARs, the out half's PLAY (slot [OUT_SLOT]) and the
 * rules' answers. [Phase.FRAME]: the arriving frame goes in as many flushes as it takes; the flush that leaves nothing
 * pending carries the in half's PLAY-AFTER (slot [IN_SLOT], `after` = [OUT_SLOT]) after its drawing subs. [Phase.IN]:
 * the in half runs on the glasses. From the out PLAY to the run's end every flush is held by both owners ([held]): a
 * write under the in half — his input's frame, a chrome tick, the window's own repaint — goes at once and shows at the
 * in half's next step (it draws `new` from the live shadow) or at LAND, so the transition plays in full (the first
 * review pass of the transitions, 2026-11-20). It ends at RIGHT's in-half body (outcome 0: the phone CLOSEs the out
 * half's slot, §7.11), at [deadlineMs] (no body by the in half's nominal end plus one period: both slots CLOSEd), or is
 * cut — a keyframe, a failed flush, a newer act (its out PLAY's close pass ends what is left), a session's end, the
 * glasses asleep.
 */
internal class TransitionRun(
    val tier: Transitions.Tier,
    val built: Transitions.Built,
    /** The records' mode-19 message, the out half's PLAY and the in half's PLAY-AFTER, encoded at the act. */
    val write: ByteArray, val playOut: ByteArray, val playAfter: ByteArray,
    val tagOut: Int, val tagIn: Int,
    val actAtMs: Long,
    /** C3 DM-T, Encode: the in half's cell lists' mode-19 message — written after the out PLAY, above the out lists; null
     *  when they ride [write] (C3 X2T item 2: the records and both halves' lists fit one compressed message). */
    val listWrite: ByteArray? = null,
) {
    enum class Phase { OWED, FRAME, IN }

    var phase = Phase.OWED
    var writeId: Long? = null
    var outId: Long? = null
    /** The in half's lists' write (Encode). */
    var listId: Long? = null
    var inId: Long? = null
    var outSentAtMs = 0L
    var inSentAtMs = 0L
    /** When the flush carrying the out PLAY was acknowledged (0: not yet) — the base of the out half's nominal end: the
     *  ack follows the PLAY's arrival on the glasses, so a link that held the PLAY back cannot move the end earlier. */
    var outDoneAtMs = 0L
    /** When the flush carrying the PLAY-AFTER was acknowledged (0: not yet). */
    var inDoneAtMs = 0L

    val steps: Int get() = built.timing.steps
    private val periodMs: Double get() = built.timing.period * 1000.0 / 1024

    /** C3 DM-T, the DM spec's D6: an Encode or Matrix run reads the atlas's fonts (and Encode its lists in the stage
     *  reserve) from the act to its end, so while it is in hand no other cache write goes ([Shell]'s five writers read it;
     *  C3 X2T item 1: and one in-half period past RIGHT's in-half body). */
    val holdsCache: Boolean get() = built.effect.glyphs

    /** The slots whose owners hold a flush assembled now: both halves', from the out PLAY to the run's end (§7.11). */
    fun held(): Set<Int> = if (phase == Phase.FRAME || phase == Phase.IN) HELD else emptySet()

    /** The out half's nominal end: its last step, then the one period its pose shows for (phone clock, from the out
     *  PLAY's ack). */
    fun outEndMs(): Long = outDoneAtMs + Math.round(steps * periodMs)

    /** The in half's nominal end — it starts at the later of the PLAY-AFTER's ack and the out half's end — plus one
     *  period: past it with no in-half body, the phone CLOSEs both slots (§7.11). Null until both acks are in. */
    fun deadlineMs(): Long? = if (phase != Phase.IN || inDoneAtMs == 0L || outDoneAtMs == 0L) null
        else maxOf(inDoneAtMs, outEndMs()) + Math.round((steps + 1) * periodMs)

    fun describe(): String = "${tier.word} ${built.describe()}; tags out 0x%04x in 0x%04x".format(tagOut, tagIn)

    companion object {
        /** K1 §9's slots: 0 the bound list, 1 the wrap's PLAY, 2 a transition's out half, 3 its in half. */
        const val OUT_SLOT = 2
        const val IN_SLOT = 3
        private val HELD = setOf(OUT_SLOT, IN_SLOT)
        /** One tag per PLAY, cycling through 0x1000 apiece (§7.11: never reused while a PLAY-AFTER names the slot). */
        const val TAG_OUT = 0xC000
        const val TAG_IN = 0xD000
    }
}
