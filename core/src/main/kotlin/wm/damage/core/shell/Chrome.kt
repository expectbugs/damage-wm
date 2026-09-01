package wm.damage.core.shell

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.Level
import wm.damage.core.text.DrawnStrings
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * The permanent chrome — top bar, both dividers, status bar (DESIGN.md §4.1,
 * §4.4, §4.6). Layout comes from [Layout] (safe-rect relative); look ported
 * from design/render_shots.py chrome(), the measured reference.
 *
 * Chrome never justifies its own flush (§8.3): [sync] paints only the cells
 * whose CONTENT changed and returns their rects; the shell lets them ride the
 * next content flush, or flushes them on the 5 s idle tick.
 */
class Chrome(private val text: TextRasterizer) {

    data class Battery(val pct: Int, val flashPhase: Int? = null)

    data class State(
        val windowName: String = "MAIN",
        val windowIcon: IconKind = IconKind.SETTINGS,
        val context: String = "",
        val clock: String = "--:--",
        val clockAmPm: String = "",
        val glasses: Battery? = null,
        val phone: Battery? = null,
        val windowCount: Int = 1,
        val windowAt: Int = 0,
        val dirtyAt: Set<Int> = emptySet(),
        val stackDepth: Int = 1,
        val op: String = "idle",
        val status: String = "ok",
        val inputEcho: String = "",
        val thru: String = "",
        val compass: String? = null,
        val linkBars: Int = 0,           // 0..4
        val linkDbm: Int? = null,
        val hostState: String = "",      // "" healthy-quiet · "PC 4m" when gone (§4.4)
    )

    // System face, x-height-normalised scale applied by the rasterizer host.
    private val fChrome = FontSpec(Face.SYSTEM, 16)
    private val fChromeB = FontSpec(Face.SYSTEM, 16, bold = true)
    private val fTiny = FontSpec(Face.SYSTEM, 12, bold = true)
    private val fTel = FontSpec(Face.SYSTEM, 13)
    private val fBattL = FontSpec(Face.SYSTEM, 15, bold = true)

    private var painted: State? = null
    private var paintedLayout: Layout? = null

    /** Paint every chrome cell whose content changed; return the damage rects. */
    fun sync(g: Gray8, l: Layout, s: State): List<Rect> {
        val out = ArrayList<Rect>()
        val p = if (paintedLayout == l) painted else null
        paintedLayout = l

        if (p == null || p.windowName != s.windowName || p.windowIcon != s.windowIcon ||
            p.context != s.context
        ) {
            paintTitle(g, l, s); out.add(l.titleCell)
        }
        if (p == null || p.glasses != s.glasses || p.phone != s.phone) {
            paintBatteries(g, l, s); out.add(l.batteryCell)
        }
        if (p == null || p.clock != s.clock || p.clockAmPm != s.clockAmPm) {
            paintClock(g, l, s); out.add(l.clockCell)
        }
        if (p == null || p.windowCount != s.windowCount || p.windowAt != s.windowAt ||
            p.dirtyAt != s.dirtyAt
        ) {
            paintTopDivider(g, l, s); out.add(l.topDivider)
        }
        if (p == null || p.stackDepth != s.stackDepth) {
            paintBottomDivider(g, l, s); out.add(l.bottomDivider)
        }
        if (p == null || p.op != s.op) { paintOp(g, l, s); out.add(l.opCell) }
        if (p == null || p.status != s.status || p.inputEcho != s.inputEcho) {
            paintStatus(g, l, s); out.add(l.statusCell)
        }
        if (p == null || p.thru != s.thru) { paintThru(g, l, s); out.add(l.thruCell) }
        if (p == null || p.compass != s.compass) { paintTape(g, l, s); out.add(l.tapeCell) }
        if (p == null || p.linkBars != s.linkBars || p.linkDbm != s.linkDbm ||
            p.hostState != s.hostState
        ) {
            paintLink(g, l, s); out.add(l.linkCell)
        }
        painted = s
        return out
    }

    fun invalidate() {
        painted = null
    }

    private fun draw(g: Gray8, x: Int, y: Int, str: String, lv: Int, f: FontSpec) {
        DrawnStrings.check(str, text, f)
        text.draw(g, Geometry.snapX(x), Geometry.snapY(y), str, f, lv)
    }

    /** DYNAMIC strings — exception text, host banners, window titles, book
     *  metadata — arrive from outside the locked glyph set. They are drawn
     *  with every uncoverable character replaced by '?' rather than refused:
     *  the SYM001 check throwing INSIDE chrome.sync would abort the pump
     *  before any flush, and the error status that reports it is itself
     *  chrome — a display that cannot paint its own error (round 4). The
     *  substitution is visible on the glass and logged once per string. */
    private fun dynamic(str: String, f: FontSpec): String {
        if (str.isEmpty() || text.covers(str, f)) return str
        val bad = LinkedHashSet<String>()
        val fixed = buildString(str.length) {
            var i = 0
            while (i < str.length) {
                // whole code points: a surrogate pair is one glyph, never two
                val cp = str.codePointAt(i)
                val s = String(Character.toChars(cp))
                if (text.covers(s, f)) append(s) else { append('?'); bad.add(s) }
                i += Character.charCount(cp)
            }
        }
        // dedupe on the OFFENDING characters, not the formatted string — a
        // book title with one such glyph would otherwise log (and grow the
        // set) on every page turn (round 5)
        if (warned.add(bad.joinToString(""))) {
            wm.damage.core.util.Log.w("chrome", "glyphs the ${f.face} face cannot draw: $bad — " +
                "shown as '?' (first seen in '$str')")
        }
        return fixed
    }

