package wm.damage.desktop

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.motion.TestPrograms

/**
 * Build C1, package D3b (decision A.15): `desktop --motion-check DIR`. Context for the reader: Adam's own glasses on
 * his own firmware fork; the tool plays the feel session's test programs on the simulator and writes a strip per
 * program. These pins: it exits 0 and writes exactly one strip (and its text) per test program, each strip one frame
 * per step; a build without the engine is a failure, not a quiet strip; and run through `main` it reads and writes
 * nothing under the home directory (the config is never touched) and writes only into DIR.
 */
class MotionCheckTest {
    private fun files(dir: Path): List<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
    /** Build C3 (DP2): the transitions' strips beside the test programs' and the notice box's (K1-4a). */
    private val expected = (TestPrograms.NAMES + listOf("popup-timer", "popup-fade", "popup-slide") + MotionCheck.TRANSITION_NAMES)
        .flatMap { listOf("$it.png", "$it.txt") }.sorted()

    @Test
    fun theMotionCheckWritesOneStripPerProgramAndEveryProgramHolds_A15() {
        val dir = Files.createTempDirectory("damage-motion-check")
        try {
            val out = ByteArrayOutputStream()
            assertEquals(0, MotionCheck.run(dir, out = PrintStream(out, true)), out.toString())
            assertTrue("ALL HOLD" in out.toString(), out.toString())
            assertEquals(expected, files(dir).filter { !it.startsWith("scroll-") }, "one strip and one text per test program and per transition, nothing else")
            // K1-1: each Scroll effect's notches (both ways at four heights, the wrap at the two that wrap) as strips
            assertEquals(4 * (4 * 2 + 2 * 2), files(dir).count { it.startsWith("scroll-") && it.endsWith(".png") }, "the scroll strips: ${files(dir).filter { it.startsWith("scroll-") }}")
            for (f in files(dir).filter { it.startsWith("scroll-") && it.endsWith(".txt") && it != "scroll-staging.txt" })
                assertTrue("checks: all hold" in Files.readString(dir.resolve(f)), "$f:\n${Files.readString(dir.resolve(f))}")
            for (name in MotionCheck.TRANSITION_NAMES) {
                val txt = Files.readString(dir.resolve("$name.txt"))
                assertTrue("checks: the in half lands the arriving frame" in txt && "FAILED" !in txt, "$name:\n$txt")
            }
            for (name in TestPrograms.NAMES) {
                val steps = TestPrograms.build(name, 160 * 1024)!!.program.T
                val txt = Files.readString(dir.resolve("$name.txt"))
                assertEquals(steps + 1, Regex("""(?m)^  frame \d+ """).findAll(txt).count(), "$name: the frame before the PLAY and one per step:\n$txt")
                assertTrue("checks: every message taken" in txt && "FAILED" !in txt, "$name:\n$txt")
                val img = ImageIO.read(dir.resolve("$name.png").toFile())
                assertTrue(img.width > 100 && img.height > 100, "$name: a strip of ${img.width}x${img.height}")
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun aBuildWithoutTheEngineFailsEveryProgram_A15() {
        val dir = Files.createTempDirectory("damage-motion-check-0x7f")
        try {
            val out = ByteArrayOutputStream()
            assertEquals(1, MotionCheck.run(dir, features = 0x7f, out = PrintStream(out, true), scroll = false), out.toString())
            for (name in TestPrograms.NAMES) assertTrue(Regex("""(?m)^  - $name: (LEFT|RIGHT) refused the PLAY""").containsMatchIn(out.toString()),
                "$name's refused PLAY is named:\n$out")
        } finally { dir.toFile().deleteRecursively() }
    }

    /** A.15's bounds through `main` itself: with the home directory an empty one, `--motion-check DIR` leaves it empty
     *  — `Config.load()` would have written `~/.damage/config.json` there — and DIR holds the strips and nothing else. */
    @Test
    fun throughMainItTouchesNothingUnderHomeAndWritesOnlyIntoDir_A15() {
        val home = Files.createTempDirectory("damage-motion-home")
        val dir = Files.createTempDirectory("damage-motion-dir")
        val wasHome = System.getProperty("user.home")
        val wasHeadless = System.getProperty("java.awt.headless")
        try {
            System.setProperty("user.home", home.toString())
            main(arrayOf("--motion-check", dir.toString()))
        } finally {
            System.setProperty("user.home", wasHome)
            if (wasHeadless == null) System.clearProperty("java.awt.headless") else System.setProperty("java.awt.headless", wasHeadless)
        }
        try {
            // K1-1: the scroll section draws Main's rows with the desktop's faces (AWT), and the JDK writes its font
            // configuration cache under user.home (`.java/fonts/<version>/fcinfo*.properties`) the first time; that is the
            // JDK's, not Damage's — nothing else may be there (the config, `~/.damage`, above all)
            // every path under home, directories included (an empty `~/.damage/` fails), is `.java`, `.java/fonts` or below it
            val under = Files.walk(home).use { s -> s.filter { it != home }.map { home.relativize(it).toString() }.toList() }
            assertTrue(under.all { it == ".java" || it == ".java/fonts" || it.startsWith(".java/fonts/") },
                "nothing under the home directory but the JDK's font cache: $under")
            assertEquals(expected, files(dir).filter { !it.startsWith("scroll-") }, "the strips in DIR, nothing else (and the scroll strips)")
            assertTrue(files(dir).any { it.startsWith("scroll-") }, "the scroll strips in DIR")
        } finally { home.toFile().deleteRecursively(); dir.toFile().deleteRecursively() }
    }
}
