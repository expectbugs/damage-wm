package wm.damage.desktop

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.content.LocalContent
import wm.damage.core.geom.Geometry
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.windows.reader.ReaderWindow

/**
 * Drive the real shell through its surfaces and save what the LEFT LENS PANEL
 * holds — not the composed buffer: these PNGs are the post-wire truth (through
 * pack, RLE, deflate, fragmenting, the sim firmware, and its shadow). The
 * true-1x rule applies: no upscaling, same green mapping as design/shots.
 */
object Snapshot {
    private val failures = ArrayList<String>()

    fun run(cfg: Config, outDir: Path): Nothing {
        Files.createDirectories(outDir)
        try {
            runBlocking { script(cfg, outDir) }
        } catch (e: Throwable) {
            e.printStackTrace()
            failures.add("snapshot run crashed: $e")
        }
        println("snapshots in $outDir")
        if (failures.isNotEmpty()) {
            println("snapshot: ${failures.size} FAILURE(S):")
            for (f in failures) println("  - $f")
            kotlin.system.exitProcess(1)
        }
        kotlin.system.exitProcess(0)
    }

    private suspend fun script(cfg: Config, out: Path) {
        val tmp = Files.createTempDirectory("damage-snap")
        val books = Path.of(cfg.booksDir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val text = AwtText()
        val shell = Shell(text, transport, Persistence(tmp.resolve("state.json")), null, scope)
        val reader = ReaderWindow(text, LocalContent(books), scope)
        shell.register(reader)
        shell.start()
        settle(shell)
        save(sim, out, "01-main-active")

        shell.postGesture(EvenHubMsg.EV_CLICK)          // into Reader (library)
        settle(shell)
        waitFor { reader.summary().line.contains("book") }
        settle(shell)
        save(sim, out, "02-reader-library")

        shell.postGesture(EvenHubMsg.EV_CLICK)          // open the first book
        waitFor { reader.levelDepth() >= 2 }
        settle(shell)
        save(sim, out, "03-reader-book")

        repeat(12) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        settle(shell)
        save(sim, out, "04-reader-scrolled")

        shell.postGesture(EvenHubMsg.EV_CLICK)          // actions level
        settle(shell)
        save(sim, out, "05-reader-actions")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        settle(shell)

        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)   // switcher
        settle(shell)
        save(sim, out, "06-switcher")
        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)   // cancel
        settle(shell)

        shell.postNotice(wm.damage.core.shell.Notifications.Notice(
            "SMS · MOM", "t1", "on my way, should be there in about twenty minutes", "14:32"))
        settle(shell)
        save(sim, out, "07-notification-arrived")
        delay(3_000)
        settle(shell)
        save(sim, out, "08-notification-focused")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // dismiss + read
        settle(shell)

        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // book -> library
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // library -> Main
        settle(shell)
        save(sim, out, "09-main-back")

        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // silent
        settle(shell)
        save(sim, out, "10-silent")

        shell.stop()
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    private suspend fun settle(shell: Shell) {
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 15_000) delay(20)
        if (!shell.isQuiescent()) failures.add("shell did not settle — snapshots may show mid-states")
        delay(50)
    }

    private suspend fun waitFor(cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < 30_000) delay(25)
        if (!cond()) failures.add("a wait condition never became true — snapshots are of the WRONG state")
    }

    private fun save(sim: GlassFirmwareSim, dir: Path, name: String) {
        val img = BufferedImage(Geometry.PANEL_W, Geometry.PANEL_H, BufferedImage.TYPE_INT_RGB)
        val ctx = sim.left
        for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
            val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
            val n = if (x and 1 == 0) b shr 4 else b and 0x0F
            val v = n * 17
            img.setRGB(x, y, ((v * 0.16).toInt() shl 16) or (minOf(255, (v * 1.05).toInt()) shl 8) or (v * 0.34).toInt())
        }
        ImageIO.write(img, "png", dir.resolve("$name.png").toFile())
        println("  wrote $name.png")
    }
}
