package wm.damage.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import wm.damage.core.app.ActionTable
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.Draft
import wm.damage.core.app.Expect
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.NoticeRecord
import wm.damage.core.app.Notices
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Windows

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q3 (the spec's §2.1, §5 N1; S1-2 R0 §2): the notice record on the PC — what a raise writes, the
 * occurrence as text, the apply-time rule (a state never moves backwards; raise and clear the PC's own), the Notify row
 * read once per raise, the hold Quiet hours put on a NORMAL record, and a raise the log did not take. Over a record in a
 * temp directory with made-up notices; nothing touches the real data directory.
 */
class NoticeCoreTest {
    private val dir: Path = Files.createTempDirectory("damage-notices")
    private val hosts = ArrayList<AppHost>()
    @Volatile private var syncFails = false
    private val zone = ZoneId.of("UTC")
    /** 2026-11-15 23:30 UTC: inside 22–07. */
    private var now = ZonedDateTime.of(2026, 11, 15, 23, 30, 0, 0, zone).toInstant().toEpochMilli()

    /** The record's own windows and a made-up `t` whose `set` writes a Notify row. */
    private val actions = ActionTable { w, a -> TEST_ACTIONS.spec(w, a) ?: Windows.ACTIONS.spec(w, a) }

    private fun host(): AppHost = AppHost(dir, actions, CoreExecutor("damage-core-notices"), { now },
        logSync = { ch -> if (syncFails) throw IOException("no space left on device (test)"); ch.force(true) })
        .also { hosts += it; it.open() }

    @AfterTest
    fun cleanUp() {
        for (h in hosts) { h.close(); h.executor.shutdown() }
        dir.toFile().deleteRecursively()
    }

    /** The executor's turns: a raise, then its op, then its answer. */
    private fun drain(h: AppHost) = repeat(4) { h.executor.call {} }

    private fun value(h: AppHost, id: String): JsonObject? =
        h.read(Notices.WINDOW).third.items["rec.notices.$id"]?.value as? JsonObject

    private fun record(h: AppHost, id: String): NoticeRecord = Notices.parse(id, value(h, id)!!)!!

    private fun ask(h: AppHost, o: Op): Answer {
        val got = ArrayList<Answer>()
        h.act(o) { got += it }
        h.executor.call {}
        return got.single()
    }

    private val next = HashMap<String, Long>()
    private fun op(device: String, d: Draft): Op {
        val n = (next[device] ?: 0L) + 1
        next[device] = n
        return Op(device, OpId.Queued("s-$device", n), Notices.WINDOW, d.action, d.targets, d.result, d.expect)
    }

    private fun core(h: AppHost, quiet: String = "off", notes: MutableList<String> = ArrayList()) =
        NoticeCore(h, { quiet }, { now }, { zone }, { notes += it })

    @Test
    fun aRaiseWritesItsOccurrenceAsTextAndADismissExpectingItLands() {
        val h = host()
        val c = core(h)
        c.raise("torrent", "abc1", "done · a made-up download", "torrents", "t:abc1", "Torrents")
        drain(h)
        val v = assertNotNull(value(h, "torrent:abc1"), "the raise wrote rec.notices.torrent:abc1")
        // the dismiss a page builds (Q3w): the value as seen with its state changed, the occurrence as text whatever
        // form the value gave it
        val occText = v["occ"]!!.jsonPrimitive.content
        val dismiss = Draft(Notices.DISMISS, listOf("torrent:abc1"),
            JsonObject(v + ("state" to JsonPrimitive(Notices.DISMISSED))), Expect.Occurrence(occText))
        val a = ask(h, op("phone", dismiss))
        assertIs<Answer.Seen>(a, "a dismiss with the occurrence expectation lands (R0 §2; a numeric occ makes it lose, unsaid)")
        assertEquals(Notices.DISMISSED, record(h, "torrent:abc1").state)
        val occ = v["occ"]!!.jsonPrimitive
        assertTrue(occ.isString, "occ is written as text (R0 §2), as Expect.Occurrence reads it: $occ")
        assertEquals("1", occ.content)
        assertEquals(Notices.RAISED, v["state"]!!.jsonPrimitive.content)
        assertEquals("torrents", v["app"]!!.jsonPrimitive.content)
        assertEquals("t:abc1", v["target"]!!.jsonPrimitive.content)
        assertEquals(now, v["raisedAt"]!!.jsonPrimitive.longOrNull)
        assertNull(v["heldUntil"], "Quiet hours off: nothing held (decisions A.2)")
    }

    @Test
    fun aReadThatComesAfterADismissLosesAndTheNoticeStaysDismissed() {
        val h = host()
        core(h).raise("torrent", "abc2", "done · another", "torrents", "t:abc2", "Torrents")
        drain(h)
        val seen = record(h, "torrent:abc2")                       // what the page saw before the desk dismissed
        assertIs<Answer.Seen>(ask(h, op("phone", Notices.dismissDraft(seen))))
        val late = ask(h, op("web-1", Notices.readDraft(seen)))
        assertIs<Answer.Lost>(late, "a read after a dismiss of the same occurrence is refused at apply as lost")
        assertEquals(Notices.DISMISSED, record(h, "torrent:abc2").state, "a notice's state never moves backwards")
        // an ack after a dismiss is not backwards (an ackRequired record's ack ends it, C§7.2)
        assertIs<Answer.Seen>(ask(h, op("phone", Notices.ackDraft(seen))))
        assertEquals(Notices.ACKED, record(h, "torrent:abc2").state)
    }

    @Test
    fun aStaleOccurrenceChangesNothing() {
        val h = host()
        val c = core(h)
        c.raise("torrent", "abc3", "done · first", "torrents", "t:abc3", "Torrents")
        drain(h)
        val first = record(h, "torrent:abc3")
        c.raise("torrent", "abc3", "done · again", "torrents", "t:abc3", "Torrents")
        drain(h)
        assertEquals("2", record(h, "torrent:abc3").occ, "a new raise is the next occurrence")
        assertIs<Answer.Lost>(ask(h, op("phone", Notices.dismissDraft(first))), "a dismiss of occurrence 1 loses")
        // a client that forgets the occurrence (Any) is refused, never judged by arrival order
        val forgot = Notices.dismissDraft(first).copy(expect = Expect.Any)
        val a = ask(h, op("web-1", forgot))
        assertIs<Answer.Refused>(a, "a reading effect expects the occurrence it carries")
        assertEquals(Answer.INVALID, a.code)
        val now2 = record(h, "torrent:abc3")
        assertEquals("2", now2.occ)
        assertEquals(Notices.RAISED, now2.state, "occurrence 2 is untouched by occurrence 1's dismiss")
    }

    @Test
    fun raiseAndClearAreThePcsOwn() {
        val h = host()
        val v = JsonObject(mapOf("occ" to JsonPrimitive("9"), "state" to JsonPrimitive(Notices.RAISED)))
        val a = ask(h, Op("phone", OpId.Queued("s-phone", 1), Notices.WINDOW, Notices.RAISE, listOf("x:y"), v))
        assertIs<Answer.Refused>(a, "a raise from the phone is refused at apply time")
        assertEquals(Answer.LEVEL, a.code)
        assertNull(value(h, "x:y"))
    }

    @Test
    fun aNotifyRowThatIsOffRaisesNothing() {
        val h = host()
        val c = core(h)
        assertIs<Answer.Seen>(ask(h, Op("local", OpId.Queued("s-row", 1), "t", "set", listOf("notify.done"), JsonPrimitive(false))))
        c.raise("torrent", "abc4", "done · muted", "torrents", "t:abc4", "Torrents", notifyRow = "rec.t.notify.done")
        drain(h)
        assertNull(value(h, "torrent:abc4"), "the Notify row is off: nothing raised")
        assertIs<Answer.Seen>(ask(h, Op("local", OpId.Queued("s-row", 2), "t", "set", listOf("notify.done"), JsonPrimitive(true))))
        c.raise("torrent", "abc4", "done · heard", "torrents", "t:abc4", "Torrents", notifyRow = "rec.t.notify.done")
        drain(h)
        assertEquals("done · heard", record(h, "torrent:abc4").body)
    }

    @Test
    fun aNormalRaiseInsideQuietHoursCarriesTheEndInThePcsClock() {
        val h = host()
        val c = core(h, quiet = "22-07")
        c.raise("torrent", "abc5", "done · at night", "torrents", "t:abc5", "Torrents")
        c.raise("torrent", "abc6", "error · at night", "torrents", "t:abc6", "Torrents", urgency = Notices.URGENT)
        drain(h)
        val end = ZonedDateTime.of(2026, 11, 16, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(end, record(h, "torrent:abc5").heldUntil, "held until 07:00 the next morning")
        assertNull(record(h, "torrent:abc6").heldUntil, "URGENT passes quiet hours")
        now = ZonedDateTime.of(2026, 11, 16, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        c.raise("torrent", "abc7", "done · at noon", "torrents", "t:abc7", "Torrents")
        drain(h)
        assertNull(record(h, "torrent:abc7").heldUntil, "outside the hours: not held")
    }

    @Test
    fun aRaiseTheLogDidNotTakeIsSentAgainAtTheTickAndSaidAtTheEnd() {
        val h = host()
        val notes = CopyOnWriteArrayList<String>()
        val c = core(h, notes = notes)
        syncFails = true
        c.raise("torrent", "abc8", "done · kept", "torrents", "t:abc8", "Torrents")
        drain(h)
        assertNull(value(h, "torrent:abc8"), "the log did not take it")
        c.atEnd()
        assertTrue(notes.any { "not announced" in it && "torrent:abc8" in it }, "a raise still kept is said at the end: $notes")
        h.executor.call { c.tick() }                              // still failing: kept again, never dropped
        drain(h)
        assertNull(value(h, "torrent:abc8"))
        syncFails = false
        h.executor.call { c.tick() }
        drain(h)
        assertEquals("done · kept", record(h, "torrent:abc8").body, "sent again at the record's tick")
        notes.clear()
        c.atEnd()
        assertTrue(notes.isEmpty(), "nothing kept once it landed: $notes")
    }

    @Test
    fun theDescriptionListsTheLiveTable() {
        val h = host()
        val c = core(h)
        c.raise("torrent", "a", "done · a", "torrents", "t:a", "Torrents")
        c.raise("torrent", "b", "done · b", "torrents", "t:b", "Torrents")
        drain(h)
        assertIs<Answer.Seen>(ask(h, op("phone", Notices.dismissDraft(record(h, "torrent:a")))))
        val (e, s, items) = h.read(Notices.WINDOW)
        val d = c.describe(wm.damage.core.app.WindowState(Notices.WINDOW, items.items, cursor = wm.damage.core.app.Cursor(e, s)))
        val live = (d["live"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("torrent:b"), live, "a dismissed notice leaves the live table")
    }
}
