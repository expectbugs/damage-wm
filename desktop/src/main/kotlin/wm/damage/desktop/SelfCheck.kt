package wm.damage.desktop

import java.nio.file.Files
import wm.damage.core.transport.Arm
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.content.LocalContent
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Pack
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.replica.ReplicaServer
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.windows.reader.ReaderWindow

/**
 * Headless scripted verification of the WHOLE stack — shell -> compositor ->
 * emit -> AA/EvenHub wire -> sim firmware -> per-lens shadow — with real fonts.
 * Exit 0 only when every assertion holds. This is the repeatable gate the
 * review loop runs; a hang here is a failure, so waits are bounded LOUDLY
 * (test harness only — never runtime code, where NO TIMEOUTS stands).
 */
object SelfCheck {
    private val failures = ArrayList<String>()
    /** Written from the transport's event collector (any dispatcher thread),
     *  read from the script at the end: a plain Int has no visibility
     *  guarantee across that hand-off, and a gate that reads a stale 0 passes
     *  a run that actually faulted — a silent failure in the thing whose job
     *  is to have none (review 2026-09-02). */
    private val faults = java.util.concurrent.atomic.AtomicInteger()

    /**
     * The per-lens TRUTH oracle, run on EVERY settle (review 2026-09-05).
     *
     * The shell's own divergence check compares its BELIEF to the glass, so a
     * defect that writes wrong pixels into the shadow and then sends them is
     * invisible to it (`HANDOFF.md` §25). This recomputes the independent
     * truth of `comp.composed` under `comp.planes` — splitting the panel by
     * plane PIECES — and compares THAT to the firmware model, over every
     * surface the script visits, with the real faces. It also catches ink
     * painted into `composed` outside any damaged rect, which is the class
     * that produced the notification-body, menu-detail and Settings-row
     * findings: unsent ink shows up here as truth ≠ glass.
     */
    private var oracleSim: GlassFirmwareSim? = null
    private var oracleShell: Shell? = null
    private var oracleRuns = 0

    /** One settled, ATOMIC reading of the whole system (review §30). */
    private class Sample(
        val composed: wm.damage.core.gfx.Gray8,
        val planes: List<wm.damage.core.comp.Compositor.PlaneRegion>,
        val left: ByteArray,
        val right: ByteArray,
        val stride: Int,
    )

