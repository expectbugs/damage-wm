package wm.damage.core

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import wm.damage.core.app.Answer
import wm.damage.core.app.Change
import wm.damage.core.app.RecordAlerts
import wm.damage.core.app.SeqFile

/**
 * Build S1-1, package P1 (`CONTRACT.md` §9.3, §13 (b) "sequence file"): the file holds the highest `seq` published,
 * written before each publish; missing at a start → a new epoch; a store older than it (the nightly-copy restore)
 * → a new epoch; a store the log brings back level with it keeps its epoch; a failed write holds the publish back,
 * said, and the next tick publishes in order.
 */
class SeqFileTest {
    private val rig = RecordRig()

    @After
    fun tearDown() = rig.cleanUp()

    @Test
    fun aMissingSequenceFileMintsANewEpoch() {
        val h = rig.opened()
        rig.ask(h, op("phone", "s1", 1, "set", listOf("a"), j("v1")))
        val epoch = h.epoch
        rig.stop(h)
        Files.delete(rig.seqFile)
        rig.now += 1_000
        val h2 = rig.host()
        assertEquals("the sequence file was missing", h2.open().newEpoch)
        assertNotEquals(epoch, h2.epoch)
        assertEquals(j("v1"), rig.item(h2, "a")!!.value)
        assertEquals(SeqFile.Held(h2.epoch, 1), SeqFile(rig.seqFile).read(), "written again at the start")
    }

    /** The pin of C§9.3's restore rule (mutation S1-seqfile-ignored): `record.json` comes back from a copy that
     *  covers 3 while changes up to 5 were published; the log of 4 and 5 is gone with the disk. Clients hold cursors
     *  the store cannot answer, so the store takes a new epoch and every client gets a fresh snapshot. */
    @Test
    fun aStoreOlderThanTheSequenceFileMintsANewEpoch() {
        val h = rig.opened()
        for (n in 1L..3L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        rig.tick(h)
        val copy = rig.dir.resolve("nightly-record.json")
        Files.copy(rig.recordFile, copy)
        for (n in 4L..5L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        rig.tick(h)
        val epoch = h.epoch
        rig.stop(h)
        Files.copy(copy, rig.recordFile, StandardCopyOption.REPLACE_EXISTING)
        Files.delete(rig.logFile)
        rig.now += 1_000
        val h2 = rig.host()
        val o = h2.open()
        assertEquals("the store is older than the sequence file (3 < 5)", o.newEpoch)
        assertNotEquals(epoch, h2.epoch)
        assertEquals(5L, h2.seq, "no seq is given out twice")
        assertNull(rig.item(h2, "k4"), "what the restore lacks stays absent: the clients' snapshots say so")
        assertEquals(Answer.Seen(), rig.ask(h2, op("desk", "d", 1, "set", listOf("k1"), j("w"))))
        assertEquals(6L, h2.seq)
    }

    @Test
    fun aStoreTheLogBringsLevelKeepsItsEpoch() {
        val h = rig.opened()
        for (n in 1L..3L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        rig.tick(h)
        val copy = rig.dir.resolve("nightly-record.json")
        Files.copy(rig.recordFile, copy)
        for (n in 4L..5L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        rig.tick(h)
        val epoch = h.epoch
        rig.stop(h)
        Files.copy(copy, rig.recordFile, StandardCopyOption.REPLACE_EXISTING)   // the log stays
        val h2 = rig.host()
        assertNull(h2.open().newEpoch)
        assertEquals(epoch, h2.epoch)
        assertEquals(j("v5"), rig.item(h2, "k5")!!.value)
    }

    /** A start that minted an epoch and could not save `record.json` leaves the store under the old epoch and the
     *  sequence file under the new one: the next start cannot tell which changes clients hold, so it mints again. */
    @Test
    fun aSequenceFileOfAnotherEpochMintsANewEpoch() {
        val h = rig.opened()
        rig.ask(h, op("phone", "s1", 1, "set", listOf("a"), j("v1")))
        val epoch = h.epoch
        rig.stop(h)
        SeqFile(rig.seqFile).write(epoch + 7, 1)
        rig.now += 1_000
        val h2 = rig.host()
        assertEquals("the sequence file names another epoch", h2.open().newEpoch)
        assertTrue(h2.epoch != epoch && h2.epoch != epoch + 7)
        assertEquals(j("v1"), rig.item(h2, "a")!!.value)
    }

    @Test
    fun theFileHoldsTheSeqBeforeItsChangeIsPublished() {
        val h = rig.opened()
        val atPublish = ArrayList<Pair<Long, Long?>>()
        h.addListener { c -> atPublish += c.seq to SeqFile(rig.seqFile).read()?.seq }
        for (n in 1L..3L) rig.ask(h, op("phone", "s1", n, "set", listOf("a"), j("v$n")))
        assertEquals(listOf<Pair<Long, Long?>>(1L to 1L, 2L to 2L, 3L to 3L), atPublish)
    }

    @Test
    fun aFailedWriteHoldsThePublishBackAndTheNextTickSendsItInOrder() {
        val h = rig.opened()
        val published = ArrayList<Change>()
        h.addListener { published += it }
        Files.delete(rig.seqFile)
        Files.createDirectories(rig.seqFile.resolve("in-the-way"))   // a rename over a directory fails
        assertEquals(Answer.Seen(), rig.ask(h, op("phone", "s1", 1, "set", listOf("a"), j("v1"))), "applied and answered")
        assertEquals(Answer.Seen(), rig.ask(h, op("phone", "s1", 2, "set", listOf("a"), j("v2"))))
        assertTrue(published.isEmpty(), "not published while the file cannot take the seq")
        assertEquals(1, rig.said.count { it.startsWith("raise ${RecordAlerts.SEQ}") })
        assertEquals(emptyList<Change>(), h.ring.since(0), "nor resumable from the ring")
        rig.tick(h)
        assertTrue(published.isEmpty(), "still in the way")
        rig.seqFile.toFile().deleteRecursively()
        rig.tick(h)
        assertEquals(listOf(1L, 2L), published.map { it.seq })
        assertEquals(listOf(1L, 2L), h.ring.since(0)!!.map { it.seq })
        assertEquals("clear ${RecordAlerts.SEQ}", rig.said.last { RecordAlerts.SEQ in it })
        assertEquals(SeqFile.Held(h.epoch, 2), SeqFile(rig.seqFile).read())
    }
}
