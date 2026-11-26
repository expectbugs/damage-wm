package wm.damage.core.windows.music

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.motion.Motion
import wm.damage.core.shell.WindowLanes
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/**
 * Music's motion update, stage S (ruling D / 13): Scope's lane played on the simulator's model of a C3 build (DamageCaps
 * 0xdff, EFFECTS armed) at 1×, for the lead's look before the glasses (`desktop --motion-check`, `music-scope-*`) and for
 * `ScopeLaneTest`. Context for the reader: Adam's own glasses on his own firmware fork; nothing here contacts a device,
 * and the envelopes are made up ([scenes]: never a file of his).
 *
 * The chain as the lane sends it: each stretch planned and laid out by the product's own builder
 * ([MusicMotion.scopeLanes], [WindowLanes.area] — the same bytes), its area written through the ring of three, the first
 * stretch's PLAY in slot 0 and each next one's PLAY-AFTER into the other slot once the one before has half its steps
 * left, its step 1 planned at the one before's end; the model woken at every due step. **Checks**, each a failure: every
 * step on both lenses is today's Scope (`ScopeViz.paint`) panned by a whole pixel — the background, the zero line and
 * every column — at a place within [PLACE_TOL_MS] of the song's at that step's time; every stretch drew all its steps.
 */
object ScopeRehearsal {
    class Frame(val label: String, val tick: Long, val l: ByteArray, val r: ByteArray)
    class Scene(val name: String, val data: VizData, val fromMs: Long, val height: Int)
    class Result(val scene: Scene, val strip: Rect, val steps: Int, val frames: List<Frame>, val checked: Int,
                 /** Each area write: (stretch, raw mode-19 bytes, deflated mode-25 bytes). */
                 val writes: List<Triple<Int, Int, Int>>, val notes: List<String>, val failures: List<String>) {
        /** Both lenses' strip: the nominal strip widened by the disparity each way. */
        val crop: Rect get() = Rect(strip.x - D, strip.y, strip.w + 2 * D, strip.h)
    }

    /** The plane's disparity the strip is played at (left lens x − 4, right x + 4). */
    const val D = 4
    /** The place a step shows may differ from the song's at its time by the engine's rounding of the offset (a pixel is
     *  5 ms) and the anchor's: 2 px. */
    const val PLACE_TOL_MS = 10L
    private const val CACHE_KIB = 256
    private const val TAG0 = WindowLanes.TAG + 0x100

    /** Music Mode's strip at a height (`MusicGlasses.mmLayout`: 16 px in, 8 px up from the safe area's bottom, 64 px
     *  tall from 416 up, else 48). */
    fun strip(height: Int): Rect {
        val h = if (height >= 416) 64 else 48
        return Rect(Geometry.snapX(16), Geometry.snapY(Geometry.snapY(height - 8) - h), Geometry.snapX(640 - 32), h)
    }

    /** An RMS envelope of [levels] (0..15, one per 20-ms slot) as `VizData` packs it. */
    fun envelope(levels: IntArray): VizData {
        val rms = ByteArray((levels.size + 1) / 2)
        for ((i, v) in levels.withIndex()) rms[i shr 1] = (rms[i shr 1].toInt() or (if (i and 1 == 0) v shl 4 else v)).toByte()
        return VizData(0, 0, 0, ByteArray(0), levels.size, rms, IntArray(0))
    }

    /** The rehearsal's scenes: a sweep from the song's start (no column before it), a loud passage cut by three seconds
     *  of silence, a loud passage at 0:30, and the sweep at 352 (the 48-px strip). */
    fun scenes(): List<Scene> {
        val sweep = envelope(IntArray(1_000) { i -> val ph = i % 300; if (ph < 150) 1 + ph * 14 / 149 else 15 - (ph - 150) * 14 / 149 })
        val gap = envelope(IntArray(1_000) { i -> if (i in 150..299) 0 else 9 + (i * 7 + i / 3) % 7 })
        val loud = envelope(IntArray(3_000) { i -> 10 + ((i * 2654435761L) ushr 28 and 5).toInt() })
        return listOf(Scene("sweep", sweep, 0, 480), Scene("silence", gap, 2_000, 480), Scene("loud", loud, 30_000, 480), Scene("sweep-h352", sweep, 0, 352))
    }

