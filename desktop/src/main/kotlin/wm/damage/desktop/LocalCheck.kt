package wm.damage.desktop

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.shell.MainLocalCheck
import wm.damage.core.shell.WindowListCheck

/**
 * `desktop --local-check DIR` (build C2, stage DM2; `C2-spec.md` §3 DM, §7; decisions B.11). Context for the reader:
 * Adam's own glasses on his own firmware fork; this fires Main's bound list through the simulator's model of the
 * glasses' input hook and draws what each lens shows — nothing contacts a device.
 *
 * The checks are [MainLocalCheck]'s, with the desktop's own rasterizer ([AwtText], the faces the phone draws with):
 * Main staged on a bit-10 build at each of the four heights, on 160 KiB (the shared reserve) and 256 KiB (Main's own
 * region); every cursor and both directions fired through the hook — RIGHT's report against `BoundList.predict`, the
 * last step against Main's own paint, COMMIT's END against belief; the press at every cursor; the report-only entry; a
 * row re-staged in place. It writes `DIR/<case>.png` — one notch's frames (the one before the fire, then every step),
 * the left lens above the right, cropped to the program's areas, at true 1x — and `DIR/<case>.txt` with what was checked
 * and what failed, plus `DIR/staging.txt` (bytes, slots, P and T per height and cache). Exit 0 only when every case holds.
 * K1-2 stage 2: and a window's list in the window form ([WindowListCheck]: Settings' categories, Global and a three-row
 * category at the four heights, stage 5, 256 KiB, every effect of the form at every cursor both ways, the report-only
 * tap) — `DIR/list-<list>-h<height>-<effect>-<down|up|tap>` beside `DIR/list-staging.txt`.
 * C3 DW-1b-2: and the switcher's wheel bound as list 2 (`WheelCheck.localCases`: the four heights, d 0 and 8, n 3, 5 and
 * 9, every notch at every cursor both ways, the wrap, the report-only tap at both ends; not offered at n 2 and 160 KiB) —
 * `DIR/wheel-h<height>-d<d>-n<n>-<down|up|tap>.txt`, `DIR/wheel-n2-d<d>.txt`, `DIR/wheel-160k.txt` beside
 * `DIR/wheel-bound.txt` (the block's bytes and place per case).
 *
 * The bounds (decisions B.11, C1's A.15 form): `main` hands this the directory before the config is read, so nothing
 * under `~/.damage` is read; no stack, transport, shell or scope is made, so no port is bound and no window opens (the
 * JVM is set headless first); the only files written are in DIR.
 */
object LocalCheck {
    private const val COLS = 7
    private const val GAP = 4
    private const val PAD = 8

    fun run(outDir: Path, out: java.io.PrintStream = System.out): Int {
        Files.createDirectories(outDir)
        val r = try { MainLocalCheck.run(AwtText()) } catch (e: Exception) {
            out.println("local-check: FAILED to run: ${e.javaClass.simpleName}: ${e.message}")
            return 1
        }
        for (c in r.cases) writeCase(outDir, c, out)
        Files.writeString(outDir.resolve("staging.txt"), r.table.joinToString("\n", postfix = "\n"))
        for (l in r.table) out.println("  $l")
        val w = try { WindowListCheck.run(AwtText()) } catch (e: Exception) {
            out.println("local-check: the window lists FAILED to run: ${e.javaClass.simpleName}: ${e.message}")
            return 1
        }
        for (c in w.cases) writeCase(outDir, c, out)
        Files.writeString(outDir.resolve("list-staging.txt"), w.table.joinToString("\n", postfix = "\n"))
        for (l in w.table) out.println("  $l")
        // C3 DW-1b-2 (the DW spec §7.2): the switcher's wheel bound as list 2
        val k = try { wm.damage.core.motion.WheelCheck.localCases(AwtText()) } catch (e: Exception) {
            out.println("local-check: the wheel FAILED to run: ${e.javaClass.simpleName}: ${e.message}")
            return 1
        }
        for (c in k.cases) writeCase(outDir, c, out)
        Files.writeString(outDir.resolve("wheel-bound.txt"), k.table.joinToString("\n", postfix = "\n"))
        for (l in k.table) out.println("  $l")
        val failures = r.failures + w.failures + k.failures
        out.println("local-check: ${r.cases.size} case(s) of Main's, ${w.cases.size} of a window's list and ${k.cases.size} of the wheel into $outDir")
        if (failures.isEmpty()) { out.println("local-check: ALL HOLD"); return 0 }
        out.println("local-check: ${failures.size} FAILURE(S):")
        for (f in failures.take(60)) out.println("  - $f")
        if (failures.size > 60) out.println("  - … ${failures.size - 60} more in the cases' .txt files")
        return 1
    }

    /** [c]'s strip (when it kept frames) and its text into [outDir] (K1-1: `--motion-check`'s scroll section writes its
     *  cases this way too). */
    internal fun writeCase(outDir: Path, c: MainLocalCheck.Case, out: java.io.PrintStream) {
        if (c.frames.isNotEmpty()) writeStrip(outDir.resolve("${c.name}.png"), c)
        Files.writeString(outDir.resolve("${c.name}.txt"), buildString {
            appendLine("${c.name}: ${c.about}")
            if (c.frames.isNotEmpty()) appendLine("strip: ${c.frames.size} frames (${c.frames.joinToString { it.label }}), left lens above right, crop ${c.crop.x},${c.crop.y} ${c.crop.w}x${c.crop.h} at 1x")
            for (l in c.lines) appendLine("  $l")
            if (c.failures.isEmpty()) appendLine("checks: all hold") else for (f in c.failures) appendLine("FAILED: $f")
        })
        out.println("  ${if (c.failures.isEmpty()) "held" else "FAILED"}: ${c.name}${if (c.frames.isNotEmpty()) " -> ${c.name}.png" else ""}")
    }

    private fun writeStrip(path: Path, c: MainLocalCheck.Case) {
        val full = Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)
        val crop = Rect(c.crop.x - PAD, c.crop.y - PAD, c.crop.w + 2 * PAD, c.crop.h + 2 * PAD).intersect(full) ?: full
        val cols = minOf(COLS, c.frames.size)
        val rows = (c.frames.size + cols - 1) / cols
        val cellW = crop.w + GAP
        val cellH = 2 * crop.h + GAP / 2 + GAP
        val img = BufferedImage(cols * cellW + GAP, rows * cellH + GAP, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) img.setRGB(x, y, 0x502020)      // the gaps: not a lens colour
        val stride = Geometry.PANEL_W / 2
        c.frames.forEachIndexed { i, f ->
            val ox = GAP + (i % cols) * cellW
            val oy = GAP + (i / cols) * cellH
            for ((lens, panel) in listOf(0 to f.left, 1 to f.right)) for (y in 0 until crop.h) for (x in 0 until crop.w) {
                val px = crop.x + x; val py = crop.y + y
                val b = panel[py * stride + (px shr 1)].toInt() and 0xFF
                val v = (if (px and 1 == 0) b shr 4 else b and 0x0F) * 17
                // `MotionCheck`'s lens tint, so a strip reads as the scenes do
                img.setRGB(ox + x, oy + lens * (crop.h + GAP / 2) + y,
                    ((v * 0.16).toInt() shl 16) or (minOf(255, (v * 1.05).toInt()) shl 8) or (v * 0.34).toInt())
            }
        }
        ImageIO.write(img, "png", path.toFile())
    }
}
