package wm.damage.core.windows.music

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.Level
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * MUSIC — the visualizer renderers (`MUSIC.md` §3.8, §8.3; the Settings row is
 * "Visualizer: Off/Bars/Scope/Pulse/Meter" in §8.4).
 *
 * These are PURE: a renderer is an object with no mutable state, and one paint
 * is a function of (data, posMs, r) alone. That matters twice over — the
 * gravity/smoothing everyone expects from a spectrum is computed by looking
 * BACK through the precomputed frames rather than by remembering the last
 * paint, so a repaint after a window switch, a driver swap or a restore draws
 * exactly what a continuous run would have drawn, and `VizTest` can pin it
 * byte-for-byte.
 *
 * Geometry (`DESIGN.md` §2): every rect these return has x/w on 4 px and y/h on
 * 2 px, because a mode-3 box encodes left/4 · top/2 · width/4 · height/2 and an
 * unaligned box is refused by the firmware without a word
 * (g2flash/patches/zlib_glue.c). Fills INSIDE the returned rect are free of
 * that rule — they ride the one damage rect — but they stay on the grid anyway
 * for crisper edges and longer RLE runs (§8.5).
 *
 * One rect per surface per frame (`MUSIC.md` §12): a strip painted bar-by-bar
 * would burn the ~6 mode-3 fid budget in a batch and the extra boxes would be
 * skipped in silence. Each renderer therefore returns ONE rect that contains
 * every pixel it can light at that r — the same rect on every frame, whatever
 * the data — so the shell can push a stable delta box and nothing it leaves
 * behind is ever stale.
 *
 * Ink (`DESIGN.md` §4.2): level 0 does not emit, so ink coverage IS opacity.
 * [VizRenderer.inkBudget] is a ceiling over r ONLY; the strip itself is at most
 * 608x96 = 19 % of the panel, so a 0.36 strip is ~4 % of the view. Budgets are
 * measured, not guessed — `VizTest.inkStaysUnderBudget` drives a full-scale
 * synthetic signal through every renderer at every shell height and fails if a
 * renderer creeps over what it promises. All four sit in one band on purpose
 * (0.31–0.35 at full scale, 0.10–0.32 on varied signal): switching visualizers
 * should change the look, not how much of the view is lit.
 */
interface VizRenderer {
    val name: String                       // "Bars" · "Scope" · "Pulse" · "Meter"

    /** Paint ONE frame for [posMs] into exactly [r] (cleared first) and return the ONE rect that
     *  changed (r itself, or a sub-rect when less moved). Deterministic for (data, posMs, r).
     *  Never throws on short/empty data — paints the resting form. */
    fun paint(g: Gray8, r: Rect, data: VizData?, posMs: Long): Rect

    /** Ink fraction ceiling this renderer promises inside r (the test pins it). */
    val inkBudget: Double
}

object Viz {
    val ALL: List<VizRenderer> = listOf(BarsViz, ScopeViz, PulseViz, MeterViz)

    /** The Settings values in row order; "Off" is the absence of a renderer. */
    val NAMES: List<String> = listOf("Off") + ALL.map { it.name }

    /** Case-insensitive so a settings string round-trips; null for "Off" and for
     *  anything a newer build wrote that this one does not know. */
    fun byName(n: String): VizRenderer? = ALL.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) }

    /** The default strip height (`MUSIC.md` §8.3: 608 x 48…96). */
    const val STRIP_H = 48
    const val STRIP_W = 608

    /** The strip at each of the four shell heights (`DESIGN.md` §2.4 rule 4).
     *  Taller panels can spend more rows on it; 288 keeps the card and lyrics. */
    fun stripHeight(shellHeight: Int): Int = when {
        shellHeight >= 480 -> 96
        shellHeight >= 416 -> 72
        shellHeight >= 352 -> 56
        else -> STRIP_H
    }

    /** Below this a strip cannot carry 12 VU blocks or a legible bar, and the
     *  caller is asking for something it cannot use. Refused loudly. */
    const val MIN_W = 48
    const val MIN_H = 16
}

// ====================================================================== shared helpers

/** The 4x2-aligned rect INSIDE [r] — the paint area. Snapped inward, never
 *  outward: growing would paint outside the strip the window gave us. */
