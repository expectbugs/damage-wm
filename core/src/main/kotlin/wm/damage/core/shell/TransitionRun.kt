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
 * half's slot, §7.11), at [deadlineMs] (no in-half body by its nominal end plus an allowance: both slots CLOSEd), or is
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
    /** C3 TR1: when RIGHT's body of the out half (outcome 0: its last step drawn) reached the shell (0: not yet) — the in
     *  half starts one period after that step on RIGHT, so this is where the in half's nominal end is measured from. */
    var outBodyAtMs = 0L

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

    /** Past this with no in-half body, the phone CLOSEs both slots (§7.11): the one rule that ends a waiter whose body
     *  never comes. C3 TR1 (his first stage-5 session, 2026-11-21: RIGHT ran the out half about 150–200 ms after its
     *  PLAY's ack, so the ack-based end fell before 119 of 139 in-half bodies): with RIGHT's out-half body in hand, the in
     *  half's nominal end is that body plus T periods (it starts one period after the out half's last step on RIGHT),
     *  never before the PLAY-AFTER's ack plus T periods, plus [BODY_ALLOWANCE_MS]. Until that body comes, today's rule:
     *  the later of the PLAY-AFTER's ack and the out half's end from its PLAY's ack, plus T + 1 periods. Null until
     *  both acks are in (the out-body rule needs the PLAY-AFTER's). */
    fun deadlineMs(): Long? = when {
        phase != Phase.IN || inDoneAtMs == 0L -> null
        outBodyAtMs != 0L -> maxOf(outBodyAtMs, inDoneAtMs) + Math.round(steps * periodMs) + BODY_ALLOWANCE_MS
        outDoneAtMs == 0L -> null
        else -> maxOf(inDoneAtMs, outEndMs()) + Math.round((steps + 1) * periodMs)
    }

    fun describe(): String = "${tier.word} ${built.describe()}; tags out 0x%04x in 0x%04x".format(tagOut, tagIn)

    companion object {
        /** K1 §9's slots: 0 the bound list, 1 the wrap's PLAY, 2 a transition's out half, 3 its in half. */
        const val OUT_SLOT = 2
        const val IN_SLOT = 3
        private val HELD = setOf(OUT_SLOT, IN_SLOT)
        /** One tag per PLAY, cycling through 0x1000 apiece (§7.11: never reused while a PLAY-AFTER names the slot). */
        const val TAG_OUT = 0xC000
        const val TAG_IN = 0xD000
        /** C3 TR1: what the backstop allows past the in half's nominal end measured from RIGHT's out-half body — RIGHT may
         *  run the PLAY-AFTER late behind the arriving frame's batches, and the body has its way back. His first stage-5
         *  session (measured, 132 in halves drawn whole): an in-half body at most 240 ms past that end (a Warp whose
         *  PLAY-AFTER RIGHT ran late), median 19 ms before it; 500 ms is about twice the worst. Liveness only: it costs
         *  nothing unless RIGHT's in-half body is lost. */
        const val BODY_ALLOWANCE_MS = 500L
    }
}
