package wm.damage.core.geom

/**
 * Damage geometry and budget rules — the Kotlin twin of `tools/geometry.py`
 * (DESIGN.md §2.1, §8.2, §9.2b). Same rule IDs, same semantics: the Python file
 * gates the repo statically, this one gates the compositor at runtime on every
 * emit. `GeometryParityTest` pins both to the same fixture set so they cannot
 * drift apart silently.
 *
 * Every check exists because the hardware reports the failure as SILENCE: an
 * unaligned or out-of-bounds mode-3 box is rejected without a word and the
 * previous frame stays up (g2flash/patches/zlib_glue.c), a duplicate fid is
 * skipped, a stale delta composites onto the wrong base.
 */
object Geometry {
    // --- hardware constants, each traceable to a source --------------------------
    /** zlib_glue.c PANEL_W/PANEL_H — the full physical panel, all of it visible. */
    const val PANEL_W = 640
    const val PANEL_H = 480

    /** mode-3 box encodes [left/4][top/2][width/4][height/2] (zlib_glue.c). */
    const val X_STEP = 4
    const val Y_STEP = 2

    /** fid range: 0xFFFF is the CFW's empty-ring sentinel (zlib_glue.c). */
    const val FID_MIN = 1
    const val FID_MAX = 0xFFFE

    /** zlib_glue.c recent_fids[] depth. */
    const val CFW_FID_RING = 16

    /** e0-20 f1=0/f1=7 layout-frame wall — layout frames ONLY (overview.md §2). */
    const val MAX_LAYOUT_FRAME = 1000

    /** 4096 app-chunk cap with envelope headroom (faceclaw ConnectionOptions
     *  IMAGE_FRAGMENT_SIZE = 3800 — read as a protocol fact, not copied code). */
    const val MAX_IMAGE_FRAGMENT = 3800

    /** zlib_glue.c bmp_max: 118 + aligned-stride(641/2) * 480 = 153,718 B. */
    val MODE8_MAX = 118 + ((((PANEL_W + 1) shr 1) + 3) and (3.inv())) * PANEL_H

    /** DESIGN.md §8.2 — rects x window <= CFW_FID_RING; only mode-3 consumes a fid. */
    fun rectBudget(window: Int): Int {
        if (window < 1) throw LintError("pipeline window must be >= 1, got $window")
        return maxOf(1, CFW_FID_RING / window)
    }

    fun snapX(v: Int): Int = (v / X_STEP) * X_STEP
    fun snapY(v: Int): Int = (v / Y_STEP) * Y_STEP

    // --- GEO: a box the firmware would silently reject ----------------------------
    /** GEO001 alignment · GEO002 bounds · GEO003 degenerate. */
    fun checkRect(r: Rect, what: String = "rect"): List<String> {
        val out = ArrayList<String>(3)
        if (r.x % X_STEP != 0 || r.w % X_STEP != 0)
            out += "GEO001 $what $r: x and width must be multiples of $X_STEP " +
                "(x%4=${r.x % X_STEP}, w%4=${r.w % X_STEP}) — mode-3 encodes left/4 and width/4"
        if (r.y % Y_STEP != 0 || r.h % Y_STEP != 0)
            out += "GEO001 $what $r: y and height must be multiples of $Y_STEP " +
                "(y%2=${r.y % Y_STEP}, h%2=${r.h % Y_STEP}) — mode-3 encodes top/2 and height/2"
        if (r.w <= 0 || r.h <= 0)
            out += "GEO003 $what $r: zero or negative extent is rejected by the firmware"
        if (r.x < 0 || r.y < 0 || r.right > PANEL_W || r.bottom > PANEL_H)
            out += "GEO002 $what $r: outside the ${PANEL_W}x$PANEL_H panel " +
                "(right=${r.right}, bottom=${r.bottom}) — box is rejected in SILENCE, " +
                "leaving the previous frame up"
        return out
    }

    /** GEO004 size mismatch · GEO005 vertical disparity · GEO006 off-ladder.
     *  zlib_glue.c size-checks the pair: `if (src[3]!=src[7] || src[4]!=src[8]) reject`. */
    fun checkStereoPair(left: Rect, right: Rect): List<String> {
        val out = ArrayList<String>()
        out += checkRect(left, "stereo L")
        out += checkRect(right, "stereo R")
        if (left.w != right.w || left.h != right.h)
            out += "GEO004 stereo pair $left / $right: boxes must be the SAME SIZE; " +
                "only the position may differ"
        if (left.y != right.y)
            out += "GEO005 stereo pair $left / $right: vertical disparity is forbidden " +
                "(DESIGN.md §3.4) — horizontal offsets only"
        if (kotlin.math.abs(left.x - right.x) % X_STEP != 0)
            out += "GEO006 stereo pair $left / $right: disparity ${kotlin.math.abs(left.x - right.x)} px " +
                "is not a multiple of $X_STEP; the ladder is 0/4/8/12/16"
        return out
    }