private fun inner(r: Rect, g: Gray8, who: String): Rect {
    if (r.w <= 0 || r.h <= 0)
        throw LintError("$who: rect $r has no extent")
    if (r.x < 0 || r.y < 0 || r.right > g.w || r.bottom > g.h)
        throw LintError("$who: rect $r lies outside the ${g.w}x${g.h} surface — the paint " +
            "would be clipped and the strip would come up short with no other sign")
    val x0 = Geometry.snapX(r.x + Geometry.X_STEP - 1)
    val y0 = Geometry.snapY(r.y + Geometry.Y_STEP - 1)
    val a = Rect(x0, y0, Geometry.snapX(r.right) - x0, Geometry.snapY(r.bottom) - y0)
    if (a.w < Viz.MIN_W || a.h < Viz.MIN_H)
        throw LintError("$who: $r leaves ${a.w}x${a.h} on the 4x2 damage grid; the strip needs " +
            "at least ${Viz.MIN_W}x${Viz.MIN_H}")
    return a
}

/**
 * Band level 0–15, with the two guards `VizData.level` deliberately does not
 * have. It CLAMPS an out-of-range frame onto the first/last one, which is right
 * for a lookup and wrong for a look-back: a gravity walk that runs off the
 * front of the track would read frame 0 over and over and smear the opening
 * frame backwards for half a second. Outside the track is silence here. The
 * short-blob check is the contract's "never throws on short data" — a truncated
 * frame table paints what it holds and nothing where it holds nothing.
 */
private fun levelAt(d: VizData, f: Int, b: Int): Int {
    if (d.frameCount <= 0 || d.bands <= 0) return 0
    if (f < 0 || f >= d.frameCount || b < 0 || b >= d.bands) return 0
    if ((f * d.bands + b) shr 1 >= d.frames.size) return 0
    return d.level(f, b)
}

/** RMS level 0–15 of one 20 ms slot, or -1 outside the envelope. Same reason as
 *  [levelAt]: `VizData.rmsAt` clamps, and a scrolling strip must draw NOTHING
 *  for the time before the track started rather than repeat slot 0. The nibble
 *  unpack mirrors `VizData.Companion.nib` (MusicModel.kt). */
private fun rmsSlot(d: VizData, i: Int): Int {
    if (i < 0 || i >= d.rmsCount) return -1
    if ((i shr 1) >= d.rms.size) return -1
    val byte = d.rms[i shr 1].toInt() and 0xFF
    return if (i and 1 == 0) byte shr 4 else byte and 0x0F
}

/** 0.0–1.0 of the RMS envelope at [ms]; 0.0 before the track and past its end. */
private fun rmsFrac(d: VizData?, ms: Long): Double {
    if (d == null || ms < 0) return 0.0
    val v = rmsSlot(d, (ms / 20L).toInt())
    return if (v < 0) 0.0 else v / 15.0
}

/** A level 0–15 as a height in pixels on the 2 px grid; anything audible is at
 *  least one grid step tall, so a quiet band reads as present, not as absent. */
private fun heightOf(level: Double, maxH: Int): Int {
    if (level <= 0.0) return 0
    val h = Geometry.snapY((level / 15.0 * maxH).roundToInt())
    return h.coerceIn(Geometry.Y_STEP, maxH)
}

// ====================================================================== Bars

/**
 * The cava-style spectrum: one bar per band, gravity on the fall, a peak cap
 * that falls much slower.
 *
 * The gravity model is `max over the last k frames of (level - k * fall)` — the
 * bar jumps to a new peak instantly (that is what makes a spectrum feel
 * connected to the music) and slides down at a fixed rate afterwards. Written
 * as a look-back it needs no state between paints, so it is identical whether
 * the shell painted the previous frame or not.
 */
object BarsViz : VizRenderer {
    override val name = "Bars"

    /** Bars cover at most 1/3 of the width by construction, so a full-scale
     *  spectrum measures 0.318–0.333 and a varied one ~0.25 (VizTest pins it). */
    override val inkBudget = 0.36

    private const val BASE_H = 2          // the resting floor line
    private const val CAP_H = 2           // the peak cap
    private const val CAP_GAP = 2         // clear air between a bar and its cap
    private const val MIN_PITCH = 12      // 4 px bar + 8 px gap at the narrowest

    /** Levels per second the bar falls once a band goes quiet: full scale in
     *  ~0.47 s. Expressed per SECOND so the look is the same at any data fps. */
    private const val FALL_PER_S = 32.0

