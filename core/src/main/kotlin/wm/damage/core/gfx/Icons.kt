package wm.damage.core.gfx

import kotlin.math.cos
import kotlin.math.sin

/**
 * The drawn icon set and UI shapes — ported from design/render_shots.py, which
 * is the measured reference the ink budgets were priced against. UI symbols are
 * DRAWN, never typed (DESIGN.md §Type): `▸`/`⚙` are tofu in most locked faces.
 * Drawing rules (§2.4 rule 9, also the compression rules): thick strokes,
 * closed forms, no hairlines, few levels, solid fills.
 */
enum class IconKind { TERMINAL, CALENDAR, MUSIC, TIMER, SMS, READER, FILES, NOTICES, SCOUT, SETTINGS, MAIL, TORRENTS }

object Icons {
    private const val BG = 0

    /** The `▸` continuation mark (§2.4 rule 3): advertises overflow without a
     *  marquee. Height [h], apex to the right (or left). */
    fun tri(s: Gray8, x: Int, y: Int, h: Int, level: Int, left: Boolean = false) {
        for (i in 0 until h) {
            val w = (h - kotlin.math.abs(h - 1 - 2 * i)) / 2
            if (w <= 0) continue
            if (!left) s.fillRect(x, y + i, w + 1, 1, level)
            else s.fillRect(x - w, y + i, w + 1, 1, level)
        }
    }

    /** Coarse block progress (§4.5b): solid blocks are long RLE runs; a smooth
     *  bar is not. */
    fun blocks(s: Gray8, x: Int, y: Int, w: Int, h: Int, frac: Double, n: Int = 8, level: Int = Level.BODY) {
        val bw = (w - (n - 1) * 2) / n
        for (k in 0 until n) {
            val bx = x + k * (bw + 2)
            s.fillRect(bx, y, bw, h, if ((k + 1).toDouble() / n <= frac) level else Level.FAINT)
        }
    }

    /** Battery: a plain fill bar, deliberately NOT segmented (§4.1 — measured;
     *  do not "fix" it by segmenting or widening without reading that section). */
    fun batteryBar(s: Gray8, x: Int, y: Int, w: Int, h: Int, pct: Int, level: Int) {
        s.outlineRect(x, y, w, h, level, 2)
        s.fillRect(x + w + 1, y + h / 3, 4, h - 2 * (h / 3), level)   // the nub
        val fill = ((w - 6) * pct.coerceIn(0, 100)) / 100
        if (fill > 0) s.fillRect(x + 3, y + 3, fill, h - 6, level)
    }

    /** Brightness tracks charge; <=20% pulses via [flashPhase] (§4.1). */
    val FLASH_SEQ = intArrayOf(15, 8, 3, 8, 15, 8, 3)
    fun batteryLevel(pct: Int, flashPhase: Int?): Int =
        if (flashPhase != null) Level.of(FLASH_SEQ[flashPhase % FLASH_SEQ.size])
        else Level.of((2 + 8 * pct / 100).coerceIn(2, 10))

