package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.Timeout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.SettingsMsg

/**
 * Build W, package D (the spec's T27, §2(e); the lead's decisions B.5 and E.1): at every session start the transport
 * journals the glasses' free memory as `heap3` notes at four points — `pre-create` (the reset check's own record),
 * `lease` (a launched TELEMETRY read), `atlas` (the read-back's own record) and `first-frame` (a launched read) —
 * once each per session; nothing in the start or the image lane waits on a point; none is read in the firmware's
 * Silent Mode; and the knob's default is off on an instant test transport and on at the real pace.
 *
 * Nothing here reaches a radio: every frame is the firmware model's. Waits decide on a LATCH ([until] over a
 * [Signal] bumped by every event they read: one evaluation per event, no sleep and no clock; fix round 1, review 1
 * note 9); an absence is asserted behind [Seen.sync] (the probe's mark goes through the same event flow). A run whose
 * wait is never satisfied is ended by the runner's supervision ([timeout]), never decided by a wall-clock look.
 */
class Heap3Test {

    /** The runner's supervision only: a wait that is never satisfied ends here, named by the test. Generous on
     *  purpose — no wait in this class decides on it, so a slow machine is slower, never failed. */
    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    private class Seen(scope: CoroutineScope, private val t: Transport, val signal: Signal = Signal()) {
        private val all = ArrayList<TransportEvent>()
        private var marks = 0
        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                t.events.collect { synchronized(all) { all.add(it) }; signal.bump() }
            }
        }
        fun notes(kind: String): List<String> = synchronized(all) { all.filterIsInstance<TransportEvent.Note>().filter { it.kind == kind }.map { it.detail } }
        fun heap(): List<String> = notes("heap3")
        fun done(): List<TransportEvent.FlushDone> = synchronized(all) { all.filterIsInstance<TransportEvent.FlushDone>() }
        suspend fun sync() {
            val tag = "mark${++marks}"
            t.devProbe("mark", tag)
            until("the mark $tag came through", signal) { notes("probe").contains("mark=$tag") }
        }
    }

    private fun point(note: String) = note.removePrefix("point=").substringBefore(' ')

    /** One full start's points: the start, the read-back asked twice, two flushes acknowledged. */
    private suspend fun drive(t: Transport, seen: Seen, flushEpoch: Long) {
        t.cacheCheck()
        t.cacheCheck()
        val before = seen.done().size
        val a = t.submit(FlushRequest(listOf(DisplayOp.Keyframe(warmup())), flushEpoch))
        val b = t.submit(FlushRequest(listOf(DisplayOp.Keyframe(warmup())), flushEpoch))
        until("both flushes acknowledged", seen.signal) { seen.done().count { it.id == a || it.id == b } == 2 }
        assertTrue(seen.done().drop(before).all { it.ok }, "${seen.done()}")
    }

    private val record = Regex("""point=(pre-create|lease|atlas|first-frame) id=\d+ a13=(\d+|\?) a20=(\d+|\?) a27=(\d+|\?) cache=(\d+|none) uptime=\d+ lease=(\d+|\?)""")

    /** [heap] on or off over the same drive: the reads the four points add and the notes they leave. */
    private fun session(heap: Boolean): Pair<Int, List<String>> = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 2 }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            t.heapReads = heap
            val seen = Seen(scope, t)
            t.start(warmup())
            if (heap) until("the lease point is answered", seen.signal) { seen.heap().any { it.startsWith("point=lease ") } }
            drive(t, seen, 1L)
            if (heap) until("the first-frame point is answered", seen.signal) { seen.heap().any { it.startsWith("point=first-frame ") } }
            seen.sync()
            val n = sim.telemetryReadsSeen
            t.stop()
            n to seen.heap()
        } finally { scope.cancel() }
    }

    @Test
    fun theFourPointsAreJournaledOnceEachAndTwoOfThemRead() {
        val (off, none) = session(heap = false)
        assertEquals(emptyList(), none, "the knob off: no heap3 note")
        val (on, notes) = session(heap = true)
        assertEquals(listOf("pre-create", "lease", "atlas", "first-frame"), notes.map(::point), "one per point, once: $notes")
        for (n in notes) assertTrue(record.matches(n), "the note's shape: $n")
        assertEquals(off + 2, on, "lease and first-frame read once each; pre-create and atlas reuse a record: $notes")
    }

    /** The points are the SESSION's: a second start says all four again (the said marks reset at the start). */
    @Test
    fun aSecondSessionSaysItsOwnPoints(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 2 }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            t.heapReads = true
            val seen = Seen(scope, t)
            t.start(warmup())
            until("the lease point is answered", seen.signal) { seen.heap().any { it.startsWith("point=lease ") } }
            drive(t, seen, 1L)
            until("the first-frame point is answered", seen.signal) { seen.heap().any { it.startsWith("point=first-frame ") } }
            assertTrue(t.restartSession("test: a second session"))
            t.start(warmup())
            until("the second lease point is answered", seen.signal) { seen.heap().count { it.startsWith("point=lease ") } == 2 }
            drive(t, seen, 2L)
            until("the second first-frame point", seen.signal) { seen.heap().count { it.startsWith("point=first-frame ") } == 2 }
            seen.sync()
            assertEquals(List(2) { listOf("pre-create", "lease", "atlas", "first-frame") }.flatten(), seen.heap().map(::point),
                "each session says its four points once: ${seen.heap()}")
            t.stop()
        } finally { scope.cancel() }
    }

    /** §36/§42: nothing of ours goes to glasses in Silent Mode beyond the wake path — a start inside it reads no
     *  heap point and says so; the reset check's record (read by the start anyway) is still journaled. */
    @Test
    fun aStartInSilentModeReadsNoPoint() {
        fun run(heap: Boolean): Pair<Int, List<String>> = runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val sim = GlassFirmwareSim().also { it.damageContract = 2 }
                sim.setSilent(true)                                   // the both-temple press came before the start
                val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
                t.heapReads = heap
                val seen = Seen(scope, t)
                t.start(warmup())
                assertTrue(t.state.value.glassesSilent, "the start's READ said Silent Mode")
                // a read that went out is answered by the model inside its write, and only then is its note emitted:
                // once the lease point's note is here, a read it made has been counted
                if (heap) until("the lease point's note", seen.signal) { seen.heap().any { it.startsWith("point=lease") } }
                seen.sync()
                val n = sim.telemetryReadsSeen
                t.stop()
                n to seen.heap()
            } finally { scope.cancel() }
        }
        val (off, _) = run(heap = false)
        val (on, notes) = run(heap = true)
        assertEquals(off, on, "no heap3 read goes out in Silent Mode: $notes")
        assertEquals(listOf("pre-create", "lease"), notes.map(::point), "$notes")
        assertTrue(record.matches(notes[0]), "the reset check's record is journaled: ${notes[0]}")
        assertEquals("point=lease not read: Silent Mode", notes[1])
    }

    /** A lab over the model that never lets a TELEMETRY read through after the first request id it sees (the
     *  start's reset check): every later op-1 read goes unanswered. Contract 1, no flags wanted: the start reads
     *  nothing else of the kind. */
    private class DropLab(val glass: GlassFirmwareSim, scope: CoroutineScope) : CfwTransportBase(scope, "droplab", mirrorSim = glass) {
        override val instant: Boolean get() = true
        @Volatile var firstId = -1
        @Volatile var dropped = 0
        /** Bumped on every drop: the latch a wait on [dropped] decides on. */
        val signal = Signal()
        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { onNotifyPacket(arm, packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }
        override suspend fun connectLink() {}
        override suspend fun disconnectLink() { glass.linkReset() }
        override suspend fun writeArm(arm: Arm, packet: ByteArray) {
            val id = telemetryReadId(packet)
            if (id != null) {
                if (firstId < 0) firstId = id
                if (id != firstId) { dropped++; signal.bump(); return }
            }
            glass.write(arm, packet, nowMs())
        }
        private fun telemetryReadId(packet: ByteArray): Int? {
            if (packet.size < AaFrame.HEADER + AaFrame.CRC || (packet[6].toInt() and 0xFF) != SettingsMsg.SID) return null
            val len = packet[3].toInt() and 0xFF
            return try {
                val body = Pb.bytesField(packet.copyOfRange(AaFrame.HEADER, AaFrame.HEADER + len - AaFrame.CRC), DamageMsg.CONTROL_FIELD)
                if (body == null || body.size != 6 || (body[3].toInt() and 0xFF) != DamageMsg.OP_TELEMETRY) null
                else (body[4].toInt() and 0xFF) or ((body[5].toInt() and 0xFF) shl 8)
            } catch (e: IllegalArgumentException) { null }
        }
        override fun onMaintenanceTick() {
            glass.tick(nowMs())
            val held = glass.leaseHeld(Arm.LEFT, nowMs()) && glass.leaseHeld(Arm.RIGHT, nowMs())
            if (!held && state.value.started) setLease(false, "lease lost (model)") else if (held) setLease(true, "lease held")
        }
    }

    /** The start returns while its `lease` read is still unanswered, a flush goes through meanwhile, and the read
     *  ends at three asks with a note that says so. Awaited inside the start, the note would be there when it returns. */
    @Test
    fun theStartAndTheLaneNeverWaitForAPoint(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = DropLab(GlassFirmwareSim().also { it.damageContract = 1 }, scope)
            lab.heapReads = true
            val seen = Seen(scope, lab)
            lab.start(warmup())
            assertTrue(seen.heap().none { it.startsWith("point=lease") }, "the start returned before its lease point ended: ${seen.heap()}")
            until("the lease point's read was written (and held back by the lab)", lab.signal) { lab.dropped >= 1 }
            val id = lab.submit(FlushRequest(listOf(DisplayOp.Keyframe(warmup())), 1L))
            until("a flush is acknowledged while the read is unanswered", seen.signal) { seen.done().any { it.id == id && it.ok } }
            assertTrue(seen.heap().none { it.startsWith("point=lease") }, "${seen.heap()}")
            until("the lease point ends at three asks", seen.signal) { seen.heap().any { it.startsWith("point=lease ") } }
            assertTrue(seen.heap().contains("point=lease not answered after 3 asks"), "${seen.heap()}")
            lab.stop()
        } finally { scope.cancel() }
    }

    /** The knob: off on an instant transport with it left alone (the instant pins count telemetry reads), on at the
     *  real pace (the modeled-timing simulator, as both radios run). */
    @Test
    fun theDefaultIsOffOnAnInstantTransportAndOnAtTheRealPace(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val quiet = SimTransport(GlassFirmwareSim().also { it.damageContract = 2 }, scope, SimTransport.Timing(instant = true))
            assertNull(quiet.heapReads, "the knob is left alone")
            val qs = Seen(scope, quiet)
            quiet.start(warmup())
            drive(quiet, qs, 1L)
            qs.sync()
            assertEquals(emptyList(), qs.heap(), "an instant transport says no heap3 point with the knob left alone")
            quiet.stop()

            val paced = SimTransport(GlassFirmwareSim().also { it.damageContract = 2 }, scope)   // modeled timing
            assertNull(paced.heapReads, "the knob is left alone")
            val ps = Seen(scope, paced)
            paced.start(warmup())
            until("the lease point at the real pace", ps.signal) { ps.heap().any { it.startsWith("point=lease id=") } }
            assertTrue(ps.heap().first().startsWith("point=pre-create id="), "${ps.heap()}")
            paced.stop()
        } finally { scope.cancel() }
    }
}

/** A latch: bumped by every event a wait reads. Each bump changes the value, so a waiter always sees the last one. */
private class Signal {
    val v = MutableStateFlow(0L)
    fun bump() = v.update { it + 1 }
}

/** Waits until [cond] holds, evaluating it ONCE per [signal] bump (and once at the start): no sleep, no clock. [what]
 *  names the wait for a reader; a wait never satisfied is ended by the class's Timeout rule. */
@Suppress("UNUSED_PARAMETER")
private suspend fun until(what: String, signal: Signal, cond: () -> Boolean) {
    signal.v.first { cond() }
}
