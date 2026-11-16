package wm.damage.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import org.junit.After
import wm.damage.core.app.AppHost
import wm.damage.core.app.Item
import wm.damage.core.app.RecordAlerts
import wm.damage.core.app.RecordStore
import wm.damage.core.app.StreamKey

/**
 * Build S1-1, package P1 (`CONTRACT.md` §9.7 item 1, §13 (b) "PC record and seq"; dec. A.12): `record.json` is saved
 * on the tick from a snapshot the executor takes in one step, so it never covers a line whose effect it lacks, and a
 * save that raced newer lines is followed by another; a failed save is said and retried on the next tick while the log
 * keeps what it does not cover; an unreadable file is kept as `.bad` and the log is replayed whole.
 */
class RecordStoreTest {
    private val rig = RecordRig()

    @After
    fun tearDown() = rig.cleanUp()

    private fun saved() = RecordStore.decode(String(Files.readAllBytes(rig.recordFile)))

    /** The pin of the covered `seq` (mutation S1-covered-seq-first): lines 4 and 5 land while the save of 1–3 waits
     *  on the writer. That save says it covers 3 and holds 1–3 exactly; the host knows it covers 3, not 5, so the
     *  next tick saves again and covers 5. A host that took the covered `seq` from its live counter would believe
     *  the disk covers 5 and never save 4 and 5 until another op came. */
    @Test
    fun aSaveNeverCoversALineItLacks() {
        val held = HeldWriter()
        rig.storeWriter = held
        val h = rig.host()
        h.open()
        held.runAll()                                            // nothing: the start saves on the executor
        for (n in 1L..3L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        h.tick()
        h.executor.call {}
        assertEquals(1, held.waiting)
        for (n in 4L..5L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        held.runAll()
        h.executor.call {}
        val first = saved()
        assertEquals(3L, first.covered)
        assertEquals((1..3).map { "rec.t.k$it" }.toSet(), first.items.keys, "exactly the effects it covers")
        assertEquals(mapOf(StreamKey("phone", "s1") to 3L), first.streams)
        h.tick()
        h.executor.call {}
        assertEquals(1, held.waiting, "the raced save is followed by another")
        held.runAll()
        h.executor.call {}
        val second = saved()
        assertEquals(5L, second.covered)
        assertEquals(j("v5"), second.items["rec.t.k5"]!!.value)
        h.tick()
        h.executor.call {}
        assertEquals(0, held.waiting, "nothing new: no save")
    }

    @Test
    fun aFailedSaveIsSaidAndRetriedOnTheNextTick() {
        var fail = true
        rig.storeWrite = { f: Path, t: String ->
            if (fail) throw IOException("no space left on device (test)")
            wm.damage.core.shell.Persistence.writeAtomic(f, t, sync = true)
        }
        val h = rig.host()
        h.open()                                                 // the start's save fails too: said
        assertTrue(rig.said.any { it.startsWith("raise ${RecordAlerts.SAVE}") })
        for (n in 1L..2L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        rig.tick(h)
        rig.tick(h)
        assertEquals(1, rig.said.count { it.startsWith("raise ${RecordAlerts.SAVE}") }, "once per run of failures")
        assertNull(if (Files.exists(rig.recordFile)) saved() else null)
        // a stop now: the log holds everything the record never saved, and a start whose own save fails never sets
        // it aside, however large
        rig.stop(h)
        rig.setAsideBytes = 1
        val h2 = rig.host()
        val o = h2.open()
        assertEquals(2, o.replayed)
        assertNull(o.setAside)
        assertEquals(2, Files.readAllLines(rig.logFile).size)
        assertEquals(j("v2"), rig.item(h2, "k2")!!.value)
        // the disk takes it again: the next tick saves, the run's end is said
        fail = false
        rig.ask(h2, op("phone", "s1", 3, "set", listOf("k3"), j("v3")))
        rig.tick(h2)
        assertEquals(3L, saved().covered)
        assertEquals("clear ${RecordAlerts.SAVE}", rig.said.last { RecordAlerts.SAVE in it })
        assertTrue(rig.notes().any { "record.json works again" in it })
    }

    @Test
    fun aStartWhoseLogWouldNotOpen_savesNothing_refusesWithRetry_andTheNextGoodStartReplaysItWhole() {
        // fix round 1, ruling 1.7: three ops logged, no save since the start (record.json covers seq 0)
        val h = rig.opened()
        for (n in 1L..3L) assertEquals(wm.damage.core.app.Answer.Seen(), rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n"))))
        rig.stop(h)
        val perms = Files.getPosixFilePermissions(rig.logFile)
        Files.setPosixFilePermissions(rig.logFile, emptySet())       // the next start cannot open the log
        val bad = rig.host()
        val o = bad.open()
        assertTrue(rig.said.any { it.startsWith("raise ${RecordAlerts.LOG}") }, "the record.log alert: ${rig.said}")
        // fix round 2, ruling 1.8: nothing opens the log again in this process, and the alert says so
        assertTrue(rig.said.any { it.startsWith("raise ${RecordAlerts.LOG}") && "until the service is restarted" in it }, "${rig.said}")
        // build S1-2 Q1 (N3's class): a page keeps no queue, so the alert does not say every device keeps its changes
        assertTrue(rig.said.any { it.startsWith("raise ${RecordAlerts.LOG}") && "a page says so and keeps none" in it }, "${rig.said}")
        assertTrue(o.problems.any { "not saved while the log is closed" in it }, "${o.problems}")
        val refused = rig.ask(bad, op("phone", "s1", 4, "set", listOf("k4"), j("v4")))
        assertEquals(wm.damage.core.app.Answer.RETRY, (refused as wm.damage.core.app.Answer.Refused).code)
        rig.tick(bad)                                                // the pacing tick saves nothing either
        rig.stop(bad)
        Files.setPosixFilePermissions(rig.logFile, perms)
        val good = rig.opened()
        for (n in 1L..3L) assertEquals(j("v$n"), rig.item(good, "k$n")?.value, "op $n replayed")
        assertEquals(3L, good.highWater(StreamKey("phone", "s1")))
        assertEquals(wm.damage.core.app.Answer.Seen(), rig.ask(good, op("phone", "s1", 4, "set", listOf("k4"), j("v4"))))
    }

    @Test
    fun aStartWhoseLogOpenedButWouldNotReplay_writesNothingIntoIt_andRefusesWithRetry() {
        // fix round 1, ruling 1.7: the log opens but cannot be read (past 2 GiB: a sparse file, no space used); an op
        // must not be written into a log whose lines this start never replayed
        rig.opened().also { rig.stop(it) }
        java.io.RandomAccessFile(rig.logFile.toFile(), "rw").use { it.setLength(3L shl 30) }
        val bad = rig.host()
        val o = bad.open()
        assertTrue(o.problems.any { "could not be opened" in it }, "${o.problems}")
        val refused = rig.ask(bad, op("phone", "s1", 1, "set", listOf("k1"), j("v1")))
        assertEquals(wm.damage.core.app.Answer.RETRY, (refused as wm.damage.core.app.Answer.Refused).code)
        val head = ByteArray(64).also { b -> java.io.RandomAccessFile(rig.logFile.toFile(), "r").use { it.readFully(b) } }
        assertTrue(head.all { it == 0.toByte() }, "nothing was written into the log")
        assertNull(rig.item(bad, "k1"))
    }

    @Test
    fun anUnreadableRecordIsKeptAsideAndTheLogReplayedWhole() {
        val h = rig.opened()
        for (n in 1L..2L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        rig.tick(h)
        rig.stop(h)
        Files.writeString(rig.recordFile, "{\"v\":1,\"epo")
        val h2 = rig.host()
        val o = h2.open()
        assertEquals("record.json was unreadable", o.newEpoch)
        assertTrue(Files.exists(rig.dir.resolve("record.json.bad")))
        assertTrue(o.problems.any { "kept as record.json.bad" in it })
        assertEquals(2, o.replayed)
        assertEquals(Item(j("v2"), 1, "phone", rig.now), rig.item(h2, "k2"))
        assertEquals(2L, h2.highWater(StreamKey("phone", "s1")))
    }

    /**
     * Fix round 2, ruling 1.5 (review 2-A's finding 4): a start that finds no `record.json` beside a log an earlier
     * start set aside replays that log first, in order, then the current one; it raises the `record.` alert and its
     * journal note names what it replayed. Before, it replayed only the current log and saved a record that lacked
     * every line set aside, saying "the log is replayed whole".
     */
    @Test
    fun aStartWithNoRecordBesideASetAsideLog_replaysItFirst_raisesTheAlert_andSaysSo() {
        val h = rig.opened()
        for (n in 1L..3L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        rig.tick(h)
        rig.stop(h)
        rig.setAsideBytes = 1
        val h2 = rig.host()
        val aside = assertNotNull(h2.open().setAside, "this start sets the log aside")
        for (n in 4L..5L) rig.ask(h2, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        rig.tick(h2)
        rig.stop(h2)
        Files.delete(rig.recordFile)
        rig.setAsideBytes = AppHost.LOG_SET_ASIDE_BYTES
        val h3 = rig.host()
        val o = h3.open()
        for (n in 1..5) assertEquals(j("v$n"), rig.item(h3, "k$n")?.value, "op $n replayed")
        assertEquals(5L, h3.highWater(StreamKey("phone", "s1")))
        assertEquals(5L, o.seq)
        assertTrue(rig.said.any { it.startsWith("raise ${RecordAlerts.REPLAY}") && aside.fileName.toString() in it }, "${rig.said}")
        assertTrue(rig.notes().any { "replayed" in it && aside.fileName.toString() in it }, "${rig.notes()}")
        assertEquals(wm.damage.core.app.Answer.Seen(), rig.ask(h3, op("phone", "s1", 6, "set", listOf("k6"), j("v6"))))
    }

    /** Fix round 2, ruling 1.5: a second unreadable `record.json` never replaces the first one kept aside. */
    @Test
    fun twoUnreadableRecords_areBothKeptAside_numbered() {
        rig.opened().also { rig.stop(it) }
        Files.writeString(rig.recordFile, "{\"first")
        rig.opened().also { rig.stop(it) }
        Files.writeString(rig.recordFile, "{\"second")
        val o = rig.host().open()
        assertEquals("{\"first", Files.readString(rig.dir.resolve("record.json.bad")))
        assertEquals("{\"second", Files.readString(rig.dir.resolve("record.json.bad.2")))
        assertTrue(o.problems.any { "kept as record.json.bad.2" in it }, "${o.problems}")
    }

    @Test
    fun theFilesFormRoundTripsWithTheCoveredSeqLast() {
        val h = rig.opened()
        rig.ask(h, op("phone", "s1", 1, "add", listOf("q.s1.1.0"), kotlinx.serialization.json.JsonArray(listOf(j("a")))))
        rig.ask(h, op("desk", "d", 1, "drop", listOf("q.s1.1.0"), JsonNull, wm.damage.core.app.Expect.Rev(1)))
        rig.ask(h, op("phone", "s1", 2, "set", listOf("x"), j("b"), wm.damage.core.app.Expect.Value(j("a"))))
        rig.tick(h)
        val text = String(Files.readAllBytes(rig.recordFile))
        assertTrue(text.endsWith(",\"covered\":3}"), text)
        val s = RecordStore.decode(text)
        assertEquals(text, RecordStore.encode(s))
        assertTrue(s.items["rec.t.q.s1.1.0"]!!.gone)
        assertNotNull(s.items["rec.t.x"]!!.notApplied)
        assertEquals(emptyList(), s.colls["rec.t.queue"]!!.order)
    }
}
