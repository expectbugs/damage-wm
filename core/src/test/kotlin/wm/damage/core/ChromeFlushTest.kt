package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/**
 * `DESIGN.md` §8.3 — chrome never justifies its own flush — made true for the
 * telemetry cells (`HANDOFF.md` §37.1 → §40). The throughput readout changes
 * after every ack, so any sync that ran for another reason used to paint it
 * and, when the content diff was empty, ship the digits alone: 149 of 320
 * flushes in the 2026-09-05 walk. Now a content-neutral repaint sends
 * nothing; the idle tick and a gesture's own flush carry the readout.
 */
class ChromeFlushTest {

    private class Rig(scope: CoroutineScope) {
        val tmp = Files.createTempDirectory("damage-chrome")
        val sim = GlassFirmwareSim()
        // MODELED link timing (not instant): the ack EMAs move on every
        // flush, which is what makes the readout change after every ack
        val transport = SimTransport(sim, scope, SimTransport.Timing(ackMs = 15, bytesPerSec = 200_000.0))
        /** The readout as painted at each submit, in order — taken ON the
         *  loop (submit is called there), so it is what that flush carried. */
        val thruAtSubmit = ArrayList<String?>()
        lateinit var shell: Shell
        private val spy = object : wm.damage.core.transport.Transport by transport {
            override suspend fun submit(flush: wm.damage.core.transport.FlushRequest): Long {
                synchronized(thruAtSubmit) { thruAtSubmit.add(shell.chromeThru) }
                return transport.submit(flush)
            }
        }
        init { shell = Shell(GlyphyText(), spy, Persistence(tmp.resolve("state.json")), null, scope) }
        fun thruAt(i: Int): String? = synchronized(thruAtSubmit) { thruAtSubmit[i] }
        /** Counted on the shell's loop, so a settle covers it (a collector on
         *  the transport's events can lag the loop and miss the last one). */
        val flushes: Long get() = shell.flushesSubmitted

        /** ONE evaluation per wait (§27.6). */
        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10_000) {
                if (shell.isQuiescent()) return
                delay(10)
            }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }
    }

    @Test
    fun aContentNeutralRepaintShipsNothingAndTheIdleTickCarriesTheReadout(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.shell.start(); rig.settle("start")
            // a bare shell's Main holds only Settings: enter it, where a scroll
            // moves a cursor through real rows (the persistence gate test's path)
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP); rig.settle("wrap to Settings")
            rig.shell.postGesture(EvenHubMsg.EV_CLICK); rig.settle("enter Settings")
            assertEquals("settings", rig.shell.currentWindowId())
            rig.shell.postGesture(EvenHubMsg.EV_CLICK); rig.settle("enter the first category")
            val atEntry = rig.flushes
            // gestures and their acks, so the readout has a value to change from
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("scroll")
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("scroll again")
            val before = rig.flushes
            assertTrue(before > atEntry, "the scrolls flushed (entry $atEntry, now $before)")
            val thruBefore = rig.shell.chromeThru
            assertTrue(!thruBefore.isNullOrEmpty(), "the readout has been painted: '$thruBefore'")

            // content-neutral repaints: the same content composed again. The
            // readout HAS changed since it was painted (the acks above moved
            // the EMAs), so the old shell shipped its digits alone here.
            repeat(4) { rig.shell.requestRepaint(); rig.settle("repaint $it") }
            assertEquals(before, rig.flushes, "a content-neutral repaint sends nothing")
            assertEquals(thruBefore, rig.shell.chromeThru, "the readout waits for a flush it may ride")

            // the idle tick is where chrome-only changes flush (§8.3)
            rig.shell.postIdleTick(); rig.settle("idle tick")
            assertEquals(before + 1, rig.flushes, "the idle tick carried the readout alone, once")
            val thruIdle = rig.shell.chromeThru
            assertNotEquals(thruBefore, thruIdle, "the readout moved on the idle tick")

            // §41: a gesture's FIRST flush never carries the readout — that
            // flush is the one whose bytes decide what the gesture feels like
            // (40–230 B of a 184–470 B first flush measured on 2026-09-06) —
            // a later frame of the same gesture does
            val first = rig.flushes.toInt()
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("scroll")
            assertTrue(rig.flushes > first + 1, "the notch produced more than one flush (${rig.flushes - first})")
            assertEquals(thruIdle, rig.thruAt(first), "the gesture's first flush left the readout as it was")
            assertNotEquals(thruIdle, rig.shell.chromeThru, "a later flush of the gesture carried the readout")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }
}

/** A fake rasterizer whose glyphs DIFFER: the box height follows the
 *  character, so "12K/s" and "14K/s" are different pixels. `FakeText` draws
 *  every character as the same box, which hides a changed readout from the
 *  compositor's diff — exactly the change this test is about. */
class GlyphyText(private val advance: Int = 8) : wm.damage.core.text.TextRasterizer {
    override fun measure(text: String, font: wm.damage.core.text.FontSpec): Int = text.length * advance
    override fun metrics(font: wm.damage.core.text.FontSpec) = wm.damage.core.text.FontMetrics(12, 4, 16)
    override fun draw(surface: wm.damage.core.gfx.Gray8, x: Int, y: Int, text: String, font: wm.damage.core.text.FontSpec, level: Int) {
        for ((i, ch) in text.withIndex()) {
            if (ch != ' ') surface.fillRect(x + i * advance, y + 2, advance - 2, 3 + ch.code % 9, level)
        }
    }
    override fun covers(text: String, font: wm.damage.core.text.FontSpec) = true
}
