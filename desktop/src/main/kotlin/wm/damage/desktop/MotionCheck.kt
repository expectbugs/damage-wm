package wm.damage.desktop

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.motion.TestPrograms
import wm.damage.core.motion.TransitionRehearsal
import wm.damage.core.motion.Transitions
import wm.damage.core.shell.MainLocalCheck
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/**
 * `desktop --motion-check DIR` (build C1, decision A.15; `FORK.md` Phase 3: "renders a program tick by tick to a strip
 * for eyeballing before it goes to glass"). Context for the reader: Adam's own glasses on his own firmware fork; this
 * plays the feel session's test programs ([TestPrograms]) on the simulator's model of the engine and draws what each
 * lens shows after every step.
 *
 * Per program, on a fresh simulator of a C1 build (DamageCaps 0x1ff) with its clock as the OS tick, as the vectors run
 * (`FIRMWARE.md` §5.10): the lease, a 160 KiB cache, DRAW2 and ENGINE armed, the program's stage as a keyframe, its
 * records, then the PLAY (step 1 drawn by its own present) and one wake at each later step's tick. It writes
 * `DIR/NAME.png` — every step's frame (the one before the PLAY first), the left lens above the right, cropped to the
 * program's areas, at true 1x — and `DIR/NAME.txt`, the steps and the checks. Checks, each a failure (exit 1) when it
 * does not hold: every message is taken by both lenses; RIGHT's one notify says the last step was drawn with T steps
 * drawn and none folded; after the last step nothing is planned and each lens shows exactly the shadow (END, §5.6).
 *
 * K1-1: and Main's Scroll effects ([MainLocalCheck.effectStrips]: each effect's notch from the middle row both ways and
 * the wrap where Main wraps, at the four heights on 256 KiB, fired through the simulator's input hook, the desktop's own
 * faces) as `DIR/scroll-<effect>-h<height>-<notch>.png` and `.txt` beside `DIR/scroll-staging.txt` — the lead's look before
 * Adam's; each case's checks (the last step and COMMIT's END against Main's paint) count as the programs' do. C3 FF: and the
 * same at stage 5, `DIR/scroll-<effect>-h<height>-s5-<notch>`, on the fast DRAW path's fit (the wrap a ring fire at 288/352).
 * C3 X1DM1 (decisions BP (4)): and his sequences at 352, stage 5 ([MainLocalCheck.effectBursts]) — `DIR/scroll-<effect>-
 * h352-s5-burst` for None, Depth, Slide, Fall, Crush and Flip (six notches down at the ring's least gap, a pause, six up)
 * and `-fast` for Depth and Crush (three notches down 120 ticks apart): one strip row a notch, every step, each with its
 * `.txt` (where each notch was cut and the rows' travel left there); their checks count as the strips' do.
 *
 * The bounds A.15 sets: `main` hands this the directory before the config is read, so nothing under `~/.damage` is
 * read; no stack, transport, shell or scope is made, so no port is bound and no window opens (the JVM is set headless
 * first); the only files Damage writes are in DIR — the two per program and the scroll section's (K1-1). The scroll
 * strips draw Main's rows with the desktop's faces (AWT), and the JDK keeps its font configuration cache under the home
 * directory (`~/.java/fonts/<version>/`), creating or refreshing it there on first use: that one path is the JDK's.
 */
object MotionCheck {
    /** The build the programs are checked against: the engine and the riders (`FIRMWARE.md` §5.1, DamageCaps 7 and 8). */
    const val C1_FEATURES = 0x1ff
    private const val CACHE_KIB = 160
    private const val COLS = 9           // frames per row of a strip
    private const val GAP = 4            // px between frames
    private const val PAD = 8            // px around the program's areas

