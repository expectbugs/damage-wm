package wm.damage.core.motion

import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.wire.CfwModes

/**
 * Build C3, stage DP2: the full-screen transitions of `MOTION.md` §7 as version-2 programs (`FIRMWARE.md` §7.2–§7.5,
 * §7.7, §7.11). Context for the reader: Adam's own glasses on his own firmware fork; display-rendering work. A
 * transition is two halves over one area — the leaving screen's picture moved, shrunk, sliced, spread or dimmed IN PLACE
 * by an OWN program (the out half, no LAND), then the arriving window's picture drawn from the shadow by an OWN + LAND
 * program started by PLAY-AFTER (the in half), whose last step copies the shadow in.
 *
 * An effect is a pair of PATHS — the out half's (identity → its far pose) and the in half's (its far pose → identity),
 * each with an ease. Played reversed in time (Close/Back; leaving the silent clock), the pair swaps: the out half runs
 * the in half's path backwards on the panel's picture, the in half the out half's backwards from the shadow, each ease
 * reversed in time (E'[j] = 4096 − E[n − j]: ease-out ↔ ease-in). What an in-place path cannot carry backwards is
 * dropped there and said here: a reseed every step and a stagger (both layer new only, §7.4).
 *
 * Every program is built through [Motion.program2] — the one gate, so nothing here is built below stage 5 — and so is
 * refused offline where the glasses would refuse it, the step budget at the PLAY and the PLAY-AFTER included.
 */
object Transitions {
    /** The tiers that play a transition. */
    enum class Tier(val word: String) { SELECT("Select"), BACK("Back"), LOCK("Lock") }

    /** `MOTION.md` §7's effects that this build draws. Encode and Matrix (GLYPHS) are not built in DP2 (their cell lists
     *  from the screens' text are DM's `CellLists`): their rows are not shown. */
    enum class Effect(val id: String, val label: String) {
        DEPTH("depth", "Depth"), WARP("warp", "Warp"), FLIP("flip", "Flip"), FALL("fall", "Fall"),
        GLITCH("glitch", "Glitch"), EXPLODE("explode", "Explode"), FADE("fade", "Fade"), BUILD("build", "Build");
        companion object { fun of(id: String): Effect? = entries.firstOrNull { it.id == id } }
    }

    /** The effects each tier offers, in its rows' order (Close/Back: Select's, each reversed in time). */
    fun effectsOf(tier: Tier): List<Effect> = when (tier) {
        Tier.SELECT, Tier.BACK -> listOf(Effect.DEPTH, Effect.WARP, Effect.FLIP, Effect.FALL, Effect.GLITCH, Effect.EXPLODE)
        Tier.LOCK -> listOf(Effect.FADE, Effect.BUILD, Effect.GLITCH, Effect.EXPLODE)
    }

    /** Steps a half and the period (OS ticks) for a tier's `length` (ms) and `frames` (0 = Auto: about one frame per
     *  45 ms, the measured step of today's pans — `MOTION.md` §7's "450 ms at 10 frames"). Each half is half the length;
     *  an in-place or `new` step costs ≈ 1–3 ms (§7.12, I), so the period is set by the length and frames. */
    data class Timing(val steps: Int, val period: Int) {
        /** The half's nominal length in ms (T steps at P ticks; a tick is 1/1024 s). */
        val halfMs: Long get() = steps.toLong() * period * 1000L / 1024
    }

    fun timing(lengthMs: Int, frames: Int): Timing {
        val f = if (frames > 0) frames else (Math.round(lengthMs / 45.0).toInt().coerceIn(4, 16) + 1) / 2 * 2
        val steps = maxOf(1, f / 2)
        val p = Math.round(lengthMs / 2.0 / steps * 1.024).toInt().coerceIn(CfwModes.MOTION_PERIODS.first, CfwModes.MOTION_PERIODS.last)
        return Timing(steps, p)
    }

    // ------------------------------------------------------------------------------------------------- the paths
    enum class EaseKind { LINEAR, OUT, IN;
        /** Reversed in time: E'[j] = 4096 − E[n − j]. */
        val reversed: EaseKind get() = when (this) { LINEAR -> LINEAR; OUT -> IN; IN -> OUT }
    }

