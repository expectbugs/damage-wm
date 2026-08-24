package wm.damage.core.geom

/**
 * FID001 reuse · FID002 gap/reversal · FID003 range · FID004 delta before keyframe.
 *
 * Mirrors zlib_glue.c cfw_diag(): only an EXACT hit in the last-16 ring is
 * SKIPPED; a stale fid that has aged out is flagged and then APPLIED, clobbering
 * newer pixels (DESIGN.md §8.2). So the rule is never to put a fid on the wire
 * twice, not to hope the ring saves you. On a missed ack, damage is recomputed
 * and sent with a FRESH fid — never retransmitted.
 *
 * The 16-bit wrap 0xFFFE -> 1 computes d = 3 in uint16 and trips f_skip once per
 * 65k rects (§8.2 #6); `expectWrap` whitelists exactly that step so the panic
 * path does not fire on an expected event.
 */
class FidTracker {
    var last: Int? = null
        private set
    var seeded: Boolean = false
        private set
    private val issued = HashSet<Int>()

    /** Mode-6 keyframe: seeds the shadow and rebaselines the delta sequence
     *  (cfw_diag(0,0) sets fid_resync). */
    fun keyframe(): List<String> {
        seeded = true
        last = null
        return emptyList()
    }

    /** Call after ANY mode-7 sub-0 clear: it resets the FIRMWARE's fid ring,
     *  flags AND lastFid, so the host resets its issued-set and its gap
     *  baseline with it — the next fid establishes a fresh sequence (review
     *  round 2 #1, round 3 D2). The shadow stays seeded; sub-0 does not
     *  touch pixels. */
    fun resync() {
        issued.clear()
        last = null
    }

    /** Validate one mode-3 delta fid about to go on the wire. */
    fun delta(fid: Int): List<String> {
        val out = ArrayList<String>(2)
        if (!seeded)
            out += "FID004 mode-3 delta with no prior mode-6 keyframe; the shadow is unseeded"
        if (fid < Geometry.FID_MIN || fid > Geometry.FID_MAX)
            out += "FID003 fid $fid outside [${Geometry.FID_MIN}, ${Geometry.FID_MAX}] " +
                "(0xFFFF is the empty-ring sentinel)"
        if (fid in issued)
            out += "FID001 fid $fid reused; the same id must never reach the wire twice"
        val l = last
        if (l != null) {
            val d = (fid - l) and 0xFFFF
            val wrap = l == Geometry.FID_MAX && fid == Geometry.FID_MIN
            if (d == 0 || d >= 0x8000)
                out += "FID002 fid went backward ($l -> $fid); sets f_reorder"
            else if (d > 1 && !wrap)
                out += "FID002 fid gap ($l -> $fid, +$d); sets f_skip. " +
                    "Allocate at EMIT time, never at plan time"
        }
        issued.add(fid)
        last = fid
        return out
    }
}

/**
 * The allocator the transport uses at EMIT time (DESIGN.md §8.2 #5: fids are
 * strictly +1 and allocated at emit, never at plan). Range [1, 0xFFFE]; the wrap
 * back to 1 is deliberate and must be paired with a mode-7 sub-0 flag clear or a
 * whitelisted f_skip (§8.2 #6) — the transport surfaces `wrapPending` for that.
 */
class FidAllocator(start: Int = Geometry.FID_MIN) {
    init {
        require(start in Geometry.FID_MIN..Geometry.FID_MAX) { "fid start $start out of range" }
    }

    private var next = start

    /** True after a wrap has been handed out and before the caller clears it. */
    var wrapPending: Boolean = false
        private set

    fun peek(): Int = next

    fun take(): Int {
        val fid = next
        if (next >= Geometry.FID_MAX) {
            next = Geometry.FID_MIN
            wrapPending = true
        } else next += 1
        return fid
    }

    fun clearWrap() {
        wrapPending = false
    }

    /** Restart at FID_MIN after an out-of-band mode-7 clear — used when a
     *  flush would otherwise SPAN the 0xFFFE wrap (a mode-7 clear cannot ride
     *  inside a mode-8 batch, so the whole flush re-encodes post-wrap; review
     *  round 3 D2). Pair with FidTracker.resync(). */
    fun restart() {
        next = Geometry.FID_MIN
        wrapPending = false
    }
}