    /** Plays every test program into [outDir]; returns the exit status (0 all hold, 1 otherwise) after saying why.
     *  [features] is for the tool's own test (a build without the engine must fail every program). */
    fun run(outDir: Path, features: Int = C1_FEATURES, out: java.io.PrintStream = System.out, scroll: Boolean = true): Int {
        Files.createDirectories(outDir)
        val failures = ArrayList<String>()
        for (name in TestPrograms.NAMES) {
            val f = try { one(name, outDir, features, out) } catch (e: Exception) { listOf("$name: ${e.javaClass.simpleName}: ${e.message}") }
            failures += f
        }
        // build K1-4a: the notice box's programs (the timer, the Fade and Slide arrivals), for the lead's look
        for (style in wm.damage.core.motion.PopupPrograms.Style.entries) {
            failures += try { popup(style, outDir, features, out) } catch (e: Exception) { listOf("popup-${style.name.lowercase()}: ${e.javaClass.simpleName}: ${e.message}") }
        }
        failures += try { transitions(outDir, out) } catch (e: Exception) { listOf("transitions: ${e.javaClass.simpleName}: ${e.message}") }
        if (scroll) failures += scrollEffects(outDir, out)
        if (scroll) failures += scrollBursts(outDir, out)
        out.println("motion-check: ${TestPrograms.NAMES.size} program(s), 3 notice programs and ${PLAYS.size} transition(s)${if (scroll) " and Main's scroll effects" else ""} into $outDir")
        if (failures.isEmpty()) { out.println("motion-check: ALL HOLD"); return 0 }
        out.println("motion-check: ${failures.size} FAILURE(S):")
        for (f in failures) out.println("  - $f")
        return 1
    }

    /** K1-1: Main's Scroll effects as strips (see the class's text); the failures. */
    private fun scrollEffects(outDir: Path, out: java.io.PrintStream): List<String> {
        // C3 DM-1: each strip written as its case is made (six effects' frames held at once passed the test's heap)
        val r = try { MainLocalCheck.effectStrips(AwtText()) { c -> LocalCheck.writeCase(outDir, c, out) } } catch (e: Exception) { return listOf("scroll effects: ${e.javaClass.simpleName}: ${e.message}") }
        Files.writeString(outDir.resolve("scroll-staging.txt"), r.table.joinToString("\n", postfix = "\n"))
        return r.failures
    }

    /** C3 X1DM1: his sequences (see the class's text) — each strip and its text written as it is made; the failures. */
    private fun scrollBursts(outDir: Path, out: java.io.PrintStream): List<String> {
        val failures = ArrayList<String>()
        try {
            MainLocalCheck.effectBursts(AwtText()) { b ->
                if (b.rows.isNotEmpty()) burstStrip(outDir.resolve("${b.name}.png"), b.rows, b.crop)
                Files.writeString(outDir.resolve("${b.name}.txt"), buildString {
                    appendLine("${b.name}: ${b.about}")
                    appendLine("strip: ${b.rows.sumOf { it.size }} frames, one row a notch (the first the frame before); in each frame the left lens " +
                        "above the right; crop ${b.crop.x},${b.crop.y} ${b.crop.w}x${b.crop.h} (and $PAD px around) at 1x")
                    for (l in b.lines) appendLine("  $l")
                    for (f in b.rows.flatten()) appendLine("  frame (${f.label}): L %08x R %08x".format(crc(f.left), crc(f.right)))
                    if (b.failures.isEmpty()) appendLine("checks: all hold (every message taken by both lenses; each report as predicted; " +
                        "the last step Main's paint at the last cursor; COMMIT's END belief's)")
                    else for (f in b.failures) appendLine("FAILED: $f")
                })
                failures += b.failures.map { "${b.name}: $it" }
                out.println("  ${if (b.failures.isEmpty()) "held" else "FAILED"}: ${b.name} -> ${b.name}.png, ${b.name}.txt")
            }
        } catch (e: Exception) { return failures + "scroll bursts: ${e.javaClass.simpleName}: ${e.message}" }
        return failures
    }

    /** [rows] of frames, one strip row each, the left lens above the right in each frame, cropped to [crop] and [PAD] px
     *  around it at 1x. */
    private fun burstStrip(file: Path, rows: List<List<MainLocalCheck.Frame>>, crop0: Rect) {
        val full = Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)
        val crop = Rect(crop0.x - PAD, crop0.y - PAD, crop0.w + 2 * PAD, crop0.h + 2 * PAD).intersect(full) ?: full
        val cols = rows.maxOf { it.size }
        val cellW = crop.w + GAP
        val cellH = 2 * crop.h + GAP / 2 + GAP
        val img = BufferedImage(cols * cellW + GAP, rows.size * cellH + GAP, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, 0x502020)
        val stride = (Geometry.PANEL_W + 1) / 2
        rows.forEachIndexed { ri, row -> row.forEachIndexed { ci, f ->
            val ox = GAP + ci * cellW
            val oy = GAP + ri * cellH
            for ((lens, panel) in listOf(0 to f.left, 1 to f.right)) for (y in 0 until crop.h) for (x in 0 until crop.w) {
                val px = crop.x + x; val py = crop.y + y
                val b = panel[py * stride + (px shr 1)].toInt() and 0xFF
                val v = (if (px and 1 == 0) b shr 4 else b and 0x0F) * 17
                img.setRGB(ox + x, oy + lens * (crop.h + GAP / 2) + y,
                    ((v * 0.16).toInt() shl 16) or (minOf(255, (v * 1.05).toInt()) shl 8) or (v * 0.34).toInt())
            }
        } }
        ImageIO.write(img, "png", file.toFile())
    }

