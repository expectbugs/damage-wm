package wm.damage.core.transport

/**
 * Stage BL (2026-11-25): when the phone's scan for the pair may stop waiting.
 *
 * A lens that the phone still holds a link to does not advertise, so a scan waits for it without end (his log,
 * 2026-11-25 15:02: LEFT found after 1.4 s, RIGHT never in 2 min 20 s, found 2.5 s after a Bluetooth toggle).
 * This answers whether to keep waiting or to connect — each lens from the scan, or by its remembered address when
 * the phone holds a link to it. No Android type here: the phone's glue feeds [PairWatch] and acts
 * (`BleTransport.scanForPair`).
 */
object PairFind {
    /** Where the device for a lens comes from. */
    enum class Via { SCAN, HELD }

    /** One lens as [PairWatch] holds it at one evaluation. */
    data class Lens(
        /** This scan has reported the lens. */
        val seen: Boolean,
        /** The asks in a row at which the phone held a link to the lens ([PairWatch.ask]). */
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
        x.heldAsks >= HELD_ASKS && !x.ours -> Via.HELD
        else -> null
    }
}

/**
 * Stage BLF: everything [PairFind.decide] reads. Two signals that the phone holds a link to a lens: its GATT list
 * (links an app has on record with the phone's Bluetooth service) and its link notices (up and down, whoever holds
 * the link — kept per address for the transport's life). A lens counts as held at an ask when either says so; a
 * lens with no notice in this record is unknown and counts as not held. Not thread-safe: the glue calls it under
 * one lock (the notices' receiver, the scan's callback and the asker).
 */
class PairWatch {
    /** The last link notice for an address: up or down, and when (the glue's wall clock). */
    data class Notice(val up: Boolean, val atMs: Long)

    private val notices = HashMap<String, Notice>()        // by address, upper case: the transport's life
    private val address = arrayOfNulls<String>(2)           // this scan's remembered addresses, by Arm.ordinal
    private val seen = BooleanArray(2)
    private val asks = IntArray(2)
    private val ours = BooleanArray(2)

    /** A link-up or link-down notice from the phone, for any device. A link-down ends that lens's count at once. */
    fun linkNotice(addr: String, up: Boolean, atMs: Long) {
        val a = addr.uppercase()
        notices[a] = Notice(up, atMs)
        if (!up) for (i in 0..1) if (address[i] == a) asks[i] = 0
    }

    /** Bluetooth went off: every link it held is gone, and so is every record of one. */
    fun bluetoothOff() = notices.clear()

    /** A scan starts with these remembered addresses (null: none — a first-time pair): nothing seen, nothing counted. */
    fun scanStarts(left: String?, right: String?) {
        address[0] = left?.uppercase()
        address[1] = right?.uppercase()
        seen.fill(false); asks.fill(0); ours.fill(false)
    }

    fun seen(arm: Arm) { seen[arm.ordinal] = true }

    /** One ask on the pacing tick for one lens: whether the phone's GATT list names it (null: the list could not be
     *  read — that decides nothing), and whether this app's own link to it is not ended. */
    fun ask(arm: Arm, listed: Boolean?, ours: Boolean) {
        val i = arm.ordinal
        this.ours[i] = ours
        val a = address[i]
        val held = a != null && (listed == true || notices[a]?.up == true)
        asks[i] = if (held) asks[i] + 1 else 0
    }

    /** The record for this scan's remembered lens (null: no notice in this transport's life). */
    fun notice(arm: Arm): Notice? = address[arm.ordinal]?.let { notices[it] }

    fun heldAsks(arm: Arm): Int = asks[arm.ordinal]

    fun decide(): PairFind.Decision = PairFind.decide(lens(0), lens(1))

    private fun lens(i: Int) = PairFind.Lens(seen[i], asks[i], ours[i])
}
