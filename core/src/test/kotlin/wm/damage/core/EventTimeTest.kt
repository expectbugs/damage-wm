package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import wm.damage.core.comp.Journal
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.SettingsMsg

/**
 * Build W, package G (the lead's decision H, row E2; G-design §2). Context for the reader: the phone's journal of
 * Adam's own glasses. A transport event carried no time, and the journal stamped each line when it was written —
 * during a start the events wait for the loop or for a failure's drain, so on 2026-11-12 nine failed starts' notes
 * were stamped about 500 s after they happened. Events now carry the time they were built; the line's `t` is that
 * time, and `late` says by how much the line came after it.
 */
class EventTimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp = Files.createTempDirectory("damage-event-time")
    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private fun rec(line: String) = Json.parseToJsonElement(line).jsonObject
    private fun t(line: String) = rec(line)["t"]!!.jsonPrimitive.long
    private fun late(line: String) = rec(line)["late"]?.jsonPrimitive?.longOrNull

    @Test
    fun aLineCarriesItsEventsTimeAndSaysWhenItIsLate() {
        val path = tmp.resolve("j.jsonl")
        val j = Journal(path)
        j.note("x", "an event at 1,000 ms", atMs = 1_000)
        val now = System.currentTimeMillis()
        j.note("y", "an event now", atMs = now)
        j.note("z", "no event time")
        j.present(1, 2, 3, 4, atMs = 2_000)
        j.close()
        val lines = Files.readAllLines(path)
        assertEquals(1_000, t(lines[0]), lines[0])
        assertTrue((late(lines[0]) ?: 0) >= Journal.LATE_MS, "a line written long after its event says late: ${lines[0]}")
        assertEquals(now, t(lines[1]), lines[1])
        assertEquals(null, late(lines[1]), "on time: no late: ${lines[1]}")
        assertEquals(null, late(lines[2]), "no event time: stamped now, no late: ${lines[2]}")
        assertEquals(2_000, t(lines[3]), lines[3])
        assertTrue((late(lines[3]) ?: 0) >= Journal.LATE_MS, lines[3])
    }

    /**
     * A start that fails 1.5 s after its READ: the READ's notes (the battery reading among them) are journaled by the
     * failure's drain, after 1.5 s — with their own time, a second or more before the keeper's "start failed" line,
     * and `late`. Watched on the unfixed tree: their `t` was the drain's, within a few ms of the failure.
     */
    @Test
    fun aFailedStartsNotesCarryTheTimeTheyHappened(): Unit = runBlocking {
        val glass = GlassFirmwareSim().also { it.damageContract = 2 }
        val t = StartRig(glass, scope)
        val broken = java.util.concurrent.atomic.AtomicBoolean(false)
        // the reset check's first answer is lost; its re-ask to RIGHT then takes 1.5 s and fails (StartGateTest's shape)
        t.onNotify = { _, p ->
            val r = StartRig.telemetry(p)
            if (r != null && r.requestId != 0L && broken.compareAndSet(false, true)) null else p
        }
        t.onWrite = { arm, p ->
            if (broken.get() && arm == Arm.RIGHT && StartRig.sid(p) == SettingsMsg.SID) {
                Thread.sleep(1_500)
                throw java.io.IOException("RIGHT: write characteristic gone")
            }
            p
        }
        val journal = tmp.resolve("journal.jsonl")
        val shell = Shell(FakeText(), t, Persistence(tmp.resolve("state.json")), journal, scope)
        val keeper = ShellKeeper(shell, t, scope, retryPauseMs = 60_000)
        keeper.start()
        fun lines() = if (Files.exists(journal)) Files.readAllLines(journal) else emptyList()
        waitUntil(15_000, 10, { "the start fails: ${lines().takeLast(8)}" }) { lines().any { "start failed" in it } }
        keeper.stop()
        val all = lines()
        val failed = all.first { "\"kind\":\"keeper\"" in it && "start failed" in it }
        val battery = all.first { "\"kind\":\"battery\"" in it }
        assertTrue(t(failed) - t(battery) >= 1_000, "the READ's battery note carries its own time, 1.5 s before the failure: " +
            "battery ${t(battery)}, failure ${t(failed)}\n$battery\n$failed")
        assertTrue((late(battery) ?: 0) >= Journal.LATE_MS, "and says it was written late: $battery")
    }
}
