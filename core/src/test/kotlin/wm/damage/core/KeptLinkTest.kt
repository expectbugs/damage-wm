package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.CfwTransportBase.Companion.KEPT_LINK_REASKS
import wm.damage.core.transport.CfwTransportBase.Companion.KEPT_LINK_WARMUP_RESENDS
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.LaunchMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.SettingsMsg

/**
 * Stage BW (Adam's word, 2026-11-25; `private/build085/BL-decisions.md` Parts B and D): a deliberate session restart
 * keeps the radio link and the next start adopts it — the rules around that decision, against the simulator (they
 * prove the phone's sequence, never the firmware's answer). The wake's own pin is `SilentGlassesTest`'s push-OFF rig;
 * the start's bytes are `BindTransportTest`'s files; the watchdog's link end is `WatchdogTest`'s.
 */
class KeptLinkTest {

    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    private class Rig(scope: CoroutineScope, retryPauseMs: Long) {
        val tmp = Files.createTempDirectory("damage-keptlink")
        val sim = GlassFirmwareSim()
        val st = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val transport = st
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope, HELD_CLOCK)
        val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
        val keeper = ShellKeeper(shell, transport, scope, onStatus = { statuses.add(it) }, retryPauseMs = retryPauseMs)
        /** Stage BWF: the start's one `link` note each — kept or fresh first, each gate's answer, where it fell back. */
        val linkNotes = java.util.concurrent.CopyOnWriteArrayList<String>()
        init {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                st.events.collect { if (it is TransportEvent.Note && it.kind == "link") linkNotes.add(it.detail) }
            }
        }
        fun driving() = keeper.state == ShellKeeper.State.RUNNING && transport.state.value.started
        suspend fun until(what: String, maxMs: Long = 10_000, cond: () -> Boolean) =
            waitUntil(maxMs, 10, { "$what — keeper ${keeper.state} attempts ${keeper.attempts}; connects ${st.connects} " +
                "disconnects ${st.disconnects}; transport ${transport.state.value}; statuses $statuses" }, cond)
    }

    /** An arm that drops in the gap after a kept restart fails the adoption: the start says so and connects afresh
     *  (on the phone `connectLink` ends the survivor first). Beside it, the same restart with the link still up is
     *  adopted — no second connect, the link event says so.
     *  Mutation watched: the adoption's refusal ignored (the start adopts whatever `adoptLink` says). */
    @Test
    fun anArmDownInTheGapTakesTheFullConnect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (drop in listOf(false, true)) {
                val t = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
                val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
                scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    t.events.collect {
                        if (it is TransportEvent.Note) notes.add("${it.kind}: ${it.detail}")
                        if (it is TransportEvent.Link) notes.add("link: ${it.connected} ${it.detail}")
                    }
                }
                t.start(warmup())
                assertEquals(1, t.connects)
                assertTrue(t.restartSession("test: a deliberate restart"))
                assertTrue(t.state.value.linkKept, "the restart kept the link")
                assertFalse(t.state.value.started)
                assertEquals(0, t.disconnects, "nothing ended the link")
                if (drop) t.linkEndsInGap()
                t.start(warmup())
                assertTrue(t.state.value.started, "drop=$drop: the start completes")
                assertFalse(t.state.value.linkKept, "drop=$drop: the start cleared the mark")
                if (drop) {
                    assertEquals(2, t.connects, "an arm down in the gap: today's full connect")
                    waitUntil(5_000, 10, { "the refusal is said: $notes" }) {
                        notes.any { it == "keeper: the kept link was not adopted (the sim's link ended in the gap) — connecting afresh" } }
                } else {
                    assertEquals(1, t.connects, "both arms up: the link is adopted, no connect")
                    waitUntil(5_000, 10, { "the adoption is said: $notes" }) { notes.any { it.startsWith("link: true") && "adopted" in it } }
                }
                t.stop()
                assertEquals(1, t.disconnects, "drop=$drop: the stop ends the link")
            }
        } finally { scope.cancel() }
    }

    /** A stop in the gap (the service stopping, a Target switch: `ShellService.stopStack` stops the keeper, then the
     *  transport, exactly once) ends the kept link, as a stop always ended it, and the mark goes with it. The keeper
     *  itself never stops the transport after a kept end (the yield form). */
    @Test
    fun aStopInTheGapEndsTheKeptLink(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope, retryPauseMs = 60_000)
            rig.keeper.start()
            rig.until("the first session drives") { rig.driving() }
            assertTrue(rig.transport.restartSession("test: a deliberate restart"))
            rig.until("the keeper waits in the gap with the shell stopped") { rig.statuses.contains("retrying in 60 s") }
            assertEquals(0, rig.st.disconnects, "the gap: the link is kept")
            assertTrue(rig.transport.state.value.linkKept)
            assertTrue(rig.statuses.any { it.startsWith("session ended: ") }, "the keeper says the session ended, not the link: ${rig.statuses}")
            rig.keeper.stop()
            rig.transport.stop()
            assertEquals(1, rig.st.disconnects, "the stop in the gap ended the kept link")
            assertFalse(rig.transport.state.value.linkKept, "and the mark")
        } finally { scope.cancel() }
    }

    /** The fallback (his word, Part D; the count as Part G item 2 has it): on an ADOPTED link a CREATE asked again
     *  [KEPT_LINK_REASKS] times with no answer fails the start with words that say the kept link did not answer; the
     *  start's `link` note says it fell back there; the rollback ends the link and the next attempt connects afresh —
     *  and there, on a link connected afresh, the re-asks go on as before: the CREATE is sent at least twice more than
     *  the kept link's give-up allows, on that one start (no second failure, no third connect), then the answer comes
     *  and the session drives. (The count is taken from the moment the fresh connect is seen, which on the instant sim
     *  may already include that start's first CREATE: two more than the give-up's is past it either way.)
     *  Mutations watched: the give-up never firing (the kept start re-asks with no end); firing on a fresh link too. */
    @Test
    fun aKeptLinkThatDoesNotAnswerTheCreateFallsBackToAFreshConnect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = fallsBackAt(scope, "carrier CREATE ended early — the kept link did not answer the start: the carrier CREATE " +
                "went unanswered after $KEPT_LINK_REASKS re-asks (2 s apart)", "carrier CREATE", "carrier CREATE unanswered ($KEPT_LINK_REASKS re-asks)",
                lift = false) { isEvenHubAck(it, EvenHubMsg.CMD_CREATE) }
            val createsAtFresh = rig.sim.createsSeen
            rig.until("the fresh link's CREATE is re-asked past the kept link's give-up", maxMs = 25_000) {
                rig.sim.createsSeen - createsAtFresh >= KEPT_LINK_REASKS + 2
            }
            assertEquals(1, rig.statuses.count { it.startsWith("start failed") }, "no fallback on a fresh link: ${rig.statuses}")
            assertEquals(2, rig.st.connects, "the re-asks were on the one fresh start")
            assertFalse(rig.driving())
            rig.st.notifyFilter = null                             // the glasses answer again
            rig.until("the fresh session drives") { rig.driving() }
            assertEquals(3, rig.keeper.attempts, "the first, the kept one that fell back, the fresh one")
            assertEquals(1, rig.st.disconnects)
            rig.until("the fresh start's note") { rig.linkNotes.size == 3 }
            assertTrue(rig.linkNotes[2].startsWith("fresh: ") && "carrier CREATE unanswered" !in rig.linkNotes[2] &&
                Regex("carrier CREATE \\d+ ms \\(\\d+ re-asks\\)").containsMatchIn(rig.linkNotes[2]),
                "the fresh start's note: its CREATE answered after its re-asks — ${rig.linkNotes}")
            rig.keeper.stop()
        } finally { scope.cancel() }
    }

    /** The same fallback at the first gate: a kept link whose prelude is never acked (a link gone quiet fails there
     *  first). Mutation watched: the prelude's give-up removed (the kept start then asks again with no end). */
    @Test
    fun aKeptLinkThatDoesNotAckThePreludeFallsBackToAFreshConnect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = fallsBackAt(scope, "connect prelude ended early — the kept link did not answer the start: the connect prelude " +
                "went unanswered after $KEPT_LINK_REASKS re-asks (2 s apart)", "connect prelude",
                "connect prelude unanswered ($KEPT_LINK_REASKS re-asks)") { StartRig.sid(it) == LaunchMsg.SID }
            rig.keeper.stop()
        } finally { scope.cancel() }
    }

    /** The same fallback at the capability READ: the prelude acked, the READ never answered. Mutation watched: the
     *  READ's give-up removed. */
    @Test
    fun aKeptLinkThatDoesNotAnswerTheReadFallsBackToAFreshConnect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = fallsBackAt(scope, "capability query ended early — the kept link did not answer the start: the capability READ " +
                "went unanswered after $KEPT_LINK_REASKS re-asks (2 s apart)", "capability READ",
                "capability READ unanswered ($KEPT_LINK_REASKS re-asks)") { StartRig.sid(it) == SettingsMsg.SID }
            rig.keeper.stop()
        } finally { scope.cancel() }
    }

    /** Part G item 3 (reader b's finding 1): a kept LEFT that delivers nothing — RIGHT answers the prelude, the READ
     *  and the CREATE, and the warmup, LEFT's first answer since the last session, is never acked: sent again
     *  [KEPT_LINK_WARMUP_RESENDS] times, the start fails the same way and the next one connects afresh. Mutation
     *  watched: the warmup's give-up removed (the kept start then re-sends with no end). */
    @Test
    fun aKeptLinkThatDoesNotAckTheWarmupFallsBackToAFreshConnect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = fallsBackAt(scope, "warmup frame ended early — the kept link did not answer the start: the warmup frame " +
                "went unanswered after $KEPT_LINK_WARMUP_RESENDS re-sends (10 s apart)", "warmup frame",
                "warmup frame unanswered ($KEPT_LINK_WARMUP_RESENDS re-sends)") { isEvenHubAck(it, EvenHubMsg.CMD_IMAGE) }
            rig.keeper.stop()
        } finally { scope.cancel() }
    }

    /** The shared walk: a first session drives over a fresh link; the glasses stop answering one kind of message
     *  ([drop]); a deliberate restart keeps the link and the kept start falls back with [failed]'s words; its `link`
     *  note leads `kept, fell back at the [gate]` and names [unanswered]; the rollback ends the link and the next start
     *  connects afresh. With [lift] the glasses answer again at once and that fresh start drives. */
    private suspend fun fallsBackAt(scope: CoroutineScope, failed: String, gate: String, unanswered: String,
                                    lift: Boolean = true, drop: (ByteArray) -> Boolean): Rig {
        val rig = Rig(scope, retryPauseMs = 100)
        rig.keeper.start()
        rig.until("the first session drives and its start's note is in") { rig.driving() && rig.linkNotes.size == 1 }
        assertTrue(rig.linkNotes[0].startsWith("fresh: ") && "warmup frame" in rig.linkNotes[0], "the first start's note: ${rig.linkNotes}")
        rig.st.notifyFilter = { _, p -> !safely { drop(p) } }
        assertTrue(rig.transport.restartSession("test: a deliberate restart"))
        rig.until("the fallback fails the kept start", maxMs = 25_000) { rig.statuses.any { it.startsWith("start failed: $failed") } }
        if (lift) rig.st.notifyFilter = null
        rig.until("the rollback ended the link and the next start connected afresh") {
            rig.st.disconnects == 1 && rig.st.connects == 2 && rig.linkNotes.size >= 2 }
        val note = rig.linkNotes[1]
        assertTrue(note.startsWith("kept, fell back at the $gate: ") && unanswered in note, "the kept start's note: ${rig.linkNotes}")
        if (lift) {
            rig.until("the fresh session drives") { rig.driving() }
            assertEquals(3, rig.keeper.attempts, "the first, the kept one that fell back, the fresh one")
            assertEquals(1, rig.statuses.count { it.startsWith("start failed") }, "${rig.statuses}")
            rig.until("the fresh start's note") { rig.linkNotes.size == 3 }
            assertTrue(rig.linkNotes[2].startsWith("fresh: "), "${rig.linkNotes}")
        }
        return rig
    }

    private fun safely(f: () -> Boolean): Boolean = try { f() } catch (e: IllegalArgumentException) { false } catch (e: IndexOutOfBoundsException) { false }

    private fun isEvenHubAck(p: ByteArray, cmd: Int): Boolean =
        StartRig.sid(p) == EvenHubMsg.SID && Pb.varintField(StartRig.body(p), 1) == (cmd + 1).toLong()
}
