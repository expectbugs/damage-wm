package wm.damage.core.windows.games.kit

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect

/**
 * The height ladder as a SLOT ALLOCATOR (`HOLDEM.md` §6, §9.2). Any table game
 * asks it for regions; it never mentions poker.
 *
 * 🔴 **Extra height buys INFORMATION DENSITY, never bigger cards** (verdict 9).
 * The card rung comes from [CardArt.Size.forHeight] and the bands are laid
 * around it: what grows with the panel is the seat band and the number of
 * bands, not the art.
 *
 * | size | content | bands |
 * |---|---|---|
 * | 288 | 608×224 | seat strip · board · one status line · your hole cards |
 * | 352 | 608×288 | + last action per seat, button and blind level |
 * | 416 | 608×352 | seats become a staggered arc, + your line |
 * | 480 | 608×416 | + street-by-street betting history, chip stacks drawn |
 *
 * Every rect it hands out is grid-legal by construction (x/w ×4, y/h ×2) and
 * `check()` proves it — an unaligned box is rejected by the firmware in
 * SILENCE, leaving the previous frame up.
 */
class TableLayout(
    val content: Rect,
    val panelHeight: Int,
    /** The status band's height — the caller sizes it from the MEASURED ink
     *  of the face it draws there (review §28 #9); null (the geometry tests)
     *  = the design's 22/24, and a value below those floors is raised to
     *  them. Even values only. */
    statusH: Int? = null,
    /** Your line's band (tier ≥ 2), the same way; null = the design's 24. */
    lineH: Int? = null,
) {

    val card: CardArt.Size = CardArt.Size.forHeight(panelHeight)

    /** What this rung shows. */
    val tier: Int = when {
        panelHeight <= 288 -> 0
        panelHeight <= 352 -> 1
        panelHeight <= 416 -> 2
        else -> 3
    }

    val showsLastAction: Boolean get() = tier >= 1
    val showsArc: Boolean get() = tier >= 2
    val showsObservedStats: Boolean get() = tier >= 2
    val showsYourLine: Boolean get() = tier >= 2
    val showsHistory: Boolean get() = tier >= 3
    val showsChipStacks: Boolean get() = tier >= 3

    /** The five opponent cells (6-max minus you). */
    val seats: Rect
    /** The community board — five card slots, centred. */
    val board: Rect
    /** Pot / to-call / your stack. */
    val status: Rect
    /** Your two cards. */
    val hole: Rect
    /** Your name + stack + position, or an empty rect below tier 2. */
    val yourLine: Rect
    /** Street-by-street betting history, or an empty rect below tier 3. */
    val history: Rect

    init {
        val pad = 4
        val gap = 4
        val statusH = maxOf(if (tier >= 2) 24 else 22, statusH ?: 0)
        val lineH = if (tier >= 2) maxOf(24, lineH ?: 0) else 0
        // 52, not 44: the history draws three lines at the tiny face, whose
        // MEASURED ink is 17 px, and 44 forced a 14 px pitch that ran the
        // descenders of each line into the tops of the next (review pass 3,
        // 2026-09-04 — the same class as the lens ladder). The seat strip pays
        // the 8 px and still clears its four rows.
        val histH = if (tier >= 3) HIST_H else 0
        val bands = 4 + (if (lineH > 0) 1 else 0) + (if (histH > 0) 1 else 0)
        val avail = content.h - 2 * pad
        val fixed = card.h + statusH + card.h + lineH + histH
        val gaps = (bands - 1) * gap
        var seatH = Geometry.snapY(avail - fixed - gaps)
        if (seatH < MIN_SEAT_H) {
            // a safe rect too short for the design: the seat strip floors and
            // the bottom bands are what give way — never a silent overlap
            seatH = MIN_SEAT_H
        }
        var y = content.y + pad
        seats = Rect(content.x, y, content.w, seatH); y += seatH + gap
        board = Rect(content.x, y, content.w, card.h); y += card.h + gap
        status = Rect(content.x, y, content.w, statusH); y += statusH + gap
        hole = Rect(content.x, y, content.w, card.h); y += card.h + gap
        yourLine = if (lineH > 0) Rect(content.x, y, content.w, lineH).also { y += lineH + gap }
        else Rect(content.x, y, content.w, 0)
        history = if (histH > 0) Rect(content.x, y, content.w, histH)
        else Rect(content.x, y, content.w, 0)
    }

    /** One opponent cell, [i] of [n]. Below the arc rung they tile a strip;
     *  from tier 2 they stagger vertically so the table reads as a table. */
    fun seatCell(i: Int, n: Int): Rect {
        require(n > 0) { "seat count must be positive" }
        val cw = Geometry.snapX(seats.w / n)
        val x = seats.x + i * cw
        if (!showsArc) return Rect(x, seats.y, cw, seats.h)
        // the arc: the middle seats sit higher, the outer ones lower — a
        // shallow vertical stagger, quantised to the 2 px grid
        val mid = (n - 1) / 2.0
        val rise = if (mid == 0.0) 0.0 else 1.0 - kotlin.math.abs(i - mid) / mid
        val lift = Geometry.snapY((rise * ARC_RISE).toInt())
        return Rect(x, seats.y + (ARC_RISE - lift), cw, seats.h - ARC_RISE)
    }

    /** Where the five community cards go inside [board]. */
    fun boardFan(n: Int = 5): HandFan.Layout =
        HandFan.layout(n, card, board.w, board.x + board.w / 2, if (card.w >= 64) 16 else 12)

    /** Where your two cards go inside [hole]. */
    fun holeFan(n: Int = 2): HandFan.Layout =
        HandFan.layout(n, card, hole.w, hole.x + hole.w / 2, if (card.w >= 64) 16 else 12)

    /** Every band, for the geometry gate. */
    fun bands(): Map<String, Rect> = buildMap {
        put("seats", seats); put("board", board); put("status", status); put("hole", hole)
        if (yourLine.h > 0) put("yourLine", yourLine)
        if (history.h > 0) put("history", history)
    }

    /** Grid legality + containment + no overlap. Raised LOUDLY: the hardware
     *  reports every one of these as silence. */
    fun check(): List<String> {
        val errs = ArrayList<String>()
        val cells = bands()
        for ((n, r) in cells) errs += Geometry.checkRect(r, n)
        for ((n, r) in cells) if (!content.contains(r)) errs += "band '$n' $r escapes the content area $content"
        errs += Geometry.checkCells(cells)
            .filter { it.startsWith("GEO007") }         // overlap only; bands do not tile a bar
        for (i in 0 until 5) errs += Geometry.checkRect(seatCell(i, 5), "seatCell$i")
        return errs
    }

    fun require() {
        val e = check()
        if (e.isNotEmpty()) throw LintError("TableLayout at h=$panelHeight: ${e.joinToString("; ")}")
    }

    companion object {
        const val MIN_SEAT_H = 32
        /** The 480 rung's history band: three lines at the measured 17 px ink
         *  plus a row of leading. */
        const val HIST_H = 52
        /** How far the arc lifts the middle seats (tier ≥ 2). */
        const val ARC_RISE = 12
    }
}
