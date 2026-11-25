package wm.damage.core.transport

/**
 * Stage BL (2026-11-25): when the phone's scan for the pair may stop waiting.
 *
 * A lens that the phone still holds a link to does not advertise, so a scan waits for it without end (his log,
 * 2026-11-25 15:02: LEFT found after 1.4 s, RIGHT never in 2 min 20 s, found 2.5 s after a Bluetooth toggle).
 * This answers, from what is remembered, what the scan has seen and what Android holds, whether to keep waiting
 * or to connect — each lens from the scan, or by its remembered address when Android holds it. No Android type
 * here: the phone's glue gathers the inputs and acts (`BleTransport.scanForPair`).
 */
object PairFind {
    /** Where the device for a lens comes from. */
    enum class Via { SCAN, HELD }

    /** One lens as the glue sees it at one evaluation. */
    data class Lens(
        /** An address is remembered for this lens (the last connect that completed wrote it). */
        val remembered: Boolean,
        /** This scan has reported the lens. */
        val seen: Boolean,
        /** The asks in a row, on the pacing tick, at which Android listed the remembered address as connected;
         *  0 after an ask that said not, or that could not be made. */
        val heldAsks: Int,
        /** One of this app's own links to the lens is not ended (connected, connecting or disconnecting). */
        val ours: Boolean,
    )

    sealed class Decision {
        object Wait : Decision() { override fun toString() = "wait" }
        data class Connect(val left: Via, val right: Via) : Decision()
    }

    /** Asks in a row before a held lens is taken: one ask can catch a link in the moment it ends, and the lens
     *  then advertises again within the scan's measured 0.2–1.4 s, inside one pacing tick. */
    const val HELD_ASKS = 2

    fun decide(left: Lens, right: Lens): Decision {
        val l = via(left) ?: return Decision.Wait
        val r = via(right) ?: return Decision.Wait
        return Decision.Connect(l, r)
    }

    private fun via(x: Lens): Via? = when {
        x.seen -> Via.SCAN
        x.remembered && x.heldAsks >= HELD_ASKS && !x.ours -> Via.HELD
        else -> null
    }
}
