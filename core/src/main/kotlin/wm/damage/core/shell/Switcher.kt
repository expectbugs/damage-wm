package wm.damage.core.shell

import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * The switcher — ALT+TAB as a vertical drum seen head-on (DESIGN.md §1.3/§4.3).
 * Bespoke, not a generic overlay. Nothing commits on release: long-press opens,
 * scroll spins (with live preview settling in behind, lowest priority), tap
 * commits, long-press again (or double-tap) cancels and restores.
 *
 * List order: Main, current window, then inactive windows by recency — the
 * cursor STARTS on the most recent inactive, so long-press + tap = ALT+TAB.
 *
 * The drum: detents 60° apart, cos-scaled bands — at ±60° cos = 0.5 exactly, so
 * neighbours land at half height by geometry. The fade is QUANTISED to four
 * tiers (a smooth gradient shreds RLE, §4.3); a vertical drum foreshortens
 * vertically only, which keeps every scanline's run structure.
 */
class Switcher(
    private val text: TextRasterizer,
    /** Theme icons (2026-09-01); null/miss falls back to the drawn set. */
    private val icons: () -> wm.damage.core.gfx.IconSource? = { null },
) {

    /** One drum row: Main is a pseudo-entry (null window). */
    data class Entry(val window: DamageWindow?, val name: String, val icon: IconKind, val dirty: Boolean)

    var open = false
        private set
    private var entries: List<Entry> = emptyList()
    var cursor = 0
        private set

    /** Spin animation state: rendered position moves toward cursor in 4 ease-out
     *  frames per detent; a new notch RETARGETS, never queues (§6.3). */
    private var spinFrom = 0.0
    private var spinPos = 0.0
    private var spinFrame = 0
    val spinning: Boolean get() = spinPos != cursor.toDouble()

    /** The window that was current when the switcher opened (for cancel). */
    var origin: DamageWindow? = null
        private set

    private val fBig = FontSpec(Face.SYSTEM, 21, bold = true)
    private val fBody = FontSpec(Face.SYSTEM, 17)

    fun openWith(current: DamageWindow?, recency: List<DamageWindow>) {
        val list = ArrayList<Entry>()
        list.add(Entry(null, "Main", IconKind.SETTINGS, false))
        if (current != null) list.add(Entry(current, current.name, current.icon, current.dirty))
        for (w in recency) if (w !== current) list.add(Entry(w, w.name, w.icon, w.dirty))
        entries = list
        origin = current
        // cursor starts on the most recent INACTIVE window when one exists
        // (§1.3: long-press then tap = ALT+TAB). With no current window (opened
        // from MAIN) the most recent inactive sits at index 1, not 2 — review
        // round 1 caught the fixed offset landing on the SECOND-most-recent.
        cursor = when {
            current != null && list.size > 2 -> 2      // most recent inactive
            current == null && list.size > 1 -> 1      // from MAIN: most recent
            current != null -> 0                        // only Main to go to —
            else -> 0                                   // landing on yourself is
        }                                               // a dead commit
        spinPos = cursor.toDouble()
        spinFrom = spinPos
        spinFrame = 4
        open = true
    }

    fun close() {
        open = false
        entries = emptyList()
    }

    fun selected(): Entry? = entries.getOrNull(cursor)

    /** A scroll notch: move the cursor and retarget the spin. */
    fun scroll(delta: Int) {
        if (entries.isEmpty()) return
        cursor = (cursor + delta).mod(entries.size)
        spinFrom = spinPos
        spinFrame = 0
    }

    /** Advance one animation frame; true while more frames remain. */
    fun stepSpin(): Boolean {
        if (spinFrame >= 4) { spinPos = cursor.toDouble(); return false }
        spinFrame++
        val t = spinFrame / 4.0
        val ease = 1 - (1 - t) * (1 - t)          // ease-out, quantized by the 4 steps
        // retarget-aware: interpolate toward the CURRENT cursor along the short way
        var target = cursor.toDouble()
        val n = entries.size
        if (n > 0) {
            while (target - spinFrom > n / 2.0) target -= n
            while (spinFrom - target > n / 2.0) target += n
        }
        spinPos = spinFrom + (target - spinFrom) * ease
        if (spinFrame >= 4) spinPos = cursor.toDouble()
        return spinFrame < 4
    }

    /**
     * Paint the drum into its fixed panel rect (the one place motion is free of
     * the damage grid — the panel rect never moves, §4.3). Returns the panel.
     */
    fun paint(g: Gray8, l: Layout): Rect {
        val p = l.switcherPanel
        g.fillRect(p, Level.BG)                    // the wheel clears its hole
        if (entries.isEmpty()) return p

        val n = entries.size
        val frac = spinPos - kotlin.math.floor(spinPos)
        val centerIdx = kotlin.math.floor(spinPos).toInt().mod(n)

        // band geometry at rest: above 44 · centre 88 · below 44 (§4.3); while
        // spinning, the boundary between bands slides by frac of a band.
        val bandTop = p.y + (44 * (1 - frac)).toInt() / 2 * 2
        val bandBot = p.y + 132 - (44 * frac).toInt() / 2 * 2

        // quantised tiers: centre full, neighbours ~50 %, edges ~25 % (§4.3)
        val dimLv = Level.of(4)
        fun entryAt(k: Int) = entries[(centerIdx + k).mod(n)]

        // The wheel's frame (2026-08-31 — Adam: with rules only around the
        // centre "it doesn't really look like a wheel"): four horizontal
        // rules. The OUTER pair sits fixed at the panel edges, one tier
        // dimmer, framing the upper and lower slots — dimmer edges support
        // the curving-away read (§4.3). The INNER pair brackets the centre
        // and slides with the spin. Horizontal rules only: one run each, the
        // shell's cheap structure (§4.5's vertical-bar cost applies here too).
        g.fillRect(p.x, p.y, p.w, 2, Level.FAINT)
        g.fillRect(p.x, p.bottom - 2, p.w, 2, Level.FAINT)
        // rules between bands — they move with the spin, inside the fixed rect
        g.fillRect(p.x, bandTop, p.w, 2, Level.DIM)
        g.fillRect(p.x, bandBot, p.w, 2, Level.DIM)

        // above neighbour: half height, dim
        if (n > 1) paintSmall(g, p, entryAt(-1), p.y + 4, dimLv)
        // centre: full size, full brightness, plane 0. Names must stay inside
        // the fixed panel rect — pixels past it would sit outside the damage
        // rect (silent divergence); overflow gets the drawn continuation mark
        // (marquee is the §4.3 upgrade path).
        val ce = entryAt(if (frac > 0.5) 1 else 0)
        val iconY = bandTop + 4
        wm.damage.core.gfx.IconPaint.draw(g, icons(), wm.damage.core.gfx.IconNames.forKind(ce.icon),
            p.x + (p.w - 56) / 2, iconY, 56, ce.icon, Level.HEAD)
        val maxW = p.w - 32
        val name = fitText(ce.name, fBig, maxW)
        val tw = text.measure(name, fBig)
        val lx = p.x + (p.w - tw) / 2
        text.draw(g, lx / 4 * 4, (iconY + 60) / 2 * 2, name, fBig, Level.HOT)
        if (name != ce.name) Icons.tri(g, p.right - 14, iconY + 64, 11, Level.DIM)
        if (ce.dirty) g.fillRect((lx + tw + 8).coerceAtMost(p.right - 8), iconY + 66, 4, 10, Level.HOT)
        // below neighbour
        if (n > 2) paintSmall(g, p, entryAt(1), p.bottom - 24, dimLv)
        return p
    }

    private fun paintSmall(g: Gray8, p: Rect, e: Entry, y: Int, lv: Int) {
        // square 20 px for both sets — theme bitmaps are square, and the drawn
        // set reads fine at 20×20 (the old 40×20 stretch predated theme icons)
        wm.damage.core.gfx.IconPaint.draw(g, icons(), wm.damage.core.gfx.IconNames.forKind(e.icon),
            p.x + 52, y / 2 * 2, 20, e.icon, lv)
        val name = fitText(e.name, fBody, p.right - (p.x + 96) - 12)
        text.draw(g, (p.x + 96) / 4 * 4, y / 2 * 2, name, fBody, lv)
        if (e.dirty) g.fillRect((p.x + 96 + text.measure(name, fBody) + 6).coerceAtMost(p.right - 6),
            y + 4, 3, 8, lv)
    }

    private fun fitText(s: String, f: wm.damage.core.text.FontSpec, maxW: Int): String {
        if (text.measure(s, f) <= maxW) return s
        var n = s.length
        while (n > 0 && text.measure(s.take(n), f) > maxW) n--
        return s.take(n)
    }
}