    /** One half's far pose: a uniform move ([dxL] / [dxR] per lens, [dy]), a shrink to [w] × [h] at the centre (0 on an
     *  axis: unscaled), a radial spread by the factor [spread] (1/4096) over a [gw] × [gh] grid, slices ([slices] of them,
     *  one column) torn sideways by up to [tear] px, or none of these (a level only); [level] the far pose's level. */
    data class Path(val dxL: Int = 0, val dxR: Int = 0, val dy: Int = 0, val w: Int = 0, val h: Int = 0,
                    val spread: Int = 0, val gw: Int = 8, val gh: Int = 6, val slices: Int = 0, val tear: Int = 0,
                    val level: Int = 15, val ease: EaseKind = EaseKind.LINEAR,
                    /** Layer new only: the random model's hash takes the step (opt bit 6) / the pieces' stagger. */
                    val reseed: Boolean = false, val stagger: Boolean = false) {
        val scales: Boolean get() = w != 0 || h != 0
    }

    /** The out half's path and the in half's, for a forward play ([Effect]'s own direction) over [area]. */
    private fun paths(e: Effect, area: Rect, depth: Int): Pair<Path, Path> {
        val d = DEPTH_PX.coerceAtMost(maxOf(4, 16 - depth))      // further back by up to 8 px, inside the 16-px budget
        return when (e) {
            // the screen recedes and fades; the next comes forward out of the dark (K1 §5's Depth, both halves)
            Effect.DEPTH -> Path(dxL = -d, dxR = d, level = 0, ease = EaseKind.OUT) to Path(dxL = -d, dxR = d, level = 0, ease = EaseKind.OUT)
            // shrinks into the distance, dimming; the next grows out of it (C3-design §2.3)
            Effect.WARP -> Path(w = maxOf(1, area.w / 8), h = maxOf(1, area.h / 8), level = 4, ease = EaseKind.IN) to
                Path(w = maxOf(1, area.w / 8), h = maxOf(1, area.h / 8), level = 4, ease = EaseKind.OUT)
            // a card turned edge-on, then the other face turned back
            Effect.FLIP -> Path(w = 1, ease = EaseKind.IN) to Path(w = 1, ease = EaseKind.OUT)
            // falls out of the bottom under gravity; the next falls in from above and settles
            Effect.FALL -> Path(dy = area.h, ease = EaseKind.IN) to Path(dy = -area.h, ease = EaseKind.OUT)
            // slices torn sideways, more each step; the next arrives torn, the tears re-dealt each step and closing
            Effect.GLITCH -> Path(slices = SLICES, tear = TEAR_PX, level = 5) to Path(slices = SLICES, tear = TEAR_PX, level = 5, reseed = true)
            // pieces fly outward; the next assembles from them
            Effect.EXPLODE -> Path(spread = SPREAD, level = 0, ease = EaseKind.OUT) to Path(spread = SPREAD, level = 0, ease = EaseKind.OUT)
            Effect.FADE -> Path(level = 0) to Path(level = 0)
            // its out half Explode's; its in half the pieces arriving one after another
            Effect.BUILD -> Path(spread = SPREAD, level = 0, ease = EaseKind.OUT) to Path(spread = SPREAD, level = 0, stagger = true)
        }
    }

    // --------------------------------------------------------------------------------------------- the programs
    /** A transition's two programs and the records that hold them, laid out from [at4] (4-byte units) in one blob. */
    class Built internal constructor(val effect: Effect, val reversed: Boolean, val area: Rect, val timing: Timing,
                                     val out: MotionProgram, val inn: MotionProgram, val at4: Int, val blob: ByteArray) {
        /** The out half's and the in half's C (§7.12, u) and the sum the PLAY-AFTER is judged by (the in half waits on
         *  the out half: the larger). */
        val outCost: Long get() = out.cost()
        val inCost: Long get() = inn.cost()
        fun describe(): String = "${effect.label}${if (reversed) " reversed" else ""} over $area, ${timing.steps} steps a half at P ${timing.period}, " +
            "records ${blob.size} B at off4 $at4 (out ${out.bytes.size} B, in ${inn.bytes.size} B), C out ${outCost} / in ${inCost} u"
    }