    /** The cap falls at 5 levels/s — full scale in 3 s, long enough to read. */
    private const val PEAK_FALL_PER_S = 5.0

    override fun paint(g: Gray8, r: Rect, data: VizData?, posMs: Long): Rect {
        val a = inner(r, g, "Viz.Bars")
        g.fillRect(r, Level.BG)
        // The floor: the honest resting form, and the line the bars stand on.
        g.fillRect(a.x, a.bottom - BASE_H, a.w, BASE_H, Level.FAINT)
        if (data == null || data.bands <= 0 || data.frameCount <= 0) return a

        val n = minOf(data.bands, maxOf(1, a.w / MIN_PITCH))
        val pitch = maxOf(MIN_PITCH, Geometry.snapX(a.w / n))
        val barW = maxOf(Geometry.X_STEP, Geometry.snapX(pitch / 3))
        val x0 = a.x + Geometry.snapX((a.w - n * pitch) / 2)

        val floorY = a.bottom - BASE_H
        val maxH = maxOf(Geometry.Y_STEP, Geometry.snapY(a.h - BASE_H - CAP_GAP - CAP_H - 2))
        // Three brightness zones, dim at the floor and bright at the top: the
        // eye reads height twice (DESIGN.md §4.2 — structure from brightness).
        val zoneMid = Geometry.snapY(maxH / 2)
        val zoneTop = Geometry.snapY(maxH * 4 / 5)

        val fps = if (data.fps > 0) data.fps else 20
        val frame = data.frameAt(posMs)
        for (j in 0 until n) {
            val b0 = j * data.bands / n
            val b1 = maxOf(b0 + 1, (j + 1) * data.bands / n)
            val bx = x0 + j * pitch

            val barH = heightOf(gravity(data, frame, b0, b1, FALL_PER_S / fps), maxH)
            if (barH > 0) {
                val yLow = floorY - minOf(barH, zoneMid)
                g.fillRect(bx, yLow, barW, floorY - yLow, Level.DIM)
                if (barH > zoneMid) {
                    val yMid = floorY - minOf(barH, zoneTop)
                    g.fillRect(bx, yMid, barW, yLow - yMid, Level.BODY)
                    if (barH > zoneTop) {
                        val top = floorY - barH
                        g.fillRect(bx, top, barW, yMid - top, Level.HEAD)
                    }
                }
            }

            val peakH = heightOf(gravity(data, frame, b0, b1, PEAK_FALL_PER_S / fps), maxH)
            if (peakH > 0) {
                val capTop = floorY - maxOf(peakH, barH + CAP_GAP + CAP_H)
                if (capTop >= a.y) g.fillRect(bx, capTop, barW, CAP_H, Level.MID)
            }
        }
        return a
    }

    /** The loudest of bands [b0,b1) at [frame], and every earlier frame decayed
     *  by [fallPerFrame] levels — the higher wins. Pure in the data. */
    private fun gravity(d: VizData, frame: Int, b0: Int, b1: Int, fallPerFrame: Double): Double {
        if (fallPerFrame <= 0.0) return 0.0
        val look = ceil(15.0 / fallPerFrame).toInt().coerceIn(1, 400)
        var best = 0.0
        for (k in 0..look) {
            val f = frame - k
            if (f < 0) break
            var raw = 0
            for (b in b0 until b1) raw = maxOf(raw, levelAt(d, f, b))
            if (raw == 0) continue
            val v = raw - k * fallPerFrame
            if (v > best) best = v
        }
        return best
    }
}

// ====================================================================== Scope

/**
 * The scrolling waveform strip: one 4 px column per 40 ms of the RMS envelope,
 * newest flush against the right edge, drawn as a centred vertical bar.
 *
 * 40 ms (two 20 ms slots, the louder of the pair so no peak is dropped) puts
 * ~3.0 s of history across a 608 px strip — a phrase, not a twitch. 20 ms would
 * show 1.5 s and read as noise at FAR; 80 ms would show 6 s and lose the
 * transients. The column pitch is 8 px (a 4 px column, a 4 px gap): adjacent
 * columns would make the envelope a solid block at ~0.85 ink, and spacing is
 * how this display draws structure (DESIGN.md §4.2). The wave uses two thirds
 * of the strip height for the same reason.
 *
 * The mode-9 shift optimization (`MUSIC.md` §3.8 — copy the strip left on the
 * glass, send only the new column) is the SHELL's later concern; here the whole
 * strip is one rect, which is what the one-rect-per-surface rule asks for.
 */