    /**
     * 🔴 The sample is taken ON the shell loop with nothing else pending
     * (`Shell.sampleIdle`), never field by field from this thread: composed,
     * the plane map and the two panels read separately span a window in which
     * the shell can run a whole repaint, and the oracle then compares halves
     * of two different frames. That torn read failed the standing gate about
     * one run in ten — 16,963 px at 'scale130-reader-in', with a SECOND LOOK
     * that agreed (review §30).
     */
    private suspend fun runOracle(label: String) {
        val sim = oracleSim ?: return
        val shell = oracleShell ?: return
        var s: Sample? = null
        // an idle sample can be refused when the shell picked work up again
        // between the settle and the ask; settle and re-ask until it holds
        // still, bounded so a shell that never settles fails loudly instead
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!shell.isQuiescent()) { delay(5); continue }
            s = shell.sampleIdle {
                Sample(shell.comp.composed.copy(), shell.comp.planes.toList(),
                    sim.left.panel.copyOf(), sim.right.panel.copyOf(), sim.left.stride)
            }
            if (s != null) break
        }
        if (s == null) {
            failures.add("truth oracle at '$label': the shell never held still long enough to sample " +
                "(${shell.quiescenceReport()}, quiescent=${shell.isQuiescent()})")
            return
        }
        oracleRuns++
        for (arm in Arm.entries) {
            val left = arm == Arm.LEFT
            val panel = if (left) s.left else s.right
            val truth = truthOf(s.composed, s.planes, left)
            for (y in 0 until wm.damage.core.geom.Geometry.PANEL_H) {
                for (x in 0 until wm.damage.core.geom.Geometry.PANEL_W) {
                    val b = panel[y * s.stride + (x shr 1)].toInt() and 0xFF
                    val got = if (x and 1 == 0) b shr 4 else b and 0x0F
                    val want = Pack.level(truth[x, y])
                    if (want != got) {
                        failures.add("truth oracle at '$label': ${arm.name} glass != truth at " +
                            "($x,$y) — expected level $want, glass shows $got; planes=" +
                            s.planes.joinToString { p -> "${p.rect}@${p.disparity}" })
                        return
                    }
                }
            }
        }
    }

    /** The nominal frame is the transparent base; every plane region vacates
     *  its nominal area, and the region PIECES render at their shift far to
     *  near, the nearest winning. Written without reference to the
     *  compositor's own renderTruth. */
    private fun truthOf(
        composed: wm.damage.core.gfx.Gray8,
        planes: List<wm.damage.core.comp.Compositor.PlaneRegion>,
        left: Boolean,
    ): wm.damage.core.gfx.Gray8 {
        val w = wm.damage.core.geom.Geometry.PANEL_W
        val h = wm.damage.core.geom.Geometry.PANEL_H
        val out = composed.copy()
        for (p in planes) out.fillRect(p.rect, 0)
        val xs = sortedSetOf(0, w)
        val ys = sortedSetOf(0, h)
        for (p in planes) { xs.add(p.rect.x); xs.add(p.rect.right); ys.add(p.rect.y); ys.add(p.rect.bottom) }
        val xa = xs.toIntArray()
        val ya = ys.toIntArray()
        class Piece(val r: Rect, val d: Int)
        val pieces = ArrayList<Piece>()
        for (i in 0 until xa.size - 1) for (j in 0 until ya.size - 1) {
            val r = Rect(xa[i], ya[j], xa[i + 1] - xa[i], ya[j + 1] - ya[j])
            if (r.w <= 0 || r.h <= 0) continue
            val owner = planes.lastOrNull { it.rect.contains(r) } ?: continue
            pieces.add(Piece(r, owner.disparity))
        }
        for (p in pieces.sortedByDescending { it.d }) {
            val shift = if (left) -p.d else p.d
            for (y in p.r.y until p.r.bottom) for (x in p.r.x until p.r.right) {
                val tx = x + shift
                if (tx in 0 until w) out[tx, y] = composed[x, y]
            }
        }
        return out
    }

    private fun check(what: String, ok: Boolean) {
        val mark = if (ok) "PASS" else "FAIL"
        println("  $mark  $what")
        if (!ok) failures.add(what)
    }

    fun run(cfg: Config): Nothing {
        val tmp = Files.createTempDirectory("damage-selfcheck")
        try {
            runBlocking { script(tmp) }
        } catch (e: Throwable) {
            e.printStackTrace()
            failures.add("selfcheck crashed: $e")
        } finally {
            tmp.toFile().deleteRecursively()
        }
        println()
        if (failures.isEmpty()) {
            println("selfcheck: ALL CHECKS PASS")
            kotlin.system.exitProcess(0)
        } else {
            println("selfcheck: ${failures.size} FAILURE(S):")
            for (f in failures) println("  - $f")
            kotlin.system.exitProcess(1)
        }
    }

    private suspend fun script(tmp: Path) {
        // a small library: one txt book with known content
        val books = tmp.resolve("books")
        Files.createDirectories(books)
        val bookText = buildString {
            appendLine("CHAPTER I")
            appendLine()
            for (i in 1..300) {
                appendLine("Paragraph $i. The spice must flow, and the sleeper must awaken; " +
                    "the compositor batches all damage for a frame into one atomic flush.")
                appendLine()
            }
        }
        Files.writeString(books.resolve("selfcheck-book.txt"), bookText)

        // a deterministic filesystem for the Files window (2026-09-01): one
        // SC root with a folder, a text file and a real PNG
        val scRoot = tmp.resolve("sc-root")
        Files.createDirectories(scRoot.resolve("sub"))
        Files.writeString(scRoot.resolve("sub").resolve("inner.txt"), "inner\n")
        Files.writeString(scRoot.resolve("a.txt"),
            (1..80).joinToString("\n") { "line $it of the selfcheck file" } + "\n")
        run {
            val img = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 64) for (x in 0 until 64) img.setRGB(x, y, (x * 4 shl 16) or (y * 4 shl 8))
            javax.imageio.ImageIO.write(img, "png", scRoot.resolve("img.png").toFile())
        }
        val filesLocal = wm.damage.core.windows.files.LocalFilesProvider(
            books, tmp.resolve("trash"), AwtImages(), mountsFile = tmp.resolve("no-mounts"))
        val filesProvider = object : wm.damage.core.windows.files.FilesProvider by filesLocal {
            override fun locations(): List<wm.damage.core.windows.files.FLocation> =
                listOf(wm.damage.core.windows.files.FLocation("SC", scRoot.toString(), "mount",
                    1_000_000, 400_000)) +
                    filesLocal.locations().filter { it.kind == "trash" }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val text = AwtText()
        val persistence = Persistence(tmp.resolve("state.json"))
        val shell = Shell(text, transport, persistence, tmp.resolve("journal.jsonl"), scope)
        val reader = ReaderWindow(text, LocalContent(books), scope, AwtImages())
        shell.register(reader)
        val tmuxScripted = ScriptedTmux()
        val tmuxWin = wm.damage.core.windows.tmux.TmuxWindow(text, tmuxScripted, scope)
        shell.register(tmuxWin)
        val filesWin = wm.damage.core.windows.files.FilesWindow(text, filesProvider, scope, AwtImages())
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
        /** Open the Music menu (from the root) and commit the row LABELLED
         *  [label] — by name, never by counting notches, so a new row cannot
         *  silently move what the harness selects. */
        suspend fun musicMenu(label: String) {
            settle(shell, "music-root-before-$label")
            if (!shell.menuIsOpen) {
                shell.postGesture(EvenHubMsg.EV_CLICK)
                awaitTrue("the Music menu for '$label'") { shell.menuIsOpen }
            }
            val i = shell.menuLabels.indexOfFirst { it == label || it.startsWith(label) }
            if (i < 0) { failures.add("Music menu row '$label' not in ${shell.menuLabels}"); return }
            repeat((i - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            shell.postGesture(EvenHubMsg.EV_CLICK)
        }

        val flushFails = java.util.concurrent.atomic.AtomicInteger()   // same cross-thread read as `faults`
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            transport.events.collect {
                when (it) {
                    is TransportEvent.FlushDone -> if (!it.ok) flushFails.incrementAndGet()
                    is TransportEvent.Fault -> if (it.what !in setOf("lease")) faults.incrementAndGet()
                    else -> {}
                }
            }
        }

        oracleSim = sim
        oracleShell = shell
        shell.start()
        settle(shell, "boot")
        check("shell boots and reaches quiescence", shell.isQuiescent())
        check("connect prelude acked by the firmware model", sim.preludeAcks >= 1)
        check("capability gate passed (started)", transport.state.value.started)
        check("FB lease held after start", transport.state.value.leaseHeld)
        check("keyframe delivered (left shadow seeded)", sim.left.seeded)
        check("panel is not blank after boot", sim.left.panel.any { it.toInt() != 0 })

        // ---- browser replica: the page is served, the token gate holds
        val rp = java.net.ServerSocket(0).use { it.localPort }
        val replica = ReplicaServer(rp, "sc-token", { transport.mirror }, { ReplicaServer.Status(transport = "sim") },
            { shell.postGesture(it) })
        replica.start()
        fun http(path: String): String = java.net.Socket("127.0.0.1", rp).use { s ->
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); s.getOutputStream().flush()
            s.getInputStream().bufferedReader().readText()
        }
        val page = http("/?token=sc-token")
        check("browser replica serves the page", page.startsWith("HTTP/1.1 200") && page.contains("Damage replica"))
        check("browser replica refuses a missing token", http("/").startsWith("HTTP/1.1 403"))
        replica.close()

        // ---- Main: scroll + wrap (one up from the top lands on Settings §4.2)
        val inkActive = Pack.inkFraction(shell.comp.composed)
        check("Main active ink <= 15% budget (was ${"%.1f".format(inkActive * 100)}%)", inkActive <= 0.15)
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
        settle(shell, "scroll-up-to-settings")

        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        settle(shell, "scroll-back")

        // ---- enter Reader -> library -> open the book
        shell.postGesture(EvenHubMsg.EV_CLICK)          // commit READER (cursor at 0)
        settle(shell, "enter-reader")
        awaitTrue("library loads") { reader.summary().line.contains("book") || reader.summary().line == "1 books" }
        shell.postGesture(EvenHubMsg.EV_CLICK)          // open the book
        awaitTrue("book opens and lays out") { reader.title().contains("p.") }
        settle(shell, "book-open")

        // ---- endless scroll: coalesced notches, mode-9 path, position moves
        val progressBefore = reader.summary().progress ?: 0.0
        repeat(20) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        settle(shell, "doc-scroll")
        val progressAfter = reader.summary().progress ?: 0.0
        check("document scrolled (progress $progressBefore -> $progressAfter)",
            progressAfter > progressBefore)

        // ---- actions level (tap descends §4.6), then back
        shell.postGesture(EvenHubMsg.EV_CLICK)
        settle(shell, "actions")
        check("tap descends to the actions level", reader.levelDepth() == 3)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        settle(shell, "back-to-book")
        check("double-tap returns to the book", reader.levelDepth() == 2)

        // ---- switcher: a bare long-press is a no-op (§1.2 default OFF,
        // 2026-08-30) — the tap right after it lands in the book, not a wheel
        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)
        shell.postGesture(EvenHubMsg.EV_CLICK)
        settle(shell, "bare-long-press")
        check("a bare long-press does nothing (default off): the tap landed in the book",
            reader.levelDepth() == 3)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        settle(shell, "back-from-actions-2")
        // the chord (§1.3): long-press then double-tap opens the wheel
        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        settle(shell, "switcher-open")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
        settle(shell, "switcher-spin")
        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)   // in-wheel long-press still cancels
        settle(shell, "switcher-cancel")
        check("switcher cancel restores the book view", reader.levelDepth() == 2)

        // ---- notification: post, grace, focus gestures
        shell.postNotice(wm.damage.core.shell.Notifications.Notice(
            "SMS · TEST", "t1", "on my way, should be there in about twenty minutes", "14:32"))
        settle(shell, "notice")
        check("notification box appears", shell.notifications.active)
        delay(3_000)                                        // the 2.5 s grace
        settle(shell, "grace")
        check("notification takes focus after the grace", shell.notifications.focused)
        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)    // no-op by default (§4.5, 2026-08-30)
        settle(shell, "notice-long-press")
        check("a long-press does not dismiss the focused notice (default off)", shell.notifications.active)
        delay(1_000)                                        // the chord window expires
        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)    // the chord: wheel over the box
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        settle(shell, "notice-chord")
        check("the chord parks the box and opens the wheel", !shell.notifications.active)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // cancel the wheel; the box returns
        settle(shell, "notice-return")
        awaitTrue("the box returns after the wheel") { shell.notifications.active }
        delay(3_000)                                        // its grace runs again
        settle(shell, "notice-regrace")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // dismiss (double-tap marks read)
        settle(shell, "dismiss")
        awaitTrue("notification dismisses") { !shell.notifications.active }

        // ---- back to Main, then silent mode and its minute clock
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // book -> library
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // library -> Main
        settle(shell, "to-main")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // Main -> silent
        settle(shell, "silent")
        val inkSilent = Pack.inkFraction(shell.comp.composed)
        check("silent mode ink <= 2% budget (was ${"%.2f".format(inkSilent * 100)}%)", inkSilent <= 0.02)
        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)    // swallowed in silent (gloves fix)
        settle(shell, "silent-longpress")
        check("silent mode swallows long-press", inkStill(shell, inkSilent))
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // silent -> Main
        settle(shell, "wake")

        // ---- Tmux (TMUX.md): sessions -> live grid -> history -> keys ->
        // typed-text confirm -> alert, all against the scripted provider
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)      // Main cursor: Reader -> Tmux
        settle(shell, "main-to-tmux-row")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        settle(shell, "tmux-sessions")
        check("tmux opens at the sessions list", tmuxWin.title() == "sessions")
        check("tmux summary shows the sessions", tmuxWin.summary().line.contains("2 session"))
        shell.postGesture(EvenHubMsg.EV_CLICK)              // open 'claude'
        settle(shell, "tmux-live")
        check("opening a session subscribes and shows the live grid",
            tmuxWin.title() == "claude" && tmuxScripted.sent.any { it == "sub:claude" })
        val inkTmux = Pack.inkFraction(shell.comp.composed)
        check("the live grid painted within the canvas ink note (was ${"%.1f".format(inkTmux * 100)}%)",
            inkTmux in 0.005..0.30)
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)         // scroll-up IS scrollback
        awaitTrue("history captures and wraps in") { tmuxWin.title().contains("history") }
        settle(shell, "tmux-history")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // history -> live
        settle(shell, "tmux-history-back")
        check("double-tap returns history to the live grid", tmuxWin.title() == "claude")
        shell.postGesture(EvenHubMsg.EV_CLICK)              // live -> keys
        settle(shell, "tmux-keys")
        check("tap descends to the keys level", tmuxWin.title().contains("keys"))
        shell.postGesture(EvenHubMsg.EV_CLICK)              // send Enter (cursor at row 0)
        awaitTrue("the quick key reached the session") { tmuxScripted.sent.contains("keys:claude:Enter") }
        settle(shell, "tmux-key-sent")
        check("sending a key drops back to the live view", tmuxWin.title() == "claude")
        transport.injectText("echo staged")                 // the replica typed-text path
        awaitTrue("typed text stages a confirm") { tmuxWin.title().contains("confirm") }
        check("nothing ran before the confirm", tmuxScripted.sent.none { it.startsWith("lit:") })
        shell.postGesture(EvenHubMsg.EV_CLICK)              // Run
        awaitTrue("the confirmed line ran literal + Enter") {
            tmuxScripted.sent.contains("lit:claude:echo staged") &&
                tmuxScripted.sent.count { it == "keys:claude:Enter" } == 2
        }
        settle(shell, "tmux-typed-ran")
        tmuxScripted.pushAlert()                            // 'claude' wants input
        awaitTrue("a tmux alert raises the notification box") { shell.notifications.active }
        check("the alert marks the window dirty", tmuxWin.dirty)
        delay(3_000)                                        // its grace
        settle(shell, "tmux-alert-grace")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // dismiss the alert box
        awaitTrue("the alert dismisses") { !shell.notifications.active }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // live -> sessions
        settle(shell, "tmux-to-sessions")
        check("leaving the live grid unsubscribes", tmuxScripted.sent.last() == "sub:null")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // sessions -> Main
        settle(shell, "tmux-to-main")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)         // Main cursor back to Reader
        settle(shell, "main-to-reader-row")

        // ---- Files (Adam's first conversion, 2026-09-01): locations →
        // tap-is-a-menu grammar → viewers, against the deterministic SC root
        repeat(2) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // Reader → Tmux → Files
        settle(shell, "main-to-files-row")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        settle(shell, "files-locations")
        awaitTrue("Files opens at locations") { filesWin.summary().line == "1 locations" }
        val inkLoc = Pack.inkFraction(shell.comp.composed)
        check("Files locations ink <= 15% list budget (was ${"%.1f".format(inkLoc * 100)}%)", inkLoc <= 0.15)
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // enter SC
        awaitTrue("the listing arrives") { filesWin.summary().detail.contains("1 folders") }
        settle(shell, "files-browse")
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // tap = context menu (sub)
        awaitTrue("tap opens the context menu, Open first") { shell.menuIsOpen }
        settle(shell, "files-menu")
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Open at cursor rest
        awaitTrue("two taps enter the folder") { filesWin.title().endsWith("/sub") }
        check("the menu closed on commit", !shell.menuIsOpen)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // ascend
        awaitTrue("double-tap ascends") { filesWin.title().endsWith("sc-root") }
        settle(shell, "files-ascended")
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)                  // sub → a.txt
        settle(shell, "files-to-a")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("menu on a.txt") { shell.menuIsOpen && shell.menuTitle == "a.txt" }
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Open
        awaitTrue("the text viewer opens") { filesWin.title() == "a.txt" }
        settle(shell, "files-text")
        val inkTxt = Pack.inkFraction(shell.comp.composed)
        check("Files text viewer ink <= 25% document budget (was ${"%.1f".format(inkTxt * 100)}%)", inkTxt <= 0.25)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // viewer → browse
        settle(shell, "files-back-browse")
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)                  // a.txt → img.png
        settle(shell, "files-to-img")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("menu on img.png") { shell.menuIsOpen && shell.menuTitle == "img.png" }
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Open
        awaitTrue("the image viewer opens") { filesWin.title() == "img.png" }
        settle(shell, "files-image")
        check("the image painted (panel not blank in content)",
            Pack.inkFraction(shell.comp.composed) > 0.02)
        repeat(3) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }     // viewer→browse→locations→Main
        settle(shell, "files-out")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)                     // Main cursor back toward Reader
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
        settle(shell, "main-back-to-reader-row")

        // ---- Torrents (TORRENTS.md, 2026-09-01): transfers → menu → details,
        // browse → listing → torrent page → add behind its confirm, search
        // through the §4.8 keyboard, the done edge as a notification
        repeat(3) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // Reader → Tmux → Files → Torrents
        settle(shell, "main-to-torrents-row")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("Torrents opens at the transfers list") { torrentsWin.title() == "transfers" }
        settle(shell, "torrents-transfers")
        check("the summary reads the scripted session", torrentsWin.summary().line.contains("downloading"))
        check("the focused pacing was requested on activation", torrentsScripted.ops.any { it.startsWith("focus:true") })
        val inkTor = Pack.inkFraction(shell.comp.composed)
        check("Torrents transfers ink <= 15% list budget (was ${"%.1f".format(inkTor * 100)}%)", inkTor <= 0.15)
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // tap = the transfer menu
        awaitTrue("tap opens the transfer menu, Details first") { shell.menuIsOpen }
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Details at cursor rest
        awaitTrue("Details opens as a document") { torrentsWin.title() == "details" }
        settle(shell, "torrents-details")
        check("details are one level down", torrentsWin.levelDepth() == 2)
        val inkDet = Pack.inkFraction(shell.comp.composed)
        check("Torrents details ink <= 25% document budget (was ${"%.1f".format(inkDet * 100)}%)", inkDet <= 0.25)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // → transfers
        awaitTrue("back to the transfers") { torrentsWin.title() == "transfers" }
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)                     // wrap to the Torrents menu row
        settle(shell, "torrents-menu-row")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("the wrap-end row opens the Torrents menu") { shell.menuIsOpen && shell.menuTitle == "torrents" }
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Browse TorrentLeech (row 0)
        awaitTrue("Browse opens the categories") { torrentsWin.title() == "browse" }
        settle(shell, "torrents-categories")
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Newest
        awaitTrue("the newest listing loads") {
            torrentsWin.title() == "newest" && torrentsScripted.ops.any { it.startsWith("browse:0:1") }
        }
        settle(shell, "torrents-listing")
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // the first item → torrent page
        awaitTrue("the torrent page opens") { torrentsWin.title() == "torrent" }
        settle(shell, "torrents-page")
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // tap → add menu
        awaitTrue("the add menu opens") { shell.menuIsOpen }
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Add to qBittorrent
        awaitTrue("Add stages a confirm") { shell.menuIsOpen && (shell.menuTitle ?: "").startsWith("Add ") }
        check("nothing was added before the confirm", torrentsScripted.added.isEmpty())
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)                  // Cancel → Add
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("the confirmed add reached the provider") { torrentsScripted.added.contains("241826800:false") }
        settle(shell, "torrents-added")
        repeat(3) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }     // page → listing → categories → transfers
        awaitTrue("back at the transfers") { torrentsWin.levelDepth() == 1 }   // the title carries the add notice for 4 s
        settle(shell, "torrents-back")
        // search through the keyboard: the cursor still rests on the menu row
        // it left from (the Files ascend rule) → Search → type 'u' → Enter
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("the Torrents menu again") { shell.menuIsOpen && shell.menuTitle == "torrents" }
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)                  // → Search TorrentLeech
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("Search opens the keyboard") { shell.keyboardIsOpen }
        settle(shell, "torrents-keyboard")
        val inkKb = Pack.inkFraction(shell.comp.composed)
        check("the keyboard painted (ink ${"%.1f".format(inkKb * 100)}%, wireframe by design)", inkKb in 0.02..0.30)
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)                     // home row → qwerty row
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // enter it on 'q'
        repeat(6) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // → 'u'
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // types 'u'
        awaitTrue("the ring typed 'u'") { shell.keyboardDraft() == "u" }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // KEY → ROW
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)                  // → home row
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // enter on 'a'
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)                     // wrap to Enter
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // commit
        awaitTrue("Enter runs the search") {
            !shell.keyboardIsOpen && torrentsScripted.ops.any { it.startsWith("search:u:1") }
        }
        awaitTrue("the results list is titled by the query") {
            torrentsWin.levelDepth() == 2 && torrentsWin.saveState()["listingQuery"]?.jsonPrimitive?.contentOrNull == "u"
        }
        settle(shell, "torrents-search")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // results → transfers
        awaitTrue("back from the search") { torrentsWin.title() == "transfers" }
        // the done edge → a notification, deep-linked
        torrentsScripted.fireDone()
        awaitTrue("a finished download raises the notification box") { shell.notifications.active }
        check("the done notice marks the window dirty", torrentsWin.dirty)
        delay(3_000)
        settle(shell, "torrents-done-grace")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // dismiss
        awaitTrue("the done notice dismisses") { !shell.notifications.active }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // transfers → Main
        settle(shell, "torrents-to-main")
        repeat(3) { shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }      // Main cursor back to Reader
        settle(shell, "main-back-to-reader-row-2")

        // ---- Music (MUSIC.md): the NOW PLAYING root (2026-09-03 — the queue
        // became a menu level), browse → artist → play through the set menu,
        // the queue level and its row menu, Ask through the keyboard (a replica
        // line commits the draft), lyrics, and the track-change notice off-screen
        repeat(4) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }   // Reader → Tmux → Files → Torrents → Music
        settle(shell, "main-to-music-row")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("Music opens at Now Playing") { musicWin.title() == "music" }
        settle(shell, "music-empty")
        check("the idle Now Playing summary is honest", musicWin.summary().line == "idle")
        val inkMusicEmpty = Pack.inkFraction(shell.comp.composed)
        check("Music idle Now Playing ink <= 15% (was ${"%.1f".format(inkMusicEmpty * 100)}%)", inkMusicEmpty <= 0.15)
        musicMenu("Browse")                                             // the root's tap IS the menu
        awaitTrue("Browse opens") { musicWin.title() == "browse" }
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Artists
        awaitTrue("Artists list") { musicWin.title() == "artists" }
        settle(shell, "music-artists")
        val inkArtists = Pack.inkFraction(shell.comp.composed)
        check("Music artists ink <= 15% (was ${"%.1f".format(inkArtists * 100)}%)", inkArtists <= 0.15)
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // the first artist
        awaitTrue("an artist opens") { musicWin.levelDepth() == 4 }
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // All tracks → the set menu
        awaitTrue("the set menu opens, Play now first") { shell.menuIsOpen }
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Play now
        awaitTrue("the queue plays") { musicPlayer.state.play == wm.damage.core.windows.music.PlayState.PLAYING }
        repeat(3) { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK) }     // → Now Playing
        awaitTrue("back at Now Playing") { musicWin.levelDepth() == 1 && musicWin.title() == "now playing" }
        settle(shell, "music-nowplaying")
        val inkCard = Pack.inkFraction(shell.comp.composed)
        check("Music Now Playing ink <= 15% (was ${"%.1f".format(inkCard * 100)}%)", inkCard <= 0.15)
        check("the summary reads the player", musicWin.summary().line.startsWith("playing · "))
        // the QUEUE is a menu level now (2026-09-03), and its rows keep the row menu
        musicMenu("Queue")
        awaitTrue("the queue level opens") { musicWin.title().startsWith("queue") && musicWin.levelDepth() == 2 }
        settle(shell, "music-queue")
        val inkQueue = Pack.inkFraction(shell.comp.composed)
        check("Music queue level ink <= 15% (was ${"%.1f".format(inkQueue * 100)}%)", inkQueue <= 0.15)
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // tap the current row = its menu, Pause first
        awaitTrue("the row menu opens") { shell.menuIsOpen }
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Pause
        awaitTrue("paused from the row menu") { musicPlayer.state.play == wm.damage.core.windows.music.PlayState.PAUSED }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // queue → Now Playing
        awaitTrue("back out of the queue") { musicWin.levelDepth() == 1 }
        musicMenu("Ask")
        awaitTrue("Ask opens the keyboard") { shell.keyboardIsOpen }
        transport.injectText("aggressive metal")                        // a replica line IS the draft and commits
        awaitTrue("the ask played the vocab lane") {
            musicLib.ops.contains("ask:aggressive metal") && musicPlayer.state.label.contains("metal") && musicPlayer.state.play == wm.damage.core.windows.music.PlayState.PLAYING
        }
        settle(shell, "music-ask")
        // lyrics through the Music menu: the first track ("Time") has synced lyrics
        musicPlayer.playQueue(listOf(musicLib.catalog().tracks[0].ref()), 0, wm.damage.core.windows.music.Mode.QUEUE, "Time")
        awaitTrue("Time plays") { musicPlayer.state.track?.id == 1 }
        musicMenu("Lyrics")
        awaitTrue("Lyrics opens") { musicWin.title() == "lyrics" }
        settle(shell, "music-lyrics")
        musicSkew += 6_000
        awaitTrue("the synced line advanced") { musicPlayer.positionMs() >= 5_000 }
        settle(shell, "music-lyrics-advance")
        val inkLyrics = Pack.inkFraction(shell.comp.composed)
        check("Music lyrics ink <= 25% (was ${"%.1f".format(inkLyrics * 100)}%)", inkLyrics <= 0.25)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // → Now Playing
        awaitTrue("back from lyrics") { musicWin.levelDepth() == 1 }
        // Music Mode (§8.3 / DESIGN §4.9) at 480 with Bars, then at 288 with Scope: the
        // shell's exclusive mode swallows everything but double-tap; notices show small
        shell.services.runOnShell {
            musicWin.appSettings().first { it.name == "Visualizer" }.apply("Bars")
            musicWin.appSettings().first { it.name == "Music Mode · visualizer" }.apply("on")
            musicWin.appSettings().first { it.name == "Music Mode · queue peek" }.apply("on")
        }
        // a real queue, so the mode's own surfaces can be driven as DELTAS
        // (a one-track queue ends instead of advancing)
        musicPlayer.playQueue(musicLib.catalog().tracks.take(3).map { it.ref() }, 0,
            wm.damage.core.windows.music.Mode.QUEUE, "Music Mode walk")
        awaitTrue("a three-track queue for Music Mode") { musicPlayer.state.queue.size == 3 }
        for ((h, viz) in listOf(480 to "Bars", 288 to "Scope")) {
            shell.services.runOnShell {
                musicWin.appSettings().first { it.name == "Size" }.apply("$h")
                musicWin.appSettings().first { it.name == "Visualizer" }.apply(viz)
            }
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)               // → Main (the Size applies on the next focus)
            awaitTrue("Music → Main ($h)") { shell.currentWindowId() == null }
            shell.postGesture(EvenHubMsg.EV_CLICK)
            awaitTrue("Music at $h") { shell.currentWindowId() == "music" }
            musicMenu("Music Mode")
            awaitTrue("Music Mode enters at $h") { shell.exclusiveMode }
            settle(shell, "music-mode-$h")
            val inkMm = Pack.inkFraction(shell.comp.composed)
            check("Music Mode at $h with $viz painted within the canvas note (was ${"%.1f".format(inkMm * 100)}%)", inkMm in 0.005..0.30)
            shell.postGesture(EvenHubMsg.EV_CLICK)                      // swallowed
            shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)              // swallowed
            shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)            // swallowed, never arms
            settle(shell, "music-mode-swallow-$h")
            check("Music Mode swallows tap/scroll/long-press at $h", shell.exclusiveMode && !shell.menuIsOpen && !shell.switcherIsOpen)
            // advance the queue WHILE the mode is on: the card, the queue peek
            // and the PC badge all repaint as DELTAS, and the truth oracle in
            // settle() then sees whatever they ink outside their own rects —
            // which is exactly how the 2026-09-05 card overrun was found
            val mmTrack = musicPlayer.state.entry?.qid
            musicPlayer.next()
            awaitTrue("the queue advanced inside Music Mode ($h)") { musicPlayer.state.entry?.qid != mmTrack }
            settle(shell, "music-mode-advance-$h")
            musicSkew += 40_000
            settle(shell, "music-mode-progress-$h")
            shell.postNotice(wm.damage.core.shell.Notifications.Notice("SMS · TEST", "mm$h", "a notice over music mode", "14:32"))
            awaitTrue("a notice shows over Music Mode ($h)") { shell.notifications.active }
            awaitTrue("and auto-dismisses ($h)") { !shell.notifications.active }
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)               // exits to the window
            awaitTrue("double-tap leaves Music Mode ($h)") { !shell.exclusiveMode && shell.currentWindowId() == "music" }
            settle(shell, "music-mode-exit-$h")
        }
        shell.services.runOnShell { musicWin.appSettings().first { it.name == "Size" }.apply("global") }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // → Main
        awaitTrue("Music → Main") { shell.currentWindowId() == null }
        musicPlayer.next()                                              // off-screen: the track-change notice
        awaitTrue("a track change off-screen raises the notification box") { shell.notifications.active }
        check("the notice marks Music dirty", musicWin.dirty)
        delay(3_000)
        settle(shell, "music-notice-grace")
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)                   // dismiss
        awaitTrue("the music notice dismisses") { !shell.notifications.active }
        gamesChecks(shell, gamesWin)

        typeLadderTopEnd(shell, musicWin, musicPlayer) { label -> musicMenu(label) }

        // ---- persistence round trip: leave a BOOK open, restart, land back in it
        toWindow(shell, "reader")
        awaitTrue("reader reopens") { shell.currentWindowId() == "reader" && shell.isQuiescent() }
        shell.postGesture(EvenHubMsg.EV_CLICK)              // open the book again
        awaitTrue("book reopens before shutdown") { reader.levelDepth() >= 2 }
        settle(shell, "pre-shutdown")
        awaitTrue("reopening a book mid-session restores its position (§9.1)") {
            (reader.summary().progress ?: 0.0) > 0.0
        }
        val posBefore = reader.saveSubState().toString()   // §16.4a: per-book sub-records
        shell.stop()
        torrentsWin.detach()                                // the stack-stop rule, mirrored (review P1)
        musicWin.detach()

        val persistence2 = Persistence(tmp.resolve("state.json"))
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sim2 = GlassFirmwareSim()
        val transport2 = SimTransport(sim2, scope2, SimTransport.Timing(instant = true))
        val shell2 = Shell(text, transport2, persistence2, null, scope2)
        val reader2 = ReaderWindow(text, LocalContent(books), scope2, AwtImages())
        shell2.register(reader2)
        shell2.register(wm.damage.core.windows.tmux.TmuxWindow(text, ScriptedTmux(), scope2))
        shell2.register(wm.damage.core.windows.files.FilesWindow(text, filesProvider, scope2, AwtImages()))
        shell2.register(wm.damage.core.windows.torrents.TorrentsWindow(text, ScriptedTorrents(), scope2))
        val musicLib2 = ScriptedMusic()
        shell2.register(wm.damage.core.windows.music.MusicWindow(text, musicLib2, wm.damage.core.windows.music.SimMusicPlayer(musicLib2), scope2))
        shell2.start()
        // the oracle follows the LIVE pair (review §30): left pointing at the
        // stopped shell and its sim, every settle after the restart was either
        // skipped or compared a stopped shell against its own frozen glass —
        // a free pass over the whole restored session
        oracleSim = sim2
        oracleShell = shell2
        settle(shell2, "restart")
        awaitTrue("restored reader reopens its book (mode, not just position §9.1)") {
            reader2.levelDepth() >= 2
        }
        val posAfter = reader2.saveSubState().toString()
        check("reading position survives restart ($posBefore -> $posAfter)", posBefore == posAfter)
        shell2.stop()
        scope2.cancel()

        check("no failed flushes anywhere (were ${flushFails.get()})", flushFails.get() == 0)
        check("no transport faults (decode/fid/session, were ${faults.get()})", faults.get() == 0)
        val flags = sim.flags(Arm.LEFT).filterValues { it }
        check("no sticky diagnostic flags (were $flags)", flags.isEmpty())
        check("the per-lens truth oracle ran on every settled surface (was $oracleRuns)", oracleRuns >= 100)
        scope.cancel()
    }



    /**
     * The TYPE LADDER'S TOP END (review 2026-09-05). Every "measured vs
     * guessed" defect this project has shipped is invisible at 100 % and
     * obvious at 130 %: the Music Mode card's bottom row inked past its own
     * rect at 100 %, the Games lens ladder and the history band did the same
     * at their design sizes. Walk the surfaces again with the global font
     * scale at the top of the ladder, with the truth oracle running on every
     * settle — a rect that does not hold what it draws shows up here as
     * glass != truth.
     *
     * Its own function for the same reason `gamesChecks` is: `script()` is at
     * the JVM's 64 KB method limit.
     */
    private suspend fun typeLadderTopEnd(
        shell: Shell,
        musicWin: wm.damage.core.windows.music.MusicWindow,
        musicPlayer: wm.damage.core.windows.music.SimMusicPlayer,
        musicMenu: suspend (String) -> Unit,
    ) {
            val big = wm.damage.core.shell.ShellSettings(fontScale = 1.3)
            check("the shell accepts the top of the font ladder",
                shell.postSync("shell.settings", big.toJson(), System.currentTimeMillis()))
            awaitTrue("the 130 % scale applied") { shell.settings.fontScale == 1.3 }
            settle(shell, "scale130-main")
            for (id in listOf("reader", "tmux", "files", "torrents", "music", "games", "settings")) {
                toWindow(shell, id)
                settle(shell, "scale130-$id")
                shell.postGesture(EvenHubMsg.EV_CLICK)          // one level in
                settle(shell, "scale130-$id-in")
                if (shell.menuIsOpen || shell.keyboardIsOpen) {
                    shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                    settle(shell, "scale130-$id-cancel")
                }
            }
            // Music's own CANVASES at the top of the ladder: they own their
            // damage the way Music Mode does, so an overrun there is the same
            // undelivered ink
            toWindow(shell, "music")
            for (level in listOf("Lyrics", "Volume")) {
                musicMenu(level)
                settle(shell, "scale130-music-$level")
                shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                settle(shell, "scale130-music-$level-back")
            }
            // and Music Mode at the smallest and largest rungs, where the card
            // has the least room and the ladder bites hardest
            toWindow(shell, "music")
            // the earlier Music Mode walk consumed the queue: re-seed it, or
            // `next()` below ends the queue instead of advancing it
            val again = musicPlayer.state.queue.map { it.track }
            if (again.size >= 2) {
                musicPlayer.playQueue(again, 0, wm.damage.core.windows.music.Mode.QUEUE, "130 % walk")
                awaitTrue("the queue is re-seeded for the 130 % walk") { musicPlayer.state.index == 0 }
            }
            for (h in listOf(288, 480)) {
                shell.services.runOnShell { musicWin.appSettings().first { it.name == "Size" }.apply("$h") }
                shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                awaitTrue("Music → Main at 130 % ($h)") { shell.currentWindowId() == null }
                toWindow(shell, "music")
                musicMenu("Music Mode")
                awaitTrue("Music Mode at 130 % ($h)") { shell.exclusiveMode }
                settle(shell, "scale130-music-mode-$h")
                val mmTrack = musicPlayer.state.entry?.qid
                musicPlayer.next()
                awaitTrue("the queue advanced at 130 % ($h)") { musicPlayer.state.entry?.qid != mmTrack }
                settle(shell, "scale130-music-mode-advance-$h")
                shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                awaitTrue("out of Music Mode at 130 % ($h)") { !shell.exclusiveMode }
                settle(shell, "scale130-music-mode-exit-$h")
            }
            // …and the TALLEST face at the top of the ladder: Alegreya inks
            // 35 px at 130 % against a 28 px status bar, which is the case the
            // chrome cap exists for. Any bar that cannot hold its line shows
            // up in the oracle below.
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            awaitTrue("Music → Main before the face walk") { shell.currentWindowId() == null }
            check("the shell accepts the tallest face at the top of the ladder",
                shell.postSync("shell.settings",
                    wm.damage.core.shell.ShellSettings(fontScale = 1.3, fontFace = "Alegreya").toJson(),
                    System.currentTimeMillis() + 1))
            awaitTrue("Alegreya at 130 % applied") { shell.settings.fontFace == "Alegreya" }
            settle(shell, "alegreya130-main")
            // …at a REDUCED height, where the status bar's bottom is not the
            // panel's: at 480 an overflowing chrome line is clipped away by
            // the surface edge and looks fine; at 288 those rows land on real
            // panel nobody damages (review 2026-09-05)
            check("the shell accepts the tallest face at 130 % and 288",
                shell.postSync("shell.settings",
                    wm.damage.core.shell.ShellSettings(fontScale = 1.3, fontFace = "Alegreya", heightMode = 288).toJson(),
                    System.currentTimeMillis() + 1))
            awaitTrue("288 applied") { shell.layout.safe.h == 288 }
            settle(shell, "alegreya130-288-main")
            for (id in listOf("reader", "music", "games")) {
                toWindow(shell, id)
                settle(shell, "alegreya130-288-$id")
                shell.postGesture(EvenHubMsg.EV_CLICK)
                settle(shell, "alegreya130-288-$id-in")
                if (shell.menuIsOpen || shell.keyboardIsOpen) {
                    shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                    settle(shell, "alegreya130-288-$id-cancel")
                }
            }
            check("back to the full height for the face walk",
                shell.postSync("shell.settings",
                    wm.damage.core.shell.ShellSettings(fontScale = 1.3, fontFace = "Alegreya").toJson(),
                    System.currentTimeMillis() + 2))
            awaitTrue("480 restored") { shell.layout.safe.h == 480 }
            settle(shell, "alegreya130-480-restored")
            for (id in listOf("reader", "torrents", "games")) {
                toWindow(shell, id)
                settle(shell, "alegreya130-$id")
                shell.postGesture(EvenHubMsg.EV_CLICK)
                settle(shell, "alegreya130-$id-in")
                if (shell.menuIsOpen || shell.keyboardIsOpen) {
                    shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                    settle(shell, "alegreya130-$id-cancel")
                }
            }
            shell.services.runOnShell { musicWin.appSettings().first { it.name == "Size" }.apply("global") }
            check("the shell returns to the default scale",
                shell.postSync("shell.settings", wm.damage.core.shell.ShellSettings().toJson(),
                    System.currentTimeMillis() + 2))
            awaitTrue("back to 100 %") { shell.settings.fontScale == 1.0 }
            settle(shell, "scale100-restored")
    }

    /**
     * The GAMES window (`HOLDEM.md`). Its own function, not because it is
     * separable but because `script()` had reached the JVM's 64 KB method
     * limit — the compiler says "Method too large" and the build fails, which
     * is at least loud.
     */
    private suspend fun gamesChecks(shell: Shell, gamesWin: wm.damage.core.windows.games.GamesWindow) {
        // Walk Main by COMMITTING and checking, never by counting rows: the
        // row order is the registration order and one new window shifts every
        // count (the 2026-09-03 menu-row lesson, applied to Main).
        suspend fun gamesRow(label: String) {
            for (k in 0 until 8) {
                settle(shell, "games-row-$label")
                if (gamesWin.rootRow == label) return
                shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            }
            failures.add("the Games root has no '$label' row (rest ${gamesWin.rootRow})")
        }

        // 🔴 Type ladders are checked against the REAL rasterizer's metrics,
        // not against a hand-picked pitch. Both of these shipped wrong once:
        // the standings lens stacked three lines 16 px apart under fonts whose
        // ink is 23 and 20, and the history band used a 14 px pitch under a
        // face whose ink is 17 (the live session and review pass 3, 2026-09-04).
        run {
            val tx = AwtText()
            fun ink(px: Int, bold: Boolean = false) =
                tx.metrics(FontSpec(Face.SYSTEM, px, bold = bold)).let { it.ascent + it.descent }
            check("the lens ladder fits its three lines (2/28/48 in a 64 px lens)",
                2 + ink(17, bold = true) <= 28 && 28 + ink(13) <= 48 &&
                    48 + tx.metrics(FontSpec(Face.SYSTEM, 11)).ascent <= 62)
            val hist = wm.damage.core.windows.games.kit.TableLayout(
                wm.damage.core.geom.Layout(Rect(0, 0, 640, 480)).content, 480).history
            check("the 480 history band holds three MEASURED lines",
                hist.h / tx.metrics(FontSpec(Face.SYSTEM, 11)).lineHeight >= 3)
        }

        toWindow(shell, "games")
        awaitTrue("Games opens at its root list") {
            shell.currentWindowId() == "games" && gamesWin.levelName == "GAMES"
        }
        check("a fresh bankroll is the base \$1,000 (verdict 13)", gamesWin.bankroll.cash == 1_000)
        check("the room fills to the design's roster (§7.5)", gamesWin.roster.characters.size >= 30)
        gamesRow("Hold'em")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("the table-select level opens") { gamesWin.levelName == "TABLES" }
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // Regular
        awaitTrue("the buy-in stages a confirm") { shell.menuIsOpen }
        run {
            val i = shell.menuLabels.indexOfFirst { it.startsWith("Sit down") }
            check("the buy-in confirm offers Sit down (${shell.menuLabels})", i >= 0)
            repeat((i - shell.menuCursor).coerceAtLeast(0)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            settle(shell, "buyin-cursor")
            shell.postGesture(EvenHubMsg.EV_CLICK)
        }
        awaitTrue("the table opens and the bots act") { gamesWin.tableRunning }
        check("the entry AND its visible fee left the bankroll (verdict 24)",
            gamesWin.bankroll.cash == 1_000 - 200 - 10 && gamesWin.bankroll.feesPaid == 10)
        check("six seats, all in play (verdict 10)", gamesWin.seatsLeft == 6)
        awaitTrue("the pacer reaches your first decision") { gamesWin.isMyTurn }
        settle(shell, "games-your-turn")
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // the action level
        awaitTrue("your move opens the action level") { shell.menuIsOpen }
        check("row 0 is the contextual give-up row (verdict 33)",
            shell.menuLabels.firstOrNull() == "Check" || shell.menuLabels.firstOrNull() == "Fold")
        check("one notch UP from rest is harmless (§10.2)",
            shell.menuLabels.lastOrNull() == "Hand history")
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // row 0
        awaitTrue("every action stages a confirm (verdict 32)") {
            shell.menuIsOpen && shell.menuLabels.firstOrNull() == "Cancel"
        }
        check("the cursor rests on Cancel", shell.menuCursor == 0)
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        settle(shell, "games-confirm-cursor")
        shell.postGesture(EvenHubMsg.EV_CLICK)                          // confirm
        awaitTrue("the confirmed action reaches the engine") { !shell.menuIsOpen }
        settle(shell, "games-acted")
        // 🔴 double-tap NEVER cashes out (§10.1)
        val chipsBefore = gamesWin.myStack
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        awaitTrue("double-tap backs out to the Games list") { gamesWin.levelName == "GAMES" }
        check("backing out leaves the table running — it never cashes out",
            gamesWin.tableRunning && gamesWin.myStack == chipsBefore)
        gamesRow("Standings")
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("the standings open") { gamesWin.levelName == "STANDINGS" }
        shell.postGesture(EvenHubMsg.EV_CLICK)
        awaitTrue("a character's career opens") { gamesWin.levelName == "CHARACTER" }
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        settle(shell, "games-standings")
        toMain(shell)

    }

    /** Walk Main by COMMITTING and checking, never by counting rows: the row
     *  order is the registration order and one new window shifts every count
     *  (the 2026-09-03 menu-row lesson, applied to Main — adding Games broke
     *  two Reader checks that counted notches). */
    private suspend fun toMain(shell: Shell) {
        var guard = 0
        while (shell.currentWindowId() != null && guard++ < 10) {
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            settle(shell, "back-to-main")
        }
    }

    private suspend fun toWindow(shell: Shell, id: String) {
        toMain(shell)
        for (k in 0 until 12) {
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell, "commit-$id")
            if (shell.currentWindowId() == id) return
            toMain(shell)
            shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            settle(shell, "next-row")
        }
        failures.add("could not reach the '$id' window from Main")
    }

    private fun inkStill(shell: Shell, expected: Double): Boolean =
        kotlin.math.abs(Pack.inkFraction(shell.comp.composed) - expected) < 0.005

    /** Bounded quiescence wait — a hang is a loud failure here, not a silent one. */
    private suspend fun settle(shell: Shell, label: String, maxMs: Long = 15_000) {
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent()) {
            if (System.currentTimeMillis() - t0 > maxMs) {
                failures.add("settle('$label') did not reach quiescence in ${maxMs}ms")
                return
            }
            delay(20)
        }
        // every settled surface the script visits is checked against the
        // independent per-lens truth, not only against the shell's belief
        runOracle(label)
    }

    private suspend fun awaitTrue(what: String, maxMs: Long = 30_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - t0 > maxMs) {
                failures.add("await '$what' timed out after ${maxMs}ms")
                return
            }
            delay(25)
        }
        println("  PASS  $what")
    }
}
