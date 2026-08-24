package wm.damage.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.content.LocalContent
import wm.damage.core.gfx.Pack
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
    private var faults = 0

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

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val text = AwtText()
        val persistence = Persistence(tmp.resolve("state.json"))
        val shell = Shell(text, transport, persistence, tmp.resolve("journal.jsonl"), scope)
        val reader = ReaderWindow(text, LocalContent(books), scope)
        shell.register(reader)

        var flushFails = 0
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            transport.events.collect {
                when (it) {
                    is TransportEvent.FlushDone -> if (!it.ok) flushFails++
                    is TransportEvent.Fault -> if (it.what !in setOf("lease")) faults++
                    else -> {}
                }
            }
        }

        shell.start()
        settle(shell, "boot")
        check("shell boots and reaches quiescence", shell.isQuiescent())
        check("capability gate passed (started)", transport.state.value.started)
        check("FB lease held after start", transport.state.value.leaseHeld)
        check("keyframe delivered (left shadow seeded)", sim.left.seeded)
        check("panel is not blank after boot", sim.left.panel.any { it.toInt() != 0 })

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

        // ---- switcher: open, spin, commit to Main
        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)
        settle(shell, "switcher-open")
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
        settle(shell, "switcher-spin")
        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)   // cancel restores
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
        shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)    // dismiss WITHOUT reading
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

        // ---- persistence round trip: leave a BOOK open, restart, land back in it
        shell.postGesture(EvenHubMsg.EV_CLICK)              // back into reader (library)
        awaitTrue("reader reopens") { shell.isQuiescent() }
        shell.postGesture(EvenHubMsg.EV_CLICK)              // open the book again
        awaitTrue("book reopens before shutdown") { reader.levelDepth() >= 2 }
        settle(shell, "pre-shutdown")
        awaitTrue("reopening a book mid-session restores its position (§9.1)") {
            (reader.summary().progress ?: 0.0) > 0.0
        }
        val posBefore = reader.saveState()["offsets"].toString()
        shell.stop()

        val persistence2 = Persistence(tmp.resolve("state.json"))
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sim2 = GlassFirmwareSim()
        val transport2 = SimTransport(sim2, scope2, SimTransport.Timing(instant = true))
        val shell2 = Shell(text, transport2, persistence2, null, scope2)
        val reader2 = ReaderWindow(text, LocalContent(books), scope2)
        shell2.register(reader2)
        shell2.start()
        settle(shell2, "restart")
        awaitTrue("restored reader reopens its book (mode, not just position §9.1)") {
            reader2.levelDepth() >= 2
        }
        val posAfter = reader2.saveState()["offsets"].toString()
        check("reading position survives restart ($posBefore -> $posAfter)", posBefore == posAfter)
        shell2.stop()
        scope2.cancel()

        check("no failed flushes anywhere", flushFails == 0)
        check("no transport faults (decode/fid/session)", faults == 0)
        val flags = sim.flags(GlassFirmwareSim.Arm.LEFT).filterValues { it }
        check("no sticky diagnostic flags (were $flags)", flags.isEmpty())
        scope.cancel()
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
