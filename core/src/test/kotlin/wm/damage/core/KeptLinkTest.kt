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
import wm.damage.core.transport.PathTransport
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.Pb

/**
 * Stage BW (Adam's word, 2026-11-25; `private/build085/BL-decisions.md` Parts B and D): a deliberate session restart
 * keeps the radio link and the next start adopts it — the rules around that decision, against the simulator (they
 * prove the phone's sequence, never the firmware's answer). The wake's own pin is `SilentGlassesTest`'s push-OFF rig;
 * the start's bytes are `BindTransportTest`'s files; the watchdog's link end is `WatchdogTest`'s.
 */
class KeptLinkTest {

    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    private class Rig(scope: CoroutineScope, retryPauseMs: Long, wrap: (SimTransport) -> Transport = { it }) {
        val tmp = Files.createTempDirectory("damage-keptlink")
        val sim = GlassFirmwareSim()
        val st = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val transport = wrap(st)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope, HELD_CLOCK)
        val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
        val keeper = ShellKeeper(shell, transport, scope, onStatus = { statuses.add(it) }, retryPauseMs = retryPauseMs)
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

    /** The keeper stopped in the gap (the service stopping, a Target switch): the shell is already stopped with the
     *  yield form, so the keeper stops the transport itself and the kept link ends — as a stop always ended it — and
     *  the mark goes with it. Mutations watched: the keeper's stop of the transport removed; `stop()` keeping the mark. */
    @Test
    fun aKeeperStopInTheGapEndsTheKeptLink(): Unit = runBlocking {
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
            assertEquals(1, rig.st.disconnects, "the keeper's stop in the gap ended the kept link")
            assertFalse(rig.transport.state.value.linkKept, "and the mark")
        } finally { scope.cancel() }
    }

    /** The fallback (his word, Part D): on an ADOPTED link a CREATE re-asked twice with no answer fails the start with
     *  words that say the kept link did not answer; the rollback ends the link and the next attempt connects afresh —
     *  and there, on a link connected afresh, the re-asks go on as before: the CREATE is sent at least twice more than
     *  the kept link's give-up allows, on that one start (no second failure, no third connect), then the answer comes
     *  and the session drives. (The count is taken from the moment the fresh connect is seen, which on the instant sim
     *  may already include that start's first CREATE: four more is past the give-up either way.)
     *  Mutations watched: the give-up never firing (the kept start re-asks with no end); firing on a fresh link too. */
    @Test
    fun aKeptLinkThatDoesNotAnswerTheCreateFallsBackToAFreshConnect(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope, retryPauseMs = 100)
            rig.keeper.start()
            rig.until("the first session drives") { rig.driving() }
            rig.st.notifyFilter = { _, p -> !isCreateAck(p) }       // the glasses take the CREATE and never answer it
            assertTrue(rig.transport.restartSession("test: a deliberate restart"))
            val failed = "start failed: carrier CREATE ended early — the kept link did not answer the start: the carrier " +
                "CREATE went unanswered after ${wm.damage.core.transport.CfwTransportBase.KEPT_LINK_REASKS} re-asks (2 s apart)"
            rig.until("the fallback fails the kept start", maxMs = 20_000) { rig.statuses.any { it.startsWith(failed) } }
            rig.until("the rollback ended the link and the next start connected afresh") { rig.st.disconnects == 1 && rig.st.connects == 2 }
            val createsAtFresh = rig.sim.createsSeen
            val pastGiveUp = wm.damage.core.transport.CfwTransportBase.KEPT_LINK_REASKS + 2
            rig.until("the fresh link's CREATE is re-asked past the kept link's give-up", maxMs = 15_000) {
                rig.sim.createsSeen - createsAtFresh >= pastGiveUp
            }
            assertEquals(1, rig.statuses.count { it.startsWith("start failed") }, "no fallback on a fresh link: ${rig.statuses}")
            assertEquals(2, rig.st.connects, "the re-asks were on the one fresh start")
            assertFalse(rig.driving())
            rig.st.notifyFilter = null                             // the glasses answer again
            rig.until("the fresh session drives") { rig.driving() }
            assertEquals(3, rig.keeper.attempts, "the first, the kept one that fell back, the fresh one")
            assertEquals(1, rig.st.disconnects)
            rig.keeper.stop()
        } finally { scope.cancel() }
    }

    /** `PathTransport` relays a restart to its path, which may keep its link — but its own `start()` refuses while a
     *  path is active, so it never forwards the mark: the keeper stops it (the path's link ends, as before) and the
     *  rebuild picks a path afresh and drives. Mutation watched: the mark forwarded (the keeper's start then fails on
     *  "path transport already started" at every attempt). */
    @Test
    fun aRestartThroughThePathLayerRebuildsTheSession(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope, retryPauseMs = 100) { PathTransport(listOf(PathTransport.Candidate("sim", it)), scope, headStartMs = 0) }
            rig.keeper.start()
            rig.until("the first session drives") { rig.driving() }
            assertTrue(rig.transport.restartSession("test: a deliberate restart"))
            rig.until("the rebuild drives") { rig.keeper.attempts == 2 && rig.driving() }
            assertTrue(rig.st.disconnects >= 1, "the path layer's stop ended its path's link")
            assertTrue(rig.statuses.none { it.startsWith("start failed") }, "${rig.statuses}")
            rig.keeper.stop()
        } finally { scope.cancel() }
    }

    private fun isCreateAck(p: ByteArray): Boolean = try {
        StartRig.sid(p) == EvenHubMsg.SID && Pb.varintField(StartRig.body(p), 1) == (EvenHubMsg.CMD_CREATE + 1).toLong()
    } catch (e: IllegalArgumentException) { false } catch (e: IndexOutOfBoundsException) { false }
}