    private val warned = HashSet<String>()

    /** Fit a dynamic string into [maxW] px starting at ([x],[y]); a cut gets
     *  the drawn continuation mark at [triX] (§2.4 rule 3 — never a silent
     *  clip at the panel edge). */
    private fun drawFit(g: Gray8, x: Int, y: Int, maxW: Int, str: String, lv: Int, f: FontSpec, triX: Int) {
        val s = dynamic(str, f)
        if (text.measure(s, f) <= maxW) {
            draw(g, x, y, s, lv, f)
            return
        }
        var n = s.length
        while (n > 0 && text.measure(s.take(n), f) > maxW) n--
        draw(g, x, y, s.take(n), lv, f)
        Icons.tri(g, triX, y + 5, 9, Level.REST)
    }

    private fun drawRight(g: Gray8, xRight: Int, y: Int, str: String, lv: Int, f: FontSpec) {
        val w = text.measure(str, f)
        draw(g, xRight - w, y, str, lv, f)
    }

    // --- top bar -----------------------------------------------------------------
    private fun paintTitle(g: Gray8, l: Layout, s: State) {
        g.fillRect(l.titleCell, Level.BG)
        Icons.draw(g, l.titleCell.x + 8, l.titleCell.y + 6, 20, 20, s.windowIcon, Level.HEAD)
        val nx = l.titleCell.x + 36
        val name = dynamic(s.windowName, fChromeB)
        draw(g, nx, l.titleCell.y + 6, name, Level.HEAD, fChromeB)
        val nameW = text.measure(name, fChromeB)
        if (s.context.isNotEmpty()) {
            val ctx = "· ${dynamic(s.context, fChrome)}"
            val cx = nx + nameW + 8
            val fit = ctx.length.downTo(1).firstOrNull { n ->
                text.measure(ctx.take(n), fChrome) <= l.titleCell.right - 16 - cx
            } ?: 0
            if (fit < ctx.length) {
                // NO TRUNCATION as silence: persistent+unfocused overflow gets the
                // drawn continuation mark (§2.4 rule 3); full text lives in Main's lens.
                draw(g, cx, l.titleCell.y + 6, ctx.take(maxOf(0, fit)), Level.DIM, fChrome)
                Icons.tri(g, l.titleCell.right - 12, l.titleCell.y + 12, 9, Level.DIM)
            } else {
                draw(g, cx, l.titleCell.y + 6, ctx, Level.DIM, fChrome)
            }
        }
    }

    private fun paintBatteries(g: Gray8, l: Layout, s: State) {
        g.fillRect(l.batteryCell, Level.BG)
        // G (glasses, from the wire) and P (phone). No R: ring battery has no
        // open-source source (CLAIMS.md) — a blank cell for a value that can
        // never arrive is dead chrome, removed 2026-08-31.
        val devs = listOf("G" to s.glasses, "P" to s.phone)
        for ((i, dev) in devs.withIndex()) {
            val (tag, b) = dev
            val bx = l.batteryCell.x + 4 + i * 58
            if (b == null) {
                draw(g, bx, l.batteryCell.y + 6, tag, Level.REST, fBattL)
                continue
            }
            val lv = Icons.batteryLevel(b.pct, if (b.pct <= 20) b.flashPhase else null)
            draw(g, bx, l.batteryCell.y + 6, tag, lv, fBattL)
            Icons.batteryBar(g, bx + 14, l.batteryCell.y + 10, 30, 14, b.pct, lv)
        }
    }

    private fun paintClock(g: Gray8, l: Layout, s: State) {
        g.fillRect(l.clockCell, Level.BG)
        draw(g, l.clockCell.x + 4, l.clockCell.y + 6, s.clock, Level.HEAD, fChromeB)
        if (s.clockAmPm.isNotEmpty())
            draw(g, l.clockCell.x + 52, l.clockCell.y + 8, s.clockAmPm, Level.DIM, fTiny)
    }

    /** The divider carries window position + attention ticks — the retired
     *  ribbon's whole surviving job, at zero extra bytes (§4.1). */
    private fun paintTopDivider(g: Gray8, l: Layout, s: State) {
        g.fillRect(l.topDivider, Level.FAINT)
        val n = maxOf(1, s.windowCount)
        val slot = l.topDivider.w.toDouble() / n
        for (k in s.dirtyAt) if (k in 0 until n) {
            g.fillRect(l.topDivider.x + (k * slot).toInt() + 4, l.topDivider.y, 12, l.topDivider.h, Level.MID)
        }
        g.fillRect(
            l.topDivider.x + (s.windowAt * slot).toInt(), l.topDivider.y,
            maxOf(2, slot.toInt() - 2), l.topDivider.h, Level.HEAD,
        )
    }

