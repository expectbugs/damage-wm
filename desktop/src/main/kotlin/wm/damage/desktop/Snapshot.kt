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
import kotlinx.coroutines.launch
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
import wm.damage.core.transport.effects
import wm.damage.core.wire.EvenHubMsg

/**
 * Drive the real shell through its surfaces and save what the LEFT LENS PANEL
 * holds — not the composed buffer: these PNGs are the post-wire truth (through
 * pack, RLE, deflate, fragmenting, the sim firmware, and its shadow). The
 * true-1x rule applies: no upscaling, same green mapping as design/shots.
 */
object Snapshot {
    private val failures = ArrayList<String>()
    /** Stage OP: the Files providers' "Open on PC" — a check opens nothing on the PC. */
    private val opens = CheckOpensNothing()
    /** The shell every `save` samples through — see [save]. */
    private var snapShell: Shell? = null

    fun run(cfg: Config, outDir: Path): Nothing {
        Files.createDirectories(outDir)
        // build S1-2 J0: each arrangement's shell journal is written beside its pictures (`tools/flushdiff.py` reads
        // it); a run into a directory used before starts both files afresh, so one journal is one run
        Files.deleteIfExists(outDir.resolve(JOURNAL))
        Files.deleteIfExists(outDir.resolve("phone").resolve(JOURNAL))
        try {
            runBlocking {
                script(cfg, outDir)
                feedRestartScenes(outDir)
                animationsScenes(outDir)
                phoneArrangement(cfg, outDir)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            failures.add("snapshot run crashed: $e")
        }
        opens.failure()?.let { failures.add(it) }
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
        val text = wm.damage.core.comp.CachedText(AwtText())
        val persistence = Persistence(tmp.resolve("state.json"))
        val shell = Shell(text, transport, persistence, out.resolve(JOURNAL), scope)
        snapShell = shell
        // theme icons for the eyeball scenes (2026-09-01): the REAL desktop
        // theme (Papirus-Dark on beardos), drawn fallbacks where it misses
        val icons = ThemeIcons(AwtImages(), tmp.resolve("icons"),
            onLoaded = { shell.requestRepaint() })
        shell.iconSource = icons
        // scenes wait for the resolve queue to DRAIN, not a guessed delay —
        // a late resolve repainting between settle and save tore the PNG
        // (review 2026-09-01 icons#7)
        // 0.65 P7 item 11: ONE evaluation decides — the shell at rest, no resolve in flight and none ended while
        // those two were read. The old shape waited for `pending()` to read 0 and then settled: a chain's miss
        // (`IconPaint.draw` walks `IconNames.PROJECT` / `MOUNT` one pending name at a time) leaves nothing in flight
        // until the repaint it asked for requests the next name, so the wait could end between the two steps and
        // the save took the drawn folder (`11-files-locations`' project and mount rows in two of eleven sets, P5-verify §5)
        suspend fun iconsSettled() = waitFor("theme icons settled") {
            val c = icons.completions()
            shell.isQuiescent() && icons.pending() == 0 && icons.completions() == c
        }
        // the snapshot judge's PC arrangement (`CONTRACT.md` §11.1; build S1-1 P4): Reader's presenter over a source on
        // a PC record — a scratch one in this run's temp directory, never the service's
        val pcRecord = wm.damage.core.app.AppHost.inScratchDir(Files.createDirectories(tmp.resolve("record")))
        val pcCore = wm.damage.core.windows.reader.ReaderCore(LocalContent(books), scope)
        val reader = wm.damage.core.windows.reader.ReaderGlasses(text, pcCore,
            wm.damage.core.app.LocalWindowSource(pcCore, wm.damage.core.app.HostBackend(pcRecord,
                wm.damage.core.app.Peer.LOCAL.device, wm.damage.core.app.OpQueue.newStream())), scope, AwtImages())
        shell.register(reader)
        settingsOn(shell, pcRecord)            // build S1-5 S: the shell's settings on the same scratch record
        val tmuxScripted = ScriptedTmux()
        // build S1-3 B16: Tmux's presenter over a source on the same scratch record; its alerts raised once into that
        // record by its core (attached below, beside Torrents' notices) and shown from it by this shell's notice mirror.
        // No new local lives past here: this method is at the JVM's size limit, and each one is saved at every pause
        val tmuxWin = tmuxOn(text, tmuxScripted, scope, pcRecord)
        shell.register(tmuxWin)
        // build S1-2 J0, S1-3 close: Files' pinned world (`SceneFiles`: the locations, a made-up root, folder and trash);
        // the provider under it reads no mount table (`SelfCheck`'s precedent)
        val sceneFiles = SceneFiles(wm.damage.core.windows.files.LocalFilesProvider(books, tmp.resolve("trash"), opens,
            AwtImages(), mountsFile = tmp.resolve("no-mounts")), cfg.booksDir)
        // S1-2 Q5: Files' presenter on the scratch PC record (its rows), the provider behind the PC's job slot
        val filesPc = wm.damage.core.windows.files.FilesPc(pcRecord, sceneFiles, cfg.booksDir, null, null)
        val filesWin = wm.damage.core.windows.files.FilesGlasses(text, filesPc.core, filesPc.glassesSource(), filesPc.slotted,
            scope, AwtImages(), initialBooksDir = cfg.booksDir)
        shell.register(filesWin)
        val torrentsScripted = ScriptedTorrents()
        // build S1-2 Q4: Torrents' presenter over a source on the same scratch record, its notices raised once into
        // that record by its core and shown from it by this shell's notice mirror (the PC's arrangement, `runShell`'s)
        val pcNotices = wm.damage.core.app.NoticeCore(pcRecord)
        val pcMirror = wm.damage.core.app.NoticeMirror(wm.damage.core.app.HostBackend(pcRecord,
            wm.damage.core.app.Peer.LOCAL.device, wm.damage.core.app.OpQueue.newStream()))
        shell.noticeInput = pcMirror
        pcMirror.attach(shell.noticeSink)
        val torrentsCore = wm.damage.core.windows.torrents.TorrentsCore()
        torrentsCore.attachNotices(torrentsScripted, torrentsScripted.epoch, pcNotices)
        tmuxWin.core.attachAlerts(tmuxScripted, pcNotices) { tmuxState(pcRecord) }
        val torrentsWin = wm.damage.core.windows.torrents.TorrentsGlasses(text, torrentsScripted, scope, torrentsCore,
            wm.damage.core.app.LocalWindowSource(torrentsCore, wm.damage.core.app.HostBackend(pcRecord,
                wm.damage.core.app.Peer.LOCAL.device, wm.damage.core.app.OpQueue.newStream())), recordNotices = { true })
        shell.register(torrentsWin)
        val music = MusicScene(text, scope)
        shell.register(music.win)
        val gamesWin = wm.damage.core.windows.games.GamesWindow(text, scope)
        // A PINNED world (GamesCheck's precedent, `Roster(worldSeed = ...)`).
        // Seeded off the wall clock this harness dealt a different tournament
        // every run, so its scenes were not the same scenes twice and its
        // waits could sit on a legitimate outcome the script did not expect:
        // one run in four the fold ended the whole TOURNAMENT rather than the
        // hand, `table` went null, and "the hand finishes" never became true
        // (review 2026-09-05).
        gamesWin.roster.worldSeed = 20260905L
        shell.register(gamesWin)
        // build S1-3 J: Feed on the scene's clock — the scripted feed's stamps and the window's clock from one fixed
        // instant that only the script moves — so every age, flag stamp and archive date reads the same in every run
        // (on the wall clock "fetched 4m ago" turned "5m" once a run took a minute to reach it); the provider counted
        // so a scene waits for its answers ([SceneFeed])
        val feedClock = SceneClock(FEED_T0)
        val feedScripted = wm.damage.core.windows.feed.ScriptedFeed(nowMs = FEED_T0)
        val feedProvider = SceneFeed(feedScripted)
        // build S1-3 B16: Feed's presenter over a source on the same scratch record; its core raises the notices once into
        // that record (shown by this shell's mirror), as `runShell` arranges it
        val feedCore = wm.damage.core.windows.feed.FeedCore(feedProvider)
        feedCore.startOnPc(pcRecord, pcNotices) { _, _ -> }
        val feedWin = wm.damage.core.windows.feed.FeedGlasses(text, feedProvider, scope, feedCore,
            wm.damage.core.app.LocalWindowSource(feedCore, wm.damage.core.app.HostBackend(pcRecord,
                wm.damage.core.app.Peer.LOCAL.device, wm.damage.core.app.OpQueue.newStream())), clock = feedClock::now)
        shell.register(feedWin)
        shell.start()
        iconsSettled()   // the first wave of resolves lands before any scene
        readerScenes(shell, sim, reader, out)

        // Tmux (TMUX.md): sessions, the live SGR grid with context rows and
        // the inverted cursor, the wrapped history, the keys level
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // Main cursor -> Tmux
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        settle(shell)
        tmuxScenes(shell, sim, out, tmuxWin)

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
        filesMoreScenes(shell, sim, out, filesWin, sceneFiles, ::iconsSettled)   // ends on the locations list
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Main
        settle(shell)
        repeat(2) { shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }      // cursor home
        settle(shell)

        torrentsScenes(shell, sim, out, transport, torrentsWin, torrentsScripted, ::iconsSettled)   // ends on the transfers
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // transfers → Main
        settle(shell)
        repeat(3) { shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }      // cursor home
        settle(shell)

        // Music (MUSIC.md, 2026-09-02): scenes 30 to 39, moved into [musicScenes] unchanged (build S1-4 J: the phone
        // arrangement takes them too); entered from Main's Music row, four notches down from the cursor's home
        musicScenes(shell, sim, out, transport, music, ::iconsSettled) {
            repeat(4) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // → Music row
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)
        }
        // Walk Main's cursor by COMMITTING and checking rather than by counting
        // rows: the row order is the registration order and one new window
        // silently shifts every count (the 2026-09-03 menu-row lesson).
        suspend fun toMain() {
            var guard = 0
            while (shell.currentWindowId() != null && guard++ < 10) {
                shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                settle(shell, "back-to-main")
            }
        }
        suspend fun toWindow(id: String) {
            toMain()
            for (k in 0 until 12) {
                shell.postGesture(EvenHubMsg.EV_CLICK)
                settle(shell, "commit-$id")
                if (shell.currentWindowId() == id) return
                toMain()
                shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                settle(shell, "walk-to-$id")
            }
            failures.add("could not reach the '$id' window from Main")
        }

        // build S1-4 J: Size back to global, then the Output menu at 480 (the named difference of S1-4: the PC row);
        // ends on Main with its cursor on the Music row, as before
        musicOutputScene(shell, sim, out, music)
        // Settings → Music, both by NAME (build W fix round 1): Settings is reached by committing and checking, and
        // the category through Settings' own `cat:` deep link. Counting rows ("Settings is one notch down", then five
        // categories) drew Games · standings under this name once Games was registered after Music.
        toWindow("settings")
        shell.services.runOnShell { shell.services.openWindow("settings", "cat:Music") }
        waitFor("settings · music") { shell.currentWindowId() == "settings" && shell.isQuiescent() }
        settle(shell)
        save(sim, out, "37-music-settings")
        musicSettingsLower(shell, sim, out)
        // Settings → Reader (build S1-1 P4, `HANDOFF.md` §79.8: no scene showed it), by the same `cat:` deep link,
        // so the Reader conversion's rows are watched by the snapshot judge
        readerSettingsScene(shell, sim, out)
        // Settings → Torrents and Settings → Files (build S1-2 J0): the two windows this build converts
        settingsCategoryScene(shell, sim, out, "Torrents", "37c-torrents-settings")
        settingsCategoryScene(shell, sim, out, "Files", "37d-files-settings")
        // Settings → Feed and Settings → Tmux (build S1-3 J): the two windows S1-3 converts, at their defaults
        feedTmuxSettingsScenes(shell, sim, out)
        repeat(2) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }    // → categories → Main
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // cursor home (Reader)
        settle(shell)

