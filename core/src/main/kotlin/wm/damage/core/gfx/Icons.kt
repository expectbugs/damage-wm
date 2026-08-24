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
enum class IconKind { TERMINAL, CALENDAR, MUSIC, TIMER, SMS, READER, FILES, NOTICES, SCOUT, SETTINGS, MAIL }

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
    fun analogClock(s: Gray8, cx: Int, cy: Int, r: Int, hh: Int, mm: Int) {
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0 - 90)
            val px = (cx + r * cos(a)).toInt()
            val py = (cy + r * sin(a)).toInt()
            val sz = if (i % 3 == 0) 3 else 2
            val lv = if (i % 3 == 0) Level.of(5) else Level.DIM
            s.fillRect(px - sz, py - sz, 2 * sz + 1, 2 * sz + 1, lv)
        }
        fun hand(frac: Double, length: Double, width: Int) {
            val a = Math.toRadians(frac * 360 - 90)
            s.line(cx, cy, (cx + length * cos(a)).toInt(), (cy + length * sin(a)).toInt(), Level.of(7), width)
        }
        hand(((hh % 12) + mm / 60.0) / 12.0, r * 0.52, 5)
        hand(mm / 60.0, r * 0.80, 3)
        s.fillRect(cx - 2, cy - 2, 5, 5, Level.of(7))
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
