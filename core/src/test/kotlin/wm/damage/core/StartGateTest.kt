package wm.damage.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.TransportEvent
import wm.damage.core.transport.draw2
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.SettingsMsg

/**
 * APK 0.65, P5 item 9 (the wait-no-escape sweep's finding 3, with its three corrections): three start gates on a
 * Damage build — the reset check's read, the cache size (op 5) and a FLAGS_SET — re-ask on the pacing tick, and only
 * their FIRST attempt's write failure could end the wait. A RIGHT arm whose writes throw from the second attempt on
 * (an object gone from the bus with no `Connected=false`: the PC's standby path) left the start re-asking every 2 s
 * for good, inside `start()`, where the watchdog cannot see it and a failed start is never counted. Fixed: RIGHT's
 * write failing on ANY attempt ends the wait (RIGHT is the one arm whose answer could); a LEFT that throws does not
 * (RIGHT still answers — the fourth pin, `HANDOFF.md` §63.3 item 4's regression). No clock: the write's own failure is
 * the event. Each pin is watched to fail on the tree before the fix (the first three do not return; the harness bound
 * fails them); the build report's pins table has each one.
 */
class StartGateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun clean() { scope.cancel() }

    private val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
    private val notes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    private val faults = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun rig(): StartRig {
        val t = StartRig(GlassFirmwareSim().also { it.damageContract = 2 }, scope)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            t.events.collect {
                if (it is TransportEvent.Note) notes.add(it.kind to it.detail)
                if (it is TransportEvent.Fault) faults.add("${it.what}: ${it.detail}")
            }
        }
        return t
    }

    /** RIGHT's settings-channel writes throw once [broken] is set — the second attempt's, not the first's. */
    private fun breakRight(t: StartRig, broken: AtomicBoolean) {
        t.onWrite = { arm, p ->
            if (broken.get() && arm == Arm.RIGHT && StartRig.sid(p) == SettingsMsg.SID) throw java.io.IOException("RIGHT: write characteristic gone")
            p
        }
    }

    /** Runs a start under a harness bound that decides once; returns its error (null when it completed). */
    private suspend fun startEnds(t: StartRig, what: String): Throwable? {
        val starting = scope.async { runCatching { t.start(full) } }
        waitUntil(15_000, message = { "the start did not end ($what): notes ${notes.takeLast(6)}" }) { starting.isCompleted }
        return starting.await().exceptionOrNull()
    }

    @Test
    fun aResetCheckWhoseReAskCannotBeWrittenToRightEndsTheStart(): Unit = runBlocking {
        val t = rig()
        val broken = AtomicBoolean(false)
        breakRight(t, broken)
        // the reset check's first answer is lost (its id is not 0); from then on RIGHT's writes throw
        t.onNotify = { _, p ->
            val r = StartRig.telemetry(p)
            if (r != null && r.requestId != 0L && broken.compareAndSet(false, true)) null else p
        }
        val e = startEnds(t, "the reset check") ?: fail("the start completed with RIGHT's writes failing")
        assertTrue("reset check ended early" in (e.message ?: ""), "got: $e")
        t.stop()
    }

    @Test
    fun aCacheSizeWhoseReAskCannotBeWrittenToRightEndsTheStart(): Unit = runBlocking {
        val t = rig()
        val broken = AtomicBoolean(false)
        breakRight(t, broken)
        // op 5's first answer (the first record on id 0 of the start) is lost; from then on RIGHT's writes throw
        t.onNotify = { _, p ->
            val r = StartRig.telemetry(p)
            if (r != null && r.requestId == 0L && broken.compareAndSet(false, true)) null else p
        }
        val e = startEnds(t, "the cache size") ?: fail("the start completed with RIGHT's writes failing")
        assertTrue("the cache size was not answered" in (e.message ?: ""), "got: $e")
        t.stop()
    }

    @Test
    fun aFlagsSetWhoseReAskCannotBeWrittenToRightIsNotTakenAsArmed(): Unit = runBlocking {
        val t = rig()
        val broken = AtomicBoolean(false)
        val flagsOut = AtomicBoolean(false)
        t.onWrite = { arm, p ->
            if (StartRig.damageOp(p) == DamageMsg.OP_FLAGS_SET) flagsOut.set(true)
            if (broken.get() && arm == Arm.RIGHT && StartRig.sid(p) == SettingsMsg.SID) throw java.io.IOException("RIGHT: write characteristic gone")
            p
        }
        // the FLAGS_SET's first answer is lost (placed on both arms, not answered); from then on RIGHT's writes throw
        t.onNotify = { _, p ->
            val r = StartRig.telemetry(p)
            if (r != null && r.requestId == 0L && flagsOut.get() && broken.compareAndSet(false, true)) null else p
        }
        val e = startEnds(t, "the FLAGS_SET")
        assertTrue(e == null, "the start goes on without DRAW2: $e")
        assertTrue(!t.state.value.draw2, "the set was taken as armed with RIGHT's re-ask unwritten")
        val f = faults.filter { it.startsWith("flags:") }
        assertTrue(f.size == 1 && "placed on both arms and not answered" in f[0] && "did not reach an arm" !in f[0],
            "the fault says what happened: $f")
        t.stop()
    }

    /** `HANDOFF.md` §63.3 item 4's second half, kept: attempt 1 reaches both arms and arms the set, its answer is lost,
     *  attempt 2's LEFT write throws once — RIGHT's copy is written and answered, so the set IS armed. A fix that ends the
     *  wait on any arm's failure says "did not reach an arm" and draws v1 shapes with the glasses armed. */
    @Test
    fun aFlagsSetWhoseReAskFailsOnLeftOnlyIsStillArmed(): Unit = runBlocking {
        val t = rig()
        val flagsOut = AtomicBoolean(false)
        val dropped = AtomicBoolean(false)
        val leftThrown = AtomicBoolean(false)
        t.onWrite = { arm, p ->
            if (StartRig.damageOp(p) == DamageMsg.OP_FLAGS_SET) {
                if (dropped.get() && arm == Arm.LEFT && leftThrown.compareAndSet(false, true)) throw java.io.IOException("LEFT: one write failed")
                flagsOut.set(true)
            }
            p
        }
        t.onNotify = { _, p ->
            val r = StartRig.telemetry(p)
            if (r != null && r.requestId == 0L && flagsOut.get() && dropped.compareAndSet(false, true)) null else p
        }
        val e = startEnds(t, "the FLAGS_SET re-ask")
        assertTrue(e == null, "the start completes: $e")
        assertTrue(leftThrown.get(), "the re-ask's LEFT write threw")
        assertTrue(t.state.value.draw2, "attempt 1 armed both lenses and RIGHT answered attempt 2: DRAW2 is in force")
        assertTrue(faults.none { it.startsWith("flags:") }, "no flags fault: $faults")
        t.stop()
    }

    /** Correction 3: the arming's own read (`armFeatures`) ending early dropped the arming with a log line only. Its
     *  first answer is lost and RIGHT's re-ask cannot be written: a `keeper` note says the set was not armed. */
    @Test
    fun anArmingWhoseReadEndsEarlySaysSo(): Unit = runBlocking {
        val t = rig()
        val broken = AtomicBoolean(false)
        breakRight(t, broken)
        val idZeroSeen = AtomicBoolean(false)
        // the arming's read is the first id-other-than-0 record after op 5's id-0 answer (the reset check's came before)
        t.onNotify = { _, p ->
            val r = StartRig.telemetry(p)
            if (r != null && r.requestId == 0L) idZeroSeen.set(true)
            if (r != null && r.requestId != 0L && idZeroSeen.get() && broken.compareAndSet(false, true)) null else p
        }
        val e = startEnds(t, "the arming's read")
        assertTrue(e == null, "the start goes on without DRAW2: $e")
        assertTrue(!t.state.value.draw2, "nothing armed")
        assertTrue(notes.any { it.first == "keeper" && "not armed: the read before the arming ended early" in it.second },
            "the keeper note: ${notes.filter { it.first == "keeper" }}")
        t.stop()
    }
}