        // Games: its own function (the script method is near the JVM's 64 KB limit; stage M of S1-3)
        gamesScenes(shell, sim, out, gamesWin, ::toWindow, ::iconsSettled)

        feedScenes(shell, sim, out, feedWin, feedScripted, ::toWindow, ::iconsSettled)
        // build S1-3 J: the states S1-3's presenters move onto the PC record (spec B6, B14), each ending on Main
        feedMoreScenes(shell, sim, out, transport, feedWin, feedScripted, feedProvider, feedClock, pcRecord, ::toWindow)
        tmuxMoreScenes(shell, sim, out, tmuxWin, tmuxScripted, ::toWindow, pcRecord)

        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // silent
        settle(shell)
        save(sim, out, "10-silent")

        shell.stop()
        reader.detach()
        torrentsWin.detach()
        torrentsCore.detachNotices()
        tmuxWin.core.detachAlerts()
        pcMirror.detach(shell.noticeSink)
        pcMirror.close()
        pcRecord.close()
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    /**
     * Music's scene world (build S1-4 J, moved out of [script] so the phone arrangement builds the same one): the scripted
     * library, the stand-in player and today's window. C1-R6 B6-7: the player's position runs on the scene's clock — a
     * fixed instant that only the scene moves (the skew) — so every render shows the same elapsed time; on the wall clock
     * the card read "1:05" or "1:06" by how long a run took to reach it. The window keeps the wall clock, with the skew,
     * for its card pace and notices.
     */
    private class MusicScene(text: wm.damage.core.text.TextRasterizer, scope: CoroutineScope,
                             wc: wm.damage.core.app.RecordBackend? = null) {
        val lib = ScriptedMusic()
        val skew = java.util.concurrent.atomic.AtomicLong(0)
        private val clock: () -> Long = { System.currentTimeMillis() + skew.get() }
        // build S1-4 K: over [wc] (the phone's working copy) the presenter and the stand-in player read and write Music's
        // record as the phone builds them (`ShellService`): the converted path; without it, the factory's own record
        val source = wc?.let { wm.damage.core.app.LocalWindowSource(wm.damage.core.windows.music.MusicCore(), it) }
        val player = wm.damage.core.windows.music.SimMusicPlayer(lib, now = { FEED_T0 + skew.get() }, source = source)
        val win: wm.damage.core.windows.music.MusicGlasses = if (source != null)
            wm.damage.core.windows.music.MusicGlasses(text, lib, player, scope, source.core as wm.damage.core.windows.music.MusicCore, source, clock)
        else wm.damage.core.windows.music.MusicWindow(text, lib, player, scope, clock = clock)
    }

