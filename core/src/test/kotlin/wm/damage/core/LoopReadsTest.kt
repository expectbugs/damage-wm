package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import wm.damage.core.shell.LoopClock
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/**
 * Build W, package G (the lead's decision H, rows E3 and E4; G-design §3, §4): two journal-only reads on the shell's
 * loop. E3: a ring event that met a full window waited in the shell before its flush was submitted, and the report's
 * WAIT (submit → done) never held that wait — the gesture's flush now carries `inWaitMs`. E4: a loop message that held
 * the loop 250 ms or more gets one `slow` note with what it was, the window and mode, the thread's CPU time against the
 * wall and the collector's time. Context for the reader: Adam's own phone and glasses; nothing here is sent anywhere.
 */
class LoopReadsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp = Files.createTempDirectory("damage-loop-reads")
    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private fun lines(p: java.nio.file.Path) = if (Files.exists(p)) Files.readAllLines(p) else emptyList()

    /** The JVM's own clocks, as the desktop host's `JvmLoopClock` reads them. */
    private object TestClock : LoopClock {
        private val threads = java.lang.management.ManagementFactory.getThreadMXBean()
        override fun threadCpuNs(): Long = threads.currentThreadCpuTime
        override fun gcMs(): Long = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }
    }

    /** E4: a `Run` that sleeps 300 ms on the loop is one `slow` note: its tag, the window and mode, and a thread CPU
     *  time far under the wall (it waited). A shell with no clocks writes -1 and says so once. */
    @Test
    fun aSlowHandlerIsOneNoteWithItsTagAndItsCpuAgainstTheWall(): Unit = runBlocking {
        for (withClock in listOf(true, false)) {
            val journal = tmp.resolve("slow-$withClock.jsonl")
            val t = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), t, Persistence(tmp.resolve("state-$withClock.json")), journal, scope)
            if (withClock) shell.loopClock = TestClock
            shell.start()
            waitUntil(15_000, 10, { "settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
            shell.services.runOnShell("test.sleep") { Thread.sleep(300) }
            shell.services.runOnShell("test.quick") { }
            waitUntil(10_000, 10, { "the slow note: ${lines(journal).filter { "slow" in it }}" }) {
                lines(journal).any { "\"kind\":\"slow\"" in it && "Run test.sleep" in it }
            }
            delay(200)
            val slow = lines(journal).filter { "\"kind\":\"slow\"" in it }
            val note = slow.first { "Run test.sleep" in it }
            assertTrue(slow.none { "test.quick" in it }, "a quick handler has no note: $slow")
            assertTrue("window - → -" in note && "mode MAIN → MAIN" in note, "the window and the mode: $note")
            val cpu = Regex("thread CPU (-?\\d+) ms of (\\d+) wall").find(note)!!.groupValues
            if (withClock) {
                assertTrue(cpu[1].toLong() in 0..100 && cpu[2].toLong() >= 300, "a sleep waits: CPU far under the wall: $note")
                assertTrue(slow.none { "do not answer" in it }, "the clocks answered: $slow")
            } else {
                assertTrue(cpu[1] == "-1" && "collector -1 ms" in note, "no clock: -1: $note")
                assertTrue(slow.count { "this host's clocks do not answer (none set)" in it } == 1, "said once: $slow")
            }
            shell.stop()
        }
    }

    /**
     * E3: with every image ack held (the window full), a later gesture's pump finds no room; the acks are released 800 ms
     * on, and that gesture's flush is submitted with `inWaitMs` of 800 or more, while the first gesture's flush, which
     * went at once, says 0. Watched: with the loop-entry time not kept, the waiting gesture's flush said 0.
     */
    @Test
    fun aGestureThatWaitedForASlotSaysHowLong(): Unit = runBlocking {
        val glass = GlassFirmwareSim()
        val t = StartRig(glass, scope, instantPacing = false)       // the product's 10 s stall bound: nothing nudges the held acks
        val held = java.util.concurrent.CopyOnWriteArrayList<Pair<Arm, ByteArray>>()
        val holding = java.util.concurrent.atomic.AtomicBoolean(false)
        t.onNotify = { arm, p ->
            val ack = p.size > 7 && (p[6].toInt() and 0xFF) == EvenHubMsg.SID && (p[7].toInt() and 0xFF) == EvenHubMsg.FLAG_ACK
            if (holding.get() && ack) { held.add(arm to p); null } else p
        }
        val journal = tmp.resolve("inwait.jsonl")
        val shell = Shell(FakeText(), t, Persistence(tmp.resolve("state-inwait.json")), journal, scope)
        shell.register(SilentGlassesRows(FakeText()))
        shell.start()
        waitUntil(15_000, 10, { "settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        holding.set(true)
        val submitsBefore = lines(journal).count { "\"ev\":\"submit\"" in it }
        for (i in 0 until 4) {
            glass.injectGesture(if (i % 2 == 0) EvenHubMsg.EV_SCROLL_BOTTOM else EvenHubMsg.EV_SCROLL_TOP)
            delay(150)
        }
        waitUntil(5_000, 10, { "three gestures in flight: ${t.state.value.inFlight}" }) { t.state.value.inFlight == 3 }
        delay(800)
        holding.set(false)
        for ((arm, p) in held) t.deliver(arm, p)
        held.clear()
        waitUntil(10_000, 10, { "the fourth gesture's flush: ${lines(journal).filter { "\"ev\":\"submit\"" in it }.takeLast(3)}" }) {
            lines(journal).filter { "\"ev\":\"submit\"" in it }.drop(submitsBefore).any { "\"in\":1" in it && waited(it) >= 800 }
        }
        val gestures = lines(journal).filter { "\"ev\":\"submit\"" in it }.drop(submitsBefore).filter { "\"in\":1" in it }
        // the gestures that came while the window was full are one flush (the pump coalesces): the first went at once
        assertTrue(waited(gestures.first()) == 0L, "the gesture that went at once says 0: ${gestures.map(::waited)}")
        shell.stop()
    }

    private fun waited(line: String) = Json.parseToJsonElement(line).jsonObject["inWaitMs"]!!.jsonPrimitive.long

    /** A list window, so a scroll at Main moves a row and paints (as `SilentGlassesTest`'s). */
    private class SilentGlassesRows(private val tx: wm.damage.core.text.TextRasterizer) :
        wm.damage.core.shell.DamageWindow("rows", "Rows", wm.damage.core.gfx.IconKind.FILES) {
        private val model = wm.damage.core.shell.ListModel()
        private val font = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 20)
        override fun view(): wm.damage.core.shell.WindowView = wm.damage.core.shell.WindowView.ListView(model, { 40 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 4, "Row $i text", font, wm.damage.core.gfx.Level.BODY) },
            paintLens = { g, r, i -> tx.draw(g, r.x + 44, r.y + 6, "Row $i", font, wm.damage.core.gfx.Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("40 rows")
        override fun saveState(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {}
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
    }
}
