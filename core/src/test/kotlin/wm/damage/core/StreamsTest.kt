package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import org.junit.After
import wm.damage.core.app.Answer
import wm.damage.core.app.Expect
import wm.damage.core.app.Item
import wm.damage.core.app.StreamKey
import wm.damage.core.app.Streams

/**
 * Build S1-1, package P1 (`CONTRACT.md` §2.4 "Numbering", §13 (b) "per-stream high-water"): an op at or below its
 * stream's high-water is answered from the record and never applied again; a gap is refused "expected N" and changes
 * nothing; a new stream starts at 1; streams of two devices never meet; the high-water survives a restart, the
 * answers do not ("already handled").
 */
class StreamsTest {
    private val rig = RecordRig()

    @After
    fun tearDown() = rig.cleanUp()

    private val k = StreamKey("phone", "s1")

    /** The pin of the high-water check (mutation S1-apply-twice): without it, a resent op is judged again — here it
     *  would lose to the desk's later value and mark the item, though it applied the first time. */
    @Test
    fun anOpAtOrBelowTheHighWaterIsNeverAppliedAgain() {
        val s = Streams()
        assertEquals(Streams.Check.Next, s.check(k, 1))
        s.commit(k, 1, Answer.Seen())
        assertEquals(Streams.Check.Handled(Answer.Seen()), s.check(k, 1))

        val h = rig.opened()
        val first = op("phone", "s1", 1, "set", listOf("flag"), j("b"), Expect.Value(JsonNull))
        assertEquals(Answer.Seen(), rig.ask(h, first))
        rig.ask(h, op("desk", "d", 1, "set", listOf("flag"), j("c"), Expect.Value(j("b"))))
        val seq = h.seq
        val standing = rig.item(h, "flag")
        assertEquals(Answer.Seen(), rig.ask(h, first))
        assertEquals(standing, rig.item(h, "flag"), "the item is not touched again")
        assertEquals(seq, h.seq, "no line is written for a resend")
        assertEquals(1L, h.highWater(k))
    }

    @Test
    fun aGapIsRefusedAndChangesNothing() {
        val h = rig.opened()
        rig.ask(h, op("phone", "s1", 1, "set", listOf("a"), j("1")))
        val seq = h.seq
        val got = rig.ask(h, op("phone", "s1", 3, "set", listOf("a"), j("3")))
        assertEquals(Answer.Refused("expected 2", Answer.GAP), got)
        assertEquals(seq, h.seq)
        assertEquals(1L, h.highWater(k))
        assertEquals(j("1"), rig.item(h, "a")!!.value)
        // the client resends in order: 2, then 3
        assertEquals(Answer.Seen(), rig.ask(h, op("phone", "s1", 2, "set", listOf("a"), j("2"))))
        assertEquals(Answer.Seen(), rig.ask(h, op("phone", "s1", 3, "set", listOf("a"), j("3"))))
        assertEquals(j("3"), rig.item(h, "a")!!.value)
    }

    @Test
    fun aNewStreamStartsAtOne() {
        val s = Streams()
        assertEquals(Streams.Check.Gap(1), s.check(StreamKey("browser", "fresh"), 2))
        assertEquals(Streams.Check.Gap(1), s.check(StreamKey("browser", "fresh"), 0))
        assertEquals(Streams.Check.Next, s.check(StreamKey("browser", "fresh"), 1))
    }

    @Test
    fun twoDevicesStreamsNeverMeet() {
        val h = rig.opened()
        rig.ask(h, op("phone", "same", 1, "set", listOf("a"), j("p")))
        // another device naming the same stream value is its own stream
        assertEquals(Answer.Seen(), rig.ask(h, op("desk", "same", 1, "set", listOf("a"), j("d"))))
        assertEquals(j("d"), rig.item(h, "a")!!.value)
        assertEquals(mapOf("same" to 1L), h.highWaters("desk"))
        assertEquals(mapOf("same" to 1L), h.highWaters("phone"))
    }