    /** The silent-mode analog clock (§1.5): dots and hands only, NO bezel (a
     *  circle outline breaks the RLE run on every row; twelve dots do not) and
     *  NO second hand, ever. The minute hand snaps once a minute. */
    /** Seven-segment digital clock (REFINEMENT.md §5, second revision — Adam
     *  2026-08-31: "forget analog, make it good-looking digital numbers").
     *  Classic LED-clock digits, DRAWN (never typed: the locked faces stay
     *  four): tapered hexagonal segments with softened edge rows and corner
     *  gaps, 12-hour, no leading zero. Horizontal segments are single long
     *  runs, so the RLE cost stays tiny. Extent: 138x44 from (x0,y0). Keep in
     *  lockstep with design/render_shots.py seven_seg_clock(). */
    fun sevenSegClock(s: Gray8, x0: Int, y0: Int, hh: Int, mm: Int) {
        // bit order A B C D E F G, bit 6 .. bit 0
        val segs = intArrayOf(
            0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
            0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011,
        )
        val w = 26; val t = 6
        fun hseg(x: Int, y: Int, len: Int) {
            for (r in 0 until t) {
                val inset = kotlin.math.abs(2 * r - (t - 1)) / 2
                s.fillRect(x + inset, y + r, len - 2 * inset, 1,
                    Level.of(if (r == 0 || r == t - 1) 6 else 9))
            }
        }
        fun vseg(x: Int, y: Int, len: Int) {
            for (c in 0 until t) {
                val inset = kotlin.math.abs(2 * c - (t - 1)) / 2
                s.fillRect(x + c, y + inset, 1, len - 2 * inset,
                    Level.of(if (c == 0 || c == t - 1) 6 else 9))
            }
        }
        fun digit(x: Int, d: Int) {
            val m = segs[d]
            if (m and 0b1000000 != 0) hseg(x + 4, y0, w - 8)              // A
            if (m and 0b0100000 != 0) vseg(x + w - t, y0 + 7, 12)         // B
            if (m and 0b0010000 != 0) vseg(x + w - t, y0 + 26, 12)        // C
            if (m and 0b0001000 != 0) hseg(x + 4, y0 + 38, w - 8)         // D
            if (m and 0b0000100 != 0) vseg(x, y0 + 26, 12)                // E
            if (m and 0b0000010 != 0) vseg(x, y0 + 7, 12)                 // F
            if (m and 0b0000001 != 0) hseg(x + 4, y0 + 19, w - 8)         // G
        }
        val h12 = ((hh + 11) % 12) + 1
        if (h12 >= 10) digit(x0, 1)
        digit(x0 + 32, h12 % 10)
        s.fillRect(x0 + 67, y0 + 12, 6, 6, Level.of(9))
        s.fillRect(x0 + 67, y0 + 28, 6, 6, Level.of(9))
        digit(x0 + 80, mm / 10)
        digit(x0 + 112, mm % 10)
    }

    /** The MEDIUM seven-segment readout (§1.5 silent-clock sizes, 2026-09-01):
     *  the same drawing at ~2/3 metrics — digit 18 wide / 30 tall, thickness
     *  4, pitch 24. Kept as its own function so the large one stays
     *  byte-identical (snapshots pin its pixels). Total width 102. */
    fun sevenSegClockMedium(s: Gray8, x0: Int, y0: Int, hh: Int, mm: Int) {
        val segs = intArrayOf(
            0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
            0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011,
        )
        val w = 18; val t = 4
        fun hseg(x: Int, y: Int, len: Int) {
            for (r in 0 until t) {
                val inset = kotlin.math.abs(2 * r - (t - 1)) / 2
                s.fillRect(x + inset, y + r, len - 2 * inset, 1,
                    Level.of(if (r == 0 || r == t - 1) 6 else 9))
            }
        }
        fun vseg(x: Int, y: Int, len: Int) {
            for (c in 0 until t) {
                val inset = kotlin.math.abs(2 * c - (t - 1)) / 2
                s.fillRect(x + c, y + inset, 1, len - 2 * inset,
                    Level.of(if (c == 0 || c == t - 1) 6 else 9))
            }
        }
        fun digit(x: Int, d: Int) {
            val m = segs[d]
            if (m and 0b1000000 != 0) hseg(x + 3, y0, w - 6)              // A
            if (m and 0b0100000 != 0) vseg(x + w - t, y0 + 5, 9)          // B
            if (m and 0b0010000 != 0) vseg(x + w - t, y0 + 18, 9)         // C
            if (m and 0b0001000 != 0) hseg(x + 3, y0 + 26, w - 6)         // D
            if (m and 0b0000100 != 0) vseg(x, y0 + 18, 9)                 // E
            if (m and 0b0000010 != 0) vseg(x, y0 + 5, 9)                  // F
            if (m and 0b0000001 != 0) hseg(x + 3, y0 + 13, w - 6)         // G
        }
        val h12 = ((hh + 11) % 12) + 1
        if (h12 >= 10) digit(x0, 1)
        digit(x0 + 24, h12 % 10)
        s.fillRect(x0 + 47, y0 + 8, 4, 4, Level.of(9))
        s.fillRect(x0 + 47, y0 + 19, 4, 4, Level.of(9))
        digit(x0 + 56, mm / 10)
        digit(x0 + 84, mm % 10)
    }

