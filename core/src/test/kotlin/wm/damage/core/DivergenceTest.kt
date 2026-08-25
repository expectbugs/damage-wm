package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/** HANDOFF.md §8.2 "Divergence check": at rest the compositor's belief equals
 *  the mirror; a forced disagreement is reported once and repaired by a
 *  keyframe, after which belief and mirror agree again. */
class DivergenceTest {

    @Test
    fun aDisagreeingMirrorIsReportedThenRepairedByAKeyframe(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-diverge")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val clock = Shell.LocalClock(12, 0, "12:00", "PM")
            val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("s.json")), null, scope) { clock }
            shell.start()
            settle(shell)
            assertEquals(null, shell.lastDivergence, "belief and mirror agree at rest after boot")
            assertEquals(0, shell.divergencesReported)

            // Alter the firmware model's LEFT shadow inside the battery cell —
            // a chrome cell no later flush repaints — so the next present shows
            // pixels the compositor never sent.
            val stride = sim.left.stride
            for (y in 6 until 26) {
                java.util.Arrays.fill(sim.left.shadow, y * stride + 200, y * stride + 260, 0x77.toByte())
            }
            shell.postGesture(EvenHubMsg.EV_CLICK)   // enter Settings: a content flush presents the altered shadow
            settle(shell)
            assertEquals("settings", shell.currentWindowId(), "the tap produced a real flush")

            assertTrue(shell.divergencesReported >= 1, "the disagreement was reported")
            assertEquals(null, shell.lastDivergence, "the keyframe restored agreement")
            // and the model now equals the belief, pixel for pixel
            val belief = shell.comp.expectedLens(true)
            for (y in 0 until 480) for (x in 0 until 640) {
                val b = sim.left.panel[y * stride + (x shr 1)].toInt() and 0xFF
                val n = if (x and 1 == 0) b shr 4 else b and 0x0F
                assertEquals(wm.damage.core.gfx.Pack.level(belief[x, y]), n, "belief != mirror at ($x,$y) after the repair")
            }
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    private suspend fun settle(shell: Shell) {
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 10_000) delay(10)
        assertTrue(shell.isQuiescent(), "shell did not settle")
    }
}