    /** N bright segments = N levels deep on the back stack (§4.6). */
    private fun paintBottomDivider(g: Gray8, l: Layout, s: State) {
        g.fillRect(l.bottomDivider, Level.FAINT)
        for (i in 0 until s.stackDepth.coerceAtMost(16)) {
            g.fillRect(l.bottomDivider.x + 16 + i * 26, l.bottomDivider.y, 18, l.bottomDivider.h, Level.HEAD)
        }
    }

    // --- status bar ---------------------------------------------------------------
    private fun paintOp(g: Gray8, l: Layout, s: State) {
        g.fillRect(l.opCell, Level.BG)
        drawCellFit(g, l.opCell, s.op, Level.BODY, fChrome)
    }

    private fun paintStatus(g: Gray8, l: Layout, s: State) {
        g.fillRect(l.statusCell, Level.BG)
        val txt = if (s.inputEcho.isEmpty()) s.status else "${s.status} · ${s.inputEcho}"
        drawCellFit(g, l.statusCell, txt, Level.DIM, fChrome)
    }

    /** Status strings carry exception text of arbitrary length; drawing raw
     *  would overrun the cell OUTSIDE its damage rect (silent divergence).
     *  Fit + the drawn continuation mark — the journal holds the full text. */
    private fun drawCellFit(g: Gray8, cell: Rect, txt: String, lv: Int, f: FontSpec) {
        drawFit(g, cell.x + 8, cell.y + 6, cell.w - 16 - 12, txt, lv, f, cell.right - 12)
    }

    private fun paintThru(g: Gray8, l: Layout, s: State) {
        g.fillRect(l.thruCell, Level.BG)
        draw(g, l.thruCell.x + 8, l.thruCell.y + 8, s.thru, Level.DIM, fTel)
    }

    /** Compass TAPE (§4.5b): three sectors, current one under a fixed centre
     *  mark. Hysteresis lives upstream (§7.2) — this only draws. */
    private fun paintTape(g: Gray8, l: Layout, s: State) {
        g.fillRect(l.tapeCell, Level.BG)
        val heading = s.compass
        if (heading == null) {
            g.fillRect(l.tapeCell.x + l.tapeCell.w / 2 - 8, l.tapeCell.y + 14, 16, 2, Level.REST)
            return
        }
        val sect = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val i = sect.indexOf(heading).coerceAtLeast(0)
        for (k in -1..1) {
            val sc = sect[((i + k) % 8 + 8) % 8]
            val f = if (k == 0) fChromeB else fTel
            val lv = if (k == 0) Level.BODY else Level.DIM
            val cx = l.tapeCell.x + l.tapeCell.w / 2 + k * 34
            drawRight(g, cx + text.measure(sc, f) / 2, l.tapeCell.y + if (k == 0) 6 else 8, sc, lv, f)
            g.fillRect(cx - 1, l.tapeCell.y + 22, 3, 4, Level.REST)
        }
        g.fillRect(l.tapeCell.x + l.tapeCell.w / 2 - 3, l.tapeCell.y + 2, 7, 3, Level.HOT)
    }

    /** TWO links in one cell (§4.4): BLE bars right, host state left. Host
     *  stays near the ink floor while healthy and reports DURATION when gone. */
    private fun paintLink(g: Gray8, l: Layout, s: State) {
        g.fillRect(l.linkCell, Level.BG)
        val poor = (s.linkDbm ?: 0) <= -75 && s.linkDbm != null
        for (i in 0 until 4) {
            val h = 4 + i * 4
            val on = s.linkBars > i
            val bx = l.linkCell.right - 44 + i * 8
            g.fillRect(bx, l.linkCell.y + 20 - h, 6, h,
                if (on) (if (poor) Level.HOT else Level.BODY) else Level.FAINT)
        }
        if (poor) drawRight(g, l.linkCell.right - 48, l.linkCell.y + 8, "${s.linkDbm}", Level.HOT, fTel)
        if (s.hostState.isEmpty()) {
            g.fillRect(l.linkCell.x + 6, l.linkCell.y + 12, 4, 4, Level.REST)  // healthy: one dim mark
        } else {
            // the host banner shares the cell with the BLE bars (right 44 px)
            // and the poor-signal dBm: fit into what is left, never overdraw
            val right = if (poor) l.linkCell.right - 48 - text.measure("${s.linkDbm}", fTel) - 4
                        else l.linkCell.right - 48
            drawFit(g, l.linkCell.x + 6, l.linkCell.y + 6, right - 12 - (l.linkCell.x + 6),
                s.hostState, Level.MID, fTel, right - 10)
        }
    }
}
