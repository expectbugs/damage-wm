package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.motion.Motion
import wm.damage.core.motion.MotionProgram
import wm.damage.core.motion.TransitionRehearsal
import wm.damage.core.motion.Transitions
import wm.damage.core.sim.MotionSim
import wm.damage.core.wire.DamageMsg

/**
 * Build C3, stage DP2: the transitions' programs on the simulator's model (`FIRMWARE.md` §7.2–§7.7, §7.12). Context for
 * the reader: Adam's own glasses on his own firmware fork; nothing here contacts a device.
 *
 * Pins: every effect's in half lands the arriving frame exactly and a two-batch frame shows nothing of itself before it
 * (the rehearsal on `GlassFirmwareSim`, both lenses); a late frame holds the out pose; each Back is its Select reversed in
 * time (what the picture does, step by step); the fold invariant on every out program (two wake schedules, one panel);
 * the step budget's headroom per effect at the four heights.
 */
class TransitionsTest {
    private val fx = Motion.effects(DamageMsg.Caps(2, 0xdff), DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE or DamageMsg.FLAG_EFFECTS)
    private val stride = Geometry.PANEL_W / 2

    /** Every (tier, effect, reversed) a shell can play: Select forward and Back reversed for Select's six; Lock forward
     *  (entering the silent clock) and reversed (leaving it) for its four. */
    private val plays: List<Triple<Transitions.Tier, Transitions.Effect, Boolean>> =
        Transitions.effectsOf(Transitions.Tier.SELECT).flatMap { listOf(Triple(Transitions.Tier.SELECT, it, false), Triple(Transitions.Tier.BACK, it, true)) } +
            Transitions.effectsOf(Transitions.Tier.LOCK).flatMap { listOf(Triple(Transitions.Tier.LOCK, it, false), Triple(Transitions.Tier.LOCK, it, true)) }
    /** The plays whose programs are paths (C3 DM-T: Encode and Matrix draw from the atlas and have rows of their own below). */
    private val pathPlays = plays.filter { !it.second.glyphs }

    /** A leaving screen: bars like a list's rows, a band, a rail — every level somewhere. */
    private fun oldScreen(): Gray8 = Gray8(Geometry.PANEL_W, Geometry.PANEL_H).also { g ->
        for (r in 0 until 14) g.fillRect(Rect(32, 40 + r * 32, 400 + (r * 37) % 160, 22), ((r % 12) + 4) * 17)
        g.fillRect(Rect(24, 200, 592, 64), 2 * 17)
        g.fillRect(Rect(612, 34, 6, 300), 9 * 17)
    }

    /** An arriving screen: a grid of boxes at many levels. */
    private fun newScreen(): Gray8 = Gray8(Geometry.PANEL_W, Geometry.PANEL_H).also { g ->
        for (j in 0 until 7) for (i in 0 until 9) g.fillRect(Rect(16 + i * 68, 24 + j * 64, 56, 48), (((i + 2 * j) % 15) + 1) * 17)
    }

