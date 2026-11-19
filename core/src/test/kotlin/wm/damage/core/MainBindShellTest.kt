package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.gfx.IconKind
import wm.damage.core.local.BoundList
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.StagedRecord
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.bind
import wm.damage.core.wire.CfwModes

/**
 * Build C2, stage DM2: the shell's half of Main's bound list — staged when Main is in front at stage 4, offered only
 * once the count (field 17) has proven every record, a row whose picture changes re-staged in place with its mark kept
 * and its bytes journaled, the `mainseq` probe refused while the list is bound. Context for the reader: Adam's own
 * glasses on his own firmware fork; nothing here contacts a device — the glasses are the firmware model behind
 * [SimTransport] on a bit-10 build (DamageCaps 0x5ff) at the ladder's stage 4.
 *
 * The harness: every wait decides on ONE evaluation ([waitUntil]); the journal is read from the file the shell writes
 * synchronously; the shell's state is read ON its loop ([Shell.sampleIdle]).
 */
class MainBindShellTest {

    private class Row(id: String, name: String, var s: DamageWindow.Summary) : DamageWindow(id, name, IconKind.FILES) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(stage: Int = 4) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-mainbind")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x5ff }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val journal = tmp.resolve("journal.jsonl")
        val shell = Shell(CachedText(FakeText()), t, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rows = (0 until 7).map { Row("w$it", "Win $it", DamageWindow.Summary("summary $it", detail = "detail of $it", progress = if (it == 3) 0.25 else null)) }

        init {
            for (r in rows) shell.register(r)
            if (stage != 0) t.devProbe("c1", "$stage")
        }

        fun notes(kind: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }

        suspend fun until(what: String, maxMs: Long = 30_000, cond: () -> Boolean) =
            waitUntil(maxMs, 5, { "$what — mainstage ${notes("mainstage")}; stage ${notes("stage").takeLast(4)}; ${shell.quiescenceReport()}" }, cond)

        suspend fun settle(what: String) = until("$what: settled") { shell.isQuiescent() }

        suspend fun up() {
            shell.start(); settle("start")
            shell.updateSettings { it.copy(heightMode = 352, cachedText = "on") }
            until("the atlas goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            settle("cached text on")
        }

        /** On the loop: (what Main offers, whether every base record is PROVEN, Main's records as (name, off, state)). */
        fun offered(): Triple<BoundList?, Boolean, List<Triple<String, Int, StagedRecord.State>>>? = onLoop {
            val b = shell.mainBinding
            Triple(shell.mainBoundForTest().first, b.counted, b.records.map { Triple(it.key.toString(), it.off, it.state) })
        }

        /** [block] run ON the shell's loop, its answer returned (one evaluation). */
        fun <T> onLoop(block: () -> T): T {
            val out = java.util.concurrent.CompletableFuture<T>()
            shell.services.runOnShell { out.complete(block()) }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        fun close() { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /**
     * At stage 4 on a bit-10 build, with Main in front: Main's records are written into the region the session gives them
     * and the list is offered only once every one is counted — never before (each sample taken on the loop either offers
     * nothing or finds every record PROVEN). The list is the whole list's: ids 4 and 5 the two pans, the tap the press,
     * slot 0, list 0, the staging's period.
     */
    @Test
    fun mainIsStagedAtStageFourAndOfferedOnlyOnceCounted_DM2(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            assertTrue(r.t.state.value.bind, "BIND in force at stage 4: flags 0x${r.t.state.value.flagsInForce.toString(16)}")
            var seenUncounted = false
            var offeredUncounted = false
            r.until("Main's list offered") {
                val o = r.offered() ?: return@until false
                if (!o.second && o.third.isNotEmpty()) seenUncounted = true
                if (o.first != null && !o.second) offeredUncounted = true
                o.first != null
            }
            assertTrue(!offeredUncounted, "never offered before every record was counted")
            assertTrue(seenUncounted, "the shape guard: the records were seen before they were counted")
            val o = assertNotNull(r.offered())
            val list = assertNotNull(o.first)
            val cache = r.t.state.value.cacheSize
            val region = CfwModes.mainRegion(cache, true)
            assertTrue(o.third.all { (_, off, st) -> off in region && st == StagedRecord.State.PROVEN }, "every record in ${region.first}..${region.last}: ${o.third}")
            assertEquals(listOf(4, 5, 0), list.entries.map { it.eventId })
            assertEquals(listOf(1, -1, 0), list.entries.map { it.step })
            assertEquals(0 to 0, list.list to list.slot)
            assertEquals(8, list.count)
            val notes = r.notes("mainstage")
            println("DM2-shell cache ${cache / 1024} KiB: ${notes.joinToString("\n")}")
            assertTrue(notes.indexOfFirst { it.startsWith("staged") } in 0 until notes.indexOfFirst { it.startsWith("offered") }, "staged, then offered: $notes")
            // the glasses hold the item table where the list says: one off4 per row, each the band form's slot
            val items = (0 until 8).map { i -> r.sim.left.textureCache!!.let { c -> (c[list.items4 * 4 + 2 * i].toInt() and 0xFF) or ((c[list.items4 * 4 + 2 * i + 1].toInt() and 0xFF) shl 8) } }
            assertEquals(list.items!!.toList(), items, "the item table on the glasses")
        } finally { r.close() }
    }

    /**
     * A row whose summary changes while Main is in front is painted again and re-staged IN PLACE: one write of its
     * segment(s) and band form, the list (its object, its mark) kept, the bytes journaled; the row is stale from the
     * change until the write is submitted. A second change of the same row inside [wm.damage.core.shell.MainBinding.RESTAGE_PACE_MS]
     * waits for the pace.
     */
    @Test
    fun aRowWhosePictureChangesIsRestagedInPlaceWithTheMarkKept_DM2(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.until("offered") { r.offered()?.first != null }
            r.settle("offered")
            val list = assertNotNull(r.offered()?.first)
            r.rows[3].s = r.rows[3].s.copy(line = "summary 3 grew", progress = 0.75)
            r.shell.services.requestRender(r.rows[3])
            r.until("re-staged in place") { r.notes("mainstage").any { it.startsWith("row 3 re-staged in place") } }
            r.settle("re-staged")
            val after = assertNotNull(r.offered())
            assertSame(list, after.first, "the same list, its mark kept")
            val note = r.notes("mainstage").first { it.startsWith("row 3 re-staged in place") }
            println("DM2-shell restage: $note")
            assertTrue(Regex("— (\\d+) B in (\\d) write").find(note) != null, note)
            assertTrue(r.onLoop { r.shell.mainBinding.staleRows }.isEmpty(), "nothing stale once written")
            // a second change at once waits for the row's pace
            val t0 = System.currentTimeMillis()
            r.rows[3].s = r.rows[3].s.copy(line = "summary 3 again", progress = 0.5)
            r.shell.services.requestRender(r.rows[3])
            r.until("stale until its pace") { r.onLoop { r.shell.mainBinding.stale(3) } }
            r.until("re-staged again", 10_000) { r.notes("mainstage").count { it.startsWith("row 3 re-staged in place") } == 2 }
            val waited = System.currentTimeMillis() - t0
            assertTrue(waited >= 1_000, "the second re-stage waited for the row's pace ($waited ms)")
            assertSame(list, r.offered()?.first, "still the same list")
            assertEquals(1, r.notes("mainstage").count { it.startsWith("staged") }, "no full staging: ${r.notes("mainstage")}")
        } finally { r.close() }
    }

    /** Main leaving the front and coming back finds its records counted (at 160 KiB nothing took the shared reserve
     *  meanwhile — no document staged): offered again, nothing written again. */
    @Test
    fun mainBackInFrontIsOfferedAgainWithoutWritingItsRecords_DM2(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.until("offered") { r.offered()?.first != null }
            r.settle("offered")
            val staged = r.notes("mainstage").count { it.startsWith("staged") }
            r.shell.services.runOnShell { r.shell.services.openWindow("w1", null) }
            r.settle("in a window")
            r.shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_DOUBLE_CLICK)        // a window at its top level: back to Main
            r.settle("back on Main")
            r.until("offered again") { r.offered()?.first != null }
            assertEquals(staged, r.notes("mainstage").count { it.startsWith("staged") }, "nothing staged again: ${r.notes("mainstage")}")
        } finally { r.close() }
    }

    /** While BIND is in force the scroll test is refused (P9: no PLAY into a bound slot) and says why; Main's records
     *  stay the binding's. */
    @Test
    fun theScrollTestIsRefusedWhileMainsListIsBound_DM2(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.until("offered") { r.offered()?.first != null }
            r.shell.devProbe("mainseq", "2")
            r.until("refused") { r.notes("mainseq").any { it.startsWith("mainseq=2 not taken — BIND is in force") } }
            r.settle("after")
            assertNotNull(r.offered()?.first, "still offered")
        } finally { r.close() }
    }

    /** Below stage 4 nothing of Main's is staged by the binding and nothing is offered (ladder stages 0–3 keep today's
     *  bytes: the start pins are DP1's; this is the shell's half). */
    @Test
    fun belowStageFourNothingIsStagedOrOffered_DM2(): Unit = runBlocking {
        val r = Rig(stage = 3)
        try {
            r.up()
            r.settle("up at stage 3")
            kotlinx.coroutines.delay(500)
            r.settle("still")
            assertTrue(r.notes("mainstage").isEmpty(), "${r.notes("mainstage")}")
            assertNull(r.offered()?.first)
        } finally { r.close() }
    }
}
