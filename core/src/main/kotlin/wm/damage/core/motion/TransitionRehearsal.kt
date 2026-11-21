package wm.damage.core.motion

import wm.damage.core.comp.CachedText
import wm.damage.core.comp.GlyphAtlas
import wm.damage.core.comp.TextDraw
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/**
 * Build C3, stage DP2: one transition played on the simulator's model of a C3 build (DamageCaps 0xdff, EFFECTS armed),
 * the way the shell sends it (`FIRMWARE.md` §7.11): the leaving picture on the panel, the records written, the out
 * half's PLAY in slot 2, the arriving frame in [Plan.batches] mode-3 batches held by the owner, PLAY-AFTER of the in half
 * in slot 3 naming slot 2 in the last of them (or [Plan.lateBy] periods after the out half's end, alone), then a wake at
 * every due time until nothing is planned. Context for the reader: Adam's own glasses on his own firmware fork; nothing
 * here contacts a device. `desktop --motion-check` draws its frames; the tests read its checks.
 *
 * C3 DM-T: Encode and Matrix draw from a font in the cache, so for them the rehearsal writes one (a face of its own, its
 * letters and digits as [face] draws them, packed as the atlas packs a face) and writes lines of text in it on both
 * pictures; Encode's cells are those lines' runs ([CellLists]), its out lists written with the records and its in lists
 * after the out PLAY. C3 X4T item 5 (`C3-V2T-review.md` 4, decisions AT (4)): that is the shell's NOT-compressed order —
 * the one it takes when the transport would not deflate the combined message, or the three do not fit its inflate. When
 * they fit and the transport would deflate them, the shell sends the in lists inside the records' write instead (C3 X2T
 * item 2); the rehearsal does not play that form (no pixel differs: the out half never reads the in lists). Encode's
 * conformance vectors come from the shell rig's own images (`ShipVectors`), so the C is compared on both forms.
 */
object TransitionRehearsal {
    class Frame(val label: String, val tick: Long, val l: ByteArray, val r: ByteArray)
    class Result(val built: Transitions.Built, val frames: List<Frame>, val notifies: List<DamageMsg.Motion>, val failures: List<String>,
                 /** The panels after each out step with no frame written (the control run), for the held-frame check. */
                 val control: List<Frame>)

    data class Plan(val effect: Transitions.Effect, val reversed: Boolean, val height: Int, val timing: Transitions.Timing,
                    val batches: Int = 2, val lateBy: Int = -1, val depth: Int = 8)

    const val OUT_SLOT = 2
    const val IN_SLOT = 3
    private const val AT4 = 1024
    /** Encode and Matrix: the records above the rehearsal's font; Encode's lists in the stage reserve of a 256 KiB session
     *  with BIND (`CfwModes.stageReserve`). */
    private const val GLYPH_AT4 = 4096
    private const val CACHE_KIB = 256
    private val RESERVE = CfwModes.stageReserve(CACHE_KIB * 1024, bind = true)

    /** The rehearsal's face: its letters, digits and a few signs, each [FACE_H] px tall, packed as the atlas packs one. */
    private const val FACE_H = 18
    private val faceSpec = FontSpec(Face.SYSTEM, FACE_H)
    private val face: Pair<GlyphAtlas.Entry, ByteArray> by lazy {
        val codes = CellLists.ALNUM + listOf(' '.code, '.'.code, '-'.code)
        val glyphs = codes.associate { c ->
            val ch = c.toChar()
            val w = if (ch == ' ') 6 else 7 + c % 4
            ch to wm.damage.core.wire.TextureCache.Image(w, FACE_H, ByteArray(w * FACE_H) { i ->
                val x = i % w; val y = i / w
                // a glyph-like mark: two stems and the code's bits as bars (a space is empty)
                if (ch == ' ' || y < 3 || y > FACE_H - 4 || x == w - 1) 0
                else if (x == 1 || x == w - 3 || ((c shr (y / 3)) and 1 == 1 && y % 3 == 0)) (9 + (c + y) % 7).toByte() else 0
            })
        }
        val tofu = wm.damage.core.wire.TextureCache.Image(6, FACE_H, ByteArray(6 * FACE_H) { 5 })
        val b = wm.damage.core.wire.TextureCache.Builder(v2 = true, capacity = CACHE_KIB * 1024, maxCache = CACHE_KIB * 1024)
        val font = b.addFont(glyphs, tofu)
        val n = font.glyphOffsets.size
        val images = Array(n) { glyphs[(wm.damage.core.wire.TextureCache.FIRST_CHAR + it).toChar()] ?: tofu }
        val content = b.content()
        GlyphAtlas.Entry(font, images, FACE_H, glyphs, tofu, content.size, BooleanArray(n) { (wm.damage.core.wire.TextureCache.FIRST_CHAR + it).toChar() in glyphs }, content.size) to content
    }

