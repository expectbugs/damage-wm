package wm.damage.core.app

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1 (`CONTRACT.md` §2.4 "Numbering", §13 (b) "per-stream high-water"): per stream, the highest
 * `n` handled and its last answers. An op at or below the high-water is answered from that record and never applied
 * again; a gap is refused "expected 8" and the client resends in order; a new stream starts at 1. The high-water is
 * written in the op's log line, so it lands with the op or not at all. Owned by the core executor.
 */

/** A stream's identity: the device that sent it and the stream value (C§13 (b): `(device, stream)`). */
data class StreamKey(val device: String, val stream: String)

class Streams(private val keep: Int = ANSWERS_KEPT) {

    /** What to do with an op's number. */
    sealed class Check {
        /** The next number: judge it. */
        object Next : Check() {
            override fun toString() = "Next"
        }

        /** Handled before: [answer] is its answer when still held, else null ("already handled"). */
        data class Handled(val answer: Answer?) : Check()

        /** Fix round 1, ruling 2.1: handled before, and the answer held for it was another op's (two tabs of one
         *  browser counting apart): this op is never applied, and it is refused in words. */
        data class Reused(val n: Long) : Check()

        /** A number past the next: refused, the client resends from [expected]. */
        data class Gap(val expected: Long) : Check()
    }

    private val high = HashMap<StreamKey, Long>()
    /** A held answer and the identity of the op it answered ([identity]; null = not known). */
    private class Held(val n: Long, val answer: Answer, val op: Int?)

    private val answers = HashMap<StreamKey, ArrayDeque<Held>>()

    fun highWater(key: StreamKey): Long = high[key] ?: 0L

    /** [op] is the arriving op's [identity] (null = not known): a held answer of another op is [Check.Reused]. */
    fun check(key: StreamKey, n: Long, op: Int? = null): Check {
        val hw = highWater(key)
        if (n > hw + 1 || n < 1) return Check.Gap(hw + 1)
        if (n <= hw) {
            val h = answers[key]?.lastOrNull { it.n == n } ?: return Check.Handled(null)
            return if (op != null && h.op != null && h.op != op) Check.Reused(n) else Check.Handled(h.answer)
        }
        return Check.Next
    }

    /** [n] handled with [answer]: the high-water moves to it and the answer is kept with [op], the op's [identity]
     *  (the last [keep] per stream). */
    fun commit(key: StreamKey, n: Long, answer: Answer, op: Int? = null) {
        if (n > highWater(key)) high[key] = n
        val q = answers.getOrPut(key) { ArrayDeque() }
        q.addLast(Held(n, answer, op))
        while (q.size > keep) q.removeFirst()
    }

    /** The high-water table, for `record.json` and `welcome`. */
    fun table(): Map<StreamKey, Long> = HashMap(high)

    /** At a start: the table `record.json` held. Its answers are not kept across a restart; an op covered by the
     *  save is answered "already handled". */
    fun load(table: Map<StreamKey, Long>) {
        high.clear()
        answers.clear()
        high.putAll(table)
    }

    companion object {
        /** C§2.4: "its last 1,000 answers". */
        const val ANSWERS_KEPT = 1_000

        /** What makes two ops one (ruling 2.1): the window, the action, the items and the value. A true retry
         *  carries the same (a sent op is frozen, a page sends the same frame again). */
        fun identity(op: Op): Int = listOf(op.window, op.action, op.targets, op.result).hashCode()
    }
}
