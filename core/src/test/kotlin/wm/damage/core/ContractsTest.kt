package wm.damage.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import wm.damage.core.app.Answer
import wm.damage.core.app.AppClient
import wm.damage.core.app.AppWire
import wm.damage.core.app.Contracts
import wm.damage.core.app.Op
import wm.damage.core.app.OpId

/**
 * Build S1-1, package P2 (`CONTRACT.md` §9.7 item 2, §13 (b) "contract version per window"): each side names the
 * version of each window it converts; a window whose two versions differ is not shared on that connection — the PC
 * refuses its subscription and its ops without taking a number (the op waits in the client's queue until both
 * match), leaves it out of the `record` scope, and the client reads the difference as a value its presenters show
 * ("PC and phone on different builds for Reader"). Before the PC has answered once nothing is shown.
 */
class ContractsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closing = ArrayList<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        for (c in closing.asReversed()) c.close()
    }

    @Test
    fun theComparisonAndItsLine() {
        // S1-2 R0 §2 names the change: the notice record (Q3) at version 1 beside Reader's; Torrents (Q4) and Files (Q5)
        // at version 1 (the merge keeps both packages' entries)
        assertEquals(mapOf("reader" to 1, "notices" to 1, "torrents" to 1, "files" to 1), Contracts.MINE,
            "S1-1 converts Reader, S1-2 Q3 adds the notice record, Q4 Torrents, Q5 Files, each at version 1 (S1-2 R0 §2)")
        val m = Contracts.compare(mapOf("reader" to 1, "music" to 1), mapOf("reader" to 2, "feed" to 1))
        assertEquals(listOf("feed", "music", "reader"), m.keys.toList())
        assertTrue(m.values.none { it.same })
        assertEquals(setOf("reader"), Contracts.shared(mapOf("reader" to 1, "music" to 1), mapOf("reader" to 1)))
        assertEquals("PC and phone on different builds for Reader", Contracts.mismatchLine("reader"))
        assertNull(Contracts.State.Unknown.line("reader"), "an unreachable PC is not a different build")
        val known = Contracts.State.Known(Contracts.compare(mapOf("reader" to 1), mapOf("reader" to 1)))
        assertTrue(known.shared("reader"))
        assertNull(known.line("reader"))
    }

    @Test
    fun aWindowOnDifferentBuildsIsNotSharedOnTheConnection() {
        val rig = AppLaneRig().also { closing += it }
        rig.write("desk", "d1", 1, "a", "one")
        val lane = RawLane(rig.port, contracts = mapOf("t" to 2)).also { closing += it }
        assertEquals(mapOf("t" to 1), lane.welcome().contracts, "welcome carries the PC's versions")
        lane.send(AppWire.sub("t", null))
        val refused = AppWire.parseAns(lane.next("ans"))
        assertEquals("t", refused.scope)
        assertEquals(Answer.Refused(Contracts.mismatchLine("t"), Answer.CONTRACT), refused.answer)
        lane.send(AppWire.sub(AppWire.RECORD, null))
        val snap = AppWire.parseSnap(lane.next("snap"))
        assertTrue(snap.state.isEmpty(), "the record scope leaves out a window on different builds: $snap")
        val seq = rig.host.seq
        lane.op(Op("phone", OpId.Queued("s1", 1), "t", "set", listOf("a"), j("mine")))
        assertEquals(Answer.Refused(Contracts.mismatchLine("t"), Answer.CONTRACT), lane.answer())
        assertEquals(seq, rig.host.seq, "no line: the op keeps its number and waits")
        assertEquals(emptyMap(), rig.host.highWaters("phone"))
        rig.write("desk", "d1", 2, "a", "two")
        lane.quiet()                                     // and no change of `t` reaches it
    }

    @Test
    fun theClientReadsTheDifferenceAsAValue(): Unit = runBlocking {
        val rig = AppLaneRig().also { closing += it }
        val heard = CopyOnWriteArrayList<Contracts.State>()
        val differ = AppClient("127.0.0.1", rig.port, AppClient.Credential.Token("tok"), "s1",
            object : AppClient.Listener {
                override fun contracts(s: Contracts.State) { heard += s }
            }, scope, contracts = mapOf("t" to 2), pingEveryMs = 0).also { closing += it }
        val same = AppClient("127.0.0.1", rig.port, AppClient.Credential.Token("tok"), "s2", object : AppClient.Listener {},
            scope, contracts = mapOf("t" to 1), pingEveryMs = 0).also { closing += it }
        waitUntil(10_000, 5, { "both welcomed" }) { differ.welcome != null && same.welcome != null }
        val d = differ.contractState as Contracts.State.Known
        assertFalse(d.shared("t") || d.oldPc)
        assertEquals("PC and phone on different builds for t", d.line("t"))
        assertEquals(listOf<Contracts.State>(d), heard.toList())
        assertTrue(same.contractState.shared("t"))
        assertNull(same.contractState.line("t"))
    }
}
