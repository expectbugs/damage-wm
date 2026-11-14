package wm.damage.core.app

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1 (`CONTRACT.md` §9.3): the record's recent changes, in memory only, for a reconnect that
 * names its cursor. Same epoch and a `seq` inside the ring → the changes since, in order; otherwise null and the
 * caller sends a fresh snapshot, never a silent gap. After a restart the ring is empty and its floor is the
 * record's `seq`.
 */

/** Who an op came from, carried on its change (C§9.2 `chg.origin`). */
data class Origin(val device: String, val id: OpId)

/** One published change: the record's [seq] in [epoch], the op it came from, and the entries it wrote. A change
 *  with an empty effect is an op that changed nothing (a same-value op, a refusal for good): it still takes its
 *  `seq`, so a client never sees a hole in the order. */
data class Change(val epoch: Long, val seq: Long, val origin: Origin?, val effect: Effect)

class ChangeRing(private val cap: Int = CAP) {
    private val lock = Any()
    private val ring = ArrayDeque<Change>()
    private var floorSeq = 0L
    private var lastSeq = 0L

    /** At a start or a new epoch: nothing held, every cursor below [seq] gets a snapshot. */
    fun reset(seq: Long) = synchronized(lock) {
        ring.clear()
        floorSeq = seq
        lastSeq = seq
    }

    fun add(c: Change) = synchronized(lock) {
        require(c.seq == lastSeq + 1) { "change ${c.seq} after ${lastSeq}: the ring holds the order without holes" }
        ring.addLast(c)
        lastSeq = c.seq
        while (ring.size > cap) floorSeq = ring.removeFirst().seq
    }

    /** The changes after [seq], in order, or null when the ring cannot answer for it (below its floor, or past the
     *  last change it holds). */
    fun since(seq: Long): List<Change>? = synchronized(lock) {
        if (seq < floorSeq || seq > lastSeq) null else ring.filter { it.seq > seq }
    }

    val floor: Long get() = synchronized(lock) { floorSeq }
    val last: Long get() = synchronized(lock) { lastSeq }

    companion object {
        const val CAP = 1_000
    }
}