    private class Frame(val label: String, val tick: Long, val l: ByteArray, val r: ByteArray)

    private fun one(name: String, outDir: Path, features: Int, out: java.io.PrintStream): List<String> {
        val cacheSize = CACHE_KIB * 1024
        val tp = TestPrograms.build(name, cacheSize)!!
        val failures = ArrayList<String>()
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features; it.clockIsOsTick = true }
        var now = 1000L
        fun send(what: String, m: ByteArray) {
            for (arm in Arm.entries) if (!sim.conformanceMessage(arm, m, now))
                failures += "$name: $arm refused $what (refusal record ${sim.refusalRecord(arm)})"
        }
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, CACHE_KIB, now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE, now)
        }
        send("the stage's keyframe", CfwModes.keyframe(Zl.encodeCfw(Pack.rect(tp.stage, Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)))))
        val msgs = tp.messages()
        msgs.dropLast(1).forEachIndexed { i, m -> send("record write ${i + 1} of ${msgs.size - 1}", m) }
        val frames = ArrayList<Frame>()
        fun take(label: String) { frames += Frame(label, now, sim.snapshot(Arm.LEFT), sim.snapshot(Arm.RIGHT)) }
        take("before")
        val notifies = ArrayList<DamageMsg.Motion>()
        fun notes() { for (b in sim.conformanceNotifies(Arm.RIGHT)) notifies += DamageMsg.parseMotion(Pb.l(DamageMsg.MOTION_FIELD, b))!!
            if (sim.conformanceNotifies(Arm.LEFT).isNotEmpty()) failures += "$name: LEFT sent a notify (only RIGHT can)" }
        now = 2000L
        val t0 = now
        send("the PLAY", tp.play())
        notes(); take("step 1")
        val steps = tp.program.stepsWith(tp.params)
        for (k in 2..steps) {
            now = t0 + (k - 1).toLong() * tp.period
            for (arm in Arm.entries) sim.conformanceWake(arm, now)
            notes(); take("step $k")
        }
        // the checks
        val drew = notifies.filter { it.outcome == DamageMsg.OUTCOME_DREW }
        if (notifies.size != 1 || drew.size != 1) failures += "$name: RIGHT sent ${notifies.size} notify body(ies), not one that drew the last step: ${notifies.map { it.describe() }}"
        drew.singleOrNull()?.let { m ->
            if (m.drawn != steps.toLong() || m.folded != 0L) failures += "$name: the notify says ${m.drawn} drawn and ${m.folded} folded, not $steps and 0"
            if (m.tag != tp.tag) failures += "$name: the notify's tag ${m.tag}, not ${tp.tag}"
        }
        for (arm in Arm.entries) {
            val w = sim.conformancePlan(arm, now)
            if (w != MotionSim.PLAN_FOREVER) failures += "$name: $arm still plans a wait of $w after the last step"
            if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) failures += "$name: $arm shows something other than END (the shadow) after the last step"
        }
        write(outDir, tp, frames, notifies, failures)
        out.println("  ${if (failures.isEmpty()) "held" else "FAILED"}: ${tp.describe()} -> $name.png, $name.txt")
        return failures
    }

    private fun write(outDir: Path, tp: TestPrograms.TestProgram, frames: List<Frame>, notifies: List<DamageMsg.Motion>, failures: List<String>) {
        val areas = tp.program.tracks.flatMap { listOf(it.areaL, it.areaR) }
        val u = areas.reduce { a, b -> a.union(b) }
        val crop = Rect(u.x - PAD, u.y - PAD, u.w + 2 * PAD, u.h + 2 * PAD).intersect(Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H))!!
        val cols = minOf(COLS, frames.size)
        val rows = (frames.size + cols - 1) / cols
        val cellW = crop.w + GAP
        val cellH = 2 * crop.h + GAP / 2 + GAP
        val img = BufferedImage(cols * cellW + GAP, rows * cellH + GAP, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, 0x502020)      // the gaps: not a lens colour
        val stride = (Geometry.PANEL_W + 1) / 2
        frames.forEachIndexed { i, f ->
            val ox = GAP + (i % cols) * cellW
            val oy = GAP + (i / cols) * cellH
            for ((lens, panel) in listOf(0 to f.l, 1 to f.r)) for (y in 0 until crop.h) for (x in 0 until crop.w) {
                val px = crop.x + x; val py = crop.y + y
                val b = panel[py * stride + (px shr 1)].toInt() and 0xFF
                val v = (if (px and 1 == 0) b shr 4 else b and 0x0F) * 17
                // Snapshot's lens tint, so a strip reads as the scenes do
                img.setRGB(ox + x, oy + lens * (crop.h + GAP / 2) + y,
                    ((v * 0.16).toInt() shl 16) or (minOf(255, (v * 1.05).toInt()) shl 8) or (v * 0.34).toInt())
            }
        }
        ImageIO.write(img, "png", outDir.resolve("${tp.name}.png").toFile())
        val txt = buildString {
            appendLine("${tp.name}: ${tp.about}")
            appendLine(tp.describe())
            appendLine("strip: ${frames.size} frames, $cols per row, left to right then down; in each frame the left lens above the right;")
            appendLine("crop ${crop.x},${crop.y} ${crop.w}x${crop.h} of the 640x480 panel at 1x; tracks: " +
                tp.program.tracks.joinToString("; ") { t -> "${t.areaL}" + if (t.perLens) " / ${t.areaR}" else "" })
            for ((i, f) in frames.withIndex()) appendLine("  frame $i (${f.label}) at tick ${f.tick}: L %08x R %08x".format(crc(f.l), crc(f.r)))
            for (m in notifies) appendLine("notify: ${m.describe()}")
            if (failures.isEmpty()) appendLine("checks: every message taken by both lenses; one notify, T drawn, 0 folded; nothing planned after; each lens shows END")
            else for (f in failures) appendLine("FAILED: $f")
        }
        Files.writeString(outDir.resolve("${tp.name}.txt"), txt)
    }

    private fun crc(b: ByteArray) = java.util.zip.CRC32().also { it.update(b) }.value

    /** Text as blocks of its level, 8 px a character (the tests' fake rasterizer's shape): no font is read. */
    private object BlockText : wm.damage.core.text.TextRasterizer {
        override fun measure(text: String, font: wm.damage.core.text.FontSpec): Int = text.length * 8
        override fun metrics(font: wm.damage.core.text.FontSpec) = wm.damage.core.text.FontMetrics(12, 4, 16)
        override fun draw(surface: wm.damage.core.gfx.Gray8, x: Int, y: Int, text: String, font: wm.damage.core.text.FontSpec, level: Int) {
            for ((i, ch) in text.withIndex()) if (ch != ' ') surface.fillRect(x + i * 8, y + 2, 6, 10, level)
        }
        override fun covers(text: String, font: wm.damage.core.text.FontSpec) = true
    }

    /**
     * Build K1-4a: one notice program played over a stage holding the box as the programs' END (the shell's paint with
     * the engine's timer: full ink, the bar whole) over a few rows, at disparity 0, in a 256 KiB cache with the records
     * and the capture where the shell puts them (the notice region), its text as blocks. An arrival is played with a hold of 2 steps (the
     * shell's is the rest of the 255 steps, replaced by the timer's PLAY at the arrival's end). Writes
     * `DIR/popup-STYLE.png` (every step, the left lens above the right, cropped to the box) and `.txt`; the same checks
     * as a test program's.
     */
    private fun popup(style: wm.damage.core.motion.PopupPrograms.Style, outDir: Path, features: Int, out: java.io.PrintStream): List<String> {
        val name = "popup-${style.name.lowercase()}"
        val cacheSize = 256 * 1024
        val failures = ArrayList<String>()
        // A.15's bound: no font machinery (AwtText writes under the home directory) — the box's text as blocks, the
        // motion is what the strip shows
        val text = BlockText
        val n = wm.damage.core.shell.Notifications(text)
        val l = wm.damage.core.geom.Layout()
        val notice = wm.damage.core.shell.Notifications.Notice("SMS · MOM", "mom", "on my way, should be there in about twenty minutes", "2:32")
        n.post(notice, l)
        repeat(6) { n.stepUnfurl(l, silent = false) }
        n.engine = true
        val stage = wm.damage.core.gfx.Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
        for (row in 0 until 8) text.draw(stage, 40, 120 + row * 32, "Row $row of the window under the box", wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18), 8 * 17)
        n.captureUnder(stage, n.fullRect(notice, l, false))
        n.paint(stage, l, silent = false)
        val geo = wm.damage.core.motion.PopupPrograms.Geo(n.fullRect(notice, l, false), n.ruleOffset(), wm.damage.core.shell.Notifications.BAR_INSET, 0)
        val region = CfwModes.noticeRegion(cacheSize, true)!!
        val at = region.first / 4
        val (pa, fa) = wm.damage.core.shell.MainStaging.timing(geo.areaPx, wm.damage.core.shell.MainStaging.ScrollMotion(300, 0))
        val program = if (style == wm.damage.core.motion.PopupPrograms.Style.TIMER) wm.damage.core.motion.PopupPrograms.timer(at, geo)
            else wm.damage.core.motion.PopupPrograms.arrival(at, geo, style, fa, 2)
        val period = if (style == wm.damage.core.motion.PopupPrograms.Style.TIMER) wm.damage.core.motion.PopupPrograms.TIMER_PERIOD else pa
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = features; it.clockIsOsTick = true }
        var now = 1000L
        fun send(what: String, m: ByteArray) {
            for (arm in Arm.entries) if (!sim.conformanceMessage(arm, m, now)) failures += "$name: $arm refused $what (refusal record ${sim.refusalRecord(arm)})"
        }
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, cacheSize / 1024, now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE, now)
        }
        send("the stage's keyframe", CfwModes.keyframe(Zl.encodeCfw(Pack.rect(stage, Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)))))
        send("the program", CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(at, program.bytes)), cacheSize))
        val frames = ArrayList<Frame>()
        fun take(label: String) { frames += Frame(label, now, sim.snapshot(Arm.LEFT), sim.snapshot(Arm.RIGHT)) }
        take("before")
        now = 2000L
        val t0 = now
        send("the PLAY", wm.damage.core.motion.Motion.play(program, wm.damage.core.motion.PopupPrograms.SLOT, period, 1,
            (region.first + CfwModes.NOTICE_PROGRAMS) / 4, IntArray(4)))
        take("step 1")
        val steps = program.T
        for (k in 2..steps) {
            now = t0 + (k - 1).toLong() * period
            for (arm in Arm.entries) sim.conformanceWake(arm, now)
            take("step $k")
        }
        for (arm in Arm.entries) if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) failures += "$name: $arm shows something other than END after the last step"
        val crop = Rect(geo.box.x - PAD, geo.box.y - PAD, geo.box.w + 2 * PAD, geo.box.h + 2 * PAD)
        val cols = minOf(COLS, frames.size)
        val rows = (frames.size + cols - 1) / cols
        val cellW = crop.w + GAP
        val cellH = 2 * crop.h + GAP / 2 + GAP
        val img = BufferedImage(cols * cellW + GAP, rows * cellH + GAP, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, 0x502020)
        val stride = (Geometry.PANEL_W + 1) / 2
        frames.forEachIndexed { i, f ->
            val ox = GAP + (i % cols) * cellW
            val oy = GAP + (i / cols) * cellH
            for ((lens, panel) in listOf(0 to f.l, 1 to f.r)) for (y in 0 until crop.h) for (x in 0 until crop.w) {
                val px = crop.x + x; val py = crop.y + y
                val b = panel[py * stride + (px shr 1)].toInt() and 0xFF
                val v = (if (px and 1 == 0) b shr 4 else b and 0x0F) * 17
                img.setRGB(ox + x, oy + lens * (crop.h + GAP / 2) + y, ((v * 0.16).toInt() shl 16) or (minOf(255, (v * 1.05).toInt()) shl 8) or (v * 0.34).toInt())
            }
        }
        ImageIO.write(img, "png", outDir.resolve("$name.png").toFile())
        Files.writeString(outDir.resolve("$name.txt"), buildString {
            appendLine("$name: T $steps at period $period (${"%.1f".format(period * 1000.0 / 1024)} ms), ${program.bytes.size} B program at ${region.first}, " +
                "${program.captureBytes} B capture at ${region.first + CfwModes.NOTICE_PROGRAMS}; the box ${geo.box}, one step paints ${geo.areaPx} px a lens")
            appendLine("strip: ${frames.size} frames, $cols per row; in each frame the left lens above the right; crop $crop at 1x")
            if (failures.isEmpty()) appendLine("checks: every message taken by both lenses; each lens shows END after the last step") else for (f in failures) appendLine("FAILED: $f")
        })
        out.println("  ${if (failures.isEmpty()) "held" else "FAILED"}: $name -> $name.png, $name.txt")
        return failures
    }

    // ------------------------------------------------------------------------------- build C3, stage DP2
    /** Every transition a shell plays: Select's six forward and as Back (reversed), Lock's four entering and leaving. */
    private val PLAYS: List<Triple<Transitions.Tier, Transitions.Effect, Boolean>> =
        Transitions.effectsOf(Transitions.Tier.SELECT).flatMap { listOf(Triple(Transitions.Tier.SELECT, it, false), Triple(Transitions.Tier.BACK, it, true)) } +
            Transitions.effectsOf(Transitions.Tier.LOCK).flatMap { listOf(Triple(Transitions.Tier.LOCK, it, false), Triple(Transitions.Tier.LOCK, it, true)) }

    /**
     * Build C3, stage DP2: each transition played on the model of a C3 build at height 352, 450 ms / 10 frames
     * ([TransitionRehearsal]: the out half's PLAY, the arriving frame in two held batches, the in half by PLAY-AFTER),
     * written as `DIR/transition-TIER-EFFECT[-reversed].png` — every frame from the one before the PLAY to the in half's
     * last step, the left lens above the right, the 640 × 352 area at true 1x — and its `.txt`. Checks, each a failure: the
     * in half lands the arriving frame on both lenses (panel = shadow = the frame), the frame shows nothing of itself
     * before the in half, RIGHT's two bodies say every step was drawn. C3 X4T item 5 (decisions AT (4)): Encode's strips
     * are of the shell's NOT-compressed order (its in lists written after the out PLAY, [TransitionRehearsal]'s form); the
     * shell's usual form puts them in the records' write, which changes no pixel.
     */
    /** The strips' base names, in [PLAYS]' order. */
    internal val TRANSITION_NAMES: List<String> get() = PLAYS.map { (t, e, rev) -> "transition-${t.word.lowercase()}-${e.id}${if (rev) "-reversed" else ""}" }

    private fun transitions(outDir: Path, out: java.io.PrintStream): List<String> {
        val failures = ArrayList<String>()
        val old = Gray8(Geometry.PANEL_W, Geometry.PANEL_H).also { g ->
            for (r in 0 until 10) g.fillRect(Rect(40, 44 + r * 30, 300 + (r * 53) % 220, 20), ((r % 11) + 5) * 17)
            g.fillRect(Rect(24, 164, 592, 60), 3 * 17); g.fillRect(Rect(40, 180, 260, 28), 15 * 17)
            g.fillRect(Rect(0, 0, 640, 30), 2 * 17); g.fillRect(Rect(560, 6, 64, 18), 12 * 17)
        }
        val new = Gray8(Geometry.PANEL_W, Geometry.PANEL_H).also { g ->
            for (j in 0 until 5) for (i in 0 until 8) g.fillRect(Rect(24 + i * 76, 40 + j * 60, 60, 44), (((i + 3 * j) % 14) + 2) * 17)
            g.fillRect(Rect(0, 0, 640, 30), 2 * 17); g.fillRect(Rect(24, 6, 200, 18), 13 * 17)
        }
        val timing = Transitions.timing(450, 10)
        for ((tier, e, rev) in PLAYS) {
            val name = TRANSITION_NAMES[PLAYS.indexOf(Triple(tier, e, rev))]
            val r = TransitionRehearsal.play(TransitionRehearsal.Plan(e, rev, HEIGHT, timing, batches = 2), old, new)
            failures += r.failures.map { "$name: $it" }
            val crop = Rect(0, 0, Geometry.PANEL_W, HEIGHT)
            strip(outDir.resolve("$name.png"), r.frames.map { Triple(it.label, it.l, it.r) }, crop)
            Files.writeString(outDir.resolve("$name.txt"), buildString {
                appendLine("$name: ${tier.word} ${e.label}${if (rev) ", reversed in time" else ""} — ${r.built.describe()}")
                appendLine("strip: ${r.frames.size} frames, $COLS per row; in each frame the left lens above the right; the 640x$HEIGHT area at 1x")
                for ((i, f) in r.frames.withIndex()) appendLine("  frame $i (${f.label}) at tick ${f.tick}: L %08x R %08x".format(crc(f.l), crc(f.r)))
                for (m in r.notifies) appendLine("notify: ${m.describe()}")
                if (r.failures.isEmpty()) appendLine("checks: the in half lands the arriving frame on both lenses; nothing of it before; both bodies drew every step")
                else for (f in r.failures) appendLine("FAILED: $f")
            })
            out.println("  ${if (r.failures.isEmpty()) "held" else "FAILED"}: $name -> $name.png, $name.txt")
        }
        return failures
    }

    private const val HEIGHT = 352

    private fun strip(file: Path, frames: List<Triple<String, ByteArray, ByteArray>>, crop: Rect) {
        val cols = minOf(COLS, frames.size)
        val rows = (frames.size + cols - 1) / cols
        val cellW = crop.w + GAP
        val cellH = 2 * crop.h + GAP / 2 + GAP
        val img = BufferedImage(cols * cellW + GAP, rows * cellH + GAP, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, 0x502020)
        val stride = (Geometry.PANEL_W + 1) / 2
        frames.forEachIndexed { i, (_, l, r) ->
            val ox = GAP + (i % cols) * cellW
            val oy = GAP + (i / cols) * cellH
            for ((lens, panel) in listOf(0 to l, 1 to r)) for (y in 0 until crop.h) for (x in 0 until crop.w) {
                val px = crop.x + x; val py = crop.y + y
                val b = panel[py * stride + (px shr 1)].toInt() and 0xFF
                val v = (if (px and 1 == 0) b shr 4 else b and 0x0F) * 17
                img.setRGB(ox + x, oy + lens * (crop.h + GAP / 2) + y,
                    ((v * 0.16).toInt() shl 16) or (minOf(255, (v * 1.05).toInt()) shl 8) or (v * 0.34).toInt())
            }
        }
        ImageIO.write(img, "png", file.toFile())
    }
}