    @Test
    fun everyEffectsInHalfLandsTheArrivingFrameAndHidesItUntilThen_DP2() {
        val timing = Transitions.timing(450, 10)
        val failures = ArrayList<String>()
        for ((tier, e, rev) in plays) for (h in listOf(288, 352, 416, 480)) {
            val r = TransitionRehearsal.play(TransitionRehearsal.Plan(e, rev, h, timing, batches = 2), oldScreen(), newScreen())
            failures += r.failures.map { "${tier.word} ${e.label}${if (rev) " reversed" else ""} at $h: $it" }
            assertTrue(r.frames.size >= 2 * timing.steps, "${e.label} at $h: ${r.frames.size} frames")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** "A window not ready holds the out half's last pose" (`MOTION.md` §7): the PLAY-AFTER two periods after the out
     *  half's end — every frame between shows the out half's last pose; then the in half lands the frame. */
    @Test
    fun aLateFrameHoldsTheOutPose_DP2() {
        val timing = Transitions.timing(450, 10)
        for (e in listOf(Transitions.Effect.FALL, Transitions.Effect.WARP, Transitions.Effect.FADE)) {
            val r = TransitionRehearsal.play(TransitionRehearsal.Plan(e, false, 352, timing, batches = 1, lateBy = 2), oldScreen(), newScreen())
            assertTrue(r.failures.isEmpty(), "${e.label}: ${r.failures}")
            val last = r.frames.last { it.label == "out ${timing.steps}" }
            val held = r.frames.dropWhile { it !== last }.drop(1).takeWhile { it.label != "late PLAY-AFTER" }
            assertTrue(held.isNotEmpty(), "${e.label}: frames between the out half's end and the late PLAY-AFTER: ${r.frames.map { it.label }}")
            for (f in held) assertTrue(f.l.contentEquals(last.l) && f.r.contentEquals(last.r), "${e.label}: ${f.label} at tick ${f.tick} moved off the out pose")
        }
    }

    // ------------------------------------------------------------------------------------- the model's paint
    private fun program(p: MotionProgram): Pair<MotionSim, MotionSim.Instance> {
        val cache = ByteArray(256 * 1024)
        TransitionRehearsal.fontBytes.copyInto(cache, 0)          // C3 DM-T: what Encode's and Matrix's glyphs are drawn from
        for ((o4, b) in p.records) b.copyInto(cache, o4 * 4)
        val m = MotionSim(scaled = { true }, effects = { true })
        val prog = m.validate(cache, p.at, IntArray(4), MotionSim.Op.PLAY, armed = true) as? MotionSim.Prog
            ?: error("the model refuses the program the DSL built")
        return m to MotionSim.Instance(2, 0x1234, Motion.PERIOD_30HZ, p.at, prog, null, arrayOfNulls(prog.tracks.size))
    }

    /** Step [k] of [p] on one lens: an out program painted in place over [base] from base step [b]; an in program over
     *  anything (it paints its whole area) with [shadow] as `new`. */
    private fun step(p: MotionProgram, k: Int, base: ByteArray, shadow: ByteArray, left: Boolean, b: Int = 0): ByteArray {
        val (m, o) = program(p)
        val buf = base.copyOf()
        m.paint(o, k, buf, stride, left, shadow, b)
        return buf
    }

    private fun lvl(buf: ByteArray, x: Int, y: Int): Int = (buf[y * stride + (x shr 1)].toInt() and 0xFF).let { if (x and 1 == 0) it shr 4 else it and 0xF }

    /** What the picture does at a step: its ink's bounding box in [area] (null: no ink) and its brightest level. */
    private data class Pose(val box: Rect?, val top: Int)
    private fun pose(buf: ByteArray, area: Rect): Pose {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1; var top = 0
        for (y in area.y until area.bottom) for (x in area.x until area.right) {
            val v = lvl(buf, x, y)
            if (v != 0) { x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y); top = maxOf(top, v) }
        }
        return Pose(if (x1 < 0) null else Rect(x0, y0, x1 - x0 + 1, y1 - y0 + 1), top)
    }

    /**
     * "Close/Back: each Select effect in reverse" (`MOTION.md` §7): on the model, Back's out half at step n − j over a
     * picture does what Select's in half does to that picture at step j, and Back's in half at step j what Select's out
     * half does at step n − j — the ink's bounding box per lens (within the two renderers' sampling: a nested shrink keeps
     * other rows than a scaled draw, so 2 px on Warp and Flip, 1 elsewhere) and its brightest level (within 1: a composed
     * dim floors at each step, a level ramp once). Glitch's slices tear by other hashes on the way back (Select's in half
     * re-deals them every step, which an in-place half cannot), so for it the level and the rows; Explode's pieces fly the
     * same lines both ways. The same holds for leaving the silent clock against entering it, Build's staggered arrival
     * aside (an in-place half has no stagger: its end points are compared).
     */
    @Test
    fun eachBackIsItsSelectReversedInTime_DP2() {
        val timing = Transitions.timing(450, 10)
        val n = timing.steps
        val area = Rect(0, 0, Geometry.PANEL_W, 352)
        val lit = Gray8(Geometry.PANEL_W, Geometry.PANEL_H).also { it.fillRect(area, 15 * 17) }
        val pic = Pack.rect(lit, Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H))
        val black = ByteArray(pic.size)
        val pairs = (Transitions.effectsOf(Transitions.Tier.SELECT).map { Transitions.Tier.SELECT to it } + Transitions.effectsOf(Transitions.Tier.LOCK).map { Transitions.Tier.LOCK to it })
            .filter { !it.second.glyphs }                 // C3 DM-T: Encode's and Matrix's reversal has its own row
        val failures = ArrayList<String>()
        for ((tier, e) in pairs) {
            val fwd = Transitions.build(fx, e, false, area, timing, 1024, 2048)
            val rev = Transitions.build(fx, e, true, area, timing, 1024, 2048)
            val tol = when (e) { Transitions.Effect.WARP, Transitions.Effect.FLIP -> 2; else -> 1 }
            val js = if (e == Transitions.Effect.BUILD) listOf(0, n) else (0..n).toList()
            for (left in listOf(true, false)) for (j in js) {
                // Back's out half at n − j against Select's in half at j (both over the lit picture)
                val a = pose(if (n - j == 0) pic else step(rev.out, n - j, pic, black, left), area)
                val b = pose(step(fwd.inn, j, black, pic, left), area)
                failures += compare("${tier.word} ${e.label}: Back out @${n - j} vs Select in @$j (${if (left) "L" else "R"})", a, b, tol, e)
                // Back's in half at j against Select's out half at n − j
                val c = pose(step(rev.inn, j, black, pic, left), area)
                val d = pose(if (n - j == 0) pic else step(fwd.out, n - j, pic, black, left), area)
                failures += compare("${tier.word} ${e.label}: Back in @$j vs Select out @${n - j} (${if (left) "L" else "R"})", c, d, tol, e)
            }
        }
        assertTrue(failures.isEmpty(), failures.take(40).joinToString("\n") + if (failures.size > 40) "\n… ${failures.size} in all" else "")
    }

