package wm.damage.core.wire

/**
 * sid 0x01 — the app-launch service, used as the CONNECT PRELUDE on the CFW
 * path. Protocol fact read from the CFW reference (faceclaw
 * `BleProtocol.PRELUDE_F5872_PAYLOAD` / `FaceclawBleCommunicator.connectLoopOnce`;
 * own implementation here): after both arms are connected and notifications
 * are enabled, one request
 *
 *     {1: 2, 2: <msgId>, 4: {3: {2: {2: {1: 0, 2: 0}}}}}     sid 0x01, flag 0x20
 *
 * goes to the RIGHT arm; the glasses answer with a sid-0x01 frame (a response,
 * not an event) whose field 2 echoes the msgId, and only then do the FB lease
 * and the settings query follow. The reference captured these bytes from the
 * official app; most inner fields have no known meaning, so they are sent as
 * captured. The official app's 7-packet sid-0x80 sequence is NOT part of the
 * CFW path and is never sent (CLAUDE.md: stay off sid 0x80).
 *
 * ⚠ Whether the firmware REQUIRES the prelude before an EvenHub CREATE is
 * unverified (U): every working implementation sends a prelude of some kind.
 * The simulator models the strict reading (no prelude → the page never
 * becomes active), so a missing prelude is visible as a blank panel.
 */
object LaunchMsg {
    const val SID = 0x01
    const val FLAG_REQUEST = 0x20
    const val FLAG_RESPONSE = 0x00

    /** The reference's flags for glasses-initiated events on any sid. */
    private const val FLAG_EVENT = 0x01
    private const val FLAG_EVENT_ALT = 0x06

    /** Field-1 value of the prelude request (the reference's "app-launch type"). */
    const val CMD_PRELUDE = 2

    fun prelude(msgId: Int): ByteArray {
        require(msgId in 1..0xFF) { "prelude msgId $msgId must stay one byte" }
        val inner = Pb.l(3, Pb.l(2, Pb.l(2, Pb.cat(Pb.v(1, 0), Pb.v(2, 0)))))
        return Pb.cat(Pb.v(1, CMD_PRELUDE), Pb.v(2, msgId), Pb.l(4, inner))
    }

    /** True for a glasses-initiated event frame (never an ack). */
    fun isEvent(flag: Int): Boolean = flag == FLAG_EVENT || flag == FLAG_EVENT_ALT

    /** The msgId a sid-0x01 frame carries in field 2, or null. */
    fun msgIdOf(payload: ByteArray): Int? = try {
        Pb.varintField(payload, 2)?.toInt()
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The response the firmware model sends: field 1 echoes the request type,
     *  field 2 the msgId (the reference resolves the ack on (sid, msgId)). */
    fun response(type: Int, msgId: Int): ByteArray = Pb.cat(Pb.v(1, type), Pb.v(2, msgId))
}
