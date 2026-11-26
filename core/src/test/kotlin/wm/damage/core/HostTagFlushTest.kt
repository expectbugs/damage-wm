package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.HostTag
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * R-b H2 (`private/build085/RB-lean-spec.md` §4.9): what the link cell's tag costs on the glasses' link, measured on the
 * simulator (bytes and flushes; a time on the glasses is modeled from them): a tag that appears is carried by the 5 s
 * idle tick's chrome flush (`DESIGN.md` §8.3: never a gesture's first flush), the same tag again costs nothing, and
 * all well again (the dim mark) is one more. The journal line of the flush that carried it is printed for the report.
 */
class HostTagFlushTest {

    @Test
    fun aTagRidesTheIdleTicksChromeFlushAndTheSameTagCostsNothing(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-host-tag")
        try {
            val journal = tmp.resolve("journal.jsonl")
            val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)),
                Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
            suspend fun settle(what: String) =
                waitUntil(10_000, 10, { "$what: did not settle — ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
            fun submits() = Files.readAllLines(journal).filter { "\"ev\":\"submit\"" in it }
            shell.start(); settle("start")
            shell.postIdleTick(); settle("an idle tick with nothing changed")
            val n0 = submits().size
            // an age in hours, so the test never crosses the minute its text changes at
            shell.hostTag = HostTag("PC", System.currentTimeMillis() - 3 * 3_600_000L - 30 * 60_000L)
            shell.postIdleTick(); settle("the tag")
            val carried = submits().drop(n0)
            for (l in carried) println("host tag flush: $l")
            assertEquals(1, carried.size, "one flush carries the tag: $carried")
            assertTrue("\"in\":0" in carried.single(), "an idle tick's flush, not a gesture's")
            val bytes = Regex("\"bytes\":(\\d+)").findAll(carried.single()).sumOf { it.groupValues[1].toInt() }
            println("host tag flush: $bytes B of pixels and records")
            assertTrue(bytes in 1 until 500, "a chrome cell's size ($bytes B)")
            val n1 = submits().size
            shell.postIdleTick(); settle("the same tag")
            assertEquals(n1, submits().size, "the same tag again: no flush")
            shell.hostTag = HostTag.NONE
            shell.postIdleTick(); settle("all well")
            assertEquals(n1 + 1, submits().size, "back to the dim mark: one flush")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }
}