object ScopeViz : VizRenderer {
    override val name = "Scope"

    /** Ceiling: 1/2 the width x 2/3 the height at full scale, plus the zero line
     *  showing through the gaps — 0.354 measured, and real music sits at ~0.21
     *  (VizTest's probe). Deliberately in the same band as the other three:
     *  swapping visualizers should not change how much of the view is lit. */
    override val inkBudget = 0.40

    const val COL_W = 4
    const val COL_PITCH = 8
    const val SLOTS_PER_COL = 2           // 20 ms per slot => 40 ms per column
    const val MS_PER_COL = SLOTS_PER_COL * 20L

    override fun paint(g: Gray8, r: Rect, data: VizData?, posMs: Long): Rect {
        val a = inner(r, g, "Viz.Scope")
        g.fillRect(r, Level.BG)

        val cy = a.y + Geometry.snapY(a.h / 2)
        // The zero line: the resting form, and what shows through a silence.
        g.fillRect(a.x, cy - Geometry.Y_STEP, a.w, Geometry.Y_STEP, Level.FAINT)
        if (data == null || data.rmsCount <= 0) return a

        val cols = maxOf(1, a.w / COL_PITCH)
        // Left-align the slack so the NEWEST column ends exactly on a.right.
        val x0 = a.x + (a.w - cols * COL_PITCH)
        // Two thirds of the strip, so the wave keeps a margin at both edges —
        // the strip sits directly under the lyrics in Music Mode.
        val maxHalf = maxOf(Geometry.Y_STEP, Geometry.snapY(a.h / 3))
        val newest = posMs / 20L

        for (c in 0 until cols) {
            val back = (cols - 1 - c) * SLOTS_PER_COL
            var lv = -1
            for (s in 0 until SLOTS_PER_COL) {
                val v = rmsSlot(data, (newest - back - s).toInt())
                if (v > lv) lv = v
            }
            if (lv <= 0) continue        // before the track, past its end, or silence
            val half = heightOf(lv.toDouble(), maxHalf)
            g.fillRect(x0 + c * COL_PITCH + (COL_PITCH - COL_W), cy - half, COL_W, half * 2, Level.BODY)
        }
        return a
    }
}

// ====================================================================== Pulse

/**
 * One centred wide thin bar that swells on the beat and breathes with the RMS.
 * A filled bar is one RLE run per row (DESIGN.md §8.5 — filled, never outlined)
 * and it is the least ink of the four, which is what makes it the one to leave
 * on all day.
 *
 * The returned rect is the band the bar can reach, not the whole strip: nothing
 * outside that band is ever lit at this r, so pushing the band alone is
 * complete and costs a fraction of the bytes.
 */
object PulseViz : VizRenderer {
    override val name = "Pulse"

    /** 0.288–0.299 on the beat, ~0.10 averaged over a track — by far the
     *  quietest of the four, and the reason it is the one to leave on. */
    override val inkBudget = 0.32

    /** The swell decays over ~250 ms — long enough to see at 8 fps, short
     *  enough that it is back down before the next beat at 240 bpm. */
    private const val DECAY_MS = 250.0

    override fun paint(g: Gray8, r: Rect, data: VizData?, posMs: Long): Rect {
        val a = inner(r, g, "Viz.Pulse")
        g.fillRect(r, Level.BG)

        val maxW = maxOf(Geometry.X_STEP, Geometry.snapX(a.w * 9 / 10))
        val maxH = maxOf(Geometry.Y_STEP, Geometry.snapY(a.h / 3))
        val bandX = Geometry.snapX(a.x + (a.w - maxW) / 2).coerceIn(a.x, a.right - maxW)
        val bandY = Geometry.snapY(a.y + (a.h - maxH) / 2).coerceIn(a.y, a.bottom - maxH)
        val band = Rect(bandX, bandY, maxW, maxH)

        // The resting form: a faint rule the swell rides on.
        g.fillRect(band.x, band.y + Geometry.snapY((band.h - Geometry.Y_STEP) / 2),
            band.w, Geometry.Y_STEP, Level.FAINT)

        val since = data?.sinceBeat(posMs) ?: -1L
        val beat = if (since < 0L) 0.0 else (1.0 - since / DECAY_MS).coerceIn(0.0, 1.0)
        val loud = rmsFrac(data, posMs)
        val s = (0.15 + 0.50 * loud + 0.45 * beat).coerceIn(0.0, 1.0)

        val w = maxOf(Geometry.X_STEP, Geometry.snapX((maxW * s).roundToInt())).coerceAtMost(maxW)
        val h = maxOf(Geometry.Y_STEP, Geometry.snapY((maxH * s).roundToInt())).coerceAtMost(maxH)
        val x = Geometry.snapX(band.x + (band.w - w) / 2).coerceIn(band.x, band.right - w)
        val y = Geometry.snapY(band.y + (band.h - h) / 2).coerceIn(band.y, band.bottom - h)
        // Brightness carries the beat as well as size — a user calibrated to
        // d = 0 sees no depth at all, and size alone is easy to miss at FAR.
        g.fillRect(x, y, w, h, Level.of((2.0 + 10.0 * s).toInt().coerceIn(2, 13)))
        return band
    }
}