    /**
     * The out and in programs of [effect] over [area] (both lenses), [reversed] in time or not, [depth] the content's
     * disparity (the Depth effect stays inside the 16-px budget), laid out from [at4] in at most [room] bytes: the easing
     * tables first, then the out program, then the in program, 4-byte aligned. [seed] seeds the random slices.
     * Refused ([LintError]) where the glasses would refuse a program, or when the records do not fit [room]. With
     * [freezeOut] the out half is §7.11's one-step freeze ([freeze]) in place of the effect's out program.
     */
    fun build(effects: Motion.Effects, effect: Effect, reversed: Boolean, area: Rect, timing: Timing, at4: Int, room: Int,
              depth: Int = 8, seed: Int = 0x5EED, freezeOut: Boolean = false): Built {
        val (o, i) = paths(effect, area, depth)
        // forward: out runs O on the panel, in runs I from the shadow; reversed in time: out runs I backwards on the
        // panel (identity → I's far pose, its ease reversed), in runs O backwards from the shadow
        val outPath = if (!reversed) o else i.copy(ease = i.ease.reversed, reseed = false, stagger = false)
        val inPath = if (!reversed) i else o.copy(ease = o.ease.reversed)
        val n = timing.steps
        var off = at4
        val outTable = if (freezeOut) null else tableFor(outPath.ease, n, off)?.also { off += words(it.record.size) }
        val inTable = tableFor(inPath.ease, if (inPath.stagger) n - stagger(n) else n, off)
        if (inPath.stagger && inTable != null) throw LintError("a staggered in half takes a built-in ease, not a table")
        inTable?.let { off += words(it.record.size) }
        val out = if (freezeOut) freeze(effects, area, off)
            else Motion.program2(off, effects, own = true) { inPlace(this, outPath, area, n, outTable ?: easeOf(outPath.ease), seed) }
        off += words(out.bytes.size)
        val inn = Motion.program2(off, effects, own = true, land = true) { fromShadow(this, inPath, area, n, inTable ?: easeOf(inPath.ease), seed) }
        off += words(inn.bytes.size)
        val size = (off - at4) * 4
        if (size > room) throw LintError("the transition's records are $size B, past the $room B their slot holds")
        val blob = ByteArray(size)
        for ((o4, b) in out.records + inn.records) b.copyInto(blob, (o4 - at4) * 4)
        return Built(effect, reversed, area, timing, out, inn, at4, blob)
    }

    /** §7.11's one-step freeze (PIECES uniform, dx = dy = 0, T 1, OWN, not LAND): holds the panel's picture over [area]
     *  so a several-batch frame shows nothing of itself before its in half, when there is no out half to hold it. */
    fun freeze(effects: Motion.Effects, area: Rect, at4: Int): MotionProgram =
        Motion.program2(at4, effects, own = true) { track(area, Motion.Backdrop.None) { pieces(Motion.LAYER_OLD, 1) } }

    /** The out half: one in-place track over [area] (backdrop none, its first and only track, §7.2). */
    private fun inPlace(b: Motion.ProgramBuilder, p: Path, area: Rect, n: Int, ease: Motion.Ease, seed: Int) {
        val perLens = p.dxL != p.dxR
        val build: Motion.TrackBuilder.() -> Unit = {
            when {
                p.scales -> shrink(n, ease, w0 = if (p.w != 0) area.w else 0, w1 = p.w, h0 = if (p.h != 0) area.h else 0, h1 = p.h,
                    anchor = 1, hAnchor = 1, lvl0 = 15, lvl1 = p.level)
                p.spread != 0 -> pieces(Motion.LAYER_OLD, n, ease, gw = p.gw, gh = p.gh, model = Motion.RADIAL,
                    x = area.x + area.w / 2, y = area.y + area.h / 2, lvl0 = 15, lvl1 = p.level, f0 = 0, f1 = p.spread)
                p.slices != 0 -> pieces(Motion.LAYER_OLD, n, ease, gw = 1, gh = p.slices, model = Motion.RANDOM, dx = p.tear,
                    lvl0 = 15, lvl1 = p.level, seed = seed, f0 = 0, f1 = 4096)
                else -> if (perLens) pieces(Motion.LAYER_OLD, n, ease, dx = p.dxL, dxR = p.dxR, dy = p.dy, lvl0 = 15, lvl1 = p.level)
                        else pieces(Motion.LAYER_OLD, n, ease, dx = p.dxL, dy = p.dy, lvl0 = 15, lvl1 = p.level)
            }
        }
        if (perLens) b.track(area, area, Motion.Backdrop.None, build) else b.track(area, Motion.Backdrop.None, build)
    }

