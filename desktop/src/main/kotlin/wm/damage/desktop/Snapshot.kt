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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val icons = ThemeIcons(AwtImages(), tmp.resolve("icons"),
            onLoaded = { shell.requestRepaint() })
        shell.iconSource = icons
        // scenes wait for the resolve queue to DRAIN, not a guessed delay —
        // a late resolve repainting between settle and save tore the PNG
        // (review 2026-09-01 icons#7)
        suspend fun iconsSettled() {
            val t0 = System.currentTimeMillis()
            while (icons.pending() > 0 && System.currentTimeMillis() - t0 < 30_000) delay(50)
            settle(shell)
        }
        val reader = ReaderWindow(text, LocalContent(books), scope, AwtImages())
        shell.register(reader)
        val tmuxWin = wm.damage.core.windows.tmux.TmuxWindow(text, ScriptedTmux(), scope)
        shell.register(tmuxWin)
        val filesWin = wm.damage.core.windows.files.FilesWindow(text,
            wm.damage.core.windows.files.LocalFilesProvider(books, tmp.resolve("trash"), AwtImages()),
            scope, AwtImages(), initialBooksDir = cfg.booksDir)
        shell.register(filesWin)
        val torrentsScripted = ScriptedTorrents()
        val torrentsWin = wm.damage.core.windows.torrents.TorrentsWindow(text, torrentsScripted, scope)
        shell.register(torrentsWin)
        val musicLib = ScriptedMusic()
        var musicSkew = 0L
        val musicClock: () -> Long = { System.currentTimeMillis() + musicSkew }
        val musicPlayer = wm.damage.core.windows.music.SimMusicPlayer(musicLib, musicClock)
        val musicWin = wm.damage.core.windows.music.MusicWindow(text, musicLib, musicPlayer, scope, clock = musicClock)
        shell.register(musicWin)
        /** Open the Music menu (from the NOW PLAYING root, 2026-09-03) and
         *  commit the row LABELLED [label] — by name, never by counting. */
        suspend fun musicMenu(label: String) {
            settle(shell, "music-menu-$label")
            if (!shell.menuIsOpen) {
                shell.postGesture(EvenHubMsg.EV_CLICK)
                waitFor("the Music menu for '$label'") { shell.menuIsOpen }
            }
            val i = shell.menuLabels.indexOfFirst { it == label || it.startsWith(label) }
            if (i < 0) { failures.add("Music menu row '$label' not in ${shell.menuLabels}"); return }
            repeat((i - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            shell.postGesture(EvenHubMsg.EV_CLICK)
        }
        shell.start()
        iconsSettled()   // the first wave of resolves lands before any scene
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
        iconsSettled()
        save(sim, out, "11-files-locations")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // first location (Root)
        waitFor("files listing") { filesWin.summary().detail.contains("folders") }
        iconsSettled()
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

        // Torrents (TORRENTS.md, 2026-09-01): the transfers with block bars
        // and the live lens, the transfer menu, the details document, the
        // tracker's categories and a listing, a torrent page, and the §4.8
        // keyboard opened by Search
        repeat(3) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // → Torrents row
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("torrents transfers") { torrentsWin.title() == "transfers" }
        iconsSettled()
        save(sim, out, "15-torrents-transfers")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // the transfer menu
        waitFor("torrents menu") { shell.menuIsOpen }
        settle(shell)
        save(sim, out, "16-torrents-menu")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // Details
        waitFor("torrents details") { torrentsWin.title() == "details" }
        settle(shell)
        save(sim, out, "17-torrents-details")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → transfers
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)        // wrap to the Torrents menu row
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the Torrents menu") { shell.menuIsOpen && shell.menuTitle == "torrents" }
        shell.postGesture(EvenHubMsg.EV_CLICK)             // Browse TorrentLeech
        waitFor("categories") { torrentsWin.title() == "browse" }
        iconsSettled()
        save(sim, out, "18-torrents-categories")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // Newest
        waitFor("listing") { torrentsWin.title() == "newest" && torrentsScripted.ops.any { it.startsWith("browse:0:1") } }
        iconsSettled()
        save(sim, out, "19-torrents-listing")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // the torrent page
        waitFor("torrent page") { torrentsWin.title() == "torrent" }
        settle(shell)
        save(sim, out, "20-torrents-page")
        repeat(3) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }    // → transfers (cursor still on the menu row)
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the Torrents menu again") { shell.menuIsOpen && shell.menuTitle == "torrents" }
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // → Search TorrentLeech
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the keyboard") { shell.keyboardIsOpen }
        settle(shell)
        save(sim, out, "21-keyboard-rows")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)        // → the qwerty row
        shell.postGesture(EvenHubMsg.EV_CLICK)             // enter it
        repeat(6) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // → 'u'
        shell.postGesture(EvenHubMsg.EV_CLICK)             // types 'u'
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // highlight on 'i'
        waitFor("typed") { shell.keyboardDraft() == "u" }
        settle(shell)
        save(sim, out, "22-keyboard-keys")
        repeat(2) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }    // KEY → ROW → cancel (draft kept)
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // transfers → Main
        settle(shell)
        repeat(3) { shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }      // cursor home
        settle(shell)

        // Music (MUSIC.md, 2026-09-02): the queue with the Now Playing card at
        // 480 and 288, the Music menu, browse, an artist, lyrics, YouTube
        // results, the Settings → Music rows
        repeat(4) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // → Music row
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("music opens") { musicWin.title() == "music" }
        // play AFTER entering: a track change while the window is on screen
        // shows on the card, not as a notice
        musicPlayer.playQueue(musicLib.catalog().tracks.take(6).map { it.ref() }, 0, wm.damage.core.windows.music.Mode.QUEUE, "Pink Floyd")
        musicSkew += 65_000                                             // 1:05 into "Time"
        waitFor("now playing") { musicWin.title() == "now playing" }
        iconsSettled()
        waitFor("the art arrived") { shell.isQuiescent() }
        settle(shell)
        save(sim, out, "30-music-nowplaying-480")
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the music menu") { shell.menuIsOpen && shell.menuTitle == "music" }
        settle(shell)
        save(sim, out, "31-music-menu")
        val bi = shell.menuLabels.indexOfFirst { it.startsWith("Browse") }
        repeat((bi - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("browse") { musicWin.title() == "browse" }
        iconsSettled()
        save(sim, out, "32-music-browse")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // Artists
        waitFor("artists") { musicWin.title() == "artists" }
        shell.postGesture(EvenHubMsg.EV_CLICK)             // the first artist
        waitFor("an artist") { musicWin.levelDepth() == 4 }
        iconsSettled()
        save(sim, out, "33-music-artist")
        repeat(3) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }    // → Now Playing
        musicMenu("Lyrics")
        waitFor("lyrics") { musicWin.title() == "lyrics" }
        waitFor("lyrics loaded") { shell.isQuiescent() }
        settle(shell)
        save(sim, out, "34-music-lyrics")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Now Playing
        musicMenu("Browse")
        waitFor("browse 2") { musicWin.title() == "browse" }
        repeat(7) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // → YouTube
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("yt keyboard") { shell.keyboardIsOpen }
        transport.injectText("dragula live")
        waitFor("yt results") { musicWin.title() == "youtube" && shell.isQuiescent() }
        iconsSettled()
        save(sim, out, "35-music-youtube")
        repeat(2) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }    // → queue
        settle(shell)
        // the card at 288: the window's Size row, applied on the next focus
        shell.services.runOnShell { musicWin.appSettings().first { it.name == "Size" }.apply("288") }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Main
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)             // → Music at 288
        waitFor("music at 288") { musicWin.title() == "now playing" }
        iconsSettled()
        settle(shell)
        save(sim, out, "36-music-nowplaying-288")
        // Music Mode (§8.3): the stacked surfaces at 480 with Bars, the short stack at 288 with Scope
        shell.services.runOnShell {
            musicWin.appSettings().first { it.name == "Music Mode · visualizer" }.apply("on")
            musicWin.appSettings().first { it.name == "Music Mode · queue peek" }.apply("on")
        }
        for ((h, viz) in listOf(480 to "Bars", 288 to "Scope")) {
            shell.services.runOnShell {
                musicWin.appSettings().first { it.name == "Size" }.apply("$h")
                musicWin.appSettings().first { it.name == "Visualizer" }.apply(viz)
            }
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)  // → Main
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)         // → Music at h
            waitFor("music at $h") { shell.currentWindowId() == "music" }
            musicMenu("Music Mode")
            waitFor("music mode ($h)") { shell.exclusiveMode }
            iconsSettled()
            waitFor("art + viz + lyrics landed ($h)") { shell.isQuiescent() }
            settle(shell)
            save(sim, out, if (h == 480) "38-music-mode-480-bars" else "39-music-mode-288-scope")
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)  // exits to the window
            waitFor("music mode exit ($h)") { !shell.exclusiveMode }
            settle(shell)
        }
        shell.services.runOnShell { musicWin.appSettings().first { it.name == "Size" }.apply("global") }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Main
        settle(shell)
        // Settings → Music (the Main cursor sits on the Music row; Settings is one notch down)
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        settle(shell)
        repeat(5) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // Global, Reader, Tmux, Files, Torrents, Music
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        settle(shell)
        save(sim, out, "37-music-settings")
        repeat(2) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }    // → categories → Main
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // cursor home (Reader)
        settle(shell)

        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // silent
        settle(shell)
        save(sim, out, "10-silent")

        shell.stop()
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    private suspend fun settle(shell: Shell, where: String = "?") {
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 15_000) delay(20)
        if (!shell.isQuiescent()) failures.add("shell did not settle at '$where' — snapshots may show mid-states")
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