    /** Open the Music menu (from the NOW PLAYING root, 2026-09-03) and
     *  commit the row LABELLED [label] — by name, never by counting. */
    private suspend fun musicMenu(shell: Shell, label: String) {
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

    /**
     * Music's scenes 30 to 39 (MUSIC.md, 2026-09-02): the queue with the Now Playing card at 480 and 288, the Music menu,
     * browse, an artist, lyrics, YouTube results, Music Mode at two sizes. Moved out of [script] unchanged (build S1-4 J);
     * [enter] opens the window from Main. Ends in the window at 288, out of Music Mode.
     */
    private suspend fun musicScenes(shell: Shell, sim: GlassFirmwareSim, out: Path, transport: SimTransport, m: MusicScene,
        iconsSettled: suspend () -> Unit, enter: suspend () -> Unit) {
        enter()
        waitFor("music opens") { m.win.title() == "music" }
        // play AFTER entering: a track change while the window is on screen
        // shows on the card, not as a notice
        m.player.playQueue(m.lib.catalog().tracks.take(6).map { it.ref() }, 0, wm.damage.core.windows.music.Mode.QUEUE, "Pink Floyd")
        m.skew.addAndGet(65_000)                                             // 1:05 into "Time"
        waitFor("now playing") { m.win.title() == "now playing" }
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
        waitFor("browse") { m.win.title() == "browse" }
        iconsSettled()
        save(sim, out, "32-music-browse")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // Artists
        waitFor("artists") { m.win.title() == "artists" }
        shell.postGesture(EvenHubMsg.EV_CLICK)             // the first artist
        waitFor("an artist") { m.win.levelDepth() == 4 }
        iconsSettled()
        save(sim, out, "33-music-artist")
        repeat(3) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }    // → Now Playing
        musicMenu(shell, "Lyrics")
        waitFor("lyrics") { m.win.title() == "lyrics" }
        waitFor("lyrics loaded") { shell.isQuiescent() }
        settle(shell)
        save(sim, out, "34-music-lyrics")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Now Playing
        musicMenu(shell, "Browse")
        waitFor("browse 2") { m.win.title() == "browse" }
        repeat(7) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // → YouTube
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("yt keyboard") { shell.keyboardIsOpen }
        transport.injectText("dragula live")
        waitFor("yt results") { m.win.title() == "youtube" && shell.isQuiescent() }
        iconsSettled()
        save(sim, out, "35-music-youtube")
        repeat(2) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }    // → queue
        settle(shell)
        // the card at 288: the window's Size row, applied on the next focus
        shell.services.runOnShell { m.win.appSettings().first { it.name == "Size" }.apply("288") }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Main
        settle(shell)
        // C1-R6 B6-7: the scene's clock moves on half a second between the two cards, as the wall clock did: the
        // wall-clock renders read in build C1 drew the 480 card before 65.211 s (5 of its bar's 38 blocks lit; the 6th
        // lights there) and this one after it (6 lit, "1:05" or "1:06")
        m.skew.addAndGet(500)
        shell.postGesture(EvenHubMsg.EV_CLICK)             // → Music at 288
        waitFor("music at 288") { m.win.title() == "now playing" }
        iconsSettled()
        settle(shell)
        save(sim, out, "36-music-nowplaying-288")
        // Music Mode (§8.3): the stacked surfaces at 480 with Bars, the short stack at 288 with Scope
        shell.services.runOnShell {
            m.win.appSettings().first { it.name == "Music Mode · visualizer" }.apply("on")
            m.win.appSettings().first { it.name == "Music Mode · queue peek" }.apply("on")
        }
        for ((h, viz) in listOf(480 to "Bars", 288 to "Scope")) {
            shell.services.runOnShell {
                m.win.appSettings().first { it.name == "Size" }.apply("$h")
                m.win.appSettings().first { it.name == "Visualizer" }.apply(viz)
            }
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)  // → Main
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)         // → Music at h
            waitFor("music at $h") { shell.currentWindowId() == "music" }
            musicMenu(shell, "Music Mode")
            waitFor("music mode ($h)") { shell.exclusiveMode }
            iconsSettled()
            waitFor("art + viz + lyrics landed ($h)") { shell.isQuiescent() }
            settle(shell)
            save(sim, out, if (h == 480) "38-music-mode-480-bars" else "39-music-mode-288-scope")
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)  // exits to the window
            waitFor("music mode exit ($h)") { !shell.exclusiveMode }
            settle(shell)
        }
    }

    /**
     * Build S1-4 J: Size back to global, out to Main and in again, and the Output menu at 480 — the one row list S1-4
     * names as changing (it gains the PC). Ends on Main with its cursor on the Music row, as [musicScenes] left it.
     */
    private suspend fun musicOutputScene(shell: Shell, sim: GlassFirmwareSim, out: Path, m: MusicScene) {
        shell.services.runOnShell { m.win.appSettings().first { it.name == "Size" }.apply("global") }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Main
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)             // → Music at 480
        waitFor("music at 480") { m.win.title() == "now playing" }
        musicMenu(shell, "Output")
        waitFor("the output menu") { shell.menuIsOpen && shell.menuTitle == "output" }
        settle(shell, "music-output-menu")
        save(sim, out, "31b-music-output-menu")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // cancel the menu
        settle(shell, "music-output-cancel")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Main
        settle(shell)
    }

    /**
     * Build S1-4 J: Settings → Music's lower rows, from 37-music-settings (the cursor on its first row): twelve notches
     * down (Volume … Lyrics sources: the Output row, the shared rows S1-4 moves onto the record), then twelve more
     * (the glasses rows down to Size). Every row at its default.
     */
    private suspend fun musicSettingsLower(shell: Shell, sim: GlassFirmwareSim, out: Path) {
        repeat(12) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        settle(shell, "music-settings-lower")
        save(sim, out, "37h-music-settings-lower")
        repeat(12) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        settle(shell, "music-settings-end")
        save(sim, out, "37i-music-settings-end")
    }

    /** The Games part of [script], moved out unchanged with its menu helper (S1-3's merge): the script method is near the
     *  JVM's 64 KB limit. Ends on Main, as it did there. */
    private suspend fun gamesScenes(shell: Shell, sim: GlassFirmwareSim, out: Path,
        gamesWin: wm.damage.core.windows.games.GamesWindow,
        toWindow: suspend (String) -> Unit, iconsSettled: suspend () -> Unit) {
        /** Open the Games menu row LABELLED [label] — by name, never by counting. */
        suspend fun gamesMenu(label: String) {
            settle(shell, "games-menu-$label")
            val i = shell.menuLabels.indexOfFirst { it == label || it.startsWith(label) }
            if (i < 0) { failures.add("Games menu row '$label' not in ${shell.menuLabels}"); return }
            repeat((i - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell, "games-menu-$label-committed")
        }
        // ---------------------------------------------------------------- Games
        /** Rest the Games-root cursor on the row NAMED [label] — by name,
         *  never by counting: the root gained a row once already. */
        suspend fun gamesRow(label: String) {
            for (k in 0 until 8) {
                settle(shell, "games-row-$label")
                if (gamesWin.rootRow == label) return
                shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            }
            failures.add("the Games root has no '$label' row (rest: ${gamesWin.rootRow})")
        }

        toWindow("games")
        waitFor("the games root") { shell.currentWindowId() == "games" && gamesWin.levelName == "GAMES" }
        // the snapshots want SETTLED states, and the pacer is a 600 ms clock
        // by design: put it on instant so every scene below is the state the
        // script asked for rather than whatever the pacer was mid-way through
        shell.services.runOnShell { gamesWin.appSettings().first { it.name == "Bot pace" }.apply("instant") }
        iconsSettled()
        settle(shell, "games-root")
        save(sim, out, "40-games-root")

        // the Bankroll row's lens carries the drawn seven-segment scoreboard
        gamesRow("Bankroll")
        settle(shell, "games")
        save(sim, out, "41-games-bankroll-lens")

        // sit down at Regular and play to Adam's first decision
        gamesRow("Hold'em")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // → the table select
        waitFor("the table list") { gamesWin.levelName == "TABLES" }
        settle(shell, "games")
        save(sim, out, "42-games-tables")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // Regular
        waitFor("the buy-in confirm") { shell.menuIsOpen }
        save(sim, out, "43-games-buyin")
        gamesMenu("Sit down")
        waitFor("your first decision") { gamesWin.isMyTurn }
        settle(shell, "games")
        save(sim, out, "44-games-table-480")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // the action level
        waitFor("the action menu") { shell.menuIsOpen }
        save(sim, out, "45-games-actions")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // row 0 → the confirm
        waitFor("the confirm") { shell.menuIsOpen && shell.menuLabels.firstOrNull() == "Cancel" }
        save(sim, out, "46-games-confirm")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // cancel
        settle(shell, "games")

        // Play the hand OUT and catch its TERMINAL state — the densest thing the
        // table draws: the result banner, "tap to deal", the board and the hand
        // history all at once (verdict 29: it stays up until you act). Adam
        // always takes the contextual row, so this is a showdown when the bots
        // reach one and a fold-out otherwise; the file keeps its `showdown`
        // name from when it was written.
        //
        // 🔴 One action is not a hand. The script used to take a single row and
        // then wait for a result, which only worked when that row happened to
        // be "Fold": Check+Fold are ONE contextual row (verdict 12), so with no
        // bet to call it CHECKS, the flop comes out, and the table waits for
        // Adam again — forever, correctly. The snapshot named "showdown" then
        // showed a live table and the wait sat out its bound (review
        // 2026-09-05, found once the world seed was pinned). Act every time it
        // is Adam's turn, exactly as `GamesWindowTest` does.
        var acts = 0
        while (!gamesWin.handIsComplete && gamesWin.tableRunning && acts++ < 12) {
            waitFor("your turn (act $acts)") {
                gamesWin.isMyTurn || gamesWin.handIsComplete || !gamesWin.tableRunning
            }
            if (!gamesWin.isMyTurn || gamesWin.handIsComplete) break
            shell.postGesture(EvenHubMsg.EV_CLICK)
            waitFor("the action menu (act $acts)") { shell.menuIsOpen }
            // the contextual row, by NAME: "Fold" facing a bet, "Check" when
            // checking is free — never by position
            gamesMenu(shell.menuLabels.firstOrNull { it.startsWith("Fold") || it.startsWith("Check") }
                ?: shell.menuLabels.first())
            waitFor("the confirm (act $acts)") { shell.menuIsOpen && shell.menuLabels.firstOrNull() == "Cancel" }
            shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // → the confirming row
            settle(shell, "games-confirm-$acts")
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell, "games-acted-$acts")
        }
        waitFor("the hand finishes") { gamesWin.handIsComplete || !gamesWin.tableRunning }
        if (!gamesWin.handIsComplete) failures.add(
            "the hand never reached a result after $acts actions " +
                "[running=${gamesWin.tableRunning} myTurn=${gamesWin.isMyTurn} " +
                "board=${gamesWin.boardShown}] — '50-games-showdown' is of a live table")
        waitFor("the board finishes revealing") {
            (gamesWin.handIsComplete || !gamesWin.tableRunning) && shell.isQuiescent()
        }
        settle(shell, "games-showdown")
        save(sim, out, "50-games-showdown")

        // the standings, and one character's career
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → the games root
        waitFor("back at the games root") { gamesWin.levelName == "GAMES" }
        gamesRow("Standings")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the standings") { gamesWin.levelName == "STANDINGS" }
        settle(shell, "games")
        save(sim, out, "48-games-standings")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("a character") { gamesWin.levelName == "CHARACTER" }
        settle(shell, "games")
        save(sim, out, "49-games-character")
        repeat(2) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }
        waitFor("the games root again") { gamesWin.levelName == "GAMES" }
        settle(shell, "games")

        // the same table at 288 — the tight rung of the ladder
        shell.services.runOnShell { gamesWin.appSettings().first { it.name == "Size" }.apply("288") }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Main
        settle(shell, "games")
        toWindow("games")
        waitFor("games at 288") { shell.currentWindowId() == "games" }
        settle(shell, "games")
        gamesRow("Hold'em")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // → the live table
        waitFor("the table at 288") { gamesWin.levelName == "TABLE" }
        settle(shell, "games")
        save(sim, out, "47-games-table-288")
        for (h in listOf(352, 416)) {
            shell.services.runOnShell { gamesWin.appSettings().first { it.name == "Size" }.apply("$h") }
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)  // → Main
            settle(shell, "games")
            toWindow("games")
            gamesRow("Hold'em")
            shell.postGesture(EvenHubMsg.EV_CLICK)
            waitFor("the table at $h") { gamesWin.levelName == "TABLE" }
            settle(shell, "games")
            save(sim, out, "5${if (h == 352) 1 else 2}-games-table-$h")
        }
        shell.services.runOnShell { gamesWin.appSettings().first { it.name == "Size" }.apply("global") }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        settle(shell, "games")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        settle(shell, "games")
    }

    /**
     * The snapshot judge's phone arrangement (`CONTRACT.md` §11.1; build S1-1 spec §5): Reader's scenes rendered again,
     * into `DIR/phone/`, with Reader's presenter over the phone's working copy — fed by an `AppClient` from a content
     * host on loopback that serves a scratch PC record over the same books, and the book bytes through `RemoteContent`
     * into a temp cache. Every other window is built as in the first arrangement, so Main and the switcher are the same.
     * Each render is compared with the same base scene (`tools/snapdiff.py BASE DIR/phone --subset`).
     * Build S1-2 Q4 (the spec's §3.2): Torrents' scenes (15–25, 37c) too, the presenter over the phone's provider on the
     * window channel to a `TorrentsService` of the scripted provider on the same loopback host.
     */
    private suspend fun phoneArrangement(cfg: Config, outRoot: Path) {
        val out = Files.createDirectories(outRoot.resolve("phone"))
        val tmp = Files.createTempDirectory("damage-snap-phone")
        val books = Path.of(cfg.booksDir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val token = "snapshot-" + java.util.UUID.randomUUID()
        // the PC: a scratch record and its app lane, on loopback port 0
        val pcRecord = wm.damage.core.app.AppHost.inScratchDir(Files.createDirectories(tmp.resolve("pc")))
        val net = wm.damage.core.app.AppNet(pcRecord)
        // build S1-2 Q4: the PC's torrents provider (the scripted one, made-up transfers), served to the phone over the
        // window channel as the service serves its own (`TorrentsService`)
        val torrentsScripted = ScriptedTorrents()
        // build S1-2 Q4: the PC's core raises Torrents' notices once into the scratch record (the phone shows them
        // through its mirror over the working copy below)
        val pcNotices = wm.damage.core.app.NoticeCore(pcRecord)
        val pcTorrents = wm.damage.core.windows.torrents.TorrentsCore()
        pcTorrents.attachNotices(torrentsScripted, torrentsScripted.epoch, pcNotices)
        // S1-2 Q5: Files on the scratch PC record behind its job slot, over the first arrangement's pinned world
        // (`SceneFiles`), served to the phone through the window channel
        val phoneFiles = SceneFiles(wm.damage.core.windows.files.LocalFilesProvider(books, tmp.resolve("trash"), opens,
            AwtImages(), mountsFile = tmp.resolve("no-mounts")), cfg.booksDir)
        val phoneFilesPc = wm.damage.core.windows.files.FilesPc(pcRecord, phoneFiles, cfg.booksDir, null, null)
        // bound to loopback, and gated like every listener (build W): the strict gate answers loopback only here
        val host = wm.damage.core.content.ContentHostServer(LocalContent(books), 0, token, app = net,
            // the merge of Q4 and Q5: both windows on the one window channel
            win = mapOf("torrents" to wm.damage.core.windows.torrents.TorrentsService(torrentsScripted),
                "files" to wm.damage.core.windows.files.FilesService(phoneFilesPc.slotted)),
            reach = wm.damage.core.net.ReachGate(wm.damage.core.net.Side.PHONE),
            bindAddress = java.net.InetAddress.getLoopbackAddress())
        host.start()
        // the phone: its core executor, working copy and lane
        val ex = wm.damage.core.app.CoreExecutor("damage-core-phone")
        val wc = wm.damage.core.app.WorkingCopy(tmp.resolve("workcopy"), wm.damage.core.app.AppHost.WINDOWS, ex)
        wc.start { stream ->
            wm.damage.core.app.AppClient("127.0.0.1", host.boundPort, wm.damage.core.app.AppClient.Credential.Token(token),
                stream, wc, scope)
        }
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val text = wm.damage.core.comp.CachedText(AwtText())
        val persistence = Persistence(tmp.resolve("state.json"))
        val shell = Shell(text, transport, persistence, out.resolve(JOURNAL), scope)
        val before = snapShell
        snapShell = shell
        val icons = ThemeIcons(AwtImages(), tmp.resolve("icons"), onLoaded = { shell.requestRepaint() })
        shell.iconSource = icons
        suspend fun iconsSettled() = waitFor("theme icons settled (phone)") {
            val c = icons.completions()
            shell.isQuiescent() && icons.pending() == 0 && icons.completions() == c
        }
        var onLibraryChanged: () -> Unit = {}
        val rc = wm.damage.core.content.RemoteContent("127.0.0.1", host.boundPort, token, tmp.resolve("bookcache"),
            onLibraryChanged = { onLibraryChanged() })
        val core = wm.damage.core.windows.reader.ReaderCore(rc, scope)
        val source = wm.damage.core.app.LocalWindowSource(core, wc)
        val reader = wm.damage.core.windows.reader.ReaderGlasses(text, core, source, scope, AwtImages(),
            device = wc.device, buildsLine = { wc.client?.contractState?.line(core.window) })
        onLibraryChanged = { reader.libraryChanged() }
        shell.register(reader)
        // every other window as in the first arrangement (the same order, the same scripted sources, the same seed)
        val tmuxScripted = ScriptedTmux()
        // build S1-3 B16: the PC's core raises Tmux's alerts once into the scratch record (the phone shows them through
        // its mirror below); the phone's presenter reads its rows and mutes from the working copy
        val pcTmux = wm.damage.core.windows.tmux.TmuxCore()
        pcTmux.attachAlerts(tmuxScripted, pcNotices) { tmuxState(pcRecord) }
        val phoneTmux = wm.damage.core.windows.tmux.TmuxCore()
        val tmuxWin = wm.damage.core.windows.tmux.TmuxGlasses(text, tmuxScripted, scope, phoneTmux,
            wm.damage.core.app.LocalWindowSource(phoneTmux, wc))
        shell.register(tmuxWin)
        // S1-2 Q5: Files' presenter over the phone's working copy, its calls through the window channel into a temp
        // listing cache (the phone's own provider), as the phone builds it
        val filesCore = wm.damage.core.windows.files.FilesCore()
        val filesSource = wm.damage.core.app.LocalWindowSource(filesCore, wc)
        var filesOnChannel: wm.damage.core.windows.files.FilesGlasses? = null
        val filesRemote = wm.damage.core.windows.files.RemoteFilesProvider("127.0.0.1", host.boundPort, token, scope,
            onState = { s -> if (s.isEmpty()) filesOnChannel?.channelUp() }, cacheDir = tmp.resolve("wincache").resolve("files"))
        val filesWin = wm.damage.core.windows.files.FilesGlasses(text, filesCore, filesSource, filesRemote, scope, AwtImages(),
            initialBooksDir = cfg.booksDir, device = wc.device)
        filesOnChannel = filesWin
        shell.register(filesWin)
        // build S1-2 Q4: the phone's Torrents over the window channel to the PC's provider, at today's paces; its core
        // over the working copy, and the PC's notices shown through the phone's mirror (`ShellService`'s arrangement)
        val torrentsRemote = wm.damage.core.windows.torrents.RemoteTorrentsProvider("127.0.0.1", host.boundPort, token, scope)
        val phoneMirror = wm.damage.core.app.NoticeMirror(wc, shared = { wc.shares(wm.damage.core.app.Notices.WINDOW) },
            cursorFile = tmp.resolve("workcopy").resolve(wm.damage.core.app.NoticeMirror.CURSOR_FILE))
        shell.noticeInput = phoneMirror
        phoneMirror.attach(shell.noticeSink)
        // build S1-5 S: the shell's settings over the working copy (the scratch PC record's), as `ShellService` builds it
        val settingsSource = wm.damage.core.app.LocalWindowSource(wm.damage.core.shell.SettingsCore(), wc)
        shell.settingsSource = settingsSource
        val phoneTorrents = wm.damage.core.windows.torrents.TorrentsCore()
        val torrentsSource = wm.damage.core.app.LocalWindowSource(phoneTorrents, wc)
        val torrentsWin = wm.damage.core.windows.torrents.TorrentsGlasses(text, torrentsRemote, scope, phoneTorrents, torrentsSource,
            recordNotices = { wc.shares(phoneTorrents.window) && wc.shares(wm.damage.core.app.Notices.WINDOW) },
            device = wc.device, buildsLine = { wc.client?.contractState?.line(phoneTorrents.window) })
        shell.register(torrentsWin)
        // build S1-4 J: Music's world as the first arrangement builds it (the scripted library, the stand-in player, the
        // scene clock), so its scenes here are the same scenes
        val music = MusicScene(text, scope, wc)
        shell.register(music.win)
        // build S1-5 G: Games' presenter over the working copy (its world written to the scratch PC record), as the phone
        // builds it; the same pinned world as the first arrangement's
        val gamesCore = wm.damage.core.windows.games.GamesCore()
        val gamesSource = wm.damage.core.app.LocalWindowSource(gamesCore, wc)
        val gamesWin = wm.damage.core.windows.games.GamesGlasses(text, scope, gamesCore, gamesSource)
        gamesWin.roster.worldSeed = 20260905L
        shell.register(gamesWin)
        // build S1-3 J: Feed on its own scene clock from the same instant as the first arrangement's
        val feedClock = SceneClock(FEED_T0)
        val feedScripted = wm.damage.core.windows.feed.ScriptedFeed(nowMs = FEED_T0)
        val feedProvider = SceneFeed(feedScripted)
        // build S1-3 B16: the PC's core raises Feed's notices into the scratch record from the same scripted feed (the
        // phone's presenter reads it directly, as before: the scenes wait on its calls); the presenter over the working
        // copy, as `ShellService` builds it
        val pcFeed = wm.damage.core.windows.feed.FeedCore(feedProvider)
        pcFeed.startOnPc(pcRecord, pcNotices) { _, _ -> }
        val phoneFeed = wm.damage.core.windows.feed.FeedCore()
        val feedWin = wm.damage.core.windows.feed.FeedGlasses(text, feedProvider, scope, phoneFeed,
            wm.damage.core.app.LocalWindowSource(phoneFeed, wc), clock = feedClock::now, device = wc.device)
        shell.register(feedWin)
        try {
            shell.start()
            // the phone's shelf comes over the lane: the first arrangement's scan lands before its first scene, so this
            // one waits for the listing and for the PC's first answer before scene 01
            waitFor("the phone's shelf") { reader.summary().line.endsWith(" books") }
            waitFor("the PC's record on the phone") { wc.client?.contractState is wm.damage.core.app.Contracts.State.Known }
            // build S1-2 Q4: Main's Torrents row reads the phone's copy of the PC's transfers (its first poll), as the
            // first arrangement's reads the provider itself
            waitFor("the phone's transfers") {
                torrentsRemote.snapshot()?.version == torrentsScripted.currentVersion && torrentsRemote.stateLine().isEmpty() &&
                    torrentsWin.summary().line.contains("downloading")
            }
            iconsSettled()
            readerScenes(shell, sim, reader, out)
            // build S1-2 Q4: Torrents' scenes again, the presenter over the phone's provider (the first arrangement's
            // steps; each waits for the phone's copy of the PC's snapshot where the picture shows the list)
            torrentsScenes(shell, sim, out, transport, torrentsWin, torrentsScripted, ::iconsSettled) {
                torrentsRemote.snapshot()?.version == torrentsScripted.currentVersion
            }
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // transfers → Main
            settle(shell, "torrents-to-main-phone")
            repeat(3) { shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }      // cursor home
            settle(shell, "main-home-phone")
            // Settings, reached by committing and checking (as `toWindow` does), then its Reader category
            for (k in 0 until 12) {
                shell.postGesture(EvenHubMsg.EV_CLICK)
                settle(shell, "commit-settings-phone")
                if (shell.currentWindowId() == "settings") break
                var guard = 0
                while (shell.currentWindowId() != null && guard++ < 10) {
                    shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                    settle(shell, "back-to-main-phone")
                }
                shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                settle(shell, "walk-to-settings-phone")
            }
            if (shell.currentWindowId() != "settings") failures.add("phone arrangement: could not reach Settings from Main")
            readerSettingsScene(shell, sim, out)
            settingsCategoryScene(shell, sim, out, "Torrents", "37c-torrents-settings")
            val line = wc.client?.contractState?.line(core.window)
            if (line != null) failures.add("phone arrangement: the builds differ ($line)")
            // build S1-2 Q4: the done box the phone showed was the PC's record notice (raised once, by the PC): the
            // double-tap that dismissed it reached the PC record as the phone's dismiss
            if (wc.client?.contractState?.line(phoneTorrents.window) != null) failures.add("phone arrangement: the builds differ for Torrents")
            waitFor("the phone's dismiss of the done notice in the PC record") {
                val rec = pcRecord.read(wm.damage.core.app.Notices.WINDOW).third.items["rec.notices.torrent:aa01"]
                rec?.by == wc.device && wm.damage.core.app.Notices.parse("torrent:aa01", rec.value)?.state == wm.damage.core.app.Notices.DISMISSED
            }

            // S1-2 Q5: Settings → Files, then Files' scenes 11 to 13e as the first arrangement takes them, the presenter
            // on the working copy and its calls through the window channel (the base's scenes of the same names judge them)
            settingsCategoryScene(shell, sim, out, "Files", "37d-files-settings")
            filesLine(wc)?.let { failures.add("phone arrangement: the builds differ ($it)") }
            // build S1-3 J: Settings → Feed and Settings → Tmux, as the first arrangement takes them
            feedTmuxSettingsScenes(shell, sim, out)
            repeat(2) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }    // → categories → Main
            settle(shell, "phone-files-main")
            walkTo(shell, "files", "phone-files")
            waitFor("files locations (phone)") { filesWin.summary().line.contains("locations") }
            iconsSettled()
            save(sim, out, "11-files-locations")
            shell.postGesture(EvenHubMsg.EV_CLICK)             // first location (Root)
            waitFor("files listing (phone)") { filesWin.summary().detail.contains("folders") }
            iconsSettled()
            save(sim, out, "12-files-browse")
            shell.postGesture(EvenHubMsg.EV_CLICK)             // tap = context menu
            waitFor("files menu (phone)") { shell.menuIsOpen }
            settle(shell)
            save(sim, out, "13-files-menu")
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // cancel the menu
            settle(shell)
            filesMoreScenes(shell, sim, out, filesWin, phoneFiles, iconsSettled = { iconsSettled() })

            // build S1-3 J: Tmux's and Feed's scenes, the first arrangement's steps (09b–09m, 60–78): the presenters S1-3
            // moves onto the working copy are judged here against today's windows. The ages are stamped afresh first:
            // this arrangement reaches Tmux about half a minute after its provider was made.
            val toWindow: suspend (String) -> Unit = { id -> toWindowFrom(shell, id, "phone-$id") }
            tmuxScripted.restamp(waiting = false)
            toWindow("tmux")
            waitFor("tmux sessions (phone)") { tmuxWin.title() == "sessions" }
            settle(shell, "phone-tmux")
            tmuxScenes(shell, sim, out, tmuxWin)
            feedScenes(shell, sim, out, feedWin, feedScripted, toWindow, iconsSettled = { iconsSettled() })
            feedMoreScenes(shell, sim, out, transport, feedWin, feedScripted, feedProvider, feedClock, pcRecord, toWindow)
            tmuxMoreScenes(shell, sim, out, tmuxWin, tmuxScripted, toWindow, pcRecord)
            // build S1-4 J: Music's scenes 30 to 39, its Output menu and Settings → Music (three screens), the first
            // arrangement's steps over the same window and scene clock: the presenter S1-4 moves onto the working copy
            // is judged here against today's window
            musicScenes(shell, sim, out, transport, music, iconsSettled = { iconsSettled() }) { toWindow("music") }
            musicOutputScene(shell, sim, out, music)
            toWindow("settings")
            settingsCategoryScene(shell, sim, out, "Music", "37-music-settings")
            musicSettingsLower(shell, sim, out)
            // build S1-5 J: Settings' own pages and Settings → Games, then Games' scenes 40 to 52 by the first
            // arrangement's steps: the two windows S1-5 moves onto the PC's record are judged here
            settingsOwnScenes(shell, sim, out)
            gamesScenes(shell, sim, out, gamesWin, toWindow, iconsSettled = { iconsSettled() })
            // build S1-5 G: the world the phone's presenter wrote reached the scratch PC record, and the PC's card reads
            // the line the glasses' Main row shows
            waitFor("the phone's Games world in the PC record, its card equal to Main's row") {
                val st = wm.damage.core.app.WindowState(gamesCore.window, pcRecord.read(gamesCore.window).third.items)
                st.itemNamed(wm.damage.core.windows.games.GamesCore.WORLD) != null && gamesCore.summary(st) == gamesWin.summary()
            }
        } finally {
            shell.stop()
            filesWin.detach()
            filesSource.close()
            filesRemote.close()
            reader.detach()
            source.close()
            torrentsWin.detach()
            torrentsSource.close()
            torrentsRemote.close()
            music.win.detach()                                 // build S1-4 K: the presenter leaves the working copy's source
            gamesWin.detach()                                  // build S1-5 G: the same
            gamesSource.close()
            shell.settingsSource = null                        // build S1-5 S: the shell leaves the working copy's source
            settingsSource.close()
            music.source?.close()
            phoneMirror.detach(shell.noticeSink)
            phoneMirror.close()
            pcTorrents.detachNotices()
            pcTmux.detachAlerts()
            wc.close()
            ex.shutdown()
            host.close()
            net.close()
            pcRecord.close()
            scope.cancel()
            snapShell = before
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * Reader's scenes, 01 to 09 (the spec's §5: Main's Reader row, the library, the chapters, the book, scrolled, the
     * actions, the switcher, the notice over the book, Main again) — the same steps in both arrangements of the judge.
     * The shell has started and its first wave of icons has landed.
     */
    private suspend fun readerScenes(shell: Shell, sim: GlassFirmwareSim, reader: wm.damage.core.windows.reader.ReaderGlasses, out: Path) {
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

    }

    /** Build S1-2 Q5: "PC and phone on different builds for Files", or null when they match. */
    private fun filesLine(wc: wm.damage.core.app.WorkingCopy): String? =
        wc.client?.contractState?.line(wm.damage.core.windows.files.FilesCore.WINDOW)

    /** Build S1-2 Q5: from Main, the window [id] opened by committing Main's rows one by one and checking (as the phone
     *  arrangement's walk to Settings does). */
    private suspend fun walkTo(shell: Shell, id: String, tag: String) {
        for (k in 0 until 12) {
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell, "commit-$tag")
            if (shell.currentWindowId() == id) return
            var guard = 0
            while (shell.currentWindowId() != null && guard++ < 10) {
                shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                settle(shell, "back-to-main-$tag")
            }
            shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            settle(shell, "walk-$tag")
        }
        failures.add("could not reach $id from Main ($tag)")
    }

    /** The Settings → Reader scene (37b), by Settings' `cat:` deep link, from wherever the shell is in Settings. */
    private suspend fun readerSettingsScene(shell: Shell, sim: GlassFirmwareSim, out: Path) =
        settingsCategoryScene(shell, sim, out, "Reader", "37b-reader-settings")

    /**
     * Build S1-3 J: Settings → Feed (37e, the cursor on its first row as the deep link leaves it; 37g, seven rows down,
     * where Fetch, Keep, `PC loss` and the three Notify rows show — the category's rows below 37e's screen) and
     * Settings → Tmux (37f, all of it), every row at its default. From wherever the shell is in Settings.
     */
    private suspend fun feedTmuxSettingsScenes(shell: Shell, sim: GlassFirmwareSim, out: Path) {
        settingsCategoryScene(shell, sim, out, "Feed", "37e-feed-settings")
        repeat(7) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        settle(shell, "feed-settings-lower")
        save(sim, out, "37g-feed-settings-lower")
        settingsCategoryScene(shell, sim, out, "Tmux", "37f-tmux-settings")
    }

    /**
     * Build S1-5 J (the phone arrangement): Settings' category list (26, back from a category's rows), Global at every
     * row's default in three screens (27 its top, 27b eight rows down, 27c sixteen) and Settings → Games (37j its top,
     * 37k seven rows down, where its Notify and style rows show). From a category's rows; ends in Games' category.
     */
    private suspend fun settingsOwnScenes(shell: Shell, sim: GlassFirmwareSim, out: Path) {
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)          // a category's rows → the category list
        waitFor("settings' category list") { shell.currentWindowId() == "settings" && shell.isQuiescent() }
        settle(shell, "settings-categories")
        save(sim, out, "26-settings-categories")
        settingsCategoryScene(shell, sim, out, "Global", "27-settings-global")
        for (name in listOf("27b-settings-global-mid", "27c-settings-global-end")) {
            repeat(8) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            settle(shell, name)
            save(sim, out, name)
        }
        settingsCategoryScene(shell, sim, out, "Games", "37j-games-settings")
        repeat(7) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        settle(shell, "games-settings-lower")
        save(sim, out, "37k-games-settings-lower")
    }

    /** A Settings category's scene, by Settings' `cat:` deep link, from wherever the shell is in Settings. */
    private suspend fun settingsCategoryScene(shell: Shell, sim: GlassFirmwareSim, out: Path, cat: String, name: String) {
        val opened = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        shell.services.runOnShell { opened.set(shell.services.openWindow("settings", "cat:$cat")) }
        waitFor("settings · ${cat.lowercase()}") { opened.get() != null && shell.currentWindowId() == "settings" && shell.isQuiescent() }
        if (opened.get() != true) failures.add("Settings has no $cat category (the cat:$cat deep link was refused)")
        settle(shell)
        save(sim, out, name)
    }

    /**
     * Build S1-2 J0 (the spec's §6 "J0"): Files' trash, its "This folder" menu, a file's stats menu and the text viewer,
     * over [SceneFiles]' made-up folder and trash (every date pinned). Starts on the Root listing with no menu open and
     * ends on the locations list. Each scene is reached by gestures, so `tools/flushdiff.py` prices the one before it.
     */
    private suspend fun filesMoreScenes(shell: Shell, sim: GlassFirmwareSim, out: Path,
        filesWin: wm.damage.core.windows.files.FilesWindow, scene: SceneFiles, iconsSettled: suspend () -> Unit) {
        scene.shown = true
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // Root → the locations, listed again: Scene and Trash last
        waitFor("files locations with the scene's") {
            filesWin.title() == "locations" && scene.shownLocations > 0 && filesWin.summary().line == "${scene.shownLocations} locations"
        }
        settle(shell, "files-locations-scene")
        // the cursor rests on Root (row 0); the list wraps, so one notch up is the last row, the Trash
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the files trash") { filesWin.title() == "trash" && filesWin.summary().line == "trash · ${SceneFiles.TRASHED.size}" }
        iconsSettled()
        save(sim, out, "13b-files-trash")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → the locations (the cursor still on the Trash)
        waitFor("the locations again") { filesWin.title() == "locations" }
        settle(shell, "files-locations-again")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)        // → Scene
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the scene folder") {
            filesWin.title() == SceneFiles.DIR && filesWin.summary().detail == "0 folders · ${SceneFiles.ENTRIES.size} files"
        }
        iconsSettled()
        // the cursor rests on the first file (notes.txt); one notch up wraps to the "This folder" row
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the This folder menu") { shell.menuIsOpen && shell.menuTitle == SceneFiles.DIR }
        settle(shell, "files-folder-menu")
        save(sim, out, "13c-files-folder-menu")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // cancel the menu
        settle(shell, "files-folder-menu-closed")
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // wraps back to notes.txt
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("notes.txt's menu") { shell.menuIsOpen && shell.menuTitle == "notes.txt" && shell.menuLabels.firstOrNull() == "Open" }
        menuRow(shell, "Stats")
        waitFor("notes.txt's stats") { shell.menuIsOpen && shell.menuTitle == "notes.txt" && shell.menuLabels.firstOrNull() == "Size" }
        settle(shell, "files-stats")
        save(sim, out, "13d-files-stats")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // close the stats
        settle(shell, "files-stats-closed")
        val served = scene.textServed
        shell.postGesture(EvenHubMsg.EV_CLICK)             // notes.txt's menu, Open first
        waitFor("notes.txt's menu again") { shell.menuIsOpen && shell.menuLabels.firstOrNull() == "Open" }
        shell.postGesture(EvenHubMsg.EV_CLICK)             // Open → the text viewer
        waitFor("the text viewer") { filesWin.title() == "notes.txt" && filesWin.levelDepth() == 3 && scene.textServed > served }
        settle(shell, "files-text")
        save(sim, out, "13e-files-text")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → the scene folder
        waitFor("back in the scene folder") { filesWin.title() == SceneFiles.DIR }
        settle(shell, "files-scene-back")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → the locations
        waitFor("the locations at the end") { filesWin.title() == "locations" }
        settle(shell, "files-more-done")
    }

    /**
     * Torrents' scenes, 15 to 25 (TORRENTS.md, 2026-09-01; build S1-2 J0, Q4): the transfers with block bars and the
     * live lens, the transfer menu, the details document, the tracker's categories and a listing, a torrent page, the
     * §4.8 keyboard opened by Search, then [torrentsMoreScenes] — the same steps in both arrangements of the judge.
     * Starts on Main with the cursor home; ends on the transfers. [synced] is true once the presenter's provider holds
     * [torrentsScripted]'s snapshot (the phone's provider polls it over the window channel; on the PC it is the provider).
     */
    private suspend fun torrentsScenes(shell: Shell, sim: GlassFirmwareSim, out: Path, transport: SimTransport,
        torrentsWin: wm.damage.core.windows.torrents.TorrentsWindow, torrentsScripted: ScriptedTorrents,
        iconsSettled: suspend () -> Unit, synced: () -> Boolean = { true }) {
        repeat(3) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // → Torrents row
        settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("torrents transfers") { torrentsWin.title() == "transfers" && synced() }
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
        torrentsMoreScenes(shell, sim, out, transport, torrentsWin, torrentsScripted, synced)   // ends on the transfers
    }

    /**
     * Build S1-2 J0: Torrents' menu with a recent search, its stats menu, and a done notice over the transfers. Starts on
     * the transfers with the cursor on the menu row and no surface open; ends there, the notice dismissed. [synced]: as
     * [torrentsScenes]' (the done scene waits for the finished download in the presenter's own list).
     */
    private suspend fun torrentsMoreScenes(shell: Shell, sim: GlassFirmwareSim, out: Path,
        transport: SimTransport, torrentsWin: wm.damage.core.windows.torrents.TorrentsWindow, scripted: ScriptedTorrents,
        synced: () -> Boolean) {
        shell.postGesture(EvenHubMsg.EV_CLICK)             // the Torrents menu
        waitFor("the Torrents menu for a search") { shell.menuIsOpen && shell.menuTitle == "torrents" }
        menuRow(shell, "Search TorrentLeech")
        waitFor("the search keyboard") { shell.keyboardIsOpen }
        transport.injectText("ubuntu")                     // a typed line commits the keyboard (the replica's path)
        waitFor("the search's listing") {
            torrentsWin.title() == "\"ubuntu\"" && scripted.ops.any { it.startsWith("search:ubuntu:1") } && shell.isQuiescent()
        }
        settle(shell, "torrents-search")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → transfers (the search started there)
        waitFor("back on the transfers") { torrentsWin.title() == "transfers" }
        settle(shell, "torrents-transfers-again")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // the cursor is still on the menu row
        waitFor("the Torrents menu with a recent") { shell.menuIsOpen && shell.menuTitle == "torrents" && "Search" in shell.menuLabels }
        settle(shell, "torrents-menu-recent")
        save(sim, out, "23-torrents-menu-recent")
        menuRow(shell, "Stats")
        waitFor("the Torrents stats") { shell.menuIsOpen && shell.menuTitle == "stats" }
        settle(shell, "torrents-stats")
        save(sim, out, "24-torrents-stats")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // close the stats
        settle(shell, "torrents-stats-closed")
        scripted.fireDone()                                // the done edge: a notice over the transfers
        waitFor("the done notice is up") { shell.notifications.active }
        waitFor("the done notice is focused") { shell.notifications.focused && synced() && shell.isQuiescent() }
        settle(shell, "torrents-done")
        save(sim, out, "25-torrents-done-notice")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // dismiss
        waitFor("the done notice dismissed") { !shell.notifications.active }
        settle(shell, "torrents-done-dismissed")
        // the notice marked the window dirty (its strip segment lit); a visit from Main clears it, so every later scene
        // is drawn as it was before these scenes were added
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Main (the cursor on Torrents)
        settle(shell, "torrents-done-main")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // → Torrents again
        waitFor("the transfers again") { shell.currentWindowId() == "torrents" && torrentsWin.title() == "transfers" && !torrentsWin.dirty }
        settle(shell, "torrents-done-revisited")
    }

    /** Commit the open menu's row LABELLED [label] — by name, never by counting. */
    private suspend fun menuRow(shell: Shell, label: String) {
        settle(shell, "menu-row-$label")
        val i = shell.menuLabels.indexOfFirst { it == label }
        if (i < 0) { failures.add("menu row '$label' not in ${shell.menuLabels}"); return }
        repeat((i - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        shell.postGesture(EvenHubMsg.EV_CLICK)
    }

    /**
     * Wait until nothing is pending, so a save is of a finished frame.
     *
     * The bound is a backstop for a scene that can never settle, not a budget:
     * a scripted host doing real work (the Torrents pages, a Hold'em hand at
     * `Equity.LIVE_ROLLOUTS`) exceeded 15 s on a loaded machine about once in
     * ten runs and got reported as a wrong state while it was still making
     * progress (review 2026-09-05). 60 s, and anything past 5 s prints what it
     * cost so a genuinely slow scene is visible rather than mysterious.
     */

    /** FEED (`FEED.md` §4): the scenes, split out of `script` — that function
     *  reached the JVM's 64 KB method limit on 2026-09-09. */
    private suspend fun feedScenes(shell: Shell, sim: GlassFirmwareSim, out: Path,
        feedWin: wm.damage.core.windows.feed.FeedWindow, feedScripted: wm.damage.core.windows.feed.ScriptedFeed,
        toWindow: suspend (String) -> Unit, iconsSettled: suspend () -> Unit) {
        // ---- Feed (FEED.md, 2026-09-09): the source list, an item list, an
        // article, a comic and the archive at 480, then the two that matter at 288
        /** Rest the source cursor on the row NAMED [id] — by identity, never by counting. */
        suspend fun feedRow(id: String) {
            for (k in 0 until 10) {
                settle(shell, "feed-row-$id")
                if (feedWin.rootRowId() == id) return
                shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            }
            failures.add("could not reach the Feed source row '$id'")
        }
        toWindow("feed")
        waitFor("Feed at its source list") { feedWin.title() == "feed" }
        feedRow("popular")
        iconsSettled()
        save(sim, out, "60-feed-sources")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // popular
        waitFor("the item list") { feedWin.title() == "popular" && feedWin.levelDepth() == 2 && feedWin.itemsLoaded() }
        settle(shell, "feed")
        save(sim, out, "61-feed-items")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // one tap opens
        waitFor("the article") { feedWin.title() == "article" && feedScripted.ops.any { it.startsWith("article:") } }
        settle(shell, "feed")
        save(sim, out, "62-feed-article")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // actions
        waitFor("the actions") { feedWin.levelDepth() == 4 }
        settle(shell, "feed")
        save(sim, out, "63-feed-actions")
        repeat(3) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }
        waitFor("root") { feedWin.title() == "feed" }
        feedRow("xkcd")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // xkcd
        waitFor("xkcd") { feedWin.title() == "xkcd" && feedWin.itemsLoaded() }
        settle(shell, "feed")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the strip") { feedWin.title() == "xkcd 3296" && feedScripted.ops.any { it.startsWith("comic:") } }
        settle(shell, "feed")
        save(sim, out, "64-feed-comic")
        repeat(2) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }
        waitFor("root") { feedWin.title() == "feed" }
        feedRow("8bt")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // 8-Bit Theater
        waitFor("the archive") { feedWin.title() == "8bt 1" }
        settle(shell, "feed")
        save(sim, out, "65-feed-binge")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        waitFor("root") { feedWin.title() == "feed" }
        // the source list and an article at 288 (WINDOWS.md §1: every height)
        shell.services.runOnShell { feedWin.appSettings().first { it.name == "Size" }.apply("288") }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → Main (the Size applies on the next focus)
        settle(shell, "feed")
        toWindow("feed")
        waitFor("Feed at 288") { feedWin.title() == "feed" }
        feedRow("popular")
        settle(shell, "feed")
        save(sim, out, "66-feed-sources-288")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("popular at 288") { feedWin.title() == "popular" && feedWin.itemsLoaded() }
        settle(shell, "feed")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the article at 288") { feedWin.title() == "article" }
        settle(shell, "feed")
        save(sim, out, "67-feed-article-288")
        shell.services.runOnShell { feedWin.appSettings().first { it.name == "Size" }.apply("global") }
        repeat(3) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }
        settle(shell, "feed")

    }

    /** Build S1-3 B16: Tmux's presenter over a source of its own on the scratch PC record [pc] (device `local`). */
    private fun tmuxOn(text: wm.damage.core.text.TextRasterizer, scripted: ScriptedTmux, scope: CoroutineScope,
                       pc: wm.damage.core.app.AppHost): wm.damage.core.windows.tmux.TmuxGlasses {
        val core = wm.damage.core.windows.tmux.TmuxCore()
        return wm.damage.core.windows.tmux.TmuxGlasses(text, scripted, scope, core, wm.damage.core.app.LocalWindowSource(core,
            wm.damage.core.app.HostBackend(pc, wm.damage.core.app.Peer.LOCAL.device, wm.damage.core.app.OpQueue.newStream())))
    }

    /** Build S1-3 B16: the scratch PC record's Tmux items, as Tmux's core reads its mutes at an alert. */
    private fun tmuxState(pc: wm.damage.core.app.AppHost) = wm.damage.core.app.WindowState(
        wm.damage.core.windows.tmux.TmuxCore.WINDOW, pc.read(wm.damage.core.windows.tmux.TmuxCore.WINDOW).third.items)

    /**
     * Tmux's scenes 09b to 09e (TMUX.md): the sessions, the live pane with the inverted cursor, the wrapped history,
     * the keys level — the same steps in both arrangements (build S1-3 J moved them here from `script`, unchanged).
     * Starts on the sessions list just opened from Main; ends on Main with the cursor on Tmux.
     */
    private suspend fun tmuxScenes(shell: Shell, sim: GlassFirmwareSim, out: Path, tmuxWin: wm.damage.core.windows.tmux.TmuxWindow) {
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
    }

    /**
     * Build S1-3 J (the spec's B14: what `TmuxGlasses` reads from the PC record and what the PC raises in its place):
     * a waiting session's alert box over Main (09f), the sessions list with the waiting session in the lens (09g) and
     * with its waiting mark on a row (09h), the session's Alerts row muted (09i), Main with the muted session still
     * waiting and its next edge raising nothing (09j), a full-screen program's grid with its context rows (09k) and
     * with the Context rows row off (09l), the live pane at the 288 size (09m). Starts and ends on Main; the Context
     * rows and Size rows are put back, claude stays muted.
     */
    private suspend fun tmuxMoreScenes(shell: Shell, sim: GlassFirmwareSim, out: Path,
        tmuxWin: wm.damage.core.windows.tmux.TmuxWindow, scripted: ScriptedTmux, toWindow: suspend (String) -> Unit,
        pcRecord: wm.damage.core.app.AppHost) {
        suspend fun toMain(where: String) {
            var guard = 0
            while (shell.currentWindowId() != null && guard++ < 10) {
                shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                settle(shell, where)
            }
            if (shell.currentWindowId() != null) failures.add("could not get back to Main ($where)")
        }
        // Main's cursor on the Tmux row: open it from Main and come back
        toWindow("tmux")
        waitFor("tmux sessions before the alert") { tmuxWin.title() == "sessions" }
        settle(shell, "tmux-before-alert")
        toMain("tmux-alert-main")
        // claude starts waiting: the status poll says so, and its edge raises the alert (the real provider sends both)
        scripted.restamp(waiting = true)
        scripted.pushAlert()
        waitFor("the tmux alert is up") { shell.notifications.active }
        waitFor("the tmux alert is focused") { shell.notifications.focused && shell.isQuiescent() }
        settle(shell, "tmux-alert")
        save(sim, out, "09f-tmux-alert-main")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // dismiss
        waitFor("the tmux alert dismissed") { !shell.notifications.active }
        settle(shell, "tmux-alert-dismissed")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // → Tmux, the waiting session first
        waitFor("the sessions, claude waiting") { tmuxWin.title() == "sessions" && tmuxWin.summary().line == "claude waiting" }
        settle(shell, "tmux-waiting")
        save(sim, out, "09g-tmux-waiting")
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // → build: claude's row above it carries the waiting mark
        settle(shell, "tmux-waiting-mark")
        save(sim, out, "09h-tmux-waiting-mark")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)        // → claude again
        settle(shell, "tmux-waiting-back")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // open claude
        waitFor("claude's live pane") { tmuxWin.title() == "claude" }
        settle(shell, "tmux-waiting-live")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // → keys
        waitFor("claude's keys") { tmuxWin.title().endsWith("· keys") }
        settle(shell, "tmux-waiting-keys")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)        // the list wraps: one notch up is its last row, Session…
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("claude's session actions") { tmuxWin.title().endsWith("· session") }
        settle(shell, "tmux-session-actions")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // row 0, "Alerts: on — tap to mute"
        settle(shell, "tmux-muted")
        save(sim, out, "09i-tmux-muted")
        toMain("tmux-muted-main")
        // build S1-3 B16: the PC's core reads the mute from the PC record (on the phone it arrives through the working copy)
        waitFor("claude's mute in the PC record") {
            wm.damage.core.windows.tmux.TmuxCore.muted(tmuxState(pcRecord), "beardos", "claude")
        }
        // the visit cleared the window's mark; claude still waits, and muted, its next edge raises nothing
        scripted.pushAlert()
        settle(shell, "tmux-muted-alert")
        if (shell.notifications.active) failures.add("a muted session's alert raised a box on Main")
        save(sim, out, "09j-tmux-muted-main")

        // a full-screen program in claude's pane (the grid fallback), with and without its context rows
        scripted.restamp(waiting = false)
        scripted.alternate = true
        shell.postGesture(EvenHubMsg.EV_CLICK)             // → Tmux (the cursor is still on its row)
        waitFor("the sessions for the grid") { shell.currentWindowId() == "tmux" && tmuxWin.title() == "sessions" }
        settle(shell, "tmux-grid-sessions")
        val subsGrid = scripted.sent.count { it == "sub:claude" }
        shell.postGesture(EvenHubMsg.EV_CLICK)             // open claude
        waitFor("claude's grid") { tmuxWin.title() == "claude" && scripted.sent.count { it == "sub:claude" } > subsGrid }
        settle(shell, "tmux-grid")
        save(sim, out, "09k-tmux-fullscreen")
        shell.services.runOnShell { tmuxWin.appSettings().first { it.name == "Context rows" }.apply("off") }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → sessions
        settle(shell, "tmux-grid-off-sessions")
        val subs = scripted.sent.count { it == "sub:claude" }
        shell.postGesture(EvenHubMsg.EV_CLICK)             // open claude again: a fresh frame
        waitFor("claude's grid without context") { tmuxWin.title() == "claude" && scripted.sent.count { it == "sub:claude" } > subs }
        settle(shell, "tmux-grid-off")
        save(sim, out, "09l-tmux-fullscreen-nocontext")

        // the live pane at 288: the window's Size row, applied on the next focus
        scripted.alternate = false
        shell.services.runOnShell {
            tmuxWin.appSettings().first { it.name == "Context rows" }.apply("on")
            tmuxWin.appSettings().first { it.name == "Size" }.apply("288")
        }
        toMain("tmux-288-main")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // → Tmux at 288
        waitFor("the sessions at 288") { shell.currentWindowId() == "tmux" && tmuxWin.title() == "sessions" }
        settle(shell, "tmux-288-sessions")
        val subs288 = scripted.sent.count { it == "sub:claude" }
        shell.postGesture(EvenHubMsg.EV_CLICK)             // open claude
        waitFor("claude at 288") { tmuxWin.title() == "claude" && scripted.sent.count { it == "sub:claude" } > subs288 }
        settle(shell, "tmux-288-live")
        save(sim, out, "09m-tmux-live-288")
        shell.services.runOnShell { tmuxWin.appSettings().first { it.name == "Size" }.apply("480") }   // its default
        toMain("tmux-more-done")
    }

    /**
     * Build S1-3 J (the spec's B6: what `FeedGlasses` reads from the PC record and writes as ops, and the notice the PC
     * raises in its place), each scene reached by gestures from the one before: popular's list with read rows and a
     * flagged row (68), an article scrolled to a place (69) and opened again (70), a browsed subreddit pinned and
     * marked all read (71), the source list holding it (72), popular's menu with the recent subreddit and the flag
     * count (73), the Flagged list (74), the root menu (74b), the archive scrolled into its second page (75), the
     * source list's line for that place (76), the archive entered again (77), a Feed notice over Main (78). Every title
     * notice an action sets is let run out on the scene's clock before the next scene. Starts on Main after
     * [feedScenes]; ends on Main.
     */
    private suspend fun feedMoreScenes(shell: Shell, sim: GlassFirmwareSim, out: Path, transport: SimTransport,
        feedWin: wm.damage.core.windows.feed.FeedWindow, scripted: wm.damage.core.windows.feed.ScriptedFeed,
        provider: SceneFeed, clock: SceneClock, record: wm.damage.core.app.AppHost, toWindow: suspend (String) -> Unit) {
        /** The PC record's Feed item [name] (B16: the presenter's ops land there, from the phone through its copy). */
        fun recordItem(name: String) = record.read(wm.damage.core.windows.feed.FeedCore.WINDOW).third
            .items[wm.damage.core.app.Record.key(wm.damage.core.windows.feed.FeedCore.WINDOW, name)]?.takeIf { !it.gone }?.value
        val ids = wm.damage.core.windows.feed.FeedIds
        fun popular(i: Int) = ids.id("popular", "t3_p$i")
        fun opened(id: String) = scripted.ops.count { it == "article:$id" }
        /** At rest: no provider call unanswered (its answer applies on the loop afterwards), then the shell settled. */
        suspend fun quiet(where: String) {
            waitFor("the feed provider answered ($where)") { provider.inFlight() == 0 && shell.isQuiescent() }
            settle(shell, where)
        }
        /** An action's title notice is up; the scene's clock moves past its four seconds, so the next render drops it. */
        suspend fun noticed(text: String) {
            waitFor("the title notice '$text'") { feedWin.title() == text }
            clock.advance(5_000)
        }
        suspend fun row(id: String) = feedRowOf(shell, feedWin, id)
        suspend fun openArticle(id: String, times: Int) {
            shell.postGesture(EvenHubMsg.EV_CLICK)
            waitFor("the article $id") { feedWin.title() == "article" && opened(id) == times }
            quiet("feed-article-$id")
        }
        suspend fun backToList(name: String) {
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            waitFor("$name's list") { feedWin.title() == name && feedWin.levelDepth() == 2 }
            quiet("feed-list-$name")
        }

        toWindow("feed")
        waitFor("Feed at its source list (more)") { feedWin.title() == "feed" }
        row("popular")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("popular's list (more)") { feedWin.title() == "popular" && feedWin.levelDepth() == 2 && feedWin.itemsLoaded() }
        quiet("feed-popular-more")
        // the cursor rests on the first item (opened, so read, since 62): open the second and flag it, then the third
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        openArticle(popular(1), 1)
        shell.postGesture(EvenHubMsg.EV_CLICK)             // the actions, the cursor on Comments
        waitFor("the article's actions") { feedWin.levelDepth() == 4 }
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // → Flag
        shell.postGesture(EvenHubMsg.EV_CLICK)
        noticed("flagged")
        backToList("popular")
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        openArticle(popular(2), 1)
        backToList("popular")
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // → the fourth: three read rows above it, one flagged
        quiet("feed-read-flagged")
        save(sim, out, "68-feed-items-read-flagged")

        // an article at a place: two notches down item 16, a link story (the long kind, with a picture), then opened
        // again. Not item 6: the scripted feed's picture for it throws (a negative seed indexes its colour list), and the
        // line saying so reads the exception's message, which the JVM drops once that code is compiled hot
        repeat(13) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        openArticle(popular(16), 1)
        repeat(2) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        quiet("feed-place")
        save(sim, out, "69-feed-place-scrolled")
        backToList("popular")
        openArticle(popular(16), 2)
        save(sim, out, "70-feed-place-reopened")
        backToList("popular")

        // a browsed subreddit, pinned and marked all read; popular's menu is its wrap-end row: items 17 to 29, then it
        repeat(14) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("popular's menu") { shell.menuIsOpen && shell.menuTitle == "popular" }
        menuRow(shell, "Browse a subreddit")
        waitFor("the subreddit keyboard") { shell.keyboardIsOpen }
        transport.injectText("linux")                      // a typed line commits the keyboard (the replica's path)
        waitFor("r/linux's list") { feedWin.title() == "r/linux" && feedWin.levelDepth() == 2 && feedWin.itemsLoaded() }
        quiet("feed-browsed")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)        // the first item → the menu row
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("r/linux's menu") { shell.menuIsOpen && shell.menuTitle == "r/linux" }
        menuRow(shell, "Pin")
        noticed("pinned r/linux")
        shell.postGesture(EvenHubMsg.EV_CLICK)             // the menu again (the cursor is still on its row)
        waitFor("r/linux's menu again") { shell.menuIsOpen && shell.menuTitle == "r/linux" }
        menuRow(shell, "Mark all read")
        waitFor("the mark-all-read confirm") { shell.menuIsOpen && shell.menuTitle == "Mark all of r/linux read?" }
        menuRow(shell, "Mark read")
        noticed("all read")
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // the menu row → the first item
        quiet("feed-all-read")
        save(sim, out, "71-feed-browse-all-read")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → the sources: the pinned subreddit stays
        waitFor("the sources with r/linux") { feedWin.title() == "feed" }
        row("r:linux")
        quiet("feed-pinned")
        save(sim, out, "72-feed-sources-pinned")

        // popular's menu: the recent subreddit and the flag count; then the Flagged list
        row("popular")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("popular's list again") { feedWin.title() == "popular" && feedWin.levelDepth() == 2 && feedWin.itemsLoaded() }
        quiet("feed-popular-again")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)        // another source was open: the cursor is on the first item
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("popular's menu with the recent") { shell.menuIsOpen && shell.menuTitle == "popular" && "r/linux" in shell.menuLabels }
        quiet("feed-source-menu")
        save(sim, out, "73-feed-source-menu")
        menuRow(shell, "Flagged")
        waitFor("the Flagged list") { feedWin.levelName == "FLAGGED" }
        quiet("feed-flagged")
        save(sim, out, "74-feed-flagged")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → the sources
        waitFor("the sources after Flagged") { feedWin.title() == "feed" }
        // the root menu: the list's wrap-end row, one notch below the last source (Refresh all, Flagged 1, Settings;
        // `Back to PC` only while the phone's own engine serves, which the spec retires)
        row("r:linux")
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        settle(shell, "feed-root-row")
        if (feedWin.rootRowId() != null) failures.add("the row below r/linux is a source (${feedWin.rootRowId()}), not the menu row")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("Feed's root menu") { shell.menuIsOpen && shell.menuTitle == "feed" }
        quiet("feed-root-menu")
        save(sim, out, "74b-feed-root-menu")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // cancel the menu
        waitFor("the root menu closed") { !shell.menuIsOpen }
        settle(shell, "feed-root-menu-closed")

        // the archive into its second page, notch by notch (65 left it at the top of page 1)
        row("8bt")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the archive again") { feedWin.title() == "8bt 1" }
        quiet("feed-binge")
        for (k in 0 until 20) {
            if (feedWin.title() == "8bt 2") break
            shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            quiet("feed-binge-notch-$k")
        }
        if (feedWin.title() != "8bt 2") failures.add("the archive never reached page 2 (title '${feedWin.title()}')")
        save(sim, out, "75-feed-binge-page2")
        // the source list's line reads the place the shell's save took (2 s after the last input), not a load's; B16: the
        // save point sends it to the record
        waitFor("the archive's place saved") {
            wm.damage.core.windows.feed.FeedCore.bingeOf(recordItem("binge.8bt"))?.ep == 2
        }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // → the sources, the cursor on the archive
        waitFor("the sources after the archive") { feedWin.title() == "feed" && feedWin.rootRowId() == "8bt" }
        quiet("feed-sources-binge")
        save(sim, out, "76-feed-sources-binge")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        waitFor("the archive entered again") { feedWin.title() == "8bt 2" }
        quiet("feed-binge-again")
        save(sim, out, "77-feed-binge-resumed")

        // a Feed notice over Main: Notify · Reddit on, one new item on popular
        var guard = 0
        while (shell.currentWindowId() != null && guard++ < 10) {
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            settle(shell, "feed-to-main")
        }
        shell.services.runOnShell { feedWin.appSettings().first { it.name == "Notify · Reddit" }.apply("on") }
        settle(shell, "feed-notify-on")
        // B16: the PC's core reads the row from its record when it raises (the phone's op crosses its copy first)
        waitFor("Notify · Reddit in the PC's record") { wm.damage.core.windows.feed.FeedCore.bool(recordItem("notify.reddit")) == true }
        scripted.fireNew("popular")
        waitFor("the feed notice is up") { shell.notifications.active }
        waitFor("the feed notice is focused") { shell.notifications.focused && provider.inFlight() == 0 && shell.isQuiescent() }
        settle(shell, "feed-notice")
        save(sim, out, "78-feed-notice-main")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // dismiss
        waitFor("the feed notice dismissed") { !shell.notifications.active }
        settle(shell, "feed-notice-dismissed")
    }

    /**
     * Build S1-3 J: Feed across a restart of the shell (the spec's B6 keeps P7 items 4–5's restore rules, and L1's two
     * glasses defects were restores). A shell with Feed alone, on the scene's clock, opens popular's item 16 two notches
     * down (69's place, 79a) and stops, its state saved as a stop saves it; a new shell with a new Feed window over the
     * same store starts and restores Feed (79b). That shell leaves the archive in its second page (80a) and stops; the
     * next restores the archive (80b). Each pair is the picture before the stop and after the start. Into the first
     * arrangement's directory; no icon theme (the drawn icons), every shell and window its own, one store and one
     * scripted feed for all three.
     */
    private suspend fun feedRestartScenes(out: Path) {
        val tmp = Files.createTempDirectory("damage-snap-restart")
        val clock = SceneClock(FEED_T0)
        val scripted = wm.damage.core.windows.feed.ScriptedFeed(nowMs = FEED_T0)
        val provider = SceneFeed(scripted)
        val item16 = wm.damage.core.windows.feed.FeedIds.id("popular", "t3_p16")
        fun opened() = scripted.ops.count { it == "article:$item16" }
        // B16: the presenter over one scratch record the three sessions share (the PC's record outlives a shell)
        val record = wm.damage.core.app.AppHost.inScratchDir(Files.createDirectories(tmp.resolve("record")))
        val before = snapShell
        suspend fun quiet(shell: Shell, where: String) {
            waitFor("the feed provider answered ($where)") { provider.inFlight() == 0 && shell.isQuiescent() }
            settle(shell, where)
        }
        /** The archive, notch by notch, until its top line is in page 2. */
        suspend fun bingeToPage2(shell: Shell, feedWin: wm.damage.core.windows.feed.FeedWindow, tag: String) {
            for (k in 0 until 20) {
                if (feedWin.title() == "8bt 2") return
                shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                quiet(shell, "$tag-notch-$k")
            }
            failures.add("the archive never reached page 2 ($tag, title '${feedWin.title()}')")
        }
        /** One shell's life over the run's store: Feed registered, started, [steps], stopped (its state saved). */
        suspend fun session(tag: String, steps: suspend (Shell, GlassFirmwareSim, wm.damage.core.windows.feed.FeedWindow) -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sim = GlassFirmwareSim()
            val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val text = wm.damage.core.comp.CachedText(AwtText())
            val shell = Shell(text, transport, Persistence(tmp.resolve("state.json")), tmp.resolve("journal-$tag.jsonl"), scope)
            val core = wm.damage.core.windows.feed.FeedCore(provider)
            val feedWin = wm.damage.core.windows.feed.FeedGlasses(text, provider, scope, core,
                wm.damage.core.app.LocalWindowSource(core, wm.damage.core.app.HostBackend(record,
                    wm.damage.core.app.Peer.LOCAL.device, wm.damage.core.app.OpQueue.newStream())), clock = clock::now)
            shell.register(feedWin)
            snapShell = shell
            try {
                shell.start()
                settle(shell, "restart-$tag-start")
                steps(shell, sim, feedWin)
            } finally {
                shell.stop()
                feedWin.detach()
                scope.cancel()
            }
        }
        try {
            session("a") { shell, sim, feedWin ->
                walkTo(shell, "feed", "restart-a")
                waitFor("Feed at its sources (restart a)") { feedWin.title() == "feed" }
                feedRowOf(shell, feedWin, "popular")
                shell.postGesture(EvenHubMsg.EV_CLICK)
                waitFor("popular's list (restart a)") { feedWin.title() == "popular" && feedWin.itemsLoaded() }
                quiet(shell, "restart-a-list")
                repeat(16) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                shell.postGesture(EvenHubMsg.EV_CLICK)
                waitFor("item 16 (restart a)") { feedWin.title() == "article" && opened() == 1 }
                quiet(shell, "restart-a-article")
                repeat(2) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                quiet(shell, "restart-a-place")
                save(sim, out, "79a-feed-place-before-restart")
            }
            session("b") { shell, sim, feedWin ->
                waitFor("the article restored") { feedWin.title() == "article" && opened() == 2 }
                quiet(shell, "restart-b-article")
                save(sim, out, "79b-feed-place-after-restart")
                shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)  // → the list
                shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)  // → the sources
                waitFor("the sources (restart b)") { feedWin.title() == "feed" }
                feedRowOf(shell, feedWin, "8bt")
                shell.postGesture(EvenHubMsg.EV_CLICK)
                waitFor("the archive (restart b)") { feedWin.title() == "8bt 1" }
                quiet(shell, "restart-b-binge")
                bingeToPage2(shell, feedWin, "restart-b")
                save(sim, out, "80a-feed-binge-before-restart")
            }
            session("c") { shell, sim, feedWin ->
                waitFor("the archive restored") { feedWin.title() == "8bt 2" }
                quiet(shell, "restart-c-binge")
                save(sim, out, "80b-feed-binge-after-restart")
            }
        } finally {
            snapShell = before
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * C3 AS: Settings → Animations at ladder stage 5, every row at its default — the page of tiers (37h) and the Select
     * tier's sub menu (37i). A shell of its own over the firmware model of a bit-11 build at stage 5 (the transition rigs'
     * form: DamageCaps 0xdff, `probe c1=5`, the model's engine woken every 4 ms), reached by Settings' `cat:` deep link,
     * one notch and one tap, so the sub menu is entered by the gesture he uses. Into the first arrangement's directory;
     * no icon theme (the drawn icons). Build S1-5 S: the shell's settings on a scratch PC record of its own (the first
     * arrangement's form), so these rows are judged on the record's path.
     */
    private suspend fun animationsScenes(out: Path) {
        val tmp = Files.createTempDirectory("damage-snap-anim")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xdff }
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val persistence = Persistence(tmp.resolve("state.json"))
        val shell = Shell(wm.damage.core.comp.CachedText(AwtText()), transport, persistence, tmp.resolve("journal.jsonl"), scope)
        val record = wm.damage.core.app.AppHost.inScratchDir(Files.createDirectories(tmp.resolve("record")))
        settingsOn(shell, record)
        val before = snapShell
        snapShell = shell
        try {
            transport.devProbe("c1", "5")
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in wm.damage.core.transport.Arm.entries) sim.conformanceWake(a, now); delay(4) } }
            shell.start()
            settle(shell, "anim-start")
            waitFor("stage 5 in force") { transport.state.value.effects && shell.isQuiescent() }
            settingsCategoryScene(shell, sim, out, "Animations", "37h-animations")
            shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)          // Scroll → Select
            settle(shell, "anim-select-row")
            shell.postGesture(EvenHubMsg.EV_CLICK)                  // open its sub menu
            // the window's saved state names the open tier (it is saved two seconds after the tap)
            waitFor("Animations · Select open") {
                persistence.get("window.settings")?.get("openSub")?.jsonPrimitive?.contentOrNull == "Select" && shell.isQuiescent() }
            settle(shell, "anim-select")
            save(sim, out, "37i-animations-select")
        } finally {
            shell.stop()
            shell.settingsSource = null
            record.close()
            scope.cancel()
            snapShell = before
            tmp.toFile().deleteRecursively()
        }
    }

    /** Build S1-5 S: [shell]'s settings on [record] (the PC's arrangement, `runShell`'s): its start folds the record's
     *  values, and every row's change is an op on it. */
    private fun settingsOn(shell: Shell, record: wm.damage.core.app.AppHost) {
        shell.settingsSource = wm.damage.core.app.LocalWindowSource(wm.damage.core.shell.SettingsCore(),
            wm.damage.core.app.HostBackend(record, wm.damage.core.app.Peer.LOCAL.device, wm.damage.core.app.OpQueue.newStream()))
    }

    /** Rest Feed's source cursor on the row NAMED [id] — by identity, never by counting. */
    private suspend fun feedRowOf(shell: Shell, feedWin: wm.damage.core.windows.feed.FeedWindow, id: String) {
        for (k in 0 until 12) {
            settle(shell, "feed-row-$id")
            if (feedWin.rootRowId() == id) return
            shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        }
        failures.add("could not reach the Feed source row '$id'")
    }

    /** Build S1-3 J: the window [id] from wherever the shell is: back to Main, then Main's rows committed one by one. */
    private suspend fun toWindowFrom(shell: Shell, id: String, tag: String) {
        var guard = 0
        while (shell.currentWindowId() != null && guard++ < 10) {
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            settle(shell, "back-to-main-$tag")
        }
        walkTo(shell, id, tag)
    }

    /** Build S1-3 J: a scene's clock — a fixed instant that only the script moves. */
    private class SceneClock(private val t0: Long) {
        private val skew = java.util.concurrent.atomic.AtomicLong(0)
        fun now(): Long = t0 + skew.get()
        fun advance(ms: Long) { skew.addAndGet(ms) }
    }

    /** Feed's scene instant (build S1-3 J), the Music player's (C1-R6 B6-7). */
    private const val FEED_T0 = 1_763_000_000_000L

    private suspend fun settle(shell: Shell, where: String = "?") {
        val t0 = System.currentTimeMillis()
        // 🔴 ONE evaluation decides it. Re-testing `isQuiescent()` after the
        // loop already saw it true is a race against every periodic tick — the
        // clock posts a message each second, the Hold'em pacer posts its own —
        // so a settle that succeeded could still report failure, which is
        // exactly what these scenes did about once in ten runs, in a different
        // place each time and with an EMPTY pending list to show for it
        // (review 2026-09-05). SelfCheck's `settle` was always this shape.
        var ok = false
        while (System.currentTimeMillis() - t0 < 60_000) {
            if (shell.isQuiescent()) { ok = true; break }
            delay(20)
        }
        (System.currentTimeMillis() - t0).let { if (it > 5_000) println("  (settled in ${it / 1000}s at '$where')") }
        // say WHAT was still pending and where the shell was: "did not settle"
        // on its own names no cause, and this harness's whole job is to be
        // specific about a wrong state
        if (!ok) failures.add("shell did not settle at '$where' " +
            "[in=${shell.currentWindowId() ?: "main"} ${shell.quiescenceReport()}] — snapshots may show mid-states")
        delay(50)
    }

    /**
     * Wait for a scene to reach the state its snapshot is OF.
     *
     * The bound is a harness backstop, not a policy: it exists so a scene that
     * can never arrive says so instead of stopping here, and it is deliberately
     * far above anything measured. The longest real waits are the Hold'em ones
     * — between Adam's actions every remaining bot runs `Equity.LIVE_ROLLOUTS`
     * (2000) rollouts per decision and the board reveal is paced. At 30 s those
     * reported failure while the table was still making progress (review
     * 2026-09-05), so the bound is 120 s and any wait past 5 s prints its cost.
     */
    private suspend fun waitFor(label: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        // one evaluation decides it, for the same reason `settle` does: several
        // of these conditions include `isQuiescent()`
        var ok = false
        while (System.currentTimeMillis() - t0 < 120_000) {
            if (cond()) { ok = true; break }
            delay(25)
        }
        val ms = System.currentTimeMillis() - t0
        if (!ok) failures.add("wait '" + label + "' never became true — snapshots are of the WRONG state")
        else if (ms > 5_000) println("  (waited ${ms / 1000}s for $label)")
    }

    /**
     * 🔴 The panel is copied ON the shell loop (`Shell.sampleIdle`, review §30),
     * not read from this thread while the shell is free to repaint under the
     * scan: these PNGs are the evidence a person judges the design by, and a
     * torn one is a design decision made against a frame that never existed.
     * The same torn read failed the `--selfcheck` oracle one run in ten.
     */
    private suspend fun save(sim: GlassFirmwareSim, dir: Path, name: String) {
        val ctx = sim.left
        val shell = snapShell
        var pan: ByteArray? = null
        if (shell != null) {
            val deadline = System.currentTimeMillis() + 20_000
            while (pan == null && System.currentTimeMillis() < deadline) {
                if (!shell.isQuiescent()) { delay(5); continue }
                pan = shell.sampleIdle { ctx.panel.copyOf() }
            }
        }
        if (pan == null) {
            // still write the picture — one that may be torn, with a line
            // saying so, beats no picture at all
            failures.add("snapshot '$name' could not be taken on a settled shell " +
                "— ${shell?.quiescenceReport() ?: "no shell registered"}; it may show a mid-state")
            pan = ctx.panel.copyOf()
        }
        val img = BufferedImage(Geometry.PANEL_W, Geometry.PANEL_H, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
            val b = pan[y * ctx.stride + (x shr 1)].toInt() and 0xFF
            val n = if (x and 1 == 0) b shr 4 else b and 0x0F
            val v = n * 17
            img.setRGB(x, y, ((v * 0.16).toInt() shl 16) or (minOf(255, (v * 1.05).toInt()) shl 8) or (v * 0.34).toInt())
        }
        ImageIO.write(img, "png", dir.resolve("$name.png").toFile())
        // build S1-2 J0: the scene's mark in the shell journal, after every flush its picture shows — `tools/flushdiff.py`
        // prices the first flush of the gesture before each mark
        shell?.journalNote("scene", name)
        println("  wrote $name.png")
    }

    /** Each arrangement's shell journal, beside its pictures (build S1-2 J0). */
    private const val JOURNAL = "journal.jsonl"
}