    /** Lines of text in the rehearsal's face on a copy of [g] (Encode's cells come from them), and their runs. */
    private fun withText(g: Gray8, lines: List<String>, h: Int): Pair<Gray8, List<TextDraw>> {
        val e = face.first
        val out = Gray8(g.w, g.h).also { g.pix.copyInto(it.pix) }
        val runs = ArrayList<TextDraw>()
        for ((i, line) in lines.withIndex()) {
            val y = 24 + i * 28
            if (y + FACE_H > h) break
            out.fillRect(Rect(20, y, 600, FACE_H), 0)
            val w = CachedText.blit(out, 24, y, line, e, 255)
            runs += TextDraw(Rect(24, y, w, FACE_H), line, faceSpec, 24, y, 255)
        }
        return out to runs
    }
    /** The rehearsal's font as the cache holds it from offset 0 (the tests' model draws from it too). */
    val fontBytes: ByteArray get() = face.second

    /** Encode's and Matrix's inputs over the [height] area — the rehearsal's face for the rain, the cells of lines of text
     *  written on copies of [old] and [new] — and those two pictures. */
    fun glyphInputs(height: Int, old: Gray8, new: Gray8): Triple<Transitions.Glyphs, Gray8, Gray8> {
        val area = Rect(0, 0, Geometry.PANEL_W, height)
        val (o, oRuns) = withText(old, LEAVING, height); val (n, nRuns) = withText(new, ARRIVING, height)
        fun cells(runs: List<TextDraw>) = CellLists.screen(runs, { face.first }, { { _, _ -> 0 } }, { 0 }, area)
        return Triple(Transitions.Glyphs(CellLists.face(faceSpec, face.first), cells(oRuns), cells(nRuns),
            (CACHE_KIB * 1024 - RESERVE) / 4, RESERVE), o, n)
    }

    private val LEAVING = listOf("THE LEAVING SCREEN 0123", "row one - alpha", "row two - beta 42", "row three - gamma", "the last row 9")
    private val ARRIVING = listOf("Arriving window 4567", "Settings 8 rows", "Reader page 12 of 30", "Music - now playing", "Files 3")

    fun play(plan: Plan, oldPicture: Gray8, newPicture: Gray8): Result {
        // as the shell's screens are: black below the layout's height, the transition's area
        var old = below(oldPicture, plan.height); var new = below(newPicture, plan.height)
        var glyphs: Transitions.Glyphs? = null
        if (plan.effect.glyphs) glyphInputs(plan.height, old, new).let { (g, o, n) -> glyphs = g; old = o; new = n }
        val control = run(plan, old, new, frame = false, glyphs).frames
        val r = run(plan, old, new, frame = true, glyphs)
        val failures = ArrayList(r.failures)
        // the arriving frame shows nothing of itself before the in half: every frame up to the out half's last step
        // equals the control run's (no frame written)
        val outFrames = r.frames.filter { it.label.startsWith("out ") }
        for (f in outFrames) {
            val c = control.firstOrNull { it.label == f.label } ?: continue
            if (!f.l.contentEquals(c.l) || !f.r.contentEquals(c.r)) failures += "${f.label}: the held frame shows on the panel before the in half"
        }
        return Result(r.built, r.frames, r.notifies, failures, control)
    }

    private fun below(g: Gray8, h: Int): Gray8 = Gray8(g.w, g.h).also { c -> g.pix.copyInto(c.pix, 0, 0, g.w * minOf(h, g.h)) }

    private class Run(val built: Transitions.Built, val frames: List<Frame>, val notifies: List<DamageMsg.Motion>, val failures: List<String>)

