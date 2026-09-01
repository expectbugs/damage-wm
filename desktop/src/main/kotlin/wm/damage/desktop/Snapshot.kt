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
        // theme icons for the eyeball scenes (2026-09-01): the REAL desktop
        // theme (Papirus-Dark on beardos), drawn fallbacks where it misses
        shell.iconSource = ThemeIcons(AwtImages(), tmp.resolve("icons"),
            onLoaded = { shell.requestRepaint() })
        val reader = ReaderWindow(text, LocalContent(books), scope, AwtImages())
        shell.register(reader)
        val tmuxWin = wm.damage.core.windows.tmux.TmuxWindow(text, ScriptedTmux(), scope)
        shell.register(tmuxWin)
        val filesWin = wm.damage.core.windows.files.FilesWindow(text,
            wm.damage.core.windows.files.LocalFilesProvider(books, tmp.resolve("trash"), AwtImages()),
            scope, AwtImages(), initialBooksDir = cfg.booksDir)
        shell.register(filesWin)
        shell.start()
        // let the first wave of icon resolves land so the scenes show the theme
        delay(2_000)
        settle(shell)
        settle(shell)
        save(sim, out, "01-main-active")

        shell.postGesture(EvenHubMsg.EV_CLICK)          // into Reader (library)
        settle(shell)
        waitFor("library loads") { reader.summary().line.contains("book") }
        settle(shell)
        save(sim, out, "02-reader-library")

        // Open the focused row. Since 2026-08-31 that row can be a FOLDER
        // (they sort first) and a first open lands on the CHAPTER PICKER —
        // walk both, deterministically, by what the title says.
        shell.postGesture(EvenHubMsg.EV_CLICK)
        settle(shell)
        if (reader.title().startsWith("library")) {     // it was a folder: open its first book
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
        }
        waitFor("book opens") { !reader.title().startsWith("library") && reader.title() != "opening" }
        settle(shell)
        if (reader.title().endsWith("chapters")) {      // the first-open picker: from the beginning
            save(sim, out, "03b-reader-chapters")
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
        }
        waitFor("book page") { reader.title().contains("p.") }
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

        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)   // the chord opens the switcher (§1.3)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
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

        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // book -> library (maybe inside a folder)
        settle(shell)
        // Ascend to the library ROOT deterministically (§3a: double-tap
        // ascends a folder) — the old fixed two-tap walk stopped reaching
        // Main once the shelf grew folders, and 09/10 quietly showed the
        // wrong surfaces (caught by the tmux scene's waitFor, 2026-08-31).
        var ascend = 0
        while (reader.title() != "library" && ascend++ < 6) {
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            settle(shell)
        }
        waitFor("library root") { reader.title() == "library" }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // library -> Main
        settle(shell)
        save(sim, out, "09-main-back")

        // Tmux (TMUX.md): sessions, the live SGR grid with context rows and
        // the inverted cursor, the wrapped history, the keys level
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // Main cursor -> Tmux
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        settle(shell)
        save(sim, out, "09b-tmux-sessions")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // open 'claude'
        settle(shell)
        save(sim, out, "09c-tmux-live")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)        // scroll-up IS scrollback
        waitFor("tmux history") { tmuxWin.title().contains("history") }
        settle(shell)
        save(sim, out, "09d-tmux-history")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // -> live
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)             // -> keys
        settle(shell)
        save(sim, out, "09e-tmux-keys")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // keys -> live
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // live -> sessions
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // sessions -> Main
        settle(shell)

        // Files (2026-09-01): locations with capacity bars, a browse listing
        // with per-type theme icons, the floating context menu. The Main
        // cursor sits on Tmux (row 1) after the tmux scenes — one notch down.
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)                 // → Files row
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("files locations") { filesWin.summary().line.contains("locations") }
        delay(1_500)                                       // icon resolves land
        settle(shell)
        save(sim, out, "11-files-locations")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // first location (Root)
        waitFor("files listing") { filesWin.summary().detail.contains("folders") }
        delay(1_500)
        settle(shell)
        save(sim, out, "12-files-browse")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // tap = context menu
        waitFor("files menu") { shell.menuIsOpen }
        settle(shell)
        save(sim, out, "13-files-menu")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // cancel the menu
        settle(shell)
        repeat(2) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }    // → locations → Main
        settle(shell)
        repeat(2) { shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }      // cursor home
        settle(shell)

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

    private suspend fun waitFor(label: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < 30_000) delay(25)
        if (!cond()) failures.add("wait '" + label + "' never became true — snapshots are of the WRONG state")
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