// ====================================================================== Meter

/**
 * Two horizontal VU bars in 12 blocks, the lower one lagging the upper by one
 * 20 ms slot so the pair moves like a stereo meter without the data carrying
 * two channels (the envelope is mono — `MUSIC.md` §6.4 — and inventing a second
 * channel would be a decoration pretending to be a measurement; the lag is
 * stated as what it is).
 *
 * Blocks, not a smooth bar: a solid block is a long RLE run and a gradient is
 * not (DESIGN.md §8.5), and `Icons.blocks` is the same primitive the progress
 * bars use, so the meter reads as part of the same shell.
 */
object MeterViz : VizRenderer {
    override val name = "Meter"

    /** Nearly constant at 0.271–0.319: `Icons.blocks` paints the UNLIT blocks at
     *  FAINT, so the meter costs its whole frame whatever the signal does. That
     *  is the trade for a readable scale — the eye needs the empty blocks to
     *  know how loud "loud" is. */
    override val inkBudget = 0.36

    private const val BLOCKS = 12
    private const val LAG_MS = 20L

    /** Peak hold falls to zero over 60 slots = 1.2 s. */
    private const val PEAK_SLOTS = 60

    override fun paint(g: Gray8, r: Rect, data: VizData?, posMs: Long): Rect {
        val a = inner(r, g, "Viz.Meter")
        g.fillRect(r, Level.BG)

        val barH = maxOf(Geometry.Y_STEP, Geometry.snapY(a.h / 6))
        val total = 3 * barH                       // two bars and one bar of air
        val top = a.y + Geometry.snapY((a.h - total) / 2)
        val lowY = top + 2 * barH

        Icons.blocks(g, a.x, top, a.w, barH, rmsFrac(data, posMs), BLOCKS, Level.BODY)
        Icons.blocks(g, a.x, lowY, a.w, barH, rmsFrac(data, posMs - LAG_MS), BLOCKS, Level.BODY)
        peakMark(g, a.x, top, a.w, barH, peakFrac(data, posMs))
        peakMark(g, a.x, lowY, a.w, barH, peakFrac(data, posMs - LAG_MS))

        return Rect(a.x, top, a.w, total)
    }

    /** The held peak drawn as the one bright block. The block geometry mirrors
     *  `Icons.blocks` (gfx/Icons.kt) — the same bw and the same 2 px seam. */
    private fun peakMark(g: Gray8, x: Int, y: Int, w: Int, h: Int, frac: Double) {
        if (frac <= 0.0) return
        val bw = (w - (BLOCKS - 1) * 2) / BLOCKS
        if (bw <= 0) return
        val k = (ceil(frac * BLOCKS).toInt() - 1).coerceIn(0, BLOCKS - 1)
        g.fillRect(x + k * (bw + 2), y, bw, h, Level.HEAD)
    }

    /** The loudest of the last 1.2 s, each slot decayed by its age — the same
     *  look-back shape as the spectrum's gravity, so both are pure. */
    private fun peakFrac(d: VizData?, ms: Long): Double {
        if (d == null || ms < 0) return 0.0
        var best = 0.0
        for (k in 0..PEAK_SLOTS) {
            val at = ms - k * 20L
            if (at < 0) break
            val v = rmsFrac(d, at) - k.toDouble() / PEAK_SLOTS
            if (v > best) best = v
        }
        return best.coerceIn(0.0, 1.0)
    }
}
