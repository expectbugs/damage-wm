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
 * and its bytes journaled. Context for the reader: Adam's own
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

        /** Every note in the journal's own order, (kind, detail). */
        fun allNotes(): List<Pair<String, String>> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\"" in it }.map { it.substringAfter("\"kind\":\"").substringBefore("\"") to it.substringAfter("\"detail\":\"").substringBefore("\"") }

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
            assertTrue(!offeredUncounted, "never offered before every record was counted (a sample on the loop)")
            // the journal, written in order: Main's twelve records (B082: the wrap's own among them) submitted, a reading of the count proving them, then the
            // offer — never the offer first (the sample above can miss a short window; the journal cannot)
            val all = r.allNotes()
            val offeredAt = all.indexOfFirst { it.first == "mainstage" && it.second.startsWith("offered") }
            val submits = all.withIndex().filter { (_, n) -> n.first == "stage" && n.second.startsWith("Main's") && "submitted as flush" in n.second }.map { it.index }
            assertEquals(12, submits.count { it < offeredAt }, "Main's twelve records submitted before the offer: ${all.map { it.second.take(60) }}")
            assertTrue(all.withIndex().any { (i, n) -> i in (submits.max() + 1) until offeredAt && n.first == "stage" && "proved" in n.second },
                "a reading proved them between the last write and the offer: ${all.filter { it.first == "stage" || it.first == "mainstage" }.map { it.second.take(70) }}")
            if (!seenUncounted) println("DM2-shell: no sample caught the records uncounted (the journal's order is the check)")
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
     * Decisions X: a row whose band form changes (its detail) is re-staged into a free slot and switched by one write of
     * the item table — a new list with the same mark, the old one its `previous` until that flush is acknowledged; a row
     * whose row form changes (its line) withdraws the list, writes its strip segment and offers the list again; a row's
     * second re-stage waits for [wm.damage.core.shell.MainBinding.RESTAGE_PACE_MS]. No full staging.
     */
    @Test
    fun aRowWhosePictureChangesIsRestagedWithTheMarkKept_DM2(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.until("offered") { r.offered()?.first != null }
            r.settle("offered")
            val list = assertNotNull(r.offered()?.first)
            r.rows[3].s = r.rows[3].s.copy(detail = "detail of 3, a longer one")
            r.shell.services.requestRender(r.rows[3])
            // by the row's key at the compose, not the round-robin repaint (row 3's turn is 20 s after the staging)
            r.until("re-staged", 3_000) { r.notes("mainstage").any { it.startsWith("row 3 re-staged") } }
            r.settle("re-staged")
            val t1 = System.currentTimeMillis()
            val l2 = assertNotNull(r.offered()?.first)
            val note = r.notes("mainstage").first { it.startsWith("row 3 re-staged") }
            println("DM2-shell restage: $note")
            assertTrue("its band form moved" in note, note)
            assertTrue(l2 !== list && l2.mark == list.mark, "a new list, the same mark")
            assertTrue(l2.items!![3] != list.items!![3], "row 3's item names another slot")
            assertNull(r.onLoop { l2.previous }, "the switch acknowledged: no previous kept")
            // the row form: withdrawn, written, offered again — after the row's pace
            r.rows[3].s = r.rows[3].s.copy(line = "summary 33")
            r.shell.services.requestRender(r.rows[3])
            r.until("withdrawn", 10_000) { r.notes("mainstage").any { it.startsWith("withdrawn — row 3") } }
            val waited = System.currentTimeMillis() - t1
            r.until("re-staged again", 10_000) { r.notes("mainstage").count { it.startsWith("row 3 re-staged") } == 2 }
            r.until("offered again") { r.offered()?.first != null }
            println("DM2-shell restage 2: ${r.notes("mainstage").last { it.startsWith("row 3 re-staged") }}")
            // the ruled pace said here on its own (decisions X.3: 5 s a row), so a changed constant is seen
            assertTrue(waited >= 4_500, "the second re-stage waited for the row's 5 s pace ($waited ms)")
            assertEquals(list.mark, r.offered()?.first?.mark, "the mark kept")
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

    /** A change of Global `Depth` moves Main's planes: the staging goes (the list withdrawn first) and Main is staged
     *  again with a new mark; so does a row grown past its slot's slack. */
    @Test
    fun aDepthChangeOrAnOutgrownRowIsAFullStagingWithANewMark_DM2(): Unit = runBlocking {
        val r = Rig()
        try {
            r.up()
            r.until("offered") { r.offered()?.first != null }
            r.settle("offered")
            val first = assertNotNull(r.offered()?.first)
            r.shell.updateSettings { it.copy(depth = 12) }
            r.until("dropped for the planes") { r.notes("mainstage").any { it.startsWith("dropped — Main's planes changed") } }
            r.until("offered again") { r.offered()?.first.let { it != null && it !== first } }
            val second = assertNotNull(r.offered()?.first)
            assertTrue(second.mark != first.mark, "a new mark: ${first.mark} → ${second.mark}")
            r.settle("staged at depth 12")
            r.rows[2].s = DamageWindow.Summary("a summary much longer than the one staged, running well past its slot", detail = "and a detail of the same kind, long enough to need a new slot", progress = 0.5, more = true)
            r.shell.services.requestRender(r.rows[2])
            r.until("dropped for the outgrown row") { r.notes("mainstage").any { it.startsWith("dropped — a full staging is owed") } }
            r.until("offered a third time") { r.offered()?.first.let { it != null && it !== second } }
            assertTrue(r.offered()!!.first!!.mark !in setOf(first.mark, second.mark), "a third mark")
            println("DM2-shell full stagings: ${r.notes("mainstage").filter { it.startsWith("dropped") || it.startsWith("staged") }.joinToString("\n")}")
        } finally { r.close() }
    }
}