    fun play(scene: Scene, stretches: Int = 3): Result {
        val failures = ArrayList<String>(); val notes = ArrayList<String>()
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xdff; it.clockIsOsTick = true }
        val flags = DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE or DamageMsg.FLAG_EFFECTS
        var now = 1000L                                           // OS ticks (1.024 a ms)
        fun ms(tick: Long): Double = tick * 1000.0 / 1024.0
        fun send(what: String, m: ByteArray) {
            for (arm in Arm.entries) if (!sim.conformanceMessage(arm, m, now)) failures += "$arm refused $what (refusal record ${sim.refusalRecord(arm)})"
        }
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, CACHE_KIB, now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, flags, now)
        }
        send("the black keyframe", CfwModes.keyframe(Zl.encodeCfw(ByteArray(Geometry.PANEL_W / 2 * Geometry.PANEL_H))))
        val fx = Motion.effects(DamageMsg.Caps(2, 0xdff), flags)
        val strip = strip(scene.height)
        val region = CfwModes.musicRegion(CACHE_KIB * 1024, true)!!
        // the song plays from [Scene.fromMs] at the first PLAY's tick; the place as heard at a phone time (the one equation)
        val t0 = 2_000L
        val zeroMs = ms(t0) - scene.fromMs
        val heard = { t: Long -> MusicMotion.heardAt(t, t, Math.round(t - zeroMs), true, 0) }
        val spec = MusicMotion.scopeLanes("rehearsal", strip, scene.data, heard)
        val p = spec.period; val n = spec.steps
        val frames = ArrayList<Frame>()
        val bodies = ArrayList<DamageMsg.Motion>()
        val writes = ArrayList<Triple<Int, Int, Int>>()
        var checked = 0
        fun notes() {
            for (b in sim.conformanceNotifies(Arm.RIGHT)) bodies += DamageMsg.parseMotion(Pb.l(DamageMsg.MOTION_FIELD, b))!!
            if (sim.conformanceNotifies(Arm.LEFT).isNotEmpty()) failures += "LEFT sent a notify (only RIGHT can)"
        }
        var lastCost = 0L
        /** Stretch [k] (0-based): its area written, then its PLAY (k = 0, slot 0) or PLAY-AFTER naming the one before. */
        fun stretch(k: Int, stepOneTick: Long) {
            val base = region.first + (k % WindowLanes.AREAS) * WindowLanes.AREA_BYTES
            val (plan, blob) = WindowLanes.area(spec, base, D, fx, Math.round(ms(stepOneTick)), ms(p.toLong()))
            val w = CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(base / 4, blob)), CACHE_KIB * 1024)
            writes += Triple(k + 1, w.size, CfwModes.zwrite(w).size)
            send("stretch ${k + 1}'s area", w)
            val cost = plan.program.cost(plan.p)
            val slot = k % 2
            val msg = if (k == 0) Motion.play(plan.program, slot, p, TAG0, 0, plan.p, cost = cost)
                else Motion.playAfter(plan.program, slot, p, TAG0 + k, 1 - slot, plan.p, listOf(Motion.Drawing(lastCost)), 0, cost)
            lastCost = cost
            send("stretch ${k + 1}'s ${if (k == 0) "PLAY" else "PLAY-AFTER"}", msg)
            notes()
        }
        /** One step's picture on both lenses against today's look near the song's place at [tick]. */
        fun check(label: String, tick: Long) {
            val place = heard(Math.round(ms(tick)))
            val got = Arm.entries.map { sim.snapshot(it) }
            val pr = Math.floorDiv(place - 120, 40L) * 40 + 20               // a reference place 100..140 ms back
            val s0 = Math.round((place - pr) / 5.0).toInt()
            val hit = (listOf(0) + (1..2).flatMap { listOf(it, -it) }).map { s0 + it }.firstOrNull { s -> matches(got, strip, scene.data, pr, s) }
            checked++
            if (hit == null) failures += "$label: not today's Scope within 2 px of the song's place ${place} ms on both lenses"
        }
        now = t0
        stretch(0, now)
        check("stretch 1 step 1", now)
        frames += Frame("1/1", now, sim.snapshot(Arm.LEFT), sim.snapshot(Arm.RIGHT))
        var k = 0; var step = 1
        var start = now                                           // the drawing stretch's step 1
        var sentNext = false
        var seen = 0 to 1
        var guard = 0
        while (guard++ < (stretches + 2) * n * 4) {
            val waits = Arm.entries.map { sim.conformancePlan(it, now) }.filter { it != MotionSim.PLAN_FOREVER }
            if (waits.isEmpty()) break
            now += maxOf(1L, waits.min())
            for (arm in Arm.entries) sim.conformanceWake(arm, now)
            notes()
            step = ((now - start) / p).toInt() + 1
            if (step > n) { k++; start += n.toLong() * p; step = ((now - start) / p).toInt() + 1; sentNext = false }
            if (k >= stretches) break
            if (seen == k to step) continue                     // a wake inside a step: its picture was checked
            seen = k to step
            check("stretch ${k + 1} step $step", start + (step - 1).toLong() * p)     // the place at the step's own tick
            if (step % 3 == 1 || step == n) frames += Frame("${k + 1}/$step", now, sim.snapshot(Arm.LEFT), sim.snapshot(Arm.RIGHT))
            // the next stretch with half of this one left (the lane's cadence); none after the last
            if (!sentNext && k + 1 < stretches && step >= n / 2) { stretch(k + 1, start + n.toLong() * p); sentNext = true }
        }
        if (guard >= (stretches + 2) * n * 4) failures += "the plan never ran out"
        val drew = bodies.filter { it.outcome == DamageMsg.OUTCOME_DREW }
        if (drew.size < stretches - 1) failures += "RIGHT said ${drew.size} stretch(es) drew their last step, not ${stretches - 1} or more: ${bodies.map { it.describe() }}"
        for (b in drew) if ((b.drawn ?: 0) + (b.fields[7] ?: 0) != n.toLong()) failures += "tag 0x%04x drew %s + folded %s, not T %d".format(b.tag, b.drawn, b.fields[7], n)
        if (bodies.any { it.outcome != DamageMsg.OUTCOME_DREW }) failures += "a stretch was closed or refused: ${bodies.filter { it.outcome != DamageMsg.OUTCOME_DREW }.map { it.describe() }}"
        notes += "T $n steps at P $p (${"%.0f".format(n * ms(p.toLong()))} ms a stretch); area writes ${writes.joinToString { "${it.first}: ${it.second} B (${it.third} B deflated)" }}"
        return Result(scene, strip, n, frames, checked, writes, notes, failures)
    }

    /** Whether both lenses' strip, [got] (packed panels, left then right), is today's paint of [data] at reference place
     *  [pr] (a 40-ms grid point + 20, where a column stands at the right edge) moved [s] px left — the place pr + 5s. */
    private fun matches(got: List<ByteArray>, a: Rect, data: VizData, pr: Long, s: Int): Boolean {
        if (s < 0) return false
        val m = s / ScopeViz.COL_PITCH + 2
        val wide = Rect(a.x, a.y, a.w + ScopeViz.COL_PITCH * m, a.h)
        val canvas = Gray8(wide.right + 8, wide.bottom)
        ScopeViz.paint(canvas, wide, data, pr + ScopeViz.MS_PER_COL * m)
        val stride = (Geometry.PANEL_W + 1) / 2
        for ((i, panel) in got.withIndex()) {
            val dx = if (i == 0) -D else D
            for (y in a.y until a.bottom) for (x in 0 until a.w) {
                val px = a.x + dx + x
                val b = panel[y * stride + (px shr 1)].toInt() and 0xFF
                val lv = if (px and 1 == 0) b shr 4 else b and 0x0F
                if (lv != Pack.level(canvas[a.x + s + x, y])) return false
            }
        }
        return true
    }
}
