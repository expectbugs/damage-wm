package wm.damage.core.transport

/**
 * The link's two numbers — the ack FLOOR and the TRANSFER term per KB — from the acks of a
 * session's own flushes (`HANDOFF.md` §73; it replaces the two EMAs of §32).
 *
 * What the EMAs got wrong, read from the phone's journal of 2026-11-09 (`HANDOFF.md` §73):
 *  - a small flush that waited behind a big one booked the big one's time as the floor (a
 *    33 B flush acked in 324 ms moved it 117 → 179), and during a run of atlas chunks no
 *    small flush landed at all, so the floor stood wherever the last one left it — and
 *    every transfer sample subtracts the floor (25 chunks read 10 ms/KB where isolated
 *    flushes said 29);
 *  - a sample whose ack was not above that floor was fed in as 0;
 *  - one sample moved the estimate by 30 % (one 116 ms/KB reading flipped the regime);
 *  - nothing cleared at a session start, so a regime stood for hours on a reading taken
 *    before the link came back.
 *
 * So: only an ISOLATED flush is an observation (nothing un-acked on the image lane when the
 * lane took it — its ack then times that flush and nothing else); each term is the MEDIAN of
 * its last [ring] observations; a transfer sample is priced against the floor in force when
 * it is taken, and one whose ack is not above it is skipped; everything clears at a session
 * start. Until a term has [MIN_SAMPLES] observations it reads its default — the floor 60 ms
 * measured on both radio paths (§31.6), the transfer the fast 20 ms/KB — so nothing adapts
 * before the link has been measured. A big flush timed BEFORE the floor is measured is held
 * unpriced and priced when the floor's third observation arrives: a session's first big
 * flushes are its atlas upload, ahead of any small one, and priced against the 60 ms default
 * on a link whose floor is 130 they read 87 ms/KB where the link gives 64 — and then stood
 * in the ring for as long as no big flush came (hours, on 2026-11-09).
 *
 * A cache write is never a FLOOR observation ([sample]'s `floor = false`): whether its ack
 * floor equals a display flush's is not measured (one 85 B write acked in 62 ms while display
 * flushes around it read 86–147), and a face gaining a glyph is now a small cache write.
 *
 * The size bands are §32's: under [FLOOR_BELOW_BYTES] a flush's transfer time is nil, from
 * [TRANSFER_FROM_BYTES] up it dominates; a flush between the two feeds neither term.
 *
 * Pure and synchronized: the transport's completion coroutines feed it in ack order, the
 * session start resets it from another thread.
 */
class LinkEstimator(private val ring: Int = RING) {
    init { require(ring >= MIN_SAMPLES) { "a ring of $ring cannot hold $MIN_SAMPLES samples" } }

    private val floors = ArrayDeque<Double>(ring)
    private val transfers = ArrayDeque<Double>(ring)
    /** Big flushes timed before the floor was measured, as (bytes, ack ms): priced when it is. */
    private val unpriced = ArrayDeque<Pair<Int, Long>>(ring)

    /** The floor's observations so far this session, at most [ring]. */
    val floorSamples: Int @Synchronized get() = floors.size
    /** The transfer term's observations so far this session, at most [ring]. */
    val transferSamples: Int @Synchronized get() = transfers.size

    /** The ack floor in ms: the median of the last isolated small flushes' acks. */
    val floorMs: Double @Synchronized get() = if (floors.size < MIN_SAMPLES) DEFAULT_FLOOR_MS else median(floors)

    /** The transfer term in ms per KB: the median of the last isolated big flushes' (ack − floor) / KB. */
    val transferMsPerKb: Double @Synchronized get() =
        if (transfers.size < MIN_SAMPLES) DEFAULT_TRANSFER_MS_PER_KB else median(transfers)

    /** A new session: the link that answers now is not the one the old readings timed. */
    @Synchronized fun reset() {
        floors.clear()
        transfers.clear()
        unpriced.clear()
    }

    /**
     * One acked flush of [bytes] wire bytes whose ack came [ackMs] after the lane took it.
     * [isolated] = nothing was un-acked on the image lane at that moment; [floor] = false for a
     * flush that may price the transfer term but not the floor (a cache write). True when the
     * flush became an observation of either term (or is held to become one).
     */
    @Synchronized fun sample(bytes: Int, ackMs: Long, isolated: Boolean, floor: Boolean = true): Boolean {
        if (!isolated || bytes <= 0 || ackMs < 0) return false
        if (bytes < FLOOR_BELOW_BYTES) {
            if (!floor) return false
            push(floors, ackMs.toDouble())
            if (floors.size == MIN_SAMPLES && unpriced.isNotEmpty()) {
                // the floor is measured as of this observation: the big flushes that came first are priced now
                val f = median(floors)
                for ((b, a) in unpriced) if (a > f) push(transfers, (a - f) / (b / 1000.0))
                unpriced.clear()
            }
            return true
        }
        if (bytes >= TRANSFER_FROM_BYTES) {
            if (floors.size < MIN_SAMPLES) {
                if (unpriced.size >= ring) unpriced.removeFirst()
                unpriced.addLast(bytes to ackMs)
                return true
            }
            val f = median(floors)
            // an ack at or under the floor says the floor is not this flush's: no reading, never a zero
            if (ackMs <= f) return false
            push(transfers, (ackMs - f) / (bytes / 1000.0))
            return true
        }
        return false
    }

    private fun push(q: ArrayDeque<Double>, v: Double) {
        if (q.size >= ring) q.removeFirst()
        q.addLast(v)
    }

    private fun median(q: ArrayDeque<Double>): Double {
        val s = q.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    companion object {
        /** Observations each term keeps. On the journal of 2026-11-09 every ring from 5 to 21
         *  replays with one flip into the slow period and one out of it and none elsewhere; nine
         *  keeps the fast period's estimate inside 25–36 ms/KB where five lets it reach 40
         *  (`HANDOFF.md` §73). */
        const val RING = 9
        /** A term reads its default until it has this many observations. */
        const val MIN_SAMPLES = 3
        const val DEFAULT_FLOOR_MS = 60.0
        const val DEFAULT_TRANSFER_MS_PER_KB = 20.0
        /** Under this a flush's ack is the floor (§32). */
        const val FLOOR_BELOW_BYTES = 400
        /** From this up a flush's ack prices the transfer term (§32). */
        const val TRANSFER_FROM_BYTES = 1000
    }
}