    /** GEO007 overlap · GEO008 tiling — chrome cells must tile their bar. */
    fun checkCells(cells: Map<String, Rect>, span: Rect? = null): List<String> {
        val out = ArrayList<String>()
        for ((name, r) in cells) out += checkRect(r, name).map { "$it  [cell $name]" }
        val names = cells.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) {
            val a = names[i]; val b = names[j]
            if (cells.getValue(a).overlaps(cells.getValue(b)))
                out += "GEO007 cells $a ${cells[a]} and $b ${cells[b]} overlap"
        }
        if (span != null && cells.isNotEmpty()) {
            val covered = cells.values.sumOf { it.w }
            if (covered != span.w)
                out += "GEO008 cells cover $covered px of a ${span.w} px bar " +
                    "(${if (covered < span.w) "gap" else "overflow"} of ${kotlin.math.abs(span.w - covered)})"
        }
        return out
    }

    // --- BUD: budgets that fail silently on the wire ------------------------------
    /** BUD001 rect budget · BUD002 mode-8 size cap. */
    fun checkBatch(deltas: List<Rect>, window: Int = 3, payload: Int? = null): List<String> {
        val out = ArrayList<String>()
        val budget = rectBudget(window)
        if (deltas.size > budget)
            out += "BUD001 ${deltas.size} mode-3 rects with a $window-deep pipeline exceeds " +
                "the budget of $budget (rects x window <= $CFW_FID_RING); a retransmit " +
                "would age out of the duplicate ring and be RE-APPLIED, not skipped"
        deltas.forEachIndexed { i, r -> out += checkRect(r, "delta$i").map { "$it  [batch rect $i]" } }
        if (payload != null && payload > MODE8_MAX)
            out += "BUD002 mode-8 batch $payload B exceeds the firmware cap of $MODE8_MAX B"
        return out
    }

    /** BUD003 layout/CREATE wall · BUD004 image fragment size. */
    fun checkFrameSize(nbytes: Int, kind: FrameKind): List<String> = when {
        kind == FrameKind.LAYOUT && nbytes > MAX_LAYOUT_FRAME ->
            listOf("BUD003 layout/CREATE frame $nbytes B exceeds ~$MAX_LAYOUT_FRAME B; the " +
                "firmware ignores it with NO ack and NO error")
        kind == FrameKind.IMAGE && nbytes > MAX_IMAGE_FRAGMENT ->
            listOf("BUD004 image fragment $nbytes B exceeds $MAX_IMAGE_FRAGMENT B")
        else -> emptyList()
    }

    /** BUD005 — ink coverage is opacity, distraction and cost at once (DESIGN.md §4.2). */
    fun checkInk(lit: Int, total: Int, budget: Double, surface: String): List<String> {
        val frac = if (total > 0) lit.toDouble() / total else 0.0
        return if (frac > budget)
            listOf("BUD005 surface '$surface' lights ${"%.1f".format(frac * 100)}% of its pixels, " +
                "over its ${"%.0f".format(budget * 100)}% ink budget — on an additive panel that is " +
                "opacity and transmit cost as well as brightness")
        else emptyList()
    }

    enum class FrameKind { LAYOUT, IMAGE }
}

/** Raised loudly. Never caught-and-logged — that is the failure mode this project bans. */
class LintError(message: String) : RuntimeException(message)

/** An axis-aligned box in panel coordinates. Immutable. */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h

    fun overlaps(o: Rect): Boolean =
        !(right <= o.x || o.right <= x || bottom <= o.y || o.bottom <= y)

    fun contains(o: Rect): Boolean =
        o.x >= x && o.y >= y && o.right <= right && o.bottom <= bottom

    fun intersect(o: Rect): Rect? {
        val nx = maxOf(x, o.x); val ny = maxOf(y, o.y)
        val nr = minOf(right, o.right); val nb = minOf(bottom, o.bottom)
        return if (nr > nx && nb > ny) Rect(nx, ny, nr - nx, nb - ny) else null
    }

    fun union(o: Rect): Rect {
        val nx = minOf(x, o.x); val ny = minOf(y, o.y)
        return Rect(nx, ny, maxOf(right, o.right) - nx, maxOf(bottom, o.bottom) - ny)
    }

    fun translate(dx: Int, dy: Int): Rect = Rect(x + dx, y + dy, w, h)

    /** Grow to the damage grid: x/w to multiples of 4, y/h to multiples of 2,
     *  clamped to the panel. The aligned rect always covers the original. */
    fun alignOut(): Rect {
        val ax = (x / Geometry.X_STEP) * Geometry.X_STEP
        val ay = (y / Geometry.Y_STEP) * Geometry.Y_STEP
        var ar = right; var ab = bottom
        if (ar % Geometry.X_STEP != 0) ar += Geometry.X_STEP - ar % Geometry.X_STEP
        if (ab % Geometry.Y_STEP != 0) ab += Geometry.Y_STEP - ab % Geometry.Y_STEP
        return Rect(
            maxOf(0, ax), maxOf(0, ay),
            minOf(Geometry.PANEL_W, ar) - maxOf(0, ax),
            minOf(Geometry.PANEL_H, ab) - maxOf(0, ay),
        )
    }

    val area: Int get() = w * h

    override fun toString(): String = "($x,$y ${w}x$h)"
}