    private fun compare(what: String, a: Pose, b: Pose, tol: Int, e: Transitions.Effect): List<String> {
        val out = ArrayList<String>()
        if (kotlin.math.abs(a.top - b.top) > 1) out += "$what: brightest level ${a.top} vs ${b.top}"
        if (a.top <= 1 || b.top <= 1) return out                    // all but dark: its box says nothing
        val (x, y) = a.box to b.box
        if (x == null || y == null) { if (x != y) out += "$what: ink ${x} vs ${y}"; return out }
        val rowsOnly = e == Transitions.Effect.GLITCH
        fun near(p: Int, q: Int) = kotlin.math.abs(p - q) <= tol
        if (!near(x.y, y.y) || !near(x.bottom, y.bottom) || (!rowsOnly && (!near(x.x, y.x) || !near(x.right, y.right))))
            out += "$what: ink $x vs $y"
        return out
    }

    /**
     * §7.2's fold invariant on every out program (Select's, Back's, Lock's both ways): drawn at every step
     * (each from the step before as its base) and drawn at every second step and the last (each from the step it drew
     * before), the panel is the same at every step both drew and at the end — both lenses, over a busy picture.
     */
    @Test
    fun theFoldInvariantHoldsOnEveryOutProgram_DP2() {
        val timing = Transitions.timing(450, 12)
        val n = timing.steps
        val area = Rect(0, 0, Geometry.PANEL_W, 416)
        val pic = Pack.rect(oldScreen(), Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H))
        // C3 DM-T: Encode's and Matrix's out halves too, over the rehearsal's font (the model draws their glyphs from it)
        val outs = pathPlays.map { (t, e, rev) -> "${t.word} ${e.label}${if (rev) " reversed" else ""}" to Transitions.build(fx, e, rev, area, timing, 1024, 2048).out } +
            plays.filter { it.second.glyphs }.map { (t, e, rev) -> "${t.word} ${e.label}${if (rev) " reversed" else ""}" to glyphBuilt(e, rev, area.h, timing).out }
        for ((name, p) in outs) for (left in listOf(true, false)) {
            val t = p.T
            val every = HashMap<Int, ByteArray>()
            var buf = pic.copyOf()
            for (k in 1..t) { buf = step(p, k, buf, pic, left, b = k - 1); every[k] = buf }
            var b2 = pic.copyOf(); var last = 0
            val skips = (1..t).filter { it % 2 == 0 || it == t }
            for (k in skips) {
                b2 = step(p, k, b2, pic, left, b = last); last = k
                assertTrue(b2.contentEquals(every[k]!!), "$name (${if (left) "L" else "R"}): step $k drawn from step ${skips.getOrNull(skips.indexOf(k) - 1) ?: 0} differs from every step drawn")
            }
            val once = step(p, t, pic, pic, left, b = 0)
            assertTrue(once.contentEquals(every[t]!!), "$name (${if (left) "L" else "R"}): step T drawn at once differs from every step drawn")
        }
        assertEquals(n, Transitions.build(fx, Transitions.Effect.FALL, false, area, timing, 1024, 2048).out.T)
    }

    /**
     * §7.12: each effect's two programs at the four heights — their C (u) and the larger, which a PLAY-AFTER waiting on
     * its out half is judged by — against the cap. Every one keeps at least 20 % of it (the contract's own margin for
     * the designed programs). The table is printed for the report.
     */
    @Test
    fun theStepBudgetsHeadroomPerEffectAtTheFourHeights_DP2() {
        val timing = Transitions.timing(450, 10)
        val rows = StringBuilder("effect, direction | 288 | 352 | 416 | 480 (C out / in, M u) | headroom, worst\n")
        for ((tier, e, rev) in pathPlays) {
            var worst = 0L
            val cells = listOf(288, 352, 416, 480).map { h ->
                val b = Transitions.build(fx, e, rev, Rect(0, 0, Geometry.PANEL_W, h), timing, 1024, 2048)
                val chain = Motion.stepSum(listOf(Motion.Drawing(b.outCost), Motion.Drawing(b.inCost, waitsOn = 0)))
                assertEquals(maxOf(b.outCost, b.inCost), chain, "the in half waits on the out half: the larger")
                worst = maxOf(worst, chain)
                "%.2f / %.2f".format(b.outCost / 1e6, b.inCost / 1e6)
            }
            val head = 100.0 * (Motion.BUDGET_CAP - worst) / Motion.BUDGET_CAP
            rows.append("${tier.word} ${e.label}${if (rev) " reversed" else ""} | ${cells.joinToString(" | ")} | %.0f %%\n".format(head))
            assertTrue(worst <= Motion.BUDGET_CAP * 8 / 10, "${e.label}: ${worst} u keeps less than 20 % of the cap")
        }
        println("DP2 budget table:\n$rows")
    }

    /** The records fit one slot for every play at every height and frame count, and the tier timings are as ruled:
     *  450 ms at 10 frames is 5 steps a half at 46 ticks (45 ms). */
    @Test
    fun theRecordsFitTheirSlotAndTheTimingsAreAsRuled_DP2() {
        assertEquals(Transitions.Timing(5, 46), Transitions.timing(450, 10))
        assertEquals(5, Transitions.timing(450, 0).steps, "Auto at 450 ms: 10 frames")
        for (f in listOf(0, 4, 6, 8, 10, 12, 16)) for (len in listOf(225, 300, 450, 600, 800)) for ((_, e, rev) in pathPlays) for (h in listOf(288, 480)) {
            val b = Transitions.build(fx, e, rev, Rect(0, 0, Geometry.PANEL_W, h), Transitions.timing(len, f), 1024, wm.damage.core.wire.CfwModes.TRANSITION_SLOT)
            assertTrue(b.blob.size <= wm.damage.core.wire.CfwModes.TRANSITION_SLOT)
        }
    }

    /**
     * X2P2 item 6 (the one-step freeze removed): no shipped effect needs it — at every height, every length of the tier
     * rows' ladder and every frame count they offer (Auto included), and both values the Depth effect's offset takes
     * (the content's Depth 0..8 and 12..16), every play's two programs are taken offline exactly as the shell asks for
     * them: built into one record slot, the out half's PLAY and the in half's PLAY-AFTER judged by the step budget with
     * the out half still drawing. So no setting leaves an in half without its out half. Printed for the lead (item 7):
     * what each length and frame count really plays at — the period is clamped to 16..128 ticks, so a long length with
     * few frames plays shorter than set.
     */
    @Test
    fun everyPlaysTwoHalvesAreTakenAtEveryHeightLengthAndFrameCount_X2P2() {
        val refused = ArrayList<String>()
        var checked = 0
        for (len in wm.damage.core.shell.ShellSettings.SCROLL_LENGTHS) for (f in wm.damage.core.shell.ShellSettings.FRAME_COUNTS) {
            val timing = Transitions.timing(len, f)
            for ((tier, e, rev) in pathPlays) for (h in listOf(288, 352, 416, 480)) for (depth in listOf(0, 16)) {
                val what = "${tier.word} ${e.label}${if (rev) " reversed" else ""} at $h, $len ms, ${if (f == 0) "Auto" else "$f"} frames, depth $depth"
                try {
                    val b = Transitions.build(fx, e, rev, Rect(0, 0, Geometry.PANEL_W, h), timing, 1024, wm.damage.core.wire.CfwModes.TRANSITION_SLOT, depth, seed = len * 31 + f)
                    Motion.play(b.out, 2, timing.period, 0xC000, 0, IntArray(4))
                    Motion.playAfter(b.inn, 3, timing.period, 0xD000, 2, IntArray(4), listOf(Motion.Drawing(b.out.cost())), 0)
                    checked++
                } catch (x: wm.damage.core.geom.LintError) { refused += "$what: ${x.message}" }
            }
        }
        assertTrue(refused.isEmpty(), "${refused.size} refused:\n" + refused.take(20).joinToString("\n"))
        assertEquals(5 * 7 * pathPlays.size * 4 * 2, checked)
        val table = StringBuilder("length \\ frames | " + wm.damage.core.shell.ShellSettings.FRAME_COUNTS.joinToString(" | ") { if (it == 0) "Auto" else "$it" } +
            "   (each cell: what a transition plays, 2 × T × P, in ms — T steps a half at P ticks)\n")
        for (len in wm.damage.core.shell.ShellSettings.SCROLL_LENGTHS) {
            table.append("$len ms | ")
            table.append(wm.damage.core.shell.ShellSettings.FRAME_COUNTS.joinToString(" | ") { f ->
                val t = Transitions.timing(len, f); "${2 * t.halfMs} (${t.steps} × ${t.period})" })
            table.append("\n")
        }
        println("X2P2 item 7, what each length and frame count plays (the same at every height):\n$table")
        assertEquals(500L, 2 * Transitions.timing(800, 4).halfMs, "the review's example: 800 ms at 4 frames plays 500 ms")
    }

    /** DP2 item 3: the tier rows follow the scroll rows in `Animations` only where stage 5 is in force; Select 450 ms / 10
     *  frames with Depth checked by default (Back the same, Lock Fade); a checkbox toggles one effect; an older record
     *  reads the defaults and an unknown effect id is dropped. */
    @Test
    fun theTierRowsShowOnlyAtStage5WithTheRuledDefaults_DP2() {
        var st = wm.damage.core.shell.ShellSettings()
        fun w(effects: Boolean) = wm.damage.core.shell.SettingsWindow(FakeText(), { st }, { st = it }, bindBuild = { true },
            motionHint = { "10 frames × 45 ms" }, effectsInForce = { effects })
        // K1-1's Scroll tier rows (`private/build083/K1-1-built.md`: `Scroll effect`, `Scroll length`, `Scroll frames`) and
        // K1-4a's notice box rows (the Popups tier) on a bit-10 build; at stage 5 the transition tiers go between them —
        // `MOTION.md` §7's order: Scroll, Select, Close / Back, Lock Screen, Popups (decisions BW (3)). C3 AS: the page is
        // one row per tier and each tier's rows are its sub menu
        val scrollRows = listOf("Scroll effect", "Scroll length", "Scroll frames")
        val popupRows = listOf("Popup length", "Popup frames", "Popup · Unfurl", "Popup · Fade", "Popup · Slide")
        assertEquals(listOf("Scroll", "Popups"), w(false).rowsForTest("Animations").map { it.first }, "no stage 5: the scroll and notice tiers only")
        assertEquals(listOf("Scroll", "Select", "Close / Back", "Lock Screen", "Popups"), w(true).rowsForTest("Animations").map { it.first })
        assertEquals(scrollRows, w(true).rowsForTest("Animations", "Scroll").map { it.first }, "the Scroll tier's rows")
        assertEquals(popupRows, w(true).rowsForTest("Animations", "Popups").map { it.first }, "the Popups tier's rows")
        fun tiers() = listOf("Select", "Close / Back", "Lock Screen").flatMap { w(true).rowsForTest("Animations", it) }
        val rows = w(true).rowsForTest("Animations", "Scroll") + tiers()
        val names = tiers().map { it.first }
        // C3 DM-T: Encode and Matrix in `MOTION.md` §7's order (Select: … Glitch, Encode, Matrix, Explode; Lock: Fade,
        // Build, Matrix, Encode, Glitch, Explode)
        // C3 X2T item 7: the tiers as his plan names them — "Close / Back", "Lock Screen"
        assertEquals(listOf("Select length", "Select frames", "Select · Depth", "Select · Warp", "Select · Flip", "Select · Fall", "Select · Glitch", "Select · Encode", "Select · Matrix", "Select · Explode",
            "Close / Back length", "Close / Back frames", "Close / Back · Depth", "Close / Back · Warp", "Close / Back · Flip", "Close / Back · Fall", "Close / Back · Glitch", "Close / Back · Encode", "Close / Back · Matrix", "Close / Back · Explode",
            "Lock Screen length", "Lock Screen frames", "Lock Screen · Fade", "Lock Screen · Build", "Lock Screen · Matrix", "Lock Screen · Encode", "Lock Screen · Glitch", "Lock Screen · Explode"), names)
        val v = rows.toMap()
        // every tier row says "(default)" at its default, as Scroll's rows do (X2T item 7), the scroll rows included
        assertEquals("450 ms (default)", v["Select length"]); assertEquals("10 (5 × 45 ms a half) (default)", v["Select frames"])
        assertEquals("auto (10 frames × 45 ms) (default)", v["Scroll frames"])
        assertEquals("on (default)", v["Select · Depth"]); assertEquals("off (default)", v["Select · Warp"])
        assertEquals("on (default)", v["Close / Back · Depth"]); assertEquals("on (default)", v["Lock Screen · Fade"]); assertEquals("off (default)", v["Lock Screen · Encode"])
        st = st.withTier(Transitions.Tier.SELECT, effects = wm.damage.core.shell.ShellSettings.tierEffects(Transitions.Tier.SELECT, st.selectEffects + "warp"))
        assertEquals(listOf("depth", "warp"), st.selectEffects)
        assertEquals("on", tiers().toMap()["Select · Warp"], "a checkbox off its default says no default")
        assertEquals("on (default)", tiers().toMap()["Select · Depth"])
        st = st.withTier(Transitions.Tier.SELECT, effects = listOf("warp"))
        assertEquals("off", tiers().toMap()["Select · Depth"])
        // the Length row says what really plays when the period's clamp changes it (decisions AG (4); C3-X2P2-built.md item 7's
        // table): 600 ms at 4 frames plays 500, 800 at 6 plays 750, 225 at 16 plays 250; 450 at 10 is only rounded (448)
        for ((lf, want) in listOf((600 to 4) to "600 ms (plays 500 ms)", (800 to 6) to "800 ms (plays 750 ms)", (225 to 16) to "225 ms (plays 250 ms)",
                (450 to 10) to "450 ms (default)", (600 to 10) to "600 ms", (800 to 0) to "800 ms")) {
            st = st.withTier(Transitions.Tier.BACK, length = lf.first, frames = lf.second)
            assertEquals(want, tiers().toMap()["Close / Back length"], "length ${lf.first} at ${lf.second} frames")
        }
        assertEquals(500L, Transitions.clampedPlayMs(600, 4)); assertEquals(null, Transitions.clampedPlayMs(450, 10)); assertEquals(250L, Transitions.clampedPlayMs(225, 16))
        st = st.withTier(Transitions.Tier.BACK, length = 450, frames = 10)
        fun stored(vararg ids: String) = wm.damage.core.shell.ShellSettings.fromJson(kotlinx.serialization.json.buildJsonObject {
            put("lockEffects", kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) })) })
        val old = stored("matrix", "build")
        assertEquals(Triple(450, 10, listOf("depth")), Triple(old.selectLength, old.selectFrames, old.selectEffects))
        assertEquals(listOf("build", "matrix"), old.lockEffects, "C3 DM-T: Matrix is drawn now; the tier's order")
        assertEquals(listOf("build"), stored("sparkle", "build").lockEffects, "an id this build does not draw is dropped")
        assertEquals(listOf("fade"), stored("sparkle").lockEffects, "a stored value naming nothing this build draws reads the defaults")
        assertEquals(emptyList(), stored().lockEffects, "an empty one stays None")
    }

    // ------------------------------------------------------------------------------------- C3 DM-T: Encode and Matrix
    /** Encode or Matrix over the rehearsal's font and lines of text, the records from off4 4096 (above the font). */
    private fun glyphBuilt(e: Transitions.Effect, rev: Boolean, h: Int, timing: Transitions.Timing): Transitions.Built {
        val (g, _, _) = TransitionRehearsal.glyphInputs(h, oldScreen(), newScreen())
        return Transitions.build(fx, e, rev, Rect(0, 0, Geometry.PANEL_W, h), timing, 4096, wm.damage.core.wire.CfwModes.TRANSITION_SLOT, glyphs = g)
    }

    /**
     * Back is Select reversed (the DM spec §5.1): Matrix's rain falls one way (S1), so its reversed pair is its own — the
     * same records byte for byte; Encode's reversed out half keeps the leaving picture whole (the dim's level held at 15)
     * while its letters go, and its reversed in half brings the shadow up from black (DRAW `new`, level 0 → 15) while the
     * arriving letters come — Select's halves run backwards.
     */
    @Test
    fun encodeAndMatrixBackIsSelectReversed_DMT() {
        val timing = Transitions.timing(450, 10)
        for (h in listOf(288, 352, 416, 480)) {
            val m = glyphBuilt(Transitions.Effect.MATRIX, false, h, timing); val mr = glyphBuilt(Transitions.Effect.MATRIX, true, h, timing)
            assertTrue(m.blob.contentEquals(mr.blob), "Matrix at $h: Back's records are Select's")
            val e = glyphBuilt(Transitions.Effect.ENCODE, false, h, timing); val er = glyphBuilt(Transitions.Effect.ENCODE, true, h, timing)
            val dim = e.out.tracks[0].segments[0]; val held = er.out.tracks[0].segments[0]
            assertEquals(Motion.PIECES to (15 to 0), dim.verb to (dim.lvl0 to dim.lvl1), "Encode at $h: Select's out half dims")
            assertEquals(Motion.PIECES to (15 to 15), held.verb to (held.lvl0 to held.lvl1), "Encode at $h: Back's out half keeps the picture")
            assertTrue(e.inn.tracks[0].backdrop is Motion.Backdrop.New && e.inn.tracks[0].segments[0].verb == Motion.WAIT, "Encode at $h: Select's in half is the shadow")
            val up = er.inn.tracks[0].segments[0]
            assertTrue(er.inn.tracks[0].backdrop is Motion.Backdrop.Level && up.verb == Motion.DRAW && up.lvl0 == 0 && up.lvl1 == 15, "Encode at $h: Back's in half from black")
            // the cells are the same lists both ways: the leaving screen's in the out half, the arriving screen's in the in half
            assertTrue(e.outLists!!.bytes.contentEquals(er.outLists!!.bytes) && e.inLists!!.bytes.contentEquals(er.inLists!!.bytes), "Encode at $h: the same lists")
            // the in lists lie above the out lists, inside the stage reserve
            assertTrue(e.inLists!!.at4 >= e.outLists!!.at4 + e.outLists!!.bytes.size / 4, "Encode at $h: the in lists above the out lists")
        }
    }

    /**
     * The DM spec §3's table, on the DSL (I: glyphs of 12 × 20 with an RLE of the face's own, the cells as §7.12 designs them —
     * typical 160 / 200 / 240 / 280, dense a Reader page of 24-px lines of 45 glyphs): each half's C at the four heights
     * stays within 80 % of the cap, the glyph walk inside decisions AF's bound, and "the length holds, frames give": at
     * 450 ms / 10 frames a dense page at 480 plays 6 frames. Printed for the report.
     */
    @Test
    fun encodeAndMatrixBudgetsAndTimingsAtTheFourHeights_DMT() {
        val glyphs = (('A'..'Z') + ('a'..'z') + ('0'..'9')).associateWith { ch ->
            wm.damage.core.wire.TextureCache.Image(12, 20, ByteArray(240) { i -> (((i * 7 + ch.code) % 5) * 3 + (i / 12) % 2).toByte() })
        }
        val tofu = wm.damage.core.wire.TextureCache.Image(6, 20, ByteArray(120) { 5 })
        val builder = wm.damage.core.wire.TextureCache.Builder(v2 = true, capacity = 160 * 1024)
        val font = builder.addFont(glyphs, tofu)
        val entry = wm.damage.core.comp.GlyphAtlas.Entry(font, Array(224) { glyphs[(32 + it).toChar()] ?: tofu }, 20, glyphs, tofu,
            builder.content().size, BooleanArray(224) { (32 + it).toChar() in glyphs }, builder.content().size)
        val spec = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.READER, 20)
        val face = wm.damage.core.motion.CellLists.face(spec, entry)!!
        val lg = glyphs.values.maxOf { it.encode().size - 2 }
        val rows = StringBuilder("glyphs 12×20, RLE up to $lg B\nH | screen | cells (lists) | C out / in (M u, % of cap) | step (I) | P | frames at 450 ms / 10 | walk out / in\n")
        for ((h, typical) in listOf(288 to 160, 352 to 200, 416 to 240, 480 to 280)) {
            val area = Rect(0, 0, Geometry.PANEL_W, h)
            for ((name, cells) in listOf("typical" to typical, "dense" to ((h - 32) / 24) * 45)) {
                val runs = (0 until (cells + 44) / 45).map { i ->
                    val n = minOf(45, cells - 45 * i)
                    wm.damage.core.comp.TextDraw(Rect(8, 8 + 24 * i % (h - 28), 12 * n, 20), "Ab3".repeat(15).take(n), spec, 8, 8 + (24 * i) % (h - 28), 255)
                }
                val screen = wm.damage.core.motion.CellLists.screen(runs, { entry }, { { _, _ -> 0 } }, { 0 }, area)
                val g = Transitions.Glyphs(face, screen, screen, (256 * 1024 - 32 * 1024) / 4, 32 * 1024)
                val t = Transitions.timingFor(Transitions.Effect.ENCODE, 450, 10, area, g.cells)
                val b = Transitions.build(fx, Transitions.Effect.ENCODE, false, area, t, 4096, wm.damage.core.wire.CfwModes.TRANSITION_SLOT, glyphs = g)
                assertTrue(b.withinShare, "Encode $name at $h: ${b.outCost} / ${b.inCost} u within 80 %")
                assertTrue(maxOf(b.out.walkBytes(), b.inn.walkBytes()) <= Motion.WALK_BYTES_MAX)
                val lists = (screen as wm.damage.core.motion.CellLists.Screen.Cells).lists.size
                rows.append("$h | Encode $name | ${g.cells} ($lists) | %.2f / %.2f (%.0f %%) | %.1f ms | ${t.period} | ${2 * t.steps} | ${b.out.walkBytes()} / ${b.inn.walkBytes()} B\n".format(
                    b.outCost / 1e6, b.inCost / 1e6, 100.0 * maxOf(b.outCost, b.inCost) / Motion.BUDGET_CAP,
                    0.5 + 0.036e-3 * area.w * area.h + 0.058 * g.cells))
                if (h == 480 && name == "dense") assertEquals(6, 2 * t.steps, "a dense page at 480: the length holds, frames give")
            }
            val mg = Transitions.Glyphs(rain = face)
            val mt = Transitions.timingFor(Transitions.Effect.MATRIX, 450, 10, area)
            val m = Transitions.build(fx, Transitions.Effect.MATRIX, false, area, mt, 4096, wm.damage.core.wire.CfwModes.TRANSITION_SLOT, glyphs = mg)
            assertTrue(m.withinShare, "Matrix at $h: ${m.outCost} / ${m.inCost} u within 80 %")
            rows.append("$h | Matrix | ${Transitions.rainCols(area)} × ${Transitions.RAIN_TRAIL} lit | %.2f / %.2f (%.0f %%) | %.1f ms | ${mt.period} | ${2 * mt.steps} | ${m.out.walkBytes()} / ${m.inn.walkBytes()} B\n".format(
                m.outCost / 1e6, m.inCost / 1e6, 100.0 * maxOf(m.outCost, m.inCost) / Motion.BUDGET_CAP,
                0.5 + 0.036e-3 * area.w * area.h + 0.065 * Transitions.rainCols(area) * Transitions.RAIN_TRAIL))
        }
        println("DM-T budget table (modeled):\n$rows")
    }
}