    /** The earlier analog face (ticks, tapered hands, hub) — UNUSED since the
     *  2026-08-31 digital revision; kept in case analog returns as a setting. */
    fun analogClock(s: Gray8, cx: Int, cy: Int, r: Int, hh: Int, mm: Int) {
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90)
            if (i % 3 == 0) {
                // cardinal: a radial tick — axis-aligned, so one run per row
                s.line((cx + (r - 8) * cos(a)).toInt(), (cy + (r - 8) * sin(a)).toInt(),
                    (cx + r * cos(a)).toInt(), (cy + r * sin(a)).toInt(), Level.of(6), 3)
            } else {
                val px = (cx + r * cos(a)).toInt()
                val py = (cy + r * sin(a)).toInt()
                s.fillRect(px - 1, py - 1, 3, 3, Level.DIM)
            }
        }
        fun hand(frac: Double, length: Double, wNear: Int, wFar: Int, tail: Double) {
            val a = Math.toRadians(frac * 360 - 90)
            val mx = (cx + length * 0.55 * cos(a)).toInt()
            val my = (cy + length * 0.55 * sin(a)).toInt()
            s.line(cx, cy, mx, my, Level.of(8), wNear)
            s.line(mx, my, (cx + length * cos(a)).toInt(), (cy + length * sin(a)).toInt(), Level.of(8), wFar)
            s.line(cx, cy, (cx - tail * cos(a)).toInt(), (cy - tail * sin(a)).toInt(), Level.of(8), wNear)
        }
        hand(((hh % 12) + mm / 60.0) / 12.0, r * 0.55, 5, 3, r * 0.16)   // hour: short, wide
        hand(mm / 60.0, r * 0.86, 3, 2, r * 0.20)                        // minute: long, slim
        s.fillRect(cx - 3, cy - 3, 7, 7, Level.of(4))                    // hub plate
        s.fillRect(cx - 1, cy - 1, 3, 3, Level.of(9))                    // pin
    }

    /** One icon of the shared set (§4.5b), box (x, y, w, h). */
    fun draw(s: Gray8, x: Int, y: Int, w: Int, h: Int, kind: IconKind, lv: Int) {
        val t = maxOf(3, h / 10)
        when (kind) {
            IconKind.TERMINAL -> {
                s.fillRect(x, y, w, h, lv)
                s.fillRect(x + t, y + t * 2, w - 2 * t, h - 3 * t, BG)
                val cw = maxOf(3, w / 8)
                for (i in 0 until cw * 2) {
                    s.fillRect(x + t * 2 + i, y + h / 2 - cw + i, t + 1, t + 1, lv)
                    s.fillRect(x + t * 2 + i, y + h / 2 + cw - i, t + 1, t + 1, lv)
                }
                s.fillRect(x + w / 2, y + h - t * 3, w - t * 2 - w / 2, t + 1, lv)
            }
            IconKind.CALENDAR -> {
                s.fillRect(x, y, w, h, lv)
                s.fillRect(x + t, y + h / 3, w - 2 * t, h - h / 3 - t, BG)
                for (c in 0 until 3) for (rr in 0 until 2) {
                    val dx = x + t * 2 + c * (w - t * 4) / 3
                    val dy = y + h / 3 + t + rr * (h / 4)
                    s.fillRect(dx, dy, t + 1, t + 1, lv)
                }
            }
            IconKind.MUSIC -> {
                s.fillRect(x + w - t * 2, y, t * 2, h - t * 3, lv)
                s.fillRect(x + w / 3, y + t * 2, w - w / 3, t + 1, lv)
                s.fillRect(x + w / 3, y + t * 2, t + 1, h - t * 4, lv)
                s.fillEllipse(x, y + h - t * 4, t * 4, t * 4, lv)
                s.fillEllipse(x + w / 3, y + h - t * 5, t * 4, t * 4, lv)
            }
            IconKind.TIMER -> {
                s.outlineEllipse(x, y + t, w, h - t, lv, t)
                s.fillRect(x + w / 2 - t, y, 2 * t + 1, t * 2, lv)
                s.line(x + w / 2, y + h / 2 + t / 2, x + w / 2, y + t * 3, lv, t)
            }
            IconKind.SMS -> {
                s.fillRect(x, y, w, h - t * 3, lv)
                s.fillRect(x + t, y + t, w - 2 * t, h - t * 5, BG)
                s.fillPolygon(
                    intArrayOf(x + t * 2, x + t * 5, x + t * 2),
                    intArrayOf(y + h - t * 3, y + h - t * 3, y + h), lv,
                )
            }
            IconKind.READER -> {
                s.fillRect(x, y, w, h, lv)
                s.fillRect(x + t, y + t, w / 2 - t - t / 2, h - 2 * t, BG)
                s.fillRect(x + w / 2 + t / 2, y + t, w - w / 2 - t - t / 2, h - 2 * t, BG)
            }
            IconKind.FILES -> {
                s.fillRect(x, y + t * 2, w, h - t * 2, lv)
                s.fillRect(x, y, w / 2, t * 2, lv)
                s.fillRect(x + t, y + t * 4, w - 2 * t, h - t * 5, BG)
            }
            IconKind.NOTICES -> {
                s.fillEllipse(x + t, y, w - 2 * t, h - t * 3, lv)
                s.fillRect(x + t, y + h / 2, w - 2 * t, h - t * 3 - h / 2, lv)
                s.fillRect(x, y + h - t * 3, w, t + 1, lv)
                s.fillRect(x + w / 2 - t, y + h - t * 2, 2 * t + 1, t * 2, lv)
            }
            IconKind.SCOUT -> {
                s.outlineEllipse(x, y, w - t * 3, h - t * 3, lv, t)
                s.line(x + w - t * 4, y + h - t * 4, x + w - 1, y + h - 1, lv, t + 1)
            }
            IconKind.SETTINGS -> {
                s.fillEllipse(x, y, w, h, lv)
                s.fillEllipse(x + t * 2, y + t * 2, w - t * 4, h - t * 4, BG)
                for (a in 0 until 360 step 60) {
                    val rad = Math.toRadians(a.toDouble())
                    val px = x + w / 2 + (w / 2 * cos(rad)).toInt()
                    val py = y + h / 2 + (h / 2 * sin(rad)).toInt()
                    s.fillRect(px - t, py - t, 2 * t + 1, 2 * t + 1, lv)
                }
                s.fillRect(x + w / 2 - t / 2, y + h / 2 - t / 2, t + 1, t + 1, lv)
            }
            IconKind.TORRENTS -> {
                // a download: a bold arrow into a tray — thick strokes, closed
                // forms (§2.4 r9); reads at 20 px and 56 px alike
                val cx = x + w / 2
                val stem = maxOf(2, w / 6)
                val headH = h * 5 / 12
                val trayY = y + h * 3 / 4
                s.fillRect(cx - stem / 2, y, stem, h / 2, lv)
                s.fillPolygon(intArrayOf(cx - w * 3 / 8, cx + w * 3 / 8, cx),
                    intArrayOf(y + h / 2 - headH / 3, y + h / 2 - headH / 3, y + h / 2 + headH * 2 / 3), lv)
                s.fillRect(x + w / 8, trayY, w - w / 4, maxOf(2, h / 8), lv)
                s.fillRect(x + w / 8, trayY - h / 8, maxOf(2, w / 10), h / 8, lv)
                s.fillRect(x + w - w / 8 - maxOf(2, w / 10), trayY - h / 8, maxOf(2, w / 10), h / 8, lv)
            }
            IconKind.MAIL -> {
                s.fillRect(x, y, w, h, lv)
                s.fillRect(x + t, y + t, w - 2 * t, h - 2 * t, BG)
                for (i in 0 until w / 2 - t) {
                    s.fillRect(x + t + i, y + t + i, t + 1, t + 1, lv)
                    s.fillRect(x + w - 1 - t - i - t, y + t + i, t + 1, t + 1, lv)
                }
            }
        }
    }
}