    @Test
    fun theLastAnswersAreKeptAndOlderOnesAreAlreadyHandled() {
        val s = Streams(keep = 2)
        for (n in 1L..3L) s.commit(k, n, Answer.Refused("r$n", Answer.INVALID))
        assertEquals(Streams.Check.Handled(null), s.check(k, 1))
        assertEquals(Streams.Check.Handled(Answer.Refused("r2", Answer.INVALID)), s.check(k, 2))
        // through the host: a lost answer is kept as it was
        val h = rig.opened()
        rig.ask(h, op("desk", "d", 1, "set", listOf("x"), j("c")))
        val lost = op("phone", "s1", 1, "set", listOf("x"), j("b"), Expect.Value(j("a")))
        val first = rig.ask(h, lost)
        assertIs<Answer.Lost>(first)
        assertEquals(first, rig.ask(h, lost))
    }

    /** Fix round 1, ruling 2.1: two tabs of one browser share its stream; a number that already carried ANOTHER op
     *  (the other tab's) is refused in words and never applied, while a true retry (the same op: a phone's frozen op
     *  sent again, a page's same frame) gets its held answer as before — also from a line replayed at a start. */
    @Test
    fun aRepeatedNumberCarryingAnotherOp_isRefused_aTrueRetryGetsItsHeldAnswer() {
        val h = rig.opened()
        val tabB = op("web-1", "s1", 1, "set", listOf("a"), j("tab B"))
        assertEquals(Answer.Seen(), rig.ask(h, tabB))
        val seq = h.seq
        assertEquals(Answer.Seen(), rig.ask(h, tabB), "a true retry: its held answer, as today")
        val tabA = op("web-1", "s1", 1, "set", listOf("a"), j("tab A"))
        val got = rig.ask(h, tabA)
        assertIs<Answer.Refused>(got, "another op at a handled number: $got")
        assertEquals(Answer.INVALID, got.code)
        assertTrue("another change" in got.reason && "not applied" in got.reason, got.reason)
        assertEquals(j("tab B"), rig.item(h, "a")!!.value, "the other op is never applied")
        assertEquals(seq, h.seq, "no line is written")
        assertEquals(1L, h.highWater(StreamKey("web-1", "s1")))
        // a lost answer's true retry is unchanged too
        rig.ask(h, op("desk", "d", 1, "set", listOf("x"), j("c")))
        val lost = op("web-1", "s1", 2, "set", listOf("x"), j("b"), Expect.Value(j("a")))
        val first = rig.ask(h, lost)
        assertIs<Answer.Lost>(first)
        assertEquals(first, rig.ask(h, lost))
        // the identity is read back with the log's lines at the next start
        rig.stop(h)
        val h2 = rig.opened()
        assertEquals(first, rig.ask(h2, lost), "a replayed line's true retry")
        assertIs<Answer.Refused>(rig.ask(h2, op("web-1", "s1", 2, "set", listOf("x"), j("other"))), "a replayed line's other op")
        assertEquals(Answer.Seen(), rig.ask(h2, op("web-1", "s1", 3, "set", listOf("a"), j("tab A"))), "the next number applies")
    }

    @Test
    fun theHighWaterSurvivesARestartAndItsAnswersDoNot() {
        val h = rig.opened()
        rig.ask(h, op("phone", "s1", 1, "set", listOf("a"), j("1")))
        rig.ask(h, op("phone", "s1", 2, "set", listOf("a"), j("2")))
        rig.tick(h)                                           // record.json covers both
        rig.stop(h)
        val h2 = rig.opened()
        assertEquals(2L, h2.highWater(k))
        assertEquals(Answer.Seen(again = true), rig.ask(h2, op("phone", "s1", 2, "set", listOf("a"), j("2"))))
        assertEquals(Answer.Refused("expected 3", Answer.GAP), rig.ask(h2, op("phone", "s1", 4, "set", listOf("a"), j("4"))))
        assertEquals(Item(j("2"), 2, "phone", rig.now), rig.item(h2, "a"))
    }
}
