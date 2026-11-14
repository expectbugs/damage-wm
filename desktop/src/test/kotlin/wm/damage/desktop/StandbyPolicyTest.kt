package wm.damage.desktop

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import wm.damage.core.transport.SeamProbe
import wm.damage.desktop.StandbyPolicy.Action
import wm.damage.desktop.StandbyPolicy.Phone

/**
 * Build W's rider (the spec's T15; R6 §5; the lead's decision A.9): PC-direct Bluetooth is a manual choice. In `auto`
 * the PC is the data host — no probe answer, however long the phone stays away, starts a stack; a manual stack
 * (`drive=ble`, `--transport ble`, the Target row) is never stopped by a probe; `drive=auto` puts the data host back,
 * and a stack found up in `auto` is stopped.
 *
 * Order of work (protocol rule 4): the loop was first extracted into [StandbyPolicy.step] WITH the old `Claim` (six
 * unreachable probes and both arms seen); [aPhoneAwayForeverNeverStartsAStack] was watched failing on it, then
 * `Claim` was removed. The record's pins table lists the run.
 *
 * Earlier pins kept: the probe's answer mapped to the phone's intent, and a busy port bound once it frees (§72).
 */
class StandbyPolicyTest {

    private val probe = 5_000L

    /** [n] probes of [phone] from [s] in `auto`, every action collected. */
    private fun probes(phone: Phone, n: Int, s: StandbyPolicy.State = StandbyPolicy.State(), stackHeld: Boolean = false):
        Pair<StandbyPolicy.State, List<Action>> {
        var st = s
        val out = ArrayList<Action>()
        repeat(n) {
            val step = StandbyPolicy.step(phone, st, standby = true, stackHeld = stackHeld, probeMs = probe)
            st = step.state
            out += step.action
        }
        return st to out
    }

    @Test
    fun aPhoneAwayForeverNeverStartsAStack() {
        // a day of probes with the phone unreachable: 2026-11-06's claim came after six, with both arms seen
        val (st, actions) = probes(Phone.UNREACHABLE, 17_280)
        assertTrue(actions.all { it is Action.Narrate }, "every answer only narrates: ${actions.filter { it !is Action.Narrate }.take(3)}")
        assertEquals(17_280, st.absentStreak)
        assertEquals("data host · phone unreachable 24h0m · PC-direct BLE is manual (drive=ble)", actions.last().status)
        assertEquals("data host · phone unreachable 5s · PC-direct BLE is manual (drive=ble)", actions.first().status)
        assertTrue(actions.all { it.said.isEmpty() }, "an absence says nothing until it ends")
        // and a phone that chose Target≠glasses, or drives, never starts one either
        for (p in listOf(Phone.CHOSE_NOT_TO, Phone.DRIVES))
            assertTrue(probes(p, 100).second.all { it is Action.Narrate }, "$p")
    }

    @Test
    fun thePhonesReturnIsSaidOnceWithItsLength() {
        val (away, _) = probes(Phone.UNREACHABLE, 12)
        val back = StandbyPolicy.step(Phone.DRIVES, away, standby = true, stackHeld = false, detail = "driving", probeMs = probe)
        assertEquals(listOf("standby: the phone is back after 12 missed probe(s) (~60 s)"), back.action.said)
        assertEquals("data host · phone drives · driving", back.action.status)
        assertEquals(0, back.state.absentStreak)
        val again = StandbyPolicy.step(Phone.DRIVES, back.state, standby = true, stackHeld = false, probeMs = probe)
        assertEquals(emptyList(), again.action.said, "said once")
        // a choice is said once per run of such answers, never as an absence
        val (_, chose) = probes(Phone.CHOSE_NOT_TO, 3)
        assertEquals(listOf(1, 0, 0), chose.map { it.said.size })
        assertEquals("data host · the APK chose Target≠glasses", chose.last().status)
    }

    /** A manual stack (`drive=ble`): the loop passes no answer (none is asked) and no step ever stops it, whatever
     *  the phone would say. */
    @Test
    fun aManualStackSurvivesProbesAndIsNeverHandedBack() {
        for (p in listOf(Phone.DRIVES, Phone.CHOSE_NOT_TO, Phone.UNREACHABLE, null)) {
            var st = StandbyPolicy.State(absentStreak = 40)
            repeat(50) {
                val step = StandbyPolicy.step(p, st, standby = false, stackHeld = true, probeMs = probe)
                assertTrue(step.action is Action.Narrate, "a manual stack is never handed back ($p)")
                st = step.state
            }
            assertEquals(StandbyPolicy.State(), st, "the next `auto` starts its count afresh")
        }
    }

    /** `drive=auto` puts the data host back: a stack still up in `auto` is stopped at the next probe, whatever the
     *  phone says; `drive=` takes only `ble` and `auto`. */
    @Test
    fun driveAutoEndsAManualStack() {
        assertEquals("ble", StandbyPolicy.driveOf("ble"))
        assertEquals("auto", StandbyPolicy.driveOf("auto"))
        for (v in listOf("sim", "remote", "", "BLE", "on")) assertNull(StandbyPolicy.driveOf(v), v)
        for (p in Phone.entries) {
            val step = StandbyPolicy.step(p, StandbyPolicy.State(), standby = true, stackHeld = true, probeMs = probe)
            assertTrue(step.action is Action.Handback, "a stack up in auto is stopped ($p)")
            assertTrue(step.action.status.endsWith("stopping the PC stack (auto never drives)"), step.action.status)
        }
        assertTrue(StandbyPolicy.step(Phone.DRIVES, StandbyPolicy.State(), standby = true, stackHeld = false, probeMs = probe)
            .action is Action.Narrate, "nothing up, nothing to stop")
    }

    @Test
    fun theProbeAnswerMapsToThePhonesIntent() {
        assertEquals(Phone.DRIVES, StandbyPolicy.phoneOf(SeamProbe.Result.Reachable(true, true, "")))
        assertEquals(Phone.DRIVES, StandbyPolicy.phoneOf(SeamProbe.Result.Reachable(null, false, "")),
            "an APK too old to be asked is read conservatively, as before")
        assertEquals(Phone.CHOSE_NOT_TO, StandbyPolicy.phoneOf(SeamProbe.Result.Reachable(false, false, "")))
        assertEquals(Phone.UNREACHABLE, StandbyPolicy.phoneOf(SeamProbe.Result.Unreachable))
    }

    @Test
    fun aBusyPortIsBoundOnceItIsFree() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val holder = ServerSocket(0)
            val port = holder.localPort
            var server: ServerSocket? = null
            val bound = java.util.concurrent.CountDownLatch(1)
            val now = bindOrRetry(scope, "test server", port, onBound = { bound.countDown() }, retryMs = 100) {
                server = ServerSocket(port)
            }
            assertFalse(now, "the port is held: not bound yet")
            holder.close()                       // the stray instance lets go
            assertTrue(bound.await(10, java.util.concurrent.TimeUnit.SECONDS), "the retry bound the freed port")
            assertTrue(server?.isBound == true)
            server?.close()
        } finally { scope.cancel() }
    }
}