    private fun run(plan: Plan, old: Gray8, new: Gray8, frame: Boolean, glyphs: Transitions.Glyphs?): Run {
        val failures = ArrayList<String>()
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xdff; it.clockIsOsTick = true }
        var now = 1000L
        val flags = DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE or DamageMsg.FLAG_EFFECTS
        fun send(what: String, m: ByteArray) {
            for (arm in Arm.entries) if (!sim.conformanceMessage(arm, m, now)) failures += "$arm refused $what (refusal record ${sim.refusalRecord(arm)})"
        }
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, CACHE_KIB, now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, flags, now)
        }
        val full = Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)
        send("the leaving picture's keyframe", CfwModes.keyframe(Zl.encodeCfw(Pack.rect(old, full))))
        val effects = Motion.effects(DamageMsg.Caps(2, 0xdff), flags)
        val area = Rect(0, 0, Geometry.PANEL_W, plan.height)
        if (glyphs != null) send("the rehearsal's font", CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(0, face.second)), CACHE_KIB * 1024))
        val at4 = if (glyphs != null) GLYPH_AT4 else AT4
        val b = Transitions.build(effects, plan.effect, plan.reversed, area, plan.timing, at4, CfwModes.TRANSITION_SLOT, plan.depth, glyphs = glyphs)
        // the records' write: the slot's records and, for Encode, the out half's lists, in one message (the shell's form)
        send("the records", CfwModes.cacheUpdate2(listOfNotNull(CfwModes.CacheWrite2(at4, b.blob), b.outLists?.let { CfwModes.CacheWrite2(it.at4, it.bytes) }), CACHE_KIB * 1024))
        val frames = ArrayList<Frame>()
        val notifies = ArrayList<DamageMsg.Motion>()
        fun take(label: String) { frames += Frame(label, now, sim.snapshot(Arm.LEFT), sim.snapshot(Arm.RIGHT)) }
        fun notes() {
            for (n in sim.conformanceNotifies(Arm.RIGHT)) notifies += DamageMsg.parseMotion(Pb.l(DamageMsg.MOTION_FIELD, n))!!
            if (sim.conformanceNotifies(Arm.LEFT).isNotEmpty()) failures += "LEFT sent a notify (only RIGHT can)"
        }
        take("before")
        now = 2000L
        val t0 = now
        val p = plan.timing.period
        val n = plan.timing.steps
        send("the out PLAY", Motion.play(b.out, OUT_SLOT, p, TAG_OUT, 0, IntArray(4)))
        notes(); take("out 1")
        // Encode: the in half's lists after the out PLAY, above the out lists (they meet no range the out half reads) — the
        // shell's not-compressed order (X4T item 5)
        b.inLists?.let { send("the in half's lists", CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(it.at4, it.bytes)), CACHE_KIB * 1024)) }
        val playAfter = Motion.playAfter(b.inn, IN_SLOT, p, TAG_IN, OUT_SLOT, IntArray(4), listOf(Motion.Drawing(b.outCost)), 0)
        // the arriving frame as horizontal bands, one mode-3 batch each (fid 1, 2, ...), the first right after the PLAY,
        // the next after each out step; PLAY-AFTER rides the last band's batch unless the plan makes it late
        val bands = if (frame) plan.batches.coerceIn(1, n) else 0
        var playAfterSent = false
        fun band(i: Int) {
            val h = Geometry.PANEL_H / bands / 2 * 2
            val y0 = i * h
            val r = Rect(0, y0, Geometry.PANEL_W, if (i == bands - 1) Geometry.PANEL_H - y0 else h)
            val delta = CfwModes.delta(r, Zl.encodeCfw(Pack.rect(new, r)), i + 1)
            val last = i == bands - 1 && plan.lateBy < 0
            send("band ${i + 1} of the arriving frame", CfwModes.batch(if (last) listOf(delta, playAfter) else listOf(delta), engine = true))
            if (last) playAfterSent = true
        }
        if (bands > 0) band(0)
        var k = 1
        var guard = 0
        while (guard++ < 8 * n + 16) {
            val waits = Arm.entries.map { sim.conformancePlan(it, now) }
            if (waits.all { it == MotionSim.PLAN_FOREVER } && (playAfterSent || !frame)) break
            val step = if (waits.all { it == MotionSim.PLAN_FOREVER }) p.toLong() else maxOf(1L, waits.filter { it != MotionSim.PLAN_FOREVER }.min())
            now += step
            if (frame && !playAfterSent && plan.lateBy >= 0 && now >= t0 + (n + plan.lateBy).toLong() * p) {
                send("the late PLAY-AFTER", playAfter); playAfterSent = true; notes(); take("late PLAY-AFTER")
            }
            // the out half's steps are the wakes before its body (its step T's wake sends it)
            val outDoneBefore = notifies.any { it.slot == OUT_SLOT && it.outcome == DamageMsg.OUTCOME_DREW }
            for (arm in Arm.entries) sim.conformanceWake(arm, now)
            notes()
            if (!outDoneBefore) { k++; take("out $k"); if (frame && k <= bands) band(k - 1) }
            else take(if (notifies.any { it.slot == IN_SLOT }) "in done" else "in")
        }
        if (guard >= 8 * n + 16) failures += "the plan never ran out (${Arm.entries.map { sim.conformancePlan(it, now) }})"
        if (frame) {
            val out = notifies.filter { it.slot == OUT_SLOT }; val inn = notifies.filter { it.slot == IN_SLOT }
            if (out.size != 1 || out[0].outcome != DamageMsg.OUTCOME_DREW || out[0].drawn != n.toLong())
                failures += "the out half's body: ${out.map { it.describe() }} (want one, every one of $n steps drawn)"
            if (inn.size != 1 || inn[0].outcome != DamageMsg.OUTCOME_DREW || inn[0].drawn != n.toLong())
                failures += "the in half's body: ${inn.map { it.describe() }} (want one, every one of $n steps drawn)"
            for (arm in Arm.entries) {
                if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) failures += "$arm: the panel is not the shadow after the in half's last step"
                val want = Pack.rect(new, full)
                val shadow = if (arm == Arm.LEFT) sim.left.shadow else sim.right.shadow
                if (!shadow.contentEquals(want)) failures += "$arm: the shadow is not the arriving frame"
            }
        }
        return Run(b, frames, notifies, failures)
    }

    private const val TAG_OUT = 0xC001
    private const val TAG_IN = 0xD001
}