    /** The in half: one track over [area], backdrop level 0, drawing the shadow from the far pose to rest. */
    private fun fromShadow(b: Motion.ProgramBuilder, p: Path, area: Rect, n: Int, ease: Motion.Ease, seed: Int) {
        val perLens = p.dxL != p.dxR
        val build: Motion.TrackBuilder.() -> Unit = {
            when {
                p.scales -> draw(Motion.Source.New, area.x, area.y, lvl0 = p.level, lvl1 = 15, ease = ease, n = n,
                    w0 = if (p.w != 0) p.w else 0, w1 = if (p.w != 0) area.w else 0, h0 = if (p.h != 0) p.h else 0, h1 = if (p.h != 0) area.h else 0,
                    anchor = 1, hAnchor = 1)
                p.spread != 0 -> pieces(Motion.LAYER_NEW, n, ease, gw = p.gw, gh = p.gh, model = Motion.RADIAL,
                    stagger = if (p.stagger) stagger(n) else 0, x = area.x + area.w / 2, y = area.y + area.h / 2,
                    lvl0 = p.level, lvl1 = 15, f0 = p.spread, f1 = 0)
                p.slices != 0 -> pieces(Motion.LAYER_NEW, n, ease, gw = 1, gh = p.slices, model = Motion.RANDOM, dx = p.tear,
                    lvl0 = p.level, lvl1 = 15, seed = seed, f0 = 4096, f1 = 0, reseed = p.reseed)
                perLens -> draw(Motion.Source.New, area.x + p.dxL, area.y + p.dy, dx = -p.dxL, dy = -p.dy, xR = area.x + p.dxR, dxR = -p.dxR,
                    lvl0 = p.level, lvl1 = 15, ease = ease, n = n)
                else -> draw(Motion.Source.New, area.x + p.dxL, area.y + p.dy, dx = -p.dxL, dy = -p.dy, lvl0 = p.level, lvl1 = 15, ease = ease, n = n)
            }
        }
        if (perLens) b.track(area, area, Motion.Backdrop.Level(0), build) else b.track(area, Motion.Backdrop.Level(0), build)
    }

    private fun easeOf(k: EaseKind): Motion.Ease = when (k) { EaseKind.LINEAR -> Motion.Ease.Linear; EaseKind.OUT -> Motion.Ease.EaseOut; EaseKind.IN -> Motion.Ease.Linear }

    /** Ease-in is ease-out reversed in time — a table (`n u8 · 0 · E[1..n]`): E[j] = ⌊4096·j²/n²⌋, never falling. */
    private fun tableFor(k: EaseKind, n: Int, at4: Int): Motion.Ease.Table? =
        if (k != EaseKind.IN) null else Motion.Ease.Table(at4, IntArray(n) { j -> ((4096L * (j + 1) * (j + 1)) / (n.toLong() * n)).toInt() })

    /** A staggered in half delays each piece by up to about a third of its steps (n' = n − stagger ≥ 1, §7.4). */
    private fun stagger(n: Int): Int = (n / 3).coerceAtMost(n - 1)

    private fun words(bytes: Int) = (bytes + 3) / 4

    /** Further back than the content plane by this much, per lens (Depth). */
    private const val DEPTH_PX = 8
    private const val SLICES = 24
    private const val TEAR_PX = 48
    /** Explode's spread: an edge piece flies 1.5 times its distance from the centre further out. */
    private const val SPREAD = 6144
}
